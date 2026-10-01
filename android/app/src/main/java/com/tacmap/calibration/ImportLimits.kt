package com.tacmap.calibration

import com.tacmap.calibration.fiducial.StatusMessage

/**
 * Contract s3 constants. testdata/import_limits.json is the source of truth and
 * ImportLimitsContractTest pins every value here against it (iOS does the same).
 */
internal object ImportLimits {
    const val PDF_MAX_BYTES = 512L * 1024 * 1024
    const val MBTILES_MAX_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_PAGES = 500
    const val GEOREF_SCAN_PAGES = 50
    const val PAGE_SIDE_MIN_PT = 36.0
    const val PAGE_SIDE_MAX_PT = 14_400.0
    const val PARSE_TIMEOUT_MS = 30_000L
    const val FREE_SPACE_MARGIN_BYTES = 64L * 1024 * 1024
    const val MAX_LIBRARY_ENTRIES = 100
    const val PROGRESS_HUD_DELAY_MS = 300L
    const val THUMBNAIL_WIDTH_DP = 120.0
    const val THUMBNAIL_CACHE_ENTRIES = 24
    const val CANCEL_COPY_GRANULARITY_BYTES = 1L * 1024 * 1024

    // calibration side of s3
    const val MAX_CALIBRATION_POINTS = 50
    const val UNDO_DEPTH = 50
    const val MAX_DRAFTS = 16
    const val GPS_MAX_ACCURACY_M = 20.0
    const val RESIDUAL_LINE_MIN_SCREEN_DP = 6.0
    const val MOVE_TO_CROSSHAIR_MIN_SCREEN_DP = 4.0
    const val MARKER_HIT_RADIUS_DP = 24.0
    const val TRANSITION_PREFETCH_DEADLINE_MS = 400L
    const val PROVISIONAL_SCALE_DENOMINATOR = 50_000.0
    const val MAX_INPUT_UTF16_UNITS = 64
}

/** import error code -> message key, the arg names are the catalogue's */
internal enum class ImportError(val code: String, val messageKey: String, val argNames: List<String>) {
    TOO_LARGE("tooLarge", "map_import_too_large", listOf("limit")),
    NO_SPACE("noSpace", "map_import_no_space", listOf("size")),
    PASSWORD("password", "map_import_password", emptyList()),
    TOO_MANY_PAGES("tooManyPages", "map_import_too_many_pages", listOf("limit")),
    PAGE_SIZE("pageSize", "map_import_page_size", emptyList()),
    TOO_COMPLEX("tooComplex", "map_import_too_complex", emptyList()),
    INVALID_PDF("invalidPdf", "map_import_invalid_pdf", emptyList()),
    INVALID_MBTILES("invalidMbtiles", "map_import_invalid_mbtiles", emptyList()),
    LIBRARY_FULL("libraryFull", "map_import_library_full", listOf("limit")),
    LOCKED("locked", "map_import_unlock_first", emptyList()),
    INTERRUPTED("interrupted", "map_import_interrupted", emptyList()),
    CANCELLED("cancelled", "map_import_cancelled", emptyList()),
    FAILED("failed", "map_import_read_failed", listOf("detail")),
}

/** an import that stopped, with its typed args (byte counts stay numbers until display) */
internal data class ImportFailure(val error: ImportError, val args: Map<String, Any> = emptyMap())

internal class ImportFailedException(val failure: ImportFailure) : Exception(failure.error.code)

internal enum class ImportedMapKind(val code: String) { PDF("pdf"), MBTILES("mbtiles") }

/** s9.2, in this order: library not Loaded, full, too big, no room */
internal object ImportPrecheck {
    fun check(
        libraryLoaded: Boolean,
        entryCount: Int,
        kind: ImportedMapKind,
        sizeBytes: Long,
        freeBytes: Long,
    ): ImportFailure? {
        if (!libraryLoaded) return ImportFailure(ImportError.LOCKED)
        if (entryCount >= ImportLimits.MAX_LIBRARY_ENTRIES) {
            return ImportFailure(ImportError.LIBRARY_FULL, mapOf("limit" to ImportLimits.MAX_LIBRARY_ENTRIES))
        }
        val limit = if (kind == ImportedMapKind.PDF) ImportLimits.PDF_MAX_BYTES else ImportLimits.MBTILES_MAX_BYTES
        if (sizeBytes > limit) return ImportFailure(ImportError.TOO_LARGE, mapOf("limit" to limit))
        val needed = sizeBytes + ImportLimits.FREE_SPACE_MARGIN_BYTES
        if (freeBytes < needed) return ImportFailure(ImportError.NO_SPACE, mapOf("size" to needed))
        return null
    }
}

/** one page's boxes as the inspector read them, [llx lly urx ury] */
internal data class InspectedPageBoxes(val mediaBox: List<Double>, val cropBox: List<Double>?)

/** s9.5 checks in order. a CropBox defaults to the MediaBox, either box out of range fails */
internal object ImportInspectionRules {
    fun check(
        openable: Boolean,
        encrypted: Boolean,
        emptyPasswordOpens: Boolean,
        pageCount: Int,
        boxes: Sequence<InspectedPageBoxes>,
    ): ImportFailure? {
        if (!openable) return ImportFailure(ImportError.INVALID_PDF)
        if (encrypted && !emptyPasswordOpens) return ImportFailure(ImportError.PASSWORD)
        if (pageCount > ImportLimits.MAX_PAGES) {
            return ImportFailure(ImportError.TOO_MANY_PAGES, mapOf("limit" to ImportLimits.MAX_PAGES))
        }
        if (boxes.any { !boxOk(it.mediaBox) || !boxOk(it.cropBox ?: it.mediaBox) }) return ImportFailure(ImportError.PAGE_SIZE)
        return null
    }

    fun boxOk(box: List<Double>): Boolean {
        if (box.size != 4 || box.any { !it.isFinite() }) return false
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        return w in ImportLimits.PAGE_SIDE_MIN_PT..ImportLimits.PAGE_SIDE_MAX_PT &&
            h in ImportLimits.PAGE_SIDE_MIN_PT..ImportLimits.PAGE_SIDE_MAX_PT
    }
}

/** what the inspector found on one scanned page */
internal sealed class PageGeorefState {
    data object Valid : PageGeorefState()
    data class Rejected(val reason: GeorefRejectReason) : PageGeorefState()
    data object None : PageGeorefState()
}

/** [existingUnavailable]: its file is missing or changed, so the new copy takes over (E9) */
internal data class DuplicateInfo(val existingHasGeoref: Boolean, val name: String, val existingUnavailable: Boolean = false)

internal sealed class ImportOutcome {
    /** framed, toast when it isn't page 1 */
    data class AddAndActivate(val page: Int, val toast: StatusMessage?) : ImportOutcome()
    /** added as rejected (not active), alert offering calibration */
    data class AddRejected(val page: Int, val reason: GeorefRejectReason) : ImportOutcome()
    data class PagePicker(val badges: List<Int>) : ImportOutcome()
    data class AddAndCalibrate(val page: Int) : ImportOutcome()
    /** [relink]: the existing entry was unavailable, it points at the new copy first (E9) */
    data class ActivateExisting(val toast: StatusMessage, val relink: Boolean = false) : ImportOutcome()
    data class CalibrateExisting(val toast: StatusMessage, val relink: Boolean = false) : ImportOutcome()
}

/** s9.4 + s9.6, identical on both apps. [pages] lists only the scanned pages (first 50) */
internal object ImportDecision {
    fun decide(pageCount: Int, pages: List<PageGeorefState>?, duplicate: DuplicateInfo?): ImportOutcome {
        if (duplicate != null) {
            val toast = StatusMessage("map_import_duplicate", mapOf("name" to duplicate.name))
            val relink = duplicate.existingUnavailable
            return if (duplicate.existingHasGeoref) ImportOutcome.ActivateExisting(toast, relink)
            else ImportOutcome.CalibrateExisting(toast, relink)
        }
        val scanned = pages.orEmpty()
        val valid = scanned.indexOfFirst { it is PageGeorefState.Valid }
        if (valid >= 0) {
            val toast = if (valid != 0) {
                StatusMessage("map_import_page_used", mapOf("page" to valid + 1, "pages" to pageCount))
            } else {
                null
            }
            return ImportOutcome.AddAndActivate(valid, toast)
        }
        val declared = scanned.indices.filter { scanned[it] is PageGeorefState.Rejected }
        if (declared.isNotEmpty()) {
            if (pageCount == 1 || declared == listOf(0)) {
                return ImportOutcome.AddRejected(0, (scanned[0] as PageGeorefState.Rejected).reason)
            }
            return ImportOutcome.PagePicker(declared)
        }
        if (pageCount == 1) return ImportOutcome.AddAndCalibrate(0)
        return ImportOutcome.PagePicker(emptyList())
    }
}
