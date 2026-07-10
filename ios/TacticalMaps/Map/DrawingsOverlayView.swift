import UIKit
import MapKit

/// Single vector shape (drawing, in-progress sketch, or measure line)
/// to redraw above the imported PDF.
struct PDFVectorShape {
    let coords: [CLLocationCoordinate2D]
    let isPolygon: Bool
    let style: DrawingStyle
    let isSelected: Bool
    /// In-progress sketches render dashed regardless of the saved dash pattern.
    let inProgress: Bool
}

/// Transparent subview that strokes/fills drawing/measure/in-progress shapes in
/// Core Graphics, projecting each coordinate through a `project` closure. Pure
/// renderer - no MapKit dependency of its own, so it runs on either the old
/// MKMapView (PDF path) or the new TileMapView.
///
/// (Originally added because MKPolyline/MKPolygon overlays render BENEATH the
/// imported-PDF UIImageView; now it's also the drawings renderer for the
/// MapKit-free renderer.)
final class DrawingsOverlayView: UIView {

    /// Projects a WGS84 coordinate to a point in THIS view. Set by the host.
    var project: ((CLLocationCoordinate2D) -> CGPoint)?
    private var shapes: [PDFVectorShape] = []

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false   // taps fall through to the map
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentMode = .redraw
    }

    /// Backward-compatible convenience for the MKMapView PDF path.
    convenience init(mapView: MKMapView) {
        self.init()
        frame = mapView.bounds
        project = { [weak mapView, weak self] coord in
            guard let mapView, let self else { return .zero }
            return mapView.convert(coord, toPointTo: self)
        }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(shapes: [PDFVectorShape]) {
        self.shapes = shapes
        setNeedsDisplay()
    }

    /// Re-project against the current camera (geometry unchanged).
    func reproject() { setNeedsDisplay() }

    func clear() {
        guard !shapes.isEmpty else { return }
        shapes = []
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let project else { return }
        for shape in shapes where shape.coords.count >= 2 {
            let pts = shape.coords.map(project)
            let path = UIBezierPath()
            path.move(to: pts[0])
            for p in pts.dropFirst() { path.addLine(to: p) }
            if shape.isPolygon { path.close() }

            // Fill polygons. Matches MapContainerCoordinator+Rendering -
            // selected fill brightens slightly, capped at 0.6 alpha.
            if shape.isPolygon {
                let fillHex = shape.style.fillColorHex ?? shape.style.strokeColorHex
                let alpha = min(shape.style.fillOpacity * (shape.isSelected ? 1.6 : 1.0), 0.6)
                UIColor(hex: fillHex, alpha: alpha).setFill()
                path.fill()
            }

            // Stroke - selection bumps width +3pt, in-progress is dashed.
            UIColor(hex: shape.style.strokeColorHex).setStroke()
            path.lineWidth = CGFloat(shape.style.strokeWidth) + (shape.isSelected ? 3.0 : 0.0)
            let dash = shape.inProgress ? [6.0, 4.0] : shape.style.dashPattern
            if let dash, !dash.isEmpty {
                path.setLineDash(dash.map { CGFloat($0) }, count: dash.count, phase: 0)
            }
            path.stroke()
        }
    }
}
