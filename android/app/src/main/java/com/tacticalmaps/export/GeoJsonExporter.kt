package com.tacticalmaps.export

import com.tacticalmaps.waypoints.Waypoint
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Serialises waypoints (and, later, drawing layers) into a GeoJSON FeatureCollection.
 * RFC 7946: coordinates are [longitude, latitude], CRS implicit WGS84.
 */
object GeoJsonExporter {

    fun export(waypoints: List<Waypoint>): String {
        val features = JsonArray(waypoints.map { wp ->
            buildJsonObject {
                put("type", "Feature")
                put("id", wp.id)
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") {
                        add(wp.longitude); add(wp.latitude)
                    }
                }
                putJsonObject("properties") {
                    put("name", wp.name)
                    put("kind", wp.kind.name.lowercase())
                    wp.notes?.let { put("notes", it) }
                    wp.elevationMetres?.let { put("elevation_m", it) }
                    put("created_at", DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(wp.createdAt)
                    ))
                }
            }
        })

        val collection = buildJsonObject {
            put("type", "FeatureCollection")
            put("generator", "TacticalMaps Android prototype")
            put("features", features)
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), collection)
    }
}
