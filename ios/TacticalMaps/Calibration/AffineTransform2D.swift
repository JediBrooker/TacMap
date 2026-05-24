import Foundation
import CoreGraphics
import CoreLocation

/// 2D affine transform from PDF page coordinates to WGS84 lon/lat:
///
///   lon = a*x + b*y + c
///   lat = d*x + e*y + f
///
/// Stored as the 6 coefficients (a,b,c,d,e,f). For prototype scales this is good
/// enough — it captures translation, rotation, scale, and (small) shear. For
/// production-grade work over large areas, a projective (8-DoF) fit using the actual
/// map projection is preferable, but requires the projection metadata from the PDF.
struct AffineTransform2D: Hashable, Codable {
    let a, b, c: Double
    let d, e, f: Double

    func apply(_ p: CGPoint) -> CLLocationCoordinate2D {
        CLLocationCoordinate2D(
            latitude:  d * Double(p.x) + e * Double(p.y) + f,
            longitude: a * Double(p.x) + b * Double(p.y) + c
        )
    }

    /// Invert the transform (for screen-pixel → PDF-point lookups). Returns nil if
    /// the matrix is singular.
    func inverted() -> AffineTransform2D? {
        let det = a * e - b * d
        guard abs(det) > 1e-12 else { return nil }
        let invDet = 1 / det
        // Inverse of [[a b][d e]] applied to translation
        let ia =  e * invDet
        let ib = -b * invDet
        let id = -d * invDet
        let ie =  a * invDet
        let ic = -(ia * c + ib * f)
        let `if` = -(id * c + ie * f)
        return AffineTransform2D(a: ia, b: ib, c: ic, d: id, e: ie, f: `if`)
    }
}

enum AffineFitError: Error {
    case tooFewFiduciaries(minimum: Int)
    case degenerate     // points are colinear or coincident
}

/// Least-squares fit of an affine transform from N≥3 fiduciaries.
///
/// The system is over-determined for N>3: we solve the normal equations
/// `(Aᵀ A) x = Aᵀ b` for x and y independently (the two halves of the affine are
/// uncoupled), which is the closed-form least-squares solution.
enum AffineFitter {

    struct Result {
        let transform: AffineTransform2D
        /// Root-mean-square residual in metres. Useful to surface in the UI so users
        /// know how trustworthy the calibration is.
        let rmsMetres: Double
    }

    static func fit(_ fiduciaries: [Fiduciary]) throws -> Result {
        guard fiduciaries.count >= 3 else {
            throw AffineFitError.tooFewFiduciaries(minimum: 3)
        }

        // Solve for [a b c] from x-coords (lon) and [d e f] from y-coords (lat) separately.
        let (a, b, c) = try lsq(points: fiduciaries.map { ($0.pdfX, $0.pdfY, $0.longitude) })
        let (d, e, f) = try lsq(points: fiduciaries.map { ($0.pdfX, $0.pdfY, $0.latitude)  })
        let t = AffineTransform2D(a: a, b: b, c: c, d: d, e: e, f: f)

        // Residual in metres (great-circle distance between predicted and known).
        var sumSq = 0.0
        for fid in fiduciaries {
            let predicted = t.apply(fid.pdfPoint)
            sumSq += squareDistanceMetres(predicted, fid.wgs84)
        }
        let rms = sqrt(sumSq / Double(fiduciaries.count))
        return Result(transform: t, rmsMetres: rms)
    }

    /// Closed-form LSQ for `target = a*x + b*y + c` over N points.
    private static func lsq(points: [(Double, Double, Double)]) throws -> (Double, Double, Double) {
        // Build the 3x3 normal-equations matrix AᵀA and 3-vector AᵀB.
        var sx = 0.0, sy = 0.0, sxx = 0.0, syy = 0.0, sxy = 0.0
        var sb = 0.0, sxb = 0.0, syb = 0.0
        let n = Double(points.count)
        for (x, y, b) in points {
            sx += x; sy += y
            sxx += x * x; syy += y * y; sxy += x * y
            sb += b; sxb += x * b; syb += y * b
        }
        // M = [[sxx sxy sx][sxy syy sy][sx sy n]]
        let m: [[Double]] = [
            [sxx, sxy, sx],
            [sxy, syy, sy],
            [sx,  sy,  n ]
        ]
        let rhs = [sxb, syb, sb]
        guard let sol = solve3x3(m, rhs) else { throw AffineFitError.degenerate }
        return (sol[0], sol[1], sol[2])
    }

    /// Cramer's rule for a 3x3 system. Returns nil if singular.
    private static func solve3x3(_ m: [[Double]], _ r: [Double]) -> [Double]? {
        let det = det3(m)
        guard abs(det) > 1e-12 else { return nil }
        let mx = [[r[0], m[0][1], m[0][2]], [r[1], m[1][1], m[1][2]], [r[2], m[2][1], m[2][2]]]
        let my = [[m[0][0], r[0], m[0][2]], [m[1][0], r[1], m[1][2]], [m[2][0], r[2], m[2][2]]]
        let mz = [[m[0][0], m[0][1], r[0]], [m[1][0], m[1][1], r[1]], [m[2][0], m[2][1], r[2]]]
        return [det3(mx) / det, det3(my) / det, det3(mz) / det]
    }

    private static func det3(_ m: [[Double]]) -> Double {
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
        - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    }

    private static func squareDistanceMetres(_ a: CLLocationCoordinate2D,
                                              _ b: CLLocationCoordinate2D) -> Double {
        // Equirectangular approximation — plenty accurate for residuals over a
        // single map sheet.
        let R = 6_371_000.0
        let dLat = (b.latitude  - a.latitude)  * .pi / 180
        let dLon = (b.longitude - a.longitude) * .pi / 180 * cos((a.latitude + b.latitude) / 2 * .pi / 180)
        let m = R * sqrt(dLat * dLat + dLon * dLon)
        return m * m
    }
}
