package com.tacmap.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncManagerLiveSessionTest {
    private val actor = "A".repeat(43)
    private val oldSession = "E".repeat(43)
    private val currentSession = "I".repeat(43)

    @Test
    fun leaveFrameMustMatchTheCurrentAuthenticatedSession() {
        val sessions = mapOf(actor to ("pub" to currentSession))

        assertNull(acceptedV3Departure(
            JSONObject().put("t", "leave").put("by", actor).put("sd", oldSession),
            sessions,
            ownActorId = null,
        ))
        assertEquals(
            V3Departure(actor, currentSession, V3DepartureKind.TRANSIENT),
            acceptedV3Departure(
                JSONObject().put("t", "leave").put("by", actor).put("sd", currentSession),
                sessions,
                ownActorId = null,
            ),
        )
        assertEquals(
            V3Departure(actor, currentSession, V3DepartureKind.REPLACEMENT),
            acceptedV3Departure(
                JSONObject().put("t", "leave").put("by", actor)
                    .put("sd", currentSession).put("replaced", true),
                sessions,
                ownActorId = null,
            ),
        )
        assertEquals(
            V3Departure(actor, currentSession, V3DepartureKind.EXPLICIT),
            acceptedV3Departure(
                JSONObject().put("t", "leave").put("by", actor)
                    .put("sd", currentSession).put("explicit", true),
                sessions,
                ownActorId = null,
            ),
        )
        assertEquals(
            V3Departure(actor, currentSession, V3DepartureKind.TRANSIENT),
            acceptedV3Departure(
                JSONObject().put("t", "leave").put("by", actor)
                    .put("sd", currentSession).put("transient", true),
                sessions,
                ownActorId = null,
            ),
        )
        assertNull(acceptedV3Departure(
            JSONObject().put("t", "leave").put("by", actor)
                .put("sd", currentSession).put("replaced", "true"),
            sessions,
            ownActorId = null,
        ))
        assertNull(acceptedV3Departure(
            JSONObject().put("t", "leave").put("by", actor)
                .put("sd", currentSession).put("explicit", true).put("transient", true),
            sessions,
            ownActorId = null,
        ))
    }

    @Test
    fun malformedUnknownAndSelfLeaveFramesAreIgnored() {
        val sessions = mapOf(actor to ("pub" to currentSession))

        assertNull(acceptedV3Departure(JSONObject().put("by", actor), sessions, null))
        assertNull(acceptedV3Departure(
            JSONObject().put("by", actor).put("sd", "not-base64url-32"), sessions, null,
        ))
        assertNull(acceptedV3Departure(
            JSONObject().put("by", "M".repeat(43)).put("sd", currentSession), sessions, null,
        ))
        assertNull(acceptedV3Departure(
            JSONObject().put("by", actor).put("sd", currentSession), sessions, actor,
        ))
    }

    @Test
    fun replacementLeaveRetainsStaleFixWhileExplicitLeaveClearsIt() {
        val peer = PresencePeer(
            clientId = actor, callsign = "11A", affiliation = "friend",
            echelon = "team", function = "infantry", isHQ = false,
            lat = -35.0, lon = 149.0, heading = 0.0, speed = 0.0, ts = 1,
            sessionDomain = currentSession,
            receivedAtUptimeMs = 1_000L,
        )
        val replacement = peerAfterV3Departure(
            peer,
            V3Departure(actor, currentSession, V3DepartureKind.REPLACEMENT),
            nowUptimeMs = 2_000L,
        )
        assertEquals(2_000L, replacement?.reconnectGraceStartedAtUptimeMs)
        assertNull(peerAfterV3Departure(
            peer,
            V3Departure(actor, currentSession, V3DepartureKind.EXPLICIT),
            nowUptimeMs = 2_000L,
        ))
        assertEquals(peer, peerAfterV3Departure(
            peer,
            V3Departure(actor, "Q".repeat(43), V3DepartureKind.EXPLICIT),
            nowUptimeMs = 2_000L,
        ))
        assertEquals(peer, peerAfterV3Departure(
            peer,
            V3Departure(actor, "Q".repeat(43), V3DepartureKind.REPLACEMENT),
            nowUptimeMs = 2_000L,
        ))
        val transient = peerAfterV3Departure(
            peer,
            V3Departure(actor, currentSession, V3DepartureKind.TRANSIENT),
            nowUptimeMs = 2_000L,
        )
        assertEquals(2_000L, transient?.reconnectGraceStartedAtUptimeMs)
        assertEquals(
            2_000L,
            peerAfterV3Departure(
                transient,
                V3Departure(actor, currentSession, V3DepartureKind.TRANSIENT),
                nowUptimeMs = 20_000L,
            )?.reconnectGraceStartedAtUptimeMs,
        )
    }
}
