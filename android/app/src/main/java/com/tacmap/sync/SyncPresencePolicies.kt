package com.tacmap.sync

import android.location.Location
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bits of an android Location that presence needs, pulled out at the
 * boundary so the send path also runs on a plain JVM in tests.
 */
internal data class PresenceFixSample(
    val provider: String?,
    val latitude: Double,
    val longitude: Double,
    val accuracyMetres: Double?,
    val bearingDegrees: Double?,
    val speedMps: Double?,
    val elapsedRealtimeNanos: Long,
) {
    /** Same rules as PresenceLocationQuality.fromLocation. */
    fun qualityFix(): PresenceLocationFix? {
        val accuracy = accuracyMetres ?: return null
        val timeMs = elapsedRealtimeNanos / 1_000_000L
        if (timeMs <= 0 || !PresenceLocationQuality.isUsableAccuracy(accuracy)) return null
        return PresenceLocationFix(latitude, longitude, accuracy, timeMs)
    }

    companion object {
        fun from(location: Location) = PresenceFixSample(
            provider = location.provider,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMetres = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            bearingDegrees = if (location.hasBearing()) location.bearing.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
        )
    }
}

/**
 * Presence counter persistence in strides (plans/04 section 17.1, S5-01,
 * S3-12). Write before exposing a counter 16+ past the one on disk, flush
 * every 60 s, write exact at clean points; after a crash the floor is
 * disk + 15 so no counter accepted before the crash comes back.
 */
internal object PresenceFencePersistence {
    const val STRIDE = 16L
    const val FLUSH_MS = 60_000L
    const val CRASH_FLOOR_ADD = 15L

    fun loadFloor(persisted: Long, exact: Boolean): Long =
        if (exact) persisted else min(VersionStamp.MAX_COUNTER, persisted + CRASH_FLOOR_ADD)

    /** Disk still saying exact means we must write the flag false before anything new shows. */
    fun mustPersistBeforeExposing(counter: Long, durable: Long, exactOnDisk: Boolean): Boolean =
        exactOnDisk || counter - durable >= STRIDE
}

/**
 * Foreground stationary suppression (plans/04 section 20.1, S5-10). The
 * cadence still gates on 5 s and a newer fix, this decides whether the fix is
 * worth a frame. Background cadence doesn't go through here.
 */
internal class PresenceSendPolicy {
    data class Fix(
        val lat: Double,
        val lon: Double,
        val speedMps: Double?,
        val courseDeg: Double?,
        val horizontalAccuracyM: Double?,
    )

    private var lastSent: Fix? = null
    private var lastSentAtMs: Long? = null
    private var lastConfig: Any? = null

    /** New authenticated session: the next frame goes out regardless. */
    fun beginSession() {
        lastSent = null
        lastSentAtMs = null
    }

    fun reset() {
        beginSession()
        lastConfig = null
    }

    fun shouldSend(fix: Fix, nowMs: Long, config: Any): Boolean {
        val sentAt = lastSentAtMs
        val previous = lastSent
        // first frame of the session goes straight out
        if (sentAt == null || previous == null) return true
        if (nowMs - sentAt < MIN_INTERVAL_MS) return false
        if (config != lastConfig) return true
        val moved = distanceMetres(previous.lat, previous.lon, fix.lat, fix.lon)
        if (moved > maxOf(MOVE_MIN_METRES, fix.horizontalAccuracyM ?: 0.0)) return true
        val speed = fix.speedMps ?: 0.0
        val course = fix.courseDeg
        val previousCourse = previous.courseDeg
        if (speed >= COURSE_CHECK_MIN_SPEED_MPS && course != null && previousCourse != null &&
            angleBetween(course, previousCourse) > COURSE_CHANGE_DEGREES
        ) return true
        if (abs(speed - (previous.speedMps ?: 0.0)) > SPEED_CHANGE_MPS) return true
        return nowMs - sentAt >= STATIONARY_HEARTBEAT_MS
    }

    fun recordSent(fix: Fix, nowMs: Long, config: Any) {
        lastSent = fix
        lastSentAtMs = nowMs
        lastConfig = config
    }

    companion object {
        const val MIN_INTERVAL_MS = 5_000L
        const val STATIONARY_HEARTBEAT_MS = 20_000L
        const val MOVE_MIN_METRES = 10.0
        const val COURSE_CHANGE_DEGREES = 20.0
        const val COURSE_CHECK_MIN_SPEED_MPS = 1.5
        const val SPEED_CHANGE_MPS = 1.5
        const val EARTH_RADIUS_M = 6_371_008.8

        fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }

        fun angleBetween(a: Double, b: Double): Double {
            val d = abs(((a - b) % 360.0 + 360.0) % 360.0)
            return if (d > 180.0) 360.0 - d else d
        }
    }
}

/**
 * A remote session advertising more than the 45 s foreground retention is in
 * background presence mode and drops chat unread, so a selected-unit send to
 * it would be acked Routed and lost (plans/04 section 21.6, S6-04). Room sends
 * are unchanged, Routed never claimed delivery there.
 */
internal object ChatSendGate {
    const val FOREGROUND_RETENTION_SECONDS = 45L

    fun blockedForBackground(direct: Boolean, recipientRetentionSeconds: Long?): Boolean =
        direct && recipientRetentionSeconds != null && recipientRetentionSeconds > FOREGROUND_RETENTION_SECONDS
}

/**
 * Background presence decisions (plans/04 section 21). Pure, the manager
 * feeds it time, socket state and spare epochs.
 */
internal class BackgroundPresencePolicy(
    private val reconnectEnabled: Boolean = RECONNECT_ENABLED,
) {
    enum class Action { SEND, CONNECT, WAIT, PAUSE }

    private var consecutiveFailures = 0
    private var lastAttemptAtMs: Long? = null

    /**
     * A background fix is due. [socketUsable] means the authenticated socket
     * passed the wake probe (21.3).
     */
    fun onFixDue(nowMs: Long, socketUsable: Boolean, sparesLeft: Int, eligible: Boolean): Action {
        if (!eligible) return Action.PAUSE
        if (socketUsable) return Action.SEND
        // THREAT_MODEL section 7 still says a dropped socket pauses, see contract D1
        if (!reconnectEnabled) return Action.PAUSE
        if (sparesLeft <= 0 || consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return Action.PAUSE
        val last = lastAttemptAtMs
        if (last != null && nowMs - last < MIN_SPACING_MS) return Action.WAIT
        lastAttemptAtMs = nowMs
        return Action.CONNECT
    }

    fun attemptFailed() { consecutiveFailures += 1 }
    fun attemptSucceeded() { consecutiveFailures = 0 }

    /** False once a later fix could only ever get PAUSE, so the caller can pause right at the drop. */
    fun canStillReconnect(sparesLeft: Int, eligible: Boolean): Boolean =
        reconnectEnabled && eligible && sparesLeft > 0 && consecutiveFailures < MAX_CONSECUTIVE_FAILURES

    fun reset() {
        consecutiveFailures = 0
        lastAttemptAtMs = null
    }

    companion object {
        /** Ships true only together with doc change D1 (plans/04 section 21.4). */
        const val RECONNECT_ENABLED = false
        const val MIN_SPACING_MS = 60_000L
        const val MAX_CONSECUTIVE_FAILURES = 3
        const val MAX_SNAPSHOT_DRAIN_BYTES = 4L * 1024L * 1024L
        const val BRIDGE_FIX_MAX_AGE_MS = 120_000L
        const val LIVENESS_MAX_AGE_MS = 75_000L
        const val PROBE_PONG_TIMEOUT_MS = 10_000L
        const val WAKE_LOCK_MAX_MS = 20_000L
        const val BACKGROUND_PING_INTERVAL_MS = 60_000L
        const val BACKGROUND_DEAD_AFTER_MS = 30_000L

        /** Wake-safe keepalive (21.3): quiet for 75 s means ping and wait for a pong before sending. */
        fun needsProbe(nowMs: Long, lastInboundMs: Long?): Boolean =
            lastInboundMs == null || nowMs - lastInboundMs > LIVENESS_MAX_AGE_MS
    }
}

/**
 * The spare hello epochs above the last foreground hello (plans/04 sections
 * 14 and 21.4). The foreground already wrote last + spares to disk before it
 * signed anything, so handing these out in background needs no durable write
 * and a crash can't make one get used twice. In memory only, each one once.
 */
internal class BackgroundSpareEpochs(lastForegroundEpoch: java.math.BigInteger, spares: Int) {
    private var next: java.math.BigInteger = lastForegroundEpoch.add(java.math.BigInteger.ONE)

    /** Never past ffffffffffffffff, whatever the caller claims. */
    var left: Int = HelloEpochPolicy.MAX.subtract(lastForegroundEpoch)
        .min(java.math.BigInteger.valueOf(spares.coerceAtLeast(0).toLong())).toInt()
        private set

    fun take(): java.math.BigInteger? {
        if (left <= 0) return null
        val epoch = next
        next = next.add(java.math.BigInteger.ONE)
        left -= 1
        return epoch
    }
}
