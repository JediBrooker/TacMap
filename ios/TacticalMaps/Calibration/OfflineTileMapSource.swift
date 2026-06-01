import Foundation
import MapKit

/// A basemap backed by a local MBTiles raster pyramid (offline). Like
/// `PDFMapSource` it travels alongside the WGS84 overlay store, but instead of
/// a single rasterised image it serves a proper zoomable tile set through an
/// `MKTileOverlay`. Coverage comes from the MBTiles `bounds` metadata so the
/// camera can frame the map on load.
final class OfflineTileMapSource: MapSource {
    let id = UUID()
    let displayName: String
    let kind: MapSourceKind = .offlineTiles
    let coverage: MKCoordinateRegion?
    let calibration: Calibration? = nil

    let url: URL
    let store: MBTilesStore

    init?(url: URL) {
        guard let store = MBTilesStore(url: url) else { return nil }
        self.url = url
        self.store = store
        self.displayName = store.metadata.name
            ?? url.deletingPathExtension().lastPathComponent
        if let b = store.metadata.bounds {
            let center = CLLocationCoordinate2D(
                latitude:  (b.minLat + b.maxLat) / 2,
                longitude: (b.minLon + b.maxLon) / 2
            )
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(b.maxLat - b.minLat) * 1.1,
                longitudeDelta: abs(b.maxLon - b.minLon) * 1.1
            )
            self.coverage = MKCoordinateRegion(center: center, span: span)
        } else {
            self.coverage = nil
        }
    }

    /// A fresh overlay for the map to add. The Coordinator owns its lifecycle.
    func makeOverlay() -> MBTilesTileOverlay { MBTilesTileOverlay(store: store) }
}
