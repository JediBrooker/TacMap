package com.tacmap.models

import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
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
class TrackRecorder internal constructor(
    private val logFile: File,
    private val stopRecordingService: () -> Unit = {},
    private val scheduleActivationTimeout: (Long, () -> Unit) -> Unit = { _, _ -> },
    private val recordingKeyProvider: () -> ByteArray = { SafeStore.keyProvider.key().copyOf() },
) {

    constructor(context: Context) : this(
        logFile = File(context.applicationContext.filesDir, "tracks/recording.ndjson"),
        recordingKeyProvider = DataKey::key,
        stopRecordingService = { TrackRecordingService.stop(context.applicationContext) },
        scheduleActivationTimeout = { delayMillis, action ->
            Handler(Looper.getMainLooper()).postDelayed(action, delayMillis)
        },
    )

    private val recordingKeyLock = Any()
    private var recordingKey: ByteArray? = null
    private var nextSessionGeneration = 0L
    private var currentSessionGeneration: Long? = null

    private val _uiState = MutableStateFlow(TrackRecordingUiState())
    val uiState: StateFlow<TrackRecordingUiState> = _uiState.asStateFlow()

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

    /** False until the existing log has been read and any legacy migration has
     *  durably completed. A failed verification must never be truncated by a
     *  subsequent start attempt. */
    private var recoveryReady = false

    fun acknowledgePersistError() { _persistError.value = null }

    fun dismissRecordingMessage() {
        _persistError.value = null
        if (_uiState.value.phase == TrackRecordingPhase.AwaitingPermission ||
            _uiState.value.phase == TrackRecordingPhase.Interrupted
        ) {
            _uiState.value = TrackRecordingReducer.reduce(
                _uiState.value,
                TrackRecordingEvent.Stopped,
            )
        }
    }

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
                        .onSuccess { recoveryReady = true }
                        .onFailure {
                            recoveryReady = false
                            _persistError.value = "Could not encrypt the recovered track: ${it.message}"
                        }
                } else {
                    recoveryReady = true
                }
            }
            .onFailure {
                recoveryReady = false
                _persistError.value = "Could not read the saved track: ${it.message}"
            }
    }

    /** Resolve permission/GPS prerequisites before any file or key work begins. */
    fun awaitPermissionRequest(message: String) {
        _uiState.value = TrackRecordingReducer.reduce(
            _uiState.value,
            TrackRecordingEvent.AwaitingPermission(message),
        )
    }

    fun requestStart(locationAccess: LocationAccess, gpsEnabled: Boolean): Boolean {
        if (_uiState.value.isAuthorizedSession) return false
        _uiState.value = TrackRecordingReducer.reduce(
            _uiState.value,
            TrackRecordingEvent.StartRequested(locationAccess, gpsEnabled),
        )
        return _uiState.value.phase == TrackRecordingPhase.Starting
    }

    /**
     * Create/fsync the new log first, then retain a private copy of the current
     * mission DEK for this explicitly-authorized recording session. The service
     * must still call [onServiceActivated] before the UI can show REC.
     */
    fun prepareStart(): Boolean {
        if (_uiState.value.phase != TrackRecordingPhase.Starting) return false
        if (hasRetainedRecordingKey()) return false
        if (!recoveryReady) {
            failRecording(
                _persistError.value ?: "Could not verify the saved track before recording."
            )
            return false
        }
        _persistError.value = null
        if (_recovered.value || _points.value.isNotEmpty()) {
            failRecording("Export or discard the saved track before starting a new recording.")
            return false
        }
        var preparedGeneration: Long? = null
        return runCatching { TrackLog.truncate(logFile) }
            .mapCatching {
                val supplied = recordingKeyProvider()
                try {
                    require(supplied.size == 32) { "Mission data key must be 256 bits" }
                    synchronized(recordingKeyLock) {
                        clearRecordingKeyLocked()
                        recordingKey = supplied.copyOf()
                        nextSessionGeneration += 1
                        currentSessionGeneration = nextSessionGeneration
                        preparedGeneration = currentSessionGeneration
                    }
                } finally {
                    supplied.fill(0)
                }
            }
            .fold(
                onSuccess = {
                    _points.value = emptyList()
                    _recovered.value = false
                    _isRecording.value = false
                    val generation = checkNotNull(preparedGeneration)
                    scheduleActivationTimeout(ACTIVATION_TIMEOUT_MILLIS) {
                        onServiceActivationTimedOut(generation)
                    }
                    true
                },
                onFailure = {
                    failRecording("Could not start recording safely: ${it.message}")
                    false
                }
            )
    }

    /** Called only after startForeground and the GPS listener both succeed. */
    fun onServiceActivated(generation: Long): Boolean {
        val matchesPreparedSession = synchronized(recordingKeyLock) {
            recordingKey != null && currentSessionGeneration == generation
        }
        if (_uiState.value.phase != TrackRecordingPhase.Starting || !matchesPreparedSession) return false
        _uiState.value = TrackRecordingReducer.reduce(
            _uiState.value,
            TrackRecordingEvent.ServiceActivated,
        )
        _isRecording.value = _uiState.value.phase == TrackRecordingPhase.Recording
        return _isRecording.value
    }

    internal fun preparedServiceGeneration(): Long? = synchronized(recordingKeyLock) {
        currentSessionGeneration.takeIf {
            _uiState.value.phase == TrackRecordingPhase.Starting && recordingKey != null
        }
    }

    internal fun isServiceSessionAuthorized(generation: Long): Boolean =
        synchronized(recordingKeyLock) {
            currentSessionGeneration == generation && recordingKey != null &&
                _uiState.value.isAuthorizedSession
        }

    internal fun onServiceActivationTimedOut(generation: Long) {
        if (_uiState.value.phase != TrackRecordingPhase.Starting ||
            !isServiceSessionAuthorized(generation)
        ) return
        interruptRecording(
            "Background recording did not activate in time. Try starting it again.",
            requestServiceStop = true,
        )
    }

    /** Called from Service.onDestroy; never asks the already-destroying service to stop again. */
    internal fun onServiceDestroyed(generation: Long) {
        if (!isServiceSessionAuthorized(generation)) return
        interruptRecording(
            "Background recording stopped unexpectedly. Your saved track was preserved.",
            requestServiceStop = false,
        )
    }

    internal fun onServiceFailure(
        generation: Long,
        message: String,
        settingsTarget: TrackRecordingSettingsTarget? = null,
    ) {
        if (!isServiceSessionAuthorized(generation)) return
        interruptRecording(message, settingsTarget, requestServiceStop = false)
    }

    fun stop() {
        _isRecording.value = false
        _uiState.value = TrackRecordingReducer.reduce(_uiState.value, TrackRecordingEvent.Stopped)
        clearRecordingKey()
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
        clearRecordingKey()
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
        val failure = synchronized(recordingKeyLock) {
            val key = recordingKey
                ?: return@synchronized IllegalStateException("Recording key is unavailable")
            runCatching { TrackLog.append(logFile, point, key) }.exceptionOrNull()
        }
        if (failure == null) {
            _points.value = _points.value + point
        } else {
            failRecording("Track fix not saved; recording stopped: ${failure.message}")
        }
    }

    fun failRecording(
        message: String,
        settingsTarget: TrackRecordingSettingsTarget? = null,
    ) {
        interruptRecording(message, settingsTarget, requestServiceStop = true)
    }

    fun onLocationAccessChanged(locationAccess: LocationAccess) {
        val next = TrackRecordingReducer.reduce(
            _uiState.value,
            TrackRecordingEvent.PermissionChanged(locationAccess),
        )
        if (next == _uiState.value) return
        _isRecording.value = false
        _uiState.value = next
        _persistError.value = next.message
        clearRecordingKey()
        stopRecordingService()
    }

    /** Activity locking clears the general DEK, not an authorized session key. */
    fun onMissionKeyLock() {
        val authorized = synchronized(recordingKeyLock) {
            _uiState.value.isAuthorizedSession && recordingKey != null
        }
        if (authorized) return
        clearRecordingKey()
        if (_uiState.value.isAuthorizedSession) {
            failRecording("Recording stopped because its session key was unavailable.")
        }
    }

    internal fun hasRetainedRecordingKey(): Boolean =
        synchronized(recordingKeyLock) { recordingKey != null }

    private fun clearRecordingKey() = synchronized(recordingKeyLock) {
        clearRecordingKeyLocked()
    }

    private fun clearRecordingKeyLocked() {
        recordingKey?.fill(0)
        recordingKey = null
        currentSessionGeneration = null
    }

    private fun interruptRecording(
        message: String,
        settingsTarget: TrackRecordingSettingsTarget? = null,
        requestServiceStop: Boolean,
    ) {
        _isRecording.value = false
        _persistError.value = message
        _uiState.value = TrackRecordingReducer.reduce(
            _uiState.value,
            TrackRecordingEvent.Interrupted(message, settingsTarget),
        )
        clearRecordingKey()
        if (requestServiceStop) stopRecordingService()
    }

    private companion object {
        const val ACTIVATION_TIMEOUT_MILLIS = 10_000L
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
