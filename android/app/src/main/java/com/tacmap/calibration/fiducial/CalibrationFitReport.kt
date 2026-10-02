package com.tacmap.calibration.fiducial

import com.tacmap.calibration.ControlPair
import com.tacmap.calibration.GeoCrs
import com.tacmap.calibration.GeoDatum
import com.tacmap.calibration.GeorefFit
import com.tacmap.calibration.GeorefOrigin
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PlaneAffine
import com.tacmap.calibration.PlanePoint
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.fitPlaneAffine
import com.tacmap.calibration.pageEigenRatio
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The page a calibration runs against. [pageBox] is CropBox n MediaBox as 4 raw
 * user space corners (ll, lr, ur, ul), [rotate] the page's /Rotate.
 */
data class CalibrationSheet(
    val datum: GeoDatum,
    val pageBox: List<PagePoint>,
    val rotate: Int = 0,
    val pageIndex: Int = 0,
) {
    val minX: Double get() = pageBox.minOf { it.x }
    val maxX: Double get() = pageBox.maxOf { it.x }
    val minY: Double get() = pageBox.minOf { it.y }
    val maxY: Double get() = pageBox.maxOf { it.y }

    fun contains(p: PagePoint): Boolean = p.x in minX..maxX && p.y in minY..maxY

    /** ll, lr, ur, ul of the bbox */
    fun corners(): List<PagePoint> =
        listOf(PagePoint(minX, minY), PagePoint(maxX, minY), PagePoint(maxX, maxY), PagePoint(minX, maxY))
}

enum class FitGrade(val code: String, val messageKey: String) {
    GOOD("good", "calibration_grade_good"),
    FAIR("fair", "calibration_grade_fair"),
    POOR("poor", "calibration_grade_poor"),
}

enum class BlockReason(val code: String, val messageKey: String?) {
    NEED_MORE("needMore", null),
    DEGENERATE("degenerate", "calibration_degenerate"),
    INVALID("invalid", "calibration_invalid"),
    IMPLAUSIBLE("implausible", "calibration_implausible"),
}

sealed class FitIssue {
    data class Outlier(val number: Int, val distanceM: Double) : FitIssue()
    data class Ambiguous(val first: Int, val second: Int) : FitIssue()
    data class Disagree(val maxResidualM: Double) : FitIssue()
    data class DisagreeAddFifth(val maxResidualM: Double) : FitIssue()
}

enum class ConfirmReason(val code: String) {
    EXACT("exact"), POOR("poor"), OUTLIER("outlier"), AMBIGUOUS("ambiguous"), SPREAD_LOW("spreadLow"),
}

sealed class Finishability {
    data class Blocked(val reason: BlockReason, val needMore: Int = 0) : Finishability()
    data class Confirm(val reasons: List<ConfirmReason>) : Finishability()
    data object Ready : Finishability()
}

enum class PageCorner(val code: String, val messageKey: String) {
    TOP_LEFT("topLeft", "calibration_next_corner_top_left"),
    TOP_RIGHT("topRight", "calibration_next_corner_top_right"),
    BOTTOM_RIGHT("bottomRight", "calibration_next_corner_bottom_right"),
    BOTTOM_LEFT("bottomLeft", "calibration_next_corner_bottom_left"),
}

enum class RowStatus(val code: String) { OK("ok"), WARN("warn"), ERROR("error") }

/** contract s6, everything the fit panel and points sheet show */
data class FitReport(
    val n: Int,
    val planeZone: Int?,
    val south: Boolean?,
    val eigenRatio: Double?,
    val affine: PlaneAffine?,
    val diagonalM: Double?,
    val toleranceM: Double?,
    val rmsM: Double?,
    val maxM: Double?,
    val residualsM: Map<Int, Double>,
    val looRmsM: Map<Int, Double?>,
    val looM: Map<Int, Double?>,
    val anisotropy: Double?,
    val scaleDenominator: Double?,
    val grade: FitGrade?,
    val exact: Boolean,
    val issue: FitIssue?,
    val flagged: List<Int>,
    val rowStatus: Map<Int, RowStatus>?,
    val spreadLow: Boolean,
    val nextCorner: PageCorner?,
    val nextCornerPage: PagePoint?,
    val blocked: BlockReason?,
    val finish: Finishability,
    /** the fitted georef (crop = page box) whenever a fit exists, blocked or not */
    val fitGeoref: PdfGeoreference?,
    val primaryStatus: StatusMessage,
    val confirmMessages: List<StatusMessage>,
) {
    /** the georef the map may show: a fit that isn't blocked */
    val usableGeoref: PdfGeoreference? get() = fitGeoref.takeIf { blocked == null && n >= 3 }
}

/** contract s6.7, the far warning on a pending point */
enum class EntryPredictor(val code: String) {
    FIT_WITHOUT_EDITING("fitWithoutEditing"), CURRENT_FIT("currentFit"), BASE("base"), NONE("none"),
}

data class EntryCheck(
    val predictor: EntryPredictor,
    val leverage: Double?,
    val toleranceM: Double?,
    val predicted: Wgs84Coordinate?,
    val typed: Wgs84Coordinate?,
    val distanceM: Double?,
    val thresholdM: Double?,
    val warn: Boolean,
) {
    val message: StatusMessage? get() =
        if (warn && distanceM != null) StatusMessage("calibration_warn_far", mapOf("distance" to distanceM)) else null
}

/**
 * Pure fit classification (WP4 contract s5-s6), pinned by
 * testdata/calibration_fit_report.json. The fit itself is the WP1 least squares
 * (fitPlaneAffine), plane = UTM zone of the lowest numbered point.
 */
object CalibrationFitReport {
    const val DEGENERATE_EIGEN_RATIO = 0.02
    const val SPREAD_MIN_FRACTION = 0.25
    const val TOLERANCE_FLOOR_M = 10.0
    const val TOLERANCE_DIAGONAL_FRACTION = 0.0005
    const val FAIR_MULTIPLE = 3.0
    const val IMPLAUSIBLE_ANISOTROPY = 1.5
    const val PLAUSIBLE_SCALE_MIN = 1_000.0
    const val PLAUSIBLE_SCALE_MAX = 5_000_000.0
    const val OFF_EARTH_LAT = 85.0
    const val OUTLIER_MIN_POINTS = 5
    const val ENTRY_WARN_SIGMA = 3.0
    const val ENTRY_WARN_FLOOR_M = 50.0
    const val NEXT_CORNER_INSET = 0.1
    const val METRES_PER_PAGE_POINT_AT_UNIT_SCALE = 0.0254 / 72.0

    fun tolerance(diagonalM: Double): Double = max(TOLERANCE_FLOOR_M, TOLERANCE_DIAGONAL_FRACTION * diagonalM)

    /** zone + hemisphere of the lowest numbered point: its grid zone, else the standard zone (no Norway) */
    fun planeZoneOf(first: CalibrationPoint, datum: GeoDatum): Pair<Int, Boolean>? {
        val r = first.resolved(datum) ?: return null
        r.grid?.let { return it.zone to it.south }
        // clamped 1..60, lon exactly 180 is zone 60 not 61 (A2). same helper WP1 uses
        return GeoCrs.standardUtmZone(r.longitude) to (r.latitude < 0.0)
    }

    private fun toPlane(p: CalibrationPoint, datum: GeoDatum, zone: Int, south: Boolean): PlanePoint? {
        val r = p.resolved(datum) ?: return null
        val g = r.grid
        if (g != null && g.zone == zone && g.south == south) return PlanePoint(g.easting, g.northing)
        // more than 90 deg off the CM isn't forwardable, that's how a dropped zone digit shows up
        return GeoCrs.utm(zone, south).forward(r.latitude, r.longitude, datum.ellipsoid)
    }

    private fun residuals(a: PlaneAffine, pairs: List<ControlPair>): List<Double> = pairs.map {
        val p = a.apply(it.page.x, it.page.y)
        hypot(p.x - it.plane.x, p.y - it.plane.y)
    }

    private fun rms(r: List<Double>): Double = sqrt(r.sumOf { it * it } / r.size)

    private fun diagonal(a: PlaneAffine, sheet: CalibrationSheet): Double {
        val (c0, c1, c2, c3) = sheet.corners().map { a.apply(it.x, it.y) }
        return max(hypot(c0.x - c2.x, c0.y - c2.y), hypot(c1.x - c3.x, c1.y - c3.y))
    }

    private data class Fit(val affine: PlaneAffine, val pairs: List<ControlPair>)

    fun georefFor(
        affine: PlaneAffine,
        zone: Int,
        south: Boolean,
        sheet: CalibrationSheet,
        fit: GeorefFit? = null,
    ): PdfGeoreference = PdfGeoreference(
        page = sheet.pageIndex,
        crs = GeoCrs.utm(zone, south),
        datum = sheet.datum,
        affine = affine,
        crop = sheet.corners(),
        origin = GeorefOrigin.FIDUCIARIES,
        fit = fit,
    )

    fun evaluate(sheet: CalibrationSheet, unsorted: List<CalibrationPoint>): FitReport {
        val pts = unsorted.sortedBy { it.number }
        val n = pts.size
        val datum = sheet.datum
        val pages = pts.map { it.pagePoint }
        var blocked: BlockReason? = null
        val zoneOf = pts.firstOrNull()?.let { planeZoneOf(it, datum) }
        var eigen: Double? = null
        var fit: Fit? = null
        if (n >= 3) {
            val ratio = pageEigenRatio(pages)
            eigen = ratio
            val planes = if (zoneOf == null) null else pts.map { toPlane(it, datum, zoneOf.first, zoneOf.second) }
            if (ratio < DEGENERATE_EIGEN_RATIO) {
                blocked = BlockReason.DEGENERATE
            } else if (planes == null || planes.any { it == null }) {
                blocked = BlockReason.INVALID
            } else {
                val pairs = pages.zip(planes).map { (p, q) -> ControlPair(p, q!!) }
                val a = fitPlaneAffine(pairs)
                val det = a?.let { it.a * it.e - it.b * it.d }
                if (a == null || !a.isFinite() || det == null || det == 0.0 || !det.isFinite()) {
                    blocked = BlockReason.INVALID
                } else {
                    fit = Fit(a, pairs)
                }
            }
        }

        var diagonalM: Double? = null
        var tau: Double? = null
        var rmsM: Double? = null
        var maxM: Double? = null
        val residualsM = LinkedHashMap<Int, Double>()
        val looRmsM = LinkedHashMap<Int, Double?>()
        val looM = LinkedHashMap<Int, Double?>()
        var anisotropy: Double? = null
        var scale: Double? = null
        var grade: FitGrade? = null
        var issue: FitIssue? = null
        var fitGeoref: PdfGeoreference? = null
        if (fit != null && zoneOf != null) {
            val a = fit.affine
            val res = residuals(a, fit.pairs)
            val d = diagonal(a, sheet)
            val t = tolerance(d)
            val r = rms(res)
            diagonalM = d; tau = t; rmsM = r; maxM = res.max()
            pts.forEachIndexed { i, p -> residualsM[p.number] = res[i] }
            pts.forEachIndexed { i, p ->
                val rest = fit.pairs.filterIndexed { j, _ -> j != i }
                if (rest.size < 3 || pageEigenRatio(rest.map { it.page }) < DEGENERATE_EIGEN_RATIO) {
                    looRmsM[p.number] = null; looM[p.number] = null
                } else {
                    val ai = fitPlaneAffine(rest)
                    if (ai == null) {
                        looRmsM[p.number] = null; looM[p.number] = null
                    } else {
                        looRmsM[p.number] = rms(residuals(ai, rest))
                        val q = ai.apply(fit.pairs[i].page.x, fit.pairs[i].page.y)
                        looM[p.number] = hypot(q.x - fit.pairs[i].plane.x, q.y - fit.pairs[i].plane.y)
                    }
                }
            }
            val det = a.a * a.e - a.b * a.d
            val s = a.a * a.a + a.b * a.b + a.d * a.d + a.e * a.e
            val disc = sqrt(max(0.0, s * s - 4.0 * det * det))
            val sMax = sqrt((s + disc) / 2.0)
            val sMin = sqrt(max(0.0, (s - disc) / 2.0))
            val aniso = if (sMin > 0.0) sMax / sMin else Double.POSITIVE_INFINITY
            val denom = sqrt(abs(det)) / METRES_PER_PAGE_POINT_AT_UNIT_SCALE
            anisotropy = aniso; scale = denom
            val georef = georefFor(
                a, zoneOf.first, zoneOf.second, sheet,
                GeorefFit(r, res.max(), res, crossValidated = n >= 4),
            )
            fitGeoref = georef
            var maxLat = 0.0
            for (c in sheet.corners()) {
                val w = georef.toWGS84(c.x, c.y)
                if (w == null || !w.latitude.isFinite() || !w.longitude.isFinite()) {
                    blocked = BlockReason.INVALID
                } else {
                    maxLat = max(maxLat, abs(w.latitude))
                }
            }
            if (blocked == null && maxLat > OFF_EARTH_LAT) blocked = BlockReason.INVALID
            if (blocked == null && (aniso > IMPLAUSIBLE_ANISOTROPY || denom < PLAUSIBLE_SCALE_MIN || denom > PLAUSIBLE_SCALE_MAX)) {
                blocked = BlockReason.IMPLAUSIBLE
            }
            if (blocked == null) {
                if (n >= 4) grade = when {
                    r <= t -> FitGrade.GOOD
                    r <= FAIR_MULTIPLE * t -> FitGrade.FAIR
                    else -> FitGrade.POOR
                }
                if (n >= OUTLIER_MIN_POINTS && r > t) {
                    val good = pts.mapNotNull { p -> looRmsM[p.number]?.takeIf { it <= t }?.let { it to p.number } }
                        .sortedWith(compareBy({ it.first }, { it.second }))
                    issue = when {
                        good.size == 1 -> FitIssue.Outlier(good[0].second, looM[good[0].second]!!)
                        good.size >= 2 -> FitIssue.Ambiguous(good[0].second, good[1].second)
                        else -> FitIssue.Disagree(res.max())
                    }
                } else if (n == 4 && r > t) {
                    issue = FitIssue.DisagreeAddFifth(res.max())
                }
            }
        }
        val flagged = (issue as? FitIssue.Outlier)?.let { listOf(it.number) } ?: emptyList()
        val rowStatus = if (grade != null && tau != null) {
            residualsM.mapValues { (k, v) ->
                when {
                    v > FAIR_MULTIPLE * tau || k in flagged -> RowStatus.ERROR
                    v > tau -> RowStatus.WARN
                    else -> RowStatus.OK
                }
            }
        } else {
            null
        }
        var spreadLow = false
        if (n >= 3) {
            val xs = pages.map { it.x }
            val ys = pages.map { it.y }
            spreadLow = (xs.max() - xs.min()) < SPREAD_MIN_FRACTION * (sheet.maxX - sheet.minX) ||
                (ys.max() - ys.min()) < SPREAD_MIN_FRACTION * (sheet.maxY - sheet.minY)
        }
        val corner = if (n < 3 || spreadLow) nextCorner(sheet, pages) else null
        val finish: Finishability = when {
            n < 3 -> Finishability.Blocked(BlockReason.NEED_MORE, 3 - n)
            blocked != null -> Finishability.Blocked(blocked)
            else -> {
                val reasons = ArrayList<ConfirmReason>()
                if (n == 3) reasons += ConfirmReason.EXACT
                if (grade == FitGrade.POOR) reasons += ConfirmReason.POOR
                when (issue) {
                    is FitIssue.Outlier -> reasons += ConfirmReason.OUTLIER
                    is FitIssue.Ambiguous -> reasons += ConfirmReason.AMBIGUOUS
                    else -> Unit
                }
                if (spreadLow) reasons += ConfirmReason.SPREAD_LOW
                if (reasons.isEmpty()) Finishability.Ready else Finishability.Confirm(reasons)
            }
        }
        val primary = primaryStatus(n, blocked, issue, rmsM, grade)
        val confirm = (finish as? Finishability.Confirm)?.reasons?.map { confirmMessage(it, issue, rmsM) } ?: emptyList()
        return FitReport(
            n = n,
            planeZone = zoneOf?.first,
            south = zoneOf?.second,
            eigenRatio = eigen,
            affine = fit?.affine,
            diagonalM = diagonalM,
            toleranceM = tau,
            rmsM = rmsM,
            maxM = maxM,
            residualsM = residualsM,
            looRmsM = looRmsM,
            looM = looM,
            anisotropy = anisotropy,
            scaleDenominator = scale,
            grade = grade,
            exact = n == 3,
            issue = issue,
            flagged = flagged,
            rowStatus = rowStatus,
            spreadLow = spreadLow,
            nextCorner = corner?.first,
            nextCornerPage = corner?.second,
            blocked = blocked,
            finish = finish,
            fitGeoref = fitGeoref,
            primaryStatus = primary,
            confirmMessages = confirm,
        )
    }

    private fun primaryStatus(n: Int, blocked: BlockReason?, issue: FitIssue?, rmsM: Double?, grade: FitGrade?): StatusMessage {
        if (n == 0) return StatusMessage("calibration_intro")
        blocked?.messageKey?.let { return StatusMessage(it) }
        if (n < 3) return StatusMessage("calibration_need_points", mapOf("placed" to n))
        when (issue) {
            is FitIssue.Outlier -> return StatusMessage("calibration_outlier", mapOf("number" to issue.number, "distance" to issue.distanceM))
            is FitIssue.Ambiguous -> return StatusMessage("calibration_ambiguous", mapOf("first" to issue.first, "second" to issue.second))
            is FitIssue.Disagree -> return StatusMessage("calibration_disagree", mapOf("distance" to issue.maxResidualM))
            is FitIssue.DisagreeAddFifth -> return StatusMessage("calibration_disagree_add_fifth", mapOf("distance" to issue.maxResidualM))
            null -> Unit
        }
        if (n == 3) return StatusMessage("calibration_exact_fit")
        return StatusMessage(
            "calibration_fit_summary",
            mapOf("points" to n, "rms" to (rmsM ?: 0.0), "grade" to (grade?.messageKey ?: FitGrade.POOR.messageKey)),
        )
    }

    private fun confirmMessage(reason: ConfirmReason, issue: FitIssue?, rmsM: Double?): StatusMessage = when (reason) {
        ConfirmReason.EXACT -> StatusMessage("calibration_finish_confirm_exact")
        ConfirmReason.POOR -> StatusMessage("calibration_finish_confirm_poor", mapOf("rms" to (rmsM ?: 0.0)))
        ConfirmReason.OUTLIER -> (issue as FitIssue.Outlier).let {
            StatusMessage("calibration_outlier", mapOf("number" to it.number, "distance" to it.distanceM))
        }
        ConfirmReason.AMBIGUOUS -> (issue as FitIssue.Ambiguous).let {
            StatusMessage("calibration_ambiguous", mapOf("first" to it.first, "second" to it.second))
        }
        ConfirmReason.SPREAD_LOW -> StatusMessage("calibration_finish_confirm_spread")
    }

    /**
     * corners as VIEWED after /Rotate (TL, TR, BR, BL), each pulled 10% towards the
     * opposite corner; the one furthest from every point wins, ties in that order
     */
    fun nextCorner(sheet: CalibrationSheet, pages: List<PagePoint>): Pair<PageCorner, PagePoint> {
        val bl = PagePoint(sheet.minX, sheet.minY)
        val br = PagePoint(sheet.maxX, sheet.minY)
        val tr = PagePoint(sheet.maxX, sheet.maxY)
        val tl = PagePoint(sheet.minX, sheet.maxY)
        val opposite = mapOf(tl to br, br to tl, tr to bl, bl to tr)
        val seq = when (((sheet.rotate % 360) + 360) % 360) {
            90 -> listOf(bl, tl, tr, br)
            180 -> listOf(br, bl, tl, tr)
            270 -> listOf(tr, br, bl, tl)
            else -> listOf(tl, tr, br, bl)
        }
        var best: Triple<Double, PageCorner, PagePoint>? = null
        seq.forEachIndexed { i, c ->
            val o = opposite.getValue(c)
            val inset = PagePoint(c.x + NEXT_CORNER_INSET * (o.x - c.x), c.y + NEXT_CORNER_INSET * (o.y - c.y))
            val dmin = pages.minOfOrNull { hypot(inset.x - it.x, inset.y - it.y) } ?: Double.POSITIVE_INFINITY
            if (best == null || dmin > best!!.first) best = Triple(dmin, PageCorner.entries[i], inset)
        }
        return best!!.second to best!!.third
    }

    // ---------------------------------------------------------------- entry check (s6.7)

    /** hat value of [x0] against [pages]: x0'(A'A)^-1 x0 with rows [x y 1], done centred */
    fun leverage(pages: List<PagePoint>, x0: PagePoint): Double? {
        val n = pages.size
        if (n < 3) return null
        val mx = pages.sumOf { it.x } / n
        val my = pages.sumOf { it.y } / n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in pages) {
            val dx = p.x - mx; val dy = p.y - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val det = sxx * syy - sxy * sxy
        if (!det.isFinite() || det <= 0.0) return null
        val dx = x0.x - mx
        val dy = x0.y - my
        return 1.0 / n + (dx * dx * syy - 2.0 * dx * dy * sxy + dy * dy * sxx) / det
    }

    fun entryCheck(
        sheet: CalibrationSheet,
        points: List<CalibrationPoint>,
        editingNumber: Int?,
        pending: CalibrationPoint,
        base: PdfGeoreference?,
        current: FitReport = evaluate(sheet, points),
    ): EntryCheck {
        val page = pending.pagePoint
        var predictor = EntryPredictor.NONE
        var georef: PdfGeoreference? = null
        var tau: Double? = null
        var h: Double? = null
        if (editingNumber != null) {
            val others = points.filter { it.number != editingNumber }
            if (others.size >= 3 && pageEigenRatio(others.map { it.pagePoint }) >= DEGENERATE_EIGEN_RATIO) {
                val sub = evaluate(sheet, others)
                if (sub.fitGeoref != null && sub.toleranceM != null) {
                    predictor = EntryPredictor.FIT_WITHOUT_EDITING
                    georef = sub.fitGeoref
                    tau = sub.toleranceM
                    h = leverage(others.map { it.pagePoint }, page)
                }
            }
        }
        if (georef == null && points.size >= 3 && current.usableGeoref != null && current.toleranceM != null) {
            predictor = EntryPredictor.CURRENT_FIT
            georef = current.usableGeoref
            tau = current.toleranceM
            h = leverage(points.map { it.pagePoint }, page)
        }
        if (georef == null && base != null && base.origin != GeorefOrigin.PROVISIONAL) {
            val cs = sheet.corners().map { base.toWGS84(it.x, it.y) }
            if (cs.all { it != null }) {
                val d = max(
                    Geodesic.distance(cs[0]!!.latitude, cs[0]!!.longitude, cs[2]!!.latitude, cs[2]!!.longitude),
                    Geodesic.distance(cs[1]!!.latitude, cs[1]!!.longitude, cs[3]!!.latitude, cs[3]!!.longitude),
                )
                predictor = EntryPredictor.BASE
                georef = base
                tau = tolerance(d)
                h = 0.0
            }
        }
        val typed = pending.resolved(sheet.datum)?.let { sheet.datum.toWGS84(it.latitude, it.longitude) }
            ?.let { Wgs84Coordinate(it.latitude, it.longitude) }
        if (georef == null || tau == null || h == null || typed == null) {
            return EntryCheck(EntryPredictor.NONE, null, null, null, typed, null, null, false)
        }
        val predicted = georef.toWGS84(page.x, page.y)
            ?: return EntryCheck(EntryPredictor.NONE, null, null, null, typed, null, null, false)
        val d = Geodesic.distance(predicted.latitude, predicted.longitude, typed.latitude, typed.longitude)
        val threshold = max(ENTRY_WARN_SIGMA * tau * sqrt(1.0 + h), ENTRY_WARN_FLOOR_M)
        return EntryCheck(predictor, h, tau, predicted, typed, d, threshold, d > threshold)
    }
}
