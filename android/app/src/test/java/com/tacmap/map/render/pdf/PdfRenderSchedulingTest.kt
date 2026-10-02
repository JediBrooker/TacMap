package com.tacmap.map.render.pdf

import com.tacmap.map.render.TileIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Contract E on the JVM: the pdfium executor's pick order, the 150 ms settle,
 * heavy growth, cancel + orphans, bake yielding, background. No thread, no pdfium:
 * a fake clock and a fake render that hands back strings.
 */
class PdfRenderSchedulingTest {
    private var now = 10_000L
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val rendered = ArrayList<Pair<TileJob, PdfRenderExecutor.Band>>()

    @Before
    fun setUp() {
        PdfRenderExecutor.resetForTest()
        PdfRenderExecutor.startThread = false
        PdfRenderExecutor.clock = { now }
    }

    @After
    fun tearDown() {
        PdfRenderExecutor.resetForTest()
    }

    private val finished = ArrayList<Pair<PdfRenderExecutor.Band, Throwable?>>()

    private fun queue(
        empty: Set<TileIndex> = emptySet(),
        fail: Boolean = false,
        costMs: Long = 10,
        timer: PdfRenderScheduling.CallTimer = PdfRenderScheduling.CallTimer(),
        allEmpty: Boolean = false,
        /** page open + guard arm before the draw starts, marked like the real render does (R3) */
        openMs: Long? = null,
    ): PdfJobQueue<String> {
        val q = PdfJobQueue<String>(
            render = { job, band ->
                rendered += job to band
                if (openMs != null) {
                    now += openMs
                    PdfJobTiming.markDrawStart()
                }
                now += costMs
                if (fail) throw IllegalStateException("boom")
                job.tiles().associate { t ->
                    val idx = TileIndex(t[0], t[1], t[2])
                    idx to if (allEmpty || idx in empty) EMPTY else "tile ${t[0]}/${t[1]}/${t[2]}"
                }
            },
            onJobFinished = { _, band, error -> finished += band to error },
            isEmpty = { it == EMPTY },
            timer = timer,
        )
        PdfRenderExecutor.register(q)
        return q
    }

    /** park a live request the way loadTile does, the deferred finishes when its job runs */
    private fun request(q: PdfJobQueue<String>, t: TileIndex) = scope.async { q.request(t) }

    private fun runNext(): Boolean {
        val w = PdfRenderExecutor.pickForTest(now) ?: return false
        w()
        return true
    }

    @Test
    fun vectorWorkWaitsForTheZoomToSettle() {
        val q = queue()
        q.wanted(15, 0.5, 0.5)
        val r = request(q, TileIndex(15, 100, 100))
        now += PdfRenderScheduling.VECTOR_SETTLE_MS - 1
        assertNull("picked before the zoom settled", PdfRenderExecutor.pickForTest(now))
        now += 1
        assertTrue(runNext())
        assertEquals("tile 15/100/100", r.getCompleted())
        // a zoom change restarts the clock
        q.wanted(16, 0.5, 0.5)
        request(q, TileIndex(16, 200, 200))
        assertNull(PdfRenderExecutor.pickForTest(now + 100))
        assertNotNull(PdfRenderExecutor.pickForTest(now + PdfRenderScheduling.VECTOR_SETTLE_MS))
    }

    @Test
    fun visibleBeatsFallbackAndNearestGoesFirst() {
        val q = queue()
        val n = 1 shl 15
        // centre of tile (15, 1000, 1000)
        q.wanted(15, 1000.5 / n, 1000.5 / n)
        val parent = request(q, TileIndex(14, 500, 500))
        val far = request(q, TileIndex(15, 1003, 1000))
        val near = request(q, TileIndex(15, 1000, 1000))
        now += 1_000
        assertTrue(runNext())
        assertEquals(TileJob.single(15, 1000, 1000), rendered.last().first)
        assertTrue(near.isCompleted && !far.isCompleted && !parent.isCompleted)
        assertTrue(runNext())
        assertEquals(TileJob.single(15, 1003, 1000), rendered.last().first)
        assertTrue(runNext())
        assertEquals(TileJob.single(14, 500, 500) to PdfRenderExecutor.Band.FALLBACK, rendered.last())
        assertFalse(runNext())
    }

    @Test
    fun heavyJobsGrowOverPendingVisibleTilesWithinTheLimits() {
        val timer = PdfRenderScheduling.CallTimer()
        timer.record(PdfRenderScheduling.HEAVY_THRESHOLD_MS + 40)
        assertTrue(timer.heavy)
        val q = queue(timer = timer)
        val n = 1 shl 15
        q.wanted(15, 1000.5 / n, 1000.5 / n)
        for (y in 998..1002) for (x in 998..1002) request(q, TileIndex(15, x, y))
        now += 1_000
        assertTrue(runNext())
        val job = rendered.last().first
        assertTrue("$job", job.tileCount in 2..PdfJobFormation.MAX_TILES)
        assertTrue("$job", job.cols <= PdfJobFormation.MAX_SIDE && job.rows <= PdfJobFormation.MAX_SIDE)
        assertTrue("$job", job.contains(15, 1000, 1000))
    }

    @Test
    fun aBakeSampleFlipsTheDocumentHeavyAndANewDocumentStartsLight() {
        // live source + the bake's own source on one document share its timer (E1)
        val doc = PdfRenderScheduling.CallTimer()
        val live = queue(timer = doc)
        val bake = queue(timer = doc, costMs = 225)
        val n = 1 shl 15
        live.wanted(15, 1000.5 / n, 1000.5 / n)
        // estimate sample at the bake band, nothing live has drawn yet
        val sample = scope.async { bake.bake(TileJob.single(15, 1000, 1000)) }
        assertTrue(runNext())
        assertTrue(sample.isCompleted)
        assertEquals(225.0, doc.ewmaMs, 1e-9)
        assertTrue("one heavy bake sample flips the document", doc.heavy)
        for (y in 998..1002) for (x in 998..1002) request(live, TileIndex(15, x, y))
        now += 1_000
        assertTrue(runNext())
        assertTrue("the live view grows its jobs off the bake's sample", rendered.last().first.tileCount > 1)
        // a different document has its own timer and starts light: 1x1
        val other = queue()
        other.wanted(15, 2000.5 / n, 2000.5 / n)
        for (x in 1999..2001) request(other, TileIndex(15, x, 2000))
        live.clear()
        now += 1_000
        assertTrue(runNext())
        assertEquals(TileJob.single(15, 2000, 2000), rendered.last().first)
        assertFalse(other.timer.heavy)
    }

    @Test
    fun allEmptyAndFailedJobsDontFeedTheEwma() {
        val timer = PdfRenderScheduling.CallTimer()
        val q = queue(timer = timer, allEmpty = true, costMs = 500)
        q.wanted(15, 0.5, 0.5)
        request(q, TileIndex(15, 3, 3))
        val b = scope.async { q.bake(TileJob(15, 6, 6, 3, 2)) }
        now += 1_000
        assertTrue(runNext())
        assertTrue(runNext())
        assertTrue(b.isCompleted)
        assertTrue("all EMPTY jobs never feed it", timer.ewmaMs.isNaN())
        assertFalse(timer.heavy)
        val failing = queue(timer = timer, fail = true, costMs = 500)
        failing.wanted(15, 0.5, 0.5)
        request(failing, TileIndex(15, 4, 4))
        now += 1_000
        assertTrue(runNext())
        assertTrue("a failed job doesn't either", timer.ewmaMs.isNaN())
        // and the live failure went to onJobFinished with its band, for the G2 run
        assertEquals(PdfRenderExecutor.Band.VISIBLE, finished.last().first)
        assertTrue(finished.last().second is IllegalStateException)
    }

    @Test
    fun ewmaFlipsHeavyOnlyPastTheThreshold() {
        val t = PdfRenderScheduling.CallTimer()
        assertFalse("light until the first sample", t.heavy)
        t.record(50.0)
        assertFalse(t.heavy)
        t.record(100.0) // 0.3*100 + 0.7*50 = 65
        assertEquals(65.0, t.ewmaMs, 1e-9)
        assertTrue(t.heavy)
        t.record(0.0) // 45.5
        assertFalse(t.heavy)
    }

    @Test
    fun aCancelledQueuedTileIsGoneAndARunningJobsTileBecomesAnOrphan() {
        val q = queue()
        q.wanted(15, 0.5, 0.5)
        val a = TileIndex(15, 10, 10)
        val b = TileIndex(15, 11, 10)
        val ra = request(q, a)
        val rb = request(q, b)
        // b gets cancelled while queued: removed, nothing renders it
        rb.cancel()
        assertEquals(1, q.pendingCount)
        now += 1_000
        val work = PdfRenderExecutor.pickForTest(now)!!
        // a gets cancelled while its job runs: the job still completes and parks the tile
        ra.cancel()
        work()
        assertEquals(listOf(TileJob.single(15, 10, 10)), rendered.map { it.first })
        assertEquals(1, q.orphanCount)
        assertEquals("tile 15/10/10", q.takeOrphan(a))
        assertNull(q.takeOrphan(a))
        assertFalse(runNext())
    }

    @Test
    fun orphanCacheKeepsEightAndNeverEmpties() {
        // the EMPTY one sits in the middle (x=8): if it got parked it'd be one of the
        // newest 8 and survive the LRU, so this really catches EMPTY being parked (S4)
        val q = queue(empty = setOf(TileIndex(15, 8, 0)))
        q.wanted(15, 0.0, 0.0)
        now += 1_000
        for (x in 0 until 12) {
            val r = request(q, TileIndex(15, x, 0))
            val w = PdfRenderExecutor.pickForTest(now)!!
            r.cancel()
            w()
        }
        assertNull("EMPTY never parks", q.takeOrphan(TileIndex(15, 8, 0)))
        // 11 images parked, the 8 newest stay: 3..11 minus the EMPTY 8 = 8 tiles
        assertEquals(PdfRenderScheduling.ORPHAN_CACHE_TILES, q.orphanCount)
        assertNull("oldest went first", q.takeOrphan(TileIndex(15, 2, 0)))
        assertEquals("tile 15/3/0", q.takeOrphan(TileIndex(15, 3, 0)))
        assertEquals("tile 15/11/0", q.takeOrphan(TileIndex(15, 11, 0)))
    }

    @Test
    fun bakeYieldsToVisibleWorkAndOnlyOneRunsAtATime() {
        val live = queue()
        val bake = queue()
        live.wanted(15, 0.5, 0.5)
        val b1 = scope.async { bake.bake(TileJob(14, 0, 0, 3, 2)) }
        val b2 = scope.async { bake.bake(TileJob(14, 3, 0, 3, 2)) }
        request(live, TileIndex(15, 5, 5))
        // visible tile still settling: the bake must not jump in
        assertNull(PdfRenderExecutor.pickForTest(now))
        now += 1_000
        assertTrue(runNext())
        assertEquals(PdfRenderExecutor.Band.VISIBLE, rendered.last().second)
        val w1 = PdfRenderExecutor.pickForTest(now)!!
        assertNull("one bake job in flight at most", PdfRenderExecutor.pickForTest(now))
        w1()
        assertTrue(b1.isCompleted && !b2.isCompleted)
        assertEquals(PdfRenderExecutor.Band.BAKE, rendered.last().second)
        assertTrue(runNext())
        assertEquals(6, b2.getCompleted().size)
    }

    @Test
    fun inTheBackgroundOnlyBakeAndBackgroundTasksRun() {
        val live = queue()
        val bake = queue()
        live.wanted(15, 0.5, 0.5)
        val r = request(live, TileIndex(15, 1, 1))
        val b = scope.async { bake.bake(TileJob.single(14, 0, 0)) }
        val ran = ArrayList<String>()
        val fgTask: Job = scope.launch { PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE) { ran += "fg" } }
        scope.launch { PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE, background = true) { ran += "probe" } }
        now += 1_000
        PdfRenderExecutor.foreground = false
        assertTrue(runNext())
        assertEquals(listOf("probe"), ran)
        assertTrue(runNext())
        assertTrue("the bake carries on in the background", b.isCompleted)
        assertFalse("visible tiles and foreground tasks wait", runNext())
        assertFalse(r.isCompleted)
        PdfRenderExecutor.foreground = true
        assertTrue(runNext())
        assertEquals(listOf("probe", "fg"), ran)
        assertTrue(runNext())
        assertTrue(r.isCompleted)
        assertTrue(fgTask.isCompleted)
    }

    @Test
    fun aFailedJobWakesItsWaitersWithNothing() {
        val q = queue(fail = true)
        q.wanted(15, 0.5, 0.5)
        val r = request(q, TileIndex(15, 3, 3))
        now += 1_000
        assertTrue(runNext())
        assertNull(r.getCompleted())
        assertEquals(0, q.orphanCount)
    }

    @Test
    fun clearedQueueReleasesEverybody() {
        val q = queue()
        q.wanted(15, 0.5, 0.5)
        val r = request(q, TileIndex(15, 3, 3))
        val b = scope.async { runCatching { q.bake(TileJob.single(14, 1, 1)) } }
        q.clear()
        assertNull(r.getCompleted())
        assertTrue(b.getCompleted().isFailure)
        now += 1_000
        assertFalse(runNext())
        assertEquals(0, q.pendingCount)
    }

    @Test
    fun aStartedJobEveryWaiterLeftStillCountsAndFeedsTheEwma() {
        // R2: started means it counts, waiters or not
        val timer = PdfRenderScheduling.CallTimer()
        val q = queue(timer = timer, costMs = 80)
        q.wanted(15, 0.5, 0.5)
        val r = request(q, TileIndex(15, 3, 3))
        now += 1_000
        val work = PdfRenderExecutor.pickForTest(now)!!
        r.cancel()
        finished.clear()
        work()
        assertEquals("counted once", 1, finished.size)
        assertNull(finished.single().second)
        assertEquals(80.0, timer.ewmaMs, 1e-9)
        assertEquals("nobody waits, so it's parked", 1, q.orphanCount)
    }

    @Test
    fun aJobCancelledBeforeItStartedHasNoEffect() {
        val timer = PdfRenderScheduling.CallTimer()
        val q = queue(timer = timer, costMs = 80)
        q.wanted(15, 0.5, 0.5)
        val r = request(q, TileIndex(15, 3, 3))
        r.cancel()
        finished.clear()
        now += 1_000
        assertFalse(runNext())
        assertTrue(finished.isEmpty())
        assertTrue(timer.ewmaMs.isNaN())
    }

    @Test
    fun theEwmaSampleStartsAfterThePageOpenAndGuardArm() {
        // R3: 500 ms of open + fsync before the draw mustn't make the document heavy
        val timer = PdfRenderScheduling.CallTimer()
        val q = queue(timer = timer, costMs = 12, openMs = 500)
        q.wanted(15, 0.5, 0.5)
        request(q, TileIndex(15, 3, 3))
        val b = scope.async { q.bakeTimed(TileJob.single(15, 9, 9)) }
        now += 1_000
        assertTrue(runNext())
        assertTrue(runNext())
        assertEquals(12.0, timer.ewmaMs, 1e-9)
        assertFalse(timer.heavy)
        assertEquals("the estimate's jobMs is the same draw time", 12.0, b.getCompleted().drawMs!!, 1e-9)
    }

    private companion object {
        const val EMPTY = "<empty>"
    }
}
