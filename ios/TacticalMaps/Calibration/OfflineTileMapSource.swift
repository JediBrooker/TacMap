import Foundation
import MapKit

/// Basemap backed by a local MBTiles raster pyramid (offline). Like PDFMapSource
/// it travels alongside the WGS84 overlay store, but instead of a single
/// rasterised image it serves a proper zoomable tile set through MKTileOverlay.
/// Coverage comes from MBTiles `bounds` metadata so camera can frame map on load.
final class OfflineTileMapSource: MapSource {
    let id = UUID()
    let displayName: String
    let kind: MapSourceKind = .offlineTiles
    let coverage: MKCoordinateRegion?
    let calibration: Calibration? = nil

    let url: URL
    let store: MBTilesStore

    private init(url: URL, store: MBTilesStore) {
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

    convenience init?(url: URL) {
        guard let store = MBTilesStore(url: url) else { return nil }
        self.init(url: url, store: store)
    }

    /// Builds the UI/map wrapper from metadata validated by the detached
    /// import worker. No file open or SQLite query occurs in this initializer.
    convenience init(prevalidatedURL url: URL, metadata: MBTilesStore.Metadata) {
        self.init(url: url,
                  store: MBTilesStore(prevalidatedURL: url, metadata: metadata))
    }

    /// Fresh overlay for map to add. Coordinator owns the lifecycle.
    func makeOverlay() -> MBTilesTileOverlay { MBTilesTileOverlay(store: store) }

    func closeForDeletion() { store.closeForDeletion() }
}
