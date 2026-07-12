package com.tacmap.map.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.EsriKey
import com.tacmap.calibration.MBTilesStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

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

private const val MAX_ENCODED_TILE_BYTES = 4 * 1024 * 1024
private const val MAX_TILE_DIMENSION = 2048
private const val MAX_TILE_PIXELS = 4_194_304L
private const val MAX_DECODED_TILE_BYTES = 16 * 1024 * 1024

/** Reject decompression bombs before BitmapFactory allocates their pixel buffer. */
private fun decodeBoundedTile(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_ENCODED_TILE_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0 || width > MAX_TILE_DIMENSION || height > MAX_TILE_DIMENSION ||
        width.toLong() * height.toLong() > MAX_TILE_PIXELS) return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    if (bitmap.allocationByteCount > MAX_DECODED_TILE_BYTES) {
        bitmap.recycle()
        return null
    }
    return bitmap
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

    override suspend fun loadTile(tile: TileIndex): Bitmap? {
        val url = tileUrl(tile) ?: return null
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(Request.Builder().url(url).build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val bitmap = response.use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body ?: return@use null
                        if (body.contentLength() > MAX_ENCODED_TILE_BYTES) return@use null
                        val input = body.byteStream()
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_ENCODED_TILE_BYTES) return@use null
                            out.write(buffer, 0, n)
                        }
                        val bytes = out.toByteArray()
                        decodeBoundedTile(bytes)
                    }
                    if (continuation.isActive) continuation.resume(bitmap)
                }
            })
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
        decodeBoundedTile(data)
    }
}
