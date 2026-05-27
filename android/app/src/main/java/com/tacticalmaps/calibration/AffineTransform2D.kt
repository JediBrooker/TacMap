package com.tacticalmaps.calibration

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 2D affine transform from PDF page coordinates to WGS84 lon/lat:
 *
 *   lon = a*x + b*y + c
 *   lat = d*x + e*y + f
 *
 * Stored as six coefficients. Captures translation, rotation, scale, and shear.
 * For production work over large areas a projective fit using the source map's
 * actual projection is preferable, but for prototype scale this is sufficient.
 */
@Serializable
data class AffineTransform2D(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double
) {
    fun apply(x: Double, y: Double): Wgs84Coordinate =
        Wgs84Coordinate(d * x + e * y + f, a * x + b * y + c)

    fun inverted(): AffineTransform2D? {
        val det = a * e - b * d
        if (abs(det) < 1e-12) return null
        val inv = 1.0 / det
        val ia =  e * inv
        val ib = -b * inv
        val id = -d * inv
        val ie =  a * inv
        return AffineTransform2D(
            a = ia, b = ib, c = -(ia * c + ib * f),
            d = id, e = ie, f = -(id * c + ie * f)
        )
    }
}

sealed class AffineFitError(message: String) : Throwable(message) {
    data object TooFewFiduciaries : AffineFitError("Need at least 3 fiduciaries")
    data object Degenerate         : AffineFitError("Fiduciaries are colinear or coincident")
}

data class AffineFitResult(
    val transform: AffineTransform2D,
    /** RMS residual in metres. Surface this to users so they know how trustworthy
     *  the calibration is. */
    val rmsMetres: Double
)

/**
 * Least-squares fit of an affine transform from N≥3 fiduciaries.
 *
 * The X- and Y-halves of the affine are uncoupled, so we solve two independent
 * 3-parameter LSQ problems via the normal equations (closed form, Cramer’s rule
 * for the 3x3 matrix).
 */
object AffineFitter {

    fun fit(fids: List<Fiduciary>): AffineFitResult {
        require(fids.size >= 3) { throw AffineFitError.TooFewFiduciaries }

        val (a, b, c) = lsq(fids.map { Triple(it.pdfX, it.pdfY, it.longitude) })
        val (d, e, f) = lsq(fids.map { Triple(it.pdfX, it.pdfY, it.latitude) })
        val t = AffineTransform2D(a, b, c, d, e, f)

        var sumSq = 0.0
        for (fid in fids) {
            val predicted = t.apply(fid.pdfX, fid.pdfY)
            sumSq += squareMetres(predicted, fid.wgs84)
        }
        return AffineFitResult(t, sqrt(sumSq / fids.size))
    }

    private fun lsq(points: List<Triple<Double, Double, Double>>): Triple<Double, Double, Double> {
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sb = 0.0; var sxb = 0.0; var syb = 0.0
        val n = points.size.toDouble()
        for ((x, y, b) in points) {
            sx += x; sy += y
            sxx += x * x; syy += y * y; sxy += x * y
            sb += b; sxb += x * b; syb += y * b
        }
        val m = arrayOf(
            doubleArrayOf(sxx, sxy, sx),
            doubleArrayOf(sxy, syy, sy),
            doubleArrayOf(sx,  sy,  n)
        )
        val r = doubleArrayOf(sxb, syb, sb)
        return solve3x3(m, r) ?: throw AffineFitError.Degenerate
    }

    private fun solve3x3(m: Array<DoubleArray>, r: DoubleArray): Triple<Double, Double, Double>? {
        val det = det3(m)
        if (abs(det) < 1e-12) return null
        val mx = arrayOf(
            doubleArrayOf(r[0], m[0][1], m[0][2]),
            doubleArrayOf(r[1], m[1][1], m[1][2]),
            doubleArrayOf(r[2], m[2][1], m[2][2])
        )
        val my = arrayOf(
            doubleArrayOf(m[0][0], r[0], m[0][2]),
            doubleArrayOf(m[1][0], r[1], m[1][2]),
            doubleArrayOf(m[2][0], r[2], m[2][2])
        )
        val mz = arrayOf(
            doubleArrayOf(m[0][0], m[0][1], r[0]),
            doubleArrayOf(m[1][0], m[1][1], r[1]),
            doubleArrayOf(m[2][0], m[2][1], r[2])
        )
        return Triple(det3(mx) / det, det3(my) / det, det3(mz) / det)
    }

    private fun det3(m: Array<DoubleArray>): Double =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
        m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
        m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])

    private fun squareMetres(a: Wgs84Coordinate, b: Wgs84Coordinate): Double {
        val R = 6_371_000.0
        val dLat = (b.latitude  - a.latitude)  * PI / 180
        val dLon = (b.longitude - a.longitude) * PI / 180 *
            cos((a.latitude + b.latitude) / 2 * PI / 180)
        val m = R * sqrt(dLat * dLat + dLon * dLon)
        return m * m
    }
}
