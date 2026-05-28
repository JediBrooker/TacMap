package com.tacticalmaps.map

import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Local state holder for the measure tool. Lives in [MapScreen]'s
 * composition; tap events while [isActive] is true call [addPoint], and
 * the running [points] list is rendered as a dashed polyline.
 *
 * Distances use [Location.distanceBetween] (haversine on WGS84); area
 * uses the spherical-excess approximation, accurate enough for tactical
 * polygons on a tabletop scale.
 */
class MeasureSession {
    var isActive by mutableStateOf(false)
        private set
    val points = mutableStateListOf<Pair<Double, Double>>()  // lat, lng

    fun start() {
        isActive = true
        points.clear()
    }

    fun cancel() {
        isActive = false
        points.clear()
    }

    fun addPoint(lat: Double, lng: Double) {
        points.add(lat to lng)
    }

    fun undo() {
        if (points.isNotEmpty()) points.removeAt(points.lastIndex)
    }

    val totalDistanceMeters: Double
        get() {
            if (points.size < 2) return 0.0
            val out = FloatArray(1)
            var total = 0.0
            for (i in 0 until points.size - 1) {
                Location.distanceBetween(
                    points[i].first, points[i].second,
                    points[i + 1].first, points[i + 1].second,
                    out
                )
                total += out[0]
            }
            return total
        }

    val lastBearingDegrees: Double?
        get() {
            if (points.size < 2) return null
            val a = points[points.size - 2]
            val b = points.last()
            val phi1 = a.first  * PI / 180
            val phi2 = b.first  * PI / 180
            val dLambda = (b.second - a.second) * PI / 180
            val y = sin(dLambda) * cos(phi2)
            val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
            val theta = atan2(y, x) * 180 / PI
            return (theta + 360) % 360
        }

    val lastBearingMils: Int?
        get() = lastBearingDegrees?.let { (it * 6400 / 360).roundToInt() % 6400 }

    val enclosedAreaSquareMeters: Double?
        get() {
            if (points.size < 3) return null
            val R = 6_378_137.0
            var area = 0.0
            for (i in points.indices) {
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]
                val lat1 = p1.first  * PI / 180
                val lat2 = p2.first  * PI / 180
                val lon1 = p1.second * PI / 180
                val lon2 = p2.second * PI / 180
                area += (lon2 - lon1) * (2 + sin(lat1) + sin(lat2))
            }
            return abs(area * R * R / 2)
        }
}

/** Format helpers used by the measure HUD. */
object MeasureFormat {
    fun distance(m: Double): String = when {
        m < 1000 -> "%.0f m".format(m)
        m < 100_000 -> "%.2f km".format(m / 1000)
        else -> "%.0f km".format(m / 1000)
    }

    fun area(sqm: Double): String = when {
        sqm < 10_000 -> "%.0f m²".format(sqm)
        sqm < 1_000_000 -> "%.2f ha".format(sqm / 10_000)
        else -> "%.2f km²".format(sqm / 1_000_000)
    }
}
