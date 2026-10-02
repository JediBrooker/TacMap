package com.tacmap.sync

internal data class SyncNackDecision(
    /** Op leaves the tracker, timers die, and it is NOT put into reconciliation. */
    val resolveOp: Boolean,
    val retry: Boolean = false,
    val reconnect: Boolean = false,
    val localClose: SyncLocalClose? = null,
    val issue: SyncIssueCode? = null,
    val suppressUntilLocalEdit: Boolean = false,
    val pauseMutationsForJoin: Boolean = false,
    val markConfirmed: Boolean = false,
)

/**
 * op-nack reactions (plans/04 section 6). Nack codes are unauthenticated relay
 * statements so the only thing they get to do is drop, retry, pause or
 * reconnect our own work. Never SECURITY, never touches replay state.
 */
internal object SyncNackPolicy {
    fun decide(
        code: String,
        retryFlag: Boolean,
        rejectedStampEqualsOwnPersisted: Boolean = false,
        wireIdSkippedThisJoin: Boolean = false,
    ): SyncNackDecision = when (code) {
        "stale", "not-found" -> {
            // ordinary LWW loss. if the relay already holds our exact stamp
            // (S3-15) it's really a confirmation, unless that id was skipped,
            // in which case we can't tell and just stay quiet
            val confirmed = rejectedStampEqualsOwnPersisted && !wireIdSkippedThisJoin
            SyncNackDecision(
                resolveOp = true,
                markConfirmed = confirmed,
                suppressUntilLocalEdit = !confirmed,
            )
        }
        "counter-window" -> SyncNackDecision(
            resolveOp = true,
            pauseMutationsForJoin = true,
            issue = SyncIssueCode.ROOM_RESET_CHANGES_PAUSED,
        )
        "quota" -> SyncNackDecision(
            resolveOp = true,
            suppressUntilLocalEdit = true,
            issue = SyncIssueCode.ROOM_QUOTA_NACK,
        )
        "invalid" -> SyncNackDecision(
            resolveOp = true,
            suppressUntilLocalEdit = true,
            issue = SyncIssueCode.RELAY_INVALID_NACK,
        )
        "storage" -> SyncNackDecision(resolveOp = false, retry = true)
        "hello-required" -> SyncNackDecision(
            resolveOp = false,
            reconnect = true,
            localClose = SyncLocalClose.HELLO_REQUIRED_NACK,
        )
        "session-mismatch", "session-replaced" -> SyncNackDecision(
            resolveOp = false,
            reconnect = true,
            localClose = SyncLocalClose.SESSION_NACK,
        )
        else -> if (retryFlag) {
            decide("storage", true)
        } else {
            decide("invalid", false)
        }
    }
}
