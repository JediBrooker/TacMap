import Foundation
import CoreLocation

/// Geodetic datum that a calibrated map's grid references are expressed in.
/// Most Aussie defence / topo sheets use MGA (GDA94 or GDA2020), not WGS84,
/// and they can be up to ~1.8 m apart. When calibrating such a sheet the MGRS
/// the user types is in the sheet's datum; we shift each fiduciary to WGS84
/// before storing so overlays line up with satellite basemap and GeoJSON exports.
enum Datum: String, CaseIterable, Codable {
    case wgs84
    case gda94
    case gda2020

    var displayName: String {
        switch self {
        case .wgs84:   return "WGS84"
        case .gda94:   return "GDA94 / MGA94"
        case .gda2020: return "GDA2020 / MGA2020"
        }
    }

    /// Shift a coordinate in this datum to WGS84. GDA2020 and WGS84 are
    /// basically coincident (both ~ITRF2014 @ epoch 2020); GDA94 to WGS84
    /// uses the official ICSM 7-parameter conformal transform.
    func toWGS84(_ c: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        switch self {
        case .wgs84, .gda2020: return c
        case .gda94:           return DatumTransform.gda94ToGDA2020(c)
        }
    }
}

/// 7-parameter Helmert datum transforms. Coords go geodetic -> ECEF ->
/// similarity transform -> ECEF -> geodetic on GRS80 ellipsoid (same as
/// WGS84's to sub-millimetre).
enum DatumTransform {

    // ICSM "GDA94 -> GDA2020" conformal 7-parameter transformation
    // (GDA2020 Technical Manual). Coordinate-frame rotation convention.
    private static let tx = 0.06155, ty = -0.01087, tz = -0.04019          // metres
    private static let rxSec = -0.0394924, rySec = -0.0327221, rzSec = -0.0328979  // arc-seconds
    private static let scalePPM = -0.009994                                 // ppm

    // GRS80 ellipsoid.
    private static let a = 6_378_137.0
    private static let f = 1.0 / 298.257222101
    private static let e2 = (1.0 / 298.257222101) * (2 - 1.0 / 298.257222101)

    static func gda94ToGDA2020(_ c: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        fromECEF(helmert(toECEF(c)))
    }

    private static func toECEF(_ c: CLLocationCoordinate2D, h: Double = 0) -> (Double, Double, Double) {
        let lat = c.latitude * .pi / 180
        let lon = c.longitude * .pi / 180
        let sinLat = sin(lat), cosLat = cos(lat)
        let N = a / (1 - e2 * sinLat * sinLat).squareRoot()
        return (
            (N + h) * cosLat * cos(lon),
            (N + h) * cosLat * sin(lon),
            (N * (1 - e2) + h) * sinLat
        )
    }

    private static func helmert(_ p: (Double, Double, Double)) -> (Double, Double, Double) {
        let arc = Double.pi / 180 / 3600
        let rx = rxSec * arc, ry = rySec * arc, rz = rzSec * arc
        let s = 1 + scalePPM * 1e-6
        // Coordinate-frame rotation convention (ICSM).
        return (
            tx + s * ( p.0 + rz * p.1 - ry * p.2),
            ty + s * (-rz * p.0 + p.1 + rx * p.2),
            tz + s * ( ry * p.0 - rx * p.1 + p.2)
        )
    }

    private static func fromECEF(_ p: (Double, Double, Double)) -> CLLocationCoordinate2D {
        let (x, y, z) = p
        let lon = atan2(y, x)
        let r = (x * x + y * y).squareRoot()
        var lat = atan2(z, r * (1 - e2))
        for _ in 0..<6 {
            let sinLat = sin(lat)
            let N = a / (1 - e2 * sinLat * sinLat).squareRoot()
            lat = atan2(z + e2 * N * sinLat, r)
        }
        return CLLocationCoordinate2D(latitude: lat * 180 / .pi, longitude: lon * 180 / .pi)
    }
}
