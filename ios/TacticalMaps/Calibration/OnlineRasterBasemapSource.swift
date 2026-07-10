import Foundation
import MapKit

/// Online raster basemap styles (alternatives to native Apple satellite).
/// Standard XYZ tile services served via MKTileOverlay. Mirrors Android
/// BasemapStyle.
///
/// Esri now points at the keyed ibasemaps World Imagery endpoint - the same
/// raster source Esri's own current Basemap Styles service composes - instead
/// of the old unauthenticated server.arcgisonline.com URL, whose terms only
/// allow noncommercial use. See `requiresEsriKey`.
enum BasemapStyle: String, CaseIterable {
    case esriSatellite
    case terrain

    var displayName: String {
        switch self {
        case .esriSatellite: return "Satellite (Esri)"
        case .terrain:       return "Terrain (OpenTopoMap)"
        }
    }

    var requiresEsriKey: Bool { self == .esriSatellite }

    /// MKTileOverlay URL template. {z}/{x}/{y} substituted by name so the
    /// Esri {z}/{y}/{x} order is fine. The Esri token is appended by
    /// `OnlineRasterBasemapSource.makeOverlay`, not baked in here.
    var urlTemplate: String {
        switch self {
        case .esriSatellite:
            return "https://ibasemaps-api.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        case .terrain:
            return "https://tile.opentopomap.org/{z}/{x}/{y}.png"
        }
    }

    var maximumZ: Int {
        switch self {
        case .esriSatellite: return 19
        case .terrain:       return 17
        }
    }
}

/// Basemap backed by an online raster XYZ tile service (Esri imagery or
/// OpenTopoMap terrain). Served via MKTileOverlay with canReplaceMapContent
/// so it fully covers the base. No API key.
final class OnlineRasterBasemapSource: MapSource {
    let id = UUID()
    let style: BasemapStyle
    var displayName: String { style.displayName }
    let kind: MapSourceKind = .onlineRaster
    let coverage: MKCoordinateRegion? = nil
    let calibration: Calibration? = nil

    init(_ style: BasemapStyle) { self.style = style }

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
        return overlay
    }
}
