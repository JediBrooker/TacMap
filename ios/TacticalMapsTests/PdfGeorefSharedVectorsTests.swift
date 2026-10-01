import XCTest
import Foundation
import CoreGraphics
import CoreLocation
import CryptoKit
@testable import TacticalMaps

/// testdata/pdf_georef.json + testdata/geopdf/*.pdf, the same files the
/// Android suite reads. Every section of the fixture gets asserted here:
/// datums, projections, gcs, sheets (real PDFs through the real parser),
/// rejections (structured + as PDFs), fiduciaryFits and tileWarp.
final class PdfGeorefSharedVectorsTests: XCTestCase {

    // MARK: - fixture plumbing

    private static func testdataURL(_ name: String) -> URL? {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }

    private static func repoFile(_ relative: String) -> URL? {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent(relative)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }

    private lazy var fx: [String: Any] = {
        guard let url = Self.testdataURL("pdf_georef.json"),
              let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            XCTFail("could not load testdata/pdf_georef.json")
            return [:]
        }
        return obj
    }()

    private func section(_ key: String) -> [String: Any] { fx[key] as? [String: Any] ?? [:] }
    private func dbl(_ a: Any?) -> Double {
        if let n = a as? NSNumber { return n.doubleValue }
        if let s = a as? String, let v = Double(s) { return v }
        return .nan
    }
    private func dbls(_ a: Any?) -> [Double] { (a as? [Any])?.map(dbl) ?? [] }
    private func pts(_ a: Any?) -> [PdfPagePoint] {
        ((a as? [[Any]]) ?? []).map { PdfPagePoint(x: dbl($0[0]), y: dbl($0[1])) }
    }

    /// metres between two lat/lon (small distances, local radii are plenty)
    private func metres(_ a: CLLocationCoordinate2D, _ lat: Double, _ lon: Double) -> Double {
        let m = PdfGeoreference.metresPerUnit(crs: .geographic, ellipsoid: .wgs84, latitude: lat)
        return hypot((a.longitude - lon) * m.east, (a.latitude - lat) * m.north)
    }

    private func crs(_ o: Any?) -> GeoCrs? {
        guard let o = o as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: o) else { return nil }
        return try? JSONDecoder().decode(GeoCrs.self, from: data)
    }

    private func datum(_ o: Any?) -> GeoDatum? {
        guard let o = o as? [String: Any], let data = try? JSONSerialization.data(withJSONObject: o) else { return nil }
        return try? JSONDecoder().decode(GeoDatum.self, from: data)
    }

    private func assertCrs(_ got: GeoCrs?, _ expected: Any?, _ ctx: String, file: StaticString = #filePath, line: UInt = #line) {
        guard let e = expected as? [String: Any], let want = crs(e) else {
            return XCTFail("\(ctx): bad expected crs", file: file, line: line)
        }
        guard let got else { return XCTFail("\(ctx): no crs", file: file, line: line) }
        let gotParams = crsParams(got), wantParams = crsParams(want)
        XCTAssertEqual(gotParams.kind, wantParams.kind, ctx, file: file, line: line)
        XCTAssertEqual(gotParams.values.count, wantParams.values.count, ctx, file: file, line: line)
        for (g, w) in zip(gotParams.values, wantParams.values) {
            XCTAssertEqual(g, w, accuracy: 1e-9 * max(1, abs(w)), "\(ctx) crs param", file: file, line: line)
        }
        if let zone = e["utmZone"] {
            XCTAssertEqual(got.utmZone?.zone, Int(dbl(zone)), "\(ctx) utm zone", file: file, line: line)
            XCTAssertEqual(got.utmZone?.south, (e["hemisphere"] as? String) == "S", "\(ctx) hemisphere", file: file, line: line)
        }
    }

    private func crsParams(_ c: GeoCrs) -> (kind: String, values: [Double]) {
        switch c {
        case .geographic: return ("geographic", [])
        case let .transverseMercator(a, b, k, fe, fn): return ("tm", [a, b, k, fe, fn])
        case let .lambertConformalConic2SP(p1, p2, a, b, fe, fn): return ("lcc2", [p1, p2, a, b, fe, fn])
        case let .lambertConformalConic1SP(a, b, k, fe, fn): return ("lcc1", [a, b, k, fe, fn])
        case let .mercator1SP(b, k, fe, fn): return ("merc", [b, k, fe, fn])
        }
    }

    private func assertDatum(_ got: GeoDatum?, _ expected: Any?, _ ctx: String, file: StaticString = #filePath, line: UInt = #line) {
        guard let e = expected as? [String: Any] else { return XCTFail("\(ctx): bad expected datum", file: file, line: line) }
        guard let got else { return XCTFail("\(ctx): no datum", file: file, line: line) }
        XCTAssertEqual(got.id, e["id"] as? String, "\(ctx) datum id", file: file, line: line)
        if (e["id"] as? String) == "custom" {
            XCTAssertEqual(got.a, dbl(e["a"]), accuracy: 1e-9, ctx, file: file, line: line)
            XCTAssertEqual(got.invF, dbl(e["invF"]), accuracy: 1e-9, ctx, file: file, line: line)
            XCTAssertEqual(got.dx, dbl(e["dx"]), accuracy: 1e-9, ctx, file: file, line: line)
            XCTAssertEqual(got.dy, dbl(e["dy"]), accuracy: 1e-9, ctx, file: file, line: line)
            XCTAssertEqual(got.dz, dbl(e["dz"]), accuracy: 1e-9, ctx, file: file, line: line)
        }
    }

    private func assertAffine(_ got: PlaneAffine, _ expected: Any?, tol: Any?, _ ctx: String,
                              file: StaticString = #filePath, line: UInt = #line) {
        let e = dbls(expected), t = dbls(tol)
        guard e.count == 6, t.count == 6 else { return XCTFail("\(ctx): bad expected affine", file: file, line: line) }
        for (i, (g, w)) in zip(got.coefficients, e).enumerated() {
            XCTAssertEqual(g, w, accuracy: t[i], "\(ctx) affine[\(i)]", file: file, line: line)
        }
    }

    private func assertCrop(_ got: [PdfPagePoint], _ expected: Any?, _ ctx: String,
                            file: StaticString = #filePath, line: UInt = #line) {
        let e = pts(expected)
        XCTAssertEqual(got.count, e.count, "\(ctx) crop count", file: file, line: line)
        for (g, w) in zip(got, e) {
            XCTAssertEqual(g.x, w.x, accuracy: 1e-6, "\(ctx) crop", file: file, line: line)
            XCTAssertEqual(g.y, w.y, accuracy: 1e-6, "\(ctx) crop", file: file, line: line)
        }
    }

    private func assertFit(_ got: PdfGeorefFitStats?, _ expected: Any?, _ ctx: String,
                           file: StaticString = #filePath, line: UInt = #line) {
        guard let e = expected as? [String: Any] else { return }
        guard let got else { return XCTFail("\(ctx): no fit stats", file: file, line: line) }
        XCTAssertEqual(got.rmsMetres, dbl(e["rmsMetres"]), accuracy: 1e-3, "\(ctx) rms", file: file, line: line)
        XCTAssertEqual(got.maxResidualMetres, dbl(e["maxResidualMetres"]), accuracy: 1e-3, "\(ctx) max", file: file, line: line)
        let r = dbls(e["residualsMetres"])
        XCTAssertEqual(got.residualsMetres.count, r.count, ctx, file: file, line: line)
        for (g, w) in zip(got.residualsMetres, r) { XCTAssertEqual(g, w, accuracy: 1e-3, "\(ctx) residual", file: file, line: line) }
        XCTAssertEqual(got.sheetDiagonalMetres, dbl(e["sheetDiagonalMetres"]), accuracy: 1e-3, "\(ctx) diag", file: file, line: line)
        XCTAssertEqual(got.gateLimitMetres, dbl(e["gateLimitMetres"]), accuracy: 1e-3, "\(ctx) gate", file: file, line: line)
        XCTAssertEqual(got.passesGate, e["passesGate"] as? Bool, "\(ctx) passesGate", file: file, line: line)
    }

    /// page -> wgs84 (0.01 m), plane (1 mm), wgs84 -> page (1e-3 pt)
    private func assertChecks(_ g: PdfGeoreference, _ checks: Any?, _ ctx: String,
                              wgs84Metres: Double = 0.01, pagePoints: Double = 1e-3, planeMetres: Double = 1e-3,
                              file: StaticString = #filePath, line: UInt = #line) {
        let rows = checks as? [[String: Any]] ?? []
        XCTAssertFalse(rows.isEmpty, "\(ctx): no checks", file: file, line: line)
        for c in rows {
            let label = "\(ctx) / \(c["label"] as? String ?? "?")"
            let page = dbls(c["page"]), w = dbls(c["wgs84"]), tp = dbls(c["toPage"]), plane = dbls(c["plane"])
            guard let got = g.toWGS84(x: page[0], y: page[1]) else { XCTFail("\(label): toWGS84 nil", file: file, line: line); continue }
            XCTAssertLessThanOrEqual(metres(got, w[0], w[1]), wgs84Metres, "\(label) toWGS84", file: file, line: line)
            let pl = g.toPlane(x: page[0], y: page[1])
            let planeTol = g.crs.isGeographic ? 1e-8 : planeMetres
            XCTAssertEqual(pl.x, plane[0], accuracy: planeTol, "\(label) plane x", file: file, line: line)
            XCTAssertEqual(pl.y, plane[1], accuracy: planeTol, "\(label) plane y", file: file, line: line)
            guard let back = g.toPage(lat: w[0], lon: w[1]) else { XCTFail("\(label): toPage nil", file: file, line: line); continue }
            XCTAssertEqual(back.x, tp[0], accuracy: pagePoints, "\(label) toPage x", file: file, line: line)
            XCTAssertEqual(back.y, tp[1], accuracy: pagePoints, "\(label) toPage y", file: file, line: line)
        }
    }

    // MARK: - datums

    func testDatumTableMatchesSharedFixture() throws {
        let rows = section("datums")["table"] as? [[String: Any]] ?? []
        XCTAssertEqual(rows.count, GeoDatum.table.count)
        for (row, d) in zip(rows, GeoDatum.table) {
            let id = row["id"] as? String ?? "?"
            XCTAssertEqual(d.id, id, "table order")
            XCTAssertEqual(d.a, dbl(row["a"]), id)
            XCTAssertEqual(d.invF, dbl(row["invF"]), id)
            XCTAssertEqual(d.transform.rawValue, row["transform"] as? String, id)
            XCTAssertEqual(d.dx, dbl(row["dx"]), id)
            XCTAssertEqual(d.dy, dbl(row["dy"]), id)
            XCTAssertEqual(d.dz, dbl(row["dz"]), id)
            if d.transform == .helmert7 {
                XCTAssertEqual(row["convention"] as? String, "coordinateFrame", id)
                XCTAssertEqual(d.rxArcsec, dbl(row["rxArcsec"]), id)
                XCTAssertEqual(d.ryArcsec, dbl(row["ryArcsec"]), id)
                XCTAssertEqual(d.rzArcsec, dbl(row["rzArcsec"]), id)
                XCTAssertEqual(d.scalePpm, dbl(row["scalePpm"]), id)
            }
            let al = try XCTUnwrap(row["aliases"] as? [String: Any], id)
            let mine = try XCTUnwrap(GeoDatum.aliases(for: d.id), id)
            XCTAssertEqual(mine.wktDatumNames, al["wktDatumNames"] as? [String], id)
            XCTAssertEqual(mine.lgiCodes, al["lgiCodes"] as? [String], id)
            XCTAssertEqual(mine.lgiCodePrefixes, al["lgiCodePrefixes"] as? [String], id)
            XCTAssertEqual(mine.epsgDatum, (al["epsgDatum"] as? NSNumber)?.intValue, id)
            XCTAssertEqual(mine.epsgGeographic, (al["epsgGeographic"] as? [NSNumber])?.map(\.intValue), id)
            // every alias resolves back to this row
            for n in mine.wktDatumNames { XCTAssertEqual(GeoDatum.matchingWktName(n)?.id, d.id, "wkt \(n)") }
            for c in mine.lgiCodes { XCTAssertEqual(GeoDatum.matchingLgiCode(c)?.id, d.id, "lgi \(c)") }
            for c in mine.epsgGeographic { XCTAssertEqual(GeoDatum.matchingEpsgGeographic(c)?.id, d.id, "epsg \(c)") }
        }
        // prefix families fall back to their parent row, real LGIDict codes resolve (D1-06)
        XCTAssertEqual(GeoDatum.matchingLgiCode(" nas-x ")?.id, "NAD27")
        XCTAssertEqual(GeoDatum.matchingLgiCode("NAR-C")?.id, "NAD83")
        XCTAssertEqual(GeoDatum.matchingLgiCode("EUR-A")?.id, "ED50")
        XCTAssertEqual(GeoDatum.matchingLgiCode("WGE")?.id, "WGS84")
        XCTAssertNil(GeoDatum.matchingLgiCode("ZZZ"))
    }

    func testDatumShiftsMatchSharedFixture() throws {
        let d = section("datums")
        let tol = dbl((d["tolerance"] as? [String: Any])?["degrees"])
        let to = d["toWGS84"] as? [[String: Any]] ?? []
        let from = d["fromWGS84"] as? [[String: Any]] ?? []
        XCTAssertFalse(to.isEmpty)
        XCTAssertFalse(from.isEmpty)
        for c in to {
            let id = c["datum"] as? String ?? "?"
            let datum = try XCTUnwrap(GeoDatum.named(id), id)
            let w = try XCTUnwrap(datum.toWGS84(lat: dbl(c["lat"]), lon: dbl(c["lon"])), id)
            let e = dbls(c["wgs84"])
            XCTAssertEqual(w.lat, e[0], accuracy: tol, "\(id) toWGS84 lat")
            XCTAssertEqual(w.lon, e[1], accuracy: tol, "\(id) toWGS84 lon")
        }
        for c in from {
            let id = c["datum"] as? String ?? "?"
            let datum = try XCTUnwrap(GeoDatum.named(id), id)
            let wi = dbls(c["wgs84"]), e = dbls(c["source"])
            let s = try XCTUnwrap(datum.fromWGS84(lat: wi[0], lon: wi[1]), id)
            XCTAssertEqual(s.lat, e[0], accuracy: tol, "\(id) fromWGS84 lat")
            XCTAssertEqual(s.lon, e[1], accuracy: tol, "\(id) fromWGS84 lon")
        }
    }

    // MARK: - projections

    func testProjectionsForwardAndInverseMatchSharedFixture() throws {
        let p = section("projections")
        let tol = p["tolerance"] as? [String: Any] ?? [:]
        let planeTol = dbl(tol["planeMetres"]), degTol = dbl(tol["degrees"])
        let cases = p["cases"] as? [[String: Any]] ?? []
        XCTAssertGreaterThan(cases.count, 20)
        for c in cases {
            let name = c["name"] as? String ?? "?"
            let k = try XCTUnwrap(crs(c["crs"]), name)
            let ell = Ellipsoid(a: dbl(c["a"]), invF: dbl(c["invF"]))
            let f = try XCTUnwrap(k.forward(lat: dbl(c["lat"]), lon: dbl(c["lon"]), ellipsoid: ell), name)
            XCTAssertEqual(f.x, dbl(c["x"]), accuracy: planeTol, "\(name) x")
            XCTAssertEqual(f.y, dbl(c["y"]), accuracy: planeTol, "\(name) y")
            let i = try XCTUnwrap(k.inverse(x: dbl(c["x"]), y: dbl(c["y"]), ellipsoid: ell), name)
            XCTAssertEqual(i.lat, dbl(c["inverseLat"]), accuracy: degTol, "\(name) inverse lat")
            XCTAssertEqual(i.lon, dbl(c["inverseLon"]), accuracy: degTol, "\(name) inverse lon")
        }
    }

    // MARK: - gcs

    func testGcsParsingMatchesSharedFixture() throws {
        let cases = section("gcs")["cases"] as? [[String: Any]] ?? []
        XCTAssertGreaterThan(cases.count, 30)
        for c in cases {
            let id = c["id"] as? String ?? "?"
            let input = c["input"] as? [String: Any] ?? [:]
            let expected = c["expected"] as? [String: Any] ?? [:]
            var results: [(String, GcsParseResult)] = []
            if let wkt = input["wkt"] as? String { results.append(("wkt", GcsParser.parse(wkt: wkt))) }
            if let literal = input["wktPdfLiteral"] as? String {
                // the raw literal has to go through the real PDF string decoder
                let decoded = try XCTUnwrap(decodeWktLiteralThroughPDF(literal), id)
                XCTAssertEqual(decoded, input["wkt"] as? String, "\(id) literal decode")
                results.append(("literal", GcsParser.parse(wkt: decoded)))
            }
            if let epsg = input["epsg"] { results.append(("epsg", GcsParser.parse(epsg: Int(dbl(epsg))))) }
            XCTAssertFalse(results.isEmpty, id)
            for (how, r) in results {
                let ctx = "\(id) (\(how))"
                switch expected["status"] as? String {
                case "ok": XCTAssertEqual(r.status, .ok, ctx)
                case "fallbackLocalTM": XCTAssertEqual(r.status, .fallbackLocalTM, ctx)
                case "unknownDatum": XCTAssertEqual(r.status, .unknownDatum, ctx)
                case "malformed": XCTAssertEqual(r.status, .malformed, ctx)
                default: XCTFail("\(ctx): unexpected status in fixture")
                }
                if expected["crs"] != nil { assertCrs(r.crs, expected["crs"], ctx) }
                if expected["datum"] != nil { assertDatum(r.datum, expected["datum"], ctx) }
                if r.status != .unknownDatum && r.status != .malformed {
                    XCTAssertEqual(r.datumAssumed, expected["datumAssumed"] as? Bool ?? false, "\(ctx) datumAssumed")
                }
                if let reason = expected["reason"] as? String { XCTAssertEqual(r.reason?.rawValue, reason, ctx) }
                if let unit = expected["linearUnitMetres"] { XCTAssertEqual(r.linearUnitMetres, dbl(unit), ctx) }
            }
        }
    }

    func testGcsFallbackLocalTransverseMercatorExample() throws {
        let ex = try XCTUnwrap(section("gcs")["fallbackExample"] as? [String: Any])
        let input = try XCTUnwrap(ex["input"] as? [String: Any])
        let expected = try XCTUnwrap(ex["expected"] as? [String: Any])
        let gcs = try XCTUnwrap(input["gcs"] as? [String: Any])
        let vp = AdobeViewportInput(name: nil, bbox: .values(dbls(input["bbox"])), lpts: .values(dbls(input["lpts"])),
                                    gpts: .values(dbls(input["gpts"])),
                                    gcs: .described(wkt: gcs["wkt"] as? String, epsg: nil))
        guard case .georef(let g, _, let stats) = PdfGeorefBuilder.buildViewport(vp, index: 0) else {
            return XCTFail("fallback example should build")
        }
        assertCrs(g.crs, expected["crs"], "fallback")
        assertDatum(g.datum, expected["datum"], "fallback")
        XCTAssertTrue(g.datumAssumed == false, "WGS84 read off the WKT, not assumed")
        assertAffine(g.affine, expected["affine"], tol: expected["affineTol"], "fallback")
        assertFit(stats, expected["fit"], "fallback")
        assertChecks(g, expected["checks"], "fallback")
    }

    // MARK: - sheets (the real PDFs through the real parser)

    func testSheetsParseToTheSharedGeoreference() throws {
        let sheets = fx["sheets"] as? [[String: Any]] ?? []
        XCTAssertEqual(sheets.count, 20)
        for s in sheets {
            let id = s["id"] as? String ?? "?"
            let url = try XCTUnwrap(Self.testdataURL(s["file"] as? String ?? ""), "\(id): pdf missing")
            let bytes = try Data(contentsOf: url)
            XCTAssertEqual(SHA256.hash(data: bytes).map { String(format: "%02x", $0) }.joined(), s["sha256"] as? String,
                           "\(id): fixture and pdf out of step, rerun scripts/gen_test_geopdfs.py")
            let readout = try XCTUnwrap(GeoPDFReader.read(url: url), id)
            try assertSheet(readout, s, id)
        }
    }

    private func assertSheet(_ readout: GeoPDFReader.Readout, _ s: [String: Any], _ id: String) throws {
        // the generator writes /MediaBox to 3 dp while the fixture keeps 7, so
        // page boxes only agree to the fixture's 1e-3 pt page tolerance
        let media = dbls(s["mediaBox"])
        XCTAssertEqual(Double(readout.page.mediaBox.minX), media[0], accuracy: 1e-3, id)
        XCTAssertEqual(Double(readout.page.mediaBox.minY), media[1], accuracy: 1e-3, id)
        XCTAssertEqual(Double(readout.page.mediaBox.maxX), media[2], accuracy: 1e-3, id)
        XCTAssertEqual(Double(readout.page.mediaBox.maxY), media[3], accuracy: 1e-3, id)
        if let crop = s["cropBox"] as? [Any] {
            let c = crop.map(dbl)
            XCTAssertEqual(Double(readout.page.cropBox.minX), c[0], accuracy: 1e-3, id)
            XCTAssertEqual(Double(readout.page.cropBox.minY), c[1], accuracy: 1e-3, id)
            XCTAssertEqual(Double(readout.page.cropBox.maxX), c[2], accuracy: 1e-3, id)
            XCTAssertEqual(Double(readout.page.cropBox.maxY), c[3], accuracy: 1e-3, id)
        }
        XCTAssertEqual(readout.page.rotation, Int(dbl(s["rotate"])), id)

        let e = try XCTUnwrap(s["expected"] as? [String: Any], id)
        if (e["origin"] as? String) == "none" {
            XCTAssertEqual(readout.outcome, .notGeoreferenced, "\(id): plain sheet must go to calibration")
            return
        }
        let g = try XCTUnwrap(readout.georef, "\(id): \(readout.outcome)")
        XCTAssertEqual(g.origin.rawValue, e["origin"] as? String, id)
        let sel = try XCTUnwrap(e["selected"] as? [String: Any], id)
        XCTAssertEqual(readout.selection?.kind.rawValue, sel["kind"] as? String, id)
        XCTAssertEqual(readout.selection?.index, Int(dbl(sel["index"])), id)
        if let name = sel["name"] as? String { XCTAssertEqual(readout.selection?.name, name, id) }
        if let desc = sel["description"] as? String { XCTAssertEqual(readout.selection?.name, desc, id) }
        if let src = sel["source"] as? String { XCTAssertEqual(readout.selection?.source?.rawValue, src, id) }
        assertCrs(g.crs, e["crs"], id)
        assertDatum(g.datum, e["datum"], id)
        XCTAssertEqual(g.datumAssumed, e["datumAssumed"] as? Bool ?? false, id)
        assertCrop(g.crop, e["crop"], id)
        assertAffine(g.affine, e["affine"], tol: e["affineTol"], id)
        if e["fit"] != nil { assertFit(readout.fitStats, e["fit"], id) }
        let controls = e["controls"] as? [[String: Any]] ?? []
        if !controls.isEmpty {
            // the pairs the builder really fitted: LPTS through the BBox, GPTS forwarded
            let fitted = try XCTUnwrap(readout.fitStats?.controls, id)
            XCTAssertEqual(fitted.count, controls.count, "\(id) control count")
            let tol = e["tolerances"] as? [String: Any] ?? [:]
            for (i, (c, got)) in zip(controls, fitted).enumerated() {
                let page = dbls(c["page"]), gp = dbls(c["gpts"]), plane = dbls(c["plane"])
                XCTAssertEqual(got.page.x, page[0], accuracy: dbl(tol["pagePoints"]), "\(id) control \(i) page")
                XCTAssertEqual(got.page.y, page[1], accuracy: dbl(tol["pagePoints"]), "\(id) control \(i) page")
                let ptol = g.crs.isGeographic ? 1e-9 : dbl(tol["planeMetres"])
                XCTAssertEqual(got.plane.x, plane[0], accuracy: ptol, "\(id) control \(i) plane")
                XCTAssertEqual(got.plane.y, plane[1], accuracy: ptol, "\(id) control \(i) plane")
                let fwd = try XCTUnwrap(g.crs.forward(lat: gp[0], lon: gp[1], ellipsoid: g.datum.ellipsoid), id)
                XCTAssertEqual(fwd.x, plane[0], accuracy: ptol, "\(id) control \(i) gpts forward")
                XCTAssertEqual(fwd.y, plane[1], accuracy: ptol, "\(id) control \(i) gpts forward")
            }
        }
        let tol = e["tolerances"] as? [String: Any] ?? [:]
        assertChecks(g, e["checks"], id, wgs84Metres: dbl(tol["wgs84Metres"]), pagePoints: dbl(tol["pagePoints"]),
                     planeMetres: dbl(tol["planeMetres"]))
        for c in (e["checks"] as? [[String: Any]] ?? []) where c["truthWgs84"] != nil {
            // printed grid ticks: the model has to put them where NAD83 UTM says
            let page = dbls(c["page"]), truth = dbls(c["truthWgs84"])
            let got = try XCTUnwrap(g.toWGS84(x: page[0], y: page[1]))
            XCTAssertLessThanOrEqual(metres(got, truth[0], truth[1]), dbl(tol["printedGridMetres"]), "\(id) printed grid")
        }
    }

    func testUsgsStandInPrintedGridAndPageExtras() throws {
        let sheets = fx["sheets"] as? [[String: Any]] ?? []
        let s = try XCTUnwrap(sheets.first { ($0["id"] as? String) == "usgs_sf_north" })
        let g = try XCTUnwrap(GeoPDFReader.read(url: try XCTUnwrap(Self.testdataURL(s["file"] as! String)))?.georef)
        // the real sheet's printed 1000 m grid vs the model, 1 m target
        let pm = try XCTUnwrap(s["printedGridMeasured"] as? [String: Any])
        let limit = dbl(pm["toleranceMetres"])
        let nad83 = try XCTUnwrap(GeoDatum.named("NAD83"))
        for p in pm["points"] as? [[String: Any]] ?? [] {
            let grid = dbls(p["gridNAD83"]), measured = dbls(p["pageMeasured"]), model = dbls(p["pageModel"])
            let ll = try XCTUnwrap(GeoCrs.utm(zone: 10, south: false).inverse(x: grid[0], y: grid[1], ellipsoid: nad83.ellipsoid))
            let w = try XCTUnwrap(nad83.toWGS84(lat: ll.lat, lon: ll.lon))
            let back = try XCTUnwrap(g.toPage(lat: w.lat, lon: w.lon))
            XCTAssertEqual(back.x, model[0], accuracy: 1e-3)
            XCTAssertEqual(back.y, model[1], accuracy: 1e-3)
            let at = try XCTUnwrap(g.toWGS84(x: measured[0], y: measured[1]))
            XCTAssertLessThanOrEqual(metres(at, w.lat, w.lon), limit)
            XCTAssertEqual(metres(at, w.lat, w.lon), dbl(p["modelErrorMetres"]), accuracy: 0.01)
        }
        // same /VP dicts dropped into a fresh page: same georef
        let extras = try XCTUnwrap(s["pdfPageExtras"] as? String)
        let rebuilt = try XCTUnwrap(readPDF(pageExtras: extras, mediaBox: dbls(s["mediaBox"]))?.georef)
        XCTAssertEqual(rebuilt, g)
        // the parsed viewports line up with the fixture's view of the real file
        let doc = try XCTUnwrap(CGPDFDocument(try XCTUnwrap(Self.testdataURL(s["file"] as! String)) as CFURL))
        guard case .list(let vps)? = GeoPDFReader.extractViewports(in: try XCTUnwrap(doc.page(at: 1)?.dictionary)) else {
            return XCTFail("usgs stand-in has three /VP viewports")
        }
        let want = s["viewports"] as? [[String: Any]] ?? []
        XCTAssertEqual(vps.count, want.count)
        for (v, w) in zip(vps, want) {
            XCTAssertEqual(v.name, w["name"] as? String)
            // CoreGraphics' real parser can land 1 ulp off the correctly rounded double
            for key in ["bbox", "lpts", "gpts", "bounds"] {
                let got: PdfNumbers
                switch key {
                case "bbox": got = v.bbox
                case "lpts": got = v.lpts
                case "gpts": got = v.gpts
                default: got = v.bounds
                }
                let want = dbls(w[key])
                XCTAssertEqual(got.values?.count, want.count, key)
                for (a, b) in zip(got.values ?? [], want) { XCTAssertEqual(a, b, accuracy: 1e-12 * max(1, abs(b)), key) }
            }
            let gcs = w["gcs"] as? [String: Any] ?? [:]
            XCTAssertEqual(v.gcs, .described(wkt: gcs["wkt"] as? String, epsg: nil), "backslash-CR continuation must vanish")
        }
    }

    /// Optional: the real 38 MB USGS sample when it's been fetched locally.
    func testRealUsgsSampleWhenPresent() throws {
        guard let url = Self.repoFile("samples/USGS_SF_North.pdf") else {
            throw XCTSkip("samples/USGS_SF_North.pdf not fetched (scripts/fetch_samples.sh)")
        }
        let sheets = fx["sheets"] as? [[String: Any]] ?? []
        let s = try XCTUnwrap(sheets.first { ($0["id"] as? String) == "usgs_sf_north" })
        let readout = try XCTUnwrap(GeoPDFReader.read(url: url))
        let e = try XCTUnwrap(s["expected"] as? [String: Any])
        let g = try XCTUnwrap(readout.georef, "\(readout.outcome)")
        XCTAssertEqual(readout.selection?.index, 0)
        assertCrs(g.crs, e["crs"], "usgs real")
        assertDatum(g.datum, e["datum"], "usgs real")
        assertAffine(g.affine, e["affine"], tol: e["affineTol"], "usgs real")
        assertCrop(g.crop, e["crop"], "usgs real")
    }

    // MARK: - rejections

    func testRejectionsFromParsedInputs() throws {
        for c in section("rejections")["cases"] as? [[String: Any]] ?? [] {
            let id = c["id"] as? String ?? "?"
            let input = try XCTUnwrap(c["input"] as? [String: Any], id)
            let mb = dbls(c["mediaBox"])
            let result: PdfGeorefBuildResult?
            if let vps = input["viewports"] as? [[String: Any]] {
                result = PdfGeorefBuilder.build(viewports: vps.map(viewportInput))
            } else if let entries = input["entries"] as? [[String: Any]] {
                let box = [PdfPagePoint(x: mb[0], y: mb[1]), PdfPagePoint(x: mb[2], y: mb[1]),
                           PdfPagePoint(x: mb[2], y: mb[3]), PdfPagePoint(x: mb[0], y: mb[3])]
                result = PdfGeorefBuilder.build(lgiEntries: entries.map(lgiInput), pageBox: box)
            } else {
                // entries that isn't an array stands in for /LGIDict of the wrong type,
                // which the reader turns into Extracted.malformed before the builder
                XCTAssertNotNil(input["entries"], id)
                result = .rejected(.malformed)
            }
            try assertRejectionCase(result, selectionOf: result, c, "\(id) (parsed)")
        }
    }

    func testRejectionsThroughThePDFParser() throws {
        for c in section("rejections")["cases"] as? [[String: Any]] ?? [] {
            let id = c["id"] as? String ?? "?"
            let extras = try XCTUnwrap(c["pdfPageExtras"] as? String, id)
            let readout = try XCTUnwrap(readPDF(pageExtras: extras, mediaBox: dbls(c["mediaBox"])), id)
            let expected = try XCTUnwrap(c["expected"] as? [String: Any], id)
            if (expected["outcome"] as? String) == "reject" {
                XCTAssertEqual(readout.outcome, .rejected(PdfGeorefRejectReason(rawValue: expected["reason"] as! String)!),
                               "\(id) (pdf)")
            } else {
                let g = try XCTUnwrap(readout.georef, "\(id) (pdf): \(readout.outcome)")
                let built: PdfGeorefBuildResult = .georef(g, readout.selection!, readout.fitStats)
                try assertRejectionCase(built, selectionOf: built, c, "\(id) (pdf)")
            }
        }
    }

    private func assertRejectionCase(_ result: PdfGeorefBuildResult?, selectionOf: PdfGeorefBuildResult?,
                                     _ c: [String: Any], _ ctx: String) throws {
        let expected = try XCTUnwrap(c["expected"] as? [String: Any], ctx)
        let r = try XCTUnwrap(result, ctx)
        if (expected["outcome"] as? String) == "reject" {
            XCTAssertEqual(r, .rejected(try XCTUnwrap(PdfGeorefRejectReason(rawValue: expected["reason"] as? String ?? ""))), ctx)
            return
        }
        guard case .georef(let g, let sel, let stats) = r else { return XCTFail("\(ctx): expected accept, got \(r)") }
        let want = try XCTUnwrap(expected["selected"] as? [String: Any], ctx)
        XCTAssertEqual(sel.kind.rawValue, want["kind"] as? String, ctx)
        XCTAssertEqual(sel.index, Int(dbl(want["index"])), ctx)
        if let src = want["source"] as? String { XCTAssertEqual(sel.source?.rawValue, src, ctx) }
        XCTAssertEqual(g.origin.rawValue, expected["origin"] as? String, ctx)
        assertCrs(g.crs, expected["crs"], ctx)
        assertDatum(g.datum, expected["datum"], ctx)
        assertCrop(g.crop, expected["crop"], ctx)
        assertAffine(g.affine, expected["affine"], tol: expected["affineTol"], ctx)
        if expected["fit"] != nil { assertFit(stats, expected["fit"], ctx) }
        assertChecks(g, expected["checks"], ctx)
    }

    private func numbers(_ a: Any?) -> PdfNumbers {
        guard let arr = a as? [Any] else { return a == nil || a is NSNull ? .missing : .malformed }
        var out: [Double] = []
        for v in arr {
            if let n = v as? NSNumber { out.append(n.doubleValue); continue }
            // json can't hold inf, the fixture writes "Infinity"
            if let s = v as? String, let d = Double(s) {
                if !d.isFinite { return .nonFinite }
                out.append(d); continue
            }
            return .malformed
        }
        return .values(out)
    }

    /// structured stand-ins for wrong PDF types (rejections.note): gcs that isn't
    /// an object, wkt that isn't a string, epsg that isn't an integer -> malformed
    private func viewportInput(_ v: [String: Any]) -> AdobeViewportInput {
        var gcs = PdfGcsInput.missing
        if let g = v["gcs"] as? [String: Any] {
            let wkt = g["wkt"], epsg = g["epsg"]
            let wktOK = wkt == nil || wkt is String
            let epsgOK = epsg == nil || ((epsg as? NSNumber).map { Double($0.int64Value) == $0.doubleValue } ?? false)
            gcs = wktOK && epsgOK
                ? .described(wkt: wkt as? String, epsg: (epsg as? NSNumber)?.intValue)
                : .malformed
        } else if v["gcs"] != nil, !(v["gcs"] is NSNull) {
            gcs = .malformed
        }
        return AdobeViewportInput(name: v["name"] as? String, bbox: numbers(v["bbox"]), lpts: numbers(v["lpts"]),
                                  gpts: numbers(v["gpts"]), bounds: numbers(v["bounds"]), gcs: gcs)
    }

    private func lgiValue(_ v: Any) -> LgiValue {
        if let s = v as? String { return .text(s) }
        if let n = v as? NSNumber { return .number(n.doubleValue) }
        if let d = v as? [String: Any] { return .dictionary(d.mapValues(lgiValue)) }
        return .other
    }

    private func lgiInput(_ e: [String: Any]) -> LgiEntryInput {
        var reg = PdfRegistration.missing
        if let rows = e["registration"] as? [[Any]] { reg = .rows(rows.map { $0.map(dbl) }) }
        return LgiEntryInput(description: e["description"] as? String, ctm: numbers(e["ctm"]), registration: reg,
                             neatline: numbers(e["neatline"]),
                             projection: (e["projection"] as? [String: Any])?.mapValues(lgiValue),
                             display: (e["display"] as? [String: Any])?.mapValues(lgiValue))
    }

    // MARK: - fiduciary fits

    func testFiduciaryFitsMatchSharedFixture() throws {
        let f = section("fiduciaryFits")
        let tol = f["tolerance"] as? [String: Any] ?? [:]
        let sets = f["sets"] as? [[String: Any]] ?? []
        XCTAssertEqual(sets.count, 11)
        for set in sets {
            let name = set["name"] as? String ?? "?"
            let sheetDatum = try XCTUnwrap(GeoDatum.named(set["datum"] as? String ?? ""), name)
            let crop = dbls(set["cropBBox"])
            var controls: [FiduciaryControl] = []
            for p in set["points"] as? [[String: Any]] ?? [] {
                let input = p["input"] as? String ?? ""
                let ref = try XCTUnwrap(FiduciaryReference.parse(input), "\(name): can't parse \(input)")
                assertParsed(ref, p["parsed"] as? [String: Any] ?? [:], "\(name) \(input)")
                let page = dbls(p["page"])
                controls.append(FiduciaryControl(page: PdfPagePoint(x: page[0], y: page[1]), reference: ref))
            }
            let r = try XCTUnwrap(FiduciaryFitter.fit(controls, datum: sheetDatum,
                                                      cropBBox: CGRect(x: crop[0], y: crop[1],
                                                                       width: crop[2] - crop[0], height: crop[3] - crop[1])), name)
            let e = try XCTUnwrap(set["expected"] as? [String: Any], name)
            XCTAssertEqual(r.zone, Int(dbl(e["zone"])), name)
            XCTAssertEqual(r.south, (e["hemisphere"] as? String) == "S", name)
            assertCrs(r.crs, e["crs"], name)
            assertDatum(r.datum, e["datum"], name)
            let plane = pts(e["planePoints"])
            XCTAssertEqual(r.planePoints.count, plane.count, name)
            for (g, w) in zip(r.planePoints, plane) {
                XCTAssertEqual(g.x, w.x, accuracy: dbl(tol["planeMetres"]), "\(name) plane")
                XCTAssertEqual(g.y, w.y, accuracy: dbl(tol["planeMetres"]), "\(name) plane")
            }
            XCTAssertEqual(r.eigenRatio, dbl(e["eigenRatio"]),
                           accuracy: dbl(tol["eigenRatioRelative"]) * max(1e-12, abs(dbl(e["eigenRatio"]))),
                           "\(name) eigen ratio")
            XCTAssertEqual(r.degenerate, e["degenerate"] as? Bool, name)
            let span = dbls(e["spanFraction"])
            XCTAssertEqual(r.spanFraction.x, span[0], accuracy: 1e-6, name)
            XCTAssertEqual(r.spanFraction.y, span[1], accuracy: 1e-6, name)
            XCTAssertEqual(r.spanWarning, e["spanWarning"] as? Bool, name)
            XCTAssertEqual(r.crossValidated, e["crossValidated"] as? Bool, name)
            if e["affine"] is NSNull || e["affine"] == nil {
                XCTAssertNil(r.affine, "\(name): must refuse the fit")
                XCTAssertNil(r.georeference(crop: [PdfPagePoint(x: 0, y: 0), PdfPagePoint(x: 1, y: 0), PdfPagePoint(x: 1, y: 1)]))
                continue
            }
            let affine = try XCTUnwrap(r.affine, name)
            let ea = dbls(e["affine"])
            for (i, (g, w)) in zip(affine.coefficients, ea).enumerated() {
                XCTAssertEqual(g, w, accuracy: 1e-7 * max(1, abs(w)), "\(name) affine[\(i)]")
            }
            let resTol = dbl(tol["residualMetres"])
            for (g, w) in zip(r.residualsMetres, dbls(e["residualsMetres"])) { XCTAssertEqual(g, w, accuracy: resTol, "\(name) residual") }
            XCTAssertEqual(r.rmsMetres, dbl(e["rmsMetres"]), accuracy: resTol, name)
            XCTAssertEqual(r.maxResidualMetres, dbl(e["maxResidualMetres"]), accuracy: resTol, name)
            if let loo = e["leaveOneOutMetres"] as? [Any] {
                let looRms = e["leaveOneOutRmsMetres"] as? [Any] ?? []
                XCTAssertEqual(r.leaveOneOutMetres.count, loo.count, name)
                for (g, w) in zip(r.leaveOneOutMetres, loo) {
                    if w is NSNull { XCTAssertNil(g, name) } else { XCTAssertEqual(g ?? .nan, dbl(w), accuracy: resTol, "\(name) loo") }
                }
                for (g, w) in zip(r.leaveOneOutRmsMetres, looRms) {
                    if w is NSNull { XCTAssertNil(g, name) } else { XCTAssertEqual(g ?? .nan, dbl(w), accuracy: resTol, "\(name) looRms") }
                }
                XCTAssertEqual(r.flaggedOutliers, (e["flaggedOutliers"] as? [NSNumber])?.map(\.intValue), name)
            }
            XCTAssertEqual(r.exactFit, e["exactFit"] as? Bool ?? false, name)
            XCTAssertEqual(r.message, e["message"] as? String, name)
            let g = try XCTUnwrap(r.georeference(crop: [PdfPagePoint(x: crop[0], y: crop[1]), PdfPagePoint(x: crop[2], y: crop[1]),
                                                        PdfPagePoint(x: crop[2], y: crop[3]), PdfPagePoint(x: crop[0], y: crop[3])]), name)
            XCTAssertEqual(g.origin, .fiduciaries)
            XCTAssertEqual(g.fit?.crossValidated, e["crossValidated"] as? Bool)
            assertChecks(g, e["checks"], name, wgs84Metres: dbl(tol["wgs84Metres"]), pagePoints: dbl(tol["pagePoints"]),
                         planeMetres: dbl(tol["planeMetres"]))
        }
    }

    private func assertParsed(_ ref: FiduciaryReference, _ want: [String: Any], _ ctx: String) {
        switch ref {
        case let .mgrs(zone, band, south, e, n), let .utm(zone, band, south, e, n):
            if case .mgrs = ref { XCTAssertEqual(want["kind"] as? String, "mgrs", ctx) } else { XCTAssertEqual(want["kind"] as? String, "utm", ctx) }
            XCTAssertEqual(zone, Int(dbl(want["zone"])), ctx)
            XCTAssertEqual(String(band), want["band"] as? String, ctx)
            XCTAssertEqual(south, (want["hemisphere"] as? String) == "S", ctx)
            XCTAssertEqual(e, dbl(want["easting"]), accuracy: 1e-6, ctx)
            XCTAssertEqual(n, dbl(want["northing"]), accuracy: 1e-6, ctx)
        case let .latLon(lat, lon):
            XCTAssertEqual(want["kind"] as? String, "latlon", ctx)
            XCTAssertEqual(lat, dbl(want["lat"]), accuracy: 1e-12, ctx)
            XCTAssertEqual(lon, dbl(want["lon"]), accuracy: 1e-12, ctx)
        }
    }

    /// Plain sheets carry their printed grid truth: calibrating on the four
    /// ring targets has to land every grid intersection.
    func testPlainSheetsCalibrateOntoTheirPrintedGrid() throws {
        for s in fx["sheets"] as? [[String: Any]] ?? [] where (s["writer"] as? String) == "plain" {
            let id = s["id"] as? String ?? "?"
            let truth = try XCTUnwrap(s["truth"] as? [String: Any], id)
            let datum = try XCTUnwrap(GeoDatum.named(truth["datum"] as? String ?? ""), id)
            let controls = try (truth["fiducialTargets"] as? [[String: Any]] ?? []).map { t -> FiduciaryControl in
                let page = dbls(t["page"])
                return FiduciaryControl(page: PdfPagePoint(x: page[0], y: page[1]),
                                        reference: try XCTUnwrap(FiduciaryReference.parse(t["label"] as? String ?? ""), id))
            }
            let media = dbls(s["mediaBox"])
            let box = CGRect(x: media[0], y: media[1], width: media[2] - media[0], height: media[3] - media[1])
            let fit = try XCTUnwrap(FiduciaryFitter.fit(controls, datum: datum, cropBBox: box), id)
            XCTAssertLessThan(fit.rmsMetres, 0.01, id)
            let g = try XCTUnwrap(fit.georeference(crop: [PdfPagePoint(x: media[0], y: media[1]), PdfPagePoint(x: media[2], y: media[1]),
                                                          PdfPagePoint(x: media[2], y: media[3]), PdfPagePoint(x: media[0], y: media[3])]), id)
            let grid = s["gridChecks"] as? [[String: Any]] ?? []
            XCTAssertFalse(grid.isEmpty, id)
            for c in grid {
                let page = dbls(c["page"]), w = dbls(c["wgs84"])
                let got = try XCTUnwrap(g.toWGS84(x: page[0], y: page[1]), id)
                XCTAssertLessThanOrEqual(metres(got, w[0], w[1]), 0.01, "\(id) \(c["label"] ?? "")")
                let back = try XCTUnwrap(g.toPage(lat: w[0], lon: w[1]), id)
                XCTAssertEqual(back.x, page[0], accuracy: 1e-3, id)
                XCTAssertEqual(back.y, page[1], accuracy: 1e-3, id)
            }
        }
    }

    /// fiduciaryFits.parseCases: the input grammar both apps share, refusals included
    func testFiduciaryReferenceParseCases() throws {
        let cases = section("fiduciaryFits")["parseCases"] as? [[String: Any]] ?? []
        XCTAssertGreaterThanOrEqual(cases.count, 20)
        for c in cases {
            let input = c["input"] as? String ?? ""
            let got = FiduciaryReference.parse(input)
            if let want = c["parsed"] as? [String: Any] {
                assertParsed(try XCTUnwrap(got, "should parse '\(input)'"), want, input)
            } else {
                XCTAssertNil(got, "'\(input)' must be refused: \(c["note"] ?? "")")
            }
        }
    }

    /// fiduciaryFits.whiteSpaceCodePoints: exactly these become a space, every
    /// other BMP scalar is left alone (Kotlin uses the same list)
    func testFiduciaryWhitespaceSetMatchesSharedFixture() throws {
        let want = Set((section("fiduciaryFits")["whiteSpaceCodePoints"] as? [NSNumber] ?? []).map(\.uint32Value))
        XCTAssertEqual(want.count, 29)
        var got = Set<UInt32>()
        for v in UInt32(0)...0xFFFF {
            guard let u = Unicode.Scalar(v) else { continue }
            if FiduciaryReference.normalisedWhitespace("a" + String(Character(u)) + "b") == "a b" { got.insert(v) }
        }
        XCTAssertEqual(got, want)
    }

    /// fiduciaryFits.storedSets go through the production entry point: saved
    /// Fiduciary (page point, WGS84 lat/lon, what was typed) -> georef. The zone
    /// has to come off the first point's typed MGRS/UTM, not its longitude.
    func testStoredFiduciariesRefitThroughProductionPath() throws {
        let f = section("fiduciaryFits")
        let tol = f["tolerance"] as? [String: Any] ?? [:]
        let sets = f["storedSets"] as? [[String: Any]] ?? []
        XCTAssertEqual(sets.count, 3)
        for set in sets {
            let name = set["name"] as? String ?? "?"
            let fids = (set["fiduciaries"] as? [[String: Any]] ?? []).map {
                Fiduciary(pdfX: dbl($0["pdfX"]), pdfY: dbl($0["pdfY"]), mgrs: $0["mgrs"] as? String ?? "",
                          latitude: dbl($0["latitude"]), longitude: dbl($0["longitude"]))
            }
            let crop = dbls(set["cropBBox"])
            let rect = CGRect(x: crop[0], y: crop[1], width: crop[2] - crop[0], height: crop[3] - crop[1])
            let e = try XCTUnwrap(set["expected"] as? [String: Any], name)
            let zs = FiduciaryFitter.storedZone(fids[0])
            XCTAssertEqual(zs.zone, Int(dbl(e["zone"])), name)
            XCTAssertEqual(zs.south, (e["hemisphere"] as? String) == "S", name)
            XCTAssertEqual(FiduciaryFitter.standardZone(lon: fids[0].longitude), Int(dbl(e["standardZoneOfFirstPoint"])), name)

            let g = try XCTUnwrap(FiduciaryFitter.georeference(fromWGS84: fids, crop: rect), name)
            XCTAssertEqual(g.origin, .fiduciaries)
            assertCrs(g.crs, e["crs"], name)
            assertDatum(g.datum, e["datum"], name)
            let ea = dbls(e["affine"])
            for (i, (got, want)) in zip(g.affine.coefficients, ea).enumerated() {
                XCTAssertEqual(got, want, accuracy: 1e-7 * max(1, abs(want)), "\(name) affine[\(i)]")
            }
            let resTol = dbl(tol["residualMetres"])
            let fit = try XCTUnwrap(g.fit, name)
            XCTAssertEqual(fit.perPoint.count, fids.count, name)
            for (got, want) in zip(fit.perPoint, dbls(e["residualsMetres"])) { XCTAssertEqual(got, want, accuracy: resTol, name) }
            XCTAssertEqual(fit.rmsMetres, dbl(e["rmsMetres"]), accuracy: resTol, name)
            XCTAssertEqual(fit.maxResidualMetres, dbl(e["maxResidualMetres"]), accuracy: resTol, name)
            XCTAssertEqual(fit.crossValidated, e["crossValidated"] as? Bool, name)
            assertChecks(g, e["checks"], name, wgs84Metres: dbl(tol["wgs84Metres"]), pagePoints: dbl(tol["pagePoints"]),
                         planeMetres: dbl(tol["planeMetres"]))
            // and the printed grid lands, which a fit in the standard zone doesn't (1.4 m / 0.8 m)
            let limit = dbl(e["truthToleranceMetres"])
            var worst = 0.0
            for t in e["truth"] as? [[String: Any]] ?? [] {
                let page = dbls(t["page"]), w = dbls(t["wgs84"])
                let got = try XCTUnwrap(g.toWGS84(x: page[0], y: page[1]), name)
                worst = max(worst, metres(got, w[0], w[1]))
            }
            XCTAssertLessThanOrEqual(worst, limit, "\(name) printed grid")
            XCTAssertEqual(worst, dbl(e["modelErrorMetres"]), accuracy: 0.01, name)
            // same thing through the source, which is what finish() and the migration call
            let src = PDFMapSource(url: URL(fileURLWithPath: "/dev/null"), georef: try XCTUnwrap(PdfGeoreference.provisional(
                pageBox: rect, rotation: 0, centredOn: CLLocationCoordinate2D(latitude: 0, longitude: 0))))
            src.applyCalibration(transform: try XCTUnwrap(g.bestFitLatLonAffine()), fiduciaries: fids)
            XCTAssertEqual(src.georef, g, "\(name): PDFMapSource.applyCalibration takes the same zone")
        }
    }

    // MARK: - tile warp

    func testTileWarpMatchesSharedFixture() throws {
        let t = section("tileWarp")
        let tol = t["tolerance"] as? [String: Any] ?? [:]
        let sheets = fx["sheets"] as? [[String: Any]] ?? []
        let tiles = t["tiles"] as? [[String: Any]] ?? []
        XCTAssertEqual(tiles.count, 16)
        for tile in tiles {
            let gid = tile["georef"] as? String ?? "?"
            let s = try XCTUnwrap(sheets.first { ($0["id"] as? String) == gid }, gid)
            let e = try XCTUnwrap(s["expected"] as? [String: Any], gid)
            let a = dbls(e["affine"])
            let g = PdfGeoreference(crs: try XCTUnwrap(crs(e["crs"])), datum: try XCTUnwrap(datum(e["datum"])),
                                    affine: PlaneAffine(a: a[0], b: a[1], c: a[2], d: a[3], e: a[4], f: a[5]),
                                    crop: pts(e["crop"]), origin: .adobeVP)
            let z = Int(dbl(tile["z"])), x = Int(dbl(tile["x"])), y = Int(dbl(tile["y"]))
            for sample in tile["samples"] as? [[String: Any]] ?? [] {
                let px = dbls(sample["px"]), w = dbls(sample["wgs84"]), page = dbls(sample["page"])
                let ll = PdfTileWarp.wgs84(z: z, x: x, y: y, px: px[0], py: px[1])
                XCTAssertEqual(ll.latitude, w[0], accuracy: dbl(tol["degrees"]), "\(gid) z\(z)")
                XCTAssertEqual(ll.longitude, w[1], accuracy: dbl(tol["degrees"]), "\(gid) z\(z)")
                let p = try XCTUnwrap(PdfTileWarp.pagePoint(g, z: z, x: x, y: y, px: px[0], py: px[1]), gid)
                XCTAssertEqual(Double(p.x), page[0], accuracy: dbl(tol["pagePoints"]), "\(gid) z\(z) \(px)")
                XCTAssertEqual(Double(p.y), page[1], accuracy: dbl(tol["pagePoints"]), "\(gid) z\(z) \(px)")
            }
        }
    }

    // MARK: - one page PDF writer (latin-1, one char per byte, same as the fixture)

    private func readPDF(pageExtras: String, mediaBox: [Double]) -> GeoPDFReader.Readout? {
        let mb = mediaBox.map { String($0) }.joined(separator: " ")
        let objects = ["<< /Type /Catalog /Pages 2 0 R >>",
                       "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                       "<< /Type /Page /Parent 2 0 R /MediaBox [\(mb)] /Resources << >> /Contents 4 0 R \(pageExtras) >>",
                       "<< /Length 0 >>\nstream\n\nendstream"]
        var data = Data()
        func append(_ s: String) { data.append(s.data(using: .isoLatin1) ?? Data()) }
        append("%PDF-1.7\n")
        var offsets: [Int] = []
        for (i, o) in objects.enumerated() {
            offsets.append(data.count)
            append("\(i + 1) 0 obj\n\(o)\nendobj\n")
        }
        let xref = data.count
        append("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n")
        offsets.forEach { append(String(format: "%010d 00000 n \n", $0)) }
        append("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n")
        guard let provider = CGDataProvider(data: data as CFData),
              let doc = CGPDFDocument(provider) else { return nil }
        return GeoPDFReader.read(document: doc)
    }

    private func decodeWktLiteralThroughPDF(_ literal: String) -> String? {
        let vp = "/VP [<< /BBox [0 0 1 1] /Measure << /Subtype /GEO /GPTS [] /LPTS [] /GCS << /WKT (\(literal)) >> >> >>]"
        let mb = "0 0 600 400"
        let objects = ["<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                       "<< /Type /Page /Parent 2 0 R /MediaBox [\(mb)] \(vp) >>"]
        var data = Data()
        func append(_ s: String) { data.append(s.data(using: .isoLatin1) ?? Data()) }
        append("%PDF-1.7\n")
        var offsets: [Int] = []
        for (i, o) in objects.enumerated() { offsets.append(data.count); append("\(i + 1) 0 obj\n\(o)\nendobj\n") }
        let xref = data.count
        append("xref\n0 \(objects.count + 1)\n0000000000 65535 f \n")
        offsets.forEach { append(String(format: "%010d 00000 n \n", $0)) }
        append("trailer\n<< /Size \(objects.count + 1) /Root 1 0 R >>\nstartxref\n\(xref)\n%%EOF\n")
        guard let provider = CGDataProvider(data: data as CFData), let doc = CGPDFDocument(provider),
              let dict = doc.page(at: 1)?.dictionary,
              case .list(let vps)? = GeoPDFReader.extractViewports(in: dict),
              case .described(let wkt, _) = vps.first?.gcs else { return nil }
        return wkt
    }
}
