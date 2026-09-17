import UIKit
import MapKit
import PDFKit
import CoreLocation

struct PDFRasterSize: Equatable {
    let width: Int
    let height: Int
    var byteCount: Int64 { Int64(width) * Int64(height) * 4 }
}

/// A UIView placement that maps an image's local top/right/down basis onto the
/// corresponding projected PDF-page basis. Keeping the full two-dimensional
/// basis is important: a valid PDF -> WGS84 affine can contain shear, and
/// reducing it to width/height plus one rotation angle moves every point away
/// from its calibrated ground position.
struct PDFAffineOverlayPlacement: Equatable {
    let boundsSize: CGSize
    let center: CGPoint
    let transform: CGAffineTransform
}

func pdfAffineOverlayPlacement(
    topLeft: CGPoint,
    topRight: CGPoint,
    bottomLeft: CGPoint,
    bottomRight: CGPoint
) -> PDFAffineOverlayPlacement? {
    let points = [topLeft, topRight, bottomLeft, bottomRight]
    guard points.allSatisfy({ $0.x.isFinite && $0.y.isFinite }) else { return nil }

    let right = CGVector(dx: topRight.x - topLeft.x, dy: topRight.y - topLeft.y)
    let down = CGVector(dx: bottomLeft.x - topLeft.x, dy: bottomLeft.y - topLeft.y)
    let width = hypot(right.dx, right.dy)
    let height = hypot(down.dx, down.dy)
    guard width >= 1, height >= 1, width.isFinite, height.isFinite else { return nil }

    let transform = CGAffineTransform(
        a: right.dx / width,
        b: right.dy / width,
        c: down.dx / height,
        d: down.dy / height,
        tx: 0,
        ty: 0
    )
    let determinant = transform.a * transform.d - transform.b * transform.c
    // Reject a collapsed or effectively colinear screen projection. The
    // scale-normalised determinant is the sine of the angle between the two
    // page axes, so this threshold is independent of zoom.
    guard transform.a.isFinite, transform.b.isFinite,
          transform.c.isFinite, transform.d.isFinite,
          determinant.isFinite,
          abs(determinant) > 1e-9 else { return nil }

    let center = CGPoint(
        x: points.reduce(0) { $0 + $1.x } / CGFloat(points.count),
        y: points.reduce(0) { $0 + $1.y } / CGFloat(points.count)
    )
    guard center.x.isFinite, center.y.isFinite else { return nil }
    return PDFAffineOverlayPlacement(
        boundsSize: CGSize(width: width, height: height),
        center: center,
        transform: transform
    )
}

/// Preserve native dimensions for small sheets while bounding the actual
/// scale-1 ARGB allocation for hostile or unusually tall pages.
func boundedPDFRasterSize(
    width: Double,
    height: Double,
    maxDimension: Int = 4096,
    maxBytes: Int64 = 32 * 1024 * 1024
) -> PDFRasterSize? {
    guard width.isFinite, height.isFinite,
          width > 0, height > 0,
          maxDimension > 0, maxBytes >= 4 else { return nil }
    let area = width * height
    guard area.isFinite, area > 0 else { return nil }
    let maxPixels = Double(maxBytes / 4)
    let dimensionScale = Double(maxDimension) / max(width, height)
    let memoryScale = sqrt(maxPixels / area)
    let scale = min(1, dimensionScale, memoryScale)
    guard scale.isFinite, scale > 0 else { return nil }
    return PDFRasterSize(
        width: max(1, Int((width * scale).rounded(.down))),
        height: max(1, Int((height * scale).rounded(.down)))
    )
}

/// Rasterises a PDF's first page once and exposes result as a UIImage.
/// Used by PDFImageOverlayView to draw the PDF directly into map view's
/// subview hierarchy. Sidesteps iOS 26 MapKit's broken MKOverlay /
/// MKTileOverlay paths for satellite imagery.
enum PDFRasteriser {

    /// Render page 1 of a PDF to a UIImage.
    /// - Parameter cropRect: optional crop in PDF user space (points, y-up,
    ///   origin bottom-left). If nil, the full media box is rendered.
    ///   Use the LGIDict Neatline bounding box here to drop legend/title
    ///   marginalia and render only the map content.
    static func render(url: URL,
                       cropRect: CGRect? = nil,
                       maxPixelDimension: Int = 4096,
                       maxBytes: Int64 = 32 * 1024 * 1024) -> UIImage? {
        guard let doc  = PDFDocument(url: url),
              let page = doc.page(at: 0) else { return nil }
        let pageRect = page.bounds(for: .mediaBox)
        let renderRect = cropRect ?? pageRect
        guard let rasterSize = boundedPDFRasterSize(
            width: Double(renderRect.width),
            height: Double(renderRect.height),
            maxDimension: maxPixelDimension,
            maxBytes: maxBytes
        ) else { return nil }
        let imageSize = CGSize(width: rasterSize.width, height: rasterSize.height)
        let scale = min(
            1,
            CGFloat(rasterSize.width) / renderRect.width,
            CGFloat(rasterSize.height) / renderRect.height
        )

        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: imageSize, format: format)
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

/// UIImageView pinned to PDF's geographic bounds. Updates frame on every
/// camera change so it stays positioned correctly over satellite. Also hosts
/// fiduciary marker subviews during calibration.
final class PDFImageOverlayView: UIImageView {
    let pdfSW: CLLocationCoordinate2D
    let pdfNE: CLLocationCoordinate2D
    /// PDF user space rect that was rasterised into `image` (the crop rect,
    /// or the full media box if there's no crop). Drives the screen ↔ PDF
    /// coordinate conversions used by fiduciary calibration.
    let pdfRenderRect: CGRect

    /// Affine (PDF user-space -> WGS84) for rotation/scale-correct placement.
    /// When set, updateFrame projects page's true corners through it;
    /// nil falls back to axis-aligned lat/lon stretch.
    let placementTransform: AffineTransform2D?

    /// Per-fiduciary marker subviews, indexed by fiduciary id so we can
    /// reposition on layout without rebuilding them.
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
        // MapContainerView flips this on while CalibrationSession is active
        // so taps hit-test this view and we can convert to PDF coords.
        self.isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    /// Backward-compatible entry for the MKMapView path.
    func updateFrame(in mapView: MKMapView) {
        updateFrame(project: { [weak mapView, weak self] coord in
            guard let mapView, let self else { return .zero }
            return mapView.convert(coord, toPointTo: self.superview ?? mapView)
        }, headingDegrees: mapView.camera.heading)
    }

    /// Pin image to PDF's geographic bounds. Projects coordinates through
    /// `project` (into the superview's coord space), uses all four corners to
    /// derive a rotation-invariant size, then re-applies map heading so the
    /// image spins with the map instead of collapsing into an axis-aligned bbox.
    /// No MapKit dependency of its own, so it runs on MKMapView or TileMapView.
    func updateFrame(project: (CLLocationCoordinate2D) -> CGPoint, headingDegrees: Double) {
        // Preferred path: place via the embedded affine so the sheet sits at its
        // true rotation (grid convergence) + scale and lines up with the MGRS
        // grid. Falls through to the lat/lon stretch when there's no affine.
        if let t = placementTransform, applyAffinePlacement(t, project: project) {
            return
        }

        // Build the four geographic corners of the rect (not just SW & NE).
        let nw = CLLocationCoordinate2D(latitude: pdfNE.latitude, longitude: pdfSW.longitude)
        let ne = pdfNE
        let sw = pdfSW

        let nwPt = project(nw)
        let nePt = project(ne)
        let swPt = project(sw)

        // Side lengths in screen space, invariant under rotation
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
        let centreScreen = project(centreGeo)

        // Sizing without rotation (transform identity first so .bounds writes
        // are interpreted in screen-aligned coords).
        self.transform = .identity
        self.bounds = CGRect(x: 0, y: 0, width: width, height: height)
        self.center = centreScreen

        // Then rotate to match the map heading.
        // heading=0 (north-up) = identity.
        // heading=90 (east-up) = image visually rotated 90 deg clockwise on screen
        // (positive rotationAngle in UIKit = clockwise because y-down).
        if abs(headingDegrees) > 0.001 {
            self.transform = CGAffineTransform(rotationAngle: headingDegrees * .pi / 180)
        }
    }

    /// Place page using the embedded PDF->WGS84 affine: map crop's four true
    /// corners to geo points, project to screen, then size + rotate the
    /// axis-aligned image rect onto that quad. Projected corners already fold
    /// in camera heading so no seperate heading term needed. Returns false on
    /// degenerate projection so updateFrame can fall back to lat/lon stretch.
    private func applyAffinePlacement(_ t: AffineTransform2D,
                                      project: (CLLocationCoordinate2D) -> CGPoint) -> Bool {
        let r = pdfRenderRect
        // The image is rasterised from the crop with a Y-flip, so image-top maps
        // to crop-top (maxY) and image-bottom to crop-bottom (minY).
        let pTL = project(t.apply(CGPoint(x: r.minX, y: r.maxY)))
        let pTR = project(t.apply(CGPoint(x: r.maxX, y: r.maxY)))
        let pBL = project(t.apply(CGPoint(x: r.minX, y: r.minY)))
        let pBR = project(t.apply(CGPoint(x: r.maxX, y: r.minY)))

        guard let placement = pdfAffineOverlayPlacement(
            topLeft: pTL,
            topRight: pTR,
            bottomLeft: pBL,
            bottomRight: pBR
        ) else { return false }
        self.isHidden = false
        self.transform = .identity
        self.bounds = CGRect(origin: .zero, size: placement.boundsSize)
        self.center = placement.center
        self.transform = placement.transform
        return true
    }

    // MARK: - Tap ↔ PDF coordinate conversion

    /// Convert a tap (in mapView's coord space) to PDF user space point
    /// (y-up, origin = bottom-left of pdfRenderRect). nil if tap is outside
    /// the rendered image.
    func pdfPoint(forScreenTap tap: CGPoint, in mapView: MKMapView) -> CGPoint? {
        pdfPoint(forScreenTap: tap, inView: mapView)
    }

    /// Same, but takes the tap in any host view's coord space (the MapKit-free
    /// renderer passes its TileMapView here instead of an MKMapView).
    func pdfPoint(forScreenTap tap: CGPoint, inView host: UIView) -> CGPoint? {
        let local = self.convert(tap, from: host)
        guard self.bounds.contains(local) else { return nil }

        // Image fills bounds (scaleToFill). View-local x/y map linearly to
        // image pixels, which in turn map to `pdfRenderRect` with a Y flip.
        let fracX = local.x / bounds.width
        let fracY = local.y / bounds.height
        let pdfX  = pdfRenderRect.minX + fracX * pdfRenderRect.width
        let pdfY  = pdfRenderRect.maxY - fracY * pdfRenderRect.height
        return CGPoint(x: pdfX, y: pdfY)
    }

    /// Inverse of the above. PDF point to view-local, used to place markers.
    func localPoint(forPDFPoint p: CGPoint) -> CGPoint {
        let fracX = (p.x - pdfRenderRect.minX) / pdfRenderRect.width
        let fracY = (pdfRenderRect.maxY - p.y) / pdfRenderRect.height
        return CGPoint(x: fracX * bounds.width, y: fracY * bounds.height)
    }

    // MARK: - Fiduciary markers

    /// Sync marker subviews with fiduciaries + pending tap. Cheap to call
    /// on every layout change.
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
