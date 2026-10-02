package com.tacmap.map.render.pdf

import com.tacmap.calibration.GeoPdfGeorefResult
import com.tacmap.calibration.GeoPdfGeoreferencer
import com.tacmap.calibration.GeoPdfPageData
import com.tacmap.calibration.GeoPdfViewportData
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeorefFixture
import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.d
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.TileCacheState
import com.tacmap.map.render.TileDrawItem
import com.tacmap.map.render.TileDrawPlanner
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.TileMath
import com.tacmap.map.render.TileMemoryBudget
import com.tacmap.map.render.UnitRect
import com.tacmap.map.render.VisibleTile
import com.tacmap.map.render.TileDrawPlanner.MAX_ANCESTOR_LEVELS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max

/**
 * testdata/pdf_tile_render.json, every section (plans/WP2-render-shared_contract.md O).
 * The pixel level marker / ring / blank renders also run for real on device in
 * PdfTileRenderInstrumentedTest; here the same expectations are checked against
 * the pure geometry those renders are built from.
 */
class PdfTileRenderFixtureTest {
    private val fx = PdfTileRenderFixture.root
    private val tol = fx.obj("tolerances")

    private fun relClose(label: String, expected: Double, actual: Double, rel: Double, absFloor: Double = 0.0) {
        val lim = max(rel * abs(expected), absFloor)
        assertTrue("$label: expected $expected got $actual (lim $lim)", abs(expected - actual) <= lim)
    }

    private fun JsonObject.i(k: String): Int = this[k]!!.jsonPrimitive.int
    private fun JsonObject.b(k: String): Boolean = this[k]!!.jsonPrimitive.boolean
    private fun JsonObject.dbl(k: String): DoubleArray = PdfGeorefFixture.doubles(this[k]!!).toDoubleArray()

    @Test fun automaticPathBoundariesUseSharedContract() {
        for (row in fx.arr("renderPath").map { it.jsonObject }) {
            val above = row["aboveBaseMaxZoom"]!!.jsonPrimitive.booleanOrNull!!
            val path = if (!above) "raster" else if (PdfTileRenderer.usesStaging(row.i("cellCount"))) "staged" else "vector"
            assertEquals(row.str("expected"), path)
        }
    }

    // ---------------------------------------------------------------- constants

    @Test
    fun constants() {
        val c = fx.obj("constants")
        assertEquals(c.i("vectorMaxCells"), PdfTileRenderer.VECTOR_MAX_CELLS)
        assertEquals(com.tacmap.map.render.WebMercator.TILE_SIZE, c.d("tileLogicalSize"), 0.0)
        assertEquals(PdfZoomPolicy.DENSITY_CAP, c.obj("tilePx").d("densityCap"), 0.0)
        assertEquals(PdfZoomPolicy.TILE_PX_QUANTUM, c.obj("tilePx").i("quantum"))
        assertEquals(MapCamera.MIN_ZOOM, c.d("cameraZoomMin"), 0.0)
        assertEquals(MapCamera.MAX_ZOOM, c.d("cameraZoomMax"), 0.0)
        assertEquals(TileMath.MAX_UNDERZOOM_LEVELS, c.d("maxUnderzoomLevels"), 0.0)
        assertEquals(TileMath.VISIBLE_INFLATE_DP, c.d("visibleTileInflateUnits"), 0.0)
        assertEquals(MAX_ANCESTOR_LEVELS, c.i("maxAncestorLevels"))
        val tiers = c.obj("tileCache").arr("tiers").map { it.jsonObject }
        assertEquals(TileMemoryBudget.LOW_RAM_BELOW_BYTES, tiers[0]["below"]!!.jsonPrimitive.long)
        assertEquals(TileMemoryBudget.cacheBytes(0, false), tiers[0]["bytes"]!!.jsonPrimitive.long)
        assertEquals(true, tiers[0]["orLowRamDevice"]!!.jsonPrimitive.booleanOrNull)
        assertEquals(TileMemoryBudget.cacheBytes(tiers[1]["below"]!!.jsonPrimitive.long - 1, false), tiers[1]["bytes"]!!.jsonPrimitive.long)
        assertEquals(TileMemoryBudget.cacheBytes(Long.MAX_VALUE, false), tiers[2]["bytes"]!!.jsonPrimitive.long)
        assertEquals(com.tacmap.map.render.TileBitmapCache.EMPTY_COST_BYTES, c.obj("tileCache")["emptyEntryCostBytes"]!!.jsonPrimitive.long)
        assertEquals(MercatorWorld.EARTH_RADIUS, c.d("earthRadius"), 0.0)
        assertEquals(MercatorWorld.METRES_PER_UNIT_Z0, c.d("metresPerUnitZ0"), 1e-9)
        assertEquals(PdfFootprint.SEGMENTS_PER_EDGE, c.i("footprintSegmentsPerEdge"))
        assertEquals(PdfClipPolygon.MIN_AREA_PT2, c.d("clipMinAreaPt2"), 0.0)
        assertEquals(PdfClipPolygon.DEDUPE_EPS, c.d("clipDedupeEpsPt"), 0.0)
        assertEquals(PdfZoomPolicy.JACOBIAN_STEP_PT, c.d("jacobianStepPt"), 0.0)
        assertEquals(PdfZoomPolicy.DETAIL_OVERSAMPLE, c.d("detailOversample"), 0.0)
        assertEquals(PdfZoomPolicy.DETAIL_HYSTERESIS, c.d("detailZoomHysteresis"), 0.0)
        assertEquals(PdfZoomPolicy.MAX_ZOOM_CAP, c.i("maxZoomCap"))
        val base = c.obj("baseRaster")
        assertEquals(PdfZoomPolicy.BUDGET_PX, base.i("budgetPx"))
        assertEquals(PdfZoomPolicy.LOW_RAM_BUDGET_PX, base.i("lowRamBudgetPx"))
        assertEquals(TileMemoryBudget.LOW_RAM_BELOW_BYTES, base["lowRamBelowBytes"]!!.jsonPrimitive.long)
        assertEquals(PdfZoomPolicy.BASE_MAX_SIDE, base.d("maxSide"), 0.0)
        assertEquals(PdfZoomPolicy.BASE_MAX_SCALE, base.d("maxScale"), 0.0)
        assertEquals(PdfZoomPolicy.MIP_STOP_SIDE, base.i("mipStopSide"))
        assertEquals(PdfZoomPolicy.UPSAMPLE_TOLERANCE, base.d("upsampleTolerance"), 0.0)
        assertEquals(PdfStagedPlan.OVERSAMPLE, c.obj("staged").d("oversample"), 0.0)
        assertEquals(PdfStagedPlan.MAX_PIXELS_FACTOR, c.obj("staged").d("maxPixelsFactor"), 0.0)
        assertEquals(PdfStagedPlan.SHRINK_FACTOR, c.obj("staged").d("shrinkFactor"), 0.0)
        assertEquals(PdfStagedPlan.PAD_PX, c.obj("staged").d("padPx"), 0.0)
        val w = c.obj("warp")
        assertEquals(PdfTileWarp.MAX_ERROR_PX, w.d("maxErrorPx"), 0.0)
        assertEquals(PdfTileWarp.BASE_DEPTH, w.i("baseDepth"))
        assertEquals(PdfTileWarp.MIN_CELL_PX, w.i("minCellPx"))
        assertEquals(PdfTileWarp.ROOT_PAD_PX, w.i("rootPadPx"))
        assertEquals("#FFFFFF", c.str("paperWhite"))
        val s = c.obj("scheduling")
        assertEquals(PdfRenderScheduling.VECTOR_SETTLE_MS, s["vectorSettleMs"]!!.jsonPrimitive.long)
        assertEquals(PdfRenderScheduling.EWMA_ALPHA, s.d("ewmaAlpha"), 0.0)
        assertEquals(PdfRenderScheduling.HEAVY_THRESHOLD_MS, s.d("heavyThresholdMs"), 0.0)
        assertEquals(PdfJobFormation.MAX_TILES, s.i("jobMaxTiles"))
        assertEquals(PdfJobFormation.MAX_SIDE, s.i("jobMaxSide"))
        assertEquals(PdfJobFormation.BAKE_BLOCK_COLS, s.i("bakeBlockCols"))
        assertEquals(PdfJobFormation.BAKE_BLOCK_ROWS, s.i("bakeBlockRows"))
        assertEquals(PdfRenderScheduling.MAX_BAKE_JOBS_IN_FLIGHT, s.i("maxBakeJobsInFlight"))
        assertEquals(PdfRenderScheduling.ORPHAN_CACHE_TILES, s.i("orphanCacheTiles"))
        assertEquals(PdfRenderScheduling.PDFIUM_THREADS, s.i("androidPdfiumThreads"))
        val st = c.obj("status")
        assertEquals(PdfRenderRules.PREPARING_LABEL_DELAY_MS, st["preparingLabelDelayMs"]!!.jsonPrimitive.long)
        assertEquals(PdfRenderRules.RENDER_ERROR_RUN, st.i("renderErrorConsecutiveFailures"))
        assertEquals(PdfRenderRules.BLANK_SAMPLE_STRIDE, st.i("blankSampleStride"))
        assertEquals(PdfRenderRules.BLANK_WHITE_MAX, st.i("blankMinChannelMax"))
        assertEquals(hex(PdfRenderRules.PREPARING_COLOR), st.str("preparingColor"))
        assertEquals(hex(PdfRenderRules.FAILED_COLOR), st.str("failedColor"))
        assertEquals(hex(PdfRenderRules.READY_COLOR), st.str("readyColor"))
        assertEquals(PdfRenderFailure.entries.map { it.code }, st.arr("failureReasons").map { it.jsonPrimitive.content })
        assertEquals(hex(com.tacmap.map.render.TILE_VIEW_BACKGROUND_ARGB), c.obj("hiddenBackground").str("android"))
        assertEquals(com.tacmap.map.IMPORTED_MAP_VISIBLE_KEY, c.obj("visibilityKey").str("android"))
        assertEquals(true, c.obj("visibilityKey").b("default"))
        val bake = c.obj("bake")
        assertEquals(PdfBakePlan.MAX_TILES, bake.i("maxTiles"))
        assertEquals(PdfBakePlan.FREE_SPACE_FACTOR, bake.d("freeSpaceFactor"), 0.0)
        assertEquals(PdfBakePlan.BYTES_SAFETY, bake.d("bytesSafety"), 0.0)
        assertEquals(PdfBakePlan.SAMPLE_TILES, bake.i("sampleTiles"))
        assertEquals(PdfBakePlan.COMMIT_EVERY, bake.i("commitEvery"))
        assertEquals(PdfBakePlan.RENDERER_VERSION, bake.i("rendererVersion"))
        assertEquals(PdfBakePlan.KEY_PREFIX, bake.str("bakeKeyPrefix"))
        assertEquals(PdfBakePlan.WEBP_QUALITY, bake.i("androidWebpQuality"))
        assertEquals(PdfBakePlan.WEBP_LOSSLESS, bake.b("androidWebpLossless"))
        assertEquals(PdfBakePlan.MBTILES_FORMAT, bake.str("androidMbtilesFormat"))
        // D4: the production gate is the fixture's, and the psnr section agrees
        assertEquals(PdfBakePlan.PSNR_GATE_DB, bake.d("psnrGateDb"), 0.0)
        assertEquals(PdfBakePlan.PSNR_GATE_DB, fx.obj("psnr").d("gateDb"), 0.0)
        assertEquals(PdfBakePlan.MBTILES_NAME, bake.str("mbtilesName"))
        assertEquals(PdfBakePlan.MIN_ZOOM, bake.i("minZoom"))
        assertEquals(PdfBakePlan.CANDIDATE_OFFSETS, bake.arr("candidateOffsets").map { it.jsonPrimitive.int })
        assertEquals(PdfBakePlan.DEFAULT_OFFSET, bake.i("defaultOffset"))
        assertEquals(PdfRenderGuardState.MAX_VERIFIED_TOKENS, c.obj("crashGuard").i("maxVerifiedTokens"))
        assertEquals(PdfRenderGuardState.FILE_VERSION, c.obj("crashGuard").i("fileVersion"))
        for (m in c.arr("memoryCases").map { it.jsonObject }) {
            val ram = m["physicalMemory"]!!.jsonPrimitive.long
            val low = m.b("lowRamDevice")
            assertEquals("cache $ram/$low", m["tileCacheBytes"]!!.jsonPrimitive.long, TileMemoryBudget.cacheBytes(ram, low))
            assertEquals("budget $ram/$low", m.i("baseBudgetPx"), TileMemoryBudget.baseBudgetPx(ram, low))
        }
    }

    private fun hex(argb: Long): String = "#%06X".format(argb and 0xFFFFFF)

    // ---------------------------------------------------------------- tilePx

    @Test
    fun tilePx() {
        for (row in fx.arr("tilePx").map { it.jsonObject }) {
            assertEquals("density ${row.d("density")}", row.i("tilePx"), PdfZoomPolicy.tilePx(row.d("density")))
        }
    }

    // ---------------------------------------------------------------- zoomPolicy

    @Test
    fun zoomPolicyEverySheet() {
        val rows = fx.arr("zoomPolicy").map { it.jsonObject }
        assertEquals(8, rows.size)
        for (row in rows) assertZoomPolicy(PdfTileRenderFixture.sheetById(row.str("sheet")), row)
    }

    @Test
    fun zoomPolicyMarkersSheet() {
        assertZoomPolicy(PdfTileRenderFixture.sheetById("render_markers"), fx.obj("markers").obj("zoomPolicy"))
    }

    private fun assertZoomPolicy(sheet: PdfTileRenderFixture.Sheet, row: JsonObject) {
        val id = row.str("sheet")
        val pb = row.dbl("pageBox")
        assertEquals("$id pageBox", pb.toList(), listOf(sheet.pageBox.llx, sheet.pageBox.lly, sheet.pageBox.urx, sheet.pageBox.ury).map { it }
            .zip(pb.toList()).map { (a, e) -> if (abs(a - e) <= 1e-6) e else a })
        val fp = sheet.footprint
        assertCyclic("$id clip", PdfTileRenderFixture.points(row["clipPolygon"]!!), fp.clip, tol.d("clipPolygonPt"))
        val mean = row.dbl("clipMean")
        assertEquals("$id clipMean.x", mean[0], fp.clipMean.x, tol.d("clipMeanPt"))
        assertEquals("$id clipMean.y", mean[1], fp.clipMean.y, tol.d("clipMeanPt"))
        assertEquals("$id clipArea", row.d("clipArea"), fp.clipArea, 1e-5)
        val fb = row.dbl("footprintBboxWorld")
        for (i in 0 until 4) assertEquals("$id footprint bbox[$i]", fb[i], fp.worldBounds[i], 1e-8)
        val pol = sheet.policy
        val jac = row.arr("jacobian").map { PdfGeorefFixture.doubles(it) }
        for (r in 0..1) for (k in 0..1) relClose("$id J[$r][$k]", jac[r][k], pol.jacobian[r][k], 1e-6, 1e-7)
        relClose("$id mercMetresPerPoint", row.d("mercMetresPerPoint"), pol.mercMetresPerPoint, tol.d("mercMetresPerPointRel"))
        assertEquals("$id detailZoomRaw", row.d("detailZoomRaw"), pol.detailZoomRaw, tol.d("detailZoomRawAbs"))
        assertEquals("$id detailZoom", row.i("detailZoom"), pol.detailZoom)
        assertEquals("$id minZoom", row.i("minZoom"), pol.minZoom)
        assertEquals("$id maxZoom", row.i("maxZoom"), pol.maxZoom)
        val fixtureMmpp = row.d("mercMetresPerPoint")
        for (p in row.arr("pxPerPt").map { it.jsonObject }) {
            val z = p.i("z"); val tp = p.i("tilePx")
            relClose("$id pxPerPt z$z@$tp fixture mmpp", p.d("value"), PdfZoomPolicy.pxPerPoint(fixtureMmpp, z, tp), tol.d("pxPerPtRel"))
            relClose("$id pxPerPt z$z@$tp own mmpp", p.d("value"), pol.pxPerPoint(z, tp), 1e-6)
        }
        for ((name, budgetRam) in listOf("normal" to 8L * 1024 * 1024 * 1024, "lowRam" to 2L * 1024 * 1024 * 1024)) {
            val e = row.obj("basePlan").obj(name)
            val budget = TileMemoryBudget.baseBudgetPx(budgetRam, false)
            assertEquals("$id $name budget", e.i("budgetPx"), budget)
            val plan = pol.baseRasterPlan(fp, budget)
            val region = e.dbl("region")
            for (i in 0 until 4) assertEquals("$id $name region[$i]", region[i], plan.region[i], 1e-6)
            relClose("$id $name s", e.d("s"), plan.scale, 1e-9)
            assertEquals("$id $name W", e.i("W"), plan.width)
            assertEquals("$id $name H", e.i("H"), plan.height)
            relClose("$id $name density", e.d("densityPxPerPt"), plan.densityPxPerPt, 1e-9)
            val mips = e.arr("mips").map { PdfTileRenderFixture.ints(it).toList() }
            assertEquals("$id $name mips", mips, plan.mips.map { it.toList() })
            for (bm in e.arr("baseMaxZoom").map { it.jsonObject }) {
                val expected = bm["baseMaxZoom"]!!.jsonPrimitive.intOrNull ?: -1
                assertEquals("$id $name baseMaxZoom @${bm.i("tilePx")}", expected, pol.baseMaxZoom(plan, bm.i("tilePx")))
            }
        }
    }

    private fun assertCyclic(label: String, expected: List<PagePoint>, actual: List<PagePoint>, eps: Double) {
        assertEquals("$label size", expected.size, actual.size)
        val n = expected.size
        val ok = (0 until n).any { shift ->
            (0 until n).all { i ->
                val e = expected[i]; val a = actual[(i + shift) % n]
                abs(e.x - a.x) <= eps && abs(e.y - a.y) <= eps
            }
        }
        assertTrue("$label: $actual isn't a rotation of $expected", ok)
    }

    // ---------------------------------------------------------------- coverage

    @Test
    fun coverageEveryLevel() {
        for (row in fx.arr("coverage").map { it.jsonObject }) {
            val id = row.str("sheet")
            val sheet = PdfTileRenderFixture.sheetById(id)
            val fp = sheet.footprint
            for (lv in row.arr("levels").map { it.jsonObject }) {
                val z = lv.i("z")
                val level = fp.level(z)
                assertEquals("$id z$z count", lv.i("count"), level.count)
                assertEquals("$id z$z inside", lv.i("insideCount"), level.insideCount)
                assertEquals("$id z$z edge", lv.i("edgeCount"), level.count - level.insideCount)
                val lines = (0 until level.count).joinToString("\n") { "$z/${level.xs[it]}/${level.ys[it]}" }
                assertEquals("$id z$z sha", lv.str("sha256"), sha256(lines))
                assertEquals("$id z$z runs", runs(lv["runs"]!!), runsOf(level, null))
                assertEquals("$id z$z insideRuns", runs(lv["insideRuns"]!!), runsOf(level, TileCoverage.INSIDE))
                lv["tiles"]?.jsonArray?.let { tiles ->
                    assertEquals("$id z$z tiles", tiles.map { PdfTileRenderFixture.ints(it).toList() },
                        (0 until level.count).map { listOf(level.xs[it], level.ys[it]) })
                }
                // per tile classify has to agree with the level scan, plus a ring of OUTSIDE around it
                if (level.count <= 4000) {
                    for (i in 0 until level.count) {
                        assertEquals("$id z$z ${level.xs[i]},${level.ys[i]}", level.coverage[i], fp.classify(z, level.xs[i], level.ys[i]))
                    }
                    if (level.count > 0) {
                        val x0 = level.xs.min() - 1; val x1 = level.xs.max() + 1
                        val y0 = level.ys.min() - 1; val y1 = level.ys.max() + 1
                        val set = (0 until level.count).map { level.xs[it] to level.ys[it] }.toSet()
                        for (x in x0..x1) for (y in y0..y1) if ((x to y) !in set) {
                            assertEquals("$id z$z outside $x,$y", TileCoverage.OUTSIDE, fp.classify(z, x, y))
                        }
                    }
                }
            }
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.US_ASCII)).joinToString("") { "%02x".format(it) }

    private fun runs(e: JsonElement): List<List<Int>> = e.jsonArray.map { PdfTileRenderFixture.ints(it).toList() }

    private fun runsOf(level: PdfFootprint.Level, only: TileCoverage?): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var y = Int.MIN_VALUE; var start = 0; var prev = 0
        for (i in 0 until level.count) {
            if (only != null && level.coverage[i] != only) continue
            val x = level.xs[i]; val ty = level.ys[i]
            if (ty == y && x == prev + 1) { prev = x; continue }
            if (y != Int.MIN_VALUE) out += listOf(y, start, prev)
            y = ty; start = x; prev = x
        }
        if (y != Int.MIN_VALUE) out += listOf(y, start, prev)
        return out
    }

    // ---------------------------------------------------------------- bakeOptions

    @Test
    fun bakeOptions() {
        for (row in fx.arr("bakeOptions").map { it.jsonObject }) {
            val id = row.str("sheet")
            val sheet = PdfTileRenderFixture.sheetById(id)
            assertEquals("$id D", row.i("detailZoom"), sheet.policy.detailZoom)
            // the real path: each level counted with what's left of 6000, stops once past it (S2)
            val limits = ArrayList<Int>()
            val opts = PdfBakePlan.options(sheet.policy.detailZoom) { z, limit ->
                limits += limit
                sheet.footprint.count(z, limit)
            }
            val cands = row.arr("candidates").map { it.jsonObject }
            assertEquals("$id kept", cands.map { it.i("maxZoom") to it.b("kept") }, opts.candidates.map { it.maxZoom to it.kept })
            for ((e, a) in cands.zip(opts.candidates)) {
                val m = e.i("maxZoom")
                // the dropped ones' exact totals, off the uncapped count, have to match too
                assertEquals("$id z$m exact tiles", e.i("tiles"), (0..m).sumOf { sheet.footprint.count(it) })
                if (e.b("kept")) assertEquals("$id z$m tiles", e.i("tiles"), a.tiles)
                else assertTrue("$id z$m dropped past the cap ${a.tiles}", a.tiles > PdfBakePlan.MAX_TILES)
            }
            assertTrue("$id limits $limits", limits.all { it in 0..PdfBakePlan.MAX_TILES })
            assertEquals("$id options", row.arr("options").map { it.jsonPrimitive.int }, opts.options.map { it.maxZoom })
            assertEquals("$id default", row["default"]!!.jsonPrimitive.intOrNull, opts.default?.maxZoom)
            assertEquals("$id tooLarge", row.b("tooLarge"), opts.tooLarge)
        }
    }

    // ---------------------------------------------------------------- warp

    @Test
    fun warpEveryJob() {
        val rows = fx.arr("warp").map { it.jsonObject }
        assertTrue(rows.size > 100)
        var insideJobs = 0
        for (row in rows) {
            val id = row.str("sheet")
            val sheet = PdfTileRenderFixture.sheetById(id)
            val j = row.obj("job")
            val job = TileJob(j.i("z"), j.i("x0"), j.i("y0"), j.i("cols"), j.i("rows"))
            val tp = row.i("tilePx")
            val what = "$id ${row.str("label")} z${job.z} ${job.x0}/${job.y0} ${job.cols}x${job.rows} @$tp"
            val coverage = job.tiles().map { sheet.footprint.classify(it[0], it[1], it[2]).name.lowercase() }
            assertEquals("$what tileCoverage", row.arr("tileCoverage").map { it.jsonPrimitive.content }, coverage)
            val plan = PdfTileWarp.plan(job, tp, sheet.footprint, sheet.georef)
            assertEquals("$what maxDepth", row.i("maxDepth"), plan.maxDepth)
            val root = row["root"]
            if (root == null || root is JsonNull) assertNull("$what root", plan.root)
            else assertEquals("$what root", PdfTileRenderFixture.ints(root).toList(), plan.root!!.toList())
            assertEquals("$what leafCount", row.i("leafCount"), plan.leaves.size)
            assertEquals("$what cellCount", row.i("cellCount"), plan.cells.size)
            assertEquals("$what insideCells", row.i("insideCells"), plan.cells.count { it.coverage == TileCoverage.INSIDE })
            assertEquals("$what edgeCells", row.i("edgeCells"), plan.cells.count { it.coverage == TileCoverage.EDGE })
            assertEquals("$what outsideLeaves", row.i("outsideLeaves"), plan.leaves.count { it.coverage == TileCoverage.OUTSIDE })
            assertEquals("$what dropped", row.i("droppedCells"), plan.dropped)
            assertEquals("$what maxErrorPx", row.d("maxErrorPx"), plan.maxErrorPx, tol.d("warpErrorPx"))
            assertEquals("$what errorBoundMet", row.b("errorBoundMet"), plan.errorBoundMet)
            if (row.b("errorBoundMet")) assertTrue("$what max err", plan.maxErrorPx <= PdfTileWarp.MAX_ERROR_PX)
            relClose("$what required", row.d("requiredPxPerPt"), plan.requiredPxPerPt, tol.d("requiredPxPerPtRel"), 1e-12)
            val cells = row["cells"]?.jsonArray ?: continue
            insideJobs++
            assertEquals("$what cells", cells.size, plan.leaves.size)
            cells.map { it.jsonObject }.zip(plan.leaves).forEachIndexed { i, (e, a) ->
                assertEquals("$what cell $i rect", PdfTileRenderFixture.ints(e["rect"]!!).toList(), listOf(a.left, a.top, a.right, a.bottom))
                val m = PdfGeorefFixture.doubles(e["pageToPx"]!!)
                val got = listOf(a.pageToPx.a, a.pageToPx.b, a.pageToPx.c, a.pageToPx.d, a.pageToPx.e, a.pageToPx.f)
                for (k in 0 until 6) relClose("$what cell $i pageToPx[$k]", m[k], got[k], tol.d("warpPageToPxRel"), tol.d("warpPageToPxAbsFloor"))
                assertEquals("$what cell $i err", e.d("errorPx"), a.errorPx, tol.d("warpErrorPx"))
                assertEquals("$what cell $i coverage", e.str("coverage"), a.coverage.name.lowercase())
                listOf("pageTL", "pageTR", "pageBR", "pageBL").forEachIndexed { k, key ->
                    val p = PdfGeorefFixture.point(e[key]!!)
                    assertEquals("$what cell $i $key.x", p.x, a.quad[k].x, tol.d("warpPagePt"))
                    assertEquals("$what cell $i $key.y", p.y, a.quad[k].y, tol.d("warpPagePt"))
                }
            }
        }
        assertTrue(insideJobs > 50)
    }

    @Test
    fun warpSyntheticGeorefIsTheOneTheWarpRowsUse() {
        val wide = fx.arr("warpSyntheticGeorefs").map { it.jsonObject }.single()
        assertEquals("wide_tm_1m", wide.str("id"))
        val sheet = PdfTileRenderFixture.sheetById("wide_tm_1m")
        assertEquals(4, sheet.footprint.clip.size)
        // Wide jobs exercise the regenerated 64–1024-cell corpus; every row is checked above.
        val counts = fx.arr("warp").map { it.jsonObject }.filter { it.str("sheet") == "wide_tm_1m" }.map { it.i("cellCount") }
        assertTrue(counts.contains(256))
        assertTrue(counts.contains(64))
    }

    // ---------------------------------------------------------------- jobFormation

    @Test
    fun jobFormation() {
        for (row in fx.arr("jobFormation").map { it.jsonObject }) {
            val e = row.obj("job")
            val expected = TileJob(e.i("z"), e.i("x0"), e.i("y0"), e.i("cols"), e.i("rows"))
            val heavy = row.b("heavy")
            val got = if (row.str("mode") == "bake") {
                val t = PdfTileRenderFixture.ints(row["tile"]!!)
                PdfJobFormation.bakeBlock(TileIndex(t[0], t[1], t[2]), heavy)
            } else {
                val s = PdfTileRenderFixture.ints(row["seed"]!!)
                val seed = TileIndex(s[0], s[1], s[2])
                val pending = row.arr("pending").map { it.jsonObject }
                    .filter { it.str("band") == "visible" }
                    .map { TileIndex(it.i("z"), it.i("x"), it.i("y")) }.toSet()
                if (heavy) PdfJobFormation.grow(seed) { it in pending } else TileJob.single(seed.z, seed.x, seed.y)
            }
            assertEquals(row.str("label"), expected, got)
        }
    }

    // ---------------------------------------------------------------- drawPlan

    @Test
    fun drawPlan() {
        for (row in fx.arr("drawPlan").map { it.jsonObject }) {
            val label = row.str("label")
            val visible = row.arr("visible").map { it.jsonObject }.map {
                VisibleTile(TileIndex(it.i("z"), it.i("x"), it.i("y")), it.i("col"), it.i("row"))
            }
            val cache = row.arr("cache").map { it.jsonObject }.associate {
                val t = PdfTileRenderFixture.ints(it["tile"]!!)
                TileIndex(t[0], t[1], t[2]) to if (it.str("state") == "image") TileCacheState.IMAGE else TileCacheState.EMPTY
            }
            val fz = row["fallbackZoom"]?.jsonPrimitive?.intOrNull
            val plan = TileDrawPlanner.plan(visible, { cache[it] ?: TileCacheState.MISSING }, fz)
            val items = row.arr("items").map { it.jsonObject }
            assertEquals("$label item count", items.size, plan.items.size)
            items.zip(plan.items).forEach { (e, a) ->
                assertEquals("$label kind", e.str("kind"), a.kind.name.lowercase())
                val src = PdfTileRenderFixture.ints(e["source"]!!)
                assertEquals("$label source", TileIndex(src[0], src[1], src[2]), a.source)
                val d = e.obj("dest")
                assertEquals("$label dest", VisibleTile(TileIndex(d.i("z"), d.i("x"), d.i("y")), d.i("col"), d.i("row")), a.dest)
                assertEquals("$label unitRect", unit(e["unitRect"]!!), a.unitRect)
                assertEquals("$label destRect", unit(e["destRect"]!!), a.destRect)
                assertEquals("$label order", e.i("order"), a.order)
            }
            assertEquals("$label requests", row.arr("requests").map { PdfTileRenderFixture.ints(it).toList() },
                plan.requests.map { listOf(it.z, it.x, it.y) })
            assertTrue(plan.items.all { it.kind != TileDrawItem.Kind.OWN || it.unitRect == UnitRect.FULL })
        }
    }

    private fun unit(e: JsonElement): UnitRect = PdfGeorefFixture.doubles(e).let { UnitRect(it[0], it[1], it[2], it[3]) }

    // ---------------------------------------------------------------- crashGuard

    @Test
    fun crashGuardReducer() {
        for (case in fx.arr("crashGuard").map { it.jsonObject }) {
            val label = case.str("label")
            // the legacy case starts from a file an older build wrote, everything else from nothing
            val g = case["initialState"]?.let { PdfRenderGuardState.fromJson(it) } ?: PdfRenderGuardState()
            case["initialVerifiedOrder"]?.let { order ->
                assertEquals("$label initial order", order.jsonArray.map { it.jsonPrimitive.content }, g.verifiedOrder)
            }
            for ((i, step) in case.arr("steps").map { it.jsonObject }.withIndex()) {
                val what = "$label step $i"
                val result = apply(g, step.obj("event"))
                assertEquals("$what result", step.obj("result"), result)
                assertEquals("$what state", step.obj("state"), g.toJson())
                assertEquals("$what verifiedOrder", step.arr("verifiedOrder").map { it.jsonPrimitive.content }, g.verifiedOrder)
                // the on disk shape has to round trip, order included
                val back = PdfRenderGuardState.fromJson(Json.parseToJsonElement(g.toJson().toString()))
                assertEquals("$what round trip", g.toJson(), back.toJson())
                assertEquals("$what round trip order", g.verifiedOrder, back.verifiedOrder)
            }
        }
    }

    private fun apply(g: PdfRenderGuardState, ev: JsonObject): JsonObject {
        fun s(k: String) = (ev[k] as? JsonPrimitive)?.contentOrNull
        return when (s("op")) {
            "arm" -> {
                val fg = (ev["foreground"] as? JsonPrimitive)?.booleanOrNull ?: true
                val armed = g.arm(GuardKind.fromCode(s("kind"))!!, s("token")!!, fg, s("opKey"))
                JsonObject(mapOf("armed" to JsonPrimitive(armed)))
            }
            "complete" -> { g.complete(GuardKind.fromCode(s("kind"))!!, s("token")!!); JsonObject(emptyMap()) }
            "disarmBackground" -> { g.disarmBackground(); JsonObject(emptyMap()) }
            "launch" -> {
                val d = g.launch(s("restoredToken"))
                val m = linkedMapOf<String, JsonElement>("decision" to JsonPrimitive(d.code))
                if (d.importInterrupted) d.operationKey?.let { m["op"] = JsonPrimitive(it) }
                m["bakeInterrupted"] = JsonPrimitive(d.bakeInterrupted)
                // both notices when both apply, the decision's one first (K1)
                val notices = PdfLaunchNotice.from(d)
                val want = listOfNotNull(
                    if (d.importInterrupted) PdfLaunchNotice.ImportInterrupted(d.operationKey) else null,
                    if (d.bakeInterrupted) PdfLaunchNotice.BakeInterrupted else null,
                )
                assertEquals("notices for $m", want, notices)
                JsonObject(m)
            }
            "resolve" -> {
                g.resolve(GuardResolution.entries.first { it.code == s("choice") })
                JsonObject(emptyMap())
            }
            else -> error("unknown op ${s("op")}")
        }
    }

    @Test
    fun crashGuardFileSurvivesAReopen() {
        val dir = Files.createTempDirectory("guard").toFile()
        try {
            val file = File(dir, PdfRenderGuard.FILE_NAME)
            val tok = "00000000-0000-4000-8000-000000000001"
            val a = PdfRenderGuard(file)
            assertTrue(a.arm(GuardKind.VECTOR, tok))
            assertTrue(file.isFile)
            assertFalse(File(dir, PdfRenderGuard.FILE_NAME + ".tmp").exists())
            // "process death" then a relaunch reads the marker back
            val b = PdfRenderGuard(file)
            assertTrue(b.launchDecision(tok).suppress)
            // asked again in the same process, same answer, no second launch step
            assertTrue(b.launchDecision(tok).suppress)
            b.resolve(GuardResolution.OPEN_ANYWAY)
            assertEquals(GuardLaunchDecision.NONE, PdfRenderGuard(file).launchDecision(tok))
            // junk on disk fails open
            file.writeText("{not json")
            assertEquals(GuardLaunchDecision.NONE, PdfRenderGuard(file).launchDecision(tok))
            // and a non uuid token never gets written
            assertFalse(PdfRenderGuard(file).arm(GuardKind.BAKE, "my-map.pdf"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun crashGuardResolutionsAcrossRelaunches() {
        val dir = Files.createTempDirectory("guard-res").toFile()
        try {
            val file = File(dir, PdfRenderGuard.FILE_NAME)
            val tok = "00000000-0000-4000-8000-000000000002"
            val bake = "00000000-0000-4000-8000-000000000003"
            // a first vector render and a bake both die with the process
            assertTrue(PdfRenderGuard(file).run { arm(GuardKind.BAKE, bake) && arm(GuardKind.VECTOR, tok) })
            val first = PdfRenderGuard(file).launchDecision(tok)
            assertTrue(first.suppress)
            assertTrue(first.bakeInterrupted)
            // suppress has its own alert, the bake notice queues behind it
            assertEquals(listOf(PdfLaunchNotice.BakeInterrupted), PdfLaunchNotice.from(first))
            // Not Now keeps the suspect, the next launch asks again (bake notice only once)
            PdfRenderGuard(file).also { it.launchDecision(tok) }.resolve(GuardResolution.NOT_NOW)
            val again = PdfRenderGuard(file).launchDecision(tok)
            assertTrue(again.suppress)
            assertFalse(again.bakeInterrupted)
            // Open Anyway clears it for good
            PdfRenderGuard(file).also { it.launchDecision(tok) }.resolve(GuardResolution.OPEN_ANYWAY)
            assertEquals(GuardLaunchDecision.NONE, PdfRenderGuard(file).launchDecision(tok))
            // an import that dies during a bake: both notices, import first
            PdfRenderGuard(file).run {
                arm(GuardKind.BAKE, bake)
                arm(GuardKind.IMPORT, tok, operationKey = "pdf:op-1")
            }
            val both = PdfRenderGuard(file).launchDecision(null)
            assertEquals(
                listOf(PdfLaunchNotice.ImportInterrupted("pdf:op-1"), PdfLaunchNotice.BakeInterrupted),
                PdfLaunchNotice.from(both),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- bakeJobFormation (E2)

    @Test
    fun bakeJobFormation() {
        val cases = fx.arr("bakeJobFormation").map { it.jsonObject }
        assertTrue(cases.size >= 7)
        for (case in cases) {
            val label = case.str("label")
            val bmz = case["baseMaxZoom"]?.jsonPrimitive?.intOrNull ?: -1
            for (lv in case.arr("levels").map { it.jsonObject }) {
                val z = lv.i("z")
                val tiles = lv.arr("tiles").map { PdfTileRenderFixture.ints(it) }
                val xs = IntArray(tiles.size) { tiles[it][0] }
                val ys = IntArray(tiles.size) { tiles[it][1] }
                // a fake estimator: heavy at the level start, then it flips after the first job
                var heavy = lv.b("heavyAtLevelStart")
                var asked = 0
                val plan = PdfBakeJobs.level(z, xs, ys, bmz) { asked++; heavy }
                assertEquals("$label z$z path", lv.str("path"), if (plan.raster) "raster" else "vector")
                assertEquals("$label z$z heavyUsed", lv.b("heavyUsed"), plan.heavy)
                assertTrue("$label z$z heavy read at most once", asked <= 1)
                val got = ArrayList<List<Int>>()
                for (j in plan.jobs()) {
                    got += listOf(j.job.z, j.job.x0, j.job.y0, j.job.cols, j.job.rows, j.wanted)
                    heavy = lv.b("heavyAfterFirstJob")
                }
                val want = lv.arr("jobs").map { it.jsonObject }.map {
                    listOf(it.i("z"), it.i("x0"), it.i("y0"), it.i("cols"), it.i("rows"), it.i("wanted"))
                }
                assertEquals("$label z$z jobs", want, got)
                assertEquals("$label z$z every tile once", tiles.size, got.sumOf { it[5] })
                assertTrue("$label z$z heavy still read once", asked <= 1)
            }
        }
    }

    // ---------------------------------------------------------------- failureAccounting (G2)

    @Test
    fun failureAccounting() {
        val cases = fx.arr("failureAccounting").map { it.jsonObject }
        assertTrue(cases.size >= 22)
        var baseCases = 0
        var ewmaSteps = 0
        for (case in cases) {
            val label = case.str("label")
            val t = PdfRenderRules.FailureTracker()
            // R1 cases drive the real base raster lifecycle the live source runs on
            val bmz = (case["baseMaxZoom"] as? JsonPrimitive)?.intOrNull
            val base = bmz?.let { PdfRenderRules.BaseRasterLifecycle(it, t) }
            if (base != null) baseCases++
            for ((i, step) in case.arr("steps").map { it.jsonObject }.withIndex()) {
                val ev = step.obj("event")
                val at = "$label step $i"
                fun reason(key: String = "reason"): PdfRenderFailure? = when (val r = ev.str(key)) {
                    "other" -> null
                    else -> PdfRenderFailure.fromCode(r) ?: error("$at: reason $r")
                }
                fun path() = when (ev.str("path")) {
                    "vector" -> PdfRenderRules.JobPath.VECTOR
                    "staged" -> PdfRenderRules.JobPath.STAGED
                    "rasterSample" -> PdfRenderRules.JobPath.RASTER_SAMPLE
                    "baseRaster" -> PdfRenderRules.JobPath.BASE_RASTER
                    else -> error("$at: path ${ev.str("path")}")
                }
                var started: Boolean? = null
                var replan: Boolean? = null
                when (ev.str("op")) {
                    // waiters only says who was still listening, the started job counts either way (R2)
                    "jobOk" -> { path(); t.jobOk() }
                    "jobFailed" -> t.jobFailed(path(), reason())
                    "jobCancelled" -> t.jobCancelled()
                    "bakeJobOk", "bakeJobFailed" -> t.bakeJobFinished()
                    "documentFailure" -> t.documentFailure(reason()!!)
                    "blankVerdict" -> t.blankVerdict()
                    "retry" -> { t.retry(); base?.reset() }
                    "wanted" -> started = base!!.wanted(ev.i("z"))
                    "baseDone" -> replan = base!!.done(
                        when (ev.str("result")) {
                            "ok" -> PdfRenderRules.BaseRasterLifecycle.Result.Ok
                            "blank" -> PdfRenderRules.BaseRasterLifecycle.Result.Blank
                            else -> PdfRenderRules.BaseRasterLifecycle.Result.Failed(reason("result"))
                        },
                    )
                    else -> error("$at: op ${ev.str("op")}")
                }
                val st = step.obj("state")
                assertEquals("$at failed", (st["failed"] as? JsonPrimitive)?.contentOrNull, t.failed?.code)
                assertEquals("$at run", st.i("consecutiveFailures"), t.consecutiveFailures)
                if (base != null) {
                    val eb = step.obj("base")
                    assertEquals("$at base status", eb.str("status"), baseStatus(base.status))
                    assertEquals("$at base attempts", eb.i("attempts"), base.attempts)
                    assertEquals("$at startsBaseAttempt", step.b("startsBaseAttempt"), started == true)
                    assertEquals("$at replan", step.b("replan"), replan == true)
                }
                (step["feedsEwma"] as? JsonPrimitive)?.booleanOrNull?.let { feeds ->
                    ewmaSteps++
                    val op = ev.str("op")
                    val got = op in setOf("jobOk", "jobFailed") && PdfRenderRules.feedsEwma(
                        path(), ok = op == "jobOk", anyImage = (ev["allEmpty"] as? JsonPrimitive)?.booleanOrNull != true,
                    )
                    assertEquals("$at feedsEwma", feeds, got)
                }
            }
        }
        assertTrue("R1 base cases", baseCases >= 5)
        assertTrue("R2 ewma steps", ewmaSteps >= 10)
    }

    private fun baseStatus(s: PdfRenderRules.BaseRasterLifecycle.Status) = when (s) {
        PdfRenderRules.BaseRasterLifecycle.Status.NOT_STARTED -> "notStarted"
        PdfRenderRules.BaseRasterLifecycle.Status.IN_FLIGHT -> "inFlight"
        PdfRenderRules.BaseRasterLifecycle.Status.READY -> "ready"
        PdfRenderRules.BaseRasterLifecycle.Status.BLANK -> "blank"
        PdfRenderRules.BaseRasterLifecycle.Status.FAILED -> "failed"
    }

    // ---------------------------------------------------------------- stagedRegion (r1 D5, R5)

    private fun stagedNum(e: JsonElement?): Double = when (val c = (e as JsonPrimitive).content) {
        "NaN" -> Double.NaN
        "Infinity" -> Double.POSITIVE_INFINITY
        "-Infinity" -> Double.NEGATIVE_INFINITY
        else -> c.toDouble()
    }

    @Test
    fun stagedRegion() {
        val rows = fx.arr("stagedRegion").map { it.jsonObject }.filter { it.str("kind") == "region" }
        assertTrue(rows.size >= 20)
        val pt = tol.d("stagedRegionPt")
        val rel = tol.d("stagedDensityRel")
        var errors = 0
        var shrunk = 0
        var fromWarp = 0
        for (row in rows) {
            val label = row.str("label")
            val bbox = (row["cellBbox"] as? JsonArray)?.let { PdfGeorefFixture.doubles(it).toDoubleArray() }
            val required = stagedNum(row["requiredPxPerPt"])
            val jobPx = row["jobPixels"]!!.jsonPrimitive.long
            val r = PdfStagedPlan.region(bbox, required, row.dbl("clipBBox"), jobPx)
            val e = row.obj("expected")
            if (e.str("result") == "renderError") {
                errors++
                assertNull("$label renderError", r)
                continue
            }
            assertNotNull("$label ok", r)
            r!!
            assertEquals("$label pad", e.d("pad"), r.pad, pt)
            val region = e.dbl("region")
            assertEquals("$label x0", region[0], r.x0, pt); assertEquals("$label y0", region[1], r.y0, pt)
            assertEquals("$label x1", region[2], r.x1, pt); assertEquals("$label y1", region[3], r.y1, pt)
            assertEquals("$label rw", e.d("rw"), r.x1 - r.x0, pt)
            assertEquals("$label rh", e.d("rh"), r.y1 - r.y0, pt)
            relClose("$label cap", e.d("cap"), r.cap, rel)
            assertEquals("$label dFrom", e.str("dFrom"), if (r.fromCap) "cap" else "oversample")
            relClose("$label d0", e.d("d0"), r.d0, rel)
            assertEquals("$label W0", e.i("W0"), r.width0)
            assertEquals("$label H0", e.i("H0"), r.height0)
            assertEquals("$label maxPx", e["maxPx"]!!.jsonPrimitive.long, r.maxPx)
            assertEquals("$label shrinkSteps", e.i("shrinkSteps"), r.shrinkSteps)
            relClose("$label d", e.d("d"), r.density, rel)
            assertEquals("$label W", e.i("W"), r.width)
            assertEquals("$label H", e.i("H"), r.height)
            assertTrue("$label under 2x job px", r.width.toLong() * r.height <= r.maxPx)
            if (r.shrinkSteps > 0) shrunk++
            // the inputs came off a warp job: our own plan has to give the same bbox + required
            (row["fromWarp"] as? JsonObject)?.let { w ->
                fromWarp++
                val sheet = PdfTileRenderFixture.sheetById(w.str("sheet"))
                val j = w.obj("job")
                val job = TileJob(j.i("z"), j.i("x0"), j.i("y0"), j.i("cols"), j.i("rows"))
                val plan = PdfTileWarp.plan(job, w.i("tilePx"), sheet.footprint, sheet.georef)
                assertEquals("$label cells", w.i("cellCount"), plan.cells.size)
                val q = plan.cells.flatMap { it.quad }
                val own = doubleArrayOf(q.minOf { it.x }, q.minOf { it.y }, q.maxOf { it.x }, q.maxOf { it.y })
                for (k in 0 until 4) assertEquals("$label own bbox[$k]", bbox!![k], own[k], tol.d("warpPagePt"))
                relClose("$label own required", required, plan.requiredPxPerPt, tol.d("requiredPxPerPtRel"))
                assertArrayEquals("$label clip bbox", row.dbl("clipBBox"), sheet.footprint.clipBounds, 1e-6)
            }
        }
        assertTrue("renderError rows", errors >= 4)
        assertTrue("shrink rows", shrunk >= 2)
        assertTrue("fromWarp rows", fromWarp >= 3)
    }

    @Test
    fun stagedRegionBlankCheckSheet() {
        // the pixel half (import fails blank) runs on device, PdfTileRenderInstrumentedTest
        val b = fx.arr("stagedRegion").map { it.jsonObject }.single { it.str("kind") == "blankCheck" }
        assertEquals("blank", b.obj("expected").str("importFailure"))
        val bytes = PdfGeorefFixture.readBytes(b.str("file"))
        assertEquals(b.i("bytes"), bytes.size)
        assertEquals(b.str("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val g = parseVp(bytes)
        val sheet = PdfTileRenderFixture.sheetById("render_blank_corner")
        assertCyclic("blank corner crop", sheet.georef.crop, g.crop, 1e-6)
        val fp = PdfFootprint.build(g, PdfTileRenderFixture.pageBox(b))
        assertCyclic("blank corner clip", PdfTileRenderFixture.points(b["clipPolygon"]!!), fp.clip, tol.d("clipPolygonPt"))
        assertArrayEquals(b.dbl("clipBBox"), fp.clipBounds, 1e-9)
        val plan = PdfZoomPolicy.of(g, fp).baseRasterPlan(fp, PdfZoomPolicy.BUDGET_PX)
        val e = b.obj("basePlan")
        val region = e.dbl("region")
        for (i in 0 until 4) assertEquals("basePlan region[$i]", region[i], plan.region[i], 1e-9)
        assertEquals(e.i("W"), plan.width)
        assertEquals(e.i("H"), plan.height)
        relClose("basePlan s", e.d("s"), plan.scale, 1e-12)
        assertEquals(PdfRenderRules.BLANK_SAMPLE_STRIDE, b.i("blankSampleStride"))
        // every mark covers stride samples of the base raster, none of them inside the clip polygon
        val xs = fp.clip.map { it.x }.toDoubleArray(); val ys = fp.clip.map { it.y }.toDoubleArray()
        val w = plan.region[2] - plan.region[0]; val h = plan.region[3] - plan.region[1]
        for (m in b.arr("marks").map { it.jsonObject }) {
            val r = m.dbl("pageRect")
            var covered = 0; var inside = 0
            var j = 0
            while (j < plan.height) {
                val py = plan.region[3] - (j + 0.5) * h / plan.height
                var i = 0
                while (i < plan.width) {
                    val px = plan.region[0] + (i + 0.5) * w / plan.width
                    if (px >= r[0] && px <= r[2] && py >= r[1] && py <= r[3]) {
                        covered++
                        if (PdfClipPolygon.contains(px, py, xs, ys)) inside++
                    }
                    i += PdfRenderRules.BLANK_SAMPLE_STRIDE
                }
                j += PdfRenderRules.BLANK_SAMPLE_STRIDE
            }
            assertEquals("${m.str("label")} samples", m.i("strideSamplesCovered"), covered)
            assertEquals("${m.str("label")} inside clip", m.i("samplesInsideClip"), inside)
            assertTrue(covered > 0)
        }
    }

    // ---------------------------------------------------------------- bakeFormat (r1 OD-F7)

    @Test
    fun bakeFormat() {
        val f = fx.obj("bakeFormat")
        assertEquals(1_000_000, f.obj("rules").i("megabyte"))
        val locales = mapOf("en" to java.util.Locale.ENGLISH, "de" to java.util.Locale.GERMAN)
        val cases = f.arr("cases").map { it.jsonObject }
        assertTrue(cases.size >= 19)
        for (c in cases) {
            val tiles = c["tiles"]!!.jsonPrimitive.long
            val bytes = c["bytes"]!!.jsonPrimitive.long
            for ((tag, loc) in locales) {
                val e = c.obj(tag)
                assertEquals("$tag tiles $tiles", e.str("tiles"), PdfBakeFormat.tiles(tiles, loc))
                assertEquals("$tag size $bytes", e.str("size"), PdfBakeFormat.size(bytes, loc))
            }
        }
    }

    // ---------------------------------------------------------------- psnr + dense (r1 D1)

    @Test
    fun psnrGateTilesFollowTheZoomRule() {
        val p = fx.obj("psnr")
        assertEquals(listOf(768, 512), p.arr("tilePx").map { it.jsonPrimitive.int })
        val sheets = p.arr("sheets").map { it.jsonObject }
        assertEquals(listOf("rot5_iso", "dense"), sheets.map { it.str("sheet") })
        for (sh in sheets) {
            val sheet = PdfTileRenderFixture.sheetById(if (sh.str("sheet") == "dense") "render_dense" else sh.str("sheet"))
            for (check in sh.arr("checks").map { it.jsonObject }) {
                val tp = check.i("tilePx")
                val label = "${sh.str("sheet")}@$tp"
                val plan = sheet.policy.baseRasterPlan(sheet.footprint, PdfZoomPolicy.BUDGET_PX)
                val bmz = sheet.policy.baseMaxZoom(plan, tp)
                assertEquals("$label baseMaxZoom", check.i("baseMaxZoom"), bmz)
                assertEquals("$label detailZoom", check.i("detailZoom"), sheet.policy.detailZoom)
                val zooms = check.arr("zooms").map { it.jsonObject }
                // z = baseMaxZoom..D, the middle INSIDE tile row major, z without one skipped
                val mine = (bmz..sheet.policy.detailZoom).mapNotNull { z ->
                    val level = sheet.footprint.level(z)
                    val inside = (0 until level.count).filter { level.coverage[it] == TileCoverage.INSIDE }
                    inside.getOrNull(inside.size / 2)?.let { Triple(z, listOf(level.xs[it], level.ys[it]), inside.size) }
                }
                assertEquals("$label zooms", zooms.map { it.i("z") }, mine.map { it.first })
                for ((e, m) in zooms.zip(mine)) {
                    assertEquals("$label z${m.first} tile", PdfTileRenderFixture.ints(e["tile"]!!).toList(), m.second)
                    assertEquals("$label z${m.first} insideCount", e.i("insideCount"), m.third)
                    assertEquals("$label z${m.first} path", e.str("path"), if (m.first <= bmz) "raster" else "vector")
                }
                val vector = mine.count { it.first > bmz }
                assertEquals("$label vectorZooms", check.i("vectorZooms"), vector)
                assertTrue("$label checks a vector zoom", vector >= 1)
            }
        }
    }

    @Test
    fun densePdfParsesToTheFixtureGeoref() {
        val d = fx.obj("dense")
        assertEquals("geopdf/tacmap_render_dense.pdf", d.str("file"))
        assertEquals(d.str("file"), fx.obj("psnr").arr("sheets").map { it.jsonObject }.single { it.str("sheet") == "dense" }.str("file"))
        val bytes = PdfGeorefFixture.readBytes(d.str("file"))
        assertEquals(d.i("bytes"), bytes.size)
        assertEquals(d.str("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val g = parseVp(bytes)
        val expected = PdfTileRenderFixture.sheetById("render_dense").georef
        assertCyclic("dense crop", expected.crop, g.crop, 1e-6)
        for (pt in expected.crop) {
            val a = expected.planeOf(pt.x, pt.y); val b = g.planeOf(pt.x, pt.y)
            assertTrue("dense plane at $pt", abs(a.x - b.x) <= 0.01 && abs(a.y - b.y) <= 0.01)
        }
        assertZoomPolicy(PdfTileRenderFixture.sheetById("render_dense"), d.obj("zoomPolicy"))
    }

    // ---------------------------------------------------------------- bakeEstimate (J2)

    @Test
    fun bakeEstimateFallbacksAreOurs() {
        val f = fx.obj("bakeEstimate").obj("fallback")
        assertEquals(PdfBakeEstimate.FALLBACK_JOB_MS, f.d("jobMs"), 0.0)
        assertEquals(PdfBakeEstimate.FALLBACK_ENCODE_MS, f.d("encodeMs"), 0.0)
        assertEquals(PdfBakeEstimate.FALLBACK_TILE_BYTES, f["tileBytes"]!!.jsonPrimitive.long)
    }

    @Test
    fun bakeEstimateSampleSelection() {
        val rows = fx.obj("bakeEstimate").arr("sampleSelection").map { it.jsonObject }
        assertTrue(rows.size >= 8)
        for (row in rows) {
            val id = row.str("sheet")
            val sheet = PdfTileRenderFixture.sheetById(id)
            val z = row.i("z")
            // z is the default maxZoom, same as the real estimate picks it
            val opts = PdfBakePlan.options(sheet.policy.detailZoom) { lz, limit -> sheet.footprint.count(lz, limit) }
            assertEquals("$id default", z, opts.default?.maxZoom)
            val c = sheet.footprint.clipMean
            val ll = sheet.georef.toWGS84(c.x, c.y)!!
            val centre = PdfBakeEstimate.centreTile(MercatorWorld.x(ll.longitude), MercatorWorld.y(ll.latitude), z)!!
            val ec = row.dbl("centreTile")
            assertEquals("$id cx", ec[0], centre[0], tol("bakeEstimateCentreTile", 1e-9))
            assertEquals("$id cy", ec[1], centre[1], tol("bakeEstimateCentreTile", 1e-9))
            val sel = PdfBakeEstimate.sampleSelection(sheet.footprint.level(z), centre)
            assertEquals("$id pool", row.str("pool"), if (sel.insidePool) "inside" else "all")
            assertEquals("$id poolCount", row.i("poolCount"), sel.poolCount)
            assertEquals("$id picks", row.arr("tiles").map { PdfTileRenderFixture.ints(it).toList() }, sel.picks.map { listOf(it.x, it.y) })
            val d2 = row.dbl("d2")
            sel.picks.forEachIndexed { k, p -> assertEquals("$id d2[$k]", d2[k], p.d2, tol("bakeEstimateD2", 1e-9)) }
            // no centre (toWGS84 failed): first 3 of the pool in row major order
            val rowMajor = PdfBakeEstimate.samplePicks(sheet.footprint.level(z), null)
            val level = sheet.footprint.level(z)
            val pool = (0 until level.count).filter { !sel.insidePool || level.coverage[it] == TileCoverage.INSIDE }
            assertEquals("$id row major", pool.take(3).map { listOf(level.xs[it], level.ys[it]) }, rowMajor.map { listOf(it.x, it.y) })
        }
    }

    private fun tol(key: String, fallback: Double): Double = (tol[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: fallback

    @Test
    fun bakeEstimateCases() {
        val cases = fx.obj("bakeEstimate").arr("cases").map { it.jsonObject }
        assertTrue(cases.size >= 10)
        for (case in cases) {
            val label = case.str("label")
            val sheet = PdfTileRenderFixture.sheetById(case.str("sheet"))
            assertEquals("$label D", case.i("detailZoom"), sheet.policy.detailZoom)
            val options = case.arr("options").map { it.jsonObject }.map { PdfBakeOption(it.i("maxZoom"), it.i("tiles"), true) }
            // the option tiles are the footprint's own, so the per level jobs below line up
            for (o in options) assertEquals("$label z${o.maxZoom} tiles", o.tiles, (0..o.maxZoom).sumOf { sheet.footprint.count(it) })
            val samples = case.arr("samples").map { it.jsonObject }.map {
                val result = when (it.str("result")) {
                    "image" -> PdfBakeEstimate.SampleResult.IMAGE
                    "empty" -> PdfBakeEstimate.SampleResult.EMPTY
                    "failed" -> PdfBakeEstimate.SampleResult.FAILED
                    else -> error("$label: result ${it.str("result")}")
                }
                PdfBakeEstimate.Sample(
                    result, it.d("jobMs"),
                    (it["bytes"] as? JsonPrimitive)?.contentOrNull?.toLong() ?: 0L,
                    (it["encodeMs"] as? JsonPrimitive)?.contentOrNull?.toDouble() ?: 0.0,
                )
            }
            val r = PdfBakeEstimate.estimate(
                levels = { sheet.footprint.level(it) },
                baseMaxZoom = case["baseMaxZoom"]?.jsonPrimitive?.intOrNull ?: -1,
                options = options,
                defaultMaxZoom = case.i("defaultMaxZoom"),
                samples = samples,
                ewmaMs = (case["ewmaMs"] as? JsonPrimitive)?.contentOrNull?.toDouble(),
                freeBytes = (case["freeBytes"] as? JsonPrimitive)?.contentOrNull?.toLong(),
            )
            val e = case.obj("expected")
            fun from(f: PdfBakeEstimate.From) = f.name.lowercase()
            assertEquals("$label heavy", e.b("heavy"), r.heavy)
            relClose("$label jobMs", e.d("jobMs"), r.jobMs, 1e-12)
            assertEquals("$label jobMsFrom", e.str("jobMsFrom"), from(r.jobMsFrom))
            relClose("$label encodeMs", e.d("encodeMs"), r.encodeMs, 1e-12)
            assertEquals("$label encodeMsFrom", e.str("encodeMsFrom"), from(r.encodeMsFrom))
            relClose("$label meanTileBytes", e.d("meanTileBytes"), r.meanTileBytes, 1e-12)
            assertEquals("$label meanTileBytesFrom", e.str("meanTileBytesFrom"), from(r.meanTileBytesFrom))
            val eo = e.arr("options").map { it.jsonObject }
            assertEquals("$label option count", eo.size, r.options.size)
            for ((x, a) in eo.zip(r.options)) {
                val w = "$label z${x.i("maxZoom")}"
                assertEquals("$w maxZoom", x.i("maxZoom"), a.maxZoom)
                assertEquals("$w tiles", x.i("tiles"), a.tiles)
                assertEquals("$w jobsPerLevel", x.arr("jobsPerLevel").map { it.jsonPrimitive.int }, a.jobsPerLevel)
                assertEquals("$w jobs", x.i("jobs"), a.jobs)
                assertEquals("$w bytes", x["bytes"]!!.jsonPrimitive.long, a.bytes)
                assertEquals("$w neededBytes", x["neededBytes"]!!.jsonPrimitive.long, a.neededBytes)
                assertEquals("$w enoughSpace", x.b("enoughSpace"), a.enoughSpace)
                relClose("$w estimatedMs", x.d("estimatedMs"), a.estimatedMs, 1e-12)
                assertEquals("$w minutes", x.i("minutes"), a.minutes)
            }
            assertEquals("$label initialSelection", e.i("initialSelection"), r.initialSelection)
            assertEquals("$label generateEnabled", e.b("generateEnabled"), r.generateEnabled)
        }
    }

    // ---------------------------------------------------------------- cameraZoom

    @Test
    fun cameraZoom() {
        val c = fx.obj("cameraZoom")
        val cam = MapCamera(37.0, -122.0, 0.0, 0.0, 400.0, 800.0)
        for (p in c.arr("pinch").map { it.jsonObject }) {
            val z = p.d("zoom"); val scale = p.d("scale")
            assertEquals("pinch $z x$scale", p.d("result"), MapCamera.clampZoom(z + log2(scale)), tol.d("cameraZoom"))
            // the real gesture path, zoom limits only (no source clamp any more)
            val next = com.tacmap.map.applyCustomMapTransform(
                cam.copy(zoom = z), androidx.compose.ui.geometry.Offset(200f, 400f),
                androidx.compose.ui.geometry.Offset.Zero, scale.toFloat(), 0f, 1f,
                MapCamera.MIN_ZOOM, MapCamera.MAX_ZOOM,
            )
            assertEquals("gesture $z x$scale", p.d("result"), next.zoom, 1e-6)
        }
        for (t in c.arr("target").map { it.jsonObject }) {
            assertEquals("target ${t.d("target")}", t.d("result"), MapCamera.clampZoom(t.d("target")), tol.d("cameraZoom"))
        }
        for (t in c.arr("tileZoom").map { it.jsonObject }) {
            val cz = t.d("cameraZoom")
            val tz = TileMath.tileZoom(cz, t.i("minZoom"), t.i("maxZoom"))
            assertEquals("tileZoom $cz", t.i("tileZoom"), tz)
            assertEquals("underzoom $cz", t.b("underzoomHidden"), TileMath.underzoomHidden(tz, cz))
        }
    }

    // ---------------------------------------------------------------- markers

    @Test
    fun markersPdfParsesToTheFixtureGeoref() {
        val m = fx.obj("markers")
        val bytes = PdfGeorefFixture.readBytes(m.str("file"))
        assertEquals(m.i("bytes"), bytes.size)
        assertEquals(m.str("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val g = parseVp(bytes)
        val expected = PdfTileRenderFixture.sheetById("render_markers").georef
        assertCyclic("markers crop", expected.crop, g.crop, 1e-6)
        // compare like the pdf_georef sheets: 0.01 m in the plane, 1e-3 pt back
        for (p in expected.crop + listOf(PagePoint(395.0, 305.0))) {
            val a = expected.planeOf(p.x, p.y); val b = g.planeOf(p.x, p.y)
            assertTrue("plane at $p", abs(a.x - b.x) <= 0.01 && abs(a.y - b.y) <= 0.01)
        }
        // written values are the ones in the file
        val w = m.obj("written")
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("/BBox [" + w.dbl("bbox").joinToString(" ") { num(it) } + "]"))
    }

    @Test
    fun markerExpectationsThroughOurPipeline() {
        val m = fx.obj("markers")
        val sheet = PdfTileRenderFixture.sheetById("render_markers")
        val all = m.arr("markers").map { it.jsonObject } + listOf(m.obj("clippedMarker"), m.obj("hiddenOcgSquare"))
        for (mk in all) {
            val page = PdfGeorefFixture.point(mk["page"]!!)
            for (e in mk.arr("expected").map { it.jsonObject }) {
                assertTilePx("markers ${mk["label"]?.jsonPrimitive?.content ?: "hidden"}", sheet, page, e, tol.d("markerCentroidPx"))
            }
        }
        // the clipped marker sits off the clip polygon, the rest on it
        val fp = sheet.footprint
        val clipXs = fp.clip.map { it.x }.toDoubleArray(); val clipYs = fp.clip.map { it.y }.toDoubleArray()
        for (mk in m.arr("markers").map { it.jsonObject }) {
            val p = PdfGeorefFixture.point(mk["page"]!!)
            assertTrue(mk.str("label"), PdfClipPolygon.contains(p.x, p.y, clipXs, clipYs))
        }
        val clipped = PdfGeorefFixture.point(m.obj("clippedMarker")["page"]!!)
        assertFalse(PdfClipPolygon.contains(clipped.x, clipped.y, clipXs, clipYs))
        // alpha samples: inside the clip polygon is paper (255), outside is see through (0)
        for (s in m.arr("alphaSamples").map { it.jsonObject }) {
            val p = PdfGeorefFixture.point(s["page"]!!)
            val inside = PdfClipPolygon.contains(p.x, p.y, clipXs, clipYs)
            assertEquals("alpha ${s.str("label")} z${s.i("z")}", s.i("alpha") == 255, inside)
            assertTilePx("alpha ${s.str("label")}", sheet, p, s, tol.d("markerCentroidPx"))
        }
    }

    @Test
    fun ringTargetsThroughOurPipeline() {
        for (r in fx.arr("ringTargets").map { it.jsonObject }) {
            val sheet = PdfTileRenderFixture.sheetById(r.str("sheet"))
            val page = PdfGeorefFixture.point(r["page"]!!)
            for (e in r.arr("expected").map { it.jsonObject }) assertTilePx("ring ${r.str("id")}", sheet, page, e, tol.d("markerCentroidPx"))
        }
    }

    /**
     * page point -> toWGS84 -> tile px has to land on the fixture px (0.01), and the warp
     * cell that pixel falls in has to put the page point back within [cellTol] px.
     */
    private fun assertTilePx(label: String, sheet: PdfTileRenderFixture.Sheet, page: PagePoint, e: JsonObject, cellTol: Double) {
        val z = e.i("z"); val tp = e.i("tilePx")
        val tile = PdfTileRenderFixture.ints(e["tile"]!!)
        val px = e.dbl("px")
        val ll = sheet.georef.toWGS84(page.x, page.y)!!
        val wx = MercatorWorld.x(ll.longitude) * Math.scalb(1.0, z)
        val wy = MercatorWorld.y(ll.latitude) * Math.scalb(1.0, z)
        val tx = floor(wx / 256.0).toInt(); val ty = floor(wy / 256.0).toInt()
        assertEquals("$label z$z@$tp tile", tile.toList(), listOf(tx, ty))
        val u = (wx - tx * 256.0) * tp / 256.0
        val v = (wy - ty * 256.0) * tp / 256.0
        assertEquals("$label z$z@$tp u", px[0], u, tol.d("tilePxExpectPx"))
        assertEquals("$label z$z@$tp v", px[1], v, tol.d("tilePxExpectPx"))
        val plan = PdfTileWarp.plan(TileJob.single(z, tx, ty), tp, sheet.footprint, sheet.georef)
        val cell = plan.leaves.firstOrNull { u >= it.left && u < it.right && v >= it.top && v < it.bottom } ?: return
        val pu = cell.pageToPx.mapX(page.x, page.y)
        val pv = cell.pageToPx.mapY(page.x, page.y)
        assertTrue("$label z$z@$tp cell maps to ($pu,$pv) vs ($u,$v)", abs(pu - u) <= cellTol && abs(pv - v) <= cellTol)
    }

    // ---------------------------------------------------------------- blank

    @Test
    fun blankPdfAndTheBlankRule() {
        val b = fx.obj("blank")
        assertEquals("blank", b.str("expectedFailure"))
        assertEquals(PdfRenderFailure.BLANK, PdfRenderFailure.fromCode(b.str("expectedFailure")))
        val bytes = PdfGeorefFixture.readBytes(b.str("file"))
        assertEquals(b.i("bytes"), bytes.size)
        assertEquals(b.str("sha256"), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val g = parseVp(bytes)
        val expected = PdfTileRenderFixture.sheetById("render_blank").georef
        for (p in expected.crop) {
            val a = expected.planeOf(p.x, p.y); val c = g.planeOf(p.x, p.y)
            assertTrue("blank plane at $p", abs(a.x - c.x) <= 0.01 && abs(a.y - c.y) <= 0.01)
        }
        // what the blank page rasterises to: transparent, white, and a 254 grey box
        val w = 40; val h = 30
        val px = IntArray(w * h) { 0 }
        for (y in 5 until 25) for (x in 5 until 35) px[y * w + x] = 0xFFFFFFFF.toInt()
        for (y in 10 until 20) for (x in 10 until 20) px[y * w + x] = 0xFFFEFEFE.toInt()
        assertTrue(PdfRenderRules.isBlank(px, w, h))
        // one 250 grey sample on the stride grid is a real mark
        px[12 * w + 12] = 0xFFFAFAFA.toInt()
        assertFalse(PdfRenderRules.isBlank(px, w, h))
        // off the stride grid it's not sampled
        px[12 * w + 12] = 0xFFFEFEFE.toInt(); px[13 * w + 13] = 0xFF000000.toInt()
        assertTrue(PdfRenderRules.isBlank(px, w, h))
        // a fully transparent dark pixel doesn't count
        px[12 * w + 12] = 0x00000000
        assertTrue(PdfRenderRules.isBlank(px, w, h))
    }

    private fun num(v: Double): String = if (v == floor(v)) v.toLong().toString() else v.toString()

    /** the /VP of our own uncompressed fixture PDFs, enough for GeoPdfGeoreferencer */
    private fun parseVp(bytes: ByteArray): com.tacmap.calibration.PdfGeoreference {
        val text = String(bytes, Charsets.ISO_8859_1)
        fun arr(key: String, src: String = text): List<Double> =
            Regex("/$key \\[([^\\]]*)\\]").find(src)!!.groupValues[1].trim().split(Regex("\\s+")).map { it.toDouble() }
        val vp = text.substring(text.indexOf("/VP"))
        val wkt = Regex("/WKT \\((.*?)\\) >>").find(vp)!!.groupValues[1]
        val rotate = Regex("/Rotate (\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
        val crop = if (text.contains("/CropBox")) arr("CropBox") else null
        val page = GeoPdfPageData(
            mediaBox = arr("MediaBox"),
            cropBox = crop,
            rotate = rotate,
            viewports = listOf(
                GeoPdfViewportData(
                    name = Regex("/Name \\(([^)]*)\\)").find(vp)?.groupValues?.get(1),
                    bbox = arr("BBox", vp), gpts = arr("GPTS", vp), lpts = arr("LPTS", vp),
                    bounds = runCatching { arr("Bounds", vp) }.getOrNull(), gcsWkt = wkt,
                ),
            ),
        )
        val result = GeoPdfGeoreferencer.build(page)
        assertTrue("parse: $result", result is GeoPdfGeorefResult.Georeferenced)
        return (result as GeoPdfGeorefResult.Georeferenced).georef
    }
}
