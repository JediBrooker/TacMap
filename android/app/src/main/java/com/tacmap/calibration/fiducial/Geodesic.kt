package com.tacmap.calibration.fiducial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 ellipsoidal distance (Vincenty inverse). Sub-mm vs Karney for anything
 * that isn't near antipodal, which is all a calibration ever measures. Pure so
 * the entry check runs on the JVM; Location.distanceBetween is the same algorithm.
 */
object Geodesic {
    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257223563
    private const val B = A * (1.0 - F)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == lat2 && lon1 == lon2) return 0.0
        val l = (lon2 - lon1) * PI / 180.0
        val u1 = atan((1.0 - F) * tan(lat1 * PI / 180.0))
        val u2 = atan((1.0 - F) * tan(lat2 * PI / 180.0))
        val sinU1 = sin(u1); val cosU1 = cos(u1)
        val sinU2 = sin(u2); val cosU2 = cos(u2)
        var lambda = l
        var sinSigma = 0.0
        var cosSigma = 0.0
        var sigma = 0.0
        var cos2Alpha = 0.0
        var cos2SigmaM = 0.0
        for (i in 0 until 200) {
            val sinLambda = sin(lambda)
            val cosLambda = cos(lambda)
            val t1 = cosU2 * sinLambda
            val t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosLambda
            sinSigma = sqrt(t1 * t1 + t2 * t2)
            if (sinSigma == 0.0) return 0.0
            cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            cos2Alpha = 1.0 - sinAlpha * sinAlpha
            cos2SigmaM = if (cos2Alpha != 0.0) cosSigma - 2.0 * sinU1 * sinU2 / cos2Alpha else 0.0
            val c = F / 16.0 * cos2Alpha * (4.0 + F * (4.0 - 3.0 * cos2Alpha))
            val prev = lambda
            lambda = l + (1.0 - c) * F * sinAlpha *
                (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM)))
            if (abs(lambda - prev) < 1e-13) break
        }
        val uSq = cos2Alpha * (A * A - B * B) / (B * B)
        val bigA = 1.0 + uSq / 16384.0 * (4096.0 + uSq * (-768.0 + uSq * (320.0 - 175.0 * uSq)))
        val bigB = uSq / 1024.0 * (256.0 + uSq * (-128.0 + uSq * (74.0 - 47.0 * uSq)))
        val deltaSigma = bigB * sinSigma * (
            cos2SigmaM + bigB / 4.0 * (
                cosSigma * (-1.0 + 2.0 * cos2SigmaM * cos2SigmaM) -
                    bigB / 6.0 * cos2SigmaM * (-3.0 + 4.0 * sinSigma * sinSigma) * (-3.0 + 4.0 * cos2SigmaM * cos2SigmaM)
                )
            )
        return B * bigA * (sigma - deltaSigma)
    }
}
