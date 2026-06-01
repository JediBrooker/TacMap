package com.tacticalmaps.calibration

import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.io.File
import java.util.UUID

/** Google Maps TileProvider that serves 256px raster tiles from a local
 *  MBTiles file via [MBTilesStore]. */
class MBTilesTileProvider(private val store: MBTilesStore) : TileProvider {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val data = store.tileData(zoom, x, y) ?: return TileProvider.NO_TILE
        return Tile(256, 256, data)
    }
}

/**
 * A basemap backed by a local MBTiles raster pyramid (offline). The Android
 * mirror of iOS's OfflineTileMapSource: travels alongside the WGS84 overlay
 * store and serves zoomable tiles through a Google Maps [TileProvider]. Coverage
 * comes from the MBTiles `bounds` metadata so the camera can frame on load.
 */
class OfflineTileMapSourceAndroid private constructor(
    val path: String,
    private val store: MBTilesStore,
    override val displayName: String,
    override val coverage: Wgs84Bounds?
) : MapSource {
    override val id: String = UUID.randomUUID().toString()
    override val kind = MapSourceKind.OFFLINE_TILES
    override val calibration: Calibration? = null

    fun tileProvider(): TileProvider = MBTilesTileProvider(store)

    companion object {
        fun open(path: String): OfflineTileMapSourceAndroid? {
            val store = MBTilesStore.open(path) ?: return null
            return OfflineTileMapSourceAndroid(
                path = path,
                store = store,
                displayName = store.metadata.name ?: File(path).nameWithoutExtension,
                coverage = store.metadata.bounds
            )
        }
    }
}
