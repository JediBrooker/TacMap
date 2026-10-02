package com.tacmap.sync

import java.math.BigInteger

/**
 * Hello epoch selection (plans/04 section 14, S3-11).
 *
 * next = max(persisted + 1 (or 1),
 *            unix minutes            if nothing persisted or last close was 4014,
 *            2 * rejected            if last close was 4014)
 * then persist next, or next + 64 when background presence is opted in.
 *
 * Replay protection doesn't change: the relay and every peer only take a
 * strictly higher epoch and we still persist before signing. Skipping values
 * can't make an old hello acceptable again.
 */
internal object HelloEpochPolicy {
    val MAX: BigInteger = BigInteger("ffffffffffffffff", 16)
    const val BACKGROUND_SPARE_BLOCK = 64
    const val TIME_FLOOR_UNIT_MS = 60_000L

    sealed interface Result {
        data class Next(
            val next: BigInteger,
            /** What we write to disk before signing. Spares are next+1..persisted. */
            val persisted: BigInteger,
        ) : Result {
            val nextHex: String get() = hex(next)
            val persistedHex: String get() = hex(persisted)
            val spareCount: Int get() = persisted.subtract(next).toInt()
        }
        data object Exhausted : Result
    }

    /** The floor to hand to [SyncReplayState.reserveHelloEpoch], or null for a plain +1. */
    fun floor(
        persisted: BigInteger?,
        nowMs: Long,
        after4014: Boolean,
        rejected: BigInteger?,
    ): BigInteger? {
        val candidates = ArrayList<BigInteger>(2)
        if (persisted == null || after4014) {
            candidates += BigInteger.valueOf(Math.floorDiv(nowMs, TIME_FLOOR_UNIT_MS).coerceAtLeast(0L))
        }
        if (after4014 && rejected != null) candidates += rejected.shiftLeft(1)
        return candidates.maxOrNull()
    }

    fun next(
        persisted: BigInteger?,
        nowMs: Long,
        after4014: Boolean,
        rejected: BigInteger?,
        bgOptIn: Boolean,
    ): Result = reserve(
        persisted = persisted,
        floor = floor(persisted, nowMs, after4014, rejected),
        spare = if (bgOptIn) BACKGROUND_SPARE_BLOCK else 0,
    )

    /** The core used by SyncReplayState so the stored-state path can't drift. */
    fun reserve(persisted: BigInteger?, floor: BigInteger?, spare: Int): Result {
        val plain = (persisted ?: BigInteger.ZERO).add(BigInteger.ONE)
        val next = if (floor != null && floor > plain) floor else plain
        if (next > MAX) return Result.Exhausted
        val stored = next.add(BigInteger.valueOf(spare.coerceAtLeast(0).toLong()))
        if (stored > MAX) return Result.Exhausted
        return Result.Next(next, stored)
    }

    fun parse(hex: String?): BigInteger? =
        hex?.let(SyncIdentity::parseHelloEpoch)?.let { BigInteger(it, 16) }

    fun hex(value: BigInteger): String = value.toString(16).padStart(16, '0')
}
