import Foundation
import CoreLocation
import MGRS
import Grid

/// Wrapper around NGA's `mgrs-ios`. Overlays store WGS84, this is just for display.
///
/// Returns the 3-part form `"<GZD> <easting> <northing>"`, e.g.
/// `"56HLH 13225 37516"`. NGA gives us the digits mushed together so
/// we split them up here.
enum MGRSFormatter {

    /// 1m precision (5+5 digits).
    static let defaultPrecision: GridType = .METER

    /// Returns an out-of-range string for polar lats, nil if we're good.
    static func outOfRangeMarker(_ latitude: Double) -> String? {
        if latitude > 84.0 { return "N/A (>84°N)" }
        if latitude < -80.0 { return "N/A (<80°S)" }
        return nil
    }

    static func string(from coordinate: CLLocationCoordinate2D,
                       precision: GridType = defaultPrecision,
                       spaced: Bool = true) -> String {
        // UTM/MGRS only covers 80S to 84N. Past that NGA clamps to a grid
        // thats like 110 km off, so just show an explicit marker instead.
        if let m = outOfRangeMarker(coordinate.latitude) { return m }
        let mgrs = MGRS.from(coordinate)
        let raw = mgrs.coordinate(precision)
        return spaced ? formatted(raw) : raw.replacingOccurrences(of: " ", with: "")
    }

    /// UTM grid readout for display, e.g. `"33N 450000mE 6700000mN"`.
    /// Hemisphere comes from lat sign (N/S), zone + easting + northing
    /// from NGA's `toUTM()`.
    static func utm(from coordinate: CLLocationCoordinate2D) -> String {
        if let m = outOfRangeMarker(coordinate.latitude) { return m }
        let u = MGRS.from(coordinate).toUTM()
        let hemi = coordinate.latitude >= 0 ? "N" : "S"
        return String(format: "%02d%@ %.0fmE %.0fmN", u.zone, hemi, u.easting, u.northing)
    }

    /// Decode `"56HLH 13225 37516"` (or the no-space form) back to WGS84.
    ///
    /// NGA's `MGRS.parse` calls `fatalError` on strings that don't look
    /// MGRS-shaped at all (a single "H", garbage like "hello", etc.) and
    /// its non-throwing, so nothing to catch. We pre-validate with a regex
    /// so the library only ever sees right-shaped strings.
    static func coordinate(from mgrs: String) -> CLLocationCoordinate2D? {
        let compact = mgrs
            .replacingOccurrences(of: " ", with: "")
            .uppercased()
        guard looksLikeMGRS(compact) else { return nil }
        let point = MGRS.parse(compact).toPoint()
        return CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
    }

    /// True only for strings that match a full MGRS shape: zone (1-2 digits)
    /// + band letter + 2-letter 100km square + even number of digits
    /// (2, 4, 6, 8 or 10). Also accepts UPS polar (4 letters + digits).
    /// Partial typing, place names, gibberish all return false so we
    /// never feed junk to `MGRS.parse`.
    static func looksLikeMGRS(_ s: String) -> Bool {
        guard !s.isEmpty else { return false }
        // Zone 1-60 (not 00 or >60), band C-X excluding I/O. The old
        // `\d{1,2}` + `[A-HJ-NP-Z]` pattern let through zone 00/61-99
        // and bands A/B/Y/Z, which NGA's non-throwing parser just
        // fatalErrors on. Square letters also exclude I/O; easting/northing
        // group is optional (bare 100km square is a valid location).
        let utm = #"^(0?[1-9]|[1-5]\d|60)[C-HJ-NP-X][A-HJ-NP-Z][A-HJ-NP-Z](\d{2}|\d{4}|\d{6}|\d{8}|\d{10})?$"#
        let ups = #"^[ABYZ][A-HJ-NP-Z][A-HJ-NP-Z](\d{2}|\d{4}|\d{6}|\d{8}|\d{10})?$"#
        for pattern in [utm, ups] {
            guard let rx = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(s.startIndex..., in: s)
            if rx.firstMatch(in: s, range: range) != nil { return true }
        }
        return false
    }

    // MARK: - Formatting

    /// Insert spaces so GZD+square prefix and easting/northing halves
    /// are visually seperated. `"56HLH1322537516"` -> `"56HLH 13225 37516"`.
    static func formatted(_ raw: String) -> String {
        // Nuke any existing whitespace so we always start from a clean form.
        let compact = raw.replacingOccurrences(of: " ", with: "")

        // Try UTM-zone form: 1–2 digits, latitude band letter, 2-letter 100km square,
        // followed by an even number of digits (easting + northing).
        let utm = #"^(\d{1,2}[A-HJ-NP-Z][A-HJ-NP-Z]{2})(\d+)$"#
        if let m = matchGroups(utm, in: compact), m.count == 2 {
            return splitDigits(prefix: m[0], digits: m[1])
        }

        // UPS (polar) form: leading letter (A, B, Y, Z), then 2-letter square, then digits.
        let ups = #"^([ABYZ][A-Z]{2})(\d+)$"#
        if let m = matchGroups(ups, in: compact), m.count == 2 {
            return splitDigits(prefix: m[0], digits: m[1])
        }

        // Unknown shape, just hand it back unchanged so we don't hide the real coordinate.
        return compact
    }

    private static func splitDigits(prefix: String, digits: String) -> String {
        guard digits.count.isMultiple(of: 2) else { return prefix + " " + digits }
        let half = digits.count / 2
        let easting  = String(digits.prefix(half))
        let northing = String(digits.suffix(half))
        return "\(prefix) \(easting) \(northing)"
    }

    private static func matchGroups(_ pattern: String, in s: String) -> [String]? {
        guard let rx = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(s.startIndex..., in: s)
        guard let m = rx.firstMatch(in: s, range: range) else { return nil }
        var groups: [String] = []
        for i in 1..<m.numberOfRanges {
            guard let r = Range(m.range(at: i), in: s) else { return nil }
            groups.append(String(s[r]))
        }
        return groups
    }
}
