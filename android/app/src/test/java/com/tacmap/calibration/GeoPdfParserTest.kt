package com.tacmap.calibration

import com.tacmap.mgrs.MgrsFormatter
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GeoPDF viewport parsing against a real USGS US Topo sheet, pinned by
 * testdata/geopdf_usgs_sf_north.json (same vectors as iOS PDFImportSmokeTests).
 *
 * The map body's LPTS overshoot the unit square a touch, which the 2.1.0
 * hardening rejected, so every US Topo sheet fell back to the camera-centred
 * placeholder at ~0.56x scale.
 */
class GeoPdfParserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vectors: JsonObject =
        Json.parseToJsonElement(testdataFile("geopdf_usgs_sf_north.json").readText()).jsonObject
    private val fixture = testdataFile(vectors.str("fixture"))

    @Test
    fun usgsUsTopoMapBodyIsSelectedDespiteLptsOvershoot() {
        val parsed = parseOrFail(fixture)
        val body = vectors.obj("mapBody")

        assertEquals(vectors.d("pageWidth"), parsed.pageWidth, 1e-6)
        assertEquals(vectors.d("pageHeight"), parsed.pageHeight, 1e-6)
        assertEquals(body["controlPoints"]!!.jsonPrimitive.int, parsed.correspondences.size)
        // Map body, not the 32..42N quadrangle-location inset or the
        // adjoining-sheet diagram.
        val (latLo, latHi) = body.range("latRange")
        val (lonLo, lonHi) = body.range("lonRange")
        parsed.correspondences.forEach {
            assertTrue("lat ${it.latitude}", it.latitude in latLo..latHi)
            assertTrue("lon ${it.longitude}", it.longitude in lonLo..lonHi)
        }
        // LPTS x = -0.00708 on the SW corner lands just left of the page.
        assertEquals(-12.234, parsed.correspondences.first().pdfX, 0.01)
    }

    @Test
    fun usgsUsTopoBoundsAndCentreMatchTheRealQuad() {
        val parsed = parseOrFail(fixture)
        val quad = vectors.obj("quad")
        val lats = parsed.correspondences.map { it.latitude }
        val lons = parsed.correspondences.map { it.longitude }

        // Parsed extent is the whole page incl. collar, so it has to contain
        // the published 7.5' quad and be centred on it.
        assertTrue(lats.min() < quad.d("south") && lats.max() > quad.d("north"))
        assertTrue(lons.min() < quad.d("west") && lons.max() > quad.d("east"))
        val centreLat = (lats.min() + lats.max()) / 2
        val centreLon = (lons.min() + lons.max()) / 2
        val offCentre = metresBetween(
            centreLat, centreLon,
            (quad.d("south") + quad.d("north")) / 2, (quad.d("west") + quad.d("east")) / 2,
        )
        assertTrue(
            "centre $centreLat,$centreLon is ${offCentre}m off the quad",
            offCentre < vectors.d("centreToleranceMetres"),
        )
    }

    @Test
    fun usgsUsTopoPlacementIsTrueScaleAndOnThePrintedUtmGrid() {
        val parsed = parseOrFail(fixture)
        val transform = AffineFitter.fit(parsed.correspondences.map { it.toFiduciary() }).transform

        // 1:24,000 is ~8.47 m of ground per PDF point, the placeholder was ~0.56x.
        val scale = vectors.obj("scale")
        val from = scale.obj("from")
        listOf("alongX", "alongY").forEach { axis ->
            val to = scale.obj(axis)
            val metres = metresBetween(transform.at(from), transform.at(to))
            val points = hypot(to.d("pdfX") - from.d("pdfX"), to.d("pdfY") - from.d("pdfY"))
            assertEquals(
                axis,
                scale.d("metresPerPdfPoint"),
                metres / points,
                scale.d("metresPerPdfPoint") * scale.d("tolerance"),
            )
        }

        // Printed grid crossings pin position and the ~0.34 deg convergence
        // rotation, independent of the parser.
        vectors["printedGridCrossings"]!!.jsonArray.map { it.jsonObject }.forEach { crossing ->
            val expected = requireNotNull(MgrsFormatter.parse(crossing.str("mgrs")))
            val placed = transform.at(crossing)
            val miss = metresBetween(placed.latitude, placed.longitude, expected.first, expected.second)
            assertTrue(
                "${crossing.str("name")} is ${miss}m off ${crossing.str("mgrs")}",
                miss < vectors.d("gridToleranceMetres"),
            )
        }
    }

    @Test
    fun fullUsgsSampleMatchesTrimmedFixtureWhenFetched() {
        // samples/*.pdf is gitignored (38 MB), scripts/fetch_samples.sh pulls it.
        val sample = ancestors().map { File(it, "samples/USGS_SF_North.pdf") }.firstOrNull { it.isFile }
        assumeTrue("samples/USGS_SF_North.pdf not fetched", sample != null)

        assertEquals(parseOrFail(fixture), parseOrFail(sample!!))
    }

    @Test
    fun lptsWrittenInUserSpaceUnitsStillFailClosed() {
        val pdf = pdfWithViewports(
            viewport(600.0, 400.0, lpts = doubleArrayOf(0.0, 0.0, 600.0, 0.0, 600.0, 400.0, 0.0, 400.0)),
        )
        assertNull(GeoPdfParser.parseFile(pdf))
    }

    @Test
    fun largerMalformedViewportStillBlocksAValidInset() {
        val pdf = pdfWithViewports(
            viewport(50.0, 50.0, lpts = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)),
            viewport(600.0, 400.0, lpts = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 2.5)),
        )
        assertNull(GeoPdfParser.parseFile(pdf))
    }

    @Test
    fun smallLptsOvershootOnAGeneratedSheetIsAccepted() {
        val pdf = pdfWithViewports(
            viewport(600.0, 400.0, lpts = doubleArrayOf(-0.01, 0.0, 1.0, -0.01, 1.01, 1.0, 0.0, 1.01)),
        )
        val parsed = parseOrFail(pdf)
        assertEquals(-6.0, parsed.correspondences[0].pdfX, 1e-4)
        assertEquals(404.0, parsed.correspondences[3].pdfY, 1e-4)
    }

    private fun parseOrFail(file: File): GeoPdfResult {
        val result = GeoPdfParser.parseFile(file)
        assertNotNull("no georeference parsed from ${file.name}", result)
        return result!!
    }

    private fun pdfWithViewports(vararg viewports: COSDictionary): File {
        val file = tmp.newFile()
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 400f))
            page.cosObject.setItem(COSName.getPDFName("VP"), COSArray().apply { viewports.forEach(::add) })
            doc.addPage(page)
            doc.save(file)
        }
        return file
    }

    private fun viewport(width: Double, height: Double, lpts: DoubleArray): COSDictionary {
        val measure = COSDictionary().apply {
            setName(COSName.getPDFName("Subtype"), "GEO")
            setItem(COSName.getPDFName("GPTS"), numbers(-34.0, 150.0, -34.0, 151.0, -33.0, 151.0, -33.0, 150.0))
            setItem(COSName.getPDFName("LPTS"), numbers(*lpts))
        }
        return COSDictionary().apply {
            setItem(COSName.getPDFName("BBox"), numbers(0.0, 0.0, width, height))
            setItem(COSName.getPDFName("Measure"), measure)
        }
    }

    private fun numbers(vararg values: Double) = COSArray().apply {
        values.forEach { add(COSFloat(it.toFloat())) }
    }

    private fun AffineTransform2D.at(point: JsonObject): Wgs84Coordinate =
        apply(point.d("pdfX"), point.d("pdfY"))

    private fun metresBetween(a: Wgs84Coordinate, b: Wgs84Coordinate): Double =
        metresBetween(a.latitude, a.longitude, b.latitude, b.longitude)

    private fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * 6_371_008.8 * asin(sqrt(h))
    }

    private fun ancestors(): Sequence<File> =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }

    private fun testdataFile(name: String): File =
        ancestors().take(8).map { File(it, "testdata/$name") }.firstOrNull { it.isFile }
            ?: error("Could not locate testdata/$name from ${System.getProperty("user.dir")}")

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject
    private fun JsonObject.d(key: String): Double = this[key]!!.jsonPrimitive.double
    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.range(key: String): Pair<Double, Double> =
        this[key]!!.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double }
}
