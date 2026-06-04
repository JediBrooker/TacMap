package com.tacticalmaps.export

import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.waypoints.MilitarySymbolSpec
import com.tacticalmaps.waypoints.SymbolAffiliation
import com.tacticalmaps.waypoints.SymbolEchelon
import com.tacticalmaps.waypoints.SymbolFunction
import com.tacticalmaps.waypoints.TacticalControlMeasure
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

    @Test
    fun roundTripsTacticalWaypointSchema() {
        val layer = DrawingLayer(id = "layer-alpha", name = "Alpha", color = 0xFF123456.toInt())
        val military = Waypoint(
            id = "mil-1",
            name = "HQ",
            notes = "watch",
            latitude = -33.86,
            longitude = 151.21,
            elevationMetres = 42.0,
            kind = WaypointKind.Military(
                MilitarySymbolSpec(
                    affiliation = SymbolAffiliation.HOSTILE,
                    echelon = SymbolEchelon.BATTALION_REGIMENT,
                    function = SymbolFunction.AIR_DEFENCE,
                    isHeadquarters = true
                )
            ),
            layerId = layer.id
        )
        val control = Waypoint(
            id = "tcm-1",
            name = "Attack axis",
            latitude = -33.87,
            longitude = 151.22,
            kind = WaypointKind.ControlMeasure(TacticalControlMeasure.AXIS_OF_MAIN_ATTACK),
            rotation = 42.0,
            scaleX = 2.5,
            scaleY = 0.75,
            layerId = layer.id
        )

        val json = GeoJsonExporter.export(listOf(military, control), layers = listOf(layer))
        val result = GeoJsonImporter.parse(
            json = json,
            existingLayers = emptyList(),
            fallbackLayerId = "fallback"
        )

        assertEquals(1, result.newLayers.size)
        assertEquals(layer.id, result.newLayers.single().id)
        assertEquals(layer.name, result.newLayers.single().name)
        assertEquals(layer.color, result.newLayers.single().color)
        val importedMilitary = result.waypoints.first { it.id == "mil-1" }
        val importedMilitaryKind = importedMilitary.kind as WaypointKind.Military
        assertEquals(layer.id, importedMilitary.layerId)
        assertEquals("watch", importedMilitary.notes)
        assertEquals(42.0, importedMilitary.elevationMetres!!, 1e-9)
        assertEquals(SymbolAffiliation.HOSTILE, importedMilitaryKind.spec.affiliation)
        assertEquals(SymbolEchelon.BATTALION_REGIMENT, importedMilitaryKind.spec.echelon)
        assertEquals(SymbolFunction.AIR_DEFENCE, importedMilitaryKind.spec.function)
        assertTrue(importedMilitaryKind.spec.isHeadquarters)

        val importedControl = result.waypoints.first { it.id == "tcm-1" }
        val importedControlKind = importedControl.kind as WaypointKind.ControlMeasure
        assertEquals(TacticalControlMeasure.AXIS_OF_MAIN_ATTACK, importedControlKind.measure)
        assertEquals(layer.id, importedControl.layerId)
        assertEquals(42.0, importedControl.rotation, 1e-9)
        assertEquals(2.5, importedControl.scaleX, 1e-9)
        assertEquals(0.75, importedControl.scaleY, 1e-9)
    }
}
