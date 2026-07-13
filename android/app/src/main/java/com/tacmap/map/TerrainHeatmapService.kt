package com.tacmap.map

import android.graphics.Bitmap
import android.graphics.Color
import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.settings.OpsecSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Auto terrain heatmap for a WGS84 region. Samples a grid of elevations
 * from Open-Meteo's Copernicus DEM and colours blue (low) to red (high).
 * No user-uploaded file needed, unlike the competitor's manual import.
 *
 * Returned bitmap is upscaled + alpha-blended for a smooth semi-transparent
 * overlay when stretched across the region.
 */
class TerrainHeatmapService {

    @Serializable
    private data class Response(val elevation: List<Double> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Sample [grid]x[grid] elevations over [bounds] and return a coloured,
     *  upscaled bitmap, or null on failure. */
    suspend fun generate(bounds: Wgs84Bounds, grid: Int = 24): Bitmap? = withContext(Dispatchers.IO) {
        // OPSEC: sampling the DEM sends coordinates to Open-Meteo,
        // only proceed if user opted into online lookups.
        if (OpsecSettings.shared?.onlineLookups?.value != true) return@withContext null
        val south = bounds.southwest.latitude
        val north = bounds.northeast.latitude
        val west = bounds.southwest.longitude
        val east = bounds.northeast.longitude
        if (grid !in 2..32 || !south.isFinite() || !north.isFinite() ||
            !west.isFinite() || !east.isFinite() || south !in -90.0..90.0 ||
            north !in -90.0..90.0 || west !in -180.0..180.0 || east !in -180.0..180.0
        ) return@withContext null

        // Row-major grid; row 0 = north edge, col 0 = west edge (GroundOverlay's
        // image origin is the NW corner).
        val lat = DoubleArray(grid * grid)
        val lon = DoubleArray(grid * grid)
        for (r in 0 until grid) {
            val y = north - (north - south) * r / (grid - 1)
            for (c in 0 until grid) {
                val idx = r * grid + c
                lat[idx] = y
                lon[idx] = west + (east - west) * c / (grid - 1)
            }
        }

        val elev = DoubleArray(grid * grid) { Double.NaN }
        var i = 0
        while (i < elev.size) {
            // If user panned away or toggled heatmap off, bail early
            // instead of finishing every batch.
            ensureActive()
            val end = minOf(i + 100, elev.size)   // Open-Meteo: <=100 points/request
            // Coarsen to ~11 m before egress: more precision is useless for
            // this 24x24 visualisation and needlessly fingerprints the AO.
            val latStr = (i until end).joinToString(",") { String.format(Locale.US, "%.4f", lat[it]) }
            val lonStr = (i until end).joinToString(",") { String.format(Locale.US, "%.4f", lon[it]) }
            val body = fetch(
                "https://api.open-meteo.com/v1/elevation?latitude=$latStr&longitude=$lonStr"
            ) ?: return@withContext null
            val parsed = runCatching { json.decodeFromString<Response>(body) }.getOrNull()
                ?: return@withContext null
            for (k in parsed.elevation.indices) if (i + k < elev.size) elev[i + k] = parsed.elevation[k]
            i = end
        }

        val valid = elev.filter { !it.isNaN() }
        if (valid.isEmpty()) return@withContext null
        val min = valid.min()
        val max = valid.max()
        val range = (max - min).takeIf { it > 1e-6 } ?: 1.0

        val small = Bitmap.createBitmap(grid, grid, Bitmap.Config.ARGB_8888)
        for (r in 0 until grid) {
            for (c in 0 until grid) {
                val e = elev[r * grid + c]
                small.setPixel(c, r, if (e.isNaN()) Color.TRANSPARENT else colorFor((e - min) / range))
            }
        }
        // Upscale with bilinear filtering for a smooth gradient overlay.
        val scaled = Bitmap.createScaledBitmap(small, 256, 256, true)
        small.recycle()
        scaled
    }

    /** Blue (t=0, low) → cyan → green → yellow → red (t=1, high), ~45% alpha. */
    private fun colorFor(t: Double): Int {
        val clamped = t.coerceIn(0.0, 1.0)
        val hue = ((1.0 - clamped) * 240.0).toFloat()  // 240=blue .. 0=red
        val rgb = Color.HSVToColor(floatArrayOf(hue, 0.85f, 0.95f)) and 0x00FFFFFF
        return (0x73 shl 24) or rgb   // 0x73 ≈ 45% alpha
    }

    private suspend fun fetch(url: String): String? = boundedHttpsGet(url, MAX_RESPONSE_BYTES)

    private companion object { const val MAX_RESPONSE_BYTES = 1024 * 1024 }
}
