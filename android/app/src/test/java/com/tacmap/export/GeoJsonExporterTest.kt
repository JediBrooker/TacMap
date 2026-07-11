package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.MarkerSet
import com.tacmap.waypoints.MarkerSymbol
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
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
        // generator is the fixed string "TacMap" - no platform/version leak (F10).
        assertEquals("TacMap", root["generator"]!!.jsonPrimitive.content)

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

    @Test
    fun roundTripsMarkerWaypoint() {
        // A marker waypoint used to degrade to a generic pin on import (Fable
        // data-loss finding). Its set/symbol/colour must now survive.
        val marker = Waypoint(
            id = "mk-1",
            name = "Objective",
            latitude = -33.88,
            longitude = 151.23,
            kind = WaypointKind.Marker(
                MarkerSymbol(set = MarkerSet.AIRSOFT, symbolId = "objective", colorHex = "#F2872E")
            )
        )
        val json = GeoJsonExporter.export(listOf(marker), emptyList(), emptyList())
        val result = GeoJsonImporter.parse(json, existingLayers = emptyList(), fallbackLayerId = "fallback")
        val kind = result.waypoints.single().kind as WaypointKind.Marker
        assertEquals(MarkerSet.AIRSOFT, kind.marker.set)
        assertEquals("objective", kind.marker.symbolId)
        assertEquals("#F2872E", kind.marker.colorHex)
    }

    /**
     * The strongest parity guarantee: export → import → export must be
     * byte-identical. This proves the export is deterministic (no wall-clock
     * `generated_at`), that a rotated/scaled drawing bakes into geometry with
     * identity transform props (so re-import doesn't double-transform), and that
     * style, dash, task colour and created_at all round-trip.
     */
    @Test
    fun exportImportExportIsIdempotent() {
        val layer = DrawingLayer(id = "layer-a", name = "Alpha", color = 0xFF112233.toInt())
        val poly = DrawingFeature(
            id = "poly-1", name = "AO", notes = "watch the tree line",
            geometry = DrawingGeometry.POLYGON,
            points = listOf(DrawingPoint(-33.0, 151.0), DrawingPoint(-33.0, 151.1), DrawingPoint(-33.1, 151.1)),
            layerId = layer.id,
            strokeColor = 0xFF00FF00.toInt(), fillColor = 0x5500FF00, strokeWidth = 4f,
            strokeStyle = DrawingStrokeStyle.DASHED,
            scaleX = 2.0, scaleY = 1.5, rotationDegrees = 30.0,
            createdAt = 1_700_000_000_123L
        )
        val wp = Waypoint(
            id = "tcm-1", name = "Axis", latitude = -33.5, longitude = 151.5,
            kind = WaypointKind.ControlMeasure(TacticalControlMeasure.AXIS_OF_MAIN_ATTACK),
            taskColor = TaskColor.BLUE, layerId = layer.id, createdAt = 1_700_000_000_123L
        )

        val export1 = GeoJsonExporter.export(listOf(wp), listOf(poly), listOf(layer))
        val reimported = GeoJsonImporter.parse(export1, existingLayers = emptyList(), fallbackLayerId = "fallback")
        assertEquals(TaskColor.BLUE, reimported.waypoints.single().taskColor)
        assertEquals(DrawingStrokeStyle.DASHED, reimported.drawings.single().strokeStyle)
        val export2 = GeoJsonExporter.export(reimported.waypoints, reimported.drawings, reimported.newLayers)
        assertEquals(export1, export2)
    }

    /** iOS-format drawing (simplestyle keys + uppercase UUIDs) imports with its
     *  styling intact and adopts an existing same-named layer case-insensitively. */
    @Test
    fun importsIosStyleDrawingWithCaseInsensitiveLayer() {
        val existing = DrawingLayer(id = "layer-upper", name = "Alpha", color = 0xFF000000.toInt())
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"AABBCCDD-1111-2222-3333-444455556666",
               "geometry":{"type":"Polygon","coordinates":[[[151.0,-33.0],[151.1,-33.0],[151.1,-33.1],[151.0,-33.0]]]},
               "properties":{"tacticalmaps:category":"drawing","tacticalmaps:layer_id":"LAYER-UPPER",
                 "tacticalmaps:layer":"Alpha","stroke":"#112233","stroke-width":5.0,
                 "fill":"#445566","fill-opacity":0.5,"tacticalmaps:stroke_style":"dashed"}}]}
        """.trimIndent()
        val r = GeoJsonImporter.parse(json, existingLayers = listOf(existing), fallbackLayerId = "fallback")
        assertEquals(0, r.newLayers.size) // adopted the existing "Alpha" layer by case-insensitive id
        val d = r.drawings.single()
        assertEquals("layer-upper", d.layerId)
        assertEquals(0xFF112233.toInt(), d.strokeColor)
        assertEquals(0x80445566.toInt(), d.fillColor) // 0.5*255 → 128 = 0x80 alpha
        assertEquals(5f, d.strokeWidth)
        assertEquals(DrawingStrokeStyle.DASHED, d.strokeStyle)
    }

    /** U3: stroke width is carried across the wire in density-independent dp so a
     *  line keeps its physical width on any display and matches iOS. */
    @Test
    fun strokeWidthRoundTripsAcrossDensity() {
        val density = 2.75f
        val line = DrawingFeature(
            id = "l1", name = "trace", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-33.0, 151.0), DrawingPoint(-33.1, 151.1)),
            layerId = "layer-a", strokeColor = 0xFFFFA000.toInt(), fillColor = 0x33FFA000,
            strokeWidth = 8f
        )
        val json = GeoJsonExporter.export(emptyList(), listOf(line), emptyList(), density = density)
        // The portable simplestyle key is dp (px ÷ density ≈ 2.9); re-parsing at
        // density 1 reads the raw wire value back (keys differ → dp branch).
        val wireDp = GeoJsonImporter.parse(json, existingLayers = emptyList(),
                                           fallbackLayerId = "fallback", density = 1f)
            .drawings.single().strokeWidth
        assertEquals(8f / density, wireDp, 0.02f)
        // Re-import at the same density recovers the original px width.
        val back = GeoJsonImporter.parse(json, existingLayers = emptyList(),
                                         fallbackLayerId = "fallback", density = density)
        assertEquals(8f, back.drawings.single().strokeWidth, 0.05f)
    }

    /** An iOS-style file (dp-only `stroke-width`, no legacy px key) scales to px by
     *  the importing device's density. */
    @Test
    fun iosDpStrokeWidthScalesToPixels() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"i1",
               "geometry":{"type":"LineString","coordinates":[[151.0,-33.0],[151.1,-33.1]]},
               "properties":{"tacticalmaps:category":"drawing","stroke-width":3.0}}]}
        """.trimIndent()
        val r = GeoJsonImporter.parse(json, existingLayers = emptyList(),
                                      fallbackLayerId = "fallback", density = 2.0f)
        assertEquals(6f, r.drawings.single().strokeWidth, 0.01f) // 3 dp × 2.0 = 6 px
    }

    /** An OLD Android file wrote both keys as the SAME px value; the importer must
     *  keep that verbatim (no density re-thickening of pre-existing exports). */
    @Test
    fun legacyEqualKeysTreatedAsPixels() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"o1",
               "geometry":{"type":"LineString","coordinates":[[151.0,-33.0],[151.1,-33.1]]},
               "properties":{"tacticalmaps:category":"drawing","stroke-width":8.0,"stroke_width":8.0}}]}
        """.trimIndent()
        val r = GeoJsonImporter.parse(json, existingLayers = emptyList(),
                                      fallbackLayerId = "fallback", density = 2.75f)
        assertEquals(8f, r.drawings.single().strokeWidth, 0.01f) // px verbatim, not 22
    }

    /** U3 density=1.0 edge case: at density 1, dp == px numerically so the two
     *  keys are equal. The explicit `tacticalmaps:stroke_unit` marker must
     *  disambiguate correctly, so a cross-device import at a higher density
     *  scales the line width up. */
    @Test
    fun density1ExportScalesCorrectlyOnHigherDensityImport() {
        val line = DrawingFeature(
            id = "d1", name = "trace", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-33.0, 151.0), DrawingPoint(-33.1, 151.1)),
            layerId = "layer-a", strokeColor = 0xFFFFA000.toInt(), fillColor = 0x33FFA000,
            strokeWidth = 8f
        )
        // Export at density 1.0: both keys = 8.0
        val json = GeoJsonExporter.export(emptyList(), listOf(line), emptyList(), density = 1.0f)
        // Import on a 2.75x device: must scale dp → px = 8 * 2.75 = 22
        val imported = GeoJsonImporter.parse(json, existingLayers = emptyList(),
                                              fallbackLayerId = "fallback", density = 2.75f)
        assertEquals(22f, imported.drawings.single().strokeWidth, 0.1f)
    }

    @Test
    fun skipsInvalidCoordinatesAndCountsThem() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","id":"a","geometry":{"type":"Point","coordinates":[999.0,10.0]},"properties":{"tacticalmaps:category":"generic"}},
              {"type":"Feature","id":"b","geometry":{"type":"Point","coordinates":[151.0,-33.0]},"properties":{"tacticalmaps:category":"generic"}}]}
        """.trimIndent()
        val r = GeoJsonImporter.parse(json, existingLayers = emptyList(), fallbackLayerId = "fallback")
        assertEquals(1, r.waypoints.size)
        assertEquals(1, r.invalidSkipped)
    }
}
