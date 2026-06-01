package com.tacticalmaps.calibration

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Abstract basemap source. Three flavours today:
 *  - [OpenStreetMapSourceAndroid] — default online OSM tiles when no PDF is loaded.
 *  - [PdfMapSource] in `.geoPDF` mode — calibration parsed from GeoPDF tags.
 *  - [PdfMapSource] in `.calibratedPdf` mode — user-fitted via 3+ fiduciaries.
 *
 * Overlays are stored in WGS84 and travel between sources unchanged.
 */
sealed interface MapSource {
    val id: String
    val displayName: String
    val kind: MapSourceKind
    val coverage: Wgs84Bounds?
    val calibration: Calibration?
}

enum class MapSourceKind { OPEN_STREET_MAP, GEO_PDF, CALIBRATED_PDF, OFFLINE_TILES }

@Serializable
data class Wgs84Coordinate(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class Wgs84Bounds(
    val southwest: Wgs84Coordinate,
    val northeast: Wgs84Coordinate
) {
    val center: Wgs84Coordinate
        get() = Wgs84Coordinate(
            latitude = (southwest.latitude + northeast.latitude) / 2.0,
            longitude = (southwest.longitude + northeast.longitude) / 2.0
        )

    val latitudeSpan: Double get() = northeast.latitude - southwest.latitude
    val longitudeSpan: Double get() = northeast.longitude - southwest.longitude

    /**
     * True when (lat, lng) falls inside these bounds. Handles bounds that
     * cross the antimeridian (southwest.longitude > northeast.longitude),
     * where a plain `lng in sw..ne` range would be empty and wrongly
     * report every point as outside.
     */
    fun contains(lat: Double, lng: Double): Boolean {
        if (lat < southwest.latitude || lat > northeast.latitude) return false
        return if (southwest.longitude <= northeast.longitude) {
            lng in southwest.longitude..northeast.longitude
        } else {
            lng >= southwest.longitude || lng <= northeast.longitude
        }
    }
}

/** Calibration state for a PDF source. */
sealed interface Calibration {
    data class Parsed(val crs: String, val transform: AffineTransform2D) : Calibration
    data class Fiduciaries(val fids: List<Fiduciary>, val transform: AffineTransform2D) : Calibration
}

class OpenStreetMapSourceAndroid : MapSource {
    override val id: String = UUID.randomUUID().toString()
    override val displayName = "OpenStreetMap"
    override val kind = MapSourceKind.OPEN_STREET_MAP
    override val coverage: Wgs84Bounds? = null
    override val calibration: Calibration? = null
}
