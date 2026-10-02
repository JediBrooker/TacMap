package com.tacmap.map.render

import kotlin.math.max
import kotlin.math.pow

/** x is wrapped mod 2^z (the tile to fetch), col stays unwrapped (where it goes on screen) */
data class VisibleTile(val index: TileIndex, val col: Int, val row: Int)

enum class TileCacheState { IMAGE, EMPTY, MISSING }

/** y down sub rect in unit space, [x, y, w, h] */
data class UnitRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    companion object {
        val FULL = UnitRect(0.0, 0.0, 1.0, 1.0)
    }
}

data class TileDrawItem(
    val kind: Kind,
    /** the cached image this item draws from */
    val source: TileIndex,
    val dest: VisibleTile,
    /** part of the source image */
    val unitRect: UnitRect,
    /** part of the dest frame */
    val destRect: UnitRect,
    val order: Int,
) {
    enum class Kind { ANCESTOR, CHILD, OWN }
}

class TileDrawPlan(val items: List<TileDrawItem>, val requests: List<TileIndex>) {
    companion object {
        val EMPTY = TileDrawPlan(emptyList(), emptyList())
    }
}

fun TileIndex.ancestor(levelsUp: Int): TileIndex? =
    if (levelsUp < 0 || levelsUp > z) null else TileIndex(z - levelsUp, x shr levelsUp, y shr levelsUp)

val TileIndex.parent: TileIndex? get() = ancestor(1)

/** TL, TR, BL, BR */
val TileIndex.children: List<TileIndex>
    get() = listOf(
        TileIndex(z + 1, 2 * x, 2 * y), TileIndex(z + 1, 2 * x + 1, 2 * y),
        TileIndex(z + 1, 2 * x, 2 * y + 1), TileIndex(z + 1, 2 * x + 1, 2 * y + 1),
    )

/** where this tile sits inside an ancestor k levels up, y down */
fun TileIndex.unitRectIn(levelsUp: Int): UnitRect {
    val m = 2.0.pow(levelsUp)
    val k = 1 shl levelsUp
    return UnitRect(Math.floorMod(x, k) / m, Math.floorMod(y, k) / m, 1.0 / m, 1.0 / m)
}

/**
 * Parent/child fallback for every raster source (contract B), pinned by the
 * fixture's drawPlan cases. For a missing visible tile: the nearest loaded
 * ancestor up to max(4, tz - fallbackZoom) levels up (an EMPTY one stops the
 * search, nothing gets drawn), otherwise its loaded children. Requests put the
 * fallbackZoom ancestors of missing tiles first (deduped), then the missing
 * visible tiles in visible order.
 */
object TileDrawPlanner {
    const val MAX_ANCESTOR_LEVELS = 4

    fun plan(
        visible: List<VisibleTile>,
        state: (TileIndex) -> TileCacheState,
        fallbackZoom: Int?,
    ): TileDrawPlan {
        class Anc(val z: Int, val visibleIndex: Int, val item: TileDrawItem)
        val ancestors = ArrayList<Anc>()
        val children = ArrayList<TileDrawItem>()
        val own = ArrayList<TileDrawItem>()
        val fallbackRequests = LinkedHashSet<TileIndex>()
        val visibleRequests = ArrayList<TileIndex>()

        for ((vi, v) in visible.withIndex()) {
            val t = v.index
            when (state(t)) {
                TileCacheState.IMAGE -> {
                    own += TileDrawItem(TileDrawItem.Kind.OWN, t, v, UnitRect.FULL, UnitRect.FULL, 0)
                    continue
                }
                TileCacheState.EMPTY -> continue
                TileCacheState.MISSING -> Unit
            }
            visibleRequests += t
            val useFz = fallbackZoom != null && fallbackZoom >= 0 && fallbackZoom < t.z
            if (useFz) {
                val fa = t.ancestor(t.z - fallbackZoom!!)!!
                if (state(fa) == TileCacheState.MISSING) fallbackRequests += fa
            }
            val maxUp = if (useFz) max(MAX_ANCESTOR_LEVELS, t.z - fallbackZoom!!) else MAX_ANCESTOR_LEVELS
            var found = false
            for (k in 1..maxUp) {
                if (t.z - k < 0) break
                val a = t.ancestor(k)!!
                when (state(a)) {
                    TileCacheState.IMAGE -> {
                        ancestors += Anc(a.z, vi, TileDrawItem(TileDrawItem.Kind.ANCESTOR, a, v, t.unitRectIn(k), UnitRect.FULL, 0))
                        found = true
                    }
                    TileCacheState.EMPTY -> found = true
                    TileCacheState.MISSING -> Unit
                }
                if (found) break
            }
            if (found) continue
            for ((ci, c) in t.children.withIndex()) {
                if (state(c) == TileCacheState.IMAGE) {
                    val dx = (ci % 2) / 2.0
                    val dy = (ci / 2) / 2.0
                    children += TileDrawItem(TileDrawItem.Kind.CHILD, c, v, UnitRect.FULL, UnitRect(dx, dy, 0.5, 0.5), 0)
                }
            }
        }
        // coarsest ancestors first, ties in visible order. then children, then own tiles
        ancestors.sortWith(compareBy<Anc>({ it.z }, { it.visibleIndex }))
        val ordered = ArrayList<TileDrawItem>(ancestors.size + children.size + own.size)
        ancestors.forEach { ordered += it.item }
        ordered += children
        ordered += own
        val items = ordered.mapIndexed { i, it -> it.copy(order = i) }
        return TileDrawPlan(items, fallbackRequests.toList() + visibleRequests)
    }
}
