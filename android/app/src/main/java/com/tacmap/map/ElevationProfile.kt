package com.tacmap.map

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Elevation profile and line-of-sight maths, kept free of UI and network so
 * it can be pinned to `testdata/elevation_profile.json`. iOS mirrors this in
 * `ElevationProfile.swift`.
 */
object ElevationProfile {
    /** Mean Earth radius (IUGG), metres. */
    const val EARTH_RADIUS_METRES = 6_371_008.8
    /** Standard atmospheric refraction coefficient for visual sight lines:
     * light bends with the Earth, so the horizon is a little further away. */
    const val REFRACTION_COEFFICIENT = 0.13
    /** Two Open-Meteo requests' worth of points. */
    const val MAX_SAMPLES = 200
    /** About three DEM cells; the terrain data is no finer than this. */
    const val TARGET_SPACING_METRES = 30.0

    data class Coordinate(val latitude: Double, val longitude: Double)

    /** [distance] is metres along the path from its first point. */
    data class Sample(val distance: Double, val latitude: Double, val longitude: Double)

    /**
     * Evenly spaced points along [path], about every 30 m and never more than
     * [MAX_SAMPLES]; the first and last are the path's ends. Empty for fewer
     * than two points or a path of zero length.
     */
    fun samples(path: List<Coordinate>): List<Sample> {
        if (path.size < 2) return emptyList()
        val segments = (0 until path.size - 1).map { distance(path[it], path[it + 1]) }
        val length = segments.sum()
        if (!(length > 0) || !length.isFinite()) return emptyList()
        val count = minOf(MAX_SAMPLES, maxOf(2, floor(length / TARGET_SPACING_METRES).toInt() + 1))
        val cumulative = DoubleArray(segments.size + 1)
        for (i in segments.indices) cumulative[i + 1] = cumulative[i] + segments[i]

        val result = ArrayList<Sample>(count)
        var k = 0
        for (i in 0 until count) {
            val d = if (i == count - 1) length else length * i / (count - 1)
            while (k < segments.size - 1 && d > cumulative[k + 1]) k++
            val a = path[k]
            val b = path[k + 1]
            val segment = segments[k]
            val f = if (segment <= 0) 0.0 else ((d - cumulative[k]) / segment).coerceIn(0.0, 1.0)
            val latitude = a.latitude + f * (b.latitude - a.latitude)
            val longitude = normalizedLongitude(a.longitude + f * normalizedLongitude(b.longitude - a.longitude))
            result.add(Sample(d, latitude, longitude))
        }
        return result
    }

    /** [ascent] and [descent] are the sums of every rise and fall. */
    data class Stats(val minimum: Double, val maximum: Double, val ascent: Double, val descent: Double)

    fun stats(elevations: List<Double>): Stats? {
        if (elevations.isEmpty()) return null
        var ascent = 0.0
        var descent = 0.0
        for (i in 0 until elevations.size - 1) {
            val step = elevations[i + 1] - elevations[i]
            if (step > 0) ascent += step else descent -= step
        }
        return Stats(elevations.min(), elevations.max(), ascent, descent)
    }

    /**
     * [blocked] is true when terrain rises above the straight line between the
     * observer's eye and the target. [worstIndex] is where terrain comes
     * closest to, or furthest above, that line, and [worstMargin] is how far
     * above it the terrain is there (negative is clearance). [visible] says
     * whether the ground at each sample can be seen from the observer's eye;
     * hidden stretches are dead ground. [heights] is the sight line in the
     * same frame as the elevations, so it can be drawn over the profile.
     */
    data class SightLine(
        val blocked: Boolean,
        val worstIndex: Int?,
        val worstMargin: Double?,
        val visible: List<Boolean>,
        val heights: List<Double>,
    )

    /**
     * Line of sight from an observer [observerHeight] above the first sample
     * to a target [targetHeight] above the last, allowing for Earth curvature
     * and refraction. Null when there is nothing to compare.
     */
    fun lineOfSight(
        distances: List<Double>,
        elevations: List<Double>,
        observerHeight: Double,
        targetHeight: Double,
    ): SightLine? {
        if (distances.size != elevations.size || distances.size < 2) return null
        val total = distances.last()
        if (!(total > 0)) return null
        val effectiveRadius = EARTH_RADIUS_METRES / (1 - REFRACTION_COEFFICIENT)
        val drops = distances.map { it * it / (2 * effectiveRadius) }
        val terrain = elevations.indices.map { elevations[it] - drops[it] }
        val eye = elevations[0] + observerHeight
        val target = terrain.last() + targetHeight
        val line = distances.map { eye + (target - eye) * it / total }

        var worstIndex: Int? = null
        var worstMargin = Double.NEGATIVE_INFINITY
        for (i in 1 until distances.size - 1) {
            val margin = terrain[i] - line[i]
            if (margin > worstMargin) {
                worstMargin = margin
                worstIndex = i
            }
        }

        val visible = ArrayList<Boolean>(distances.size).apply { add(true) }
        var steepest = Double.NEGATIVE_INFINITY
        for (i in 1 until distances.size) {
            val slope = (terrain[i] - eye) / distances[i]
            visible.add(slope >= steepest)
            steepest = maxOf(steepest, slope)
        }

        return SightLine(
            blocked = worstIndex != null && worstMargin > 0,
            worstIndex = worstIndex,
            worstMargin = if (worstIndex == null) null else worstMargin,
            visible = visible,
            heights = line.indices.map { line[it] + drops[it] },
        )
    }

    /**
     * Heights the chart spans: all of the terrain, the eye and the target,
     * and the sight line only as far as one terrain span (at least 50 m)
     * beyond the terrain, so on a long line the Earth's curvature doesn't
     * flatten the terrain into a strip. Null without elevations.
     */
    fun chartRange(elevations: List<Double>, sightHeights: List<Double>): ClosedFloatingPointRange<Double>? {
        val terrainLow = elevations.minOrNull() ?: return null
        val terrainHigh = elevations.maxOrNull() ?: return null
        val pad = max(terrainHigh - terrainLow, 50.0)
        var low = terrainLow
        var high = terrainHigh
        for (height in sightHeights) {
            low = min(low, max(height, terrainLow - pad))
            high = max(high, min(height, terrainHigh + pad))
        }
        if (sightHeights.isNotEmpty()) high = maxOf(high, sightHeights.first(), sightHeights.last())
        return low..high
    }

    /** Great-circle distance, metres. */
    fun distance(a: Coordinate, b: Coordinate): Double {
        val phi1 = Math.toRadians(a.latitude)
        val phi2 = Math.toRadians(b.latitude)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(normalizedLongitude(b.longitude - a.longitude))
        val h = sin(dPhi / 2) * sin(dPhi / 2) + cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_METRES * asin(minOf(1.0, sqrt(h)))
    }

    fun normalizedLongitude(value: Double): Double {
        var wrapped = (value + 180) % 360
        if (wrapped < 0) wrapped += 360
        return wrapped - 180
    }
}
