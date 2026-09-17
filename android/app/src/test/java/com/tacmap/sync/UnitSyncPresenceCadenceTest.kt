package com.tacmap.sync

import com.tacmap.settings.BackgroundUnitSyncInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitSyncPresenceCadenceTest {
    @Test
    fun supportedBackgroundIntervalsAreOneFiveFifteenThirtyAndSixtyMinutes() {
        assertEquals(
            listOf(1, 5, 15, 30, 60),
            BackgroundUnitSyncInterval.entries.map { it.minutes },
        )
    }

    @Test
    fun firstFreshFixIsImmediatelyDueAndFreshnessBoundsAreInclusive() {
        val now = seconds(300)
        val cadence = UnitSyncPresenceCadence()

        assertTrue(cadence.isSendDue(seconds(180), now, true, interval(15)))
        assertTrue(UnitSyncPresenceCadence.isLocationFresh(seconds(330), now))
        assertFalse(UnitSyncPresenceCadence.isLocationFresh(seconds(179) + 999_999_999L, now))
        assertFalse(UnitSyncPresenceCadence.isLocationFresh(seconds(330) + 1L, now))
    }

    @Test
    fun foregroundAndEveryBackgroundIntervalUseAnInclusiveMonotonicDueBoundary() {
        assertDueBoundary(isBackground = false, interval = interval(60), intervalSeconds = 5)

        BackgroundUnitSyncInterval.entries.forEach { selected ->
            assertDueBoundary(
                isBackground = true,
                interval = selected,
                intervalSeconds = selected.minutes.toLong() * 60L,
            )
        }
    }

    @Test
    fun onlyFixesStrictlyNewerThanTheLastSuccessfulFixAreEligible() {
        val cadence = UnitSyncPresenceCadence()
        val firstFix = seconds(1_000)
        cadence.recordSendResult(true, firstFix, firstFix)
        val dueAt = firstFix + UnitSyncPresenceCadence.sendIntervalNanos(false, interval(15))

        assertFalse(cadence.isSendDue(firstFix, dueAt, false, interval(15)))
        assertFalse(cadence.isSendDue(firstFix - 1L, dueAt, false, interval(15)))
        assertTrue(cadence.isSendDue(dueAt, dueAt, false, interval(15)))
    }

    @Test
    fun failedSendDoesNotAdvanceCadenceOrConsumeTheFix() {
        val cadence = UnitSyncPresenceCadence()
        val fix = seconds(1_000)

        assertTrue(cadence.isSendDue(fix, fix, true, interval(15)))
        cadence.recordSendResult(false, fix, fix)
        assertTrue(cadence.isSendDue(fix, fix, true, interval(15)))

        cadence.recordSendResult(true, fix, fix)
        assertFalse(cadence.isSendDue(fix + 1L, fix, true, interval(15)))
    }

    @Test
    fun exactNextGpsIntervalIsNotDelayedByEarlierSendCompletionLatency() {
        val cadence = UnitSyncPresenceCadence()
        val firstFix = seconds(1_000)
        val completion = firstFix + 250_000_000L
        cadence.recordSendResult(true, firstFix, completion)

        val nextFix = firstFix + seconds(15 * 60L)
        assertTrue(cadence.isSendDue(nextFix, nextFix, true, interval(15)))
    }

    @Test
    fun retentionIsFortyFiveSecondsForegroundAndIntervalPlusFiveMinutesBackground() {
        assertEquals(45L, UnitSyncPresenceCadence.retentionSeconds(false, interval(60)))

        val expected = mapOf(
            1 to 6L * 60L,
            5 to 10L * 60L,
            15 to 20L * 60L,
            30 to 35L * 60L,
            60 to 65L * 60L,
        )
        BackgroundUnitSyncInterval.entries.forEach { selected ->
            assertEquals(
                expected.getValue(selected.minutes),
                UnitSyncPresenceCadence.retentionSeconds(true, selected),
            )
        }
    }

    @Test
    fun resetMakesTheNextFreshFixImmediatelyDue() {
        val cadence = UnitSyncPresenceCadence()
        val firstFix = seconds(1_000)
        cadence.recordSendResult(true, firstFix, firstFix)

        cadence.reset()

        assertTrue(cadence.isSendDue(firstFix, firstFix, true, interval(60)))
    }

    @Test
    fun authenticatedSessionMaySeedOnceFromTheSameStillRecentFix() {
        val cadence = UnitSyncPresenceCadence()
        val previousFix = seconds(1_000)
        cadence.recordSendResult(true, previousFix, previousFix)

        cadence.beginAuthenticatedSession()

        val stillRecent = previousFix + seconds(9)
        assertTrue(cadence.isSendDue(previousFix, stillRecent, false, interval(15)))
        cadence.recordSendResult(false, previousFix, stillRecent)
        assertTrue(cadence.isSendDue(previousFix, stillRecent, false, interval(15)))

        cadence.recordSendResult(true, previousFix, stillRecent)
        assertFalse(cadence.isSendDue(previousFix, stillRecent, false, interval(15)))

        cadence.beginAuthenticatedSession()
        assertTrue(cadence.isSendDue(previousFix, stillRecent, false, interval(15)))
    }

    @Test
    fun authenticatedSessionNeverSeedsFromAnOldFixButNewFixIsImmediatelyDue() {
        val cadence = UnitSyncPresenceCadence()
        val previousFix = seconds(1_000)
        cadence.recordSendResult(true, previousFix, previousFix)

        cadence.beginAuthenticatedSession()

        assertTrue(cadence.isSendDue(previousFix, previousFix + seconds(10), false, interval(15)))
        assertFalse(cadence.isSendDue(previousFix, previousFix + seconds(10) + 1L, false, interval(15)))
        assertTrue(
            ForegroundPresenceLiveness().shouldRequestFreshFix(
                lastSuccessfulFixElapsedRealtimeNanos = previousFix,
                nowElapsedRealtimeNanos = previousFix + seconds(10) + 1L,
                force = true,
            ),
        )
        val newFix = previousFix + seconds(10) + 1L
        assertTrue(cadence.isSendDue(newFix, newFix, false, interval(15)))
    }

    private fun assertDueBoundary(
        isBackground: Boolean,
        interval: BackgroundUnitSyncInterval,
        intervalSeconds: Long,
    ) {
        val cadence = UnitSyncPresenceCadence()
        val firstFix = seconds(10_000)
        cadence.recordSendResult(true, firstFix, firstFix)
        val boundary = firstFix + seconds(intervalSeconds)

        assertFalse(cadence.isSendDue(boundary, boundary - 1L, isBackground, interval))
        assertTrue(cadence.isSendDue(boundary, boundary, isBackground, interval))
    }

    private fun interval(minutes: Int): BackgroundUnitSyncInterval =
        BackgroundUnitSyncInterval.entries.single { it.minutes == minutes }

    private fun seconds(value: Long): Long = value * 1_000_000_000L
}
