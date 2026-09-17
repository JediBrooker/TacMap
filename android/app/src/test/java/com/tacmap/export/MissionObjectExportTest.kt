package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.models.TrackPoint
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionObjectExportTest {
    @Test
    fun productionExportGateAndPayloadPreserveALayerOnlyMission() {
        val layer = DrawingLayer(
            id = "layer-only",
            name = "Empty staging layer",
            color = 0xFF456789.toInt(),
            isVisible = false,
        )

        assertTrue(
            MissionObjectExport.hasExportableContent(
                waypoints = emptyList(),
                drawings = emptyList(),
                layers = listOf(layer),
            )
        )

        val root = Json.parseToJsonElement(
            MissionObjectExport.geoJson(
                waypoints = emptyList(),
                drawings = emptyList(),
                layers = listOf(layer),
            )
        ).jsonObject
        assertTrue(root["features"]!!.jsonArray.isEmpty())
        val catalog = root["tacticalmaps:layers"]!!.jsonArray
        assertEquals(1, catalog.size)
        assertEquals(layer.id, catalog.single().jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("false", catalog.single().jsonObject["visible"]!!.jsonPrimitive.content)
    }

    @Test
    fun combinedActionUsesTruthfulTitleAndExportsOnlyMissionObjectGeoJson() {
        assertEquals("Export All Mission Objects", MissionObjectExport.ACTION_TITLE)
        assertEquals(MissionObjectExport.ACTION_TITLE, MissionObjectExport.SHARE_TITLE)
        assertEquals("TacMap-MissionObjects.geojson", MissionObjectExport.FILE_NAME)

        val layer = DrawingLayer(id = "ops", name = "Operations", color = 0xFF123456.toInt())
        val emptyLayer = DrawingLayer(id = "unused", name = "Unused", color = 0xFFABCDEF.toInt())
        val waypoint = Waypoint(
            id = "symbol-1",
            name = "Observation post",
            latitude = -33.8,
            longitude = 151.2,
            kind = WaypointKind.Generic,
            layerId = layer.id,
        )
        val drawing = DrawingFeature(
            id = "drawing-1",
            name = "Boundary",
            geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-33.81, 151.21), DrawingPoint(-33.82, 151.22)),
            layerId = layer.id,
        )
        val payload = MissionObjectExport.geoJson(
            waypoints = listOf(waypoint),
            drawings = listOf(drawing),
            layers = listOf(layer, emptyLayer),
        )

        val root = Json.parseToJsonElement(payload).jsonObject
        val features = root["features"]!!.jsonArray
        assertEquals(2, features.size)
        assertEquals(
            setOf("symbol", "drawing"),
            features.map { it.jsonObject["properties"]!!.jsonObject["source"]!!.jsonPrimitive.content }.toSet(),
        )
        features.forEach { feature ->
            val properties = feature.jsonObject["properties"]!!.jsonObject
            assertEquals(layer.id, properties["layer_id"]!!.jsonPrimitive.content)
            assertEquals(layer.name, properties["layer_name"]!!.jsonPrimitive.content)
            assertEquals("#123456", properties["tacticalmaps:layer_color"]!!.jsonPrimitive.content)
        }
        val layerCatalog = root["tacticalmaps:layers"]!!.jsonArray
        assertEquals(2, layerCatalog.size)
        assertEquals(
            setOf(layer.id, emptyLayer.id),
            layerCatalog.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet(),
        )
        assertEquals(
            "#ABCDEF",
            layerCatalog.single {
                it.jsonObject["id"]!!.jsonPrimitive.content == emptyLayer.id
            }.jsonObject["color"]!!.jsonPrimitive.content,
        )
        assertFalse(payload.contains("trkpt", ignoreCase = true))
        assertFalse(payload.contains("track point", ignoreCase = true))

        val separateTrackPayload = GpxExporter.export(
            listOf(TrackPoint(-35.123456, 149.654321, 620.0, 1_700_000_000_000L)),
        )
        assertTrue(separateTrackPayload.contains("<trkpt"))
        assertTrue(separateTrackPayload.contains("-35.123456"))
        assertFalse(payload.contains("-35.123456"))
        assertFalse(payload.contains("149.654321"))
    }
}
