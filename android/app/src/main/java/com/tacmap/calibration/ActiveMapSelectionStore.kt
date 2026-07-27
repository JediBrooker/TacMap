package com.tacmap.calibration

import android.content.Context
import com.tacmap.util.SafeStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Encrypted pointer to the basemap that was active when the map screen closed.
 *
 * Imported filenames can identify an area of operations, so this is stored with
 * [SafeStore] rather than ordinary SharedPreferences. Offline paths are kept
 * relative to the app sandbox and are revalidated on restore; an absent,
 * moved, or traversal path is treated as stale and the caller falls back to
 * the preferred online basemap.
 */
class ActiveMapSelectionStore private constructor(private val filesDir: File) {

    constructor(context: Context) : this(context.applicationContext.filesDir)

    private val file = File(filesDir, FILE_NAME)
    private val migrationMarker = File(filesDir, MIGRATION_MARKER)
    private val json = Json { ignoreUnknownKeys = true }

    internal fun loadState(): ActiveMapSelectionLoadState =
        when (val result = SafeStore.readOrQuarantine(file, LABEL) {
            json.decodeFromString<ActiveMapSelection>(it)
        }) {
            is SafeStore.LoadResult.Loaded -> ActiveMapSelectionLoadState.Loaded(result.value)
            SafeStore.LoadResult.Empty -> ActiveMapSelectionLoadState.Missing
            is SafeStore.LoadResult.Corrupt ->
                ActiveMapSelectionLoadState.Unavailable(ActiveMapSelectionFailure.CORRUPT)
            is SafeStore.LoadResult.Locked ->
                ActiveMapSelectionLoadState.Unavailable(ActiveMapSelectionFailure.LOCKED)
        }

    fun saveOnline(style: BasemapStyle): Boolean =
        save(
            ActiveMapSelection(
                kind = ActiveMapKind.ONLINE,
                preferredOnlineStyle = style.name
            )
        )

    fun savePdf(preferredOnlineStyle: BasemapStyle): Boolean =
        save(
            ActiveMapSelection(
                kind = ActiveMapKind.PDF,
                preferredOnlineStyle = preferredOnlineStyle.name
            )
        )

    fun saveOffline(path: String, preferredOnlineStyle: BasemapStyle): Boolean {
        val relativePath = relativeOfflinePath(File(path)) ?: return false
        return save(
            ActiveMapSelection(
                kind = ActiveMapKind.OFFLINE_TILES,
                preferredOnlineStyle = preferredOnlineStyle.name,
                offlineRelativePath = relativePath
            )
        )
    }

    /**
     * True exactly once for an install upgrading from the pre-selector build.
     *
     * That build unconditionally restored any persisted PDF on relaunch, even
     * when MBTiles files also remained in its library. Preserve that observable
     * behaviour on the first upgraded launch; the separate marker prevents a
     * later missing descriptor from inferring/resurrecting the PDF again.
     */
    internal fun legacyPdfMigrationPending(): Boolean = !migrationMarker.exists()

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

    private fun save(selection: ActiveMapSelection): Boolean {
        val saved = runCatching {
            SafeStore.writeAtomically(file, LABEL, json.encodeToString(selection))
        }.isSuccess
        if (saved) {
            // This marker contains no mission data. It exists separately from
            // the encrypted descriptor so deleting/losing that descriptor
            // later cannot rerun a legacy migration and resurrect an old PDF.
            runCatching {
                migrationMarker.parentFile?.mkdirs()
                migrationMarker.createNewFile()
            }
        }
        return saved
    }

    internal companion object {
        private const val FILE_NAME = "active_map_source.json"
        private const val LABEL = "active_map_source.json"
        private const val MIGRATION_MARKER = ".active_map_selection_migrated_v1"

        fun forTests(filesDir: File) = ActiveMapSelectionStore(filesDir)
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
