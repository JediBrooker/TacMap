import Foundation

/// Reference ellipsoid. Just a and f, everything else is derived.
struct Ellipsoid: Hashable, Sendable {
    /// Semi-major axis (m).
    let a: Double
    /// Flattening.
    let f: Double

    init(a: Double, f: Double) {
        self.a = a
        self.f = f
    }

    init(a: Double, invF: Double) {
        self.init(a: a, f: 1.0 / invF)
    }

    var e2: Double      { 2*f - f*f }
    var eDash2: Double  { e2 / (1 - e2) }
    var e: Double       { sqrt(e2) }

    /// Sane-looking earth ellipsoid. Anything else is garbage metadata and
    /// the projection maths would happily return nonsense for it.
    var isPlausibleEarth: Bool {
        a.isFinite && (6_000_000.0...7_000_000.0).contains(a)
            && f.isFinite && f > 0 && f < 0.01
    }

    static let wgs84             = Ellipsoid(a: 6378137.0,   invF: 298.257223563)
    static let grs80             = Ellipsoid(a: 6378137.0,   invF: 298.257222101)
    static let airy1830          = Ellipsoid(a: 6377563.396, invF: 299.3249646)
    static let bessel1841        = Ellipsoid(a: 6377397.155, invF: 299.1528128)
    static let international1924 = Ellipsoid(a: 6378388.0,   invF: 297.0)
    // EPSG derives these two invF from a,b. the old truncated values were
    // sub-micron off, harmless, but the shared table pins the full ones now
    static let clarke1866        = Ellipsoid(a: 6378206.4,   invF: 294.978698213898)
    static let clarke1880IGN     = Ellipsoid(a: 6378249.2,   invF: 293.466021293627)
    static let krassovsky1940    = Ellipsoid(a: 6378245.0,   invF: 298.3)
}

/// Geodetic datum: an ellipsoid plus the geocentric shift that lands it on
/// WGS84. This is THE datum table for both GeoPDF paths and calibration, it
/// mirrors testdata/pdf_georef.json datums.table row for row (and Android's
/// copy), so if you touch a number here the shared fixture test will yell.
struct GeoDatum: Hashable, Sendable {

    enum Transform: String, Codable, Sendable {
        case identity
        case translation3
        /// 7 param, coordinate frame convention, rotations in arcsec.
        case helmert7
    }

    static let customID = "custom"

    /// Table id (WGS84, NAD27, ...) or "custom" for one read off a WKT TOWGS84
    /// or an inline LGIDict datum dict.
    let id: String
    let a: Double
    let invF: Double
    let transform: Transform
    let dx: Double
    let dy: Double
    let dz: Double
    let rxArcsec: Double
    let ryArcsec: Double
    let rzArcsec: Double
    let scalePpm: Double

    var ellipsoid: Ellipsoid { Ellipsoid(a: a, invF: invF) }
    var isCustom: Bool { id == Self.customID }

    private init(id: String, a: Double, invF: Double, transform: Transform,
                 dx: Double = 0, dy: Double = 0, dz: Double = 0,
                 rx: Double = 0, ry: Double = 0, rz: Double = 0, scalePpm: Double = 0) {
        self.id = id
        self.a = a
        self.invF = invF
        self.transform = transform
        self.dx = dx; self.dy = dy; self.dz = dz
        self.rxArcsec = rx; self.ryArcsec = ry; self.rzArcsec = rz
        self.scalePpm = scalePpm
    }

    /// Datum we don't have a row for: its own ellipsoid + a plain 3 param
    /// shift. Zero shift collapses to identity so it's a no-op on the fast path.
    static func custom(a: Double, invF: Double, dx: Double, dy: Double, dz: Double) -> GeoDatum? {
        guard Ellipsoid(a: a, invF: invF).isPlausibleEarth,
              [dx, dy, dz].allSatisfy({ $0.isFinite && abs($0) < 10_000 }) else { return nil }
        let zero = dx == 0 && dy == 0 && dz == 0
        return GeoDatum(id: customID, a: a, invF: invF,
                        transform: zero ? .identity : .translation3,
                        dx: dx, dy: dy, dz: dz)
    }

    // MARK: - shift

    /// Source geodetic (h = 0) -> WGS84 geodetic, height dropped.
    func toWGS84(lat: Double, lon: Double) -> (lat: Double, lon: Double)? {
        guard lat.isFinite, lon.isFinite else { return nil }
        switch transform {
        case .identity:
            return (lat, lon)
        case .translation3:
            let p = Self.ecef(lat: lat, lon: lon, ellipsoid: ellipsoid)
            return Self.geodetic(x: p.x + dx, y: p.y + dy, z: p.z + dz, ellipsoid: .wgs84)
        case .helmert7:
            let p = Self.ecef(lat: lat, lon: lon, ellipsoid: ellipsoid)
            let m = helmertMatrix
            let x = dx + m[0][0] * p.x + m[0][1] * p.y + m[0][2] * p.z
            let y = dy + m[1][0] * p.x + m[1][1] * p.y + m[1][2] * p.z
            let z = dz + m[2][0] * p.x + m[2][1] * p.y + m[2][2] * p.z
            return Self.geodetic(x: x, y: y, z: z, ellipsoid: .wgs84)
        }
    }

    /// WGS84 geodetic -> this datum. helmert7 is the proper 3x3 inverse, not
    /// flipping all seven signs (that's ~3 mm out on OSGB36, fails 1e-9 deg).
    func fromWGS84(lat: Double, lon: Double) -> (lat: Double, lon: Double)? {
        guard lat.isFinite, lon.isFinite else { return nil }
        switch transform {
        case .identity:
            return (lat, lon)
        case .translation3:
            let p = Self.ecef(lat: lat, lon: lon, ellipsoid: .wgs84)
            return Self.geodetic(x: p.x - dx, y: p.y - dy, z: p.z - dz, ellipsoid: ellipsoid)
        case .helmert7:
            let p = Self.ecef(lat: lat, lon: lon, ellipsoid: .wgs84)
            guard let inv = Self.invert3x3(helmertMatrix) else { return nil }
            let vx = p.x - dx, vy = p.y - dy, vz = p.z - dz
            let x = inv[0][0] * vx + inv[0][1] * vy + inv[0][2] * vz
            let y = inv[1][0] * vx + inv[1][1] * vy + inv[1][2] * vz
            let z = inv[2][0] * vx + inv[2][1] * vy + inv[2][2] * vz
            return Self.geodetic(x: x, y: y, z: z, ellipsoid: ellipsoid)
        }
    }

    /// M = (1 + s) R, coordinate frame rotation.
    private var helmertMatrix: [[Double]] {
        let arc = Double.pi / 180 / 3600
        let rx = rxArcsec * arc, ry = ryArcsec * arc, rz = rzArcsec * arc
        let s = 1 + scalePpm * 1e-6
        return [
            [s,       s * rz,  -s * ry],
            [-s * rz, s,        s * rx],
            [s * ry,  -s * rx,  s]
        ]
    }

    private static func invert3x3(_ m: [[Double]]) -> [[Double]]? {
        let c00 = m[1][1] * m[2][2] - m[1][2] * m[2][1]
        let c01 = m[1][2] * m[2][0] - m[1][0] * m[2][2]
        let c02 = m[1][0] * m[2][1] - m[1][1] * m[2][0]
        let det = m[0][0] * c00 + m[0][1] * c01 + m[0][2] * c02
        guard det.isFinite, abs(det) > 1e-12 else { return nil }
        let inv = 1 / det
        return [
            [c00 * inv, (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * inv, (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * inv],
            [c01 * inv, (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * inv, (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * inv],
            [c02 * inv, (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * inv, (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * inv]
        ]
    }

    static func ecef(lat: Double, lon: Double, ellipsoid e: Ellipsoid) -> (x: Double, y: Double, z: Double) {
        let phi = lat * .pi / 180, lam = lon * .pi / 180
        let sinPhi = sin(phi), cosPhi = cos(phi)
        let n = e.a / (1 - e.e2 * sinPhi * sinPhi).squareRoot()
        return (n * cosPhi * cos(lam), n * cosPhi * sin(lam), n * (1 - e.e2) * sinPhi)
    }

    /// ECEF -> geodetic, height dropped. tan(lat) = (z + e2 N sin lat) / p is
    /// exact for any height so the fixed point converges to the real answer,
    /// ~e2 per step, few iterations and we're at the double floor.
    static func geodetic(x: Double, y: Double, z: Double, ellipsoid e: Ellipsoid) -> (lat: Double, lon: Double)? {
        guard x.isFinite, y.isFinite, z.isFinite else { return nil }
        let lon = atan2(y, x)
        let p = (x * x + y * y).squareRoot()
        var lat = atan2(z, p * (1 - e.e2))
        for _ in 0..<40 {
            let s = sin(lat)
            let n = e.a / (1 - e.e2 * s * s).squareRoot()
            let next = atan2(z + e.e2 * n * s, p)
            if abs(next - lat) < 1e-16 { lat = next; break }
            lat = next
        }
        let latDeg = lat * 180 / .pi, lonDeg = lon * 180 / .pi
        guard latDeg.isFinite, lonDeg.isFinite else { return nil }
        return (latDeg, lonDeg)
    }

    // MARK: - the shared table

    struct Aliases {
        let wktDatumNames: [String]
        let lgiCodes: [String]
        let lgiCodePrefixes: [String]
        let epsgDatum: Int?
        let epsgGeographic: [Int]
    }

    private struct Row {
        let datum: GeoDatum
        let aliases: Aliases
    }

    private static func datumRow(_ id: String, _ e: Ellipsoid, invF: Double, _ transform: Transform,
                            _ t: (Double, Double, Double) = (0, 0, 0),
                            rot: (Double, Double, Double) = (0, 0, 0), ppm: Double = 0,
                            wkt: [String], lgi: [String] = [], prefixes: [String] = [],
                            epsgDatum: Int? = nil, epsgGeographic: [Int] = []) -> Row {
        Row(datum: GeoDatum(id: id, a: e.a, invF: invF, transform: transform,
                            dx: t.0, dy: t.1, dz: t.2,
                            rx: rot.0, ry: rot.1, rz: rot.2, scalePpm: ppm),
            aliases: Aliases(wktDatumNames: wkt, lgiCodes: lgi, lgiCodePrefixes: prefixes,
                             epsgDatum: epsgDatum, epsgGeographic: epsgGeographic))
    }

    // order matters for prefix lookups (first hit wins), keep it the same as the fixture
    private static let rows: [Row] = [
        datumRow("WGS84", .wgs84, invF: 298.257223563, .identity,
            wkt: ["WGS_1984", "WGS 84", "WGS84", "World Geodetic System 1984", "D_WGS_1984"],
            // WD is really WGS72 in the NIMA table but nobody could confirm who
            // writes it, kept as WGS84 like before (flagged in the fixture)
            lgi: ["WE", "WGE", "WD"], epsgDatum: 6326, epsgGeographic: [4326]),
        datumRow("NAD83", .grs80, invF: 298.257222101, .identity,
            wkt: ["North_American_Datum_1983", "North American Datum 1983", "NAD83", "D_North_American_1983"],
            lgi: ["NA", "NAR"], prefixes: ["NAR-"], epsgDatum: 6269, epsgGeographic: [4269]),
        // ICSM GDA94 -> GDA2020, then GDA2020 taken as WGS84
        datumRow("GDA94", .grs80, invF: 298.257222101, .helmert7, (0.06155, -0.01087, -0.04019),
            rot: (-0.0394924, -0.0327221, -0.0328979), ppm: -0.009994,
            wkt: ["Geocentric_Datum_of_Australia_1994", "GDA94", "D_GDA_1994"],
            lgi: ["GD"], epsgDatum: 6283, epsgGeographic: [4283]),
        datumRow("GDA2020", .grs80, invF: 298.257222101, .identity,
            wkt: ["Geocentric_Datum_of_Australia_2020", "GDA2020", "D_GDA2020"],
            epsgDatum: 1168, epsgGeographic: [7844]),
        datumRow("ETRS89", .grs80, invF: 298.257222101, .identity,
            wkt: ["European_Terrestrial_Reference_System_1989", "ETRS89", "D_ETRS_1989"],
            epsgDatum: 6258, epsgGeographic: [4258]),
        // CONUS mean, any NAS-x without its own row falls back here
        datumRow("NAD27", .clarke1866, invF: 294.978698213898, .translation3, (-8, 160, 176),
            wkt: ["North_American_Datum_1927", "North American Datum 1927", "NAD27", "D_North_American_1927"],
            lgi: ["NS", "NAS", "NAS-C"], prefixes: ["NAS-"], epsgDatum: 6267, epsgGeographic: [4267]),
        datumRow("NAD27_CONUS_EAST", .clarke1866, invF: 294.978698213898, .translation3, (-9, 161, 179),
            wkt: [], lgi: ["NAS-A"]),
        datumRow("NAD27_CONUS_WEST", .clarke1866, invF: 294.978698213898, .translation3, (-8, 159, 175),
            wkt: [], lgi: ["NAS-B"]),
        datumRow("NAD27_ALASKA", .clarke1866, invF: 294.978698213898, .translation3, (-5, 135, 172),
            wkt: [], lgi: ["NAS-D"]),
        datumRow("NAD27_CANADA", .clarke1866, invF: 294.978698213898, .translation3, (-10, 158, 187),
            wkt: [], lgi: ["NAS-E"]),
        datumRow("ED50", .international1924, invF: 297.0, .translation3, (-87, -98, -121),
            wkt: ["European_Datum_1950", "European Datum 1950", "ED50", "D_European_1950"],
            lgi: ["EU", "EUR", "EUR-M"], prefixes: ["EUR-"], epsgDatum: 6230, epsgGeographic: [4230]),
        // full EPSG:1314, the old translation-only half was 13-15 m out
        datumRow("OSGB36", .airy1830, invF: 299.3249646, .helmert7, (446.448, -125.157, 542.06),
            rot: (-0.15, -0.247, -0.842), ppm: -20.489,
            wkt: ["OSGB_1936", "OSGB 1936", "OSGB36", "D_OSGB_1936", "Ordnance Survey of Great Britain 1936"],
            lgi: ["OB", "OG", "OS", "OGB", "OGB-M"], prefixes: ["OGB-"], epsgDatum: 6277, epsgGeographic: [4277]),
        datumRow("TOKYO", .bessel1841, invF: 299.1528128, .translation3, (-146.414, 507.337, 680.507),
            wkt: ["Tokyo", "D_Tokyo"], lgi: ["TC", "TOY"], prefixes: ["TOY-"],
            epsgDatum: 6301, epsgGeographic: [4301]),
        datumRow("CH1903", .bessel1841, invF: 299.1528128, .translation3, (674.374, 15.056, 405.346),
            wkt: ["CH1903", "D_CH1903"], lgi: ["CH"], epsgDatum: 6149, epsgGeographic: [4149]),
        datumRow("NTF", .clarke1880IGN, invF: 293.466021293627, .translation3, (-168, -60, 320),
            wkt: ["Nouvelle_Triangulation_Francaise", "NTF", "D_NTF"], lgi: ["NT", "NF"],
            epsgDatum: 6275, epsgGeographic: [4275]),
        // full EPSG:1267 (GOST R 51794-2001), coordinate frame
        datumRow("SK42", .krassovsky1940, invF: 298.3, .helmert7, (23.92, -141.27, -80.9),
            rot: (0, -0.35, -0.82), ppm: -0.12,
            wkt: ["Pulkovo_1942", "Pulkovo 1942", "SK-42", "SK42", "D_Pulkovo_1942"],
            lgi: ["KK", "SPK"], epsgDatum: 6284, epsgGeographic: [4284]),
    ]

    static let table: [GeoDatum] = rows.map(\.datum)
    static let wgs84 = rows[0].datum

    static func named(_ id: String) -> GeoDatum? {
        rows.first { $0.datum.id == id }?.datum
    }

    static func aliases(for id: String) -> Aliases? {
        rows.first { $0.datum.id == id }?.aliases
    }

    /// Strip a leading D_, uppercase, keep A-Z/0-9 only. Same on both sides.
    static func normalisedWktName(_ name: String) -> String {
        var s = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.uppercased().hasPrefix("D_") { s.removeFirst(2) }
        return String(s.uppercased().unicodeScalars.filter {
            ($0.value >= 65 && $0.value <= 90) || ($0.value >= 48 && $0.value <= 57)
        }.map(Character.init))
    }

    static func matchingWktName(_ name: String) -> GeoDatum? {
        let wanted = normalisedWktName(name)
        guard !wanted.isEmpty else { return nil }
        return rows.first { r in
            r.aliases.wktDatumNames.contains { normalisedWktName($0) == wanted }
        }?.datum
    }

    /// Exact code first, then the prefix families (NAS-x, EUR-x ...).
    static func matchingLgiCode(_ code: String) -> GeoDatum? {
        let c = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !c.isEmpty else { return nil }
        if let hit = rows.first(where: { $0.aliases.lgiCodes.contains(c) }) { return hit.datum }
        return rows.first { r in r.aliases.lgiCodePrefixes.contains { c.hasPrefix($0) } }?.datum
    }

    static func matchingEpsgGeographic(_ code: Int) -> GeoDatum? {
        rows.first { $0.aliases.epsgGeographic.contains(code) }?.datum
    }
}

extension GeoDatum: Codable {
    private enum CodingKeys: String, CodingKey { case id, a, invF, dx, dy, dz }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let id = try c.decode(String.self, forKey: .id)
        if id == Self.customID {
            guard let d = Self.custom(a: try c.decode(Double.self, forKey: .a),
                                      invF: try c.decode(Double.self, forKey: .invF),
                                      dx: try c.decode(Double.self, forKey: .dx),
                                      dy: try c.decode(Double.self, forKey: .dy),
                                      dz: try c.decode(Double.self, forKey: .dz)) else {
                throw DecodingError.dataCorruptedError(forKey: .a, in: c, debugDescription: "bad custom datum")
            }
            self = d
        } else {
            // table rows persist by id only, so a fixed constant reaches old saves too
            guard let d = Self.named(id) else {
                throw DecodingError.dataCorruptedError(forKey: .id, in: c, debugDescription: "unknown datum id")
            }
            self = d
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        if isCustom {
            try c.encode(a, forKey: .a)
            try c.encode(invF, forKey: .invF)
            try c.encode(dx, forKey: .dx)
            try c.encode(dy, forKey: .dy)
            try c.encode(dz, forKey: .dz)
        }
    }
}

/// Old entry point for the 2-letter LGIDict codes. Now just a view onto the
/// shared GeoDatum table so iOS and Android can't drift on the constants.
enum DatumShift {

    /// Unknown codes are an identity here (callers that care check
    /// GeoDatum.matchingLgiCode themselves and fail loud).
    static func toWGS84(lat: Double, lon: Double, datumCode code: String) -> (lat: Double, lon: Double) {
        guard let datum = GeoDatum.matchingLgiCode(code),
              let w = datum.toWGS84(lat: lat, lon: lon) else { return (lat, lon) }
        return w
    }

    /// Explicit ellipsoid + 3 param translation, the inline /Datum dict form.
    static func toWGS84(lat: Double, lon: Double,
                        sourceEllipsoid: Ellipsoid,
                        dx: Double, dy: Double, dz: Double) -> (lat: Double, lon: Double) {
        if dx == 0, dy == 0, dz == 0 { return (lat, lon) }
        let p = GeoDatum.ecef(lat: lat, lon: lon, ellipsoid: sourceEllipsoid)
        return GeoDatum.geodetic(x: p.x + dx, y: p.y + dy, z: p.z + dz, ellipsoid: .wgs84) ?? (lat, lon)
    }
}
