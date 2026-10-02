package com.tacmap.sync

import kotlin.math.pow
import kotlin.random.Random

/** Backoff classes from the client contract (plans/04 section 8). Names match
 * testdata/sync_client_behaviour.json so the fixture test can look them up. */
internal enum class SyncBackoffClass(
    val wireName: String,
    val floorMs: Long,
    val spreadMs: Long,
    val capMs: Long,
) {
    TRANSIENT("transient", 250L, 750L, 30_000L),
    SLOW_BUSY("slow_busy", 15_000L, 15_000L, 300_000L),
    SLOW_RATE("slow_rate", 60_000L, 30_000L, 300_000L),
    SLOW_STORAGE("slow_storage", 30_000L, 30_000L, 300_000L),
    SLOW_UNKNOWN("slow_unknown", 30_000L, 30_000L, 300_000L);

    companion object {
        fun fromWire(name: String): SyncBackoffClass? = entries.firstOrNull { it.wireName == name }
    }
}

/**
 * Full jitter between the class floor and the exponential ceiling, one attempt
 * counter shared by every class in a join. The counter only resets once a
 * session proved itself (first op-ack, or 30 s connected). Resetting at
 * hello-ack is what gave us the 1 s reconnect loops in S3-02 / S3-15.
 *
 * Pure: time and randomness come in from the caller.
 */
internal class SyncBackoffPolicy(
    private val random: () -> Double = { Random.nextDouble() },
) {
    var attempt: Int = 0
        private set

    private var connectedSinceMs: Long? = null
    private var lastAttemptStartMs: Long? = null
    private var pendingClass: SyncBackoffClass? = null
    private var pendingConnectAtMs: Long? = null

    /** Delay for the next reconnect, then bumps the shared counter. */
    fun nextDelay(cls: SyncBackoffClass): Double {
        val r = random().coerceIn(0.0, 1.0)
        val ceiling = minOf(
            (cls.capMs - cls.floorMs).toDouble(),
            cls.spreadMs.toDouble() * 2.0.pow(attempt),
        )
        attempt = (attempt + 1).coerceAtMost(ATTEMPT_CAP)
        return cls.floorMs + r * ceiling
    }

    /** Schedules a reconnect and remembers it so reachability can pull it in. */
    fun scheduleReconnect(cls: SyncBackoffClass, nowMs: Long): Long {
        connectedSinceMs = null
        val delayMs = nextDelay(cls).toLong()
        pendingClass = cls
        pendingConnectAtMs = nowMs + delayMs
        return delayMs
    }

    fun attemptStarted(nowMs: Long) {
        lastAttemptStartMs = nowMs
        pendingClass = null
        pendingConnectAtMs = null
    }

    /** hello-ack. Starts the stable timer, does NOT reset the counter. */
    fun connected(nowMs: Long) {
        connectedSinceMs = nowMs
    }

    /** Any session end. A session that never got stable keeps its growth. */
    fun sessionEnded() {
        connectedSinceMs = null
    }

    /** First op-ack of the session counts as proof the session works. */
    fun opAcked() {
        if (connectedSinceMs != null) attempt = 0
    }

    /** Polled by the manager; resets once we've been connected for long enough. */
    fun tick(nowMs: Long) {
        val since = connectedSinceMs ?: return
        if (nowMs - since >= STABLE_SESSION_MS) attempt = 0
    }

    /** Next time a stable-session tick is worth running, or null. */
    fun stableDeadlineMs(): Long? = connectedSinceMs?.let { it + STABLE_SESSION_MS }

    /**
     * Network came back. Only a pending transient reconnect gets pulled in, and
     * never closer than 2 s after the last attempt started. Slow classes keep
     * their floor. Returns when the reconnect should now happen, or null when
     * nothing is pending.
     */
    fun networkAvailable(nowMs: Long): Long? {
        val at = pendingConnectAtMs ?: return null
        if (pendingClass != SyncBackoffClass.TRANSIENT) return at
        val earliest = maxOf(nowMs, (lastAttemptStartMs ?: Long.MIN_VALUE / 2) + MIN_SPACING_FROM_LAST_ATTEMPT_MS)
        val pulled = minOf(at, earliest)
        pendingConnectAtMs = pulled
        return pulled
    }

    fun pendingReconnectAtMs(): Long? = pendingConnectAtMs

    /** Explicit Retry, leave, or a new join. */
    fun reset() {
        attempt = 0
        connectedSinceMs = null
        pendingClass = null
        pendingConnectAtMs = null
    }

    /** Test hook for the reachability vector that starts at attempt 6. */
    internal fun forceAttempt(value: Int) {
        attempt = value.coerceIn(0, ATTEMPT_CAP)
    }

    companion object {
        const val ATTEMPT_CAP = 20
        const val STABLE_SESSION_MS = 30_000L
        const val MIN_SPACING_FROM_LAST_ATTEMPT_MS = 2_000L
    }
}
