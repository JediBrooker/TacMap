package com.tacmap.calibration

import java.io.File
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A basemap backed by a local MBTiles raster pyramid (offline). The Android
 * mirror of iOS's OfflineTileMapSource: travels alongside the WGS84 overlay
 * store and serves zoomable tiles through the custom renderer's [renderTileSource].
 * Coverage comes from the MBTiles `bounds` metadata so the camera can frame on load.
 *
 * Either admitted up front ([open], off main) or prevalidated ([prevalidated]): then
 * nothing's opened till the first tile read, which runs on IO anyway (s14.2)
 */
class OfflineTileMapSourceAndroid private constructor(
    val path: String,
    admitted: MBTilesStore?,
    internal val metadata: MBTilesStore.Metadata,
    override val displayName: String,
) : MapSource, Closeable {
    override val id: String = UUID.randomUUID().toString()
    override val kind = MapSourceKind.OFFLINE_TILES
    override val calibration: Calibration? = null
    override val coverage: Wgs84Bounds? = metadata.bounds
    val minZoom: Int = metadata.minZoom
    val maxZoom: Int = metadata.maxZoom
    private val closed = AtomicBoolean(false)

    // the lazy open holds this, close only ever tries it so main never waits on an open
    private val openLock = ReentrantLock()
    @Volatile private var store: MBTilesStore? = admitted
    private var openTried = admitted != null

    /** the open guard's first draw watch while this publication hasn't settled (s14.1), else null */
    @Volatile internal var readWatch: com.tacmap.map.render.TileReadWatch? = null

    /** Tile source for the custom (SDK-free) map view. */
    fun renderTileSource(): com.tacmap.map.render.TileSource =
        com.tacmap.map.render.OfflineRasterTileSource(this, minZoom, maxZoom, path) { readWatch }

    /** tile bytes off the IO thread. a prevalidated pack gets opened here the first time, refused = no tiles ever */
    internal fun tileData(z: Int, x: Int, y: Int): ByteArray? = storeForRead()?.tileData(z, x, y)

    private fun storeForRead(): MBTilesStore? {
        if (closed.get()) return null
        store?.let { return it }
        openLock.withLock {
            if (!openTried && !closed.get()) {
                openTried = true
                store = MBTilesStore.openPrevalidated(path, metadata)
            }
        }
        // a close that came in mid open couldn't take the lock, so closing it is on us
        if (closed.get()) {
            openLock.withLock { store?.close() }
            return null
        }
        return store
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (openLock.tryLock()) {
            try { store?.close() } finally { openLock.unlock() }
        }
    }

    internal fun isClosedForTesting(): Boolean = closed.get() && store?.isClosedForTesting() != false

    /** true once a lazy open ran and refused the pack, for the tests */
    internal fun refusedForTesting(): Boolean = openLock.withLock { openTried && store == null }

    companion object {
        /** the full admission (checks + aggregate). reads the whole tiles table, so never on main */
        fun open(path: String, displayName: String? = null): OfflineTileMapSourceAndroid? {
            val store = MBTilesStore.open(path) ?: return null
            return OfflineTileMapSourceAndroid(
                path = path,
                admitted = store,
                metadata = store.metadata,
                displayName = displayName ?: store.metadata.name ?: File(path).nameWithoutExtension,
            )
        }

        /** a pack these bytes already passed admission as, nothing touches the file till a tile's read */
        fun prevalidated(path: String, displayName: String, metadata: MBTilesStore.Metadata): OfflineTileMapSourceAndroid =
            OfflineTileMapSourceAndroid(path, null, metadata, displayName)
    }
}

/**
 * What admission gave for a pack's bytes, this process only and never written anywhere
 * (s14.2). Keyed by entry, contentKey, size and mtime so other bytes never match
 */
internal object AdmittedMbtiles {
    private data class Key(val entryId: String, val contentKey: String, val size: Long, val modifiedAtMs: Long)

    private val admitted = ConcurrentHashMap<Key, MBTilesStore.Metadata>()

    /** [size] + [modifiedAtMs] as stat'd before the admission read the file */
    fun put(entryId: String, contentKey: String?, size: Long, modifiedAtMs: Long, metadata: MBTilesStore.Metadata) {
        if (contentKey == null || size <= 0L) return
        admitted[Key(entryId, contentKey, size, modifiedAtMs)] = metadata
    }

    /** a stat, cheap enough for main */
    fun get(entryId: String, contentKey: String?, file: File): MBTilesStore.Metadata? {
        contentKey ?: return null
        return admitted[Key(entryId, contentKey, file.length(), file.lastModified())]
    }

    fun clearForTesting() = admitted.clear()
}

/**
 * Packs whose off main open came back refused this process (s15.1 rule 1), keyed like
 * [AdmittedMbtiles] but off the library entry. Memory only, never persisted. An admitted
 * open of the key or deleting the entry drops it, a relink is a new key anyway. [refused]
 * is a flow so the Layers rows pick it up. Also remembers which keys already got the
 * restore notice, that one's once per process
 */
internal object RefusedMbtiles {
    data class Key(val entryId: String, val contentKey: String?, val size: Long, val modifiedAtMs: Long)

    fun keyOf(e: ImportedMapEntry): Key = Key(e.id, e.contentKey, e.byteCount, e.fileModifiedAtMs)

    private val _refused = MutableStateFlow<Set<Key>>(emptySet())
    val refused: StateFlow<Set<Key>> = _refused.asStateFlow()
    private val noticed = ConcurrentHashMap.newKeySet<Key>()

    fun record(e: ImportedMapEntry) = _refused.update { it + keyOf(e) }

    fun admitted(e: ImportedMapEntry) = _refused.update { it - keyOf(e) }

    fun isRefused(e: ImportedMapEntry): Boolean = e.isMbtiles && keyOf(e) in _refused.value

    /** the entry's gone, so are its keys */
    fun forget(entryId: String) {
        _refused.update { s -> s.filterTo(HashSet()) { it.entryId != entryId } }
        noticed.removeIf { it.entryId == entryId }
    }

    /** true the first time for this key this process, the restore notice only shows then */
    fun firstNotice(e: ImportedMapEntry): Boolean = noticed.add(keyOf(e))

    fun clearForTesting() {
        _refused.value = emptySet()
        noticed.clear()
    }
}
