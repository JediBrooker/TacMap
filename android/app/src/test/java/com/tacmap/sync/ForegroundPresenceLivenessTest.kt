package com.tacmap.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ForegroundPresenceLivenessTest {
    @Test
    fun refreshStartsWellBeforeForegroundPeerExpiryAndIsThrottled() {
        val policy = ForegroundPresenceLiveness()
        val fix = seconds(100)

        assertFalse(policy.shouldRequestFreshFix(fix, seconds(119) + 999_999_999L))
        assertTrue(policy.shouldRequestFreshFix(fix, seconds(120)))

        policy.recordRequest(seconds(120))
        assertFalse(policy.shouldRequestFreshFix(fix, seconds(124) + 999_999_999L))
        assertTrue(policy.shouldRequestFreshFix(fix, seconds(125)))
        assertTrue(
            ForegroundPresenceLiveness.REQUEST_AFTER_SECONDS <
                UnitSyncPresenceCadence.FOREGROUND_RETENTION_SECONDS,
        )
    }

    @Test
    fun forcedReconnectRequestStillHonoursThrottleAndClockRollback() {
        val policy = ForegroundPresenceLiveness()
        policy.recordRequest(seconds(100))

        assertFalse(policy.shouldRequestFreshFix(seconds(100), seconds(99), force = true))
        assertFalse(policy.shouldRequestFreshFix(seconds(100), seconds(104), force = true))
        assertTrue(policy.shouldRequestFreshFix(seconds(100), seconds(105), force = true))
    }

    @Test
    fun requestedFixMustBeStrictlyNewerActuallyRecentAndNotFromTheFuture() {
        val baseline = seconds(100)
        val now = seconds(120)

        assertFalse(ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(baseline, baseline, now))
        assertFalse(ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(baseline, seconds(109), now))
        assertTrue(ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(baseline, seconds(110), now))
        assertTrue(ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(baseline, now, now))
        assertFalse(ForegroundPresenceLiveness.isGenuinelyFreshRequestedFix(baseline, now + 1L, now))
    }

    @Test
    fun screenOffTransitionMayBridgeOnlyARecentFixWithoutRestampingAnOldOne() {
        val now = seconds(120)

        assertTrue(ForegroundPresenceLiveness.isGenuinelyRecentFix(seconds(110), now))
        assertFalse(ForegroundPresenceLiveness.isGenuinelyRecentFix(seconds(110) - 1L, now))
        assertFalse(ForegroundPresenceLiveness.isGenuinelyRecentFix(now + 1L, now))

        val manager = sourceText(
            "android/app/src/main/java/com/tacmap/sync/SyncManager.kt",
        )
        val transition = manager.substring(
            manager.indexOf("internal fun enterBackgroundPresenceOnly("),
            manager.indexOf("return true", manager.indexOf("internal fun enterBackgroundPresenceOnly(")),
        )
        assertTrue(transition.contains("LocationManager.GPS_PROVIDER"))
        assertTrue(transition.contains("isGenuinelyRecentFix"))
    }

    @Test
    fun staleHandshakeTimeoutCannotCancelAnotherSocketOrGeneration() {
        assertTrue(isCurrentPendingV3Handshake(7, 7, true, 3, true))
        assertFalse(isCurrentPendingV3Handshake(7, 8, true, 3, true))
        assertFalse(isCurrentPendingV3Handshake(7, 7, false, 3, true))
        assertFalse(isCurrentPendingV3Handshake(7, 7, true, 2, true))
        assertFalse(isCurrentPendingV3Handshake(7, 7, true, 3, false))
    }

    @Test
    fun productionRequesterIsGpsOnlyAndDoesNotRestampLocations() {
        val source = sourceText(
            "android/app/src/main/java/com/tacmap/sync/ForegroundPresenceLiveness.kt",
        )

        assertTrue(source.contains("LocationManager.GPS_PROVIDER"))
        assertFalse(source.contains("LocationManager.NETWORK_PROVIDER"))
        assertFalse(source.contains("FusedLocationProvider"))
        assertFalse(source.contains("location.time ="))
        assertFalse(source.contains("location.elapsedRealtimeNanos ="))
    }

    private fun seconds(value: Long): Long = value * 1_000_000_000L

    private fun sourceText(relativePath: String): String {
        var directory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val source = File(directory, relativePath)
            if (source.exists()) return source.readText()
            directory = directory.parentFile ?: return@repeat
        }
        error("Could not locate $relativePath")
    }
}
