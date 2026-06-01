package com.tacticalmaps.map

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure screen-space hit-test geometry behind drawing selection.
 * Mirrors the iOS MapGeometryTests so the two platforms stay in step.
 */
class MapGeometryTest {

    @Test
    fun segmentDistance_perpendicular() {
        assertEquals(3f, pointToSegmentDistance(Offset(5f, 3f), Offset(0f, 0f), Offset(10f, 0f)), 1e-4f)
    }

    @Test
    fun segmentDistance_beyondEndpointClamps() {
        // Projection parameter > 1 clamps to endpoint (10,0): hypot(3,4) = 5.
        assertEquals(5f, pointToSegmentDistance(Offset(13f, 4f), Offset(0f, 0f), Offset(10f, 0f)), 1e-4f)
    }

    @Test
    fun segmentDistance_degenerateSegment() {
        assertEquals(5f, pointToSegmentDistance(Offset(3f, 4f), Offset(0f, 0f), Offset(0f, 0f)), 1e-4f)
    }

    @Test
    fun polylineDistance_picksNearestSegment() {
        val line = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f))
        assertEquals(0f, pointToPolylineDistance(Offset(5f, 0f), line), 1e-4f)
        assertEquals(2f, pointToPolylineDistance(Offset(8f, 2f), line), 1e-4f)
    }

    @Test
    fun pointInPolygon_insideAndOutside() {
        val square = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 10f), Offset(0f, 10f))
        assertTrue(pointInPolygon(Offset(5f, 5f), square))
        assertFalse(pointInPolygon(Offset(15f, 5f), square))
    }
}
