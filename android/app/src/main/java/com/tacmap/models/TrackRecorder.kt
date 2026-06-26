package com.tacmap.models

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One recorded fix on a GPX track. */
data class TrackPoint(
    val latitude: Double,
    val longitude: Double,
    val elevationMetres: Double?,
    val timeEpochMs: Long
)

/**
 * Accumulates GPS fixes into a track while recording. Fed by [MapViewModel]
 * from [LocationService] updates. Foreground recording (the FusedLocation
 * client keeps streaming while the app is in the foreground / lightly
 * backgrounded; a foreground service for long background tracks is a follow-up).
 */
class TrackRecorder {
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    /** Minimum spacing between stored fixes (m) — drops GPS jitter. */
    private val minSpacingMetres = 2.0

    fun start() {
        _points.value = emptyList()
        _isRecording.value = true
    }

    fun stop() {
        _isRecording.value = false
    }

    fun onLocation(loc: Location) {
        if (!_isRecording.value) return
        val last = _points.value.lastOrNull()
        if (last != null &&
            distanceMetres(last.latitude, last.longitude, loc.latitude, loc.longitude) < minSpacingMetres
        ) return
        _points.value = _points.value + TrackPoint(
            latitude = loc.latitude,
            longitude = loc.longitude,
            elevationMetres = if (loc.hasAltitude()) loc.altitude else null,
            timeEpochMs = if (loc.time > 0) loc.time else System.currentTimeMillis()
        )
    }

    private fun distanceMetres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, results)
        return results[0].toDouble()
    }
}
