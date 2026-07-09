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

/// Transparent subview that redraws drawing/measure/in-progress shapes
/// ABOVE the imported PDF image.
///
/// The problem: MKPolyline/MKPolygon overlays render in MapKit's overlay
/// layer which is BENEATH the PDF UIImageView, so drawings just vanish
/// under the imported map. This view reprojects coords with
/// MKMapView.convert every camera change and strokes/fills in CG to
/// match the overlay renderer. Only used while a PDF is active,
/// plain-basemap path uses the cheaper MKOverlay renderer.
final class DrawingsOverlayView: UIView {

    private weak var mapView: MKMapView?
    private var shapes: [PDFVectorShape] = []

    init(mapView: MKMapView) {
        self.mapView = mapView
        super.init(frame: mapView.bounds)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false   // taps fall through to the map
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentMode = .redraw
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
        guard let mv = mapView else { return }
        for shape in shapes where shape.coords.count >= 2 {
            let pts = shape.coords.map { mv.convert($0, toPointTo: self) }
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
