package com.tacmap.sync

import kotlin.math.pow
import kotlin.random.Random

internal fun isCurrentSocketCallback(
    scheduledGeneration: Long,
    currentGeneration: Long,
    isCurrentSocket: Boolean,
): Boolean = scheduledGeneration == currentGeneration && isCurrentSocket

/**
 * Bounded exponential reconnect delay. A transport open is not success: callers
 * reset this policy only after the snapshot and authenticated handshake finish.
 */
internal class SyncReconnectBackoff(
    private val baseDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 30_000L,
    private val jitterFraction: Double = 0.20,
    private val randomUnit: () -> Double = { Random.nextDouble() },
) {
    private var attempt = 0

    fun nextDelayMs(): Long {
        val exponent = attempt.coerceAtMost(20)
        val unjittered = (baseDelayMs.toDouble() * 2.0.pow(exponent))
            .coerceAtMost(maximumDelayMs.toDouble())
        attempt = (attempt + 1).coerceAtMost(21)
        val unit = randomUnit().coerceIn(0.0, 1.0)
        val multiplier = 1.0 + ((unit * 2.0 - 1.0) * jitterFraction)
        return (unjittered * multiplier).toLong().coerceIn(1L, maximumDelayMs)
    }

    fun reset() {
        attempt = 0
    }

    internal val attemptCount: Int get() = attempt
}
