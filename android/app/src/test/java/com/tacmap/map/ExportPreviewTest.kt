package com.tacmap.map

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.waypoints.MarkerSymbol
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPreviewTest {

    private fun waypoint(kind: WaypointKind) = Waypoint(name = "wp", latitude = 0.0, longitude = 0.0, kind = kind)

    private fun drawing(geometry: DrawingGeometry, points: Int) = DrawingFeature(
        name = "d",
        geometry = geometry,
        points = List(points) { DrawingPoint(it * 0.001, 0.0) },
    )

    @Test fun countsFollowTheIosSummaryOrderAndSkipEmptyTypes() {
        val counts = geoJsonExportCounts(
            waypoints = listOf(
                waypoint(WaypointKind.ControlMeasure()),
                waypoint(WaypointKind.Generic),
                waypoint(WaypointKind.Military(MilitarySymbolSpec())),
                waypoint(WaypointKind.ControlMeasure()),
                waypoint(WaypointKind.Marker(MarkerSymbol())),
            ),
            drawings = listOf(
                drawing(DrawingGeometry.LINE, 2),
                drawing(DrawingGeometry.LINE, 20),
                drawing(DrawingGeometry.LINE, 21),
                drawing(DrawingGeometry.POLYGON, 4),
            ),
        )
        assertEquals(
            listOf(
                ExportCount("unit", 1),
                ExportCount("task", 2),
                ExportCount("marker", 2),
                ExportCount("line", 2),
                ExportCount("free-draw", 1),
                ExportCount("area", 1),
            ),
            counts,
        )
    }

    @Test fun nothingToCountGivesAnEmptySummary() {
        assertEquals(emptyList<ExportCount>(), geoJsonExportCounts(emptyList(), emptyList()))
        assertEquals(
            listOf(ExportCount("point", 1)),
            geoJsonExportCounts(emptyList(), listOf(drawing(DrawingGeometry.POINT, 1))),
        )
    }

    @Test fun shortContentIsShownWhole() {
        assertEquals(ExportPreview("abcd", truncated = false), exportPreview("abcd", maxBytes = 4))
        assertEquals(ExportPreview("", truncated = false), exportPreview(""))
    }

    @Test fun longContentIsCutAtTheIosByteLimit() {
        val preview = exportPreview("a".repeat(5_000))
        assertTrue(preview.truncated)
        assertEquals(EXPORT_PREVIEW_MAX_BYTES, preview.text.length)
        assertFalse(exportPreview("a".repeat(EXPORT_PREVIEW_MAX_BYTES)).truncated)
    }

    @Test fun cutNeverSplitsACharacter() {
        // é is two bytes in UTF-8, € three, and 😀 four (a surrogate pair).
        assertEquals(ExportPreview("a", truncated = true), exportPreview("aé", maxBytes = 2))
        assertEquals(ExportPreview("aé", truncated = true), exportPreview("aéb", maxBytes = 3))
        assertEquals(ExportPreview("€", truncated = true), exportPreview("€x", maxBytes = 3))
        assertEquals(ExportPreview("a", truncated = true), exportPreview("a😀b", maxBytes = 4))
        assertEquals(ExportPreview("a😀", truncated = true), exportPreview("a😀b", maxBytes = 5))
    }
}
