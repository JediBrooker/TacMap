import Foundation
import CoreGraphics
import CoreLocation

/// WP4 contract s3 constants. import_limits.json "calibration" pins them,
/// ImportLimitsContractTests checks every one.
enum CalibrationLimits {
    static let maxCalibrationPoints = 50
    static let undoDepth = 50
    static let maxDrafts = 16
    static let gpsMaxAccuracyM = 20.0
    static let degenerateEigenRatio = 0.02
    static let spreadMinFraction = 0.25
    static let toleranceFloorM = 10.0
    static let toleranceDiagonalFraction = 0.0005
    static let gradeFairToleranceMultiple = 3.0
    static let implausibleAnisotropy = 1.5
    static let plausibleScaleMin = 1_000.0
    static let plausibleScaleMax = 5_000_000.0
    static let offEarthLatDeg = 85.0
    static let outlierMinPoints = 5
    static let entryWarnSigma = 3.0
    static let entryWarnFloorM = 50.0
    static let nextCornerInsetFraction = 0.1
    static let residualLineMinScreenPt = 6.0
    static let moveToCrosshairMinScreenPt = 4.0
    static let markerHitRadiusPt = 24.0
    static let zoomHintScreenPtPerPagePt = 1.0
    static let cameraZoomMin = 2.0
    static let cameraZoomMax = 22.0
    static let cameraJacobianStepPt = 1.0
    static let transitionPrefetchDeadlineMs = 400
    static let metresPerPagePointAtUnitScale = 0.0254 / 72
    static let provisionalScaleDenominator = 50_000.0
    static let maxInputUTF16Units = CoordinateInputParser.maxInputUTF16Units
}

enum PageCorner: String, CaseIterable, Sendable {
    case topLeft, topRight, bottomRight, bottomLeft

    var messageKey: String {
        switch self {
        case .topLeft: return "calibration_next_corner_top_left"
        case .topRight: return "calibration_next_corner_top_right"
        case .bottomRight: return "calibration_next_corner_bottom_right"
        case .bottomLeft: return "calibration_next_corner_bottom_left"
        }
    }
}

enum CalibrationBlockReason: Equatable, Sendable {
    case needMore(Int)
    case degenerate
    case invalid
    case implausible

    var code: String {
        switch self {
        case .needMore: return "needMore"
        case .degenerate: return "degenerate"
        case .invalid: return "invalid"
        case .implausible: return "implausible"
        }
    }
}

enum CalibrationConfirmReason: String, Equatable, Sendable {
    case exact, poor, outlier, ambiguous, spreadLow
}

enum CalibrationFinishability: Equatable, Sendable {
    case blocked(CalibrationBlockReason)
    case confirm([CalibrationConfirmReason])
    case ready
}

enum CalibrationIssue: Equatable, Sendable {
    case outlier(number: Int, distanceM: Double)
    case ambiguous(first: Int, second: Int)
    case disagree(maxResidualM: Double)
    case disagreeAddFifth(maxResidualM: Double)
}

enum CalibrationRowStatus: String, Sendable {
    case ok, warn, error
}

/// A fit the fitter produced (blocked or not): page -> UTM affine in the
/// lowest-numbered point's zone, sheet datum.
struct CalibrationFit: Sendable {
    var affine: PlaneAffine
    var zone: Int
    var south: Bool
    var toleranceM: Double
    var georef: PdfGeoreference
}

/// Everything the fit panel shows. Pure, pinned by calibration_fit_report.json.
struct CalibrationFitReport: Sendable {
    var n: Int = 0
    var planeZone: Int?
    var south: Bool?
    var eigenRatio: Double?
    var affine: PlaneAffine?
    var diagonalM: Double?
    var toleranceM: Double?
    var rmsM: Double?
    var maxM: Double?
    /// by point number
    var residualsM: [Int: Double] = [:]
    var looRmsM: [Int: Double?] = [:]
    var looM: [Int: Double?] = [:]
    var anisotropy: Double?
    var scaleDenominator: Double?
    var grade: CalibrationGrade?
    var exact = false
    var issue: CalibrationIssue?
    var flagged: [Int] = []
    var rowStatus: [Int: CalibrationRowStatus]?
    var spreadLow = false
    var nextCorner: PageCorner?
    var nextCornerPage: PdfPagePoint?
    /// degenerate / invalid / implausible, n >= 3 only (needMore lives in finishability)
    var blocked: CalibrationBlockReason?
    var finishability: CalibrationFinishability = .blocked(.needMore(3))
    /// any fit, blocked or not (the leave-one-out predictor wants it)
    var anyFit: CalibrationFit?
    /// the fit when it isn't blocked, what gets drawn and saved
    var fit: CalibrationFit?

    var georef: PdfGeoreference? { fit?.georef }

    /// B2: the panel's secondary line before the camera has its say: the
    /// next-corner hint whenever there is one, n = 0 included
    var secondaryHint: CalibrationMessage? { nextCorner.map { CalibrationMessage(key: $0.messageKey) } }

    var primaryStatus: CalibrationMessage {
        if n == 0 { return CalibrationMessage(key: "calibration_intro") }
        if let blocked { return CalibrationMessage(key: "calibration_" + blocked.code) }
        if n < 3 { return CalibrationMessage(key: "calibration_need_points", args: ["placed": .number(n)]) }
        if let issue { return Self.issueMessage(issue) }
        if n == 3 { return CalibrationMessage(key: "calibration_exact_fit") }
        return CalibrationMessage(key: "calibration_fit_summary",
                                  args: ["points": .number(n), "rms": .metres(rmsM ?? 0),
                                         "grade": .key((grade ?? .good).messageKey)])
    }

    var confirmMessages: [CalibrationMessage] {
        guard case .confirm(let reasons) = finishability else { return [] }
        return reasons.map { r in
            switch r {
            case .exact: return CalibrationMessage(key: "calibration_finish_confirm_exact")
            case .poor: return CalibrationMessage(key: "calibration_finish_confirm_poor", args: ["rms": .metres(rmsM ?? 0)])
            case .outlier, .ambiguous: return issue.map(Self.issueMessage) ?? CalibrationMessage(key: "calibration_finish_confirm_exact")
            case .spreadLow: return CalibrationMessage(key: "calibration_finish_confirm_spread")
            }
        }
    }

    static func issueMessage(_ i: CalibrationIssue) -> CalibrationMessage {
        switch i {
        case let .outlier(number, d):
            return CalibrationMessage(key: "calibration_outlier", args: ["number": .number(number), "distance": .metres(d)])
        case let .ambiguous(a, b):
            return CalibrationMessage(key: "calibration_ambiguous", args: ["first": .number(a), "second": .number(b)])
        case .disagree(let d):
            return CalibrationMessage(key: "calibration_disagree", args: ["distance": .metres(d)])
        case .disagreeAddFifth(let d):
            return CalibrationMessage(key: "calibration_disagree_add_fifth", args: ["distance": .metres(d)])
        }
    }
}

/// contract s5 + s6. Port of evaluate() in scripts/gen_calibration_fixtures.py
enum CalibrationFitEvaluator {

    typealias L = CalibrationLimits

    static func evaluate(_ state: CalibrationState, pageBox: [PdfPagePoint], rotate: Int,
                         pageIndex: Int = 0) -> CalibrationFitReport {
        evaluate(points: state.points, datum: state.sheetDatum, pageBox: pageBox, rotate: rotate, pageIndex: pageIndex)
    }

    static func evaluate(points unsorted: [CalibrationPoint], datum: GeoDatum, pageBox: [PdfPagePoint],
                         rotate: Int, pageIndex: Int = 0) -> CalibrationFitReport {
        let pts = unsorted.sorted { $0.number < $1.number }
        var r = CalibrationFitReport()
        let n = pts.count
        r.n = n
        r.exact = n == 3
        let pages = pts.map(\.page)
        let box = boxCorners(pageBox)
        var blocked: CalibrationBlockReason?
        if let first = pts.first, let zs = planeZone(of: first, datum: datum) {
            r.planeZone = zs.zone
            r.south = zs.south
        }

        var fitted: (affine: PlaneAffine, pairs: [PlaneAffineFitter.Pair])?
        if n >= 3 {
            let ratio = PlaneAffineFitter.eigenRatio(pages)
            r.eigenRatio = ratio
            let planes = pts.map { p -> PdfPagePoint? in
                guard let z = r.planeZone, let s = r.south else { return nil }
                return toPlane(p, datum: datum, zone: z, south: s)
            }
            if ratio < L.degenerateEigenRatio {
                blocked = .degenerate
            } else if planes.contains(where: { $0 == nil }) {
                // a point more than 90 deg off the plane CM can't be forwarded
                blocked = .invalid
            } else {
                let pairs = zip(pages, planes.compactMap { $0 }).map { PlaneAffineFitter.Pair(page: $0, plane: $1) }
                if let a = PlaneAffineFitter.fit(pairs), a.isFinite, a.a * a.e - a.b * a.d != 0 {
                    fitted = (a, pairs)
                } else {
                    blocked = .invalid
                }
            }
        }

        if let (a, pairs) = fitted, let zone = r.planeZone, let south = r.south {
            let det = a.a * a.e - a.b * a.d
            let d = diagonal(a, box)
            let tau = max(L.toleranceFloorM, L.toleranceDiagonalFraction * d)
            let (rms, res) = rmsOf(a, pairs)
            r.affine = a
            r.diagonalM = d
            r.toleranceM = tau
            r.rmsM = rms
            r.maxM = res.max() ?? 0
            for (p, v) in zip(pts, res) { r.residualsM[p.number] = v }
            var loo: [Int: Double?] = [:], looRms: [Int: Double?] = [:]
            for (i, p) in pts.enumerated() {
                var rest = pairs
                rest.remove(at: i)
                guard rest.count >= 3, PlaneAffineFitter.eigenRatio(rest.map(\.page)) >= L.degenerateEigenRatio,
                      let ai = PlaneAffineFitter.fit(rest) else {
                    loo[p.number] = .some(nil)
                    looRms[p.number] = .some(nil)
                    continue
                }
                looRms[p.number] = rmsOf(ai, rest).rms
                let q = ai.apply(pairs[i].page.x, pairs[i].page.y)
                loo[p.number] = hypot(q.x - pairs[i].plane.x, q.y - pairs[i].plane.y)
            }
            r.looM = loo
            r.looRmsM = looRms
            let aniso = anisotropy(a)
            let scale = abs(det).squareRoot() / L.metresPerPagePointAtUnitScale
            r.anisotropy = aniso
            r.scaleDenominator = scale
            let g = PdfGeoreference(page: pageIndex, crs: .utm(zone: zone, south: south), datum: datum, affine: a,
                                    crop: pageBox, origin: .fiduciaries,
                                    fit: .init(rmsMetres: rms, maxResidualMetres: res.max() ?? 0, perPoint: res,
                                               crossValidated: n >= 4))
            var lats: [Double] = []
            for c in [(box.x0, box.y0), (box.x1, box.y0), (box.x1, box.y1), (box.x0, box.y1)] {
                guard let w = g.toWGS84(x: c.0, y: c.1), w.latitude.isFinite, w.longitude.isFinite else {
                    blocked = .invalid
                    continue
                }
                lats.append(w.latitude)
            }
            if blocked == nil, lats.map(abs).max() ?? 0 > L.offEarthLatDeg { blocked = .invalid }
            if blocked == nil, aniso > L.implausibleAnisotropy || !(L.plausibleScaleMin...L.plausibleScaleMax).contains(scale) {
                blocked = .implausible
            }
            let fit = CalibrationFit(affine: a, zone: zone, south: south, toleranceM: tau, georef: g)
            r.anyFit = fit
            if blocked == nil {
                r.fit = fit
                if n >= 4 {
                    r.grade = rms <= tau ? .good : (rms <= L.gradeFairToleranceMultiple * tau ? .fair : .poor)
                }
                if n >= L.outlierMinPoints && rms > tau {
                    let good = pts.compactMap { p -> (Double, Int)? in
                        guard let v = looRms[p.number] ?? nil, v <= tau else { return nil }
                        return (v, p.number)
                    }.sorted { $0.0 != $1.0 ? $0.0 < $1.0 : $0.1 < $1.1 }
                    if good.count == 1 {
                        let k = good[0].1
                        r.issue = .outlier(number: k, distanceM: (loo[k] ?? nil) ?? 0)
                    } else if good.count >= 2 {
                        r.issue = .ambiguous(first: good[0].1, second: good[1].1)
                    } else {
                        r.issue = .disagree(maxResidualM: res.max() ?? 0)
                    }
                } else if n == 4 && rms > tau {
                    r.issue = .disagreeAddFifth(maxResidualM: res.max() ?? 0)
                }
            }
        }
        if case .outlier(let k, _)? = r.issue { r.flagged = [k] }
        if r.grade != nil, let tau = r.toleranceM {
            var rows: [Int: CalibrationRowStatus] = [:]
            for (k, v) in r.residualsM {
                rows[k] = v > L.gradeFairToleranceMultiple * tau || r.flagged.contains(k) ? .error : (v > tau ? .warn : .ok)
            }
            r.rowStatus = rows
        }
        if n >= 3 {
            let xs = pages.map(\.x), ys = pages.map(\.y)
            r.spreadLow = (xs.max()! - xs.min()!) < L.spreadMinFraction * (box.x1 - box.x0)
                || (ys.max()! - ys.min()!) < L.spreadMinFraction * (box.y1 - box.y0)
        }
        if n < 3 || r.spreadLow {
            let nc = nextCorner(pageBox: pageBox, rotate: rotate, pages: pages)
            r.nextCorner = nc.corner
            r.nextCornerPage = nc.at
        }
        r.blocked = blocked
        if n < 3 {
            r.finishability = .blocked(.needMore(3 - n))
        } else if let blocked {
            r.finishability = .blocked(blocked)
        } else {
            var reasons: [CalibrationConfirmReason] = []
            if n == 3 { reasons.append(.exact) }
            if r.grade == .poor { reasons.append(.poor) }
            switch r.issue {
            case .outlier?: reasons.append(.outlier)
            case .ambiguous?: reasons.append(.ambiguous)
            default: break
            }
            if r.spreadLow { reasons.append(.spreadLow) }
            r.finishability = reasons.isEmpty ? .ready : .confirm(reasons)
        }
        return r
    }

    // MARK: - plane

    /// zone + hemisphere of a point: its typed grid zone, else the standard 6
    /// degree zone of its lat/lon (no Norway/Svalbard, same as WP1)
    static func planeZone(of p: CalibrationPoint, datum: GeoDatum) -> (zone: Int, south: Bool)? {
        guard let r = p.resolved(sheetDatum: datum) else { return nil }
        if let g = r.grid { return (g.zone, g.south) }
        let z = Int(floor((r.lon + 180) / 6)) + 1
        return (min(max(z, 1), 60), r.lat < 0)
    }

    static func toPlane(_ p: CalibrationPoint, datum: GeoDatum, zone: Int, south: Bool) -> PdfPagePoint? {
        guard let r = p.resolved(sheetDatum: datum) else { return nil }
        if let g = r.grid, g.zone == zone, g.south == south { return PdfPagePoint(x: g.e, y: g.n) }
        guard let q = CoordinateInputParser.forward(datum, zone: zone, south: south, lat: r.lat, lon: r.lon) else { return nil }
        return PdfPagePoint(x: q.x, y: q.y)
    }

    // MARK: - bits

    struct Box { var x0, y0, x1, y1: Double }

    static func boxCorners(_ pb: [PdfPagePoint]) -> Box {
        guard pb.count >= 3 else { return Box(x0: 0, y0: 0, x1: 0, y1: 0) }
        return Box(x0: pb[0].x, y0: pb[0].y, x1: pb[1].x, y1: pb[2].y)
    }

    static func diagonal(_ a: PlaneAffine, _ b: Box) -> Double {
        let p = a.apply(b.x0, b.y0), q = a.apply(b.x1, b.y0), r = a.apply(b.x1, b.y1), s = a.apply(b.x0, b.y1)
        return max(hypot(p.x - r.x, p.y - r.y), hypot(q.x - s.x, q.y - s.y))
    }

    static func rmsOf(_ a: PlaneAffine, _ pairs: [PlaneAffineFitter.Pair]) -> (rms: Double, residuals: [Double]) {
        let res = pairs.map { p -> Double in
            let q = a.apply(p.page.x, p.page.y)
            return hypot(q.x - p.plane.x, q.y - p.plane.y)
        }
        guard !res.isEmpty else { return (0, []) }
        return ((res.reduce(0) { $0 + $1 * $1 } / Double(res.count)).squareRoot(), res)
    }

    /// sigma_max / sigma_min of the 2x2 linear part
    static func anisotropy(_ a: PlaneAffine) -> Double {
        let t = a.a * a.a + a.b * a.b + a.d * a.d + a.e * a.e
        let det = a.a * a.e - a.b * a.d
        let disc = max(0, t * t - 4 * det * det).squareRoot()
        let s1 = ((t + disc) / 2).squareRoot()
        let s2 = (max(0, (t - disc) / 2)).squareRoot()
        // s2 from the difference loses digits when it's tiny, det/s1 doesn't
        let s2b = s1 > 0 ? abs(det) / s1 : 0
        let sigmaMin = s2b > 0 ? s2b : s2
        return sigmaMin > 0 ? s1 / sigmaMin : .infinity
    }

    /// pageBox corners as the page is VIEWED after /Rotate, TL TR BR BL, each
    /// with its opposite raw corner
    static func uprightCorners(pageBox: [PdfPagePoint], rotate: Int) -> [(PageCorner, PdfPagePoint, PdfPagePoint)] {
        let b = boxCorners(pageBox)
        let raw: [String: PdfPagePoint] = ["bl": PdfPagePoint(x: b.x0, y: b.y0), "br": PdfPagePoint(x: b.x1, y: b.y0),
                                           "tr": PdfPagePoint(x: b.x1, y: b.y1), "tl": PdfPagePoint(x: b.x0, y: b.y1)]
        let seq: [String]
        switch ((rotate % 360) + 360) % 360 {
        case 90: seq = ["bl", "tl", "tr", "br"]
        case 180: seq = ["br", "bl", "tl", "tr"]
        case 270: seq = ["tr", "br", "bl", "tl"]
        default: seq = ["tl", "tr", "br", "bl"]
        }
        let opp = ["tl": "br", "br": "tl", "tr": "bl", "bl": "tr"]
        return zip(PageCorner.allCases, seq).map { ($0, raw[$1]!, raw[opp[$1]!]!) }
    }

    static func nextCorner(pageBox: [PdfPagePoint], rotate: Int, pages: [PdfPagePoint]) -> (corner: PageCorner, at: PdfPagePoint) {
        let f = L.nextCornerInsetFraction
        var best: (d: Double, corner: PageCorner, at: PdfPagePoint)?
        for (name, c, o) in uprightCorners(pageBox: pageBox, rotate: rotate) {
            let at = PdfPagePoint(x: c.x + f * (o.x - c.x), y: c.y + f * (o.y - c.y))
            let dmin = pages.map { hypot(at.x - $0.x, at.y - $0.y) }.min() ?? .infinity
            if best == nil || dmin > best!.d { best = (dmin, name, at) }
        }
        return (best!.corner, best!.at)
    }

    // MARK: - entry check (s6.7)

    enum Predictor: String, Sendable { case fitWithoutEditing, currentFit, base, none }

    struct EntryCheck: Sendable {
        var predictor: Predictor
        var leverage: Double?
        var toleranceM: Double?
        var predictedWGS84: CLLocationCoordinate2D?
        var typedWGS84: CLLocationCoordinate2D?
        var distanceM: Double?
        var thresholdM: Double?
        var warn: Bool

        var message: CalibrationMessage? {
            guard warn, let distanceM else { return nil }
            return CalibrationMessage(key: "calibration_warn_far", args: ["distance": .metres(distanceM)])
        }
    }

    /// - Parameters:
    ///   - pending: the point being saved (page + typed reference); number is ignored
    ///   - editing: id of the point being edited, nil for a new one
    ///   - base: the georef calibration started from; only a non-provisional one predicts
    static func entryCheck(state: CalibrationState, report: CalibrationFitReport, pageBox: [PdfPagePoint],
                           rotate: Int, pending: CalibrationPoint, editing: UUID?,
                           base: PdfGeoreference?) -> EntryCheck {
        let datum = state.sheetDatum
        let pts = state.points.sorted { $0.number < $1.number }
        let page = pending.page
        var pred: (Predictor, PdfGeoreference, Double, Double)?
        if let editing {
            let others = pts.filter { $0.id != editing }
            if others.count >= 3, PlaneAffineFitter.eigenRatio(others.map(\.page)) >= L.degenerateEigenRatio {
                let sub = evaluate(points: others, datum: datum, pageBox: pageBox, rotate: rotate)
                if let f = sub.anyFit {
                    pred = (.fitWithoutEditing, f.georef, f.toleranceM, leverage(others.map(\.page), page))
                }
            }
        }
        if pred == nil, pts.count >= 3, let f = report.fit {
            pred = (.currentFit, f.georef, f.toleranceM, leverage(pts.map(\.page), page))
        }
        if pred == nil, let base, base.origin != .provisional {
            let b = boxCorners(pageBox)
            let cs = [(b.x0, b.y0), (b.x1, b.y0), (b.x1, b.y1), (b.x0, b.y1)].map { base.toWGS84(x: $0.0, y: $0.1) }
            if cs.allSatisfy({ $0 != nil }) {
                let d = max(geodesic(cs[0]!, cs[2]!), geodesic(cs[1]!, cs[3]!))
                pred = (.base, base, max(L.toleranceFloorM, L.toleranceDiagonalFraction * d), 0)
            }
        }
        let typed = pending.typedWGS84(sheetDatum: datum)
        guard let (kind, g, tau, h) = pred, let typed, let pw = g.toWGS84(x: page.x, y: page.y) else {
            return EntryCheck(predictor: .none, leverage: nil, toleranceM: nil, predictedWGS84: nil,
                              typedWGS84: typed, distanceM: nil, thresholdM: nil, warn: false)
        }
        let d = geodesic(pw, typed)
        let thr = max(L.entryWarnSigma * tau * (1 + h).squareRoot(), L.entryWarnFloorM)
        return EntryCheck(predictor: kind, leverage: h, toleranceM: tau, predictedWGS84: pw, typedWGS84: typed,
                          distanceM: d, thresholdM: thr, warn: d > thr)
    }

    /// x0'(A'A)^-1 x0, A rows [x y 1]
    static func leverage(_ pages: [PdfPagePoint], _ p: PdfPagePoint) -> Double {
        // centre first, the plain 3x3 with page coords ~1000 is badly scaled
        let n = Double(pages.count)
        guard n > 0 else { return 0 }
        let mx = pages.reduce(0) { $0 + $1.x } / n, my = pages.reduce(0) { $0 + $1.y } / n
        var sxx = 0.0, sxy = 0.0, syy = 0.0
        for q in pages {
            let x = q.x - mx, y = q.y - my
            sxx += x * x; sxy += x * y; syy += y * y
        }
        let det = sxx * syy - sxy * sxy
        guard det > 0, det.isFinite else { return .infinity }
        let x = p.x - mx, y = p.y - my
        // centred design: block diag [S, n], so h = 1/n + v' S^-1 v
        return 1 / n + (syy * x * x - 2 * sxy * x * y + sxx * y * y) / det
    }

    static func geodesic(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> Double {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }
}

/// contract s7.3 + s7.4: keep the page point under the crosshair and the page's
/// on-screen scale when the georef underneath changes, and what a capture reads.
enum CalibrationCameraAnchor {

    /// web mercator world point, zoom 0, 256 units across
    static func world(_ c: CLLocationCoordinate2D) -> (x: Double, y: Double) {
        let p = WebMercator.worldPoint(c, zoom: 0)
        return (Double(p.x), Double(p.y))
    }

    /// sqrt|det J|, J = d(world zoom 0)/d(page) by central differences
    static func scale(_ g: PdfGeoreference, at p: PdfPagePoint) -> Double? {
        let h = CalibrationLimits.cameraJacobianStepPt
        guard let a = g.toWGS84(x: p.x + h, y: p.y), let b = g.toWGS84(x: p.x - h, y: p.y),
              let c = g.toWGS84(x: p.x, y: p.y + h), let d = g.toWGS84(x: p.x, y: p.y - h) else { return nil }
        let wa = world(a), wb = world(b), wc = world(c), wd = world(d)
        let j11 = (wa.x - wb.x) / (2 * h), j21 = (wa.y - wb.y) / (2 * h)
        let j12 = (wc.x - wd.x) / (2 * h), j22 = (wc.y - wd.y) / (2 * h)
        let s = abs(j11 * j22 - j12 * j21).squareRoot()
        return s.isFinite && s > 0 ? s : nil
    }

    static func adjust(camera: MapCamera, from old: PdfGeoreference, to new: PdfGeoreference,
                       zoomRange: ClosedRange<Double> = CalibrationLimits.cameraZoomMin...CalibrationLimits.cameraZoomMax) -> MapCamera? {
        guard let (centre, z) = anchored(camera: camera, from: old, to: new) else { return nil }
        var out = camera
        out.center = centre
        out.zoom = min(max(z, zoomRange.lowerBound), zoomRange.upperBound)
        return out
    }

    /// the zoom before the [2, 22] clamp (fixture unclampedZoom), nil when any step is
    static func unclampedZoom(camera: MapCamera, from old: PdfGeoreference, to new: PdfGeoreference) -> Double? {
        anchored(camera: camera, from: old, to: new)?.zoom
    }

    private static func anchored(camera: MapCamera, from old: PdfGeoreference,
                                 to new: PdfGeoreference) -> (centre: CLLocationCoordinate2D, zoom: Double)? {
        guard let p = old.toPage(lat: camera.center.latitude, lon: camera.center.longitude) else { return nil }
        let page = PdfPagePoint(x: p.x, y: p.y)
        guard let centre = new.toWGS84(x: page.x, y: page.y),
              let sOld = scale(old, at: page), let sNew = scale(new, at: page) else { return nil }
        let z = camera.zoom + log2(sOld / sNew)
        guard z.isFinite else { return nil }
        return (centre, z)
    }

    static func screenPointsPerPagePoint(_ g: PdfGeoreference, at p: PdfPagePoint, zoom: Double) -> Double? {
        scale(g, at: p).map { pow(2, zoom) * $0 }
    }
}

/// What "Add point" / "Set here" read off the live camera (contract s7.4)
struct CalibrationCapture: Sendable {
    var page: PdfPagePoint
    var onSheet: Bool
    var screenPtPerPagePt: Double?
    var generation: Int

    var zoomHint: Bool { (screenPtPerPagePt ?? 1) < CalibrationLimits.zoomHintScreenPtPerPagePt }

    /// the status this capture puts up on its own (fixture capture[].status):
    /// off the sheet wins, else the zoom hint, else nothing
    var status: CalibrationMessage? {
        if !onSheet { return CalibrationMessage(key: "calibration_off_sheet") }
        return zoomHint ? CalibrationMessage(key: "calibration_zoom_hint") : nil
    }

    /// B2 secondary line: the zoom hint only while the crosshair is on the
    /// sheet, otherwise the report's next-corner hint
    static func secondaryLine(report: CalibrationFitReport, onSheet: Bool, zoomHint: Bool) -> CalibrationMessage? {
        if onSheet && zoomHint { return CalibrationMessage(key: "calibration_zoom_hint") }
        return report.secondaryHint
    }

    static func capture(georef: PdfGeoreference, pageBox: [PdfPagePoint], camera: MapCamera,
                        generation: Int) -> CalibrationCapture? {
        guard let p = georef.toPage(lat: camera.center.latitude, lon: camera.center.longitude) else { return nil }
        let page = PdfPagePoint(x: p.x, y: p.y)
        let b = CalibrationFitEvaluator.boxCorners(pageBox)
        let on = page.x >= min(b.x0, b.x1) && page.x <= max(b.x0, b.x1) && page.y >= min(b.y0, b.y1) && page.y <= max(b.y0, b.y1)
        return CalibrationCapture(page: page, onSheet: on,
                                  screenPtPerPagePt: CalibrationCameraAnchor.screenPointsPerPagePoint(georef, at: page, zoom: camera.zoom),
                                  generation: generation)
    }
}
