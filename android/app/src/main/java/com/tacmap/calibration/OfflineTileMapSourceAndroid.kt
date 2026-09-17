package com.tacmap.calibration

import java.io.File
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A basemap backed by a local MBTiles raster pyramid (offline). The Android
 * mirror of iOS's OfflineTileMapSource: travels alongside the WGS84 overlay
 * store and serves zoomable tiles through the custom renderer's [renderTileSource].
 * Coverage comes from the MBTiles `bounds` metadata so the camera can frame on load.
 */
class OfflineTileMapSourceAndroid private constructor(
    val path: String,
    private val store: MBTilesStore,
    override val displayName: String,
    override val coverage: Wgs84Bounds?,
    val minZoom: Int,
    val maxZoom: Int,
) : MapSource, Closeable {
    override val id: String = UUID.randomUUID().toString()
    override val kind = MapSourceKind.OFFLINE_TILES
    override val calibration: Calibration? = null
    private val closed = AtomicBoolean(false)

    /** Tile source for the custom (SDK-free) map view. */
    fun renderTileSource(): com.tacmap.map.render.TileSource =
        com.tacmap.map.render.OfflineRasterTileSource(store, minZoom, maxZoom)

    override fun close() {
        if (closed.compareAndSet(false, true)) store.close()
    }

    internal fun isClosedForTesting(): Boolean = closed.get() && store.isClosedForTesting()

    companion object {
        fun open(path: String): OfflineTileMapSourceAndroid? {
            val store = MBTilesStore.open(path) ?: return null
            return OfflineTileMapSourceAndroid(
                path = path,
                store = store,
                displayName = store.metadata.name ?: File(path).nameWithoutExtension,
                coverage = store.metadata.bounds,
                minZoom = store.metadata.minZoom,
                maxZoom = store.metadata.maxZoom,
            )
        }
    }
}
