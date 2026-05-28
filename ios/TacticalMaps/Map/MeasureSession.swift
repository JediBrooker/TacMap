import Foundation
import CoreLocation
import Combine

/// State machine for the measure tool. When `isActive` is true the bottom HUD
/// swaps to `MeasureToolbar`, taps on the map add vertices, and the in-progress
/// polyline renders alongside drawings (without ever being persisted).
///
/// Distances use the haversine formula via `CLLocation.distance(from:)`;
/// areas use the spherical-excess approximation, fine for the polygon
/// sizes the user is likely to draw on a tactical map.
final class MeasureSession: ObservableObject {
    @Published private(set) var isActive: Bool = false
    @Published private(set) var points: [CLLocationCoordinate2D] = []

    func start() {
        isActive = true
        points = []
    }

    func cancel() {
        isActive = false
        points = []
    }

    func addPoint(_ c: CLLocationCoordinate2D) {
        points.append(c)
    }

    func undo() {
        guard !points.isEmpty else { return }
        points.removeLast()
    }

    /// Sum of haversine distances between consecutive points, in metres.
    var totalDistanceMeters: Double {
        guard points.count >= 2 else { return 0 }
        var total = 0.0
        for i in 0 ..< points.count - 1 {
            let a = CLLocation(latitude: points[i].latitude, longitude: points[i].longitude)
            let b = CLLocation(latitude: points[i + 1].latitude, longitude: points[i + 1].longitude)
            total += a.distance(from: b)
        }
        return total
    }

    /// Bearing of the most recent segment, in degrees (0=N, 90=E).
    var lastBearingDegrees: Double? {
        guard points.count >= 2 else { return nil }
        return Self.bearing(from: points[points.count - 2], to: points[points.count - 1])
    }

    /// Bearing in NATO mils (6400 per circle) for the most recent segment.
    var lastBearingMils: Int? {
        guard let deg = lastBearingDegrees else { return nil }
        return Int(round(deg * 6400 / 360)) % 6400
    }

    /// Enclosed area in square metres when the user has 3+ points. The polygon
    /// is closed implicitly (last → first segment).
    var enclosedAreaSquareMeters: Double? {
        guard points.count >= 3 else { return nil }
        let R: Double = 6_378_137  // mean Earth radius
        var area = 0.0
        for i in 0 ..< points.count {
            let p1 = points[i]
            let p2 = points[(i + 1) % points.count]
            let lat1 = p1.latitude  * .pi / 180
            let lat2 = p2.latitude  * .pi / 180
            let lon1 = p1.longitude * .pi / 180
            let lon2 = p2.longitude * .pi / 180
            area += (lon2 - lon1) * (2 + sin(lat1) + sin(lat2))
        }
        return abs(area * R * R / 2)
    }

    private static func bearing(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> Double {
        let φ1 = a.latitude  * .pi / 180
        let φ2 = b.latitude  * .pi / 180
        let Δλ = (b.longitude - a.longitude) * .pi / 180
        let y = sin(Δλ) * cos(φ2)
        let x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
        let θ = atan2(y, x) * 180 / .pi
        return (θ + 360).truncatingRemainder(dividingBy: 360)
    }
}

/// Format helpers used by `MeasureToolbar` and the running HUD.
enum MeasureFormat {
    static func distance(_ m: Double) -> String {
        if m < 1000 { return String(format: "%.0f m", m) }
        if m < 100_000 { return String(format: "%.2f km", m / 1000) }
        return String(format: "%.0f km", m / 1000)
    }

    static func area(_ sqm: Double) -> String {
        if sqm < 10_000 { return String(format: "%.0f m²", sqm) }
        if sqm < 1_000_000 { return String(format: "%.2f ha", sqm / 10_000) }
        return String(format: "%.2f km²", sqm / 1_000_000)
    }
}
