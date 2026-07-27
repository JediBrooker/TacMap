package com.tacmap.map.render

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sinh

/** A point in the square Web Mercator world (pixels), or on screen. */
data class Vec2(val x: Double, val y: Double)

/**
 * Web Mercator (EPSG:3857) projection math for the custom map renderer that
 * replaces the Google Maps SDK. Byte-for-byte the same math as the iOS
 * WebMercator (same canonical fixtures in the tests), so the two renderers
 * agree. Pure Kotlin, no Android deps, so it unit-tests on the host JVM.
 *
 * "World point" = pixel coordinate in a square map that is `tileSize * 2^zoom`
 * points on a side, origin top-left, x east, y south. Latitude is clamped to
 * the Mercator limit (~85.051deg) where the projection diverges.
 */
object WebMercator {

    const val TILE_SIZE = 256.0
    /** WGS84 semi-major axis, the radius Web Mercator is defined against. */
    const val EARTH_RADIUS = 6_378_137.0
    val EARTH_CIRCUMFERENCE = 2 * PI * EARTH_RADIUS
    /** Past this latitude Mercator y goes to infinity. Standard clamp. */
    const val LAT_LIMIT = 85.051_128_779_806_589_5

    fun mapSize(zoom: Double): Double = TILE_SIZE * Math.pow(2.0, zoom)

    /** Coordinate -> world point at [zoom]. */
    fun worldPoint(latitude: Double, longitude: Double, zoom: Double): Vec2 {
        val size = mapSize(zoom)
        val lat = min(max(latitude, -LAT_LIMIT), LAT_LIMIT)
        val x = (longitude + 180) / 360 * size
        val sinLat = sin(lat * PI / 180)
        val y = (0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * size
        // A valid coord always lands inside the square; clamp off any
        // floating-point spill at the very edge so callers never see y<0.
        return Vec2(min(max(x, 0.0), size), min(max(y, 0.0), size))
    }

    /**
     * World point at [zoom] -> coordinate (latitude, longitude).
     *
     * Screen-space panning can ask for a point beyond the finite Mercator
     * square. Clamp that point before inversion so a camera centre, dragged
     * graphic, or crosshair placement can never escape valid Web Mercator
     * latitude or WGS84 longitude bounds.
     */
    fun coordinate(point: Vec2, zoom: Double): Pair<Double, Double> {
        val size = mapSize(zoom)
        if (!size.isFinite() || size <= 0.0) return 0.0 to 0.0
        val x = if (point.x.isNaN()) size / 2.0 else point.x.coerceIn(0.0, size)
        val y = if (point.y.isNaN()) size / 2.0 else point.y.coerceIn(0.0, size)
        val lon = (x / size * 360 - 180).coerceIn(-180.0, 180.0)
        val n = PI - 2 * PI * y / size
        val lat = (180 / PI * atan(sinh(n))).coerceIn(-LAT_LIMIT, LAT_LIMIT)
        return lat to lon
    }

    /** Ground distance one screen point covers, in metres, at a latitude/zoom.
     *  156543.03 m/pt at the equator, zoom 0. Feeds scale bar + symbol sizing. */
    fun groundResolution(latitude: Double, zoom: Double): Double {
        val lat = min(max(latitude, -LAT_LIMIT), LAT_LIMIT)
        return cos(lat * PI / 180) * EARTH_CIRCUMFERENCE / mapSize(zoom)
    }
}
