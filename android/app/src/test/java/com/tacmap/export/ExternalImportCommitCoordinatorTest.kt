package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.waypoints.Waypoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalImportCommitCoordinatorTest {
    private val imported = GeoJsonImporter.Result(
        waypoints = listOf(Waypoint(id = "w", name = "W", latitude = 0.0, longitude = 0.0)),
        drawings = listOf(
            DrawingFeature(
                id = "d",
                name = "D",
                geometry = DrawingGeometry.LINE,
                points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(1.0, 1.0)),
            )
        ),
        newLayers = emptyList(),
    )

    @Test
    fun waypointFailureStopsBeforeDrawingCommit() {
        var drawingsCalled = false
        val result = commitExternalImport(
            imported,
            { ExternalImportStoreCommit(false, 0) },
            { _, _ -> drawingsCalled = true; ExternalImportStoreCommit(true, 1) },
        )
        assertFalse(result.succeeded)
        assertFalse(result.partialCommit)
        assertFalse(drawingsCalled)
    }

    @Test
    fun drawingFailureIsExplicitlyPartialAndRetrySafe() {
        val result = commitExternalImport(
            imported,
            { ExternalImportStoreCommit(true, 1) },
            { _, _ -> ExternalImportStoreCommit(false, 0) },
        )
        assertFalse(result.succeeded)
        assertTrue(result.partialCommit)
        assertTrue(result.message.contains("same IDs"))
    }

    @Test
    fun bothDurableCommitsPublishSuccess() {
        val result = commitExternalImport(
            imported,
            { ExternalImportStoreCommit(true, 1) },
            { _, _ -> ExternalImportStoreCommit(true, 1) },
        )
        assertTrue(result.succeeded)
        assertFalse(result.partialCommit)
        assertTrue(result.message.contains("1 waypoint(s) and 1 drawing(s)"))
    }
}
