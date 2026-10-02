package com.tacmap.map.render.pdf

import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.PdfBakeError
import com.tacmap.calibration.PdfBakeErrors
import com.tacmap.map.PdfGeorefLabel
import com.tacmap.map.pdfGeorefLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/** the WP2 round 1 rules that aren't fixture rows: staged region, S2 caps, error mapping, labels */
class PdfRenderRulesTest {

    @Test
    fun stagedRegionPadsByTwoOverRequiredAndNeverPassesTwiceTheJobPixels() {
        val clip = doubleArrayOf(0.0, 0.0, 1000.0, 800.0)
        val jobPx = 3L * 2 * 768 * 768
        // required 1.0 px/pt: pad 2 pt each side, cut to the clip bbox
        val r = PdfStagedPlan.region(doubleArrayOf(100.0, 100.0, 300.0, 200.0), 1.0, clip, jobPx)!!
        assertEquals(98.0, r.x0, 1e-12)
        assertEquals(98.0, r.y0, 1e-12)
        assertEquals(302.0, r.x1, 1e-12)
        assertEquals(202.0, r.y1, 1e-12)
        assertEquals(1.25, r.density, 1e-12)
        assertEquals(ceil(204 * 1.25).toInt(), r.width)
        assertEquals(ceil(104 * 1.25).toInt(), r.height)
        // the pad never reaches past the clip bbox
        val edge = PdfStagedPlan.region(doubleArrayOf(0.5, 0.5, 50.0, 50.0), 1.0, clip, jobPx)!!
        assertEquals(0.0, edge.x0, 0.0)
        assertEquals(0.0, edge.y0, 0.0)
        // a sweep of awkward sizes: the ceil can't push W x H past floor(2 x job px)
        for (w in listOf(997.3, 1234.567, 3001.01, 77.7)) for (h in listOf(13.1, 999.99, 2048.5)) {
            val box = doubleArrayOf(0.0, 0.0, w, h)
            val big = PdfStagedPlan.region(box, 50.0, box, 768L * 768)!!
            assertTrue("$w x $h -> ${big.width} x ${big.height}", big.width.toLong() * big.height <= floor(2.0 * 768 * 768).toLong())
            assertTrue(big.density <= 1.25 * 50.0)
        }
        // nothing left after the cut, no cells, a bad required: renderError (R5), never paper
        assertNull(PdfStagedPlan.region(doubleArrayOf(2000.0, 2000.0, 2100.0, 2100.0), 1.0, clip, jobPx))
        assertNull(PdfStagedPlan.region(null, 1.0, clip, jobPx))
        assertNull(PdfStagedPlan.region(doubleArrayOf(100.0, 100.0, 300.0, 200.0), 0.0, clip, jobPx))
        assertNull(PdfStagedPlan.region(doubleArrayOf(100.0, 100.0, 300.0, 200.0), Double.NaN, clip, jobPx))
    }

    @Test
    fun webpChunkStripKeepsTheVp8lBitstreamAndDropsTheIccWrapper() {
        // RIFF/WEBP + VP8X(10) + ICCP(3, padded) + VP8L(5, padded): what skia hands back
        fun le(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun chunk(tag: String, body: ByteArray) =
            tag.toByteArray() + le(body.size) + body + (if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        val vp8l = byteArrayOf(0x2f, 1, 2, 3, 4)
        val payload = "WEBP".toByteArray() + chunk("VP8X", ByteArray(10)) + chunk("ICCP", byteArrayOf(9, 9, 9)) + chunk("VP8L", vp8l)
        val wrapped = "RIFF".toByteArray() + le(payload.size) + payload
        val simple = com.tacmap.calibration.PdfBaker.stripToSimpleLossless(wrapped)
        val expectPayload = "WEBP".toByteArray() + chunk("VP8L", vp8l)
        assertEquals(("RIFF".toByteArray() + le(expectPayload.size) + expectPayload).toList(), simple.toList())
        // already simple, or anything we don't know: untouched
        assertTrue(simple.contentEquals(com.tacmap.calibration.PdfBaker.stripToSimpleLossless(simple)))
        val exif = "RIFF".toByteArray() + le(payload.size + 12) + payload + chunk("EXIF", ByteArray(4))
        assertTrue(exif.contentEquals(com.tacmap.calibration.PdfBaker.stripToSimpleLossless(exif)))
        val truncated = wrapped.copyOf(wrapped.size - 3)
        assertTrue(truncated.contentEquals(com.tacmap.calibration.PdfBaker.stripToSimpleLossless(truncated)))
    }

    @Test
    fun liveBaseRasterStartsOnceAndOnlyRetriesForARasterZoom() {
        // the R1 shape the live source runs on, the fixture pins the full sequences
        val t = PdfRenderRules.FailureTracker()
        val base = PdfRenderRules.BaseRasterLifecycle(12, t)
        assertTrue("first wanted starts it at any z", base.wanted(16))
        assertFalse(base.wanted(5))
        assertFalse(base.done(PdfRenderRules.BaseRasterLifecycle.Result.Failed(PdfRenderFailure.OUT_OF_MEMORY)))
        assertEquals(1, t.consecutiveFailures)
        assertFalse("never from above baseMaxZoom", base.wanted(13))
        assertTrue(base.wanted(12))
        assertTrue("ok asks for a re-plan", base.done(PdfRenderRules.BaseRasterLifecycle.Result.Ok))
        assertEquals(0, t.consecutiveFailures)
        assertFalse(base.wanted(3))
        assertEquals(2, base.attempts)
        // the import probe handed one over: never starts
        val adopted = PdfRenderRules.BaseRasterLifecycle(12, PdfRenderRules.FailureTracker(), alreadyReady = true)
        assertFalse(adopted.wanted(3))
    }

    @Test
    fun drawingMapLabelOnlyAfterPreparingFor300ms() = kotlinx.coroutines.runBlocking {
        val status = kotlinx.coroutines.flow.MutableStateFlow<PdfRenderStatus>(PdfRenderStatus.Preparing)
        val seen = java.util.Collections.synchronizedList(ArrayList<Boolean>())
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch { PdfRenderRules.preparingLabel(status) { seen += it } }
        kotlinx.coroutines.delay(PdfRenderRules.PREPARING_LABEL_DELAY_MS - 150)
        assertEquals("not yet", listOf(false), seen.toList())
        kotlinx.coroutines.delay(300)
        assertEquals(listOf(false, true), seen.toList())
        // ready before 300 ms on the next round: never shows
        status.value = PdfRenderStatus.Ready
        kotlinx.coroutines.delay(50)
        status.value = PdfRenderStatus.Preparing
        kotlinx.coroutines.delay(100)
        status.value = PdfRenderStatus.Failed(PdfRenderFailure.RENDER_ERROR)
        kotlinx.coroutines.delay(400)
        job.cancel()
        assertEquals(listOf(false, true, false, false, false), seen.toList())
    }

    @Test
    fun throwablesMapToTheirG2Reason() {
        assertEquals(PdfRenderFailure.OUT_OF_MEMORY, PdfRenderRules.reasonOf(OutOfMemoryError()))
        assertEquals(PdfRenderFailure.OUT_OF_MEMORY, PdfRenderRules.reasonOf(PdfRenderException(PdfRenderFailure.OUT_OF_MEMORY)))
        assertEquals(PdfRenderFailure.PAGE_GEOMETRY, PdfRenderRules.reasonOf(PdfRenderException(PdfRenderFailure.PAGE_GEOMETRY)))
        assertNull("unclassified counts as renderError", PdfRenderRules.reasonOf(IllegalStateException("boom")))
        // and an unclassified error really counts as renderError in the tracker
        val t = PdfRenderRules.FailureTracker()
        repeat(2) { assertNull(t.jobFailed(PdfRenderRules.JobPath.RASTER_SAMPLE, null)) }
        assertEquals(PdfRenderFailure.RENDER_ERROR, t.jobFailed(PdfRenderRules.JobPath.VECTOR, null))
    }

    @Test
    fun levelCountingStopsEarlyPastItsLimit() {
        // the 1:1M synthetic sheet at z14 has way more than 6000 tiles
        val sheet = PdfTileRenderFixture.sheetById("wide_tm_1m")
        val fp = sheet.footprint
        val t0 = System.nanoTime()
        val capped = fp.level(16, 6000)
        val ms = (System.nanoTime() - t0) / 1e6
        assertTrue(capped.truncated)
        assertTrue(capped.count > 6000)
        assertTrue("early exit took $ms ms", ms < 5_000)
        // under the limit it's the exact same level as an uncapped scan
        for (z in 0..8) {
            val a = fp.level(z)
            val b = fp.level(z, a.count)
            assertFalse(b.truncated)
            assertEquals(a.xs.toList(), b.xs.toList())
            assertEquals(a.ys.toList(), b.ys.toList())
            assertEquals(a.coverage.toList(), b.coverage.toList())
        }
        // cancellation gets polled while it scans
        var calls = 0
        val thrown = runCatching { fp.level(18, Int.MAX_VALUE) { ++calls < 3 } }.exceptionOrNull()
        assertTrue("$thrown", thrown is kotlinx.coroutines.CancellationException)
        // options() hands each level what's left of the 6000 and stops once past it
        val limits = ArrayList<Pair<Int, Int>>()
        val opts = PdfBakePlan.options(22) { z, limit -> limits += z to limit; fp.count(z, limit) }
        assertTrue(opts.tooLarge)
        assertTrue("stopped early: $limits", limits.last().first < 20)
        assertTrue(limits.zipWithNext().all { (a, b) -> b.second <= a.second })
    }

    @Test
    fun fullDiskMapsToNoSpaceEverythingElseWriteFailed() {
        assertEquals(PdfBakeError.NO_SPACE, PdfBakeErrors.classifyWrite(IOException("write failed: ENOSPC (No space left on device)")))
        assertEquals(PdfBakeError.NO_SPACE, PdfBakeErrors.classifyWrite(RuntimeException("x", IOException("No space left on device"))))
        assertEquals(PdfBakeError.NO_SPACE, PdfBakeErrors.classifyWrite(RuntimeException("database or disk is full (code 13 SQLITE_FULL)")))
        assertEquals(PdfBakeError.WRITE_FAILED, PdfBakeErrors.classifyWrite(IOException("EACCES (Permission denied)")))
        assertEquals(PdfBakeError.WRITE_FAILED, PdfBakeErrors.classifyWrite(IllegalStateException("whatever")))
        assertEquals("unknown is writeFailed", PdfBakeError.WRITE_FAILED, PdfBakeErrors.classifyWrite(null))
    }

    @Test
    fun layersSubtitleFollowsThePlacementOrigin() {
        assertEquals(PdfGeorefLabel.ADOBE_VP, pdfGeorefLabel(GeorefOrigin.ADOBE_VP))
        assertEquals(PdfGeorefLabel.LGI_DICT, pdfGeorefLabel(GeorefOrigin.LGI_DICT))
        assertEquals(PdfGeorefLabel.MANUAL, pdfGeorefLabel(GeorefOrigin.FIDUCIARIES))
        assertEquals(PdfGeorefLabel.PROVISIONAL, pdfGeorefLabel(GeorefOrigin.PROVISIONAL))
        assertEquals(PdfGeorefLabel.PROVISIONAL, pdfGeorefLabel(null))
    }

    @Test
    fun bakeEncodingIsLosslessWebp() {
        // J3: lossy q85 measured 32-34 dB, the gate is 35
        assertTrue(PdfBakePlan.WEBP_LOSSLESS)
        assertEquals("webp", PdfBakePlan.MBTILES_FORMAT)
        assertNotNull(PdfBakePlan.WEBP_QUALITY)
    }
}
