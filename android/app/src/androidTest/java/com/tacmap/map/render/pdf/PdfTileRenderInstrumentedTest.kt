package com.tacmap.map.render.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.calibration.GeoPdfGeorefResult
import com.tacmap.calibration.PdfDocumentInspector
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.TileSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * testdata/pdf_tile_render.json markers / blank / ringTargets, rendered for real
 * through pdfium on the device (the JVM test covers the geometry). Every path: base
 * raster sample, direct vector and staged vector, at tilePx 768 and 512.
 */
@RunWith(AndroidJUnit4::class)
class PdfTileRenderInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val fx get() = PdfTileRenderFixture.root
    /** D8: the fixture's tolerance, not a literal */
    private val markerTol get() = fx.obj("tolerances")["markerCentroidPx"]!!.jsonPrimitive.content.toDouble()

    @Before
    fun setUp() {
        PdfGeorefFixture.readBytes = { name -> assets.open(name).use { it.readBytes() } }
        PDFBoxResourceLoader.init(context)
        com.tacmap.map.render.pdf.PdfRenderSessions.init(context)
        // an earlier activity test's onStop leaves the pdf thread in background mode, where
        // only bake work runs. these render like a visible map would
        PdfRenderExecutor.foreground = true
    }

    private class Sheet(val file: File, val georef: PdfGeoreference, val geometry: PdfPageGeometry) {
        val footprint = PdfFootprint.build(georef, geometry.visibleBox)
        val policy = PdfZoomPolicy.of(georef, footprint)
    }

    private fun sheet(asset: String): Sheet {
        val out = File(context.cacheDir, "wp2-" + asset.substringAfterLast('/'))
        out.writeBytes(PdfGeorefFixture.readBytes(asset))
        val page = PdfDocumentInspector.inspect(context, out)
        val g = (page.georeference() as GeoPdfGeorefResult.Georeferenced).georef
        return Sheet(out, g, page.geometry)
    }

    private enum class Path { RASTER, DIRECT, STAGED }

    /** one tile at [z] through [path], on the pdfium thread like the app does */
    private fun render(s: Sheet, t: TileIndex, tilePx: Int, path: Path): Bitmap = runBlocking {
        val job = TileJob.single(t.z, t.x, t.y)
        val plan = PdfTileWarp.plan(job, tilePx, s.footprint, s.georef)
        val session = PdfRenderSessions.acquire(s.file, s.georef.page)
        try {
            val out = when (path) {
                Path.RASTER -> {
                    val base = session.baseRaster(
                        s.policy.baseRasterPlan(s.footprint, PdfZoomPolicy.BUDGET_PX), s.geometry, s.footprint.clip, PdfRenderExecutor.Band.VISIBLE,
                    ).await()
                    PdfTileRenderer.renderJob(job, tilePx, plan, s.footprint, PdfRenderSourceKind.Raster(base))
                }
                else -> PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE) {
                    session.withPage { page ->
                        val frame = session.frame(s.geometry, page)
                        PdfTileRenderer.renderJob(job, tilePx, plan, s.footprint,
                            PdfRenderSourceKind.Vector(page, frame, staged = path == Path.STAGED))
                    }
                }
            }
            out.getValue(t)
        } finally {
            PdfRenderSessions.release(session)
        }
    }

    /** Real production automatic chooser: compare its bitmap against both native paths. */
    @Test fun automaticFourCellCanberraPreservesDirectVectorPixels() = runBlocking {
        val s = sheet("geopdf/tacmap_grid_cbr50k_iso.pdf")
        val job = TileJob.single(15, 29954, 19823)
        val tile = TileIndex(15, 29954, 19823)
        val output = File(context.filesDir, "task4/path-diagnostic").apply { mkdirs() }
        var allDirect = true
        val rows = kotlinx.serialization.json.buildJsonArray {
            for (tilePx in listOf(672, 768)) {
                val plan = PdfTileWarp.plan(job, tilePx, s.footprint, s.georef)
                assertEquals("The proven actual job stays four cells", 4, plan.cells.size)
                val session = PdfRenderSessions.acquire(s.file, s.georef.page)
                val automatic = PdfTileSource(session, s.georef, s.geometry, s.footprint, s.policy,
                    tilePx, PdfZoomPolicy.BUDGET_PX, null, "automatic-four-cell-$tilePx", forBake = true)
                try {
                    assertTrue("Production bake/live route must be vector", job.z > automatic.baseMaxZoom)
                    val timed = automatic.renderForBakeTimed(job)
                    val auto = timed.tiles.getValue(tile)
                    val directStart = android.os.SystemClock.elapsedRealtimeNanos()
                    val direct = render(s, tile, tilePx, Path.DIRECT)
                    val directMs = (android.os.SystemClock.elapsedRealtimeNanos() - directStart) / 1e6
                    val stagedStart = android.os.SystemClock.elapsedRealtimeNanos()
                    val staged = render(s, tile, tilePx, Path.STAGED)
                    val stagedMs = (android.os.SystemClock.elapsedRealtimeNanos() - stagedStart) / 1e6
                    try {
                        for ((label, bmp) in listOf("automatic" to auto, "direct" to direct, "staged" to staged)) {
                            File(output, "canberra-$tilePx-$label.png").outputStream().use {
                                assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                        var referenceError: Double? = null
                        if (tilePx == 768) {
                            val reference = fx.arr("rasterSamplingReference").single().jsonObject
                            val expected = PdfGeorefFixture.doubles(reference["expectedPx"]!!)[0]
                            val scanline = reference["scanlinePixelIndex"]!!.jsonPrimitive.int
                            val threshold = reference["contrastThreshold"]!!.jsonPrimitive.content.toDouble()
                            val padding = reference["centroidPaddingPx"]!!.jsonPrimitive.int
                            fun contrast(x: Int): Double {
                                val color = auto.getPixel(x, scanline)
                                return ((Color.red(color) - maxOf(Color.green(color), Color.blue(color))) / 255.0).coerceAtLeast(0.0)
                            }
                            var left = expected.toInt(); var right = left
                            assertTrue("Independent reference must hit the connected printed band", contrast(left) > threshold)
                            while (left > 0 && contrast(left - 1) > threshold) left--
                            while (right < auto.width - 1 && contrast(right + 1) > threshold) right++
                            left = (left - padding).coerceAtLeast(0); right = (right + padding).coerceAtMost(auto.width - 1)
                            var total = 0.0; var moment = 0.0
                            val offset = reference["pixelCenterOffset"]!!.jsonPrimitive.content.toDouble()
                            for (x in left..right) { val weight = contrast(x); total += weight; moment += (x + offset) * weight }
                            referenceError = abs(moment / total - expected) * reference["physicalScale"]!!.jsonPrimitive.content.toDouble()
                            assertTrue("Automatic native bitmap source-only physical residual $referenceError", referenceError.isFinite() &&
                                referenceError <= reference["maxPhysicalError"]!!.jsonPrimitive.content.toDouble())
                        }
                        val sameDirect = auto.sameAs(direct)
                        val sameStaged = auto.sameAs(staged)
                        assertTrue("The diagnostic must distinguish actual raster paths", !direct.sameAs(staged))
                        allDirect = allDirect && sameDirect
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("tilePx", kotlinx.serialization.json.JsonPrimitive(tilePx))
                            put("cells", kotlinx.serialization.json.JsonPrimitive(plan.cells.size))
                            put("automaticSameDirect", kotlinx.serialization.json.JsonPrimitive(sameDirect))
                            put("automaticSameStaged", kotlinx.serialization.json.JsonPrimitive(sameStaged))
                            referenceError?.let { put("independentSourcePhysicalError", kotlinx.serialization.json.JsonPrimitive(it)) }
                            put("automaticDrawMs", kotlinx.serialization.json.JsonPrimitive(timed.drawMs))
                            put("directAcquireAndRenderMs", kotlinx.serialization.json.JsonPrimitive(directMs))
                            put("stagedAcquireAndRenderMs", kotlinx.serialization.json.JsonPrimitive(stagedMs))
                        })
                    } finally { auto.recycle(); direct.recycle(); staged.recycle() }
                } finally { automatic.dispose(); PdfRenderSessions.release(session) }
            }
        }
        File(output, "chooser-result.json").writeText(rows.toString())
        assertTrue("Actual automatic four-cell bitmap must retain native direct-vector pixels", allDirect)
    }

    /** centroid of pixels matching [hit] in a (2r+1)² window around (cx, cy), null if none */
    private fun centroid(b: Bitmap, cx: Double, cy: Double, r: Int, hit: (Int) -> Double): Pair<Double, Double>? {
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        val x0 = (cx - r).roundToInt().coerceAtLeast(0); val x1 = (cx + r).roundToInt().coerceAtMost(b.width - 1)
        val y0 = (cy - r).roundToInt().coerceAtLeast(0); val y1 = (cy + r).roundToInt().coerceAtMost(b.height - 1)
        for (y in y0..y1) for (x in x0..x1) {
            val w = hit(b.getPixel(x, y))
            if (w > 0) { sw += w; sx += w * (x + 0.5); sy += w * (y + 0.5) }
        }
        return if (sw > 0) (sx / sw) to (sy / sw) else null
    }

    // red ink weight: how far the pixel is from paper towards pure red
    private fun redness(c: Int): Double {
        if (Color.alpha(c) < 128) return 0.0
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return if (r > 150 && g < 200 && b < 200 && r - maxOf(g, b) > 40) (255.0 - maxOf(g, b)) / 255.0 else 0.0
    }

    private fun darkness(c: Int): Double {
        if (Color.alpha(c) < 128) return 0.0
        val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0
        return if (l < 160) (255 - l) / 255.0 else 0.0
    }

    private fun pathsFor(s: Sheet, z: Int, tilePx: Int): List<Path> {
        val bmz = s.policy.baseMaxZoom(s.policy.baseRasterPlan(s.footprint, PdfZoomPolicy.BUDGET_PX), tilePx)
        return if (z <= bmz) listOf(Path.RASTER, Path.DIRECT, Path.STAGED) else listOf(Path.DIRECT, Path.STAGED)
    }

    @Test
    fun markersLandOnTheirPixelsOnEveryPath() {
        val m = fx.obj("markers")
        val s = sheet(m.str("file"))
        var checked = 0
        for (mk in m.arr("markers").map { it.jsonObject }) {
            for (e in mk.arr("expected").map { it.jsonObject }) {
                val (t, px, tp) = target(e)
                // a marker hanging over the tile edge only has half of itself in this tile
                val half = 2 * s.policy.pxPerPoint(t.z, tp) + 2
                if (px[0] < half || px[1] < half || px[0] > tp - half || px[1] > tp - half) continue
                for (path in pathsFor(s, t.z, tp)) {
                    val bmp = render(s, t, tp, path)
                    try {
                        val pxPerPt = s.policy.pxPerPoint(t.z, tp)
                        val r = (4 * pxPerPt).toInt() + 4
                        val c = centroid(bmp, px[0], px[1], r, ::redness)
                            ?: throw AssertionError("${mk.str("label")} z${t.z}@$tp $path: no red marker near ${px.toList()}")
                        assertEquals("${mk.str("label")} z${t.z}@$tp $path u", px[0], c.first, markerTol)
                        assertEquals("${mk.str("label")} z${t.z}@$tp $path v", px[1], c.second, markerTol)
                        checked++
                    } finally {
                        if (bmp !== TileSource.EMPTY) bmp.recycle()
                    }
                }
            }
        }
        assertTrue(checked > 40)
    }

    @Test
    fun clippedMarkerHiddenOcgAndAlphaSamples() {
        val m = fx.obj("markers")
        val s = sheet(m.str("file"))
        // outside the CropBox but inside the neatline: never drawn
        for (e in m.obj("clippedMarker").arr("expected").map { it.jsonObject }) {
            val (t, px, tp) = target(e)
            for (path in pathsFor(s, t.z, tp)) {
                val bmp = render(s, t, tp, path)
                try {
                    val r = (2 * s.policy.pxPerPoint(t.z, tp)).toInt().coerceAtLeast(1)
                    assertEquals("clipped z${t.z}@$tp $path", null, centroid(bmp, px[0], px[1], r, ::redness))
                } finally {
                    if (bmp !== TileSource.EMPTY) bmp.recycle()
                }
            }
        }
        // the blue square sits in an OFF optional content group: paper white
        for (e in m.obj("hiddenOcgSquare").arr("expected").map { it.jsonObject }) {
            val (t, px, tp) = target(e)
            for (path in pathsFor(s, t.z, tp)) {
                val bmp = render(s, t, tp, path)
                try {
                    val c = bmp.getPixel(floor(px[0]).toInt(), floor(px[1]).toInt())
                    assertTrue("ocg z${t.z}@$tp $path ${Integer.toHexString(c)}",
                        Color.blue(c) - maxOf(Color.red(c), Color.green(c)) < 30 && Color.alpha(c) == 255)
                } finally {
                    if (bmp !== TileSource.EMPTY) bmp.recycle()
                }
            }
        }
        // alpha: 255 on the sheet, 0 off it (every sample is >= 2 px from the edge)
        for (a in m.arr("alphaSamples").map { it.jsonObject }) {
            val (t, px, tp) = target(a)
            for (path in pathsFor(s, t.z, tp)) {
                val bmp = render(s, t, tp, path)
                try {
                    val alpha = if (bmp === TileSource.EMPTY) 0 else Color.alpha(bmp.getPixel(floor(px[0]).toInt(), floor(px[1]).toInt()))
                    assertEquals("alpha ${a.str("label")} z${t.z}@$tp $path", a["alpha"]!!.jsonPrimitive.int, alpha)
                } finally {
                    if (bmp !== TileSource.EMPTY) bmp.recycle()
                }
            }
        }
    }

    @Test
    fun ringTargetsLandOnTheirPixels() {
        val files = PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associate { it.str("id") to it.str("file") }
        val cache = HashMap<String, Sheet>()
        for (r in fx.arr("ringTargets").map { it.jsonObject }) {
            val s = cache.getOrPut(r.str("sheet")) { sheet(files.getValue(r.str("sheet"))) }
            for (e in r.arr("expected").map { it.jsonObject }) {
                val (t, px, tp) = target(e)
                val path = pathsFor(s, t.z, tp).last { it != Path.STAGED }
                val bmp = render(s, t, tp, path)
                try {
                    val rad = (6 * s.policy.pxPerPoint(t.z, tp)).toInt()
                    // a 6 pt window can poke past the tile edge near a seam, skip those
                    if (px[0] - rad < 0 || px[1] - rad < 0 || px[0] + rad >= tp || px[1] + rad >= tp) continue
                    val c = centroid(bmp, px[0], px[1], rad, ::darkness) ?: throw AssertionError("ring ${r.str("id")}: nothing dark")
                    assertEquals("ring ${r.str("sheet")} ${r.str("id")} z${t.z}@$tp u", px[0], c.first, markerTol)
                    assertEquals("ring ${r.str("sheet")} ${r.str("id")} z${t.z}@$tp v", px[1], c.second, markerTol)
                } finally {
                    if (bmp !== TileSource.EMPTY) bmp.recycle()
                }
            }
        }
    }

    @Test
    fun blankPageFailsWithBlank() = runBlocking {
        val b = fx.obj("blank")
        val s = sheet(b.str("file"))
        val session = PdfRenderSessions.acquire(s.file, 0)
        try {
            val err = runCatching {
                session.baseRaster(s.policy.baseRasterPlan(s.footprint, PdfZoomPolicy.BUDGET_PX), s.geometry, s.footprint.clip, PdfRenderExecutor.Band.VISIBLE).await()
            }.exceptionOrNull()
            assertEquals(PdfRenderFailure.BLANK, (err as? PdfRenderException)?.failure)
        } finally {
            PdfRenderSessions.release(session)
        }
    }

    @Test
    fun blankCornerSheetStillFailsWithBlank() = runBlocking {
        // G3 + r1: black marks in the BBox corners but outside the /Bounds quad never count
        val b = fx.arr("stagedRegion").map { it.jsonObject }.single { it.str("kind") == "blankCheck" }
        val s = sheet(b.str("file"))
        val expected = PdfTileRenderFixture.sheetById("render_blank_corner")
        assertEquals(expected.georef.crop.size, s.georef.crop.size)
        val plan = s.policy.baseRasterPlan(s.footprint, PdfZoomPolicy.BUDGET_PX)
        val e = b.obj("basePlan")
        assertEquals(e["W"]!!.jsonPrimitive.int, plan.width)
        assertEquals(e["H"]!!.jsonPrimitive.int, plan.height)
        val session = PdfRenderSessions.acquire(s.file, 0)
        try {
            val err = runCatching {
                session.baseRaster(plan, s.geometry, s.footprint.clip, PdfRenderExecutor.Band.VISIBLE).await()
            }.exceptionOrNull()
            assertEquals(b.obj("expected").str("importFailure"), (err as? PdfRenderException)?.failure?.code)
            // and the marks really are on the page: unclipped, the same raster isn't blank
            val inked = PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE) {
                session.withPage { page ->
                    val bmp = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
                    val m = android.graphics.Matrix()
                    m.setValues(session.frame(s.geometry, page).renderMatrix(plan.pageToMip(0)).toFloatArray9())
                    page.render(bmp, null, m, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    PdfTileRenderer.isBlank(bmp).also { bmp.recycle() }
                }
            }
            assertEquals("the corner marks draw when nothing clips them", false, inked)
        } finally {
            PdfRenderSessions.release(session)
        }
    }

    @Test
    fun probeAndSeamsOnAHeavyBlock() {
        // a 3x2 staged job and the same six tiles one by one have to agree at the seams
        val s = sheet("geopdf/tacmap_grid_rot5_iso.pdf")
        val c = s.georef.toWGS84(s.footprint.clipMean.x, s.footprint.clipMean.y)!!
        val z = 15
        val n = Math.scalb(1.0, z)
        val tx = floor(MercatorWorld.x(c.longitude) * n / 256).toInt()
        val ty = floor(MercatorWorld.y(c.latitude) * n / 256).toInt()
        val job = TileJob(z, tx - 1, ty, 3, 2)
        val block = runBlocking {
            val session = PdfRenderSessions.acquire(s.file, 0)
            try {
                PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE) {
                    session.withPage { page ->
                        val plan = PdfTileWarp.plan(job, 512, s.footprint, s.georef)
                        PdfTileRenderer.renderJob(job, 512, plan, s.footprint,
                            PdfRenderSourceKind.Vector(page, session.frame(s.geometry, page), staged = true))
                    }
                }
            } finally {
                PdfRenderSessions.release(session)
            }
        }
        var worst = 0
        for ((t, bmp) in block) {
            val single = render(s, t, 512, Path.DIRECT)
            // same pixels give or take antialiasing: compare ink coverage per row band
            var diff = 0L
            for (y in 0 until 512 step 8) for (x in 0 until 512 step 8) {
                val a = darkness(bmp.getPixel(x, y)) + redness(bmp.getPixel(x, y))
                val d = darkness(single.getPixel(x, y)) + redness(single.getPixel(x, y))
                if (abs(a - d) > 0.6) diff++
            }
            worst = maxOf(worst, diff.toInt())
            single.recycle(); bmp.recycle()
        }
        assertTrue("staged vs direct disagree at $worst sample points", worst < 40)
        assertTrue(PdfDisplaySizeProbe.lastMode() != null)
    }

    private fun target(e: JsonObject): Triple<TileIndex, DoubleArray, Int> {
        val tile = PdfGeorefFixture.doubles(e["tile"]!!)
        val px = PdfGeorefFixture.doubles(e["px"]!!).toDoubleArray()
        return Triple(TileIndex(e["z"]!!.jsonPrimitive.int, tile[0].toInt(), tile[1].toInt()), px, e["tilePx"]!!.jsonPrimitive.int)
    }
}
