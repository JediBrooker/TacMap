import CoreGraphics
import Foundation

/// Pure screen-space + zoom geometry pulled out of MapContainerView.Coordinator
/// so it can be unit-tested without MKMapView. Plain values only, nothing
/// touches MapKit or app model types.
enum MapGeometry {

    /// Shortest distance from point `p` to segment `a`-`b` (screen coords).
    static func distance(from p: CGPoint, toSegment a: CGPoint, _ b: CGPoint) -> CGFloat {
        let dx = b.x - a.x, dy = b.y - a.y
        let l2 = dx * dx + dy * dy
        if l2 == 0 { return hypot(p.x - a.x, p.y - a.y) }
        var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / l2
        t = max(0, min(1, t))
        let projX = a.x + t * dx, projY = a.y + t * dy
        return hypot(p.x - projX, p.y - projY)
    }

    /// Ray-casting point-in-polygon test on screen coordinates.
    static func pointInPolygon(_ p: CGPoint, vertices: [CGPoint]) -> Bool {
        guard vertices.count >= 3 else { return false }
        var inside = false
        var j = vertices.count - 1
        for i in 0 ..< vertices.count {
            let vi = vertices[i], vj = vertices[j]
            if ((vi.y > p.y) != (vj.y > p.y)) &&
               (p.x < (vj.x - vi.x) * (p.y - vi.y) / (vj.y - vi.y) + vi.x) {
                inside.toggle()
            }
            j = i
        }
        return inside
    }

    /// Metres-per-point at current camera. latitudeDelta in degrees,
    /// viewHeightPoints in points (clamped to >=1). 111k m per degree lat.
    static func metresPerPoint(latitudeDelta: Double, viewHeightPoints: Double) -> Double {
        let latDeltaMetres = latitudeDelta * 111_000
        let viewHeight = max(viewHeightPoints, 1)
        return latDeltaMetres / viewHeight
    }

    /// Unit zoom scale, 1.0 = reference zoom. Halving mpp (zoom in) gives
    /// 2.0, doubling (zoom out) gives 0.5. Clamped [0.005, 50] so symbols
    /// stay visible from building-level to continental.
    static func zoomScaleFactor(metresPerPoint mpp: Double, reference: Double) -> CGFloat {
        let raw = reference / mpp
        return CGFloat(max(0.005, min(raw, 50.0)))
    }
}
