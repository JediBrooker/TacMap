package com.tacmap.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

/**
 * Terrain-elevation reading for the crosshair / map centre.
 *
 * Port of the iOS ElevationReading / ElevationService (Open-Meteo
 * Copernicus ~30m DEM, no API key). [isStale] marks an approximate value
 * from the nearby cache when network was unavailable - the UI
 * prefixes those with "~".
 */
data class ElevationReading(
    val metres: Double,
    val isStale: Boolean
)

/**
 * Bounded cache of DEM readings keyed by coordinate rounded to 4dp (~11m).
 * Also does nearest-neighbour lookup so when network drops we can still
 * show an approx height from somewhere we've been instead of blanking
 * the readout. Mirrors ElevationCache on iOS.
 */
internal class ElevationCache(private val capacity: Int = 256) {
    private data class Entry(val lat: Double, val lng: Double, val metres: Double)

    // oldest first, most recent last
    private val entries = ArrayList<Entry>()

    private fun key(lat: Double, lng: Double): String = "%.4f,%.4f".format(lat, lng)

    fun insert(lat: Double, lng: Double, metres: Double) {
        val k = key(lat, lng)
        entries.removeAll { key(it.lat, it.lng) == k }
        entries.add(Entry(lat, lng, metres))
        while (entries.size > capacity) entries.removeAt(0)
    }

    /** Exact hit on the rounded key (previously fetched DEM value). */
    fun exact(lat: Double, lng: Double): Double? {
        val k = key(lat, lng)
        return entries.lastOrNull { key(it.lat, it.lng) == k }?.metres
    }

    /** Nearest cached reading within [maxMetres], or null if none is close enough. */
    fun nearest(lat: Double, lng: Double, maxMetres: Double): Double? {
        var bestMetres: Double? = null
        var bestDist = Double.MAX_VALUE
        for (e in entries) {
            val d = distanceMetres(lat, lng, e.lat, e.lng)
            if (d <= maxMetres && d < bestDist) {
                bestDist = d
                bestMetres = e.metres
            }
        }
        return bestMetres
    }

    companion object {
        /** Equirectangular approx - good enough at the few-km offline-fallback scale. */
        fun distanceMetres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val r = 6_371_000.0
            val dLat = (bLat - aLat) * Math.PI / 180
            val meanLat = (aLat + bLat) / 2 * Math.PI / 180
            val dLon = (bLng - aLng) * Math.PI / 180 * cos(meanLat)
            return r * Math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}

/**
 * Returns terrain elevation (metres MSL) for a WGS84 coordinate,
 * backed by Open-Meteo's free elevation endpoint (Copernicus DEM ~30m, no key).
 *
 * Offline-resilient: readings are cached, and when network is down we return
 * the nearest cached reading within [staleFallbackMetres] marked stale, so
 * a field user with no signal still sees an approximate height.
 *
 * Not thread-safe - call from a single collector coroutine (as
 * MapViewModel does via collectLatest).
 */
class ElevationService(
    private val staleFallbackMetres: Double = 2_000.0
) {
    @Serializable
    private data class ElevationResponse(val elevation: List<Double> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ElevationCache()

    /**
     * Fetch elevation reading. Returns null only when genuinely unknown
     * (no network AND nothing close enough cached). Skips 0,0 sentinel.
     */
    suspend fun reading(lat: Double, lng: Double): ElevationReading? {
        if (lat == 0.0 && lng == 0.0) return null

        cache.exact(lat, lng)?.let { return ElevationReading(it, isStale = false) }

        val fetched = fetch(lat, lng)
        if (fetched != null) {
            cache.insert(lat, lng, fetched)
            return ElevationReading(fetched, isStale = false)
        }

        // Network failed, fall back to nearest height we already know
        cache.nearest(lat, lng, staleFallbackMetres)?.let {
            return ElevationReading(it, isStale = true)
        }
        return null
    }

    private suspend fun fetch(lat: Double, lng: Double): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://api.open-meteo.com/v1/elevation?latitude=$lat&longitude=$lng")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = 6_000
            }
            val body = try {
                if (conn.responseCode != 200) return@runCatching null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            json.decodeFromString<ElevationResponse>(body).elevation.firstOrNull()
        }.getOrNull()
    }
}
