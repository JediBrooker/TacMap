package com.tacmap.drawings

import org.junit.Assert.assertEquals
import org.junit.Test

class DrawingMoveToCrosshairTest {
    @Test
    fun everyPersistedGeometryMovesItsRenderedAnchorAndPreservesShape() {
        val target = DrawingPoint(-33.86, 151.21)
        val fixtures = listOf(
            DrawingFeature(name = "Point", geometry = DrawingGeometry.POINT,
                points = listOf(DrawingPoint(-34.0, 150.0))),
            DrawingFeature(name = "Line", geometry = DrawingGeometry.LINE,
                points = listOf(DrawingPoint(-34.1, 150.0), DrawingPoint(-33.9, 150.4)),
                rotationDegrees = 35.0, scaleX = 1.5, scaleY = 0.75),
            DrawingFeature(name = "Polygon", geometry = DrawingGeometry.POLYGON,
                points = listOf(
                    DrawingPoint(-34.1, 150.0), DrawingPoint(-34.0, 150.4),
                    DrawingPoint(-33.8, 150.2),
                )),
        )

        fixtures.forEach { original ->
            val moved = original.movedToCrosshair(target.latitude, target.longitude)
            assertEquals(original.points.size, moved.points.size)
            assertEquals(original.rotationDegrees, moved.rotationDegrees, 0.0)
            assertEquals(original.scaleX, moved.scaleX, 0.0)
            assertEquals(original.scaleY, moved.scaleY, 0.0)
            assertEquals(target.latitude, renderedAnchor(moved).latitude, 1e-9)
            assertEquals(target.longitude, renderedAnchor(moved).longitude, 1e-9)
            if (original.points.size > 1) {
                assertEquals(
                    original.points[1].latitude - original.points[0].latitude,
                    moved.points[1].latitude - moved.points[0].latitude,
                    1e-9,
                )
            }
        }
    }

    private fun renderedAnchor(feature: DrawingFeature): DrawingPoint {
        val points = feature.effectivePoints
        return when (feature.geometry) {
            DrawingGeometry.POINT -> points.first()
            DrawingGeometry.LINE -> {
                val mid = points.size / 2
                DrawingPoint(
                    (points[mid - 1].latitude + points[mid].latitude) / 2,
                    (points[mid - 1].longitude + points[mid].longitude) / 2,
                )
            }
            DrawingGeometry.POLYGON -> DrawingPoint(
                points.sumOf { it.latitude } / points.size,
                points.sumOf { it.longitude } / points.size,
            )
        }
    }
}
