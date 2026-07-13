package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class GeoJsonImportHandlerTest {
    @Test fun validPickerDocumentIsParsedAppliedAndReported() {
        val json = """{"type":"FeatureCollection","features":[{"type":"Feature","id":"rv","geometry":{"type":"Point","coordinates":[151.2,-33.8]},"properties":{"name":"RV"}}]}"""
        val parsed = parseGeoJsonDocument(ByteArrayInputStream(json.toByteArray()), emptyList(), "fallback")
        var appliedWaypoints = 0

        val feedback = applyGeoJsonImportResult(parsed) { appliedWaypoints = it.waypoints.size }

        assertTrue(feedback.succeeded)
        assertEquals(1, appliedWaypoints)
        assertEquals("Imported 1 waypoint(s) and 0 drawing(s)", feedback.message)
    }

    @Test fun unreadablePickerDocumentProducesVisibleErrorAndDoesNotApply() {
        var applied = false
        val feedback = applyGeoJsonImportResult(
            parseGeoJsonDocument(null, emptyList(), "fallback")
        ) { applied = true }

        assertFalse(feedback.succeeded)
        assertFalse(applied)
        assertTrue(feedback.message.startsWith("Import failed:"))
    }
}
