package com.tacticalmaps.calibration

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodetic datum a calibrated map's grid references are expressed in. The
 * Android mirror of iOS `Datum`: most Australian MGA sheets are GDA94/GDA2020,
 * not WGS84 (up to ~1.8 m apart), so fiduciary coordinates are shifted to WGS84
 * before storing.
 */
enum class Datum(val displayName: String) {
    WGS84("WGS84"),
    GDA94("GDA94 / MGA94"),
    GDA2020("GDA2020 / MGA2020");

    /** Shift a (lat, lng) expressed in this datum to WGS84. */
    fun toWgs84(lat: Double, lng: Double): Pair<Double, Double> = when (this) {
        WGS84, GDA2020 -> lat to lng
        GDA94 -> DatumTransform.gda94ToGda2020(lat, lng)
    }
}

/**
 * 7-parameter Helmert datum transforms. Geodetic → ECEF → similarity transform
 * → ECEF → geodetic on the GRS80 ellipsoid. Same constants as iOS DatumTransform.
 */
object DatumTransform {

    // ICSM "GDA94 → GDA2020" conformal 7-parameter transformation
    // (GDA2020 Technical Manual). Coordinate-frame rotation convention.
    private const val TX = 0.06155
    private const val TY = -0.01087
    private const val TZ = -0.04019
    private const val RX_SEC = -0.0394924
    private const val RY_SEC = -0.0327221
    private const val RZ_SEC = -0.0328979
    private const val SCALE_PPM = -0.009994

    // GRS80 ellipsoid.
    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257222101
    private val E2 = F * (2 - F)

    fun gda94ToGda2020(lat: Double, lng: Double): Pair<Double, Double> {
        val (x, y, z) = toEcef(lat, lng)
        val (hx, hy, hz) = helmert(x, y, z)
        return fromEcef(hx, hy, hz)
    }

    private fun toEcef(lat: Double, lng: Double): Triple<Double, Double, Double> {
        val la = lat * PI / 180
        val lo = lng * PI / 180
        val sinLat = sin(la)
        val cosLat = cos(la)
        val n = A / sqrt(1 - E2 * sinLat * sinLat)
        return Triple(
            n * cosLat * cos(lo),
            n * cosLat * sin(lo),
            n * (1 - E2) * sinLat
        )
    }

    private fun helmert(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val arc = PI / 180 / 3600
        val rx = RX_SEC * arc
        val ry = RY_SEC * arc
        val rz = RZ_SEC * arc
        val s = 1 + SCALE_PPM * 1e-6
        return Triple(
            TX + s * (x + rz * y - ry * z),
            TY + s * (-rz * x + y + rx * z),
            TZ + s * (ry * x - rx * y + z)
        )
    }

    private fun fromEcef(x: Double, y: Double, z: Double): Pair<Double, Double> {
        val lon = atan2(y, x)
        val r = sqrt(x * x + y * y)
        var lat = atan2(z, r * (1 - E2))
        repeat(6) {
            val sinLat = sin(lat)
            val n = A / sqrt(1 - E2 * sinLat * sinLat)
            lat = atan2(z + E2 * n * sinLat, r)
        }
        return (lat * 180 / PI) to (lon * 180 / PI)
    }
}
