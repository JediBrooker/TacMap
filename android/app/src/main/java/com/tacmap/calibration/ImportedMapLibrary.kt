package com.tacmap.calibration

import com.tacmap.calibration.fiducial.CalibrationPoint
import com.tacmap.calibration.fiducial.StatusMessage
import kotlinx.serialization.Serializable

/**
 * Contract s8.2: the ONE sealed authority for the active map and every imported
 * map. Every transition is one write of this whole state, then publication.
 */
@Serializable
internal data class LibraryState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val active: ActiveRef,
    val preferredOnlineStyle: String,
    val entries: List<ImportedMapEntry> = emptyList(),
    // A rebuilt index cannot prove which unreferenced files or drafts are disposable.
    val recoveryPreservesOrphans: Boolean? = null,
    /**
     * goes up on every write (ImportedMapLibraryStore.commit), so a writer can tell the sealed
     * library moved on since it read its copy. older builds never wrote it, that reads as 0
     */
    val generation: Long = 0,
) {
    val permitsCleanup: Boolean get() = recoveryPreservesOrphans != true

    fun entry(id: String?): ImportedMapEntry? = id?.let { wanted -> entries.firstOrNull { it.id == wanted } }

    val activeEntry: ImportedMapEntry? get() = entry(active.entryId)

    fun byContentKey(key: String): ImportedMapEntry? = entries.firstOrNull { it.contentKey == key }

    /** [id] plus everything baked from it */
    fun withDerived(id: String): List<ImportedMapEntry> = entries.filter { it.id == id || it.derivedFromId == id }

    fun replacing(entry: ImportedMapEntry): LibraryState = copy(entries = entries.map { if (it.id == entry.id) entry else it })

    /** every bake file name a PDF entry vouches for, what the bake-only sweep keeps (M7) */
    val bakeFileNames: Set<String> get() = entries.mapNotNullTo(LinkedHashSet()) { it.pdf?.validBake?.fileName }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** {kind:"online", style} | {kind:"entry", id}, flat so it's the contract's json shape */
@Serializable
internal data class ActiveRef(
    val kind: String,
    val style: String? = null,
    val id: String? = null,
) {
    val entryId: String? get() = id.takeIf { kind == KIND_ENTRY }

    companion object {
        const val KIND_ONLINE = "online"
        const val KIND_ENTRY = "entry"

        fun online(style: String) = ActiveRef(KIND_ONLINE, style = style)
        fun entry(id: String) = ActiveRef(KIND_ENTRY, id = id)
    }
}

@Serializable
internal data class ImportedMapEntry(
    val id: String,
    /** "pdf" | "mbtiles" */
    val kind: String,
    /** opaque, relative to filesDir: pdf_maps/import-<16hex>.pdf, mbtiles/..., offline_tiles/... */
    val fileName: String,
    /** source file stem, kept only in here (sealed) */
    val displayName: String,
    /** sha256:<64 hex>, optional only for migrated MBTiles */
    val contentKey: String? = null,
    val byteCount: Long,
    val fileModifiedAtMs: Long,
    val importedAtMs: Long,
    val derivedFromId: String? = null,
    val pdf: PdfEntryInfo? = null,
) {
    val isPdf: Boolean get() = kind == ImportedMapKind.PDF.code
    val isMbtiles: Boolean get() = kind == ImportedMapKind.MBTILES.code

    /**
     * what the crash guard knows this map by. an entry from before the merge has no token,
     * its id is a random uuid that's just as sealed, so that stands in (no lazy write needed)
     */
    val renderGuardToken: String get() = pdf?.renderGuardToken?.takeIf(::isGuardUuid) ?: id
}

private val GUARD_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

internal fun isGuardUuid(s: String): Boolean = GUARD_UUID.matches(s)

@Serializable
internal data class PdfEntryInfo(
    val pageCount: Int,
    val pageIndex: Int,
    val rotate: Int,
    /** CropBox n MediaBox of the chosen page, ll lr ur ul, raw user space */
    val pageBox: List<List<Double>>,
    val embedded: PersistedGeoreference? = null,
    /** WP1 reject reason code */
    val embeddedIssue: String? = null,
    val manual: ManualCalibration? = null,
    /** reserved for WP2 D5-07 */
    val firstRenderPending: Boolean = false,
    /** android only: pdfium's frame of the page, the renderer and v1 migration need it */
    val geometry: PdfPageGeometry? = null,
    /** WP2 Generate Offline Tiles output for exactly the effective georef, a georef change drops it */
    val bake: PersistedPdfBake? = null,
    /** WP2 crash guard token, a random uuid minted with the entry (never from the file or its name) */
    val renderGuardToken: String? = null,
) {
    val pageBoxPoints: List<PagePoint> get() = pageBox.map { PagePoint(it[0], it[1]) }

    /** the bake record if it's one we'd have written, else nothing (same rule restore + sweep use) */
    val validBake: PersistedPdfBake? get() = bake?.takeIf(::isValidBakeRecord)
}

@Serializable
internal data class ManualCalibration(
    val datumId: String,
    val points: List<CalibrationPoint>,
    val georef: PersistedGeoreference,
    val n: Int,
    /** null for an exact 3 point fit */
    val rmsM: Double? = null,
    /** good | fair | poor, null for 3 points */
    val grade: String? = null,
    val savedAtMs: Long,
)

internal enum class EntryState(val code: String) {
    GEO_PDF("geoPDF"),
    CALIBRATED("calibrated"),
    REJECTED("rejected"),
    NEEDS_CALIBRATION("needsCalibration"),
    OFFLINE_TILES("offlineTiles"),
    DERIVED("derived"),
    UNAVAILABLE("unavailable"),
    /** an MBTiles pack whose open got refused this process, tap retries (s15.1) */
    OPEN_FAILED("openFailed"),
}

internal enum class EntryFileStatus { OK, MISSING, MISMATCH }

internal enum class EntryRowTap(val code: String) { ACTIVATE("activate"), CALIBRATE("calibrate"), NONE("none") }

internal enum class EntryMenuAction(val code: String) {
    CALIBRATE("map_action_calibrate"),
    CHOOSE_PAGE("map_action_choose_page"),
    GENERATE_TILES("generateOfflineTiles"),
    USE_EMBEDDED("map_action_use_embedded"),
    DELETE("map_action_delete"),
}

/** just the facts the row needs, so the pure table can run off the fixture too */
internal data class EntryFacts(
    val kind: ImportedMapKind,
    val pageCount: Int = 1,
    val hasEmbedded: Boolean = false,
    val embeddedIssue: String? = null,
    val manualPoints: Int? = null,
    val manualRmsM: Double? = null,
    val derivedFromId: String? = null,
    val parentName: String? = null,
)

internal data class EntryPresentation(
    val state: EntryState,
    /** "manual" | "embedded" | null */
    val effectiveGeoref: String?,
    val canBeDurableActive: Boolean,
    val subtitle: StatusMessage,
    val rowTap: EntryRowTap,
    val menu: List<EntryMenuAction>,
)

/** s8.2 derived state + the Layers row (import_limits.json entryStates) */
internal object LibraryEntryRules {
    fun facts(entry: ImportedMapEntry, parentName: String? = null): EntryFacts {
        val pdf = entry.pdf
        return EntryFacts(
            kind = if (entry.isPdf) ImportedMapKind.PDF else ImportedMapKind.MBTILES,
            pageCount = pdf?.pageCount ?: 1,
            hasEmbedded = pdf?.embedded != null,
            embeddedIssue = pdf?.embeddedIssue,
            manualPoints = pdf?.manual?.n,
            manualRmsM = pdf?.manual?.rmsM,
            derivedFromId = entry.derivedFromId,
            parentName = parentName,
        )
    }

    /** [packRefused]: the pack's open was refused this process (RefusedMbtiles), MBTiles only */
    fun state(f: EntryFacts, file: EntryFileStatus, packRefused: Boolean = false): EntryState = when {
        file != EntryFileStatus.OK -> EntryState.UNAVAILABLE
        // a corrupt-library rebuild adopted a PDF it couldn't inspect: Delete only (S2)
        f.kind == ImportedMapKind.PDF && f.pageCount < 1 -> EntryState.UNAVAILABLE
        f.kind == ImportedMapKind.MBTILES && packRefused -> EntryState.OPEN_FAILED
        f.kind == ImportedMapKind.MBTILES -> if (f.derivedFromId != null) EntryState.DERIVED else EntryState.OFFLINE_TILES
        f.manualPoints != null -> EntryState.CALIBRATED
        f.hasEmbedded -> EntryState.GEO_PDF
        f.embeddedIssue != null -> EntryState.REJECTED
        else -> EntryState.NEEDS_CALIBRATION
    }

    fun canBeDurableActive(state: EntryState): Boolean =
        state == EntryState.GEO_PDF || state == EntryState.CALIBRATED ||
            state == EntryState.OFFLINE_TILES || state == EntryState.DERIVED || state == EntryState.OPEN_FAILED

    fun present(f: EntryFacts, file: EntryFileStatus, draftPoints: Int?, packRefused: Boolean = false): EntryPresentation {
        val state = state(f, file, packRefused)
        val effective = if (f.kind == ImportedMapKind.PDF) {
            when {
                f.manualPoints != null -> "manual"
                f.hasEmbedded -> "embedded"
                else -> null
            }
        } else {
            null
        }
        val canActive = canBeDurableActive(state)
        val subtitle = when {
            state == EntryState.UNAVAILABLE -> StatusMessage("map_state_unavailable")
            draftPoints != null && f.kind == ImportedMapKind.PDF -> StatusMessage("map_state_draft", mapOf("points" to draftPoints))
            else -> when (state) {
                EntryState.GEO_PDF -> StatusMessage("map_state_geopdf")
                EntryState.CALIBRATED -> f.manualRmsM?.let {
                    StatusMessage("map_state_calibrated", mapOf("points" to f.manualPoints!!, "rms" to it))
                } ?: StatusMessage("map_state_calibrated_exact", mapOf("points" to f.manualPoints!!))
                EntryState.REJECTED -> StatusMessage("map_state_rejected")
                EntryState.NEEDS_CALIBRATION -> StatusMessage("map_state_needs_calibration")
                EntryState.OFFLINE_TILES -> StatusMessage("map_state_offline_tiles")
                EntryState.DERIVED -> StatusMessage("map_state_derived", mapOf("name" to (f.parentName ?: "")))
                EntryState.UNAVAILABLE -> StatusMessage("map_state_unavailable")
                EntryState.OPEN_FAILED -> StatusMessage("map_state_open_failed")
            }
        }
        val tap = when {
            state == EntryState.UNAVAILABLE -> EntryRowTap.NONE
            canActive -> EntryRowTap.ACTIVATE
            else -> EntryRowTap.CALIBRATE
        }
        val menu = ArrayList<EntryMenuAction>()
        if (f.kind == ImportedMapKind.PDF && state != EntryState.UNAVAILABLE) {
            menu += EntryMenuAction.CALIBRATE
            if (f.pageCount > 1) menu += EntryMenuAction.CHOOSE_PAGE
            if (effective != null) menu += EntryMenuAction.GENERATE_TILES
            if (f.manualPoints != null && f.hasEmbedded) menu += EntryMenuAction.USE_EMBEDDED
        }
        menu += EntryMenuAction.DELETE
        return EntryPresentation(state, effective, canActive, subtitle, tap, menu)
    }
}
