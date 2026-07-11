package com.tacmap.map.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Viewing state of the custom map: centre, zoom, heading, viewport size. The
 * single source of the coord<->screen projection every overlay reads, replacing
 * the Google Maps SDK's `Projection.toScreenLocation` / `fromScreenLocation`.
 *
 * [headingDegrees] is the map's clockwise rotation: 0 = north up. Positive turns
 * the map clockwise on screen, matching the existing rotate-gesture behaviour.
 * [screenPoint] and [coordinate] are exact inverses for any heading, which is
 * the property the whole overlay stack depends on.
 *
 * Immutable value: gestures produce a new camera, the view diffs and redraws.
 * Mirrors the iOS MapCamera (same fixtures in the tests).
 */
data class MapCamera(
    val centerLat: Double,
    val centerLon: Double,
    val zoom: Double,
    val headingDegrees: Double,
    val viewportWidth: Double,
    val viewportHeight: Double
) {
    private val viewportCenterX get() = viewportWidth / 2
    private val viewportCenterY get() = viewportHeight / 2

    /** Coordinate -> screen point (origin top-left). */
    fun screenPoint(latitude: Double, longitude: Double): Vec2 {
        val w = WebMercator.worldPoint(latitude, longitude, zoom)
        val c = WebMercator.worldPoint(centerLat, centerLon, zoom)
        val (dx, dy) = rotate(w.x - c.x, w.y - c.y, -headingDegrees)
        return Vec2(viewportCenterX + dx, viewportCenterY + dy)
    }

    /** Screen point -> coordinate (latitude, longitude). Inverse of [screenPoint]. */
    fun coordinate(x: Double, y: Double): Pair<Double, Double> {
        val (dx, dy) = rotate(x - viewportCenterX, y - viewportCenterY, headingDegrees)
        val c = WebMercator.worldPoint(centerLat, centerLon, zoom)
        return WebMercator.coordinate(Vec2(c.x + dx, c.y + dy), zoom)
    }

    /** Metres per screen point at the current centre + zoom. */
    val metresPerPoint: Double get() = WebMercator.groundResolution(centerLat, zoom)

    private fun rotate(dx: Double, dy: Double, degrees: Double): Pair<Double, Double> {
        if (degrees == 0.0) return dx to dy
        val r = degrees * PI / 180
        return (dx * cos(r) - dy * sin(r)) to (dx * sin(r) + dy * cos(r))
    }
}
