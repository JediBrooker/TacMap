package com.tacmap.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveClipboardPolicyTest {
    private val pending = SensitiveClipExpiry(
        token = "original",
        createdElapsedMs = 1_000L,
        expiresElapsedMs = 61_000L,
        expiresWallMs = 161_000L,
    )

    @Test
    fun exactExpiredClipIsCleared() {
        assertTrue(shouldClearSensitiveClip(pending, "original", 61_000L, 161_000L))
    }

    @Test
    fun replacementClipIsNeverCleared() {
        assertFalse(shouldClearSensitiveClip(pending, "replacement", 61_000L, 161_000L))
        assertFalse(shouldClearSensitiveClip(pending, null, 61_000L, 161_000L))
    }

    @Test
    fun exactClipIsNotClearedBeforeExpiry() {
        assertFalse(shouldClearSensitiveClip(pending, "original", 60_999L, 160_999L))
    }

    @Test
    fun sleepAndWallClockRollbackStillExpireConservatively() {
        assertTrue(shouldClearSensitiveClip(pending, "original", 61_000L, 120_000L))
        assertTrue(shouldClearSensitiveClip(pending, "original", 500L, 120_000L))
    }

    @Test
    fun uncertainClipboardWriteRetainsBothOldAndNewCandidates() {
        val prior = SensitiveClipboardState(listOf(pending))
        val next = pending.copy(
            token = "next",
            createdElapsedMs = 2_000L,
            expiresElapsedMs = 62_000L,
            expiresWallMs = 162_000L,
        )
        val transition = prior.transitionTo(next)

        assertTrue(transition.matching("next") == next)
        assertTrue(transition.matching("original") == pending)
        assertTrue(
            transition.resolvedAfterReadback(
                SensitiveClipObservation(true, "next"),
                descriptionReadSucceeded = true,
            ).candidates == listOf(next)
        )
        assertTrue(
            transition.resolvedAfterReadback(
                observation = null,
                descriptionReadSucceeded = false,
            ) == transition
        )
        val third = next.copy(token = "third")
        val full = transition.transitionTo(third)
        assertFalse(full.canTransitionTo(next.copy(token = "fourth")))
    }

    @Test
    fun definiteClearFailureOrNoOpRetriesWhileAmbiguityWaitsForForeground() {
        assertTrue(
            sensitiveClipClearDecision("original", false, null, false) ==
                SensitiveClipClearDecision.RetrySoon
        )
        assertTrue(
            sensitiveClipClearDecision(
                "original",
                true,
                SensitiveClipObservation(true, "original"),
                true,
            ) == SensitiveClipClearDecision.RetrySoon
        )
        assertTrue(
            sensitiveClipClearDecision(
                "original",
                true,
                SensitiveClipObservation(false, null),
                true,
            ) == SensitiveClipClearDecision.WaitForForeground
        )
        assertTrue(
            sensitiveClipClearDecision(
                "original",
                true,
                SensitiveClipObservation(true, "replacement"),
                true,
            ) == SensitiveClipClearDecision.RemoveState
        )
        assertTrue(
            sensitiveClipClearDecision(
                "original",
                true,
                SensitiveClipObservation(true, null),
                true,
            ) == SensitiveClipClearDecision.RemoveState
        )
    }
}
