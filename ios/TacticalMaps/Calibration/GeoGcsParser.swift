import Foundation

/// What a GeoPDF /GCS (WKT or EPSG) boils down to. Rules are
/// pdf_georef.json gcs.rules, Android parses the same way.
struct GcsParseResult: Equatable {
    enum Status: Equatable {
        case ok
        /// projection we can't do or a GCS we can't read. The caller fits a
        /// local TM centred on the GPTS instead (still way better than a
        /// lat/lon affine), crs is nil here.
        case fallbackLocalTM
        /// can't put the datum on WGS84, loud failure
        case unknownDatum
        /// present but broken in a way that moves every point (garbled
        /// PRIMEM), don't guess Greenwich
        case malformed
    }

    enum Reason: String, Equatable {
        case unsupportedProjection
        case unreadableGcs
        case unsupportedEpsg
    }

    var status: Status
    var crs: GeoCrs?
    var datum: GeoDatum?
    var datumAssumed = false
    var reason: Reason?
    /// degrees east of Greenwich that GPTS longitudes are measured from
    var primeMeridian = 0.0
    var linearUnitMetres = 1.0

    static func fallback(_ reason: Reason, datum: GeoDatum = .wgs84, assumed: Bool = true,
                         primeMeridian: Double = 0) -> GcsParseResult {
        GcsParseResult(status: .fallbackLocalTM, crs: nil, datum: datum, datumAssumed: assumed,
                       reason: reason, primeMeridian: primeMeridian)
    }
}

enum GcsParser {

    // MARK: - EPSG

    static func parse(epsg code: Int) -> GcsParseResult {
        func utm(_ zone: Int, south: Bool, _ datum: GeoDatum) -> GcsParseResult {
            GcsParseResult(status: .ok, crs: .utm(zone: zone, south: south), datum: datum)
        }
        let d = { (id: String) in GeoDatum.named(id)! }
        switch code {
        case 32601...32660: return utm(code - 32600, south: false, .wgs84)
        case 32701...32760: return utm(code - 32700, south: true, .wgs84)
        // 269xx / 267xx past the UTM zones are state plane, don't let them in
        case 26901...26923: return utm(code - 26900, south: false, d("NAD83"))
        case 26701...26722: return utm(code - 26700, south: false, d("NAD27"))
        case 28348...28358: return utm(code - 28300, south: true, d("GDA94"))
        case 7846...7859:   return utm(code - 7800, south: true, d("GDA2020"))
        default:
            if let datum = GeoDatum.matchingEpsgGeographic(code),
               [4326, 4269, 4283, 7844].contains(code) {
                return GcsParseResult(status: .ok, crs: .geographic, datum: datum)
            }
            return .fallback(.unsupportedEpsg)
        }
    }

    // MARK: - WKT (1, OGC + ESRI flavours)

    static func parse(wkt: String) -> GcsParseResult {
        guard let root = WktNode.parse(wkt) else { return .fallback(.unreadableGcs) }
        switch root.keyword {
        case "GEOGCS":
            guard let geog = readGeogcs(root) else { return .fallback(.unreadableGcs) }
            if geog.primeMeridianBroken { return GcsParseResult(status: .malformed) }
            switch geog.datum {
            case .unreadable:
                return .fallback(.unreadableGcs, primeMeridian: geog.primeMeridian)
            case .unknown:
                return GcsParseResult(status: .unknownDatum, crs: .geographic,
                                      primeMeridian: geog.primeMeridian)
            case .resolved(let datum, let assumed):
                return GcsParseResult(status: .ok, crs: .geographic, datum: datum,
                                      datumAssumed: assumed, primeMeridian: geog.primeMeridian)
            }

        case "PROJCS":
            guard let geogNode = root.child("GEOGCS"), let geog = readGeogcs(geogNode) else {
                return .fallback(.unreadableGcs)
            }
            if geog.primeMeridianBroken { return GcsParseResult(status: .malformed) }
            let unit = root.child("UNIT")?.number(at: 1) ?? 1
            guard unit.isFinite, unit > 0 else {
                // projection's unusable but the GEOGCS read fine, keep its datum
                switch geog.datum {
                case .resolved(let datum, let assumed):
                    return .fallback(.unreadableGcs, datum: datum, assumed: assumed, primeMeridian: geog.primeMeridian)
                case .unknown:
                    return GcsParseResult(status: .unknownDatum, primeMeridian: geog.primeMeridian)
                case .unreadable:
                    return .fallback(.unreadableGcs, primeMeridian: geog.primeMeridian)
                }
            }
            // the projection wants an ellipsoid even when the datum is a dud
            // (unknownDatum still reports the crs), so use the WKT spheroid
            let spheroid: Ellipsoid?
            switch geog.datum {
            case .resolved(let datum, _): spheroid = datum.ellipsoid
            default: spheroid = geog.spheroid
            }
            let projection = readProjection(root, unitMetres: unit, ellipsoid: spheroid,
                                            primeMeridian: geog.primeMeridian)
            switch (geog.datum, projection) {
            case (.unreadable, _):
                return .fallback(.unreadableGcs, primeMeridian: geog.primeMeridian)
            case (.unknown, .some(.ok(let crs))):
                return GcsParseResult(status: .unknownDatum, crs: crs, primeMeridian: geog.primeMeridian,
                                      linearUnitMetres: unit)
            case (.unknown, _):
                return GcsParseResult(status: .unknownDatum, primeMeridian: geog.primeMeridian,
                                      linearUnitMetres: unit)
            case (.resolved(let datum, let assumed), .some(.ok(let crs))):
                return GcsParseResult(status: .ok, crs: crs, datum: datum, datumAssumed: assumed,
                                      primeMeridian: geog.primeMeridian, linearUnitMetres: unit)
            case (.resolved(let datum, let assumed), .some(.unsupported)):
                return .fallback(.unsupportedProjection, datum: datum, assumed: assumed,
                                 primeMeridian: geog.primeMeridian)
            case (.resolved(let datum, let assumed), nil):
                return .fallback(.unreadableGcs, datum: datum, assumed: assumed,
                                 primeMeridian: geog.primeMeridian)
            }

        default:
            return .fallback(.unreadableGcs)
        }
    }

    private enum DatumRead {
        case resolved(GeoDatum, assumed: Bool)
        case unknown
        case unreadable
    }

    private struct Geogcs {
        var datum: DatumRead
        var spheroid: Ellipsoid?
        var primeMeridian: Double
        var primeMeridianBroken: Bool
    }

    private static func readGeogcs(_ node: WktNode) -> Geogcs? {
        var pm = 0.0
        var pmBroken = false
        if let primem = node.child("PRIMEM") {
            if let v = primem.number(at: 1), v.isFinite, (-180.0...180.0).contains(v) {
                pm = v
            } else {
                pmBroken = true
            }
        }
        // no DATUM, one with no quoted name, or one with no SPHEROID/ELLIPSOID (PROJ
        // refuses that even for a known name) is an unreadable GCS (gcs.rules.unreadableDatum)
        guard let datumNode = node.child("DATUM"), let name = datumNode.string(at: 0),
              let sph = datumNode.child("SPHEROID") ?? datumNode.child("ELLIPSOID") else {
            return Geogcs(datum: .unreadable, spheroid: nil, primeMeridian: pm, primeMeridianBroken: pmBroken)
        }
        var spheroid: Ellipsoid?
        if let a = sph.number(at: 1), let invF = sph.number(at: 2), invF > 0 {
            let e = Ellipsoid(a: a, invF: invF)
            spheroid = e.isPlausibleEarth ? e : nil
        }
        // a name we know always wins, TOWGS84 or not
        if let known = GeoDatum.matchingWktName(name) {
            return Geogcs(datum: .resolved(known, assumed: false), spheroid: spheroid,
                          primeMeridian: pm, primeMeridianBroken: pmBroken)
        }
        guard let spheroid, let a = sph.number(at: 1), let invF = sph.number(at: 2) else {
            return Geogcs(datum: .unreadable, spheroid: nil, primeMeridian: pm, primeMeridianBroken: pmBroken)
        }
        // then TOWGS84 with no rotation/scale, a plain 3 param shift. exactly 3
        // values, or 7 with the last 4 zero. any other length is ignored
        if let t = datumNode.child("TOWGS84") {
            let vals = t.numbers
            let zeroRest = vals.count == 3 || (vals.count == 7 && vals[3...6].allSatisfy { $0 == 0 })
            if zeroRest, let custom = GeoDatum.custom(a: a, invF: invF, dx: vals[0], dy: vals[1], dz: vals[2]) {
                return Geogcs(datum: .resolved(custom, assumed: false), spheroid: spheroid,
                              primeMeridian: pm, primeMeridianBroken: pmBroken)
            }
        }
        // last, a modern geocentric ellipsoid: call it zero shift but say so
        let modern = [Ellipsoid.wgs84, .grs80].contains { m in
            abs(a - m.a) <= 1 && abs(invF - 1 / m.f) <= 1e-6
        }
        if modern, let custom = GeoDatum.custom(a: a, invF: invF, dx: 0, dy: 0, dz: 0) {
            return Geogcs(datum: .resolved(custom, assumed: true), spheroid: spheroid,
                          primeMeridian: pm, primeMeridianBroken: pmBroken)
        }
        return Geogcs(datum: .unknown, spheroid: spheroid, primeMeridian: pm, primeMeridianBroken: pmBroken)
    }

    private enum ProjectionRead {
        case ok(GeoCrs)
        case unsupported
    }

    /// nil = a projection we'd do but its parameters are missing/garbage
    private static func readProjection(_ projcs: WktNode, unitMetres: Double,
                                       ellipsoid: Ellipsoid?, primeMeridian: Double) -> ProjectionRead? {
        guard let name = projcs.child("PROJECTION")?.string(at: 0) else { return nil }
        var params: [String: Double] = [:]
        for p in projcs.children where p.keyword == "PARAMETER" {
            guard let key = p.string(at: 0), let v = p.number(at: 1) else { return nil }
            params[normalised(key)] = v
        }
        guard params.values.allSatisfy(\.isFinite) else { return nil }
        let fe = (params["false_easting"] ?? 0) * unitMetres
        let fn = (params["false_northing"] ?? 0) * unitMetres
        let lat0 = params["latitude_of_origin"] ?? params["latitude_of_center"] ?? 0
        // PROJ's aliases, central_meridian wins wherever it's written
        guard let cm = params["central_meridian"] ?? params["longitude_of_origin"] ?? params["longitude_of_center"] else {
            // every kind we support needs a central meridian, 0 by default would be a silent guess
            return supported(normalised(name)) ? nil : .unsupported
        }
        // relative to PRIMEM, not wrapped (gcs.rules.parameters)
        let lon0 = cm + primeMeridian
        let k0 = params["scale_factor"] ?? 1
        switch normalised(name) {
        case "transverse_mercator", "gauss_kruger":
            // ESRI writes Pulkovo / CGCS2000 GK zones as Gauss_Kruger, GDAL reads it as plain TM
            return .ok(.transverseMercator(lat0: lat0, lon0: lon0, k0: k0, fe: fe, fn: fn))
        case "lambert_conformal_conic_2sp":
            guard let p1 = params["standard_parallel_1"], let p2 = params["standard_parallel_2"] else { return nil }
            return .ok(.lambertConformalConic2SP(lat1: p1, lat2: p2, lat0: lat0, lon0: lon0, fe: fe, fn: fn))
        case "lambert_conformal_conic_1sp":
            return .ok(.lambertConformalConic1SP(lat0: lat0, lon0: lon0, k0: k0, fe: fe, fn: fn))
        case "lambert_conformal_conic":
            // ESRI spelling: two parallels means 2SP, otherwise 1SP + Scale_Factor
            if let p1 = params["standard_parallel_1"], let p2 = params["standard_parallel_2"] {
                return .ok(.lambertConformalConic2SP(lat1: p1, lat2: p2, lat0: lat0, lon0: lon0, fe: fe, fn: fn))
            }
            let origin = params["latitude_of_origin"] ?? params["standard_parallel_1"] ?? 0
            return .ok(.lambertConformalConic1SP(lat0: origin, lon0: lon0, k0: k0, fe: fe, fn: fn))
        case "mercator_1sp":
            return .ok(.mercator1SP(lon0: lon0, k0: k0, fe: fe, fn: fn))
        case "mercator_2sp", "mercator":
            // ESRI Mercator / 2SP carry a standard parallel, fold it into k0
            if let lts = params["standard_parallel_1"] {
                guard let ellipsoid, abs(lts) < 90 else { return nil }
                return .ok(.mercator1SP(lon0: lon0, k0: GeoCrs.mercatorK0(standardParallel: lts, ellipsoid: ellipsoid),
                                        fe: fe, fn: fn))
            }
            return .ok(.mercator1SP(lon0: lon0, k0: k0, fe: fe, fn: fn))
        default:
            return .unsupported
        }
    }

    private static func supported(_ name: String) -> Bool {
        ["transverse_mercator", "gauss_kruger", "lambert_conformal_conic_2sp", "lambert_conformal_conic_1sp",
         "lambert_conformal_conic", "mercator_1sp", "mercator_2sp", "mercator"].contains(name)
    }

    private static func normalised(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .replacingOccurrences(of: " ", with: "_")
    }
}

/// Tiny WKT1 tree: KEYWORD[ item, item, ... ] where an item is a quoted
/// string, a number, a bare word (EAST, NORTH) or another node. Brackets or
/// parens both allowed. Anything off-grammar -> nil, caller treats as unreadable.
struct WktNode {
    enum Item {
        case string(String)
        case number(Double)
        case word(String)
        case node(WktNode)
    }

    let keyword: String
    let items: [Item]

    var children: [WktNode] {
        items.compactMap { if case .node(let n) = $0 { return n } else { return nil } }
    }

    func child(_ keyword: String) -> WktNode? {
        children.first { $0.keyword == keyword }
    }

    /// nth item that's a string / number (skipping nothing, index is positional)
    func string(at index: Int) -> String? {
        guard index < items.count, case .string(let s) = items[index] else { return nil }
        return s
    }

    func number(at index: Int) -> Double? {
        guard index < items.count, case .number(let v) = items[index] else { return nil }
        return v
    }

    var numbers: [Double] {
        items.compactMap { if case .number(let v) = $0 { return v } else { return nil } }
    }

    // hostile-file guards, real WKT is a couple of KB and ~5 deep
    private static let maxLength = 64 * 1024
    private static let maxDepth = 24

    static func parse(_ text: String) -> WktNode? {
        guard text.utf8.count <= maxLength else { return nil }
        var p = Parser(scalars: Array(text.unicodeScalars))
        guard let node = p.node(depth: 0) else { return nil }
        p.skipSpace()
        return p.atEnd ? node : nil
    }

    private struct Parser {
        let scalars: [Unicode.Scalar]
        var i = 0

        var atEnd: Bool { i >= scalars.count }

        mutating func skipSpace() {
            while i < scalars.count, CharacterSet.whitespacesAndNewlines.contains(scalars[i]) { i += 1 }
        }

        mutating func node(depth: Int) -> WktNode? {
            guard depth < WktNode.maxDepth else { return nil }
            skipSpace()
            guard let kw = word(), !kw.isEmpty else { return nil }
            skipSpace()
            guard i < scalars.count, scalars[i] == "[" || scalars[i] == "(" else { return nil }
            let close: Unicode.Scalar = scalars[i] == "[" ? "]" : ")"
            i += 1
            var items: [Item] = []
            while true {
                skipSpace()
                guard i < scalars.count else { return nil }
                if scalars[i] == close, items.isEmpty { i += 1; break }
                guard let item = item(depth: depth) else { return nil }
                items.append(item)
                skipSpace()
                guard i < scalars.count else { return nil }
                if scalars[i] == "," { i += 1; continue }
                if scalars[i] == close { i += 1; break }
                return nil
            }
            return WktNode(keyword: kw.uppercased(), items: items)
        }

        mutating func item(depth: Int) -> Item? {
            skipSpace()
            guard i < scalars.count else { return nil }
            if scalars[i] == "\"" {
                i += 1
                var s = String.UnicodeScalarView()
                while i < scalars.count {
                    if scalars[i] == "\"" {
                        // "" is an escaped quote inside a WKT string
                        if i + 1 < scalars.count, scalars[i + 1] == "\"" { s.append("\""); i += 2; continue }
                        i += 1
                        return .string(String(s))
                    }
                    s.append(scalars[i]); i += 1
                }
                return nil
            }
            let start = i
            guard let w = word(), !w.isEmpty else { return nil }
            skipSpace()
            if i < scalars.count, scalars[i] == "[" || scalars[i] == "(" {
                i = start
                return node(depth: depth + 1).map(Item.node)
            }
            if let v = Double(w) { return .number(v) }
            return .word(w)
        }

        /// bare token up to a delimiter
        mutating func word() -> String? {
            var s = String.UnicodeScalarView()
            while i < scalars.count {
                let c = scalars[i]
                if c == "[" || c == "]" || c == "(" || c == ")" || c == "," || c == "\""
                    || CharacterSet.whitespacesAndNewlines.contains(c) { break }
                s.append(c); i += 1
            }
            return String(s)
        }
    }
}
