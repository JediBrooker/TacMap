package com.tacmap.calibration

import android.net.Uri
import java.util.UUID
import kotlin.math.cos

/**
 * PDF-backed map source. [GeoPdfParser] resolves supported geospatial metadata;
 * otherwise the user supplies 3+ fiduciaries and the importer fits an affine.
 * Large pages are rendered through the app's bounded on-device tile pipeline.
 */
class PdfMapSource(
    val uri: Uri,
    override val displayName: String,
    override val kind: MapSourceKind,
    override val coverage: Wgs84Bounds?,
    override val calibration: Calibration?,
    val pageInfo: PdfPageInfo? = null
) : MapSource {
    override val id: String = UUID.randomUUID().toString()

    fun calibrated(transform: AffineTransform2D, fiduciaries: List<Fiduciary>): PdfMapSource {
        val info = pageInfo ?: return this
        if (fiduciaries.size < 3 || fiduciaries.any { !it.isSafeAffineInput() } ||
            !transform.hasFiniteCoefficients() || transform.inverted() == null
        ) return this
        val bounds = calibratedPdfBounds(transform, info) ?: return this
        return PdfMapSource(
            uri = uri,
            displayName = displayName,
            kind = MapSourceKind.CALIBRATED_PDF,
            coverage = bounds,
            calibration = Calibration.Fiduciaries(fiduciaries, transform),
            pageInfo = info
        )
    }

    companion object {
        /** Placeholder factory used by Import flow. */
        fun placeholder(uri: Uri, name: String): PdfMapSource =
            PdfMapSource(uri, name, MapSourceKind.CALIBRATED_PDF, null, null)

        fun imported(
            uri: Uri,
            name: String,
            center: Wgs84Coordinate,
            pageInfo: PdfPageInfo
        ): PdfMapSource =
            PdfMapSource(
                uri = uri,
                displayName = name,
                kind = MapSourceKind.CALIBRATED_PDF,
                coverage = fallbackBounds(center, pageInfo.aspectRatio),
                calibration = null,
                pageInfo = pageInfo
            )

        private fun fallbackBounds(
            center: Wgs84Coordinate,
            aspectRatio: Double
        ): Wgs84Bounds {
            val halfHeightKm = 5.0
            val halfWidthKm = halfHeightKm * aspectRatio.coerceIn(0.25, 4.0)
            val latDelta = halfHeightKm / 111.32
            val lonScale = (111.32 * cos(Math.toRadians(center.latitude))).coerceAtLeast(0.01)
            val lonDelta = halfWidthKm / lonScale
            return Wgs84Bounds(
                southwest = Wgs84Coordinate(center.latitude - latDelta, center.longitude - lonDelta),
                northeast = Wgs84Coordinate(center.latitude + latDelta, center.longitude + lonDelta)
            )
        }
    }
}

/** Final fail-closed boundary before an affine becomes live map state. */
internal fun calibratedPdfBounds(
    transform: AffineTransform2D,
    pageInfo: PdfPageInfo,
): Wgs84Bounds? {
    val width = pageInfo.pageWidth.toDouble()
    val height = pageInfo.pageHeight.toDouble()
    if (!width.isFinite() || !height.isFinite() ||
        width <= 0.0 || height <= 0.0 ||
        width > MAX_SAFE_PDF_COORDINATE || height > MAX_SAFE_PDF_COORDINATE ||
        !transform.hasFiniteCoefficients() || transform.inverted() == null
    ) return null
    val corners = listOf(
        transform.apply(0.0, 0.0),
        transform.apply(width, 0.0),
        transform.apply(width, height),
        transform.apply(0.0, height),
    )
    if (corners.any { !it.isValidEarthCoordinate() }) return null
    val lats = corners.map { it.latitude }
    val lons = corners.map { it.longitude }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLon = lons.min()
    val maxLon = lons.max()
    if (minLat >= maxLat || minLon >= maxLon) return null
    return Wgs84Bounds(
        southwest = Wgs84Coordinate(minLat, minLon),
        northeast = Wgs84Coordinate(maxLat, maxLon),
    )
}
