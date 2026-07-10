import Foundation
import CoreLocation
import CoreGraphics

/// The viewing state of the custom map: where it's centred, how far in, which
/// way it's turned, and how big the view is. This is the single source of the
/// coord<->screen projection that every overlay reads, replacing MKMapView's
/// `convert(_:toPointTo:)` / `convert(_:toCoordinateFrom:)`.
///
/// `heading` is the map's clockwise rotation in degrees: 0 = north up. Positive
/// turns the map clockwise on screen (so content appears to rotate
/// anticlockwise), matching the existing rotate-gesture behaviour.
///
/// A value type on purpose: gestures produce a new camera, the view diffs and
/// redraws. `screenPoint(for:)` and `coordinate(for:)` are exact inverses for
/// any heading, which is the property the whole overlay stack depends on.
struct MapCamera: Equatable {
    var center: CLLocationCoordinate2D
    var zoom: Double
    var headingDegrees: Double
    var viewportSize: CGSize

    static func == (a: MapCamera, b: MapCamera) -> Bool {
        a.center.latitude == b.center.latitude &&
        a.center.longitude == b.center.longitude &&
        a.zoom == b.zoom &&
        a.headingDegrees == b.headingDegrees &&
        a.viewportSize == b.viewportSize
    }

    private var viewportCenter: CGPoint {
        CGPoint(x: viewportSize.width / 2, y: viewportSize.height / 2)
    }

    /// Coordinate -> screen point (points, origin top-left).
    func screenPoint(for coord: CLLocationCoordinate2D) -> CGPoint {
        let w = WebMercator.worldPoint(coord, zoom: zoom)
        let c = WebMercator.worldPoint(center, zoom: zoom)
        let d = rotate(dx: w.x - c.x, dy: w.y - c.y, byDegrees: -headingDegrees)
        return CGPoint(x: viewportCenter.x + d.dx, y: viewportCenter.y + d.dy)
    }

    /// Screen point -> coordinate. Inverse of `screenPoint(for:)`.
    func coordinate(for screen: CGPoint) -> CLLocationCoordinate2D {
        let px = Double(screen.x - viewportCenter.x)
        let py = Double(screen.y - viewportCenter.y)
        let d = rotate(dx: px, dy: py, byDegrees: headingDegrees)
        let c = WebMercator.worldPoint(center, zoom: zoom)
        return WebMercator.coordinate(fromWorld: CGPoint(x: c.x + d.dx, y: c.y + d.dy), zoom: zoom)
    }

    /// Metres per screen point at the current centre + zoom.
    var metresPerPoint: Double {
        WebMercator.groundResolution(latitude: center.latitude, zoom: zoom)
    }

    private func rotate(dx: Double, dy: Double, byDegrees deg: Double) -> (dx: Double, dy: Double) {
        if deg == 0 { return (dx, dy) }
        let r = deg * .pi / 180
        return (dx * cos(r) - dy * sin(r), dx * sin(r) + dy * cos(r))
    }
}
