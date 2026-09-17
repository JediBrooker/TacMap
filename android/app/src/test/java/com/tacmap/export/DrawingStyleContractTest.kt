package com.tacmap.export

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStrokeStyle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DrawingStyleContractTest {
    @Test
    fun sharedDensityRoundTripsUsePortableWidthsWithoutPersistedRewrite() {
        val root = fixture()
        root.getValue("widthRoundTrips").jsonArray.forEach { element ->
            val case = element.jsonObject
            val source = line(
                width = case.d("sourceStoredPixels").toFloat(),
                stroke = 0xFF336699.toInt(),
                fill = 0x6633CC44,
            )
            val wire = GeoJsonExporter.export(
                emptyList(),
                listOf(source),
                density = case.d("sourceDensity").toFloat(),
            )
            val properties = Json.parseToJsonElement(wire).jsonObject
                .getValue("features").jsonArray.single().jsonObject
                .getValue("properties").jsonObject
            assertEquals(
                case.d("expectedPortableWidth"),
                properties.getValue("stroke-width").jsonPrimitive.double,
                0.0001,
            )
            val imported = GeoJsonImporter.parse(
                wire,
                existingLayers = emptyList(),
                fallbackLayerId = "default",
                density = case.d("targetDensity").toFloat(),
            ).drawings.single()
            assertEquals(case.d("expectedTargetStoredPixels").toFloat(), imported.strokeWidth, 0.0001f)
            // Source model remains raw renderer pixels; no migration or rewrite.
            assertEquals(case.d("sourceStoredPixels").toFloat(), source.strokeWidth, 0f)
        }
    }

    @Test
    fun polygonStrokeFillAndOpacityRoundTripIndependently() {
        val root = fixture()
        val expected = root.getValue("polygonStyle").jsonObject
            .getValue("expectedAndroidAtDensity2_5").jsonObject
        val polygon = DrawingFeature(
            id = "polygon",
            name = "Styled area",
            geometry = DrawingGeometry.POLYGON,
            points = listOf(
                DrawingPoint(-33.8, 151.2),
                DrawingPoint(-33.8, 151.3),
                DrawingPoint(-33.9, 151.3),
            ),
            strokeColor = parseArgb(expected.str("strokeArgb")),
            fillColor = parseArgb(expected.str("fillArgb")),
            strokeWidth = expected.d("strokeWidthPixels").toFloat(),
            strokeStyle = DrawingStrokeStyle.DASHED,
        )
        val wire = GeoJsonExporter.export(emptyList(), listOf(polygon), density = 2.5f)
        val imported = GeoJsonImporter.parse(
            wire,
            existingLayers = emptyList(),
            fallbackLayerId = "default",
            density = 2.5f,
        ).drawings.single()
        assertEquals(polygon.strokeColor, imported.strokeColor)
        assertEquals(polygon.fillColor, imported.fillColor)
        assertEquals(polygon.strokeWidth, imported.strokeWidth, 0.0001f)
        assertEquals(polygon.strokeStyle, imported.strokeStyle)

        val mutations = root.getValue("polygonStyle").jsonObject
            .getValue("independentMutations").jsonArray.associate { element ->
                val case = element.jsonObject
                case.str("caseKey") to case
            }
        val strokeOnly = polygon.copy(strokeColor = parseRgb(mutations.getValue("change_stroke_only")
            .getValue("mutation").jsonObject.str("strokeRgb")))
        assertEquals(polygon.fillColor, strokeOnly.fillColor)

        val fillCase = mutations.getValue("change_fill_only")
        val fillMutation = fillCase.getValue("mutation").jsonObject
        val alpha = (fillMutation.d("fillOpacity") * 255).toInt()
        val fillOnly = polygon.copy(fillColor = (alpha shl 24) or (parseRgb(fillMutation.str("fillRgb")) and 0xFFFFFF))
        assertEquals(polygon.strokeColor, fillOnly.strokeColor)
        assertEquals(fillCase.str("expectedFillRgb").removePrefix("#").toLong(16).toInt(), fillOnly.fillColor and 0xFFFFFF)
    }

    private fun line(width: Float, stroke: Int, fill: Int) = DrawingFeature(
        id = "line",
        name = "Line",
        geometry = DrawingGeometry.LINE,
        points = listOf(DrawingPoint(-33.8, 151.2), DrawingPoint(-33.9, 151.3)),
        strokeColor = stroke,
        fillColor = fill,
        strokeWidth = width,
    )

    private fun parseArgb(value: String): Int = value.removePrefix("#").toLong(16).toInt()
    private fun parseRgb(value: String): Int = 0xFF000000.toInt() or value.removePrefix("#").toInt(16)
    private fun fixture() = Json.parseToJsonElement(fixtureText("drawing_style.json")).jsonObject
    private fun kotlinx.serialization.json.JsonObject.d(key: String) = getValue(key).jsonPrimitive.double
    private fun kotlinx.serialization.json.JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun fixtureText(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val fixture = File(dir, "testdata/$name")
            if (fixture.exists()) return fixture.readText()
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name")
    }
}
