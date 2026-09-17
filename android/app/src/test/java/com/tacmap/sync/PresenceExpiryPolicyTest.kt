package com.tacmap.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceExpiryPolicyTest {
    private val now = 2_000_000L

    @Test
    fun recentAndMatchingActiveSessionLocationsAreRetainedWithinBounds() {
        assertTrue(PresenceExpiryPolicy.shouldRetain(peer(ageMs = 45_000), null, now))
        assertTrue(
            PresenceExpiryPolicy.shouldRetain(
                peer(
                    ageMs = 60L * 60L * 1_000L,
                    session = "session-a",
                    retentionWindowMs = PresenceExpiryPolicy.ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS,
                ),
                "session-a",
                now,
            )
        )
    }

    @Test
    fun legacyMismatchedAndOverdueLocationsExpire() {
        assertFalse(PresenceExpiryPolicy.shouldRetain(peer(ageMs = 45_001), null, now))
        assertFalse(
            PresenceExpiryPolicy.shouldRetain(
                peer(ageMs = 60_000, session = "session-a"),
                "session-a",
                now,
            )
        )
        assertFalse(
            PresenceExpiryPolicy.shouldRetain(
                peer(
                    ageMs = 60_000,
                    session = "session-a",
                    retentionWindowMs = PresenceExpiryPolicy.ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS,
                ),
                "session-b",
                now,
            )
        )
        assertFalse(
            PresenceExpiryPolicy.shouldRetain(
                peer(
                    ageMs = PresenceExpiryPolicy.ACTIVE_SESSION_LAST_KNOWN_WINDOW_MS + 1,
                    session = "session-a",
                    retentionWindowMs = Long.MAX_VALUE,
                ),
                "session-a",
                now,
            )
        )
        assertFalse(
            PresenceExpiryPolicy.shouldRetain(
                peer(ageMs = 0),
                null,
                now - 1,
            )
        )
    }

    private fun peer(
        ageMs: Long,
        session: String? = null,
        retentionWindowMs: Long = PresenceExpiryPolicy.LIVE_UPDATE_WINDOW_MS,
    ) = PresencePeer(
        clientId = "peer",
        callsign = "A11",
        affiliation = "friend",
        echelon = "team",
        function = "infantry",
        isHQ = false,
        lat = -33.86,
        lon = 151.21,
        heading = 0.0,
        speed = 0.0,
        ts = now - ageMs,
        receivedAt = now - ageMs,
        sessionDomain = session,
        retentionWindowMs = retentionWindowMs,
        receivedAtUptimeMs = now - ageMs,
    )
}
