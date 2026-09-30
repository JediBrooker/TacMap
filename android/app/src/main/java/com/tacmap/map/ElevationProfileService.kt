package com.tacmap.map

import com.tacmap.settings.OpsecSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Fetches terrain heights for elevation-profile samples from Open-Meteo's
 * elevation endpoint (Copernicus DEM, no API key), at most 100 points per
 * request. Like the other elevation lookups it only runs while the user has
 * turned on online lookups, and it rounds every point to 4 decimal places
 * (about 11 m) before it leaves the device. iOS mirrors this in
 * `ElevationProfileService.swift`.
 */
class ElevationProfileService(
    private val onlineLookupsEnabled: () -> Boolean = {
        OpsecSettings.shared?.onlineLookups?.value == true
    },
    private val fetchBody: suspend (url: String, limit: Int) -> String? = ::boundedHttpsGet,
) {
    sealed class Result {
        data class Heights(val metres: List<Double>) : Result()
        /** Online lookups are off in Privacy & OPSEC. */
        data object LookupsOff : Result()
        /** No usable answer: offline, a server error, or a malformed reply. */
        data object NetworkFailed : Result()
    }

    @Serializable
    private data class Response(val elevation: List<Double?> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }

    /** Terrain height in metres for every sample, in order. */
    suspend fun elevations(samples: List<ElevationProfile.Sample>): Result = withContext(Dispatchers.IO) {
        if (!onlineLookupsEnabled()) return@withContext Result.LookupsOff
        val heights = ArrayList<Double>(samples.size)
        for (url in requestUrls(samples)) {
            coroutineContext.ensureActive()
            val body = fetchBody(url, MAX_RESPONSE_BYTES)
            coroutineContext.ensureActive()
            // The setting can be turned off while a request is in flight.
            if (!onlineLookupsEnabled()) return@withContext Result.LookupsOff
            val parsed = body?.let { runCatching { json.decodeFromString<Response>(it) }.getOrNull() }
                ?: return@withContext Result.NetworkFailed
            for (height in parsed.elevation) {
                if (height == null || !height.isFinite()) return@withContext Result.NetworkFailed
                heights.add(height)
            }
        }
        if (heights.size != samples.size) Result.NetworkFailed else Result.Heights(heights)
    }

    companion object {
        /** Open-Meteo accepts up to 100 coordinates per request. */
        const val BATCH_SIZE = 100
        private const val MAX_RESPONSE_BYTES = 64 * 1024

        /** One request per 100 samples, coordinates rounded to 4 decimal places. */
        fun requestUrls(samples: List<ElevationProfile.Sample>): List<String> =
            samples.chunked(BATCH_SIZE).map { batch ->
                val latitudes = batch.joinToString(",") { String.format(Locale.US, "%.4f", it.latitude) }
                val longitudes = batch.joinToString(",") { String.format(Locale.US, "%.4f", it.longitude) }
                "https://api.open-meteo.com/v1/elevation?latitude=$latitudes&longitude=$longitudes"
            }
    }
}
