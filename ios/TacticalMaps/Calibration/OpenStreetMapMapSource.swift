import Foundation
import MapKit

/// Online OpenStreetMap raster basemap (standard OSM tiles) — an alternative to
/// Apple Satellite for street / topographic context. Served via an
/// `MKTileOverlay` with `canReplaceMapContent` on, so it fully covers the
/// satellite base underneath (mirrors the offline-MBTiles path).
///
/// No API key. Use is subject to the OSM Foundation tile-usage policy
/// (https://operations.osmfoundation.org/policies/tiles/): light/personal use
/// with attribution shown in-app (see AcknowledgementsView). For heavier
/// traffic, point `urlTemplate` at a self-hosted or commercial endpoint.
final class OpenStreetMapMapSource: MapSource {
    let id = UUID()
    let displayName = "OpenStreetMap"
    let kind: MapSourceKind = .openStreetMap
    let coverage: MKCoordinateRegion? = nil
    let calibration: Calibration? = nil

    func makeOverlay() -> MKTileOverlay {
        let overlay = MKTileOverlay(urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
        overlay.canReplaceMapContent = true
        overlay.maximumZ = 19
        return overlay
    }
}
