package com.tacticalmaps.calibration

import android.net.Uri
import java.util.UUID
import kotlin.math.cos

/**
 * PDF-backed map source. GeoPDF tags are read via PDFBox/iText (future work); for
 * non-GeoPDF, the user drops 3+ fiduciaries and we fit an affine.
 *
 * TODO:
 *  - Parse OGC GeoPDF / Adobe "LGIDict" dictionaries.
 *  - For large rasters, sidecar tile pyramid via GDAL.
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
        val corners = listOf(
            transform.apply(0.0, 0.0),
            transform.apply(info.pageWidth.toDouble(), 0.0),
            transform.apply(info.pageWidth.toDouble(), info.pageHeight.toDouble()),
            transform.apply(0.0, info.pageHeight.toDouble())
        )
        val lats = corners.map { it.latitude }
        val lons = corners.map { it.longitude }
        val bounds = Wgs84Bounds(
            southwest = Wgs84Coordinate(lats.min(), lons.min()),
            northeast = Wgs84Coordinate(lats.max(), lons.max())
        )
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
