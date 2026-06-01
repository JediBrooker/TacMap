import MapKit
import UIKit

// MARK: - Screen-space hit testing
//
// Tap / press hit-tests against the tactical-symbol overlay, drawings, and the
// vertex-edit handles. Extracted verbatim from MapContainerView.swift; the pure
// geometry these rely on lives in MapGeometry.
extension MapContainerView.Coordinator {

    /// Hit-test the tactical-symbol overlay using the published
    /// screen positions and per-kind sizes. The overlay itself is
    /// non-interactive (touches pass through to MKMapView so pinch
    /// works), so selection has to happen from here.
    func waypointHitTest(at pt: CGPoint) -> UUID? {
        let positions = mapVM.waypointScreenPositions
        let zoom = mapVM.zoomScaleFactor
        // Most-recent-on-top: walk the waypoints in reverse to
        // match the overlay's draw order.
        for wp in waypointStore.waypoints.reversed() {
            guard let centre = positions[wp.id] else { continue }
            let size = waypointBubbleSize(for: wp, zoomScale: zoom)
            let frame = CGRect(
                x: centre.x - size.width  / 2,
                y: centre.y - size.height / 2,
                width:  size.width,
                height: size.height
            )
            guard frame.contains(pt) else { continue }
            // Control measures: extra alpha-mask test so taps in
            // the transparent corners of a hexagonal/triangle
            // graphic fall through. Military / generic glyphs fill
            // their frame solidly so a rect check is enough.
            if case .controlMeasure(let measure) = wp.kind {
                let local = CGPoint(x: pt.x - frame.minX, y: pt.y - frame.minY)
                let normalized = CGPoint(
                    x: local.x / max(frame.width,  1),
                    y: local.y / max(frame.height, 1)
                )
                if !TacticalControlMeasureAlphaMask.containsInVisibleBounds(
                    measure: measure,
                    rotation: wp.rotation,
                    normalizedPoint: normalized
                ) { continue }
            }
            return wp.id
        }
        return nil
    }

    /// Mirror of TacticalSymbolOverlay.bubbleSize so the tap
    /// hit-test sees the same bubble geometry the overlay draws.
    private func waypointBubbleSize(for wp: Waypoint, zoomScale: CGFloat) -> CGSize {
        switch wp.kind {
        case .controlMeasure:
            let w = max(8, 64 * CGFloat(wp.scaleX) * zoomScale)
            let h = max(8, 64 * CGFloat(wp.scaleY) * zoomScale)
            return CGSize(width: w, height: h)
        case .military:
            return CGSize(width: 44, height: 44)
        case .generic:
            return CGSize(width: 34, height: 34)
        }
    }

    /// True if the press began on (or close to) any vertex-edit
    /// handle annotation. Used by the whole-shape drag gesture to
    /// step aside and let the handle's own drag run instead.
    func pressIsOnVertexHandle(at pt: CGPoint, on mv: MKMapView) -> Bool {
        let tol: CGFloat = 22
        for ann in mv.annotations {
            guard let h = ann as? DrawingVertexHandleAnnotation else { continue }
            let p = mv.convert(h.coordinate, toPointTo: mv)
            if hypot(p.x - pt.x, p.y - pt.y) <= tol { return true }
        }
        return false
    }

    /// Return the midpoint ("+" insertion) handle nearest to the
    /// tap point, or nil if the tap missed all of them. Skips real
    /// vertices so tap-to-insert and tap-on-vertex don't collide.
    func midpointHandleHitTest(at pt: CGPoint, on mv: MKMapView)
        -> DrawingVertexHandleAnnotation?
    {
        let tol: CGFloat = 22
        var best: DrawingVertexHandleAnnotation?
        var bestDist: CGFloat = .infinity
        for ann in mv.annotations {
            guard let h = ann as? DrawingVertexHandleAnnotation, h.isMidpoint else { continue }
            let p = mv.convert(h.coordinate, toPointTo: mv)
            let d = hypot(p.x - pt.x, p.y - pt.y)
            if d <= tol && d < bestDist {
                best = h
                bestDist = d
            }
        }
        return best
    }

    /// Hit-test the visible drawings against a screen-space tap. Returns
    /// the topmost shape within the tap tolerance, or nil. Uses a 20pt
    /// screen-space tolerance so thin strokes still feel tappable.
    func drawingHitTest(at tap: CGPoint, on mv: MKMapView) -> DrawingShape? {
        let tolerance: CGFloat = 20
        for shape in drawingStore.visibleShapes.reversed() {
            let screen = shape.effectiveCoordinates.map {
                mv.convert(CLLocationCoordinate2D(latitude: $0.latitude,
                                                 longitude: $0.longitude),
                           toPointTo: mv)
            }
            switch shape.kind {
            case .point:
                if let p = screen.first,
                   hypot(p.x - tap.x, p.y - tap.y) <= tolerance {
                    return shape
                }
            case .polyline where screen.count >= 2:
                for i in 0 ..< screen.count - 1 {
                    if MapGeometry.distance(from: tap, toSegment: screen[i], screen[i+1]) <= tolerance {
                        return shape
                    }
                }
            case .polygon where screen.count >= 3:
                if MapGeometry.pointInPolygon(tap, vertices: screen) {
                    return shape
                }
                for i in 0 ..< screen.count {
                    let next = screen[(i + 1) % screen.count]
                    if MapGeometry.distance(from: tap, toSegment: screen[i], next) <= tolerance {
                        return shape
                    }
                }
            default:
                continue
            }
        }
        return nil
    }
}
