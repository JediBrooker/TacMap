package com.tacmap.map

import com.tacmap.map.render.WebMercator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Width and height in points (dp on Android). */
internal data class TaskGraphicSize(val width: Double, val height: Double)

/**
 * Ground-size maths for task graphics (tactical control measures). Mirrors iOS
 * `TacticalSymbolOverlay.bubbleSize`, `MapGeometry.zoomScaleFactor` and
 * `MapViewModel.defaultControlMeasureScale`, so a synced task covers the same
 * ground, and so looks the same size, on both platforms.
 *
 * A task is [BASE_SIZE_POINTS] × scale points wide at the reference zoom, where
 * one point covers [REFERENCE_METRES_PER_POINT] metre: scale 1 is 64 m of
 * ground, and the graphic grows and shrinks with the map. The custom map
 * camera projects in dp exactly as the iOS camera projects in points, so the
 * same numbers apply with dp in place of points.
 */
internal object TaskGraphicSizing {
    const val BASE_SIZE_POINTS = 64.0
    const val REFERENCE_METRES_PER_POINT = 1.0
    /** Keeps tasks visible from building level out to continental zoom. */
    const val MIN_ZOOM_SCALE_FACTOR = 0.005
    const val MAX_ZOOM_SCALE_FACTOR = 50.0
    /** Floor per axis so a zoomed-out task never vanishes or loses its tap target. */
    const val MIN_SIDE_POINTS = 8.0
    /** New tasks enter at roughly 80 pt wide, about a tenth of the screen. */
    const val DEFAULT_SCALE_PER_METRE_PER_POINT = 1.18

    /** 1 at the reference zoom; 2 after zooming in one level, 0.5 after zooming out one. */
    fun zoomScaleFactor(metresPerPoint: Double): Double {
        if (metresPerPoint.isNaN()) return 1.0
        return (REFERENCE_METRES_PER_POINT / metresPerPoint)
            .coerceIn(MIN_ZOOM_SCALE_FACTOR, MAX_ZOOM_SCALE_FACTOR)
    }

    /** The task's box on screen: 64 × scale × zoom factor on each axis. */
    fun displaySize(scaleX: Double, scaleY: Double, metresPerPoint: Double): TaskGraphicSize {
        val zoom = zoomScaleFactor(metresPerPoint)
        return TaskGraphicSize(side(scaleX, zoom), side(scaleY, zoom))
    }

    private fun side(scale: Double, zoom: Double): Double {
        val raw = BASE_SIZE_POINTS * scale * zoom
        return if (raw.isNaN()) MIN_SIDE_POINTS else max(MIN_SIDE_POINTS, raw)
    }

    /** Scale for a task created now, clamped to the editor's slider range. */
    fun defaultScale(metresPerPoint: Double): Double {
        val raw = DEFAULT_SCALE_PER_METRE_PER_POINT * metresPerPoint
        if (raw.isNaN()) return 1.0
        return raw.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE)
    }

    /** [defaultScale] at the centre of [viewport]; 1× before the first camera fix. */
    fun defaultScale(viewport: MapViewportState?): Double =
        viewport?.let { defaultScale(WebMercator.groundResolution(it.latitude, it.zoom)) } ?: 1.0

    /**
     * The artwork's own size inside the task box. iOS fits the asset into the
     * square symbol (keeping its aspect) and then stretches that square to the
     * box, so a wide graphic stays wide and scale X/Y still stretch it.
     */
    fun artworkSize(box: TaskGraphicSize, artworkAspect: Double): TaskGraphicSize {
        val aspect = if (artworkAspect.isFinite() && artworkAspect > 0.0) artworkAspect else 1.0
        return if (aspect >= 1.0) {
            TaskGraphicSize(box.width, box.height / aspect)
        } else {
            TaskGraphicSize(box.width * aspect, box.height)
        }
    }

    /**
     * Axis-aligned screen bounds of the drawn task. Like iOS, the artwork is
     * rotated inside the unit square first and the square is then stretched to
     * the box, so scale X and Y always act along the screen axes.
     */
    fun screenBounds(box: TaskGraphicSize, artworkAspect: Double, rotationDegrees: Double): TaskGraphicSize {
        val unit = artworkSize(TaskGraphicSize(1.0, 1.0), artworkAspect)
        val radians = Math.toRadians(if (rotationDegrees.isFinite()) rotationDegrees else 0.0)
        val c = abs(cos(radians))
        val s = abs(sin(radians))
        val rotatedWidth = unit.width * c + unit.height * s
        val rotatedHeight = unit.width * s + unit.height * c
        return TaskGraphicSize(rotatedWidth * box.width, rotatedHeight * box.height)
    }
}
