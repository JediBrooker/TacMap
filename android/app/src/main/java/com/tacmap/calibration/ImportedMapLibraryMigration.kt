package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.CalibrationPointKind
import com.tacmap.calibration.fiducial.CalibrationReference
import com.tacmap.calibration.fiducial.StoredPagePoint
import com.tacmap.calibration.fiducial.WGS84_OVERRIDE
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** a legacy PDF session as the old stores hand it back, file still on disk */
internal data class LegacyPdf(
    val file: File,
    val displayName: String,
    val geometry: PdfPageGeometry,
    val pageCount: Int,
    val contentKey: String,
    /** a GeoPDF's own georef */
    val embedded: PdfGeoreference? = null,
    /** user fiduciaries that refit (raw page space, WGS84 lat/lon) */
    val manualFiduciaries: List<Fiduciary> = emptyList(),
    val issue: PdfGeorefIssue? = null,
    /** points from a calibration that didn't survive, they go into a draft */
    val pendingFiduciaries: List<Fiduciary> = emptyList(),
    /** a WP2 session's crash guard token, carried so a standing suspect still matches */
    val renderGuardToken: String? = null,
)

internal data class LegacyOffline(val file: File, val displayName: String, val contentKey: String? = null)

internal sealed class LegacyActive {
    data class Online(val style: String) : LegacyActive()
    data object Pdf : LegacyActive()
    data class Offline(val file: File) : LegacyActive()
    data object None : LegacyActive()
}

internal data class LegacyMapInputs(
    val active: LegacyActive,
    val preferredOnlineStyle: String,
    val pdf: LegacyPdf?,
    val offline: List<LegacyOffline>,
)

internal data class MigrationResult(
    val state: LibraryState,
    val drafts: List<CalibrationDraft>,
    /** the PDF that was active but never georeferenced, the user gets told once */
    val uncalibratedActiveName: String?,
)

/**
 * Contract s8.2 migration, one time and fail closed: whatever the old selector +
 * PDF session + retained map pointed at becomes library entries. Pure so the
 * frozen legacy cases run on the JVM; [LegacyMapReader] does the store reading.
 * Android file names are already opaque, nothing gets renamed.
 */
internal object ImportedMapLibraryMigration {
    fun build(
        inputs: LegacyMapInputs,
        filesDir: File,
        nowMs: Long,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): MigrationResult? {
        val entries = ArrayList<ImportedMapEntry>()
        val drafts = ArrayList<CalibrationDraft>()
        var pdfEntry: ImportedMapEntry? = null
        inputs.pdf?.let { legacy ->
            val name = ImportedMapLibraryStore.relativeName(filesDir, legacy.file) ?: return null
            val g = legacy.geometry
            val pageBox = g.visibleBox.corners()
            val id = newId()
            val manual = legacy.manualFiduciaries.takeIf { it.size >= 3 }?.let { manualFrom(it, pageBox, nowMs) }
            val issueCode = (legacy.issue as? PdfGeorefIssue.Rejected)?.reason?.code
            val entry = ImportedMapEntry(
                id = id,
                kind = ImportedMapKind.PDF.code,
                fileName = name,
                displayName = legacy.displayName,
                contentKey = legacy.contentKey,
                byteCount = legacy.file.length(),
                fileModifiedAtMs = legacy.file.lastModified(),
                importedAtMs = nowMs,
                pdf = PdfEntryInfo(
                    pageCount = legacy.pageCount,
                    pageIndex = 0,
                    rotate = g.rotation,
                    pageBox = pageBox.map { listOf(it.x, it.y) },
                    embedded = legacy.embedded?.let(PdfGeoreferenceCodec::encode),
                    embeddedIssue = issueCode,
                    manual = manual,
                    geometry = g,
                    // the old session's bake record isn't carried (M7): it's cheap to make
                    // again and the sweep reaps the file
                    renderGuardToken = legacy.renderGuardToken?.takeIf(::isGuardUuid) ?: UUID.randomUUID().toString(),
                ),
            )
            entries += entry
            pdfEntry = entry
            // points that didn't refit (or a manual set that won't fit any more) aren't thrown away
            val leftover = when {
                manual == null && legacy.manualFiduciaries.isNotEmpty() -> legacy.manualFiduciaries
                legacy.pendingFiduciaries.isNotEmpty() -> legacy.pendingFiduciaries
                else -> emptyList()
            }
            if (leftover.isNotEmpty()) {
                drafts += CalibrationDraft(
                    contentKey = legacy.contentKey,
                    pageIndex = 0,
                    entryId = id,
                    datumId = GeoDatums.WGS84.id,
                    points = pointsFrom(leftover),
                    nextNumber = leftover.size + 1,
                    active = false,
                    updatedAtMs = nowMs,
                )
            }
        }
        val offlineEntries = inputs.offline.distinctBy { it.file.absolutePath }.mapNotNull { o ->
            val name = ImportedMapLibraryStore.relativeName(filesDir, o.file) ?: return@mapNotNull null
            o.file.absolutePath to ImportedMapEntry(
                id = newId(),
                kind = ImportedMapKind.MBTILES.code,
                fileName = name,
                displayName = o.displayName,
                contentKey = o.contentKey,
                byteCount = o.file.length(),
                fileModifiedAtMs = o.file.lastModified(),
                importedAtMs = nowMs,
            )
        }
        entries += offlineEntries.map { it.second }

        var uncalibrated: String? = null
        val active = when (val a = inputs.active) {
            is LegacyActive.Online -> ActiveRef.online(a.style)
            LegacyActive.None -> ActiveRef.online(inputs.preferredOnlineStyle)
            is LegacyActive.Offline -> offlineEntries.firstOrNull { it.first == a.file.absolutePath }
                ?.let { ActiveRef.entry(it.second.id) } ?: ActiveRef.online(inputs.preferredOnlineStyle)
            LegacyActive.Pdf -> {
                val e = pdfEntry
                if (e == null) {
                    ActiveRef.online(inputs.preferredOnlineStyle)
                } else if (LibraryEntryRules.canBeDurableActive(LibraryEntryRules.state(LibraryEntryRules.facts(e), EntryFileStatus.OK))) {
                    ActiveRef.entry(e.id)
                } else {
                    // a never-georeferenced PDF isn't a basemap any more (D2-06, D5-02)
                    uncalibrated = e.displayName
                    ActiveRef.online(inputs.preferredOnlineStyle)
                }
            }
        }
        val state = LibraryState(
            active = active,
            preferredOnlineStyle = inputs.preferredOnlineStyle,
            entries = entries,
        )
        return MigrationResult(state, drafts, uncalibrated)
    }

    /** v1 fiduciaries: geographic with the WGS84 override, the typed MGRS kept as the input text */
    fun pointsFrom(fids: List<Fiduciary>): List<CalibrationPoint> = fids.mapIndexed { i, f ->
        CalibrationPoint(
            id = f.id,
            number = i + 1,
            page = StoredPagePoint(f.pdfX, f.pdfY),
            input = f.mgrs.ifBlank { latLonText(f.latitude, f.longitude) },
            reference = CalibrationReference.Geographic(f.latitude, f.longitude),
            kind = CalibrationPointKind.INTERSECTION,
            datumOverride = WGS84_OVERRIDE,
            label = f.label,
            canonical = latLonText(f.latitude, f.longitude),
        )
    }

    /** refit through WP1 (zone off the first typed MGRS, like the old session did), crop = page box */
    fun manualFrom(fids: List<Fiduciary>, pageBox: List<PagePoint>, nowMs: Long): ManualCalibration? {
        val fit = FiduciaryFitter.refitStored(fids, pageBox) ?: return null
        val georef = fit.georeference(pageBox) ?: return null
        val n = fids.size
        val diagonal = pageBox.let { b ->
            val a = requireNotNull(fit.affine)
            val c0 = a.apply(b[0].x, b[0].y); val c1 = a.apply(b[1].x, b[1].y)
            val c2 = a.apply(b[2].x, b[2].y); val c3 = a.apply(b[3].x, b[3].y)
            maxOf(kotlin.math.hypot(c0.x - c2.x, c0.y - c2.y), kotlin.math.hypot(c1.x - c3.x, c1.y - c3.y))
        }
        val tau = com.tacmap.calibration.fiducial.CalibrationFitReport.tolerance(diagonal)
        val grade = if (n >= 4) when {
            fit.rmsMetres <= tau -> "good"
            fit.rmsMetres <= 3.0 * tau -> "fair"
            else -> "poor"
        } else null
        return ManualCalibration(
            datumId = GeoDatums.WGS84.id,
            points = pointsFrom(fids),
            georef = PdfGeoreferenceCodec.encode(georef),
            n = n,
            rmsM = if (n >= 4) fit.rmsMetres else null,
            grade = grade,
            savedAtMs = nowMs,
        )
    }

    private fun latLonText(lat: Double, lon: Double): String = String.format(
        Locale.US, "%.5f° %s, %.5f° %s",
        abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W",
    )
}
