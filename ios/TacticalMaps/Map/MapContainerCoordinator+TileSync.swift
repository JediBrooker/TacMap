import MapKit

// MARK: - Raster basemap overlay (offline MBTiles OR online raster OR blank)
//
// Adds/removes the MKTileOverlay for raster basemaps (OfflineTileMapSource
// or OnlineRasterBasemapSource). Both replace satellite (canReplaceMapContent).
// Overlay persists across refresh() which filters MKTileOverlay out of its
// teardown, so tiles dont reload on every model change.
//
// The OPSEC gate lives here too. While `onlineBasemaps` is off we install a
// BlankBasemapOverlay instead, which covers Apple's basemap so MapKit has no
// reason to fetch it. That has to apply in PDF mode as well: the PDF draws into
// a subview above the map, so without the blank overlay Apple satellite would
// still be loading underneath a sheet the user imported precisely so they
// wouldn't have to touch the network.
extension MapContainerView.Coordinator {

    func syncTileOverlay(on mv: MKMapView, source: MapSource, onlineBasemaps: Bool) {
        // Resolve the overlay this state wants (if any) to an id + factory.
        let newID: UUID?
        let makeOverlay: (() -> MKTileOverlay)?

        switch source {
        case let s as OfflineTileMapSource:
            // Local tiles, no egress, allowed regardless of the gate.
            newID = s.id; makeOverlay = { s.makeOverlay() }

        case let s as OnlineRasterBasemapSource where onlineBasemaps:
            newID = s.id; makeOverlay = { s.makeOverlay() }

        default:
            if onlineBasemaps {
                // Apple's own basemap (satellite or under a PDF). Nothing to add.
                newID = nil; makeOverlay = nil
            } else {
                newID = BlankBasemapOverlay.sourceID
                makeOverlay = { BlankBasemapOverlay() }
            }
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
