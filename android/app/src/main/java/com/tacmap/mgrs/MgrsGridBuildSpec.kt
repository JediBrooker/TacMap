package com.tacmap.mgrs

import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.Vec2
import com.tacmap.map.render.WebMercator
import mil.nga.mgrs.grid.GridType
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow

/**
 * What one grid build covers, and when the overlay has to throw it away.
 *
 * Rebuild only when the drawn / labelled levels change, the zoom moves 0.5 or
 * more, the centre moves 64 dp or more, or the viewport resizes. Heading never
 * rebuilds. The coverage square is big enough that no camera inside that
 * envelope (any heading) can see past it, so panning never shows missing lines
 * while the next build runs off the main thread. Numbers pinned in
 * testdata/mgrs_grid.json policy.cache.
 */
class MgrsGridBuildSpec(
    val centerLat: Double,
    val centerLon: Double,
    /** buildZoom */
    val zoom: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
    val pxPerDp: Double,
    val drawn: List<GridType>,
    val labelled: List<GridType>,
) {
    /** densify for the most zoomed-in camera this build still serves */
    val densifyZoom: Double get() = zoom + REBUILD_ZOOM_DELTA

    val coverageHalfSideDp: Double = coverageHalfSideDp(viewportWidth, viewportHeight)

    /** [south, west, north, east] of the north-up coverage square at buildZoom. */
    fun coverageBounds(): DoubleArray =
        coverageBounds(centerLat, centerLon, zoom, coverageHalfSideDp)

    fun isStaleFor(camera: MapCamera, pxPerDp: Double, lod: MgrsGridRenderer.Lod): Boolean {
        if (lod.drawn != drawn || lod.labelled != labelled) return true
        // nothing drawn either way, don't churn builds while panning a world view
        if (drawn.isEmpty()) return false
        if (pxPerDp != this.pxPerDp) return true
        if (camera.viewportWidth != viewportWidth || camera.viewportHeight != viewportHeight) return true
        if (abs(camera.zoom - zoom) >= REBUILD_ZOOM_DELTA) return true
        return centreMoveDp(camera) >= REBUILD_CENTRE_MOVE_DP
    }

    /** How far the camera centre is from the build centre, in dp at the camera's zoom. */
    fun centreMoveDp(camera: MapCamera): Double {
        val a = WebMercator.worldPoint(centerLat, centerLon, camera.zoom)
        val b = WebMercator.worldPoint(camera.centerLat, camera.centerLon, camera.zoom)
        return hypot(a.x - b.x, a.y - b.y)
    }

    fun build(checkCancelled: () -> Unit = {}): MgrsGridRenderer.GridGeometry {
        val b = coverageBounds()
        return MgrsGridRenderer.build(
            south = b[0], west = b[1], north = b[2], east = b[3],
            drawn = drawn,
            withSquares = GridType.HUNDRED_KILOMETER in labelled,
            densifyZoom = densifyZoom,
            pxPerDp = pxPerDp,
            checkCancelled = checkCancelled,
        )
    }

    companion object {
        const val REBUILD_ZOOM_DELTA = 0.5
        const val REBUILD_CENTRE_MOVE_DP = 64.0
        const val COVERAGE_MARGIN_DP = 32.0

        fun coverageHalfSideDp(widthDp: Double, heightDp: Double): Double =
            (hypot(widthDp, heightDp) / 2 + REBUILD_CENTRE_MOVE_DP) * 2.0.pow(REBUILD_ZOOM_DELTA) +
                COVERAGE_MARGIN_DP

        fun coverageBounds(lat: Double, lon: Double, zoom: Double, halfSideDp: Double): DoubleArray {
            val c = WebMercator.worldPoint(lat, lon, zoom)
            val nw = WebMercator.coordinate(Vec2(c.x - halfSideDp, c.y - halfSideDp), zoom)
            val se = WebMercator.coordinate(Vec2(c.x + halfSideDp, c.y + halfSideDp), zoom)
            return doubleArrayOf(se.first, nw.second, nw.first, se.second)
        }

        fun forCamera(
            camera: MapCamera,
            pxPerDp: Double,
            lod: MgrsGridRenderer.Lod = MgrsGridRenderer.lod(camera.zoom, camera.centerLat),
        ) = MgrsGridBuildSpec(
            centerLat = camera.centerLat,
            centerLon = camera.centerLon,
            zoom = camera.zoom,
            viewportWidth = camera.viewportWidth,
            viewportHeight = camera.viewportHeight,
            pxPerDp = pxPerDp,
            drawn = lod.drawn,
            labelled = lod.labelled,
        )
    }
}
