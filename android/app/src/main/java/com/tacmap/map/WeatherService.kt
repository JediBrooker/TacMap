package com.tacmap.map

import com.tacmap.settings.OpsecSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** Current-conditions reading from Open-Meteo (same provider as elevation).
 *  Units: °C, m/s, metres. */
data class WeatherReading(
    val temperatureC: Double?,
    val windSpeedMs: Double?,
    val windGustsMs: Double?,
    val visibilityM: Double?,
    val weatherCode: Int?
)

/** UAV flight-safety risk, overall = worst of all components. */
enum class UAVRisk(val label: String) {
    SAFE("Safe to fly"),
    CAUTION("Marginal — caution"),
    DANGER("Do not fly")
}

/** Default UAV thresholds, small/consumer drone oriented. Tunable later. */
data class UAVThresholds(
    val windCautionMs: Double = 7.0, val windDangerMs: Double = 10.0,
    val gustCautionMs: Double = 8.0, val gustDangerMs: Double = 12.0,
    val visCautionM: Double = 5000.0, val visDangerM: Double = 1500.0,
    val tempLowDangerC: Double = -10.0, val tempLowCautionC: Double = 0.0,
    val tempHighCautionC: Double = 40.0, val tempHighDangerC: Double = 45.0
)

object UAVAssessment {
    /** Worst-of-components risk. Missing values don't raise risk. */
    fun risk(r: WeatherReading, t: UAVThresholds = UAVThresholds()): UAVRisk {
        var level = UAVRisk.SAFE
        fun bump(x: UAVRisk) { if (x.ordinal > level.ordinal) level = x }

        r.windSpeedMs?.let { if (it >= t.windDangerMs) bump(UAVRisk.DANGER) else if (it >= t.windCautionMs) bump(UAVRisk.CAUTION) }
        r.windGustsMs?.let { if (it >= t.gustDangerMs) bump(UAVRisk.DANGER) else if (it >= t.gustCautionMs) bump(UAVRisk.CAUTION) }
        r.visibilityM?.let { if (it <= t.visDangerM) bump(UAVRisk.DANGER) else if (it <= t.visCautionM) bump(UAVRisk.CAUTION) }
        r.temperatureC?.let {
            if (it <= t.tempLowDangerC || it >= t.tempHighDangerC) bump(UAVRisk.DANGER)
            else if (it <= t.tempLowCautionC || it >= t.tempHighCautionC) bump(UAVRisk.CAUTION)
        }
        return level
    }
}

/** Fetches current conditions from Open-Meteo's forecast endpoint. */
class WeatherService {

    @Serializable
    private data class Response(
        val current: Current? = null,
        val hourly: Hourly? = null
    ) {
        @Serializable
        data class Current(
            val time: String? = null,
            val temperature_2m: Double? = null,
            val wind_speed_10m: Double? = null,
            val wind_gusts_10m: Double? = null,
            val weather_code: Int? = null
        )
        @Serializable
        data class Hourly(
            val time: List<String> = emptyList(),
            val visibility: List<Double> = emptyList()
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun reading(lat: Double, lng: Double): WeatherReading? {
        // OPSEC: weather lookup transmits coord to Open-Meteo, only proceed
        // if user opted in. Without this the dialog leaked map-centre even
        // with online lookups off.
        if (OpsecSettings.shared?.onlineLookups?.value != true) return null
        if (lat == 0.0 && lng == 0.0) return null
        // Coarsen to ~110m so exact position isn't disclosed.
        val cLat = (lat * 1000).roundToInt() / 1000.0
        val cLng = (lng * 1000).roundToInt() / 1000.0
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$cLat&longitude=$cLng" +
            "&current=temperature_2m,wind_speed_10m,wind_gusts_10m,weather_code" +
            "&hourly=visibility&wind_speed_unit=ms&forecast_days=1&timezone=auto"

        val body = boundedHttpsGet(url, MAX_RESPONSE_BYTES) ?: return null

        val decoded = runCatching { json.decodeFromString<Response>(body) }.getOrNull() ?: return null
        val c = decoded.current ?: return null
        return WeatherReading(
            temperatureC = c.temperature_2m,
            windSpeedMs = c.wind_speed_10m,
            windGustsMs = c.wind_gusts_10m,
            visibilityM = visibilityNow(decoded, c.time),
            weatherCode = c.weather_code
        )
    }

    private fun visibilityNow(r: Response, currentTime: String?): Double? {
        val times = r.hourly?.time ?: return null
        val vis = r.hourly.visibility
        if (vis.isEmpty()) return null
        val idx = currentTime?.let { times.indexOf(it) } ?: -1
        return if (idx in vis.indices) vis[idx] else vis.firstOrNull()
    }

    private companion object { const val MAX_RESPONSE_BYTES = 1024 * 1024 }
}
