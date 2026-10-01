import XCTest
import CoreGraphics
import UIKit
@testable import TacticalMaps

/// The real CoreGraphics renderer against testdata: markers PDF (offset
/// MediaBox, inset CropBox, /Rotate 90, OCG off), blank PDF, and the WP1
/// sheets' fiducial rings. Fixture sections markers / blank / ringTargets.
final class PDFTileRendererTests: XCTestCase {
    typealias F = PDFTileRenderFixtureTests

    override func setUp() {
        super.setUp()
        PDFTileRenderer.assertsOffMainThread = false
    }

    override func tearDown() {
        PDFTileRenderer.assertsOffMainThread = true
        super.tearDown()
    }

    struct Sheet {
        let url: URL
        let doc: CGPDFDocument
        let page: CGPDFPage
        let georef: PdfGeoreference
        let footprint: PDFFootprint
        let policy: PDFZoomPolicy
        let base: PDFPageRaster
        let basePlan: PDFBaseRasterPlan
    }

    func openSheet(_ file: String, georef: PdfGeoreference? = nil) throws -> Sheet {
        let url = try XCTUnwrap(F.testdataURL(file), file)
        let (doc, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        let g = try georef ?? XCTUnwrap(GeoPDFReader.read(url: url)?.georef, "\(file) georef")
        let fp = try PDFFootprint(georef: g, pageBox: PDFTileRenderer.pageBox(page))
        let policy = try PDFZoomPolicy(georef: g, footprint: fp)
        let plan = policy.baseRasterPlan(footprint: fp, budgetPixels: PDFTileConstants.baseBudgetPx)
        let base = try PDFTileRenderer.renderBaseRaster(page: page, plan: plan, footprint: fp)
        return Sheet(url: url, doc: doc, page: page, georef: g, footprint: fp, policy: policy, base: base, basePlan: plan)
    }

    enum Path { case live, raster, direct, staged }

    func render(_ s: Sheet, tile t: TileIndex, tilePx: Int, path: Path = .live) throws -> CGImage? {
        let job = TileJob(z: t.z, x0: t.x, y0: t.y, cols: 1, rows: 1)
        let plan = PDFTileWarp.plan(job: job, tilePx: tilePx, footprint: s.footprint, georef: s.georef)
        let kind: PDFRenderSourceKind
        switch path {
        case .live:
            if t.z <= s.policy.baseMaxZoom(plan: s.basePlan, tilePx: tilePx) { kind = .raster(s.base) }
            else if plan.cells.count == 1 { kind = .vector(s.page) }
            else { kind = .staged(s.page) }
        case .raster: kind = .raster(s.base)
        case .direct: kind = .vector(s.page)
        case .staged: kind = .staged(s.page)
        }
        let out = try PDFTileRenderer.renderJob(job, tilePx: tilePx, plan: plan, footprint: s.footprint, source: kind)
        if case .image(let img)? = out[t] { return img }
        return nil
    }

    struct Pixels {
        let data: [UInt8]
        let bpr: Int
        let w: Int
        let h: Int
        /// unpremultiplied r, g, b, a
        func at(_ x: Int, _ y: Int) -> (r: Int, g: Int, b: Int, a: Int) {
            guard x >= 0, y >= 0, x < w, y < h else { return (0, 0, 0, 0) }
            let i = y * bpr + x * 4
            let a = Int(data[i + 3])
            guard a > 0 else { return (0, 0, 0, 0) }
            return (Int(data[i + 2]) * 255 / a, Int(data[i + 1]) * 255 / a, Int(data[i]) * 255 / a, a)
        }
    }

    func pixels(_ img: CGImage) throws -> Pixels {
        let p = try XCTUnwrap(PDFTileRenderer.pixels(img))
        return Pixels(data: p.data, bpr: p.bytesPerRow, w: img.width, h: img.height)
    }

    /// weighted centroid of pixels passing weight in a window round near
    func centroid(_ px: Pixels, near: (Double, Double), radius: Double,
                  weight: ((r: Int, g: Int, b: Int, a: Int)) -> Double) -> (Double, Double)? {
        var sx = 0.0, sy = 0.0, sw = 0.0
        let x0 = Int(near.0 - radius), x1 = Int(near.0 + radius), y0 = Int(near.1 - radius), y1 = Int(near.1 + radius)
        for y in max(0, y0)...min(px.h - 1, y1) {
            for x in max(0, x0)...min(px.w - 1, x1) {
                // round window, a square one picks up the ring labels at (+6, +6) pt
                let dx = Double(x) + 0.5 - near.0, dy = Double(y) + 0.5 - near.1
                if dx * dx + dy * dy > radius * radius { continue }
                let w = weight(px.at(x, y))
                if w > 0 { sx += (Double(x) + 0.5) * w; sy += (Double(y) + 0.5) * w; sw += w }
            }
        }
        return sw > 0 ? (sx / sw, sy / sw) : nil
    }

    static func redness(_ p: (r: Int, g: Int, b: Int, a: Int)) -> Double {
        let v = Double(p.r - max(p.g, p.b)) / 255 * Double(p.a) / 255
        return v > 0.2 ? v : 0
    }

    // MARKS: markers sheet

    func testMarkersGeorefAndZoomPolicyMatchFixture() throws {
        let m = try XCTUnwrap(F.fx["markers"] as? [String: Any])
        let s = try openSheet(m["file"] as! String)
        let g = try XCTUnwrap(m["georef"] as? [String: Any])
        for (got, want) in zip(s.georef.affine.coefficients, F.dbls(g["affine"])) {
            XCTAssertEqual(got, want, accuracy: max(1e-6 * abs(want), 0.01 / 600), "markers affine")
        }
        XCTAssertEqual(s.georef.crop, F.pts(g["crop"]))
        var zp = try XCTUnwrap(m["zoomPolicy"] as? [String: Any])
        zp["sheet"] = "render_markers"
        try assertZoomPolicy(zp, georef: s.georef)
    }

    func testMarkersLandOnTheirTilePixelsOnEveryPath() throws {
        let m = try XCTUnwrap(F.fx["markers"] as? [String: Any])
        let s = try openSheet(m["file"] as! String)
        let tol = F.dbl((F.fx["tolerances"] as? [String: Any])?["markerCentroidPx"])
        var checked = 0
        for marker in m["markers"] as? [[String: Any]] ?? [] {
            for e in marker["expected"] as? [[String: Any]] ?? [] {
                let tp = F.int(e["tilePx"]), z = F.int(e["z"])
                let tile = e["tile"] as! [Int]
                let t = TileIndex(z: z, x: tile[0], y: tile[1])
                let want = F.dbls(e["px"])
                let bmz = s.policy.baseMaxZoom(plan: s.basePlan, tilePx: tp)
                var paths: [Path] = [.live]
                if z > bmz { paths += [.direct, .staged] } else { paths += [.raster] }
                for path in paths {
                    let img = try XCTUnwrap(try render(s, tile: t, tilePx: tp, path: path))
                    let px = try pixels(img)
                    let r = 4 * s.policy.pxPerPoint(z: z, tilePx: tp) + 3
                    let c = try XCTUnwrap(centroid(px, near: (want[0], want[1]), radius: r, weight: Self.redness),
                                          "\(marker["label"]!) z\(z)@\(tp) \(path) no red")
                    // the 4 pt square can straddle a tile edge, then the visible part's centroid is off: skip those
                    if want[0] - r < 0 || want[1] - r < 0 || want[0] + r > Double(tp) || want[1] + r > Double(tp) { continue }
                    XCTAssertEqual(c.0, want[0], accuracy: tol, "\(marker["label"]!) z\(z)@\(tp) \(path) x")
                    XCTAssertEqual(c.1, want[1], accuracy: tol, "\(marker["label"]!) z\(z)@\(tp) \(path) y")
                    checked += 1
                }
            }
        }
        XCTAssertGreaterThan(checked, 40)
    }

    func testClippedMarkerAndHiddenOptionalContentAreNotDrawn() throws {
        let m = try XCTUnwrap(F.fx["markers"] as? [String: Any])
        let s = try openSheet(m["file"] as! String)
        let clipped = try XCTUnwrap(m["clippedMarker"] as? [String: Any])
        for e in clipped["expected"] as? [[String: Any]] ?? [] {
            let tile = e["tile"] as! [Int]
            let tp = F.int(e["tilePx"]), z = F.int(e["z"])
            guard let img = try render(s, tile: TileIndex(z: z, x: tile[0], y: tile[1]), tilePx: tp) else { continue }
            let px = try pixels(img)
            let want = F.dbls(e["px"])
            XCTAssertNil(centroid(px, near: (want[0], want[1]), radius: 2 * s.policy.pxPerPoint(z: z, tilePx: tp),
                                  weight: Self.redness), "clipped marker drawn z\(z)@\(tp)")
            XCTAssertEqual(px.at(Int(want[0]), Int(want[1])).a, 0, "clipped marker alpha z\(z)@\(tp)")
        }
        // OCG gate: the blue square is in a layer thats OFF by default
        let hidden = try XCTUnwrap(m["hiddenOcgSquare"] as? [String: Any])
        for e in hidden["expected"] as? [[String: Any]] ?? [] {
            let tile = e["tile"] as! [Int]
            let tp = F.int(e["tilePx"]), z = F.int(e["z"])
            for path: Path in [.live, .direct, .raster] {
                let img = try XCTUnwrap(try render(s, tile: TileIndex(z: z, x: tile[0], y: tile[1]), tilePx: tp, path: path))
                let px = try pixels(img)
                let want = F.dbls(e["px"])
                let p = px.at(Int(want[0]), Int(want[1]))
                XCTAssertEqual(p.a, 255, "ocg z\(z)@\(tp) \(path)")
                XCTAssertGreaterThan(min(p.r, p.g), 240, "ocg square drawn z\(z)@\(tp) \(path): \(p)")
            }
        }
    }

    func testAlphaSamples() throws {
        let m = try XCTUnwrap(F.fx["markers"] as? [String: Any])
        let s = try openSheet(m["file"] as! String)
        var cache: [String: Pixels] = [:]
        for e in m["alphaSamples"] as? [[String: Any]] ?? [] {
            let tile = e["tile"] as! [Int]
            let tp = F.int(e["tilePx"]), z = F.int(e["z"])
            let key = "\(z)/\(tile[0])/\(tile[1])@\(tp)"
            if cache[key] == nil {
                if let img = try render(s, tile: TileIndex(z: z, x: tile[0], y: tile[1]), tilePx: tp) {
                    cache[key] = try pixels(img)
                } else {
                    cache[key] = Pixels(data: [], bpr: 0, w: 0, h: 0)
                }
            }
            let px = F.dbls(e["px"])
            XCTAssertEqual(cache[key]!.at(Int(px[0]), Int(px[1])).a, F.int(e["alpha"]), "\(e["label"]!) \(key)")
        }
    }

    func testBlankPdfIsReportedBlank() throws {
        let b = try XCTUnwrap(F.fx["blank"] as? [String: Any])
        let s = try openSheet(b["file"] as! String)
        XCTAssertEqual(b["expectedFailure"] as? String, PDFRenderFailure.blank.rawValue)
        XCTAssertTrue(s.base.blank)
        // and a sheet with linework is not
        let m = try openSheet("geopdf/tacmap_grid_sf_iso.pdf")
        XCTAssertFalse(m.base.blank)
    }

    func testPasswordProtectedPdfFailsCleanly() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("enc-\(UUID().uuidString).pdf")
        defer { try? FileManager.default.removeItem(at: url) }
        var box = CGRect(x: 0, y: 0, width: 200, height: 200)
        let info: [CFString: Any] = [kCGPDFContextUserPassword: "secret", kCGPDFContextOwnerPassword: "owner"]
        let ctx = try XCTUnwrap(CGContext(url as CFURL, mediaBox: &box, info as CFDictionary))
        ctx.beginPDFPage(nil)
        ctx.setFillColor(UIColor.red.cgColor)
        ctx.fill(CGRect(x: 10, y: 10, width: 50, height: 50))
        ctx.endPDFPage()
        ctx.closePDF()
        XCTAssertThrowsError(try PDFTileRenderer.openPage(url: url, pageIndex: 0)) {
            XCTAssertEqual($0 as? PDFRenderFailure, .passwordProtected)
        }
        XCTAssertThrowsError(try PDFTileRenderer.openPage(url: try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf")),
                                                          pageIndex: 3)) {
            XCTAssertEqual($0 as? PDFRenderFailure, .pageMissing)
        }
        XCTAssertThrowsError(try PDFTileRenderer.openPage(url: url.appendingPathExtension("nope"), pageIndex: 0)) {
            XCTAssertEqual($0 as? PDFRenderFailure, .cannotOpen)
        }
    }

    // MARK: WP1 sheets

    func testFiducialRingsLandOnTheirTilePixels() throws {
        var sheets: [String: Sheet] = [:]
        var checked = 0
        // same fixture tolerance the marker test reads, never a literal
        let tol = F.dbl((F.fx["tolerances"] as? [String: Any])?["markerCentroidPx"])
        XCTAssertGreaterThan(tol, 0)
        for r in try XCTUnwrap(F.fx["ringTargets"] as? [[String: Any]]) {
            let id = r["sheet"] as! String
            if sheets[id] == nil { sheets[id] = try openSheet("geopdf/tacmap_grid_\(id).pdf", georef: F.georef(sheet: id)) }
            let s = sheets[id]!
            for e in r["expected"] as? [[String: Any]] ?? [] {
                let tile = e["tile"] as! [Int]
                let tp = F.int(e["tilePx"]), z = F.int(e["z"])
                let want = F.dbls(e["px"])
                let rad = 6 * s.policy.pxPerPoint(z: z, tilePx: tp)
                if want[0] - rad < 0 || want[1] - rad < 0 || want[0] + rad > Double(tp) || want[1] + rad > Double(tp) { continue }
                let img = try XCTUnwrap(try render(s, tile: TileIndex(z: z, x: tile[0], y: tile[1]), tilePx: tp))
                let px = try pixels(img)
                let c = try XCTUnwrap(centroid(px, near: (want[0], want[1]), radius: rad) { p in
                    // dark and grey: the ring is black, the printed grid is red
                    let dark = 255 - max(p.r, p.g, p.b)
                    let grey = max(p.r, p.g, p.b) - min(p.r, p.g, p.b) < 40
                    return p.a > 128 && grey && dark > 40 ? Double(dark) / 255 : 0
                }, "\(id) \(r["id"]!) z\(z)@\(tp)")
                XCTAssertEqual(c.0, want[0], accuracy: tol, "\(id) \(r["id"]!) z\(z)@\(tp) x")
                XCTAssertEqual(c.1, want[1], accuracy: tol, "\(id) \(r["id"]!) z\(z)@\(tp) y")
                checked += 1
            }
        }
        XCTAssertGreaterThan(checked, 20)
    }

    /// red grid line crossings along the vertical seam between two tiles
    /// rendered by separate jobs line up (D3-01, D2-04, D4-09)
    func testLinesAreContinuousAcrossSeparatelyRenderedTiles() throws {
        let s = try openSheet("geopdf/tacmap_grid_rot5_iso.pdf", georef: F.georef(sheet: "rot5_iso"))
        let c = s.footprint.clipMean
        let ll = try XCTUnwrap(s.georef.toWGS84(x: c.x, y: c.y))
        for (z, tp) in [(15, 768), (16, 768), (16, 512)] {
            let w = PDFTileMath.world0(lat: ll.latitude, lon: ll.longitude)
            let n = pow(2.0, Double(z))
            let tx = Int((w.x * n / 256).rounded(.down)), ty = Int((w.y * n / 256).rounded(.down))
            let a = try pixels(try XCTUnwrap(try render(s, tile: TileIndex(z: z, x: tx, y: ty), tilePx: tp)))
            let b = try pixels(try XCTUnwrap(try render(s, tile: TileIndex(z: z, x: tx + 1, y: ty), tilePx: tp)))
            func runs(_ px: Pixels, col: Int) -> [Double] {
                var out: [Double] = []
                var y = 0
                while y < px.h {
                    if Self.redness(px.at(col, y)) > 0 {
                        var e = y
                        while e + 1 < px.h, Self.redness(px.at(col, e + 1)) > 0 { e += 1 }
                        out.append(Double(y + e) / 2)
                        y = e + 1
                    } else { y += 1 }
                }
                return out
            }
            let left = runs(a, col: tp - 1), right = runs(b, col: 0)
            XCTAssertFalse(left.isEmpty, "z\(z)@\(tp) no line crossings on the seam")
            XCTAssertEqual(left.count, right.count, "z\(z)@\(tp) \(left) vs \(right)")
            for (l, r) in zip(left, right) { XCTAssertEqual(l, r, accuracy: 1.0, "z\(z)@\(tp) seam") }
        }
    }

    func testBaseRasterAgreesWithVectorAroundBaseMaxZoom() throws {
        let m = try XCTUnwrap(F.fx["markers"] as? [String: Any])
        let s = try openSheet(m["file"] as! String)
        let marker = try XCTUnwrap((m["markers"] as? [[String: Any]])?.first)
        for tp in [768, 512] {
            let bmz = s.policy.baseMaxZoom(plan: s.basePlan, tilePx: tp)
            for z in [bmz, bmz + 1] {
                guard let e = (marker["expected"] as? [[String: Any]])?.first(where: { F.int($0["z"]) == z && F.int($0["tilePx"]) == tp }) else { continue }
                let tile = e["tile"] as! [Int]
                let t = TileIndex(z: z, x: tile[0], y: tile[1])
                let want = F.dbls(e["px"])
                let r = 4 * s.policy.pxPerPoint(z: z, tilePx: tp) + 3
                let ra = try XCTUnwrap(centroid(try pixels(try XCTUnwrap(try render(s, tile: t, tilePx: tp, path: .raster))),
                                                near: (want[0], want[1]), radius: r, weight: Self.redness))
                let ve = try XCTUnwrap(centroid(try pixels(try XCTUnwrap(try render(s, tile: t, tilePx: tp, path: .direct))),
                                                near: (want[0], want[1]), radius: r, weight: Self.redness))
                XCTAssertEqual(ra.0, ve.0, accuracy: 1, "z\(z)@\(tp)")
                XCTAssertEqual(ra.1, ve.1, accuracy: 1, "z\(z)@\(tp)")
            }
        }
    }

    func testMultiTileJobMatchesSingleTileJobs() throws {
        let s = try openSheet("geopdf/tacmap_grid_sf_iso.pdf", georef: F.georef(sheet: "sf_iso"))
        let c = s.footprint.clipMean
        let ll = try XCTUnwrap(s.georef.toWGS84(x: c.x, y: c.y))
        let z = 16, tp = 512
        let w = PDFTileMath.world0(lat: ll.latitude, lon: ll.longitude)
        let n = pow(2.0, Double(z))
        let tx = Int((w.x * n / 256).rounded(.down)), ty = Int((w.y * n / 256).rounded(.down))
        let job = TileJob(z: z, x0: tx - 1, y0: ty, cols: 3, rows: 2)
        let plan = PDFTileWarp.plan(job: job, tilePx: tp, footprint: s.footprint, georef: s.georef)
        let out = try PDFTileRenderer.renderJob(job, tilePx: tp, plan: plan, footprint: s.footprint,
                                                source: plan.cells.count == 1 ? .vector(s.page) : .staged(s.page))
        XCTAssertEqual(out.count, 6)
        for t in job.tiles {
            guard case .image(let big)? = out[t] else { return XCTFail("\(t) not rendered") }
            XCTAssertEqual(big.width, tp)
            let single = try XCTUnwrap(try render(s, tile: t, tilePx: tp, path: .direct))
            let a = try pixels(big), b = try pixels(single)
            var diff = 0
            for y in stride(from: 0, to: tp, by: 3) {
                for x in stride(from: 0, to: tp, by: 3) {
                    let p = a.at(x, y), q = b.at(x, y)
                    if abs(p.r - q.r) > 96 || abs(p.a - q.a) > 96 { diff += 1 }
                }
            }
            // only antialiasing wobble along lines, the picture is the same
            XCTAssertLessThan(Double(diff) / Double((tp / 3) * (tp / 3)), 0.01, "\(t)")
        }
    }

    func testOutsideTilesAreEmptyWithoutRendering() throws {
        let s = try openSheet("geopdf/tacmap_grid_sf_iso.pdf", georef: F.georef(sheet: "sf_iso"))
        let t = TileIndex(z: 14, x: 2626 + 10, y: 6333)
        XCTAssertEqual(s.footprint.classify(t), .outside)
        let job = TileJob(z: 14, x0: t.x, y0: t.y, cols: 1, rows: 1)
        let plan = PDFTileWarp.plan(job: job, tilePx: 768, footprint: s.footprint, georef: s.georef)
        let out = try PDFTileRenderer.renderJob(job, tilePx: 768, plan: plan, footprint: s.footprint, source: .vector(s.page))
        guard case .empty? = out[t] else { return XCTFail("expected empty") }
    }
}

extension XCTestCase {
    /// whole page through the real base raster path, for tests that used to
    /// poke the old single image overlay
    func renderPDFBaseRaster(_ source: PDFMapSource, tilePx: Int = 512) throws -> PDFPageRaster {
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        let (_, page) = try PDFTileRenderer.openPage(url: source.url, pageIndex: source.georef.page)
        let ctx = try PDFRenderContext(url: source.url,
                                       identity: PDFDocumentIdentity(contentKey: source.contentKey ?? source.url.path,
                                                                     pageIndex: source.georef.page),
                                       georef: source.georef, pageBox: PDFTileRenderer.pageBox(page), tilePx: tilePx,
                                       guardToken: source.renderGuardToken, baseBudgetPx: PDFTileConstants.baseBudgetPx)
        return try PDFTileRenderer.renderBaseRaster(page: page, plan: ctx.basePlan, footprint: ctx.footprint)
    }
}
