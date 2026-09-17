package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingLayer
import com.tacmap.waypoints.Waypoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The user-facing boundary for the combined mission-object export.
 *
 * A recorded route is intentionally absent from this API. Tracks have their
 * own GPX export, which keeps the combined GeoJSON action from implying that
 * it is a full application backup.
 */
object MissionObjectExport {
    const val ACTION_TITLE = "Export All Mission Objects"
    const val SHARE_TITLE = ACTION_TITLE
    const val FILE_NAME = "TacMap-MissionObjects.geojson"

    /** The production share path must retain a mission's empty layer catalog. */
    fun hasExportableContent(
        waypoints: List<Waypoint>,
        drawings: List<DrawingFeature>,
        layers: List<DrawingLayer>,
    ): Boolean = waypoints.isNotEmpty() || drawings.isNotEmpty() || layers.isNotEmpty()

    fun geoJson(
        waypoints: List<Waypoint>,
        drawings: List<DrawingFeature>,
        layers: List<DrawingLayer>,
        density: Float = 1f,
    ): String {
        val base = Json.parseToJsonElement(
            GeoJsonExporter.export(
                waypoints = waypoints,
                drawings = drawings,
                layers = layers,
                density = density,
            )
        ).jsonObject
        val layerCatalog = JsonArray(layers.map { layer ->
            buildJsonObject {
                put("id", layer.id)
                put("name", layer.name)
                put("color", String.format("#%06X", layer.color and 0xFFFFFF))
                put("visible", layer.isVisible)
                put(
                    "created_at",
                    DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(layer.createdAt)
                            .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    )
                )
            }
        })
        val missionCollection = JsonObject(base + ("tacticalmaps:layers" to layerCatalog))
        return Json { prettyPrint = true }
            .encodeToString(JsonObject.serializer(), missionCollection)
    }
}
