package com.tacmap.map.render.pdf

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one thread that ever touches pdfium (PdfRenderer isn't thread safe and the
 * framework holds a global lock anyway). Everything goes through here: the import
 * probe, the display size probe, live tiles, the bake.
 *
 * Pull based. When the thread frees up it picks, in band order (VISIBLE, FALLBACK,
 * BAKE), a one off task or the best job any registered [PdfJobQueue] can offer,
 * nearest the viewport centre first (contract E). Vector jobs wait until the tile
 * zoom has sat still for 150 ms, a bake job never starts while visible work is
 * pending, and in the background only bake work runs.
 */
internal object PdfRenderExecutor {
    enum class Band { VISIBLE, FALLBACK, BAKE }

    private const val TAG = "PdfRenderExecutor"

    /** guards the task list and every registered queue's state */
    internal val lock = Object()
    private var seq = 0L
    private val tasks = ArrayList<Task>()
    private val queues = LinkedHashSet<PdfJobQueue<*>>()
    private var thread: Thread? = null

    /** uptime ms. swapped for a fake clock in the JVM scheduling tests */
    @Volatile internal var clock: () -> Long = { SystemClock.uptimeMillis() }

    /** false in JVM tests: they pull work by hand through [pickForTest], no thread */
    @Volatile internal var startThread: Boolean = true

    @Volatile
    var foreground: Boolean = true
        set(value) {
            field = value
            wake()
        }

    private class Task(
        val band: Band,
        val seq: Long,
        /** keeps running in the background (import probe, bake) */
        val background: Boolean,
        val body: () -> Unit,
    )

    /** what a queue offers the picker */
    class Offer(
        val band: Band,
        /** squared distance of the job centre to the viewport centre, 0..1 world units */
        val distance: Double,
        /** vector work waits for the zoom to settle; base raster samples don't come through here */
        val settledAtMs: Long,
        val queue: PdfJobQueue<*>,
    )

    fun register(queue: PdfJobQueue<*>) = synchronized(lock) {
        queues += queue
        ensureThreadLocked()
        lock.notifyAll()
    }

    fun unregister(queue: PdfJobQueue<*>) = synchronized(lock) {
        queues -= queue
        lock.notifyAll()
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }

    internal inline fun <T> locked(block: () -> T): T = synchronized(lock) {
        val r = block()
        lock.notifyAll()
        r
    }

    /** run [block] on the pdfium thread at [band]. cancellation drops it if it hasn't started */
    suspend fun <T> run(band: Band, background: Boolean = false, block: () -> T): T =
        suspendCancellableCoroutine { cont: CancellableContinuation<T> ->
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val task = Task(band, synchronized(lock) { seq++ }, background, body = {
                if (!cancelled.get()) {
                    // runCatching on purpose: an OutOfMemoryError from our own bitmap has to reach the
                    // caller as a failure, never be swallowed as success (D5-15)
                    val result = runCatching(block)
                    result.fold(
                        { if (cont.isActive) cont.resume(it) },
                        { if (cont.isActive) cont.resumeWithException(it) },
                    )
                }
            })
            synchronized(lock) {
                tasks += task
                ensureThreadLocked()
                lock.notifyAll()
            }
            cont.invokeOnCancellation {
                cancelled.set(true)
                synchronized(lock) { tasks.remove(task) }
            }
        }

    /** true while visible work is waiting anywhere, the bake yields to it */
    private fun visiblePendingLocked(): Boolean =
        tasks.any { it.band == Band.VISIBLE } || queues.any { it.hasVisiblePendingLocked() }

    private fun ensureThreadLocked() {
        if (!startThread || thread?.isAlive == true) return
        thread = Thread({
            // DEFAULT + LESS_FAVORABLE, not BACKGROUND: that cgroup pins us to the little cores
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            loop()
        }, "PdfRender").apply { isDaemon = true; start() }
    }

    private sealed class Pick {
        class Run(val work: () -> Unit) : Pick()
        class Wait(val ms: Long) : Pick()
    }

    private fun loop() {
        while (true) {
            val work = synchronized(lock) {
                var w: (() -> Unit)? = null
                while (w == null) {
                    when (val p = pickLocked(clock())) {
                        is Pick.Run -> w = p.work
                        is Pick.Wait -> try {
                            lock.wait(p.ms)
                        } catch (_: InterruptedException) {
                        }
                    }
                }
                w
            }
            try {
                work()
            } catch (t: Throwable) {
                // a job failing is handled by its queue, this is just so the thread never dies
                Log.w(TAG, "pdf render work threw (${t.javaClass.simpleName})")
            }
        }
    }

    private fun pickLocked(now: Long): Pick {
        val fg = foreground
        var waitMs = 0L
        for (band in Band.values()) {
            if (!fg && band != Band.BAKE) {
                // background: only bake work, plus tasks that asked to keep going (import probe)
                val task = tasks.filter { it.band == band && it.background }.minByOrNull { it.seq }
                if (task != null) {
                    tasks.remove(task)
                    return Pick.Run(task.body)
                }
                continue
            }
            // the bake yields to visible work, but in the background visible work isn't going
            // anywhere, so don't let parked tiles starve it
            if (band == Band.BAKE && fg && visiblePendingLocked()) continue
            val task = tasks.filter { it.band == band }.minByOrNull { it.seq }
            if (task != null) {
                tasks.remove(task)
                return Pick.Run(task.body)
            }
            val offers = queues.mapNotNull { it.offerLocked(band) }
            if (offers.isEmpty()) continue
            val ready = offers.filter { band == Band.BAKE || now >= it.settledAtMs }
            val best = ready.minByOrNull { it.distance }
            if (best != null) {
                val work = best.queue.takeJobLocked(band) ?: continue
                return Pick.Run(work)
            }
            val soonest = offers.minOf { it.settledAtMs } - now
            waitMs = if (waitMs == 0L) maxOf(1L, soonest) else minOf(waitMs, maxOf(1L, soonest))
            // a settling visible job still blocks lower bands from jumping the queue for pdfium,
            // except the bake check above already covers that. fallback may run meanwhile
        }
        return Pick.Wait(waitMs)
    }

    /** JVM tests: what the pdfium thread would run next at [now], null when it'd wait */
    internal fun pickForTest(now: Long): (() -> Unit)? = synchronized(lock) {
        (pickLocked(now) as? Pick.Run)?.work
    }

    /** JVM tests: forget every queue and task. the heavy timers live per document now (E1) */
    internal fun resetForTest() = synchronized(lock) {
        tasks.clear()
        queues.clear()
        foreground = true
    }
}
