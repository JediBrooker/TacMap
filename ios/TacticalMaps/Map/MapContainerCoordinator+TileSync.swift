import MapKit

// MARK: - Raster basemap overlay (offline MBTiles OR online OpenStreetMap)
//
// Adds/removes the MKTileOverlay for raster basemaps (OfflineTileMapSource
// or OnlineRasterBasemapSource). Both replace satellite (canReplaceMapContent).
// Overlay persists across refresh() which filters MKTileOverlay out of its
// teardown, so tiles dont reload on every model change.
extension MapContainerView.Coordinator {

    func syncTileOverlay(on mv: MKMapView, source: MapSource) {
        // Resolve the active raster-basemap source (if any) to an id + factory.
        let newID: UUID?
        let makeOverlay: (() -> MKTileOverlay)?
        switch source {
        case let s as OfflineTileMapSource:       newID = s.id; makeOverlay = { s.makeOverlay() }
        case let s as OnlineRasterBasemapSource:  newID = s.id; makeOverlay = { s.makeOverlay() }
        default:                                  newID = nil;  makeOverlay = nil
        }

        // Remove when the source changed or is no longer a raster source.
        if let existing = tileOverlay, newID != tileSourceID {
            mv.removeOverlay(existing)
            tileOverlay = nil
            tileSourceID = nil
        }

        // Add when new raster source becomes active. Above labels so it
        // covers satellite basemap. Drawings + MGRS grid are added after
        // this so they stay on top.
        if tileOverlay == nil, let make = makeOverlay {
            let overlay = make()
            mv.addOverlay(overlay, level: .aboveLabels)
            tileOverlay = overlay
            tileSourceID = newID
        }
    }
}
