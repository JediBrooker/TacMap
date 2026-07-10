package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/**
 * Serialises waypoints + drawing layers into a GeoJSON FeatureCollection.
 * RFC 7946: coords are [longitude, latitude], CRS is implicit WGS84.
 */
object GeoJsonExporter {

    /**
     * @param density display density to convert stored stroke width (Google
     *   Maps pixels) to density-independent dp that the portable `stroke-width`
     *   key carries (matching iOS points and simplestyle-spec CSS-px intent).
     *   Defaults to 1f, identity conversion for callers/tests with no display.
     */
    fun export(
        waypoints: List<Waypoint>,
        drawings: List<DrawingFeature> = emptyList(),
        layers: List<DrawingLayer> = emptyList(),
        density: Float = 1f
    ): String {
        val layersById = layers.associateBy { it.id }
        val features = JsonArray(
            waypoints.map { waypointFeature(it, layersById[it.layerId]) } +
                drawings.map { drawingFeature(it, layersById[it.layerId], density) }
        )

        val collection = buildJsonObject {
            put("type", "FeatureCollection")
            put("generator", "TacMap Android prototype")
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
                is WaypointKind.Marker -> {
                    put("tacticalmaps:marker_set", kind.marker.set.name.lowercase())
                    put("tacticalmaps:marker_symbol", kind.marker.symbolId)
                    put("tacticalmaps:marker_color", kind.marker.colorHex)
                }
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
            // task/control-measure colour, shared lowercase token set (matches
            // iOS) so symbol colour round-trips across platforms
            put("tacticalmaps:task_color", wp.taskColor.name.lowercase())

            wp.notes?.let {
                put("notes", it)
                put("description", it)
            }
            wp.elevationMetres?.let {
                put("elevation_m", it)
                put("tacticalmaps:elevation_m", it)
            }
            val createdAt = isoSeconds(wp.createdAt)
            put("created_at", createdAt)
            put("tacticalmaps:created_at", createdAt)
        }
    }

    /** ISO-8601 truncated to whole seconds, same as iOS exporter (no fractional
     *  seconds) so the same object serialises identically on both platforms and
     *  cross-platform sync doesn't churn on created_at. */
    private fun isoSeconds(epochMs: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(
            Instant.ofEpochMilli(epochMs).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        )

    private fun drawingFeature(feature: DrawingFeature, layer: DrawingLayer?, density: Float): JsonObject =
        buildJsonObject {
            put("type", "Feature")
            put("id", feature.id)
            put("geometry", feature.geometryJson())
            putJsonObject("properties") {
                put("name", feature.name)
                feature.notes?.let {
                    put("notes", it)
                    put("description", it) // simplestyle + iOS DrawingShape.notes
                }
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
                // simplestyle-spec keys, read by iOS and external tools (colours
                // as #RRGGBB, opacity separate). This is what makes Android
                // drawings keep their styling on iOS import.
                put("stroke", feature.strokeColor.rgbHex())
                // portable key in dp (px / density) so iOS + external tools render
                // at the same physical width. Legacy `stroke_width` below stays
                // in raw px for older Android readers.
                put("stroke-width", if (density > 0f) feature.strokeWidth / density else feature.strokeWidth)
                if (feature.geometry == DrawingGeometry.POLYGON) {
                    put("fill", feature.fillColor.rgbHex())
                    put("fill-opacity", ((feature.fillColor ushr 24) and 0xFF) / 255.0)
                }
                put("tacticalmaps:stroke_style", feature.strokeStyle.name.lowercase())
                put("tacticalmaps:stroke_unit", "dp")
                // legacy Android keys for backward compat
                put("stroke_color", feature.strokeColor.argbHex())
                put("fill_color", feature.fillColor.argbHex())
                put("stroke_width", feature.strokeWidth)
                put("stroke_style", feature.strokeStyle.name.lowercase())
                feature.lineGraphic?.let { put("tacticalmaps:line_graphic", it.wire) }
                // geometry is exported already baked (rotation/scale applied) so
                // transform is identity on the wire, re-import must not apply
                // it a second time
                put("scale_x", 1.0)
                put("scale_y", 1.0)
                put("rotation_degrees", 0.0)
                val createdAt = isoSeconds(feature.createdAt)
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
            is WaypointKind.Marker -> "marker"
        }

    private val WaypointKind.exportCategory: String
        get() = when (this) {
            WaypointKind.Generic -> "generic"
            is WaypointKind.Military -> "military"
            is WaypointKind.ControlMeasure -> "controlMeasure"
            is WaypointKind.Marker -> "marker"
        }

    private val WaypointKind.exportDescriptor: String
        get() = when (this) {
            WaypointKind.Generic -> "generic"
            is WaypointKind.Military ->
                "${spec.affiliation.exportValue}.${spec.function.assetName}.${spec.echelon.exportValue}"
            is WaypointKind.ControlMeasure -> measure.assetName
            is WaypointKind.Marker -> "${marker.set.name.lowercase()}.${marker.symbolId}"
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
