package com.tacmap.sync

internal fun isCurrentSocketCallback(
    scheduledGeneration: Long,
    currentGeneration: Long,
    isCurrentSocket: Boolean,
): Boolean = scheduledGeneration == currentGeneration && isCurrentSocket

// The old +-20% backoff lived here. It clamped at the cap (S2-14) and was reset
// at hello-ack (S3-02), see SyncBackoffPolicy for the replacement.
