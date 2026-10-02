package com.tacmap.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/** A point in a georef's plane: metres for projected CRSs, (lon, lat) degrees for geographic. */
data class PlanePoint(val x: Double, val y: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite()
}

/**
 * The plane a PdfGeoreference's affine maps page space into (plan 02 s1). Params
 * are in degrees / metres exactly as the WKT or LGIDict wrote them; the math runs
 * on whatever ellipsoid the caller hands in (always the georef datum's).
 */
sealed class GeoCrs {
    abstract fun projector(ellipsoid: GeoEllipsoid): Projector?

    fun forward(latitude: Double, longitude: Double, ellipsoid: GeoEllipsoid): PlanePoint? =
        projector(ellipsoid)?.forward(latitude, longitude)

    fun inverse(x: Double, y: Double, ellipsoid: GeoEllipsoid): GeodeticPoint? =
        projector(ellipsoid)?.inverse(x, y)

    object Geographic : GeoCrs() {
        override fun projector(ellipsoid: GeoEllipsoid): Projector = object : Projector {
            override fun forward(latitude: Double, longitude: Double): PlanePoint? =
                if (latInRange(latitude) && longitude.isFinite()) PlanePoint(longitude, latitude) else null

            // plane lon can run past 180 on a sheet that straddles the antimeridian, wrap it back
            override fun inverse(x: Double, y: Double): GeodeticPoint? =
                if (latInRange(y) && x.isFinite()) GeodeticPoint(y, wrapDegrees(x)) else null
        }

        override fun toString(): String = "Geographic"
    }

    data class TransverseMercator(
        val lat0: Double,
        val lon0: Double,
        val k0: Double,
        val fe: Double,
        val fn: Double,
    ) : GeoCrs() {
        /** zone number when the params are exactly a UTM zone, else null */
        val utmZone: Int?
            get() {
                if (lat0 != 0.0 || k0 != 0.9996 || fe != 500_000.0 || (fn != 0.0 && fn != 10_000_000.0)) return null
                val z = (lon0 + 183.0) / 6.0
                return if (z == round(z) && z in 1.0..60.0) z.toInt() else null
            }

        /** 'N' / 'S' when this is a UTM zone */
        val hemisphere: Char? get() = utmZone?.let { if (fn == 0.0) 'N' else 'S' }

        override fun projector(ellipsoid: GeoEllipsoid): Projector? {
            // lon0 isn't wrapped by the wkt parser (cm + PRIMEM), so allow a full turn either way like iOS
            if (!ellipsoid.isValid() || !lat0.isFinite() || lat0 !in -90.0..90.0 ||
                !lon0.isFinite() || lon0 !in -360.0..360.0 ||
                !k0.isFinite() || k0 <= 0.0 || k0 > 10.0 || !fe.isFinite() || !fn.isFinite()
            ) return null
            return KrugerTm(this, ellipsoid)
        }
    }

    data class LambertConformalConic2SP(
        val lat1: Double,
        val lat2: Double,
        val lat0: Double,
        val lon0: Double,
        val fe: Double,
        val fn: Double,
    ) : GeoCrs() {
        override fun projector(ellipsoid: GeoEllipsoid): Projector? =
            LambertProjector.create(ellipsoid, lat1, lat2, lat0, lon0, 1.0, fe, fn, oneParallel = false)
    }

    data class LambertConformalConic1SP(
        val lat0: Double,
        val lon0: Double,
        val k0: Double,
        val fe: Double,
        val fn: Double,
    ) : GeoCrs() {
        override fun projector(ellipsoid: GeoEllipsoid): Projector? =
            LambertProjector.create(ellipsoid, lat0, lat0, lat0, lon0, k0, fe, fn, oneParallel = true)
    }

    /** also covers Mercator 2SP, fold k0 in with [mercatorScaleAt] first */
    data class Mercator1SP(
        val lon0: Double,
        val k0: Double,
        val fe: Double,
        val fn: Double,
    ) : GeoCrs() {
        override fun projector(ellipsoid: GeoEllipsoid): Projector? {
            if (!ellipsoid.isValid() || !lon0.isFinite() ||
                !k0.isFinite() || k0 <= 0.0 || k0 > 10.0 || !fe.isFinite() || !fn.isFinite()
            ) return null
            return MercatorProjector(this, ellipsoid)
        }
    }

    companion object {
        fun utm(zone: Int, southern: Boolean): TransverseMercator {
            require(zone in 1..60) { "UTM zone out of range" }
            return TransverseMercator(0.0, zone * 6.0 - 183.0, 0.9996, 500_000.0, if (southern) 10_000_000.0 else 0.0)
        }

        /** standard zone for a lon, no Norway/Svalbard exceptions (fiduciary rule) */
        fun standardUtmZone(longitude: Double): Int =
            (floor((longitude + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)

        /** Mercator 2SP -> 1SP: k0 = cos(lat_ts) / sqrt(1 - e2 sin2(lat_ts)) */
        fun mercatorScaleAt(latTs: Double, ellipsoid: GeoEllipsoid): Double {
            val phi = latTs * PI / 180.0
            val s = sin(phi)
            return cos(phi) / sqrt(1.0 - ellipsoid.e2 * s * s)
        }
    }
}

interface Projector {
    /** (lat, lon) on the ellipsoid -> plane, null when it can't be projected sanely */
    fun forward(latitude: Double, longitude: Double): PlanePoint?
    fun inverse(x: Double, y: Double): GeodeticPoint?
}

private const val DEG = PI / 180.0

/** same as iOS GeoCrs.wrapLongitude: left alone inside [-180, 180], else one modulo */
internal fun wrapDegrees(v: Double): Double {
    if (!v.isFinite() || v in -180.0..180.0) return v
    var l = (v + 180.0) % 360.0
    if (l < 0.0) l += 360.0
    return l - 180.0
}

private fun latInRange(lat: Double) = lat.isFinite() && lat in -90.0..90.0

/**
 * Kruger n-series to 6th order (Karney 2011), same as PROJ's poder/engsager tmerc
 * to well under a mm inside +-4 deg of the CM. The old Snyder series left mm-level
 * junk out at the zone edge and at lat 70, which the fixture now catches.
 */
private class KrugerTm(val crs: GeoCrs.TransverseMercator, ell: GeoEllipsoid) : Projector {
    private val e = ell.e
    private val bigA: Double
    private val alpha = DoubleArray(7)
    private val beta = DoubleArray(7)
    private val m0: Double

    init {
        val n = ell.n
        val n2 = n * n; val n3 = n2 * n; val n4 = n3 * n; val n5 = n4 * n; val n6 = n5 * n
        bigA = ell.a / (1.0 + n) * (1.0 + n2 / 4.0 + n4 / 64.0 + n6 / 256.0)
        alpha[1] = n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800
        alpha[2] = 13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360
        alpha[3] = 61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440
        alpha[4] = 49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600
        alpha[5] = 34729 * n5 / 80640 - 3418889 * n6 / 1995840
        alpha[6] = 212378941 * n6 / 319334400
        beta[1] = n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800
        beta[2] = n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720
        beta[3] = 17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720
        beta[4] = 4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600
        beta[5] = 4583 * n5 / 161280 - 108847 * n6 / 3991680
        beta[6] = 20648693 * n6 / 638668800
        // northing of (lat0, lon0) so a non-zero origin latitude (BNG etc) works
        m0 = crs.k0 * bigA * seriesY(conformalLat(crs.lat0 * DEG), 0.0)
    }

    private fun conformalLat(phi: Double): Double {
        val s = sin(phi)
        val t = sinh(atanh(s) - e * atanh(e * s))
        return atan(t)
    }

    private fun seriesY(xi: Double, eta: Double): Double {
        var y = xi
        for (j in 1..6) y += alpha[j] * sin(2 * j * xi) * cosh(2 * j * eta)
        return y
    }

    override fun forward(latitude: Double, longitude: Double): PlanePoint? {
        if (!latInRange(latitude) || !longitude.isFinite()) return null
        val phi = latitude * DEG
        val dLon = wrapDegrees(longitude - crs.lon0)
        // the series is junk past the quarter turn from the CM (iOS draws the line at 90 too)
        if (!(abs(dLon) < 90.0)) return null
        val lam = dLon * DEG
        val s = sin(phi)
        val t = sinh(atanh(s) - e * atanh(e * s))
        val xiP = atan2(t, cos(lam))
        val etaP = atanh(sin(lam) / sqrt(1.0 + t * t))
        var x = etaP
        var y = xiP
        for (j in 1..6) {
            x += alpha[j] * cos(2 * j * xiP) * sinh(2 * j * etaP)
            y += alpha[j] * sin(2 * j * xiP) * cosh(2 * j * etaP)
        }
        val out = PlanePoint(crs.fe + crs.k0 * bigA * x, crs.fn + crs.k0 * bigA * y - m0)
        return out.takeIf { it.isFinite() }
    }

    override fun inverse(x: Double, y: Double): GeodeticPoint? {
        if (!x.isFinite() || !y.isFinite()) return null
        val xi = (y - crs.fn + m0) / (crs.k0 * bigA)
        val eta = (x - crs.fe) / (crs.k0 * bigA)
        // way outside any sane strip, bail out (same limits as iOS)
        if (!(abs(eta) < 2.5) || !(abs(xi) < 3.2)) return null
        var xiP = xi
        var etaP = eta
        for (j in 1..6) {
            xiP -= beta[j] * sin(2 * j * xi) * cosh(2 * j * eta)
            etaP -= beta[j] * cos(2 * j * xi) * sinh(2 * j * eta)
        }
        val sinhEta = sinh(etaP)
        val cosXi = cos(xiP)
        val tauP = sin(xiP) / sqrt(sinhEta * sinhEta + cosXi * cosXi)
        // newton on tau, Karney 2011 eq 19-21
        var tau = tauP
        val e2 = e * e
        for (i in 0 until 15) {
            val sig = sinh(e * atanh(e * tau / sqrt(1.0 + tau * tau)))
            val tt = tau * sqrt(1.0 + sig * sig) - sig * sqrt(1.0 + tau * tau)
            val dt = (tauP - tt) * (1.0 + (1.0 - e2) * tau * tau) /
                ((1.0 - e2) * sqrt(1.0 + tt * tt) * sqrt(1.0 + tau * tau))
            tau += dt
            if (!tau.isFinite()) return null
            if (abs(dt) < 1e-15) break
        }
        val lat = atan(tau) / DEG
        val lon = wrapDegrees(crs.lon0 + atan2(sinhEta, cosXi) / DEG)
        return GeodeticPoint(lat, lon).takeIf { it.isValid() }
    }
}

private class LambertProjector(
    private val a: Double,
    private val e: Double,
    private val n: Double,
    private val bigF: Double,
    private val rho0: Double,
    private val lon0: Double,
    private val fe: Double,
    private val fn: Double,
) : Projector {
    private fun t(phi: Double): Double {
        val s = sin(phi)
        return tan(PI / 4.0 - phi / 2.0) / ((1.0 - e * s) / (1.0 + e * s)).pow(e / 2.0)
    }

    override fun forward(latitude: Double, longitude: Double): PlanePoint? {
        // the apex pole blows up and the far one is at infinity, neither is a map
        if (!latInRange(latitude) || !(abs(latitude) < 90.0) || !longitude.isFinite()) return null
        val phi = latitude * DEG
        val rho = a * bigF * t(phi).pow(n)
        val theta = n * wrapDegrees(longitude - lon0) * DEG
        return PlanePoint(fe + rho * sin(theta), fn + rho0 - rho * cos(theta)).takeIf { it.isFinite() }
    }

    override fun inverse(x: Double, y: Double): GeodeticPoint? {
        if (!x.isFinite() || !y.isFinite()) return null
        val dx = x - fe
        val dy = rho0 - (y - fn)
        val sn = if (n >= 0.0) 1.0 else -1.0
        val rho = sn * hypot(dx, dy)
        val theta = atan2(sn * dx, sn * dy)
        if (rho == 0.0) return GeodeticPoint(sn * 90.0, lon0).takeIf { it.isValid() }
        val tt = (rho / (a * bigF)).pow(1.0 / n)
        if (!tt.isFinite() || tt <= 0.0) return null
        var phi = PI / 2.0 - 2.0 * atan(tt)
        for (i in 0 until 40) {
            val s = sin(phi)
            val next = PI / 2.0 - 2.0 * atan(tt * ((1.0 - e * s) / (1.0 + e * s)).pow(e / 2.0))
            if (abs(next - phi) < 1e-15) {
                phi = next
                break
            }
            phi = next
        }
        return GeodeticPoint(phi / DEG, wrapDegrees(lon0 + theta / n / DEG)).takeIf { it.isValid() }
    }

    companion object {
        fun create(
            ell: GeoEllipsoid,
            lat1: Double,
            lat2: Double,
            lat0: Double,
            lon0: Double,
            k0: Double,
            fe: Double,
            fn: Double,
            oneParallel: Boolean,
        ): Projector? {
            if (!ell.isValid() || listOf(lat1, lat2, lat0, lon0, k0, fe, fn).any { !it.isFinite() }) return null
            if (abs(lat1) >= 89.999 || abs(lat2) >= 89.999 || abs(lat0) >= 89.999) return null
            if (k0 <= 0.0 || k0 > 10.0) return null
            val e = ell.e
            fun m(phi: Double): Double = cos(phi) / sqrt(1.0 - ell.e2 * sin(phi) * sin(phi))
            fun t(phi: Double): Double {
                val s = sin(phi)
                return tan(PI / 4.0 - phi / 2.0) / ((1.0 - e * s) / (1.0 + e * s)).pow(e / 2.0)
            }
            val p1 = lat1 * DEG
            val p2 = lat2 * DEG
            val p0 = lat0 * DEG
            val n = if (oneParallel || abs(p1 - p2) < 1e-12) sin(p1)
            else (ln(m(p1)) - ln(m(p2))) / (ln(t(p1)) - ln(t(p2)))
            if (!n.isFinite() || !(abs(n) > 1e-12)) return null
            val bigF = m(p1) / (n * t(p1).pow(n)) * k0
            val rho0 = ell.a * bigF * t(p0).pow(n)
            if (!bigF.isFinite() || !rho0.isFinite()) return null
            return LambertProjector(ell.a, e, n, bigF, rho0, lon0, fe, fn)
        }
    }
}

private class MercatorProjector(val crs: GeoCrs.Mercator1SP, ell: GeoEllipsoid) : Projector {
    private val a = ell.a
    private val e = ell.e

    override fun forward(latitude: Double, longitude: Double): PlanePoint? {
        if (!latInRange(latitude) || !longitude.isFinite() || abs(latitude) >= 89.999) return null
        val phi = latitude * DEG
        val s = sin(phi)
        val y = ln(tan(PI / 4.0 + phi / 2.0) * ((1.0 - e * s) / (1.0 + e * s)).pow(e / 2.0))
        val x = wrapDegrees(longitude - crs.lon0) * DEG
        return PlanePoint(crs.fe + a * crs.k0 * x, crs.fn + a * crs.k0 * y).takeIf { it.isFinite() }
    }

    override fun inverse(x: Double, y: Double): GeodeticPoint? {
        if (!x.isFinite() || !y.isFinite()) return null
        val t = exp(-(y - crs.fn) / (a * crs.k0))
        if (!t.isFinite() || t <= 0.0) return null
        var phi = PI / 2.0 - 2.0 * atan(t)
        for (i in 0 until 40) {
            val s = sin(phi)
            val next = PI / 2.0 - 2.0 * atan(t * ((1.0 - e * s) / (1.0 + e * s)).pow(e / 2.0))
            if (abs(next - phi) < 1e-15) {
                phi = next
                break
            }
            phi = next
        }
        val lon = wrapDegrees(crs.lon0 + (x - crs.fe) / (a * crs.k0) / DEG)
        return GeodeticPoint(phi / DEG, lon).takeIf { it.isValid() }
    }
}
