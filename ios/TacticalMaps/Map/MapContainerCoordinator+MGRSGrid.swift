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
        // Always drop the existing MKOverlay set + label annotations first —
        // if the toggle is off (or we're on the PDF/subview path) this leaves
        // the map clean.
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
            removeMGRSGridOverlayView()
            return
        }

        // A PDF basemap is drawn as a UIImageView subview, so MKOverlay grid
        // lines paint underneath it and vanish. While a PDF is active we
        // render the grid into a sibling subview placed ABOVE the PDF instead.
        let pdfActive = (pdfImageView != nil)

        // Skip the heavy work when the rounded region hasn't moved enough to
        // change which 100km / 10km / 1km cells are visible. `pdfActive` is in
        // the fingerprint so flipping basemap forces a path switch.
        let region = mv.region
        let widthPts = mv.bounds.width
        let fp = String(format: "%d,%.3f,%.3f,%.3f,%.3f,%.0f",
                        pdfActive ? 1 : 0,
                        region.center.latitude,
                        region.center.longitude,
                        region.span.latitudeDelta,
                        region.span.longitudeDelta,
                        widthPts)
        if fp == lastMGRSFingerprint { return }
        lastMGRSFingerprint = fp

        let built = MGRSGridRenderer.build(for: region, mapWidthPoints: widthPts)

        if pdfActive {
            let gridView = ensureMGRSGridOverlayView(on: mv)
            gridView.update(lines: built.lines, labels: built.labels)
            return
        }

        // Plain-basemap path: cheaper MKOverlay lines + label annotations.
        removeMGRSGridOverlayView()
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

    /// Create (or reuse) the subview grid renderer and keep it positioned
    /// directly above the PDF image — below MapKit's annotation views, so
    /// waypoints still sit on top.
    private func ensureMGRSGridOverlayView(on mv: MKMapView) -> MGRSGridOverlayView {
        let view = mgrsGridOverlayView ?? {
            let v = MGRSGridOverlayView(mapView: mv)
            mgrsGridOverlayView = v
            return v
        }()
        if let pdf = pdfImageView, pdf.superview === mv {
            // aboveSubview re-stacks even if already a subview, so this also
            // re-raises the grid after the PDF view is recreated on a source swap.
            mv.insertSubview(view, aboveSubview: pdf)
        } else if view.superview !== mv {
            mv.addSubview(view)
        }
        return view
    }

    /// Tear down the subview grid (toggled off, or switched back to a plain
    /// basemap where the MKOverlay path takes over).
    func removeMGRSGridOverlayView() {
        mgrsGridOverlayView?.removeFromSuperview()
        mgrsGridOverlayView = nil
    }

    /// Re-project the subview grid against the current camera. No-op on the
    /// MKOverlay path. Called on every camera change so the grid stays glued
    /// to the map between cell-rebuilds.
    func reprojectMGRSGridOverlay() {
        mgrsGridOverlayView?.reproject()
    }
}
