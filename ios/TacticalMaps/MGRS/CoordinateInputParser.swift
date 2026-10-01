import Foundation
import CoreLocation

/// Grid intersection = SW corner of the typed cell, feature = cell centre.
enum CalibrationPointKind: String, Codable, Sendable, CaseIterable {
    case intersection
    case feature
}

/// Where a shorthand's zone/band/square came from.
enum CoordinateCompletion: Equatable, Sendable {
    case map
    case point(Int)
}

/// One line of calibration copy before it's localised: catalogue key plus raw
/// args (distances in metres, counts, other keys). The fixtures pin these, the
/// UI formats them. Used for the entry interpretation line and the fit panel.
struct CalibrationMessage: Equatable, Sendable {
    enum Arg: Equatable, Sendable {
        case text(String)
        case metres(Double)
        case number(Int)
        /// another catalogue key, e.g. the grade word
        case key(String)
    }
    let key: String
    let args: [String: Arg]

    init(key: String, args: [String: Arg] = [:]) {
        self.key = key
        self.args = args
    }
}

typealias CoordinateParseMessage = CalibrationMessage

/// What the parser read. Grid refs keep their own zone + SW corner E/N, never
/// a lat/lon round trip. resolved = sheet datum lat/lon (kind offset applied).
struct ParsedReference: Equatable, Sendable {
    enum Source: String, Sendable { case mgrs, utm, latLon }

    var source: Source
    var zone: Int?
    var south: Bool?
    var band: Character?
    var square: String?
    var easting: Double?
    var northing: Double?
    var digits: Int?
    var cellSizeM: Double?
    var effectiveKind: CalibrationPointKind?
    var kindSegment: Bool
    var lat: Double?
    var lon: Double?
    var completedFrom: CoordinateCompletion?
    var canonical: String
    var resolved: (lat: Double, lon: Double)
    var messages: [CoordinateParseMessage]

    static func == (a: ParsedReference, b: ParsedReference) -> Bool {
        a.source == b.source && a.zone == b.zone && a.south == b.south && a.band == b.band
            && a.square == b.square && a.easting == b.easting && a.northing == b.northing
            && a.digits == b.digits && a.cellSizeM == b.cellSizeM && a.effectiveKind == b.effectiveKind
            && a.kindSegment == b.kindSegment && a.lat == b.lat && a.lon == b.lon
            && a.completedFrom == b.completedFrom && a.canonical == b.canonical
            && a.resolved.lat == b.resolved.lat && a.resolved.lon == b.resolved.lon
            && a.messages == b.messages
    }

    /// what gets stored on the point
    var reference: CalibrationReference {
        switch source {
        case .latLon:
            return .geographic(latitude: lat ?? resolved.lat, longitude: lon ?? resolved.lon)
        case .mgrs, .utm:
            return .grid(zone: zone ?? 0, south: south ?? false, easting: easting ?? 0, northing: northing ?? 0,
                         cellSizeM: cellSizeM ?? 1, source: source == .mgrs ? .mgrs : .utm)
        }
    }
}

enum CoordinateParseError: Error, Equatable, Sendable {
    case empty
    case unrecognised
    case tooCoarse
    case unequalDigits
    case needsFullReference
    case invalidSquare(square: String, zone: Int)
    case bandMismatch(band: String)
    case polarUnsupported
    case outOfRange
    case oldLettering(datumID: String)

    var code: String {
        switch self {
        case .empty: return "empty"
        case .unrecognised: return "unrecognised"
        case .tooCoarse: return "tooCoarse"
        case .unequalDigits: return "unequalDigits"
        case .needsFullReference: return "needsFullReference"
        case .invalidSquare: return "invalidSquare"
        case .bandMismatch: return "bandMismatch"
        case .polarUnsupported: return "polarUnsupported"
        case .outOfRange: return "outOfRange"
        case .oldLettering: return "oldLettering"
        }
    }

    /// calibration_input.json errorKeys
    var messageKey: String? {
        switch self {
        case .empty: return nil
        case .unrecognised: return "calibration_err_unrecognised"
        case .tooCoarse: return "calibration_err_too_coarse"
        case .unequalDigits: return "calibration_err_odd_digits"
        case .needsFullReference: return "calibration_err_needs_full"
        case .invalidSquare: return "calibration_err_square"
        case .bandMismatch: return "calibration_err_band"
        case .polarUnsupported: return "calibration_err_polar"
        case .outOfRange: return "calibration_err_range"
        case .oldLettering: return "calibration_err_old_lettering"
        }
    }
}

struct GridAnchor: Equatable, Sendable {
    var zone: Int
    var band: Character
    var square: String
    var fromPoint: Int
}

struct CoordinateParseContext: Sendable {
    var datumID: String
    var isFirstPoint: Bool
    var kind: CalibrationPointKind
    var gridAnchor: GridAnchor?
    /// WGS84, what displayedGeoref.toWGS84 says the pending point is. nil when
    /// the displayed georef is provisional (it knows nothing)
    var predicted: CLLocationCoordinate2D?

    init(datumID: String, isFirstPoint: Bool, kind: CalibrationPointKind = .intersection,
         gridAnchor: GridAnchor? = nil, predicted: CLLocationCoordinate2D? = nil) {
        self.datumID = datumID
        self.isFirstPoint = isFirstPoint
        self.kind = kind
        self.gridAnchor = gridAnchor
        self.predicted = predicted
    }
}

/// The calibration entry field parser (WP4 contract s4). Port of the reference
/// parser in scripts/gen_calibration_fixtures.py, calibration_input.json pins
/// every case. Pure and quick (< 1 ms), runs per keystroke.
///
/// Shares the zone column sets with MGRSFormatter (the same table the Search
/// MGRS gate uses) but does its own grid arithmetic: NGA's parser fatalErrors
/// on odd input and goes via lat/lon, which we never want here.
enum CoordinateInputParser {

    static let maxInputUTF16Units = 64

    static func parse(_ raw: String, context ctx: CoordinateParseContext) -> Result<ParsedReference, CoordinateParseError> {
        do {
            return .success(try parseThrowing(raw, ctx))
        } catch let e as CoordinateParseError {
            return .failure(e)
        } catch {
            return .failure(.unrecognised)
        }
    }

    // MARK: - normalise

    /// fiduciaryFits.whiteSpaceCodePoints, spelled out so an OS Unicode bump
    /// can't change what counts (Android uses the same list)
    static let whiteSpaceCodePoints: Set<UInt32> = Set(
        Array(0x09...0x0D) + Array(0x1C...0x20) + [0x85, 0xA0, 0x1680] + Array(0x2000...0x200A)
            + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
    )

    static func normalise(_ raw: String) -> String {
        var scalars = String.UnicodeScalarView()
        for u in raw.unicodeScalars {
            switch u.value {
            case let v where whiteSpaceCodePoints.contains(v): scalars.append(" ")
            case 0x2212: scalars.append("-")
            case 0x2032, 0x2019: scalars.append("'")
            case 0x2033, 0x201D: scalars.append("\"")
            default: scalars.append(u)
            }
        }
        var s = String(scalars).replacingOccurrences(of: "''", with: "\"")
        while s.contains("  ") { s = s.replacingOccurrences(of: "  ", with: " ") }
        return s.trimmingCharacters(in: CharacterSet(charactersIn: " "))
    }

    // MARK: - patterns (ascii only, same as the python reference)

    private static func re(_ p: String) -> NSRegularExpression {
        // swiftlint:disable:next force_try
        try! NSRegularExpression(pattern: "^(?:" + p + ")$")
    }

    private static let fullRE = re(#"([0-9]{1,2}) ?([A-Z]) ?([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?"#)
    private static let upsRE = re(#"([ABYZ]) ?([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?"#)
    private static let shortSquareRE = re(#"([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?"#)
    private static let shortDigitsRE = re(#"([0-9]+)(?: ([0-9]+))?"#)
    private static let utmRE = re(#"([0-9]{1,2}) ?([A-Z]) ([0-9]+)(?: ?ME)? ([0-9]+)(?: ?MN)?"#)
    private static let utmWordsRE = re(#"([0-9]{1,2}) (NORTH|SOUTH) ([0-9]+)(?: ?ME)? ([0-9]+)(?: ?MN)?"#)

    private static func match(_ r: NSRegularExpression, _ s: String) -> [String?]? {
        let ns = s as NSString
        guard let m = r.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)),
              m.range.location == 0, m.range.length == ns.length else { return nil }
        return (1..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            return r.location == NSNotFound ? nil : ns.substring(with: r)
        }
    }

    // MARK: - top level

    private static func parseThrowing(_ raw: String, _ ctx: CoordinateParseContext) throws -> ParsedReference {
        let s = normalise(raw)
        if s.isEmpty { throw CoordinateParseError.empty }
        if s.utf16.count > maxInputUTF16Units { throw CoordinateParseError.unrecognised }
        let u = s.uppercased()
        guard let datum = GeoDatum.named(ctx.datumID) else { throw CoordinateParseError.unrecognised }
        let oldLettering = usesOldLettering(datum)
        if let m = match(fullRE, u) {
            return try parseFull(m, ctx, datum: datum, oldLettering: oldLettering)
        }
        if match(upsRE, u) != nil { throw CoordinateParseError.polarUnsupported }
        if let m = match(shortSquareRE, u) {
            return try parseShort(col: m[0], row: m[1], g1: m[2], g2: m[3], ctx, datum: datum, oldLettering: oldLettering)
        }
        if let m = match(shortDigitsRE, u) {
            return try parseShort(col: nil, row: nil, g1: m[0], g2: m[1], ctx, datum: datum, oldLettering: oldLettering)
        }
        if let r = try parseUTM(u, ctx, datum: datum) { return r }
        if let r = try parseDecimal(u) { return r }
        if let r = try parseDMS(u) { return r }
        throw CoordinateParseError.unrecognised
    }

    /// AL lettering sheets (old Clarke/Bessel MGRS) aren't decoded on purpose:
    /// a wrong decode shifts every point the same ~1000 km and the fit looks perfect
    static func usesOldLettering(_ datum: GeoDatum) -> Bool {
        let e = datum.ellipsoid
        return [Ellipsoid.clarke1866, .clarke1880IGN, .bessel1841].contains { abs($0.a - e.a) < 1e-6 && abs($0.f - e.f) < 1e-12 }
    }

    // MARK: - MGRS letters + the NGA northing rule

    static let bandLetters = Array("CDEFGHJKLMNPQRSTUVWX")
    static let rowLetters = Array("ABCDEFGHJKLMNPQRSTUV")
    /// Svalbard: these grid zones don't exist
    private static let missingGZD: Set<String> = ["32X", "34X", "36X"]

    static func columnLetters(zone: Int) -> [Character] {
        Array(MGRSFormatter.columnLetters(forZone: zone))
    }

    static func rowLetters(zone: Int) -> [Character] {
        zone.isMultiple(of: 2) ? Array(rowLetters[5...] + rowLetters[..<5]) : rowLetters
    }

    static func bandIndex(lat: Double) -> Int? {
        guard lat.isFinite, lat >= -80, lat <= 84 else { return nil }
        return min(19, Int(floor((lat + 80) / 8)))
    }

    static func band(lat: Double) -> Character? {
        bandIndex(lat: lat).map { bandLetters[$0] }
    }

    /// MGRS grid zone incl. the Norway/Svalbard exceptions (what's printed on a sheet)
    static func mgrsZone(lat: Double, lon: Double) -> Int {
        // python style wrap, 180 lands on -180 (zone 1) like the reference
        let l = ((lon + 180).truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) - 180
        var z = Int(floor((l + 180) / 6)) + 1
        if lat >= 56, lat < 64, l >= 3, l < 12 { z = 32 }
        if lat >= 72, lat <= 84, l >= 0 {
            if l < 9 { z = 31 } else if l < 21 { z = 33 } else if l < 33 { z = 35 } else if l < 42 { z = 37 }
        }
        return min(max(z, 1), 60)
    }

    /// NGA MGRS.utmNorthing(): floor to 100 km of the WGS84 UTM northing of the
    /// band's south edge at lon 0 (zone 31). Values from calibration_input.json rules
    static let bandBottomNorthing: [Character: Double] = [
        "C": 1_100_000, "D": 2_000_000, "E": 2_800_000, "F": 3_700_000, "G": 4_600_000,
        "H": 5_500_000, "J": 6_400_000, "K": 7_300_000, "L": 8_200_000, "M": 9_100_000,
        "N": 0, "P": 800_000, "Q": 1_700_000, "R": 2_600_000, "S": 3_500_000,
        "T": 4_400_000, "U": 5_300_000, "V": 6_200_000, "W": 7_100_000, "X": 7_900_000,
    ]

    /// MGRS.parse().toUTM(), straight off the letters
    static func gridEN(zone: Int, band: Character, col: Character, row: Character,
                       eIn: Double, nIn: Double) -> (e: Double, n: Double)? {
        guard let ci = columnLetters(zone: zone).firstIndex(of: col),
              let ri = rowLetters(zone: zone).firstIndex(of: row),
              let floorN = bandBottomNorthing[band] else { return nil }
        let e = Double(ci + 1) * 100_000 + eIn
        let n100 = Double(ri) * 100_000
        var n2m = 0.0
        while n2m + n100 + nIn < floorN { n2m += 2_000_000 }
        return (e, n2m + n100 + nIn)
    }

    static func square(zone: Int, e: Double, n: Double) -> String? {
        let ci = Int(floor(e / 100_000)) - 1
        guard (0..<8).contains(ci), n.isFinite, n >= 0 else { return nil }
        let rows = rowLetters(zone: zone)
        return String(columnLetters(zone: zone)[ci]) + String(rows[Int(floor(n / 100_000)) % 20])
    }

    private static func inverse(_ datum: GeoDatum, zone: Int, south: Bool, e: Double, n: Double) -> (lat: Double, lon: Double)? {
        GeoCrs.utm(zone: zone, south: south).inverse(x: e, y: n, ellipsoid: datum.ellipsoid)
    }

    static func forward(_ datum: GeoDatum, zone: Int, south: Bool, lat: Double, lon: Double) -> (x: Double, y: Double)? {
        let cm = Double(6 * zone - 183)
        guard abs(GeoCrs.wrapLongitude(lon - cm)) <= 90 else { return nil }
        return GeoCrs.utm(zone: zone, south: south).forward(lat: lat, lon: lon, ellipsoid: datum.ellipsoid)
    }

    // MARK: - figures

    /// (eIn, nIn, total) in metres from the digit groups
    private static func digitRule(_ g1: String?, _ g2: String?, kind: CalibrationPointKind) throws -> (Double, Double, Int) {
        let a = g1 ?? ""
        if let g2, a.count != g2.count { throw CoordinateParseError.unequalDigits }
        let digits = a + (g2 ?? "")
        let total = digits.count
        if total % 2 != 0 { throw CoordinateParseError.unequalDigits }
        if total == 0 || total == 2 { throw CoordinateParseError.tooCoarse }
        if total > 10 { throw CoordinateParseError.unrecognised }
        if kind == .feature, total == 4 { throw CoordinateParseError.tooCoarse }
        let half = total / 2
        let unit = pow(10.0, Double(5 - half))
        guard let e = Double(digits.prefix(half)), let n = Double(digits.suffix(half)) else {
            throw CoordinateParseError.unrecognised
        }
        return (e * unit, n * unit, total)
    }

    private static func pad5(_ v: Int) -> String {
        let t = String(v)
        return String(repeating: "0", count: max(0, 5 - t.count)) + t
    }

    private static func gridResult(zone: Int, band: Character, col: Character, row: Character,
                                   eIn: Double, nIn: Double, total: Int, ctx: CoordinateParseContext,
                                   datum: GeoDatum, completed: CoordinateCompletion? = nil) throws -> ParsedReference {
        guard let en = gridEN(zone: zone, band: band, col: col, row: row, eIn: eIn, nIn: nIn) else {
            throw CoordinateParseError.invalidSquare(square: String([col, row]), zone: zone)
        }
        let south = band < "N"
        guard let sw = inverse(datum, zone: zone, south: south, e: en.e, n: en.n),
              let bi = bandIndex(lat: sw.lat) else { throw CoordinateParseError.polarUnsupported }
        if let typed = bandLetters.firstIndex(of: band), abs(bi - typed) > 1 {
            throw CoordinateParseError.bandMismatch(band: String(band))
        }
        let cell = pow(10.0, Double(5 - total / 2))
        let kind: CalibrationPointKind = total < 10 ? ctx.kind : .intersection
        let off = kind == .feature ? cell / 2 : 0
        guard let resolved = inverse(datum, zone: zone, south: south, e: en.e + off, n: en.n + off) else {
            throw CoordinateParseError.polarUnsupported
        }
        let canonical = "\(zone)\(band) \(col)\(row) " + pad5(Int(en.e.truncatingRemainder(dividingBy: 100_000)))
            + " " + pad5(Int(en.n.truncatingRemainder(dividingBy: 100_000)))
        var messages = [CoordinateParseMessage(key: "calibration_reads_as", args: ["text": .text(canonical)])]
        if kind == .feature {
            messages.append(.init(key: "calibration_cell_feature", args: ["sizeM": .metres(cell), "halfM": .metres(cell / 2)]))
        } else {
            messages.append(.init(key: "calibration_cell_intersection", args: ["sizeM": .metres(cell)]))
        }
        switch completed {
        case .map?: messages.append(.init(key: "calibration_completed_from_map", args: [:]))
        case .point(let n)?: messages.append(.init(key: "calibration_completed_from", args: ["number": .number(n)]))
        case nil: break
        }
        let half = total / 2
        return ParsedReference(source: .mgrs, zone: zone, south: south, band: band, square: String([col, row]),
                               easting: en.e, northing: en.n, digits: total, cellSizeM: cell,
                               effectiveKind: kind, kindSegment: (2...4).contains(half),
                               lat: nil, lon: nil, completedFrom: completed, canonical: canonical,
                               resolved: resolved, messages: messages)
    }

    // MARK: - full MGRS

    private static func parseFull(_ m: [String?], _ ctx: CoordinateParseContext, datum: GeoDatum,
                                  oldLettering: Bool) throws -> ParsedReference {
        guard let zs = m[0], let zone = Int(zs), let band = m[1]?.first,
              let col = m[2]?.first, let row = m[3]?.first else { throw CoordinateParseError.unrecognised }
        guard (1...60).contains(zone), bandLetters.contains(band),
              !missingGZD.contains("\(zone)\(band)") else { throw CoordinateParseError.unrecognised }
        if oldLettering { throw CoordinateParseError.oldLettering(datumID: ctx.datumID) }
        guard columnLetters(zone: zone).contains(col), rowLetters.contains(row) else {
            throw CoordinateParseError.invalidSquare(square: String([col, row]), zone: zone)
        }
        let (e, n, total) = try digitRule(m[4], m[5], kind: ctx.kind)
        return try gridResult(zone: zone, band: band, col: col, row: row, eIn: e, nIn: n, total: total, ctx: ctx, datum: datum)
    }

    // MARK: - shorthand

    private static func parseShort(col: String?, row: String?, g1: String?, g2: String?,
                                   _ ctx: CoordinateParseContext, datum: GeoDatum,
                                   oldLettering: Bool) throws -> ParsedReference {
        if oldLettering { throw CoordinateParseError.oldLettering(datumID: ctx.datumID) }
        let typedCol = col?.first, typedRow = row?.first
        if let predicted = ctx.predicted {
            guard let p = datum.fromWGS84(lat: predicted.latitude, lon: predicted.longitude) else {
                throw CoordinateParseError.unrecognised
            }
            let zone = mgrsZone(lat: p.lat, lon: p.lon)
            let south = p.lat < 0
            guard let band = Self.band(lat: p.lat) else { throw CoordinateParseError.polarUnsupported }
            if let c = typedCol, let r = typedRow,
               !(columnLetters(zone: zone).contains(c) && rowLetters.contains(r)) {
                throw CoordinateParseError.invalidSquare(square: String([c, r]), zone: zone)
            }
            let (eIn, nIn, total) = try digitRule(g1, g2, kind: ctx.kind)
            if let c = typedCol, let r = typedRow {
                return try gridResult(zone: zone, band: band, col: c, row: r, eIn: eIn, nIn: nIn,
                                      total: total, ctx: ctx, datum: datum, completed: .map)
            }
            // figures only: the predicted square and its 8 neighbours, nearest wins
            guard let ep = forward(datum, zone: zone, south: south, lat: p.lat, lon: p.lon) else {
                throw CoordinateParseError.unrecognised
            }
            let cell = pow(10.0, Double(5 - total / 2))
            let kind: CalibrationPointKind = total < 10 ? ctx.kind : .intersection
            let off = kind == .feature ? cell / 2 : 0
            let e0 = floor(ep.x / 100_000) * 100_000, n0 = floor(ep.y / 100_000) * 100_000
            var best: (d: Double, e: Double, n: Double)?
            for dn in -1...1 {
                for de in -1...1 {
                    let e = e0 + Double(de) * 100_000 + eIn
                    let n = n0 + Double(dn) * 100_000 + nIn
                    guard e >= 100_000, e < 900_000, n >= 0, n <= 10_000_000 else { continue }
                    let d = hypot(e + off - ep.x, n + off - ep.y)
                    if best == nil || d < best!.d { best = (d, e, n) }
                }
            }
            guard let best, let sq = square(zone: zone, e: best.e, n: best.n) else {
                throw CoordinateParseError.unrecognised
            }
            guard let sw = inverse(datum, zone: zone, south: south, e: best.e, n: best.n),
                  let bandC = Self.band(lat: sw.lat) else { throw CoordinateParseError.polarUnsupported }
            let letters = Array(sq)
            return try gridResult(zone: zone, band: bandC, col: letters[0], row: letters[1], eIn: eIn, nIn: nIn,
                                  total: total, ctx: ctx, datum: datum, completed: .map)
        }
        guard let anchor = ctx.gridAnchor else { throw CoordinateParseError.needsFullReference }
        let zone = anchor.zone
        var c = typedCol, r = typedRow
        if c == nil {
            let letters = Array(anchor.square)
            guard letters.count == 2 else { throw CoordinateParseError.needsFullReference }
            c = letters[0]; r = letters[1]
        } else if let cc = c, let rr = r, !(columnLetters(zone: zone).contains(cc) && rowLetters.contains(rr)) {
            throw CoordinateParseError.invalidSquare(square: String([cc, rr]), zone: zone)
        }
        let (eIn, nIn, total) = try digitRule(g1, g2, kind: ctx.kind)
        guard let col = c, let row = r else { throw CoordinateParseError.unrecognised }
        return try gridResult(zone: zone, band: anchor.band, col: col, row: row, eIn: eIn, nIn: nIn,
                              total: total, ctx: ctx, datum: datum, completed: .point(anchor.fromPoint))
    }

    // MARK: - UTM

    private static func utmResult(zone: Int, south: Bool, e: Int, n: Int, datum: GeoDatum) throws -> ParsedReference {
        guard let g = inverse(datum, zone: zone, south: south, e: Double(e), n: Double(n)),
              g.lat >= -80, g.lat <= 84, let band = Self.band(lat: g.lat) else { throw CoordinateParseError.polarUnsupported }
        let canonical = "UTM \(zone)\(band) \(e) \(n)"
        return ParsedReference(source: .utm, zone: zone, south: south, band: band, square: nil,
                               easting: Double(e), northing: Double(n), digits: nil, cellSizeM: nil,
                               effectiveKind: nil, kindSegment: false, lat: nil, lon: nil, completedFrom: nil,
                               canonical: canonical, resolved: g,
                               messages: [.init(key: "calibration_reads_as", args: ["text": .text(canonical)])])
    }

    private static func parseUTM(_ u: String, _ ctx: CoordinateParseContext, datum: GeoDatum) throws -> ParsedReference? {
        var words: String?
        var m = match(utmRE, u)
        if m == nil {
            m = match(utmWordsRE, u)
            guard m != nil else { return nil }
            words = m?[1]
        }
        // A3: figures are numbers, leading zeros at any length are fine. the
        // significant part is what has to stay sane (and fit an Int)
        func significant(_ s: String) -> Substring { let t = s.drop { $0 == "0" }; return t.isEmpty ? "0" : t }
        guard let m, let zone = Int(m[0] ?? ""), let es = m[2].map(significant), let ns = m[3].map(significant),
              es.count <= 9, ns.count <= 9, let e = Int(es), let n = Int(ns) else { throw CoordinateParseError.unrecognised }
        guard (1...60).contains(zone), (100_000...900_000).contains(e), (0...10_000_000).contains(n) else {
            throw CoordinateParseError.unrecognised
        }
        if let words { return try utmResult(zone: zone, south: words == "SOUTH", e: e, n: n, datum: datum) }
        guard let letter = m[1]?.first, let bi = bandLetters.firstIndex(of: letter) else {
            throw CoordinateParseError.unrecognised
        }
        let southBand = letter < "N"
        let lat = inverse(datum, zone: zone, south: southBand, e: Double(e), n: Double(n))?.lat ?? .nan
        let lo = -80 + 8 * Double(bi)
        let hi = lo + (letter == "X" ? 12 : 8)
        if lat >= lo - 0.5, lat <= hi + 0.5 {
            return try utmResult(zone: zone, south: southBand, e: e, n: n, datum: datum)
        }
        if letter == "N" || letter == "S" {
            // our own readout writes the hemisphere there, 56S 334000mE 6250000mN is southern
            return try utmResult(zone: zone, south: letter == "S", e: e, n: n, datum: datum)
        }
        throw CoordinateParseError.bandMismatch(band: String(letter))
    }

    // MARK: - lat/lon

    private static func latLonResult(_ lat: Double, _ lon: Double) throws -> ParsedReference {
        guard abs(lat) <= 90, abs(lon) <= 180 else { throw CoordinateParseError.outOfRange }
        let canonical = String(format: "%.5f° %@, %.5f° %@", abs(lat), lat >= 0 ? "N" : "S", abs(lon), lon >= 0 ? "E" : "W")
        return ParsedReference(source: .latLon, zone: nil, south: nil, band: nil, square: nil, easting: nil,
                               northing: nil, digits: nil, cellSizeM: nil, effectiveKind: nil, kindSegment: false,
                               lat: lat, lon: lon, completedFrom: nil, canonical: canonical, resolved: (lat, lon),
                               messages: [.init(key: "calibration_reads_as", args: ["text": .text(canonical)])])
    }

    private struct SignedValue { let sign: String; let value: Double; let letter: Character? }

    /// two values -> (lat, lon). letters on both or neither, never sign + letter
    private static func assign(_ vals: [SignedValue]) throws -> (Double, Double) {
        let letters = vals.map(\.letter)
        let withLetter = letters.filter { $0 != nil }.count
        if withLetter != 0 && withLetter != vals.count { throw CoordinateParseError.unrecognised }
        if withLetter == vals.count {
            if vals.contains(where: { !$0.sign.isEmpty }) { throw CoordinateParseError.unrecognised }
            var lat: Double?, lon: Double?
            for v in vals {
                guard let l = v.letter else { continue }
                let value = (l == "S" || l == "W") ? -v.value : v.value
                if l == "N" || l == "S" {
                    if lat != nil { throw CoordinateParseError.unrecognised }
                    lat = value
                } else {
                    if lon != nil { throw CoordinateParseError.unrecognised }
                    lon = value
                }
            }
            guard let lat, let lon else { throw CoordinateParseError.unrecognised }
            return (lat, lon)
        }
        func signed(_ v: SignedValue) -> Double { v.sign == "-" ? -v.value : v.value }
        return (signed(vals[0]), signed(vals[1]))
    }

    private static func number(_ s: String?) -> Double? {
        guard let s else { return nil }
        return Double(s.replacingOccurrences(of: ",", with: "."))
    }

    private static let decimalPatterns: [(none: NSRegularExpression, suffix: NSRegularExpression, prefix: NSRegularExpression)] = {
        let modes: [(num: String, sep: String)] = [
            (#"[0-9]+(?:\.[0-9]+)?"#, #"(?: ?[,;] ?| )"#),
            (#"[0-9]+(?:[.,][0-9]+)?"#, #" ?; ?"#),
            (#"[0-9]+,[0-9]+"#, #" "#),
        ]
        return modes.map { mode in
            let nl = "([+-]?)(\(mode.num)) ?°?"
            let suf = "([+-]?)(\(mode.num)) ?°? ?([NSEWO])"
            let pre = "([NSEWO]) ?([+-]?)(\(mode.num)) ?°?"
            return (re(nl + mode.sep + nl), re(suf + mode.sep + suf), re(pre + mode.sep + pre))
        }
    }()

    private static func parseDecimal(_ u: String) throws -> ParsedReference? {
        for mode in decimalPatterns {
            if let g = match(mode.none, u) {
                guard let a = number(g[1]), let b = number(g[3]) else { continue }
                let (lat, lon) = try assign([SignedValue(sign: g[0] ?? "", value: a, letter: nil),
                                             SignedValue(sign: g[2] ?? "", value: b, letter: nil)])
                return try latLonResult(lat, lon)
            }
            if let g = match(mode.suffix, u) {
                guard let a = number(g[1]), let b = number(g[4]) else { continue }
                let (lat, lon) = try assign([SignedValue(sign: g[0] ?? "", value: a, letter: g[2]?.first),
                                             SignedValue(sign: g[3] ?? "", value: b, letter: g[5]?.first)])
                return try latLonResult(lat, lon)
            }
            if let g = match(mode.prefix, u) {
                guard let a = number(g[2]), let b = number(g[5]) else { continue }
                let (lat, lon) = try assign([SignedValue(sign: g[1] ?? "", value: a, letter: g[0]?.first),
                                             SignedValue(sign: g[4] ?? "", value: b, letter: g[3]?.first)])
                return try latLonResult(lat, lon)
            }
        }
        return nil
    }

    // MARK: - DM / DMS

    private enum DMSStyle { case none, suffix, prefix }

    /// group names get the value index so both halves can live in one pattern
    private static func dmsValue(_ i: Int, _ style: DMSStyle) -> String {
        let sym = #"(?<d\#(i)>[0-9]+) ?° ?(?<m\#(i)>[0-9]+(?:\.[0-9]+)?) ?'(?: ?(?<s\#(i)>[0-9]+(?:\.[0-9]+)?) ?")?"#
        let spc = #"(?<sd\#(i)>[0-9]+) (?<sm\#(i)>[0-9]+(?:\.[0-9]+)?)(?: (?<ss\#(i)>[0-9]+(?:\.[0-9]+)?))?"#
        switch style {
        case .none: return "(?<g\(i)>[+-]?)" + sym
        case .suffix: return "(?<g\(i)>[+-]?)(?:\(sym)|\(spc)) ?(?<h\(i)>[NSEWO])"
        case .prefix: return "(?<h\(i)>[NSEWO]) ?(?<g\(i)>[+-]?)(?:\(sym)|\(spc))"
        }
    }

    private static let dmsPatterns: [(DMSStyle, NSRegularExpression)] = [DMSStyle.none, .suffix, .prefix].map {
        ($0, re(dmsValue(1, $0) + #"(?: ?[,;] ?| )"# + dmsValue(2, $0)))
    }

    private static func parseDMS(_ u: String) throws -> ParsedReference? {
        let ns = u as NSString
        for (style, r) in dmsPatterns {
            guard let m = r.firstMatch(in: u, range: NSRange(location: 0, length: ns.length)),
                  m.range.location == 0, m.range.length == ns.length else { continue }
            func group(_ name: String) -> String? {
                let rr = m.range(withName: name)
                return rr.location == NSNotFound ? nil : ns.substring(with: rr)
            }
            var vals: [SignedValue] = []
            for i in 1...2 {
                // the space-only groups only exist in the lettered patterns, asking
                // NSTextCheckingResult for a name that isn't there blows up
                let spaced = style != .none && group("d\(i)") == nil
                guard let d = spaced ? group("sd\(i)") : group("d\(i)"),
                      let mi = spaced ? group("sm\(i)") : group("m\(i)") else {
                    throw CoordinateParseError.unrecognised
                }
                let se = spaced ? group("ss\(i)") : group("s\(i)")
                // 52.5' 30" makes no sense
                if se != nil, mi.contains(".") { throw CoordinateParseError.unrecognised }
                guard let dv = Double(d), let mv = Double(mi) else { throw CoordinateParseError.unrecognised }
                let sv = se.flatMap(Double.init) ?? 0
                if mv >= 60 || sv >= 60 { throw CoordinateParseError.outOfRange }
                let letter: Character? = style == .none ? nil : group("h\(i)")?.first
                vals.append(SignedValue(sign: group("g\(i)") ?? "", value: dv + mv / 60 + sv / 3600, letter: letter))
            }
            let (lat, lon) = try assign(vals)
            return try latLonResult(lat, lon)
        }
        return nil
    }
}
