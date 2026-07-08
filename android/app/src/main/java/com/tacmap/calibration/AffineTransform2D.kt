package com.tacmap.calibration

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
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
        // Scale-invariant singularity test: |det| / (‖row1‖·‖row2‖) is the sine
        // of the angle between the two basis vectors ∈ [0,1]. The old absolute
        // 1e-12 on `det` — which is ≈ degrees²/pixel² (~1e-10 at fine scale) —
        // falsely rejected valid high-zoom calibrations as singular.
        val rowScale = hypot(a, b) * hypot(d, e)
        if (rowScale <= 0.0 || abs(det) <= 1e-9 * rowScale) return null
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
    val rmsMetres: Double,
    /** False for an exactly-determined 3-point fit: it passes through all three
     *  points so its RMS is ~0 regardless of accuracy — not evidence the map is
     *  correct. True once N≥4 over-constrains the fit and the RMS is meaningful. */
    val crossValidated: Boolean
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
        if (fids.size < 3) throw AffineFitError.TooFewFiduciaries
        // Reject control points that lie on (or almost on) a single line: the
        // affine's perpendicular direction is then unconstrained and it
        // extrapolates wildly — a wrong map that still fits the fiduciaries.
        if (isDegenerate(fids)) throw AffineFitError.Degenerate

        val (a, b, c) = lsq(fids.map { Triple(it.pdfX, it.pdfY, it.longitude) })
        val (d, e, f) = lsq(fids.map { Triple(it.pdfX, it.pdfY, it.latitude) })
        val t = AffineTransform2D(a, b, c, d, e, f)

        var sumSq = 0.0
        for (fid in fids) {
            val predicted = t.apply(fid.pdfX, fid.pdfY)
            sumSq += squareMetres(predicted, fid.wgs84)
        }
        return AffineFitResult(t, sqrt(sumSq / fids.size), crossValidated = fids.size >= 4)
    }

    /** True when the fiduciaries are coincident or (near-)colinear. Uses the
     *  ratio of the covariance eigenvalues of the PDF-space points, which is
     *  scale-invariant — unlike an absolute determinant threshold, which is
     *  meaningless at pixel-coordinate magnitudes. */
    private fun isDegenerate(fids: List<Fiduciary>): Boolean {
        val n = fids.size.toDouble()
        var mx = 0.0; var my = 0.0
        for (f in fids) { mx += f.pdfX; my += f.pdfY }
        mx /= n; my /= n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (f in fids) {
            val dx = f.pdfX - mx; val dy = f.pdfY - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val tr = sxx + syy
        if (tr <= 0.0) return true // all points coincident
        val disc = sqrt(maxOf(0.0, tr * tr - 4.0 * (sxx * syy - sxy * sxy)))
        val l1 = (tr + disc) / 2.0
        val l2 = (tr - disc) / 2.0
        return l1 <= 0.0 || l2 / l1 < MIN_SPREAD_RATIO
    }

    /** Minor/major spread ratio below which the points count as colinear. */
    private const val MIN_SPREAD_RATIO = 1e-6

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
