package com.tacmap.sync

internal enum class SyncInboundFrameRejection {
    OVERSIZED,
    BINARY,
    RATE_LIMITED,
}

internal sealed interface SyncInboundFrameDecision {
    data class Accept(val byteCount: Int) : SyncInboundFrameDecision
    data class Reject(val reason: SyncInboundFrameRejection) : SyncInboundFrameDecision
}

/** Admission by UTF-8 wire bytes, before JSONObject can allocate or parse. */
internal object SyncInboundFramePolicy {
    const val MAX_FRAME_BYTES = 1_048_576

    fun inspectText(text: String): SyncInboundFrameDecision {
        // UTF-16 code units are a safe lower bound on UTF-8 bytes. Reject a
        // huge decoded String before duplicating it into a byte array.
        if (text.length > MAX_FRAME_BYTES) {
            return SyncInboundFrameDecision.Reject(SyncInboundFrameRejection.OVERSIZED)
        }
        val byteCount = text.toByteArray(Charsets.UTF_8).size
        return if (byteCount <= MAX_FRAME_BYTES) {
            SyncInboundFrameDecision.Accept(byteCount)
        } else {
            SyncInboundFrameDecision.Reject(SyncInboundFrameRejection.OVERSIZED)
        }
    }

    fun inspectBinary(byteCount: Int): SyncInboundFrameDecision =
        SyncInboundFrameDecision.Reject(
            if (byteCount > MAX_FRAME_BYTES) {
                SyncInboundFrameRejection.OVERSIZED
            } else {
                SyncInboundFrameRejection.BINARY
            }
        )
}

/** Claims at most one protocol close for a connection generation. */
internal class SyncInboundFrameCloseGate {
    private var closedGeneration: Long? = null

    @Synchronized
    fun claimClose(generation: Long): Boolean {
        if (closedGeneration == generation) return false
        closedGeneration = generation
        return true
    }
}

/**
 * Generation-scoped preparse budget. Initial handshakes/snapshots preserve the
 * bounded snapshot allowance while counting malformed/control frames too;
 * entering live mode resets into the smaller rolling window.
 */
internal class SyncLiveReceiveBudget {
    enum class Phase { INITIAL, LIVE }

    private var generation: Long? = null
    private var phase: Phase? = null
    private var windowStartMs = 0L
    private var frames = 0
    private var bytes = 0

    @Synchronized
    fun admit(
        newGeneration: Long,
        byteCount: Int,
        newPhase: Phase,
        nowMs: Long,
    ): Boolean {
        if (byteCount < 0) return false
        if (generation != newGeneration || phase != newPhase) {
            generation = newGeneration
            phase = newPhase
            windowStartMs = nowMs
            frames = 0
            bytes = 0
        }
        if (newPhase == Phase.INITIAL) {
            if (frames >= MAX_INITIAL_FRAMES || byteCount > MAX_INITIAL_BYTES - bytes) return false
            frames += 1
            bytes += byteCount
            return true
        }
        if (nowMs < windowStartMs || nowMs - windowStartMs >= WINDOW_MS) {
            windowStartMs = nowMs
            frames = 0
            bytes = 0
        }
        if (frames >= MAX_FRAMES || byteCount > MAX_BYTES - bytes) return false
        frames += 1
        bytes += byteCount
        return true
    }

    companion object {
        const val MAX_INITIAL_FRAMES = 10_000
        const val MAX_INITIAL_BYTES = 54_525_952
        const val MAX_FRAMES = 200
        const val MAX_BYTES = 4 * 1_048_576
        const val WINDOW_MS = 10_000L
    }
}
