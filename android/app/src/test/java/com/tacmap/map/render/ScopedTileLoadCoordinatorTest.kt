package com.tacmap.map.render

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ScopedTileLoadCoordinatorTest {
    @Test fun delayedTileFromBeforePanCannotStrandCurrentVisibleTile() = runBlocking {
        val harness = Harness(this)
        val source = Source("offline")

        harness.coordinator.reconcile(source, setOf(1), isLoaded = { false })
        yield()
        harness.coordinator.reconcile(source, setOf(2), isLoaded = { false })
        yield()

        harness.complete(source, 1, "stale")
        harness.complete(source, 2, "current")
        harness.awaitPublished(1)

        assertEquals(listOf("offline:2:current"), harness.published)
        assertTrue("stale result is explicitly discarded", "stale" in harness.discarded)
        harness.close()
    }

    @Test fun staleSourceCompletionCannotClearReplacementForSameTile() = runBlocking {
        val harness = Harness(this)
        val oldSource = Source("old")
        val currentSource = Source("current")

        harness.coordinator.reconcile(oldSource, setOf(7), isLoaded = { false })
        yield()
        harness.coordinator.reconcile(currentSource, setOf(7), isLoaded = { false })
        yield()

        // Complete stale work first. Its token must not remove currentSource's
        // in-flight entry for the same TileIndex.
        harness.complete(oldSource, 7, "old-result")
        yield()
        harness.complete(currentSource, 7, "new-result")
        harness.awaitPublished(1)

        assertEquals(listOf("current:7:new-result"), harness.published)
        assertTrue("old-result" in harness.discarded)
        harness.close()
    }

    @Test fun stalePanCompletionCannotClearSameSourceReplacementToken() = runBlocking {
        val harness = Harness(this)
        val source = Source("offline")

        harness.coordinator.reconcile(source, setOf(4), isLoaded = { false })
        yield()
        harness.coordinator.reconcile(source, emptySet(), isLoaded = { false })
        harness.coordinator.reconcile(source, setOf(4), isLoaded = { false })
        yield()

        harness.complete(source, 4, "first-stale")
        yield()
        harness.complete(source, 4, "replacement")
        harness.awaitPublished(1)

        assertEquals(listOf("offline:4:replacement"), harness.published)
        assertTrue("first-stale" in harness.discarded)
        harness.close()
    }

    private class Source(val name: String)

    private class Harness(parent: CoroutineScope) {
        private val scope = CoroutineScope(
            parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]),
        )
        private val waiting = HashMap<Pair<Source, Int>, ArrayDeque<Continuation<String?>>>()
        val published = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val coordinator = ScopedTileLoadCoordinator<Source, Int, String>(
            scope = scope,
            load = { source, key ->
                // Deliberately ignores Job cancellation, modelling a decoder or
                // source that completes late after a pan/source swap.
                suspendCoroutine {
                    waiting.getOrPut(source to key) { ArrayDeque() }.addLast(it)
                }
            },
            publish = { source, key, value -> published += "${source.name}:$key:$value" },
            discard = { discarded += it },
        )

        fun complete(source: Source, key: Int, value: String) {
            val queue = checkNotNull(waiting[source to key])
            queue.removeFirst().resume(value)
            if (queue.isEmpty()) waiting.remove(source to key)
        }

        suspend fun awaitPublished(count: Int) {
            withTimeout(2_000L) {
                while (published.size < count) yield()
            }
        }

        fun close() {
            coordinator.dispose()
            scope.cancel()
        }
    }
}
