import Foundation
import MapKit

/// Online raster basemap styles. Mirrors Android BasemapStyle. Four of them,
/// and three ride the licensed ArcGIS key so we're not hot-linking community
/// servers from a paid app:
///
///  - esriSatellite: keyed ibasemaps World Imagery (256px), the default.
///  - esriTopo: keyed Esri static "outdoor" topo tiles (512px).
///  - osmStreet: keyed Esri static "open/osm-style" - genuine OSM cartography
///    but served licensed, because tile.openstreetmap.org blocks app clients.
///  - osmTopo: OpenTopoMap's own community server (256px). The one exception:
///    no licensed OpenTopoMap exists, so this hits their volunteer tiles. No
///    uptime guarantee, discouraged for heavy use - fine opt-in, not as default.
///
/// `requiresEsriKey` styles get `?token=` appended by makeOverlay and are
/// unavailable with no key. `tileSize` differs per source (Esri static = 512px).
enum BasemapStyle: String, CaseIterable {
    case esriSatellite
    case esriTopo
    case osmTopo
    case osmStreet

    var displayName: String {
        switch self {
        case .esriSatellite: return "Satellite (Esri)"
        case .esriTopo:      return "Topographic (Esri)"
        case .osmTopo:       return "Topographic (OpenTopoMap)"
        case .osmStreet:     return "Street (OpenStreetMap)"
        }
    }

    var requiresEsriKey: Bool { self != .osmTopo }

    /// MKTileOverlay URL template. {z}/{x}/{y} substituted by name so the Esri
    /// {z}/{y}/{x} order is fine. The Esri token is appended by makeOverlay.
    var urlTemplate: String {
        switch self {
        case .esriSatellite:
            return "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        case .esriTopo:
            return "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/arcgis/outdoor/static/tile/{z}/{y}/{x}"
        case .osmTopo:
            // {s} is deterministically replaced with a/b/c by the custom tile
            // loader, spreading normal interactive traffic across OTM's
            // documented raster hosts without duplicate requests.
            return "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png"
        case .osmStreet:
            return "https://static-map-tiles-api.arcgis.com/arcgis/rest/services/static-basemap-tiles-service/v1/open/osm-style/static/tile/{z}/{y}/{x}"
        }
    }

    /// Esri static tiles are 512px; ibasemaps + OpenTopoMap are 256px.
    var tileSize: Int {
        switch self {
        case .esriTopo, .osmStreet: return 512
        case .esriSatellite, .osmTopo: return 256
        }
    }

    var maximumZ: Int {
        switch self {
        case .esriSatellite: return 19
        case .esriTopo, .osmStreet: return 20
        case .osmTopo: return 17
        }
    }

    var attribution: String {
        switch self {
        case .esriSatellite: return "Esri, Maxar, Earthstar Geographics, and the GIS User Community"
        case .esriTopo:      return "Esri, TomTom, Garmin, FAO, NOAA, USGS, OpenStreetMap contributors"
        case .osmTopo:       return "© OpenTopoMap (CC-BY-SA), © OpenStreetMap contributors"
        case .osmStreet:     return "© OpenStreetMap contributors, served by Esri"
        }
    }
}

/// Basemap backed by an online raster XYZ tile service (Esri Satellite/Topo or
/// OSM Topo/Street). Served via MKTileOverlay with canReplaceMapContent so it
/// fully covers the base. Keyed styles carry the ArcGIS token.
final class OnlineRasterBasemapSource: MapSource {
    let id = UUID()
    let style: BasemapStyle
    var displayName: String { style.displayName }
    let kind: MapSourceKind = .onlineRaster
    let coverage: MKCoordinateRegion? = nil
    let calibration: Calibration? = nil

    init(_ style: BasemapStyle) { self.style = style }

    /// The default basemap: Esri Satellite when we have a key, else the one
    /// style that needs none (OpenTopoMap) so a keyless dev build shows a map.
    static var defaultStyle: BasemapStyle { EsriKey.isAvailable ? .esriSatellite : .osmTopo }
    static func makeDefault() -> OnlineRasterBasemapSource { OnlineRasterBasemapSource(defaultStyle) }

    func makeOverlay() -> MKTileOverlay {
        // Append the ArcGIS token for keyed styles. If Esri is selected without
        // a key we deliberately return a template that resolves nothing rather
        // than hot-link the unauthenticated endpoint - and the Layers UI hides
        // the option in the first place, so this is the safety net.
        var template = style.urlTemplate
        if style.requiresEsriKey {
            guard EsriKey.isAvailable else {
                let dead = MKTileOverlay(urlTemplate: nil) // no source, draws nothing
                dead.canReplaceMapContent = false
                return dead
            }
            template += "?token=\(EsriKey.token)"
        }
        let overlay = MKTileOverlay(urlTemplate: template)
        overlay.canReplaceMapContent = true
        overlay.maximumZ = style.maximumZ
        // Esri static tiles are 512px; MapKit needs to know or it scales wrong.
        overlay.tileSize = CGSize(width: style.tileSize, height: style.tileSize)
        return overlay
    }
}
