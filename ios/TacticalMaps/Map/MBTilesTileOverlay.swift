import MapKit

/// `MKTileOverlay` that serves raster tiles from a local MBTiles file via
/// `MBTilesStore`. `canReplaceMapContent` is on so the offline tiles cover the
/// satellite basemap entirely (the user wants the imported map, not a blend).
final class MBTilesTileOverlay: MKTileOverlay {

    private let store: MBTilesStore

    init(store: MBTilesStore) {
        self.store = store
        super.init(urlTemplate: nil)
        canReplaceMapContent = true
        if let mn = store.metadata.minZoom { minimumZ = mn }
        if let mx = store.metadata.maxZoom { maximumZ = mx }
    }

    override func loadTile(at path: MKTileOverlayPath,
                          result: @escaping (Data?, Error?) -> Void) {
        result(store.tileData(z: path.z, x: path.x, y: path.y), nil)
    }
}
