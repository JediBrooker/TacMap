package com.tacticalmaps

import com.tacticalmaps.calibration.AffineFitter
import com.tacticalmaps.calibration.Fiduciary
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.export.GeoJsonExporter
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.Waypoint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Loads the shared golden vectors in `testdata/` — the SAME files the iOS suite
 * reads — and asserts the Kotlin implementations match them, so the two native
 * ports of the affine solve, MGRS formatting, and GeoJSON geometry cannot
 * silently drift apart. See `testdata/README.md`.
 */
class SharedVectorsTest {

    private fun fixtureText(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/$name")
            if (f.exists()) return f.readText()
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name from ${System.getProperty("user.dir")}")
    }

    private fun obj(name: String): JsonObject = Json.parseToJsonElement(fixtureText(name)).jsonObject
    private fun JsonObject.d(k: String): Double = this[k]!!.jsonPrimitive.double
    private fun JsonObject.str(k: String): String = this[k]!!.jsonPrimitive.content

    @Test
    fun sharedAffineVectors() {
        val cases = obj("affine_fits.json")["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (cEl in cases) {
            val c = cEl.jsonObject
            val tf = c["transform"]!!.jsonObject
            val fids = c["fiduciaries"]!!.jsonArray.map { it.jsonObject }.map {
                Fiduciary(pdfX = it.d("pdfX"), pdfY = it.d("pdfY"), mgrs = "",
                          latitude = it.d("lat"), longitude = it.d("lon"))
            }
            val tol = c.d("coeffTolerance")
            val rmsMax = c.d("rmsMaxMetres")
            val r = AffineFitter.fit(fids)
            assertEquals(tf.d("a"), r.transform.a, tol)
            assertEquals(tf.d("b"), r.transform.b, tol)
            assertEquals(tf.d("c"), r.transform.c, tol)
            assertEquals(tf.d("d"), r.transform.d, tol)
            assertEquals(tf.d("e"), r.transform.e, tol)
            assertEquals(tf.d("f"), r.transform.f, tol)
            assertTrue(r.rmsMetres < rmsMax)
        }
    }

    @Test
    fun sharedMgrsVectors() {
        val root = obj("mgrs_samples.json")
        for (cEl in root["coordinates"]!!.jsonArray) {
            val c = cEl.jsonObject
            val lat = c.d("lat")
            val lon = c.d("lon")
            assertEquals(c.str("spaced"), MgrsFormatter.format(lat, lon, spaced = true))
            assertEquals(c.str("compact"), MgrsFormatter.format(lat, lon, spaced = false))
            val back = MgrsFormatter.parse(c.str("spaced"))!!
            assertTrue(abs(back.first - lat) < 1e-3)
            assertTrue(abs(back.second - lon) < 1e-3)
        }
        for (sEl in root["invalid"]!!.jsonArray) {
            assertNull(MgrsFormatter.parse(sEl.jsonPrimitive.content))
        }
    }

    private fun exportedGeometries(wps: List<Waypoint>, ds: List<DrawingFeature>): List<JsonElement> =
        Json.parseToJsonElement(GeoJsonExporter.export(wps, ds)).jsonObject["features"]!!
            .jsonArray.map { it.jsonObject["geometry"]!! }

    /** Numeric-aware deep comparison (so `2` and `2.0` match). */
    private fun jsonNumEqual(a: JsonElement, b: JsonElement): Boolean = when {
        a is JsonObject && b is JsonObject ->
            a.keys == b.keys && a.keys.all { jsonNumEqual(a.getValue(it), b.getValue(it)) }
        a is JsonArray && b is JsonArray ->
            a.size == b.size && a.indices.all { jsonNumEqual(a[it], b[it]) }
        a is JsonPrimitive && b is JsonPrimitive -> {
            val ad = a.doubleOrNull
            val bd = b.doubleOrNull
            if (ad != null && bd != null) abs(ad - bd) < 1e-9 else a.content == b.content
        }
        else -> false
    }

    @Test
    fun sharedGeoJsonGeometry() {
        val root = obj("geojson_geometry.json")

        val pt = root["point"]!!.jsonObject
        val pin = pt["input"]!!.jsonObject
        val wp = Waypoint(name = "p", latitude = pin.d("lat"), longitude = pin.d("lon"))
        assertTrue("point", jsonNumEqual(pt["geometry"]!!, exportedGeometries(listOf(wp), emptyList())[0]))

        val ln = root["line"]!!.jsonObject
        val lpts = ln["input"]!!.jsonArray.map { it.jsonObject }.map { DrawingPoint(it.d("lat"), it.d("lon")) }
        val line = DrawingFeature(name = "l", geometry = DrawingGeometry.LINE, points = lpts)
        assertTrue("line", jsonNumEqual(ln["geometry"]!!, exportedGeometries(emptyList(), listOf(line))[0]))

        val pg = root["polygon"]!!.jsonObject
        val ppts = pg["input"]!!.jsonArray.map { it.jsonObject }.map { DrawingPoint(it.d("lat"), it.d("lon")) }
        val poly = DrawingFeature(name = "p", geometry = DrawingGeometry.POLYGON, points = ppts)
        assertTrue("polygon", jsonNumEqual(pg["geometry"]!!, exportedGeometries(emptyList(), listOf(poly))[0]))
    }
}
