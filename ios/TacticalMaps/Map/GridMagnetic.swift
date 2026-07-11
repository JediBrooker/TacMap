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
    /// Raw grid-magnetic angle in degrees (+E / -W), or nil when there's no
    /// coordinate to compute for. Feed it to `label(degrees:mils:)` for the
    /// banner text.
    static func angle(latitude: Double?, longitude: Double?, altitudeMeters: Double = 0) -> Double? {
        guard let lat = latitude, let lon = longitude else { return nil }
        let declination = GeoMagnetism.declinationDegrees(
            latitude: lat, longitude: lon, altitudeMeters: altitudeMeters)

        let zone = Int(floor((lon + 180.0) / 6.0)) + 1
        let centralMeridian = -180.0 + Double(zone - 1) * 6.0 + 3.0
        let convergence = atan(tan((lon - centralMeridian) * .pi / 180.0)
                               * sin(lat * .pi / 180.0)) * 180.0 / .pi

        return declination - convergence
    }

    /// Banner text for a G-M angle. Mils by default (NATO 6400/circle - what a
    /// compass dial and a fire mission actually read); tapping the banner flips
    /// it to degrees. e.g. "G-M 222 mils E" / "G-M 12.5°W".
    static func label(degrees: Double, mils: Bool) -> String {
        let dir = degrees >= 0 ? "E" : "W"
        if mils {
            let m = Int((abs(degrees) * 6400.0 / 360.0).rounded())
            return "G-M \(m) mils \(dir)"
        } else {
            return String(format: "G-M %.1f°%@", abs(degrees), dir)
        }
    }
}
