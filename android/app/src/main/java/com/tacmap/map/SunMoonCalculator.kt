package com.tacmap.map

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Sun and moon events for one local day, as epoch milliseconds. Null means the
 * event does not happen inside the day (polar day or night, or a day without a
 * moonrise). */
data class SunMoonDay(
    /** Begin morning nautical twilight: sun centre rises through -12°. */
    val bmnt: Long?,
    /** Begin morning civil twilight: sun centre rises through -6°. */
    val bmct: Long?,
    val sunrise: Long?,
    val sunset: Long?,
    /** End evening civil twilight. */
    val eect: Long?,
    /** End evening nautical twilight. */
    val eent: Long?,
    val moonrise: Long?,
    val moonset: Long?,
    /** Illuminated fraction of the lunar disc (0..1) at the middle of the day. */
    val moonIllumination: Double,
    val moonWaxing: Boolean,
)

/**
 * Offline sun and moon times. Low-precision solar (NOAA/Meeus) and lunar
 * (Astronomical Almanac) positions keep this dependency-free and on-device:
 * within about 30 s of PyEphem for the sun and 6 min for the moon.
 * iOS mirrors this in `SunMoonCalculator.swift`;
 * `testdata/sun_moon_times.json` pins both.
 */
object SunMoonCalculator {
    const val STEP_SECONDS = 300.0
    const val BISECTIONS = 16
    const val SUNRISE_ALTITUDE = -0.833
    const val CIVIL_ALTITUDE = -6.0
    const val NAUTICAL_ALTITUDE = -12.0

    private const val AU_KILOMETRES = 149_597_870.7
    private const val EARTH_RADIUS_KILOMETRES = 6378.14

    /** Start (inclusive) and end (exclusive) epoch milliseconds of the local
     * calendar day [dayOffset] days after the one containing [nowMillis]. DST
     * changes make some days 23 or 25 hours long. */
    fun localDay(nowMillis: Long, dayOffset: Int = 0, timeZone: TimeZone = TimeZone.getDefault()): Pair<Long, Long> {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, dayOffset)
        }
        val start = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        return start to calendar.timeInMillis
    }

    fun day(latitude: Double, longitude: Double, startMillis: Long, endMillis: Long): SunMoonDay {
        val start = startMillis / 1000.0
        val end = endMillis / 1000.0
        fun sunAbove(threshold: Double): (Double) -> Double =
            { sunAltitude(it, latitude, longitude) - threshold }
        val nautical = crossings(sunAbove(NAUTICAL_ALTITUDE), start, end)
        val civil = crossings(sunAbove(CIVIL_ALTITUDE), start, end)
        val sun = crossings(sunAbove(SUNRISE_ALTITUDE), start, end)
        val moon = crossings({ moonAltitudeAboveHorizon(it, latitude, longitude) }, start, end)
        val (fraction, waxing) = illumination((start + end) / 2)
        fun millis(unix: Double?): Long? = unix?.let { Math.round(it * 1000) }
        return SunMoonDay(
            bmnt = millis(nautical.first), bmct = millis(civil.first),
            sunrise = millis(sun.first), sunset = millis(sun.second),
            eect = millis(civil.second), eent = millis(nautical.second),
            moonrise = millis(moon.first), moonset = millis(moon.second),
            moonIllumination = fraction, moonWaxing = waxing,
        )
    }

    /** First upward and first downward zero crossing of [f] in [start, end)
     * (epoch seconds), stepping [STEP_SECONDS] and refining by bisection. */
    fun crossings(f: (Double) -> Double, start: Double, end: Double): Pair<Double?, Double?> {
        var rising: Double? = null
        var setting: Double? = null
        var t0 = start
        var f0 = f(t0)
        while (t0 < end && (rising == null || setting == null)) {
            val t1 = min(t0 + STEP_SECONDS, end)
            val f1 = f(t1)
            if ((f0 < 0) != (f1 < 0)) {
                var lo = t0
                var hi = t1
                var flo = f0
                repeat(BISECTIONS) {
                    val mid = (lo + hi) / 2
                    val fm = f(mid)
                    if ((fm < 0) == (flo < 0)) {
                        lo = mid
                        flo = fm
                    } else {
                        hi = mid
                    }
                }
                val time = (lo + hi) / 2
                if (f0 < 0) {
                    if (rising == null) rising = time
                } else if (setting == null) {
                    setting = time
                }
            }
            t0 = t1
            f0 = f1
        }
        return rising to setting
    }

    private fun julianDay(unix: Double): Double = unix / 86_400 + 2_440_587.5

    private fun centuries(unix: Double): Double = (julianDay(unix) - 2_451_545) / 36_525

    private fun meanObliquity(t: Double): Double =
        23 + (26 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60) / 60

    /** Greenwich mean sidereal time in degrees. */
    private fun siderealTime(unix: Double): Double {
        val jd = julianDay(unix)
        val t = (jd - 2_451_545) / 36_525
        return (280.46061837 + 360.98564736629 * (jd - 2_451_545) +
            0.000387933 * t * t - t * t * t / 38_710_000) % 360
    }

    private class SunEcliptic(val longitude: Double, val distance: Double, val omega: Double)

    /** Apparent ecliptic longitude (degrees), distance (AU) and the node
     * longitude used for the nutation/aberration corrections. */
    private fun sunEcliptic(t: Double): SunEcliptic {
        val l0 = (280.46646 + t * (36000.76983 + 0.0003032 * t)) % 360
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mr) * (0.019993 - 0.000101 * t) +
            sin(3 * mr) * 0.000289
        val trueLongitude = l0 + c
        val anomaly = m + c
        val distance = 1.000001018 * (1 - e * e) / (1 + e * cos(Math.toRadians(anomaly)))
        val omega = 125.04 - 1934.136 * t
        val apparent = trueLongitude - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        return SunEcliptic(apparent, distance, omega)
    }

    private class MoonEcliptic(val longitude: Double, val latitude: Double, val parallax: Double)

    /** Low-precision lunar longitude, latitude and horizontal parallax (degrees). */
    private fun moonEcliptic(t: Double): MoonEcliptic {
        fun s(degrees: Double) = sin(Math.toRadians(degrees))
        fun c(degrees: Double) = cos(Math.toRadians(degrees))
        val longitude = 218.32 + 481267.881 * t +
            6.29 * s(135.0 + 477198.87 * t) -
            1.27 * s(259.3 - 413335.36 * t) +
            0.66 * s(235.7 + 890534.22 * t) +
            0.21 * s(269.9 + 954397.74 * t) -
            0.19 * s(357.5 + 35999.05 * t) -
            0.11 * s(186.5 + 966404.03 * t)
        val latitude = 5.13 * s(93.3 + 483202.02 * t) +
            0.28 * s(228.2 + 960400.89 * t) -
            0.28 * s(318.3 + 6003.15 * t) -
            0.17 * s(217.6 - 407332.21 * t)
        val parallax = 0.9508 +
            0.0518 * c(135.0 + 477198.87 * t) +
            0.0095 * c(259.3 - 413335.36 * t) +
            0.0078 * c(235.7 + 890534.22 * t) +
            0.0028 * c(269.9 + 954397.74 * t)
        return MoonEcliptic(longitude, latitude, parallax)
    }

    private fun altitude(ra: Double, dec: Double, unix: Double, latitude: Double, longitude: Double): Double {
        val hourAngle = Math.toRadians(siderealTime(unix) + longitude) - ra
        val phi = Math.toRadians(latitude)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(hourAngle)))
    }

    fun sunAltitude(unix: Double, latitude: Double, longitude: Double): Double {
        val t = centuries(unix)
        val sun = sunEcliptic(t)
        val epsilon = Math.toRadians(meanObliquity(t) + 0.00256 * cos(Math.toRadians(sun.omega)))
        val lambda = Math.toRadians(sun.longitude)
        val dec = asin(sin(epsilon) * sin(lambda))
        val ra = atan2(cos(epsilon) * sin(lambda), cos(lambda))
        return altitude(ra, dec, unix, latitude, longitude)
    }

    /** Geocentric altitude of the moon's centre minus the standard rise/set
     * altitude (parallax, semi-diameter and refraction), so zero is moonrise. */
    fun moonAltitudeAboveHorizon(unix: Double, latitude: Double, longitude: Double): Double {
        val t = centuries(unix)
        val moon = moonEcliptic(t)
        val epsilon = Math.toRadians(meanObliquity(t))
        val lambda = Math.toRadians(moon.longitude)
        val beta = Math.toRadians(moon.latitude)
        val dec = asin(sin(beta) * cos(epsilon) + cos(beta) * sin(epsilon) * sin(lambda))
        val ra = atan2(sin(lambda) * cos(epsilon) - tan(beta) * sin(epsilon), cos(lambda))
        return altitude(ra, dec, unix, latitude, longitude) - (0.7275 * moon.parallax - 0.5667)
    }

    /** Illuminated fraction and whether the moon is waxing, at epoch seconds [unix]. */
    fun illumination(unix: Double): Pair<Double, Boolean> {
        val t = centuries(unix)
        val sun = sunEcliptic(t)
        val moon = moonEcliptic(t)
        val elongation = acos(cos(Math.toRadians(moon.latitude)) * cos(Math.toRadians(moon.longitude - sun.longitude)))
        val moonDistance = EARTH_RADIUS_KILOMETRES / sin(Math.toRadians(moon.parallax))
        val sunDistance = sun.distance * AU_KILOMETRES
        val phaseAngle = atan2(sunDistance * sin(elongation), moonDistance - sunDistance * cos(elongation))
        return (1 + cos(phaseAngle)) / 2 to (sin(Math.toRadians(moon.longitude - sun.longitude)) > 0)
    }
}
