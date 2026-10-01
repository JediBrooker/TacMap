package com.tacmap.calibration

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// sealed session JSON for the georef (schema v2). Flat and boring on purpose,
// table datums go by id so a table fix reaches old sessions too.
// ---------------------------------------------------------------------------

@Serializable
internal data class PersistedCrs(
    val kind: String,
    val lat0: Double? = null,
    val lon0: Double? = null,
    val k0: Double? = null,
    val fe: Double? = null,
    val fn: Double? = null,
    val lat1: Double? = null,
    val lat2: Double? = null,
)

@Serializable
internal data class PersistedDatum(
    val id: String,
    val a: Double? = null,
    val invF: Double? = null,
    val dx: Double? = null,
    val dy: Double? = null,
    val dz: Double? = null,
)

@Serializable
internal data class PersistedFit(
    val rmsMetres: Double,
    val maxResidualMetres: Double,
    val perPointMetres: List<Double>,
    val crossValidated: Boolean,
)

@Serializable
internal data class PersistedGeoreference(
    val page: Int = 0,
    val crs: PersistedCrs,
    val datum: PersistedDatum,
    val affine: List<Double>,
    val crop: List<List<Double>>,
    val origin: String,
    val fit: PersistedFit? = null,
    val datumAssumed: Boolean = false,
)

internal object PdfGeoreferenceCodec {
    fun encode(g: PdfGeoreference): PersistedGeoreference = PersistedGeoreference(
        page = g.page,
        crs = when (val c = g.crs) {
            GeoCrs.Geographic -> PersistedCrs("geographic")
            is GeoCrs.TransverseMercator -> PersistedCrs("transverseMercator", lat0 = c.lat0, lon0 = c.lon0, k0 = c.k0, fe = c.fe, fn = c.fn)
            is GeoCrs.LambertConformalConic2SP ->
                PersistedCrs("lambertConformalConic2SP", lat0 = c.lat0, lon0 = c.lon0, fe = c.fe, fn = c.fn, lat1 = c.lat1, lat2 = c.lat2)
            is GeoCrs.LambertConformalConic1SP ->
                PersistedCrs("lambertConformalConic1SP", lat0 = c.lat0, lon0 = c.lon0, k0 = c.k0, fe = c.fe, fn = c.fn)
            is GeoCrs.Mercator1SP -> PersistedCrs("mercator1SP", lon0 = c.lon0, k0 = c.k0, fe = c.fe, fn = c.fn)
        },
        datum = if (g.datum.isCustom) {
            PersistedDatum(GeoDatum.CUSTOM_ID, g.datum.ellipsoid.a, g.datum.ellipsoid.invF, g.datum.dx, g.datum.dy, g.datum.dz)
        } else {
            PersistedDatum(g.datum.id)
        },
        affine = g.affine.coefficients,
        crop = g.crop.map { listOf(it.x, it.y) },
        origin = g.origin.code,
        fit = g.fit?.let { PersistedFit(it.rmsMetres, it.maxResidualMetres, it.perPointMetres, it.crossValidated) },
        datumAssumed = g.datumAssumed,
    )

    /** null for anything that doesn't decode into a usable georef, callers fail closed */
    fun decode(p: PersistedGeoreference): PdfGeoreference? {
        val c = p.crs
        val crs: GeoCrs = when (c.kind) {
            "geographic" -> GeoCrs.Geographic
            "transverseMercator" -> GeoCrs.TransverseMercator(c.lat0 ?: return null, c.lon0 ?: return null, c.k0 ?: return null, c.fe ?: return null, c.fn ?: return null)
            "lambertConformalConic2SP" -> GeoCrs.LambertConformalConic2SP(
                c.lat1 ?: return null, c.lat2 ?: return null, c.lat0 ?: return null, c.lon0 ?: return null, c.fe ?: return null, c.fn ?: return null,
            )
            "lambertConformalConic1SP" -> GeoCrs.LambertConformalConic1SP(c.lat0 ?: return null, c.lon0 ?: return null, c.k0 ?: return null, c.fe ?: return null, c.fn ?: return null)
            "mercator1SP" -> GeoCrs.Mercator1SP(c.lon0 ?: return null, c.k0 ?: return null, c.fe ?: return null, c.fn ?: return null)
            else -> return null
        }
        val d = p.datum
        val datum = if (d.id == GeoDatum.CUSTOM_ID) {
            GeoDatum.custom(d.a ?: return null, d.invF ?: return null, d.dx ?: 0.0, d.dy ?: 0.0, d.dz ?: 0.0)
        } else {
            GeoDatums.byId(d.id)
        } ?: return null
        val affine = PlaneAffine.of(p.affine) ?: return null
        if (p.crop.any { it.size != 2 }) return null
        val origin = GeorefOrigin.fromCode(p.origin) ?: return null
        return PdfGeoreference(
            page = p.page,
            crs = crs,
            datum = datum,
            affine = affine,
            crop = p.crop.map { PagePoint(it[0], it[1]) },
            origin = origin,
            fit = p.fit?.let { GeorefFit(it.rmsMetres, it.maxResidualMetres, it.perPointMetres, it.crossValidated) },
            datumAssumed = p.datumAssumed,
        ).takeIf { it.isUsable() }
    }

    fun encodeIssue(issue: PdfGeorefIssue?): String? = when (issue) {
        null -> null
        PdfGeorefIssue.NoMetadata -> "noMetadata"
        PdfGeorefIssue.LegacyPlacement -> "legacyPlacement"
        PdfGeorefIssue.CalibrationLost -> "calibrationLost"
        is PdfGeorefIssue.Rejected -> "rejected:" + issue.reason.code
    }

    fun decodeIssue(code: String?): PdfGeorefIssue? = when {
        code == null -> null
        code == "noMetadata" -> PdfGeorefIssue.NoMetadata
        code == "legacyPlacement" -> PdfGeorefIssue.LegacyPlacement
        code == "calibrationLost" -> PdfGeorefIssue.CalibrationLost
        code.startsWith("rejected:") -> GeorefRejectReason.entries
            .firstOrNull { it.code == code.removePrefix("rejected:") }
            ?.let { PdfGeorefIssue.Rejected(it) } ?: PdfGeorefIssue.NoMetadata
        else -> PdfGeorefIssue.NoMetadata
    }
}

/**
 * v1 -> v2 rules from plan 02 s1 "Persistence and migration", pure so the three
 * cases are JVM tested. A GeoPDF source (parsed, or fiduciaries with no typed MGRS,
 * which were the auto correspondences) gets re-parsed, the old lat/lon affine is
 * never trusted. User fiduciaries carry WGS84 lat/lon, so they're refitted in the
 * UTM zone of the first point, after their v1 page points (PdfRenderer relative,
 * y up) are moved to raw user space. No calibration at all was the made up
 * camera box: that one comes back uncalibrated and asks.
 */
internal object PdfSessionMigration {
    sealed class Outcome {
        data class Georeferenced(val calibration: Calibration) : Outcome()
        data class Uncalibrated(val issue: PdfGeorefIssue, val pendingFiduciaries: List<Fiduciary> = emptyList()) : Outcome()
    }

    const val KIND_NONE = "none"
    const val KIND_PARSED = "parsed"
    const val KIND_FIDUCIARIES = "fiduciaries"

    fun migrate(
        calibrationKind: String,
        fiduciaries: List<Fiduciary>,
        v1RendererWidth: Int,
        v1RendererHeight: Int,
        geometry: PdfPageGeometry,
        reparse: () -> GeoPdfGeorefResult?,
    ): Outcome {
        val userTyped = fiduciaries.any { it.mgrs.isNotBlank() }
        val wasGeoPdf = calibrationKind == KIND_PARSED ||
            (calibrationKind == KIND_FIDUCIARIES && fiduciaries.isNotEmpty() && !userTyped)
        if (wasGeoPdf) return reparsed(reparse())
        if (calibrationKind != KIND_FIDUCIARIES || fiduciaries.size < 3) {
            return Outcome.Uncalibrated(PdfGeorefIssue.LegacyPlacement)
        }
        val raw = fiduciaries.map { fid ->
            val p = v1PointToRaw(fid.pdfX, fid.pdfY, v1RendererWidth, v1RendererHeight, geometry)
                ?: return Outcome.Uncalibrated(PdfGeorefIssue.CalibrationLost)
            fid.copy(pdfX = p.x, pdfY = p.y)
        }
        return refit(raw, geometry)
    }

    /**
     * Schema 2 whose georef no longer decodes (codec change, a field gone bad).
     * Same idea as v1, minus the page space undo since v2 points are already raw:
     * a GeoPDF gets re-read from its bytes, user fiduciaries get refitted. A refit
     * that's refused keeps the points pending for the next calibration rather than
     * making the user find them again.
     */
    fun recover(
        calibrationKind: String,
        rawFiduciaries: List<Fiduciary>,
        geometry: PdfPageGeometry,
        reparse: () -> GeoPdfGeorefResult?,
    ): Outcome = when {
        calibrationKind == KIND_PARSED -> reparsed(reparse())
        calibrationKind == KIND_FIDUCIARIES && rawFiduciaries.size >= 3 -> refit(rawFiduciaries, geometry)
        else -> Outcome.Uncalibrated(PdfGeorefIssue.CalibrationLost, rawFiduciaries)
    }

    private fun reparsed(result: GeoPdfGeorefResult?): Outcome = when (result) {
        is GeoPdfGeorefResult.Georeferenced -> Outcome.Georeferenced(Calibration.Parsed(result.georef))
        is GeoPdfGeorefResult.Rejected -> Outcome.Uncalibrated(PdfGeorefIssue.Rejected(result.reason))
        else -> Outcome.Uncalibrated(PdfGeorefIssue.NoMetadata)
    }

    // raw page space + WGS84 lat/lon -> UTM refit over the visible crop. refused
    // (collinear etc) keeps the points pending, same rule on iOS
    private fun refit(raw: List<Fiduciary>, geometry: PdfPageGeometry): Outcome {
        val georef = FiduciaryFitter.refitStored(raw, geometry.visibleCrop())?.georeference(geometry.visibleCrop())
            ?: return Outcome.Uncalibrated(PdfGeorefIssue.CalibrationLost, raw)
        return Outcome.Georeferenced(Calibration.Fiduciaries(raw, georef))
    }

    /**
     * v1 calibration taps were (xRatio * W, yRatio * H) over PdfRenderer's int page
     * size with y up, i.e. renderer space flipped. v1 refused rotated pages, so this
     * is exact for everything v1 could have saved.
     */
    fun v1PointToRaw(x: Double, y: Double, v1Width: Int, v1Height: Int, geometry: PdfPageGeometry): PagePoint? {
        if (!x.isFinite() || !y.isFinite() || v1Width <= 0 || v1Height <= 0) return null
        val u = x * geometry.rendererWidth / v1Width
        val v = geometry.rendererHeight - y * geometry.rendererHeight / v1Height
        return geometry.rendererToRaw(u, v).takeIf { it.isFinite() }
    }
}
