package com.tacmap.map

import com.tacmap.calibration.GeorefRejectReason
import com.tacmap.calibration.ImportOutcome
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.InFlightImportFiles
import com.tacmap.calibration.InspectedPage
import com.tacmap.calibration.fiducial.StatusMessage
import java.io.File

/** what the M7/M12 probe made of the page about to be committed */
internal sealed class ImportProbeResult {
    data object Drawable : ImportProbeResult()
    /** WP2's reason copy. nothing gets written (iOS calls this MapImportError.cannotDraw) */
    data class CannotDraw(val reason: String) : ImportProbeResult()
}

/** what the user hears about an import. failures are alerts with OK, the rest toasts (OD-F5) */
internal sealed class ImportNotice {
    data class Toast(val message: StatusMessage) : ImportNotice()
    data class Alert(val text: String) : ImportNotice()
}

/** the bits of the view model a commit touches, so the order can be driven off device */
internal interface ImportCommitTarget {
    fun addEntry(entry: ImportedMapEntry, activate: Boolean): Boolean
    fun activate(id: String): Boolean
    fun relink(id: String, file: File): Boolean
    fun showRejectedPrompt(id: String, reason: GeorefRejectReason)
    fun showPagePicker(prepared: PreparedPdfImport)
    fun calibrate(id: String)
}

/**
 * The M12 probe: draw the base raster of [entry]'s page under the crash guard before the
 * library write. A page that can't be drawn loses its copy and commits nothing.
 * [probe] throws [PdfImportRejectedException] with WP2's reason copy. [S] is a PdfMapSource
 * in the app, anything in the JVM tests
 */
internal class ImportProbeStep<S : Any>(
    private val sourceFor: (ImportedMapEntry) -> S?,
    private val probe: suspend (S) -> Unit,
    private val deleteCopy: suspend (File) -> Unit,
    private val fallbackReason: () -> String,
) {
    suspend fun run(entry: ImportedMapEntry, file: File): ImportProbeResult {
        // nothing to draw it on (no geometry yet): the first real render catches it instead
        val source = sourceFor(entry) ?: return ImportProbeResult.Drawable
        return try {
            probe(source)
            ImportProbeResult.Drawable
        } catch (rejected: PdfImportRejectedException) {
            deleteCopy(file)
            ImportProbeResult.CannotDraw(rejected.message ?: fallbackReason())
        }
    }
}

/**
 * A prepared PDF landing in the library, main thread (s9.4 + s9.6, then M7 probe, then the
 * write, E5 order). Out of MapScreen so the probe-fails-nothing-written and the cancel
 * re-check after the probe (E2) have JVM tests. [onStage] reports probe + write
 */
internal class PdfImportCommitter(
    private val target: ImportCommitTarget,
    private val probe: ImportProbeStep<*>,
    private val newEntry: (PreparedPdfImport, InspectedPage) -> ImportedMapEntry?,
    private val notify: (ImportNotice) -> Unit,
    private val onStage: (ImportStage) -> Unit = {},
    /** null = the coroutine's own cancel state, tests hand in a flag */
    private val isCancelled: (() -> Boolean)? = null,
    private val release: (File) -> Unit = InFlightImportFiles::release,
) {
    /** the probed entry for a page, null = it can't be drawn (already told) */
    private suspend fun probed(prepared: PreparedPdfImport, page: InspectedPage): ImportedMapEntry? {
        val entry = newEntry(prepared, page) ?: return null
        onStage(ImportStage.PROBE)
        return when (val r = probe.run(entry, prepared.file)) {
            ImportProbeResult.Drawable -> {
                // E2: a cancel that landed during the probe still wins, nothing's written
                recheckCancel(prepared.file, isCancelled)
                entry
            }
            is ImportProbeResult.CannotDraw -> {
                notify(ImportNotice.Alert(r.reason))
                null
            }
        }
    }

    suspend fun commit(prepared: PreparedPdfImport) {
        val outcome = prepared.outcome
        try {
            when (outcome) {
                is ImportOutcome.ActivateExisting -> prepared.duplicate?.let { existing ->
                    if (outcome.relink && !relink(existing, prepared.file)) return
                    target.activate(existing.id)
                    notify(ImportNotice.Toast(outcome.toast))
                }
                is ImportOutcome.CalibrateExisting -> prepared.duplicate?.let { existing ->
                    if (outcome.relink && !relink(existing, prepared.file)) return
                    notify(ImportNotice.Toast(outcome.toast))
                    target.calibrate(existing.id)
                }
                is ImportOutcome.AddAndActivate -> {
                    val entry = prepared.inspection.page(outcome.page)?.let { probed(prepared, it) } ?: return
                    onStage(ImportStage.WRITE)
                    // E7: page used only once it's really in
                    if (target.addEntry(entry, activate = true)) outcome.toast?.let { notify(ImportNotice.Toast(it)) }
                }
                is ImportOutcome.AddRejected -> {
                    val entry = prepared.inspection.page(outcome.page)?.let { probed(prepared, it) } ?: return
                    onStage(ImportStage.WRITE)
                    if (target.addEntry(entry, activate = false)) target.showRejectedPrompt(entry.id, outcome.reason)
                }
                is ImportOutcome.AddAndCalibrate -> {
                    val entry = prepared.inspection.page(outcome.page)?.let { probed(prepared, it) } ?: return
                    onStage(ImportStage.WRITE)
                    // E1: no georef, straight into calibration on a provisional placement
                    if (target.addEntry(entry, activate = false)) target.calibrate(entry.id)
                }
                is ImportOutcome.PagePicker -> {
                    // the copy stays in flight in the VM until they pick or cancel
                    target.showPagePicker(prepared)
                    return
                }
            }
        } finally {
            if (outcome !is ImportOutcome.PagePicker) release(prepared.file)
        }
    }

    /** the import picker's choice (s9.6 case 3): add it, then calibrate */
    suspend fun commitPicked(prepared: PreparedPdfImport, page: InspectedPage) {
        try {
            val entry = probed(prepared, page) ?: return
            onStage(ImportStage.WRITE)
            if (target.addEntry(entry, activate = false)) target.calibrate(entry.id)
        } finally {
            release(prepared.file)
        }
    }

    /**
     * E9 re-link. a failed write leaves the copy where it is so the VM's Retry can still
     * use it; if nobody does, it's an orphan the next reconcile takes
     */
    private fun relink(existing: ImportedMapEntry, file: File): Boolean {
        onStage(ImportStage.WRITE)
        return target.relink(existing.id, file)
    }
}
