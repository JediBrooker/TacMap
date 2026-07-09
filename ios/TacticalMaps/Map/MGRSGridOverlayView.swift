import UIKit
import MapKit
import MGRS

/// Draws MGRS grid (lines + labels) as a transparent subview above the
/// imported-PDF image view.
///
/// Needed b/c iOS 26 MapKit won't draw MKOverlays over satellite imagery
/// (see syncPDFOverlay) so overlay-based grid lines end up underneath the
/// PDF and disappear. We sidestep this by projecting grid coords to screen
/// points via MKMapView.convert on every camera change and stroking with
/// Core Graphics. Mirrors the Android Compose-canvas grid overlay.
///
/// Only used while a PDF is active - plain basemap uses the cheaper
/// MKOverlay/annotation renderer.
final class MGRSGridOverlayView: UIView {

    private struct Line {
        let a: CLLocationCoordinate2D
        let b: CLLocationCoordinate2D
        let gridType: GridType
    }

    private weak var mapView: MKMapView?
    private var lines: [Line] = []
    private var labels: [MGRSGridRenderer.LabelMark] = []

    init(mapView: MKMapView) {
        self.mapView = mapView
        super.init(frame: mapView.bounds)
        backgroundColor = .clear
        isOpaque = false
        // Taps fall through to the map for pan / zoom / draw.
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentMode = .redraw
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    /// Replace grid geometry when visible cells change (pan/zoom into new
    /// bucket). Screen projection is re-derived in draw() so this just
    /// controls *which* lines exist.
    func update(lines builtLines: [MGRSGridRenderer.LineSegment],
                labels builtLabels: [MGRSGridRenderer.LabelMark]) {
        lines = builtLines.map { seg in
            var pts = [CLLocationCoordinate2D(latitude: 0, longitude: 0),
                       CLLocationCoordinate2D(latitude: 0, longitude: 0)]
            seg.polyline.getCoordinates(&pts, range: NSRange(location: 0, length: 2))
            return Line(a: pts[0], b: pts[1], gridType: seg.gridType)
        }
        labels = builtLabels
        setNeedsDisplay()
    }

    /// Re-project against current camera. Cheap, called on every camera
    /// change; geometry is unchanged, only screen position moves.
    func reproject() { setNeedsDisplay() }

    func clear() {
        guard !lines.isEmpty || !labels.isEmpty else { return }
        lines = []
        labels = []
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard let mv = mapView, let ctx = UIGraphicsGetCurrentContext() else { return }

        ctx.setStrokeColor(MGRSGridRenderer.inkColor.cgColor)
        ctx.setLineCap(.round)
        for line in lines {
            let p1 = mv.convert(line.a, toPointTo: self)
            let p2 = mv.convert(line.b, toPointTo: self)
            ctx.setLineWidth(MGRSGridRenderer.lineWidth(for: line.gridType))
            ctx.beginPath()
            ctx.move(to: p1)
            ctx.addLine(to: p2)
            ctx.strokePath()
        }

        // Dark-grey bold text + white halo. Vertical (easting) labels
        // rotated -90 so they run along the line.
        for mark in labels {
            let pt = mv.convert(mark.coordinate, toPointTo: self)
            drawLabel(mark, at: pt, in: ctx)
        }
    }

    private func drawLabel(_ mark: MGRSGridRenderer.LabelMark, at pt: CGPoint, in ctx: CGContext) {
        let font = UIFont.systemFont(ofSize: MGRSGridRenderer.labelFontSize(for: mark.gridType), weight: .bold)
        let text = mark.text as NSString
        let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: MGRSGridRenderer.labelTextColor]
        let halo: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(white: 1, alpha: 0.9)]
        let size = text.size(withAttributes: base)

        ctx.saveGState()
        ctx.translateBy(x: pt.x, y: pt.y)
        if mark.isVertical { ctx.rotate(by: -.pi / 2) }
        let origin = CGPoint(x: -size.width / 2, y: -size.height / 2)
        let o: CGFloat = 1
        for dx in [-o, o] {
            for dy in [-o, o] {
                text.draw(at: CGPoint(x: origin.x + dx, y: origin.y + dy), withAttributes: halo)
            }
        }
        text.draw(at: origin, withAttributes: base)
        ctx.restoreGState()
    }
}
