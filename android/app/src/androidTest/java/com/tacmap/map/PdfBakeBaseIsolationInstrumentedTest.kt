package com.tacmap.map

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatums
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.PlaneAffine
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.pdf.PdfRenderException
import com.tacmap.map.render.pdf.PdfRenderExecutor
import com.tacmap.map.render.pdf.PdfRenderFailure
import com.tacmap.map.render.pdf.PdfRenderSessions
import com.tacmap.map.render.pdf.PdfRenderStatus
import com.tacmap.map.render.pdf.PdfTileSource
import com.tacmap.map.render.pdf.TileCoverage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * AND-R2-1: the bake / estimate base raster is its own attempt. The live source never adopts
 * it (in flight or done), so a bake's failure isn't in the live G2 run and a bake's success
 * doesn't make the live source ready or replan. The other way round the bake may read a
 * finished live raster, which changes nothing on the live side
 */
@RunWith(AndroidJUnit4::class)
class PdfBakeBaseIsolationInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context.applicationContext as Application
    private val dir = File(context.cacheDir, "bakeiso-${System.nanoTime()}")
    private val made = ArrayList<PdfTileSource>()

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(context)
        PdfRenderSessions.init(context)
        PdfRenderExecutor.foreground = true
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        made.forEach(::disposePdfTileSource)
        dir.deleteRecursively()
    }

    private fun sheet(name: String, inked: Boolean): File {
        val file = File(dir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 400f))
            doc.addPage(page)
            if (inked) PDPageContentStream(doc, page).use { ink ->
                ink.setStrokingColor(0.9f, 0.1f, 0.1f)
                ink.setLineWidth(1.5f)
                for (k in 0..12) {
                    ink.moveTo(k * 50f, 0f); ink.lineTo(k * 50f, 400f)
                    ink.moveTo(0f, k * 33f); ink.lineTo(600f, k * 33f)
                }
                ink.stroke()
            }
            doc.save(file)
        }
        return file
    }

    private fun pdf(file: File) = PdfMapSource(
        uri = Uri.fromFile(file),
        displayName = "iso",
        kind = MapSourceKind.CALIBRATED_PDF,
        calibration = Calibration.Parsed(
            PdfGeoreference(
                page = 0,
                crs = GeoCrs.Geographic,
                datum = GeoDatums.WGS84,
                affine = PlaneAffine(0.5 / 600.0, 0.0, 150.0, 0.0, 0.5 / 400.0, -34.0),
                crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
                origin = GeorefOrigin.FIDUCIARIES,
            ),
        ),
        geometry = PdfPageGeometry(PdfBox(0.0, 0.0, 600.0, 400.0), null, 0, 600, 400),
    )

    private fun pair(file: File): Pair<PdfTileSource, PdfTileSource> {
        val src = pdf(file)
        val live = createPdfTileSource(app, src, 256, "live-${System.nanoTime()}", null, { true })
        val bake = createPdfTileSource(app, src, 256, "bake-${System.nanoTime()}", null, { true }, forBake = true)
        made += live; made += bake
        assertSame("one session for both", live.session, bake.session)
        return live to bake
    }

    private fun tileAt(s: PdfTileSource, z: Int): TileIndex {
        val l = s.footprint.level(z)
        val i = (0 until l.count).first { l.coverage[it] != TileCoverage.OUTSIDE }
        return TileIndex(z, l.xs[i], l.ys[i])
    }

    private fun want(s: PdfTileSource, t: TileIndex) {
        val n = Math.scalb(1.0, t.z)
        s.onWanted(listOf(t), t.z, (t.x + 0.5) / n, (t.y + 0.5) / n)
    }

    @Test
    fun aBakeSuccessLeavesTheLiveSourceAlone() = runBlocking<Unit> {
        val (live, bake) = pair(sheet("ok.pdf", inked = true))
        val session = live.session
        val raster = bake.awaitBaseForBake()
        assertEquals(1, session.bakeBaseStarts.get())
        assertEquals(0, session.liveBaseStarts.get())
        // nothing moved on the live side: not ready, no replan, no fallback level, no adopted raster
        delay(200)
        assertEquals(PdfRenderStatus.Preparing, live.status.value)
        assertEquals(0, live.replanTicks.value)
        assertNull(session.readyBase(live.basePlan))
        assertNull(live.fallbackZoom(live.baseMaxZoom + 1))
        val t = tileAt(live, live.baseMaxZoom)
        assertNull("live never borrows the bake's raster", live.loadTile(t))

        // the live source's own attempt is what makes it ready
        want(live, t)
        assertEquals(1, session.liveBaseStarts.get())
        withTimeout(15_000) { live.status.first { it == PdfRenderStatus.Ready } }
        withTimeout(15_000) { live.replanTicks.first { it == 1 } }
        assertTrue(live.loadTile(t) != null)

        // and from here the bake reads the finished live raster instead of keeping its own
        val again = bake.awaitBaseForBake()
        assertSame(session.readyBase(live.basePlan), again)
        assertEquals(1, session.bakeBaseStarts.get())
        assertTrue(raster !== again)
        // R3-1: the live one landing dropped the finished bake copy, without recycling it under
        // whoever still had it in hand
        assertEquals(1, session.heldBaseRasters())
        assertTrue(raster.levels.none { it.isRecycled })
    }

    @Test
    fun aBakeAttemptInFlightIsNeverAdoptedByTheLiveSource() = runBlocking<Unit> {
        val (live, bake) = pair(sheet("inflight.pdf", inked = true))
        val session = live.session
        // undispatched: the bake's request is made right here, before the live one
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { bake.awaitBaseForBake() }
        val t = tileAt(live, live.baseMaxZoom)
        want(live, t)
        // two attempts, the live wait sits on its own one at the visible band
        assertEquals(1, session.liveBaseStarts.get())
        assertEquals(1, session.bakeBaseStarts.get())
        pending.await()
        withTimeout(15_000) { live.status.first { it == PdfRenderStatus.Ready } }
        assertEquals(1, session.heldBaseRasters())
    }

    /** holds the pdfium thread until [release], so the test can line work up behind it */
    private suspend fun gate(): Pair<kotlinx.coroutines.Deferred<Unit>, java.util.concurrent.CountDownLatch> {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val job = PdfRenderSessions.scope.async {
            PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE) {
                started.countDown()
                release.await(20, java.util.concurrent.TimeUnit.SECONDS)
                Unit
            }
        }
        assertTrue("pdfium thread picked the gate up", started.await(10, java.util.concurrent.TimeUnit.SECONDS))
        return job to release
    }

    @Test
    fun aQueuedBakeTakesTheLiveRasterThatLandedWhileItWaited() = runBlocking<Unit> {
        val (live, bake) = pair(sheet("queued.pdf", inked = true))
        val session = live.session
        val (held, release) = gate()
        // bake asks first and sits queued behind the gate
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { bake.awaitBaseForBake() }
        assertEquals(1, session.bakeBaseStarts.get())
        // the live side lands meanwhile (the import probe handover is the instant way to get there)
        val w = live.basePlan.mips[0][0]
        val h = live.basePlan.mips[0][1]
        val landed = com.tacmap.map.render.pdf.PdfBaseRaster(
            live.basePlan,
            com.tacmap.map.render.pdf.PdfTileRenderer.buildMips(android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888), live.basePlan),
        )
        session.adoptBase(landed)
        release.countDown()
        held.await()
        // when the bake's block finally ran it looked again and drew nothing
        assertSame(landed, withTimeout(15_000) { pending.await() })
        assertEquals(1, session.bakeLiveReuses.get())
        assertEquals(1, session.heldBaseRasters())
        assertSame(landed, bake.awaitBaseForBake())
        assertEquals(1, session.bakeBaseStarts.get())
    }

    @Test
    fun aLiveBaseFinishingFirstLeavesOneFullRaster() = runBlocking<Unit> {
        val (live, bake) = pair(sheet("race.pdf", inked = true))
        val session = live.session
        val (held, release) = gate()
        // both queued: bake asked first, the live one goes ahead of it on band
        val pending = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { bake.awaitBaseForBake() }
        val t = tileAt(live, live.baseMaxZoom)
        want(live, t)
        assertEquals(1, session.liveBaseStarts.get())
        assertEquals(1, session.bakeBaseStarts.get())
        release.countDown()
        held.await()
        withTimeout(15_000) { live.status.first { it == PdfRenderStatus.Ready } }
        val got = withTimeout(15_000) { pending.await() }
        // whichever of the two landed second, only the live raster stays held. the live
        // landing drops the bake copy a hair after it flips Ready, hence the short poll
        withTimeout(5_000) { while (session.heldBaseRasters() != 1) delay(10) }
        val ready = session.readyBase(live.basePlan)
        assertTrue(ready != null)
        if (got === ready) assertEquals(1, session.bakeLiveReuses.get())
        assertSame(ready, bake.awaitBaseForBake())
        assertEquals(1, session.bakeBaseStarts.get())
        // and the live side never noticed: its own attempt, one replan, tiles from its raster
        assertEquals(1, session.liveBaseStarts.get())
        withTimeout(15_000) { live.replanTicks.first { it == 1 } }
        assertTrue(live.loadTile(t) != null)
    }

    @Test
    fun aBakeFailureIsntCountedAgainstTheLiveSource() = runBlocking<Unit> {
        val (live, bake) = pair(sheet("blank.pdf", inked = false))
        val session = live.session
        val err = runCatching { bake.awaitBaseForBake() }.exceptionOrNull()
        assertEquals(PdfRenderFailure.BLANK, (err as? PdfRenderException)?.failure)
        delay(200)
        // no G2 effect: the live source hasn't failed and hasn't started anything
        assertEquals(PdfRenderStatus.Preparing, live.status.value)
        assertEquals(0, session.liveBaseStarts.get())

        // its own attempt is the one that counts
        want(live, tileAt(live, live.baseMaxZoom))
        assertEquals(PdfRenderStatus.Failed(PdfRenderFailure.BLANK), withTimeout(15_000) { live.status.first { it is PdfRenderStatus.Failed } })
        assertEquals(1, session.liveBaseStarts.get())

        // and a failed live attempt doesn't hand the bake a dead deferred: it tries its own again
        assertEquals(PdfRenderFailure.BLANK, (runCatching { bake.awaitBaseForBake() }.exceptionOrNull() as? PdfRenderException)?.failure)
        assertEquals(2, session.bakeBaseStarts.get())
    }
}
