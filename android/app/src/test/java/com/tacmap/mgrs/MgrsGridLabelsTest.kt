package com.tacmap.mgrs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mil.nga.grid.features.Point
import mil.nga.mgrs.MGRS
import mil.nga.mgrs.grid.GridType
import mil.nga.mgrs.utm.UTM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Grid line labels vs testdata/mgrs_grid_labels.json (same vectors as iOS
 * MGRSGridLabelTests). The 1 km line at northing 87000 used to read "86"
 * because the label came from truncating a round-tripped 86999.99.
 */
class MgrsGridLabelsTest {

    private val cases = Json.parseToJsonElement(testdataFile("mgrs_grid_labels.json").readText())
        .jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun listedLinesCarryTheirOwnValue() {
        cases.forEach { case ->
            val labels = build(case)
            case["lines"]!!.jsonArray.map { it.jsonObject }.forEach { line ->
                val type = GridType.valueOf(line.str("gridType"))
                val vertical = line["vertical"]!!.jsonPrimitive.boolean
                val anchor = MGRS.parse(line.str("mgrs")).toUTM()
                val lineValue = across(anchor, vertical)
                val onLine = labels.filter { label ->
                    label.type == type && label.isVertical == vertical &&
                        abs(across(utmOf(label, anchor), vertical) - lineValue) < interval(type) / 20
                }
                val what = "${case.str("name")}: $type ${if (vertical) "E" else "N"} $lineValue"
                assertTrue("$what has no label", onLine.isNotEmpty())
                onLine.forEach { assertEquals(what, line.str("label"), it.text) }
            }
        }
    }

    @Test
    fun everyNumericLabelMatchesTheLineUnderIt() {
        cases.forEach { case ->
            val labels = build(case)
            var checked = 0
            labels.filter { it.type == GridType.KILOMETER || it.type == GridType.TEN_KILOMETER }
                .forEach { label ->
                    val interval = interval(label.type)
                    val value = across(UTM.from(Point.point(label.lng, label.lat)), label.isVertical)
                    val nearest = (value / interval).roundToLong()
                    // Anchor is the lat/lon midpoint, which sags off a long
                    // line (~1.5 m on 10 km), so match within 1/20 of a square.
                    if (abs(value - nearest * interval) > interval / 20) return@forEach
                    val expected = if (label.type == GridType.KILOMETER) {
                        "%02d".format(nearest % 100)
                    } else {
                        (nearest % 10).toString()
                    }
                    assertEquals("${case.str("name")}: ${label.type} at $value", expected, label.text)
                    checked++
                }
            assertTrue("${case.str("name")}: no numeric labels checked", checked > 0)
        }
    }

    private fun build(case: JsonObject): List<MgrsGridRenderer.LabelMark> {
        val b = case["bounds"]!!.jsonObject
        return MgrsGridRenderer.build(
            minLat = b.d("south"), minLng = b.d("west"),
            maxLat = b.d("north"), maxLng = b.d("east"),
            mapWidthPx = case["mapWidth"]!!.jsonPrimitive.int,
        ).second
    }

    private fun utmOf(label: MgrsGridRenderer.LabelMark, zoneOf: UTM): UTM =
        UTM.from(Point.point(label.lng, label.lat), zoneOf.zone, zoneOf.hemisphere)

    private fun across(utm: UTM, vertical: Boolean) = if (vertical) utm.easting else utm.northing

    private fun interval(type: GridType): Double = when (type) {
        GridType.HUNDRED_KILOMETER -> 100_000.0
        GridType.TEN_KILOMETER -> 10_000.0
        else -> 1_000.0
    }

    private fun testdataFile(name: String): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .take(8).map { File(it, "testdata/$name") }.firstOrNull { it.isFile }
            ?: error("Could not locate testdata/$name from ${System.getProperty("user.dir")}")

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.d(key: String) = this[key]!!.jsonPrimitive.double
}
