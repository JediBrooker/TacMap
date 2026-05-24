package com.tacticalmaps.calibration

import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import java.util.UUID

/**
 * Abstract basemap source. Three flavours today:
 *  - [AppleSatelliteMapSourceAndroid] — Google satellite, used when no PDF is loaded
 *    (we keep the iOS naming so the cross-platform doc reads consistently).
 *  - [PdfMapSource] in `.geoPDF` mode — calibration parsed from GeoPDF tags.
 *  - [PdfMapSource] in `.calibratedPdf` mode — user-fitted via 3+ fiduciaries.
 *
 * Overlays are stored in WGS84 and travel between sources unchanged.
 */
sealed interface MapSource {
    val id: String
    val displayName: String
    val kind: MapSourceKind
    val coverage: LatLngBounds?
    val calibration: Calibration?
}

enum class MapSourceKind { APPLE_SATELLITE, GEO_PDF, CALIBRATED_PDF }

/** Calibration state for a PDF source. */
sealed interface Calibration {
    data class Parsed(val crs: String, val transform: AffineTransform2D) : Calibration
    data class Fiduciaries(val fids: List<Fiduciary>, val transform: AffineTransform2D) : Calibration
}

class AppleSatelliteMapSourceAndroid : MapSource {
    override val id: String = UUID.randomUUID().toString()
    override val displayName = "Google Satellite"
    override val kind = MapSourceKind.APPLE_SATELLITE
    override val coverage: LatLngBounds? = null
    override val calibration: Calibration? = null
}
