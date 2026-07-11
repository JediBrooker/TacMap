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
        guard let project, let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.setLineCap(.round); ctx.setLineJoin(.round)
        for shape in shapes where shape.coords.count >= 2 {
            let pts = shape.coords.map(project)
            let lw = CGFloat(shape.style.strokeWidth) + (shape.isSelected ? 3.0 : 0.0)
            let color = UIColor(hex: shape.style.strokeColorHex)

            if shape.isPolygon {
                let path = UIBezierPath()
                path.move(to: pts[0])
                for p in pts.dropFirst() { path.addLine(to: p) }
                path.close()
                // Fill - selected fill brightens slightly, capped at 0.6 alpha.
                let fillHex = shape.style.fillColorHex ?? shape.style.strokeColorHex
                let alpha = min(shape.style.fillOpacity * (shape.isSelected ? 1.6 : 1.0), 0.6)
                UIColor(hex: fillHex, alpha: alpha).setFill()
                path.fill()
                color.setStroke()
                path.lineWidth = lw
                if let dash = shape.style.dashPattern, !dash.isEmpty {
                    path.setLineDash(dash.map { CGFloat($0) }, count: dash.count, phase: 0)
                }
                path.stroke()
                continue
            }

            // Polylines: decorated NATO tactical graphics or a plain line.
            // In-progress sketches ignore the graphic and just draw dashed.
            let graphic: LineGraphic = shape.inProgress ? .plain : (shape.style.lineGraphic ?? .plain)
            switch graphic {
            case .forwardEdge:
                strokeDecorated(Self.crenellated(pts, period: 22, height: 12), ctx, lw, color)
            case .boundary:
                strokeDecorated(Self.polyPath(pts), ctx, lw, color)
                strokeDecorated(Self.boundaryTicks(pts, spacing: 30, len: 9), ctx, lw, color)
            case .axisOfAdvance:
                strokeDecorated(Self.polyPath(pts), ctx, lw, color)
                if let head = Self.arrowHead(pts, size: 17) {
                    strokeDecorated(head, ctx, lw, color)
                }
            case .plain, .phaseLine:
                let path = UIBezierPath()
                path.move(to: pts[0])
                for p in pts.dropFirst() { path.addLine(to: p) }
                path.lineWidth = lw
                // Phase line = dashed control line; else honour the saved dash.
                let dash: [Double]? = shape.inProgress ? [6, 4]
                    : (graphic == .phaseLine ? [10, 6] : shape.style.dashPattern)
                if let dash, !dash.isEmpty {
                    path.setLineDash(dash.map { CGFloat($0) }, count: dash.count, phase: 0)
                }
                color.setStroke()
                path.stroke()
            }
        }
    }

    /// Dark halo under the coloured stroke so decorated graphics read on any
    /// basemap - mirrors TacticalLineRenderer's two-pass stroke.
    private func strokeDecorated(_ path: CGPath, _ ctx: CGContext, _ lw: CGFloat, _ color: UIColor) {
        ctx.addPath(path)
        ctx.setStrokeColor(UIColor.black.withAlphaComponent(0.55).cgColor)
        ctx.setLineWidth(lw + 3)
        ctx.strokePath()
        ctx.addPath(path)
        ctx.setStrokeColor(color.cgColor)
        ctx.setLineWidth(lw)
        ctx.strokePath()
    }

    // MARK: - Tactical line geometry (screen space; sizes are constant points)

    private static func polyPath(_ pts: [CGPoint]) -> CGPath {
        let p = CGMutablePath(); p.addLines(between: pts); return p
    }

    /// Walk the polyline at `step` intervals: point + left-normal + arc length.
    private static func sample(_ pts: [CGPoint], step: CGFloat) -> [(p: CGPoint, n: CGVector, s: CGFloat)] {
        var out: [(CGPoint, CGVector, CGFloat)] = []
        var s: CGFloat = 0
        for i in 0..<(pts.count - 1) {
            let a = pts[i], b = pts[i + 1]
            let dx = b.x - a.x, dy = b.y - a.y
            let len = max(hypot(dx, dy), 0.0001)
            let ux = dx / len, uy = dy / len
            let n = CGVector(dx: -uy, dy: ux)
            var t: CGFloat = 0
            while t < len {
                out.append((CGPoint(x: a.x + ux * t, y: a.y + uy * t), n, s + t))
                t += step
            }
            s += len
        }
        out.append((pts.last!, CGVector(dx: 0, dy: 0), s))
        return out
    }

    /// Square-wave (battlement) path - the FLOT/FEBA forward-line graphic.
    private static func crenellated(_ pts: [CGPoint], period: CGFloat, height: CGFloat) -> CGPath {
        let samp = sample(pts, step: max(1, period / 8))
        let half = period / 2
        let p = CGMutablePath()
        var started = false
        for (pt, n, s) in samp {
            let raised = Int(s / half) % 2 == 1
            let off = raised ? height : 0
            let q = CGPoint(x: pt.x + n.dx * off, y: pt.y + n.dy * off)
            if started { p.addLine(to: q) } else { p.move(to: q); started = true }
        }
        return p
    }

    /// Perpendicular tick marks at intervals - boundary line decoration.
    private static func boundaryTicks(_ pts: [CGPoint], spacing: CGFloat, len: CGFloat) -> CGPath {
        let samp = sample(pts, step: max(1, spacing / 6))
        let p = CGMutablePath()
        var next: CGFloat = spacing
        for (pt, n, s) in samp where s >= next {
            p.move(to: CGPoint(x: pt.x + n.dx * len, y: pt.y + n.dy * len))
            p.addLine(to: CGPoint(x: pt.x - n.dx * len, y: pt.y - n.dy * len))
            next += spacing
        }
        return p
    }

    /// Two strokes forming an arrowhead at the polyline's end.
    private static func arrowHead(_ pts: [CGPoint], size: CGFloat) -> CGPath? {
        guard let tip = pts.last, pts.count >= 2 else { return nil }
        let prev = pts[pts.count - 2]
        let ang = atan2(tip.y - prev.y, tip.x - prev.x)
        let p = CGMutablePath()
        for da in [CGFloat.pi * 0.83, -CGFloat.pi * 0.83] {
            p.move(to: tip)
            p.addLine(to: CGPoint(x: tip.x + cos(ang + da) * size,
                                  y: tip.y + sin(ang + da) * size))
        }
        return p
    }
}
