package com.tacmap.calibration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pins how PdfRenderer (pdfium) really maps raw PDF user space, the thing
 * PdfPageGeometry encodes: CropBox-intersect-MediaBox origin, /Rotate clockwise,
 * int truncated page size. Every check renders a window of RAW user space through
 * PdfPageRenderer and finds a red grid intersection the fixture says is at a known
 * raw point. Wrong origin = 20-150 pt off, wrong rotation = way off, float vs int
 * size = up to 0.8 pt off; we demand under 1/8 pt.
 */
@RunWith(AndroidJUnit4::class)
class PdfRendererConventionInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val sheets by lazy { PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") } }

    @Before
    fun setUp() {
        PdfGeorefFixture.readBytes = { name -> assets.open(name).use { it.readBytes() } }
        PDFBoxResourceLoader.init(context)
    }

    private fun copy(id: String, rotate: Int? = null): File {
        val sheet = sheets.getValue(id)
        val out = File(context.cacheDir, "convention-$id-${rotate ?: "as-is"}.pdf")
        out.writeBytes(PdfGeorefFixture.readBytes(sheet.str("file")))
        if (rotate != null) {
            // same sheet, page turned by PDFBox; raw user space and the georef don't move
            PDDocument.load(out).use { doc ->
                doc.getPage(0).rotation = rotate
                doc.save(out)
            }
        }
        return out
    }

    /** interior printed grid intersections, raw page space, away from the frame labels */
    private fun gridPoints(id: String, take: Int = 4): List<PagePoint> {
        val e = sheets.getValue(id).obj("expected")
        if (id == "usgs_sf_north") {
            // the stand-in draws its red 1000 m grid straight from the model affine
            val affine = PlaneAffine.of(PdfGeorefFixture.doubles(e["affine"]!!))!!
            return listOf(546_000.0 to 4_180_000.0, 553_000.0 to 4_188_000.0, 549_000.0 to 4_184_000.0, 555_000.0 to 4_181_000.0)
                .map { (east, north) -> affine.invert(east, north)!! }
                .take(take)
        }
        val crop = e.arr("crop").map(PdfGeorefFixture::point)
        val xs = crop.map { it.x }
        val ys = crop.map { it.y }
        return e.arr("checks").map { it.jsonObject }
            .filter { it.str("kind") == "grid" }
            .map { PdfGeorefFixture.point(it["page"]!!) }
            .filter { p -> p.x > xs.min() + 40 && p.x < xs.max() - 40 && p.y > ys.min() + 40 && p.y < ys.max() - 40 }
            .let { pts -> listOf(pts.first(), pts.last(), pts[pts.size / 2], pts[pts.size / 3]).take(take) }
    }

    private fun assertRawWindows(label: String, file: File, points: List<PagePoint>) {
        val geometry = PdfDocumentInspector.inspect(context, file).geometry
        for (p in points) {
            // 32 x 32 pt of raw space at 8 px/pt, the intersection should sit dead centre
            val bmp = PdfPageRenderer.renderRawRegion(
                context, Uri.fromFile(file), geometry,
                p.x - 16.0, p.y - 16.0, p.x + 16.0, p.y + 16.0, 256, 256,
            )
            try {
                val (cx, cy) = redCross(bmp)
                assertEquals("$label $p x px", 128.0, cx, 1.0)
                assertEquals("$label $p y px", 128.0, cy, 1.0)
            } finally {
                bmp.recycle()
            }
        }
    }

    @Test
    fun cropOriginFractionalSizeAndRotateAllLandOnTheGrid() {
        // sf: fractional A-ish size, offset: MediaBox origin (100,150) + CropBox inset,
        // rot90: /Rotate 90 as written, cbr50k: 1277.858 truncates (rounding would be 1278)
        for (id in listOf("sf_iso", "offset_iso", "rot90_iso", "cbr50k_iso", "usgs_sf_north")) {
            assertRawWindows(id, copy(id), gridPoints(id))
        }
        // and every other quarter turn of the same page
        for (rotate in listOf(90, 180, 270)) {
            assertRawWindows("sf_iso /Rotate $rotate", copy("sf_iso", rotate), gridPoints("sf_iso"))
        }
    }

    @Test
    fun displaySizeProbeMatchesThePinnedConvention() {
        // WP2 replaced the whole page overlay with tiles drawn through PdfPageFrame. The frame
        // picks FLOAT/TRUNCATED from a runtime probe; assertRawWindows above already renders
        // through it, here we just make sure the probe agrees with WP1's pinned int sizing
        val file = copy("offset_iso")
        val geometry = PdfDocumentInspector.inspect(context, file).geometry
        val mode = com.tacmap.map.render.pdf.PdfDisplaySizeProbe.mode(context.cacheDir)
        val frame = com.tacmap.map.render.pdf.PdfPageFrame(geometry, geometry.rendererWidth, geometry.rendererHeight, mode)
        for (p in gridPoints("offset_iso", 2)) {
            val (u, v) = geometry.rawToRenderer(p.x, p.y)
            // TRUNCATED is the convention WP1 measured; FLOAT may only differ by the int size slack
            val tol = if (mode == com.tacmap.map.render.pdf.DisplaySizeMode.TRUNCATED) 1e-6 else 1.0
            assertEquals("probe $mode u", u, frame.userToRenderer.mapX(p.x, p.y), tol)
            assertEquals("probe $mode v", v, frame.userToRenderer.mapY(p.x, p.y), tol)
        }
    }

    // column/row centroid of the red ink after dropping the flat baseline the
    // perpendicular line leaves in every column/row
    private fun redCross(bmp: Bitmap): Pair<Double, Double> {
        val w = bmp.width
        val h = bmp.height
        val cols = DoubleArray(w)
        val rows = DoubleArray(h)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = px[y * w + x]
            val red = ((Color.red(c) - (Color.green(c) + Color.blue(c)) / 2).coerceAtLeast(0)) / 255.0
            if (red > 0.1) {
                cols[x] += red
                rows[y] += red
            }
        }
        assertTrue("no red grid in the window", cols.sum() > 0.0)
        return peak(cols) to peak(rows)
    }

    private fun peak(v: DoubleArray): Double {
        val base = v.sorted()[v.size / 2]
        val max = v.max()
        var sw = 0.0
        var s = 0.0
        for (i in v.indices) {
            val a = v[i] - base
            if (a > 0.3 * (max - base)) {
                sw += a
                s += a * (i + 0.5)
            }
        }
        return s / sw
    }
}
