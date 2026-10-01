package com.tacmap.map.render

import org.junit.Assert.assertEquals
import org.junit.Test

/** bind evicts only on a real key change, EMPTY entries are cheap and safe (contract B) */
class TileBitmapCacheTest {

    @Test
    fun bindOnlyEvictsOnAKeyChange() {
        val cache = TileBitmapCache(1024)
        cache.bind("pdf:a")
        val t = TileIndex(15, 1, 2)
        cache.put(t, TileCacheEntry.Empty)
        assertEquals(TileCacheState.EMPTY, cache.state(t))
        // same identity (a rebuilt source object, a failed source that stays attached): kept
        cache.bind("pdf:a")
        assertEquals(TileCacheState.EMPTY, cache.state(t))
        // Try Again is a new key: gone
        cache.bind("pdf:a:g1")
        assertEquals(TileCacheState.MISSING, cache.state(t))
        assertEquals(0L, cache.bytes)
    }

    @Test
    fun emptyEntriesCostTheirFixedBytesAndTrimKeepsOnlyInUse() {
        val cache = TileBitmapCache(TileBitmapCache.EMPTY_COST_BYTES * 3)
        cache.bind("k")
        val tiles = (0 until 5).map { TileIndex(10, it, 0) }
        tiles.forEach { cache.put(it, TileCacheEntry.Empty) }
        // byte budget holds: 3 EMPTY entries fit, the oldest two went
        assertEquals(TileBitmapCache.EMPTY_COST_BYTES * 3, cache.bytes)
        assertEquals(TileCacheState.MISSING, cache.state(tiles[0]))
        assertEquals(TileCacheState.EMPTY, cache.state(tiles[4]))
        cache.markInUse(setOf(tiles[3]))
        cache.trimToInUse()
        assertEquals(TileCacheState.EMPTY, cache.state(tiles[3]))
        assertEquals(TileCacheState.MISSING, cache.state(tiles[4]))
        assertEquals(TileBitmapCache.EMPTY_COST_BYTES, cache.bytes)
    }
}
