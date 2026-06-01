import MapKit

// MARK: - Offline MBTiles raster basemap overlay
//
// Adds/removes the MKTileOverlay that serves a local MBTiles file when the
// active source is an OfflineTileMapSource. The overlay is added once and
// persists across refresh() (which filters MKTileOverlay out of its teardown)
// so the tiles don't reload on every model change.
extension MapContainerView.Coordinator {

    func syncTileOverlay(on mv: MKMapView, source: MapSource) {
        let tileSource = source as? OfflineTileMapSource
        let newID = tileSource?.id

        // Remove when the source changed or is no longer an offline-tile source.
        if let existing = tileOverlay, newID != tileSourceID {
            mv.removeOverlay(existing)
            tileOverlay = nil
            tileSourceID = nil
        }

        // Add when a new offline-tile source becomes active. Added above labels
        // so it covers the satellite basemap; drawings + the MGRS grid are added
        // after this in add-order, so they stay on top.
        if tileOverlay == nil, let src = tileSource {
            let overlay = src.makeOverlay()
            mv.addOverlay(overlay, level: .aboveLabels)
            tileOverlay = overlay
            tileSourceID = newID
        }
    }
}
