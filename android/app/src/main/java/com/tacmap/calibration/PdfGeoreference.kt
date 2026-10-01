package com.tacmap.calibration

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point in PDF default user space of the page: raw coords, y up, INCLUDING any
 * MediaBox/CropBox origin offset and IGNORING /Rotate. Every parser and the
 * calibration flow produce these, the renderers map from them explicitly.
 */
data class PagePoint(val x: Double, val y: Double) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite()
}

/** page -> plane: X = a*x + b*y + c ; Y = d*x + e*y + f (same order as the fixture) */
data class PlaneAffine(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double,
) {
    val coefficients: List<Double> get() = listOf(a, b, c, d, e, f)

    fun isFinite(): Boolean = coefficients.all { it.isFinite() }

    fun apply(x: Double, y: Double): PlanePoint = PlanePoint(a * x + b * y + c, d * x + e * y + f)

    /** plane -> page, null when the matrix is singular */
    fun invert(px: Double, py: Double): PagePoint? {
        val det = a * e - b * d
        if (!det.isFinite() || det == 0.0) return null
        val dx = px - c
        val dy = py - f
        return PagePoint((e * dx - b * dy) / det, (-d * dx + a * dy) / det).takeIf { it.isFinite() }
    }

    /** same scale free singularity test AffineTransform2D uses */
    fun isInvertible(): Boolean {
        val det = a * e - b * d
        val rowScale = hypot(a, b) * hypot(d, e)
        return isFinite() && det.isFinite() && rowScale.isFinite() && rowScale > 0.0 &&
            abs(det) > 1e-9 * rowScale
    }

    companion object {
        fun of(values: List<Double>): PlaneAffine? {
            if (values.size != 6) return null
            return PlaneAffine(values[0], values[1], values[2], values[3], values[4], values[5])
        }

        /** LGIDict /CTM [A B C D E F] is pdf matrix order, so affine = [A, C, E, B, D, F] */
        fun fromPdfMatrix(m: List<Double>): PlaneAffine? {
            if (m.size != 6) return null
            return PlaneAffine(m[0], m[2], m[4], m[1], m[3], m[5])
        }
    }
}

enum class GeorefOrigin(val code: String) {
    ADOBE_VP("adobeVP"),
    LGI_DICT("lgiDict"),
    FIDUCIARIES("fiduciaries"),
    PROVISIONAL("provisional");

    companion object {
        fun fromCode(code: String): GeorefOrigin? = entries.firstOrNull { it.code == code }
    }
}

data class GeorefFit(
    val rmsMetres: Double,
    val maxResidualMetres: Double,
    val perPointMetres: List<Double>,
    val crossValidated: Boolean,
)

/**
 * Plan 02 s1 georeference: page user space -> plane (affine) -> geodetic on the
 * sheet datum (crs inverse) -> WGS84 (datum shift). Replaces the old single
 * page->lon/lat affine, which can't hold a UTM sheet (metres to km off).
 *
 *   toWGS84(page) = datum.toWGS84(crs.inverse(affine(page)))
 *   toPage(wgs84) = affine^-1(crs.forward(datum.fromWGS84(wgs84)))
 */
data class PdfGeoreference(
    val page: Int,
    val crs: GeoCrs,
    val datum: GeoDatum,
    val affine: PlaneAffine,
    val crop: List<PagePoint>,
    val origin: GeorefOrigin,
    val fit: GeorefFit? = null,
    /** the datum was guessed (WGS84/GRS80 sized spheroid, or unreadable GCS), worth a warning */
    val datumAssumed: Boolean = false,
) {
    // benign race, worst case two threads both build the same projector
    private var cachedProjector: Projector? = null

    private fun projector(): Projector? =
        cachedProjector ?: crs.projector(datum.ellipsoid)?.also { cachedProjector = it }

    fun isUsable(): Boolean =
        affine.isInvertible() && crop.size >= 3 && crop.all { it.isFinite() } && projector() != null

    /**
     * Same sanity gate as iOS isStructurallyValid: sane crop, invertible affine,
     * crop bbox with some area, and the first crop corner actually lands on earth.
     */
    fun isStructurallyValid(): Boolean {
        if (page < 0 || crop.size < 3 || !isUsable()) return false
        if (crop.any { abs(it.x) > MAX_SAFE_PDF_COORDINATE || abs(it.y) > MAX_SAFE_PDF_COORDINATE }) return false
        val b = cropBounds()
        if (!(b[2] - b[0] > 0.0) || !(b[3] - b[1] > 0.0)) return false
        return toWGS84(crop[0].x, crop[0].y) != null
    }

    /** vertex average of the crop, the antimeridian branch is picked around this */
    val cropCentroid: PagePoint
        get() = PagePoint(crop.sumOf { it.x } / maxOf(crop.size, 1), crop.sumOf { it.y } / maxOf(crop.size, 1))

    fun planeOf(x: Double, y: Double): PlanePoint = affine.apply(x, y)

    fun toWGS84(x: Double, y: Double): Wgs84Coordinate? {
        if (!x.isFinite() || !y.isFinite()) return null
        val plane = affine.apply(x, y)
        val geodetic = projector()?.inverse(plane.x, plane.y) ?: return null
        val wgs = datum.toWGS84(geodetic.latitude, geodetic.longitude) ?: return null
        return Wgs84Coordinate(wgs.latitude, wgs.longitude)
    }

    fun toPage(latitude: Double, longitude: Double): PagePoint? {
        val local = datum.fromWGS84(latitude, longitude) ?: return null
        val plane = projector()?.forward(local.latitude, local.longitude) ?: return null
        if (crs is GeoCrs.Geographic) {
            // keep lon on the sheet's branch so an antimeridian sheet doesn't jump 360 deg
            val centre = affine.apply(cropCentroid.x, cropCentroid.y).x
            return affine.invert(sameBranch(plane.x, centre), plane.y)
        }
        return affine.invert(plane.x, plane.y)
    }

    /** crop polygon bbox in page space: [minX, minY, maxX, maxY] */
    fun cropBounds(): DoubleArray {
        val xs = crop.map { it.x }
        val ys = crop.map { it.y }
        return doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /**
     * Best-fit lon/lat affine (least squares over a 9x9 grid of crop bbox points)
     * for the display code that still places the page linearly in lat/lon. It's a
     * stopgap until the tile renderer lands; exact for geographic sheets and a
     * couple of metres on a 1:25k UTM sheet, vs up to km with the old fits.
     */
    val bestFitLatLonAffine: AffineTransform2D? by lazy { fitLatLonAffine() }

    private fun fitLatLonAffine(): AffineTransform2D? {
        if (crop.size < 3) return null
        val (x0, y0, x1, y1) = cropBounds().toList()
        if (!(x1 > x0) || !(y1 > y0)) return null
        val pairs = ArrayList<ControlPair>(81)
        val ref = toWGS84((x0 + x1) / 2.0, (y0 + y1) / 2.0)
        for (i in 0..8) for (j in 0..8) {
            val x = x0 + (x1 - x0) * i / 8.0
            val y = y0 + (y1 - y0) * j / 8.0
            val w = toWGS84(x, y) ?: return null
            // same lon branch as the middle or the fit smears across 360 (iOS does this too)
            val lon = ref?.let { sameBranch(w.longitude, it.longitude) } ?: w.longitude
            pairs += ControlPair(PagePoint(x, y), PlanePoint(lon, w.latitude))
        }
        // plain LS on (lon, lat), not AffineFitter: that one validates fiduciaries and
        // refuses a lon past 180 on an antimeridian sheet
        val fit = fitPlaneAffine(pairs) ?: return null
        return AffineTransform2D(fit.a, fit.b, fit.c, fit.d, fit.e, fit.f).takeIf { it.inverted() != null }
    }

    /**
     * WGS84 box around the crop, edges densified so a curved sheet edge in lat/lon
     * doesn't get clipped. Used as the source coverage + camera framing.
     */
    fun wgs84Bounds(samplesPerEdge: Int = 8): Wgs84Bounds? {
        if (crop.size < 3) return null
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        for (i in crop.indices) {
            val p = crop[i]
            val q = crop[(i + 1) % crop.size]
            for (k in 0 until samplesPerEdge) {
                val t = k.toDouble() / samplesPerEdge
                val w = toWGS84(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t) ?: return null
                minLat = minOf(minLat, w.latitude); maxLat = maxOf(maxLat, w.latitude)
                minLon = minOf(minLon, w.longitude); maxLon = maxOf(maxLon, w.longitude)
            }
        }
        if (!(minLat < maxLat) || !(minLon < maxLon)) return null
        return Wgs84Bounds(Wgs84Coordinate(minLat, minLon), Wgs84Coordinate(maxLat, maxLon))
    }

    companion object {
        /** nominal scale for a plain PDF we know nothing about: 1 pt = 1/72 in, ~17.6 m at 1:50k */
        const val PROVISIONAL_METRES_PER_POINT = 50_000.0 * 0.0254 / 72.0

        /**
         * Plan s1 provisional placement for a plain PDF while it's being calibrated:
         * geographic WGS84, page box centred on [center], north up AS VIEWED (so a
         * /Rotate'd scan comes up the right way round), nominal 1:50,000. Never a
         * usable basemap, the UI labels it uncalibrated. Mirrors iOS provisional().
         */
        fun provisional(center: Wgs84Coordinate, cropBox: List<PagePoint>, rotation: Int = 0): PdfGeoreference? {
            if (cropBox.size < 3 || cropBox.any { !it.isFinite() }) return null
            val x0 = cropBox.minOf { it.x }
            val x1 = cropBox.maxOf { it.x }
            val y0 = cropBox.minOf { it.y }
            val y1 = cropBox.maxOf { it.y }
            if (!(x1 > x0) || !(y1 > y0)) return null
            // clamp a junk/polar camera to somewhere web mercator can draw
            val lat = if (center.latitude.isFinite()) center.latitude.coerceIn(-80.0, 80.0) else 0.0
            val lon = wrapDegrees(if (center.longitude.isFinite()) center.longitude else 0.0)
            val (me, mn) = metresPerPlaneUnit(GeoCrs.Geographic, GeoEllipsoid.WGS84, lat)
            val sx = PROVISIONAL_METRES_PER_POINT / me
            val sy = PROVISIONAL_METRES_PER_POINT / mn
            // /Rotate is clockwise as viewed, turn the page back the other way by the same amount
            val (cosR, sinR) = when (((rotation % 360) + 360) % 360) {
                90 -> 0.0 to -1.0
                180 -> -1.0 to 0.0
                270 -> 0.0 to 1.0
                else -> 1.0 to 0.0
            }
            val cx = (x0 + x1) / 2.0
            val cy = (y0 + y1) / 2.0
            val a = sx * cosR
            val b = -sx * sinR
            val d = sy * sinR
            val e = sy * cosR
            val affine = PlaneAffine(a, b, lon - a * cx - b * cy, d, e, lat - d * cx - e * cy)
            val crop = listOf(PagePoint(x0, y0), PagePoint(x1, y0), PagePoint(x1, y1), PagePoint(x0, y1))
            val g = PdfGeoreference(
                page = 0,
                crs = GeoCrs.Geographic,
                datum = GeoDatums.WGS84,
                affine = affine,
                crop = crop,
                origin = GeorefOrigin.PROVISIONAL,
                datumAssumed = true,
            )
            // a huge page at 1:50k near the pole could still run off the earth
            return g.takeIf { it.wgs84Bounds() != null && it.isStructurallyValid() }
        }
    }
}

/** shift lon by whole turns so it sits within 180 deg of [reference] */
internal fun sameBranch(lon: Double, reference: Double): Double {
    if (!lon.isFinite() || !reference.isFinite()) return lon
    val d = lon - reference
    if (d in -180.0..180.0) return lon
    return lon - 360.0 * Math.rint(d / 360.0)
}

/** (east, north) metres per plane unit: 1 for projected, local radii for lat/lon */
internal fun metresPerPlaneUnit(crs: GeoCrs, ellipsoid: GeoEllipsoid, latitude: Double): Pair<Double, Double> {
    if (crs !is GeoCrs.Geographic) return 1.0 to 1.0
    val p = latitude * PI / 180.0
    val w = 1.0 - ellipsoid.e2 * sin(p) * sin(p)
    val nu = ellipsoid.a / sqrt(w)
    val rho = ellipsoid.a * (1.0 - ellipsoid.e2) / (w * sqrt(w))
    return (PI / 180.0 * nu * cos(p)) to (PI / 180.0 * rho)
}

/** one page<->plane control pair */
internal data class ControlPair(val page: PagePoint, val plane: PlanePoint)

/**
 * Least squares page -> plane affine, centred first so 6e6 m northings don't eat
 * the precision. Exact for 3 points. Null when the page points can't pin it.
 */
internal fun fitPlaneAffine(pairs: List<ControlPair>): PlaneAffine? {
    if (pairs.size < 3) return null
    val n = pairs.size.toDouble()
    val mx = pairs.sumOf { it.page.x } / n
    val my = pairs.sumOf { it.page.y } / n
    val mX = pairs.sumOf { it.plane.x } / n
    val mY = pairs.sumOf { it.plane.y } / n
    var sxx = 0.0; var sxy = 0.0; var syy = 0.0
    var sxX = 0.0; var syX = 0.0; var sxY = 0.0; var syY = 0.0
    for (p in pairs) {
        val dx = p.page.x - mx
        val dy = p.page.y - my
        val dX = p.plane.x - mX
        val dY = p.plane.y - mY
        sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        sxX += dx * dX; syX += dy * dX; sxY += dx * dY; syY += dy * dY
    }
    val det = sxx * syy - sxy * sxy
    if (!det.isFinite() || det <= 0.0 || det <= 1e-18 * (sxx + syy) * (sxx + syy)) return null
    val a = (sxX * syy - syX * sxy) / det
    val b = (syX * sxx - sxX * sxy) / det
    val d = (sxY * syy - syY * sxy) / det
    val e = (syY * sxx - sxY * sxy) / det
    val c = mX - a * mx - b * my
    val f = mY - d * mx - e * my
    return PlaneAffine(a, b, c, d, e, f).takeIf { it.isFinite() }
}

/** min/max eigenvalue of the page points' 2x2 covariance, 0 when they're all on top of each other */
internal fun pageEigenRatio(points: List<PagePoint>): Double {
    if (points.isEmpty()) return 0.0
    val n = points.size.toDouble()
    val mx = points.sumOf { it.x } / n
    val my = points.sumOf { it.y } / n
    var sxx = 0.0; var syy = 0.0; var sxy = 0.0
    for (p in points) {
        val dx = p.x - mx
        val dy = p.y - my
        sxx += dx * dx; syy += dy * dy; sxy += dx * dy
    }
    sxx /= n; syy /= n; sxy /= n
    val tr = sxx + syy
    if (!tr.isFinite() || tr <= 0.0) return 0.0
    val disc = sqrt(maxOf(0.0, (sxx - syy) * (sxx - syy) / 4.0 + sxy * sxy))
    val l1 = tr / 2.0 + disc
    val l2 = tr / 2.0 - disc
    return if (l1 > 0.0) maxOf(0.0, l2) / l1 else 0.0
}

/** shoelace area, sign dropped */
internal fun polygonArea(points: List<PagePoint>): Double {
    if (points.size < 3) return 0.0
    var s = 0.0
    for (i in points.indices) {
        val p = points[i]
        val q = points[(i + 1) % points.size]
        s += p.x * q.y - q.x * p.y
    }
    return abs(s) / 2.0
}
