import Foundation
import CoreGraphics
import CoreLocation

/// What the user typed for one calibration point, already parsed. UTM/MGRS
/// keep their own zone + grid numbers (no lat/lon round trip, and no
/// ellipsoid baked in), lat/lon is in the sheet datum.
enum FiduciaryReference: Hashable {
    case mgrs(zone: Int, band: Character, south: Bool, easting: Double, northing: Double)
    case utm(zone: Int, band: Character, south: Bool, easting: Double, northing: Double)
    case latLon(lat: Double, lon: Double)

    /// Full MGRS (any spacing/case, 4 to 10 even digits), UTM "55H 691000 6089000"
    /// (band letter, not N/S) or decimal "lat, lon" / "lat lon". Same grammar as
    /// Android's FiduciaryReferenceParser. The fancier shorthand and DMS come
    /// with the calibration UX rework.
    /// - Parameter ellipsoid: sheet datum's, only used to pick the MGRS 2000 km row cycle
    static func parse(_ raw: String, ellipsoid: Ellipsoid = .wgs84) -> FiduciaryReference? {
        let text = normalisedWhitespace(raw)
        // utf16 count is what Kotlin's length is, so the cap bites at the same spot
        guard !text.isEmpty, text.utf16.count <= 64 else { return nil }

        // lat, lon (comma or just whitespace between, no leading +)
        if let m = groups(text, latLonPattern) {
            guard let lat = Double(m[0]), let lon = Double(m[1]),
                  (-90.0...90.0).contains(lat), (-180.0...180.0).contains(lon) else { return nil }
            return .latLon(lat: lat, lon: lon)
        }

        // UTM: <zone><band> E N
        let upper = text.uppercased()
        if let m = groups(upper, utmPattern) {
            guard let zone = Int(m[0]), (1...60).contains(zone), let band = m[1].first,
                  let e = Double(m[2]), let n = Double(m[3]),
                  (100_000.0...900_000.0).contains(e), (0.0...10_000_000.0).contains(n) else { return nil }
            return .utm(zone: zone, band: band, south: band < "N", easting: e, northing: n)
        }
        return mgrs(upper.replacingOccurrences(of: " ", with: ""), ellipsoid: ellipsoid)
    }

    /// fiduciaryFits.whiteSpaceCodePoints (NBSP, thin space, tabs, U+3000, the
    /// U+001C-001F separators Kotlin counts ...) -> a plain space, then trimmed.
    /// Copy-pasted coords off web pages and PDFs are full of NBSP. The list is
    /// spelled out (same table as Android's isReferenceSpace) instead of asking
    /// the OS's Unicode tables, which move between releases. The patterns below
    /// only ever see ascii spaces and [0-9] (fiduciaryFits.parseRules)
    static func normalisedWhitespace(_ raw: String) -> String {
        var scalars = String.UnicodeScalarView()
        for u in raw.unicodeScalars {
            scalars.append(CoordinateInputParser.whiteSpaceCodePoints.contains(u.value) ? " " : u)
        }
        return String(scalars).trimmingCharacters(in: CharacterSet(charactersIn: " "))
    }

    private static let latLonPattern = #"^ *(-?[0-9]+(?:\.[0-9]+)?) *[, ] *(-?[0-9]+(?:\.[0-9]+)?) *$"#
    private static let utmPattern = #"^([0-9]{1,2})([C-HJ-NP-X]) +([0-9]+(?:\.[0-9]+)?) +([0-9]+(?:\.[0-9]+)?)$"#
    private static let mgrsPattern = #"^([0-9]{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])([0-9]*)$"#

    /// capture groups of a whole-string match, nil when it doesn't match
    private static func groups(_ s: String, _ pattern: String) -> [String]? {
        guard let re = try? NSRegularExpression(pattern: pattern),
              let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              m.range.location == 0, m.range.length == (s as NSString).length else { return nil }
        return (1..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: s).map { String(s[$0]) } ?? ""
        }
    }

    private static let bandLetters = Array("CDEFGHJKLMNPQRSTUVWX")
    private static let rowLetters = Array("ABCDEFGHJKLMNPQRSTUV")

    /// Grid arithmetic straight off the letters, no lat/lon round trip (and
    /// no NGA parser that fatalErrors on odd input). Only the 2000 km row
    /// cycle needs the band, a degree of slop there doesn't care about ellipsoids.
    private static func mgrs(_ s: String, ellipsoid: Ellipsoid) -> FiduciaryReference? {
        guard let m = groups(s, mgrsPattern), let zone = Int(m[0]), (1...60).contains(zone),
              let band = m[1].first, let col = m[2].first, let row = m[3].first else { return nil }
        let digits = m[4]
        // 0 or 2 digits is a 100 km / 10 km square, way too coarse to calibrate on
        guard (4...10).contains(digits.count), digits.count.isMultiple(of: 2) else { return nil }
        let half = digits.count / 2
        let unit = pow(10.0, Double(5 - half))
        guard let eDigits = Double(digits.prefix(half)), let nDigits = Double(digits.suffix(half)) else { return nil }
        let e5 = eDigits * unit, n5 = nDigits * unit
        let colSet: [Character]
        switch zone % 3 {
        case 1: colSet = Array("ABCDEFGH")
        case 2: colSet = Array("JKLMNPQR")
        default: colSet = Array("STUVWXYZ")
        }
        guard let ci = colSet.firstIndex(of: col), let ri = rowLetters.firstIndex(of: row),
              let bi = bandLetters.firstIndex(of: band) else { return nil }
        let easting = Double(ci + 1) * 100_000 + e5
        let rowIndex = ((ri - (zone.isMultiple(of: 2) ? 5 : 0)) % 20 + 20) % 20
        let base = Double(rowIndex) * 100_000 + n5
        let south = band < "N"
        let lo = -80.0 + 8 * Double(bi)
        let hi = lo + (band == "X" ? 12 : 8)
        let crs = GeoCrs.utm(zone: zone, south: south)
        for k in 0..<6 {
            let northing = base + Double(k) * 2_000_000
            guard let lat = crs.inverse(x: easting, y: northing, ellipsoid: ellipsoid)?.lat else { continue }
            if lat >= lo - 1, lat <= hi + 1 {
                return .mgrs(zone: zone, band: band, south: south, easting: easting, northing: northing)
            }
        }
        return nil
    }

    var gridZone: (zone: Int, south: Bool)? {
        switch self {
        case let .mgrs(zone, _, south, _, _), let .utm(zone, _, south, _, _): return (zone, south)
        case .latLon: return nil
        }
    }
}

struct FiduciaryControl: Hashable {
    var page: PdfPagePoint
    var reference: FiduciaryReference
}

/// Everything the fit panel wants to show, plus the georef when there's one.
/// Field names follow pdf_georef.json fiduciaryFits expected.
struct FiduciaryFitResult {
    var zone: Int
    var south: Bool
    var crs: GeoCrs
    var datum: GeoDatum
    var planePoints: [PdfPagePoint]
    var eigenRatio: Double
    var degenerate: Bool
    var spanFraction: (x: Double, y: Double)
    var spanWarning: Bool
    var crossValidated: Bool
    var affine: PlaneAffine?
    var residualsMetres: [Double] = []
    var rmsMetres: Double = 0
    var maxResidualMetres: Double = 0
    /// n >= 4 only; nil entries where the other n-1 can't define a fit
    var leaveOneOutMetres: [Double?] = []
    var leaveOneOutRmsMetres: [Double?] = []
    var flaggedOutliers: [Int] = []
    var exactFit: Bool { affine != nil && planePoints.count == 3 }
    /// what the fit panel says about it, same text as Android (fixture message)
    var message: String? { exactFit ? "exact fit - add a 4th point to check accuracy" : nil }

    func georeference(crop: [PdfPagePoint], page: Int = 0) -> PdfGeoreference? {
        guard let affine, !degenerate else { return nil }
        let g = PdfGeoreference(page: page, crs: crs, datum: datum, affine: affine, crop: crop,
                                origin: .fiduciaries,
                                fit: .init(rmsMetres: rmsMetres, maxResidualMetres: maxResidualMetres,
                                           perPoint: residualsMetres, crossValidated: crossValidated))
        return g.isStructurallyValid ? g : nil
    }
}

/// plans/02 s1 Fiduciaries: fit page -> UTM of the FIRST point's zone, in
/// the sheet datum. Exact for grid-aligned sheets, which is the whole point.
enum FiduciaryFitter {

    static let degenerateEigenRatio = 0.02
    static let spanWarningFraction = 0.25

    /// - Parameter zoneOverride: zone + hemisphere when the caller already
    ///   knows it (the stored MGRS of a saved point), else it comes off point 0
    static func fit(_ controls: [FiduciaryControl], datum: GeoDatum, cropBBox: CGRect,
                    zoneOverride: (zone: Int, south: Bool)? = nil) -> FiduciaryFitResult? {
        guard let first = controls.first,
              controls.allSatisfy({ $0.page.isFinite
                  && abs($0.page.x) <= maximumSafePDFCoordinateMagnitude
                  && abs($0.page.y) <= maximumSafePDFCoordinateMagnitude }) else { return nil }
        let zone: Int, south: Bool
        if let zoneOverride {
            (zone, south) = zoneOverride
        } else {
            switch first.reference {
            case let .mgrs(z, _, s, _, _), let .utm(z, _, s, _, _):
                zone = z; south = s
            case let .latLon(lat, lon):
                zone = standardZone(lon: lon)
                south = lat < 0
            }
        }
        guard (1...60).contains(zone) else { return nil }
        let crs = GeoCrs.utm(zone: zone, south: south)
        let ell = datum.ellipsoid

        var plane: [PdfPagePoint] = []
        for c in controls {
            switch c.reference {
            case let .mgrs(z, _, s, e, n), let .utm(z, _, s, e, n):
                if z == zone && s == south {
                    plane.append(PdfPagePoint(x: e, y: n))
                } else {
                    // other zone: back to lat/lon on the sheet ellipsoid, forward into ours
                    guard let g = GeoCrs.utm(zone: z, south: s).inverse(x: e, y: n, ellipsoid: ell),
                          let p = crs.forward(lat: g.lat, lon: g.lon, ellipsoid: ell) else { return nil }
                    plane.append(PdfPagePoint(x: p.x, y: p.y))
                }
            case let .latLon(lat, lon):
                guard let p = crs.forward(lat: lat, lon: lon, ellipsoid: ell) else { return nil }
                plane.append(PdfPagePoint(x: p.x, y: p.y))
            }
        }

        let pages = controls.map(\.page)
        let ratio = PlaneAffineFitter.eigenRatio(pages)
        let xs = pages.map(\.x), ys = pages.map(\.y)
        let w = Double(cropBBox.width), h = Double(cropBBox.height)
        let span = (x: w > 0 ? ((xs.max() ?? 0) - (xs.min() ?? 0)) / w : 0,
                    y: h > 0 ? ((ys.max() ?? 0) - (ys.min() ?? 0)) / h : 0)
        let degenerate = controls.count < 3 || ratio < degenerateEigenRatio
        var out = FiduciaryFitResult(zone: zone, south: south, crs: crs, datum: datum,
                                     planePoints: plane, eigenRatio: ratio, degenerate: degenerate,
                                     spanFraction: span, spanWarning: min(span.x, span.y) < spanWarningFraction,
                                     crossValidated: controls.count >= 4, affine: nil)
        guard !degenerate else { return out }

        let pairs = zip(pages, plane).map { PlaneAffineFitter.Pair(page: $0, plane: $1) }
        guard let affine = PlaneAffineFitter.fit(pairs) else {
            out.degenerate = true
            return out
        }
        out.affine = affine
        out.residualsMetres = residuals(affine, pairs)
        out.rmsMetres = rms(out.residualsMetres)
        out.maxResidualMetres = out.residualsMetres.max() ?? 0

        if pairs.count >= 4 {
            var loo: [Double?] = [], looRms: [Double?] = []
            for i in pairs.indices {
                var rest = pairs
                rest.remove(at: i)
                guard PlaneAffineFitter.eigenRatio(rest.map(\.page)) >= degenerateEigenRatio,
                      let a = PlaneAffineFitter.fit(rest) else {
                    loo.append(nil); looRms.append(nil)
                    continue
                }
                let p = a.apply(pairs[i].page.x, pairs[i].page.y)
                loo.append(hypot(p.x - pairs[i].plane.x, p.y - pairs[i].plane.y))
                looRms.append(rms(residuals(a, rest)))
            }
            out.leaveOneOutMetres = loo
            out.leaveOneOutRmsMetres = looRms
            // one suspect at a time: the point whose removal leaves the tidiest
            // fit, flagged only if it sits well off that fit (proposed rule)
            if pairs.count >= 5 {
                let usable = pairs.indices.filter { looRms[$0] != nil }
                if let k = usable.min(by: { (looRms[$0]!, $0) < (looRms[$1]!, $1) }),
                   let lk = loo[k], let rk = looRms[k], lk > max(5.0, 5.0 * rk) {
                    out.flaggedOutliers = [k]
                }
            }
        }
        return out
    }

    private static func residuals(_ a: PlaneAffine, _ pairs: [PlaneAffineFitter.Pair]) -> [Double] {
        pairs.map { p in
            let q = a.apply(p.page.x, p.page.y)
            return hypot(q.x - p.plane.x, q.y - p.plane.y)
        }
    }

    private static func rms(_ r: [Double]) -> Double {
        r.isEmpty ? 0 : (r.reduce(0) { $0 + $1 * $1 } / Double(r.count)).squareRoot()
    }

    /// standard 6 degree zone, no Norway/Svalbard specials
    static func standardZone(lon: Double) -> Int {
        // clamp as a double, Int() of a junk lon would trap
        guard lon.isFinite else { return 1 }
        return Int(min(60, max(1, floor((lon + 180) / 6) + 1)))
    }

    /// Zone + hemisphere for a saved calibration: off the first point's typed
    /// MGRS/UTM when it still parses (a 55H point east of 150E stays in 55,
    /// a Norway 32V one stays in 32), else the standard zone of its lat/lon.
    /// Same rule as Android FiduciaryFitter.refitStored.
    static func storedZone(_ first: Fiduciary) -> (zone: Int, south: Bool) {
        if let zs = FiduciaryReference.parse(first.mgrs)?.gridZone { return zs }
        return (standardZone(lon: first.longitude), first.latitude < 0)
    }

    /// The fiduciaries we've always persisted: page point + WGS84 lat/lon
    /// (already datum shifted when entered). Refit them on WGS84 in the zone
    /// of the first one (storedZone). Used by finish() and the v1 migration.
    static func georeference(fromWGS84 fids: [Fiduciary], crop: CGRect, page: Int = 0) -> PdfGeoreference? {
        guard fids.count >= 3, fids.allSatisfy(isSafeAffineInput) else { return nil }
        let controls = fids.map {
            FiduciaryControl(page: PdfPagePoint(x: $0.pdfX, y: $0.pdfY),
                             reference: .latLon(lat: $0.latitude, lon: $0.longitude))
        }
        let c = crop.standardized
        guard let result = fit(controls, datum: .wgs84, cropBBox: c, zoneOverride: storedZone(fids[0])),
              !result.degenerate else { return nil }
        let cropPoly = [PdfPagePoint(x: Double(c.minX), y: Double(c.minY)),
                        PdfPagePoint(x: Double(c.maxX), y: Double(c.minY)),
                        PdfPagePoint(x: Double(c.maxX), y: Double(c.maxY)),
                        PdfPagePoint(x: Double(c.minX), y: Double(c.maxY))]
        return result.georeference(crop: cropPoly, page: page)
    }
}

/// Web Mercator tile pixel -> page point, the pure half of the tile warp.
/// XYZ, 256 px tiles, pixel y down, spherical Mercator on WGS84 lat/lon.
/// The renderer that consumes this lands with the tile source rework.
enum PdfTileWarp {

    static func wgs84(z: Int, x: Int, y: Int, px: Double, py: Double) -> CLLocationCoordinate2D {
        let n = 256 * pow(2.0, Double(z))
        let X = (Double(x) * 256 + px) / n
        let Y = (Double(y) * 256 + py) / n
        let lon = X * 360 - 180
        let lat = atan(sinh(.pi * (1 - 2 * Y))) * 180 / .pi
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }

    static func pagePoint(_ georef: PdfGeoreference, z: Int, x: Int, y: Int,
                          px: Double, py: Double) -> CGPoint? {
        georef.toPage(wgs84(z: z, x: x, y: y, px: px, py: py))
    }
}
