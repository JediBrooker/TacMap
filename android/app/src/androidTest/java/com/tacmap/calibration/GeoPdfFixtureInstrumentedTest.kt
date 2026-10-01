package com.tacmap.calibration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tacmap.calibration.PdfGeorefFixture.strOrNull
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real testdata/geopdf sheets through PDFBox on device -> the pure georef
 * construction -> every expectation in testdata/pdf_georef.json (the JVM suite
 * does the same from the fixture's 'written' numbers). Proves the reader keeps
 * PDF reals as doubles, decodes UTF-16 names and backslash-CR WKT literals, and
 * that the rejection cases fail the same way when they come out of a real file.
 */
@RunWith(AndroidJUnit4::class)
class GeoPdfFixtureInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @Before
    fun setUp() {
        PdfGeorefFixture.readBytes = { name -> assets.open(name).use { it.readBytes() } }
        PDFBoxResourceLoader.init(context)
    }

    private fun copy(name: String): File {
        val out = File(context.cacheDir, "fixture-" + name.substringAfterLast('/'))
        out.writeBytes(PdfGeorefFixture.readBytes(name))
        return out
    }

    private fun sheets(): List<JsonObject> = PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }

    @Test
    fun everyFixturePdfParsesToTheSharedGeoreference() {
        var georeferenced = 0
        for (sheet in sheets()) {
            val id = sheet.str("id")
            val page = PdfDocumentInspector.inspect(context, copy(sheet.str("file")))
            assertFalse("$id frames", page.framesDisagree)
            val meta = requireNotNull(page.metadata) { "$id PDFBox read" }
            // the files write boxes to 3 dp, the fixture lists them unrounded
            assertBox("$id mediaBox", PdfGeorefFixture.doubles(sheet["mediaBox"]!!), meta.mediaBox)
            val crop = sheet["cropBox"]?.takeIf { it !is JsonNull }?.let(PdfGeorefFixture::doubles)
            if (crop == null) assertEquals("$id cropBox", null, meta.cropBox) else assertBox("$id cropBox", crop, meta.cropBox)
            assertEquals(id, sheet["rotate"]!!.jsonPrimitive.int, meta.rotate)
            assertEquals(id, sheet["rotate"]!!.jsonPrimitive.int, page.geometry.rotation)
            assertReadExactly(id, sheet, meta)

            val result = page.georeference()
            val expected = sheet.obj("expected")
            if (expected.str("origin") == "none") {
                assertEquals("$id is plain", GeoPdfGeorefResult.NoGeoreference, result)
            } else {
                PdfGeorefFixture.assertGeoref(id, expected, result)
                georeferenced++
            }
        }
        assertEquals(17, georeferenced)
    }

    private fun assertBox(label: String, want: List<Double>, got: List<Double>?) {
        requireNotNull(got) { "$label missing" }
        assertEquals(label, 4, got.size)
        want.zip(got).forEach { (w, g) -> assertEquals(label, w, g, 1e-3) }
    }

    // GPTS etc come off the page as the exact doubles the fixture wrote, not float32
    private fun assertReadExactly(id: String, sheet: JsonObject, meta: GeoPdfPageData) {
        val written = sheet["written"]?.jsonObject ?: sheet
        written["viewports"]?.jsonArray?.map { it.jsonObject }?.forEachIndexed { i, vp ->
            val read = meta.viewports[i]
            assertEquals("$id vp$i name", vp.strOrNull("name"), read.name)
            for (key in listOf("bbox", "gpts", "lpts", "bounds")) {
                val want = PdfGeorefFixture.nums(vp[key]) ?: continue
                val got = when (key) {
                    "bbox" -> read.bbox
                    "gpts" -> read.gpts
                    "lpts" -> read.lpts
                    else -> read.bounds
                }
                assertEquals("$id vp$i $key", want, got)
            }
            vp["gcs"]?.jsonObject?.strOrNull("wkt")?.let { assertEquals("$id vp$i wkt", it, read.gcsWkt) }
        }
        written["entries"]?.jsonArray?.map { it.jsonObject }?.forEachIndexed { i, e ->
            val read = meta.lgiEntries[i]
            assertEquals("$id lgi$i description", e.strOrNull("description"), read.description)
            PdfGeorefFixture.nums(e["ctm"])?.let { assertEquals("$id lgi$i ctm", it, read.ctm) }
            PdfGeorefFixture.nums(e["neatline"])?.let { assertEquals("$id lgi$i neatline", it, read.neatline) }
        }
    }

    @Test
    fun rejectionCasesBuiltIntoRealPdfsFailTheSameWay() {
        val cases = PdfGeorefFixture.root.obj("rejections").arr("cases").map { it.jsonObject }
        var checked = 0
        for (c in cases) {
            val id = c.str("id")
            val extras = c.strOrNull("pdfPageExtras") ?: continue
            val file = File(context.cacheDir, "rejection-$id.pdf")
            writeOnePagePdf(file, PdfGeorefFixture.doubles(c["mediaBox"]!!), extras)
            val result = PdfDocumentInspector.inspect(context, file).georeference()
            val expected = c.obj("expected")
            if (expected.strOrNull("outcome") == "reject") {
                assertTrue("$id should reject, got $result", result is GeoPdfGeorefResult.Rejected)
                val rejected = result as GeoPdfGeorefResult.Rejected
                assertEquals(id, expected.str("reason"), rejected.reason.code)
                expected["fit"]?.takeIf { it !is JsonNull }?.let { PdfGeorefFixture.assertFitStats(id, it.jsonObject, rejected.fitStats) }
            } else {
                PdfGeorefFixture.assertGeoref(id, expected, result)
            }
            checked++
        }
        // every case carries its pdf form
        assertEquals(cases.size, checked)
        assertTrue("only $checked cases", checked >= 54)
    }

    private fun sfLayersProjection() =
        "/Projection << /Type /Projection /ProjectionType (UT) /Zone 10 /Hemisphere (N) /Datum (WE) >>"

    @Test
    fun sharedIndirectRegistrationRowsAreRefusedBeforeAnyValueIsRead() {
        // review 2026-10: every row points at one 8192-number array, 64 entries of 4096
        // rows each. Reading them all is GBs of boxed doubles; the row length check
        // has to bail on the first row of each entry
        val big = (0 until 8_192).joinToString(" ", "[", "]") { (it % 997).toString() }
        val rows = (0 until GeoPdfParser.MAX_REGISTRATION_ROWS).joinToString(" ") { "5 0 R" }
        val entry = "<< /Type /LGIDict /Description (Layers) /Registration [$rows] ${sfLayersProjection()} >>"
        val extras = "/LGIDict [" + (0 until 64).joinToString(" ") { entry } + "]"
        val file = File(context.cacheDir, "hostile-registration.pdf")
        writeOnePagePdf(file, listOf(0.0, 0.0, 824.3, 1051.1), extras, extraObjects = listOf(big))
        val started = System.nanoTime()
        val page = PdfDocumentInspector.inspect(context, file)
        val elapsedMs = (System.nanoTime() - started) / 1e6
        val meta = requireNotNull(page.metadata)
        assertEquals(64, meta.lgiEntries.size)
        meta.lgiEntries.forEach { assertEquals("bad row -> malformed marker, nothing read", listOf(null), it.registration) }
        assertEquals(GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED), page.georeference())
        assertTrue("took $elapsedMs ms", elapsedMs < 10_000)
    }

    @Test
    fun sharedIndirectValueArraysRunOutThePageBudget() {
        // the same trick on /VP arrays: 64 GEO viewports whose four arrays are all the
        // same 8192 numbers. The page-wide cap stops it and the page reads as malformed
        val big = (0 until 8_192).joinToString(" ", "[", "]") { "0.5" }
        val vp = "<< /Type /Viewport /BBox 5 0 R /Measure << /Type /Measure /Subtype /GEO /GPTS 5 0 R /LPTS 5 0 R " +
            "/Bounds 5 0 R /GCS << /Type /PROJCS /EPSG 32610 >> >> >>"
        val extras = "/VP [" + (0 until 64).joinToString(" ") { vp } + "]"
        val file = File(context.cacheDir, "hostile-viewports.pdf")
        writeOnePagePdf(file, listOf(0.0, 0.0, 824.3, 1051.1), extras, extraObjects = listOf(big))
        val page = PdfDocumentInspector.inspect(context, file)
        val meta = requireNotNull(page.metadata)
        assertTrue("budget blown -> oversized", meta.viewportsOversized)
        val held = meta.viewports.sumOf { v -> listOf(v.bbox, v.gpts, v.lpts, v.bounds).sumOf { it?.size ?: 0 } }
        assertTrue("held $held numbers", held <= GeoPdfParser.MAX_PAGE_VALUES + 4 * 64)
        assertEquals(GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED), page.georeference())
    }

    private fun rejectionCase(id: String): JsonObject =
        PdfGeorefFixture.root.obj("rejections").arr("cases").map { it.jsonObject }.first { it.str("id") == id }

    @Test
    fun aViewportReadBeforeTheBudgetRanOutStillStands() {
        // lgiRules.pageBudget: the LGIDict blows the page budget, but the /VP was read in
        // full before that, so it's still the georef (iOS ReadBudget does the same)
        val c = rejectionCase("non_geo_viewport_first")
        val big = (0 until 8_192).joinToString(" ", "[", "]") { "1" }
        val hostile = "/LGIDict [" + (0 until 64).joinToString(" ") {
            "<< /Type /LGIDict /Description (Layers) /CTM 5 0 R /Neatline 5 0 R ${sfLayersProjection()} >>"
        } + "]"
        val file = File(context.cacheDir, "budget-after-vp.pdf")
        writeOnePagePdf(file, PdfGeorefFixture.doubles(c["mediaBox"]!!), c.str("pdfPageExtras") + " " + hostile, extraObjects = listOf(big))
        val page = PdfDocumentInspector.inspect(context, file)
        val meta = requireNotNull(page.metadata)
        assertFalse("vp read in full", meta.viewportsOversized)
        assertTrue("lgi ran out", meta.lgiOversized)
        PdfGeorefFixture.assertGeoref("vp before budget", c.obj("expected"), page.georeference())
    }

    @Test
    fun runningOutOnTheViewportsTakesTheLgiDictDownToo() {
        // ...and the other way round: out of budget inside /VP, a perfectly good LGIDict
        // after it isn't read either, the page is malformed
        val c = rejectionCase("lgi_ctm_registration_agree")
        val big = (0 until 8_192).joinToString(" ", "[", "]") { "0.5" }
        val vp = "<< /Type /Viewport /BBox 5 0 R /Measure << /Type /Measure /Subtype /GEO /GPTS 5 0 R /LPTS 5 0 R " +
            "/GCS << /Type /PROJCS /EPSG 32610 >> >> >>"
        val hostile = "/VP [" + (0 until 64).joinToString(" ") { vp } + "]"
        val file = File(context.cacheDir, "budget-in-vp.pdf")
        writeOnePagePdf(file, PdfGeorefFixture.doubles(c["mediaBox"]!!), hostile + " " + c.str("pdfPageExtras"), extraObjects = listOf(big))
        // sanity: the LGIDict alone is fine
        val alone = File(context.cacheDir, "budget-lgi-alone.pdf")
        writeOnePagePdf(alone, PdfGeorefFixture.doubles(c["mediaBox"]!!), c.str("pdfPageExtras"))
        assertTrue(PdfDocumentInspector.inspect(context, alone).georeference() is GeoPdfGeorefResult.Georeferenced)
        val meta = requireNotNull(PdfDocumentInspector.inspect(context, file).metadata)
        assertTrue(meta.viewportsOversized)
        assertTrue(meta.lgiOversized)
        assertEquals(GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED), PdfDocumentInspector.inspect(context, file).georeference())
    }

    @Test
    fun catalogViewportsAreUsedWhenThePageVpHasNoGeoViewport() {
        // iOS reads the catalog /VP when the page one has nothing GEO in it, so do we
        val c = PdfGeorefFixture.root.obj("rejections").arr("cases").map { it.jsonObject }
            .first { it.str("id") == "non_geo_viewport_first" }
        val geoVp = c.str("pdfPageExtras")
        val nonGeoOnly = "/VP [<< /Type /Viewport /Name (Scale bar) /BBox [0 0 100 20] " +
            "/Measure << /Type /Measure /Subtype /RL /R (1 in = 2000 ft) >> >>]"
        val file = File(context.cacheDir, "catalog-vp.pdf")
        writeOnePagePdf(file, PdfGeorefFixture.doubles(c["mediaBox"]!!), nonGeoOnly, catalogExtras = geoVp)
        PdfGeorefFixture.assertGeoref("catalog vp", c.obj("expected"), PdfDocumentInspector.inspect(context, file).georeference())
    }

    /**
     * The real 38 MB USGS sheet isn't in git. Push it to run this:
     *   adb push samples/USGS_SF_North.pdf /sdcard/Android/data/com.tacmap/files/
     */
    @Test
    fun realUsgsSampleMatchesItsPinnedViewportsWhenPresent() {
        val sample = File(context.getExternalFilesDir(null), "USGS_SF_North.pdf")
        assumeTrue("USGS sample not on device, skipping", sample.isFile)
        val sheet = sheets().first { it.str("id") == "usgs_sf_north" }
        val page = PdfDocumentInspector.inspect(context, sample)
        assertFalse(page.framesDisagree)
        PdfGeorefFixture.assertGeoref("USGS_SF_North.pdf", sheet.obj("expected"), page.georeference())
    }

    companion object {
        /** latin-1, one char per byte, same layout as scripts/gen_test_geopdfs.py write_pdf */
        /** [extraObjects] become objects 5, 6, ... so extras can point at them with "5 0 R" */
        fun writeOnePagePdf(
            file: File,
            media: List<Double>,
            extras: String,
            extraObjects: List<String> = emptyList(),
            catalogExtras: String = "",
        ) {
            val content = "q 1 1 1 rg 0 0 10 10 re f Q".toByteArray(Charsets.ISO_8859_1)
            val mediaText = media.joinToString(" ") { v -> if (v == Math.rint(v)) v.toLong().toString() else v.toString() }
            val page = "<< /Type /Page /Parent 2 0 R /MediaBox [$mediaText] /Resources << >> /Contents 4 0 R $extras >>"
            val objects = listOf(
                "<< /Type /Catalog /Pages 2 0 R $catalogExtras >>".toByteArray(Charsets.ISO_8859_1),
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>".toByteArray(Charsets.ISO_8859_1),
                page.toByteArray(Charsets.ISO_8859_1),
                "<< /Length ${content.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + content +
                    "\nendstream".toByteArray(Charsets.ISO_8859_1),
            ) + extraObjects.map { it.toByteArray(Charsets.ISO_8859_1) }
            val out = java.io.ByteArrayOutputStream()
            out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
            val offsets = ArrayList<Int>()
            objects.forEachIndexed { i, o ->
                offsets += out.size()
                out.write("${i + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
                out.write(o)
                out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            }
            val xref = out.size()
            val sb = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
            offsets.forEach { sb.append(String.format(java.util.Locale.US, "%010d 00000 n \n", it)) }
            sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
            out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
            file.writeBytes(out.toByteArray())
        }
    }
}
