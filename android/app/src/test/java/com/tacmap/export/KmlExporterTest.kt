package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.roundToInt

/** Pins KML export to `testdata/kml_export.json`, the same fixture the iOS suite reads. */
class KmlExporterTest {
    private val fixture: JsonObject by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/kml_export.json")
            if (file.exists()) return@lazy Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/kml_export.json")
    }

    private val layers by lazy {
        fixture["layers"]!!.jsonArray.map { it.jsonObject }.map { json ->
            DrawingLayer(
                id = json.string("id"),
                name = json.string("name"),
                color = rgb(json.string("color")),
                isVisible = json["visible"]!!.jsonPrimitive.boolean,
            )
        }
    }

    private val waypoints by lazy {
        fixture["waypoints"]!!.jsonArray.map { it.jsonObject }.map { json ->
            val kind = when (json.string("kind")) {
                "military" -> WaypointKind.Military(
                    MilitarySymbolSpec(
                        affiliation = SymbolAffiliation.entries.first {
                            it.name.equals(json.string("affiliation"), ignoreCase = true)
                        }
                    )
                )
                "controlMeasure" -> WaypointKind.ControlMeasure()
                else -> WaypointKind.Generic
            }
            Waypoint(
                id = json.string("id"),
                name = json.string("name"),
                notes = json["notes"]?.jsonPrimitive?.content,
                latitude = json["lat"]!!.jsonPrimitive.double,
                longitude = json["lon"]!!.jsonPrimitive.double,
                elevationMetres = json["elevation"]?.jsonPrimitive?.doubleOrNull,
                kind = kind,
                taskColor = json["taskColor"]?.jsonPrimitive?.content
                    ?.let { name -> TaskColor.entries.first { it.name.equals(name, ignoreCase = true) } }
                    ?: TaskColor.BLACK,
                layerId = json.string("layerID"),
            )
        }
    }

    private val drawings by lazy {
        fixture["drawings"]!!.jsonArray.map { it.jsonObject }.map { json ->
            val alpha = (json["fillOpacity"]!!.jsonPrimitive.double * 255).roundToInt()
            DrawingFeature(
                id = json.string("id"),
                name = json.string("name"),
                notes = json["notes"]?.jsonPrimitive?.content,
                geometry = when (json.string("kind")) {
                    "point" -> DrawingGeometry.POINT
                    "polyline" -> DrawingGeometry.LINE
                    else -> DrawingGeometry.POLYGON
                },
                points = json["coordinates"]!!.jsonArray.map { pair ->
                    val (lat, lon) = pair.jsonArray.map { it.jsonPrimitive.double }
                    DrawingPoint(lat, lon)
                },
                layerId = json.string("layerID"),
                strokeColor = rgb(json.string("stroke")),
                fillColor = (alpha shl 24) or (rgb(json.string("fill")) and 0xFFFFFF),
                strokeWidth = json["strokeWidth"]!!.jsonPrimitive.double.toFloat(),
            )
        }
    }

    @Test
    fun kmlMatchesSharedFixture() {
        val kml = KmlExporter.export(waypoints, drawings, layers)
        assertEquals(fixture.string("expectedKML"), kml)
    }

    @Test
    fun strokeWidthsUseDensityIndependentUnits() {
        val scaled = drawings.map { it.copy(strokeWidth = it.strokeWidth * 2.625f) }
        assertEquals(
            fixture.string("expectedKML"),
            KmlExporter.export(waypoints, scaled, layers, density = 2.625f),
        )
    }

    @Test
    fun fixedPointFormattingMatchesC() {
        assertEquals("42.2", KmlExporter.fixed(42.25, 1))
        assertEquals("42.4", KmlExporter.fixed(42.35, 1)) // 42.35 is stored just above .35
        assertEquals("0.0000000", KmlExporter.fixed(-0.00000001, 7))
        assertEquals("-0.1278000", KmlExporter.fixed(-0.1278, 7))
        assertEquals("ff0000ff", KmlExporter.kmlColor(0xFFFF0000.toInt(), 255))
    }

    @Test
    fun kmzHoldsDocumentFirstAndOneImagePerDistinctSymbol() {
        val unit = byteArrayOf(1, 2, 3)
        val marker = byteArrayOf(4, 5, 6, 7)
        val bytes = KmzExporter.export(waypoints, drawings, layers) { waypoint ->
            when (waypoint.kind) {
                is WaypointKind.Military -> KmzSymbolImage(unit, hotSpotX = 0.5, hotSpotY = 0.25)
                WaypointKind.Generic -> KmzSymbolImage(marker, hotSpotX = 0.5, hotSpotY = 0.0)
                else -> null
            }
        }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val unitPath = "files/icons/${KmzExporter.iconName(unit)}.png"
        val markerPath = "files/icons/${KmzExporter.iconName(marker)}.png"
        assertEquals(listOf("doc.kml", unitPath, markerPath), entries.keys.toList())
        assertTrue(entries.getValue(unitPath).contentEquals(unit))
        val kml = entries.getValue("doc.kml").toString(Charsets.UTF_8)
        assertEquals(1, Regex("<href>$unitPath</href>").findAll(kml).count())
        assertTrue(kml.contains("""<hotSpot x="0.5" y="0.25" xunits="fraction" yunits="fraction"/>"""))
        assertTrue(kml.contains("""<hotSpot x="0.5" y="0" xunits="fraction" yunits="fraction"/>"""))
        // Both military symbols share the one unit image style.
        assertEquals(2, Regex("<styleUrl>#icon-${KmzExporter.iconName(unit)}</styleUrl>").findAll(kml).count())
        // The control measure without an image keeps its affiliation pin.
        assertTrue(kml.contains("<styleUrl>#sym-friend</styleUrl>"))
    }

    private fun rgb(hex: String): Int = (0xFF shl 24) or hex.removePrefix("#").toInt(16)

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content
}
