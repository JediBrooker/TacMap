package com.tacmap.sync

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Priority order: control > presence > chat > retry > mutation. */
internal enum class SyncOutboundClass(val usesInteractiveReserve: Boolean) {
    CONTROL(true),
    PRESENCE(true),
    CHAT(true),
    RETRY(false),
    MUTATION(false),
}

/**
 * Client side pacing so we stay under relayLimits.clientPacing (150 frames /
 * 3 MiB per 10 s) no matter how much the diff wants to send. Two token
 * buckets that refill continuously, a little reserve that bulk work can't eat,
 * and a cap on unacked put/del frames. Pure: callers pass time in, the manager
 * owns the actual socket write.
 */
internal class SyncOutboundPacer<T>(
    startMs: Double,
    after4008: Boolean = false,
) {
    data class Entry<T>(
        val cls: SyncOutboundClass,
        val bytes: Int,
        /** Mutations: local object id, so a newer reservation can supersede it. */
        val key: String?,
        val payload: T,
    )

    private var frameTokens: Double =
        if (after4008) AFTER_4008_FRAME_TOKENS.toDouble() else FRAME_CAPACITY.toDouble()
    private var byteTokens: Double =
        if (after4008) AFTER_4008_BYTE_TOKENS.toDouble() else BYTE_CAPACITY.toDouble()
    private var lastRefillMs = startMs
    private val queues = SyncOutboundClass.entries.associateWith { ArrayDeque<Entry<T>>() }
    private val inFlight = HashMap<String, Int>()
    private var inFlightBytes = 0L

    val queuedCount: Int get() = queues.values.sumOf { it.size }
    val inFlightCount: Int get() = inFlight.size
    fun isEmpty(): Boolean = queues.values.all { it.isEmpty() }

    fun offer(entry: Entry<T>) {
        val queue = queues.getValue(entry.cls)
        when (entry.cls) {
            // only the newest presence matters, an older queued one is just stale
            SyncOutboundClass.PRESENCE -> queue.clear()
            SyncOutboundClass.MUTATION -> if (entry.key != null) {
                queue.removeAll { it.key == entry.key }
            }
            else -> Unit
        }
        queue.addLast(entry)
    }

    /** Drops queued (not yet written) entries for [key], e.g. superseded or resolved. */
    fun removeQueued(key: String): Int {
        var removed = 0
        for (cls in listOf(SyncOutboundClass.MUTATION, SyncOutboundClass.RETRY)) {
            val queue = queues.getValue(cls)
            val before = queue.size
            queue.removeAll { it.key == key }
            removed += before - queue.size
        }
        return removed
    }

    fun removeQueuedIf(predicate: (Entry<T>) -> Boolean) {
        queues.values.forEach { it.removeAll(predicate) }
    }

    fun hasQueued(key: String, cls: SyncOutboundClass): Boolean =
        queues.getValue(cls).any { it.key == key }

    /** Next entry allowed out right now, tokens already spent. Strict priority. */
    fun poll(nowMs: Double): Entry<T>? {
        refill(nowMs)
        for (cls in SyncOutboundClass.entries) {
            val queue = queues.getValue(cls)
            val head = queue.firstOrNull() ?: continue
            if (!canSend(head)) return null
            queue.removeFirst()
            frameTokens -= 1.0
            byteTokens -= head.bytes.toDouble()
            if (cls == SyncOutboundClass.MUTATION && head.key != null) {
                // a newer copy of the same object takes over the old copy's
                // slot. the old ack never matches anything anymore, so keeping
                // its bytes would leak window space forever
                inFlight.put(head.key, head.bytes)?.let { inFlightBytes -= it }
                inFlightBytes += head.bytes
            }
            return head
        }
        return null
    }

    /**
     * When the head could go if only tokens were the problem. Null when the
     * queue is empty or we're waiting on acks for the in-flight window.
     */
    fun nextReadyAtMs(nowMs: Double): Double? {
        refill(nowMs)
        for (cls in SyncOutboundClass.entries) {
            val head = queues.getValue(cls).firstOrNull() ?: continue
            if (cls == SyncOutboundClass.MUTATION && blockedByInFlight(head)) return null
            val (needFrames, needBytes) = need(head)
            val waitFrames = max(0.0, (needFrames - frameTokens) / FRAME_RATE_PER_MS)
            val waitBytes = max(0.0, (needBytes - byteTokens) / BYTE_RATE_PER_MS)
            return nowMs + max(waitFrames, waitBytes)
        }
        return null
    }

    /** Ack, nack resolution or socket loss for an in-flight mutation. */
    fun release(key: String) {
        val bytes = inFlight.remove(key) ?: return
        inFlightBytes -= bytes
    }

    fun clear() {
        queues.values.forEach { it.clear() }
        inFlight.clear()
        inFlightBytes = 0L
    }

    private fun canSend(entry: Entry<T>): Boolean {
        if (entry.cls == SyncOutboundClass.MUTATION && blockedByInFlight(entry)) return false
        val (needFrames, needBytes) = need(entry)
        return frameTokens + EPSILON >= needFrames && byteTokens + EPSILON >= needBytes
    }

    private fun blockedByInFlight(entry: Entry<T>): Boolean {
        // whatever the same object already has in flight gets replaced, not added to
        val replaced = entry.key?.let { inFlight[it] }
        val others = if (replaced != null) inFlight.size - 1 else inFlight.size
        if (others == 0) return false
        if (others >= MAX_IN_FLIGHT_MUTATIONS) return true
        return inFlightBytes - (replaced ?: 0) + entry.bytes > MAX_IN_FLIGHT_BYTES
    }

    private fun need(entry: Entry<T>): Pair<Double, Double> {
        val reserveFrames = if (entry.cls.usesInteractiveReserve) 0 else RESERVE_FRAMES
        val reserveBytes = if (entry.cls.usesInteractiveReserve) 0L else RESERVE_BYTES
        val needFrames = (1 + reserveFrames).toDouble()
        // a frame that can never leave the reserve standing waits for a full bucket instead
        val needBytes = min(entry.bytes.toLong() + reserveBytes, BYTE_CAPACITY).toDouble()
        return needFrames to max(needBytes, entry.bytes.toDouble().coerceAtMost(BYTE_CAPACITY.toDouble()))
    }

    private fun refill(nowMs: Double) {
        val elapsed = nowMs - lastRefillMs
        if (elapsed > 0) {
            frameTokens = min(FRAME_CAPACITY.toDouble(), frameTokens + elapsed * FRAME_RATE_PER_MS)
            byteTokens = min(BYTE_CAPACITY.toDouble(), byteTokens + elapsed * BYTE_RATE_PER_MS)
        }
        lastRefillMs = max(lastRefillMs, nowMs)
    }

    companion object {
        const val WINDOW_MS = 10_000L
        const val FRAME_CAPACITY = 30
        const val FRAME_REFILL_PER_WINDOW = 120
        const val BYTE_CAPACITY = 1_048_576L
        const val BYTE_REFILL_PER_WINDOW = 2_097_152L
        const val RESERVE_FRAMES = 10
        const val RESERVE_BYTES = 65_536L
        const val MAX_IN_FLIGHT_MUTATIONS = 32
        const val MAX_IN_FLIGHT_BYTES = 1_048_576L
        const val AFTER_4008_FRAME_TOKENS = 15
        const val AFTER_4008_BYTE_TOKENS = 524_288L
        private const val FRAME_RATE_PER_MS = FRAME_REFILL_PER_WINDOW.toDouble() / WINDOW_MS
        private const val BYTE_RATE_PER_MS = BYTE_REFILL_PER_WINDOW.toDouble() / WINDOW_MS
        private const val EPSILON = 1e-6
    }
}

/**
 * Retransmit timing (plans/04 section 11.3). The timer for a copy starts when
 * that copy actually finished writing, not when it was queued, and we never
 * queue another copy while one is still sitting unwritten (S3-06).
 */
internal class SyncAckTimer {
    enum class Due { NOTHING, RETRANSMIT, EXHAUSTED }

    private data class State(
        var attempt: Int,
        var copyUnwritten: Boolean,
        var deadlineMs: Long?,
    )

    private val states = HashMap<String, State>()

    fun enqueued(rid: String) {
        states[rid] = State(attempt = 1, copyUnwritten = true, deadlineMs = null)
    }

    /** Returns the ack deadline for the copy that just finished writing. */
    fun writeComplete(rid: String, nowMs: Long, aheadInFlight: Int): Long? {
        val state = states[rid] ?: return null
        state.copyUnwritten = false
        val deadline = nowMs + timeoutMs(state.attempt, aheadInFlight)
        state.deadlineMs = deadline
        return deadline
    }

    /** Deadline check. RETRANSMIT means: queue one more copy, attempt already bumped. */
    fun check(rid: String, nowMs: Long): Due {
        val state = states[rid] ?: return Due.NOTHING
        if (state.copyUnwritten) return Due.NOTHING
        val deadline = state.deadlineMs ?: return Due.NOTHING
        if (nowMs < deadline) return Due.NOTHING
        if (state.attempt >= MAX_ATTEMPTS) {
            states.remove(rid)
            return Due.EXHAUSTED
        }
        state.attempt += 1
        state.copyUnwritten = true
        state.deadlineMs = null
        return Due.RETRANSMIT
    }

    /** storage nack: wait the normal timeout from now before the next copy. */
    fun restartTimer(rid: String, nowMs: Long, aheadInFlight: Int): Long? {
        val state = states[rid] ?: return null
        if (state.copyUnwritten) return null
        val deadline = nowMs + timeoutMs(state.attempt, aheadInFlight)
        state.deadlineMs = deadline
        return deadline
    }

    fun attempt(rid: String): Int? = states[rid]?.attempt
    fun copyUnwritten(rid: String): Boolean = states[rid]?.copyUnwritten == true
    fun deadline(rid: String): Long? = states[rid]?.deadlineMs
    fun nextDeadlineMs(): Long? = states.values.mapNotNull { it.deadlineMs }.minOrNull()
    fun pendingRequestIds(): Set<String> = states.keys.toSet()

    fun resolved(rid: String) {
        states.remove(rid)
    }

    fun clear() = states.clear()

    companion object {
        const val BASE_MS = 5_000L
        const val PER_AHEAD_MS = 200L
        const val CAP_MS = 30_000L
        const val MAX_ATTEMPTS = 3
        const val ANDROID_WRITE_POLL_MS = 250L

        fun timeoutMs(attempt: Int, aheadInFlight: Int): Long {
            val n = attempt.coerceAtLeast(1)
            val raw = (BASE_MS + PER_AHEAD_MS * aheadInFlight.coerceAtLeast(0)).toDouble() * 2.0.pow(n - 1)
            return min(CAP_MS.toDouble(), raw).toLong()
        }
    }
}
