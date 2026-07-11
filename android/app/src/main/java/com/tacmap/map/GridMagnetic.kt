package com.tacmap.map

import android.hardware.GeomagneticField
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.roundToInt
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
 * Returns the raw angle in degrees (+E / -W), or null when there's no
 * coordinate to compute for. [formatGridMagnetic] turns it into banner text.
 */
fun gridMagneticDegrees(lat: Double?, lon: Double?, altitudeMetres: Double? = null): Double? {
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

    return declination - convergence
}

/**
 * Banner text for a G-M angle. Mils by default (NATO 6400/circle - what a
 * compass dial and a fire mission actually read); tapping the banner flips it
 * to degrees. E/W suffix. e.g. "G-M 222 mils E" or "G-M 12.5°E".
 */
fun formatGridMagnetic(degrees: Double, mils: Boolean): String {
    val dir = if (degrees >= 0) "E" else "W"
    return if (mils) {
        val m = (abs(degrees) * 6400.0 / 360.0).roundToInt()
        "G-M $m mils $dir"
    } else {
        "G-M %.1f°%s".format(abs(degrees), dir)
    }
}
