package com.tacmap.sync

/**
 * Inbound frame budget (plans/04 section 12). The old single 200 frames / 10 s
 * live budget was exactly one relay sender's allowance, so one member's import
 * or our own op-acks disconnected the room (S2-04, S4-02). Now:
 *
 *  - initial: cumulative 10,000 frames / 54,525,952 bytes until hello-ack
 *  - room: scales with authenticated sessions, tumbling 10 s window
 *  - self-response: our own acks/nacks, classified by `t`, own window
 *  - background: frames we drop unparsed in background presence mode
 *
 * Over any of them we close 1008 and reconnect with transient backoff.
 */
internal class SyncReceiveBudget {
    enum class Phase { INITIAL, LIVE, BACKGROUND }
    enum class Bucket { ROOM, SELF_RESPONSE }

    private class Window(var startMs: Long = 0L, var frames: Int = 0, var bytes: Long = 0L) {
        fun reset(nowMs: Long) {
            startMs = nowMs
            frames = 0
            bytes = 0L
        }
    }

    private var generation: Long? = null
    private var phase: Phase? = null
    private val initial = Window()
    private val room = Window()
    private val self = Window()
    private val background = Window()

    @Synchronized
    fun admit(
        newGeneration: Long,
        byteCount: Int,
        newPhase: Phase,
        bucket: Bucket,
        activeSessions: Int,
        nowMs: Long,
    ): Boolean {
        if (byteCount < 0) return false
        if (generation != newGeneration || phase != newPhase) {
            generation = newGeneration
            phase = newPhase
            initial.reset(nowMs)
            room.reset(nowMs)
            self.reset(nowMs)
            background.reset(nowMs)
        }
        return when (newPhase) {
            Phase.INITIAL -> take(initial, byteCount, INITIAL_MAX_FRAMES, INITIAL_MAX_BYTES, nowMs, windowed = false)
            Phase.BACKGROUND -> take(background, byteCount, BACKGROUND_MAX_FRAMES, BACKGROUND_MAX_BYTES, nowMs, windowed = true)
            Phase.LIVE -> when (bucket) {
                Bucket.SELF_RESPONSE -> take(self, byteCount, SELF_MAX_FRAMES, SELF_MAX_BYTES, nowMs, windowed = true)
                Bucket.ROOM -> take(
                    room, byteCount,
                    roomFrameLimit(activeSessions), roomByteLimit(activeSessions),
                    nowMs, windowed = true,
                )
            }
        }
    }

    private fun take(
        window: Window,
        byteCount: Int,
        maxFrames: Int,
        maxBytes: Long,
        nowMs: Long,
        windowed: Boolean,
    ): Boolean {
        if (windowed && (nowMs < window.startMs || nowMs - window.startMs >= WINDOW_MS)) window.reset(nowMs)
        if (window.frames >= maxFrames || byteCount.toLong() > maxBytes - window.bytes) return false
        window.frames += 1
        window.bytes += byteCount
        return true
    }

    companion object {
        const val WINDOW_MS = 10_000L
        const val INITIAL_MAX_FRAMES = 10_000
        const val INITIAL_MAX_BYTES = 54_525_952L
        const val ROOM_BASE_FRAMES = 600
        const val ROOM_PER_SESSION_FRAMES = 25
        const val ROOM_MAX_FRAMES = 4_000
        const val ROOM_BASE_BYTES = 12_582_912L
        const val ROOM_PER_SESSION_BYTES = 262_144L
        const val ROOM_MAX_BYTES = 50_331_648L
        const val SELF_MAX_FRAMES = 400
        const val SELF_MAX_BYTES = 1_048_576L
        const val BACKGROUND_MAX_FRAMES = 4_000
        const val BACKGROUND_MAX_BYTES = 50_331_648L

        val SELF_RESPONSE_TYPES = setOf(
            "op-ack", "op-nack", "chat-ack", "chat-nack", "chat-key-ack", "chat-key-nack", "hello-ack",
        )

        fun bucketFor(type: String?): Bucket =
            if (type != null && type in SELF_RESPONSE_TYPES) Bucket.SELF_RESPONSE else Bucket.ROOM

        fun roomFrameLimit(sessions: Int): Int =
            minOf(ROOM_MAX_FRAMES.toLong(), ROOM_BASE_FRAMES + ROOM_PER_SESSION_FRAMES.toLong() * sessions.coerceAtLeast(0)).toInt()

        fun roomByteLimit(sessions: Int): Long =
            minOf(ROOM_MAX_BYTES, ROOM_BASE_BYTES + ROOM_PER_SESSION_BYTES * sessions.coerceAtLeast(0))
    }
}
