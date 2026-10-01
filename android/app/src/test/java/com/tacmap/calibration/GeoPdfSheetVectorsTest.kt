package com.tacmap.calibration

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.d
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * testdata/pdf_georef.json sheets[]: the values each fixture PDF carries (the
 * 'written' dicts, i.e. what a reader pulls off the page) go through the pure
 * construction and must land on the expected georef + every check row. The real
 * PDFs get parsed with PDFBox on device in GeoPdfFixtureInstrumentedTest, which
 * asserts the same expectations; here we also make sure the files on disk are
 * the ones the fixture describes.
 */
class GeoPdfSheetVectorsTest {
    private val sheets = PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }

    private fun written(sheet: JsonObject): JsonObject? = when {
        sheet["written"] != null -> sheet.obj("written")
        // the usgs stand-in keeps its dicts at the top level
        sheet["viewports"] != null -> sheet
        else -> null
    }

    @Test
    fun everySheetBuildsTheExpectedGeoreference() {
        assertEquals(20, sheets.size)
        var georeferenced = 0
        for (sheet in sheets) {
            val id = sheet.str("id")
            val page = PdfGeorefFixture.pageData(sheet, written(sheet))
            val result = GeoPdfGeoreferencer.build(page)
            val expected = sheet.obj("expected")
            if (expected.str("origin") == "none") {
                assertEquals("$id is plain", GeoPdfGeorefResult.NoGeoreference, result)
                continue
            }
            PdfGeorefFixture.assertGeoref(id, expected, result)
            georeferenced++
        }
        assertEquals(17, georeferenced)
    }

    @Test
    fun fixturePdfsOnDiskAreTheOnesTheFixtureDescribes() {
        for (sheet in sheets) {
            val bytes = PdfGeorefFixture.readBytes(sheet.str("file"))
            assertEquals(sheet.str("id"), sheet.d("bytes").toInt(), bytes.size)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(sheet.str("id"), sheet.str("sha256"), sha)
        }
    }

    @Test
    fun usgsStandInMatchesTheRealSheetsPrintedGrid() {
        val sheet = sheets.first { it.str("id") == "usgs_sf_north" }
        val result = GeoPdfGeoreferencer.build(PdfGeorefFixture.pageData(sheet, sheet))
        val g = (result as GeoPdfGeorefResult.Georeferenced).georef
        val printed = sheet.obj("printedGridMeasured")
        val limit = printed.d("toleranceMetres")
        val modelTol = sheet.obj("expected").obj("tolerances").d("printedGridMetres")
        val points = printed.arr("points").map { it.jsonObject }
        assertTrue(points.size >= 10)
        for (p in points) {
            val grid = PdfGeorefFixture.doubles(p["gridNAD83"]!!)
            // grid (NAD83 UTM 10N) -> page through the model
            val page = requireNotNull(g.affine.invert(grid[0], grid[1]))
            val model = PdfGeorefFixture.point(p["pageModel"]!!)
            // page tolerance from the georef's own scale, not a hardcoded 1:24k number
            val metresPerPt = kotlin.math.sqrt(kotlin.math.abs(g.affine.a * g.affine.e - g.affine.b * g.affine.d))
            assertEquals("$grid model.x", model.x, page.x, modelTol / metresPerPt)
            assertEquals("$grid model.y", model.y, page.y, modelTol / metresPerPt)
            // and the real printed line sits within ~1 m of it
            val measured = PdfGeorefFixture.point(p["pageMeasured"]!!)
            val plane = g.planeOf(measured.x, measured.y)
            val err = kotlin.math.hypot(plane.x - grid[0], plane.y - grid[1])
            assertTrue("$grid printed grid off by $err m", err <= limit)
        }
    }

    @Test
    fun plainSheetsCarryGridTruthForCalibration() {
        // the plain sheets have no georef but their gridChecks let a calibration test
        // prove a 4 point fiducial fit lands on the printed grid
        for (sheet in sheets.filter { it.obj("expected").str("origin") == "none" }) {
            val id = sheet.str("id")
            val truth = sheet.obj("truth")
            val targets = truth.arr("fiducialTargets").map { it.jsonObject }
            val datum = requireNotNull(GeoDatums.byId(truth.str("datum")))
            val points = targets.map {
                FiduciaryPoint(
                    PdfGeorefFixture.point(it["page"]!!),
                    requireNotNull(FiduciaryReferenceParser.parse(it.str("label"), datum)) { "$id ${it.str("label")}" },
                )
            }
            val media = PdfGeorefFixture.doubles(sheet["mediaBox"]!!)
            val fit = requireNotNull(FiduciaryFitter.fit(points, datum, media.toDoubleArray()))
            val crop = listOf(PagePoint(media[0], media[1]), PagePoint(media[2], media[1]), PagePoint(media[2], media[3]), PagePoint(media[0], media[3]))
            val g = requireNotNull(fit.georeference(crop))
            val checks = (sheet["gridChecks"] ?: truth["gridChecks"])?.takeIf { it !is JsonNull }?.jsonArray
                ?: continue
            for (c in checks.map { it.jsonObject }) {
                val page = PdfGeorefFixture.point(c["page"]!!)
                val w = PdfGeorefFixture.doubles(c["wgs84"]!!)
                val got = requireNotNull(g.toWGS84(page.x, page.y))
                val err = PdfGeorefFixture.metres(w[0], w[1], got.latitude, got.longitude)
                assertTrue("$id ${c.str("label")} off by $err m", err <= 0.01)
            }
        }
    }
}
