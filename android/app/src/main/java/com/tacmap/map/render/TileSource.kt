package com.tacmap.map.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.EsriKey
import com.tacmap.calibration.MBTilesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Where the tile view gets its raster bytes. The custom renderer's answer to the
 * Google Maps `TileProvider` / `UrlTileProvider`, but decoding to a [Bitmap] we
 * draw ourselves on a Compose canvas. Mirrors the iOS RasterTileSource.
 *
 * loadTile is suspend + off the main thread; the view calls it from a coroutine
 * and caches the result.
 */
interface TileSource {
    val minZoom: Int
    val maxZoom: Int
    /** Native pixel size of one source tile (256 for XYZ/MBTiles, 512 for Esri static). */
    val tileSizePx: Int
    suspend fun loadTile(tile: TileIndex): Bitmap?
}

/**
 * Online XYZ raster basemap (Esri Satellite/Topo or OSM Topo/Street), fetched
 * over HTTPS. Keyed styles carry the ArcGIS token; with no key a keyed style
 * returns nothing rather than hot-linking the unauthenticated endpoint. This is
 * the ONLY thing that hits the network, and only when the caller builds it -
 * i.e. when the online-basemaps gate is on.
 */
class OnlineRasterTileSource(private val style: BasemapStyle) : TileSource {
    override val minZoom = 0
    override val maxZoom = style.maxZoom
    override val tileSizePx = style.tileSize

    /**
     * The HTTPS URL for one tile, or null if we must not fetch it: past the
     * style's max zoom, or a keyed style with no ArcGIS key configured (we refuse
     * rather than hot-link the unauthenticated endpoint). Pure + no network, so
     * the basemap wiring is unit-testable (RasterTileSourceTest).
     */
    fun tileUrl(tile: TileIndex): String? {
        if (tile.z > style.maxZoom) return null
        if (style.requiresEsriKey && !EsriKey.isAvailable) return null
        var url = style.urlTemplate
            .replace("{z}", tile.z.toString())
            .replace("{x}", tile.x.toString())
            .replace("{y}", tile.y.toString())
        if (style.requiresEsriKey) url += "?token=${EsriKey.token}"
        return url
    }

    override suspend fun loadTile(tile: TileIndex): Bitmap? = withContext(Dispatchers.IO) {
        val url = tileUrl(tile) ?: return@withContext null
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        // Shared pool: tile bursts reuse connections instead of a socket per tile.
        private val client = OkHttpClient()
    }
}

/** Offline MBTiles raster pyramid, read locally with zero network. */
class OfflineRasterTileSource(
    private val store: MBTilesStore,
    override val maxZoom: Int = 22
) : TileSource {
    override val minZoom = 0
    override val tileSizePx = 256

    override suspend fun loadTile(tile: TileIndex): Bitmap? = withContext(Dispatchers.IO) {
        val data = store.tileData(tile.z, tile.x, tile.y) ?: return@withContext null
        BitmapFactory.decodeByteArray(data, 0, data.size)
    }
}
