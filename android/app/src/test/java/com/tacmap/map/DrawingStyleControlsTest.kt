package com.tacmap.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The drawing card's stroke width and fill opacity controls use the shared
 *  style contract in `testdata/drawing_style.json`. */
class DrawingStyleControlsTest {
    @Test
    fun strokeWidthSliderWorksInPortablePointsAtEveryDensity() {
        fixture().getValue("widthRoundTrips").jsonArray.forEach { element ->
            val case = element.jsonObject
            val points = DrawingDefaults.portableStrokeWidth(
                case.d("sourceStoredPixels").toFloat(),
                case.d("sourceDensity").toFloat(),
            )
            assertEquals(case.d("expectedPortableWidth").toFloat(), points, 0.0001f)
            assertEquals(
                case.d("expectedTargetStoredPixels").toFloat(),
                DrawingDefaults.storedStrokeWidth(points, case.d("targetDensity").toFloat()),
                0.0001f,
            )
        }
    }

    @Test
    fun strokeWidthMatchesTheIosRangeAndDefault() {
        assertEquals(1.25f, DrawingDefaults.storedStrokeWidth(0.1f, 2.5f), 0f)
        assertEquals(40f, DrawingDefaults.storedStrokeWidth(20f, 2.5f), 0f)
        assertEquals(
            DrawingDefaults.rendererStrokeWidth(2.5f),
            DrawingDefaults.storedStrokeWidth(DrawingDefaults.STROKE_WIDTH_DP, 2.5f),
            0f,
        )
    }

    @Test
    fun fillOpacityPresetsRoundTripThroughTheStoredAlpha() {
        assertEquals(listOf(0, 10, 20, 40, 60, 80, 100), DrawingDefaults.FILL_OPACITY_PRESETS)
        DrawingDefaults.FILL_OPACITY_PRESETS.forEach { percent ->
            assertEquals(percent, DrawingDefaults.fillPercent(DrawingDefaults.fillAlpha(percent)))
        }
        assertEquals(0, DrawingDefaults.fillAlpha(0))
        assertEquals(DrawingDefaults.DEFAULT_FILL_ALPHA, DrawingDefaults.fillAlpha(20))
        assertEquals(255, DrawingDefaults.fillAlpha(100))
        // The portable 0.4 fill opacity in the shared polygon fixture.
        assertEquals(0x66, DrawingDefaults.fillAlpha(40))
    }

    private fun JsonObject.d(key: String) = getValue(key).jsonPrimitive.double

    private fun fixture(): JsonObject {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val file = File(dir, "testdata/drawing_style.json")
            if (file.exists()) return Json.parseToJsonElement(file.readText()).jsonObject
            dir = dir?.parentFile
        }
        error("Could not locate testdata/drawing_style.json")
    }
}
