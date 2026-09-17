package com.tacmap.sync

import com.tacmap.settings.BackgroundUnitSyncInterval

/**
 * Monotonic timing policy for Unit Sync presence sends.
 *
 * Callers must serialize [isSendDue] and [recordSendResult]. A failed send is
 * intentionally not recorded, allowing the same fresh fix to be retried.
 */
internal class UnitSyncPresenceCadence {
    private var lastSuccessfulFixElapsedRealtimeNanos: Long? = null
    private var lastSuccessfulSendElapsedRealtimeNanos: Long? = null
    private var authenticatedSessionSeedAvailable = false

    val latestSuccessfulFixElapsedRealtimeNanos: Long?
        get() = lastSuccessfulFixElapsedRealtimeNanos

    fun isSendDue(
        fixElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long,
        isBackground: Boolean,
        backgroundInterval: BackgroundUnitSyncInterval,
    ): Boolean {
        if (!isLocationFresh(fixElapsedRealtimeNanos, nowElapsedRealtimeNanos)) return false

        val lastFix = lastSuccessfulFixElapsedRealtimeNanos
        if (lastFix != null && fixElapsedRealtimeNanos < lastFix) return false
        if (lastFix != null && fixElapsedRealtimeNanos == lastFix) {
            return isAuthenticatedSessionSeedFix(
                fixElapsedRealtimeNanos = fixElapsedRealtimeNanos,
                nowElapsedRealtimeNanos = nowElapsedRealtimeNanos,
            )
        }

        val lastSend = lastSuccessfulSendElapsedRealtimeNanos ?: return true
        if (nowElapsedRealtimeNanos < lastSend) return false

        // LocationManager schedules its next callback from the prior fix, while
        // websocket completion is a few milliseconds later. Gate by successful
        // fix timestamps so an exact 15-minute callback cannot miss by those
        // milliseconds and slip to 30 minutes. Keep lastSend above solely as a
        // monotonic rollback guard.
        val effectiveFixTime = minOf(fixElapsedRealtimeNanos, nowElapsedRealtimeNanos)
        return effectiveFixTime - checkNotNull(lastFix) >=
            sendIntervalNanos(isBackground, backgroundInterval)
    }

    fun recordSendResult(
        successful: Boolean,
        fixElapsedRealtimeNanos: Long,
        completedAtElapsedRealtimeNanos: Long,
    ) {
        if (!successful) return

        val lastFix = lastSuccessfulFixElapsedRealtimeNanos
        if (lastFix != null && fixElapsedRealtimeNanos < lastFix) return
        if (lastFix != null && fixElapsedRealtimeNanos == lastFix) {
            if (!authenticatedSessionSeedAvailable) return
            authenticatedSessionSeedAvailable = false
            lastSuccessfulSendElapsedRealtimeNanos = lastSuccessfulSendElapsedRealtimeNanos
                ?.let { maxOf(it, completedAtElapsedRealtimeNanos) }
                ?: completedAtElapsedRealtimeNanos
            return
        }

        authenticatedSessionSeedAvailable = false
        lastSuccessfulFixElapsedRealtimeNanos = fixElapsedRealtimeNanos
        lastSuccessfulSendElapsedRealtimeNanos = lastSuccessfulSendElapsedRealtimeNanos
            ?.let { maxOf(it, completedAtElapsedRealtimeNanos) }
            ?: completedAtElapsedRealtimeNanos
    }

    fun reset() {
        lastSuccessfulFixElapsedRealtimeNanos = null
        lastSuccessfulSendElapsedRealtimeNanos = null
        authenticatedSessionSeedAvailable = false
    }

    /**
     * A new authenticated v3 transport has no prior presence. Allow it to seed
     * exactly once from the same OS fix when that fix is still genuinely recent;
     * otherwise only a newer fix can publish. A failed websocket enqueue does
     * not consume this allowance, so the same frame can be retried safely.
     */
    fun beginAuthenticatedSession() {
        authenticatedSessionSeedAvailable = true
        lastSuccessfulSendElapsedRealtimeNanos = null
    }

    fun isAuthenticatedSessionSeedFix(
        fixElapsedRealtimeNanos: Long,
        nowElapsedRealtimeNanos: Long,
    ): Boolean {
        val lastFix = lastSuccessfulFixElapsedRealtimeNanos ?: return false
        if (!authenticatedSessionSeedAvailable || fixElapsedRealtimeNanos != lastFix) return false
        if (fixElapsedRealtimeNanos > nowElapsedRealtimeNanos) return false
        return nowElapsedRealtimeNanos - fixElapsedRealtimeNanos <=
            secondsToNanos(MAX_AUTHENTICATED_SESSION_SEED_AGE_SECONDS)
    }

    companion object {
        const val FOREGROUND_SEND_INTERVAL_SECONDS = 5L
        const val FOREGROUND_RETENTION_SECONDS = 45L
        const val MAX_AUTHENTICATED_SESSION_SEED_AGE_SECONDS = 10L
        const val MAX_LOCATION_AGE_SECONDS = 2L * 60L
        const val MAX_LOCATION_FUTURE_SKEW_SECONDS = 30L
        const val BACKGROUND_RETENTION_GRACE_SECONDS = 5L * 60L
        const val MAX_RETENTION_SECONDS = 65L * 60L

        fun isLocationFresh(
            fixElapsedRealtimeNanos: Long,
            nowElapsedRealtimeNanos: Long,
        ): Boolean {
            val oldestAccepted = nowElapsedRealtimeNanos - secondsToNanos(MAX_LOCATION_AGE_SECONDS)
            val newestAccepted = nowElapsedRealtimeNanos +
                secondsToNanos(MAX_LOCATION_FUTURE_SKEW_SECONDS)
            return fixElapsedRealtimeNanos in oldestAccepted..newestAccepted
        }

        fun sendIntervalNanos(
            isBackground: Boolean,
            backgroundInterval: BackgroundUnitSyncInterval,
        ): Long {
            val seconds = if (isBackground) {
                backgroundInterval.minutes.toLong() * 60L
            } else {
                FOREGROUND_SEND_INTERVAL_SECONDS
            }
            return secondsToNanos(seconds)
        }

        fun retentionSeconds(
            isBackground: Boolean,
            backgroundInterval: BackgroundUnitSyncInterval,
        ): Long = if (isBackground) {
            (backgroundInterval.minutes.toLong() * 60L + BACKGROUND_RETENTION_GRACE_SECONDS)
                .coerceAtMost(MAX_RETENTION_SECONDS)
        } else {
            FOREGROUND_RETENTION_SECONDS
        }

        private fun secondsToNanos(seconds: Long): Long = seconds * 1_000_000_000L
    }
}
