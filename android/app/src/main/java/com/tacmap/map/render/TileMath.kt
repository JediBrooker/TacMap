package com.tacmap.map.render

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** One XYZ tile address. */
data class TileIndex(val z: Int, val x: Int, val y: Int)

/**
 * Which tiles a camera can see, and at what integer zoom. Mirrors the iOS
 * TileMath. Shared rules in plans/WP2-render-shared_contract.md A + B.
 */
object TileMath {

    /** nothing is requested or drawn when the source would have to shrink tiles past this */
    const val MAX_UNDERZOOM_LEVELS = 2.0

    /** each tile square grows by this many dp before the viewport test, so edge slivers still count */
    const val VISIBLE_INFLATE_DP = 1.0

    /** Integer tile zoom for a fractional camera zoom, clamped to a source's
     *  range. Round (ties up) rather than floor so we cross to the sharper level near the
     *  halfway point instead of staying blurry until the next whole zoom. */
    fun tileZoom(cameraZoom: Double, minZoom: Int, maxZoom: Int): Int =
        cameraZoom.roundToInt().coerceIn(minZoom, maxZoom)

    /** a minZoom 10 pack at camera z2 would want thousands of tiles, skip the source instead */
    fun underzoomHidden(tileZoom: Int, cameraZoom: Double): Boolean = tileZoom - cameraZoom > MAX_UNDERZOOM_LEVELS

    /**
     * Every tile whose square (grown by 1 dp) overlaps the rotated viewport,
     * separating axis test. Columns stay unwrapped so a camera over the
     * antimeridian lays tiles out in the right place; the index x is wrapped.
     * Sorted by tile centre distance to the viewport centre, ties by row then col.
     */
    fun visibleTiles(camera: MapCamera, tileZoom: Int): List<VisibleTile> {
        if (camera.viewportWidth <= 0.0 || camera.viewportHeight <= 0.0) return emptyList()
        val n = 1 shl tileZoom
        val tile = WebMercator.TILE_SIZE
        // world units at tileZoom per screen dp
        val unitPerDp = 2.0.pow(tileZoom - camera.zoom)
        val c = WebMercator.worldPoint(camera.centerLat, camera.centerLon, tileZoom.toDouble())
        val hw = camera.viewportWidth / 2.0 * unitPerDp
        val hh = camera.viewportHeight / 2.0 * unitPerDp
        val r = camera.headingDegrees * PI / 180.0
        // screen axes in world space (see MapCamera.coordinate)
        val ux = cos(r); val uy = sin(r)
        val vx = -sin(r); val vy = cos(r)
        val inflate = VISIBLE_INFLATE_DP * unitPerDp
        val ex = abs(ux) * hw + abs(vx) * hh
        val ey = abs(uy) * hw + abs(vy) * hh
        val minCol = floor((c.x - ex - inflate) / tile).toInt()
        val maxCol = floor((c.x + ex + inflate) / tile).toInt()
        val minRow = max(0, floor((c.y - ey - inflate) / tile).toInt())
        val maxRow = min(n - 1, floor((c.y + ey + inflate) / tile).toInt())
        val out = ArrayList<Pair<Double, VisibleTile>>()
        val half = tile / 2.0 + inflate
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                val cx = (col + 0.5) * tile
                val cy = (row + 0.5) * tile
                val dx = cx - c.x
                val dy = cy - c.y
                // world axes: the axis aligned extents already overlap by construction of the range,
                // the two viewport axes need checking
                val pu = dx * ux + dy * uy
                val radU = half * (abs(ux) + abs(uy))
                if (abs(pu) > hw + radU) continue
                val pv = dx * vx + dy * vy
                val radV = half * (abs(vx) + abs(vy))
                if (abs(pv) > hh + radV) continue
                if (abs(dx) > ex + half || abs(dy) > ey + half) continue
                val wrapped = Math.floorMod(col, n)
                out += (dx * dx + dy * dy) to VisibleTile(TileIndex(tileZoom, wrapped, row), col, row)
            }
        }
        out.sortWith(compareBy<Pair<Double, VisibleTile>>({ it.first }, { it.second.row }, { it.second.col }))
        return out.map { it.second }
    }

    /** viewport centre in z0 world units / 256 (0..1), for the PDF scheduler's distance order */
    fun viewportCentreUnit(camera: MapCamera): Pair<Double, Double> {
        val c = WebMercator.worldPoint(camera.centerLat, camera.centerLon, 0.0)
        return c.x / WebMercator.TILE_SIZE to c.y / WebMercator.TILE_SIZE
    }
}

/**
 * One layout origin per frame: frame(col, row) = origin + (col - col0, row - row0) * edge.
 * Every tile edge comes out of the same expression as its neighbour's, so shared
 * edges are bit identical and non antialiased drawing can't leave a seam.
 * Heading is ignored, the view rotates the whole tile layer.
 */
class TileGrid(camera: MapCamera, val tileZoom: Int) {
    /** on screen edge of one tile, dp */
    val edge: Double = WebMercator.TILE_SIZE * 2.0.pow(camera.zoom - tileZoom)
    private val col0: Int
    private val row0: Int
    private val originX: Double
    private val originY: Double

    init {
        val c = WebMercator.worldPoint(camera.centerLat, camera.centerLon, tileZoom.toDouble())
        val scale = 2.0.pow(camera.zoom - tileZoom)
        col0 = floor(c.x / WebMercator.TILE_SIZE).toInt()
        row0 = floor(c.y / WebMercator.TILE_SIZE).toInt()
        originX = camera.viewportWidth / 2.0 + (col0 * WebMercator.TILE_SIZE - c.x) * scale
        originY = camera.viewportHeight / 2.0 + (row0 * WebMercator.TILE_SIZE - c.y) * scale
    }

    fun left(col: Int): Double = originX + (col - col0) * edge
    fun top(row: Int): Double = originY + (row - row0) * edge

    /** [left, top, right, bottom] dp */
    fun frame(v: VisibleTile): DoubleArray =
        doubleArrayOf(left(v.col), top(v.row), left(v.col + 1), top(v.row + 1))
}
