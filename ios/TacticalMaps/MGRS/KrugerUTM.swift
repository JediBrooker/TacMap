import Foundation

/// WGS84 UTM using the 6th order Kruger series (Karney 2011), which is the
/// same math PROJ's etmerc runs. Good to a few nanometres inside a zone.
///
/// We can't use the vendored NGA UTM for grid lines: it's Snyder and rounds to
/// 1e-7 deg, so it lands 1-4 cm off, which is bigger than a 0.25 px sagitta
/// budget at z19. Fine for the header readout, not fine for drawing lines.
enum KrugerUTM {

    static let semiMajor = 6_378_137.0
    static let flattening = 1 / 298.257223563
    static let scale = 0.9996
    static let falseEasting = 500_000.0
    static let southFalseNorthing = 10_000_000.0

    private static let n = flattening / (2 - flattening)
    private static let ecc = (flattening * (2 - flattening)).squareRoot()
    private static let oneMinusE2 = 1 - flattening * (2 - flattening)

    /// k0 * A, the rectifying radius times the UTM scale.
    private static let kA: Double = {
        let n2 = n * n
        return scale * semiMajor / (1 + n) * (1 + n2 / 4 + n2 * n2 / 64 + n2 * n2 * n2 / 256)
    }()

    private static let alpha: [Double] = {
        let n2 = n * n, n3 = n2 * n, n4 = n3 * n, n5 = n4 * n, n6 = n5 * n
        return [
            n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180 - 127 * n5 / 288 + 7891 * n6 / 37800,
            13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440 + 281 * n5 / 630 - 1983433 * n6 / 1935360,
            61 * n3 / 240 - 103 * n4 / 140 + 15061 * n5 / 26880 + 167603 * n6 / 181440,
            49561 * n4 / 161280 - 179 * n5 / 168 + 6601661 * n6 / 7257600,
            34729 * n5 / 80640 - 3418889 * n6 / 1995840,
            212378941 * n6 / 319334400,
        ]
    }()

    private static let beta: [Double] = {
        let n2 = n * n, n3 = n2 * n, n4 = n3 * n, n5 = n4 * n, n6 = n5 * n
        return [
            n / 2 - 2 * n2 / 3 + 37 * n3 / 96 - n4 / 360 - 81 * n5 / 512 + 96199 * n6 / 604800,
            n2 / 48 + n3 / 15 - 437 * n4 / 1440 + 46 * n5 / 105 - 1118711 * n6 / 3870720,
            17 * n3 / 480 - 37 * n4 / 840 - 209 * n5 / 4480 + 5569 * n6 / 90720,
            4397 * n4 / 161280 - 11 * n5 / 504 - 830251 * n6 / 7257600,
            4583 * n5 / 161280 - 108847 * n6 / 3991680,
            20648693 * n6 / 638668800,
        ]
    }()

    /// Central meridian of a UTM zone in degrees.
    static func centralMeridian(zone: Int) -> Double { Double(6 * zone - 183) }

    /// lat/lon (deg) -> easting/northing in zone. south adds the 10,000 km
    /// false northing. Works a few degrees outside the zone too (32V, 31X etc).
    static func forward(latitude: Double, longitude: Double,
                        zone: Int, south: Bool) -> (easting: Double, northing: Double) {
        let phi = latitude * .pi / 180
        let lam = (longitude - centralMeridian(zone: zone)) * .pi / 180
        let tau = tan(phi)
        let sigma = sinh(ecc * atanh(ecc * tau / (1 + tau * tau).squareRoot()))
        let tauP = tau * (1 + sigma * sigma).squareRoot() - sigma * (1 + tau * tau).squareRoot()
        let cosLam = cos(lam)
        let xiP = atan2(tauP, cosLam)
        let etaP = asinh(sin(lam) / (tauP * tauP + cosLam * cosLam).squareRoot())
        var xi = xiP, eta = etaP
        for j in 0 ..< 6 {
            let k = 2 * Double(j + 1)
            xi += alpha[j] * sin(k * xiP) * cosh(k * etaP)
            eta += alpha[j] * cos(k * xiP) * sinh(k * etaP)
        }
        return (falseEasting + kA * eta, (south ? southFalseNorthing : 0) + kA * xi)
    }

    /// easting/northing in zone -> lat/lon (deg). Longitude is built as
    /// CM + offset in degrees on purpose, so E=500000 comes back as exactly
    /// the CM (3.0, not 3.0000000000000004). The half-open cell edges care.
    static func inverse(easting: Double, northing: Double,
                        zone: Int, south: Bool) -> (latitude: Double, longitude: Double) {
        let xi = (northing - (south ? southFalseNorthing : 0)) / kA
        let eta = (easting - falseEasting) / kA
        var xiP = xi, etaP = eta
        for j in 0 ..< 6 {
            let k = 2 * Double(j + 1)
            xiP -= beta[j] * sin(k * xi) * cosh(k * eta)
            etaP -= beta[j] * cos(k * xi) * sinh(k * eta)
        }
        let sinhEta = sinh(etaP), cosXi = cos(xiP)
        let tauP = sin(xiP) / (sinhEta * sinhEta + cosXi * cosXi).squareRoot()
        let lam = atan2(sinhEta, cosXi)

        // conformal -> geodetic latitude, Newton on tau. 2-3 rounds is plenty
        var tau = tauP
        for _ in 0 ..< 6 {
            let s = sinh(ecc * atanh(ecc * tau / (1 + tau * tau).squareRoot()))
            let ti = tau * (1 + s * s).squareRoot() - s * (1 + tau * tau).squareRoot()
            let d = (tauP - ti) / (1 + ti * ti).squareRoot()
                * (1 + oneMinusE2 * tau * tau) / (oneMinusE2 * (1 + tau * tau).squareRoot())
            tau += d
            if abs(d) <= 1e-14 * max(1, abs(tau)) { break }
        }
        return (atan(tau) * 180 / .pi, centralMeridian(zone: zone) + lam * 180 / .pi)
    }
}
