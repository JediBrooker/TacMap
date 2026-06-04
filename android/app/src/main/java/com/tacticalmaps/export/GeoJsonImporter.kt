package com.tacticalmaps.export

import com.tacticalmaps.drawings.DrawingDocument
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.drawings.DrawingStrokeStyle
import com.tacticalmaps.waypoints.MilitarySymbolSpec
import com.tacticalmaps.waypoints.SymbolAffiliation
import com.tacticalmaps.waypoints.SymbolEchelon
import com.tacticalmaps.waypoints.SymbolFunction
import com.tacticalmaps.waypoints.TacticalControlMeasure
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Parse a GeoJSON FeatureCollection back into TacticalMaps domain objects.
 *
 * Understands both the Android export schema (source / kind / layer_id)
 * and the iOS export schema (tacticalmaps:category / tacticalmaps:layer).
 * Foreign GeoJSON lands as generic shapes on the [fallbackLayerId].
 */
object GeoJsonImporter {

    data class Result(
        val waypoints: List<Waypoint>,
        val drawings:  List<DrawingFeature>,
        val newLayers: List<DrawingLayer>
    )

    class ImportException(msg: String) : Exception(msg)

    fun parse(
        json: String,
        existingLayers: List<DrawingLayer>,
        fallbackLayerId: String
    ): Result {
        val root = try {
            Json.parseToJsonElement(json).jsonObject
        } catch (e: Exception) {
            throw ImportException("Not valid JSON: ${e.message}")
        }
        if (root["type"]?.jsonPrimitive?.contentOrNull != "FeatureCollection") {
            throw ImportException("Not a GeoJSON FeatureCollection")
        }
        val features = root["features"]?.jsonArray ?: JsonArray(emptyList())

        val layersById = existingLayers.associateBy { it.id }.toMutableMap()
        val newLayers = mutableListOf<DrawingLayer>()
        val waypoints = mutableListOf<Waypoint>()
        val drawings  = mutableListOf<DrawingFeature>()

        for (raw in features) {
            val feat = (raw as? JsonObject) ?: continue
            val geometry = feat["geometry"] as? JsonObject ?: continue
            val geomType = geometry["type"]?.jsonPrimitive?.contentOrNull ?: continue
            val props = (feat["properties"] as? JsonObject) ?: JsonObject(emptyMap())
            val featureId = feat["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()

            val layerId = resolveLayerId(props, layersById, newLayers, fallbackLayerId)

            // Detect what kind of feature this is. Try the namespaced
            // iOS schema first, then the Android "source" flag, then
            // fall back to geometry-only guesses.
            val category = resolveCategory(props)
            val isDrawing = category == "drawing"
            val isWaypoint = category == "symbol" || category == "military"
                || category == "controlMeasure" || category == "generic"

            when {
                isDrawing || (!isWaypoint && geomType != "Point") -> {
                    parseDrawing(featureId, geometry, geomType, props, layerId)?.let { drawings += it }
                }
                geomType == "Point" -> {
                    parseWaypoint(featureId, geometry, props, category, layerId)?.let { waypoints += it }
                }
            }
        }
        return Result(waypoints = waypoints, drawings = drawings, newLayers = newLayers)
    }

    private fun resolveCategory(props: JsonObject): String? {
        props["tacticalmaps:category"]?.jsonPrimitive?.contentOrNull?.let { return it }
        val source = props["source"]?.jsonPrimitive?.contentOrNull ?: return null
        if (source != "symbol") return source
        return when (props["kind"]?.jsonPrimitive?.contentOrNull) {
            "military" -> "military"
            "control_measure", "controlMeasure" -> "controlMeasure"
            "generic" -> "generic"
            else -> "generic"
        }
    }

    // ----- Layer resolution -----

    private fun resolveLayerId(
        props: JsonObject,
        layersById: MutableMap<String, DrawingLayer>,
        newLayers: MutableList<DrawingLayer>,
        fallback: String
    ): String {
        // 1. Direct ID match (both Android and iOS write layer_id).
        val explicitId = props["layer_id"]?.jsonPrimitive?.contentOrNull
            ?: props["tacticalmaps:layer_id"]?.jsonPrimitive?.contentOrNull
        if (explicitId != null && explicitId in layersById) return explicitId

        // 2. ID present but no matching layer → create a new layer.
        if (explicitId != null) {
            val name = props["layer_name"]?.jsonPrimitive?.contentOrNull
                ?: props["tacticalmaps:layer"]?.jsonPrimitive?.contentOrNull
                ?: "Imported"
            val color = (props["tacticalmaps:layer_color"]?.jsonPrimitive?.contentOrNull
                ?: props["layer_color"]?.jsonPrimitive?.contentOrNull)
                ?.let(::parseHexColor) ?: DrawingDocument.FRIENDLY_LAYER_COLOR
            val layer = DrawingLayer(id = explicitId, name = name, color = color)
            layersById[explicitId] = layer
            newLayers += layer
            return explicitId
        }

        // 3. Name-only — look up an existing layer with that name.
        val name = props["layer_name"]?.jsonPrimitive?.contentOrNull
            ?: props["tacticalmaps:layer"]?.jsonPrimitive?.contentOrNull
        if (name != null) {
            layersById.values.firstOrNull { it.name == name }?.let { return it.id }
        }

        // 4. Default fallback.
        return fallback
    }

    // ----- Drawing parsing -----

    private fun parseDrawing(
        featureId: String,
        geometry: JsonObject,
        geomType: String,
        props: JsonObject,
        layerId: String
    ): DrawingFeature? {
        val coords = geometry["coordinates"] ?: return null
        val (kind, points) = when (geomType) {
            "Point" -> {
                val arr = (coords as? JsonArray) ?: return null
                val pt = parseCoordinate(arr) ?: return null
                DrawingGeometry.POINT to listOf(pt)
            }
            "LineString" -> {
                val arr = (coords as? JsonArray) ?: return null
                val pts = arr.mapNotNull { parseCoordinate(it) }
                if (pts.size < 2) return null
                DrawingGeometry.LINE to pts
            }
            "Polygon" -> {
                val rings = (coords as? JsonArray) ?: return null
                val outer = (rings.firstOrNull() as? JsonArray) ?: return null
                val pts = outer.mapNotNull { parseCoordinate(it) }.toMutableList()
                // Drop closing repeat if present.
                if (pts.size >= 2 && pts.first() == pts.last()) pts.removeAt(pts.lastIndex)
                if (pts.size < 3) return null
                DrawingGeometry.POLYGON to pts
            }
            else -> return null
        }

        val name = props["name"]?.jsonPrimitive?.contentOrNull ?: ""
        val stroke = props["stroke"]?.jsonPrimitive?.contentOrNull?.let(::parseHexColor)
            ?: props["stroke_color"]?.jsonPrimitive?.contentOrNull?.let(::parseArgbHex)
            ?: 0xFFFFA000.toInt()
        val fill = props["fill"]?.jsonPrimitive?.contentOrNull?.let(::parseHexColor)
            ?: props["fill_color"]?.jsonPrimitive?.contentOrNull?.let(::parseArgbHex)
            ?: 0x33FFA000
        val strokeWidth = (props["stroke-width"] ?: props["stroke_width"])
            ?.jsonPrimitive?.doubleOrNull?.toFloat() ?: 8f
        val dashed = props["stroke_style"]?.jsonPrimitive?.contentOrNull
            ?.let { it.equals("dashed", ignoreCase = true) } == true

        return DrawingFeature(
            id = featureId,
            name = name,
            geometry = kind,
            points = points,
            layerId = layerId,
            strokeColor = stroke,
            fillColor = fill,
            strokeWidth = strokeWidth,
            strokeStyle = if (dashed) DrawingStrokeStyle.DASHED else DrawingStrokeStyle.SOLID
        )
    }

    private fun parseCoordinate(el: JsonElement?): DrawingPoint? {
        val arr = (el as? JsonArray) ?: return null
        val lon = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null
        return DrawingPoint(latitude = lat, longitude = lon)
    }

    // ----- Waypoint parsing -----

    private fun parseWaypoint(
        featureId: String,
        geometry: JsonObject,
        props: JsonObject,
        category: String?,
        layerId: String
    ): Waypoint? {
        val coords = (geometry["coordinates"] as? JsonArray) ?: return null
        val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return null
        val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return null

        val name = props["name"]?.jsonPrimitive?.contentOrNull ?: "Imported"
        val notes = props["notes"]?.jsonPrimitive?.contentOrNull
            ?: props["description"]?.jsonPrimitive?.contentOrNull
        val elevation = (props["tacticalmaps:elevation_m"] ?: props["elevation_m"])
            ?.jsonPrimitive?.doubleOrNull
        val rotation = (props["tacticalmaps:rotation_deg"] ?: props["rotation"])
            ?.jsonPrimitive?.doubleOrNull ?: 0.0
        val scaleX = (props["tacticalmaps:scale_x"] ?: props["scale_x"])
            ?.jsonPrimitive?.doubleOrNull ?: 1.0
        val scaleY = (props["tacticalmaps:scale_y"] ?: props["scale_y"])
            ?.jsonPrimitive?.doubleOrNull ?: 1.0

        val kind: WaypointKind = when (category) {
            "military" -> WaypointKind.Military(parseMilSpec(props))
            "controlMeasure" -> parseControlMeasure(props) ?: WaypointKind.Generic
            else -> WaypointKind.Generic
        }
        return Waypoint(
            id = featureId,
            name = name,
            notes = notes,
            latitude = lat,
            longitude = lon,
            elevationMetres = elevation,
            kind = kind,
            rotation = rotation,
            scaleX = scaleX,
            scaleY = scaleY,
            layerId = layerId
        )
    }

    private fun parseMilSpec(props: JsonObject): MilitarySymbolSpec {
        val aff = parseAffiliation(props["tacticalmaps:affiliation"]?.jsonPrimitive?.contentOrNull)
            ?: SymbolAffiliation.FRIEND
        val ech = parseEchelon(props["tacticalmaps:echelon"]?.jsonPrimitive?.contentOrNull)
            ?: SymbolEchelon.PLATOON
        val fn  = parseFunction(props["tacticalmaps:function"]?.jsonPrimitive?.contentOrNull)
            ?: SymbolFunction.INFANTRY
        val isHeadquarters = props["tacticalmaps:is_hq"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        return MilitarySymbolSpec(aff, ech, fn, isHeadquarters = isHeadquarters)
    }

    private fun parseControlMeasure(props: JsonObject): WaypointKind.ControlMeasure? {
        val name = props["tacticalmaps:tcm_asset"]?.jsonPrimitive?.contentOrNull
            ?: props["tacticalmaps:kind"]?.jsonPrimitive?.contentOrNull
            ?: props["kind"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val measure = TacticalControlMeasure.entries.firstOrNull {
            it.assetName == name || normaliseToken(it.name) == normaliseToken(name)
        }
            ?: return null
        return WaypointKind.ControlMeasure(measure)
    }

    private fun parseAffiliation(raw: String?): SymbolAffiliation? =
        raw?.let { value ->
            SymbolAffiliation.entries.firstOrNull {
                normaliseToken(it.name) == normaliseToken(value)
            }
        }

    private fun parseEchelon(raw: String?): SymbolEchelon? =
        raw?.let { value ->
            SymbolEchelon.entries.firstOrNull {
                normaliseToken(it.name) == normaliseToken(value) ||
                    normaliseToken(it.exportValue) == normaliseToken(value)
            }
        }

    private fun parseFunction(raw: String?): SymbolFunction? =
        raw?.let { value ->
            SymbolFunction.entries.firstOrNull {
                normaliseToken(it.name) == normaliseToken(value) ||
                    normaliseToken(it.assetName) == normaliseToken(value)
            }
        }

    private fun normaliseToken(raw: String): String =
        raw.filter { it.isLetterOrDigit() }.lowercase()

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

    // ----- Colour parsing -----

    /** "#RRGGBB" → 0xFFRRGGBB Int. */
    private fun parseHexColor(hex: String): Int {
        val clean = hex.removePrefix("#")
        if (clean.length != 6) return 0xFFFFA000.toInt()
        val rgb = clean.toLongOrNull(16) ?: return 0xFFFFA000.toInt()
        return (0xFF000000 or rgb).toInt()
    }

    /** "#AARRGGBB" → ARGB Int. Falls back to RGB-only parse. */
    private fun parseArgbHex(hex: String): Int {
        val clean = hex.removePrefix("#")
        return when (clean.length) {
            8 -> clean.toLongOrNull(16)?.toInt() ?: 0xFFFFA000.toInt()
            6 -> parseHexColor(hex)
            else -> 0xFFFFA000.toInt()
        }
    }
}
