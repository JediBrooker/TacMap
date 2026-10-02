package com.tacmap.map.render.pdf

import com.tacmap.map.render.TileIndex
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * One PDF tile source's pending pdfium work, pulled by [PdfRenderExecutor]. All
 * state is guarded by the executor's lock.
 *
 * Live tiles: a loadTile coroutine parks here until a job covering its tile
 * finishes. Cancelling it drops the waiter, and a queued tile with no waiters is
 * gone. A running job always finishes; tiles nobody wants any more go to the
 * 8 tile orphan cache that loadTile checks first.
 *
 * Generic over the tile type so the scheduling rules run on the JVM with fakes,
 * the app only ever uses Bitmap.
 */
internal class PdfJobQueue<T : Any>(
    /** pdfium thread: draw the job, tile -> image ([isEmpty] ones mean nothing there) */
    private val render: (TileJob, PdfRenderExecutor.Band) -> Map<TileIndex, T>,
    /** pdfium thread, after a job: which band and whether it threw. live bands feed the renderError run */
    private val onJobFinished: (TileJob, PdfRenderExecutor.Band, Throwable?) -> Unit,
    /** the "nothing on this tile" sentinel test, never parked as an orphan */
    private val isEmpty: (T) -> Boolean,
    /**
     * The document's heavy EWMA (E1). Shared by every queue drawing the same document
     * (the live source and the bake's own source sit on one PdfRenderSession), so
     * a bake sample can flip the live view heavy and a new document starts light.
     */
    val timer: PdfRenderScheduling.CallTimer = PdfRenderScheduling.CallTimer(),
) {
    private inner class Pending(val tile: TileIndex) {
        var band = PdfRenderExecutor.Band.VISIBLE
        val waiters = ArrayList<CancellableContinuation<T?>>(1)
    }

    private inner class BakeRequest(val job: TileJob, val cont: CancellableContinuation<Timed<T>>)

    /** a bake job's tiles plus its E1 draw time (null when it never got to drawing) */
    class Timed<T>(val tiles: Map<TileIndex, T>, val drawMs: Double?)

    private val pending = LinkedHashMap<TileIndex, Pending>()
    private val running = HashSet<TileIndex>()
    private val orphans = LinkedHashMap<TileIndex, T>(16, 0.75f, true)
    private val bakeJobs = ArrayDeque<BakeRequest>()
    private var bakeInFlight = 0

    private var tileZoom = -1
    private var tileZoomChangedAt = 0L
    private var centreX = 0.5
    private var centreY = 0.5

    /** the view's current tile zoom + centre. a new zoom restarts the 150 ms settle */
    fun wanted(tz: Int, cx: Double, cy: Double) = PdfRenderExecutor.locked {
        if (tz != tileZoom) {
            tileZoom = tz
            tileZoomChangedAt = PdfRenderExecutor.clock()
        }
        centreX = cx
        centreY = cy
        for (p in pending.values) p.band = bandFor(p.tile)
    }

    private fun bandFor(t: TileIndex) =
        if (t.z == tileZoom || tileZoom < 0) PdfRenderExecutor.Band.VISIBLE else PdfRenderExecutor.Band.FALLBACK

    fun takeOrphan(t: TileIndex): T? = PdfRenderExecutor.locked { orphans.remove(t) }

    /** live vector tile. null when its job failed */
    suspend fun request(t: TileIndex): T? = suspendCancellableCoroutine { cont ->
        PdfRenderExecutor.locked {
            val p = pending.getOrPut(t) { Pending(t) }
            p.band = bandFor(t)
            p.waiters += cont
        }
        cont.invokeOnCancellation {
            PdfRenderExecutor.locked {
                val p = pending[t] ?: return@locked
                p.waiters.remove(cont)
                if (p.waiters.isEmpty()) pending.remove(t)
            }
        }
    }

    /** a bake job at the lowest band. throws whatever the render threw */
    suspend fun bake(job: TileJob): Map<TileIndex, T> = bakeTimed(job).tiles

    /** same, plus the draw time the EWMA saw, for the estimate's jobMs (J2 r1) */
    suspend fun bakeTimed(job: TileJob): Timed<T> = suspendCancellableCoroutine { cont ->
        val req = BakeRequest(job, cont)
        PdfRenderExecutor.locked { bakeJobs.addLast(req) }
        cont.invokeOnCancellation { PdfRenderExecutor.locked { bakeJobs.remove(req) } }
    }

    // ---- executor side, lock held ----

    fun hasVisiblePendingLocked(): Boolean =
        pending.values.any { it.band == PdfRenderExecutor.Band.VISIBLE && it.tile !in running }

    private fun distance(t: TileIndex): Double {
        val n = Math.scalb(1.0, t.z)
        val dx = (t.x + 0.5) / n - centreX
        val dy = (t.y + 0.5) / n - centreY
        return dx * dx + dy * dy
    }

    private fun bestLocked(band: PdfRenderExecutor.Band): Pending? =
        pending.values.filter { it.band == band && it.tile !in running }.minByOrNull { distance(it.tile) }

    fun offerLocked(band: PdfRenderExecutor.Band): PdfRenderExecutor.Offer? {
        if (band == PdfRenderExecutor.Band.BAKE) {
            if (bakeJobs.isEmpty() || bakeInFlight >= PdfRenderScheduling.MAX_BAKE_JOBS_IN_FLIGHT) return null
            return PdfRenderExecutor.Offer(band, 0.0, 0L, this)
        }
        val best = bestLocked(band) ?: return null
        return PdfRenderExecutor.Offer(band, distance(best.tile), tileZoomChangedAt + PdfRenderScheduling.VECTOR_SETTLE_MS, this)
    }

    /**
     * One sample per started vector job, any band, waiters or not (E1, R2). The clock starts
     * where the render marked the page draw ([PdfJobTiming], after the page open and the
     * guard arm, R3), or at the job start if it never marked. A job that threw, or one that
     * came back all EMPTY (nothing of the sheet in it, no real pdfium call), doesn't count.
     * Returns the draw time, null when nothing got timed
     */
    private fun recordSample(startedMs: Long, out: Map<TileIndex, T>?): Double? {
        val drawStart = PdfJobTiming.take(startedMs)
        val ms = (PdfRenderExecutor.clock() - drawStart).toDouble()
        val anyImage = out != null && out.values.any { !isEmpty(it) }
        if (!PdfRenderRules.feedsEwma(PdfRenderRules.JobPath.VECTOR, out != null, anyImage)) return if (out != null) ms else null
        timer.record(ms)
        return ms
    }

    fun takeJobLocked(band: PdfRenderExecutor.Band): (() -> Unit)? {
        if (band == PdfRenderExecutor.Band.BAKE) {
            val req = bakeJobs.removeFirstOrNull() ?: return null
            bakeInFlight++
            return {
                PdfJobTiming.reset()
                val started = PdfRenderExecutor.clock()
                val result = runCatching { render(req.job, band) }
                val ms = recordSample(started, result.getOrNull())
                PdfRenderExecutor.locked { bakeInFlight-- }
                onJobFinished(req.job, band, result.exceptionOrNull())
                result.fold(
                    { if (req.cont.isActive) req.cont.resume(Timed(it, ms)) },
                    { if (req.cont.isActive) req.cont.resumeWithException(it) },
                )
            }
        }
        val seed = bestLocked(band) ?: return null
        val job = if (band == PdfRenderExecutor.Band.VISIBLE && timer.heavy) {
            PdfJobFormation.grow(seed.tile) { t ->
                val p = pending[t]
                p != null && p.band == PdfRenderExecutor.Band.VISIBLE && t !in running
            }
        } else {
            TileJob.single(seed.tile.z, seed.tile.x, seed.tile.y)
        }
        val tiles = job.tiles().map { TileIndex(it[0], it[1], it[2]) }
        running += tiles
        return {
            PdfJobTiming.reset()
            val started = PdfRenderExecutor.clock()
            val result = runCatching { render(job, band) }
            val out = result.getOrNull()
            recordSample(started, out)
            onJobFinished(job, band, result.exceptionOrNull())
            PdfRenderExecutor.locked {
                for (t in tiles) {
                    running -= t
                    val img = out?.get(t)
                    val p = pending.remove(t)
                    if (p != null) {
                        for (w in p.waiters) if (w.isActive) w.resume(img)
                    } else if (img != null && !isEmpty(img)) {
                        orphans[t] = img
                        while (orphans.size > PdfRenderScheduling.ORPHAN_CACHE_TILES) {
                            orphans.remove(orphans.keys.first())
                        }
                    }
                }
            }
        }
    }

    /** dropped by the source (new georef, failure, trim): wake every waiter with nothing */
    fun clear() = PdfRenderExecutor.locked {
        for (p in pending.values) for (w in p.waiters) if (w.isActive) w.resume(null)
        pending.clear()
        orphans.clear()
        for (b in bakeJobs) if (b.cont.isActive) b.cont.resumeWithException(java.util.concurrent.CancellationException("queue cleared"))
        bakeJobs.clear()
    }

    fun clearOrphans() = PdfRenderExecutor.locked { orphans.clear() }

    /** for tests: how many tiles are queued and how many orphans are parked */
    internal val pendingCount: Int get() = PdfRenderExecutor.locked { pending.size }
    internal val orphanCount: Int get() = PdfRenderExecutor.locked { orphans.size }
}

/**
 * Where a vector job's EWMA sample starts (R3). The render calls [markDrawStart] on the
 * pdfium thread once the page is open and the crash guard is armed, so neither the
 * open nor the guard's fsync lands in the heavy flag. Thread local, one job at a time
 */
internal object PdfJobTiming {
    private val start = ThreadLocal<Long?>()

    fun reset() = start.set(null)

    fun markDrawStart() = start.set(PdfRenderExecutor.clock())

    /** the marked start, or [fallback] when the render never marked one */
    fun take(fallback: Long): Long = (start.get() ?: fallback).also { start.set(null) }
}
