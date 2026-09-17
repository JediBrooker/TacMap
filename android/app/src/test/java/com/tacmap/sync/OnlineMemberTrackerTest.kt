package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineMemberTrackerTest {
    @Test
    fun twoAuthenticatedNonSharingClientsAppearWithoutLocationPresence() {
        val tracker = OnlineMemberTracker(staleAfterMs = 1_000)

        tracker.authenticatedHello("actor-alpha", "session-a", nowMs = 10)
        val members = tracker.authenticatedHello("actor-bravo", "session-b", nowMs = 20)

        assertEquals(setOf("actor-alpha", "actor-bravo"), members.keys)
        assertEquals("Member or-alpha", members.getValue("actor-alpha").displayName)
        assertNull(members.getValue("actor-alpha").callsign)
        assertNull(members.getValue("actor-bravo").callsign)
    }

    @Test
    fun signedPresenceAddsBoundedDisplayMetadataWithoutDefiningMembership() {
        val tracker = OnlineMemberTracker(staleAfterMs = 1_000)
        tracker.authenticatedHello("actor-alpha", "session-a", nowMs = 10)

        val members = tracker.updatePresenceMetadata(
            clientId = "actor-alpha",
            sessionDomain = "session-a",
            callsign = "  Alpha\u0000\u202E One  ",
            affiliation = "FRIEND",
            echelon = "TEAM",
            function = "INFANTRY",
            isHQ = false,
            nowMs = 20,
        )

        assertEquals("Alpha One", members.getValue("actor-alpha").displayName)
        assertEquals("FRIEND", members.getValue("actor-alpha").affiliation)
        val unchanged = tracker.updatePresenceMetadata(
            "actor-alpha", "stale-session", "Impostor", "HOSTILE", "TEAM", "INFANTRY", false, 30,
        )
        assertEquals("Alpha One", unchanged.getValue("actor-alpha").displayName)
    }

    @Test
    fun removalStalenessAndClearAreDeterministic() {
        val tracker = OnlineMemberTracker(staleAfterMs = 100)
        tracker.authenticatedHello("actor-alpha", "session-a", nowMs = 0)
        tracker.authenticatedHello("actor-bravo", "session-b", nowMs = 0)
        tracker.updatePresenceMetadata(
            "actor-alpha", "session-a", "Alpha", "FRIEND", "TEAM", "INFANTRY", false, 10,
        )

        val fresh = tracker.expireStaleMetadata(nowMs = 125)
        assertEquals(setOf("actor-alpha", "actor-bravo"), fresh.keys)
        assertNull(fresh.getValue("actor-alpha").callsign)
        assertEquals("Member or-alpha", fresh.getValue("actor-alpha").displayName)
        assertNull(fresh.getValue("actor-bravo").callsign)
        assertFalse(fresh.isEmpty())
        assertTrue(tracker.remove("actor-alpha").containsKey("actor-bravo"))
        assertTrue(tracker.remove("actor-bravo").isEmpty())

        tracker.authenticatedHello("actor-charlie", "session-c", nowMs = 130)
        assertTrue(tracker.clear().isEmpty())
        assertTrue(tracker.snapshot().isEmpty())
    }

    @Test
    fun delayedOldSessionLeaveCannotEraseReplacementSession() {
        val tracker = OnlineMemberTracker(staleAfterMs = 1_000)
        tracker.authenticatedHello("actor-alpha", "old-session", nowMs = 10)
        tracker.authenticatedHello("actor-alpha", "new-session", nowMs = 20)

        val afterOldLeave = tracker.remove("actor-alpha", "old-session")
        assertEquals(setOf("actor-alpha"), afterOldLeave.keys)
        assertEquals(20L, afterOldLeave.getValue("actor-alpha").joinedAt)

        assertTrue(tracker.remove("actor-alpha", "new-session").isEmpty())
    }
}
