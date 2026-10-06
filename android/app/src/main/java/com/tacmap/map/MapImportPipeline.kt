package com.tacmap.map

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.tacmap.calibration.DuplicateInfo
import com.tacmap.calibration.GeoPdfGeorefResult
import com.tacmap.calibration.ImportDecision
import com.tacmap.calibration.ImportError
import com.tacmap.calibration.ImportFailure
import com.tacmap.calibration.ImportLimits
import com.tacmap.calibration.ImportOutcome
import com.tacmap.calibration.ImportPrecheck
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.ImportedMapKind
import com.tacmap.calibration.ImportedMapLibraryStore
import com.tacmap.calibration.InFlightImportFiles
import com.tacmap.calibration.InspectedPage
import com.tacmap.calibration.InspectionResult
import com.tacmap.calibration.MBTilesStore
import com.tacmap.calibration.PdfEntryInfo
import com.tacmap.calibration.PdfGeoreferenceCodec
import com.tacmap.calibration.PdfInspection
import com.tacmap.calibration.PdfInspector
import com.tacmap.calibration.PdfPageGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** contract s9.1 stages, the progress card shows these after 300 ms */
internal sealed class ImportProgress {
    data class Copying(val done: Long, val total: Long?) : ImportProgress() {
        val percent: Int? get() = total?.takeIf { it > 0 }?.let { ((done * 100) / it).toInt().coerceIn(0, 100) }
    }
    data class Reading(val page: Int, val pages: Int) : ImportProgress()
    data object Saving : ImportProgress()

    /** E2: Cancel is only there while copying and reading, never once it's saving */
    val cancelVisible: Boolean get() = this !is Saving
}

/** the import's steps in contract order (E5, import_limits.json lifecycle.importPipeline) */
internal enum class ImportStage(val code: String) {
    PRECHECK("precheck"),
    COPY_AND_HASH("copyAndHash"),
    DEDUPE("dedupe"),
    INSPECT("inspect"),
    DECISION("decision"),
    PROBE("probe"),
    WRITE("write"),
}

/** a copied, hashed and inspected PDF waiting on the main thread to be committed */
internal data class PreparedPdfImport(
    val file: File,
    val contentKey: String,
    val displayName: String,
    val inspection: PdfInspection,
    val outcome: ImportOutcome,
    val duplicate: ImportedMapEntry? = null,
    /** "Choose page..." on a library entry: [file] IS that entry's file, never delete it */
    val existingEntryId: String? = null,
)

internal data class PreparedMbtilesImport(
    val file: File,
    val contentKey: String,
    val displayName: String,
    val duplicate: ImportedMapEntry? = null,
    /** the duplicate was unavailable: [file] is kept and the entry re-linked to it (E9) */
    val relink: Boolean = false,
    /** what the admission on the worker gave, so the commit puts it up without another (s14.2) */
    val metadata: MBTilesStore.Metadata? = null,
)

internal sealed class PreparedOutcome {
    data class Pdf(val prepared: PreparedPdfImport) : PreparedOutcome()
    data class Mbtiles(val prepared: PreparedMbtilesImport) : PreparedOutcome()
    data class Failed(val failure: ImportFailure) : PreparedOutcome()
}

/** what the pre-check needs from the library, read on main before the job starts */
internal data class LibrarySnapshot(
    val loaded: Boolean,
    val entryCount: Int,
    val byContentKey: (String) -> ImportedMapEntry?,
    /** file missing or changed (stat, or a hash mismatch this launch), off main is fine */
    val isUnavailable: (ImportedMapEntry) -> Boolean = { false },
)

/**
 * Contract s9: pre-check -> one-pass copy + hash -> dedupe -> crash marker ->
 * inspect ONCE under a 30 s watchdog -> ImportDecision. Runs on IO from the
 * composition-scoped job (MapScreen goes away on every pause and the copy
 * journal makes the retry idempotent). Nothing here touches the library; the
 * caller commits on main and then releases the in-flight file.
 */
internal class MapImportPipeline(
    private val context: Context,
    private val journal: DocumentImportCopyStateStore,
    private val inspect: (File, () -> Boolean, (Int, Int) -> Unit) -> InspectionResult = { f, c, p ->
        PdfInspector.inspect(context, f, c, p)
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val freeBytes: () -> Long = { StatFs(context.filesDir.path).availableBytes },
    private val onStage: (ImportStage) -> Unit = {},
    /** the MBTiles admission, a seam so a test can look at the marker while it runs. null = refused */
    private val validateMbtiles: (File) -> MBTilesStore.Metadata? = { f -> MBTilesStore.open(f.path)?.use { it.metadata } },
) {
    private val stages = PdfImportStages(journal, inspect, clock, onStage)

    suspend fun runPdf(
        uri: Uri,
        operationKey: String,
        library: LibrarySnapshot,
        progress: (ImportProgress) -> Unit,
    ): PreparedOutcome = InspectionMarks.lockFor(operationKey).withLock {
        runPdfOnce(uri, operationKey, library, progress)
    }

    private suspend fun runPdfOnce(
        uri: Uri,
        operationKey: String,
        library: LibrarySnapshot,
        progress: (ImportProgress) -> Unit,
    ): PreparedOutcome {
        interruptedBefore(operationKey)?.let { return it }
        val size = sourceSize(uri)
        onStage(ImportStage.PRECHECK)
        precheck(library, ImportedMapKind.PDF, size)?.let { return PreparedOutcome.Failed(it) }
        val name = displayStem(uri, ".pdf")
        onStage(ImportStage.COPY_AND_HASH)
        val copied = copy(uri, operationKey, "pdf_maps", "pdf", ImportLimits.PDF_MAX_BYTES, size, progress) { true }
            ?: return PreparedOutcome.Failed(ImportFailure(ImportError.TOO_LARGE, mapOf("limit" to ImportLimits.PDF_MAX_BYTES)))
        val (file, key) = copied
        return stages.afterCopy(file, key, name, operationKey, library, progress)
    }

    suspend fun runMbtiles(
        uri: Uri,
        operationKey: String,
        library: LibrarySnapshot,
        progress: (ImportProgress) -> Unit,
    ): PreparedOutcome = InspectionMarks.lockFor(operationKey).withLock {
        // a pause cancels the job but not the admission, which can't be stopped. the replay on
        // resume waits for that one to come back so they never share the copy (DL-1)
        runMbtilesOnce(uri, operationKey, library, progress)
    }

    private suspend fun runMbtilesOnce(
        uri: Uri,
        operationKey: String,
        library: LibrarySnapshot,
        progress: (ImportProgress) -> Unit,
    ): PreparedOutcome {
        // the same pack took the app down in its admission last time, replaying it would loop
        interruptedBefore(operationKey)?.let { return it }
        val size = sourceSize(uri)
        precheck(library, ImportedMapKind.MBTILES, size)?.let { return PreparedOutcome.Failed(it) }
        val name = displayStem(uri, ".mbtiles")
        var admitted: MBTilesStore.Metadata? = null
        val copied = try {
            copy(uri, operationKey, "mbtiles", "mbtiles", ImportLimits.MBTILES_MAX_BYTES, size, progress) { f ->
                // s9.8 / s14.1: the admission reads a hostile file, so it runs under the same
                // durable marker as a PDF parse. a crash in there gets swept at the next launch
                markInspecting(operationKey, clock())
                try {
                    validateMbtiles(f).also { admitted = it } != null
                } finally {
                    markInspecting(operationKey, null)
                }
            } ?: return PreparedOutcome.Failed(ImportFailure(ImportError.TOO_LARGE, mapOf("limit" to ImportLimits.MBTILES_MAX_BYTES)))
        } catch (c: CancellationException) {
            throw c
        } catch (_: IllegalStateException) {
            return PreparedOutcome.Failed(ImportFailure(ImportError.INVALID_MBTILES))
        }
        val (file, key) = copied
        recheckCancel(file)
        library.byContentKey(key)?.let { existing ->
            // E9: an unavailable entry gets these (identical, just verified) bytes back
            val relink = library.isUnavailable(existing)
            if (!relink) discard(file)
            return PreparedOutcome.Mbtiles(PreparedMbtilesImport(file, key, existing.displayName, existing, relink))
        }
        return PreparedOutcome.Mbtiles(PreparedMbtilesImport(file, key, name, metadata = admitted))
    }

    /**
     * "Choose page..." on a library entry: read its own file again under the same
     * 30 s watchdog. No copy, no marker (it's the user's own action on a file that
     * already imported fine once, not something that runs at launch). null = timed out
     */
    suspend fun reinspect(file: File, progress: (ImportProgress) -> Unit): InspectionResult? = stages.runWatchdog(file, progress)

    // ------------------------------------------------------------------ stages

    private fun precheck(library: LibrarySnapshot, kind: ImportedMapKind, size: Long?): ImportFailure? =
        ImportPrecheck.check(
            libraryLoaded = library.loaded,
            entryCount = library.entryCount,
            kind = kind,
            // unknown size: the bounded copy enforces the limit as it goes
            sizeBytes = size ?: 0L,
            freeBytes = runCatching { freeBytes() }.getOrDefault(Long.MAX_VALUE),
        )

    /** (file, contentKey) or null when the source ran past [limit] */
    private suspend fun copy(
        uri: Uri,
        operationKey: String,
        dirName: String,
        extension: String,
        limit: Long,
        total: Long?,
        progress: (ImportProgress) -> Unit,
        validate: (File) -> Boolean,
    ): Pair<File, String>? {
        val ctx = currentCoroutineContext()
        val op = IdempotentDocumentCopy(
            destinationDir = File(context.filesDir, dirName),
            extension = extension,
            maxBytes = limit,
            stateStore = journal,
            openSource = {
                requireNotNull(context.contentResolver.openInputStream(uri)) { "source unreadable" }
            },
            validate = validate,
            onBytes = { progress(ImportProgress.Copying(it, total)) },
            checkCancelled = { ctx.ensureActive() },
        )
        progress(ImportProgress.Copying(0, total))
        return try {
            op.executeHashed(operationKey).let { it.file to it.contentKey }
        } catch (_: ImportTooLargeException) {
            null
        }
    }

    /** the same operation died inside the parse last time: don't go round again (s9.8) */
    private fun interruptedBefore(operationKey: String): PreparedOutcome? {
        val st = runCatching { journal.state(operationKey) }.getOrNull() ?: return null
        if (st.inspectStartedAtEpochMs == null && !st.interrupted) return null
        // a marker this process set and just couldn't clear (the key relocked under the
        // parse) isn't a crash, the parse came back. clear it and go again (DL-1)
        if (!st.interrupted && InspectionMarks.clearStale(journal, st, clock())) return null
        clearInterrupted(journal, st)
        return PreparedOutcome.Failed(ImportFailure(ImportError.INTERRUPTED))
    }

    private fun discard(file: File) = discardImportCopy(file)

    private fun markInspecting(operationKey: String, at: Long?) = InspectionMarks.mark(journal, operationKey, at, clock())

    private fun sourceSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
        }
    }.getOrNull()

    private fun displayStem(uri: Uri, ext: String): String {
        val raw = runCatching { context.displayNameFor(uri) }.getOrNull() ?: "map$ext"
        return if (raw.endsWith(ext, ignoreCase = true)) raw.dropLast(ext.length) else raw
    }

    companion object {
        fun hasGeoref(e: ImportedMapEntry): Boolean = e.isMbtiles || e.pdf?.manual != null || e.pdf?.embedded != null

        /** wipe the copy behind a stuck marker and clear it (shared with the launch sweep) */
        fun clearInterrupted(journal: DocumentImportCopyStateStore, st: DocumentImportCopyState) {
            st.resultPath?.let { p ->
                runCatching { File(p).delete() }
                runCatching { File("$p.partial").delete() }
            }
            runCatching {
                journal.persist(st.copy(phase = DocumentImportCopyPhase.FAILED, inspectStartedAtEpochMs = null, interrupted = true))
            }
        }

        /**
         * the copies a stuck marker names (and their .partial), what the launch sweep deletes.
         * a restore that adopts orphan map files leaves these out, the parse already killed us once
         */
        fun interruptedFiles(journal: DocumentImportCopyStateStore): Set<File> =
            runCatching { journal.all() }.getOrDefault(emptyList())
                .filter { it.inspectStartedAtEpochMs != null }
                .flatMap { st -> st.resultPath?.let { listOf(File(it), File("$it.partial")) }.orEmpty() }
                .toSet()

        /**
         * launch sweep: any marker still set means an inspection killed the process. unless its
         * copy is still in flight in this one, then it's a live import (another map screen's,
         * or ours before a re-adopt) mid parse and not a crash at all
         */
        fun sweepInterrupted(
            journal: DocumentImportCopyStateStore,
            inFlight: Set<File> = InFlightImportFiles.snapshot(),
        ): Boolean {
            val live = inFlight.map { it.absoluteFile }.toSet()
            val stuck = runCatching { journal.all() }.getOrDefault(emptyList())
                .filter { it.inspectStartedAtEpochMs != null }
                .filterNot { st -> st.resultPath?.let { File(it).absoluteFile in live } == true }
                // set by this process and its parse came back (or is still going): not a crash.
                // a stale one gets its clear retried, the copy and the pending pick stay (DL-1)
                .filterNot { st -> InspectionMarks.clearStale(journal, st) }
            stuck.forEach { clearInterrupted(journal, it) }
            return stuck.isNotEmpty()
        }

        /**
         * The library entry for [page] of a prepared PDF. Geometry comes from the
         * inspection (or a fresh pdfium frame for a picked page).
         */
        fun pdfEntry(
            prepared: PreparedPdfImport,
            page: InspectedPage,
            geometry: PdfPageGeometry?,
            filesDir: File,
            nowMs: Long,
            id: String = UUID.randomUUID().toString(),
        ): ImportedMapEntry? {
            val rel = ImportedMapLibraryStore.relativeName(filesDir, prepared.file) ?: return null
            val info = pdfInfo(prepared.inspection.pageCount, page, geometry) ?: return null
            return ImportedMapEntry(
                id = id,
                kind = ImportedMapKind.PDF.code,
                fileName = rel,
                displayName = prepared.displayName,
                contentKey = prepared.contentKey,
                byteCount = prepared.file.length(),
                fileModifiedAtMs = prepared.file.lastModified(),
                importedAtMs = nowMs,
                pdf = info,
            )
        }

        /** the library's view of one inspected page (box, /Rotate, its georef or why not) */
        fun pdfInfo(pageCount: Int, page: InspectedPage, geometry: PdfPageGeometry?): PdfEntryInfo? {
            val box = page.visibleBox ?: return null
            val embedded = (page.georef as? GeoPdfGeorefResult.Georeferenced)?.georef
            val issue = (page.georef as? GeoPdfGeorefResult.Rejected)?.reason?.code
            return PdfEntryInfo(
                pageCount = pageCount,
                pageIndex = page.index,
                rotate = PdfPageGeometry.normaliseRotation(page.rotate),
                pageBox = box.corners().map { listOf(it.x, it.y) },
                embedded = embedded?.let { PdfGeoreferenceCodec.encode(it.copy(page = page.index)) },
                embeddedIssue = issue,
                geometry = geometry ?: page.geometry,
                // WP2 crash guard token, minted with the entry (M2)
                renderGuardToken = UUID.randomUUID().toString(),
            )
        }

        /** the picker badges: scanned pages that declared a georef we couldn't use */
        fun rejectedBadges(inspection: PdfInspection): List<Int> =
            inspection.pages.take(ImportLimits.GEOREF_SCAN_PAGES).filter { it.state is com.tacmap.calibration.PageGeorefState.Rejected }.map { it.index }

        fun mbtilesEntry(prepared: PreparedMbtilesImport, filesDir: File, nowMs: Long): ImportedMapEntry? {
            val rel = ImportedMapLibraryStore.relativeName(filesDir, prepared.file) ?: return null
            return ImportedMapEntry(
                id = UUID.randomUUID().toString(),
                kind = ImportedMapKind.MBTILES.code,
                fileName = rel,
                displayName = prepared.displayName,
                contentKey = prepared.contentKey,
                byteCount = prepared.file.length(),
                fileModifiedAtMs = prepared.file.lastModified(),
                importedAtMs = nowMs,
            )
        }
    }
}

/**
 * s9.8 markers this process set, memory only. The marker lives in the sealed journal, so once
 * a pause relocks the key mid parse its clear can't be written and it's left on disk although
 * nothing crashed (DL-1). Another journal instance may also have read it before the clear
 * landed. So: [running] while a parse is going, [returned] once one came back in this
 * process. A marker for a returned op is ours, not a crash. A real crash takes this memory
 * with it, so the next launch still reads the marker as one and the loop breaker holds
 */
internal object InspectionMarks {
    private val running = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val returned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    /** one run of an operation at a time in this process */
    fun lockFor(operationKey: String): kotlinx.coroutines.sync.Mutex =
        locks.getOrPut(operationKey) { kotlinx.coroutines.sync.Mutex() }

    /** set ([at]) or clear (null) the durable marker, and keep track of it here */
    fun mark(journal: DocumentImportCopyStateStore, operationKey: String, at: Long?, now: Long) {
        if (at != null) running.merge(operationKey, 1, Int::plus)
        runCatching {
            journal.state(operationKey)?.let { journal.persist(it.copy(inspectStartedAtEpochMs = at, updatedAtEpochMs = now)) }
        }
        if (at == null) {
            returned += operationKey
            running.computeIfPresent(operationKey) { _, n -> (n - 1).takeIf { it > 0 } }
        }
    }

    /** this process set the op's marker and is still alive: a parse running or one that came back */
    fun ranHere(operationKey: String): Boolean = running.containsKey(operationKey) || operationKey in returned

    /**
     * true = [st]'s marker is this process's, not a crash. Once nothing's parsing it any more
     * the clear it may still owe is tried again (best effort, the next look tries again).
     * never cleared while one's running, that one's crash protection still counts
     */
    fun clearStale(journal: DocumentImportCopyStateStore, st: DocumentImportCopyState, now: Long = System.currentTimeMillis()): Boolean {
        if (!ranHere(st.operationKey)) return false
        if (!running.containsKey(st.operationKey) && st.inspectStartedAtEpochMs != null) {
            runCatching { journal.persist(st.copy(inspectStartedAtEpochMs = null, updatedAtEpochMs = now)) }
        }
        return true
    }

    /** a new process as far as this goes, for tests */
    fun forgetForTesting() {
        running.clear()
        returned.clear()
    }
}

/** the copy's gone and nothing's holding it in flight any more */
internal fun discardImportCopy(file: File) {
    runCatching { file.delete() }
    InFlightImportFiles.release(file)
}

/**
 * E2: Cancel can land between stages, the copy is dropped and nothing gets written.
 * Throws the cancellation on so the caller's toast path runs as for any other cancel
 */
internal suspend fun recheckCancel(file: File, isCancelled: (() -> Boolean)? = null) {
    val cancelled = isCancelled?.invoke() ?: !currentCoroutineContext().isActive
    if (!cancelled) return
    discardImportCopy(file)
    throw CancellationException("import cancelled")
}

/**
 * Everything a PDF import does after the copy + hash (contract s9.4-s9.6, E5 order):
 * dedupe, crash marker, the one inspection under the 30 s watchdog, the page decision.
 * No Context in here so the JVM tests run the real thing. [onStage] reports each step.
 */
internal class PdfImportStages(
    private val journal: DocumentImportCopyStateStore,
    private val inspect: (File, () -> Boolean, (Int, Int) -> Unit) -> InspectionResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStage: (ImportStage) -> Unit = {},
    /** null = the coroutine's own cancel state, tests hand in a flag */
    private val isCancelled: (() -> Boolean)? = null,
) {
    suspend fun afterCopy(
        file: File,
        key: String,
        name: String,
        operationKey: String,
        library: LibrarySnapshot,
        progress: (ImportProgress) -> Unit,
    ): PreparedOutcome {
        // cancelled while the last bytes went in: copy goes, nothing else happens
        recheckCancel(file, isCancelled)
        onStage(ImportStage.DEDUPE)
        library.byContentKey(key)?.let { existing ->
            // E9: an unavailable entry keeps the new copy, it's the same bytes it was made from
            val unavailable = library.isUnavailable(existing)
            if (!unavailable) discardImportCopy(file)
            val outcome = ImportDecision.decide(
                1, null, DuplicateInfo(MapImportPipeline.hasGeoref(existing), existing.displayName, unavailable),
            )
            return PreparedOutcome.Pdf(PreparedPdfImport(file, key, existing.displayName, PdfInspection(0, emptyList()), outcome, existing))
        }
        // s9.8: durable marker before PDFBox / pdfium get the untrusted bytes
        onStage(ImportStage.INSPECT)
        markInspecting(operationKey, clock())
        val inspected = try {
            runWatchdog(file, progress)
        } catch (c: CancellationException) {
            discardImportCopy(file)
            markInspecting(operationKey, null)
            throw c
        }
        markInspecting(operationKey, null)
        recheckCancel(file, isCancelled)
        val result = inspected ?: run {
            discardImportCopy(file)
            return PreparedOutcome.Failed(ImportFailure(ImportError.TOO_COMPLEX))
        }
        return when (result) {
            is InspectionResult.Failed -> {
                discardImportCopy(file)
                PreparedOutcome.Failed(result.failure)
            }
            is InspectionResult.Ok -> {
                onStage(ImportStage.DECISION)
                val i = result.inspection
                PreparedOutcome.Pdf(PreparedPdfImport(file, key, name, i, ImportDecision.decide(i.pageCount, i.scanned, null)))
            }
        }
    }

    suspend fun runWatchdog(file: File, progress: (ImportProgress) -> Unit): InspectionResult? {
        val done = CompletableDeferred<InspectionResult>()
        val stop = AtomicBoolean(false)
        // CGPDF/PDFBox can't be interrupted; on a timeout this thread is abandoned, the
        // copy goes and the user gets tooComplex. The journal marker covers a native crash
        Thread({
            try {
                done.complete(inspect(file, { stop.get() }) { p, n -> progress(ImportProgress.Reading(p, n)) })
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }, "pdf-import-inspector").apply { isDaemon = true }.start()
        return try {
            withTimeoutOrNull(ImportLimits.PARSE_TIMEOUT_MS) { done.await() }.also { if (it == null) stop.set(true) }
        } catch (c: CancellationException) {
            stop.set(true)
            throw c
        } catch (_: InterruptedException) {
            null
        }
    }

    private fun markInspecting(operationKey: String, at: Long?) = InspectionMarks.mark(journal, operationKey, at, clock())
}
