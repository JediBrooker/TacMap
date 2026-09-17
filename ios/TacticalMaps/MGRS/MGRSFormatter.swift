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

    struct ResolvedGridReference {
        let coordinate: CLLocationCoordinate2D
        let formattedReference: String
        /// Total numeric figures (easting + northing): 4, 6, 8, or 10.
        let figureCount: Int
        let squareSizeMetres: Int
        let usedLocalContext: Bool
    }

    enum GridReferenceError: LocalizedError, LocalizedMessageError, Equatable {
        case empty
        case unsupportedPrecision
        case invalidReference
        case unavailableLocalContext

        var errorDescription: String? { localizedMessage.text }

        var localizedMessage: LocalizedMessage {
            switch self {
            case .empty:
                return Messages.displayEnterAnMgrsGridReferenceMessage()
            case .unsupportedPrecision:
                return Messages.displayUseAOrFigureGridReferenceMessage()
            case .invalidReference:
                return Messages.displayEnterAValidFullMgrsReferenceOrALocalMessage()
            case .unavailableLocalContext:
                return Messages.displayALocalMgrsGridContextIsUnavailableAtThisMessage()
            }
        }
    }

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
            .filter { !$0.isWhitespace }
            .uppercased()
        guard looksLikeMGRS(compact) else { return nil }
        let isBarePrefix = compact.range(
            of: #"^(?:0?[1-9]|[1-5][0-9]|60)[C-HJ-NP-X][A-HJ-NP-Z][A-HJ-NP-V]$"#,
            options: .regularExpression
        ) != nil
        guard isSafeUTMMGRS(compact) || (isBarePrefix && isSafeUTMMGRS(compact + "0000")) else {
            // The vendored parser implements UTM MGRS only. Reject UPS and
            // parser-incompatible rows here instead of allowing fatalError.
            return nil
        }
        let point = MGRS.parse(compact).toPoint()
        return CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)
    }

    /// Resolve the field shorthand used over radio (for example `1234`) or a
    /// complete MGRS value. Numeric-only input inherits the grid-zone and 100 km
    /// square from `anchor`; no provider or network API participates.
    ///
    /// NGA's parser returns the south-west corner for reduced precision. We
    /// convert through UTM and add half the indicated cell size so all supported
    /// precisions resolve to the centre of their 1 km / 100 m / 10 m / 1 m cell.
    static func resolveGridReference(
        _ raw: String,
        relativeTo anchor: CLLocationCoordinate2D
    ) throws -> ResolvedGridReference {
        let compact = raw.uppercased().filter { !$0.isWhitespace }
        guard !compact.isEmpty else { throw GridReferenceError.empty }

        let allowedFigureCounts = [4, 6, 8, 10]
        let prefix: String
        let figures: String
        let usedLocalContext: Bool

        if isASCIIDigits(compact) {
            guard allowedFigureCounts.contains(compact.count) else {
                throw GridReferenceError.unsupportedPrecision
            }
            guard let localPrefix = safeUTMPrefix(for: anchor) else {
                throw GridReferenceError.unavailableLocalContext
            }
            prefix = localPrefix
            figures = compact
            usedLocalContext = true
        } else {
            guard let parts = fullGridParts(compact),
                  allowedFigureCounts.contains(parts.figures.count) else {
                if compact.rangeOfCharacter(from: .decimalDigits) != nil,
                   compact.rangeOfCharacter(from: .letters) != nil {
                    throw GridReferenceError.unsupportedPrecision
                }
                throw GridReferenceError.invalidReference
            }
            prefix = parts.prefix
            figures = parts.figures
            usedLocalContext = false
        }

        let half = figures.count / 2
        let easting = String(figures.prefix(half))
        let northing = String(figures.suffix(half))
        let compactReference = prefix + easting + northing
        guard isSafeUTMMGRS(compactReference) else {
            throw GridReferenceError.invalidReference
        }

        let southWestUTM = MGRS.parse(compactReference).toUTM()
        let squareSize = Int(pow(10.0, Double(5 - half)))
        let rawCentre = UTM(
            southWestUTM.zone,
            southWestUTM.hemisphere,
            southWestUTM.easting + Double(squareSize) / 2,
            southWestUTM.northing + Double(squareSize) / 2
        ).toCoordinate()
        guard rawCentre.latitude.isFinite, rawCentre.longitude.isFinite else {
            throw GridReferenceError.invalidReference
        }
        let longitude = ((rawCentre.longitude + 180)
            .truncatingRemainder(dividingBy: 360) + 360)
            .truncatingRemainder(dividingBy: 360) - 180
        let centre = CLLocationCoordinate2D(
            latitude: rawCentre.latitude,
            longitude: longitude
        )
        // A valid boundary cell may centre just beyond the nominal -80...84
        // MGRS generation band. Accept the finite WGS84 point; only shorthand
        // context derivation itself requires an in-range anchor.
        guard CLLocationCoordinate2DIsValid(centre),
              (-90...90).contains(centre.latitude) else {
            throw GridReferenceError.invalidReference
        }

        return ResolvedGridReference(
            coordinate: centre,
            formattedReference: "\(prefix) \(easting) \(northing)",
            figureCount: figures.count,
            squareSizeMetres: squareSize,
            usedLocalContext: usedLocalContext
        )
    }

    private static func safeUTMPrefix(for coordinate: CLLocationCoordinate2D) -> String? {
        guard CLLocationCoordinate2DIsValid(coordinate),
              (-80...84).contains(coordinate.latitude) else { return nil }
        let compact = string(from: coordinate, spaced: false)
        let pattern = #"^((?:0?[1-9]|[1-5][0-9]|60)[C-HJ-NP-X][A-HJ-NP-Z][A-HJ-NP-V])"#
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(
                in: compact, range: NSRange(compact.startIndex..., in: compact)),
              let range = Range(match.range(at: 1), in: compact) else { return nil }
        let prefix = String(compact[range])
        return isSafeUTMMGRS(prefix + "0000") ? prefix : nil
    }

    private static func fullGridParts(_ compact: String) -> (prefix: String, figures: String)? {
        let pattern = #"^((?:0?[1-9]|[1-5][0-9]|60)[C-HJ-NP-X][A-HJ-NP-Z][A-HJ-NP-V])([0-9]+)$"#
        guard let expression = try? NSRegularExpression(pattern: pattern),
              let match = expression.firstMatch(
                in: compact, range: NSRange(compact.startIndex..., in: compact)),
              let prefixRange = Range(match.range(at: 1), in: compact),
              let figureRange = Range(match.range(at: 2), in: compact) else { return nil }
        return (String(compact[prefixRange]), String(compact[figureRange]))
    }

    /// A crash-safe gate matching the vendored parser's actual UTM support.
    /// In particular the row is A-V (excluding I/O), and the column must be in
    /// the repeating zone-specific set.
    private static func isSafeUTMMGRS(_ compact: String) -> Bool {
        guard compact.unicodeScalars.allSatisfy({ $0.value <= 127 }),
              let parts = fullGridParts(compact),
              let zoneEnd = parts.prefix.firstIndex(where: { $0.isLetter }),
              let zone = Int(parts.prefix[..<zoneEnd]),
              let column = parts.prefix.dropFirst(parts.prefix.distance(from: parts.prefix.startIndex,
                                                                          to: zoneEnd) + 1).first
        else { return false }
        let columns: String
        switch zone % 3 {
        case 1: columns = "ABCDEFGH"
        case 2: columns = "JKLMNPQR"
        default: columns = "STUVWXYZ"
        }
        return columns.contains(column) && MGRS.isMGRS(compact)
    }

    private static func isASCIIDigits(_ value: String) -> Bool {
        !value.isEmpty && value.unicodeScalars.allSatisfy {
            $0.value >= 48 && $0.value <= 57
        }
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
        let utm = #"^(0?[1-9]|[1-5][0-9]|60)[C-HJ-NP-X][A-HJ-NP-Z][A-HJ-NP-Z]([0-9]{2}|[0-9]{4}|[0-9]{6}|[0-9]{8}|[0-9]{10})?$"#
        let ups = #"^[ABYZ][A-HJ-NP-Z][A-HJ-NP-Z]([0-9]{2}|[0-9]{4}|[0-9]{6}|[0-9]{8}|[0-9]{10})?$"#
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
