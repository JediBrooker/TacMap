package com.tacmap.calibration

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Abstract basemap source. Online basemaps + imported maps:
 *  - [OnlineRasterMapSourceAndroid] - the online raster basemaps (Esri
 *    Satellite/Topo, OSM Topo/Street), one per [BasemapStyle].
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
 * Online raster basemap styles. Mirrors iOS BasemapStyle. Four of them, and
 * three ride the licensed ArcGIS key so we're not hot-linking community servers
 * from a paid app:
 *
 *  - ESRI_SATELLITE: keyed ibasemaps World Imagery (256px), the default.
 *  - ESRI_TOPO: keyed Esri static "outdoor" topo tiles (512px).
 *  - OSM_STREET: keyed Esri static "open/osm-style" - genuine OSM cartography
 *    but served licensed, because tile.openstreetmap.org blocks app clients.
 *  - OSM_TOPO: OpenTopoMap's own community server (256px). The one exception:
 *    there is no licensed OpenTopoMap, so this hits their volunteer tiles. No
 *    uptime guarantee, discouraged for heavy use - fine as an opt-in option,
 *    would not be OK as the default. Attribution required (CC-BY-SA).
 *
 * [requiresEsriKey] styles get `?token=` appended by RasterTileProvider and are
 * unavailable when no key is configured. [tileSize] differs per source (Esri
 * static tiles are 512px), so the provider is built per-style.
 */
enum class BasemapStyle(
    val displayName: String,
    val urlTemplate: String,
    val tileSize: Int,
    val maxZoom: Int,
    val requiresEsriKey: Boolean,
    val attribution: String
) {
    ESRI_SATELLITE(
        "Satellite (Esri)",
        "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        256, 19, true,
        "Esri, Maxar, Earthstar Geographics, and the GIS User Community"
    ),
    ESRI_TOPO(
        "Topographic (Esri)",
        "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/arcgis/outdoor/static/tile/{z}/{y}/{x}",
        512, 20, true,
        "Esri, TomTom, Garmin, FAO, NOAA, USGS, OpenStreetMap contributors"
    ),
    OSM_TOPO(
        "Topographic (OpenTopoMap)",
        "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",
        256, 17, false,
        "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors"
    ),
    OSM_STREET(
        "Street (OpenStreetMap)",
        "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/open/osm-style/static/tile/{z}/{y}/{x}",
        512, 20, true,
        "© OpenStreetMap contributors, served by Esri"
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

/** Online raster basemap (Esri Satellite/Topo or OSM Topo/Street), rendered via
 *  XYZ tile overlay. Keyed styles carry the ArcGIS token, see RasterTileProvider. */
class OnlineRasterMapSourceAndroid(val style: BasemapStyle) : MapSource {
    override val id: String = UUID.randomUUID().toString()
    override val displayName = style.displayName
    override val kind = MapSourceKind.ONLINE_RASTER
    override val coverage: Wgs84Bounds? = null
    override val calibration: Calibration? = null
}
