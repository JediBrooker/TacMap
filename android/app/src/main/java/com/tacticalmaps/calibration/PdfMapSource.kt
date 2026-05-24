package com.tacticalmaps.calibration

import android.net.Uri
import com.google.android.gms.maps.model.LatLngBounds
import java.util.UUID

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
    override val coverage: LatLngBounds?,
    override val calibration: Calibration?
) : MapSource {
    override val id: String = UUID.randomUUID().toString()

    companion object {
        /** Placeholder factory used by Import flow. */
        fun placeholder(uri: Uri, name: String): PdfMapSource =
            PdfMapSource(uri, name, MapSourceKind.CALIBRATED_PDF, null, null)
    }
}
