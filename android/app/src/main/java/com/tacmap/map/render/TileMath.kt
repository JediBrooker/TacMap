package com.tacmap.map.render

import kotlin.math.floor
import kotlin.math.roundToInt

/** One XYZ tile address. */
data class TileIndex(val z: Int, val x: Int, val y: Int)

/** Top-left screen point + on-screen edge length of a tile. */
data class TileFrame(val x: Double, val y: Double, val edge: Double)

/**
 * Which tiles a camera can see, and at what integer zoom. Mirrors the iOS
 * TileMath.
 */
object TileMath {

    /** Integer tile zoom for a fractional camera zoom, clamped to a source's
     *  range. Round rather than floor so we cross to the sharper level near the
     *  halfway point instead of staying blurry until the next whole zoom. */
    fun tileZoom(cameraZoom: Double, minZoom: Int, maxZoom: Int): Int =
        cameraZoom.roundToInt().coerceIn(minZoom, maxZoom)

    /**
     * Every tile touching the viewport at [tileZoom]. Samples the four viewport
     * corners and takes their world-point bounding box, so a rotated camera
     * still gets full coverage (a missing tile is a visible hole, an extra one
     * isn't). y is clamped to the pyramid; x wraps the antimeridian via modulo.
     */
    fun visibleTiles(camera: MapCamera, tileZoom: Int): List<TileIndex> {
        val n = 1 shl tileZoom
        val corners = listOf(
            0.0 to 0.0,
            camera.viewportWidth to 0.0,
            0.0 to camera.viewportHeight,
            camera.viewportWidth to camera.viewportHeight
        )
        val world = corners.map { (sx, sy) ->
            val (lat, lon) = camera.coordinate(sx, sy)
            WebMercator.worldPoint(lat, lon, tileZoom.toDouble())
        }
        val minTX = floor((world.minOf { it.x }) / WebMercator.TILE_SIZE).toInt()
        val maxTX = floor((world.maxOf { it.x }) / WebMercator.TILE_SIZE).toInt()
        val minTY = maxOf(0, floor((world.minOf { it.y }) / WebMercator.TILE_SIZE).toInt())
        val maxTY = minOf(n - 1, floor((world.maxOf { it.y }) / WebMercator.TILE_SIZE).toInt())

        // At low zoom the viewport can be wider than the whole world, so several
        // screen columns map onto the same wrapped tile. Dedup, else the tile
        // view draws the same tile several times.
        val seen = HashSet<TileIndex>()
        val tiles = ArrayList<TileIndex>()
        var tx = minTX
        while (tx <= maxTX) {
            val wrappedX = ((tx % n) + n) % n
            var ty = minTY
            while (ty <= maxTY) {
                val t = TileIndex(tileZoom, wrappedX, ty)
                if (seen.add(t)) tiles.add(t)
                ty++
            }
            tx++
        }
        return tiles
    }

    /** Top-left screen point + on-screen edge of a tile, for laying it out. The
     *  view rotates the whole tile layer by heading, so this projects the NW
     *  corner with heading ignored. */
    fun tileFrame(tile: TileIndex, camera: MapCamera): TileFrame {
        val scale = Math.pow(2.0, camera.zoom - tile.z)
        val edge = WebMercator.TILE_SIZE * scale
        val (lat, lon) = WebMercator.coordinate(
            Vec2(tile.x * WebMercator.TILE_SIZE, tile.y * WebMercator.TILE_SIZE),
            tile.z.toDouble()
        )
        val flat = camera.copy(headingDegrees = 0.0)
        val origin = flat.screenPoint(lat, lon)
        return TileFrame(origin.x, origin.y, edge)
    }
}
