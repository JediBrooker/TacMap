import UIKit
import CoreLocation

/// Calibration point markers (s7.5), drawn at displayedGeoref.toWGS84(page),
/// never at the typed position. A "+" right on the point and the number badge
/// off to the upper right on a leader so it never hides the intersection.
/// Taps fall through; MapEditingController asks pointID(at:) for selection.
final class CalibrationMarkersOverlayView: UIView {

    struct Marker: Equatable {
        var id: UUID
        var number: Int
        var page: PdfPagePoint
        var flagged: Bool
        /// WGS84 of what was typed, for the residual line
        var typed: CLLocationCoordinate2D?

        static func == (a: Marker, b: Marker) -> Bool {
            a.id == b.id && a.number == b.number && a.page == b.page && a.flagged == b.flagged
                && a.typed?.latitude == b.typed?.latitude && a.typed?.longitude == b.typed?.longitude
        }
    }

    struct Model: Equatable {
        var georef: PdfGeoreference?
        var generation = 0
        var markers: [Marker] = []
        var pending: PdfPagePoint?
        var movingID: UUID?
        var selectedID: UUID?
        var showResiduals = false
    }

    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    private var model = Model()
    /// toWGS84 per marker, cached for the generation so a frame is just projection
    private var wgs: [UUID: CLLocationCoordinate2D] = [:]
    private var pendingWGS: CLLocationCoordinate2D?
    private var cachedGeneration = -1

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = false
        backgroundColor = .clear
        isUserInteractionEnabled = false
        contentMode = .redraw
    }

    required init?(coder: NSCoder) { nil }

    func update(_ m: Model) {
        guard m != model else { return }
        let regen = m.generation != cachedGeneration || m.markers.map(\.page) != model.markers.map(\.page)
            || m.pending != model.pending
        model = m
        if regen { recache() }
        setNeedsDisplay()
    }

    func clear() { update(Model()) }

    func reproject() {
        guard model.georef != nil else { return }
        setNeedsDisplay()
    }

    private func recache() {
        cachedGeneration = model.generation
        wgs = [:]
        pendingWGS = nil
        guard let g = model.georef else { return }
        for m in model.markers {
            wgs[m.id] = g.toWGS84(x: m.page.x, y: m.page.y)
        }
        if let p = model.pending { pendingWGS = g.toWGS84(x: p.x, y: p.y) }
    }

    /// marker under a tap, nearest within radius
    func pointID(at pt: CGPoint, radius: CGFloat = CGFloat(CalibrationLimits.markerHitRadiusPt)) -> UUID? {
        guard let project else { return nil }
        var best: (UUID, CGFloat)?
        for m in model.markers {
            guard let c = wgs[m.id] else { continue }
            let s = project(c)
            let d = hypot(s.x - pt.x, s.y - pt.y)
            if d <= radius, best == nil || d < best!.1 { best = (m.id, d) }
        }
        return best?.0
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext(), let project, model.georef != nil else { return }
        // residual lines first, under everything
        if model.showResiduals {
            for m in model.markers {
                guard let c = wgs[m.id], let t = m.typed else { continue }
                let a = project(c), b = project(t)
                guard hypot(a.x - b.x, a.y - b.y) >= CGFloat(CalibrationLimits.residualLineMinScreenPt) else { continue }
                ctx.setLineWidth(3)
                ctx.setStrokeColor(UIColor.black.withAlphaComponent(0.6).cgColor)
                ctx.strokeLineSegments(between: [a, b])
                ctx.setLineWidth(1.5)
                ctx.setStrokeColor((m.flagged ? UIColor.systemRed : UIColor.systemYellow).cgColor)
                ctx.strokeLineSegments(between: [a, b])
                ctx.setFillColor((m.flagged ? UIColor.systemRed : UIColor.systemYellow).cgColor)
                ctx.fillEllipse(in: CGRect(x: b.x - 2.5, y: b.y - 2.5, width: 5, height: 5))
            }
        }
        for m in model.markers {
            guard let c = wgs[m.id] else { continue }
            let p = project(c)
            let moving = model.movingID == m.id
            drawCross(ctx, at: p, alpha: moving ? 0.45 : 1)
            if model.selectedID == m.id && !moving {
                ctx.setLineWidth(4)
                ctx.setStrokeColor(UIColor.black.withAlphaComponent(0.7).cgColor)
                ctx.strokeEllipse(in: CGRect(x: p.x - 13, y: p.y - 13, width: 26, height: 26))
                ctx.setLineWidth(2)
                ctx.setStrokeColor(UIColor.white.cgColor)
                ctx.strokeEllipse(in: CGRect(x: p.x - 13, y: p.y - 13, width: 26, height: 26))
            }
            drawBadge(ctx, at: p, number: m.number, flagged: m.flagged, alpha: moving ? 0.45 : 1)
        }
        if let pw = pendingWGS {
            let p = project(pw)
            ctx.saveGState()
            ctx.setLineWidth(2)
            ctx.setLineDash(phase: 0, lengths: [4, 3])
            ctx.setStrokeColor(UIColor.white.cgColor)
            ctx.strokeEllipse(in: CGRect(x: p.x - 12, y: p.y - 12, width: 24, height: 24))
            ctx.restoreGState()
            drawCross(ctx, at: p, alpha: 1)
        }
    }

    private func drawCross(_ ctx: CGContext, at p: CGPoint, alpha: CGFloat) {
        let arm: CGFloat = 8
        let segs = [CGPoint(x: p.x - arm, y: p.y), CGPoint(x: p.x - 2, y: p.y),
                    CGPoint(x: p.x + 2, y: p.y), CGPoint(x: p.x + arm, y: p.y),
                    CGPoint(x: p.x, y: p.y - arm), CGPoint(x: p.x, y: p.y - 2),
                    CGPoint(x: p.x, y: p.y + 2), CGPoint(x: p.x, y: p.y + arm)]
        ctx.setLineWidth(3.5)
        ctx.setStrokeColor(UIColor.black.withAlphaComponent(0.8 * alpha).cgColor)
        ctx.strokeLineSegments(between: segs)
        ctx.setLineWidth(1.5)
        ctx.setStrokeColor(UIColor.white.withAlphaComponent(alpha).cgColor)
        ctx.strokeLineSegments(between: segs)
    }

    private func drawBadge(_ ctx: CGContext, at p: CGPoint, number: Int, flagged: Bool, alpha: CGFloat) {
        let centre = CGPoint(x: p.x + 20, y: p.y - 20)
        ctx.setLineWidth(1.5)
        ctx.setStrokeColor(UIColor.white.withAlphaComponent(0.9 * alpha).cgColor)
        ctx.strokeLineSegments(between: [CGPoint(x: p.x + 4, y: p.y - 4), CGPoint(x: centre.x - 7, y: centre.y + 7)])
        // "!" next to the number on a flagged point, a glyph not just a colour
        let text = flagged ? "\(number)!" : "\(number)"
        let font = UIFont.systemFont(ofSize: 13, weight: .heavy)
        let attrs: [NSAttributedString.Key: Any] = [.font: font,
                                                    .foregroundColor: (flagged ? UIColor.white : UIColor.black).withAlphaComponent(alpha)]
        let size = (text as NSString).size(withAttributes: attrs)
        let w = max(22, size.width + 10), h: CGFloat = 22
        let r = CGRect(x: centre.x - w / 2, y: centre.y - h / 2, width: w, height: h)
        let path = UIBezierPath(roundedRect: r, cornerRadius: h / 2)
        ctx.setFillColor((flagged ? UIColor.systemRed : UIColor(red: 1, green: 0.65, blue: 0.18, alpha: 1)).withAlphaComponent(alpha).cgColor)
        ctx.addPath(path.cgPath)
        ctx.fillPath()
        ctx.setStrokeColor(UIColor.white.withAlphaComponent(alpha).cgColor)
        ctx.setLineWidth(1.5)
        ctx.addPath(path.cgPath)
        ctx.strokePath()
        (text as NSString).draw(at: CGPoint(x: centre.x - size.width / 2, y: centre.y - size.height / 2), withAttributes: attrs)
    }
}
