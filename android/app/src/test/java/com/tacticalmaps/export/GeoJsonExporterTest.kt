package com.tacticalmaps.export

import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mirror of the iOS GeoJSONExporterTests: pins the FeatureCollection shape,
 * [lon, lat] coordinate ordering, geometry types, and ring closure so the
 * Android export stays interchangeable with the iOS one.
 */
class GeoJsonExporterTest {

    @Test
    fun exportsFeatureCollectionStructure() {
        val wp = Waypoint(
            id = "wp1", name = "OP North",
            latitude = 37.7749, longitude = -122.4194,
            elevationMetres = 120.0, kind = WaypointKind.Generic
        )
        val line = DrawingFeature(
            id = "l1", name = "route", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(1.0, 2.0), DrawingPoint(3.0, 4.0))
        )
        val poly = DrawingFeature(
            id = "p1", name = "area", geometry = DrawingGeometry.POLYGON,
            points = listOf(DrawingPoint(0.0, 0.0), DrawingPoint(0.0, 1.0), DrawingPoint(1.0, 1.0))
        )

        val json = GeoJsonExporter.export(listOf(wp), listOf(line, poly))
        val root = Json.parseToJsonElement(json).jsonObject

        assertEquals("FeatureCollection", root["type"]!!.jsonPrimitive.content)
        assertTrue(root["generator"]!!.jsonPrimitive.content.contains("TacticalMaps Android prototype"))

        val features = root["features"]!!.jsonArray
        assertEquals(3, features.size)

        // Waypoint → Point with [lon, lat] ordering.
        val wpGeom = features[0].jsonObject["geometry"]!!.jsonObject
        assertEquals("Point", wpGeom["type"]!!.jsonPrimitive.content)
        val wpCoords = wpGeom["coordinates"]!!.jsonArray.map { it.jsonPrimitive.double }
        assertEquals(-122.4194, wpCoords[0], 1e-9)
        assertEquals(37.7749, wpCoords[1], 1e-9)

        // Line → LineString, vertices in [lon, lat] order.
        val lineGeom = features[1].jsonObject["geometry"]!!.jsonObject
        assertEquals("LineString", lineGeom["type"]!!.jsonPrimitive.content)
        val lineCoords = lineGeom["coordinates"]!!.jsonArray
            .map { it.jsonArray.map { c -> c.jsonPrimitive.double } }
        assertEquals(listOf(listOf(2.0, 1.0), listOf(4.0, 3.0)), lineCoords)

        // Polygon → single ring, closed implicitly (first == last).
        val polyGeom = features[2].jsonObject["geometry"]!!.jsonObject
        assertEquals("Polygon", polyGeom["type"]!!.jsonPrimitive.content)
        val ring = polyGeom["coordinates"]!!.jsonArray[0].jsonArray
        assertEquals(4, ring.size)
        assertEquals(ring.first(), ring.last())
    }
}
