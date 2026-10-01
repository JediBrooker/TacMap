package com.tacmap.calibration

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID

/** the legacy side of a restore, the libraryLoad fixture's `legacy` column */
internal enum class LegacyLibraryState(val code: String) {
    NONE("none"),
    READABLE("readable"),
    LOCKED("locked"),
    CORRUPT("corrupt"),
    PDF_UNCONVERTIBLE("pdfUnconvertible"),
    NAMES_NOTHING("namesNothing"),
}

internal enum class RestoreStatus(val code: String) {
    LOADED("loaded"), EMPTY("empty"), LOCKED("locked"), CORRUPT("corrupt"), MIGRATION_PENDING("migrationPending"),
}

internal enum class RestoreMigration(val code: String) {
    NONE("none"), RUN("run"), BLOCKED("blocked"), WRITE_EMPTY_AND_CLEAR("writeEmptyAndClear"),
}

internal enum class RestoreIssue(val code: String) { LOCKED_RETRY("lockedRetry"), CORRUPT_RETRY("corruptRetry") }

internal data class RestorePlan(
    val status: RestoreStatus,
    val migration: RestoreMigration,
    /** reconcile, the bake sweep and the draft prune may run. only off a real read */
    val authoritative: Boolean,
    val clearLegacy: Boolean,
    val issue: RestoreIssue?,
)

/**
 * Contract s8.2 r1 load + recovery (import_limits.json libraryLoad), pure so the fixture
 * rows run on the JVM against the same function MapViewModel restores with.
 */
internal object LibraryRestoreRules {
    fun plan(load: LibraryLoad, legacy: LegacyLibraryState): RestorePlan = when (load) {
        // D8: legacy prefs still hanging round after a Loaded restore just go
        is LibraryLoad.Loaded -> RestorePlan(RestoreStatus.LOADED, RestoreMigration.NONE, load.state.permitsCleanup, legacy != LegacyLibraryState.NONE, null)
        LibraryLoad.Locked -> RestorePlan(RestoreStatus.LOCKED, RestoreMigration.NONE, false, false, RestoreIssue.LOCKED_RETRY)
        LibraryLoad.Corrupt -> RestorePlan(RestoreStatus.CORRUPT, RestoreMigration.NONE, false, false, RestoreIssue.CORRUPT_RETRY)
        LibraryLoad.Empty -> when (legacy) {
            LegacyLibraryState.NONE -> RestorePlan(RestoreStatus.EMPTY, RestoreMigration.NONE, true, false, null)
            LegacyLibraryState.READABLE -> RestorePlan(RestoreStatus.LOADED, RestoreMigration.RUN, true, true, null)
            // S4: an authenticated read that names nothing still on disk: write empty, clear
            LegacyLibraryState.NAMES_NOTHING ->
                RestorePlan(RestoreStatus.LOADED, RestoreMigration.WRITE_EMPTY_AND_CLEAR, true, true, null)
            // fail closed, nothing written cleared or deleted (S1, S3)
            LegacyLibraryState.LOCKED, LegacyLibraryState.CORRUPT, LegacyLibraryState.PDF_UNCONVERTIBLE ->
                RestorePlan(RestoreStatus.MIGRATION_PENDING, RestoreMigration.BLOCKED, false, false, RestoreIssue.LOCKED_RETRY)
        }
    }

    /** the reader's answer as a fixture state. Unavailable covers locked, quarantined and unconvertible */
    fun legacyState(read: LegacyMapReader.Read): LegacyLibraryState = when (read) {
        is LegacyMapReader.Read.Present -> LegacyLibraryState.READABLE
        LegacyMapReader.Read.Absent -> LegacyLibraryState.NAMES_NOTHING
        LegacyMapReader.Read.Unavailable -> LegacyLibraryState.LOCKED
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
