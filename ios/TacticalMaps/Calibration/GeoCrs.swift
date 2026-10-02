import Foundation

/// The plane a PdfGeoreference's affine maps into. Projected kinds are metres
/// (x east, y north), geographic is (lon, lat) degrees in the sheet datum.
/// Always runs on the datum's own ellipsoid, the caller passes it in.
///
/// Names + parameters line up with pdf_georef.json crs objects (and Android).
enum GeoCrs: Hashable, Sendable {
    case geographic
    case transverseMercator(lat0: Double, lon0: Double, k0: Double, fe: Double, fn: Double)
    case lambertConformalConic2SP(lat1: Double, lat2: Double, lat0: Double, lon0: Double, fe: Double, fn: Double)
    case lambertConformalConic1SP(lat0: Double, lon0: Double, k0: Double, fe: Double, fn: Double)
    /// Mercator 2SP folds into this via k0 = cos-scale of lat_ts (see mercatorK0).
    case mercator1SP(lon0: Double, k0: Double, fe: Double, fn: Double)

    static func utm(zone: Int, south: Bool) -> GeoCrs {
        .transverseMercator(lat0: 0, lon0: Double(zone) * 6 - 183, k0: 0.9996,
                            fe: 500_000, fn: south ? 10_000_000 : 0)
    }

    /// Zone + hemisphere when this TM is exactly a UTM zone.
    var utmZone: (zone: Int, south: Bool)? {
        guard case let .transverseMercator(lat0, lon0, k0, fe, fn) = self,
              lat0 == 0, k0 == 0.9996, fe == 500_000,
              fn == 0 || fn == 10_000_000 else { return nil }
        // range check the double first, Int() on a decoded junk lon0 would trap
        let z = (lon0 + 183) / 6
        guard z.isFinite, z == z.rounded(), z >= 1, z <= 60 else { return nil }
        return (Int(z), fn == 10_000_000)
    }

    var isGeographic: Bool {
        if case .geographic = self { return true }
        return false
    }

    /// k0 for Mercator 2SP folded into 1SP: cos(lat_ts) / sqrt(1 - e2 sin2(lat_ts)).
    static func mercatorK0(standardParallel: Double, ellipsoid: Ellipsoid) -> Double {
        let p = standardParallel * .pi / 180
        return cos(p) / (1 - ellipsoid.e2 * sin(p) * sin(p)).squareRoot()
    }

    // MARK: - forward / inverse

    /// (lat, lon) degrees -> plane. nil for junk input or somewhere the
    /// projection blows up (pole on Mercator, antipode on TM ...).
    func forward(lat: Double, lon: Double, ellipsoid: Ellipsoid) -> (x: Double, y: Double)? {
        guard lat.isFinite, lon.isFinite, (-90.0...90.0).contains(lat),
              ellipsoid.isPlausibleEarth else { return nil }
        let out: (x: Double, y: Double)?
        switch self {
        case .geographic:
            out = (lon, lat)
        case let .transverseMercator(lat0, lon0, k0, fe, fn):
            out = GeoCrs.tmForward(lat: lat, lon: lon, lat0: lat0, lon0: lon0, k0: k0, fe: fe, fn: fn, ellipsoid: ellipsoid)
        case let .lambertConformalConic2SP(lat1, lat2, lat0, lon0, fe, fn):
            out = LccConstants(lat1: lat1, lat2: lat2, lat0: lat0, k0: 1, ellipsoid: ellipsoid)?
                .forward(lat: lat, lon: lon, lon0: lon0, fe: fe, fn: fn)
        case let .lambertConformalConic1SP(lat0, lon0, k0, fe, fn):
            out = LccConstants(lat1: lat0, lat2: lat0, lat0: lat0, k0: k0, ellipsoid: ellipsoid)?
                .forward(lat: lat, lon: lon, lon0: lon0, fe: fe, fn: fn)
        case let .mercator1SP(lon0, k0, fe, fn):
            out = GeoCrs.mercForward(lat: lat, lon: lon, lon0: lon0, k0: k0, fe: fe, fn: fn, ellipsoid: ellipsoid)
        }
        guard let out, out.x.isFinite, out.y.isFinite else { return nil }
        return out
    }

    /// plane -> (lat, lon) degrees, lon wrapped into [-180, 180].
    func inverse(x: Double, y: Double, ellipsoid: Ellipsoid) -> (lat: Double, lon: Double)? {
        guard x.isFinite, y.isFinite, ellipsoid.isPlausibleEarth else { return nil }
        let out: (lat: Double, lon: Double)?
        switch self {
        case .geographic:
            out = (y, x)
        case let .transverseMercator(lat0, lon0, k0, fe, fn):
            out = GeoCrs.tmInverse(x: x, y: y, lat0: lat0, lon0: lon0, k0: k0, fe: fe, fn: fn, ellipsoid: ellipsoid)
        case let .lambertConformalConic2SP(lat1, lat2, lat0, lon0, fe, fn):
            out = LccConstants(lat1: lat1, lat2: lat2, lat0: lat0, k0: 1, ellipsoid: ellipsoid)?
                .inverse(x: x, y: y, lon0: lon0, fe: fe, fn: fn)
        case let .lambertConformalConic1SP(lat0, lon0, k0, fe, fn):
            out = LccConstants(lat1: lat0, lat2: lat0, lat0: lat0, k0: k0, ellipsoid: ellipsoid)?
                .inverse(x: x, y: y, lon0: lon0, fe: fe, fn: fn)
        case let .mercator1SP(lon0, k0, fe, fn):
            out = GeoCrs.mercInverse(x: x, y: y, lon0: lon0, k0: k0, fe: fe, fn: fn, ellipsoid: ellipsoid)
        }
        guard let out, out.lat.isFinite, out.lon.isFinite,
              (-90.0...90.0).contains(out.lat) else { return nil }
        let lon = GeoCrs.wrapLongitude(out.lon)
        guard (-180.0...180.0).contains(lon) else { return nil }
        return (out.lat, lon)
    }

    static func wrapLongitude(_ lon: Double) -> Double {
        guard lon < -180 || lon > 180 else { return lon }
        var l = (lon + 180).truncatingRemainder(dividingBy: 360)
        if l < 0 { l += 360 }
        return l - 180
    }

    // MARK: - Transverse Mercator, Krueger n-series to n^6 (Karney 2011)
    // same flavour as PROJ's poder_engsager, sub-micron inside a few deg of CM

    private struct Kruger {
        let a: Double          // rectifying radius A
        let alpha: [Double]    // 1...6, index 0 unused
        let beta: [Double]
        let e: Double

        init(_ ell: Ellipsoid) {
            let f = ell.f
            let n = f / (2 - f)
            let n2 = n * n, n3 = n2 * n, n4 = n3 * n, n5 = n4 * n, n6 = n5 * n
            a = ell.a / (1 + n) * (1 + n2 / 4 + n4 / 64 + n6 / 256)
            alpha = [0,
                     n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800,
                     13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360,
                     61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440,
                     49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600,
                     34729 * n5 / 80640 - 3418889 * n6 / 1995840,
                     212378941 * n6 / 319334400]
            beta = [0,
                    n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800,
                    n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720,
                    17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720,
                    4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600,
                    4583 * n5 / 161280 - 108847 * n6 / 3991680,
                    20648693 * n6 / 638668800]
            e = ell.e
        }

        /// tan of the conformal latitude
        func tauPrime(_ phi: Double) -> Double {
            let s = sin(phi)
            return sinh(atanh(s) - e * atanh(e * s))
        }

        /// Gauss-Krueger (xi, eta) scaled to unit A, before k0/false origin
        func forward(phi: Double, lam: Double) -> (xi: Double, eta: Double) {
            let t = tauPrime(phi)
            let xiP = atan2(t, cos(lam))
            let etaP = atanh(sin(lam) / (1 + t * t).squareRoot())
            var xi = xiP, eta = etaP
            for j in 1...6 {
                let jj = 2 * Double(j)
                xi += alpha[j] * sin(jj * xiP) * cosh(jj * etaP)
                eta += alpha[j] * cos(jj * xiP) * sinh(jj * etaP)
            }
            return (xi, eta)
        }

        func inverse(xi: Double, eta: Double) -> (phi: Double, lam: Double)? {
            var xiP = xi, etaP = eta
            for j in 1...6 {
                let jj = 2 * Double(j)
                xiP -= beta[j] * sin(jj * xi) * cosh(jj * eta)
                etaP -= beta[j] * cos(jj * xi) * sinh(jj * eta)
            }
            let tp = sin(xiP) / (sinh(etaP) * sinh(etaP) + cos(xiP) * cos(xiP)).squareRoot()
            guard tp.isFinite else { return nil }
            // newton on tau, Karney 2011 eq 19-21
            var t = tp
            let e2 = e * e
            for _ in 0..<15 {
                let s = sinh(e * atanh(e * t / (1 + t * t).squareRoot()))
                let tt = t * (1 + s * s).squareRoot() - s * (1 + t * t).squareRoot()
                let dt = (tp - tt) * (1 + (1 - e2) * t * t)
                    / ((1 - e2) * (1 + tt * tt).squareRoot() * (1 + t * t).squareRoot())
                t += dt
                if !(abs(dt) >= 1e-15) { break }
            }
            guard t.isFinite else { return nil }
            return (atan(t), atan2(sinh(etaP), cos(xiP)))
        }
    }

    private static func tmForward(lat: Double, lon: Double, lat0: Double, lon0: Double,
                                  k0: Double, fe: Double, fn: Double,
                                  ellipsoid: Ellipsoid) -> (x: Double, y: Double)? {
        guard validTM(lat0: lat0, lon0: lon0, k0: k0, fe: fe, fn: fn) else { return nil }
        let dLon = wrapLongitude(lon - lon0)
        // the series is garbage past the equator-crossing hemisphere, don't pretend
        guard abs(dLon) < 90 else { return nil }
        let k = Kruger(ellipsoid)
        let p = k.forward(phi: lat * .pi / 180, lam: dLon * .pi / 180)
        let origin = k.forward(phi: lat0 * .pi / 180, lam: 0)
        return (fe + k0 * k.a * p.eta, fn + k0 * k.a * (p.xi - origin.xi))
    }

    private static func tmInverse(x: Double, y: Double, lat0: Double, lon0: Double,
                                  k0: Double, fe: Double, fn: Double,
                                  ellipsoid: Ellipsoid) -> (lat: Double, lon: Double)? {
        guard validTM(lat0: lat0, lon0: lon0, k0: k0, fe: fe, fn: fn) else { return nil }
        let k = Kruger(ellipsoid)
        let origin = k.forward(phi: lat0 * .pi / 180, lam: 0)
        let xi = (y - fn) / (k0 * k.a) + origin.xi
        let eta = (x - fe) / (k0 * k.a)
        // way outside any sane TM strip (> ~ a quarter turn), bail out
        guard abs(eta) < 2.5, abs(xi) < 3.2, let g = k.inverse(xi: xi, eta: eta) else { return nil }
        return (g.phi * 180 / .pi, lon0 + g.lam * 180 / .pi)
    }

    private static func validTM(lat0: Double, lon0: Double, k0: Double, fe: Double, fn: Double) -> Bool {
        [lat0, lon0, k0, fe, fn].allSatisfy(\.isFinite)
            && (-90.0...90.0).contains(lat0) && (-360.0...360.0).contains(lon0)
            && k0 > 0 && k0 <= 10
    }

    // MARK: - Lambert Conformal Conic (EPSG 9801 / 9802)

    private struct LccConstants {
        let a: Double
        let e: Double
        let n: Double
        let aFk: Double    // a * F * k0
        let rho0: Double

        init?(lat1: Double, lat2: Double, lat0: Double, k0: Double, ellipsoid: Ellipsoid) {
            guard [lat1, lat2, lat0, k0].allSatisfy(\.isFinite), k0 > 0, k0 <= 10,
                  abs(lat1) < 89.999, abs(lat2) < 89.999, abs(lat0) < 89.999 else { return nil }
            let e = ellipsoid.e
            a = ellipsoid.a
            self.e = e
            func m(_ p: Double) -> Double { cos(p) / (1 - e * e * sin(p) * sin(p)).squareRoot() }
            func t(_ p: Double) -> Double {
                tan(.pi / 4 - p / 2) / pow((1 - e * sin(p)) / (1 + e * sin(p)), e / 2)
            }
            let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180, p0 = lat0 * .pi / 180
            let n: Double
            if abs(p1 - p2) < 1e-12 {
                // 1SP (or a 2SP written with equal parallels)
                n = sin(p1)
            } else {
                n = (log(m(p1)) - log(m(p2))) / (log(t(p1)) - log(t(p2)))
            }
            guard n.isFinite, abs(n) > 1e-12 else { return nil }
            self.n = n
            let f = m(p1) / (n * pow(t(p1), n))
            aFk = a * f * k0
            rho0 = aFk * pow(t(p0), n)
            guard aFk.isFinite, rho0.isFinite else { return nil }
        }

        func t(_ phi: Double) -> Double {
            tan(.pi / 4 - phi / 2) / pow((1 - e * sin(phi)) / (1 + e * sin(phi)), e / 2)
        }

        func forward(lat: Double, lon: Double, lon0: Double, fe: Double, fn: Double) -> (x: Double, y: Double)? {
            guard lon0.isFinite, fe.isFinite, fn.isFinite else { return nil }
            // the cone's apex pole blows up, the far pole is a point at infinity
            guard abs(lat) < 90 else { return nil }
            let rho = aFk * pow(t(lat * .pi / 180), n)
            let theta = n * wrapLongitude(lon - lon0) * .pi / 180
            return (fe + rho * sin(theta), fn + rho0 - rho * cos(theta))
        }

        func inverse(x: Double, y: Double, lon0: Double, fe: Double, fn: Double) -> (lat: Double, lon: Double)? {
            guard lon0.isFinite, fe.isFinite, fn.isFinite else { return nil }
            let sign: Double = n >= 0 ? 1 : -1
            let dx = x - fe, dy = rho0 - (y - fn)
            let rho = sign * (dx * dx + dy * dy).squareRoot()
            guard rho != 0 else { return (sign * 90, lon0) }
            let tt = pow(rho / aFk, 1 / n)
            let theta = atan2(sign * dx, sign * dy)
            guard tt.isFinite, tt > 0 else { return nil }
            var phi = .pi / 2 - 2 * atan(tt)
            for _ in 0..<40 {
                let es = e * sin(phi)
                let next = .pi / 2 - 2 * atan(tt * pow((1 - es) / (1 + es), e / 2))
                if abs(next - phi) < 1e-15 { phi = next; break }
                phi = next
            }
            return (phi * 180 / .pi, lon0 + theta / n * 180 / .pi)
        }
    }

    // MARK: - Mercator 1SP (EPSG 9804)

    private static func mercForward(lat: Double, lon: Double, lon0: Double, k0: Double,
                                    fe: Double, fn: Double, ellipsoid: Ellipsoid) -> (x: Double, y: Double)? {
        guard [lon0, k0, fe, fn].allSatisfy(\.isFinite), k0 > 0, k0 <= 10, abs(lat) < 89.999 else { return nil }
        let e = ellipsoid.e
        let phi = lat * .pi / 180
        let es = e * sin(phi)
        let y = log(tan(.pi / 4 + phi / 2) * pow((1 - es) / (1 + es), e / 2))
        let ak = ellipsoid.a * k0
        return (fe + ak * wrapLongitude(lon - lon0) * .pi / 180, fn + ak * y)
    }

    private static func mercInverse(x: Double, y: Double, lon0: Double, k0: Double,
                                    fe: Double, fn: Double, ellipsoid: Ellipsoid) -> (lat: Double, lon: Double)? {
        guard [lon0, k0, fe, fn].allSatisfy(\.isFinite), k0 > 0, k0 <= 10 else { return nil }
        let e = ellipsoid.e
        let ak = ellipsoid.a * k0
        let t = exp(-(y - fn) / ak)
        guard t.isFinite, t > 0 else { return nil }
        var phi = .pi / 2 - 2 * atan(t)
        for _ in 0..<40 {
            let es = e * sin(phi)
            let next = .pi / 2 - 2 * atan(t * pow((1 - es) / (1 + es), e / 2))
            if abs(next - phi) < 1e-15 { phi = next; break }
            phi = next
        }
        return (phi * 180 / .pi, lon0 + (x - fe) / ak * 180 / .pi)
    }
}

extension GeoCrs: Codable {
    private enum CodingKeys: String, CodingKey {
        case kind, lat0, lon0, k0, fe, fn, lat1, lat2, utmZone, hemisphere
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func d(_ k: CodingKeys) throws -> Double {
            let v = try c.decode(Double.self, forKey: k)
            guard v.isFinite else {
                throw DecodingError.dataCorruptedError(forKey: k, in: c, debugDescription: "non-finite")
            }
            return v
        }
        switch try c.decode(String.self, forKey: .kind) {
        case "geographic":
            self = .geographic
        case "transverseMercator":
            self = .transverseMercator(lat0: try d(.lat0), lon0: try d(.lon0), k0: try d(.k0),
                                       fe: try d(.fe), fn: try d(.fn))
        case "lambertConformalConic2SP":
            self = .lambertConformalConic2SP(lat1: try d(.lat1), lat2: try d(.lat2), lat0: try d(.lat0),
                                             lon0: try d(.lon0), fe: try d(.fe), fn: try d(.fn))
        case "lambertConformalConic1SP":
            self = .lambertConformalConic1SP(lat0: try d(.lat0), lon0: try d(.lon0), k0: try d(.k0),
                                             fe: try d(.fe), fn: try d(.fn))
        case "mercator1SP":
            self = .mercator1SP(lon0: try d(.lon0), k0: try d(.k0), fe: try d(.fe), fn: try d(.fn))
        default:
            throw DecodingError.dataCorruptedError(forKey: .kind, in: c, debugDescription: "unknown crs")
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .geographic:
            try c.encode("geographic", forKey: .kind)
        case let .transverseMercator(lat0, lon0, k0, fe, fn):
            try c.encode("transverseMercator", forKey: .kind)
            try c.encode(lat0, forKey: .lat0); try c.encode(lon0, forKey: .lon0)
            try c.encode(k0, forKey: .k0); try c.encode(fe, forKey: .fe); try c.encode(fn, forKey: .fn)
            if let z = utmZone {
                try c.encode(z.zone, forKey: .utmZone)
                try c.encode(z.south ? "S" : "N", forKey: .hemisphere)
            }
        case let .lambertConformalConic2SP(lat1, lat2, lat0, lon0, fe, fn):
            try c.encode("lambertConformalConic2SP", forKey: .kind)
            try c.encode(lat1, forKey: .lat1); try c.encode(lat2, forKey: .lat2)
            try c.encode(lat0, forKey: .lat0); try c.encode(lon0, forKey: .lon0)
            try c.encode(fe, forKey: .fe); try c.encode(fn, forKey: .fn)
        case let .lambertConformalConic1SP(lat0, lon0, k0, fe, fn):
            try c.encode("lambertConformalConic1SP", forKey: .kind)
            try c.encode(lat0, forKey: .lat0); try c.encode(lon0, forKey: .lon0)
            try c.encode(k0, forKey: .k0); try c.encode(fe, forKey: .fe); try c.encode(fn, forKey: .fn)
        case let .mercator1SP(lon0, k0, fe, fn):
            try c.encode("mercator1SP", forKey: .kind)
            try c.encode(lon0, forKey: .lon0); try c.encode(k0, forKey: .k0)
            try c.encode(fe, forKey: .fe); try c.encode(fn, forKey: .fn)
        }
    }
}
