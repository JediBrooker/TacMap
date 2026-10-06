package com.tacmap.calibration

import androidx.annotation.WorkerThread
import java.io.File
import java.util.UUID

/**
 * The legacy half of a library restore (s8.2 r1 + the 3.0.1 s13.1 amendment): whether an
 * Empty (or ledger-only, s14.3) library needs the migration hop, the hop itself (run, write
 * empty, salvage, adopt orphans: drafts first, then the one library write) and what the pass
 * comes to once the library's been read again. MapViewModel restores through this, the JVM
 * row tests run the same thing over real store files.
 *
 * The rule it keeps: only a locked key or a failed write leaves a restore pending, and no
 * Empty library is ever authoritative while there's a map file it could delete.
 */
internal class LegacyLibraryMigrator(
    private val filesDir: File,
    private val library: ImportedMapLibraryStore,
    private val drafts: CalibrationDraftStorage,
    private val legacy: LegacyMapReader,
    private val defaultStyle: String,
    private val recoveredName: (Int) -> String,
    private val inspectPdf: LibraryRebuild.PdfInspect,
    private val validateMbtiles: (File) -> Boolean,
    /** the copies a stuck s9.8 import marker names. the launch sweep deletes those, nobody adopts them */
    private val interruptedImports: () -> Set<File> = { emptySet() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    sealed class Outcome {
        /** this pass wrote the library */
        data class Written(
            val migration: RestoreMigration,
            /** run only: the active PDF that was never georeferenced, told once */
            val uncalibratedName: String?,
            val activeEntryId: String?,
        ) : Outcome()

        /** nothing written or cleared: the key's locked, or a draft or the library write failed. Retry */
        data class Blocked(val legacy: LegacyLibraryState, val managedFiles: Boolean, val writes: LibraryWrites) : Outcome()

        /** another screen got a library down first, that one stands */
        data object Superseded : Outcome()
    }

    /** never adopted or counted: an import or bake still writing, a stuck s9.8 copy */
    private fun skipped(): Set<File> =
        InFlightImportFiles.snapshot() + runCatching { interruptedImports() }.getOrDefault(emptySet())

    /** s13.1 managedFiles: something in the map dirs a reconcile or the bake sweep would delete */
    fun managedFiles(): Boolean = LibraryRebuild.hasManagedFiles(filesDir, skipped())

    /** cheap, main thread is fine: an Empty library has old stores to read or map files to adopt */
    fun isDue(): Boolean = legacy.hasLegacyState() || managedFiles()

    /**
     * whether the restore that read [load] needs the hop first. Unfinished only with old stores
     * waiting (s14.3), its map files alone never make it an orphan adoption
     */
    fun isDue(load: LibraryLoad): Boolean = when (load) {
        LibraryLoad.Empty -> isDue()
        LibraryLoad.Unfinished -> legacy.hasLegacyState()
        else -> false
    }

    /** the hop: reads, hashes and parses. null = nothing was due after all */
    @WorkerThread
    fun migrate(load: LibraryLoad = LibraryLoad.Empty): Outcome? = try {
        migrateOnce(salvageOnly = load == LibraryLoad.Unfinished)
    } catch (e: Exception) {
        QuietLog.w(TAG, "legacy map migration failed")
        Outcome.Blocked(LegacyLibraryState.LOCKED, managedFiles = true, writes = LibraryWrites.OK)
    }

    /** [salvageOnly]: the ledger-only library (s14.3). old stores that read fine still salvage, never run */
    private fun migrateOnce(salvageOnly: Boolean): Outcome? {
        val skip = skipped()
        val managed = LibraryRebuild.hasManagedFiles(filesDir, skip)
        if (!legacy.hasLegacyState()) return if (managed && !salvageOnly) adoptOrphans(skip) else null
        val read = legacy.read(defaultStyle)
        val built = when (read) {
            is LegacyMapReader.Read.Present -> build(read.inputs)
            is LegacyMapReader.Read.Uncertain -> build(read.inputs)
            else -> null
        }
        // read fine but won't convert (a file outside our dirs) is uncertain too, never dropped
        val state = if (read is LegacyMapReader.Read.Present && built?.unconverted?.isNotEmpty() == true) {
            LegacyLibraryState.PDF_UNCONVERTIBLE
        } else {
            LibraryRestoreRules.legacyState(read)
        }
        val load = if (salvageOnly) LibraryLoad.Unfinished else LibraryLoad.Empty
        return when (LibraryRestoreRules.plan(load, state, managed).migration) {
            RestoreMigration.RUN -> commit(RestoreMigration.RUN, state, managed, requireNotNull(built))
            RestoreMigration.WRITE_EMPTY_AND_CLEAR -> commit(RestoreMigration.WRITE_EMPTY_AND_CLEAR, state, managed, empty())
            // a ledger-only read that names nothing has nothing to convert, the rest still gets adopted
            RestoreMigration.SALVAGE -> commit(RestoreMigration.SALVAGE, state, managed, salvaged(built ?: empty(), skip))
            else -> Outcome.Blocked(state, managed, LibraryWrites.OK)
        }
    }

    private fun empty(): MigrationResult =
        MigrationResult(LibraryState(active = ActiveRef.online(defaultStyle), preferredOnlineStyle = defaultStyle), emptyList(), null)

    private fun build(inputs: LegacyMapInputs): MigrationResult =
        ImportedMapLibraryMigration.build(inputs, filesDir, nowMs(), newId)

    /**
     * L5: what converted, plus every other map file in our dirs adopted the S2 way (re-hashed,
     * PDFs re-inspected, Recovered map n). A file a converted entry holds isn't adopted twice.
     * The flag keeps every later reconcile, sweep and prune off this library
     */
    private fun salvaged(converted: MigrationResult, skip: Set<File>): MigrationResult {
        val taken = converted.state.entries.mapNotNull { e ->
            library.fileOf(e)?.let { runCatching { it.canonicalFile }.getOrNull() }
        }.toSet()
        val adopted = rebuild(LibraryRebuild.candidates(filesDir, skip).filter { it.file !in taken }).entries
        return converted.copy(
            state = converted.state.copy(entries = converted.state.entries + adopted, recoveryPreservesOrphans = true),
        )
    }

    /** L7: map files with no library and nothing to migrate are adopted, never reconciled away */
    private fun adoptOrphans(skip: Set<File>): Outcome =
        commit(
            RestoreMigration.ADOPT_ORPHANS, LegacyLibraryState.NONE, true,
            MigrationResult(rebuild(LibraryRebuild.candidates(filesDir, skip)), emptyList(), null),
        )

    private fun rebuild(candidates: List<LibraryRebuild.Candidate>): LibraryState = LibraryRebuild.rebuild(
        filesDir = filesDir,
        defaultStyle = defaultStyle,
        nowMs = nowMs(),
        recoveredName = recoveredName,
        inspectPdf = inspectPdf,
        validateMbtiles = validateMbtiles,
        newId = newId,
        candidates = candidates,
    )

    /**
     * L6: every draft durably saved first (a false or a throw aborts, nothing gets written),
     * then the one library write, then for run / write empty the old stores go. Drafts are
     * keyed contentKey#page so the next try just writes over them. A salvage or an adoption
     * clears nothing, its old stores stay frozen
     */
    private fun commit(migration: RestoreMigration, state: LegacyLibraryState, managed: Boolean, r: MigrationResult): Outcome {
        val saved = r.drafts.all { d -> runCatching { drafts.save(d) }.getOrDefault(false) }
        if (!saved) return Outcome.Blocked(state, managed, LibraryWrites.DRAFT_FAILS)
        return when (val c = library.create(r.state)) {
            is LibraryCommit.Written -> {
                val clears = migration == RestoreMigration.RUN || migration == RestoreMigration.WRITE_EMPTY_AND_CLEAR
                if (clears) legacy.clearAfterMigration()
                Outcome.Written(migration, r.uncalibratedActiveName.takeIf { clears }, c.state.active.entryId)
            }
            is LibraryCommit.Stale -> Outcome.Superseded
            LibraryCommit.Failed -> Outcome.Blocked(state, managed, LibraryWrites.LIBRARY_FAILS)
        }
    }

    /**
     * What the pass comes to, [load] being the library read again after the hop (or the first
     * read when nothing was due). D8 is done here when it applies, MapViewModel shows the rest
     */
    fun settle(load: LibraryLoad, migrated: Outcome?): RestorePlan {
        val plan = when {
            load is LibraryLoad.Loaded && migrated is Outcome.Written -> afterWrite(load.state, migrated.migration)
            load is LibraryLoad.Loaded -> LibraryRestoreRules.plan(load, legacy.loadedLegacyState())
            (load == LibraryLoad.Empty || load == LibraryLoad.Unfinished) && migrated is Outcome.Blocked ->
                LibraryRestoreRules.plan(load, migrated.legacy, migrated.managedFiles, migrated.writes)
            // only a genuine first launch stands in as empty, nothing to migrate and nothing in
            // the map dirs, so the cleanup it allows has nothing to delete
            load == LibraryLoad.Empty ->
                if (migrated == null && !isDue()) LibraryRestoreRules.plan(load, LegacyLibraryState.NONE)
                else LibraryRestoreRules.pending()
            // ledger only with no old store to salvage from is corrupt (s14.3)
            load == LibraryLoad.Unfinished ->
                if (migrated == null && !isDue(load)) LibraryRestoreRules.plan(load, LegacyLibraryState.NONE)
                else LibraryRestoreRules.pending()
            else -> LibraryRestoreRules.plan(load, LegacyLibraryState.NONE)
        }
        // D8: leftovers from an earlier migration go now the library's read back durable, only
        // off a library that may clean up. a salvaged or rebuilt one keeps them frozen (L8)
        if (plan.clearLegacy && load is LibraryLoad.Loaded && migrated !is Outcome.Written) legacy.clearAfterMigration()
        return plan
    }

    private fun afterWrite(state: LibraryState, migration: RestoreMigration): RestorePlan {
        val recovered = migration == RestoreMigration.SALVAGE || migration == RestoreMigration.ADOPT_ORPHANS
        return RestorePlan(
            status = RestoreStatus.LOADED,
            migration = migration,
            authoritative = state.permitsCleanup,
            clearLegacy = migration == RestoreMigration.RUN || migration == RestoreMigration.WRITE_EMPTY_AND_CLEAR,
            issue = null,
            notice = RestoreNotice.RECOVERED.takeIf { recovered },
            recoveryPreservesOrphans = !state.permitsCleanup,
        )
    }

    private companion object {
        const val TAG = "LegacyLibraryMigrator"
    }
}
