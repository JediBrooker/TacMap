import Foundation
import MapKit

/// Online raster basemap styles (alternatives to native Apple satellite).
/// Standard XYZ tile services, no API key needed, served via MKTileOverlay.
/// Mirrors Android BasemapStyle.
enum BasemapStyle: String, CaseIterable {
    case esriSatellite
    case terrain

    var displayName: String {
        switch self {
        case .esriSatellite: return "Satellite (Esri)"
        case .terrain:       return "Terrain (OpenTopoMap)"
        }
    }

    /// MKTileOverlay URL template. {z}/{x}/{y} substituted by name so the
    /// Esri {z}/{y}/{x} order is fine.
    var urlTemplate: String {
        switch self {
        case .esriSatellite:
            return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
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
        let overlay = MKTileOverlay(urlTemplate: style.urlTemplate)
        overlay.canReplaceMapContent = true
        overlay.maximumZ = style.maximumZ
        return overlay
    }
}
