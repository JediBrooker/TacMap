import UIKit
import CoreLocation

/// Non-interactive drawing decorations pinned to their coordinates and projected
/// through a `project` closure: the tap-point dots shown while drawing/measuring,
/// the name pill under a finished shape, and the pin marker for point drawings.
/// Replaces the MKAnnotation path (DrawingVertexAnnotation / DrawingLabelAnnotation
/// / DrawingPointAnnotation) so these render on the MapKit-free renderer.
final class DrawingDecorationsOverlayView: UIView {

    /// Projects a WGS84 coordinate to a point in THIS view. Set by the host.
    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    /// What to draw. The host rebuilds this from the stores/sessions.
    /// Coords are kept as lat/lon doubles so the model is Equatable without a
    /// retroactive CLLocationCoordinate2D conformance.
    struct Model: Equatable {
        var dots: [Dot] = []
        var labels: [Label] = []
        var pins: [Pin] = []

        struct Dot: Equatable {
            let lat: Double; let lon: Double; let colorHex: String
            var coord: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
        }
        struct Label: Equatable {
            let lat: Double; let lon: Double; let text: String
            var coord: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
        }
        struct Pin: Equatable {
            let lat: Double; let lon: Double; let colorHex: String
            var coord: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
        }
    }

    private var model = Model()
    // Each item's view keeps its anchor coord so reproject can move it.
    private var dotViews: [(coord: CLLocationCoordinate2D, view: UIView)] = []
    private var labelViews: [(coord: CLLocationCoordinate2D, view: UIView)] = []
    private var pinViews: [(coord: CLLocationCoordinate2D, view: UIView)] = []

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(model new: Model) {
        guard new != model else { return }
        model = new
        rebuild()
        reproject()
    }

    func reproject() {
        guard let project else { return }
        for d in dotViews { d.view.center = project(d.coord) }
        for l in labelViews {
            let p = project(l.coord)
            // Pill hangs below the anchor.
            l.view.center = CGPoint(x: p.x, y: p.y + l.view.bounds.height / 2 + 8)
        }
        for p in pinViews {
            let pt = project(p.coord)
            // Pin tip sits on the coord; image is anchored bottom-centre.
            p.view.center = CGPoint(x: pt.x, y: pt.y - p.view.bounds.height / 2)
        }
    }

    func clear() {
        subviews.forEach { $0.removeFromSuperview() }
        dotViews.removeAll(); labelViews.removeAll(); pinViews.removeAll()
    }

    private func rebuild() {
        clear()
        for dot in model.dots {
            let iv = UIImageView(image: Self.dotImage(color: UIColor(hex: dot.colorHex)))
            iv.sizeToFit()
            addSubview(iv)
            dotViews.append((dot.coord, iv))
        }
        for pin in model.pins {
            let iv = UIImageView(image: Self.pinImage(color: UIColor(hex: pin.colorHex)))
            iv.sizeToFit()
            addSubview(iv)
            pinViews.append((pin.coord, iv))
        }
        for label in model.labels {
            let iv = UIImageView(image: Self.labelPill(text: label.text))
            iv.sizeToFit()
            addSubview(iv)
            labelViews.append((label.coord, iv))
        }
    }

    // MARK: - Image builders (match the MKMapView renderers)

    private static func dotImage(color: UIColor) -> UIImage {
        let size = CGSize(width: 12, height: 12)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let cg = ctx.cgContext
            cg.setFillColor(color.cgColor)
            cg.fillEllipse(in: CGRect(x: 1, y: 1, width: 10, height: 10))
            cg.setStrokeColor(UIColor.white.cgColor)
            cg.setLineWidth(1.5)
            cg.strokeEllipse(in: CGRect(x: 1, y: 1, width: 10, height: 10))
        }
    }

    /// Teardrop map pin, tip at bottom-centre, in the shape's stroke colour.
    private static func pinImage(color: UIColor) -> UIImage {
        let size = CGSize(width: 28, height: 38)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let cg = ctx.cgContext
            let headR: CGFloat = 12
            let headC = CGPoint(x: size.width / 2, y: headR + 1)
            // Teardrop: circle head + tapering tail to the tip.
            let path = UIBezierPath()
            let tip = CGPoint(x: size.width / 2, y: size.height - 1)
            let leftTangent = CGPoint(x: headC.x - headR, y: headC.y + headR * 0.5)
            path.move(to: tip)
            path.addQuadCurve(to: leftTangent, controlPoint: CGPoint(x: headC.x - headR, y: size.height * 0.55))
            path.addArc(withCenter: headC, radius: headR,
                        startAngle: .pi - 0.5, endAngle: 0.5, clockwise: false)
            path.addQuadCurve(to: tip, controlPoint: CGPoint(x: headC.x + headR, y: size.height * 0.55))
            path.close()
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 2,
                         color: UIColor.black.withAlphaComponent(0.5).cgColor)
            color.setFill()
            path.fill()
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            UIColor.white.setStroke()
            path.lineWidth = 1.5
            path.stroke()
            // White inner dot.
            UIColor.white.setFill()
            UIBezierPath(ovalIn: CGRect(x: headC.x - 4, y: headC.y - 4, width: 8, height: 8)).fill()
        }
    }

    /// Name pill: same look as the MKMapView `renderLabelPill`.
    private static func labelPill(text: String) -> UIImage {
        let font = UIFont.systemFont(ofSize: 11, weight: .semibold)
        let attrs: [NSAttributedString.Key: Any] = [.font: font]
        let textSize = (text as NSString).size(withAttributes: attrs)
        let padH: CGFloat = 6, padV: CGFloat = 3
        let size = CGSize(width: ceil(textSize.width) + padH * 2,
                          height: ceil(textSize.height) + padV * 2)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let cg = ctx.cgContext
            let rect = CGRect(origin: .zero, size: size).insetBy(dx: 0.5, dy: 0.5)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: 4).cgPath
            cg.addPath(path)
            cg.setFillColor(UIColor.black.withAlphaComponent(0.62).cgColor)
            cg.fillPath()
            cg.addPath(path)
            cg.setStrokeColor(UIColor.white.withAlphaComponent(0.18).cgColor)
            cg.setLineWidth(0.5)
            cg.strokePath()
            let textAttrs: [NSAttributedString.Key: Any] = [
                .font: font,
                .foregroundColor: UIColor.white,
                .shadow: {
                    let s = NSShadow()
                    s.shadowColor = UIColor.black.withAlphaComponent(0.8)
                    s.shadowBlurRadius = 1.5
                    return s
                }()
            ]
            (text as NSString).draw(at: CGPoint(x: padH, y: padV), withAttributes: textAttrs)
        }
    }
}
