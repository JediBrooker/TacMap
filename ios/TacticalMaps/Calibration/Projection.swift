import Foundation
import CoreLocation
import MGRS
import Grid

/// Reference ellipsoid for a projection. We only carry the two values
/// (`a` semi-major, `f` flattening) needed by Snyder's formulas; everything
/// else (e², e'², secondary radii) is derived.
struct Ellipsoid: Hashable {
    /// Semi-major axis (m).
    let a: Double
    /// Flattening (1/x form computed at the call-site).
    let f: Double

    var e2: Double      { 2*f - f*f }
    var eDash2: Double  { e2 / (1 - e2) }
    var e: Double       { sqrt(e2) }

    // --- Common ellipsoids -----

    static let wgs84             = Ellipsoid(a: 6378137.0,   f: 1.0/298.257223563)
    static let grs80             = Ellipsoid(a: 6378137.0,   f: 1.0/298.257222101) // NAD83, GDA94, ETRS89
    static let airy1830          = Ellipsoid(a: 6377563.396, f: 1.0/299.3249646)   // OSGB36
    static let bessel1841        = Ellipsoid(a: 6377397.155, f: 1.0/299.1528128)   // CH1903, Tokyo
    static let international1924 = Ellipsoid(a: 6378388.0,   f: 1.0/297.0)         // ED50
    static let clarke1866        = Ellipsoid(a: 6378206.4,   f: 1.0/294.9786982)   // NAD27
    static let clarke1880IGN     = Ellipsoid(a: 6378249.2,   f: 1.0/293.4660213)   // NTF (legacy France)
    static let krassovsky1940    = Ellipsoid(a: 6378245.0,   f: 1.0/298.3)         // SK-42 (former USSR)

    /// Map an OGC GeoPDF 2-letter datum code to its associated ellipsoid.
    /// Returns WGS84 for unknowns. The ellipsoid fixes the *shape* of the
    /// inverse projection; `DatumShift.toWGS84` then translates the result onto
    /// WGS84 so legacy datums line up with the satellite basemap.
    static func forDatumCode(_ code: String) -> Ellipsoid {
        switch code.uppercased() {
        case "WE", "WD":         return .wgs84
        case "GD", "NA":         return .grs80           // GDA94 / NAD83
        case "OB", "OG", "OS":   return .airy1830        // OSGB36
        case "EU":               return .international1924 // ED50
        case "NS":               return .clarke1866       // NAD27
        case "TC":               return .bessel1841       // Tokyo (Japan)
        case "CH":               return .bessel1841       // CH1903
        case "NT", "NF":         return .clarke1880IGN    // NTF
        case "KK":               return .krassovsky1940   // SK-42
        default:                 return .wgs84
        }
    }
}

/// 3-parameter geocentric-translation datum shifts (M29) for the legacy datums
/// we decode out of GeoPDF LGIDicts.
///
/// `Projection.inverse` returns lat/lon on the *source* datum's ellipsoid.
/// Without a shift, legacy datums (OSGB36, NAD27, Tokyo, ED50, NTF, SK-42) land
/// 50–250 m from their true WGS84 position — enough to put a grid reference in
/// the wrong field. We apply the pure translation part of the Helmert transform
/// (Molodensky-Badekas without rotation/scale): it is *unambiguous* — additive
/// in ECEF, with none of the rotation-sign-convention traps of the 7-parameter
/// form — and closes the gap to ~5–20 m, which is well inside the visual
/// tolerance of the basemap. Modern datums (WGS84, GDA94/GRS80, NAD83) are
/// coincident with WGS84 to sub-metre and use a zero shift (identity fast-path).
enum DatumShift {

    /// `(sourceEllipsoid, dx, dy, dz)` keyed by OGC 2-letter datum code, where
    /// the translation takes source-ellipsoid ECEF → WGS84 ECEF (EPSG `towgs84`
    /// order/sign). Unlisted codes fall through to a zero shift (identity).
    static func params(for code: String) -> (ellipsoid: Ellipsoid, dx: Double, dy: Double, dz: Double) {
        switch code.uppercased() {
        case "WE", "WD":       return (.wgs84, 0, 0, 0)
        case "GD":             return (.grs80, 0, 0, 0)                                // GDA94 ≈ WGS84
        case "NA":             return (.grs80, 0, 0, 0)                                // NAD83 ≈ WGS84
        case "OB", "OG", "OS": return (.airy1830,          446.448, -125.157, 542.06) // OSGB36
        case "EU":             return (.international1924, -87.0,    -98.0,  -121.0)   // ED50 (mean)
        case "NS":             return (.clarke1866,         -8.0,    160.0,   176.0)   // NAD27 (CONUS)
        case "TC":             return (.bessel1841,       -146.414, 507.337, 680.507) // Tokyo (Japan)
        case "CH":             return (.bessel1841,        674.374,  15.056, 405.346) // CH1903
        case "NT", "NF":       return (.clarke1880IGN,    -168.0,   -60.0,   320.0)   // NTF (France)
        case "KK":             return (.krassovsky1940,     23.92,  -141.27,  -80.9)  // SK-42 / Pulkovo
        default:               return (.wgs84, 0, 0, 0)
        }
    }

    /// Shift a coordinate produced by `Projection.inverse` (expressed on the
    /// source datum, named by its 2-letter OGC code) to WGS84. A zero-translation
    /// datum returns the input unchanged, so modern sheets are byte-for-byte
    /// untouched.
    static func toWGS84(lat: Double, lon: Double, datumCode code: String) -> (lat: Double, lon: Double) {
        let (src, dx, dy, dz) = params(for: code)
        return toWGS84(lat: lat, lon: lon, sourceEllipsoid: src, dx: dx, dy: dy, dz: dz)
    }

    /// Shift a coordinate to WGS84 using an *explicit* source ellipsoid and
    /// geocentric-translation (dx, dy, dz). Used for GeoPDFs that carry the datum
    /// as an inline `/Datum` dictionary with an embedded `/ToWGS84` — the form
    /// USGS US Topo and many OGC GeoPDFs use — instead of a 2-letter code. A
    /// zero translation is an identity fast-path.
    static func toWGS84(lat: Double, lon: Double,
                        sourceEllipsoid: Ellipsoid,
                        dx: Double, dy: Double, dz: Double) -> (lat: Double, lon: Double) {
        if dx == 0, dy == 0, dz == 0 { return (lat, lon) }
        let (x, y, z) = geodeticToECEF(lat: lat, lon: lon, ellipsoid: sourceEllipsoid)
        return ecefToGeodetic(x: x + dx, y: y + dy, z: z + dz, ellipsoid: .wgs84)
    }

    /// Geodetic (deg, height 0) → ECEF on the given ellipsoid.
    private static func geodeticToECEF(lat: Double, lon: Double, ellipsoid e: Ellipsoid) -> (Double, Double, Double) {
        let phi = lat * .pi / 180, lam = lon * .pi / 180
        let sinPhi = sin(phi), cosPhi = cos(phi)
        let N = e.a / (1 - e.e2 * sinPhi * sinPhi).squareRoot()
        return (N * cosPhi * cos(lam), N * cosPhi * sin(lam), N * (1 - e.e2) * sinPhi)
    }

    /// ECEF → geodetic (deg) on the given ellipsoid (Bowring fixed-point, h≈0).
    private static func ecefToGeodetic(x: Double, y: Double, z: Double, ellipsoid e: Ellipsoid) -> (lat: Double, lon: Double) {
        let lon = atan2(y, x)
        let p = (x * x + y * y).squareRoot()
        var lat = atan2(z, p * (1 - e.e2))
        for _ in 0..<6 {
            let sinLat = sin(lat)
            let N = e.a / (1 - e.e2 * sinLat * sinLat).squareRoot()
            lat = atan2(z + e.e2 * N * sinLat, p)
        }
        return (lat * 180 / .pi, lon * 180 / .pi)
    }
}

/// All map projections we decode out of LGIDict / Measure dictionaries.
///
/// `inverse(easting:northing:)` returns lat/lon *on the source datum's
/// ellipsoid*. For modern datums (GDA94, NAD83, ETRS89) that is WGS84 to
/// sub-metre; for legacy datums (OSGB36, NAD27, Tokyo, NTF) the caller must
/// finish with `DatumShift.toWGS84(...)` to remove the 50–250 m offset.
enum Projection {

    /// Geographic: x = longitude, y = latitude (degrees).
    case longLat

    /// Universal Transverse Mercator — routed through NGA's UTM helper.
    case utm(zone: Int, hemisphere: Hemisphere, ellipsoid: Ellipsoid = .wgs84)

    /// General Transverse Mercator with arbitrary central meridian + scale.
    /// Snyder pp. 60–64 (inverse series). Covers UK OSGB36, NZ NZTM2000,
    /// Swiss CH1903+, Irish ITM, German DHDN, French Lambert… wait, France
    /// is LCC. Anyway: this case handles non-UTM TM.
    case transverseMercator(centralMeridian: Double,   // degrees
                             originLatitude: Double,   // degrees
                             falseEasting: Double,     // metres
                             falseNorthing: Double,    // metres
                             scaleFactor: Double,      // typically 0.9996
                             ellipsoid: Ellipsoid)

    /// Lambert Conformal Conic with two standard parallels. Snyder pp.
    /// 104–110. Used by French IGN (RGF93 Lambert-93, the four older
    /// Lambert zones), US state plane (most states), Canadian NRCan,
    /// Spanish IGN, many ICAO aviation charts.
    case lambertConformalConic(stdParallel1: Double,   // degrees
                                stdParallel2: Double,   // degrees
                                originLatitude: Double, // degrees
                                centralMeridian: Double,// degrees
                                falseEasting: Double,   // metres
                                falseNorthing: Double,  // metres
                                ellipsoid: Ellipsoid)

    /// Convert projection-native (easting, northing) to WGS84 (lat, lon)
    /// degrees. Returns nil only when the projection math diverges (e.g.
    /// north pole for Mercator) — not for normal terrestrial inputs.
    func inverse(easting x: Double, northing y: Double) -> (lat: Double, lon: Double)? {
        switch self {
        case .longLat:
            return (y, x)

        case .utm(let zone, let hemisphere, _):
            let u = UTM(zone, hemisphere, x, y)
            let p = u.toPoint()
            return (p.latitude, p.longitude)

        case .transverseMercator(let lon0, let phi0, let fe, let fn, let k0, let ell):
            return tmInverse(x: x, y: y,
                              centralMeridian: lon0, originLatitude: phi0,
                              falseEasting: fe, falseNorthing: fn,
                              scaleFactor: k0, ellipsoid: ell)

        case .lambertConformalConic(let p1d, let p2d, let phi0d, let lon0d, let fe, let fn, let ell):
            return lccInverse(x: x, y: y,
                              stdParallel1: p1d, stdParallel2: p2d,
                              originLatitude: phi0d, centralMeridian: lon0d,
                              falseEasting: fe, falseNorthing: fn,
                              ellipsoid: ell)
        }
    }

    // MARK: - Snyder Transverse Mercator inverse (ellipsoidal)

    /// Snyder eq. 8-3 .. 8-10. Output in degrees.
    private func tmInverse(x: Double, y: Double,
                            centralMeridian: Double,
                            originLatitude: Double,
                            falseEasting: Double,
                            falseNorthing: Double,
                            scaleFactor: Double,
                            ellipsoid: Ellipsoid) -> (lat: Double, lon: Double)? {
        let a   = ellipsoid.a
        let e2  = ellipsoid.e2
        let eD2 = ellipsoid.eDash2
        let k0  = scaleFactor
        let lon0 = centralMeridian * .pi / 180
        let phi0 = originLatitude  * .pi / 180

        let xE = x - falseEasting
        let yN = y - falseNorthing

        // Meridional arc from equator to origin.
        let M0 = meridionalArc(phi: phi0, a: a, e2: e2)
        let M  = M0 + yN / k0

        // Footprint latitude phi_1 via Snyder series in mu.
        let mu = M / (a * (1 - e2/4 - 3*e2*e2/64 - 5*e2*e2*e2/256))
        let e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        let e1_2 = e1*e1, e1_3 = e1_2*e1, e1_4 = e1_2*e1_2
        let phi1 = mu
            + (3*e1/2 - 27*e1_3/32) * sin(2*mu)
            + (21*e1_2/16 - 55*e1_4/32) * sin(4*mu)
            + (151*e1_3/96) * sin(6*mu)
            + (1097*e1_4/512) * sin(8*mu)

        let sP1 = sin(phi1), cP1 = cos(phi1), tP1 = tan(phi1)
        // Near the pole cos(phi1) → 0 and the longitude series divides by it,
        // producing non-finite garbage. The contract is to return nil when the
        // math diverges, so bail instead of handing back a bogus coordinate.
        guard abs(cP1) > 1e-12 else { return nil }
        let onemE2sin2 = 1 - e2*sP1*sP1
        let N1 = a / sqrt(onemE2sin2)
        let T1 = tP1*tP1
        let C1 = eD2 * cP1*cP1
        let R1 = a * (1 - e2) / pow(onemE2sin2, 1.5)
        let D  = xE / (N1 * k0)
        let D2 = D*D, D3 = D2*D, D4 = D2*D2, D5 = D4*D, D6 = D4*D2

        let phi = phi1 - (N1 * tP1 / R1) * (
            D2/2
            - (5 + 3*T1 + 10*C1 - 4*C1*C1 - 9*eD2) * D4 / 24
            + (61 + 90*T1 + 298*C1 + 45*T1*T1 - 252*eD2 - 3*C1*C1) * D6 / 720
        )
        let lam = lon0 + (D
            - (1 + 2*T1 + C1) * D3 / 6
            + (5 - 2*C1 + 28*T1 - 3*C1*C1 + 8*eD2 + 24*T1*T1) * D5 / 120
        ) / cP1

        let latDeg = phi * 180 / .pi
        let lonDeg = lam * 180 / .pi
        guard latDeg.isFinite, lonDeg.isFinite else { return nil }
        return (latDeg, lonDeg)
    }

    // MARK: - Snyder Lambert Conformal Conic inverse (ellipsoidal, 2-parallel)

    /// Snyder eq. 15-1 .. 15-10. Output in degrees.
    private func lccInverse(x: Double, y: Double,
                             stdParallel1: Double,
                             stdParallel2: Double,
                             originLatitude: Double,
                             centralMeridian: Double,
                             falseEasting: Double,
                             falseNorthing: Double,
                             ellipsoid: Ellipsoid) -> (lat: Double, lon: Double)? {
        let a   = ellipsoid.a
        let e   = ellipsoid.e
        let e2  = ellipsoid.e2
        let lon0 = centralMeridian * .pi / 180
        let phi0 = originLatitude  * .pi / 180
        let phi1 = stdParallel1   * .pi / 180
        let phi2 = stdParallel2   * .pi / 180

        // Conformal latitude helpers.
        func m(_ phi: Double) -> Double {
            cos(phi) / sqrt(1 - e2 * sin(phi) * sin(phi))
        }
        func t(_ phi: Double) -> Double {
            tan(.pi/4 - phi/2) /
            pow((1 - e*sin(phi)) / (1 + e*sin(phi)), e/2)
        }

        let m1 = m(phi1), m2 = m(phi2)
        let t0 = t(phi0), t1 = t(phi1), t2 = t(phi2)

        // For "1-parallel" LCC where phi1 == phi2 the formula reduces to
        // n = sin(phi1). Use that to avoid the log(m1/m2) divide-by-zero.
        let n: Double
        if abs(phi1 - phi2) < 1e-10 {
            n = sin(phi1)
        } else {
            n = (log(m1) - log(m2)) / (log(t1) - log(t2))
        }
        guard n != 0 else { return nil }

        let F = m1 / (n * pow(t1, n))
        let rho0 = a * F * pow(t0, n)

        let xE = x - falseEasting
        let yN = y - falseNorthing

        // rho carries the sign of n so southern-origin charts still resolve.
        let dy = rho0 - yN
        let rho = (n >= 0 ? 1.0 : -1.0) * sqrt(xE*xE + dy*dy)
        guard rho != 0 else { return nil }

        let theta = atan2((n >= 0 ? xE : -xE), (n >= 0 ? dy : -dy))
        let tValue = pow(rho / (a * F), 1.0 / n)

        // Iterate Snyder eq. 15-9 until convergence.
        var phi = .pi/2 - 2 * atan(tValue)
        for _ in 0..<12 {
            let next = .pi/2 - 2 * atan(tValue * pow((1 - e*sin(phi)) / (1 + e*sin(phi)), e/2))
            if abs(next - phi) < 1e-12 {
                phi = next
                break
            }
            phi = next
        }

        let lam = theta / n + lon0
        return (phi * 180 / .pi, lam * 180 / .pi)
    }

    // MARK: - Meridional arc (shared)

    private func meridionalArc(phi: Double, a: Double, e2: Double) -> Double {
        let e4 = e2*e2, e6 = e4*e2
        return a * (
            (1 - e2/4 - 3*e4/64 - 5*e6/256) * phi
            - (3*e2/8 + 3*e4/32 + 45*e6/1024) * sin(2*phi)
            + (15*e4/256 + 45*e6/1024) * sin(4*phi)
            - (35*e6/3072) * sin(6*phi)
        )
    }
}
