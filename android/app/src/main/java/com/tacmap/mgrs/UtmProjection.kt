package com.tacmap.mgrs

import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 UTM, forward + inverse, via the Kruger n-series to 6th order (Karney 2011,
 * same series PROJ's etmerc uses). Sub-mm inside a zone and still way under a mm
 * 6 deg off the CM, which the Norway / Svalbard cells need.
 *
 * Grid lines can't use the vendored NGA UTM for this: it's 1-4 cm off and rounds
 * to 1e-7 deg, which is more than a z19 grid line is allowed to be off.
 */
internal object UtmProjection {

    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    const val K0 = 0.9996
    const val FALSE_EASTING = 500_000.0
    const val FALSE_NORTHING_SOUTH = 10_000_000.0

    private val E2 = F * (2.0 - F)
    private val E = sqrt(E2)
    private val ALPHA = DoubleArray(7)
    private val BETA = DoubleArray(7)
    // rectifying radius times k0, the one scale both directions need
    private val K0A: Double

    init {
        val n = F / (2.0 - F)
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        K0A = K0 * A / (1.0 + n) * (1.0 + n2 / 4.0 + n4 / 64.0 + n6 / 256.0)

        ALPHA[1] = n / 2.0 - 2.0 / 3.0 * n2 + 5.0 / 16.0 * n3 + 41.0 / 180.0 * n4 -
            127.0 / 288.0 * n5 + 7891.0 / 37800.0 * n6
        ALPHA[2] = 13.0 / 48.0 * n2 - 3.0 / 5.0 * n3 + 557.0 / 1440.0 * n4 +
            281.0 / 630.0 * n5 - 1983433.0 / 1935360.0 * n6
        ALPHA[3] = 61.0 / 240.0 * n3 - 103.0 / 140.0 * n4 + 15061.0 / 26880.0 * n5 +
            167603.0 / 181440.0 * n6
        ALPHA[4] = 49561.0 / 161280.0 * n4 - 179.0 / 168.0 * n5 + 6601661.0 / 7257600.0 * n6
        ALPHA[5] = 34729.0 / 80640.0 * n5 - 3418889.0 / 1995840.0 * n6
        ALPHA[6] = 212378941.0 / 319334400.0 * n6

        BETA[1] = n / 2.0 - 2.0 / 3.0 * n2 + 37.0 / 96.0 * n3 - 1.0 / 360.0 * n4 -
            81.0 / 512.0 * n5 + 96199.0 / 604800.0 * n6
        BETA[2] = 1.0 / 48.0 * n2 + 1.0 / 15.0 * n3 - 437.0 / 1440.0 * n4 +
            46.0 / 105.0 * n5 - 1118711.0 / 3870720.0 * n6
        BETA[3] = 17.0 / 480.0 * n3 - 37.0 / 840.0 * n4 - 209.0 / 4480.0 * n5 +
            5569.0 / 90720.0 * n6
        BETA[4] = 4397.0 / 161280.0 * n4 - 11.0 / 504.0 * n5 - 830251.0 / 7257600.0 * n6
        BETA[5] = 4583.0 / 161280.0 * n5 - 108847.0 / 3991680.0 * n6
        BETA[6] = 20648693.0 / 638668800.0 * n6
    }

    fun centralMeridian(zone: Int): Double = 6.0 * zone - 183.0

    /** lat/lon (deg) -> out[0] = easting, out[1] = northing, metres. */
    fun forward(zone: Int, south: Boolean, latitude: Double, longitude: Double, out: DoubleArray) {
        val phi = Math.toRadians(latitude)
        val lam = Math.toRadians(longitude - centralMeridian(zone))
        val tau = tan(phi)
        val tauP = conformalTau(tau)
        val cosLam = cos(lam)
        val xiP = atan2(tauP, cosLam)
        val etaP = asinh(sin(lam) / sqrt(tauP * tauP + cosLam * cosLam))
        var xi = xiP
        var eta = etaP
        for (j in 1..6) {
            val k = 2.0 * j
            xi += ALPHA[j] * sin(k * xiP) * cosh(k * etaP)
            eta += ALPHA[j] * cos(k * xiP) * sinh(k * etaP)
        }
        out[0] = FALSE_EASTING + K0A * eta
        out[1] = (if (south) FALSE_NORTHING_SOUTH else 0.0) + K0A * xi
    }

    /** easting/northing (m) -> out[0] = latitude, out[1] = longitude, degrees. */
    fun inverse(zone: Int, south: Boolean, easting: Double, northing: Double, out: DoubleArray) {
        val xi = (northing - (if (south) FALSE_NORTHING_SOUTH else 0.0)) / K0A
        val eta = (easting - FALSE_EASTING) / K0A
        var xiP = xi
        var etaP = eta
        for (j in 1..6) {
            val k = 2.0 * j
            xiP -= BETA[j] * sin(k * xi) * cosh(k * eta)
            etaP -= BETA[j] * cos(k * xi) * sinh(k * eta)
        }
        val sinhEtaP = sinh(etaP)
        val cosXiP = cos(xiP)
        val tauP = sin(xiP) / sqrt(sinhEtaP * sinhEtaP + cosXiP * cosXiP)

        // newton for tau from tau', converges in 2-3 goes
        var tau = tauP
        for (i in 0 until 12) {
            val tauI = conformalTau(tau)
            val step = (tauP - tauI) / sqrt(1.0 + tauI * tauI) *
                (1.0 + (1.0 - E2) * tau * tau) / ((1.0 - E2) * sqrt(1.0 + tau * tau))
            tau += step
            if (abs(step) < 1e-14 * maxOf(1.0, abs(tau))) break
        }
        out[0] = Math.toDegrees(atan(tau))
        out[1] = centralMeridian(zone) + Math.toDegrees(atan2(sinhEtaP, cosXiP))
    }

    private fun conformalTau(tau: Double): Double {
        val sigma = sinh(E * atanh(E * tau / sqrt(1.0 + tau * tau)))
        return tau * sqrt(1.0 + sigma * sigma) - sigma * sqrt(1.0 + tau * tau)
    }
}
