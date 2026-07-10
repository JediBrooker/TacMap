import UIKit
import CoreLocation

/// One vertex-edit handle for the selected polyline/polygon. Real vertices get a
/// solid orange disc (drag to move, long-press to delete); midpoints get a hollow
/// "+" disc (tap or drag to insert). Screen hit-testing + mutation live in
/// `MapEditingController`; this struct is the shared model.
struct EditHandle: Equatable {
    let shapeID: UUID
    /// Real verts: index in the shape. Midpoints: insertion index.
    let vertexIndex: Int
    let isMidpoint: Bool
    let lat: Double
    let lon: Double
    var coord: CLLocationCoordinate2D { .init(latitude: lat, longitude: lon) }
}

/// Renders the edit handles as image subviews projected through a `project`
/// closure. Non-interactive - the gesture layer hit-tests handle screen
/// positions itself. Replaces the DrawingVertexHandleAnnotation views.
final class VertexHandlesOverlayView: UIView {

    var project: ((CLLocationCoordinate2D) -> CGPoint)?

    private var handles: [EditHandle] = []
    private var views: [UIView] = []

    init() {
        super.init(frame: .zero)
        backgroundColor = .clear
        isOpaque = false
        isUserInteractionEnabled = false
        autoresizingMask = [.flexibleWidth, .flexibleHeight]
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func update(handles new: [EditHandle]) {
        guard new != handles else { return }
        handles = new
        views.forEach { $0.removeFromSuperview() }
        views.removeAll()
        for h in handles {
            let iv = UIImageView(image: Self.handleImage(midpoint: h.isMidpoint))
            iv.sizeToFit()
            addSubview(iv)
            views.append(iv)
        }
        reproject()
    }

    func reproject() {
        guard let project else { return }
        for (h, v) in zip(handles, views) { v.center = project(h.coord) }
    }

    /// Move one handle to follow the finger during a drag (the shape edge
    /// itself only snaps once the drag commits, matching the old behaviour).
    func liveMove(handleIndex index: Int, to point: CGPoint) {
        guard views.indices.contains(index) else { return }
        views[index].center = point
    }

    func clear() { update(handles: []) }

    /// Same art as the MKMapView `renderVertexHandle`.
    private static func handleImage(midpoint: Bool) -> UIImage {
        let size = CGSize(width: 26, height: 26)
        let orange = UIColor(red: 1, green: 0.65, blue: 0.18, alpha: 1)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let cg = ctx.cgContext
            let rect = CGRect(x: 3, y: 3, width: 20, height: 20)
            if midpoint {
                cg.setFillColor(UIColor.white.withAlphaComponent(0.85).cgColor)
                cg.fillEllipse(in: rect)
                cg.setStrokeColor(orange.cgColor)
                cg.setLineWidth(2)
                cg.strokeEllipse(in: rect)
                cg.setStrokeColor(orange.cgColor)
                cg.setLineWidth(2.5)
                cg.move(to: CGPoint(x: 13, y: 8));  cg.addLine(to: CGPoint(x: 13, y: 18))
                cg.move(to: CGPoint(x:  8, y: 13)); cg.addLine(to: CGPoint(x: 18, y: 13))
                cg.strokePath()
            } else {
                cg.setFillColor(orange.cgColor)
                cg.fillEllipse(in: rect)
                cg.setStrokeColor(UIColor.white.cgColor)
                cg.setLineWidth(2)
                cg.strokeEllipse(in: rect)
            }
        }
    }
}
