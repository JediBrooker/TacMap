package com.tacmap.models

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** One recorded fix on a GPX track. */
@Serializable
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMetres: Double?,
    val timeEpochMs: Long
)

/**
 * Accumulates GPS fixes into a track. Fed from MapViewModel via
 * LocationService.
 *
 * Every fix is fsync'd to tracks/recording.ndjson via TrackLog so
 * process death only loses the single in-flight fix, never the whole
 * track. On construction we recover any leftover track from a session
 * that died mid-recording. Background recording kept alive by
 * TrackRecordingService.
 */
class TrackRecorder(context: Context) {

    private val logFile = File(context.applicationContext.filesDir, "tracks/recording.ndjson")

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    /** True if we recovered points from a previous session that got killed
     *  mid-recording. UI can offer export/clear. */
    private val _recovered = MutableStateFlow(false)
    val recovered: StateFlow<Boolean> = _recovered.asStateFlow()

    /** Min spacing between fixes (m). Drops GPS jitter. */
    private val minSpacingMetres = 2.0

    init {
        val restored = TrackLog.read(logFile)
        if (restored.isNotEmpty()) {
            _points.value = restored
            _recovered.value = true
        }
    }

    fun start() {
        _points.value = emptyList()
        _recovered.value = false
        TrackLog.truncate(logFile)
        _isRecording.value = true
    }

    fun stop() {
        _isRecording.value = false
        // Intentionally keep the log file - a completed track must survive
        // until user exports or discards it.
    }

    /** Clear the current (recorded or recovered) track and remove its file. */
    fun discard() {
        _points.value = emptyList()
        _recovered.value = false
        _isRecording.value = false
        TrackLog.delete(logFile)
    }

    fun onLocation(loc: Location) {
        if (!_isRecording.value) return
        val last = _points.value.lastOrNull()
        if (last != null &&
            distanceMetres(last.latitude, last.longitude, loc.latitude, loc.longitude) < minSpacingMetres
        ) return
        val point = TrackPoint(
            latitude = loc.latitude,
            longitude = loc.longitude,
            elevationMetres = if (loc.hasAltitude()) loc.altitude else null,
            timeEpochMs = if (loc.time > 0) loc.time else System.currentTimeMillis()
        )
        _points.value = _points.value + point
        // Persist before returning so the fix is durable the moment its shown.
        runCatching { TrackLog.append(logFile, point) }
    }

    private fun distanceMetres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, results)
        return results[0].toDouble()
    }
}
