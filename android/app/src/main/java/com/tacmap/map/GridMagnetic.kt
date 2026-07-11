package com.tacmap.map

import android.hardware.GeomagneticField
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Grid-magnetic angle (grid north -> magnetic north) for the MGRS banner, so a
 * user can convert a grid bearing to a compass bearing. East positive.
 *
 * G-M = magnetic declination - UTM grid convergence.
 *  - declination (true -> magnetic) comes from the platform's World Magnetic
 *    Model via [GeomagneticField] - always available, no network, no sensor.
 *  - convergence (true -> grid) = atan(tan(lon - centralMeridian) * sin(lat)),
 *    the small rotation between true north and the UTM grid's north.
 *
 * Returns a preformatted string like "G-M 13.4°E" / "G-M 2.1°W", or null when
 * there's no coordinate to compute for.
 */
fun gridMagneticLabel(lat: Double?, lon: Double?, altitudeMetres: Double? = null): String? {
    if (lat == null || lon == null) return null
    val declination = GeomagneticField(
        lat.toFloat(), lon.toFloat(), (altitudeMetres ?: 0.0).toFloat(),
        System.currentTimeMillis()
    ).declination.toDouble()

    // UTM zone central meridian for this longitude.
    val zone = floor((lon + 180.0) / 6.0).toInt() + 1
    val centralMeridian = -180.0 + (zone - 1) * 6.0 + 3.0
    val convergence = Math.toDegrees(
        atan(tan(Math.toRadians(lon - centralMeridian)) * sin(Math.toRadians(lat)))
    )

    val gm = declination - convergence
    val dir = if (gm >= 0) "E" else "W"
    return "G-M %.1f°%s".format(abs(gm), dir)
}
