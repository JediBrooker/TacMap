import MapKit
import Grid

// MARK: - MGRS grid overlay
//
// The on-map MGRS grid (polylines + labels). Extracted verbatim from
// MapContainerView.swift; the grid's overlay/label bookkeeping lives on the
// Coordinator and is read back by the overlay renderer.
extension MapContainerView.Coordinator {

    /// Rebuild the visible MGRS-grid polylines. Cheap: bounded by
    /// what's actually on screen, and skipped entirely when the
    /// toggle is off. We bucket by a coarse fingerprint so panning
    /// inside a stable cell doesn't re-tessellate the same lines.
    func refreshMGRSGrid(on mv: MKMapView) {
        // Always drop the existing overlay set + label annotations
        // first — if the toggle is off, this leaves the map clean.
        if !mgrsOverlayIDs.isEmpty {
            let toRemove = mv.overlays.filter { mgrsOverlayIDs.contains(ObjectIdentifier($0)) }
            mv.removeOverlays(toRemove)
            mgrsOverlayIDs.removeAll()
            mgrsGridTypeByOverlay.removeAll()
        }
        if !mgrsLabelAnnotations.isEmpty {
            mv.removeAnnotations(mgrsLabelAnnotations)
            mgrsLabelAnnotations.removeAll()
        }
        guard mgrsGridVisibleFlag else {
            lastMGRSFingerprint = ""
            return
        }

        // Skip the heavy work when the rounded region hasn't moved
        // enough to change which 100km / 10km / 1km cells are visible.
        let region = mv.region
        let widthPts = mv.bounds.width
        let fp = String(format: "%.3f,%.3f,%.3f,%.3f,%.0f",
                        region.center.latitude,
                        region.center.longitude,
                        region.span.latitudeDelta,
                        region.span.longitudeDelta,
                        widthPts)
        if fp == lastMGRSFingerprint { return }
        lastMGRSFingerprint = fp

        let built = MGRSGridRenderer.build(for: region, mapWidthPoints: widthPts)
        for seg in built.lines {
            mgrsGridTypeByOverlay[ObjectIdentifier(seg.polyline)] = seg.gridType
            mgrsOverlayIDs.insert(ObjectIdentifier(seg.polyline))
            mv.addOverlay(seg.polyline, level: .aboveLabels)
        }
        for label in built.labels {
            let ann = MGRSGridLabelAnnotation(text: label.text,
                                              coordinate: label.coordinate,
                                              gridType: label.gridType,
                                              isVertical: label.isVertical)
            mgrsLabelAnnotations.append(ann)
        }
        if !mgrsLabelAnnotations.isEmpty {
            mv.addAnnotations(mgrsLabelAnnotations)
        }
    }
}
