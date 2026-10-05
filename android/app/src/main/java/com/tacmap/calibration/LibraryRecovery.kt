package com.tacmap.calibration

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** the legacy side of a restore, the libraryLoad fixture's legacy column (legacyCodes) */
internal enum class LegacyLibraryState(val code: String) {
    NONE("none"),
    READABLE("readable"),
    LOCKED("locked"),
    NAMES_NOTHING("namesNothing"),
    // 3.0.1: the read authenticated but something in the old stores won't read or convert.
    // these salvage now, they used to block forever (or read as none on the next pass)
    CORRUPT("corrupt"),
    QUARANTINED_ONLY("quarantinedOnly"),
    RETAINED_CORRUPT("retainedCorrupt"),
    RETAINED_QUARANTINED_ONLY("retainedQuarantinedOnly"),
    SESSION_INVALID("sessionInvalid"),
    PDF_HASH_MISMATCH("pdfHashMismatch"),
    PDF_UNCONVERTIBLE("pdfUnconvertible"),
    ;

    /** a live store D8 could clear. only a .corrupt copy left isn't one, nothing ever clears that (L2) */
    val clearable: Boolean get() = this != NONE && this != QUARANTINED_ONLY && this != RETAINED_QUARANTINED_ONLY
}

/** how this pass's writes went, the fixture's given.writes */
internal enum class LibraryWrites(val code: String) { OK("ok"), DRAFT_FAILS("draftFails"), LIBRARY_FAILS("libraryFails") }

internal enum class RestoreStatus(val code: String) {
    LOADED("loaded"), EMPTY("empty"), LOCKED("locked"), CORRUPT("corrupt"), MIGRATION_PENDING("migrationPending"),
}

internal enum class RestoreMigration(val code: String) {
    NONE("none"), RUN("run"), BLOCKED("blocked"), WRITE_EMPTY_AND_CLEAR("writeEmptyAndClear"),
    SALVAGE("salvage"), ADOPT_ORPHANS("adoptOrphans"),
}

internal enum class RestoreIssue(val code: String) { LOCKED_RETRY("lockedRetry"), CORRUPT_RETRY("corruptRetry") }

internal enum class RestoreNotice(val code: String) { RECOVERED("recovered") }

internal data class RestorePlan(
    val status: RestoreStatus,
    val migration: RestoreMigration,
    /** reconcile, the bake sweep and the draft prune may run. only off a real read */
    val authoritative: Boolean,
    val clearLegacy: Boolean,
    val issue: RestoreIssue?,
    /** map_library_recovered_notice, once, after a salvage or an orphan adoption */
    val notice: RestoreNotice? = null,
    /** the usable library's sealed flag, null when there's no usable library */
    val recoveryPreservesOrphans: Boolean? = null,
) {
    /** the s9.2 import pre-check and every library transition need this */
    val usable: Boolean get() = status == RestoreStatus.LOADED || status == RestoreStatus.EMPTY
}

/**
 * Contract s8.2 r1 + s13.1 load + recovery (import_limits.json libraryLoad), pure so the
 * fixture rows run on the JVM against the same function MapViewModel restores with.
 */
internal object LibraryRestoreRules {
    private val PENDING = RestorePlan(RestoreStatus.MIGRATION_PENDING, RestoreMigration.BLOCKED, false, false, RestoreIssue.LOCKED_RETRY)

    /**
     * One launch / unlock / Retry restore, the generator's restore_plan. [managedFiles] = map
     * files sit in the managed dirs that nothing's writing, [writes] = how the pass's drafts and
     * library write went (OK until they've been tried)
     */
    fun plan(
        load: LibraryLoad,
        legacy: LegacyLibraryState,
        managedFiles: Boolean = false,
        writes: LibraryWrites = LibraryWrites.OK,
    ): RestorePlan {
        // drafts first, then the one library write. either failing writes nothing that counts
        // and clears nothing, so it's pending with Retry (L4, L6)
        fun written(migration: RestoreMigration, clear: Boolean, recovered: Boolean, drafts: Boolean): RestorePlan =
            if (writes == LibraryWrites.LIBRARY_FAILS || (writes == LibraryWrites.DRAFT_FAILS && drafts)) PENDING
            else RestorePlan(RestoreStatus.LOADED, migration, !recovered, clear, null, RestoreNotice.RECOVERED.takeIf { recovered }, recovered)
        return when (load) {
            // D8 only off a library that may clean up, a salvaged or rebuilt one keeps the old stores frozen (L8)
            is LibraryLoad.Loaded -> {
                val cleanup = load.state.permitsCleanup
                RestorePlan(RestoreStatus.LOADED, RestoreMigration.NONE, cleanup, cleanup && legacy.clearable, null, null, !cleanup)
            }
            LibraryLoad.Locked -> RestorePlan(RestoreStatus.LOCKED, RestoreMigration.NONE, false, false, RestoreIssue.LOCKED_RETRY)
            LibraryLoad.Corrupt -> RestorePlan(RestoreStatus.CORRUPT, RestoreMigration.NONE, false, false, RestoreIssue.CORRUPT_RETRY)
            // s14.3 ledger only: a first write that died before its rename. old stores still waiting
            // means it was the migration's, so salvage (never a run, deleting file + marker leaves
            // the same state). nothing to go on without them, corrupt as before
            LibraryLoad.Unfinished -> when (legacy) {
                LegacyLibraryState.NONE -> plan(LibraryLoad.Corrupt, legacy)
                LegacyLibraryState.LOCKED -> PENDING
                else -> written(RestoreMigration.SALVAGE, clear = false, recovered = true, drafts = legacy != LegacyLibraryState.NAMES_NOTHING)
            }
            LibraryLoad.Empty -> when (legacy) {
                // map files with no library and nothing to migrate get adopted, never reconciled
                // away. only an Empty with nothing in the dirs is authoritative (L7)
                LegacyLibraryState.NONE ->
                    if (managedFiles) written(RestoreMigration.ADOPT_ORPHANS, clear = false, recovered = true, drafts = false)
                    else RestorePlan(RestoreStatus.EMPTY, RestoreMigration.NONE, true, false, null, null, false)
                // no key, nothing's known: nothing written cleared or deleted, Retry and unlock re-run it
                LegacyLibraryState.LOCKED -> PENDING
                LegacyLibraryState.READABLE -> written(RestoreMigration.RUN, clear = true, recovered = false, drafts = true)
                // S4: an authenticated read that names nothing still on disk: write empty, clear
                LegacyLibraryState.NAMES_NOTHING ->
                    written(RestoreMigration.WRITE_EMPTY_AND_CLEAR, clear = true, recovered = false, drafts = false)
                // L5 salvage: what converts, every other map file adopted, the old stores frozen
                else -> written(RestoreMigration.SALVAGE, clear = false, recovered = true, drafts = true)
            }
        }
    }

    /** pending with Retry, for an Empty that still has something to migrate or adopt */
    fun pending(): RestorePlan = PENDING

    /** the reader's answer as the fixture's legacy column. any uncertain cause makes the read uncertain */
    fun legacyState(read: LegacyMapReader.Read): LegacyLibraryState = when (read) {
        is LegacyMapReader.Read.Present -> LegacyLibraryState.READABLE
        LegacyMapReader.Read.Absent -> LegacyLibraryState.NAMES_NOTHING
        LegacyMapReader.Read.Locked -> LegacyLibraryState.LOCKED
        is LegacyMapReader.Read.Uncertain -> read.causes.first()
    }
}

internal enum class AutoResumeAction(val code: String) { RESUME("resume"), SKIP("skip"), DEFER_UNTIL_LOADED("deferUntilLoaded") }

internal enum class AutoResumeEntry { OK, UNAVAILABLE, MISSING }

internal data class AutoResumeDecision(
    val action: AutoResumeAction,
    /** true = the draft is (re)written active, false = left inactive, null = no draft */
    val draftActiveAfter: Boolean?,
    val frameSheet: Boolean,
    val reopenPending: Boolean,
    /** the calibration_resumed {points} toast, null = no toast */
    val toastPoints: Int?,
)

/** E3 at launch (contract s2.1 r1, import_limits.json lifecycle.autoResume) */
internal object AutoResumeRules {
    fun decide(
        draftActive: Boolean?,
        points: Int,
        pending: Boolean,
        entry: AutoResumeEntry,
        library: RestoreStatus,
        crashSuspectPending: Boolean,
    ): AutoResumeDecision {
        fun skip() = AutoResumeDecision(AutoResumeAction.SKIP, draftActive, false, false, null)
        if (draftActive == null) return skip()
        // locked: runs after the first Loaded restore (the unlock or Retry), same rules then (F3)
        if (library == RestoreStatus.LOCKED || library == RestoreStatus.MIGRATION_PENDING) {
            return AutoResumeDecision(AutoResumeAction.DEFER_UNTIL_LOADED, draftActive, false, false, null)
        }
        if (library != RestoreStatus.LOADED && library != RestoreStatus.EMPTY) return skip()
        if (!draftActive) return skip()
        // M10/C2/C8: any crash suspect pending, the draft stays active for next time
        if (crashSuspectPending) return skip()
        // start preconditions: the entry and its file. the draft stays, reconcile prunes it if it has to
        if (entry != AutoResumeEntry.OK) return skip()
        return AutoResumeDecision(
            action = AutoResumeAction.RESUME,
            draftActiveAfter = true,
            frameSheet = true,
            reopenPending = pending,
            toastPoints = points.takeIf { it > 0 },
        )
    }
}

internal enum class MismatchAction {
    /** the PDF's up: fresh source, the session fails it cannotOpen (Try Again rechecks) */
    RECHECK_RENDER,
    /** a PDF that isn't up (held back by the crash guard): just remember it, never go online durably */
    MARK_ONLY,
    /** an MBTiles pack that's still the active map: online, durably, and say why */
    ONLINE_AND_ALERT,
    ALERT,
}

/** the once-per-launch background sha256 came back different (s8.2 restore, M6/M8, D6) */
internal object BackgroundHashRules {
    fun onMismatch(isPdf: Boolean, shownIsEntry: Boolean, stillActive: Boolean): MismatchAction = when {
        isPdf && shownIsEntry -> MismatchAction.RECHECK_RENDER
        isPdf -> MismatchAction.MARK_ONLY
        stillActive -> MismatchAction.ONLINE_AND_ALERT
        else -> MismatchAction.ALERT
    }
}

/**
 * S2: Retry on a corrupt library rebuilds it from the map files still on disk, it never
 * deletes one. Each opaque map file nobody's importing gets an entry back: re-hashed, a
 * neutral name, a PDF re-inspected (marker + watchdog) and put on its first valid page.
 * One that won't inspect comes back unavailable (Delete only). Bakes aren't adopted, the
 * sealed recovery flag protects them and unmatched drafts on later restores too.
 */
internal object LibraryRebuild {
    /** the crash loop breaker for the rebuild's own parse, holds just the opaque relative name */
    const val MARKER_NAME = ".library-rebuild-inspecting"

    data class Candidate(val file: File, val relativeName: String, val kind: ImportedMapKind)

    /** what one PDF parse came back with. null from the callback = watchdog fired */
    fun interface PdfInspect {
        fun inspect(file: File): InspectionResult?
    }

    /**
     * s13.1 managedFiles: a regular file in the map dirs the reconcile or the bake sweep would
     * delete (map file, sidecar, .partial, bake) that isn't in [skip] (in flight, or a stuck
     * s9.8 copy the launch sweep deletes anyway). With one there an Empty library adopts
     */
    fun hasManagedFiles(filesDir: File, skip: Set<File>): Boolean {
        val skipped = skip.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toSet()
        return ImportedMapLibraryStore.MANAGED_DIRECTORIES.any { dirName ->
            File(filesDir, dirName).listFiles().orEmpty().any { f ->
                ManagedImportedMapFileLifecycle.isManagedCandidateName(f.name) &&
                    Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                    runCatching { f.canonicalFile }.getOrNull()?.let { it !in skipped } == true
            }
        }
    }

    /** every opaque map file no in-flight import owns, bakes left out, oldest first */
    fun candidates(filesDir: File, inFlight: Set<File> = InFlightImportFiles.snapshot()): List<Candidate> {
        val owned = inFlight.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.toSet()
        val out = ArrayList<Candidate>()
        for (dirName in ImportedMapLibraryStore.MANAGED_DIRECTORIES) {
            val files = File(filesDir, dirName).listFiles().orEmpty()
            for (f in files) {
                val name = f.name
                if (name.startsWith(".")) continue
                if (!Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS)) continue
                if (ManagedImportedMapFileLifecycle.isGeneratedBakeName(name)) continue
                val kind = when {
                    name.endsWith(".pdf") && dirName != "offline_tiles" -> ImportedMapKind.PDF
                    name.endsWith(".mbtiles") -> ImportedMapKind.MBTILES
                    else -> continue
                }
                val canonical = runCatching { f.canonicalFile }.getOrNull() ?: continue
                if (canonical in owned) continue
                val rel = ImportedMapLibraryStore.relativeName(filesDir, f) ?: continue
                out += Candidate(canonical, rel, kind)
            }
        }
        return out.sortedWith(compareBy<Candidate>({ it.file.lastModified() }, { it.relativeName }))
    }

    /**
     * The rebuilt library, nothing written yet (the caller does the one write). Slow: hashes
     * every file and parses every PDF, worker thread only.
     */
    fun rebuild(
        filesDir: File,
        defaultStyle: String,
        nowMs: Long,
        recoveredName: (Int) -> String,
        inspectPdf: PdfInspect,
        validateMbtiles: (File) -> Boolean,
        hash: (File) -> String? = PdfCalibrationIdentity::contentKey,
        newId: () -> String = { UUID.randomUUID().toString() },
        candidates: List<Candidate> = candidates(filesDir),
    ): LibraryState {
        val marker = File(filesDir, MARKER_NAME)
        // a parse that took the process down last Retry doesn't get another go, that file
        // comes back unavailable instead of looping
        val crashedOn = runCatching { marker.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
        val entries = candidates.mapIndexed { i, c ->
            val key = hash(c.file)
            val base = ImportedMapEntry(
                id = newId(),
                kind = c.kind.code,
                fileName = c.relativeName,
                displayName = recoveredName(i + 1),
                contentKey = key,
                byteCount = c.file.length(),
                fileModifiedAtMs = c.file.lastModified(),
                importedAtMs = nowMs,
            )
            when (c.kind) {
                ImportedMapKind.PDF -> {
                    val info = if (key == null || c.relativeName == crashedOn) null else inspected(marker, c, inspectPdf)
                    base.copy(pdf = info ?: uninspectable())
                }
                // an MBTiles that won't open stays listed but can't match its stamp: unavailable, Delete only
                ImportedMapKind.MBTILES -> if (runCatching { validateMbtiles(c.file) }.getOrDefault(false)) base else base.copy(byteCount = -1)
            }
        }
        runCatching { marker.delete() }
        return LibraryState(
            active = ActiveRef.online(defaultStyle),
            preferredOnlineStyle = defaultStyle,
            entries = entries,
            recoveryPreservesOrphans = true,
        )
    }

    private fun inspected(marker: File, c: Candidate, inspect: PdfInspect): PdfEntryInfo? {
        writeMarker(marker, c.relativeName)
        val result = try {
            inspect.inspect(c.file)
        } catch (e: Exception) {
            null
        }
        val ok = (result as? InspectionResult.Ok)?.inspection ?: return null
        if (ok.pageCount <= 0) return null
        // s9.6 first valid page, no picker here: anything else is page 1
        val pageIndex = when (val o = ImportDecision.decide(ok.pageCount, ok.scanned, null)) {
            is ImportOutcome.AddAndActivate -> o.page
            is ImportOutcome.AddRejected -> o.page
            is ImportOutcome.AddAndCalibrate -> o.page
            else -> 0
        }
        val page = ok.page(pageIndex) ?: return null
        return com.tacmap.map.MapImportPipeline.pdfInfo(ok.pageCount, page, null)
    }

    /** pageCount 0 = it wouldn't inspect, always unavailable (recovered_uninspectable_pdf) */
    fun uninspectable(): PdfEntryInfo = PdfEntryInfo(
        pageCount = 0,
        pageIndex = 0,
        rotate = 0,
        pageBox = List(4) { listOf(0.0, 0.0) },
        renderGuardToken = UUID.randomUUID().toString(),
    )

    /**
     * the s9.5 watchdog: PDFBox can't be interrupted, so the parse gets its own daemon thread
     * and is abandoned (stop flag set) after [timeoutMs]. null = timed out or blew up
     */
    fun withWatchdog(timeoutMs: Long = ImportLimits.PARSE_TIMEOUT_MS, parse: (stop: () -> Boolean) -> InspectionResult): InspectionResult? {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val result = java.util.concurrent.atomic.AtomicReference<InspectionResult?>(null)
        val done = java.util.concurrent.CountDownLatch(1)
        Thread({
            try {
                result.set(parse { stop.get() })
            } catch (t: Throwable) {
                // the inspector maps OOM / stack overflow itself, anything else is just a failed parse
            } finally {
                done.countDown()
            }
        }, "library-rebuild-inspector").apply { isDaemon = true }.start()
        val finished = try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!finished) stop.set(true)
        return if (finished) result.get() else null
    }

    private fun writeMarker(marker: File, relativeName: String) {
        runCatching {
            FileOutputStream(marker).use { out ->
                out.write(relativeName.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }
}
