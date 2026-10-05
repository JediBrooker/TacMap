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
    /// the ImportedMapLibrary entry this source shows, nil for legacy/test sources
    let entryID: UUID?
    /// 3.0.2 M1: set when the open ran under the MBTiles open guard. The
    /// renderer reports its reads here, the guard clears once the first draw
    /// settles (or this source goes away first)
    var firstDraw: MBTilesFirstDrawWatch?

    private init(url: URL, store: MBTilesStore, entryID: UUID? = nil, displayName: String? = nil) {
        self.url = url
        self.store = store
        self.entryID = entryID
        self.displayName = displayName ?? store.metadata.name
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

    convenience init?(url: URL, entryID: UUID? = nil, displayName: String? = nil) {
        guard let store = MBTilesStore(url: url) else { return nil }
        self.init(url: url, store: store, entryID: entryID, displayName: displayName)
    }

    /// Builds the UI/map wrapper from metadata validated by the detached
    /// import worker. No file open or SQLite query occurs in this initializer.
    convenience init(prevalidatedURL url: URL, metadata: MBTilesStore.Metadata,
                     entryID: UUID? = nil, displayName: String? = nil) {
        self.init(url: url,
                  store: MBTilesStore(prevalidatedURL: url, metadata: metadata),
                  entryID: entryID, displayName: displayName)
    }

    /// Fresh overlay for map to add. Coordinator owns the lifecycle.
    func makeOverlay() -> MBTilesTileOverlay { MBTilesTileOverlay(store: store) }

    /// the store's lock waits out a read in flight, so by the time the guard
    /// hears about it nothing of this pack is still running
    func closeForDeletion() {
        store.closeForDeletion()
        firstDraw?.settle()
    }
}

/// 3.0.2 M2: what a restore shows while its pack gets opened and admitted off
/// main. No tiles, no tile requests (the renderer maps it to blank), no
/// coverage so nothing reframes. The pack's area never goes to an online
/// provider while it's being checked
final class MBTilesOpeningSource: MapSource {
    let id = UUID()
    let displayName: String
    let kind: MapSourceKind = .offlineTiles
    let coverage: MKCoordinateRegion? = nil
    let calibration: Calibration? = nil
    let entryID: UUID

    init(entryID: UUID, displayName: String) {
        self.entryID = entryID
        self.displayName = displayName
    }
}
