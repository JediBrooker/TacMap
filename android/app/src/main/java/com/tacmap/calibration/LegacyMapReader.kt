package com.tacmap.calibration

import android.content.Context
import android.net.Uri
import androidx.annotation.WorkerThread
import com.tacmap.util.SafeStore
import java.io.File

/**
 * Reads the pre-library stores (ActiveMapSelectionStore + PdfSessionStore) for
 * the one-time migration. Worker thread: a v1 GeoPDF session gets re-parsed and
 * the PDF hashed once (same cost the old PdfSessionStore.save paid, just once).
 */
internal class LegacyMapReader(
    private val context: Context,
    private val selection: ActiveMapSelectionStore,
    private val session: PdfSessionStore,
) {
    sealed class Read {
        data class Present(val inputs: LegacyMapInputs) : Read()
        data object Absent : Read()
        /** locked / corrupt legacy state: no migration, nothing deleted */
        data object Unavailable : Read()
    }

    fun hasLegacyState(): Boolean = selection.hasLegacyState() || session.hasLegacyState()

    @WorkerThread
    fun read(defaultStyle: String): Read {
        // Absent has to come off an authenticated read (S4), with no key nothing is known
        if (runCatching { SafeStore.keyProvider.key().fill(0) }.isFailure) return Read.Unavailable
        val active = selection.loadState()
        val retained = selection.loadRetainedImportedState()
        if (active is ActiveMapSelectionLoadState.Unavailable || retained is ActiveMapSelectionLoadState.Unavailable) {
            return Read.Unavailable
        }
        val activeSel = (active as? ActiveMapSelectionLoadState.Loaded)?.selection
        val retainedSel = (retained as? ActiveMapSelectionLoadState.Loaded)?.selection
        val preferred = (activeSel ?: retainedSel)?.preferredOnlineStyle
            ?.takeIf { s -> BasemapStyle.entries.any { it.name == s } } ?: defaultStyle

        val hadPdf = session.hasActivePdf()
        val pdfSource = try {
            session.load()
        } catch (e: Exception) {
            return Read.Unavailable
        }
        val pdf: LegacyPdf? = when {
            pdfSource != null -> when (val l = legacyPdf(pdfSource)) {
                is LegacyPdfRead.Ok -> l.pdf
                // the file's gone, its calibration goes with it (D5-19), not a failure
                LegacyPdfRead.FileGone -> null
                LegacyPdfRead.Unreadable -> return Read.Unavailable
            }
            // S3 fail closed: the record's still there but wouldn't open / decode / convert.
            // (a record whose file is gone is cleared by loadSession, so it isn't here any more)
            hadPdf && session.hasActivePdf() -> return Read.Unavailable
            else -> null
        }

        val offline = listOfNotNull(activeSel, retainedSel)
            .filter { it.kind == ActiveMapKind.OFFLINE_TILES }
            .mapNotNull { selection.offlineFile(it) }
            .map { f -> LegacyOffline(f, mbtilesName(f)) }

        val legacyActive = when (activeSel?.kind) {
            ActiveMapKind.ONLINE -> LegacyActive.Online(preferred)
            ActiveMapKind.PDF -> if (pdf != null) LegacyActive.Pdf else LegacyActive.None
            ActiveMapKind.OFFLINE_TILES -> selection.offlineFile(activeSel)?.let { LegacyActive.Offline(it) } ?: LegacyActive.None
            // a missing selector only means "the old app's PDF" before the selector existed,
            // once the marker's there it never brings a PDF back (WP1 review leftover)
            null -> if (pdf != null && selection.legacyPdfMigrationPending()) LegacyActive.Pdf else LegacyActive.None
        }
        if (pdf == null && offline.isEmpty() && activeSel == null) return Read.Absent
        return Read.Present(LegacyMapInputs(legacyActive, preferred, pdf, offline))
    }

    private sealed class LegacyPdfRead {
        data class Ok(val pdf: LegacyPdf) : LegacyPdfRead()
        data object FileGone : LegacyPdfRead()
        data object Unreadable : LegacyPdfRead()
    }

    private fun legacyPdf(source: PdfMapSource): LegacyPdfRead {
        val file = source.uri.path?.let(::File)?.takeIf { it.isFile } ?: return LegacyPdfRead.FileGone
        // the file's there but won't hash: that's an IO failure, block rather than drop it
        val key = PdfCalibrationIdentity.contentKey(file) ?: return LegacyPdfRead.Unreadable
        if (source.render.contentKey != null && source.render.contentKey != key) return LegacyPdfRead.Unreadable
        val pages = runCatching { PdfPageRenderer.pageCount(context, Uri.fromFile(file)) }.getOrNull()
            ?.takeIf { it > 0 } ?: return LegacyPdfRead.Unreadable
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
            // only a token the session really had, never one minted on this read
            renderGuardToken = source.render.renderGuardToken.takeUnless { source.render.tokenMinted },
        ))
    }

    private fun mbtilesName(f: File): String =
        MBTilesStore.open(f.path)?.use { it.metadata.name }?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension

    fun clearAfterMigration(): Boolean {
        val a = selection.clearAfterLibraryMigration()
        val b = session.clearAfterLibraryMigration()
        return a && b
    }
}
