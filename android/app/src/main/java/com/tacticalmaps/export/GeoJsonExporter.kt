package com.tacticalmaps.export

import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.waypoints.SymbolAffiliation
import com.tacticalmaps.waypoints.SymbolEchelon
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Serialises waypoints and drawing layers into a GeoJSON FeatureCollection.
 * RFC 7946: coordinates are [longitude, latitude], CRS implicit WGS84.
 */
object GeoJsonExporter {

    fun export(
        waypoints: List<Waypoint>,
        drawings: List<DrawingFeature> = emptyList(),
        layers: List<DrawingLayer> = emptyList()
    ): String {
        val layersById = layers.associateBy { it.id }
        val features = JsonArray(
            waypoints.map { waypointFeature(it, layersById[it.layerId]) } +
                drawings.map { drawingFeature(it, layersById[it.layerId]) }
        )

        val collection = buildJsonObject {
            put("type", "FeatureCollection")
            put("generator", "TacticalMaps Android prototype")
            put("features", features)
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), collection)
    }

    private fun waypointFeature(wp: Waypoint, layer: DrawingLayer?): JsonObject = buildJsonObject {
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
            put("source", "symbol")
            put("kind", wp.kind.exportKind)
            put("kind_display", wp.kind.displayName)
            put("tacticalmaps:category", wp.kind.exportCategory)
            put("tacticalmaps:kind", wp.kind.exportDescriptor)

            put("layer_id", wp.layerId)
            put("tacticalmaps:layer_id", wp.layerId)
            layer?.let {
                put("layer_name", it.name)
                put("tacticalmaps:layer", it.name)
                put("tacticalmaps:layer_color", it.color.rgbHex())
            }

            when (val kind = wp.kind) {
                WaypointKind.Generic -> Unit
                is WaypointKind.Military -> {
                    put("tacticalmaps:affiliation", kind.spec.affiliation.exportValue)
                    put("tacticalmaps:echelon", kind.spec.echelon.exportValue)
                    put("tacticalmaps:function", kind.spec.function.assetName)
                    if (kind.spec.isHeadquarters) put("tacticalmaps:is_hq", true)
                }
                is WaypointKind.ControlMeasure -> {
                    put("tacticalmaps:tcm_name", kind.measure.displayName)
                    put("tacticalmaps:tcm_asset", kind.measure.assetName)
                    put("rotation", wp.rotation)
                    put("scale_x", wp.scaleX)
                    put("scale_y", wp.scaleY)
                    put("tacticalmaps:rotation_deg", wp.rotation)
                    put("tacticalmaps:scale_x", wp.scaleX)
                    put("tacticalmaps:scale_y", wp.scaleY)
                }
            }

            wp.notes?.let {
                put("notes", it)
                put("description", it)
            }
            wp.elevationMetres?.let {
                put("elevation_m", it)
                put("tacticalmaps:elevation_m", it)
            }
            val createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(wp.createdAt))
            put("created_at", createdAt)
            put("tacticalmaps:created_at", createdAt)
        }
    }

    private fun drawingFeature(feature: DrawingFeature, layer: DrawingLayer?): JsonObject =
        buildJsonObject {
            put("type", "Feature")
            put("id", feature.id)
            put("geometry", feature.geometryJson())
            putJsonObject("properties") {
                put("name", feature.name)
                put("source", "drawing")
                put("kind", feature.geometry.name.lowercase())
                put("tacticalmaps:category", "drawing")
                put("tacticalmaps:kind", feature.geometry.name.lowercase())
                put("layer_id", feature.layerId)
                put("tacticalmaps:layer_id", feature.layerId)
                layer?.let {
                    put("layer_name", it.name)
                    put("tacticalmaps:layer", it.name)
                    put("tacticalmaps:layer_color", it.color.rgbHex())
                }
                put("stroke_color", feature.strokeColor.argbHex())
                put("fill_color", feature.fillColor.argbHex())
                put("stroke_width", feature.strokeWidth)
                put("stroke_style", feature.strokeStyle.name.lowercase())
                put("scale_x", feature.scaleX)
                put("scale_y", feature.scaleY)
                put("rotation_degrees", feature.rotationDegrees)
                val createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(feature.createdAt))
                put("created_at", createdAt)
                put("tacticalmaps:created_at", createdAt)
            }
        }

    private fun DrawingFeature.geometryJson(): JsonObject = buildJsonObject {
        val exportPoints = transformedPointsForExport()
        when (geometry) {
            DrawingGeometry.POINT -> {
                put("type", "Point")
                put("coordinates", exportPoints.firstOrNull()?.coordinateJson() ?: JsonNull)
            }
            DrawingGeometry.LINE -> {
                put("type", "LineString")
                putJsonArray("coordinates") {
                    exportPoints.forEach { add(it.coordinateJson()) }
                }
            }
            DrawingGeometry.POLYGON -> {
                put("type", "Polygon")
                putJsonArray("coordinates") {
                    add(JsonArray(exportPoints.closedRing().map { it.coordinateJson() }))
                }
            }
        }
    }

    private fun DrawingFeature.transformedPointsForExport(): List<DrawingPoint> {
        if (scaleX == 1.0 && scaleY == 1.0 && rotationDegrees == 0.0) return points
        if (points.isEmpty()) return points

        val centerLat = points.map { it.latitude }.average()
        val centerLng = points.map { it.longitude }.average()
        val lonScale = cos(Math.toRadians(centerLat)).coerceAtLeast(0.000001)
        val radians = Math.toRadians(-rotationDegrees)
        val cosA = cos(radians)
        val sinA = sin(radians)

        return points.map { point ->
            val localX = (point.longitude - centerLng) * lonScale
            val localY = point.latitude - centerLat
            val scaledX = localX * scaleX
            val scaledY = localY * scaleY
            val rotatedX = scaledX * cosA - scaledY * sinA
            val rotatedY = scaledX * sinA + scaledY * cosA
            DrawingPoint(
                latitude = centerLat + rotatedY,
                longitude = centerLng + rotatedX / lonScale
            )
        }
    }

    private fun DrawingPoint.coordinateJson(): JsonArray =
        JsonArray(listOf(JsonPrimitive(longitude), JsonPrimitive(latitude)))

    private fun List<DrawingPoint>.closedRing(): List<DrawingPoint> {
        if (isEmpty()) return this
        return if (first() == last()) this else this + first()
    }

    private fun Int.argbHex(): String = "#%08X".format(this)

    private fun Int.rgbHex(): String = "#%06X".format(this and 0x00FFFFFF)

    private val WaypointKind.exportKind: String
        get() = when (this) {
            WaypointKind.Generic -> "generic"
            is WaypointKind.Military -> "military"
            is WaypointKind.ControlMeasure -> "control_measure"
        }

    private val WaypointKind.exportCategory: String
        get() = when (this) {
            WaypointKind.Generic -> "generic"
            is WaypointKind.Military -> "military"
            is WaypointKind.ControlMeasure -> "controlMeasure"
        }

    private val WaypointKind.exportDescriptor: String
        get() = when (this) {
            WaypointKind.Generic -> "generic"
            is WaypointKind.Military ->
                "${spec.affiliation.exportValue}.${spec.function.assetName}.${spec.echelon.exportValue}"
            is WaypointKind.ControlMeasure -> measure.assetName
        }

    private val SymbolAffiliation.exportValue: String
        get() = name.lowercase()

    private val SymbolEchelon.exportValue: String
        get() = when (this) {
            SymbolEchelon.TEAM -> "team"
            SymbolEchelon.SECTION -> "section"
            SymbolEchelon.PLATOON -> "platoon"
            SymbolEchelon.COMPANY -> "company"
            SymbolEchelon.BATTALION_REGIMENT -> "battalionRegiment"
            SymbolEchelon.BRIGADE -> "brigade"
            SymbolEchelon.DIVISION -> "division"
        }
}
