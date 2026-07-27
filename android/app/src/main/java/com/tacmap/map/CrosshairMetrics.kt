package com.tacmap.map

import com.tacmap.waypoints.Waypoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance between a user fix and the map crosshair. */
internal fun crosshairDistanceMetres(
    userLat: Double,
    userLng: Double,
    crosshairLat: Double,
    crosshairLng: Double
): Double? {
    if (!userLat.isFinite() || !userLng.isFinite() ||
        !crosshairLat.isFinite() || !crosshairLng.isFinite()
    ) return null
    if (userLat !in -90.0..90.0 || crosshairLat !in -90.0..90.0 ||
        userLng !in -180.0..180.0 || crosshairLng !in -180.0..180.0
    ) return null

    val lat1 = Math.toRadians(userLat)
    val lat2 = Math.toRadians(crosshairLat)
    val dLat = lat2 - lat1
    val dLng = Math.toRadians(crosshairLng - userLng)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
    val angle = 2 * atan2(sqrt(a.coerceIn(0.0, 1.0)), sqrt((1.0 - a).coerceAtLeast(0.0)))
    return (EARTH_MEAN_RADIUS_METRES * angle).takeIf { it.isFinite() }
}

/** Coordinate-only waypoint mutation used by the Move to Crosshair action. */
internal fun moveWaypointToCrosshair(
    waypoint: Waypoint,
    crosshairLat: Double,
    crosshairLng: Double
): Waypoint = waypoint.copy(latitude = crosshairLat, longitude = crosshairLng)

private const val EARTH_MEAN_RADIUS_METRES = 6_371_008.8
