import Foundation

/// Why a declared georeference got refused. Raw values are the fixture's
/// reason codes (pdf_georef.json rejections.reasons), shared with Android.
enum PdfGeorefRejectReason: String, Error, Equatable, CaseIterable, Sendable {
    case lptsOutOfRange
    case nonFinite
    case gptsOffEarth
    case rmsGate
    case degenerateViewport
    case malformed
    case unknownDatum
    case unsupportedProjection
}

/// A numeric PDF array as the parser found it. Keeps "wrong type" and
/// "number that won't fit a double" apart since they're different reasons.
enum PdfNumbers: Equatable {
    case missing
    case malformed
    case nonFinite
    case values([Double])

    var values: [Double]? {
        if case .values(let v) = self { return v }
        return nil
    }
}

/// /GCS as found on a viewport's Measure
enum PdfGcsInput: Equatable {
    case missing
    /// present but not a usable dict / wrong-typed members
    case malformed
    case described(wkt: String?, epsg: Int?)
}

/// One ISO 32000 /VP viewport, already pulled out of the PDF (or the fixture).
struct AdobeViewportInput: Equatable {
    var name: String?
    var bbox: PdfNumbers
    var lpts: PdfNumbers
    var gpts: PdfNumbers
    var bounds: PdfNumbers = .missing
    var gcs: PdfGcsInput = .missing
}

/// lgiRules.numericStrings: a pdf string standing in for a number has to look
/// like one. Swift's Double() also takes hex floats, inf and nan, Kotlin's
/// toDouble takes 10d/10f, so both apps gate on the same grammar first.
enum PdfNumericString {
    private static let grammar = try? NSRegularExpression(pattern: #"^[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?$"#)

    /// trimmed of fiduciaryFits.whiteSpaceCodePoints, both ends only
    static func trim(_ s: String) -> String {
        let ws = CoordinateInputParser.whiteSpaceCodePoints
        var scalars = Array(s.unicodeScalars)
        while let f = scalars.first, ws.contains(f.value) { scalars.removeFirst() }
        while let l = scalars.last, ws.contains(l.value) { scalars.removeLast() }
        var v = String.UnicodeScalarView()
        v.append(contentsOf: scalars)
        return String(v)
    }

    /// nil = not a number string at all (the wrong type). A match too big for a
    /// double comes back infinite, like an overflowing pdf real
    static func parse(_ raw: String) -> Double? {
        let t = trim(raw)
        let ns = t as NSString
        guard !t.isEmpty, let grammar,
              let m = grammar.firstMatch(in: t, range: NSRange(location: 0, length: ns.length)),
              m.range.length == ns.length else { return nil }
        return Double(t) ?? .infinity
    }
}

/// LGIDict values can be names, strings, numbers or dicts depending on the producer.
/// Names and strings stay apart: both are text, only strings can be numbers
/// (pdfbox on Android never reads a name as a number either).
indirect enum LgiValue: Equatable {
    /// a pdf string
    case text(String)
    /// a pdf name
    case name(String)
    /// a pdf real
    case number(Double)
    /// a pdf integer, kept apart from reals: a /Datum integer reads as its
    /// digits, a /Datum real is malformed (lgiRules.valueTypes)
    case integer(Int64)
    case dictionary([String: LgiValue])
    /// the pdf null object. PRESENT with the wrong type, never "absent"
    /// (lgiRules.nullValues), so it can't open a /Display or default fallback
    case null
    case other

    /// name or string, trimmed of the shared whitespace set. numbers aren't
    /// text (ProjectionType 5 is malformed)
    var text: String? {
        switch self {
        case .text(let s), .name(let s): return PdfNumericString.trim(s)
        default: return nil
        }
    }

    /// Datum code: text, or a pdf integer of any size read as its digits (same as Android)
    var code: String? {
        if let t = text { return t }
        if case .integer(let n) = self { return String(n) }
        return nil
    }

    /// finite numbers, or numeric strings like (-122.6) that pass the grammar.
    /// never a name, and never inf/nan, so callers can range check and Int() it safely
    var number: Double? {
        let v: Double?
        switch self {
        case .number(let n): v = n
        case .integer(let n): v = Double(n)
        case .text(let s): v = PdfNumericString.parse(s)
        default: v = nil
        }
        guard let v, v.isFinite else { return nil }
        return v
    }
}

struct LgiEntryInput: Equatable {
    var description: String?
    var ctm: PdfNumbers = .missing
    /// [[x, y, X, Y], ...]
    var registration: PdfRegistration = .missing
    var neatline: PdfNumbers = .missing
    var projection: [String: LgiValue]?
    /// some producers park Zone/Hemisphere under /Display instead
    var display: [String: LgiValue]?
}

enum PdfRegistration: Equatable {
    case missing
    case malformed
    case nonFinite
    case rows([[Double]])
}

/// What the builder picked, for tests and logs.
struct PdfGeorefSelection: Equatable {
    enum Kind: String { case viewport, lgiEntry }
    enum Source: String { case ctm, registration }
    var kind: Kind
    var index: Int
    var name: String?
    var source: Source?
}

/// Fit numbers for the Adobe/Registration paths, the fixture's fit object.
struct PdfGeorefFitStats: Equatable {
    var rmsMetres: Double
    var maxResidualMetres: Double
    var residualsMetres: [Double]
    var sheetDiagonalMetres: Double
    var gateLimitMetres: Double
    var passesGate: Bool
    /// the page -> plane pairs that got fitted (fixture controls)
    var controls: [PlaneAffineFitter.Pair] = []
}

enum PdfGeorefBuildResult: Equatable {
    case georef(PdfGeoreference, PdfGeorefSelection, PdfGeorefFitStats?)
    case rejected(PdfGeorefRejectReason)

    var georef: PdfGeoreference? {
        if case .georef(let g, _, _) = self { return g }
        return nil
    }
}

/// Turns parsed metadata into a PdfGeoreference (plans/02 s1 Construction).
/// Pure: no PDF parsing in here, so the fixture can drive it directly.
enum PdfGeorefBuilder {

    /// [-0.5, 1.5] per plan (D1-01, USGS sits just outside [0, 1]). The slop
    /// covers producers that write the boundary as -0.500000001 after rounding
    /// their BBox, a real out-of-range LPTS is off by way more than that.
    static let lptsRange: ClosedRange<Double> = (-0.5 - 1e-6)...(1.5 + 1e-6)
    /// bigger than any real viewport/LGIDict list, hostile files get cut off
    static let maximumEntries = 64
    static let maximumControlValues = 8_192
    /// LGIDict /Registration rows, same cap as Android MAX_CONTROL_POINTS
    static let maximumRegistrationRows = 4_096

    // MARK: - ISO 32000 / Adobe /VP

    /// Largest-BBox viewport that yields a valid georef wins. When none do,
    /// report the failure of the biggest declared one.
    static func build(viewports: [AdobeViewportInput], page: Int = 0) -> PdfGeorefBuildResult? {
        guard !viewports.isEmpty else { return nil }
        guard viewports.count <= maximumEntries else { return .rejected(.malformed) }
        var best: (area: Double, result: PdfGeorefBuildResult)?
        var worst: (area: Double, reason: PdfGeorefRejectReason)?
        for (index, vp) in viewports.enumerated() {
            let area = bboxArea(vp.bbox)
            let result = buildViewport(vp, index: index, page: page)
            switch result {
            case .georef:
                if area > (best?.area ?? -1) { best = (area, result) }
            case .rejected(let reason):
                if area > (worst?.area ?? -1) { worst = (area, reason) }
            }
        }
        if let best { return best.result }
        return .rejected(worst?.reason ?? .malformed)
    }

    private static func bboxArea(_ bbox: PdfNumbers) -> Double {
        guard let b = bbox.values, b.count == 4 else { return -1 }
        let a = abs((b[2] - b[0]) * (b[3] - b[1]))
        return a.isFinite ? a : -1
    }

    static func buildViewport(_ vp: AdobeViewportInput, index: Int, page: Int = 0) -> PdfGeorefBuildResult {
        // structure first: types, finiteness, lengths
        for arr in [vp.bbox, vp.lpts, vp.gpts] {
            switch arr {
            case .missing, .malformed: return .rejected(.malformed)
            case .nonFinite: return .rejected(.nonFinite)
            case .values: break
            }
        }
        // a broken /Bounds rejects the viewport, never quietly swapped for the BBox
        if case .malformed = vp.bounds { return .rejected(.malformed) }
        if case .nonFinite = vp.bounds { return .rejected(.nonFinite) }
        guard let bbox = vp.bbox.values, let lpts = vp.lpts.values, let gpts = vp.gpts.values,
              bbox.count == 4,
              lpts.count.isMultiple(of: 2), gpts.count.isMultiple(of: 2),
              lpts.count == gpts.count,
              gpts.count <= maximumControlValues else { return .rejected(.malformed) }
        let bounds = vp.bounds.values
        if let bounds, !bounds.count.isMultiple(of: 2) || bounds.count < 6 || bounds.count > maximumControlValues {
            return .rejected(.malformed)
        }
        guard bbox.allSatisfy({ abs($0) <= maximumSafePDFCoordinateMagnitude }) else {
            return .rejected(.malformed)
        }
        let n = gpts.count / 2
        guard n >= 3 else { return .rejected(.degenerateViewport) }
        let ox = bbox[0], oy = bbox[1], dx = bbox[2] - bbox[0], dy = bbox[3] - bbox[1]
        guard abs(dx * dy) > 0 else { return .rejected(.degenerateViewport) }
        guard lpts.allSatisfy({ lptsRange.contains($0) }) else { return .rejected(.lptsOutOfRange) }

        // GCS
        let gcs: GcsParseResult
        switch vp.gcs {
        case .missing:
            gcs = .fallback(.unreadableGcs)
        case .malformed:
            return .rejected(.malformed)
        case .described(let wkt, let epsg):
            if let epsg {
                let byCode = GcsParser.parse(epsg: epsg)
                gcs = (byCode.status == .fallbackLocalTM && wkt != nil) ? GcsParser.parse(wkt: wkt!) : byCode
            } else if let wkt {
                gcs = GcsParser.parse(wkt: wkt)
            } else {
                gcs = .fallback(.unreadableGcs)
            }
        }
        if gcs.status == .malformed { return .rejected(.malformed) }

        // GPTS are (lat, lon) in the GCS's own geographic datum, lon from its prime
        // meridian. lon + PRIMEM past 180 is off the earth, not wrapped
        var geo: [(lat: Double, lon: Double)] = []
        for i in 0..<n {
            let lat = gpts[2 * i], lon = gpts[2 * i + 1] + gcs.primeMeridian
            guard (-90.0...90.0).contains(lat), (-180.0...180.0).contains(lon) else {
                return .rejected(.gptsOffEarth)
            }
            geo.append((lat, lon))
        }
        if gcs.status == .unknownDatum { return .rejected(.unknownDatum) }
        guard let datum = gcs.datum else { return .rejected(.unknownDatum) }

        let crs: GeoCrs
        if gcs.status == .fallbackLocalTM {
            // documented fallback: local TM on the GPTS centroid, k0 1, no false origin
            crs = .transverseMercator(lat0: geo.reduce(0) { $0 + $1.lat } / Double(n),
                                      lon0: geo.reduce(0) { $0 + $1.lon } / Double(n),
                                      k0: 1, fe: 0, fn: 0)
        } else {
            guard let c = gcs.crs else { return .rejected(.unsupportedProjection) }
            crs = c
        }

        let pagePts = (0..<n).map { PdfPagePoint(x: ox + lpts[2 * $0] * dx, y: oy + lpts[2 * $0 + 1] * dy) }
        guard PlaneAffineFitter.eigenRatio(pagePts) >= 1e-6 else { return .rejected(.degenerateViewport) }

        var pairs: [PlaneAffineFitter.Pair] = []
        for (p, g) in zip(pagePts, geo) {
            guard let plane = crs.forward(lat: g.lat, lon: g.lon, ellipsoid: datum.ellipsoid) else {
                return .rejected(.gptsOffEarth)
            }
            pairs.append(.init(page: p, plane: PdfPagePoint(x: plane.x, y: plane.y)))
        }
        guard let affine = PlaneAffineFitter.fit(pairs) else { return .rejected(.degenerateViewport) }
        guard let stats = fitStats(affine: affine, pairs: pairs, crs: crs, datum: datum) else {
            return .rejected(.malformed)
        }
        guard stats.passesGate else { return .rejected(.rmsGate) }

        let crop: [PdfPagePoint]
        if let b = bounds {
            crop = (0..<(b.count / 2)).map { PdfPagePoint(x: ox + b[2 * $0] * dx, y: oy + b[2 * $0 + 1] * dy) }
        } else {
            crop = [PdfPagePoint(x: bbox[0], y: bbox[1]), PdfPagePoint(x: bbox[2], y: bbox[1]),
                    PdfPagePoint(x: bbox[2], y: bbox[3]), PdfPagePoint(x: bbox[0], y: bbox[3])]
        }
        let g = PdfGeoreference(page: page, crs: crs, datum: datum, affine: affine, crop: crop,
                                origin: .adobeVP,
                                fit: .init(rmsMetres: stats.rmsMetres, maxResidualMetres: stats.maxResidualMetres,
                                           perPoint: stats.residualsMetres, crossValidated: n >= 4),
                                datumAssumed: gcs.datumAssumed)
        guard g.isStructurallyValid, g.wgs84Bounds() != nil else { return .rejected(.degenerateViewport) }
        return .georef(g, PdfGeorefSelection(kind: .viewport, index: index, name: vp.name), stats)
    }

    /// residuals in metres (plane units scaled by local radii for lat/lon),
    /// gate = max(2 m, 0.002 x the control points' plane bbox diagonal)
    static func fitStats(affine: PlaneAffine, pairs: [PlaneAffineFitter.Pair],
                         crs: GeoCrs, datum: GeoDatum) -> PdfGeorefFitStats? {
        var res: [Double] = []
        for p in pairs {
            let pred = affine.apply(p.page.x, p.page.y)
            guard let lat = crs.inverse(x: p.plane.x, y: p.plane.y, ellipsoid: datum.ellipsoid)?.lat else { return nil }
            let m = PdfGeoreference.metresPerUnit(crs: crs, ellipsoid: datum.ellipsoid, latitude: lat)
            res.append(hypot((pred.x - p.plane.x) * m.east, (pred.y - p.plane.y) * m.north))
        }
        let xs = pairs.map(\.plane.x), ys = pairs.map(\.plane.y)
        let n = Double(pairs.count)
        guard let midLat = crs.inverse(x: xs.reduce(0, +) / n, y: ys.reduce(0, +) / n,
                                       ellipsoid: datum.ellipsoid)?.lat,
              let x0 = xs.min(), let x1 = xs.max(), let y0 = ys.min(), let y1 = ys.max() else { return nil }
        let m = PdfGeoreference.metresPerUnit(crs: crs, ellipsoid: datum.ellipsoid, latitude: midLat)
        let diag = hypot((x1 - x0) * m.east, (y1 - y0) * m.north)
        let rms = (res.reduce(0) { $0 + $1 * $1 } / n).squareRoot()
        let gate = max(2.0, 0.002 * diag)
        guard rms.isFinite, diag.isFinite else { return nil }
        return PdfGeorefFitStats(rmsMetres: rms, maxResidualMetres: res.max() ?? 0, residualsMetres: res,
                                 sheetDiagonalMetres: diag, gateLimitMetres: gate, passesGate: rms <= gate,
                                 controls: pairs)
    }

    // MARK: - OGC LGIDict

    /// Entry described "Layers" first, else the biggest neatline. Only that
    /// one entry is tried (same on Android), a broken Layers entry is a
    /// loud failure rather than a quiet fallback to some inset.
    static func build(lgiEntries entries: [LgiEntryInput], pageBox: [PdfPagePoint], page: Int = 0) -> PdfGeorefBuildResult? {
        // only called when /LGIDict was declared, so nothing in it is a loud failure
        guard !entries.isEmpty else { return .rejected(.malformed) }
        guard entries.count <= maximumEntries else { return .rejected(.malformed) }
        let index = selectLgiEntry(entries)
        return buildLgiEntry(entries[index], index: index, pageBox: pageBox, page: page)
    }

    static func selectLgiEntry(_ entries: [LgiEntryInput]) -> Int {
        if let i = entries.firstIndex(where: {
            $0.description?.trimmingCharacters(in: .whitespacesAndNewlines) == "Layers"
        }) { return i }
        var best = 0, bestArea = -1.0
        for (i, e) in entries.enumerated() {
            let a = shoelace(lgiOutline(e))
            if a > bestArea { best = i; bestArea = a }
        }
        return best
    }

    private static func lgiOutline(_ e: LgiEntryInput) -> [PdfPagePoint] {
        if let nl = e.neatline.values, nl.count.isMultiple(of: 2) {
            return (0..<(nl.count / 2)).map { PdfPagePoint(x: nl[2 * $0], y: nl[2 * $0 + 1]) }
        }
        if case .rows(let rows) = e.registration {
            return rows.compactMap { $0.count >= 2 ? PdfPagePoint(x: $0[0], y: $0[1]) : nil }
        }
        return []
    }

    static func shoelace(_ poly: [PdfPagePoint]) -> Double {
        guard poly.count >= 3 else { return 0 }
        var s = 0.0
        for i in 0..<poly.count {
            let p = poly[i], q = poly[(i + 1) % poly.count]
            s += p.x * q.y - q.x * p.y
        }
        let a = abs(s) / 2
        return a.isFinite ? a : 0
    }

    static func buildLgiEntry(_ e: LgiEntryInput, index: Int, pageBox: [PdfPagePoint],
                              page: Int = 0) -> PdfGeorefBuildResult {
        // structure
        switch e.ctm {
        case .malformed: return .rejected(.malformed)
        case .nonFinite: return .rejected(.nonFinite)
        case .values(let v) where v.count != 6: return .rejected(.malformed)
        default: break
        }
        switch e.neatline {
        case .malformed: return .rejected(.malformed)
        case .nonFinite: return .rejected(.nonFinite)
        case .values(let v) where v.count < 6 || !v.count.isMultiple(of: 2) || v.count > maximumControlValues:
            return .rejected(.malformed)
        case .values(let v) where !v.allSatisfy({ abs($0) <= maximumSafePDFCoordinateMagnitude }):
            return .rejected(.malformed)
        default: break
        }
        var registration: [[Double]]?
        switch e.registration {
        case .malformed: return .rejected(.malformed)
        case .nonFinite: return .rejected(.nonFinite)
        case .rows(let rows):
            guard rows.count <= maximumRegistrationRows, rows.allSatisfy({ $0.count == 4 }) else {
                return .rejected(.malformed)
            }
            guard rows.allSatisfy({ $0.allSatisfy(\.isFinite) }) else { return .rejected(.nonFinite) }
            registration = rows
        case .missing: break
        }
        guard e.ctm.values != nil || registration != nil else { return .rejected(.malformed) }

        // no /Projection is malformed, even when the numbers look like lon/lat
        guard let projection = e.projection else { return .rejected(.malformed) }
        let crs: GeoCrs, datum: GeoDatum, assumed: Bool
        switch lgiCrs(projection, display: e.display) {
        case .failure(let reason): return .rejected(reason)
        case .success(let v): (crs, datum, assumed) = (v.crs, v.datum, v.datumAssumed)
        }

        // CTM and Registration are always metres, Units only ever touches the
        // false origin (GDAL ParseProjDict, lgiRules.units)
        let ctm = e.ctm.values.map(PlaneAffine.init(pdfMatrix:))
        var pairs: [PlaneAffineFitter.Pair]?
        if let registration {
            let p = registration.map {
                PlaneAffineFitter.Pair(page: PdfPagePoint(x: $0[0], y: $0[1]), plane: PdfPagePoint(x: $0[2], y: $0[3]))
            }
            if p.count < 3 || PlaneAffineFitter.eigenRatio(p.map(\.page)) < 1e-6 {
                // useless Registration: fine next to a CTM (CTM alone), fatal on its own
                guard ctm != nil else { return .rejected(.degenerateViewport) }
            } else {
                pairs = p
            }
        }

        // CTM when it agrees with every Registration point to 1 m, else Registration
        var affine: PlaneAffine?
        var source = PdfGeorefSelection.Source.ctm
        var stats: PdfGeorefFitStats?
        if let pairs {
            let ctmAgrees: Bool = {
                guard let ctm, ctm.isInvertible else { return false }
                return pairs.allSatisfy { p in
                    let q = ctm.apply(p.page.x, p.page.y)
                    guard let lat = crs.inverse(x: p.plane.x, y: p.plane.y, ellipsoid: datum.ellipsoid)?.lat else {
                        return false
                    }
                    let m = PdfGeoreference.metresPerUnit(crs: crs, ellipsoid: datum.ellipsoid, latitude: lat)
                    return hypot((q.x - p.plane.x) * m.east, (q.y - p.plane.y) * m.north) <= 1.0
                }
            }()
            if ctmAgrees {
                affine = ctm
            } else {
                guard let fitted = PlaneAffineFitter.fit(pairs) else { return .rejected(.degenerateViewport) }
                guard let s = fitStats(affine: fitted, pairs: pairs, crs: crs, datum: datum) else {
                    return .rejected(.malformed)
                }
                guard s.passesGate else { return .rejected(.rmsGate) }
                affine = fitted
                stats = s
                source = .registration
            }
        } else {
            affine = ctm
        }
        guard let affine, affine.isInvertible else { return .rejected(.degenerateViewport) }

        let crop: [PdfPagePoint]
        if let nl = e.neatline.values {
            crop = (0..<(nl.count / 2)).map { PdfPagePoint(x: nl[2 * $0], y: nl[2 * $0 + 1]) }
        } else if let registration {
            // no neatline: the Registration page points are the outline, need 3 (Android: malformed too)
            guard registration.count >= 3 else { return .rejected(.malformed) }
            crop = registration.map { PdfPagePoint(x: $0[0], y: $0[1]) }
        } else {
            crop = pageBox
        }
        guard crop.count >= 3, shoelace(crop) > 0 else { return .rejected(.degenerateViewport) }
        let fit = stats.map {
            PdfGeoreference.Fit(rmsMetres: $0.rmsMetres, maxResidualMetres: $0.maxResidualMetres,
                                perPoint: $0.residualsMetres, crossValidated: $0.residualsMetres.count >= 4)
        }
        let g = PdfGeoreference(page: page, crs: crs, datum: datum, affine: affine, crop: crop,
                                origin: .lgiDict, fit: fit, datumAssumed: assumed)
        // a CTM that throws the neatline off the planet is junk, not a map
        guard g.isStructurallyValid, g.wgs84Bounds() != nil else { return .rejected(.gptsOffEarth) }
        return .georef(g, PdfGeorefSelection(kind: .lgiEntry, index: index, name: e.description, source: source), stats)
    }

    struct LgiCrs {
        var crs: GeoCrs
        var datum: GeoDatum
        var datumAssumed: Bool
        /// metres per /Units, already applied to the false origin
        var unitMetres: Double
    }

    static let internationalFoot = 0.3048
    static let usSurveyFoot = 1200.0 / 3937.0

    /// LGIDict /Projection -> crs + datum + linear unit. Codes per OGC GeoPDF /
    /// DIGEST Annex A, the ones real TerraGo/USGS files use (LE, NAS-C, WGE ...).
    /// Checks run type, datum, units, then the parameters.
    static func lgiCrs(_ p: [String: LgiValue], display: [String: LgiValue]?)
        -> Result<LgiCrs, PdfGeorefRejectReason> {
        guard let rawType = p["ProjectionType"] else { return .failure(.malformed) }
        guard let type = rawType.text?.uppercased(), !type.isEmpty else { return .failure(.malformed) }
        let geographic = ["LL", "LONGLAT", "GEOGRAPHIC", "GEO"].contains(type)
        guard geographic || ["UT", "UTM", "TC", "LE", "LC"].contains(type) else {
            return .failure(.unsupportedProjection)
        }

        // datum: a code, or an inline dict with Ellipsoid (+ ToWGS84). A broken
        // inline one is malformed (lgiRules.inlineDatum)
        let datum: GeoDatum
        var assumed = false
        switch p["Datum"] {
        case nil:
            // a projected CTM with no datum could be anything, lat/lon we call WGS84
            guard geographic else { return .failure(.unknownDatum) }
            datum = .wgs84
            assumed = true
        case .some(.dictionary(let d)):
            guard case .dictionary(let ell)? = d["Ellipsoid"],
                  let a = ell["SemiMajorAxis"]?.number, let invF = ell["InvFlattening"]?.number,
                  invF > 0, Ellipsoid(a: a, invF: invF).isPlausibleEarth else { return .failure(.malformed) }
            if let t = d["ToWGS84"] {
                guard case .dictionary(let tw) = t,
                      let dx = tw["dx"]?.number, let dy = tw["dy"]?.number, let dz = tw["dz"]?.number,
                      let custom = GeoDatum.custom(a: a, invF: invF, dx: dx, dy: dy, dz: dz) else {
                    return .failure(.malformed)
                }
                datum = custom
            } else {
                let modern = [Ellipsoid.wgs84, .grs80].contains { m in
                    abs(a - m.a) <= 1 && abs(invF - 1 / m.f) <= 1e-6
                }
                guard modern, let custom = GeoDatum.custom(a: a, invF: invF, dx: 0, dy: 0, dz: 0) else {
                    return .failure(.unknownDatum)
                }
                datum = custom
                assumed = true
            }
        case .some(let v):
            guard let code = v.code else { return .failure(.malformed) }
            guard let known = GeoDatum.matchingLgiCode(code) else { return .failure(.unknownDatum) }
            datum = known
        }

        // units: metres, FT or USSF, same set GDAL reads (State Plane TerraGo sheets).
        // Lat/lon ignores it. A UTM zone's false origin is fixed metres, so a foot
        // Units on UT can't mean anything we'd trust
        var unit = 1.0
        if !geographic, let units = p["Units"] {
            switch units.text?.uppercased() ?? "?" {
            case "M", "METER", "METERS", "METRE", "METRES": unit = 1
            case "FT": unit = internationalFoot
            case "USSF": unit = usSurveyFoot
            default: return .failure(.unsupportedProjection)
            }
            if unit != 1, type == "UT" || type == "UTM" { return .failure(.unsupportedProjection) }
        }

        func num(_ k: String) -> Double? {
            guard let v = p[k]?.number, v.isFinite else { return nil }
            return v
        }
        let crs: GeoCrs
        switch type {
        case _ where geographic:
            return .success(LgiCrs(crs: .geographic, datum: datum, datumAssumed: assumed, unitMetres: 1))
        case "UT", "UTM":
            // /Display only when the key's missing from /Projection, a junk value
            // there is malformed rather than quietly swapped (lgiRules.valueTypes).
            // range check the double BEFORE Int(), (1e30) or a huge real used to trap here
            guard let z = (p["Zone"] ?? display?["Zone"])?.number,
                  z == z.rounded(), z >= 1, z <= 60,
                  let hemi = (p["Hemisphere"] ?? display?["Hemisphere"])?.text?.uppercased() else {
                return .failure(.malformed)
            }
            let south: Bool
            switch hemi {
            case "N", "NORTH": south = false
            case "S", "SOUTH": south = true
            default: return .failure(.malformed)
            }
            crs = .utm(zone: Int(z), south: south)
        case "TC":
            guard let cm = num("CentralMeridian"), abs(cm) <= 360,
                  let lat0 = num("OriginLatitude"), abs(lat0) <= 90,
                  let fe = num("FalseEasting"), let fn = num("FalseNorthing"),
                  let k0 = num("ScaleFactor"), k0 > 0, k0 <= 10 else { return .failure(.malformed) }
            crs = .transverseMercator(lat0: lat0, lon0: cm, k0: k0, fe: fe * unit, fn: fn * unit)
        default: // LE (what real files write) / LC
            guard let p1 = num("StandardParallelOne"), abs(p1) < 89.999,
                  let lat0 = num("OriginLatitude"), abs(lat0) < 89.999,
                  let cm = num("CentralMeridian"), abs(cm) <= 360,
                  let fe = num("FalseEasting"), let fn = num("FalseNorthing") else { return .failure(.malformed) }
            // a present StandardParallelTwo that's null or junk doesn't default to the first one
            let p2: Double
            if p["StandardParallelTwo"] != nil {
                guard let v = num("StandardParallelTwo") else { return .failure(.malformed) }
                p2 = v
            } else {
                p2 = p1
            }
            guard abs(p2) < 89.999 else { return .failure(.malformed) }
            crs = .lambertConformalConic2SP(lat1: p1, lat2: p2, lat0: lat0, lon0: cm, fe: fe * unit, fn: fn * unit)
        }
        return .success(LgiCrs(crs: crs, datum: datum, datumAssumed: assumed, unitMetres: unit))
    }
}
