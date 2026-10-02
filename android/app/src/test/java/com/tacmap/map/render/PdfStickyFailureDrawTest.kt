package com.tacmap.map.render

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OD2-R2-4 / G1: once the PDF source is sticky failed it stays attached with the same cache
 * key, every new load answers null, and the view keeps drawing what it already has: cached
 * own tiles and fallback ancestors. The same planner + coordinator + cache bind TileMapView
 * runs, with a fake failed source standing in for PdfTileSource (its null-when-failed
 * loadTile is checked on device in PdfStoredFileRecoveryInstrumentedTest)
 */
class PdfStickyFailureDrawTest {
    private class FailedSource {
        val cacheKey = "pdf:/x.pdf:abcd:768:g0"
        var loads = 0
        @Suppress("UNUSED_PARAMETER")
        suspend fun loadTile(t: TileIndex): String? {
            loads++
            return null
        }
    }

    @Test
    fun cachedTilesAndAncestorsKeepDrawingWhileNewLoadsGetNothing() = runBlocking {
        val tz = 15
        val a = TileIndex(tz, 100, 200)
        val b = TileIndex(tz, 101, 200)
        val c = TileIndex(tz, 140, 260)
        // b's z13 ancestor came in before the failure, a itself too. c has nothing
        val bAncestor = b.ancestor(2)!!
        val cache = HashMap<TileIndex, TileCacheState>().apply {
            put(a, TileCacheState.IMAGE)
            put(bAncestor, TileCacheState.IMAGE)
        }
        val visible = listOf(VisibleTile(a, a.x, a.y), VisibleTile(b, b.x, b.y), VisibleTile(c, c.x, c.y))
        val source = FailedSource()

        // same key after the failure: the bind keeps the cache (only Try Again's new key clears it)
        val bitmapCache = TileBitmapCache(1 shl 20)
        bitmapCache.bind(source.cacheKey)
        bitmapCache.put(a, TileCacheEntry.Empty)
        bitmapCache.bind(source.cacheKey)
        assertEquals(TileCacheState.EMPTY, bitmapCache.state(a))

        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val published = ArrayList<TileIndex>()
        val coordinator = ScopedTileLoadCoordinator<FailedSource, TileIndex, String>(
            scope = scope,
            load = { s, t -> s.loadTile(t) },
            publish = { _, t, _ -> published += t; cache[t] = TileCacheState.IMAGE },
            discard = { },
        )
        // a failed base keeps fallbackZoom null, a base that landed earlier keeps its level: both draw the same here
        for (fallbackZoom in listOf<Int?>(null, 12)) {
            val plan = TileDrawPlanner.plan(visible, { cache[it] ?: TileCacheState.MISSING }, fallbackZoom)
            val own = plan.items.filter { it.kind == TileDrawItem.Kind.OWN }.map { it.source }
            val anc = plan.items.filter { it.kind == TileDrawItem.Kind.ANCESTOR }.map { it.source to it.dest.index }
            assertEquals("the cached tile keeps drawing", listOf(a), own)
            assertEquals("b's cached ancestor keeps drawing under it", listOf(bAncestor to b), anc)
            assertTrue("nothing for c, it just stays missing", plan.items.none { it.dest.index == c })

            coordinator.reconcile(source, LinkedHashSet(plan.requests), isLoaded = { cache[it] != null })
            repeat(5) { yield() }
            assertTrue("a failed source delivers nothing new", published.isEmpty())
            // and the cache didn't lose anything either
            assertEquals(TileCacheState.IMAGE, cache[a])
            assertEquals(TileCacheState.IMAGE, cache[bAncestor])
        }
        assertTrue("loads were asked and answered null", source.loads > 0)
        coordinator.dispose()
        scope.cancel()
    }
}
