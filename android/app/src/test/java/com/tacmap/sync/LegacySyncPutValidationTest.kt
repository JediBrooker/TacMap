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
        assertFalse(isValidLegacySyncPut(id.uppercase(), "waypoint", result(waypoints = listOf(waypoint))))
        assertFalse(isValidLegacySyncPut(id, "waypoint", result(waypoints = listOf(waypoint.copy(id = id.uppercase())))))
    }

    @Test
    fun alternateCaseEnvelopeCannotBypassMonotonicVersionOrOverwrite() {
        val versions = mutableMapOf(id to 7L)
        var stored = waypoint.copy(name = "trusted")

        fun apply(rawEnvelopeId: String, version: Long, incoming: Waypoint) {
            val acceptedId = acceptedLegacySyncRecordId(rawEnvelopeId, version, versions) ?: return
            val parsed = result(waypoints = listOf(incoming))
            if (!isValidLegacySyncPut(acceptedId, "waypoint", parsed)) return
            versions[acceptedId] = version
            stored = incoming
        }

        apply(id.uppercase(), 8L, waypoint.copy(name = "case-bypass"))
        assertEquals("trusted", stored.name)
        assertEquals(mapOf(id to 7L), versions)
        assertNull(acceptedLegacySyncRecordId(id.uppercase(), 999L, versions))

        apply(id, 7L, waypoint.copy(name = "same-version-replay"))
        assertEquals("trusted", stored.name)

        apply(id, 8L, waypoint.copy(name = "canonical-newer"))
        assertEquals("canonical-newer", stored.name)
        assertEquals(mapOf(id to 8L), versions)
    }

    private fun result(
        waypoints: List<Waypoint> = emptyList(),
        drawings: List<DrawingFeature> = emptyList(),
    ) = GeoJsonImporter.Result(waypoints, drawings, emptyList())
}
