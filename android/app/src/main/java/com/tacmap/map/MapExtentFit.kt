package com.tacmap.map

import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.map.render.Vec2
import com.tacmap.map.render.WebMercator
import kotlin.math.log2
import kotlin.math.min

/** Camera centre and zoom that frame a whole map extent. */
internal data class ExtentFit(val latitude: Double, val longitude: Double, val zoom: Double)

/** Share of the viewport left free on each side, so the edges of an imported
 *  sheet don't sit under the header or the bottom pills. */
internal const val EXTENT_FIT_MARGIN = 0.05
internal const val EXTENT_FIT_MIN_ZOOM = 2.0
internal const val EXTENT_FIT_MAX_ZOOM = 19.0

/**
 * Frames all of [bounds] in a viewport of [viewportWidth] x [viewportHeight]
 * points (the renderer's dp). iOS flies to an imported map's whole coverage
 * region the same way, with the zoom clamped to 2...19; this also checks the
 * width, so a wide sheet on a portrait phone fits too. Bounds that cross the
 * antimeridian (west edge east of the east edge) are handled. Returns null for
 * an unusable viewport or inverted latitudes.
 */
internal fun fitExtent(
    bounds: Wgs84Bounds,
    viewportWidth: Double,
    viewportHeight: Double,
    margin: Double = EXTENT_FIT_MARGIN,
): ExtentFit? {
    if (!(viewportWidth > 0.0) || !(viewportHeight > 0.0)) return null
    if (!(bounds.northeast.latitude >= bounds.southwest.latitude)) return null
    val world = WebMercator.mapSize(0.0)
    val sw = WebMercator.worldPoint(bounds.southwest.latitude, bounds.southwest.longitude, 0.0)
    val ne = WebMercator.worldPoint(bounds.northeast.latitude, bounds.northeast.longitude, 0.0)
    val spanX = (ne.x - sw.x).let { if (it < 0.0) it + world else it }
    val spanY = sw.y - ne.y
    val fill = (1.0 - 2.0 * margin.coerceIn(0.0, 0.45))
    // An axis with no extent (a single point) doesn't constrain the zoom.
    val scaleX = if (spanX > 0.0) viewportWidth * fill / spanX else Double.POSITIVE_INFINITY
    val scaleY = if (spanY > 0.0) viewportHeight * fill / spanY else Double.POSITIVE_INFINITY
    val zoom = log2(min(scaleX, scaleY))
        .takeIf { it.isFinite() }
        ?.coerceIn(EXTENT_FIT_MIN_ZOOM, EXTENT_FIT_MAX_ZOOM)
        ?: EXTENT_FIT_MAX_ZOOM
    val centreX = (sw.x + spanX / 2.0).let { if (it > world) it - world else it }
    val (latitude, longitude) = WebMercator.coordinate(Vec2(centreX, (sw.y + ne.y) / 2.0), 0.0)
    return ExtentFit(latitude, longitude, zoom)
}
