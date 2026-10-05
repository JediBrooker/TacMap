package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.tacmap.util.SafeStore
import java.io.File

/**
 * What the migration needs off the old PDF session prefs: PdfSessionStore in the app, a
 * stand in on the JVM (no SharedPreferences there)
 */
internal interface LegacySessionSource {
    /** active_pdf or pdf_calibrations is stored at all */
    fun hasLegacyState(): Boolean
    fun hasActivePdf(): Boolean
    /** the active session as the old app left it, null = none, or it wouldn't open */
    @WorkerThread fun legacySession(): LegacySession?
    /** the stored record's PDF, if the record opened far enough to name one */
    fun activeFile(): File?
    /** null = no pdf_calibrations stored, false = stored but it won't open or decode */
    fun calibrationsReadable(): Boolean?
    fun clearAfterLibraryMigration(): Boolean
}

/** a legacy PDF session, no android types in it so the JVM tests can make one */
internal data class LegacySession(
    val file: File?,
    val displayName: String,
    val geometry: PdfPageGeometry,
    /** what the session says the bytes hash to, null on an old record that never kept one */
    val contentKey: String?,
    val calibration: Calibration?,
    val georefIssue: PdfGeorefIssue?,
    val pendingFiduciaries: List<Fiduciary> = emptyList(),
    /** only a token the session really had, never one minted on this read */
    val renderGuardToken: String? = null,
)

/**
 * Reads the pre-library stores (ActiveMapSelectionStore + PdfSessionStore) for the one-time
 * migration. Worker thread: a v1 GeoPDF session gets re-parsed and the PDF hashed once (same
 * cost the old PdfSessionStore.save paid, just once).
 */
internal class LegacyMapReader(
    private val filesDir: File,
    private val selection: ActiveMapSelectionStore,
    private val session: LegacySessionSource,
    /** pdfium's page count, null or 0 = it won't open */
    private val pageCount: (File) -> Int?,
    private val mbtilesName: (File) -> String?,
) {
    constructor(context: Context, selection: ActiveMapSelectionStore, session: PdfSessionStore) : this(
        filesDir = context.filesDir,
        selection = selection,
        session = PdfSessionLegacySource(session),
        pageCount = { f -> kotlin.runCatching { PdfPageRenderer.pageCount(context, Uri.fromFile(f)) }.getOrNull() },
        mbtilesName = { f -> MBTilesStore.open(f.path)?.use { it.metadata.name } },
    )

    sealed class Read {
        data class Present(val inputs: LegacyMapInputs) : Read()
        /** an authenticated read that names nothing still on disk (S4) */
        data object Absent : Read()
        /** the key went at some point of the read: nothing's known, nothing written or deleted */
        data object Locked : Read()
        /**
         * authenticated, but something in the old stores won't read or convert (s13.1 L3).
         * [inputs] is what did, the migration salvages around the rest
         */
        data class Uncertain(val inputs: LegacyMapInputs, val causes: Set<LegacyLibraryState>) : Read()
    }

    /** anything for the migration to read, a quarantined selector included (it never goes away) */
    fun hasLegacyState(): Boolean = selection.hasLegacyState() || session.hasLegacyState()

    /** the live stores D8 may clear, never a .corrupt copy */
    fun hasClearableLegacyState(): Boolean = selection.hasClearableLegacyState() || session.hasLegacyState()

    /** the legacy column for a Loaded restore, where all that counts is whether D8 has something to clear */
    fun loadedLegacyState(): LegacyLibraryState = when {
        hasClearableLegacyState() -> LegacyLibraryState.READABLE
        hasLegacyState() -> LegacyLibraryState.QUARANTINED_ONLY
        else -> LegacyLibraryState.NONE
    }

    private fun keyAvailable(): Boolean = runCatching { SafeStore.keyProvider.key().fill(0) }.isSuccess

    @WorkerThread
    fun read(defaultStyle: String): Read {
        // Absent has to come off an authenticated read (S4), with no key nothing is known
        if (!keyAvailable()) return Read.Locked
        val causes = LinkedHashSet<LegacyLibraryState>()
        // an unseal or auth failure only counts as locked if the key's really gone now (L3)
        fun lostKey(cause: LegacyLibraryState): Boolean {
            if (!keyAvailable()) return true
            causes += cause
            return false
        }

        val activeThere = selection.hasActiveSelectorFile()
        val active = selection.loadState()
        when ((active as? ActiveMapSelectionLoadState.Unavailable)?.reason) {
            ActiveMapSelectionFailure.LOCKED -> return Read.Locked
            // this read quarantined it, or an earlier one (2.x included) did
            ActiveMapSelectionFailure.CORRUPT ->
                if (lostKey(if (activeThere) LegacyLibraryState.CORRUPT else LegacyLibraryState.QUARANTINED_ONLY)) return Read.Locked
            null -> Unit
        }
        // once the active selector's v2 the retained one lives inside it, so with that unreadable
        // there's nothing separate to read. its pack still gets adopted with the other files
        val retainedThere = selection.hasRetainedSelectorFile()
        val retained = if (active is ActiveMapSelectionLoadState.Unavailable) ActiveMapSelectionLoadState.Missing
            else selection.loadRetainedImportedState()
        when ((retained as? ActiveMapSelectionLoadState.Unavailable)?.reason) {
            ActiveMapSelectionFailure.LOCKED -> return Read.Locked
            ActiveMapSelectionFailure.CORRUPT -> if (lostKey(
                    if (retainedThere) LegacyLibraryState.RETAINED_CORRUPT else LegacyLibraryState.RETAINED_QUARANTINED_ONLY,
                )) return Read.Locked
            null -> Unit
        }
        val activeSel = (active as? ActiveMapSelectionLoadState.Loaded)?.selection
        val retainedSel = (retained as? ActiveMapSelectionLoadState.Loaded)?.selection
        val preferred = (activeSel ?: retainedSel)?.preferredOnlineStyle
            ?.takeIf { s -> BasemapStyle.entries.any { it.name == s } } ?: defaultStyle

        val hadPdf = session.hasActivePdf()
        val record = try {
            session.legacySession()
        } catch (e: Exception) {
            null
        }
        var pdf: LegacyPdf? = null
        when {
            record != null -> when (val l = legacyPdf(record)) {
                is LegacyPdfRead.Ok -> pdf = l.pdf
                // the file's gone, its calibration goes with it (D5-19), not a failure
                LegacyPdfRead.FileGone -> Unit
                // the file's adopted by the salvage, the session stays frozen where it is
                is LegacyPdfRead.Failed -> causes += l.cause
            }
            // the record's still there but wouldn't open / decode / convert. (a record whose file
            // is gone is cleared by loadSession, so it isn't here any more)
            hadPdf && session.hasActivePdf() -> if (lostKey(
                    if (session.activeFile()?.isFile == true) LegacyLibraryState.PDF_UNCONVERTIBLE
                    else LegacyLibraryState.SESSION_INVALID,
                )) return Read.Locked
        }
        // the content-keyed calibrations: nothing converts from them, but one that won't open
        // isn't cleared either
        if (session.calibrationsReadable() == false && lostKey(LegacyLibraryState.SESSION_INVALID)) return Read.Locked

        val offline = ArrayList<LegacyOffline>()
        for (sel in listOfNotNull(activeSel, retainedSel).filter { it.kind == ActiveMapKind.OFFLINE_TILES }) {
            // gone: dropped like a gone PDF (D5-19)
            val f = selection.offlineFile(sel) ?: continue
            // a pack outside our map dirs can't be taken over, and it's never dropped either.
            // no separate code for a pack, the outcome's the same as for a PDF
            if (ImportedMapLibraryStore.relativeName(filesDir, f) == null) {
                causes += LegacyLibraryState.PDF_UNCONVERTIBLE
                continue
            }
            if (offline.none { it.file == f }) {
                offline += LegacyOffline(f, mbtilesName(f)?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension)
            }
        }

        val legacyActive = when (activeSel?.kind) {
            ActiveMapKind.ONLINE -> LegacyActive.Online(preferred)
            ActiveMapKind.PDF -> if (pdf != null) LegacyActive.Pdf else LegacyActive.None
            ActiveMapKind.OFFLINE_TILES -> selection.offlineFile(activeSel)?.let { LegacyActive.Offline(it) } ?: LegacyActive.None
            // a missing selector only means "the old app's PDF" before the selector existed,
            // once the marker's there it never brings a PDF back (WP1 review leftover)
            null -> if (pdf != null && selection.legacyPdfMigrationPending()) LegacyActive.Pdf else LegacyActive.None
        }
        val inputs = LegacyMapInputs(legacyActive, preferred, pdf, offline)
        if (causes.isNotEmpty()) return Read.Uncertain(inputs, causes)
        if (pdf == null && offline.isEmpty() && activeSel == null) return Read.Absent
        return Read.Present(inputs)
    }

    private sealed class LegacyPdfRead {
        data class Ok(val pdf: LegacyPdf) : LegacyPdfRead()
        data object FileGone : LegacyPdfRead()
        data class Failed(val cause: LegacyLibraryState) : LegacyPdfRead()
    }

    private fun legacyPdf(source: LegacySession): LegacyPdfRead {
        val file = source.file?.takeIf { it.isFile } ?: return LegacyPdfRead.FileGone
        // outside our map dirs nothing can take it over, and nothing here touches it either
        if (ImportedMapLibraryStore.relativeName(filesDir, file) == null) return LegacyPdfRead.Failed(LegacyLibraryState.PDF_UNCONVERTIBLE)
        // the file's there but won't hash: an IO failure, never a reason to drop it
        val key = PdfCalibrationIdentity.contentKey(file) ?: return LegacyPdfRead.Failed(LegacyLibraryState.PDF_UNCONVERTIBLE)
        // other bytes than the calibration was made on: it's never applied to these
        if (source.contentKey != null && source.contentKey != key) return LegacyPdfRead.Failed(LegacyLibraryState.PDF_HASH_MISMATCH)
        val pages = pageCount(file)?.takeIf { it > 0 } ?: return LegacyPdfRead.Failed(LegacyLibraryState.PDF_UNCONVERTIBLE)
        var embedded: PdfGeoreference? = null
        var manual: List<Fiduciary> = emptyList()
        when (val c = source.calibration) {
            is Calibration.Parsed -> embedded = c.georef
            is Calibration.Fiduciaries ->
                if (c.fids.any { it.mgrs.isNotBlank() }) manual = c.fids else embedded = c.georef
            null -> Unit
        }
        return LegacyPdfRead.Ok(LegacyPdf(
            file = file,
            displayName = source.displayName,
            geometry = source.geometry,
            pageCount = pages,
            contentKey = key,
            embedded = embedded,
            manualFiduciaries = manual,
            issue = source.georefIssue,
            pendingFiduciaries = source.pendingFiduciaries,
            renderGuardToken = source.renderGuardToken,
        ))
    }

    fun clearAfterMigration(): Boolean {
        val a = selection.clearAfterLibraryMigration()
        val b = session.clearAfterLibraryMigration()
        return a && b
    }
}

/** the old session prefs as the migration sees them */
private class PdfSessionLegacySource(private val store: PdfSessionStore) : LegacySessionSource {
    override fun hasLegacyState(): Boolean = store.hasLegacyState()
    override fun hasActivePdf(): Boolean = store.hasActivePdf()
    override fun legacySession(): LegacySession? = store.load()?.let { s ->
        LegacySession(
            file = s.uri.path?.let(::File),
            displayName = s.displayName,
            geometry = s.geometry,
            contentKey = s.render.contentKey,
            calibration = s.calibration,
            georefIssue = s.georefIssue,
            pendingFiduciaries = s.pendingFiduciaries,
            renderGuardToken = s.render.renderGuardToken.takeUnless { s.render.tokenMinted },
        )
    }
    override fun activeFile(): File? = store.activeFile()
    override fun calibrationsReadable(): Boolean? = store.calibrationsReadable()
    override fun clearAfterLibraryMigration(): Boolean = store.clearAfterLibraryMigration()
}
