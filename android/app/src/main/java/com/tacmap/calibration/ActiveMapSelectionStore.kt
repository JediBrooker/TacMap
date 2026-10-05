package com.tacmap.calibration

import android.content.Context
import com.tacmap.util.SafeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val ACTIVE_MAP_SNAPSHOT_SCHEMA_VERSION = 2

/** Persist the non-sensitive migration sentinel before replacing the selector.
 * The marker has to be durable first: otherwise a successful selector write
 * followed by marker failure could return false after already changing the
 * active map. */
private fun persistActiveMapMigrationMarker(marker: File): Boolean {
    if (marker.isFile) return true
    if (marker.exists()) return false
    return runCatching {
        val parent = marker.parentFile ?: throw IOException("Migration marker has no parent")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("Could not create migration marker directory")
        }
        val temporary = File.createTempFile("${marker.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(byteArrayOf(1))
                output.flush()
                output.fd.sync()
            }
            val moved = runCatching {
                Files.move(
                    temporary.toPath(),
                    marker.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.isSuccess
            if (!moved && !marker.isFile) {
                throw IOException("Could not persist active-map migration marker")
            }
        } finally {
            temporary.delete()
        }
        marker.isFile
    }.getOrDefault(false)
}

/**
 * Encrypted pointer to the basemap that was active when the map screen closed.
 *
 * Imported filenames can identify an area of operations, so this is stored with
 * [SafeStore] rather than ordinary SharedPreferences. Offline paths are kept
 * relative to the app sandbox and are revalidated on restore; an absent,
 * moved, or traversal path is treated as stale and the caller falls back to
 * the preferred online basemap.
 */
class ActiveMapSelectionStore private constructor(
    private val filesDir: File,
    private val migrationMarkerWriter: (File) -> Boolean,
) :
    ActiveMapSelectionPersistence {

    constructor(context: Context) : this(
        context.applicationContext.filesDir,
        ::persistActiveMapMigrationMarker,
    )

    private val file = File(filesDir, FILE_NAME)
    private val retainedFile = File(filesDir, RETAINED_FILE_NAME)
    private val migrationMarker = File(filesDir, MIGRATION_MARKER)
    private val json = Json {
        ignoreUnknownKeys = true
        // schemaVersion is deliberately a default in memory, but it must never
        // be omitted from the durable envelope or a reader cannot distinguish
        // a supported snapshot from an unversioned future shape.
        encodeDefaults = true
    }

    internal fun loadState(): ActiveMapSelectionLoadState =
        when (val container = loadActiveContainer()) {
            is ActiveMapSelectionContainerLoad.Loaded -> container.state.active.asLoadState()
            ActiveMapSelectionContainerLoad.Missing -> ActiveMapSelectionLoadState.Missing
            is ActiveMapSelectionContainerLoad.Unavailable ->
                ActiveMapSelectionLoadState.Unavailable(container.reason)
        }

    internal fun loadRetainedImportedState(): ActiveMapSelectionLoadState =
        when (val container = loadActiveContainer()) {
            is ActiveMapSelectionContainerLoad.Loaded -> {
                if (container.isLegacy) loadRetainedFile()
                else container.state.retainedImported.asLoadState()
            }
            ActiveMapSelectionContainerLoad.Missing -> loadRetainedFile()
            is ActiveMapSelectionContainerLoad.Unavailable ->
                ActiveMapSelectionLoadState.Unavailable(container.reason)
        }

    /**
     * the pre-v2 retained file. gone with its .corrupt copy next to it means an earlier read
     * quarantined it, so it's corrupt, never "no retained map" (its pack would drop out of the
     * migration and get reconciled away)
     */
    private fun loadRetainedFile(): ActiveMapSelectionLoadState =
        when (val result = load(retainedFile, RETAINED_LABEL)) {
            ActiveMapSelectionLoadState.Missing ->
                if (hasQuarantinedRetainedSelection()) {
                    ActiveMapSelectionLoadState.Unavailable(ActiveMapSelectionFailure.CORRUPT)
                } else {
                    result
                }
            else -> result
        }

    /**
     * The active and retained selectors share one encrypted atomic snapshot.
     * Pre-v2 builds wrote [ActiveMapSelection] to [file] and the retained
     * selector to [retainedFile]; both are read during the first mutation and
     * folded into the v2 snapshot. Once a v2 snapshot exists, the legacy
     * retained file is deliberately ignored so a cleared map cannot reappear.
     */
    private fun loadActiveContainer(): ActiveMapSelectionContainerLoad =
        when (val result = SafeStore.readOrQuarantine(file, LABEL) { raw ->
            val element = json.parseToJsonElement(raw)
            val snapshotObject = element as? JsonObject
            val isSnapshot = snapshotObject != null &&
                ("schemaVersion" in snapshotObject ||
                    "active" in snapshotObject ||
                    "retainedImported" in snapshotObject)
            if (isSnapshot) {
                val schemaVersion = snapshotObject?.get("schemaVersion")?.jsonPrimitive
                require(
                    schemaVersion != null &&
                        !schemaVersion.isString &&
                        schemaVersion.intOrNull == ACTIVE_MAP_SNAPSHOT_SCHEMA_VERSION
                ) {
                    "Unsupported or missing active-map snapshot schemaVersion"
                }
                ActiveMapSelectionContainer(
                    state = json.decodeFromString<ActiveMapSelectionSnapshot>(raw),
                    isLegacy = false,
                )
            } else {
                ActiveMapSelectionContainer(
                    state = ActiveMapSelectionSnapshot(
                        active = json.decodeFromString<ActiveMapSelection>(raw),
                    ),
                    isLegacy = true,
                )
            }
        }) {
            is SafeStore.LoadResult.Loaded -> ActiveMapSelectionContainerLoad.Loaded(
                state = result.value.state,
                isLegacy = result.value.isLegacy,
            )
            SafeStore.LoadResult.Empty -> {
                if (hasQuarantinedActiveSelectionRecovery()) {
                    ActiveMapSelectionContainerLoad.Unavailable(
                        ActiveMapSelectionFailure.CORRUPT
                    )
                } else {
                    ActiveMapSelectionContainerLoad.Missing
                }
            }
            is SafeStore.LoadResult.Corrupt ->
                ActiveMapSelectionContainerLoad.Unavailable(ActiveMapSelectionFailure.CORRUPT)
            is SafeStore.LoadResult.Locked ->
                ActiveMapSelectionContainerLoad.Unavailable(ActiveMapSelectionFailure.LOCKED)
        }

    private fun load(source: File, label: String): ActiveMapSelectionLoadState =
        when (val result = SafeStore.readOrQuarantine(source, label) {
            json.decodeFromString<ActiveMapSelection>(it)
        }) {
            is SafeStore.LoadResult.Loaded -> ActiveMapSelectionLoadState.Loaded(result.value)
            SafeStore.LoadResult.Empty -> ActiveMapSelectionLoadState.Missing
            is SafeStore.LoadResult.Corrupt ->
                ActiveMapSelectionLoadState.Unavailable(ActiveMapSelectionFailure.CORRUPT)
            is SafeStore.LoadResult.Locked ->
                ActiveMapSelectionLoadState.Unavailable(ActiveMapSelectionFailure.LOCKED)
        }

    override fun saveOnline(style: BasemapStyle): Boolean =
        mutate { current ->
            current.copy(
                active = ActiveMapSelection(
                kind = ActiveMapKind.ONLINE,
                preferredOnlineStyle = style.name
                )
            )
        }

    fun savePdf(preferredOnlineStyle: BasemapStyle): Boolean =
        mutate { current ->
            current.copy(
                active = ActiveMapSelection(
                    kind = ActiveMapKind.PDF,
                    preferredOnlineStyle = preferredOnlineStyle.name,
                )
            )
        }

    fun saveOffline(path: String, preferredOnlineStyle: BasemapStyle): Boolean {
        val selection = offlineSelection(path, preferredOnlineStyle) ?: return false
        return mutate { current -> current.copy(active = selection) }
    }

    override fun saveActiveAndRetainedPdf(preferredOnlineStyle: BasemapStyle): Boolean {
        val selection = ActiveMapSelection(
            kind = ActiveMapKind.PDF,
            preferredOnlineStyle = preferredOnlineStyle.name,
        )
        return mutate { current ->
            current.copy(active = selection, retainedImported = selection)
        }
    }

    override fun saveActiveAndRetainedOffline(
        path: String,
        preferredOnlineStyle: BasemapStyle,
    ): Boolean {
        val selection = offlineSelection(path, preferredOnlineStyle) ?: return false
        return mutate { current ->
            current.copy(active = selection, retainedImported = selection)
        }
    }

    override fun saveOnlineAndClearRetained(style: BasemapStyle): Boolean =
        mutate { current ->
            current.copy(
                active = ActiveMapSelection(
                    kind = ActiveMapKind.ONLINE,
                    preferredOnlineStyle = style.name,
                ),
                retainedImported = null,
            )
        }

    fun saveRetainedPdf(preferredOnlineStyle: BasemapStyle): Boolean =
        mutate { current ->
            current.copy(
                retainedImported = ActiveMapSelection(
                    kind = ActiveMapKind.PDF,
                    preferredOnlineStyle = preferredOnlineStyle.name,
                )
            )
        }

    fun saveRetainedOffline(path: String, preferredOnlineStyle: BasemapStyle): Boolean {
        val selection = offlineSelection(path, preferredOnlineStyle) ?: return false
        return mutate { current -> current.copy(retainedImported = selection) }
    }

    fun clearRetainedImported(): Boolean =
        mutate { current -> current.copy(retainedImported = null) }

    /**
     * True exactly once for an install upgrading from the pre-selector build.
     *
     * That build unconditionally restored any persisted PDF on relaunch, even
     * when MBTiles files also remained in its library. Preserve that observable
     * behaviour on the first upgraded launch; the separate marker prevents a
     * later missing descriptor from inferring/resurrecting the PDF again.
     */
    internal fun legacyPdfMigrationPending(): Boolean = !migrationMarker.isFile

    /**
     * anything for the library migration to read at all. a quarantined selector counts too, on
     * every pass: the read that moved it aside doesn't make it "no legacy left" (s13.1 L1)
     */
    internal fun hasLegacyState(): Boolean =
        hasClearableLegacyState() ||
            hasQuarantinedActiveSelectionRecovery() ||
            hasQuarantinedRetainedSelection()

    /** the live selector files, all D8 may clear. a .corrupt copy is never cleared (L2) */
    internal fun hasClearableLegacyState(): Boolean = file.exists() || retainedFile.exists()

    /** before a read that might quarantine them: corrupt vs only the copy left */
    internal fun hasActiveSelectorFile(): Boolean = file.exists()
    internal fun hasRetainedSelectorFile(): Boolean = retainedFile.exists()

    /**
     * After the imported-map library has durably taken over (contract s8.2), the
     * old selector files go. The marker stays so an even older code path can never
     * resurrect a PDF from a missing selector.
     */
    @Synchronized
    internal fun clearAfterLibraryMigration(): Boolean {
        if (!migrationMarker.isFile) migrationMarkerWriter(migrationMarker)
        val a = !file.exists() || file.delete()
        val b = !retainedFile.exists() || retainedFile.delete()
        return a && b
    }

    /**
     * Whether cold-start orphan cleanup has an authenticated current-schema
     * selector as its authority. Missing, legacy, locked, and corrupt state
     * must first be resolved durably; none is safe evidence for deletion.
     */
    internal fun hasAuthenticatedCurrentSnapshot(): Boolean =
        (loadActiveContainer() as? ActiveMapSelectionContainerLoad.Loaded)
            ?.let { !it.isLegacy } == true

    /**
     * Remove plaintext imported-map bytes that are no longer reachable from
     * the authenticated v2 active+retained snapshot. PDF session metadata is
     * cleared before deleting a superseded PDF, so a partial cleanup can only
     * leave a harmless orphan for a later retry, never a descriptor pointing
     * at deleted bytes.
     */
    @Synchronized
    internal fun reconcileManagedImportedMapFiles(
        currentPdfFile: () -> File?,
        clearPdfSession: () -> Boolean,
        /**
         * The PDF's Generate Offline Tiles output, lives in offline_tiles next to imported
         * packs. No default on purpose: a caller that forgets it reaps the bake (D3)
         */
        currentPdfBakeFile: () -> File?,
    ): Boolean = withManagedFilesLock { reconcileLocked(currentPdfFile, clearPdfSession, currentPdfBakeFile) }

    private fun reconcileLocked(
        currentPdfFile: () -> File?,
        clearPdfSession: () -> Boolean,
        currentPdfBakeFile: () -> File?,
    ): Boolean {
        val container = loadActiveContainer() as? ActiveMapSelectionContainerLoad.Loaded
            ?: return false
        if (container.isLegacy) return false
        val active = container.state.active
        val retained = container.state.retainedImported
        val keep = when (retained?.kind) {
            ActiveMapKind.PDF -> {
                if (active?.kind == ActiveMapKind.OFFLINE_TILES) return false
                // the bake goes with the PDF. an in-progress bake sits in pdf_bake_work, never listed here
                setOfNotNull(currentPdfFile() ?: return false, currentPdfBakeFile())
            }
            ActiveMapKind.OFFLINE_TILES -> {
                if (active?.kind == ActiveMapKind.PDF ||
                    active?.kind == ActiveMapKind.OFFLINE_TILES && active != retained
                ) return false
                val file = offlineFile(retained) ?: return false
                if (!clearPdfSession()) return false
                setOf(file)
            }
            ActiveMapKind.ONLINE, null -> {
                if (active?.kind == ActiveMapKind.PDF ||
                    active?.kind == ActiveMapKind.OFFLINE_TILES
                ) return false
                if (!clearPdfSession()) return false
                emptySet()
            }
        }
        return ManagedImportedMapFileLifecycle.reconcile(
            managedParent = filesDir,
            directories = listOf(
                File(filesDir, "pdf_maps"),
                File(filesDir, "mbtiles"),
                File(filesDir, "offline_tiles"),
            ),
            keeping = keep,
        )
    }

    /**
     * Resolve an encrypted relative path without allowing it to escape the app
     * files directory. The file must still exist and be an MBTiles database.
     */
    internal fun offlineFile(selection: ActiveMapSelection): File? {
        if (selection.kind != ActiveMapKind.OFFLINE_TILES) return null
        val relative = selection.offlineRelativePath ?: return null
        val candidate = runCatching { File(filesDir, relative).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
        if (!candidate.path.startsWith(root.path + File.separator)) return null
        if (!candidate.isFile || !candidate.name.endsWith(".mbtiles", ignoreCase = true)) return null
        return candidate
    }

    private fun relativeOfflinePath(source: File): String? {
        val root = runCatching { filesDir.canonicalFile }.getOrNull() ?: return null
        val candidate = runCatching { source.canonicalFile }.getOrNull() ?: return null
        if (!candidate.path.startsWith(root.path + File.separator)) return null
        if (!candidate.isFile || !candidate.name.endsWith(".mbtiles", ignoreCase = true)) return null
        return candidate.relativeTo(root).invariantSeparatorsPath
    }

    private fun offlineSelection(
        path: String,
        preferredOnlineStyle: BasemapStyle,
    ): ActiveMapSelection? {
        val relativePath = relativeOfflinePath(File(path)) ?: return null
        return ActiveMapSelection(
            kind = ActiveMapKind.OFFLINE_TILES,
            preferredOnlineStyle = preferredOnlineStyle.name,
            offlineRelativePath = relativePath,
        )
    }

    @Synchronized
    private fun mutate(
        transform: (ActiveMapSelectionSnapshot) -> ActiveMapSelectionSnapshot,
    ): Boolean {
        val current = when (val container = loadActiveContainer()) {
            is ActiveMapSelectionContainerLoad.Loaded -> {
                if (container.isLegacy) {
                    val retained = when (val retainedState = load(retainedFile, RETAINED_LABEL)) {
                        is ActiveMapSelectionLoadState.Loaded -> retainedState.selection
                        ActiveMapSelectionLoadState.Missing -> null
                        is ActiveMapSelectionLoadState.Unavailable -> return false
                    }
                    container.state.copy(retainedImported = retained)
                } else {
                    container.state
                }
            }
            ActiveMapSelectionContainerLoad.Missing -> {
                val retained = when (val retainedState = load(retainedFile, RETAINED_LABEL)) {
                    is ActiveMapSelectionLoadState.Loaded -> retainedState.selection
                    ActiveMapSelectionLoadState.Missing -> null
                    is ActiveMapSelectionLoadState.Unavailable -> return false
                }
                ActiveMapSelectionSnapshot(retainedImported = retained)
            }
            is ActiveMapSelectionContainerLoad.Unavailable -> {
                // A quarantined corrupt selector remains a cold-start recovery
                // barrier. A later user-initiated map choice may replace it
                // from an empty baseline, but automatic restore never calls a
                // mutation on this path.
                if (container.reason == ActiveMapSelectionFailure.CORRUPT &&
                    hasQuarantinedActiveSelectionRecovery()
                ) {
                    ActiveMapSelectionSnapshot()
                } else {
                    return false
                }
            }
        }
        return saveSnapshot(transform(current))
    }

    private fun hasQuarantinedActiveSelectionRecovery(): Boolean =
        filesDir.listFiles()?.any { candidate ->
            candidate.name.startsWith("$FILE_NAME.corrupt-")
        } == true

    private fun hasQuarantinedRetainedSelection(): Boolean =
        filesDir.listFiles()?.any { candidate ->
            candidate.name.startsWith("$RETAINED_FILE_NAME.corrupt-")
        } == true

    private fun saveSnapshot(snapshot: ActiveMapSelectionSnapshot): Boolean {
        // Fail before touching the selector when the sentinel cannot be made
        // durable. The coordinator can then keep the known-good map published
        // and offer Retry without lying about persistence.
        if (!migrationMarker.isFile && !migrationMarkerWriter(migrationMarker)) return false
        return runCatching {
            SafeStore.writeAtomically(file, LABEL, json.encodeToString(snapshot))
        }.isSuccess
    }

    internal companion object {
        // process wide, not per instance: the bake publisher (app scope) and the view model's
        // reconcile each have their own store object but have to take turns on the same files
        private val MANAGED_FILES_LOCK = Any()

        /** run [block] while no reconcile can list or delete managed map files */
        fun <T> withManagedFilesLock(block: () -> T): T = synchronized(MANAGED_FILES_LOCK) { block() }

        private const val FILE_NAME = "active_map_source.json"
        private const val LABEL = "active_map_source.json"
        private const val RETAINED_FILE_NAME = "retained_imported_map_source.json"
        private const val RETAINED_LABEL = "retained_imported_map_source.json"
        private const val MIGRATION_MARKER = ".active_map_selection_migrated_v1"

        fun forTests(
            filesDir: File,
            migrationMarkerWriter: (File) -> Boolean = ::persistActiveMapMigrationMarker,
        ) = ActiveMapSelectionStore(filesDir, migrationMarkerWriter)
    }
}

internal sealed interface ActiveMapSelectionLoadState {
    data class Loaded(val selection: ActiveMapSelection) : ActiveMapSelectionLoadState
    data object Missing : ActiveMapSelectionLoadState
    data class Unavailable(
        val reason: ActiveMapSelectionFailure
    ) : ActiveMapSelectionLoadState
}

internal enum class ActiveMapSelectionFailure {
    CORRUPT,
    LOCKED
}

/** Persistence boundary used by the map-selection commit coordinator. */
internal interface ActiveMapSelectionPersistence {
    fun saveOnline(style: BasemapStyle): Boolean
    fun saveActiveAndRetainedPdf(preferredOnlineStyle: BasemapStyle): Boolean
    fun saveActiveAndRetainedOffline(path: String, preferredOnlineStyle: BasemapStyle): Boolean
    fun saveOnlineAndClearRetained(style: BasemapStyle): Boolean
}

private data class ActiveMapSelectionContainer(
    val state: ActiveMapSelectionSnapshot,
    val isLegacy: Boolean,
)

private sealed interface ActiveMapSelectionContainerLoad {
    data class Loaded(
        val state: ActiveMapSelectionSnapshot,
        val isLegacy: Boolean,
    ) : ActiveMapSelectionContainerLoad

    data object Missing : ActiveMapSelectionContainerLoad

    data class Unavailable(
        val reason: ActiveMapSelectionFailure,
    ) : ActiveMapSelectionContainerLoad
}

private fun ActiveMapSelection?.asLoadState(): ActiveMapSelectionLoadState =
    this?.let(ActiveMapSelectionLoadState::Loaded) ?: ActiveMapSelectionLoadState.Missing

@Serializable
private data class ActiveMapSelectionSnapshot(
    val schemaVersion: Int = ACTIVE_MAP_SNAPSHOT_SCHEMA_VERSION,
    val active: ActiveMapSelection? = null,
    val retainedImported: ActiveMapSelection? = null,
)

@Serializable
internal data class ActiveMapSelection(
    val kind: ActiveMapKind,
    val preferredOnlineStyle: String,
    val offlineRelativePath: String? = null
)

@Serializable
internal enum class ActiveMapKind {
    ONLINE,
    PDF,
    OFFLINE_TILES
}

/**
 * Remove Offline Tiles (R2-S2): drop the bake record, then delete the file + sidecars in
 * offline_tiles right away, all under the managed files lock. Doesn't wait on the reconcile,
 * that one refuses to run while the PDF is missing and the plaintext tiles would just sit
 * there. [takeRecord] clears it and names the file ("" none, null = not cleared, delete
 * nothing). [onCleared] runs before the delete, it's where the live reader lets go. A name
 * without the tacmap-bake- prefix isn't ours and never gets deleted (R3-5). Returns null when
 * nothing was cleared, else whether the file is gone
 */
internal fun removePdfBakeFile(filesDir: File, takeRecord: () -> String?, onCleared: () -> Unit = {}): Boolean? =
    ActiveMapSelectionStore.withManagedFilesLock {
        val name = takeRecord() ?: return@withManagedFilesLock null
        onCleared()
        name.isEmpty() || ManagedImportedMapFileLifecycle.isGeneratedBakeName(name) &&
            ManagedImportedMapFileLifecycle.deleteMBTiles(File(filesDir, "offline_tiles"), name)
    }

/**
 * Bake-only sweep (R3-2), at launch and after Remove: tacmap-bake-*.mbtiles + sidecars in
 * offline_tiles that no sealed record names (the library: every PDF entry's bake). Record
 * read and sweep under the managed files lock, so a publish can't land a file in between,
 * and a bake that's moved in but not attached yet is in flight and left alone. Unreadable record = skip, null back; never
 * delete on a guess. Fine with the PDF missing, a bake can always be made again
 */
internal fun sweepOrphanPdfBakes(
    filesDir: File,
    inFlight: () -> Set<File> = { emptySet() },
    readRecord: () -> PdfBakeRecordRead,
): Boolean? =
    ActiveMapSelectionStore.withManagedFilesLock {
        val keep = when (val r = readRecord()) {
            is PdfBakeRecordRead.Read -> r.fileNames
            PdfBakeRecordRead.Unreadable -> return@withManagedFilesLock null
        }
        ManagedImportedMapFileLifecycle.sweepBakes(File(filesDir, "offline_tiles"), keep, inFlight())
    }
