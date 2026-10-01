import Foundation
import CoreGraphics
import CoreLocation

/// What the user typed for a point, as the parser read it. Grid refs keep their
/// own zone + SW corner, lat/lon is in the sheet datum (or WGS84 for GPS points,
/// see CalibrationPoint.datumOverride). Same json shape as the fixtures.
enum CalibrationReference: Hashable, Sendable {
    enum GridSource: String, Codable, Sendable { case mgrs, utm }

    case grid(zone: Int, south: Bool, easting: Double, northing: Double, cellSizeM: Double, source: GridSource)
    case geographic(latitude: Double, longitude: Double)

    var isGrid: Bool {
        if case .grid = self { return true }
        return false
    }
}

extension CalibrationReference: Codable {
    private enum Outer: String, CodingKey { case grid, geographic }
    private enum GridKeys: String, CodingKey { case zone, south, easting, northing, cellSizeM, source }
    private enum GeoKeys: String, CodingKey { case lat, lon }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Outer.self)
        if c.contains(.grid) {
            let g = try c.nestedContainer(keyedBy: GridKeys.self, forKey: .grid)
            let zone = try g.decode(Int.self, forKey: .zone)
            let e = try g.decode(Double.self, forKey: .easting)
            let n = try g.decode(Double.self, forKey: .northing)
            let cell = try g.decodeIfPresent(Double.self, forKey: .cellSizeM) ?? 1
            guard (1...60).contains(zone), e.isFinite, n.isFinite, cell.isFinite, cell >= 1 else {
                throw DecodingError.dataCorruptedError(forKey: .zone, in: g, debugDescription: "bad grid ref")
            }
            self = .grid(zone: zone, south: try g.decode(Bool.self, forKey: .south), easting: e, northing: n,
                         cellSizeM: cell, source: try g.decodeIfPresent(GridSource.self, forKey: .source) ?? .mgrs)
        } else {
            let g = try c.nestedContainer(keyedBy: GeoKeys.self, forKey: .geographic)
            let lat = try g.decode(Double.self, forKey: .lat), lon = try g.decode(Double.self, forKey: .lon)
            guard lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180 else {
                throw DecodingError.dataCorruptedError(forKey: .lat, in: g, debugDescription: "bad lat/lon")
            }
            self = .geographic(latitude: lat, longitude: lon)
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Outer.self)
        switch self {
        case let .grid(zone, south, e, n, cell, source):
            var g = c.nestedContainer(keyedBy: GridKeys.self, forKey: .grid)
            try g.encode(zone, forKey: .zone)
            try g.encode(south, forKey: .south)
            try g.encode(e, forKey: .easting)
            try g.encode(n, forKey: .northing)
            try g.encode(cell, forKey: .cellSizeM)
            try g.encode(source, forKey: .source)
        case let .geographic(lat, lon):
            var g = c.nestedContainer(keyedBy: GeoKeys.self, forKey: .geographic)
            try g.encode(lat, forKey: .lat)
            try g.encode(lon, forKey: .lon)
        }
    }
}

/// One calibration point. number is stable (never renumbered, a delete leaves a
/// gap). page is raw PDF user space, always captured through the georef that
/// was on screen.
struct CalibrationPoint: Codable, Hashable, Identifiable, Sendable {
    var id: UUID
    var number: Int
    var page: PdfPagePoint
    /// what was typed, for the list and for editing. sealed with the rest
    var input: String
    var reference: CalibrationReference
    var kind: CalibrationPointKind
    /// "WGS84" for GPS points and migrated v1 points, the lat/lon then isn't sheet datum
    var datumOverride: String?
    var label: String?

    init(id: UUID = UUID(), number: Int, page: PdfPagePoint, input: String, reference: CalibrationReference,
         kind: CalibrationPointKind = .intersection, datumOverride: String? = nil, label: String? = nil) {
        self.id = id
        self.number = number
        self.page = page
        self.input = input
        self.reference = reference
        self.kind = kind
        self.datumOverride = datumOverride
        self.label = label
    }

    /// contract s5: sheet datum lat/lon of the reference. grid also hands back
    /// the (offset) E/N it was typed in, so a point in the plane zone skips the
    /// lat/lon round trip
    func resolved(sheetDatum datum: GeoDatum) -> (lat: Double, lon: Double, grid: (zone: Int, south: Bool, e: Double, n: Double)?)? {
        switch reference {
        case let .grid(zone, south, e, n, cell, _):
            let off = kind == .feature && cell > 1 ? cell / 2 : 0
            guard let g = GeoCrs.utm(zone: zone, south: south).inverse(x: e + off, y: n + off, ellipsoid: datum.ellipsoid)
            else { return nil }
            return (g.lat, g.lon, (zone, south, e + off, n + off))
        case let .geographic(lat, lon):
            if datumOverride == "WGS84" {
                guard let s = datum.fromWGS84(lat: lat, lon: lon) else { return nil }
                return (s.lat, s.lon, nil)
            }
            return (lat, lon, nil)
        }
    }

    /// WGS84 of the typed reference, for the entry check and residual lines
    func typedWGS84(sheetDatum datum: GeoDatum) -> CLLocationCoordinate2D? {
        guard let r = resolved(sheetDatum: datum), let w = datum.toWGS84(lat: r.lat, lon: r.lon) else { return nil }
        return CLLocationCoordinate2D(latitude: w.lat, longitude: w.lon)
    }

    /// canonical text for the points list (the same text the entry card showed)
    var canonicalReference: String {
        switch reference {
        case let .grid(zone, south, e, n, _, source):
            let lat = GeoCrs.utm(zone: zone, south: south).inverse(x: e, y: n, ellipsoid: .wgs84)?.lat ?? (south ? -1 : 1)
            let band = CoordinateInputParser.band(lat: lat).map(String.init) ?? (south ? "S" : "N")
            if source == .utm { return "UTM \(zone)\(band) \(Int(e)) \(Int(n))" }
            let sq = CoordinateInputParser.square(zone: zone, e: e, n: n) ?? "??"
            func pad(_ v: Double) -> String {
                let t = String(Int(v.truncatingRemainder(dividingBy: 100_000)))
                return String(repeating: "0", count: max(0, 5 - t.count)) + t
            }
            return "\(zone)\(band) \(sq) \(pad(e)) \(pad(n))"
        case let .geographic(lat, lon):
            return String(format: "%.5f° %@, %.5f° %@", abs(lat), lat >= 0 ? "N" : "S", abs(lon), lon >= 0 ? "E" : "W")
        }
    }
}

/// Which PDF (and page) a calibration belongs to. Held strongly by the session
/// so a draft or a commit can never land on another file (D5-10).
struct CalibrationTarget: Codable, Hashable, Sendable {
    var entryID: UUID
    var contentKey: String
    var pageIndex: Int
    /// CropBox n MediaBox of the page, 4 raw user space corners (x0,y0)(x1,y0)(x1,y1)(x0,y1)
    var pageBox: [PdfPagePoint]
    var rotate: Int

    var draftKey: String { CalibrationDraft.key(contentKey: contentKey, pageIndex: pageIndex) }

    var pageRect: CGRect {
        let xs = pageBox.map(\.x), ys = pageBox.map(\.y)
        guard let x0 = xs.min(), let x1 = xs.max(), let y0 = ys.min(), let y1 = ys.max() else { return .null }
        return CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    static func box(_ r: CGRect) -> [PdfPagePoint] {
        let s = r.standardized
        return [PdfPagePoint(x: Double(s.minX), y: Double(s.minY)), PdfPagePoint(x: Double(s.maxX), y: Double(s.minY)),
                PdfPagePoint(x: Double(s.maxX), y: Double(s.maxY)), PdfPagePoint(x: Double(s.minX), y: Double(s.maxY))]
    }
}

/// The editable bit of a calibration. Value type so undo is just a stack of these.
struct CalibrationState: Codable, Hashable, Sendable {
    var datumID: String?
    var points: [CalibrationPoint]
    var nextNumber: Int

    init(datumID: String? = nil, points: [CalibrationPoint] = [], nextNumber: Int? = nil) {
        self.datumID = datumID
        self.points = points.sorted { $0.number < $1.number }
        self.nextNumber = max(nextNumber ?? 1, (points.map(\.number).max() ?? 0) + 1)
    }

    var sheetDatum: GeoDatum { datumID.flatMap(GeoDatum.named) ?? .wgs84 }

    func point(_ id: UUID) -> CalibrationPoint? { points.first { $0.id == id } }

    func applying(_ edit: CalibrationEdit) -> CalibrationState {
        var s = self
        switch edit {
        case .add(var p):
            p.number = s.nextNumber
            s.nextNumber += 1
            s.points.append(p)
        case let .move(id, page):
            if let i = s.points.firstIndex(where: { $0.id == id }) { s.points[i].page = page }
        case let .editReference(id, input, reference, kind, datumOverride, label):
            if let i = s.points.firstIndex(where: { $0.id == id }) {
                s.points[i].input = input
                s.points[i].reference = reference
                s.points[i].kind = kind
                s.points[i].datumOverride = datumOverride
                s.points[i].label = label
            }
        case .delete(let id):
            s.points.removeAll { $0.id == id }
        case .setDatum(let id):
            s.datumID = id
        }
        s.points.sort { $0.number < $1.number }
        return s
    }
}

enum CalibrationEdit: Sendable {
    case add(CalibrationPoint)
    case move(UUID, PdfPagePoint)
    case editReference(UUID, input: String, reference: CalibrationReference, kind: CalibrationPointKind,
                       datumOverride: String?, label: String?)
    case delete(UUID)
    case setDatum(String)
}

/// An unsaved entry the user was typing when the app went away. The typed
/// text isn't kept, only where the point goes.
struct CalibrationPending: Codable, Hashable, Sendable {
    var page: PdfPagePoint
    var editingId: UUID?
}

/// contract s8.1 draft. Lives in its own sealed file keyed contentKey#pageIndex
struct CalibrationDraft: Codable, Hashable, Sendable {
    var contentKey: String
    var pageIndex: Int
    var entryId: UUID
    var datumId: String?
    var points: [CalibrationPoint]
    var nextNumber: Int
    var pending: CalibrationPending?
    var active: Bool
    var updatedAtMs: Int64

    static func key(contentKey: String, pageIndex: Int) -> String { "\(contentKey)#\(pageIndex)" }
    var key: String { Self.key(contentKey: contentKey, pageIndex: pageIndex) }

    var state: CalibrationState { CalibrationState(datumID: datumId, points: points, nextNumber: nextNumber) }
}

/// The calibration saved on a library entry (contract s8.2 ManualCalibration)
struct ManualCalibration: Codable, Hashable, Sendable {
    var datumId: String
    var points: [CalibrationPoint]
    var nextNumber: Int?
    var georef: PdfGeoreference
    var n: Int
    var rmsM: Double?
    var grade: CalibrationGrade?
    var savedAtMs: Int64

    var state: CalibrationState { CalibrationState(datumID: datumId, points: points, nextNumber: nextNumber) }
}

enum CalibrationGrade: String, Codable, Sendable {
    case good, fair, poor

    var messageKey: String { "calibration_grade_\(rawValue)" }
}
