import XCTest
import CoreLocation
@testable import TacticalMaps

/// The three WP4/WP5 shared fixtures (testdata/calibration_input.json,
/// calibration_fit_report.json, import_limits.json). Android runs the same files.
enum WP4Fixtures {
    static func url(_ name: String) -> URL? {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let c = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: c.path) { return c }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }

    static func load(_ name: String) -> [String: Any] {
        guard let u = url(name), let data = try? Data(contentsOf: u),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("can't load testdata/\(name)")
            return [:]
        }
        return obj
    }

    static func dbl(_ a: Any?) -> Double {
        if let n = a as? NSNumber { return n.doubleValue }
        return .nan
    }

    static func page(_ a: Any?) -> PdfPagePoint {
        let v = (a as? [Any])?.map(dbl) ?? [.nan, .nan]
        return PdfPagePoint(x: v[0], y: v[1])
    }

    static func pageBox(_ a: Any?) -> [PdfPagePoint] { ((a as? [Any]) ?? []).map(page) }

    static func reference(_ a: Any?) throws -> CalibrationReference {
        let data = try JSONSerialization.data(withJSONObject: a ?? [:])
        return try JSONDecoder().decode(CalibrationReference.self, from: data)
    }

    static func point(_ p: [String: Any]) throws -> CalibrationPoint {
        CalibrationPoint(number: (p["number"] as? NSNumber)?.intValue ?? 0, page: page(p["page"]),
                         input: p["input"] as? String ?? "", reference: try reference(p["reference"]),
                         kind: CalibrationPointKind(rawValue: p["kind"] as? String ?? "intersection") ?? .intersection,
                         datumOverride: p["datumOverride"] as? String)
    }

    /// pdf_georef.json shape {crs, datum, affine, crop, origin}
    static func georef(_ a: Any?) throws -> PdfGeoreference {
        let j = try XCTUnwrap(a as? [String: Any])
        let crs = try JSONDecoder().decode(GeoCrs.self, from: JSONSerialization.data(withJSONObject: j["crs"] ?? [:]))
        let datum = try JSONDecoder().decode(GeoDatum.self, from: JSONSerialization.data(withJSONObject: j["datum"] ?? [:]))
        let aff = ((j["affine"] as? [Any]) ?? []).map(dbl)
        let origin = PdfGeoreference.Origin(rawValue: j["origin"] as? String ?? "") ?? .fiduciaries
        return PdfGeoreference(crs: crs, datum: datum,
                               affine: PlaneAffine(a: aff[0], b: aff[1], c: aff[2], d: aff[3], e: aff[4], f: aff[5]),
                               crop: pageBox(j["crop"]), origin: origin)
    }

    /// CalibrationMessage -> the fixture's {key, args} with plain json values
    static func json(_ m: CalibrationMessage) -> [String: Any] {
        var args: [String: Any] = [:]
        for (k, v) in m.args {
            switch v {
            case .text(let s): args[k] = s
            case .metres(let d): args[k] = d
            case .number(let n): args[k] = n
            case .key(let s): args[k] = s
            }
        }
        return ["key": m.key, "args": args]
    }

    static func assertMessage(_ got: CalibrationMessage?, _ want: Any?, tol: Double = 1e-6, _ ctx: String,
                              file: StaticString = #filePath, line: UInt = #line) {
        guard let want = want as? [String: Any] else {
            XCTAssertNil(got, ctx, file: file, line: line)
            return
        }
        guard let got else { return XCTFail("\(ctx): no message, want \(want)", file: file, line: line) }
        XCTAssertEqual(got.key, want["key"] as? String, ctx, file: file, line: line)
        let wa = want["args"] as? [String: Any] ?? [:]
        XCTAssertEqual(Set(got.args.keys), Set(wa.keys), "\(ctx) arg names", file: file, line: line)
        for (k, v) in got.args {
            switch v {
            case .text(let s), .key(let s): XCTAssertEqual(s, wa[k] as? String, "\(ctx).\(k)", file: file, line: line)
            case .number(let n): XCTAssertEqual(n, (wa[k] as? NSNumber)?.intValue, "\(ctx).\(k)", file: file, line: line)
            case .metres(let d): XCTAssertEqual(d, dbl(wa[k]), accuracy: tol, "\(ctx).\(k)", file: file, line: line)
            }
        }
    }
}

final class CoordinateInputParserContractTests: XCTestCase {

    func testEveryCalibrationInputCase() throws {
        let fx = WP4Fixtures.load("calibration_input.json")
        let cases = fx["cases"] as? [[String: Any]] ?? []
        // count guard: an empty or truncated list must not pass quietly
        XCTAssertGreaterThanOrEqual(cases.count, 100)
        let tol = fx["tolerance"] as? [String: Any] ?? [:]
        let mTol = WP4Fixtures.dbl(tol["metres"]), dTol = WP4Fixtures.dbl(tol["degrees"])
        var ran = 0
        for c in cases {
            let id = c["id"] as? String ?? "?"
            let ctxJ = c["context"] as? [String: Any] ?? [:]
            var anchor: GridAnchor?
            if let a = ctxJ["gridAnchor"] as? [String: Any] {
                anchor = GridAnchor(zone: (a["zone"] as? NSNumber)?.intValue ?? 0,
                                    band: (a["band"] as? String)?.first ?? "?",
                                    square: a["square"] as? String ?? "",
                                    fromPoint: (a["fromPoint"] as? NSNumber)?.intValue ?? 0)
            }
            var predicted: CLLocationCoordinate2D?
            if let p = ctxJ["predicted"] as? [String: Any] {
                predicted = CLLocationCoordinate2D(latitude: WP4Fixtures.dbl(p["lat"]), longitude: WP4Fixtures.dbl(p["lon"]))
            }
            let ctx = CoordinateParseContext(datumID: ctxJ["datumId"] as? String ?? "WGS84",
                                             isFirstPoint: ctxJ["isFirstPoint"] as? Bool ?? true,
                                             kind: CalibrationPointKind(rawValue: ctxJ["kind"] as? String ?? "") ?? .intersection,
                                             gridAnchor: anchor, predicted: predicted)
            let result = CoordinateInputParser.parse(c["input"] as? String ?? "", context: ctx)
            let expect = c["expect"] as? [String: Any] ?? [:]
            ran += 1
            if let ok = expect["ok"] as? [String: Any] {
                guard case .success(let r) = result else {
                    XCTFail("\(id): wanted ok, got \(result)")
                    continue
                }
                XCTAssertEqual(r.source.rawValue, ok["source"] as? String, id)
                XCTAssertEqual(r.zone, (ok["zone"] as? NSNumber)?.intValue, id)
                XCTAssertEqual(r.south, ok["south"] as? Bool, id)
                XCTAssertEqual(r.band.map(String.init), ok["band"] as? String, id)
                XCTAssertEqual(r.square, ok["square"] as? String, id)
                if let e = ok["easting"] as? NSNumber { XCTAssertEqual(r.easting ?? .nan, e.doubleValue, accuracy: mTol, id) } else { XCTAssertNil(r.easting, id) }
                if let n = ok["northing"] as? NSNumber { XCTAssertEqual(r.northing ?? .nan, n.doubleValue, accuracy: mTol, id) } else { XCTAssertNil(r.northing, id) }
                XCTAssertEqual(r.digits, (ok["digits"] as? NSNumber)?.intValue, id)
                if let cell = ok["cellSizeM"] as? NSNumber { XCTAssertEqual(r.cellSizeM, cell.doubleValue, id) } else { XCTAssertNil(r.cellSizeM, id) }
                XCTAssertEqual(r.effectiveKind?.rawValue, ok["effectiveKind"] as? String, id)
                XCTAssertEqual(r.kindSegment, ok["kindSegment"] as? Bool, id)
                if let lat = ok["lat"] as? NSNumber { XCTAssertEqual(r.lat ?? .nan, lat.doubleValue, accuracy: dTol, id) } else { XCTAssertNil(r.lat, id) }
                if let lon = ok["lon"] as? NSNumber { XCTAssertEqual(r.lon ?? .nan, lon.doubleValue, accuracy: dTol, id) } else { XCTAssertNil(r.lon, id) }
                switch r.completedFrom {
                case .map?: XCTAssertEqual(ok["completedFrom"] as? String, "map", id)
                case .point(let n)?:
                    XCTAssertEqual(ok["completedFrom"] as? String, "point", id)
                    XCTAssertEqual((ok["completedFromPoint"] as? NSNumber)?.intValue, n, id)
                case nil: XCTAssertTrue(ok["completedFrom"] is NSNull || ok["completedFrom"] == nil, id)
                }
                XCTAssertEqual(r.canonical, ok["canonical"] as? String, id)
                let res = ok["resolved"] as? [String: Any] ?? [:]
                XCTAssertEqual(r.resolved.lat, WP4Fixtures.dbl(res["lat"]), accuracy: dTol, "\(id) resolved lat")
                XCTAssertEqual(r.resolved.lon, WP4Fixtures.dbl(res["lon"]), accuracy: dTol, "\(id) resolved lon")
                // the stored point resolves to the same place, and WGS84 matches too
                let datum = try XCTUnwrap(GeoDatum.named(ctx.datumID))
                let p = CalibrationPoint(number: 1, page: PdfPagePoint(x: 0, y: 0), input: "", reference: r.reference,
                                         kind: r.effectiveKind ?? .intersection)
                let back = try XCTUnwrap(p.resolved(sheetDatum: datum), id)
                XCTAssertEqual(back.lat, r.resolved.lat, accuracy: dTol, "\(id) stored")
                XCTAssertEqual(back.lon, r.resolved.lon, accuracy: dTol, "\(id) stored")
                let w = try XCTUnwrap(p.typedWGS84(sheetDatum: datum), id)
                let ww = ok["resolvedWGS84"] as? [String: Any] ?? [:]
                XCTAssertEqual(w.latitude, WP4Fixtures.dbl(ww["lat"]), accuracy: dTol, "\(id) wgs84")
                XCTAssertEqual(w.longitude, WP4Fixtures.dbl(ww["lon"]), accuracy: dTol, "\(id) wgs84")
                if let truth = c["truthWGS84"] as? [String: Any] {
                    let expected = CLLocation(latitude: WP4Fixtures.dbl(truth["lat"]), longitude: WP4Fixtures.dbl(truth["lon"]))
                    XCTAssertLessThanOrEqual(CLLocation(latitude: w.latitude, longitude: w.longitude).distance(from: expected),
                                             WP4Fixtures.dbl(c["truthToleranceM"]), "\(id) independent PROJ truth")
                }
                let msgs = ok["messages"] as? [Any] ?? []
                XCTAssertEqual(r.messages.count, msgs.count, "\(id) message count")
                for (m, w) in zip(r.messages, msgs) { WP4Fixtures.assertMessage(m, w, "\(id) message") }
            } else {
                guard case .failure(let err) = result else {
                    XCTFail("\(id): wanted \(expect["error"] ?? "?"), got \(result)")
                    continue
                }
                XCTAssertEqual(err.code, expect["error"] as? String, id)
                XCTAssertEqual(err.messageKey, expect["messageKey"] as? String, id)
                let args = expect["args"] as? [String: Any] ?? [:]
                // T1: the arg names too, not just the values we happen to read
                let names: Set<String>
                switch err {
                case .invalidSquare: names = ["square", "zone"]
                case .bandMismatch: names = ["band"]
                case .oldLettering: names = ["datumId"]
                default: names = []
                }
                XCTAssertEqual(Set(args.keys), names, "\(id) arg names")
                switch err {
                case let .invalidSquare(square, zone):
                    XCTAssertEqual(square, args["square"] as? String, id)
                    XCTAssertEqual(zone, (args["zone"] as? NSNumber)?.intValue, id)
                case .bandMismatch(let band): XCTAssertEqual(band, args["band"] as? String, id)
                case .oldLettering(let d): XCTAssertEqual(d, args["datumId"] as? String, id)
                default: XCTAssertTrue(args.isEmpty, id)
                }
            }
        }
        XCTAssertEqual(ran, cases.count)
    }

    func testErrorKeysTable() {
        let fx = WP4Fixtures.load("calibration_input.json")
        let keys = fx["errorKeys"] as? [String: Any] ?? [:]
        let all: [CoordinateParseError] = [.empty, .unrecognised, .tooCoarse, .unequalDigits, .needsFullReference,
                                           .invalidSquare(square: "AA", zone: 1), .bandMismatch(band: "C"),
                                           .polarUnsupported, .outOfRange, .oldLettering(datumID: "NAD27")]
        XCTAssertEqual(Set(all.map(\.code)), Set(keys.keys))
        for e in all { XCTAssertEqual(e.messageKey, keys[e.code] as? String, e.code) }
    }

    /// r1 A1/OD-F11: THE datum name table and the cell size text, both languages
    func testDisplayTables() throws {
        let fx = WP4Fixtures.load("calibration_input.json")
        let display = try XCTUnwrap(fx["display"] as? [String: Any])
        let names = display["datumDisplayNames"] as? [[String: Any]] ?? []
        XCTAssertEqual(names.count, 16)
        XCTAssertEqual(CalibrationDatumChoice.order, names.compactMap { $0["id"] as? String }, "sheet order")
        for n in names {
            let id = n["id"] as? String ?? "?"
            XCTAssertEqual(CalibrationDatumChoice.displayName(id), n["name"] as? String, id)
        }
        let cells = display["cellSizeText"] as? [[String: Any]] ?? []
        XCTAssertEqual(cells.count, 7)
        for c in cells {
            let m = WP4Fixtures.dbl(c["sizeM"])
            XCTAssertEqual(DisplayFormat.cellSize(m), c["en"] as? String, "\(m)")
            XCTAssertEqual(DisplayFormat.cellSize(m), c["de"] as? String, "\(m) de")
        }
        // the whole phrase, rendered from the catalogue in both languages
        let rows = display["interpretationCells"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 4)
        let cases = fx["cases"] as? [[String: Any]] ?? []
        let before = AppLanguage.shared.selection
        defer { AppLanguage.shared.select(before) }
        for r in rows {
            let from = r["fromCase"] as? String ?? "?"
            let c = try XCTUnwrap(cases.first { $0["id"] as? String == from }, from)
            let msgs = ((c["expect"] as? [String: Any])?["ok"] as? [String: Any])?["messages"] as? [[String: Any]] ?? []
            let m = try XCTUnwrap(msgs.first { $0["key"] as? String == r["key"] as? String }, from)
            var args: [String: CalibrationMessage.Arg] = [:]
            for (k, v) in m["args"] as? [String: Any] ?? [:] { args[k] = .metres(WP4Fixtures.dbl(v)) }
            let message = CalibrationMessage(key: r["key"] as? String ?? "", args: args)
            for (k, want) in r["argText"] as? [String: String] ?? [:] {
                if case .metres(let v)? = args[k] { XCTAssertEqual(DisplayFormat.cellSize(v), want, "\(from) \(k)") }
            }
            AppLanguage.shared.select(.en)
            XCTAssertEqual(message.text, r["en"] as? String, from)
            AppLanguage.shared.select(.de)
            XCTAssertEqual(message.text, r["de"] as? String, "\(from) de")
        }
    }

    func testWhitespaceTableMatchesPdfGeorefFixture() throws {
        let fx = WP4Fixtures.load("pdf_georef.json")
        let want = Set(((fx["fiduciaryFits"] as? [String: Any])?["whiteSpaceCodePoints"] as? [NSNumber] ?? []).map(\.uint32Value))
        XCTAssertEqual(want.count, 29)
        XCTAssertEqual(CoordinateInputParser.whiteSpaceCodePoints, want)
    }
}

final class CalibrationFitReportContractTests: XCTestCase {

    private lazy var fx = WP4Fixtures.load("calibration_fit_report.json")

    private func caseByID(_ id: String) -> [String: Any]? {
        (fx["cases"] as? [[String: Any]] ?? []).first { $0["id"] as? String == id }
    }

    private func state(_ c: [String: Any]) throws -> CalibrationState {
        let pts = try (c["points"] as? [[String: Any]] ?? []).map(WP4Fixtures.point)
        return CalibrationState(datumID: c["datumId"] as? String, points: pts)
    }

    func testEveryFitReportCase() throws {
        let cases = fx["cases"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(cases.count, 25)
        let tol = fx["tolerance"] as? [String: Any] ?? [:]
        let m = WP4Fixtures.dbl(tol["metres"]), ratio = WP4Fixtures.dbl(tol["ratio"])
        var ran = 0
        for c in cases {
            let id = c["id"] as? String ?? "?"
            let e = c["expect"] as? [String: Any] ?? [:]
            let s = try state(c)
            let pb = WP4Fixtures.pageBox(c["pageBox"])
            let r = CalibrationFitEvaluator.evaluate(s, pageBox: pb, rotate: (c["rotate"] as? NSNumber)?.intValue ?? 0)
            ran += 1
            XCTAssertEqual(r.n, (e["n"] as? NSNumber)?.intValue, id)
            XCTAssertEqual(r.planeZone, (e["planeZone"] as? NSNumber)?.intValue, id)
            XCTAssertEqual(r.south, e["south"] as? Bool, id)
            func num(_ k: String, _ got: Double?, _ t: Double) {
                if let w = e[k] as? NSNumber {
                    // T1: ratios are relative (1e-4 of the value), a 0.002 eigen
                    // ratio used to pass anything within 0.0001 absolute
                    let isRatio = k == "eigenRatio" || k == "anisotropy" || k == "scaleDenominator"
                    let acc = isRatio ? max(t * abs(w.doubleValue), 1e-12) : t
                    XCTAssertEqual(got ?? .nan, w.doubleValue, accuracy: acc, "\(id) \(k)")
                } else {
                    XCTAssertNil(got, "\(id) \(k)")
                }
            }
            num("eigenRatio", r.eigenRatio, ratio)
            num("diagonalM", r.diagonalM, m)
            num("toleranceM", r.toleranceM, m)
            num("rmsM", r.rmsM, m)
            num("maxM", r.maxM, m)
            num("anisotropy", r.anisotropy, ratio)
            num("scaleDenominator", r.scaleDenominator, ratio)
            if let wa = e["affine"] as? [NSNumber] {
                let got = try XCTUnwrap(r.affine, id).coefficients
                for (g, w) in zip(got, wa) {
                    XCTAssertEqual(g, w.doubleValue, accuracy: max(1, abs(w.doubleValue)) * WP4Fixtures.dbl(tol["affineRelative"]) + 1e-9, "\(id) affine")
                }
            } else {
                XCTAssertNil(r.affine, id)
            }
            if let want = e["residualsM"] as? [String: NSNumber] {
                // T1: the same points, not just matching values for the ones listed
                XCTAssertEqual(Set(r.residualsM.keys), Set(want.keys.compactMap { Int($0) }), "\(id) residual keys")
                for (k, w) in want {
                    XCTAssertEqual(r.residualsM[Int(k)!] ?? .nan, w.doubleValue, accuracy: m, "\(id) residual \(k)")
                }
            }
            if e["residualsM"] is NSNull { XCTAssertTrue(r.residualsM.isEmpty, id) }
            for (key, got) in [("looRmsM", r.looRmsM), ("looM", r.looM)] {
                let want = e[key] as? [String: Any] ?? [:]
                XCTAssertEqual(got.count, want.count, "\(id) \(key)")
                for (k, w) in want {
                    let g = got[Int(k)!] ?? nil
                    if let w = w as? NSNumber { XCTAssertEqual(g ?? .nan, w.doubleValue, accuracy: m, "\(id) \(key) \(k)") } else { XCTAssertNil(g, "\(id) \(key) \(k)") }
                }
            }
            XCTAssertEqual(r.grade?.rawValue, e["grade"] as? String, id)
            XCTAssertEqual(r.exact, e["exact"] as? Bool, id)
            if let issue = e["issue"] as? [String: Any] {
                switch (r.issue, issue["type"] as? String) {
                case let (.outlier(n, d)?, "outlier"?):
                    XCTAssertEqual(n, (issue["number"] as? NSNumber)?.intValue, id)
                    XCTAssertEqual(d, WP4Fixtures.dbl(issue["distanceM"]), accuracy: m, id)
                case let (.ambiguous(a, b)?, "ambiguous"?):
                    XCTAssertEqual(a, (issue["first"] as? NSNumber)?.intValue, id)
                    XCTAssertEqual(b, (issue["second"] as? NSNumber)?.intValue, id)
                case let (.disagree(d)?, "disagree"?), let (.disagreeAddFifth(d)?, "disagreeAddFifth"?):
                    XCTAssertEqual(d, WP4Fixtures.dbl(issue["maxResidualM"]), accuracy: m, id)
                default:
                    XCTFail("\(id): issue \(String(describing: r.issue)) vs \(issue)")
                }
            } else {
                XCTAssertNil(r.issue, id)
            }
            XCTAssertEqual(r.flagged, (e["flagged"] as? [NSNumber] ?? []).map(\.intValue), id)
            if let rows = e["rowStatus"] as? [String: String] {
                XCTAssertEqual(r.rowStatus?.count, rows.count, id)
                for (k, v) in rows { XCTAssertEqual(r.rowStatus?[Int(k)!]?.rawValue, v, "\(id) row \(k)") }
            } else {
                XCTAssertNil(r.rowStatus, id)
            }
            XCTAssertEqual(r.spreadLow, e["spreadLow"] as? Bool, id)
            XCTAssertEqual(r.nextCorner?.rawValue, e["nextCorner"] as? String, id)
            if e["nextCornerPage"] is [Any] {
                let w = WP4Fixtures.page(e["nextCornerPage"])
                XCTAssertEqual(r.nextCornerPage?.x ?? .nan, w.x, accuracy: 1e-6, id)
                XCTAssertEqual(r.nextCornerPage?.y ?? .nan, w.y, accuracy: 1e-6, id)
            }
            let finish = e["finish"] as? [String: Any] ?? [:]
            switch (r.finishability, finish["state"] as? String) {
            case (.ready, "ready"?): break
            case (.confirm(let reasons), "confirm"?):
                XCTAssertEqual(reasons.map(\.rawValue), finish["reasons"] as? [String], id)
            case (.blocked(let b), "blocked"?):
                XCTAssertEqual(b.code, finish["reason"] as? String, id)
                if case .needMore(let k) = b { XCTAssertEqual(k, (finish["needMore"] as? NSNumber)?.intValue, id) }
            default:
                XCTFail("\(id): finish \(r.finishability) vs \(finish)")
            }
            WP4Fixtures.assertMessage(r.primaryStatus, e["primaryStatus"], tol: m, "\(id) primary")
            // B2: the secondary line before the camera has a say, next corner from n = 0
            WP4Fixtures.assertMessage(r.secondaryHint, e["secondaryHint"], "\(id) secondary")
            XCTAssertTrue(e.keys.contains("secondaryHint"), "\(id) fixture has no secondaryHint")
            let confirms = e["confirmMessages"] as? [Any] ?? []
            XCTAssertEqual(r.confirmMessages.count, confirms.count, id)
            for (g, w) in zip(r.confirmMessages, confirms) { WP4Fixtures.assertMessage(g, w, tol: m, "\(id) confirm") }
            // holdOut / checks are extra toWGS84 checks on the fit
            for h in (c["holdOut"] as? [[String: Any]] ?? []) + (c["checks"] as? [[String: Any]] ?? []) {
                guard let g = r.georef, let fit = (h["fitWGS84"] ?? h["wgs84"]) as? [String: Any] else { continue }
                let p = WP4Fixtures.page(h["page"])
                let w = try XCTUnwrap(g.toWGS84(x: p.x, y: p.y), id)
                XCTAssertEqual(w.latitude, WP4Fixtures.dbl(fit["lat"]), accuracy: 1e-9, "\(id) holdout")
                XCTAssertEqual(w.longitude, WP4Fixtures.dbl(fit["lon"]), accuracy: 1e-9, "\(id) holdout")
                // T1: and how far that is from the truth, within the case's own bound
                if let truth = h["truthWGS84"] as? [String: Any] {
                    let d = CLLocation(latitude: w.latitude, longitude: w.longitude)
                        .distance(from: CLLocation(latitude: WP4Fixtures.dbl(truth["lat"]), longitude: WP4Fixtures.dbl(truth["lon"])))
                    // CLLocation isn't a geodesic to the mm, a percent is plenty here
                    if let errM = h["errorM"] as? NSNumber {
                        XCTAssertEqual(d, errM.doubleValue, accuracy: max(0.05, 0.01 * d), "\(id) truth error")
                    }
                    if let dM = h["distanceM"] as? NSNumber {
                        XCTAssertEqual(d, dM.doubleValue, accuracy: max(0.05, 0.01 * d), "\(id) truth distance")
                    }
                    let bound = (h["maxM"] as? NSNumber ?? h["toleranceM"] as? NSNumber)?.doubleValue ?? .infinity
                    XCTAssertLessThan(d, bound, "\(id) truth bound")
                }
            }
        }
        XCTAssertEqual(ran, cases.count)
    }

    func testEntryChecks() throws {
        let checks = fx["entryChecks"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(checks.count, 9)
        let m = WP4Fixtures.dbl((fx["tolerance"] as? [String: Any])?["metres"])
        for c in checks {
            let id = c["id"] as? String ?? "?"
            let from = try XCTUnwrap(caseByID(c["fromCase"] as? String ?? ""), id)
            let s = try state(from)
            let pb = WP4Fixtures.pageBox(from["pageBox"])
            let rot = (from["rotate"] as? NSNumber)?.intValue ?? 0
            let report = CalibrationFitEvaluator.evaluate(s, pageBox: pb, rotate: rot)
            let pend = c["pending"] as? [String: Any] ?? [:]
            var pending = CalibrationPoint(number: 0, page: WP4Fixtures.page(pend["page"]), input: pend["input"] as? String ?? "",
                                           reference: try WP4Fixtures.reference(pend["reference"]),
                                           kind: CalibrationPointKind(rawValue: pend["kind"] as? String ?? "") ?? .intersection)
            var editing: UUID?
            if let n = (c["editing"] as? NSNumber)?.intValue {
                editing = s.points.first { $0.number == n }?.id
                pending.number = n
            }
            var base: PdfGeoreference?
            if let b = c["base"] as? [String: Any] { base = try WP4Fixtures.georef(b["georef"]) }
            let got = CalibrationFitEvaluator.entryCheck(state: s, report: report, pageBox: pb, rotate: rot,
                                                         pending: pending, editing: editing, base: base)
            let e = c["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(got.predictor.rawValue, e["predictor"] as? String, id)
            XCTAssertEqual(got.warn, e["warn"] as? Bool, id)
            // T1: a null in the fixture means no value at all, not "don't check"
            func opt(_ k: String, _ got: Double?, _ acc: Double) {
                if let w = e[k] as? NSNumber { XCTAssertEqual(got ?? .nan, w.doubleValue, accuracy: acc, "\(id) \(k)") }
                else { XCTAssertNil(got, "\(id) \(k)") }
            }
            opt("leverage", got.leverage, 1e-6 * max(1, WP4Fixtures.dbl(e["leverage"]).isNaN ? 1 : WP4Fixtures.dbl(e["leverage"])))
            opt("toleranceM", got.toleranceM, m)
            opt("distanceM", got.distanceM, m)
            opt("thresholdM", got.thresholdM, m)
            if e["predictedWGS84"] is NSNull { XCTAssertNil(got.predictedWGS84, "\(id) predictedWGS84") }
            let tw = e["typedWGS84"] as? [String: Any] ?? [:]
            XCTAssertEqual(got.typedWGS84?.latitude ?? .nan, WP4Fixtures.dbl(tw["lat"]), accuracy: 1e-9, id)
            XCTAssertEqual(got.typedWGS84?.longitude ?? .nan, WP4Fixtures.dbl(tw["lon"]), accuracy: 1e-9, id)
            if let pw = e["predictedWGS84"] as? [String: Any] {
                XCTAssertEqual(got.predictedWGS84?.latitude ?? .nan, WP4Fixtures.dbl(pw["lat"]), accuracy: 1e-9, id)
                XCTAssertEqual(got.predictedWGS84?.longitude ?? .nan, WP4Fixtures.dbl(pw["lon"]), accuracy: 1e-9, id)
            }
            WP4Fixtures.assertMessage(got.message, e["message"], tol: m, "\(id) message")
        }
    }

    func testCameraAnchor() throws {
        let rows = fx["cameraAnchor"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 4)
        for c in rows {
            let id = c["id"] as? String ?? "?"
            var old = try WP4Fixtures.georef(c["old"])
            let new = try WP4Fixtures.georef(c["new"])
            if let p = c["provisionalInputs"] as? [String: Any] {
                // rebuild old through the production provisional placement too
                let pb = WP4Fixtures.pageBox(p["pageBox"])
                let centre = p["centre"] as? [String: Any] ?? [:]
                let rebuilt = try XCTUnwrap(PdfGeoreference.provisional(
                    pageBox: CGRect(x: pb[0].x, y: pb[0].y, width: pb[1].x - pb[0].x, height: pb[2].y - pb[0].y),
                    rotation: (p["rotate"] as? NSNumber)?.intValue ?? 0,
                    centredOn: CLLocationCoordinate2D(latitude: WP4Fixtures.dbl(centre["lat"]), longitude: WP4Fixtures.dbl(centre["lon"]))), id)
                for (g, w) in zip(rebuilt.affine.coefficients, old.affine.coefficients) {
                    XCTAssertEqual(g, w, accuracy: max(abs(w), 1e-3) * 1e-12, "\(id) provisional affine")
                }
                old = rebuilt
            }
            let cam = c["camera"] as? [String: Any] ?? [:]
            let vp = (cam["viewport"] as? [NSNumber] ?? [390, 844]).map(\.doubleValue)
            let camera = MapCamera(center: CLLocationCoordinate2D(latitude: WP4Fixtures.dbl(cam["lat"]), longitude: WP4Fixtures.dbl(cam["lon"])),
                                   zoom: WP4Fixtures.dbl(cam["zoom"]), headingDegrees: WP4Fixtures.dbl(cam["heading"]),
                                   viewportSize: CGSize(width: vp[0], height: vp[1]))
            let out = try XCTUnwrap(CalibrationCameraAnchor.adjust(camera: camera, from: old, to: new), id)
            let e = c["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(out.center.latitude, WP4Fixtures.dbl(e["lat"]), accuracy: 1e-9, id)
            XCTAssertEqual(out.center.longitude, WP4Fixtures.dbl(e["lon"]), accuracy: 1e-9, id)
            XCTAssertEqual(out.zoom, WP4Fixtures.dbl(e["zoom"]), accuracy: 1e-6, id)
            XCTAssertEqual(out.headingDegrees, WP4Fixtures.dbl(e["heading"]), id)
            // T1: the zoom before the clamp too, so the clamp case proves it clamped
            XCTAssertEqual(try XCTUnwrap(CalibrationCameraAnchor.unclampedZoom(camera: camera, from: old, to: new), id),
                           WP4Fixtures.dbl(e["unclampedZoom"]), accuracy: 1e-6, "\(id) unclamped")
            let want = WP4Fixtures.page(e["pagePoint"])
            let p0 = try XCTUnwrap(old.toPage(lat: camera.center.latitude, lon: camera.center.longitude), id)
            XCTAssertEqual(p0.x, want.x, accuracy: 1e-6, id)
            XCTAssertEqual(p0.y, want.y, accuracy: 1e-6, id)
            // the whole point: same page point under the crosshair afterwards
            let p1 = try XCTUnwrap(new.toPage(lat: out.center.latitude, lon: out.center.longitude), id)
            XCTAssertEqual(p1.x, p0.x, accuracy: 1e-6, id)
            XCTAssertEqual(p1.y, p0.y, accuracy: 1e-6, id)
        }
    }

    func testCapture() throws {
        let rows = fx["capture"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 4)
        for c in rows {
            let id = c["id"] as? String ?? "?"
            let g = try WP4Fixtures.georef(c["georef"])
            let cam = c["camera"] as? [String: Any] ?? [:]
            let camera = MapCamera(center: CLLocationCoordinate2D(latitude: WP4Fixtures.dbl(cam["lat"]), longitude: WP4Fixtures.dbl(cam["lon"])),
                                   zoom: WP4Fixtures.dbl(cam["zoom"]), headingDegrees: 0, viewportSize: CGSize(width: 390, height: 844))
            let got = try XCTUnwrap(CalibrationCapture.capture(georef: g, pageBox: WP4Fixtures.pageBox(c["pageBox"]),
                                                               camera: camera, generation: 1), id)
            let e = c["expect"] as? [String: Any] ?? [:]
            let p = WP4Fixtures.page(e["page"])
            XCTAssertEqual(got.page.x, p.x, accuracy: 1e-6, id)
            XCTAssertEqual(got.page.y, p.y, accuracy: 1e-6, id)
            XCTAssertEqual(got.onSheet, e["onSheet"] as? Bool, id)
            let spp = WP4Fixtures.dbl(e["screenPtPerPagePt"])
            XCTAssertEqual(got.screenPtPerPagePt ?? .nan, spp, accuracy: 1e-4 * spp, id)
            XCTAssertEqual(got.zoomHint, e["zoomHint"] as? Bool, id)
            // T1: the status production puts up, not one worked out in here
            WP4Fixtures.assertMessage(got.status, e["status"], "\(id) status")
            // B2: off the sheet there's never a zoom hint on the secondary line
            let secondary = CalibrationCapture.secondaryLine(report: CalibrationFitReport(), onSheet: got.onSheet, zoomHint: got.zoomHint)
            XCTAssertEqual(secondary?.key == "calibration_zoom_hint", got.onSheet && got.zoomHint, "\(id) secondary")
        }
    }

    /// r1 OD-F12: {rms} one decimal under 9.95 m, DisplayFormat.distance above
    func testRmsDisplay() {
        let rd = fx["rmsDisplay"] as? [String: Any] ?? [:]
        let rows = rd["cases"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 15)
        let en = Locale(identifier: "en_US"), de = Locale(identifier: "de_DE")
        for r in rows {
            let m = WP4Fixtures.dbl(r["rmsM"])
            XCTAssertEqual(DisplayFormat.rms(m, locale: en), r["en"] as? String, "\(m)")
            XCTAssertEqual(DisplayFormat.rms(m, locale: de), r["de"] as? String, "\(m) de")
        }
        // and it's what the four {rms} messages actually use
        let before = AppLanguage.shared.selection
        defer { AppLanguage.shared.select(before) }
        AppLanguage.shared.select(.en)
        let summary = CalibrationMessage(key: "calibration_fit_summary",
                                         args: ["points": .number(4), "rms": .metres(0.37), "grade": .key("calibration_grade_good")])
        XCTAssertTrue(summary.text.contains("RMS 0.4 m"), summary.text)
        let row = CalibrationMessage(key: "map_state_calibrated", args: ["points": .number(4), "rms": .metres(25.7)])
        XCTAssertTrue(row.text.contains("RMS 26 m"), row.text)
        let poor = CalibrationMessage(key: "calibration_finish_confirm_poor", args: ["rms": .metres(8.623429)])
        XCTAssertTrue(poor.text.contains("8.6 m"), poor.text)
    }

    func testFitReportMessageKeysTable() {
        let keys = fx["messageKeys"] as? [String: Any] ?? [:]
        let blocked = keys["blocked"] as? [String: String] ?? [:]
        for b in [CalibrationBlockReason.degenerate, .invalid, .implausible] {
            XCTAssertEqual("calibration_" + b.code, blocked[b.code])
        }
        let corners = keys["nextCorner"] as? [String: String] ?? [:]
        for c in PageCorner.allCases { XCTAssertEqual(c.messageKey, corners[c.rawValue]) }
        let grades = keys["grade"] as? [String: String] ?? [:]
        for g in [CalibrationGrade.good, .fair, .poor] { XCTAssertEqual(g.messageKey, grades[g.rawValue]) }
    }
}

final class ImportLimitsContractTests: XCTestCase {

    private lazy var fx = WP4Fixtures.load("import_limits.json")

    func testCalibrationConstants() {
        let c = fx["calibration"] as? [String: Any] ?? [:]
        typealias L = CalibrationLimits
        let mine: [String: Double] = [
            "maxCalibrationPoints": Double(L.maxCalibrationPoints), "undoDepth": Double(L.undoDepth),
            "maxDrafts": Double(L.maxDrafts), "gpsMaxAccuracyM": L.gpsMaxAccuracyM,
            "degenerateEigenRatio": L.degenerateEigenRatio, "spreadMinFraction": L.spreadMinFraction,
            "toleranceFloorM": L.toleranceFloorM, "toleranceDiagonalFraction": L.toleranceDiagonalFraction,
            "gradeFairToleranceMultiple": L.gradeFairToleranceMultiple, "implausibleAnisotropy": L.implausibleAnisotropy,
            "plausibleScaleMin": L.plausibleScaleMin, "plausibleScaleMax": L.plausibleScaleMax,
            "offEarthLatDeg": L.offEarthLatDeg, "outlierMinPoints": Double(L.outlierMinPoints),
            "entryWarnSigma": L.entryWarnSigma, "entryWarnFloorM": L.entryWarnFloorM,
            "nextCornerInsetFraction": L.nextCornerInsetFraction, "residualLineMinScreenPt": L.residualLineMinScreenPt,
            "moveToCrosshairMinScreenPt": L.moveToCrosshairMinScreenPt, "markerHitRadiusPt": L.markerHitRadiusPt,
            "zoomHintScreenPtPerPagePt": L.zoomHintScreenPtPerPagePt, "cameraZoomMin": L.cameraZoomMin,
            "cameraZoomMax": L.cameraZoomMax, "cameraJacobianStepPt": L.cameraJacobianStepPt,
            "transitionPrefetchDeadlineMs": Double(L.transitionPrefetchDeadlineMs),
            "metresPerPagePointAtUnitScale": L.metresPerPagePointAtUnitScale,
            "provisionalScaleDenominator": L.provisionalScaleDenominator,
            "maxInputUtf16Units": Double(L.maxInputUTF16Units),
        ]
        XCTAssertEqual(Set(mine.keys), Set(c.keys))
        for (k, v) in mine { XCTAssertEqual(v, WP4Fixtures.dbl(c[k]), accuracy: abs(v) * 1e-12, k) }
        XCTAssertEqual(PdfGeoreference.provisionalMetresPerPoint,
                       L.provisionalScaleDenominator * L.metresPerPagePointAtUnitScale, accuracy: 1e-12)
        XCTAssertEqual(FiduciaryFitter.degenerateEigenRatio, L.degenerateEigenRatio)
    }

    func testImportConstants() {
        let c = fx["import"] as? [String: Any] ?? [:]
        let mine: [String: Double] = [
            "pdfMaxBytes": Double(ImportLimits.pdfMaxBytes), "mbtilesMaxBytes": Double(ImportLimits.mbtilesMaxBytes),
            "maxPages": Double(ImportLimits.maxPages), "georefScanPages": Double(ImportLimits.georefScanPages),
            "pageSideMinPt": ImportLimits.pageSideMinPt, "pageSideMaxPt": ImportLimits.pageSideMaxPt,
            "parseTimeoutMs": Double(ImportLimits.parseTimeoutMs), "freeSpaceMarginBytes": Double(ImportLimits.freeSpaceMarginBytes),
            "maxLibraryEntries": Double(ImportLimits.maxLibraryEntries), "progressHudDelayMs": Double(ImportLimits.progressHudDelayMs),
            "thumbnailWidthPt": ImportLimits.thumbnailWidthPt, "thumbnailCacheEntries": Double(ImportLimits.thumbnailCacheEntries),
            "cancelCopyGranularityBytes": Double(ImportLimits.cancelCopyGranularityBytes),
        ]
        XCTAssertEqual(Set(mine.keys), Set(c.keys))
        for (k, v) in mine { XCTAssertEqual(v, WP4Fixtures.dbl(c[k]), k) }
    }

    func testErrorsMapToMessageKeys() {
        let errors = fx["errors"] as? [String: [String: Any]] ?? [:]
        let all: [MapImportError] = [.tooLarge(limit: 1), .noSpace(size: 1), .password, .tooManyPages(limit: 1), .pageSize,
                                     .tooComplex, .invalidPdf, .invalidMbtiles, .libraryFull(limit: 1), .locked,
                                     .interrupted, .cancelled, .failed(detail: "x")]
        XCTAssertEqual(Set(all.map(\.code)), Set(errors.keys))
        for e in all {
            XCTAssertEqual(e.messageKey, errors[e.code]?["key"] as? String, e.code)
            XCTAssertFalse(e.text.isEmpty, e.code)
        }
    }

    /// r1 OD-F6: byte args use the WP2 bakeFormat rule
    func testSizeDisplay() {
        let sd = fx["sizeDisplay"] as? [String: Any] ?? [:]
        let rows = sd["cases"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 5)
        for r in rows {
            let b = (r["bytes"] as? NSNumber)?.int64Value ?? -1
            XCTAssertEqual(MapImportError.bytes(b, PDFBakeFormat.en), r["en"] as? String, "\(b)")
            XCTAssertEqual(MapImportError.bytes(b, PDFBakeFormat.de), r["de"] as? String, "\(b) de")
        }
    }

    func testPrechecks() {
        let rows = fx["prechecks"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 12)
        for r in rows {
            let id = r["id"] as? String ?? "?"
            let lib: ImportPrecheck.LibraryStatus = ["loaded": .loaded, "locked": .locked][r["library"] as? String ?? ""] ?? .corrupt
            let got = ImportPrecheck.check(library: lib, entryCount: (r["entryCount"] as? NSNumber)?.intValue ?? 0,
                                           kind: r["kind"] as? String == "pdf" ? .pdf : .mbtiles,
                                           sizeBytes: (r["sizeBytes"] as? NSNumber)?.int64Value ?? 0,
                                           freeBytes: (r["freeBytes"] as? NSNumber)?.int64Value ?? 0)
            guard let e = r["expect"] as? [String: Any] else { XCTAssertNil(got, id); continue }
            XCTAssertEqual(got?.code, e["error"] as? String, id)
            XCTAssertEqual(got?.messageKey, e["messageKey"] as? String, id)
            let args = e["args"] as? [String: NSNumber] ?? [:]
            switch got {
            case .tooLarge(let l)?: XCTAssertEqual(l, args["limit"]?.int64Value, id)
            case .noSpace(let s)?: XCTAssertEqual(s, args["size"]?.int64Value, id)
            case .libraryFull(let l)?: XCTAssertEqual(l, args["limit"]?.intValue, id)
            default: XCTAssertTrue(args.isEmpty, id)
            }
            // OD-F6: what the user actually reads for the byte args
            if let text = e["argText"] as? [String: [String: String]] {
                for (lang, sep) in [("en", PDFBakeFormat.en), ("de", PDFBakeFormat.de)] {
                    for (k, want) in text[lang] ?? [:] {
                        XCTAssertEqual(MapImportError.bytes(args[k]?.int64Value ?? -1, sep), want, "\(id) \(lang) \(k)")
                    }
                }
            }
        }
    }

    func testInspections() {
        let rows = fx["inspections"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 10)
        func rect(_ a: Any?) -> CGRect? {
            guard let v = (a as? [NSNumber])?.map(\.doubleValue), v.count == 4 else { return nil }
            return CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1])
        }
        for r in rows {
            let id = r["id"] as? String ?? "?"
            var pages: [PDFInspectionRules.PageBoxes] = []
            if let list = r["pages"] as? [[String: Any]] {
                pages = list.map { .init(mediaBox: rect($0["mediaBox"]) ?? .zero, cropBox: rect($0["cropBox"])) }
            } else if let all = r["pages"] as? [String: Any], let p = all["allPages"] as? [String: Any] {
                let one = PDFInspectionRules.PageBoxes(mediaBox: rect(p["mediaBox"]) ?? .zero, cropBox: rect(p["cropBox"]))
                pages = Array(repeating: one, count: (all["count"] as? NSNumber)?.intValue ?? 0)
            }
            let got = PDFInspectionRules.check(openable: r["openable"] as? Bool ?? false, encrypted: r["encrypted"] as? Bool ?? false,
                                               emptyPasswordOpens: r["emptyPasswordOpens"] as? Bool ?? false,
                                               pageCount: (r["pageCount"] as? NSNumber)?.intValue ?? 0, pages: pages)
            guard let e = r["expect"] as? [String: Any] else { XCTAssertNil(got, id); continue }
            XCTAssertEqual(got?.code, e["error"] as? String, id)
            XCTAssertEqual(got?.messageKey, e["messageKey"] as? String, id)
        }
    }

    func testImportDecisions() {
        let rows = fx["decisions"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, 15)
        for r in rows {
            let id = r["id"] as? String ?? "?"
            let pages: [ImportDecision.PageState] = (r["pages"] as? [[String: Any]] ?? []).map { p in
                switch p["state"] as? String {
                case "valid": return .valid
                case "rejected": return .rejected(reason: p["reason"] as? String ?? "")
                default: return .none
                }
            }
            var dup: ImportDecision.Duplicate?
            if let d = r["duplicate"] as? [String: Any] {
                dup = .init(existingHasGeoref: d["existingHasGeoref"] as? Bool ?? false, name: d["name"] as? String ?? "",
                            existingUnavailable: d["existingUnavailable"] as? Bool ?? false)
            }
            let got = ImportDecision.decide(pageCount: (r["pageCount"] as? NSNumber)?.intValue ?? 0, pages: pages, duplicate: dup)
            let o = r["outcome"] as? [String: Any] ?? [:]
            XCTAssertEqual(got.action.rawValue, o["action"] as? String, id)
            XCTAssertEqual(got.relink, o["relink"] as? Bool ?? false, "\(id) relink")
            XCTAssertEqual(got.page, (o["page"] as? NSNumber)?.intValue, id)
            WP4Fixtures.assertMessage(got.toast, o["toast"], "\(id) toast")
            if let a = o["alert"] as? [String: Any] {
                WP4Fixtures.assertMessage(got.alert?.message, a["message"], "\(id) alert")
                XCTAssertEqual(got.alert?.buttons, a["buttons"] as? [String], id)
            } else {
                XCTAssertNil(got.alert, id)
            }
            XCTAssertEqual(got.pickerBadges, (o["pickerBadges"] as? [NSNumber])?.map(\.intValue), id)
        }
    }

    func testEntryStates() throws {
        let rows = fx["entryStates"] as? [[String: Any]] ?? []
        // 3.0.3 U1: every row says packRefused, 5 new rows pin openFailed
        XCTAssertEqual(rows.count, 18)
        let dummyGeoref = PdfGeoreference(crs: .utm(zone: 32, south: false), datum: .wgs84,
                                          affine: PlaneAffine(a: 1, b: 0, c: 500_000, d: 0, e: 1, f: 5_000_000),
                                          crop: CalibrationTarget.box(CGRect(x: 0, y: 0, width: 100, height: 100)), origin: .adobeVP)
        for r in rows {
            let id = r["id"] as? String ?? "?"
            let j = r["entry"] as? [String: Any] ?? [:]
            var entry = ImportedMapEntry(id: UUID(), kind: j["kind"] as? String == "mbtiles" ? .mbtiles : .pdf,
                                         fileName: "ImportedMaps/map-x", displayName: "x", contentKey: nil, byteCount: 0,
                                         fileModifiedAtMs: 0, importedAtMs: 0,
                                         derivedFromId: (j["derivedFromId"] as? String).flatMap(UUID.init(uuidString:)), pdf: nil)
            if let p = j["pdf"] as? [String: Any] {
                var info = ImportedMapEntry.PDFInfo(pageCount: (p["pageCount"] as? NSNumber)?.intValue ?? 1, pageIndex: 0, rotate: 0,
                                                    pageBox: dummyGeoref.crop, embedded: nil, embeddedIssue: p["embeddedIssue"] as? String)
                if p["embedded"] as? Bool == true { info.embedded = dummyGeoref }
                if let m = p["manual"] as? [String: Any] {
                    info.manual = ManualCalibration(datumId: "WGS84", points: [], nextNumber: nil, georef: dummyGeoref,
                                                    n: (m["n"] as? NSNumber)?.intValue ?? 0, rmsM: (m["rmsM"] as? NSNumber)?.doubleValue,
                                                    grade: nil, savedAtMs: 0)
                }
                entry.pdf = info
            }
            let file: ImportedMapFileStatus = ["ok": .ok, "missing": .missing][r["fileStatus"] as? String ?? ""] ?? .sizeOrMtimeMismatch
            let refused = try XCTUnwrap(r["packRefused"] as? Bool, "\(id) packRefused")
            let got = ImportedMapStates.present(entry, file: file, draftPoints: (r["draftPoints"] as? NSNumber)?.intValue,
                                                parentName: j["parentName"] as? String, packRefused: refused)
            XCTAssertEqual(ImportedMapStates.state(entry, file: file, packRefused: refused), got.state, id)
            let e = r["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(got.state.rawValue, e["state"] as? String, id)
            XCTAssertEqual(got.effectiveGeoref, e["effectiveGeoref"] as? String, id)
            XCTAssertEqual(got.canBeDurableActive, e["canBeDurableActive"] as? Bool, id)
            WP4Fixtures.assertMessage(got.subtitle, e["subtitle"], "\(id) subtitle")
            XCTAssertEqual(got.rowTap.rawValue, e["rowTap"] as? String, id)
            XCTAssertEqual(got.menu.map(\.rawValue), e["menu"] as? [String], id)
        }
    }
}
