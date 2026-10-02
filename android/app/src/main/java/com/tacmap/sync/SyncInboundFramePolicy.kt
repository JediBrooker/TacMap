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

// The single 200 frames / 10 s live budget that used to live here is now
// SyncReceiveBudget (room, self-response, initial and background budgets).
