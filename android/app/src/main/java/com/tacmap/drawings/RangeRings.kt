package com.tacmap.drawings

import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.Messages
import com.tacmap.waypoints.SymbolListAffiliation
import com.tacmap.waypoints.Waypoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Range rings around a symbol, generated as ordinary closed line drawings so
 * they sync, export, hide with their layer and undo like any other drawing.
 * Each ring remembers its symbol and radius and is regenerated when the symbol
 * moves (see [RangeRingFollower]). iOS mirrors this in `RangeRings.swift`;
 * `testdata/range_rings.json` pins both.
 */
object RangeRings {
    /** Points per ring before the closing point (every 5 degrees). */
    const val SEGMENTS = 72
    const val MAX_RINGS = 10
    /** Keeps generated rings well inside the range where a closed ring on a
     * Web Mercator map is still a meaningful shape. */
    const val MAX_RADIUS_METRES = 1_000_000.0
    const val STROKE_WIDTH_DP = 2f

    val FRIEND_COLOR: Int = 0xFF0E5FD8.toInt()
    val HOSTILE_COLOR: Int = 0xFFD8281F.toInt()
    val NEUTRAL_COLOR: Int = 0xFF1E8A34.toInt()
    val UNKNOWN_COLOR: Int = 0xFFE2A400.toInt()

    /** Radii for [count] rings [intervalMetres] apart, or null when the request
     * is outside the supported range. */
    fun radii(intervalMetres: Double, count: Int): List<Double>? {
        if (!intervalMetres.isFinite() || intervalMetres <= 0.0) return null
        if (count !in 1..MAX_RINGS || intervalMetres * count > MAX_RADIUS_METRES) return null
        return (1..count).map { intervalMetres * it }
    }

    /** Closed ring of [SEGMENTS] + 1 points clockwise from true north. The final
     * point repeats the first so line renderers and GeoJSON close it. */
    fun ring(centerLat: Double, centerLng: Double, radiusMetres: Double): List<DrawingPoint> {
        val points = (0 until SEGMENTS).map { index ->
            destination(centerLat, centerLng, index * 360.0 / SEGMENTS, radiusMetres)
        }
        return points + points.first()
    }

    /** Stroke colour for rings around [waypoint]: its affiliation colour, or
     * the layer colour for symbols without one. */
    fun strokeColor(waypoint: Waypoint, layerColor: Int?): Int = when (SymbolListAffiliation.of(waypoint)) {
        SymbolListAffiliation.FRIEND -> FRIEND_COLOR
        SymbolListAffiliation.HOSTILE -> HOSTILE_COLOR
        SymbolListAffiliation.NEUTRAL -> NEUTRAL_COLOR
        SymbolListAffiliation.UNKNOWN -> UNKNOWN_COLOR
        SymbolListAffiliation.OTHER -> layerColor ?: DrawingDocument.FRIENDLY_LAYER_COLOR
    }

    /** Drawings for every ring, on the symbol's layer. [density] converts the
     * portable stroke width to the physical pixels Android stores. */
    fun features(
        waypoint: Waypoint,
        radii: List<Double>,
        layerColor: Int?,
        density: Float,
        createdAt: Long = System.currentTimeMillis(),
    ): List<DrawingFeature> {
        val stroke = strokeColor(waypoint, layerColor)
        return radii.map { radius ->
            DrawingFeature(
                name = Messages.ringsRingName(DisplayFormat.distance(radius), waypoint.name),
                geometry = DrawingGeometry.LINE,
                points = ring(waypoint.latitude, waypoint.longitude, radius),
                layerId = waypoint.layerId,
                strokeColor = stroke,
                fillColor = 0,
                strokeWidth = STROKE_WIDTH_DP * density.coerceAtLeast(0f),
                strokeStyle = DrawingStrokeStyle.DASHED,
                createdAt = createdAt,
                anchorId = waypoint.id,
                ringRadiusMetres = radius,
            )
        }
    }

    /** [feature] regenerated around its symbol's current position, or null when
     * it is not a ring of [waypoint] or already sits exactly there. */
    fun followed(feature: DrawingFeature, waypoint: Waypoint): DrawingFeature? {
        if (feature.anchorId != waypoint.id) return null
        val radius = feature.ringRadiusMetres ?: return null
        if (!(radius > 0.0 && radius <= MAX_RADIUS_METRES)) return null
        val points = ring(waypoint.latitude, waypoint.longitude, radius)
        if (feature.points == points && feature.rotationDegrees == 0.0 &&
            feature.scaleX == 1.0 && feature.scaleY == 1.0
        ) return null
        return feature.copy(points = points, rotationDegrees = 0.0, scaleX = 1.0, scaleY = 1.0)
    }

    // WGS84 geodesic direct problem (Vincenty, 1975).
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val B = (1 - F) * A

    fun destination(lat: Double, lng: Double, bearingDegrees: Double, distanceMetres: Double): DrawingPoint {
        val s = distanceMetres
        val alpha1 = Math.toRadians(bearingDegrees)
        val sinAlpha1 = sin(alpha1)
        val cosAlpha1 = cos(alpha1)
        val tanU1 = (1 - F) * tan(Math.toRadians(lat))
        val cosU1 = 1 / sqrt(1 + tanU1 * tanU1)
        val sinU1 = tanU1 * cosU1
        val sigma1 = atan2(tanU1, cosAlpha1)
        val sinAlpha = cosU1 * sinAlpha1
        val cos2Alpha = 1 - sinAlpha * sinAlpha
        val u2 = cos2Alpha * (A * A - B * B) / (B * B)
        val bigA = 1 + u2 / 16384 * (4096 + u2 * (-768 + u2 * (320 - 175 * u2)))
        val bigB = u2 / 1024 * (256 + u2 * (-128 + u2 * (74 - 47 * u2)))

        var sigma = s / (B * bigA)
        var cos2SigmaM: Double
        var sinSigma: Double
        var cosSigma: Double
        for (iteration in 0 until 200) {
            cos2SigmaM = cos(2 * sigma1 + sigma)
            sinSigma = sin(sigma)
            cosSigma = cos(sigma)
            val deltaSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                - bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
            val previous = sigma
            sigma = s / (B * bigA) + deltaSigma
            if (abs(sigma - previous) < 1e-12) break
        }
        cos2SigmaM = cos(2 * sigma1 + sigma)
        sinSigma = sin(sigma)
        cosSigma = cos(sigma)

        val x = sinU1 * sinSigma - cosU1 * cosSigma * cosAlpha1
        val phi2 = atan2(
            sinU1 * cosSigma + cosU1 * sinSigma * cosAlpha1,
            (1 - F) * sqrt(sinAlpha * sinAlpha + x * x),
        )
        val lambda = atan2(sinSigma * sinAlpha1, cosU1 * cosSigma - sinU1 * sinSigma * cosAlpha1)
        val c = F / 16 * cos2Alpha * (4 + F * (4 - 3 * cos2Alpha))
        val l = lambda - (1 - c) * F * sinAlpha *
            (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
        return DrawingPoint(
            latitude = Math.toDegrees(phi2),
            longitude = normalizedLongitude(lng + Math.toDegrees(l)),
        )
    }

    private fun normalizedLongitude(value: Double): Double =
        ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
}
