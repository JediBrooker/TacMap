import Foundation
import CoreLocation
import CoreGraphics

/// Web Mercator (EPSG:3857) projection math for the custom map renderer.
///
/// This is the slippy-map / XYZ-tile convention that MBTiles, Esri World
/// Imagery and OpenTopoMap all speak, so getting it exactly right is what lets
/// the same camera drive every tile source. Pure and side-effect free so it
/// unit-tests against known fixtures without any UI.
///
/// "World point" = pixel coordinate in a square map that is `tileSize * 2^zoom`
/// points on a side, origin top-left, x east, y south. Latitude is clamped to
/// the Mercator limit (~85.051deg) where the projection blows up.
enum WebMercator {

    static let tileSize: Double = 256
    /// WGS84 semi-major axis, the radius Web Mercator is defined against.
    static let earthRadius: Double = 6_378_137
    static let earthCircumference = 2 * Double.pi * earthRadius
    /// Past this latitude the Mercator y goes to infinity. This is the exact
    /// value where y hits the map edge (atan(sinh(pi)) in degrees); using a
    /// rounded-short version lets the clamp land a hair outside [0, size].
    static let latLimit = 85.051_128_779_806_589_5

    static func mapSize(zoom: Double) -> Double { tileSize * pow(2, zoom) }

    /// Coordinate -> world point at `zoom`.
    static func worldPoint(_ coord: CLLocationCoordinate2D, zoom: Double) -> CGPoint {
        let size = mapSize(zoom: zoom)
        let lat = min(max(coord.latitude, -latLimit), latLimit)
        let x = (coord.longitude + 180) / 360 * size
        let sinLat = sin(lat * .pi / 180)
        let y = (0.5 - log((1 + sinLat) / (1 - sinLat)) / (4 * .pi)) * size
        // A valid coord always lands inside the square; clamp off any
        // floating-point spill at the very edge so callers never see y<0.
        return CGPoint(x: min(max(x, 0), size), y: min(max(y, 0), size))
    }

    /// World point at `zoom` -> coordinate. Inverse of `worldPoint`.
    static func coordinate(fromWorld point: CGPoint, zoom: Double) -> CLLocationCoordinate2D {
        let size = mapSize(zoom: zoom)
        let lon = Double(point.x) / size * 360 - 180
        let n = Double.pi - 2 * Double.pi * Double(point.y) / size
        let lat = 180 / Double.pi * atan(sinh(n))
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    /// Ground distance one screen point covers, in metres, at a latitude/zoom.
    /// 156543.03 m/pt at the equator, zoom 0. Feeds scale bar + symbol sizing.
    static func groundResolution(latitude: Double, zoom: Double) -> Double {
        let lat = min(max(latitude, -latLimit), latLimit)
        return cos(lat * .pi / 180) * earthCircumference / mapSize(zoom: zoom)
    }

    /// Inverse of `groundResolution`: the zoom that yields `metresPerPoint` at a
    /// latitude. Used to fly to a region (fit its span to the viewport height).
    static func zoom(latitude: Double, groundResolution mpp: Double) -> Double {
        let lat = min(max(latitude, -latLimit), latLimit)
        guard mpp > 0 else { return 0 }
        return log2(cos(lat * .pi / 180) * earthCircumference / (tileSize * mpp))
    }
}
