import UIKit
import MapKit
import PDFKit
import CoreLocation

/// Rasterises a PDF's first page once and exposes the result as a UIImage.
/// Used by `PDFImageOverlayView` to draw the PDF directly into the map view's
/// subview hierarchy — sidesteps iOS 26 MapKit's broken MKOverlay /
/// MKTileOverlay paths for satellite imagery.
enum PDFRasteriser {

    /// Render page 1 of a PDF to a UIImage.
    /// - Parameter cropRect: optional crop in PDF user space (points, y-up,
    ///   origin bottom-left). If nil, the full media box is rendered.
    ///   Use the LGIDict Neatline bounding box here to drop legend/title
    ///   marginalia and render only the map content.
    static func render(url: URL,
                       cropRect: CGRect? = nil,
                       maxPixelWidth: CGFloat = 2048) -> UIImage? {
        guard let doc  = PDFDocument(url: url),
              let page = doc.page(at: 0) else { return nil }
        let pageRect = page.bounds(for: .mediaBox)
        let renderRect = cropRect ?? pageRect
        guard renderRect.width > 0, renderRect.height > 0 else { return nil }

        let scale = min(1, maxPixelWidth / renderRect.width)
        let imageSize = CGSize(width:  renderRect.width  * scale,
                                height: renderRect.height * scale)

        let renderer = UIGraphicsImageRenderer(size: imageSize)
        return renderer.image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: imageSize))
            let cg = ctx.cgContext
            cg.saveGState()
            // 1. Move origin to bottom-left of the output image.
            cg.translateBy(x: 0, y: imageSize.height)
            // 2. Flip Y so PDF (y-up) and CGContext (now y-up) agree.
            cg.scaleBy(x: scale, y: -scale)
            // 3. Translate so the crop's bottom-left maps to the image's origin.
            cg.translateBy(x: -renderRect.minX, y: -renderRect.minY)
            page.draw(with: .mediaBox, to: cg)
            cg.restoreGState()
        }
    }
}

/// UIImageView pinned to the PDF's geographic bounds. Updates its frame on
/// every map camera change so it stays correctly positioned over the satellite.
/// Also hosts fiduciary marker subviews during calibration.
final class PDFImageOverlayView: UIImageView {
    let pdfSW: CLLocationCoordinate2D
    let pdfNE: CLLocationCoordinate2D
    /// PDF user space rect that was rasterised into `image` (the crop rect,
    /// or the full media box if there's no crop). Drives the screen ↔ PDF
    /// coordinate conversions used by fiduciary calibration.
    let pdfRenderRect: CGRect

    /// Affine (PDF user-space → WGS84) for rotation/scale-correct placement.
    /// When set, `updateFrame` projects the page's true corners through it;
    /// when nil it falls back to the axis-aligned lat/lon stretch.
    let placementTransform: AffineTransform2D?

    /// Per-fiduciary marker subviews, indexed by fiduciary id so we can
    /// reposition them on layout without rebuilding.
    private var markers: [UUID: UIView] = [:]
    /// Pending-tap crosshair shown between tap and MGRS confirmation.
    private var pendingMarker: UIView?

    init(image: UIImage,
         southWest: CLLocationCoordinate2D,
         northEast: CLLocationCoordinate2D,
         pdfRenderRect: CGRect,
         placementTransform: AffineTransform2D? = nil) {
        self.pdfSW = southWest
        self.pdfNE = northEast
        self.pdfRenderRect = pdfRenderRect
        self.placementTransform = placementTransform
        super.init(image: image)
        self.contentMode = .scaleToFill
        // Default false (taps fall through to MKMapView for pan/zoom/draw).
        // MapContainerView toggles this on while a CalibrationSession is
        // active so taps hit-test this view and we can convert them to PDF coords.
        self.isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    /// Pin the image to the PDF's geographic bounds. Uses **all four corners**
    /// to derive a rotation-invariant size, then re-applies the map heading
    /// via affine transform so the image spins WITH the map instead of being
    /// crushed into an axis-aligned bounding box.
    func updateFrame(in mapView: MKMapView) {
        // Preferred path: place via the embedded affine so the sheet sits at its
        // true rotation (grid convergence) + scale and lines up with the MGRS
        // grid. Falls through to the lat/lon stretch when there's no affine.
        if let t = placementTransform, applyAffinePlacement(t, in: mapView) {
            return
        }

        // Build the four geographic corners of the rect (not just SW & NE).
        let nw = CLLocationCoordinate2D(latitude: pdfNE.latitude, longitude: pdfSW.longitude)
        let ne = pdfNE
        let sw = pdfSW

        let nwPt = mapView.convert(nw, toPointTo: mapView)
        let nePt = mapView.convert(ne, toPointTo: mapView)
        let swPt = mapView.convert(sw, toPointTo: mapView)

        // Side lengths in screen space — these are invariant under rotation,
        // unlike an axis-aligned bounding box of two opposite corners.
        let width  = hypot(nePt.x - nwPt.x, nePt.y - nwPt.y)
        let height = hypot(swPt.x - nwPt.x, swPt.y - nwPt.y)
        if width < 1 || height < 1 {
            self.isHidden = true
            return
        }
        self.isHidden = false

        // Centre the imageView on the geographic centre of the bounds.
        let centreGeo = CLLocationCoordinate2D(
            latitude:  (sw.latitude  + ne.latitude)  / 2,
            longitude: (sw.longitude + ne.longitude) / 2
        )
        let centreScreen = mapView.convert(centreGeo, toPointTo: mapView)

        // Sizing without rotation (transform identity first so .bounds writes
        // are interpreted in screen-aligned coords).
        self.transform = .identity
        self.bounds = CGRect(x: 0, y: 0, width: width, height: height)
        self.center = centreScreen

        // Then rotate to match the map heading.
        // heading=0° (north-up) → identity.
        // heading=90° (east-up) → image visually rotated 90° clockwise on screen
        // (positive rotationAngle in UIKit = clockwise because y-down).
        let heading = mapView.camera.heading
        if abs(heading) > 0.001 {
            self.transform = CGAffineTransform(rotationAngle: heading * .pi / 180)
        }
    }

    /// Place the page using the embedded PDF→WGS84 affine: map the crop's four
    /// true corners to geographic points, project them to screen, then size +
    /// rotate the (axis-aligned) image rect onto that quad. The projected
    /// corners already fold in the camera heading, so no separate heading term
    /// is needed. Returns false on a degenerate projection so `updateFrame` can
    /// fall back to the lat/lon stretch.
    private func applyAffinePlacement(_ t: AffineTransform2D, in mapView: MKMapView) -> Bool {
        let r = pdfRenderRect
        // The image is rasterised from the crop with a Y-flip, so image-top maps
        // to crop-top (maxY) and image-bottom to crop-bottom (minY).
        let pTL = mapView.convert(t.apply(CGPoint(x: r.minX, y: r.maxY)), toPointTo: mapView)
        let pTR = mapView.convert(t.apply(CGPoint(x: r.maxX, y: r.maxY)), toPointTo: mapView)
        let pBL = mapView.convert(t.apply(CGPoint(x: r.minX, y: r.minY)), toPointTo: mapView)
        let pBR = mapView.convert(t.apply(CGPoint(x: r.maxX, y: r.minY)), toPointTo: mapView)

        let width  = hypot(pTR.x - pTL.x, pTR.y - pTL.y)
        let height = hypot(pBL.x - pTL.x, pBL.y - pTL.y)
        let angle  = atan2(pTR.y - pTL.y, pTR.x - pTL.x)
        guard width >= 1, height >= 1,
              width.isFinite, height.isFinite, angle.isFinite else { return false }

        let centre = CGPoint(x: (pTL.x + pTR.x + pBL.x + pBR.x) / 4,
                             y: (pTL.y + pTR.y + pBL.y + pBR.y) / 4)
        self.isHidden = false
        self.transform = .identity
        self.bounds = CGRect(x: 0, y: 0, width: width, height: height)
        self.center = centre
        self.transform = CGAffineTransform(rotationAngle: angle)
        return true
    }

    // MARK: - Tap ↔ PDF coordinate conversion

    /// Convert a tap (in `mapView`'s coordinate space) to a point in PDF user
    /// space (y-up, origin = bottom-left of `pdfRenderRect`). Returns nil if
    /// the tap falls outside the rendered image.
    func pdfPoint(forScreenTap tap: CGPoint, in mapView: MKMapView) -> CGPoint? {
        let local = self.convert(tap, from: mapView)
        guard self.bounds.contains(local) else { return nil }

        // Image fills bounds (scaleToFill). View-local x/y map linearly to
        // image pixels, which in turn map to `pdfRenderRect` with a Y flip.
        let fracX = local.x / bounds.width
        let fracY = local.y / bounds.height
        let pdfX  = pdfRenderRect.minX + fracX * pdfRenderRect.width
        let pdfY  = pdfRenderRect.maxY - fracY * pdfRenderRect.height
        return CGPoint(x: pdfX, y: pdfY)
    }

    /// Inverse of the above — PDF point to view-local. Used to place markers.
    func localPoint(forPDFPoint p: CGPoint) -> CGPoint {
        let fracX = (p.x - pdfRenderRect.minX) / pdfRenderRect.width
        let fracY = (pdfRenderRect.maxY - p.y) / pdfRenderRect.height
        return CGPoint(x: fracX * bounds.width, y: fracY * bounds.height)
    }

    // MARK: - Fiduciary markers

    /// Sync marker subviews with the given fiduciaries + pending tap. Cheap
    /// to call on every layout change.
    func syncFiduciaryMarkers(_ fids: [Fiduciary], pendingPDFPoint: CGPoint?) {
        // Remove markers whose fiduciary was deleted.
        let liveIDs = Set(fids.map(\.id))
        for (id, view) in markers where !liveIDs.contains(id) {
            view.removeFromSuperview()
            markers.removeValue(forKey: id)
        }
        // Place / update markers.
        for (i, fid) in fids.enumerated() {
            let centre = localPoint(forPDFPoint: CGPoint(x: fid.pdfX, y: fid.pdfY))
            if let existing = markers[fid.id] {
                existing.center = centre
            } else {
                let m = Self.makeMarker(number: i + 1, tint: .systemOrange)
                m.center = centre
                addSubview(m)
                markers[fid.id] = m
            }
        }
        // Pending marker (a + crosshair).
        pendingMarker?.removeFromSuperview()
        pendingMarker = nil
        if let p = pendingPDFPoint {
            let centre = localPoint(forPDFPoint: p)
            let m = Self.makePendingMarker()
            m.center = centre
            addSubview(m)
            pendingMarker = m
        }
    }

    private static func makeMarker(number: Int, tint: UIColor) -> UIView {
        let size: CGFloat = 32
        let v = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
        v.backgroundColor = tint
        v.layer.cornerRadius = size / 2
        v.layer.borderWidth = 2
        v.layer.borderColor = UIColor.white.cgColor
        v.layer.shadowColor = UIColor.black.cgColor
        v.layer.shadowOpacity = 0.4
        v.layer.shadowRadius = 2
        v.layer.shadowOffset = .zero
        let label = UILabel(frame: v.bounds)
        label.text = "\(number)"
        label.textAlignment = .center
        label.textColor = .black
        label.font = .systemFont(ofSize: 15, weight: .bold)
        v.addSubview(label)
        return v
    }

    private static func makePendingMarker() -> UIView {
        let size: CGFloat = 28
        let v = UIView(frame: CGRect(x: 0, y: 0, width: size, height: size))
        v.backgroundColor = .clear
        let cross = CAShapeLayer()
        let path = UIBezierPath()
        path.move(to: CGPoint(x: size / 2, y: 0));    path.addLine(to: CGPoint(x: size / 2, y: size))
        path.move(to: CGPoint(x: 0, y: size / 2));    path.addLine(to: CGPoint(x: size, y: size / 2))
        cross.path = path.cgPath
        cross.strokeColor = UIColor.systemRed.cgColor
        cross.lineWidth = 2
        v.layer.addSublayer(cross)
        let ring = CAShapeLayer()
        ring.path = UIBezierPath(ovalIn: CGRect(x: 4, y: 4, width: size - 8, height: size - 8)).cgPath
        ring.strokeColor = UIColor.systemRed.cgColor
        ring.fillColor = UIColor.white.withAlphaComponent(0.3).cgColor
        ring.lineWidth = 2
        v.layer.addSublayer(ring)
        return v
    }
}
