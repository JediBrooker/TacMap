import MapKit
import UIKit

// MARK: - PDF basemap overlay sync
//
// Attaches/detaches the imported-PDF image view (and its dark basemap mask)
// and forwards calibration fiduciary markers. Pulled out of
// MapContainerView.swift. PDF renders as a UIImageView subview rather than
// MKOverlay b/c iOS 26 MapKit refuses to draw custom overlays on satellite.
extension MapContainerView.Coordinator {

    /// Attach/detach the PDF image view to match
    /// (source is PDFMapSource) && visible. Resizes frame every camera
    /// change to stay anchored to PDF’s geographic bounds.
    ///
    /// When PDF is active we also drop a dark UIView between satellite
    /// tiles and the PDF so imported map is the only visible thing
    /// (no satellite peeking through underneath).
    func syncPDFOverlay(on mv: MKMapView,
                        source: MapSource,
                        visible: Bool) {
        let pdfSource = source as? PDFMapSource
        let newID = pdfSource?.id
        let pdfActive = (pdfSource != nil) && visible

        // Drop basemap mask when PDF goes away. Mask is ADDED only once
        // the page bitmap is ready (in attachPDFOverlay), adding it up
        // front would flash a dark screen while the page rasterises.
        if !pdfActive, let mask = basemapMask {
            mask.removeFromSuperview()
            basemapMask = nil
        }
        // Kill in-flight rasterisation if source changed or went away.
        if pdfRasterizingSourceID != nil && pdfRasterizingSourceID != newID {
            pdfRasterizingSourceID = nil
        }

        // Remove if source changed, became non-PDF, or visibility flipped off.
        if let existing = pdfImageView,
           newID != pdfSourceID || !visible || pdfSource == nil {
            NSLog("[PDF] removing image view")
            existing.removeFromSuperview()
            pdfImageView = nil
            pdfSourceID = nil
            // Grid was drawn into a subview above PDF, revert to the
            // cheaper MKOverlay path now that PDF is gone.
            lastMGRSFingerprint = ""
            refreshMGRSGrid(on: mv)
        }

        // Attach if needed. Rasterising the page is heavy (decodes the PDF to a
        // bitmap), so do it OFF main thread. Map paints immediately and
        // the page streams in a moment later instead of blocking launch.
        // Cache hit = attach right away.
        if pdfImageView == nil, visible,
           let src = pdfSource,
           let bounds = src.bounds {
            if let image = src.cachedRenderedImage {
                attachPDFOverlay(image: image, source: src, bounds: bounds, sourceID: newID, on: mv)
            } else if pdfRasterizingSourceID != src.id {
                pdfRasterizingSourceID = src.id
                DispatchQueue.global(qos: .userInitiated).async { [weak self, weak mv] in
                    let image = src.renderedImage()
                    DispatchQueue.main.async {
                        guard let self, let mv,
                              self.pdfRasterizingSourceID == src.id else { return }
                        self.pdfRasterizingSourceID = nil
                        // Bail if the source changed, it got hidden, or another
                        // pass already attached the overlay.
                        guard let image,
                              self.pdfImageView == nil,
                              (self.mapVM.mapSource as? PDFMapSource)?.id == src.id else { return }
                        self.attachPDFOverlay(image: image, source: src, bounds: bounds, sourceID: src.id, on: mv)
                    }
                }
            }
        }

        // Keep the existing view's frame fresh against current camera.
        pdfImageView?.updateFrame(in: mv)
    }

    /// Add dark basemap mask + PDF image view and frame camera onto
    /// the page. Called once bitmap is ready, either sync (cache hit)
    /// or from background rasterisation.
    private func attachPDFOverlay(image: UIImage,
                                  source src: PDFMapSource,
                                  bounds: GeoPDFReader.Bounds,
                                  sourceID: UUID?,
                                  on mv: MKMapView) {
        // Dark mask between satellite and page so imported map is the
        // only visible thing (no satellite peeking through).
        if basemapMask == nil {
            let mask = UIView(frame: mv.bounds)
            mask.backgroundColor = UIColor(white: 0.10, alpha: 1.0)  // near-black
            mask.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            mask.isUserInteractionEnabled = false
            mv.addSubview(mask)
            basemapMask = mask
        }

        NSLog("[PDF] attaching image view for \(src.displayName) (\(Int(image.size.width))x\(Int(image.size.height)))")
        let view = PDFImageOverlayView(
            image: image,
            southWest: bounds.southWest,
            northEast: bounds.northEast,
            pdfRenderRect: src.pdfRenderRect,
            placementTransform: src.placementTransform
        )
        // Above the mask; MKMapView's annotation views are siblings above ours,
        // so waypoints and the user dot remain visible.
        mv.addSubview(view)
        view.updateFrame(in: mv)
        pdfImageView = view
        pdfSourceID = sourceID

        // Move the MGRS grid onto the subview renderer that draws ABOVE this PDF
        // b/c MKOverlay grid lines would otherwise be hidden beneath the image.
        lastMGRSFingerprint = ""
        refreshMGRSGrid(on: mv)

        // Fly to the PDF if it's not in the current viewport.
        let visibleRect = mv.visibleMapRect
        let pdfMapRect = MKMapRect(
            origin: MKMapPoint(bounds.northEast).x < MKMapPoint(bounds.southWest).x
                ? MKMapPoint(bounds.northEast)
                : MKMapPoint(x: MKMapPoint(bounds.southWest).x,
                              y: MKMapPoint(bounds.northEast).y),
            size: MKMapSize(
                width:  abs(MKMapPoint(bounds.northEast).x - MKMapPoint(bounds.southWest).x),
                height: abs(MKMapPoint(bounds.northEast).y - MKMapPoint(bounds.southWest).y)
            )
        )
        if !pdfMapRect.intersects(visibleRect) {
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(bounds.northEast.latitude  - bounds.southWest.latitude)  * 1.2,
                longitudeDelta: abs(bounds.northEast.longitude - bounds.southWest.longitude) * 1.2
            )
            NSLog("[PDF] off-screen, flying camera to \(bounds.centre.latitude),\(bounds.centre.longitude)")
            mv.setRegion(MKCoordinateRegion(center: bounds.centre, span: span), animated: true)
        }
    }

    /// Create/reuse drawings overlay, keep it ABOVE the PDF and above
    /// the grid subview so drawings sit on top. MapKit's annotation
    /// views are siblings above ours, so waypoints/labels stay on top.
    func ensurePDFDrawingsView(on mv: MKMapView) -> DrawingsOverlayView {
        let view = pdfDrawingsView ?? {
            let v = DrawingsOverlayView(mapView: mv)
            pdfDrawingsView = v
            return v
        }()
        if let grid = mgrsGridOverlayView, grid.superview === mv {
            mv.insertSubview(view, aboveSubview: grid)
        } else if let pdf = pdfImageView, pdf.superview === mv {
            mv.insertSubview(view, aboveSubview: pdf)
        } else if view.superview !== mv {
            mv.addSubview(view)
        }
        return view
    }

    func removePDFDrawingsView() {
        pdfDrawingsView?.removeFromSuperview()
        pdfDrawingsView = nil
    }

    /// Re-project drawings subview against current camera (no-op without
    /// a PDF). Called every camera change so shapes track the map.
    func reprojectPDFDrawings() {
        pdfDrawingsView?.reproject()
    }

    /// Forwarded into PDFImageOverlayView. Safe to call whenever,
    /// just clears markers when no calibration is active.
    func syncCalibrationMarkers() {
        guard let img = pdfImageView else { return }
        if calibration.isCalibrating {
            img.syncFiduciaryMarkers(
                calibration.fiduciaries,
                pendingPDFPoint: calibration.pendingTap?.pdfPoint
            )
        } else {
            img.syncFiduciaryMarkers([], pendingPDFPoint: nil)
        }
    }
}
