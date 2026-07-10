import UIKit
import MapKit
import MGRS

/// Draws MGRS grid (lines + labels) as a transparent subview, projecting each
/// coordinate through a `project` closure and stroking with Core Graphics. Pure
/// renderer - no MapKit tie of its own, so it runs on either the old MKMapView
/// (PDF path) or the new TileMapView. Mirrors the Android Compose-canvas grid.
final class MGRSGridOverlayView: UIView {

    private struct Line {
        let a: CLLocationCoordinate2D
        let b: CLLocationCoordinate2D
        let gridType: GridType
    }

    /// Projects a WGS84 coordinate to a point in THIS view. Set by the host.
    var project: ((CLLocationCoordinate2D) -> CGPoint)?
    private var lines: [Line] = []
    private var labels: [MGRSGridRenderer.LabelMark] = []

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        // Taps fall through to the map for pan / zoom / draw.
        isUserInteractionEnabled = false
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
        guard let project, let ctx = UIGraphicsGetCurrentContext() else { return }

        ctx.setStrokeColor(MGRSGridRenderer.inkColor.cgColor)
        ctx.setLineCap(.round)
        for line in lines {
            let p1 = project(line.a)
            let p2 = project(line.b)
            ctx.setLineWidth(MGRSGridRenderer.lineWidth(for: line.gridType))
            ctx.beginPath()
            ctx.move(to: p1)
            ctx.addLine(to: p2)
            ctx.strokePath()
        }

        // Dark-grey bold text + white halo. Vertical (easting) labels
        // rotated -90 so they run along the line.
        //
        // Declutter to one label per grid LINE: every crossing along a line
        // carries the same value, so the library hands us the same label over
        // and over (worst zoomed out). Bucket vertical labels by screen-x and
        // horizontal by screen-y (a line's perpendicular coordinate is ~constant),
        // and keep the copy nearest the top/left margin so it reads like a proper
        // grid ruler. Distinct lines land in different buckets, so finer grids
        // with unique per-line values are untouched.
        let bucket: CGFloat = 55
        var best: [String: (pt: CGPoint, mark: MGRSGridRenderer.LabelMark)] = [:]
        for mark in labels {
            let pt = project(mark.coordinate)
            let b = Int(((mark.isVertical ? pt.x : pt.y) / bucket).rounded())
            let key = "\(mark.isVertical ? "v" : "h")|\(b)|\(mark.text)"
            let margin = mark.isVertical ? pt.y : pt.x   // smaller = nearer margin
            if let ex = best[key] {
                let exMargin = mark.isVertical ? ex.pt.y : ex.pt.x
                if margin < exMargin { best[key] = (pt, mark) }
            } else {
                best[key] = (pt, mark)
            }
        }
        for (_, v) in best { drawLabel(v.mark, at: v.pt, in: ctx) }
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
