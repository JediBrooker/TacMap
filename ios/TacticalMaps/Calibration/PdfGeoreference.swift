import Foundation
import CoreGraphics
import CoreLocation

/// A point in the page's default user space: raw PDF coords, y up, box origin
/// included, /Rotate ignored. Every parser writes these and every renderer
/// maps from them with its own explicit page -> pixel matrix.
struct PdfPagePoint: Hashable, Codable, Sendable {
    var x: Double
    var y: Double

    init(x: Double, y: Double) { self.x = x; self.y = y }
    init(_ p: CGPoint) { self.init(x: Double(p.x), y: Double(p.y)) }

    var cgPoint: CGPoint { CGPoint(x: x, y: y) }
    var isFinite: Bool { x.isFinite && y.isFinite }

    // [x, y] on the wire, same shape as the fixture crop arrays
    init(from decoder: Decoder) throws {
        var c = try decoder.unkeyedContainer()
        x = try c.decode(Double.self)
        y = try c.decode(Double.self)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.unkeyedContainer()
        try c.encode(x)
        try c.encode(y)
    }
}

/// page -> plane: X = a*x + b*y + c ; Y = d*x + e*y + f
struct PlaneAffine: Hashable, Sendable {
    let a, b, c, d, e, f: Double

    init(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        self.a = a; self.b = b; self.c = c; self.d = d; self.e = e; self.f = f
    }

    /// LGIDict /CTM is PDF matrix order [A B C D E F], X = A x + C y + E
    init(pdfMatrix m: [Double]) {
        self.init(a: m[0], b: m[2], c: m[4], d: m[1], e: m[3], f: m[5])
    }

    var coefficients: [Double] { [a, b, c, d, e, f] }
    var isFinite: Bool { coefficients.allSatisfy(\.isFinite) }

    func apply(_ x: Double, _ y: Double) -> (x: Double, y: Double) {
        (a * x + b * y + c, d * x + e * y + f)
    }

    /// plane -> page. nil when the page axes collapse.
    func unapply(_ X: Double, _ Y: Double) -> (x: Double, y: Double)? {
        let det = a * e - b * d
        let scale = hypot(a, b) * hypot(d, e)
        guard det.isFinite, scale.isFinite, scale > 0, abs(det) > 1e-12 * scale else { return nil }
        let dx = X - c, dy = Y - f
        let out = ((e * dx - b * dy) / det, (-d * dx + a * dy) / det)
        return out.0.isFinite && out.1.isFinite ? out : nil
    }

    var isInvertible: Bool { isFinite && unapply(c, f) != nil }
}

extension PlaneAffine: Codable {
    init(from decoder: Decoder) throws {
        var c = try decoder.unkeyedContainer()
        var v: [Double] = []
        for _ in 0..<6 { v.append(try c.decode(Double.self)) }
        guard c.isAtEnd else {
            throw DecodingError.dataCorruptedError(in: c, debugDescription: "affine needs 6 numbers")
        }
        self.init(a: v[0], b: v[1], c: v[2], d: v[3], e: v[4], f: v[5])
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.unkeyedContainer()
        try c.encode(contentsOf: coefficients)
    }
}

/// Least squares page -> plane. Both sides get centred first, the plane
/// values are ~1e6 m and the naive normal equations eat most of the digits.
enum PlaneAffineFitter {

    struct Pair: Equatable {
        var page: PdfPagePoint
        var plane: PdfPagePoint   // (X, Y) in plane units
    }

    static func fit(_ pairs: [Pair]) -> PlaneAffine? {
        guard pairs.count >= 3,
              pairs.allSatisfy({ $0.page.isFinite && $0.plane.isFinite }) else { return nil }
        let n = Double(pairs.count)
        let mx = pairs.reduce(0) { $0 + $1.page.x } / n
        let my = pairs.reduce(0) { $0 + $1.page.y } / n
        let mX = pairs.reduce(0) { $0 + $1.plane.x } / n
        let mY = pairs.reduce(0) { $0 + $1.plane.y } / n
        var sxx = 0.0, sxy = 0.0, syy = 0.0
        var sxX = 0.0, syX = 0.0, sxY = 0.0, syY = 0.0
        for p in pairs {
            let x = p.page.x - mx, y = p.page.y - my
            let X = p.plane.x - mX, Y = p.plane.y - mY
            sxx += x * x; sxy += x * y; syy += y * y
            sxX += x * X; syX += y * X; sxY += x * Y; syY += y * Y
        }
        let det = sxx * syy - sxy * sxy
        guard det.isFinite, det > 0, det > 1e-14 * (sxx * syy) else { return nil }
        let a = (syy * sxX - sxy * syX) / det
        let b = (sxx * syX - sxy * sxX) / det
        let d = (syy * sxY - sxy * syY) / det
        let e = (sxx * syY - sxy * sxY) / det
        let out = PlaneAffine(a: a, b: b, c: mX - a * mx - b * my,
                              d: d, e: e, f: mY - d * mx - e * my)
        return out.isFinite ? out : nil
    }

    /// min/max eigenvalue of the page points' 2x2 covariance. 0 = collinear.
    static func eigenRatio(_ pts: [PdfPagePoint]) -> Double {
        guard pts.count >= 2 else { return 0 }
        let n = Double(pts.count)
        let mx = pts.reduce(0) { $0 + $1.x } / n
        let my = pts.reduce(0) { $0 + $1.y } / n
        var sxx = 0.0, syy = 0.0, sxy = 0.0
        for p in pts {
            let dx = p.x - mx, dy = p.y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        let tr = sxx + syy
        guard tr > 0, tr.isFinite else { return 0 }
        let disc = max(0, tr * tr - 4 * (sxx * syy - sxy * sxy)).squareRoot()
        let l1 = (tr + disc) / 2
        let l2 = max(0, (tr - disc) / 2)
        return l1 > 0 ? l2 / l1 : 0
    }
}

/// The georeference model from plans/02 s1. Page space in, WGS84 out:
///
///   toWGS84(page) = datum.toWGS84(crs.inverse(affine(page)))
///   toPage(wgs84) = affine^-1(crs.forward(datum.fromWGS84(wgs84)))
///
/// Same shape on Android (PdfGeoreference.kt) and pinned by pdf_georef.json.
struct PdfGeoreference: Hashable, Codable, Sendable {

    enum Origin: String, Codable, Sendable {
        case adobeVP
        case lgiDict
        case fiduciaries
        /// plain PDF placed at the camera so the user can calibrate. NOT a
        /// usable basemap, the UI must say so.
        case provisional
    }

    struct Fit: Hashable, Codable, Sendable {
        var rmsMetres: Double
        var maxResidualMetres: Double
        var perPoint: [Double]
        var crossValidated: Bool
    }

    var page: Int
    var crs: GeoCrs
    var datum: GeoDatum
    var affine: PlaneAffine
    var crop: [PdfPagePoint]
    var origin: Origin
    var fit: Fit?
    /// datum was a guess (unreadable GCS, unknown modern-ellipsoid datum)
    var datumAssumed: Bool

    init(page: Int = 0, crs: GeoCrs, datum: GeoDatum, affine: PlaneAffine,
         crop: [PdfPagePoint], origin: Origin, fit: Fit? = nil, datumAssumed: Bool = false) {
        self.page = page
        self.crs = crs
        self.datum = datum
        self.affine = affine
        self.crop = crop
        self.origin = origin
        self.fit = fit
        self.datumAssumed = datumAssumed
    }

    private enum CodingKeys: String, CodingKey {
        case page, crs, datum, affine, crop, origin, fit, datumAssumed
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        page = try c.decode(Int.self, forKey: .page)
        crs = try c.decode(GeoCrs.self, forKey: .crs)
        datum = try c.decode(GeoDatum.self, forKey: .datum)
        affine = try c.decode(PlaneAffine.self, forKey: .affine)
        crop = try c.decode([PdfPagePoint].self, forKey: .crop)
        origin = try c.decode(Origin.self, forKey: .origin)
        fit = try c.decodeIfPresent(Fit.self, forKey: .fit)
        datumAssumed = try c.decodeIfPresent(Bool.self, forKey: .datumAssumed) ?? false
        guard isStructurallyValid else {
            throw DecodingError.dataCorruptedError(forKey: .affine, in: c, debugDescription: "unusable georef")
        }
    }

    /// cheap sanity check for anything coming off disk
    var isStructurallyValid: Bool {
        page >= 0 && crop.count >= 3
            && crop.allSatisfy { $0.isFinite && abs($0.x) <= maximumSafePDFCoordinateMagnitude
                                    && abs($0.y) <= maximumSafePDFCoordinateMagnitude }
            && affine.isInvertible
            && cropBoundingRect.width > 0 && cropBoundingRect.height > 0
            && toWGS84(x: crop[0].x, y: crop[0].y) != nil
    }

    // MARK: - the two directions

    func toPlane(x: Double, y: Double) -> (x: Double, y: Double) {
        affine.apply(x, y)
    }

    func toWGS84(x: Double, y: Double) -> CLLocationCoordinate2D? {
        guard x.isFinite, y.isFinite else { return nil }
        let p = affine.apply(x, y)
        guard let g = crs.inverse(x: p.x, y: p.y, ellipsoid: datum.ellipsoid),
              let w = datum.toWGS84(lat: g.lat, lon: g.lon),
              (-90.0...90.0).contains(w.lat), (-180.0...180.0).contains(w.lon) else { return nil }
        return CLLocationCoordinate2D(latitude: w.lat, longitude: w.lon)
    }

    func toWGS84(_ p: CGPoint) -> CLLocationCoordinate2D? {
        toWGS84(x: Double(p.x), y: Double(p.y))
    }

    func toPage(_ c: CLLocationCoordinate2D) -> CGPoint? {
        toPage(lat: c.latitude, lon: c.longitude).map { CGPoint(x: $0.x, y: $0.y) }
    }

    func toPage(lat: Double, lon: Double) -> (x: Double, y: Double)? {
        guard let s = datum.fromWGS84(lat: lat, lon: lon),
              var p = crs.forward(lat: s.lat, lon: s.lon, ellipsoid: datum.ellipsoid) else { return nil }
        if crs.isGeographic {
            // keep the longitude on the same branch as the sheet so an
            // antimeridian-ish sheet doesn't jump 360 deg
            let centre = affine.apply(cropCentroid.x, cropCentroid.y)
            while p.x - centre.x > 180 { p.x -= 360 }
            while p.x - centre.x < -180 { p.x += 360 }
        }
        return affine.unapply(p.x, p.y)
    }

    // MARK: - derived stuff for the current renderer

    var cropBoundingRect: CGRect {
        let xs = crop.map(\.x), ys = crop.map(\.y)
        guard let x0 = xs.min(), let x1 = xs.max(), let y0 = ys.min(), let y1 = ys.max() else { return .null }
        return CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    var cropCentroid: PdfPagePoint {
        let n = Double(max(crop.count, 1))
        return PdfPagePoint(x: crop.reduce(0) { $0 + $1.x } / n, y: crop.reduce(0) { $0 + $1.y } / n)
    }

    /// lat/lon box around the crop polygon, edges sampled so a curved edge
    /// (TM/LCC into lat/lon) still sits inside.
    func wgs84Bounds() -> (southWest: CLLocationCoordinate2D, northEast: CLLocationCoordinate2D)? {
        guard crop.count >= 3 else { return nil }
        var lats: [Double] = [], lons: [Double] = []
        for i in 0..<crop.count {
            let p = crop[i], q = crop[(i + 1) % crop.count]
            for k in 0..<8 {
                let t = Double(k) / 8
                guard let w = toWGS84(x: p.x + (q.x - p.x) * t, y: p.y + (q.y - p.y) * t) else { return nil }
                lats.append(w.latitude); lons.append(w.longitude)
            }
        }
        guard let s = lats.min(), let n = lats.max(), let w = lons.min(), let e = lons.max(),
              s < n, w < e else { return nil }
        return (CLLocationCoordinate2D(latitude: s, longitude: w), CLLocationCoordinate2D(latitude: n, longitude: e))
    }

    /// Best-fit lon/lat affine over a 9x9 grid of crop-box points. Stopgap
    /// for the overlay/tiler that still place the page with one linear map,
    /// the tile renderer rework uses toPage per cell and drops this.
    func bestFitLatLonAffine() -> AffineTransform2D? {
        let r = cropBoundingRect
        guard !r.isNull, r.width > 0, r.height > 0 else { return nil }
        var pairs: [PlaneAffineFitter.Pair] = []
        pairs.reserveCapacity(81)
        let ref = toWGS84(x: Double(r.midX), y: Double(r.midY))
        for i in 0...8 {
            for j in 0...8 {
                let x = Double(r.minX) + Double(r.width) * Double(i) / 8
                let y = Double(r.minY) + Double(r.height) * Double(j) / 8
                guard var w = toWGS84(x: x, y: y) else { return nil }
                // same longitude branch as the middle or the fit smears across 360
                if let ref {
                    while w.longitude - ref.longitude > 180 { w.longitude -= 360 }
                    while w.longitude - ref.longitude < -180 { w.longitude += 360 }
                }
                pairs.append(.init(page: PdfPagePoint(x: x, y: y),
                                   plane: PdfPagePoint(x: w.longitude, y: w.latitude)))
            }
        }
        guard let fit = PlaneAffineFitter.fit(pairs) else { return nil }
        let t = AffineTransform2D(a: fit.a, b: fit.b, c: fit.c, d: fit.d, e: fit.e, f: fit.f)
        return t.inverted() == nil ? nil : t
    }

    /// (east, north) metres per plane unit at a latitude: 1 for projected,
    /// local radii for lat/lon. Residuals + the RMS gate use this.
    func metresPerUnit(atLatitude lat: Double) -> (east: Double, north: Double) {
        Self.metresPerUnit(crs: crs, ellipsoid: datum.ellipsoid, latitude: lat)
    }

    static func metresPerUnit(crs: GeoCrs, ellipsoid e: Ellipsoid, latitude lat: Double) -> (east: Double, north: Double) {
        guard crs.isGeographic else { return (1, 1) }
        let p = lat * .pi / 180
        let w = 1 - e.e2 * sin(p) * sin(p)
        let n = e.a / w.squareRoot()
        let m = e.a * (1 - e.e2) / pow(w, 1.5)
        let rad = Double.pi / 180
        return (rad * n * cos(p), rad * m)
    }
}

// MARK: - provisional placement

extension PdfGeoreference {

    /// Nominal scale for a plain PDF we know nothing about. 1 pt = 1/72 in,
    /// at 1:50k that's ~17.6 m on the ground.
    static let provisionalMetresPerPoint = 50_000.0 * 0.0254 / 72

    /// Plain PDF during calibration: geographic WGS84, page box centred on
    /// the camera, north-up as the page is VIEWED (so /Rotate'd scans come up
    /// the right way), nominal 1:50,000. Only ever shown with an
    /// "uncalibrated" label.
    static func provisional(pageBox: CGRect, rotation: Int,
                            centredOn camera: CLLocationCoordinate2D) -> PdfGeoreference? {
        let box = pageBox.standardized
        guard [box.minX, box.minY, box.width, box.height].allSatisfy({ Double($0).isFinite }),
              box.width > 0, box.height > 0 else { return nil }
        // clamp a junk/polar camera to somewhere Web Mercator can draw
        let lat = camera.latitude.isFinite ? min(80, max(-80, camera.latitude)) : 0
        let lon = GeoCrs.wrapLongitude(camera.longitude.isFinite ? camera.longitude : 0)
        let mpu = metresPerUnit(crs: .geographic, ellipsoid: .wgs84, latitude: lat)
        let sx = provisionalMetresPerPoint / mpu.east    // deg lon per pt
        let sy = provisionalMetresPerPoint / mpu.north   // deg lat per pt
        // /Rotate is clockwise when viewed. Turning the page back counter
        // clockwise by the same amount gives "up" as the viewer sees it.
        let r = ((rotation % 360) + 360) % 360
        let (cosR, sinR): (Double, Double)
        switch r {
        case 90:  (cosR, sinR) = (0, -1)
        case 180: (cosR, sinR) = (-1, 0)
        case 270: (cosR, sinR) = (0, 1)
        default:  (cosR, sinR) = (1, 0)
        }
        // page -> east/north points: rotate by -rotation about the box centre
        let cx = Double(box.midX), cy = Double(box.midY)
        let a = sx * cosR, b = -sx * sinR
        let d = sy * sinR, e = sy * cosR
        let affine = PlaneAffine(a: a, b: b, c: lon - a * cx - b * cy,
                                 d: d, e: e, f: lat - d * cx - e * cy)
        let crop = [PdfPagePoint(x: Double(box.minX), y: Double(box.minY)),
                    PdfPagePoint(x: Double(box.maxX), y: Double(box.minY)),
                    PdfPagePoint(x: Double(box.maxX), y: Double(box.maxY)),
                    PdfPagePoint(x: Double(box.minX), y: Double(box.maxY))]
        let g = PdfGeoreference(crs: .geographic, datum: .wgs84, affine: affine, crop: crop,
                                origin: .provisional, datumAssumed: true)
        // a huge page at 1:50k near the pole could still run off the earth
        guard g.wgs84Bounds() != nil, g.isStructurallyValid else { return nil }
        return g
    }

    /// Geographic georef straight from a lat/lon placement (old sessions and
    /// tests that only know a lon/lat affine or a box).
    static func geographic(latLonAffine t: AffineTransform2D, crop: CGRect,
                           origin: Origin) -> PdfGeoreference? {
        let c = crop.standardized
        guard c.width > 0, c.height > 0 else { return nil }
        let g = PdfGeoreference(crs: .geographic, datum: .wgs84,
                                affine: PlaneAffine(a: t.a, b: t.b, c: t.c, d: t.d, e: t.e, f: t.f),
                                crop: [PdfPagePoint(x: Double(c.minX), y: Double(c.minY)),
                                       PdfPagePoint(x: Double(c.maxX), y: Double(c.minY)),
                                       PdfPagePoint(x: Double(c.maxX), y: Double(c.maxY)),
                                       PdfPagePoint(x: Double(c.minX), y: Double(c.maxY))],
                                origin: origin)
        return g.isStructurallyValid ? g : nil
    }
}
