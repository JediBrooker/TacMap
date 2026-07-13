package com.tacmap.models

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
class TrackRecorder private constructor(
    private val logFile: File,
    private val stopRecordingService: () -> Unit,
) {

    constructor(context: Context) : this(
        logFile = File(context.applicationContext.filesDir, "tracks/recording.ndjson"),
        stopRecordingService = { TrackRecordingService.stop(context.applicationContext) },
    )

    /** File-backed constructor used by host-side regression tests. It exercises
     *  the same recovery/start/stop/discard state machine without an Android
     *  service or a device filesystem. */
    internal constructor(logFile: File) : this(logFile, {})

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points.asStateFlow()

    /** True if we recovered points from a previous session that got killed
     *  mid-recording. UI can offer export/clear. */
    private val _recovered = MutableStateFlow(false)
    val recovered: StateFlow<Boolean> = _recovered.asStateFlow()

    /** Non-null when a fix couldn't be written to disk. The UI has to say so:
     *  a recording that looks live but isn't hitting the disk is the worst
     *  possible failure for a field tool. */
    private val _persistError = MutableStateFlow<String?>(null)
    val persistError: StateFlow<String?> = _persistError.asStateFlow()

    fun acknowledgePersistError() { _persistError.value = null }

    /** Min spacing between fixes (m). Drops GPS jitter. */
    private val minSpacingMetres = 2.0

    init { recoverAfterUnlock() }

    /** Retry recovery after the platform credential has unwrapped the DEK. */
    fun reloadAfterUnlock() {
        if (_isRecording.value) return
        recoverAfterUnlock()
    }

    private fun recoverAfterUnlock() {
        // A locked at-rest key means we can't read the log yet. Leave it alone,
        // don't report an empty track as if the recording was lost.
        runCatching { TrackLog.read(logFile) }
            .onSuccess { restored ->
                if (restored.points.isNotEmpty()) {
                    _points.value = restored.points
                    _recovered.value = true
                }
                // Log came from a pre-encryption build, seal it in place once.
                if (restored.hadLegacyLines) {
                    runCatching { TrackLog.reseal(logFile, restored.points) }
                        .onFailure { _persistError.value = "Could not encrypt the recovered track: ${it.message}" }
                }
            }
            .onFailure { _persistError.value = "Could not read the saved track: ${it.message}" }
    }

    fun start(): Boolean {
        _persistError.value = null
        if (_recovered.value || _points.value.isNotEmpty()) {
            _persistError.value = "Export or discard the saved track before starting a new recording."
            return false
        }
        return runCatching { TrackLog.truncate(logFile) }
            .fold(
                onSuccess = {
                    _points.value = emptyList()
                    _recovered.value = false
                    _isRecording.value = true
                    true
                },
                onFailure = {
                    _isRecording.value = false
                    _persistError.value = "Could not start recording safely: ${it.message}"
                    false
                }
            )
    }

    fun stop() {
        _isRecording.value = false
        // Intentionally keep the log file - a completed track must survive
        // until user exports or discards it.
    }

    /** Clear a stopped (recorded or recovered) track and remove its file.
     *
     * Active recordings must first go through the explicit stop/confirmation
     * flow in the UI. Refusing here as well prevents a future caller from
     * silently deleting a live patrol track. State is only cleared after the
     * encrypted log has actually been removed. */
    fun discard(): Boolean {
        if (_isRecording.value) {
            _persistError.value = "Stop recording before discarding the saved track."
            return false
        }
        return runCatching { TrackLog.delete(logFile) }
            .fold(
                onSuccess = {
                    _points.value = emptyList()
                    _recovered.value = false
                    _persistError.value = null
                    true
                },
                onFailure = {
                    _persistError.value = "Could not discard the saved track: ${it.message}"
                    false
                }
            )
    }

    fun onLocation(loc: Location) {
        recordPoint(
            TrackPoint(
                latitude = loc.latitude,
                longitude = loc.longitude,
                elevationMetres = if (loc.hasAltitude()) loc.altitude else null,
                timeEpochMs = if (loc.time > 0) loc.time else System.currentTimeMillis()
            )
        )
    }

    /** Shared persistence path for device fixes and host-side state-machine
     * tests. The point is not published until its encrypted append is durable. */
    internal fun recordPoint(point: TrackPoint) {
        if (!_isRecording.value) return
        if (!point.latitude.isFinite() || !point.longitude.isFinite() ||
            point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0
        ) return
        val last = _points.value.lastOrNull()
        if (last != null &&
            distanceMetres(last.latitude, last.longitude, point.latitude, point.longitude) < minSpacingMetres
        ) return
        // Persist before publishing the fix. If durability fails, recording
        // stops instead of presenting an in-memory-only track as live.
        runCatching { TrackLog.append(logFile, point) }
            .onSuccess { _points.value = _points.value + point }
            .onFailure { failRecording("Track fix not saved; recording stopped: ${it.message}") }
    }

    fun failRecording(message: String) {
        _isRecording.value = false
        _persistError.value = message
        stopRecordingService()
    }

    /** Until recording has its own scoped wrapped key, crossing a DEK lock
     * boundary stops safely instead of letting the service reacquire/retain the
     * all-mission key in the background. The durable log is left untouched. */
    fun onMissionKeyLock() {
        if (_isRecording.value) {
            failRecording("Recording stopped when mission data was locked; the saved track is intact.")
        }
    }

    private fun distanceMetres(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * 6_371_000.0 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
