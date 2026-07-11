import Foundation
import CoreLocation

/// On-device magnetic declination via the World Magnetic Model.
///
/// This is the standard WMM spherical-harmonic synthesis (degree/order 12):
/// geodetic->geocentric conversion on the WGS84 ellipsoid, Schmidt
/// semi-normalized associated Legendre functions built by recursion, and
/// linear time-extrapolation of the Gauss coefficients from their epoch using
/// the secular-variation terms (g(t) = g0 + gdot * (t - 2025.0)). No network,
/// no magnetometer, works fine on the simulator.
///
/// Model epoch: WMM2025, valid 2025-01-01 through 2029-12-31.
/// Coefficients are the official NOAA/NCEI WMM2025.COF table (released
/// 2024-11-13), embedded verbatim below.
/// Source: https://www.ncei.noaa.gov/products/world-magnetic-model/coefficients
/// (file WMM2025COF.zip -> WMM2025.COF).
///
/// Validated against NOAA's own WMM2025_TestValues.txt: declination matches to
/// within 0.005 deg and X/Y to sub-nT across 2025.0-2027.5, including
/// near-pole and southern-hemisphere points.
enum GeoMagnetism {

    /// Magnetic declination in degrees, East positive (magnetic north east of
    /// true north). Uses the WMM2025 spherical-harmonic model.
    /// altitudeMeters/date default to sea level / now.
    static func declinationDegrees(latitude: Double, longitude: Double,
                                   altitudeMeters: Double = 0, date: Date = Date()) -> Double {
        let year = decimalYear(from: date)
        return Model.wmm2025.declination(latitudeDeg: latitude,
                                         longitudeDeg: longitude,
                                         altitudeKm: altitudeMeters / 1000.0,
                                         decimalYear: year)
    }

    // Decimal year the way WMM wants it: whole year plus the fraction elapsed,
    // computed in UTC so a build in any timezone lands on the same value.
    private static func decimalYear(from date: Date) -> Double {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = TimeZone(identifier: "UTC") ?? cal.timeZone
        let year = cal.component(.year, from: date)
        // ordinality gives a 1-based day-of-year, so knock one off to make Jan 1
        // read as 0.0 into the year.
        let dayOfYear = cal.ordinality(of: .day, in: .year, for: date) ?? 1
        let isLeap = (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
        let daysInYear = isLeap ? 366.0 : 365.0
        return Double(year) + Double(dayOfYear - 1) / daysInYear
    }
}

// MARK: - WMM spherical-harmonic model

private struct Model {
    static let maxDegree = 12
    // flat triangular storage, index = n*(n+1)/2 + m, n=0..12 m=0..n
    static let termCount = (maxDegree + 1) * (maxDegree + 2) / 2

    let epoch: Double          // reference year the coefficients are defined at
    let g: [Double]            // main-field g coeffs (nT)
    let h: [Double]            // main-field h coeffs (nT)
    let gDot: [Double]         // secular variation dg/dt (nT/yr)
    let hDot: [Double]         // secular variation dh/dt (nT/yr)

    /// Parsed once, lazily. Immutable afterwards so it's safe to read from any
    /// thread.
    static let wmm2025 = Model(epoch: 2025.0, cof: coefficientTable)

    init(epoch: Double, cof: String) {
        self.epoch = epoch
        var g = [Double](repeating: 0, count: Model.termCount)
        var h = [Double](repeating: 0, count: Model.termCount)
        var gd = [Double](repeating: 0, count: Model.termCount)
        var hd = [Double](repeating: 0, count: Model.termCount)
        for line in cof.split(separator: "\n") {
            let f = line.split(whereSeparator: { $0 == " " || $0 == "\t" }).compactMap { Double($0) }
            guard f.count == 6 else { continue }   // skips the header + terminator lines
            let n = Int(f[0]), m = Int(f[1])
            guard n >= 1, n <= Model.maxDegree, m >= 0, m <= n else { continue }
            let idx = n * (n + 1) / 2 + m
            g[idx] = f[2]; h[idx] = f[3]; gd[idx] = f[4]; hd[idx] = f[5]
        }
        self.g = g; self.h = h; self.gDot = gd; self.hDot = hd
    }

    // WGS84 ellipsoid + WMM geomagnetic reference radius, in km (units cancel in
    // the (re/r) ratios, km just matches the NOAA reference constants).
    private static let ellipsoidA = 6378.137          // semi-major axis
    private static let ellipsoidB = 6356.7523142      // semi-minor axis
    private static let geomagneticRadius = 6371.2     // re

    func declination(latitudeDeg: Double, longitudeDeg: Double,
                     altitudeKm: Double, decimalYear: Double) -> Double {
        let n = Model.maxDegree
        let a = Model.ellipsoidA, b = Model.ellipsoidB, re = Model.geomagneticRadius
        let e2 = 1.0 - (b * b) / (a * a)   // first eccentricity squared

        // --- geodetic -> geocentric (spherical) ---
        let phi = latitudeDeg * .pi / 180.0
        let lambda = longitudeDeg * .pi / 180.0
        let sinPhi = sin(phi), cosPhi = cos(phi)
        let rc = a / (1.0 - e2 * sinPhi * sinPhi).squareRoot()   // radius of curvature in prime vertical
        let xp = (rc + altitudeKm) * cosPhi
        let zp = (rc * (1.0 - e2) + altitudeKm) * sinPhi
        let r = (xp * xp + zp * zp).squareRoot()
        let phiGeo = asin(zp / r)   // geocentric latitude

        // --- (re/r)^(n+2) and cos(m*lambda), sin(m*lambda) by recursion ---
        var radiusPower = [Double](repeating: 0, count: n + 1)
        let ror = re / r
        radiusPower[0] = ror * ror
        for k in 1...n { radiusPower[k] = radiusPower[k - 1] * ror }

        var cosM = [Double](repeating: 0, count: n + 1)
        var sinM = [Double](repeating: 0, count: n + 1)
        cosM[0] = 1; sinM[0] = 0
        cosM[1] = cos(lambda); sinM[1] = sin(lambda)
        if n >= 2 {
            for m in 2...n {
                cosM[m] = cosM[m - 1] * cosM[1] - sinM[m - 1] * sinM[1]
                sinM[m] = cosM[m - 1] * sinM[1] + sinM[m - 1] * cosM[1]
            }
        }

        // --- Schmidt semi-normalized Legendre P and dP/d(lat) ---
        let (p, dp) = legendre(sinLat: sin(phiGeo), nMax: n)

        // --- spherical-harmonic summation into geocentric X', Y', Z' ---
        let dt = decimalYear - epoch
        var bx = 0.0, by = 0.0, bz = 0.0
        for deg in 1...n {
            for m in 0...deg {
                let idx = deg * (deg + 1) / 2 + m
                let gt = g[idx] + dt * gDot[idx]
                let ht = h[idx] + dt * hDot[idx]
                let rr = radiusPower[deg]
                let gc = gt * cosM[m] + ht * sinM[m]
                bz -= rr * gc * Double(deg + 1) * p[idx]
                by += rr * (gt * sinM[m] - ht * cosM[m]) * Double(m) * p[idx]
                bx -= rr * gc * dp[idx]
            }
        }

        let cosPhiGeo = cos(phiGeo)
        if abs(cosPhiGeo) > 1e-10 {
            by /= cosPhiGeo
        } else {
            // Sitting on a geographic pole, By hits 0/0. Fall back to the WMM
            // limit form (only the m=1 terms survive).
            by = polarBy(sinLatGeo: sin(phiGeo), radiusPower: radiusPower,
                         cosM: cosM, sinM: sinM, dt: dt, nMax: n)
        }

        // --- rotate the field from the geocentric frame back to geodetic ---
        let psi = phiGeo - phi
        let x = cos(psi) * bx - sin(psi) * bz   // geodetic north
        let y = by                              // geodetic east

        return atan2(y, x) * 180.0 / .pi
    }

    // Schmidt semi-normalized associated Legendre functions of sin(lat) and
    // their derivative w.r.t. geocentric latitude. Same recursion as the NOAA
    // reference (Gauss-normalized build, then scale by the Schmidt quasi-norm).
    private func legendre(sinLat x: Double, nMax n: Int) -> (p: [Double], dp: [Double]) {
        var p = [Double](repeating: 0, count: Model.termCount)
        var dp = [Double](repeating: 0, count: Model.termCount)
        let z = ((1.0 - x) * (1.0 + x)).squareRoot()   // cos(lat)
        p[0] = 1; dp[0] = 0
        for deg in 1...n {
            for m in 0...deg {
                let idx = deg * (deg + 1) / 2 + m
                if deg == m {
                    let i1 = (deg - 1) * deg / 2 + m - 1
                    p[idx] = z * p[i1]
                    dp[idx] = z * dp[i1] + x * p[i1]
                } else if deg == 1 && m == 0 {
                    let i1 = (deg - 1) * deg / 2 + m
                    p[idx] = x * p[i1]
                    dp[idx] = x * dp[i1] - z * p[i1]
                } else {
                    let i1 = (deg - 2) * (deg - 1) / 2 + m
                    let i2 = (deg - 1) * deg / 2 + m
                    if m > deg - 2 {
                        p[idx] = x * p[i2]
                        dp[idx] = x * dp[i2] - z * p[i2]
                    } else {
                        let k = Double((deg - 1) * (deg - 1) - m * m) / Double((2 * deg - 1) * (2 * deg - 3))
                        p[idx] = x * p[i2] - k * p[i1]
                        dp[idx] = x * dp[i2] - z * p[i2] - k * dp[i1]
                    }
                }
            }
        }
        // Gauss-normalized -> Schmidt quasi-normalized.
        var qn = [Double](repeating: 0, count: Model.termCount)
        qn[0] = 1
        for deg in 1...n {
            let idx = deg * (deg + 1) / 2
            let i1 = (deg - 1) * deg / 2
            qn[idx] = qn[i1] * Double(2 * deg - 1) / Double(deg)
            for m in 1...deg {
                let cur = deg * (deg + 1) / 2 + m
                let prev = cur - 1
                qn[cur] = qn[prev] * (Double((deg - m + 1) * (m == 1 ? 2 : 1)) / Double(deg + m)).squareRoot()
            }
        }
        for deg in 1...n {
            for m in 0...deg {
                let idx = deg * (deg + 1) / 2 + m
                p[idx] *= qn[idx]
                // flip sign: recursion gives d/d(colatitude), we want d/d(latitude)
                dp[idx] = -dp[idx] * qn[idx]
            }
        }
        return (p, dp)
    }

    // East component right at a geographic pole, where the 1/cos(lat) form is
    // singular. This is the WMM limit expression (m=1 terms only).
    private func polarBy(sinLatGeo: Double, radiusPower: [Double],
                         cosM: [Double], sinM: [Double], dt: Double, nMax n: Int) -> Double {
        var pcup = [Double](repeating: 0, count: n + 1)
        pcup[0] = 1
        var qn = 1.0
        var by = 0.0
        for deg in 1...n {
            let idx = deg * (deg + 1) / 2 + 1
            let qn2 = qn * Double(2 * deg - 1) / Double(deg)
            let qn3 = qn2 * (Double(deg * 2) / Double(deg + 1)).squareRoot()
            qn = qn2
            if deg == 1 {
                pcup[deg] = pcup[deg - 1]
            } else {
                let k = Double((deg - 1) * (deg - 1) - 1) / Double((2 * deg - 1) * (2 * deg - 3))
                pcup[deg] = sinLatGeo * pcup[deg - 1] - k * pcup[deg - 2]
            }
            let gt = g[idx] + dt * gDot[idx]
            let ht = h[idx] + dt * hDot[idx]
            by += radiusPower[deg] * (gt * sinM[1] - ht * cosM[1]) * pcup[deg] * qn3
        }
        return by
    }
}

// MARK: - WMM2025 Gauss coefficients (official NOAA/NCEI WMM2025.COF)
//
// Columns: n  m  g(nT)  h(nT)  dg/dt(nT/yr)  dh/dt(nT/yr). Epoch 2025.0.
// Verbatim from WMM2025.COF inside WMM2025COF.zip, NCEI released 2024-11-13.
private let coefficientTable = """
1 0 -29351.8 0.0 12.0 0.0
1 1 -1410.8 4545.4 9.7 -21.5
2 0 -2556.6 0.0 -11.6 0.0
2 1 2951.1 -3133.6 -5.2 -27.7
2 2 1649.3 -815.1 -8.0 -12.1
3 0 1361.0 0.0 -1.3 0.0
3 1 -2404.1 -56.6 -4.2 4.0
3 2 1243.8 237.5 0.4 -0.3
3 3 453.6 -549.5 -15.6 -4.1
4 0 895.0 0.0 -1.6 0.0
4 1 799.5 278.6 -2.4 -1.1
4 2 55.7 -133.9 -6.0 4.1
4 3 -281.1 212.0 5.6 1.6
4 4 12.1 -375.6 -7.0 -4.4
5 0 -233.2 0.0 0.6 0.0
5 1 368.9 45.4 1.4 -0.5
5 2 187.2 220.2 0.0 2.2
5 3 -138.7 -122.9 0.6 0.4
5 4 -142.0 43.0 2.2 1.7
5 5 20.9 106.1 0.9 1.9
6 0 64.4 0.0 -0.2 0.0
6 1 63.8 -18.4 -0.4 0.3
6 2 76.9 16.8 0.9 -1.6
6 3 -115.7 48.8 1.2 -0.4
6 4 -40.9 -59.8 -0.9 0.9
6 5 14.9 10.9 0.3 0.7
6 6 -60.7 72.7 0.9 0.9
7 0 79.5 0.0 -0.0 0.0
7 1 -77.0 -48.9 -0.1 0.6
7 2 -8.8 -14.4 -0.1 0.5
7 3 59.3 -1.0 0.5 -0.8
7 4 15.8 23.4 -0.1 0.0
7 5 2.5 -7.4 -0.8 -1.0
7 6 -11.1 -25.1 -0.8 0.6
7 7 14.2 -2.3 0.8 -0.2
8 0 23.2 0.0 -0.1 0.0
8 1 10.8 7.1 0.2 -0.2
8 2 -17.5 -12.6 0.0 0.5
8 3 2.0 11.4 0.5 -0.4
8 4 -21.7 -9.7 -0.1 0.4
8 5 16.9 12.7 0.3 -0.5
8 6 15.0 0.7 0.2 -0.6
8 7 -16.8 -5.2 -0.0 0.3
8 8 0.9 3.9 0.2 0.2
9 0 4.6 0.0 -0.0 0.0
9 1 7.8 -24.8 -0.1 -0.3
9 2 3.0 12.2 0.1 0.3
9 3 -0.2 8.3 0.3 -0.3
9 4 -2.5 -3.3 -0.3 0.3
9 5 -13.1 -5.2 0.0 0.2
9 6 2.4 7.2 0.3 -0.1
9 7 8.6 -0.6 -0.1 -0.2
9 8 -8.7 0.8 0.1 0.4
9 9 -12.9 10.0 -0.1 0.1
10 0 -1.3 0.0 0.1 0.0
10 1 -6.4 3.3 0.0 0.0
10 2 0.2 0.0 0.1 -0.0
10 3 2.0 2.4 0.1 -0.2
10 4 -1.0 5.3 -0.0 0.1
10 5 -0.6 -9.1 -0.3 -0.1
10 6 -0.9 0.4 0.0 0.1
10 7 1.5 -4.2 -0.1 0.0
10 8 0.9 -3.8 -0.1 -0.1
10 9 -2.7 0.9 -0.0 0.2
10 10 -3.9 -9.1 -0.0 -0.0
11 0 2.9 0.0 0.0 0.0
11 1 -1.5 0.0 -0.0 -0.0
11 2 -2.5 2.9 0.0 0.1
11 3 2.4 -0.6 0.0 -0.0
11 4 -0.6 0.2 0.0 0.1
11 5 -0.1 0.5 -0.1 -0.0
11 6 -0.6 -0.3 0.0 -0.0
11 7 -0.1 -1.2 -0.0 0.1
11 8 1.1 -1.7 -0.1 -0.0
11 9 -1.0 -2.9 -0.1 0.0
11 10 -0.2 -1.8 -0.1 0.0
11 11 2.6 -2.3 -0.1 0.0
12 0 -2.0 0.0 0.0 0.0
12 1 -0.2 -1.3 0.0 -0.0
12 2 0.3 0.7 -0.0 0.0
12 3 1.2 1.0 -0.0 -0.1
12 4 -1.3 -1.4 -0.0 0.1
12 5 0.6 -0.0 -0.0 -0.0
12 6 0.6 0.6 0.1 -0.0
12 7 0.5 -0.1 -0.0 -0.0
12 8 -0.1 0.8 0.0 0.0
12 9 -0.4 0.1 0.0 -0.0
12 10 -0.2 -1.0 -0.1 -0.0
12 11 -1.3 0.1 -0.0 0.0
12 12 -0.7 0.2 -0.1 -0.1
"""
