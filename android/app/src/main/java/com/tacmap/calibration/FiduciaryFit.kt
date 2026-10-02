package com.tacmap.calibration

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** A typed calibration reference, parsed but not yet converted to anything. */
sealed class FiduciaryReference {
    /** full UTM easting/northing resolved from the MGRS square + band */
    data class Mgrs(val zone: Int, val band: Char, val southern: Boolean, val easting: Double, val northing: Double) :
        FiduciaryReference()

    data class Utm(val zone: Int, val band: Char, val southern: Boolean, val easting: Double, val northing: Double) :
        FiduciaryReference()

    data class LatLon(val latitude: Double, val longitude: Double) : FiduciaryReference()
}

/**
 * The formats the fit core needs today: full MGRS (any spacing/case, 4-10 even
 * digits), UTM "55H 691000 6089000" and decimal "lat, lon". Shorthand, DMS etc
 * belong to the calibration UX package, this just has to agree with the fixture.
 * Everything is in the SHEET datum, the MGRS band pick runs on its ellipsoid.
 */
object FiduciaryReferenceParser {
    private const val COLUMN_SETS = "ABCDEFGH|JKLMNPQR|STUVWXYZ"
    private const val ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val latLon = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,\s]\s*(-?\d+(?:\.\d+)?)\s*$""")
    private val utm = Regex("""^(\d{1,2})([C-HJ-NP-X])\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)$""")
    private val mgrs = Regex("""^(\d{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])(\d*)$""")

    fun parse(input: String, datum: GeoDatum = GeoDatums.WGS84): FiduciaryReference? {
        // java's \s is ascii only but iOS's matches NBSP / thin space etc, and refs
        // pasted off a web page or pdf are full of them. squash every unicode space
        // to a plain one first so both apps read the same text
        val trimmed = unicodeSpacesToAscii(input).trim(' ')
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        latLon.matchEntire(trimmed)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull() ?: return null
            val lon = m.groupValues[2].toDoubleOrNull() ?: return null
            if (!GeodeticPoint(lat, lon).isValid()) return null
            return FiduciaryReference.LatLon(lat, lon)
        }
        val upper = trimmed.uppercase(Locale.US)
        utm.matchEntire(upper)?.let { m ->
            val zone = m.groupValues[1].toInt()
            if (zone !in 1..60) return null
            val band = m.groupValues[2][0]
            val e = m.groupValues[3].toDoubleOrNull() ?: return null
            val n = m.groupValues[4].toDoubleOrNull() ?: return null
            if (e !in 100_000.0..900_000.0 || n !in 0.0..10_000_000.0) return null
            return FiduciaryReference.Utm(zone, band, band < 'N', e, n)
        }
        val compact = upper.filterNot { it == ' ' }
        val m = mgrs.matchEntire(compact) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val band = m.groupValues[2][0]
        val column = m.groupValues[3][0]
        val row = m.groupValues[4][0]
        val digits = m.groupValues[5]
        // 0 or 2 digits is 100 km / 10 km, too coarse to calibrate against
        if (digits.length !in 4..10 || digits.length % 2 != 0) return null
        val half = digits.length / 2
        val scale = 10.0.pow(5 - half)
        val eDigits = digits.substring(0, half).toDouble() * scale
        val nDigits = digits.substring(half).toDouble() * scale
        val columns = COLUMN_SETS.split('|')[(zone - 1) % 3]
        val colIndex = columns.indexOf(column)
        if (colIndex < 0) return null
        val easting = (colIndex + 1) * 100_000.0 + eDigits
        val rowIndex = ROW_LETTERS.indexOf(row)
        val offset = if (zone % 2 == 0) 5 else 0
        val northingBase = ((rowIndex - offset + 20) % 20) * 100_000.0 + nDigits
        val southern = band < 'N'
        val crs = GeoCrs.utm(zone, southern)
        val bandIndex = BANDS.indexOf(band)
        val bandSouth = -80.0 + 8.0 * bandIndex
        val bandNorth = bandSouth + if (band == 'X') 12.0 else 8.0
        // pick the 2000 km cycle that lands inside the band (+-1 deg of slack)
        for (k in 0 until 6) {
            val candidate = northingBase + k * 2_000_000.0
            val lat = crs.inverse(easting, candidate, datum.ellipsoid)?.latitude ?: continue
            if (lat >= bandSouth - 1.0 && lat <= bandNorth + 1.0) {
                return FiduciaryReference.Mgrs(zone, band, southern, easting, candidate)
            }
        }
        return null
    }

    // fiduciaryFits.whiteSpaceCodePoints: Unicode White_Space (swift's isWhitespace)
    // plus the 1C-1F info separators java counts. spelled out rather than leaning on
    // Char.isWhitespace so an older ICU on some device can't move the goalposts
    internal fun isReferenceSpace(c: Char): Boolean = when (c) {
        in '\u0009'..'\u000D', in '\u001C'..'\u0020', '\u0085', '\u00A0', '\u1680', in '\u2000'..'\u200A',
        '\u2028', '\u2029', '\u202F', '\u205F', '\u3000' -> true
        else -> false
    }

    internal fun unicodeSpacesToAscii(s: String): String {
        if (s.none(::isReferenceSpace)) return s
        return buildString(s.length) { s.forEach { append(if (isReferenceSpace(it)) ' ' else it) } }
    }
}

data class FiduciaryPoint(val page: PagePoint, val reference: FiduciaryReference)

data class FiduciaryFitResult(
    val zone: Int,
    val southern: Boolean,
    val crs: GeoCrs.TransverseMercator,
    val datum: GeoDatum,
    val pagePoints: List<PagePoint>,
    val planePoints: List<PlanePoint>,
    val eigenRatio: Double,
    /** collinear or clustered, the fit is refused (affine null) */
    val degenerate: Boolean,
    /** point spread over the crop bbox, (x, y) fractions */
    val spanFraction: Pair<Double, Double>,
    val spanWarning: Boolean,
    val crossValidated: Boolean,
    val affine: PlaneAffine?,
    val residualsMetres: List<Double> = emptyList(),
    val rmsMetres: Double = 0.0,
    val maxResidualMetres: Double = 0.0,
    /** point i against the fit of the others, null when those alone are degenerate */
    val leaveOneOutMetres: List<Double?>? = null,
    val leaveOneOutRmsMetres: List<Double?>? = null,
    val flaggedOutliers: List<Int> = emptyList(),
    /** 3 points pass through the fit exactly, RMS 0 says nothing */
    val exactFit: Boolean = false,
) {
    val message: String? get() = if (exactFit) "exact fit - add a 4th point to check accuracy" else null

    fun georeference(crop: List<PagePoint>, page: Int = 0): PdfGeoreference? {
        val a = affine ?: return null
        return PdfGeoreference(
            page = page,
            crs = crs,
            datum = datum,
            affine = a,
            crop = crop,
            origin = GeorefOrigin.FIDUCIARIES,
            fit = GeorefFit(rmsMetres, maxResidualMetres, residualsMetres, crossValidated),
        ).takeIf { it.isUsable() }
    }
}

/**
 * Plan 02 s1 fiduciary fit: every reference goes into the UTM zone (and
 * hemisphere) of the FIRST point on the sheet datum, then one least squares
 * page -> plane affine. A UTM gridded sheet is linear in E/N, not in lat/lon, so
 * this is exact where the old lon/lat affine was tens of metres off at 1:50k.
 */
object FiduciaryFitter {
    const val DEGENERATE_EIGEN_RATIO = 0.02
    const val SPAN_WARNING_FRACTION = 0.25
    private const val OUTLIER_FLOOR_METRES = 5.0
    private const val OUTLIER_FACTOR = 5.0

    /**
     * @param cropBBox [minX, minY, maxX, maxY] page space, for the span warning
     * @param zoneOverride zone + southern when the caller already knows it (a
     *   stored MGRS on a migrated session), otherwise it comes off point 0
     */
    fun fit(
        points: List<FiduciaryPoint>,
        datum: GeoDatum,
        cropBBox: DoubleArray,
        zoneOverride: Pair<Int, Boolean>? = null,
    ): FiduciaryFitResult? {
        if (points.size < 3 || points.any { !it.page.isFinite() }) return null
        if (points.any { kotlin.math.abs(it.page.x) > MAX_SAFE_PDF_COORDINATE || kotlin.math.abs(it.page.y) > MAX_SAFE_PDF_COORDINATE }) return null
        val (zone, southern) = zoneOverride ?: when (val first = points[0].reference) {
            is FiduciaryReference.Mgrs -> first.zone to first.southern
            is FiduciaryReference.Utm -> first.zone to first.southern
            is FiduciaryReference.LatLon -> GeoCrs.standardUtmZone(first.longitude) to (first.latitude < 0.0)
        }
        if (zone !in 1..60) return null
        val crs = GeoCrs.utm(zone, southern)
        val projector = crs.projector(datum.ellipsoid) ?: return null
        val planes = points.map { p -> planeOf(p.reference, zone, southern, datum, projector) ?: return null }
        val pages = points.map { it.page }

        val ratio = pageEigenRatio(pages)
        val degenerate = ratio < DEGENERATE_EIGEN_RATIO
        val xs = pages.map { it.x }
        val ys = pages.map { it.y }
        val cropW = cropBBox[2] - cropBBox[0]
        val cropH = cropBBox[3] - cropBBox[1]
        val span = (if (cropW > 0) (xs.max() - xs.min()) / cropW else 0.0) to
            (if (cropH > 0) (ys.max() - ys.min()) / cropH else 0.0)
        val base = FiduciaryFitResult(
            zone = zone,
            southern = southern,
            crs = crs,
            datum = datum,
            pagePoints = pages,
            planePoints = planes,
            eigenRatio = ratio,
            degenerate = degenerate,
            spanFraction = span,
            spanWarning = minOf(span.first, span.second) < SPAN_WARNING_FRACTION,
            crossValidated = points.size >= 4,
            affine = null,
        )
        if (degenerate) return base
        val pairs = pages.zip(planes).map { (p, q) -> ControlPair(p, q) }
        val affine = fitPlaneAffine(pairs)?.takeIf { it.isInvertible() } ?: return base.copy(degenerate = true)
        val residuals = residuals(affine, pairs)
        val rms = sqrt(residuals.sumOf { it * it } / residuals.size)
        if (pairs.size < 4) {
            return base.copy(
                affine = affine, residualsMetres = residuals, rmsMetres = rms,
                maxResidualMetres = residuals.max(), exactFit = true,
            )
        }
        val loo = ArrayList<Double?>(pairs.size)
        val looRms = ArrayList<Double?>(pairs.size)
        for (i in pairs.indices) {
            val rest = pairs.filterIndexed { j, _ -> j != i }
            val restFit = if (pageEigenRatio(rest.map { it.page }) < DEGENERATE_EIGEN_RATIO) null else fitPlaneAffine(rest)
            if (restFit == null) {
                loo += null; looRms += null
                continue
            }
            val p = restFit.apply(pairs[i].page.x, pairs[i].page.y)
            loo += hypot(p.x - pairs[i].plane.x, p.y - pairs[i].plane.y)
            val rr = residuals(restFit, rest)
            looRms += sqrt(rr.sumOf { it * it } / rr.size)
        }
        // one at a time: flag the point whose removal leaves the tightest fit, if
        // it sits way off that fit. user fixes it, we run again
        val flagged = if (pairs.size >= 5) {
            val usable = looRms.indices.filter { looRms[it] != null }
            val k = usable.minWithOrNull(compareBy<Int>({ looRms[it]!! }, { it }))
            if (k != null && loo[k]!! > max(OUTLIER_FLOOR_METRES, OUTLIER_FACTOR * looRms[k]!!)) listOf(k) else emptyList()
        } else {
            emptyList()
        }
        return base.copy(
            affine = affine,
            residualsMetres = residuals,
            rmsMetres = rms,
            maxResidualMetres = residuals.max(),
            leaveOneOutMetres = loo,
            leaveOneOutRmsMetres = looRms,
            flaggedOutliers = flagged,
        )
    }

    private fun residuals(affine: PlaneAffine, pairs: List<ControlPair>): List<Double> = pairs.map { pair ->
        val p = affine.apply(pair.page.x, pair.page.y)
        hypot(p.x - pair.plane.x, p.y - pair.plane.y)
    }

    // grid refs already in the target zone go straight in; anything else goes
    // through lat/lon on the sheet ellipsoid, which is fine across a zone boundary
    private fun planeOf(
        reference: FiduciaryReference,
        zone: Int,
        southern: Boolean,
        datum: GeoDatum,
        projector: Projector,
    ): PlanePoint? {
        val geodetic: GeodeticPoint = when (reference) {
            is FiduciaryReference.Mgrs -> {
                if (reference.zone == zone && reference.southern == southern) {
                    return PlanePoint(reference.easting, reference.northing)
                }
                GeoCrs.utm(reference.zone, reference.southern)
                    .inverse(reference.easting, reference.northing, datum.ellipsoid) ?: return null
            }
            is FiduciaryReference.Utm -> {
                if (reference.zone == zone && reference.southern == southern) {
                    return PlanePoint(reference.easting, reference.northing)
                }
                GeoCrs.utm(reference.zone, reference.southern)
                    .inverse(reference.easting, reference.northing, datum.ellipsoid) ?: return null
            }
            is FiduciaryReference.LatLon -> GeodeticPoint(reference.latitude, reference.longitude)
        }
        return projector.forward(geodetic.latitude, geodetic.longitude)
    }

    /**
     * Refit a stored calibration. Stored fiduciaries carry WGS84 lat/lon (the
     * picker shifted them before saving), so fit on WGS84 in the zone of the first
     * point, taking that zone off its stored MGRS when it still parses.
     */
    fun refitStored(fiduciaries: List<Fiduciary>, crop: List<PagePoint>): FiduciaryFitResult? {
        if (fiduciaries.size < 3 || fiduciaries.any { !it.isSafeAffineInput() } || crop.size < 3) return null
        val zone = storedZone(fiduciaries[0])
        val xs = crop.map { it.x }
        val ys = crop.map { it.y }
        return fit(
            points = fiduciaries.map {
                FiduciaryPoint(PagePoint(it.pdfX, it.pdfY), FiduciaryReference.LatLon(it.latitude, it.longitude))
            },
            datum = GeoDatums.WGS84,
            cropBBox = doubleArrayOf(xs.min(), ys.min(), xs.max(), ys.max()),
            zoneOverride = zone,
        )
    }

    /**
     * (zone, southern) a stored calibration fits in: whatever the first point's typed
     * MGRS/UTM says, else the standard 6 degree zone of its lat/lon. Same as iOS
     * FiduciaryFitter.storedZone, pinned by fiduciaryFits.storedSets
     */
    fun storedZone(first: Fiduciary): Pair<Int, Boolean> = when (val parsed = FiduciaryReferenceParser.parse(first.mgrs)) {
        is FiduciaryReference.Mgrs -> parsed.zone to parsed.southern
        is FiduciaryReference.Utm -> parsed.zone to parsed.southern
        else -> GeoCrs.standardUtmZone(first.longitude) to (first.latitude < 0.0)
    }
}
