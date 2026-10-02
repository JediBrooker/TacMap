package com.tacmap.sync

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.export.GeoJsonImporter
import com.tacmap.waypoints.Waypoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySyncPutValidationTest {
    private val id = "20347f3c-0f6e-4ce9-9a64-e474ee493fb8"
    private val otherId = "56db61ee-06f7-43a3-b883-fd8eceb86aba"
    private val waypoint = Waypoint(id = id, name = "WP", latitude = 1.0, longitude = 2.0)
    private val drawing = DrawingFeature(
        id = id,
        name = "Line",
        geometry = DrawingGeometry.LINE,
        points = listOf(DrawingPoint(1.0, 2.0), DrawingPoint(2.0, 3.0)),
    )

    @Test
    fun acceptsExactlyOneExpectedObjectWithMatchingUuid() {
        assertTrue(isValidLegacySyncPut(id, "waypoint", result(waypoints = listOf(waypoint))))
        assertTrue(isValidLegacySyncPut(id, "drawing", result(drawings = listOf(drawing))))
    }

    @Test
    fun rejectsMalformedMismatchedAndMultiObjectPayloads() {
        assertFalse(isValidLegacySyncPut("not-a-uuid", "waypoint", result(waypoints = listOf(waypoint))))
        assertFalse(isValidLegacySyncPut(otherId, "waypoint", result(waypoints = listOf(waypoint))))
        assertFalse(isValidLegacySyncPut(id, "unknown", result(waypoints = listOf(waypoint))))
        assertFalse(isValidLegacySyncPut(id, "drawing", result(waypoints = listOf(waypoint))))
        assertFalse(isValidLegacySyncPut(id, "waypoint", result(drawings = listOf(drawing))))
        assertFalse(isValidLegacySyncPut(id, "waypoint", result(waypoints = listOf(waypoint, waypoint.copy(name = "duplicate")))))
        assertFalse(isValidLegacySyncPut(id, "waypoint", result(waypoints = listOf(waypoint), drawings = listOf(drawing))))
        assertFalse(isValidLegacySyncPut(id, "waypoint", result(waypoints = listOf(waypoint.copy(id = "malicious")))))
        assertFalse(isValidLegacySyncPut("{$id}", "waypoint", result(waypoints = listOf(waypoint))))
    }

    // plans/04 section 16 (S3-01): shipped iOS v2 senders use uppercase ids, so
    // either case is accepted and the embedded id matches case-insensitively.
    // This used to pin lowercase-only, which made Android drop every iOS record.
    @Test
    fun acceptsEitherCaseAndMatchesTheEmbeddedIdCaseInsensitively() {
        assertTrue(isValidLegacySyncPut(id.uppercase(), "waypoint", result(waypoints = listOf(waypoint))))
        assertTrue(isValidLegacySyncPut(id, "waypoint", result(waypoints = listOf(waypoint.copy(id = id.uppercase())))))
        assertEquals(id, canonicalLegacySyncId(id.uppercase()))
    }

    @Test
    fun alternateCaseEnvelopeCannotBypassMonotonicVersionOrCreateASecondKey() {
        val versions = mutableMapOf(id to 7L)
        val lastBy = mutableMapOf(id to "b0000000-0000-4000-8000-000000000000")
        var stored = waypoint.copy(name = "trusted")

        fun apply(rawEnvelopeId: String, version: Long, by: String, incoming: Waypoint) {
            val acceptedId = acceptedLegacySyncRecordId(rawEnvelopeId, version, by, versions, lastBy) ?: return
            val parsed = result(waypoints = listOf(incoming))
            if (!isValidLegacySyncPut(rawEnvelopeId, "waypoint", parsed)) return
            versions[acceptedId] = version
            lastBy[acceptedId] = by
            stored = incoming
        }

        // same version replayed in the other case, smaller by: still rejected
        apply(id.uppercase(), 7L, "a0000000-0000-4000-8000-000000000000", waypoint.copy(name = "case-replay"))
        assertEquals("trusted", stored.name)
        assertEquals(mapOf(id to 7L), versions)

        // newer in uppercase lands on the one lowercase key
        apply(id.uppercase(), 8L, "a0000000-0000-4000-8000-000000000000", waypoint.copy(name = "ios-newer"))
        assertEquals("ios-newer", stored.name)
        assertEquals(mapOf(id to 8L), versions)
        assertNull(acceptedLegacySyncRecordId(id, 8L, "a0000000-0000-4000-8000-000000000000", versions, lastBy))

        // equal version, larger by wins, same as the relay (S3-14)
        apply(id, 8L, "c0000000-0000-4000-8000-000000000000", waypoint.copy(name = "tie-winner"))
        assertEquals("tie-winner", stored.name)
    }

    private fun result(
        waypoints: List<Waypoint> = emptyList(),
        drawings: List<DrawingFeature> = emptyList(),
    ) = GeoJsonImporter.Result(waypoints, drawings, emptyList())
}
