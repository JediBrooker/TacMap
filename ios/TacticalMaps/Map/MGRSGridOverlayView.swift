import UIKit
import CoreLocation

/// Draws the MGRS grid (lines + labels) as a transparent subview above the
/// basemap / PDF, projecting cached geometry through the live camera every
/// frame. Geometry comes from MGRSGridRenderer.build (built off the main
/// queue by the container), labels are placed per frame against the visible
/// viewport. Mirrors Android's MgrsGridCanvas.
final class MGRSGridOverlayView: UIView {

    /// The camera to project with. Set by the host.
    var camera: (() -> MapCamera?)?

    private(set) var grid: MGRSGridRenderer.Grid = .empty
    /// text sizes barely change (2-char labels), no point re-measuring each frame
    private var textSizes: [String: CGSize] = [:]

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        // Taps fall through to the map for pan / zoom / draw.
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        contentMode = .redraw
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    /// Swap in freshly built geometry.
    func update(grid newGrid: MGRSGridRenderer.Grid) {
        grid = newGrid
        setNeedsDisplay()
    }

    /// Re-project against the current camera. Cheap, called on every camera
    /// change; geometry is unchanged, only screen positions move.
    func reproject() {
        guard !grid.isEmpty else { return }
        setNeedsDisplay()
    }

    func clear() {
        guard !grid.isEmpty else { return }
        grid = .empty
        setNeedsDisplay()
    }

    override func draw(_ rect: CGRect) {
        guard !grid.isEmpty, let camera = camera?(), let ctx = UIGraphicsGetCurrentContext() else { return }
        let proj = MGRSGridRenderer.ScreenProjection(camera: camera)
        // anything whose box misses the (rotated) viewport is skipped, a few pt
        // of slack so a thick line on the edge still draws
        let visible = proj.localBounds(of: bounds.insetBy(dx: -4, dy: -4))

        ctx.saveGState()
        ctx.setStrokeColor(MGRSGridRenderer.inkColor.cgColor)
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)
        // fine first so the heavier lines sit on top. One path per level, so
        // there's no double-inked overlap at piece joins
        for level in MGRSGridRenderer.Level.allCases.reversed() {
            let path = CGMutablePath()
            for piece in grid.pieces where piece.level == level {
                guard piece.points.count >= 2, proj.localBounds(of: piece.bounds).intersects(visible) else { continue }
                path.move(to: proj.screen(piece.points[0]))
                for p in piece.points.dropFirst() { path.addLine(to: proj.screen(p)) }
            }
            guard !path.isEmpty else { continue }
            ctx.setLineWidth(MGRSGridRenderer.appliedLineWidth(for: level, screenScale: contentScaleFactor))
            ctx.addPath(path)
            ctx.strokePath()
        }
        ctx.restoreGState()

        let labels = MGRSGridRenderer.layoutLabels(grid, camera: camera) { text, level in
            self.textSize(text, level: level)
        }
        for label in labels { drawLabel(label, in: ctx) }
    }

    private func textSize(_ text: String, level: MGRSGridRenderer.Level) -> CGSize {
        let key = "\(level.rawValue)|\(text)"
        if let s = textSizes[key] { return s }
        let s = MGRSGridRenderer.labelTextSize(text, level: level)
        textSizes[key] = s
        return s
    }

    /// Dark-grey bold text with a white halo. Easting labels rotated -90 so
    /// they run along the line.
    private func drawLabel(_ label: MGRSGridRenderer.PlacedLabel, in ctx: CGContext) {
        let font = MGRSGridRenderer.labelFont(for: label.level)
        let text = label.text as NSString
        let base: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: MGRSGridRenderer.labelTextColor]
        let halo: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: UIColor(white: 1, alpha: 0.9)]
        let size = textSize(label.text, level: label.level)

        ctx.saveGState()
        ctx.translateBy(x: label.anchor.x, y: label.anchor.y)
        if label.isVertical { ctx.rotate(by: -.pi / 2) }
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
