import MapKit
import UIKit

// MARK: - PDF basemap overlay sync
//
// Attaches/detaches the imported-PDF image view (and its dark basemap mask)
// and forwards calibration fiduciary markers. Extracted verbatim from
// MapContainerView.swift. The PDF renders as a UIImageView subview rather than
// an MKOverlay because iOS 26 MapKit refuses to draw custom overlays on
// satellite imagery.
extension MapContainerView.Coordinator {

    /// Attach/detach the PDF image view so its presence matches
    /// `(source is PDFMapSource) && visible`. Resizes its frame on every
    /// camera change to stay anchored to the PDF’s geographic bounds.
    ///
    /// When the PDF is active we also drop a dark UIView between the
    /// satellite tiles and the PDF so the imported map is the only
    /// visible content (no satellite trying to align underneath).
    func syncPDFOverlay(on mv: MKMapView,
                        source: MapSource,
                        visible: Bool) {
        let pdfSource = source as? PDFMapSource
        let newID = pdfSource?.id
        let pdfActive = (pdfSource != nil) && visible

        // Manage basemap mask.
        if pdfActive && basemapMask == nil {
            let mask = UIView(frame: mv.bounds)
            mask.backgroundColor = UIColor(white: 0.10, alpha: 1.0)  // near-black
            mask.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            mask.isUserInteractionEnabled = false
            mv.addSubview(mask)
            basemapMask = mask
        } else if !pdfActive, let mask = basemapMask {
            mask.removeFromSuperview()
            basemapMask = nil
        }

        // Remove if source changed, became non-PDF, or visibility flipped off.
        if let existing = pdfImageView,
           newID != pdfSourceID || !visible || pdfSource == nil {
            NSLog("[PDF] removing image view")
            existing.removeFromSuperview()
            pdfImageView = nil
            pdfSourceID = nil
        }

        // Attach if needed.
        if pdfImageView == nil, visible,
           let src = pdfSource,
           let bounds = src.bounds,
           let image = src.renderedImage() {
            NSLog("[PDF] attaching image view for \(src.displayName) (\(Int(image.size.width))x\(Int(image.size.height)))")
            let view = PDFImageOverlayView(
                image: image,
                southWest: bounds.southWest,
                northEast: bounds.northEast,
                pdfRenderRect: src.pdfRenderRect
            )
            // Insert near the top of the subview hierarchy so it sits
            // above the satellite tiles. MKMapView's annotations live in
            // separate sibling views above ours — waypoints and the user
            // dot remain visible.
            // PDF goes on top of the dark mask (which was added just above).
            mv.addSubview(view)
            view.updateFrame(in: mv)
            pdfImageView = view
            pdfSourceID = newID

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
                NSLog("[PDF] off-screen — flying camera to \(bounds.centre.latitude),\(bounds.centre.longitude)")
                mv.setRegion(MKCoordinateRegion(center: bounds.centre, span: span), animated: true)
            }
        }

        // Keep the existing view's frame fresh against current camera.
        pdfImageView?.updateFrame(in: mv)
    }

    /// Forwarded into the PDFImageOverlayView; safe to call whenever —
    /// clears markers when no calibration is active.
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
