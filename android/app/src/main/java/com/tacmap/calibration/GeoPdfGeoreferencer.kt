package com.tacmap.calibration

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// what the PDF reader pulled out of the page, before any geo logic. Numbers are
// kept exactly as read: null = not a number / wrong type, +-Inf = overflowed.
// A key that's present but the wrong type reads as listOf(null) so it counts as
// malformed rather than absent. Kept pure so the whole construction is JVM tested
// against testdata/pdf_georef.json without PDFBox.
// ---------------------------------------------------------------------------

data class GeoPdfViewportData(
    val name: String? = null,
    /** /Measure << /Subtype /GEO >> present */
    val isGeo: Boolean = true,
    val bbox: List<Double?>? = null,
    val gpts: List<Double?>? = null,
    val lpts: List<Double?>? = null,
    val bounds: List<Double?>? = null,
    val gcsWkt: String? = null,
    val gcsEpsg: Int? = null,
    /** /GCS present but not a dictionary, /WKT not a string or /EPSG not a pdf integer */
    val gcsMalformed: Boolean = false,
)

sealed class LgiDatumData {
    data class Code(val code: String) : LgiDatumData()
    data class Inline(
        /** null when /Ellipsoid or the key is missing / not a number */
        val semiMajorAxis: Double?,
        val inverseFlattening: Double?,
        /** null = no /ToWGS84; present -> [dx, dy, dz] with nulls for junk, [null] when it's not a dict */
        val toWgs84: List<Double?>?,
    ) : LgiDatumData()
    data object Invalid : LgiDatumData()
}

data class GeoPdfLgiProjectionData(
    /** null when missing or not a name/string, both malformed */
    val projectionType: String? = null,
    /** null = no /Zone key (look at /Display), NaN = there but not a number */
    val zone: Double? = null,
    /** null = no key, GeoPdfParser.INVALID_TEXT = there but not text */
    val hemisphere: String? = null,
    val datum: LgiDatumData? = null,
    /** CentralMeridian, OriginLatitude, FalseEasting, FalseNorthing, ScaleFactor, StandardParallelOne/Two */
    val parameters: Map<String, Double?> = emptyMap(),
    val units: String? = null,
)

data class GeoPdfLgiEntryData(
    val description: String? = null,
    val ctm: List<Double?>? = null,
    /** each [x y X Y], a null row = not an array */
    val registration: List<List<Double?>?>? = null,
    val neatline: List<Double?>? = null,
    val projection: GeoPdfLgiProjectionData? = null,
    /** some producers park Zone/Hemisphere under /Display instead */
    val display: GeoPdfLgiProjectionData? = null,
)

data class GeoPdfPageData(
    val pageIndex: Int = 0,
    val mediaBox: List<Double>? = null,
    val cropBox: List<Double>? = null,
    val rotate: Int = 0,
    /** GEO viewports of the /VP the reader settled on (page, else catalog), in order */
    val viewports: List<GeoPdfViewportData> = emptyList(),
    /** more /VP entries than we'll look at, a decompression bomb or junk */
    val viewportsOversized: Boolean = false,
    val lgiDeclared: Boolean = false,
    val lgiEntries: List<GeoPdfLgiEntryData> = emptyList(),
    val lgiOversized: Boolean = false,
)

// ---------------------------------------------------------------------------
// results
// ---------------------------------------------------------------------------

enum class GeorefRejectReason(val code: String) {
    LPTS_OUT_OF_RANGE("lptsOutOfRange"),
    NON_FINITE("nonFinite"),
    GPTS_OFF_EARTH("gptsOffEarth"),
    RMS_GATE("rmsGate"),
    DEGENERATE_VIEWPORT("degenerateViewport"),
    MALFORMED("malformed"),
    UNKNOWN_DATUM("unknownDatum"),
    UNSUPPORTED_PROJECTION("unsupportedProjection"),
}

data class GeorefSelection(
    /** "viewport" or "lgiEntry" */
    val kind: String,
    val index: Int,
    val name: String?,
    /** lgi only: "ctm" or "registration" */
    val source: String? = null,
)

/** the page <-> geodetic pairs a /VP was fitted from (GPTS in the GCS datum) */
data class GeorefControl(val page: PagePoint, val geodetic: GeodeticPoint, val plane: PlanePoint)

/**
 * Everything the RMS gate looked at, same fields as iOS PdfGeorefFitStats and the
 * fixture's expected.fit. Diagonal is the control points' plane bbox in metres.
 */
data class GeorefFitStats(
    val rmsMetres: Double,
    val maxResidualMetres: Double,
    val residualsMetres: List<Double>,
    val sheetDiagonalMetres: Double,
    val gateLimitMetres: Double,
    val passesGate: Boolean,
) {
    fun toFit(): GeorefFit = GeorefFit(rmsMetres, maxResidualMetres, residualsMetres, crossValidated = residualsMetres.size >= 4)
}

sealed class GeoPdfGeorefResult {
    data class Georeferenced(
        val georef: PdfGeoreference,
        val selection: GeorefSelection,
        val controls: List<GeorefControl> = emptyList(),
        /** null for a CTM that won (nothing got fitted) */
        val fitStats: GeorefFitStats? = null,
    ) : GeoPdfGeorefResult()

    /** no /VP GEO viewport and no /LGIDict: a plain PDF */
    data object NoGeoreference : GeoPdfGeorefResult()

    /**
     * georef was declared but we can't trust it; never a silent camera box.
     * [fitStats] only rides along on rmsGate, so the alert can say how far off it was
     */
    data class Rejected(val reason: GeorefRejectReason, val fitStats: GeorefFitStats? = null) : GeoPdfGeorefResult()
}

/**
 * Plan 02 s1 construction for ISO 32000 /VP and OGC LGIDict. Pure: takes what the
 * reader parsed and returns a PdfGeoreference in the projection-space model, or a
 * reason code. Check order, selection and precedence follow iOS PdfGeorefBuilder
 * step for step, pinned by the fixture's rejections (both platforms).
 */
object GeoPdfGeoreferencer {
    internal const val MAX_CONTROL_VALUES = 8_192
    // inclusive, with a hair of slack: producers write LPTS computed off the BBox and
    // "exactly -0.5" comes out as -0.500000001
    private const val LPTS_MIN = -0.5 - 1e-6
    private const val LPTS_MAX = 1.5 + 1e-6
    private const val DEGENERATE_EIGEN_RATIO = 1e-6
    private const val CTM_AGREEMENT_METRES = 1.0
    private const val FOOT = 0.3048
    private const val US_SURVEY_FOOT = 1200.0 / 3937.0

    fun build(page: GeoPdfPageData): GeoPdfGeorefResult {
        // ISO /VP first, then LGIDict. A broken /VP next to a good LGIDict still lands
        // the sheet (it's a real georef); otherwise the first rejection is what we report
        var rejection: GeoPdfGeorefResult.Rejected? = null
        if (page.viewportsOversized) {
            rejection = GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED)
        } else if (page.viewports.any { it.isGeo }) {
            when (val iso = fromViewports(page.viewports, page.pageIndex)) {
                is GeoPdfGeorefResult.Georeferenced -> return iso
                is GeoPdfGeorefResult.Rejected -> rejection = iso
                GeoPdfGeorefResult.NoGeoreference -> Unit
            }
        }
        if (page.lgiOversized) {
            rejection = rejection ?: GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED)
        } else if (page.lgiDeclared) {
            when (val lgi = fromLgiEntries(page.lgiEntries, pageBox(page), page.pageIndex)) {
                is GeoPdfGeorefResult.Georeferenced -> return lgi
                is GeoPdfGeorefResult.Rejected -> rejection = rejection ?: lgi
                GeoPdfGeorefResult.NoGeoreference -> Unit
            }
        }
        return rejection ?: GeoPdfGeorefResult.NoGeoreference
    }

    // crop box clipped to the media box (what's actually shown), for a CTM entry with no neatline
    private fun pageBox(page: GeoPdfPageData): List<Double>? {
        val media = PdfBox.of(page.mediaBox) ?: return page.mediaBox
        val box = PdfBox.of(page.cropBox)?.let { media.intersect(it) } ?: media
        return listOf(box.llx, box.lly, box.urx, box.ury)
    }

    /** what a parsed number array is: absent, wrong type, overflowed or fine */
    private enum class Numbers { MISSING, MALFORMED, NON_FINITE, OK }

    // a null entry (wrong type) beats an overflow, same as the iOS reader
    private fun classify(values: List<Double?>?): Numbers = when {
        values == null -> Numbers.MISSING
        values.any { it == null } -> Numbers.MALFORMED
        values.any { !it!!.isFinite() } -> Numbers.NON_FINITE
        else -> Numbers.OK
    }

    // ------------------------------------------------------------------ ISO /VP

    private sealed class Attempt {
        data class Ok(val result: GeoPdfGeorefResult.Georeferenced, val area: Double) : Attempt()
        data class Bad(val reason: GeorefRejectReason, val area: Double, val stats: GeorefFitStats? = null) : Attempt()
    }

    /**
     * Largest BBox area among the GEO viewports that give a valid georef. USGS puts
     * the state locator and adjoining-sheet insets next to the map body, and QTopo
     * lists an inset first, so first-usable is wrong. Nothing valid -> the reason
     * from the biggest declared viewport. Non-GEO viewports aren't candidates and
     * don't count towards the index.
     */
    fun fromViewports(viewports: List<GeoPdfViewportData>, pageIndex: Int = 0): GeoPdfGeorefResult {
        val geo = viewports.filter { it.isGeo }
        if (geo.isEmpty()) return GeoPdfGeorefResult.NoGeoreference
        var best: Attempt.Ok? = null
        var worst: Attempt.Bad? = null
        geo.forEachIndexed { index, vp ->
            when (val attempt = viewport(vp, index, pageIndex)) {
                is Attempt.Ok -> if (best == null || attempt.area > best!!.area) best = attempt
                is Attempt.Bad -> if (worst == null || attempt.area > worst!!.area) worst = attempt
            }
        }
        best?.let { return it.result }
        return GeoPdfGeorefResult.Rejected(worst?.reason ?: GeorefRejectReason.MALFORMED, worst?.stats)
    }

    private fun bboxArea(bbox: List<Double?>?): Double {
        if (bbox == null || bbox.size != 4 || bbox.any { it == null }) return -1.0
        val a = abs((bbox[2]!! - bbox[0]!!) * (bbox[3]!! - bbox[1]!!))
        return if (a.isFinite()) a else -1.0
    }

    private fun viewport(vp: GeoPdfViewportData, index: Int, pageIndex: Int): Attempt {
        val area = bboxArea(vp.bbox)
        fun bad(reason: GeorefRejectReason) = Attempt.Bad(reason, area)

        // structure first: types, finiteness, lengths
        for (arr in listOf(vp.bbox, vp.lpts, vp.gpts)) {
            when (classify(arr)) {
                Numbers.MISSING, Numbers.MALFORMED -> return bad(GeorefRejectReason.MALFORMED)
                Numbers.NON_FINITE -> return bad(GeorefRejectReason.NON_FINITE)
                Numbers.OK -> Unit
            }
        }
        when (classify(vp.bounds)) {
            Numbers.MALFORMED -> return bad(GeorefRejectReason.MALFORMED)
            Numbers.NON_FINITE -> return bad(GeorefRejectReason.NON_FINITE)
            else -> Unit
        }
        val bbox = vp.bbox!!.map { it!! }
        val l = vp.lpts!!.map { it!! }
        val g = vp.gpts!!.map { it!! }
        if (bbox.size != 4 || l.size % 2 != 0 || g.size % 2 != 0 || l.size != g.size || g.size > MAX_CONTROL_VALUES) {
            return bad(GeorefRejectReason.MALFORMED)
        }
        // a broken /Bounds rejects the viewport, it's not quietly swapped for the BBox
        val bounds = vp.bounds?.map { it!! }
        if (bounds != null && (bounds.size % 2 != 0 || bounds.size < 6 || bounds.size > MAX_CONTROL_VALUES)) {
            return bad(GeorefRejectReason.MALFORMED)
        }
        if (bbox.any { abs(it) > MAX_SAFE_PDF_COORDINATE }) return bad(GeorefRejectReason.MALFORMED)
        val n = g.size / 2
        if (n < 3) return bad(GeorefRejectReason.DEGENERATE_VIEWPORT)
        val (x0, y0, x1, y1) = bbox
        val dx = x1 - x0
        val dy = y1 - y0
        if (!(abs(dx * dy) > 0.0)) return bad(GeorefRejectReason.DEGENERATE_VIEWPORT)
        // USGS writes LPTS a hair outside [0,1] on purpose (true north up at the quad
        // centre), GDAL maps them linearly with no check, so allow a generous band
        if (l.any { it < LPTS_MIN || it > LPTS_MAX }) return bad(GeorefRejectReason.LPTS_OUT_OF_RANGE)

        // GCS. Missing entirely -> unreadable -> local TM fallback, wrong types are malformed
        if (vp.gcsMalformed) return bad(GeorefRejectReason.MALFORMED)
        val gcs = GcsParser.resolve(vp.gcsWkt, vp.gcsEpsg)
        if (gcs.status == GcsStatus.MALFORMED) return bad(GeorefRejectReason.MALFORMED)

        // GPTS are (lat, lon) in the GCS's own datum, lon measured from its PRIMEM.
        // Past 180 after adding the PRIMEM is off the earth, not wrapped (iOS too)
        val geodetic = (0 until n).map { i ->
            val lat = g[2 * i]
            val lon = g[2 * i + 1] + gcs.primeMeridian
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return bad(GeorefRejectReason.GPTS_OFF_EARTH)
            GeodeticPoint(lat, lon)
        }
        if (gcs.status == GcsStatus.UNKNOWN_DATUM) return bad(GeorefRejectReason.UNKNOWN_DATUM)
        val datum = gcs.datum ?: return bad(GeorefRejectReason.UNKNOWN_DATUM)
        val crs = if (gcs.status == GcsStatus.FALLBACK_LOCAL_TM) localTransverseMercator(geodetic)
        else gcs.crs ?: return bad(GeorefRejectReason.UNSUPPORTED_PROJECTION)

        val pagePoints = (0 until n).map { i -> PagePoint(x0 + l[2 * i] * dx, y0 + l[2 * i + 1] * dy) }
        if (pageEigenRatio(pagePoints) < DEGENERATE_EIGEN_RATIO) return bad(GeorefRejectReason.DEGENERATE_VIEWPORT)

        // a projection that can't take the GPTS (TM > 90 deg off its CM etc) means they're not on its earth
        val projector = crs.projector(datum.ellipsoid) ?: return bad(GeorefRejectReason.GPTS_OFF_EARTH)
        val planes = geodetic.map {
            projector.forward(it.latitude, it.longitude) ?: return bad(GeorefRejectReason.GPTS_OFF_EARTH)
        }
        val pairs = pagePoints.zip(planes).map { (p, q) -> ControlPair(p, q) }
        val affine = fitPlaneAffine(pairs)?.takeIf { it.isInvertible() }
            ?: return bad(GeorefRejectReason.DEGENERATE_VIEWPORT)
        val stats = fitStats(affine, pairs, crs, datum.ellipsoid, projector)
            ?: return bad(GeorefRejectReason.MALFORMED)
        if (!stats.passesGate) return Attempt.Bad(GeorefRejectReason.RMS_GATE, area, stats)

        val crop = bounds?.let { b -> (0 until b.size / 2).map { i -> PagePoint(x0 + b[2 * i] * dx, y0 + b[2 * i + 1] * dy) } }
            ?: listOf(PagePoint(x0, y0), PagePoint(x1, y0), PagePoint(x1, y1), PagePoint(x0, y1))
        val georef = PdfGeoreference(
            page = pageIndex,
            crs = crs,
            datum = datum,
            affine = affine,
            crop = crop,
            origin = GeorefOrigin.ADOBE_VP,
            fit = stats.toFit(),
            datumAssumed = gcs.datumAssumed,
        )
        if (!georef.isStructurallyValid() || georef.wgs84Bounds() == null) return bad(GeorefRejectReason.DEGENERATE_VIEWPORT)
        val controls = pagePoints.indices.map { GeorefControl(pagePoints[it], geodetic[it], planes[it]) }
        return Attempt.Ok(
            GeoPdfGeorefResult.Georeferenced(georef, GeorefSelection("viewport", index, vp.name), controls, stats),
            area,
        )
    }

    /** unsupported projection or unreadable GCS: TM on the GPTS centroid, still way better than a lat/lon affine */
    internal fun localTransverseMercator(points: List<GeodeticPoint>): GeoCrs.TransverseMercator =
        GeoCrs.TransverseMercator(
            lat0 = points.sumOf { it.latitude } / points.size,
            lon0 = points.sumOf { it.longitude } / points.size,
            k0 = 1.0,
            fe = 0.0,
            fn = 0.0,
        )

    /**
     * Residuals in metres (lat/lon planes get local radii), gate is plane
     * RMS <= max(2 m, 0.002 * control points' plane bbox diagonal).
     */
    internal fun fitStats(
        affine: PlaneAffine,
        pairs: List<ControlPair>,
        crs: GeoCrs,
        ellipsoid: GeoEllipsoid,
        projector: Projector,
    ): GeorefFitStats? {
        val residuals = pairs.map { pair ->
            val predicted = affine.apply(pair.page.x, pair.page.y)
            val lat = projector.inverse(pair.plane.x, pair.plane.y)?.latitude ?: return null
            val (me, mn) = metresPerPlaneUnit(crs, ellipsoid, lat)
            hypot((predicted.x - pair.plane.x) * me, (predicted.y - pair.plane.y) * mn)
        }
        if (residuals.any { !it.isFinite() }) return null
        val xs = pairs.map { it.plane.x }
        val ys = pairs.map { it.plane.y }
        val centreLat = projector.inverse(xs.average(), ys.average())?.latitude ?: return null
        val (me, mn) = metresPerPlaneUnit(crs, ellipsoid, centreLat)
        val diagonal = hypot((xs.max() - xs.min()) * me, (ys.max() - ys.min()) * mn)
        val rms = sqrt(residuals.sumOf { it * it } / residuals.size)
        if (!rms.isFinite() || !diagonal.isFinite()) return null
        val gate = max(2.0, 0.002 * diagonal)
        return GeorefFitStats(rms, residuals.max(), residuals, diagonal, gate, passesGate = rms <= gate)
    }

    // ---------------------------------------------------------------- LGIDict

    /**
     * Entry described Layers, else the largest neatline (shoelace area). Once an
     * entry is picked it either works or the sheet is rejected, never quietly
     * swapped for an inset's georef. CTM beats Registration only when both are
     * there and agree within 1 m at every Registration point.
     */
    fun fromLgiEntries(entries: List<GeoPdfLgiEntryData>, pageBox: List<Double>?, pageIndex: Int = 0): GeoPdfGeorefResult {
        // declared but empty / the wrong type is a loud failure, not a plain PDF
        if (entries.isEmpty()) return GeoPdfGeorefResult.Rejected(GeorefRejectReason.MALFORMED)
        val layers = entries.indexOfFirst { it.description?.trim() == "Layers" }
        val index = if (layers >= 0) layers else {
            var best = 0
            var bestArea = -1.0
            entries.forEachIndexed { i, e ->
                val a = outlineArea(e)
                if (a > bestArea) { best = i; bestArea = a }
            }
            best
        }
        return lgiEntry(entries[index], index, pageBox, pageIndex)
    }

    // neatline when it parsed with an even count, else the registration page points (iOS lgiOutline)
    private fun outlineArea(e: GeoPdfLgiEntryData): Double {
        val nl = e.neatline
        val pts = if (nl != null && classify(nl) == Numbers.OK && nl.size % 2 == 0) {
            (0 until nl.size / 2).map { PagePoint(nl[2 * it]!!, nl[2 * it + 1]!!) }
        } else {
            val reg = e.registration
            if (reg != null && reg.all { row -> row != null && classify(row) == Numbers.OK }) {
                reg.filter { it!!.size >= 2 }.map { PagePoint(it!![0]!!, it[1]!!) }
            } else emptyList()
        }
        val a = polygonArea(pts)
        return if (a.isFinite()) a else 0.0
    }

    private fun lgiEntry(
        entry: GeoPdfLgiEntryData,
        index: Int,
        pageBox: List<Double>?,
        pageIndex: Int,
    ): GeoPdfGeorefResult {
        fun reject(reason: GeorefRejectReason) = GeoPdfGeorefResult.Rejected(reason)

        // ---- structure: CTM, Neatline, Registration
        when (classify(entry.ctm)) {
            Numbers.MALFORMED -> return reject(GeorefRejectReason.MALFORMED)
            Numbers.NON_FINITE -> return reject(GeorefRejectReason.NON_FINITE)
            else -> Unit
        }
        val ctm = entry.ctm?.map { it!! }
        if (ctm != null && ctm.size != 6) return reject(GeorefRejectReason.MALFORMED)
        when (classify(entry.neatline)) {
            Numbers.MALFORMED -> return reject(GeorefRejectReason.MALFORMED)
            Numbers.NON_FINITE -> return reject(GeorefRejectReason.NON_FINITE)
            else -> Unit
        }
        val neatline = entry.neatline?.map { it!! }
        if (neatline != null && (neatline.size < 6 || neatline.size % 2 != 0 || neatline.size > MAX_CONTROL_VALUES ||
                neatline.any { abs(it) > MAX_SAFE_PDF_COORDINATE })
        ) return reject(GeorefRejectReason.MALFORMED)
        // shape of every row first (4 numbers, row cap), then finiteness, same order as iOS
        val registration: List<ControlPair>? = entry.registration?.let { rows ->
            if (rows.size > GeoPdfParser.MAX_REGISTRATION_ROWS ||
                rows.any { row -> row == null || row.size != 4 || row.any { it == null } }
            ) return reject(GeorefRejectReason.MALFORMED)
            if (rows.any { row -> row!!.any { !it!!.isFinite() } }) return reject(GeorefRejectReason.NON_FINITE)
            // plane values are metres whatever /Units says (GDAL)
            rows.map { row -> ControlPair(PagePoint(row!![0]!!, row[1]!!), PlanePoint(row[2]!!, row[3]!!)) }
        }
        if (ctm == null && registration == null) return reject(GeorefRejectReason.MALFORMED)

        // ---- CRS + datum
        val projection = entry.projection ?: return reject(GeorefRejectReason.MALFORMED)
        val decoded = when (val d = decodeLgiProjection(projection, entry.display)) {
            is LgiDecode.Ok -> d.crs
            is LgiDecode.Fail -> return reject(d.reason)
        }
        val crs = decoded.crs
        val ellipsoid = decoded.datum.ellipsoid
        val projector = crs.projector(ellipsoid)

        // ---- a Registration too short / collinear to fit is fine next to a CTM (CTM alone), fatal on its own
        val ctmAffine = ctm?.let { PlaneAffine.fromPdfMatrix(it) }
        val pairs = registration?.takeUnless {
            it.size < 3 || pageEigenRatio(it.map { pair -> pair.page }) < DEGENERATE_EIGEN_RATIO
        }
        if (registration != null && pairs == null && ctmAffine == null) return reject(GeorefRejectReason.DEGENERATE_VIEWPORT)

        // ---- precedence: CTM when it agrees with every Registration point to 1 m, else Registration
        var source = "ctm"
        var fit: GeorefFitStats? = null
        val affine: PlaneAffine
        if (pairs != null) {
            val ctmAgrees = ctmAffine != null && ctmAffine.isInvertible() && projector != null &&
                pairs.all { pair ->
                    val p = ctmAffine.apply(pair.page.x, pair.page.y)
                    val lat = projector.inverse(pair.plane.x, pair.plane.y)?.latitude ?: return@all false
                    val (me, mn) = metresPerPlaneUnit(crs, ellipsoid, lat)
                    hypot((p.x - pair.plane.x) * me, (p.y - pair.plane.y) * mn) <= CTM_AGREEMENT_METRES
                }
            if (ctmAgrees) {
                affine = ctmAffine!!
            } else {
                val fitted = fitPlaneAffine(pairs) ?: return reject(GeorefRejectReason.DEGENERATE_VIEWPORT)
                val stats = projector?.let { fitStats(fitted, pairs, crs, ellipsoid, it) }
                    ?: return reject(GeorefRejectReason.MALFORMED)
                if (!stats.passesGate) return GeoPdfGeorefResult.Rejected(GeorefRejectReason.RMS_GATE, stats)
                affine = fitted
                fit = stats
                source = "registration"
            }
        } else {
            affine = ctmAffine!!
        }
        if (!affine.isInvertible()) return reject(GeorefRejectReason.DEGENERATE_VIEWPORT)

        // ---- crop: neatline in order, else the registration page points (need 3), else the page box
        val crop = when {
            neatline != null -> (0 until neatline.size / 2).map { PagePoint(neatline[2 * it], neatline[2 * it + 1]) }
            registration != null -> {
                if (registration.size < 3) return reject(GeorefRejectReason.MALFORMED)
                registration.map { it.page }
            }
            pageBox != null && pageBox.size == 4 -> listOf(
                PagePoint(pageBox[0], pageBox[1]), PagePoint(pageBox[2], pageBox[1]),
                PagePoint(pageBox[2], pageBox[3]), PagePoint(pageBox[0], pageBox[3]),
            )
            else -> emptyList()
        }
        val cropArea = polygonArea(crop)
        if (crop.size < 3 || !(cropArea.isFinite() && cropArea > 0.0)) return reject(GeorefRejectReason.DEGENERATE_VIEWPORT)
        val georef = PdfGeoreference(
            page = pageIndex,
            crs = crs,
            datum = decoded.datum,
            affine = affine,
            crop = crop,
            origin = GeorefOrigin.LGI_DICT,
            fit = fit?.toFit(),
            datumAssumed = decoded.datumAssumed,
        )
        // a CTM that throws the neatline off the planet is junk, not a sheet
        if (!georef.isStructurallyValid() || georef.wgs84Bounds() == null) return reject(GeorefRejectReason.GPTS_OFF_EARTH)
        return GeoPdfGeorefResult.Georeferenced(
            georef, GeorefSelection("lgiEntry", index, entry.description, source), fitStats = fit,
        )
    }

    private data class LgiCrs(val crs: GeoCrs, val datum: GeoDatum, val datumAssumed: Boolean = false)

    private sealed class LgiDecode {
        data class Ok(val crs: LgiCrs) : LgiDecode()
        data class Fail(val reason: GeorefRejectReason) : LgiDecode()
    }

    private val GEOGRAPHIC_TYPES = setOf("LL", "LONGLAT", "GEOGRAPHIC", "GEO")
    private val PROJECTED_TYPES = setOf("UT", "UTM", "TC", "LE", "LC")
    private val METRE_UNITS = setOf("M", "METER", "METERS", "METRE", "METRES")

    /**
     * LGIDict /Projection -> crs + datum. Codes per OGC 08-139r3 Annex A plus what
     * real files use. Order matches iOS lgiCrs: type, datum, units, then params.
     */
    private fun decodeLgiProjection(
        projection: GeoPdfLgiProjectionData,
        display: GeoPdfLgiProjectionData?,
    ): LgiDecode {
        fun fail(reason: GeorefRejectReason) = LgiDecode.Fail(reason)
        val type = projection.projectionType?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() }
            ?: return fail(GeorefRejectReason.MALFORMED)
        val geographic = type in GEOGRAPHIC_TYPES
        if (!geographic && type !in PROJECTED_TYPES) return fail(GeorefRejectReason.UNSUPPORTED_PROJECTION)

        // datum: a code, or an inline dict with Ellipsoid (+ ToWGS84)
        var datumAssumed = false
        val datum: GeoDatum = when (val d = projection.datum) {
            // a projected CTM with no datum could be anything, lat/lon we call WGS84
            null -> if (geographic) { datumAssumed = true; GeoDatums.WGS84 } else return fail(GeorefRejectReason.UNKNOWN_DATUM)
            is LgiDatumData.Code -> GeoDatums.forLgiCode(d.code) ?: return fail(GeorefRejectReason.UNKNOWN_DATUM)
            is LgiDatumData.Inline -> {
                val a = d.semiMajorAxis
                val invF = d.inverseFlattening
                if (a == null || invF == null || !(invF > 0.0) || !GeoEllipsoid(a, invF).isValid()) {
                    return fail(GeorefRejectReason.MALFORMED)
                }
                val shift = d.toWgs84
                if (shift != null) {
                    if (shift.size != 3 || shift.any { it == null }) return fail(GeorefRejectReason.MALFORMED)
                    GeoDatum.custom(a, invF, shift[0]!!, shift[1]!!, shift[2]!!) ?: return fail(GeorefRejectReason.MALFORMED)
                } else if (GcsParser.isModernEllipsoid(a, invF)) {
                    // no shift on a WGS84/GRS80 sized ellipsoid: zero shift, but say it's a guess
                    datumAssumed = true
                    GeoDatum.custom(a, invF) ?: return fail(GeorefRejectReason.MALFORMED)
                } else {
                    return fail(GeorefRejectReason.UNKNOWN_DATUM)
                }
            }
            LgiDatumData.Invalid -> return fail(GeorefRejectReason.MALFORMED)
        }

        // Units (GDAL ParseProjDict): only FalseEasting/FalseNorthing are in it, the
        // CTM and Registration are metres. Lat/lon ignores it, a foot UTM can't mean anything sane
        var unit = 1.0
        if (!geographic && projection.units != null) {
            unit = when (projection.units.trim().uppercase(Locale.US)) {
                in METRE_UNITS -> 1.0
                "FT" -> FOOT
                "USSF" -> US_SURVEY_FOOT
                else -> return fail(GeorefRejectReason.UNSUPPORTED_PROJECTION)
            }
            if (unit != 1.0 && (type == "UT" || type == "UTM")) return fail(GeorefRejectReason.UNSUPPORTED_PROJECTION)
        }

        fun num(key: String): Double? = projection.parameters[key]?.takeIf { it.isFinite() }
        val crs: GeoCrs = when {
            geographic -> GeoCrs.Geographic
            type == "UT" || type == "UTM" -> {
                val zone = (projection.zone ?: display?.zone)?.takeIf { it.isFinite() && it == Math.rint(it) && it in 1.0..60.0 }
                    ?: return fail(GeorefRejectReason.MALFORMED)
                val south = when ((projection.hemisphere ?: display?.hemisphere)?.trim()?.uppercase(Locale.US)) {
                    "N", "NORTH" -> false
                    "S", "SOUTH" -> true
                    else -> return fail(GeorefRejectReason.MALFORMED)
                }
                GeoCrs.utm(zone.toInt(), south)
            }
            type == "TC" -> {
                val cm = num("CentralMeridian")?.takeIf { abs(it) <= 360.0 }
                val lat0 = num("OriginLatitude")?.takeIf { abs(it) <= 90.0 }
                val fe = num("FalseEasting")
                val fn = num("FalseNorthing")
                val k0 = num("ScaleFactor")?.takeIf { it > 0.0 && it <= 10.0 }
                if (cm == null || lat0 == null || fe == null || fn == null || k0 == null) return fail(GeorefRejectReason.MALFORMED)
                GeoCrs.TransverseMercator(lat0, cm, k0, fe * unit, fn * unit)
            }
            // LE is what real files say, LC what the old code wanted
            type == "LE" || type == "LC" -> {
                val p1 = num("StandardParallelOne")?.takeIf { abs(it) < 89.999 }
                val lat0 = num("OriginLatitude")?.takeIf { abs(it) < 89.999 }
                val cm = num("CentralMeridian")?.takeIf { abs(it) <= 360.0 }
                val fe = num("FalseEasting")
                val fn = num("FalseNorthing")
                if (p1 == null || lat0 == null || cm == null || fe == null || fn == null) return fail(GeorefRejectReason.MALFORMED)
                val p2 = num("StandardParallelTwo") ?: p1
                if (!(abs(p2) < 89.999)) return fail(GeorefRejectReason.MALFORMED)
                GeoCrs.LambertConformalConic2SP(p1, p2, lat0, cm, fe * unit, fn * unit)
            }
            else -> return fail(GeorefRejectReason.UNSUPPORTED_PROJECTION)
        }
        return LgiDecode.Ok(LgiCrs(crs, datum, datumAssumed))
    }
}
