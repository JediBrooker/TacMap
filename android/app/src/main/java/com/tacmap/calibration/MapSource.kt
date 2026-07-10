package com.tacmap.calibration

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Abstract basemap source. Online basemaps + imported maps:
 *  - [SatelliteMapSourceAndroid] - Google satellite imagery (default).
 *  - [OnlineRasterMapSourceAndroid] - Esri imagery / OpenTopoMap tiles.
 *  - [PdfMapSource] in geoPDF mode - calibration parsed from GeoPDF tags.
 *  - [PdfMapSource] in calibratedPdf mode - user-fitted via 3+ fiduciaries.
 *  - [OfflineTileMapSourceAndroid] - sideloaded MBTiles raster.
 *
 * Overlays stored in WGS84, travel between sources unchanged.
 */
sealed interface MapSource {
    val id: String
    val displayName: String
    val kind: MapSourceKind
    val coverage: Wgs84Bounds?
    val calibration: Calibration?
}

enum class MapSourceKind { SATELLITE, ONLINE_RASTER, GEO_PDF, CALIBRATED_PDF, OFFLINE_TILES }

/**
 * Online raster basemap styles. Mirrors iOS BasemapStyle.
 *
 * Esri now points at the keyed ibasemaps World Imagery endpoint - the same
 * raster source Esri's own current Basemap Styles service composes - instead of
 * the old unauthenticated server.arcgisonline.com URL, which their terms only
 * allow for noncommercial use. [requiresEsriKey] styles get `?token=` appended
 * by RasterTileProvider and are unavailable when no key is configured.
 */
enum class BasemapStyle(
    val displayName: String,
    val urlTemplate: String,
    val maxZoom: Int,
    val requiresEsriKey: Boolean = false
) {
    ESRI_SATELLITE(
        "Satellite (Esri)",
        "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        19,
        requiresEsriKey = true
    ),
    TERRAIN(
        "Terrain (OpenTopoMap)",
        "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        17
    )
}

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
     * True when (lat, lng) is inside these bounds. Handles antimeridian
     * crossing (southwest.longitude > northeast.longitude) where a
     * naive `lng in sw..ne` range would be empty and wrongly report
     * every point as outside.
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

/** Calibration state for a PDF source */
sealed interface Calibration {
    data class Parsed(val crs: String, val transform: AffineTransform2D) : Calibration
    data class Fiduciaries(val fids: List<Fiduciary>, val transform: AffineTransform2D) : Calibration
}

/** Default basemap: Google satellite (MapType.SATELLITE). */
class SatelliteMapSourceAndroid : MapSource {
    override val id: String = UUID.randomUUID().toString()
    override val displayName = "Satellite"
    override val kind = MapSourceKind.SATELLITE
    override val coverage: Wgs84Bounds? = null
    override val calibration: Calibration? = null
}

/** Online raster basemap (Esri World Imagery or OpenTopoMap terrain),
 *  rendered via XYZ tile overlay. No API key needed. */
class OnlineRasterMapSourceAndroid(val style: BasemapStyle) : MapSource {
    override val id: String = UUID.randomUUID().toString()
    override val displayName = style.displayName
    override val kind = MapSourceKind.ONLINE_RASTER
    override val coverage: Wgs84Bounds? = null
    override val calibration: Calibration? = null
}
