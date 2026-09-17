package com.tacmap.calibration

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Reference ellipsoid used by an LGIDict projection and datum transform. */
internal data class LgiEllipsoid(
    val semiMajorAxis: Double,
    val flattening: Double,
) {
    val eccentricitySquared: Double = flattening * (2.0 - flattening)
    val secondEccentricitySquared: Double = eccentricitySquared / (1.0 - eccentricitySquared)
    val eccentricity: Double = sqrt(eccentricitySquared)

    fun isValid(): Boolean =
        semiMajorAxis.isFinite() && semiMajorAxis in 6_000_000.0..7_000_000.0 &&
            flattening.isFinite() && flattening > 0.0 && flattening < 0.01 &&
            eccentricitySquared in 0.0..<1.0

    companion object {
        val WGS84 = LgiEllipsoid(6_378_137.0, 1.0 / 298.257223563)
        val GRS80 = LgiEllipsoid(6_378_137.0, 1.0 / 298.257222101)
        val AIRY_1830 = LgiEllipsoid(6_377_563.396, 1.0 / 299.3249646)
        val BESSEL_1841 = LgiEllipsoid(6_377_397.155, 1.0 / 299.1528128)
        val INTERNATIONAL_1924 = LgiEllipsoid(6_378_388.0, 1.0 / 297.0)
        val CLARKE_1866 = LgiEllipsoid(6_378_206.4, 1.0 / 294.9786982)
        val CLARKE_1880_IGN = LgiEllipsoid(6_378_249.2, 1.0 / 293.4660213)
        val KRASSOVSKY_1940 = LgiEllipsoid(6_378_245.0, 1.0 / 298.3)
    }
}

/**
 * Source datum and its unambiguous three-parameter ECEF translation to WGS84.
 * Values mirror the iOS GeoPDF implementation so the same sheet resolves to
 * the same WGS84 control points on both platforms.
 */
internal data class LgiDatum(
    val ellipsoid: LgiEllipsoid,
    val dx: Double,
    val dy: Double,
    val dz: Double,
) {
    fun toWgs84(latitude: Double, longitude: Double): Pair<Double, Double>? {
        if (!ellipsoid.isValid() ||
            !latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !dx.isFinite() || !dy.isFinite() || !dz.isFinite()
        ) return null
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) return latitude to longitude

        val source = geodeticToEcef(latitude, longitude, ellipsoid)
        return ecefToGeodetic(
            source.first + dx,
            source.second + dy,
            source.third + dz,
            LgiEllipsoid.WGS84,
        )
    }

    companion object {
        val WGS84 = LgiDatum(LgiEllipsoid.WGS84, 0.0, 0.0, 0.0)

        fun fromCode(rawCode: String): LgiDatum? = when (rawCode.trim().uppercase(Locale.US)) {
            "WE", "WD" -> WGS84
            "GD", "NA" -> LgiDatum(LgiEllipsoid.GRS80, 0.0, 0.0, 0.0)
            "OB", "OG", "OS" -> LgiDatum(LgiEllipsoid.AIRY_1830, 446.448, -125.157, 542.06)
            "EU" -> LgiDatum(LgiEllipsoid.INTERNATIONAL_1924, -87.0, -98.0, -121.0)
            "NS" -> LgiDatum(LgiEllipsoid.CLARKE_1866, -8.0, 160.0, 176.0)
            "TC" -> LgiDatum(LgiEllipsoid.BESSEL_1841, -146.414, 507.337, 680.507)
            "CH" -> LgiDatum(LgiEllipsoid.BESSEL_1841, 674.374, 15.056, 405.346)
            "NT", "NF" -> LgiDatum(LgiEllipsoid.CLARKE_1880_IGN, -168.0, -60.0, 320.0)
            "KK" -> LgiDatum(LgiEllipsoid.KRASSOVSKY_1940, 23.92, -141.27, -80.9)
            else -> null
        }

        fun inline(
            semiMajorAxis: Double,
            inverseFlattening: Double,
            dx: Double,
            dy: Double,
            dz: Double,
        ): LgiDatum? {
            if (!inverseFlattening.isFinite() || inverseFlattening <= 0.0) return null
            val ellipsoid = LgiEllipsoid(semiMajorAxis, 1.0 / inverseFlattening)
            return LgiDatum(ellipsoid, dx, dy, dz).takeIf {
                ellipsoid.isValid() && dx.isFinite() && dy.isFinite() && dz.isFinite()
            }
        }

        private fun geodeticToEcef(
            latitude: Double,
            longitude: Double,
            ellipsoid: LgiEllipsoid,
        ): Triple<Double, Double, Double> {
            val phi = latitude * PI / 180.0
            val lambda = longitude * PI / 180.0
            val sinPhi = sin(phi)
            val cosPhi = cos(phi)
            val radius = ellipsoid.semiMajorAxis /
                sqrt(1.0 - ellipsoid.eccentricitySquared * sinPhi * sinPhi)
            return Triple(
                radius * cosPhi * cos(lambda),
                radius * cosPhi * sin(lambda),
                radius * (1.0 - ellipsoid.eccentricitySquared) * sinPhi,
            )
        }

        private fun ecefToGeodetic(
            x: Double,
            y: Double,
            z: Double,
            ellipsoid: LgiEllipsoid,
        ): Pair<Double, Double>? {
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return null
            val longitude = atan2(y, x)
            val radius = sqrt(x * x + y * y)
            if (radius <= 0.0) return null
            var latitude = atan2(z, radius * (1.0 - ellipsoid.eccentricitySquared))
            repeat(6) {
                val sinLatitude = sin(latitude)
                val normalRadius = ellipsoid.semiMajorAxis /
                    sqrt(1.0 - ellipsoid.eccentricitySquared * sinLatitude * sinLatitude)
                latitude = atan2(
                    z + ellipsoid.eccentricitySquared * normalRadius * sinLatitude,
                    radius,
                )
            }
            val latitudeDegrees = latitude * 180.0 / PI
            val longitudeDegrees = longitude * 180.0 / PI
            return if (latitudeDegrees.isFinite() && latitudeDegrees in -90.0..90.0 &&
                longitudeDegrees.isFinite() && longitudeDegrees in -180.0..180.0
            ) latitudeDegrees to longitudeDegrees else null
        }
    }
}

/** Inverse projection from LGIDict map coordinates onto its source datum. */
internal sealed interface LgiProjection {
    fun inverse(easting: Double, northing: Double): Pair<Double, Double>?

    data object LongLat : LgiProjection {
        override fun inverse(easting: Double, northing: Double): Pair<Double, Double>? =
            if (northing.isFinite() && northing in -90.0..90.0 &&
                easting.isFinite() && easting in -180.0..180.0
            ) northing to easting else null
    }

    data class TransverseMercator(
        val centralMeridian: Double,
        val originLatitude: Double,
        val falseEasting: Double,
        val falseNorthing: Double,
        val scaleFactor: Double,
        val ellipsoid: LgiEllipsoid,
    ) : LgiProjection {
        override fun inverse(easting: Double, northing: Double): Pair<Double, Double>? =
            transverseMercatorInverse(
                easting,
                northing,
                centralMeridian,
                originLatitude,
                falseEasting,
                falseNorthing,
                scaleFactor,
                ellipsoid,
            )
    }

    data class LambertConformalConic(
        val standardParallelOne: Double,
        val standardParallelTwo: Double,
        val originLatitude: Double,
        val centralMeridian: Double,
        val falseEasting: Double,
        val falseNorthing: Double,
        val ellipsoid: LgiEllipsoid,
    ) : LgiProjection {
        override fun inverse(easting: Double, northing: Double): Pair<Double, Double>? =
            lambertConformalConicInverse(
                easting,
                northing,
                standardParallelOne,
                standardParallelTwo,
                originLatitude,
                centralMeridian,
                falseEasting,
                falseNorthing,
                ellipsoid,
            )
    }
}

internal object LgiProjectionFactory {
    fun longLat(): LgiProjection = LgiProjection.LongLat

    fun utm(zone: Int, southernHemisphere: Boolean, ellipsoid: LgiEllipsoid): LgiProjection? {
        if (zone !in 1..60 || !ellipsoid.isValid()) return null
        return transverseMercator(
            centralMeridian = zone * 6.0 - 183.0,
            originLatitude = 0.0,
            falseEasting = 500_000.0,
            falseNorthing = if (southernHemisphere) 10_000_000.0 else 0.0,
            scaleFactor = 0.9996,
            ellipsoid = ellipsoid,
        )
    }

    fun transverseMercator(
        centralMeridian: Double,
        originLatitude: Double,
        falseEasting: Double,
        falseNorthing: Double,
        scaleFactor: Double,
        ellipsoid: LgiEllipsoid,
    ): LgiProjection? {
        if (!centralMeridian.isFinite() || centralMeridian !in -180.0..180.0 ||
            !originLatitude.isFinite() || originLatitude !in -90.0..90.0 ||
            !falseEasting.isFinite() || !falseNorthing.isFinite() ||
            !scaleFactor.isFinite() || scaleFactor <= 0.0 || scaleFactor > 10.0 ||
            !ellipsoid.isValid()
        ) return null
        return LgiProjection.TransverseMercator(
            centralMeridian,
            originLatitude,
            falseEasting,
            falseNorthing,
            scaleFactor,
            ellipsoid,
        )
    }

    fun lambertConformalConic(
        standardParallelOne: Double,
        standardParallelTwo: Double,
        originLatitude: Double,
        centralMeridian: Double,
        falseEasting: Double,
        falseNorthing: Double,
        ellipsoid: LgiEllipsoid,
    ): LgiProjection? {
        if (!standardParallelOne.isFinite() || standardParallelOne !in -89.999..89.999 ||
            !standardParallelTwo.isFinite() || standardParallelTwo !in -89.999..89.999 ||
            !originLatitude.isFinite() || originLatitude !in -89.999..89.999 ||
            !centralMeridian.isFinite() || centralMeridian !in -180.0..180.0 ||
            !falseEasting.isFinite() || !falseNorthing.isFinite() ||
            !ellipsoid.isValid()
        ) return null
        return LgiProjection.LambertConformalConic(
            standardParallelOne,
            standardParallelTwo,
            originLatitude,
            centralMeridian,
            falseEasting,
            falseNorthing,
            ellipsoid,
        )
    }
}

internal data class LgiCoordinateConverter(
    val projection: LgiProjection,
    val datum: LgiDatum,
) {
    fun toWgs84(mapX: Double, mapY: Double): Pair<Double, Double>? {
        if (!mapX.isFinite() || !mapY.isFinite()) return null
        val source = projection.inverse(mapX, mapY) ?: return null
        return datum.toWgs84(source.first, source.second)?.takeIf { (latitude, longitude) ->
            latitude.isFinite() && latitude in -90.0..90.0 &&
                longitude.isFinite() && longitude in -180.0..180.0
        }
    }
}

private fun transverseMercatorInverse(
    x: Double,
    y: Double,
    centralMeridian: Double,
    originLatitude: Double,
    falseEasting: Double,
    falseNorthing: Double,
    scaleFactor: Double,
    ellipsoid: LgiEllipsoid,
): Pair<Double, Double>? {
    if (!x.isFinite() || !y.isFinite()) return null
    val a = ellipsoid.semiMajorAxis
    val e2 = ellipsoid.eccentricitySquared
    val eDash2 = ellipsoid.secondEccentricitySquared
    val lambda0 = centralMeridian * PI / 180.0
    val phi0 = originLatitude * PI / 180.0
    val xE = x - falseEasting
    val yN = y - falseNorthing

    val m0 = meridionalArc(phi0, a, e2)
    val m = m0 + yN / scaleFactor
    val mu = m / (a * (1.0 - e2 / 4.0 - 3.0 * e2 * e2 / 64.0 - 5.0 * e2 * e2 * e2 / 256.0))
    val e1 = (1.0 - sqrt(1.0 - e2)) / (1.0 + sqrt(1.0 - e2))
    val e1Squared = e1 * e1
    val e1Cubed = e1Squared * e1
    val e1Fourth = e1Squared * e1Squared
    val phi1 = mu +
        (3.0 * e1 / 2.0 - 27.0 * e1Cubed / 32.0) * sin(2.0 * mu) +
        (21.0 * e1Squared / 16.0 - 55.0 * e1Fourth / 32.0) * sin(4.0 * mu) +
        (151.0 * e1Cubed / 96.0) * sin(6.0 * mu) +
        (1097.0 * e1Fourth / 512.0) * sin(8.0 * mu)

    val sinPhi1 = sin(phi1)
    val cosPhi1 = cos(phi1)
    if (!phi1.isFinite() || abs(cosPhi1) <= 1e-12) return null
    val tanPhi1 = tan(phi1)
    val oneMinus = 1.0 - e2 * sinPhi1 * sinPhi1
    if (oneMinus <= 0.0) return null
    val n1 = a / sqrt(oneMinus)
    val t1 = tanPhi1 * tanPhi1
    val c1 = eDash2 * cosPhi1 * cosPhi1
    val r1 = a * (1.0 - e2) / oneMinus.pow(1.5)
    val d = xE / (n1 * scaleFactor)
    // The inverse series is not trustworthy for coordinates far outside a TM
    // zone. Reject instead of returning finite but geographically false data.
    if (!d.isFinite() || abs(d) > 1.0) return null
    val d2 = d * d
    val d3 = d2 * d
    val d4 = d2 * d2
    val d5 = d4 * d
    val d6 = d4 * d2

    val phi = phi1 - (n1 * tanPhi1 / r1) * (
        d2 / 2.0 -
            (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * eDash2) * d4 / 24.0 +
            (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * eDash2 - 3.0 * c1 * c1) * d6 / 720.0
        )
    val lambda = lambda0 + (
        d - (1.0 + 2.0 * t1 + c1) * d3 / 6.0 +
            (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * eDash2 + 24.0 * t1 * t1) * d5 / 120.0
        ) / cosPhi1

    val latitude = phi * 180.0 / PI
    val longitude = lambda * 180.0 / PI
    return if (latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0
    ) latitude to longitude else null
}

private fun lambertConformalConicInverse(
    x: Double,
    y: Double,
    standardParallelOne: Double,
    standardParallelTwo: Double,
    originLatitude: Double,
    centralMeridian: Double,
    falseEasting: Double,
    falseNorthing: Double,
    ellipsoid: LgiEllipsoid,
): Pair<Double, Double>? {
    if (!x.isFinite() || !y.isFinite()) return null
    val a = ellipsoid.semiMajorAxis
    val eccentricity = ellipsoid.eccentricity
    val e2 = ellipsoid.eccentricitySquared
    val lambda0 = centralMeridian * PI / 180.0
    val phi0 = originLatitude * PI / 180.0
    val phi1 = standardParallelOne * PI / 180.0
    val phi2 = standardParallelTwo * PI / 180.0

    fun m(phi: Double): Double = cos(phi) / sqrt(1.0 - e2 * sin(phi).pow(2))
    fun t(phi: Double): Double = tan(PI / 4.0 - phi / 2.0) /
        ((1.0 - eccentricity * sin(phi)) / (1.0 + eccentricity * sin(phi))).pow(eccentricity / 2.0)

    val m1 = m(phi1)
    val m2 = m(phi2)
    val t0 = t(phi0)
    val t1 = t(phi1)
    val t2 = t(phi2)
    val n = if (abs(phi1 - phi2) < 1e-10) {
        sin(phi1)
    } else {
        (ln(m1) - ln(m2)) / (ln(t1) - ln(t2))
    }
    if (!n.isFinite() || abs(n) < 1e-15) return null
    val f = m1 / (n * t1.pow(n))
    val rho0 = a * f * t0.pow(n)
    if (!f.isFinite() || !rho0.isFinite()) return null

    val xE = x - falseEasting
    val yN = y - falseNorthing
    val dy = rho0 - yN
    val rho = (if (n >= 0.0) 1.0 else -1.0) * sqrt(xE * xE + dy * dy)
    if (!rho.isFinite() || rho == 0.0) return null
    val theta = atan2(if (n >= 0.0) xE else -xE, if (n >= 0.0) dy else -dy)
    val tValue = (rho / (a * f)).pow(1.0 / n)
    if (!tValue.isFinite() || tValue <= 0.0) return null

    var phi = PI / 2.0 - 2.0 * atan(tValue)
    repeat(12) {
        val next = PI / 2.0 - 2.0 * atan(
            tValue * ((1.0 - eccentricity * sin(phi)) /
                (1.0 + eccentricity * sin(phi))).pow(eccentricity / 2.0),
        )
        if (abs(next - phi) < 1e-12) {
            phi = next
            return@repeat
        }
        phi = next
    }
    val lambda = theta / n + lambda0
    val latitude = phi * 180.0 / PI
    val longitude = lambda * 180.0 / PI
    return if (latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0
    ) latitude to longitude else null
}

private fun meridionalArc(phi: Double, a: Double, e2: Double): Double {
    val e4 = e2 * e2
    val e6 = e4 * e2
    return a * (
        (1.0 - e2 / 4.0 - 3.0 * e4 / 64.0 - 5.0 * e6 / 256.0) * phi -
            (3.0 * e2 / 8.0 + 3.0 * e4 / 32.0 + 45.0 * e6 / 1024.0) * sin(2.0 * phi) +
            (15.0 * e4 / 256.0 + 45.0 * e6 / 1024.0) * sin(4.0 * phi) -
            (35.0 * e6 / 3072.0) * sin(6.0 * phi)
        )
}
