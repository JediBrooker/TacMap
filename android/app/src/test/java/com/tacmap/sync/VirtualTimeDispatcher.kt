package com.tacmap.sync

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/**
 * Tiny stand-in for kotlinx-coroutines-test's StandardTestDispatcher so the
 * Unit Sync state machine runs on virtual time without adding a dependency
 * (and its verification-metadata entries) just for tests. Everything runs on
 * the test thread; nothing happens until runCurrent/advanceTimeBy.
 */
@OptIn(InternalCoroutinesApi::class)
internal class VirtualTimeDispatcher : CoroutineDispatcher(), Delay {
    private class Task(val time: Long, val seq: Long, val block: Runnable)

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.time }, { it.seq }))
    private var nextSeq = 0L

    var currentTime: Long = 0L
        private set

    @Synchronized override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue += Task(currentTime, nextSeq++, block)
    }

    @Synchronized override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val task = Task(currentTime + timeMillis.coerceAtLeast(0L), nextSeq++, Runnable { continuation.resume(Unit) })
        queue += task
        continuation.invokeOnCancellation { synchronized(this) { queue.remove(task) } }
    }

    @Synchronized override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val task = Task(currentTime + timeMillis.coerceAtLeast(0L), nextSeq++, block)
        queue += task
        return DisposableHandle { synchronized(this) { queue.remove(task) } }
    }

    /** Runs everything due now, including work that work due now schedules. */
    @Synchronized fun runCurrent() {
        while (true) {
            val head = queue.peek() ?: return
            if (head.time > currentTime) return
            queue.poll()
            head.block.run()
        }
    }

    /** Runs every task strictly before now + [ms], then moves the clock there. */
    @Synchronized fun advanceTimeBy(ms: Long) {
        val target = currentTime + ms
        while (true) {
            val head = queue.peek() ?: break
            if (head.time >= target) break
            queue.poll()
            if (head.time > currentTime) currentTime = head.time
            head.block.run()
        }
        currentTime = target
    }
}
