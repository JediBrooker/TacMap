package com.tacmap.map.render

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap

/**
 * Byte sized LRU, pure so the bind/trim rules test on the JVM. Not thread safe on
 * its own, [TileBitmapCache] is only touched from main.
 */
class ByteLruCache<K : Any, V : Any>(
    maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    var maxBytes: Long = maxBytes
        private set
    private val map = LinkedHashMap<K, V>(64, 0.75f, true)
    var bytes: Long = 0
        private set

    val size: Int get() = map.size

    operator fun get(key: K): V? = map[key]

    fun peek(key: K): V? = map[key]?.also { map[key] = it }

    fun put(key: K, value: V) {
        map.remove(key)?.let { bytes -= sizeOf(it) }
        map[key] = value
        bytes += sizeOf(value)
        trimToSize(maxBytes)
    }

    fun remove(key: K): V? = map.remove(key)?.also { bytes -= sizeOf(it) }

    fun keys(): Set<K> = map.keys.toSet()

    fun clear() {
        map.clear()
        bytes = 0
    }

    /** keep only these (memory warning), whatever else is in there goes */
    fun retainOnly(keep: Set<K>) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                bytes -= sizeOf(e.value)
                it.remove()
            }
        }
    }

    fun resize(newMax: Long) {
        maxBytes = newMax
        trimToSize(newMax)
    }

    private fun trimToSize(limit: Long) {
        val it = map.entries.iterator()
        while (bytes > limit && it.hasNext()) {
            val e = it.next()
            bytes -= sizeOf(e.value)
            it.remove()
        }
    }
}

/** memory only tile cache size by device RAM (contract B), MB here means MiB */
object TileMemoryBudget {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB
    const val LOW_RAM_BELOW_BYTES = 3_758_096_384L // 3.5 GiB

    fun cacheBytes(physicalMemory: Long, lowRamDevice: Boolean): Long = when {
        lowRamDevice || physicalMemory < LOW_RAM_BELOW_BYTES -> 64 * MIB
        physicalMemory < 6 * GIB -> 128 * MIB
        else -> 192 * MIB
    }

    /** base raster pixel budget, halved on small phones */
    fun baseBudgetPx(physicalMemory: Long, lowRamDevice: Boolean): Int =
        if (lowRamDevice || physicalMemory < LOW_RAM_BELOW_BYTES) 3_000_000 else 6_000_000

    fun physicalMemory(context: Context): Pair<Long, Boolean> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return 2 * GIB to true
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem to am.isLowRamDevice
    }
}

/** what a tile slot holds. EMPTY means "nothing to draw here", it counts as loaded */
sealed class TileCacheEntry {
    class Image(val bitmap: Bitmap) : TileCacheEntry()
    data object Empty : TileCacheEntry()
}

/**
 * The tile view's memory cache, owned by MapViewModel so a rotation keeps
 * expensive PDF tiles. Nothing here ever goes to disk.
 *
 * Bitmaps are NOT recycled on eviction any more: a recorded frame can still point
 * at an ancestor we just dropped, so we leave freeing to the GC
 * (NativeAllocationRegistry on 26+).
 */
class TileBitmapCache(maxBytes: Long) {
    private val lru = ByteLruCache<TileIndex, TileCacheEntry>(maxBytes) { entry ->
        when (entry) {
            is TileCacheEntry.Image -> entry.bitmap.allocationByteCountSafe()
            TileCacheEntry.Empty -> EMPTY_COST_BYTES
        }
    }
    private var boundKey: String? = null

    /** bumps whenever something lands so the canvas redraws */
    var version: Long = 0
        private set

    /** evicts only when the source identity actually changes */
    fun bind(cacheKey: String?) {
        if (cacheKey == boundKey) return
        boundKey = cacheKey
        lru.clear()
        version++
    }

    val key: String? get() = boundKey

    fun entry(t: TileIndex): TileCacheEntry? = lru[t]

    fun state(t: TileIndex): TileCacheState = when (lru[t]) {
        null -> TileCacheState.MISSING
        TileCacheEntry.Empty -> TileCacheState.EMPTY
        is TileCacheEntry.Image -> TileCacheState.IMAGE
    }

    fun put(t: TileIndex, entry: TileCacheEntry) {
        lru.put(t, entry)
        version++
    }

    fun trim(keeping: Set<TileIndex>) {
        lru.retainOnly(keeping)
        version++
    }

    // what the last drawn frame used, so a memory warning can drop everything else
    @Volatile private var inUse: Set<TileIndex> = emptySet()

    fun markInUse(tiles: Set<TileIndex>) {
        inUse = tiles
    }

    /** onTrimMemory: down to the tiles in the current draw plan */
    fun trimToInUse() = trim(inUse)

    fun clear() {
        lru.clear()
        version++
    }

    val bytes: Long get() = lru.bytes

    companion object {
        const val EMPTY_COST_BYTES = 64L

        fun forDevice(context: Context): TileBitmapCache {
            val (ram, low) = TileMemoryBudget.physicalMemory(context)
            return TileBitmapCache(TileMemoryBudget.cacheBytes(ram, low))
        }
    }
}

// a HARDWARE bitmap's pixels live on the gpu, count them as plain ARGB so the budget still holds
private fun Bitmap.allocationByteCountSafe(): Long =
    if (config == Bitmap.Config.HARDWARE) width.toLong() * height * 4
    else runCatching { allocationByteCount.toLong() }.getOrDefault(width.toLong() * height * 4)
