package com.tacmap.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BillingPolicyTest {
    @Test fun acknowledgementRetryIsBoundedWithBackoff() {
        assertTrue(BillingRetryPolicy.shouldRetry(isTransient = true, completedRetries = 0))
        assertTrue(BillingRetryPolicy.shouldRetry(isTransient = true, completedRetries = 2))
        assertFalse(BillingRetryPolicy.shouldRetry(isTransient = true, completedRetries = 3))
        assertFalse(BillingRetryPolicy.shouldRetry(isTransient = false, completedRetries = 0))
        assertEquals(1_000L, BillingRetryPolicy.delayMs(0))
        assertEquals(3_000L, BillingRetryPolicy.delayMs(1))
        assertEquals(10_000L, BillingRetryPolicy.delayMs(2))
    }

    @Test fun foregroundRefreshUsesMonotonicThrottleAndTreatsRollbackAsDue() {
        val interval = BillingForegroundRefreshPolicy.MIN_REFRESH_INTERVAL_MS

        assertTrue(BillingForegroundRefreshPolicy.shouldRefresh(null, 10L, queryInFlight = false))
        assertFalse(BillingForegroundRefreshPolicy.shouldRefresh(10L, 11L, queryInFlight = true))
        assertFalse(BillingForegroundRefreshPolicy.shouldRefresh(10L, 10L + interval - 1, false))
        assertTrue(BillingForegroundRefreshPolicy.shouldRefresh(10L, 10L + interval, false))
        assertTrue(BillingForegroundRefreshPolicy.shouldRefresh(100L, 99L, false))
    }
}
