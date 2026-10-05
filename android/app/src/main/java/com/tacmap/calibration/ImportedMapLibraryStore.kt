package com.tacmap.calibration

import com.tacmap.util.SafeStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

internal sealed class LibraryLoad {
    data class Loaded(val state: LibraryState) : LibraryLoad()
    data object Empty : LibraryLoad()
    data object Locked : LibraryLoad()
    data object Corrupt : LibraryLoad()
}

/** what a guarded library write got */
internal sealed class LibraryCommit {
    /** on disk, with its new generation */
    data class Written(val state: LibraryState) : LibraryCommit()
    /** the sealed library isn't what the write was built on (or can't be read): nothing written, [current] is what's there */
    data class Stale(val current: LibraryLoad) : LibraryCommit()
    /** the write itself failed, nothing changed */
    data object Failed : LibraryCommit()
}

/**
 * filesDir/imported_map_library.json, sealed (SafeStore). Contract s8.2: the one
 * authority for the active selection and every imported map. Synchronous on
 * purpose, each transition is one small write on a user action.
 */
internal class ImportedMapLibraryStore(private val filesDir: File) {
    private val file = File(filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): LibraryLoad = when (val r = SafeStore.readOrQuarantine(file, LABEL) { raw ->
        val obj = json.parseToJsonElement(raw) as? JsonObject ?: error("library isn't an object")
        val version = obj["schemaVersion"]?.jsonPrimitive
        // a newer schema we can't read is corrupt from where we sit, never "empty"
        require(version != null && !version.isString && version.intOrNull == LibraryState.SCHEMA_VERSION) {
            "unsupported library schema"
        }
        json.decodeFromString<LibraryState>(raw)
    }) {
        is SafeStore.LoadResult.Loaded -> {
            newestGeneration.accumulateAndGet(r.value.generation) { a, b -> maxOf(a, b) }
            LibraryLoad.Loaded(r.value)
        }
        // gone is only empty if we never wrote one. a quarantined copy next to it, or our own
        // record that it was written, means it vanished: corrupt, never "start fresh" (S1)
        SafeStore.LoadResult.Empty -> when {
            hasQuarantine() -> LibraryLoad.Corrupt
            else -> when (writtenBefore()) {
                true -> LibraryLoad.Corrupt
                false -> LibraryLoad.Empty
                null -> LibraryLoad.Locked
            }
        }
        is SafeStore.LoadResult.Corrupt -> {
            QuietLog.w(TAG, "Imported map library failed to open, quarantined")
            LibraryLoad.Corrupt
        }
        is SafeStore.LoadResult.Locked -> LibraryLoad.Locked
    }

    @Synchronized
    fun write(state: LibraryState): Boolean = runCatching {
        SafeStore.writeAtomically(file, LABEL, json.encodeToString(state.copy(schemaVersion = LibraryState.SCHEMA_VERSION)))
    }.onFailure { QuietLog.w(TAG, "Imported map library write failed") }.isSuccess

    /** load() under the managed files lock, so it can't land in the middle of someone's commit */
    fun loadCurrent(): LibraryLoad = ActiveMapSelectionStore.withManagedFilesLock { loadOrLocked() }

    /** a key store hiccup reads as locked here, the old write path swallowed those too. nothing goes down on it */
    private fun loadOrLocked(): LibraryLoad = runCatching { load() }.getOrElse { LibraryLoad.Locked }

    /**
     * How every transition writes. Under the managed files lock the sealed library is read
     * again and [next] only goes down if that's still the generation [next] was reduced from.
     * Anyone holding an older copy (a second MainActivity's view model, a bake that outlived
     * its screen) gets Stale and has to rebase, so it can never put an older library back
     * over newer entries or calibrations. The unwritten first launch library is generation 0
     */
    fun commit(next: LibraryState): LibraryCommit = ActiveMapSelectionStore.withManagedFilesLock {
        when (val current = loadOrLocked()) {
            is LibraryLoad.Loaded ->
                if (current.state.generation == next.generation) writeNext(next) else LibraryCommit.Stale(current)
            LibraryLoad.Empty -> if (next.generation == 0L) writeNext(next) else LibraryCommit.Stale(current)
            else -> LibraryCommit.Stale(current)
        }
    }

    /**
     * First write of a library that doesn't load: the migration, the S4 empty library, the S2
     * rebuild. Never over one that does, whoever got there first stands and this says Stale
     */
    fun create(state: LibraryState): LibraryCommit = ActiveMapSelectionStore.withManagedFilesLock {
        when (val current = loadOrLocked()) {
            is LibraryLoad.Loaded, LibraryLoad.Locked -> LibraryCommit.Stale(current)
            else -> writeNext(state)
        }
    }

    /** above every generation this process has seen, so a re-created library never matches an old copy */
    private fun writeNext(state: LibraryState): LibraryCommit {
        val written = state.copy(generation = newestGeneration.updateAndGet { maxOf(it, state.generation) + 1 })
        return if (write(written)) LibraryCommit.Written(written) else LibraryCommit.Failed
    }

    fun exists(): Boolean = file.exists()

    private fun hasQuarantine(): Boolean =
        filesDir.listFiles()?.any { it.name.startsWith("$FILE_NAME.corrupt-") } == true

    /**
     * did this device ever write the library? the sealed-only marker next to it says so without
     * the key, the authenticated ledger in the DataKey sentinel says so even if someone deleted
     * the marker too. null = can't tell right now (key locked), the caller treats that as locked
     */
    private fun writtenBefore(): Boolean? {
        if (SafeStore.wasWritten(file)) return true
        return runCatching { SafeStore.isSealedOnlyAuthenticated(LABEL) }.getOrNull()
    }

    /** the entry's file, only if its opaque relative name stays inside one of our map dirs */
    fun fileOf(entry: ImportedMapEntry): File? = resolveManaged(filesDir, entry.fileName)

    fun fileStatus(entry: ImportedMapEntry): EntryFileStatus {
        val f = fileOf(entry) ?: return EntryFileStatus.MISSING
        if (!f.isFile) return EntryFileStatus.MISSING
        if (f.length() != entry.byteCount || f.lastModified() != entry.fileModifiedAtMs) return EntryFileStatus.MISMATCH
        return EntryFileStatus.OK
    }

    /**
     * every entry's file + the sqlite sidecars of the MBTiles ones, plus each PDF's valid
     * bake in offline_tiles with its sidecars (M5). reconcile keeps these
     */
    fun managedFiles(state: LibraryState): Set<File> = buildSet {
        for (e in state.entries) {
            val f = fileOf(e) ?: continue
            add(f)
            if (e.isMbtiles) SQLITE_SIDECARS.forEach { add(File(f.parentFile, f.name + it)) }
        }
        for (name in state.bakeFileNames) {
            val bake = bakeFile(name) ?: continue
            add(bake)
            SQLITE_SIDECARS.forEach { add(File(bake.parentFile, bake.name + it)) }
        }
    }

    /** offline_tiles/<name> for a bake record's file name, nothing for a name that isn't plain */
    fun bakeFile(name: String): File? = resolveManaged(filesDir, "offline_tiles/$name")

    companion object {
        const val FILE_NAME = "imported_map_library.json"
        const val LABEL = "map_library/v1"
        private const val TAG = "ImportedMapLibrary"
        val MANAGED_DIRECTORIES = listOf("pdf_maps", "mbtiles", "offline_tiles")
        val SQLITE_SIDECARS = listOf("-wal", "-shm", "-journal")

        /** highest library generation this process has read or written, one library per app */
        private val newestGeneration = java.util.concurrent.atomic.AtomicLong(0)

        /** opaque relative path -> file under filesDir/<pdf_maps|mbtiles|offline_tiles>/, no escapes */
        fun resolveManaged(filesDir: File, relative: String): File? {
            if (relative.isBlank() || relative.startsWith("/") || '\\' in relative) return null
            val parts = relative.split('/')
            if (parts.size != 2 || parts[0] !in MANAGED_DIRECTORIES) return null
            val name = parts[1]
            if (name.isEmpty() || name == "." || name == ".." || !(name.endsWith(".pdf") || name.endsWith(".mbtiles"))) return null
            val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
            val candidate = runCatching { File(File(root, parts[0]), name).canonicalFile }.getOrNull() ?: return null
            if (candidate.parentFile?.parentFile != root) return null
            return candidate
        }

        /** the inverse: filesDir-relative name for a file we put in one of the map dirs */
        fun relativeName(filesDir: File, file: File): String? {
            val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
            val f = runCatching { file.canonicalFile }.getOrNull() ?: return null
            val dir = f.parentFile ?: return null
            if (dir.parentFile != root || dir.name !in MANAGED_DIRECTORIES) return null
            return "${dir.name}/${f.name}"
        }
    }
}
