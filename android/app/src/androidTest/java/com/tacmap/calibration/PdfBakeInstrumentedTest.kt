package com.tacmap.calibration

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tacmap.map.createPdfTileSource
import com.tacmap.map.disposePdfTileSource
import com.tacmap.map.render.pdf.PdfBakePlan
import com.tacmap.map.render.pdf.PdfTileRenderFixture
import com.tacmap.calibration.PdfGeorefFixture.obj
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.map.render.pdf.PdfRenderSessions
import com.tacmap.map.render.pdf.TileJob
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tacmap.util.DataKey
import com.tacmap.util.MissionKeyUnlockRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.log10

/**
 * Generate Offline Tiles on the device (WP2 contract J): Cancel leaves no .partial
 * and publishes nothing, and a baked WebP tile stays close to the live render it
 * replaces (PSNR logged, floor below).
 */
@RunWith(AndroidJUnit4::class)
class PdfBakeInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application
    // the library side of the publish, the app's is MapViewModel
    private val recorder = RecordingBakeRecorder()
    private lateinit var pdf: File

    // a paused MainActivity earlier in the run leaves the key relocked, and a bake now waits for it
    @get:Rule val missionKey = MissionKeyUnlockRule()

    /** what MapViewModel's attach comes down to: a sealed write, refused behind the relocked key */
    private class SealedWriteRecorder : PdfBakeRecorder {
        @Volatile var attached: PersistedPdfBake? = null
        @Volatile var attempts = 0

        override fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach {
            attempts++
            return try {
                DataKey.key().fill(0)
                attached = bake
                PdfBakeAttach.Attached
            } catch (e: DataKey.LockedException) {
                PdfBakeAttach.WriteFailed(e)
            }
        }

        override fun detach(entryId: String?, bake: PersistedPdfBake): Boolean {
            if (attached != bake) return false
            attached = null
            return true
        }
    }

    @Before
    fun setUp() {
        val testAssets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        PdfGeorefFixture.readBytes = { name -> testAssets.open(name).use { it.readBytes() } }
        PDFBoxResourceLoader.init(context)
        PdfRenderSessions.init(context)
        PdfRenderExecutor.foreground = true
        pdf = inkedPdf()
    }

    @After
    fun tearDown() {
        pdf.delete()
    }

    // a 600 x 400 pt page with a coloured grid and some text-ish strokes, so WebP has edges to chew on
    private fun inkedPdf(): File {
        val dir = File(context.filesDir, "pdf_maps").apply { mkdirs() }
        val file = File(dir, "bake-inst-${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            val page = PDPage(PDRectangle(600f, 400f))
            document.addPage(page)
            PDPageContentStream(document, page).use { ink ->
                ink.setStrokingColor(0.9f, 0.1f, 0.1f)
                ink.setLineWidth(1.5f)
                for (k in 0..12) {
                    ink.moveTo(k * 50f, 0f); ink.lineTo(k * 50f, 400f)
                    ink.moveTo(0f, k * 33f); ink.lineTo(600f, k * 33f)
                }
                ink.stroke()
                ink.setStrokingColor(0.1f, 0.1f, 0.1f)
                ink.setLineWidth(0.6f)
                for (k in 0..40) {
                    ink.moveTo(20f + k * 13f, 30f); ink.lineTo(30f + k * 13f, 370f)
                }
                ink.stroke()
            }
            document.save(file)
        }
        return file
    }

    private fun source(file: File): PdfMapSource {
        // 600 x 400 pt laid on a 0.5 x 0.5 deg box, small enough to bake quickly at z12
        val georef = PdfGeoreference(
            page = 0,
            crs = GeoCrs.Geographic,
            datum = GeoDatums.WGS84,
            affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
            crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
            origin = GeorefOrigin.FIDUCIARIES,
        )
        return PdfMapSource(
            uri = Uri.fromFile(file),
            displayName = file.nameWithoutExtension,
            kind = MapSourceKind.CALIBRATED_PDF,
            calibration = Calibration.Parsed(georef),
            geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
        )
    }

    @Test
    fun cancelMidBakeLeavesNoPartialAndPublishesNothing() = runBlocking<Unit> {
        val src = source(pdf)
        val publish = File(context.filesDir, PdfBaker.PUBLISH_DIR).apply { mkdirs() }
        val before = publish.list()?.toSet().orEmpty()
        val tiles = createPdfTileSource(app, src, 256, "bake-cancel", null, { true }, forBake = true)
        try {
            val json = src.placement!!.canonicalJson()
            val key = PdfBakePlan.bakeKey(json, 256)
            var sawPartial = false
            var sawJournal: List<String>? = null
            var progressed = 0
            val job = CoroutineScope(Dispatchers.Default).launch {
                val self = this
                PdfBaker.bake(context, tiles, src, 13, key, recorder, onProgress = { done, _ ->
                    if (done > 0 && progressed == 0) {
                        progressed = done
                        val work = PdfBaker.workDir(context).list().orEmpty().toList()
                        sawPartial = work.any { it.endsWith(".partial") }
                        // R2-S3: mid batch, nothing journal-like sits next to it
                        sawJournal = work.filter { it.endsWith("-journal") || it.endsWith("-wal") || it.endsWith("-shm") }
                        // pull the plug right after the first job lands
                        self.cancel()
                    }
                })
            }
            job.join()
            assertTrue("bake never made progress", progressed > 0)
            assertTrue("cancelled, not finished", job.isCancelled)
            assertTrue("the work file should exist while it runs", sawPartial)
            assertEquals("no *.partial-journal mid bake", emptyList<String>(), sawJournal)
            assertTrue("work dir: ${PdfBaker.workDir(context).list()?.toList()}", PdfBaker.workDir(context).list().isNullOrEmpty())
            assertEquals(before, publish.list()?.toSet().orEmpty())
            assertNull(recorder.attached)
            assertTrue("the PDF itself stays", pdf.isFile)
        } finally {
            disposePdfTileSource(tiles)
        }
    }

    /**
     * Starts a small bake with the key already relocked (Home, power button or a call mid bake)
     * and waits till every tile is written, then a bit more for the publish it would have tried
     */
    private suspend fun bakeBehindTheLock(src: PdfMapSource, tiles: com.tacmap.map.render.pdf.PdfTileSource, recorder: PdfBakeRecorder): Deferred<PersistedPdfBake> {
        val key = PdfBakePlan.bakeKey(src.placement!!.canonicalJson(), 256)
        DataKey.lock()
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        val total = java.util.concurrent.atomic.AtomicInteger(-1)
        val run = CoroutineScope(Dispatchers.Default).async {
            PdfBaker.bake(context, tiles, src, 10, key, recorder, onProgress = { d, t -> done.set(d); total.set(t) })
        }
        fun finished() = total.get() > 0 && done.get() >= total.get()
        val end = System.nanoTime() + 120_000_000_000L
        while (!finished() && !run.isCompleted && System.nanoTime() < end) delay(50)
        assertTrue("bake never got through its tiles ($done/$total)", finished())
        delay(1_500)
        return run
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun assertStillWaiting(run: Deferred<PersistedPdfBake>) {
        if (run.isCompleted) {
            val outcome = run.getCompletionExceptionOrNull() ?: run.getCompleted()
            throw AssertionError("finished behind the lock instead of waiting: $outcome")
        }
    }

    private fun publishedBakes(): Set<String> =
        File(context.filesDir, PdfBaker.PUBLISH_DIR).list().orEmpty().filter { it.startsWith("tacmap-bake-") }.toSet()

    /**
     * The bake finishes while the app is in the background. Attaching then can only fail
     * behind the relocked key, which used to delete the finished file and report writeFailed
     * (WP2 E says the bake carries on in the background). It waits for the foreground unlock
     * and publishes then
     */
    @Test
    fun aBakeThatFinishesBehindThePauseLockPublishesAfterTheUnlock() = runBlocking<Unit> {
        val src = source(pdf)
        val tiles = createPdfTileSource(app, src, 256, "bake-relocked", null, { true }, forBake = true)
        val recorder = SealedWriteRecorder()
        var out: File? = null
        try {
            val run = bakeBehindTheLock(src, tiles, recorder)
            assertStillWaiting(run)
            assertEquals("nothing tried to write behind the lock", 0, recorder.attempts)
            assertTrue("the finished file waits in the work dir", PdfBaker.workDir(context).list().orEmpty().any { it.endsWith(".partial") })
            // back to the front
            withContext(Dispatchers.Main) { DataKey.unlock() }
            val bake = withTimeout(30_000) { run.await() }
            out = File(File(context.filesDir, PdfBaker.PUBLISH_DIR), bake.fileName)
            assertEquals(bake, recorder.attached)
            assertEquals(1, recorder.attempts)
            assertTrue("published, not deleted", out.isFile)
            assertTrue(PdfBaker.workDir(context).list().isNullOrEmpty())
        } finally {
            DataKey.unlock()
            disposePdfTileSource(tiles)
            out?.let(::deleteMBTilesArtifacts)
        }
    }

    /** a cancel while it waits for the unlock still cleans up like any cancel and records nothing */
    @Test
    fun aCancelWhileWaitingForTheUnlockLeavesNothingBehind() = runBlocking<Unit> {
        val src = source(pdf)
        val tiles = createPdfTileSource(app, src, 256, "bake-relocked-cancel", null, { true }, forBake = true)
        val recorder = SealedWriteRecorder()
        val before = publishedBakes()
        try {
            val run = bakeBehindTheLock(src, tiles, recorder)
            run.cancel()
            runCatching { run.await() }
            assertTrue("cancelled, not finished", run.isCancelled)
            assertEquals(0, recorder.attempts)
            assertTrue("work dir: ${PdfBaker.workDir(context).list()?.toList()}", PdfBaker.workDir(context).list().isNullOrEmpty())
            assertEquals(before, publishedBakes())
        } finally {
            DataKey.unlock()
            disposePdfTileSource(tiles)
        }
    }

    /**
     * Another pause gets in between the unlock and the attach (the file is already in
     * offline_tiles by then). Main is held here so the attach can only run after the relock,
     * and the publish has to keep the file and wait for the next unlock instead of failing
     */
    @Test
    fun aRelockBetweenTheUnlockAndTheAttachWaitsForTheNextUnlock() = runBlocking<Unit> {
        val src = source(pdf)
        val tiles = createPdfTileSource(app, src, 256, "bake-relocked-race", null, { true }, forBake = true)
        val recorder = SealedWriteRecorder()
        val before = publishedBakes()
        var out: File? = null
        try {
            val run = bakeBehindTheLock(src, tiles, recorder)
            val moved = withContext(Dispatchers.Main) {
                DataKey.unlock()
                // main stays busy till the publish has moved the file in, so its attach queues behind this
                val end = System.nanoTime() + 30_000_000_000L
                while (publishedBakes() == before && System.nanoTime() < end) Thread.sleep(5)
                DataKey.lock()
                publishedBakes() - before
            }
            assertEquals("publish never moved the file in", 1, moved.size)
            delay(2_500)
            assertStillWaiting(run)
            assertNull(recorder.attached)
            assertEquals("the in flight file stays put", moved, publishedBakes() - before)
            withContext(Dispatchers.Main) { DataKey.unlock() }
            val bake = withTimeout(30_000) { run.await() }
            out = File(File(context.filesDir, PdfBaker.PUBLISH_DIR), bake.fileName)
            assertEquals(moved.single(), bake.fileName)
            assertEquals(bake, recorder.attached)
            assertTrue(out.isFile)
        } finally {
            DataKey.unlock()
            disposePdfTileSource(tiles)
            out?.let(::deleteMBTilesArtifacts)
        }
    }

    /**
     * A cancel that lands while the writer is still being made (Delete Map the instant a bake
     * starts): withContext throws on the way back but the .partial is already on disk with an
     * open connection. It has to go too, not sit there till the next launch (OD3-R3-1 check)
     */
    @Test
    fun cancelWhileTheWriterIsBeingMadeLeavesNoPartial() = runBlocking<Unit> {
        val src = source(pdf)
        val tiles = createPdfTileSource(app, src, 256, "bake-early-cancel", null, { true }, forBake = true)
        try {
            val json = src.placement!!.canonicalJson()
            val key = PdfBakePlan.bakeKey(json, 256)
            val work = PdfBaker.workDir(context)
            repeat(10) { round ->
                val job = CoroutineScope(Dispatchers.Default).launch {
                    PdfBaker.bake(context, tiles, src, 13, key, recorder, onProgress = { _, _ -> })
                }
                // the moment the file shows up, ie mid create more often than not
                val end = System.nanoTime() + 5_000_000_000L
                while (work.list().orEmpty().none { it.endsWith(".partial") } && !job.isCompleted && System.nanoTime() < end) Thread.yield()
                job.cancel()
                job.join()
                assertTrue("round $round, work dir: ${work.list()?.toList()}", work.list().isNullOrEmpty())
            }
            assertNull(recorder.attached)
        } finally {
            disposePdfTileSource(tiles)
        }
    }

    /**
     * R2-S3 on the real sqlite: the writer's journal_mode took (OFF or MEMORY, read back), so an
     * open batch with a few hundred KB in it leaves no -journal / -wal next to the .partial
     */
    @Test
    fun writerLeavesNoJournalNextToThePartialMidBatch() {
        val work = PdfBaker.workDir(context).apply { mkdirs() }
        val partial = File(work, "journal-check-${System.nanoTime()}.mbtiles.partial")
        var err: Throwable? = null
        val w = MBTilesWriter.create(partial.path) { err = it }
        assertTrue("writer refused: $err", w != null)
        try {
            w!!.beginBatch()
            repeat(120) { w.putTile(14, it, 7, ByteArray(4096) { b -> (b * 31 + it).toByte() }) }
            assertTrue(!w.hadError)
            val beside = work.list().orEmpty().filter { it.startsWith(partial.name) && it != partial.name }
            assertEquals("sidecars mid batch", emptyList<String>(), beside)
            w.commitBatch()
            w.beginBatch()
            repeat(10) { w.putTile(15, it, 7, ByteArray(2048)) }
            assertEquals(emptyList<String>(), work.list().orEmpty().filter { it.startsWith(partial.name) && it != partial.name })
            w.commitBatch()
        } finally {
            w?.abort()
            deleteMBTilesArtifacts(partial)
        }
    }

    /**
     * J3 gate, r1 D1: exactly the fixture's psnr tiles (rot5_iso and the dense linework sheet,
     * 768 and 512 px, z = baseMaxZoom..D, the middle INSIDE tile per z), baked vs live. The
     * source runs the normal budget like the fixture, so its baseMaxZoom is the fixture's
     */
    @Test
    fun psnrGateOnTheFixtureTiles() = runBlocking<Unit> {
        val p = PdfTileRenderFixture.root.obj("psnr")
        assertEquals(PdfBakePlan.PSNR_GATE_DB, p["gateDb"]!!.jsonPrimitive.double, 0.0)
        val assets = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
        val dir = File(context.filesDir, "pdf_maps").apply { mkdirs() }
        val report = ArrayList<String>()
        var worst = Double.POSITIVE_INFINITY
        for (sh in p["sheets"]!!.jsonArray.map { it.jsonObject }) {
            val name = sh["sheet"]!!.jsonPrimitive.content
            val sheetFile = File(dir, "bake-psnr-$name-${System.nanoTime()}.pdf")
            sheetFile.writeBytes(assets.open(sh["file"]!!.jsonPrimitive.content).use { it.readBytes() })
            try {
                val page = PdfDocumentInspector.inspect(context, sheetFile)
                val georef = (page.georeference() as GeoPdfGeorefResult.Georeferenced).georef
                val src = PdfMapSource.geoPdf(Uri.fromFile(sheetFile), name, georef, page.geometry)
                for (check in sh["checks"]!!.jsonArray.map { it.jsonObject }) {
                    val tilePx = check["tilePx"]!!.jsonPrimitive.int
                    val zooms = check["zooms"]!!.jsonArray.map { it.jsonObject }
                    val worstHere = bakeAndCompare(src, tilePx, check["detailZoom"]!!.jsonPrimitive.int, check["baseMaxZoom"]!!.jsonPrimitive.int, zooms, report)
                    worst = minOf(worst, worstHere)
                    val vector = zooms.count { it["path"]!!.jsonPrimitive.content == "vector" }
                    assertEquals("$name@$tilePx vectorZooms", check["vectorZooms"]!!.jsonPrimitive.int, vector)
                    assertTrue("$name@$tilePx checks a vector zoom", vector >= 1)
                }
            } finally {
                sheetFile.delete()
            }
        }
        android.util.Log.i("PdfBakeInstrumentedTest", "J3 gate min ${"%.2f".format(worst)} dB: $report")
        assertTrue("J3 PSNR $worst < ${PdfBakePlan.PSNR_GATE_DB}: $report", worst >= PdfBakePlan.PSNR_GATE_DB)
    }

    /**
     * OD-F3: what the encoder hands the MBTiles is the simple lossless layout (no VP8X / ICC
     * wrapper), opaque pixels come back exactly and fully transparent ones as 0,0,0,0
     */
    @Test
    fun bakeEncoderIsExactSimpleLosslessWebp() {
        val w = 300; val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            when {
                x < 100 -> 0x00000000
                x < 110 -> Color.argb(128, 200, 30, 30)
                else -> Color.argb(255, (x * 7) and 0xff, (y * 13) and 0xff, (x + y) and 0xff)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        val bytes = PdfBaker.encode(bmp)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("simple lossless, VP8L straight after the header", "VP8L", String(bytes, 12, 4, Charsets.US_ASCII))
        val back = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPremultiplied = false })
        val out = IntArray(w * h).also { back.getPixels(it, 0, w, 0, 0, w, h) }
        val src = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        for (i in out.indices) {
            val a = Color.alpha(src[i])
            when (a) {
                0 -> assertEquals("transparent at $i", 0, out[i])
                255 -> assertEquals("opaque at $i", src[i], out[i])
                else -> assertEquals("alpha at $i", a, Color.alpha(out[i]))
            }
        }
        bmp.recycle(); back.recycle()
    }

    /**
     * The on-device bake check: the whole USGS sheet at this device's tilePx up to the default
     * maxZoom, through the same PdfBaker the Generate button runs. Logs wall time, tiles and
     * file size, and leaves a copy in files/dbg/usgs_bake.mbtiles to pull. Skipped without
     * files/dbg/usgs.pdf
     */
    @Test
    fun usgsFullBakeWallTimeAndSize() = runBlocking<Unit> {
        val pushed = File(context.filesDir, "dbg/usgs.pdf")
        org.junit.Assume.assumeTrue("push samples/USGS_SF_North.pdf to files/dbg/usgs.pdf", pushed.isFile)
        val file = File(File(context.filesDir, "pdf_maps").apply { mkdirs() }, "bake-usgs-${System.nanoTime()}.pdf")
        pushed.copyTo(file)
        var out: File? = null
        try {
            val page = PdfDocumentInspector.inspect(context, file)
            val georef = (page.georeference() as GeoPdfGeorefResult.Georeferenced).georef
            val src = PdfMapSource.geoPdf(Uri.fromFile(file), "usgs", georef, page.geometry)
            val tilePx = com.tacmap.map.render.pdf.PdfZoomPolicy.tilePx(context.resources.displayMetrics.density.toDouble())
            val tiles = createPdfTileSource(app, src, tilePx, "bake-usgs-full-${System.nanoTime()}", null, { true }, forBake = true)
            try {
                val opts = PdfBakePlan.options(tiles.policy.detailZoom) { z, limit -> tiles.footprint.count(z, limit) }
                val default = opts.default!!
                val json = georef.canonicalJson()
                val t0 = android.os.SystemClock.elapsedRealtime()
                var published: PersistedPdfBake? = null
                val bake = PdfBaker.bake(
                    context, tiles, src, default.maxZoom, PdfBakePlan.bakeKey(json, tilePx), recorder,
                    onPublished = { published = it },
                    onProgress = { _, _ -> },
                )
                val ms = android.os.SystemClock.elapsedRealtime() - t0
                out = File(File(context.filesDir, PdfBaker.PUBLISH_DIR), bake.fileName)
                assertEquals(bake, published)
                assertEquals(PdfBakePlan.MBTILES_NAME, metadata(out, "name"))
                val n = SQLiteDatabase.openDatabase(out.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT COUNT(*) FROM tiles", null).use { c -> c.moveToFirst(); c.getInt(0) }
                }
                assertEquals(default.tiles, n)
                out.copyTo(File(context.filesDir, "dbg/usgs_bake.mbtiles"), overwrite = true)
                android.util.Log.i(
                    "PdfBakeInstrumentedTest",
                    "USGS full bake tilePx=$tilePx z0..${default.maxZoom} tiles=$n wallMs=$ms bytes=${out.length()} heavy=${tiles.heavy}",
                )
            } finally {
                disposePdfTileSource(tiles)
            }
        } finally {
            out?.delete()
            file.delete()
        }
    }

    /**
     * J3 numbers for the contract table: encoded bytes per tile on the real USGS sheet at
     * 768 px, default maxZoom (z15), the 3 J2 sample tiles plus the next 10 INSIDE tiles
     * by distance to the crop centre. Needs files/dbg/usgs.pdf pushed with run-as first
     * (docs/DEBUG_HOOKS.md), skipped otherwise. Leaves the 13 encoded tiles in
     * files/dbg/j3/ so they can be pulled and re-encoded on a desktop for comparison
     */
    @Test
    fun usgsBytesPerTileAt768() = runBlocking<Unit> {
        val file = File(context.filesDir, "dbg/usgs.pdf")
        org.junit.Assume.assumeTrue("push samples/USGS_SF_North.pdf to files/dbg/usgs.pdf", file.isFile)
        val page = PdfDocumentInspector.inspect(context, file)
        val georef = (page.georeference() as GeoPdfGeorefResult.Georeferenced).georef
        val src = PdfMapSource.geoPdf(Uri.fromFile(file), "usgs", georef, page.geometry)
        val tiles = createPdfTileSource(app, src, 768, "bake-usgs-${System.nanoTime()}", null, { true }, forBake = true)
        val dump = File(context.filesDir, "dbg/j3").apply { deleteRecursively(); mkdirs() }
        try {
            val opts = PdfBakePlan.options(tiles.policy.detailZoom) { z, limit -> tiles.footprint.count(z, limit) }
            val z = opts.default!!.maxZoom
            val level = tiles.footprint.level(z)
            val c = tiles.footprint.clipMean
            val ll = tiles.georef.toWGS84(c.x, c.y)!!
            val centre = com.tacmap.map.render.pdf.PdfBakeEstimate.centreTile(
                com.tacmap.map.render.pdf.MercatorWorld.x(ll.longitude), com.tacmap.map.render.pdf.MercatorWorld.y(ll.latitude), z,
            )!!
            val inside = (0 until level.count).filter { level.coverage[it] == com.tacmap.map.render.pdf.TileCoverage.INSIDE }
            fun d2(i: Int): Double {
                val dx = level.xs[i] + 0.5 - centre[0]; val dy = level.ys[i] + 0.5 - centre[1]
                return dx * dx + dy * dy
            }
            val ranked = inside.sortedWith(compareBy<Int>({ d2(it) }, { level.ys[it] }, { level.xs[it] })).take(13)
            val sel = com.tacmap.map.render.pdf.PdfBakeEstimate.samplePicks(level, centre)
            assertEquals(sel.map { it.x to it.y }, ranked.take(3).map { level.xs[it] to level.ys[it] })
            val sizes = ArrayList<Int>()
            val psnrs = ArrayList<Double>()
            var encodeMs = 0.0
            // the effort knob on its own, 3 rounds each so one slow call doesn't decide it
            val effortBytes = HashMap<Int, Long>()
            val effortMs = HashMap<Int, Double>()
            for (i in ranked) {
                val bmp = tiles.renderForBake(TileJob.single(z, level.xs[i], level.ys[i])).values.single()
                try {
                    for (round in 0 until 3) for (q in intArrayOf(85, 100)) {
                        val t1 = System.nanoTime()
                        val raw = java.io.ByteArrayOutputStream().use { o ->
                            bmp.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, q, o); o.toByteArray()
                        }
                        val n = PdfBaker.stripToSimpleLossless(raw).size
                        effortMs[q] = (effortMs[q] ?: 0.0) + (System.nanoTime() - t1) / 1e6
                        if (round == 0) effortBytes[q] = (effortBytes[q] ?: 0L) + n
                    }
                    val t0 = System.nanoTime()
                    val bytes = PdfBaker.encode(bmp)
                    encodeMs += (System.nanoTime() - t0) / 1e6
                    sizes += bytes.size
                    File(dump, "${z}_${level.xs[i]}_${level.ys[i]}.webp").writeBytes(bytes)
                    val back = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    psnrs += psnr(bmp, back)
                    back.recycle()
                } finally {
                    bmp.recycle()
                }
            }
            android.util.Log.i(
                "PdfBakeInstrumentedTest",
                "USGS z$z @768 n=${sizes.size} mean=${sizes.average()} max=${sizes.max()} " +
                    "encodeMs=${encodeMs / sizes.size} minPsnr=${psnrs.min()} per tile=$sizes | " +
                    effortMs.keys.sorted().joinToString { q ->
                        "q$q meanBytes=${effortBytes.getValue(q).toDouble() / sizes.size} ms=${effortMs.getValue(q) / (3 * sizes.size)}"
                    },
            )
            assertTrue(psnrs.min() >= PdfBakePlan.PSNR_GATE_DB)
        } finally {
            disposePdfTileSource(tiles)
        }
    }

    /**
     * Bake [src] at [tilePx] up to [maxZoom] with the normal budget, then PSNR baked vs live for
     * each fixture zoom. Also checks the MBTiles metadata name is the neutral one (F2)
     */
    private suspend fun bakeAndCompare(
        src: PdfMapSource,
        tilePx: Int,
        maxZoom: Int,
        fixtureBaseMaxZoom: Int,
        zooms: List<kotlinx.serialization.json.JsonObject>,
        report: MutableList<String>,
    ): Double {
        val georef = src.placement!!
        val footprint = com.tacmap.map.render.pdf.PdfFootprint.build(georef, src.geometry.visibleBox)
        val policy = com.tacmap.map.render.pdf.PdfZoomPolicy.of(georef, footprint)
        val tiles = com.tacmap.map.render.pdf.PdfTileSource(
            session = PdfRenderSessions.acquire(File(src.uri.path!!), georef.page),
            georef = georef,
            geometry = src.geometry,
            footprint = footprint,
            policy = policy,
            tilePx = tilePx,
            budgetPx = com.tacmap.map.render.pdf.PdfZoomPolicy.BUDGET_PX,
            guard = null,
            cacheKey = "bake-psnr-${System.nanoTime()}",
            forBake = true,
        )
        var out: File? = null
        try {
            assertEquals("${src.displayName}@$tilePx baseMaxZoom", fixtureBaseMaxZoom, tiles.baseMaxZoom)
            val json = georef.canonicalJson()
            val key = PdfBakePlan.bakeKey(json, tilePx)
            val bake = PdfBaker.bake(context, tiles, src, maxZoom, key, recorder, onProgress = { _, _ -> })
            out = File(File(context.filesDir, PdfBaker.PUBLISH_DIR), bake.fileName)
            assertEquals(bake, recorder.attached)
            assertEquals(PdfBakePlan.MBTILES_NAME, metadata(out, "name"))
            var worst = Double.POSITIVE_INFINITY
            for (e in zooms) {
                val z = e["z"]!!.jsonPrimitive.int
                val t = e["tile"]!!.jsonArray.map { it.jsonPrimitive.int }
                assertEquals("z$z path", if (z <= tiles.baseMaxZoom) "raster" else "vector", e["path"]!!.jsonPrimitive.content)
                val baked = storedTile(out, z, t[0], t[1]) ?: throw AssertionError("z$z/${t[0]}/${t[1]} not in the bake")
                val live = tiles.renderForBake(TileJob.single(z, t[0], t[1])).values.single()
                try {
                    val p = psnr(live, baked)
                    report += "${src.displayName}@$tilePx z$z ${"%.1f".format(p)}"
                    worst = minOf(worst, p)
                } finally {
                    live.recycle(); baked.recycle()
                }
            }
            return worst
        } finally {
            disposePdfTileSource(tiles)
            out?.delete()
        }
    }

    private fun metadata(file: File, key: String): String? {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT value FROM metadata WHERE name=?", arrayOf(key)).use { c ->
                return if (c.moveToFirst()) c.getString(0) else null
            }
        } finally {
            db.close()
        }
    }

    private fun storedTile(file: File, z: Int, x: Int, y: Int): Bitmap? {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tms = (1 shl z) - 1 - y
            db.rawQuery(
                "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                arrayOf(z.toString(), x.toString(), tms.toString()),
            ).use { c ->
                if (!c.moveToFirst()) return null
                val bytes = c.getBlob(0)
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } finally {
            db.close()
        }
    }

    /** RGB PSNR over the opaque pixels */
    private fun psnr(a: Bitmap, b: Bitmap): Double {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var se = 0.0
        var n = 0L
        for (i in pa.indices) {
            if (Color.alpha(pa[i]) < 255) continue
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ((pa[i] shr shift) and 0xff) - ((pb[i] shr shift) and 0xff)
                se += d * d
                n++
            }
        }
        if (n == 0L) return 0.0
        val mse = se / n
        return if (mse == 0.0) 99.0 else 10.0 * log10(255.0 * 255.0 / mse)
    }

}
