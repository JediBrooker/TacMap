import Foundation

/// Grid-magnetic angle (grid north -> magnetic north) for the MGRS banner, so a
/// user can convert a grid bearing to a compass bearing. East positive.
///
/// G-M = magnetic declination - UTM grid convergence.
///  - declination (true -> magnetic) from the WMM2025 model in [GeoMagnetism] -
///    always available, no network, no magnetometer.
///  - convergence (true -> grid) = atan(tan(lon - centralMeridian) * sin(lat)),
///    the small rotation between true north and the UTM grid's north.
enum GridMagnetic {
    /// Preformatted like "G-M 13.4°E" / "G-M 2.1°W", or nil when there's no
    /// coordinate to compute for.
    static func label(latitude: Double?, longitude: Double?, altitudeMeters: Double = 0) -> String? {
        guard let lat = latitude, let lon = longitude else { return nil }
        let declination = GeoMagnetism.declinationDegrees(
            latitude: lat, longitude: lon, altitudeMeters: altitudeMeters)

        let zone = Int(floor((lon + 180.0) / 6.0)) + 1
        let centralMeridian = -180.0 + Double(zone - 1) * 6.0 + 3.0
        let convergence = atan(tan((lon - centralMeridian) * .pi / 180.0)
                               * sin(lat * .pi / 180.0)) * 180.0 / .pi

        let gm = declination - convergence
        return String(format: "G-M %.1f°%@", abs(gm), gm >= 0 ? "E" : "W")
    }
}
