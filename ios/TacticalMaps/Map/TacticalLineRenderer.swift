import MapKit
import UIKit

/// Renders a polyline as a NATO tactical line graphic - crenellated forward
/// line (FLOT/FEBA), boundary with tick marks, or axis of advance with
/// arrowhead. Plain / phase lines just use the stock `MKPolylineRenderer`.
///
/// Decoration sizes are in screen points, divided by `zoomScale` so they
/// stay constant on-screen as the map zooms. Dark halo under the stroke
/// so it reads on any basemap.
final class TacticalLineRenderer: MKOverlayRenderer {

    private let coords: [CLLocationCoordinate2D]
    private let style: DrawingStyle
    private let graphic: LineGraphic
    private let selectionBoost: CGFloat

    init(polyline: MKPolyline, style: DrawingStyle, graphic: LineGraphic, selectionBoost: CGFloat) {
        self.style = style
        self.graphic = graphic
        self.selectionBoost = selectionBoost
        var cs = [CLLocationCoordinate2D](repeating: CLLocationCoordinate2D(), count: polyline.pointCount)
        polyline.getCoordinates(&cs, range: NSRange(location: 0, length: polyline.pointCount))
        self.coords = cs
        super.init(overlay: polyline)
    }

    override func draw(_ mapRect: MKMapRect, zoomScale: MKZoomScale, in ctx: CGContext) {
        guard coords.count >= 2 else { return }
        let z = CGFloat(zoomScale)
        let pts = coords.map { point(for: MKMapPoint($0)) }
        let lw = (CGFloat(style.strokeWidth) + selectionBoost) / z
        let haloW = lw + 3.0 / z
        let color = UIColor(hex: style.strokeColorHex).cgColor
        let halo  = UIColor.black.withAlphaComponent(0.55).cgColor
        ctx.setLineCap(.round); ctx.setLineJoin(.round)

        switch graphic {
        case .forwardEdge:
            stroke(crenellated(pts, period: 22 / z, height: 12 / z), ctx, lw, haloW, color, halo)
        case .boundary:
            stroke(polyPath(pts), ctx, lw, haloW, color, halo)
            stroke(boundaryTicks(pts, spacing: 30 / z, len: 9 / z), ctx, lw, haloW, color, halo)
        case .axisOfAdvance:
            stroke(polyPath(pts), ctx, lw, haloW, color, halo)
            if let head = arrowHead(pts, size: 17 / z) { stroke(head, ctx, lw, haloW, color, halo) }
        case .plain, .phaseLine:
            stroke(polyPath(pts), ctx, lw, haloW, color, halo)
        }
    }

    // MARK: drawing

    private func stroke(_ path: CGPath, _ ctx: CGContext, _ lw: CGFloat, _ haloW: CGFloat, _ color: CGColor, _ halo: CGColor) {
        ctx.addPath(path); ctx.setStrokeColor(halo);  ctx.setLineWidth(haloW); ctx.strokePath()
        ctx.addPath(path); ctx.setStrokeColor(color); ctx.setLineWidth(lw);    ctx.strokePath()
    }

    private func polyPath(_ pts: [CGPoint]) -> CGPath {
        let p = CGMutablePath(); p.addLines(between: pts); return p
    }

    // MARK: geometry

    /// Walk the polyline at `step` intervals, returns point + left-normal + arc length.
    private func sample(_ pts: [CGPoint], step: CGFloat) -> [(p: CGPoint, n: CGVector, s: CGFloat)] {
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
    private func crenellated(_ pts: [CGPoint], period: CGFloat, height: CGFloat) -> CGPath {
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
    private func boundaryTicks(_ pts: [CGPoint], spacing: CGFloat, len: CGFloat) -> CGPath {
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
    private func arrowHead(_ pts: [CGPoint], size: CGFloat) -> CGPath? {
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
