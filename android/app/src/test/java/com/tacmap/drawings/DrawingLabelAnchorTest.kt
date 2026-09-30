package com.tacmap.drawings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Labels sit on the rendered shape, matching iOS `DrawingShape.labelAnchor`. */
class DrawingLabelAnchorTest {
    // Uneven spacing so the central segment's midpoint is not the centroid,
    // which rotation and scaling would otherwise leave in place.
    private val line = DrawingFeature(
        name = "Route",
        geometry = DrawingGeometry.LINE,
        points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(0.0, 1.0), DrawingPoint(0.0, 3.0)),
    )

    @Test
    fun untransformedLineLabelsTheMiddleOfItsCentralSegment() {
        assertAnchor(DrawingPoint(0.0, 0.5), line.labelAnchor)
    }

    @Test
    fun rotatedLineLabelFollowsTheRenderedLine() {
        val rotated = line.copy(rotationDegrees = 90.0)
        val rendered = rotated.effectivePoints

        // Clockwise quarter turn about the centroid (0, 4/3): the line now runs
        // north to south, and the label moves with its central segment.
        assertAnchor(midpoint(rendered[0], rendered[1]), rotated.labelAnchor)
        assertAnchor(DrawingPoint(5.0 / 6.0, 4.0 / 3.0), rotated.labelAnchor)
    }

    @Test
    fun scaledLineLabelFollowsTheRenderedLine() {
        val scaled = line.copy(scaleX = 2.0, scaleY = 0.5)
        val rendered = scaled.effectivePoints

        assertAnchor(midpoint(rendered[0], rendered[1]), scaled.labelAnchor)
        assertAnchor(DrawingPoint(0.0, -1.0 / 3.0), scaled.labelAnchor)
    }

    @Test
    fun transformedPolygonLabelStaysOnItsCentroid() {
        val polygon = DrawingFeature(
            name = "EA",
            geometry = DrawingGeometry.POLYGON,
            points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(1.0, 0.0), DrawingPoint(0.0, 2.0)),
            rotationDegrees = 45.0,
            scaleX = 3.0,
        )
        assertAnchor(DrawingPoint(1.0 / 3.0, 2.0 / 3.0), polygon.labelAnchor)
    }

    private fun midpoint(a: DrawingPoint, b: DrawingPoint) =
        DrawingPoint((a.latitude + b.latitude) / 2, (a.longitude + b.longitude) / 2)

    private fun assertAnchor(expected: DrawingPoint, actual: DrawingPoint?) {
        assertNotNull(actual)
        assertEquals(expected.latitude, actual!!.latitude, 1e-9)
        assertEquals(expected.longitude, actual.longitude, 1e-9)
    }
}
