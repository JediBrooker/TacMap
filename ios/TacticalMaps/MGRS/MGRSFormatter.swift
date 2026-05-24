import Foundation
import CoreLocation
import MGRS
import Grid

/// Bridge to NGA's `mgrs-ios`. All overlays store WGS84; MGRS is presentation-only.
///
/// Returns the canonical 3-token form: `"<GZD> <easting> <northing>"`, e.g.
/// `"56HLH 13225 37516"`. The NGA library returns the digits run together; we
/// post-process to insert the spaces.
enum MGRSFormatter {

    /// Default precision: 1 metre (5+5 digits).
    static let defaultPrecision: GridType = .METER

    static func string(from coordinate: CLLocationCoordinate2D,
                       precision: GridType = defaultPrecision,
                       spaced: Bool = true) -> String {
        let mgrs = MGRS.from(coordinate)
        let raw = mgrs.coordinate(precision)
        return spaced ? formatted(raw) : raw.replacingOccurrences(of: " ", with: "")
    }

    /// Decode `"56HLH 13225 37516"` (or the no-space form) back to WGS84.
    ///
    /// **Crash safety**: NGA's `MGRS.parse` calls `fatalError` on inputs that
    /// don't even look MGRS-shaped (a single "H", garbage like "hello", etc.).
    /// `try?` cannot catch fatalError. We pre-validate with a regex so the
    /// library is only called on strings with the right shape.
    static func coordinate(from mgrs: String) -> CLLocationCoordinate2D? {
        let compact = mgrs
            .replacingOccurrences(of: " ", with: "")
            .uppercased()
        guard looksLikeMGRS(compact) else { return nil }
        guard let parsed = try? MGRS.parse(compact) else { return nil }
        let point = parsed.toPoint()
        return CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
    }

    /// True only for strings that match a full MGRS shape — zone (1–2 digits)
    /// + band letter + 2-letter 100km square + an even number of digits
    /// (2, 4, 6, 8 or 10). Also accepts UPS polar (4 letters + digits).
    /// Anything else — partial typing, place names, gibberish — returns false
    /// so we never feed malformed input to `MGRS.parse`.
    static func looksLikeMGRS(_ s: String) -> Bool {
        guard !s.isEmpty else { return false }
        let utm = #"^\d{1,2}[A-HJ-NP-Z][A-HJ-NP-Z]{2}(\d{2}|\d{4}|\d{6}|\d{8}|\d{10})?$"#
        let ups = #"^[ABYZ][A-Z]{2}(\d{2}|\d{4}|\d{6}|\d{8}|\d{10})?$"#
        for pattern in [utm, ups] {
            guard let rx = try? NSRegularExpression(pattern: pattern) else { continue }
            let range = NSRange(s.startIndex..., in: s)
            if rx.firstMatch(in: s, range: range) != nil { return true }
        }
        return false
    }

    // MARK: - Formatting

    /// Insert spaces so the GZD+square prefix and the easting/northing halves are
    /// visually separated. `"56HLH1322537516"` -> `"56HLH 13225 37516"`.
    static func formatted(_ raw: String) -> String {
        // Strip any existing whitespace first so we always work from a canonical form.
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

        // Unknown shape — hand back unchanged so we never hide the real coordinate.
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
