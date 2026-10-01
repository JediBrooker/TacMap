import XCTest
import CoreGraphics
import CryptoKit
@testable import TacticalMaps

/// testdata/pdf_tile_render.json, the WP2 shared contract. Android's
/// SharedVectorsTest reads the same file. Every section gets asserted here
/// except markers / blank / ringTargets, which need the real renderer and
/// live in PDFTileRendererTests.
final class PDFTileRenderFixtureTests: XCTestCase {

    // MARK: - plumbing

    static func testdataURL(_ name: String) -> URL? {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            dir = dir.deletingLastPathComponent()
        }
        return nil
    }

    static func loadJSON(_ name: String) -> [String: Any] {
        guard let url = testdataURL(name), let data = try? Data(contentsOf: url),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return [:] }
        return obj
    }

    static let fx = loadJSON("pdf_tile_render.json")
    static let georefFx = loadJSON("pdf_georef.json")

    static func dbl(_ a: Any?) -> Double {
        if let n = a as? NSNumber { return n.doubleValue }
        return .nan
    }
    static func int(_ a: Any?) -> Int { (a as? NSNumber)?.intValue ?? Int.min }
    static func dbls(_ a: Any?) -> [Double] { (a as? [Any])?.map(dbl) ?? [] }
    static func pts(_ a: Any?) -> [PdfPagePoint] {
        ((a as? [[Any]]) ?? []).map { PdfPagePoint(x: dbl($0[0]), y: dbl($0[1])) }
    }
    static func rect(_ a: Any?) -> CGRect {
        let v = dbls(a)
        return CGRect(x: v[0], y: v[1], width: v[2] - v[0], height: v[3] - v[1])
    }

    static func decode<T: Decodable>(_ type: T.Type, _ o: Any?) -> T? {
        guard let o, let data = try? JSONSerialization.data(withJSONObject: o) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    /// the georef a WP1 parse gives for a fixture sheet, straight from pdf_georef.json
    static func georef(sheet id: String) -> PdfGeoreference? {
        if let syn = (fx["warpSyntheticGeorefs"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) {
            return build(syn)
        }
        guard let sheets = georefFx["sheets"] as? [[String: Any]],
              let s = sheets.first(where: { $0["id"] as? String == id }),
              let e = s["expected"] as? [String: Any] else { return nil }
        return build(e)
    }

    static func build(_ e: [String: Any]) -> PdfGeoreference? {
        guard let crs = decode(GeoCrs.self, e["crs"]), let datum = decode(GeoDatum.self, e["datum"]) else { return nil }
        let a = dbls(e["affine"])
        guard a.count == 6 else { return nil }
        return PdfGeoreference(crs: crs, datum: datum,
                               affine: PlaneAffine(a: a[0], b: a[1], c: a[2], d: a[3], e: a[4], f: a[5]),
                               crop: pts(e["crop"]), origin: .adobeVP)
    }

    static func pageBox(sheet id: String) -> CGRect? {
        if let syn = (fx["warpSyntheticGeorefs"] as? [[String: Any]])?.first(where: { $0["id"] as? String == id }) {
            return rect(syn["mediaBox"])
        }
        guard let zp = (fx["zoomPolicy"] as? [[String: Any]])?.first(where: { $0["sheet"] as? String == id }) else {
            return nil
        }
        return rect(zp["pageBox"])
    }

    static func footprint(sheet id: String) throws -> (PdfGeoreference, PDFFootprint) {
        let g = try XCTUnwrap(georef(sheet: id), "\(id) georef")
        let box = try XCTUnwrap(pageBox(sheet: id), "\(id) page box")
        return (g, try PDFFootprint(georef: g, pageBox: box))
    }

    func testFixtureLoads() {
        XCTAssertFalse(Self.fx.isEmpty, "testdata/pdf_tile_render.json missing")
        XCTAssertFalse(Self.georefFx.isEmpty, "testdata/pdf_georef.json missing")
    }

    // MARK: - constants

    func testConstantsMatch() throws {
        let c = try XCTUnwrap(Self.fx["constants"] as? [String: Any])
        XCTAssertEqual(Self.dbl(c["tileLogicalSize"]), PDFTileConstants.tileLogicalSize)
        let tp = try XCTUnwrap(c["tilePx"] as? [String: Any])
        XCTAssertEqual(Self.dbl(tp["densityCap"]), PDFTileConstants.densityCap)
        XCTAssertEqual(Self.int(tp["quantum"]), PDFTileConstants.tilePxQuantum)
        XCTAssertEqual(Self.dbl(c["cameraZoomMin"]), MapCamera.zoomLimits.lowerBound)
        XCTAssertEqual(Self.dbl(c["cameraZoomMax"]), MapCamera.zoomLimits.upperBound)
        XCTAssertEqual(Self.dbls(c["iosFlyToZoomRange"]), [MapExtentFit.minZoom, MapExtentFit.maxZoom])
        XCTAssertEqual(Self.dbl(c["maxUnderzoomLevels"]), TileMath.maxUnderzoomLevels)
        XCTAssertEqual(Self.dbl(c["visibleTileInflateUnits"]), PDFTileConstants.visibleTileInflate)
        XCTAssertEqual(Self.int(c["maxAncestorLevels"]), TileDrawPlanner.maxAncestorLevels)
        let cache = try XCTUnwrap(c["tileCache"] as? [String: Any])
        XCTAssertEqual(Self.int(cache["emptyEntryCostBytes"]), PDFTileConstants.emptyEntryCostBytes)
        let tiers = try XCTUnwrap(cache["tiers"] as? [[String: Any]])
        XCTAssertEqual(UInt64(Self.int(tiers[0]["below"])), PDFTileConstants.lowRamBelowBytes)
        XCTAssertEqual(Self.int(tiers[0]["bytes"]), PDFMemoryTier.lowCacheBytes)
        XCTAssertEqual(UInt64(Self.int(tiers[1]["below"])), PDFMemoryTier.midRamBelowBytes)
        XCTAssertEqual(Self.int(tiers[1]["bytes"]), PDFMemoryTier.midCacheBytes)
        XCTAssertEqual(Self.int(tiers[2]["bytes"]), PDFMemoryTier.highCacheBytes)
        XCTAssertEqual(Self.dbl(c["earthRadius"]), PDFTileConstants.earthRadius)
        XCTAssertEqual(Self.dbl(c["metresPerUnitZ0"]), PDFTileConstants.metresPerUnitZ0, accuracy: 1e-9)
        XCTAssertEqual(Self.int(c["footprintSegmentsPerEdge"]), PDFTileConstants.footprintSegmentsPerEdge)
        XCTAssertEqual(Self.dbl(c["clipMinAreaPt2"]), PDFTileConstants.clipMinAreaPt2)
        XCTAssertEqual(Self.dbl(c["clipDedupeEpsPt"]), PDFTileConstants.clipDedupeEpsPt)
        XCTAssertEqual(Self.dbl(c["jacobianStepPt"]), PDFTileConstants.jacobianStepPt)
        XCTAssertEqual(Self.dbl(c["detailOversample"]), PDFTileConstants.detailOversample)
        XCTAssertEqual(Self.dbl(c["detailZoomHysteresis"]), PDFTileConstants.detailZoomHysteresis)
        XCTAssertEqual(Self.int(c["maxZoomCap"]), PDFTileConstants.maxZoomCap)
        let base = try XCTUnwrap(c["baseRaster"] as? [String: Any])
        XCTAssertEqual(Self.int(base["budgetPx"]), PDFTileConstants.baseBudgetPx)
        XCTAssertEqual(Self.int(base["lowRamBudgetPx"]), PDFTileConstants.lowRamBaseBudgetPx)
        XCTAssertEqual(UInt64(Self.int(base["lowRamBelowBytes"])), PDFTileConstants.lowRamBelowBytes)
        XCTAssertEqual(Self.dbl(base["maxSide"]), PDFTileConstants.baseMaxSide)
        XCTAssertEqual(Self.dbl(base["maxScale"]), PDFTileConstants.baseMaxScale)
        XCTAssertEqual(Self.int(base["mipStopSide"]), PDFTileConstants.mipStopSide)
        XCTAssertEqual(Self.dbl(base["upsampleTolerance"]), PDFTileConstants.upsampleTolerance)
        let staged = try XCTUnwrap(c["staged"] as? [String: Any])
        XCTAssertEqual(Self.dbl(staged["oversample"]), PDFTileConstants.stagedOversample)
        XCTAssertEqual(Self.dbl(staged["maxPixelsFactor"]), PDFTileConstants.stagedMaxPixelsFactor)
        XCTAssertEqual(Self.dbl(staged["shrinkFactor"]), PDFTileConstants.stagedShrinkFactor)
        XCTAssertEqual(Self.dbl(staged["padPx"]), PDFTileConstants.stagedPadPx)
        let warp = try XCTUnwrap(c["warp"] as? [String: Any])
        XCTAssertEqual(Self.dbl(warp["maxErrorPx"]), PDFTileConstants.warpMaxErrorPx)
        XCTAssertEqual(Self.int(warp["baseDepth"]), PDFTileConstants.warpBaseDepth)
        XCTAssertEqual(Self.int(warp["minCellPx"]), PDFTileConstants.warpMinCellPx)
        XCTAssertEqual(Self.int(warp["rootPadPx"]), PDFTileConstants.warpRootPadPx)
        XCTAssertEqual(c["paperWhite"] as? String, "#FFFFFF")
        let s = try XCTUnwrap(c["scheduling"] as? [String: Any])
        XCTAssertEqual(Self.dbl(s["vectorSettleMs"]), PDFTileConstants.vectorSettleMs)
        XCTAssertEqual(Self.dbl(s["ewmaAlpha"]), PDFTileConstants.ewmaAlpha)
        XCTAssertEqual(Self.dbl(s["heavyThresholdMs"]), PDFTileConstants.heavyThresholdMs)
        XCTAssertEqual(Self.int(s["jobMaxTiles"]), PDFTileConstants.jobMaxTiles)
        XCTAssertEqual(Self.int(s["jobMaxSide"]), PDFTileConstants.jobMaxSide)
        XCTAssertEqual(Self.int(s["bakeBlockCols"]), PDFTileConstants.bakeBlockCols)
        XCTAssertEqual(Self.int(s["bakeBlockRows"]), PDFTileConstants.bakeBlockRows)
        XCTAssertEqual(Self.int(s["maxBakeJobsInFlight"]), PDFTileConstants.maxBakeJobsInFlight)
        XCTAssertEqual(Self.int(s["orphanCacheTiles"]), PDFTileConstants.orphanCacheTiles)
        XCTAssertEqual(UInt64(Self.int(s["iosVectorLanesMinRamBytes"])), PDFTileConstants.iosVectorLanesMinRamBytes)
        let st = try XCTUnwrap(c["status"] as? [String: Any])
        XCTAssertEqual(Self.dbl(st["preparingLabelDelayMs"]), PDFTileConstants.preparingLabelDelayMs)
        XCTAssertEqual(Self.int(st["renderErrorConsecutiveFailures"]), PDFTileConstants.renderErrorConsecutiveFailures)
        XCTAssertEqual(Self.int(st["blankSampleStride"]), PDFTileConstants.blankSampleStride)
        XCTAssertEqual(Self.int(st["blankMinChannelMax"]), PDFTileConstants.blankMinChannelMax)
        XCTAssertEqual(st["preparingColor"] as? String, PDFRenderStatusColors.preparingHex)
        XCTAssertEqual(st["failedColor"] as? String, PDFRenderStatusColors.failedHex)
        XCTAssertEqual(st["readyColor"] as? String, PDFRenderStatusColors.readyHex)
        XCTAssertEqual(st["failureReasons"] as? [String], PDFRenderFailure.allCases.map(\.rawValue))
        let hidden = try XCTUnwrap(c["hiddenBackground"] as? [String: Any])
        XCTAssertEqual(Self.dbl(hidden["iosWhite"]), Double(TileMapView.backgroundWhite))
        var white: CGFloat = -1
        TileMapView(camera: MapCamera(center: .init(latitude: 0, longitude: 0), zoom: 3, headingDegrees: 0,
                                      viewportSize: .zero), cacheBytes: 1 << 20)
            .backgroundColor?.getWhite(&white, alpha: nil)
        XCTAssertEqual(Double(white), Self.dbl(hidden["iosWhite"]), accuracy: 1e-6, "what the view actually paints")
        let vis = try XCTUnwrap(c["visibilityKey"] as? [String: Any])
        XCTAssertEqual(vis["ios"] as? String, LayerVisibility.importedMapVisibleKey)
        XCTAssertEqual(vis["default"] as? Bool, true)
        let bake = try XCTUnwrap(c["bake"] as? [String: Any])
        XCTAssertEqual(Self.int(bake["maxTiles"]), PDFTileConstants.bakeMaxTiles)
        XCTAssertEqual(Self.dbl(bake["freeSpaceFactor"]), PDFTileConstants.bakeFreeSpaceFactor)
        XCTAssertEqual(Self.dbl(bake["bytesSafety"]), PDFTileConstants.bakeBytesSafety)
        XCTAssertEqual(Self.int(bake["sampleTiles"]), PDFTileConstants.bakeSampleTiles)
        XCTAssertEqual(Self.int(bake["commitEvery"]), PDFTileConstants.bakeCommitEvery)
        XCTAssertEqual(Self.int(bake["rendererVersion"]), PDFTileRenderer.version)
        XCTAssertEqual(bake["bakeKeyPrefix"] as? String, PDFTileConstants.bakeKeyPrefix)
        XCTAssertEqual(Self.dbl(bake["iosJpeg2000Quality"]), PDFTileConstants.bakeJpeg2000Quality)
        XCTAssertEqual(bake["iosMbtilesFormat"] as? String, PDFTileConstants.bakeMbtilesFormat)
        XCTAssertEqual(Self.dbl(bake["psnrGateDb"]), PDFTileConstants.bakePsnrGateDb)
        XCTAssertEqual(bake["mbtilesName"] as? String, PDFTileConstants.bakeMbtilesName)
        XCTAssertEqual(Self.int(bake["minZoom"]), PDFTileConstants.bakeMinZoom)
        let fb = try XCTUnwrap((Self.fx["bakeEstimate"] as? [String: Any])?["fallback"] as? [String: Any])
        XCTAssertEqual(Self.dbl(fb["jobMs"]), PDFTileConstants.bakeFallbackJobMs)
        XCTAssertEqual(Self.dbl(fb["encodeMs"]), PDFTileConstants.bakeFallbackEncodeMs)
        XCTAssertEqual(Self.int(fb["tileBytes"]), PDFTileConstants.bakeFallbackTileBytes)
        XCTAssertEqual((bake["candidateOffsets"] as? [Int]), PDFTileConstants.bakeCandidateOffsets)
        XCTAssertEqual(Self.int(bake["defaultOffset"]), PDFTileConstants.bakeDefaultOffset)
        let guardC = try XCTUnwrap(c["crashGuard"] as? [String: Any])
        XCTAssertEqual(Self.int(guardC["maxVerifiedTokens"]), PDFTileConstants.guardMaxVerifiedTokens)
        XCTAssertEqual(Self.int(guardC["fileVersion"]), PDFTileConstants.guardFileVersion)

        for m in try XCTUnwrap(c["memoryCases"] as? [[String: Any]]) {
            let ram = UInt64((m["physicalMemory"] as! NSNumber).uint64Value)
            let low = m["lowRamDevice"] as? Bool ?? false
            XCTAssertEqual(PDFMemoryTier.tileCacheBytes(physicalMemory: ram, lowRamDevice: low), Self.int(m["tileCacheBytes"]), "\(ram) \(low)")
            XCTAssertEqual(PDFMemoryTier.baseBudgetPx(physicalMemory: ram, lowRamDevice: low), Self.int(m["baseBudgetPx"]), "\(ram) \(low)")
            XCTAssertEqual(PDFMemoryTier.iosVectorLanes(physicalMemory: ram), Self.int(m["iosVectorLanes"]), "\(ram)")
        }
    }

    func testTilePxFromDensity() throws {
        for row in try XCTUnwrap(Self.fx["tilePx"] as? [[String: Any]]) {
            XCTAssertEqual(PDFTileMath.tilePx(density: Self.dbl(row["density"])), Self.int(row["tilePx"]), "\(row)")
        }
    }

    // MARK: - zoom policy + base plan

    func testZoomPolicyPerSheet() throws {
        let sheets = try XCTUnwrap(Self.fx["zoomPolicy"] as? [[String: Any]])
        XCTAssertEqual(sheets.count, 8)
        for zp in sheets {
            try assertZoomPolicy(zp, georef: try XCTUnwrap(Self.georef(sheet: zp["sheet"] as! String)))
        }
    }

    // MARK: - coverage + bake options

    private static func runs(_ tiles: [TileIndex]) -> [[Int]] {
        var out: [[Int]] = []
        var rows: [Int: [Int]] = [:]
        for t in tiles { rows[t.y, default: []].append(t.x) }
        for y in rows.keys.sorted() {
            let xs = rows[y]!.sorted()
            var start = xs[0], prev = xs[0]
            for x in xs.dropFirst() {
                if x != prev + 1 { out.append([y, start, prev]); start = x }
                prev = x
            }
            out.append([y, start, prev])
        }
        return out
    }

    func testCoveragePerSheetAndZoom() throws {
        let sheets = try XCTUnwrap(Self.fx["coverage"] as? [[String: Any]])
        XCTAssertEqual(sheets.count, 8)
        for s in sheets {
            let id = s["sheet"] as! String
            let (_, fp) = try Self.footprint(sheet: id)
            for lv in s["levels"] as? [[String: Any]] ?? [] {
                let z = Self.int(lv["z"])
                let got = try XCTUnwrap(fp.tiles(z: z))
                let tiles = got.map(\.tile)
                XCTAssertEqual(tiles.count, Self.int(lv["count"]), "\(id) z\(z) count")
                XCTAssertEqual(got.filter { $0.coverage == .inside }.count, Self.int(lv["insideCount"]), "\(id) z\(z) inside")
                XCTAssertEqual(got.filter { $0.coverage == .edge }.count, Self.int(lv["edgeCount"]), "\(id) z\(z) edge")
                let lines = tiles.map { "\(z)/\($0.x)/\($0.y)" }.joined(separator: "\n")
                let sha = SHA256.hash(data: Data(lines.utf8)).map { String(format: "%02x", $0) }.joined()
                XCTAssertEqual(sha, lv["sha256"] as? String, "\(id) z\(z) sha")
                XCTAssertEqual(Self.runs(tiles), lv["runs"] as? [[Int]], "\(id) z\(z) runs")
                let inside = got.filter { $0.coverage == .inside }.map(\.tile)
                XCTAssertEqual(inside.isEmpty ? [] : Self.runs(inside), lv["insideRuns"] as? [[Int]], "\(id) z\(z) inside runs")
                if let list = lv["tiles"] as? [[Int]] {
                    XCTAssertEqual(tiles.map { [$0.x, $0.y] }, list, "\(id) z\(z) tiles")
                    // the single tile classifier the view uses agrees, incl a ring outside
                    var ring = Set<TileIndex>()
                    for t in tiles {
                        for dx in -1...1 { for dy in -1...1 { ring.insert(TileIndex(z: z, x: t.x + dx, y: t.y + dy)) } }
                    }
                    let byTile = Dictionary(uniqueKeysWithValues: got.map { ($0.tile, $0.coverage) })
                    for t in ring {
                        XCTAssertEqual(fp.classify(t), byTile[t] ?? .outside, "\(id) classify \(t)")
                    }
                }
            }
        }
    }

    func testBakeOptions() throws {
        for b in try XCTUnwrap(Self.fx["bakeOptions"] as? [[String: Any]]) {
            let id = b["sheet"] as! String
            let (g, fp) = try Self.footprint(sheet: id)
            let policy = try PDFZoomPolicy(georef: g, footprint: fp)
            XCTAssertEqual(policy.detailZoom, Self.int(b["detailZoom"]), id)
            let opts = PDFBakePlan.options(detailZoom: policy.detailZoom, footprint: fp)
            XCTAssertEqual(opts.map(\.maxZoom), b["options"] as? [Int], "\(id) options")
            for c in b["candidates"] as? [[String: Any]] ?? [] {
                let m = Self.int(c["maxZoom"])
                let kept = (c["kept"] as? Bool) == true
                XCTAssertEqual(opts.contains { $0.maxZoom == m }, kept, "\(id) z\(m) kept")
                if kept {
                    XCTAssertEqual(opts.first { $0.maxZoom == m }?.tiles, Self.int(c["tiles"]), "\(id) tiles")
                }
                // dropped ones too: the uncapped count is what the cap compared
                let all = try (0...m).reduce(0) { $0 + (try XCTUnwrap(fp.tiles(z: $1))).count }
                XCTAssertEqual(all, Self.int(c["tiles"]), "\(id) z\(m) uncapped tiles")
                XCTAssertEqual(all <= PDFTileConstants.bakeMaxTiles, kept, "\(id) z\(m) cap")
            }
            let def = PDFBakePlan.defaultOption(opts, detailZoom: policy.detailZoom)
            XCTAssertEqual(def?.maxZoom, (b["default"] as? NSNumber)?.intValue, "\(id) default")
            XCTAssertEqual(opts.isEmpty, b["tooLarge"] as? Bool, "\(id) tooLarge")
        }
    }

    // MARK: - warp planner

    func testWarpPlans() throws {
        let entries = try XCTUnwrap(Self.fx["warp"] as? [[String: Any]])
        XCTAssertGreaterThan(entries.count, 100)
        var fps: [String: (PdfGeoreference, PDFFootprint)] = [:]
        let pageTol = tolerance("warpPagePt"), errTol = tolerance("warpErrorPx")
        for e in entries {
            let id = e["sheet"] as! String
            if fps[id] == nil { fps[id] = try Self.footprint(sheet: id) }
            let (g, fp) = fps[id]!
            let j = try XCTUnwrap(e["job"] as? [String: Any])
            let job = TileJob(z: Self.int(j["z"]), x0: Self.int(j["x0"]), y0: Self.int(j["y0"]),
                              cols: Self.int(j["cols"]), rows: Self.int(j["rows"]))
            let tilePx = Self.int(e["tilePx"])
            let ctx = "\(id) \(e["label"] ?? "") z\(job.z) \(job.x0)/\(job.y0) \(job.cols)x\(job.rows) @\(tilePx)"
            let plan = PDFTileWarp.plan(job: job, tilePx: tilePx, footprint: fp, georef: g)

            XCTAssertEqual(job.tiles.map { fp.classify($0).rawValue }, e["tileCoverage"] as? [String], "\(ctx) tile coverage")
            XCTAssertEqual(plan.maxDepth, Self.int(e["maxDepth"]), "\(ctx) maxDepth")
            if let root = e["root"] as? [Int] {
                XCTAssertEqual(plan.root.map { [$0.l, $0.t, $0.r, $0.b] }, root, "\(ctx) root")
            } else {
                XCTAssertNil(plan.root, "\(ctx) root")
            }
            XCTAssertEqual(plan.leaves.count, Self.int(e["leafCount"]), "\(ctx) leafCount")
            XCTAssertEqual(plan.cells.count, Self.int(e["cellCount"]), "\(ctx) cellCount")
            XCTAssertEqual(plan.cells.filter { $0.coverage == .inside }.count, Self.int(e["insideCells"]), "\(ctx) inside")
            XCTAssertEqual(plan.cells.filter { $0.coverage == .edge }.count, Self.int(e["edgeCells"]), "\(ctx) edge")
            XCTAssertEqual(plan.leaves.count - plan.cells.count, Self.int(e["outsideLeaves"]), "\(ctx) outside")
            XCTAssertEqual(plan.dropped, Self.int(e["droppedCells"]), "\(ctx) dropped")
            XCTAssertEqual(plan.maxErrorPx, Self.dbl(e["maxErrorPx"]), accuracy: errTol, "\(ctx) maxError")
            XCTAssertEqual(plan.errorBoundMet, e["errorBoundMet"] as? Bool, "\(ctx) errorBoundMet")
            if (e["errorBoundMet"] as? Bool) == true {
                XCTAssertLessThanOrEqual(plan.maxErrorPx, PDFTileConstants.warpMaxErrorPx, "\(ctx) error bound")
            }
            let req = Self.dbl(e["requiredPxPerPt"])
            XCTAssertTrue(rel(plan.requiredPxPerPt, req, tolerance("requiredPxPerPtRel"), floor: 1e-12),
                          "\(ctx) required \(plan.requiredPxPerPt) vs \(req)")
            guard let cells = e["cells"] as? [[String: Any]] else { continue }
            XCTAssertEqual(cells.count, plan.leaves.count, "\(ctx) listed cells")
            for (cell, want) in zip(plan.leaves, cells) {
                XCTAssertEqual([cell.l, cell.t, cell.r, cell.b], want["rect"] as? [Int], "\(ctx) rect")
                for (got, w) in zip(cell.pageToPx, Self.dbls(want["pageToPx"])) {
                    XCTAssertTrue(rel(got, w, tolerance("warpPageToPxRel"), floor: tolerance("warpPageToPxAbsFloor")),
                                  "\(ctx) pageToPx \(cell.pageToPx)")
                }
                XCTAssertEqual(cell.errorPx, Self.dbl(want["errorPx"]), accuracy: errTol, "\(ctx) cell error")
                XCTAssertEqual(cell.coverage.rawValue, want["coverage"] as? String, "\(ctx) cell coverage")
                for (k, i) in [("pageTL", 0), ("pageTR", 1), ("pageBR", 2), ("pageBL", 3)] {
                    let p = Self.dbls(want[k])
                    XCTAssertEqual(cell.quad[i].x, p[0], accuracy: pageTol, "\(ctx) \(k)")
                    XCTAssertEqual(cell.quad[i].y, p[1], accuracy: pageTol, "\(ctx) \(k)")
                }
            }
        }
    }

    func testWarpCountsMatchContractTable() throws {
        // contract D: z>=14 -> 1 cell, z12-13 -> 4 on sheets this size, 1x1 at 768
        let entries = try XCTUnwrap(Self.fx["warp"] as? [[String: Any]])
        for e in entries where (e["kind"] as? String) == "inside" && Self.int(e["tilePx"]) == 768 {
            let j = e["job"] as! [String: Any]
            guard Self.int(j["cols"]) == 1, (e["sheet"] as? String) != "wide_tm_1m" else { continue }
            if Self.int(j["z"]) >= 14 { XCTAssertEqual(Self.int(e["cellCount"]), 1, "\(e["sheet"]!) z\(j["z"]!)") }
        }
    }

    // MARK: - job formation

    func testJobFormation() throws {
        for c in try XCTUnwrap(Self.fx["jobFormation"] as? [[String: Any]]) {
            let want = try XCTUnwrap(c["job"] as? [String: Any])
            let expected = TileJob(z: Self.int(want["z"]), x0: Self.int(want["x0"]), y0: Self.int(want["y0"]),
                                   cols: Self.int(want["cols"]), rows: Self.int(want["rows"]))
            let heavy = c["heavy"] as? Bool ?? false
            let got: TileJob
            if (c["mode"] as? String) == "bake" {
                let t = c["tile"] as! [Int]
                got = PDFJobFormation.bakeBlock(for: TileIndex(z: t[0], x: t[1], y: t[2]), heavy: heavy)
            } else {
                let s = c["seed"] as! [Int]
                let seed = TileIndex(z: s[0], x: s[1], y: s[2])
                let pending = (c["pending"] as? [[String: Any]] ?? []).compactMap { p -> TileIndex? in
                    guard Self.int(p["z"]) == seed.z, (p["band"] as? String) == "visible" else { return nil }
                    return TileIndex(z: Self.int(p["z"]), x: Self.int(p["x"]), y: Self.int(p["y"]))
                }
                got = heavy ? PDFJobFormation.grow(seed: seed, pendingVisible: Set(pending))
                            : TileJob(z: seed.z, x0: seed.x, y0: seed.y, cols: 1, rows: 1)
            }
            XCTAssertEqual(got, expected, "\(c["label"] ?? "")")
        }
    }

    // MARK: - draw plan

    func testDrawPlans() throws {
        for c in try XCTUnwrap(Self.fx["drawPlan"] as? [[String: Any]]) {
            let label = c["label"] as? String ?? ""
            let visible = (c["visible"] as? [[String: Any]] ?? []).map {
                VisibleTile(index: TileIndex(z: Self.int($0["z"]), x: Self.int($0["x"]), y: Self.int($0["y"])),
                            col: Self.int($0["col"]), row: Self.int($0["row"]))
            }
            var cache: [TileIndex: TileCacheState] = [:]
            for e in c["cache"] as? [[String: Any]] ?? [] {
                let t = e["tile"] as! [Int]
                cache[TileIndex(z: t[0], x: t[1], y: t[2])] = (e["state"] as? String) == "empty" ? .empty : .image
            }
            let fz = (c["fallbackZoom"] as? NSNumber)?.intValue
            let plan = TileDrawPlanner.plan(visible: visible, state: { cache[$0] ?? .missing }, fallbackZoom: fz)
            let items = c["items"] as? [[String: Any]] ?? []
            XCTAssertEqual(plan.items.count, items.count, "\(label) item count")
            for (got, want) in zip(plan.items, items) {
                XCTAssertEqual(got.kind.rawValue, want["kind"] as? String, label)
                XCTAssertEqual([got.source.z, got.source.x, got.source.y], want["source"] as? [Int], label)
                let d = want["dest"] as! [String: Any]
                XCTAssertEqual(got.dest, VisibleTile(index: TileIndex(z: Self.int(d["z"]), x: Self.int(d["x"]), y: Self.int(d["y"])),
                                                     col: Self.int(d["col"]), row: Self.int(d["row"])), label)
                XCTAssertEqual([got.unitRect.minX, got.unitRect.minY, got.unitRect.width, got.unitRect.height].map(Double.init),
                               Self.dbls(want["unitRect"]), label)
                XCTAssertEqual([got.destRect.minX, got.destRect.minY, got.destRect.width, got.destRect.height].map(Double.init),
                               Self.dbls(want["destRect"]), label)
                XCTAssertEqual(got.order, Self.int(want["order"]), label)
            }
            let reqs = (c["requests"] as? [[Int]] ?? []).map { TileIndex(z: $0[0], x: $0[1], y: $0[2]) }
            XCTAssertEqual(plan.requests, reqs, "\(label) requests")
        }
    }

    // MARK: - crash guard

    func testCrashGuardReducer() throws {
        for c in try XCTUnwrap(Self.fx["crashGuard"] as? [[String: Any]]) {
            let label = c["label"] as? String ?? ""
            var s = PDFRenderGuardState()
            if let initial = c["initialState"] as? [String: Any] {
                // legacy file on disk. OD-F8: the LRU order is the member order of
                // "verified" (oldest first), so the file gets those members in
                // initialVerifiedOrder. JSONSerialization would scramble them
                let order = c["initialVerifiedOrder"] as? [String] ?? []
                let data = try Self.guardFile(initial, verifiedOrder: order)
                s = try XCTUnwrap(PDFRenderGuardState.decode(data), "\(label) initial state")
                XCTAssertEqual(s.verified.map(\.token), order, "\(label) initial order")
            }
            for (i, step) in (c["steps"] as? [[String: Any]] ?? []).enumerated() {
                let ev = step["event"] as! [String: Any]
                let ctx = "\(label) step \(i) \(ev["op"] ?? "")"
                var result: [String: Any] = [:]
                switch ev["op"] as? String {
                case "arm":
                    let kind = PDFGuardKind(rawValue: ev["kind"] as! String)!
                    let armed = PDFRenderGuardReducer.arm(&s, kind: kind, token: ev["token"] as! String,
                                                          foreground: ev["foreground"] as? Bool ?? true,
                                                          op: ev["opKey"] as? String)
                    result = ["armed": armed]
                case "complete":
                    PDFRenderGuardReducer.complete(&s, kind: PDFGuardKind(rawValue: ev["kind"] as! String)!,
                                                   token: ev["token"] as! String)
                case "disarmBackground":
                    PDFRenderGuardReducer.disarmBackground(&s)
                case "launch":
                    let out = PDFRenderGuardReducer.launch(&s, restoredToken: ev["restoredToken"] as? String)
                    switch out.decision {
                    case .none: result = ["decision": "none"]
                    case .suppress: result = ["decision": "suppress"]
                    case .importInterrupted(let op):
                        result = ["decision": "importInterrupted"]
                        if let op { result["op"] = op }
                    }
                    result["bakeInterrupted"] = out.bakeInterrupted
                case "resolve":
                    PDFRenderGuardReducer.resolve(&s, PDFSuspectResolution(rawValue: ev["choice"] as! String)!)
                default:
                    XCTFail("\(ctx): unknown op")
                }
                XCTAssertEqual(result as NSDictionary, (step["result"] as? [String: Any] ?? [:]) as NSDictionary, "\(ctx) result")
                XCTAssertEqual(s.jsonObject() as NSDictionary, step["state"] as? NSDictionary, "\(ctx) state")
                XCTAssertEqual(s.verified.map(\.token), step["verifiedOrder"] as? [String], "\(ctx) verified order")
                // and it survives the disk format, member order and all, with no extra keys
                let bytes = try XCTUnwrap(s.encoded())
                let round = try XCTUnwrap(PDFRenderGuardState.decode(bytes), ctx)
                XCTAssertEqual(round, s, "\(ctx) round trip")
                XCTAssertEqual(round.verified.map(\.token), step["verifiedOrder"] as? [String], "\(ctx) order on disk")
                let onDisk = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
                XCTAssertEqual(Set(onDisk.keys), ["v", "inProgress", "bakeInProgress", "suspect", "verified"], "\(ctx) K1 keys only")
            }
        }
    }

    /// a guard file with "verified" written in the given member order
    static func guardFile(_ state: [String: Any], verifiedOrder: [String], extra: [String: Any] = [:]) throws -> Data {
        func frag(_ v: Any) throws -> String {
            String(data: try JSONSerialization.data(withJSONObject: v, options: [.fragmentsAllowed, .sortedKeys]),
                   encoding: .utf8)!
        }
        let ver = state["verified"] as? [String: Any] ?? [:]
        var parts: [String] = []
        for (k, v) in state where k != "verified" { parts.append(try frag(k) + ":" + frag(v)) }
        for (k, v) in extra { parts.append(try frag(k) + ":" + frag(v)) }
        let members = try verifiedOrder.map { try frag($0) + ":" + frag(ver[$0] ?? []) }
        parts.append("\"verified\":{" + members.joined(separator: ",") + "}")
        return Data(("{" + parts.joined(separator: ",") + "}").utf8)
    }

    /// OD-F8: an older iOS build wrote an "order" array, its ignored now and the
    /// member order wins. Nothing writes it any more
    func testGuardFileOrderIsTheVerifiedMemberOrder() throws {
        let a = "00000000-0000-4000-8000-00000000000A", b = "00000000-0000-4000-8000-00000000000B",
            c = "00000000-0000-4000-8000-00000000000C"
        let state: [String: Any] = ["v": 1, "inProgress": NSNull(), "suspect": NSNull(),
                                    "verified": [a: ["base"], b: ["base", "vector"], c: ["vector"]]]
        // members c, a, b with a stale order array saying the opposite
        let legacy = try Self.guardFile(state, verifiedOrder: [c, a, b], extra: ["order": [b, a, c]])
        let s = try XCTUnwrap(PDFRenderGuardState.decode(legacy))
        XCTAssertEqual(s.verified.map(\.token), [c, a, b], "member order, not the old order key")
        let text = try XCTUnwrap(String(data: try XCTUnwrap(s.encoded()), encoding: .utf8))
        XCTAssertFalse(text.contains("\"order\""))
        let ia = try XCTUnwrap(text.range(of: a)).lowerBound, ib = try XCTUnwrap(text.range(of: b)).lowerBound,
            ic = try XCTUnwrap(text.range(of: c)).lowerBound
        XCTAssertTrue(ic < ia && ia < ib, "written oldest first")
        // and the durable store keeps it across a relaunch
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("guard-order-\(UUID())")
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        try legacy.write(to: url)
        let g = PDFRenderGuard(url: url)
        g.complete(kind: .vector, token: a)
        XCTAssertEqual(PDFRenderGuard(url: url).snapshot.verified.map(\.token), [c, b, a])
    }

    // MARK: - bake job formation (E2)

    func testBakeJobFormation() throws {
        let cases = try XCTUnwrap(Self.fx["bakeJobFormation"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 7)
        for c in cases {
            let label = c["label"] as? String ?? ""
            let bmz = (c["baseMaxZoom"] as? NSNumber)?.intValue ?? -1
            for lv in c["levels"] as? [[String: Any]] ?? [] {
                let z = Self.int(lv["z"])
                let tiles = (lv["tiles"] as? [[Int]] ?? []).map { TileIndex(z: z, x: $0[0], y: $0[1]) }
                // a fake estimator that flips after the first ask: the level must not care
                var asks = 0
                let start = lv["heavyAtLevelStart"] as? Bool ?? false
                let after = lv["heavyAfterFirstJob"] as? Bool ?? start
                let got = PDFJobFormation.bakeLevel(z: z, tiles: tiles, baseMaxZoom: bmz) {
                    asks += 1
                    return asks == 1 ? start : after
                }
                let ctx = "\(label) z\(z)"
                XCTAssertLessThanOrEqual(asks, 1, "\(ctx) heavy read once")
                XCTAssertEqual(got.path.rawValue, lv["path"] as? String, "\(ctx) path")
                XCTAssertEqual(got.heavyUsed, lv["heavyUsed"] as? Bool, "\(ctx) heavyUsed")
                let want = (lv["jobs"] as? [[String: Any]] ?? []).map {
                    [Self.int($0["z"]), Self.int($0["x0"]), Self.int($0["y0"]), Self.int($0["cols"]), Self.int($0["rows"]),
                     Self.int($0["wanted"])]
                }
                XCTAssertEqual(got.jobs.map { [$0.job.z, $0.job.x0, $0.job.y0, $0.job.cols, $0.job.rows, $0.wanted] }, want,
                               "\(ctx) jobs")
            }
        }
    }

    // MARK: - live failure accounting (G2 + r1 R1/R2)

    struct FixtureError: Error, CustomStringConvertible { let description: String }

    static let jobPaths: Set<String> = ["vector", "staged", "rasterSample", "baseRaster"]

    /// D6: only the literal "other" is an unclassified error. an unknown reason
    /// or path is a broken fixture or a broken loader, never a quiet nil
    static func failureEvent(_ ev: [String: Any]) throws -> PDFLiveAccounting.Event {
        func reason(_ r: Any?, otherAllowed: Bool = true) throws -> PDFRenderFailure? {
            guard let s = r as? String else { throw FixtureError(description: "no reason in \(ev)") }
            if s == "other", otherAllowed { return nil }
            guard let f = PDFRenderFailure(rawValue: s) else { throw FixtureError(description: "unknown reason \(s)") }
            return f
        }
        func path() throws {
            guard let p = ev["path"] as? String, jobPaths.contains(p) else {
                throw FixtureError(description: "bad path in \(ev)")
            }
        }
        switch ev["op"] as? String {
        case "jobOk": try path(); return .job(.jobOk)
        case "jobFailed": try path(); return .job(.jobFailed(try reason(ev["reason"])))
        case "jobCancelled": return .job(.jobCancelled)
        case "bakeJobOk", "bakeJobFailed": return .job(.bakeJob)
        case "documentFailure":
            return .job(.documentFailure(try XCTUnwrap(try reason(ev["reason"], otherAllowed: false))))
        case "blankVerdict": return .job(.blankVerdict)
        case "retry": return .job(.retry)
        case "wanted":
            guard let z = (ev["z"] as? NSNumber)?.intValue else { throw FixtureError(description: "wanted without z") }
            return .wanted(z: z)
        case "baseDone":
            switch ev["result"] as? String {
            case "ok": return .baseDone(.ok)
            case "blank": return .baseDone(.blank)
            default: return .baseDone(.failed(try reason(ev["result"])))
            }
        default:
            throw FixtureError(description: "unknown op \(ev["op"] ?? "nil")")
        }
    }

    func testFailureAccounting() throws {
        let cases = try XCTUnwrap(Self.fx["failureAccounting"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 22)
        var baseCases = 0, ewmaSteps = 0
        for c in cases {
            let label = c["label"] as? String ?? ""
            let bmz = (c["baseMaxZoom"] as? NSNumber)?.intValue
            if bmz != nil { baseCases += 1 }
            var t = PDFLiveAccounting(baseMaxZoom: bmz ?? -1)
            for (i, step) in (c["steps"] as? [[String: Any]] ?? []).enumerated() {
                let ev = try XCTUnwrap(step["event"] as? [String: Any])
                let ctx = "\(label) step \(i) \(ev)"
                let fx = t.apply(try Self.failureEvent(ev))
                let want = try XCTUnwrap(step["state"] as? [String: Any])
                XCTAssertEqual(t.failed?.rawValue, want["failed"] as? String, "\(ctx) failed")
                XCTAssertEqual(t.consecutiveFailures, Self.int(want["consecutiveFailures"]), "\(ctx) run")
                if let base = step["base"] as? [String: Any] {
                    XCTAssertEqual(t.baseStatus.rawValue, base["status"] as? String, "\(ctx) base status")
                    XCTAssertEqual(t.baseAttempts, Self.int(base["attempts"]), "\(ctx) base attempts")
                    XCTAssertEqual(fx.startsBaseAttempt, step["startsBaseAttempt"] as? Bool, "\(ctx) startsBaseAttempt")
                    XCTAssertEqual(fx.replan, step["replan"] as? Bool, "\(ctx) replan")
                } else {
                    XCTAssertNil(bmz, "\(ctx): base case step without base")
                }
                if let feeds = step["feedsEwma"] as? Bool {
                    ewmaSteps += 1
                    XCTAssertEqual(try feedsEwma(ev), feeds, "\(ctx) feedsEwma")
                }
            }
        }
        XCTAssertGreaterThanOrEqual(baseCases, 5)
        XCTAssertGreaterThan(ewmaSteps, 10)
    }

    /// E1 + R2 through the real scheduler: run the step's job (vector/staged
    /// on a lane, raster samples dont touch it), drop every waiter first when
    /// waiters is 0, and see whether the estimator took a sample
    func feedsEwma(_ ev: [String: Any]) throws -> Bool {
        let clock = PDFRenderSchedulerTests.FakeClock()
        var held: [(work: PDFLaneWork, done: (Result<PDFLaneOutput, Error>) -> Void)] = []
        let s = PDFRenderScheduler(lanes: 1, clock: clock.clock) { _, w, d in held.append((w, d)) }
        let g = try XCTUnwrap(Self.georef(sheet: "sf_iso"))
        let ctx = try PDFRenderContext(url: URL(fileURLWithPath: "/dev/null"),
                                       identity: PDFDocumentIdentity(contentKey: "sha256:ewma", pageIndex: 0),
                                       georef: g, pageBox: try XCTUnwrap(Self.pageBox(sheet: "sf_iso")), tilePx: 512,
                                       guardToken: UUID().uuidString, baseBudgetPx: PDFTileConstants.baseBudgetPx)
        let t = TileIndex(z: 16, x: 10480, y: 25330)
        s.setViewport(centreX: Double(t.x) * 256, centreY: Double(t.y) * 256, tileZoom: t.z)
        var reported = 0
        let ticket = s.requestTile(t, ctx: ctx, band: .visible, report: { _ in reported += 1 }) { _ in }
        let op = ev["op"] as? String
        if op == "jobCancelled" {
            // cancelled inside the settle, before it ever started
            ticket.cancel()
            clock.advance(1)
            XCTAssertTrue(held.isEmpty, "a cancelled job never starts")
            XCTAssertEqual(reported, 0, "and is never counted")
            return s.ewmaMs != nil
        }
        let path = ev["path"] as? String
        guard path == "vector" || path == "staged" else { return false }
        clock.advance(1)
        let job = try XCTUnwrap(held.first, "job started")
        if (ev["waiters"] as? NSNumber)?.intValue == 0 { ticket.cancel() }
        if op == "jobFailed" {
            job.done(.failure(PDFRenderFailure.renderError))
        } else {
            let px = PDFRenderSchedulerTests.pixel()
            let entry: TileCacheEntry = (ev["allEmpty"] as? Bool) == true ? .empty : .image(px)
            job.done(.success(.tiles([t: entry], ms: 80)))
        }
        XCTAssertEqual(reported, 1, "a started job is counted once whoever waits (R2)")
        return s.ewmaMs != nil
    }

    // MARK: - staged region (C, G3, r1 R5 + D5)

    static func special(_ a: Any?) -> Double {
        if let s = a as? String {
            switch s {
            case "NaN": return .nan
            case "Infinity": return .infinity
            case "-Infinity": return -.infinity
            default: return .nan
            }
        }
        return dbl(a)
    }

    func testStagedRegion() throws {
        let cases = try XCTUnwrap(Self.fx["stagedRegion"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 25)
        let ptTol = tolerance("stagedRegionPt"), dTol = tolerance("stagedDensityRel")
        XCTAssertFalse(ptTol.isNaN || dTol.isNaN, "tolerance keys")
        var regions = 0, errors = 0, rebuilt = 0
        for c in cases where (c["kind"] as? String) == "region" {
            regions += 1
            let label = c["label"] as? String ?? ""
            let box = c["cellBbox"] as? [Any]
            let got = PDFStagedRegion.compute(cellBBox: box.map { $0.map(Self.dbl) }, required: Self.special(c["requiredPxPerPt"]),
                                              clipBBox: Self.dbls(c["clipBBox"]), jobPixels: Self.int(c["jobPixels"]))
            XCTAssertEqual(Self.int(c["jobPixels"]), Self.int(c["cols"]) * Self.int(c["rows"]) * Self.int(c["tilePx"]) * Self.int(c["tilePx"]), label)
            let x = try XCTUnwrap(c["expected"] as? [String: Any])
            if (x["result"] as? String) == "renderError" {
                errors += 1
                XCTAssertNil(got, "\(label): renderError")
                continue
            }
            XCTAssertEqual(x["result"] as? String, "ok", label)
            let r = try XCTUnwrap(got, label)
            XCTAssertEqual(r.pad, Self.dbl(x["pad"]), accuracy: ptTol, "\(label) pad")
            for (a, b) in zip(r.region, Self.dbls(x["region"])) { XCTAssertEqual(a, b, accuracy: ptTol, "\(label) region") }
            XCTAssertEqual(r.rw, Self.dbl(x["rw"]), accuracy: ptTol, "\(label) rw")
            XCTAssertEqual(r.rh, Self.dbl(x["rh"]), accuracy: ptTol, "\(label) rh")
            XCTAssertTrue(rel(r.cap, Self.dbl(x["cap"]), dTol), "\(label) cap \(r.cap)")
            XCTAssertEqual(r.dFrom.rawValue, x["dFrom"] as? String, "\(label) dFrom")
            XCTAssertTrue(rel(r.d0, Self.dbl(x["d0"]), dTol), "\(label) d0")
            XCTAssertEqual(r.W0, Self.int(x["W0"]), "\(label) W0")
            XCTAssertEqual(r.H0, Self.int(x["H0"]), "\(label) H0")
            XCTAssertEqual(r.maxPx, Self.int(x["maxPx"]), "\(label) maxPx")
            XCTAssertEqual(r.shrinkSteps, Self.int(x["shrinkSteps"]), "\(label) shrinkSteps")
            XCTAssertTrue(rel(r.d, Self.dbl(x["d"]), dTol), "\(label) d")
            XCTAssertEqual(r.W, Self.int(x["W"]), "\(label) W")
            XCTAssertEqual(r.H, Self.int(x["H"]), "\(label) H")
            XCTAssertLessThanOrEqual(r.W * r.H, r.maxPx, "\(label) never over 2x job pixels")
            // and our own planner lands on the same inputs, through stagedPlan
            if let w = c["fromWarp"] as? [String: Any] {
                rebuilt += 1
                let id = w["sheet"] as! String
                let (g, fp) = try Self.footprint(sheet: id)
                let j = try XCTUnwrap(w["job"] as? [String: Any])
                let job = TileJob(z: Self.int(j["z"]), x0: Self.int(j["x0"]), y0: Self.int(j["y0"]),
                                  cols: Self.int(j["cols"]), rows: Self.int(j["rows"]))
                let plan = PDFTileWarp.plan(job: job, tilePx: Self.int(w["tilePx"]), footprint: fp, georef: g)
                XCTAssertEqual(plan.cells.count, Self.int(w["cellCount"]), "\(label) cells")
                let own = try XCTUnwrap(PDFTileRenderer.cellBBox(plan))
                for (a, b) in zip(own, Self.dbls(box)) { XCTAssertEqual(a, b, accuracy: tolerance("warpPagePt"), "\(label) cell bbox") }
                let sp = try XCTUnwrap(PDFTileRenderer.stagedPlan(plan: plan, footprint: fp, jobPixels: Self.int(c["jobPixels"])))
                XCTAssertEqual(sp.width, r.W, "\(label) stagedPlan W")
                XCTAssertEqual(sp.height, r.H, "\(label) stagedPlan H")
            }
        }
        XCTAssertGreaterThanOrEqual(regions, 24)
        XCTAssertGreaterThanOrEqual(errors, 7)
        XCTAssertGreaterThanOrEqual(rebuilt, 10)
    }

    /// G3 blank rule on a real file: marks outside the clip polygon but inside
    /// its bbox never count, the import fails blank
    func testBlankCornerImportsAsBlank() throws {
        let c = try XCTUnwrap((Self.fx["stagedRegion"] as? [[String: Any]])?.last { ($0["kind"] as? String) == "blankCheck" })
        let url = try XCTUnwrap(Self.testdataURL(c["file"] as! String))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.count, Self.int(c["bytes"]))
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), c["sha256"] as? String)
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef, "blank corner georef")
        let want = try XCTUnwrap(c["georef"] as? [String: Any])
        for (got, w) in zip(g.affine.coefficients, Self.dbls(want["affine"])) {
            XCTAssertEqual(got, w, accuracy: max(1e-6 * abs(w), 0.01 / 600), "affine")
        }
        XCTAssertEqual(g.crop, Self.pts(want["crop"]))
        let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        let fp = try PDFFootprint(georef: g, pageBox: PDFTileRenderer.pageBox(page))
        assertCyclicPolygon(fp.clipPolygon, Self.pts(c["clipPolygon"]), tol: tolerance("clipPolygonPt"), "blank corner")
        let policy = try PDFZoomPolicy(georef: g, footprint: fp)
        let plan = policy.baseRasterPlan(footprint: fp, budgetPixels: PDFTileConstants.baseBudgetPx)
        let bp = try XCTUnwrap(c["basePlan"] as? [String: Any])
        let r = Self.dbls(bp["region"])
        XCTAssertEqual([plan.region.minX, plan.region.minY, plan.region.maxX, plan.region.maxY].map(Double.init), r)
        XCTAssertEqual(plan.width, Self.int(bp["W"]))
        XCTAssertEqual(plan.height, Self.int(bp["H"]))
        XCTAssertEqual(Self.int(c["blankSampleStride"]), PDFTileConstants.blankSampleStride)
        for m in c["marks"] as? [[String: Any]] ?? [] {
            XCTAssertEqual(Self.int(m["samplesInsideClip"]), 0, "\(m["label"] ?? "") is off the clip")
            XCTAssertGreaterThan(Self.int(m["strideSamplesCovered"]), 0, "but the blank check would see it")
        }
        // the import probe itself: same worker the app runs
        let dest = FileManager.default.temporaryDirectory.appendingPathComponent("blank-corner-\(UUID()).pdf")
        try FileManager.default.copyItem(at: url, to: dest)
        defer { try? FileManager.default.removeItem(at: dest) }
        let readout = try XCTUnwrap(GeoPDFReader.read(url: dest))
        let payload = ImportedMapWorker.PDFPayload(destination: dest, outcome: readout.outcome, page: readout.page,
                                                   contentKey: try XCTUnwrap(PDFSessionStore.contentKey(for: dest)),
                                                   performedWorkOffMainThread: false)
        let expected = (c["expected"] as? [String: Any])?["importFailure"] as? String
        XCTAssertEqual(expected, PDFRenderFailure.blank.rawValue)
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        XCTAssertThrowsError(try ImportedMapWorker.probePDF(payload)) { e in
            guard case PDFMapImportError.cannotDraw(let f)? = e as? PDFMapImportError else {
                return XCTFail("wrong error \(e)")
            }
            XCTAssertEqual(f.rawValue, expected)
        }
    }

    // MARK: - bake format (OD-F7)

    func testBakeFormat() throws {
        let bf = try XCTUnwrap(Self.fx["bakeFormat"] as? [String: Any])
        let rules = try XCTUnwrap(bf["rules"] as? [String: Any])
        XCTAssertEqual(Self.int(rules["megabyte"]), 1_000_000)
        let locales = try XCTUnwrap(rules["locales"] as? [String: [String: String]])
        XCTAssertEqual(locales["en"]?["grouping"], PDFBakeFormat.en.grouping)
        XCTAssertEqual(locales["en"]?["decimal"], PDFBakeFormat.en.decimal)
        XCTAssertEqual(locales["de"]?["grouping"], PDFBakeFormat.de.grouping)
        XCTAssertEqual(locales["de"]?["decimal"], PDFBakeFormat.de.decimal)
        let cases = try XCTUnwrap(bf["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 19)
        for c in cases {
            let tiles = Self.int(c["tiles"])
            let bytes = try XCTUnwrap((c["bytes"] as? NSNumber)?.int64Value)
            for (code, seps) in [("en", PDFBakeFormat.en), ("de", PDFBakeFormat.de)] {
                let want = try XCTUnwrap(c[code] as? [String: String])
                XCTAssertEqual(PDFBakeFormat.tiles(tiles, seps), want["tiles"], "\(code) tiles \(tiles)")
                XCTAssertEqual(PDFBakeFormat.size(bytes, seps), want["size"], "\(code) size \(bytes)")
                XCTAssertEqual(PDFBakeFormat.separators(languageCode: code), seps)
            }
        }
    }

    /// PAR-R2-1 (r3): separators follow the app UI language, never the device
    /// region. A device language that isnt en/de gets en, de-CH gets de (not 1'646)
    func testBakeFormatSeparatorsIgnoreTheDeviceRegion() {
        for code in ["fr", "fr-FR", "it-CH", "ja", "en-GB", nil] {
            XCTAssertEqual(PDFBakeFormat.separators(languageCode: code), PDFBakeFormat.en, "\(code ?? "nil")")
        }
        XCTAssertEqual(PDFBakeFormat.separators(languageCode: "de-CH"), PDFBakeFormat.de)
        XCTAssertEqual(PDFBakeFormat.tiles(1646, PDFBakeFormat.separators(languageCode: "de-CH")), "1.646")
        XCTAssertEqual(PDFBakeFormat.size(1_500_000, PDFBakeFormat.separators(languageCode: "fr-FR")), "1.5 MB")
        // an explicit app language wins over whatever the device is set to
        let lang = AppLanguage.shared
        let was = lang.selection
        defer { lang.select(was) }
        lang.select(.de)
        XCTAssertEqual(PDFBakeFormat.current, PDFBakeFormat.de)
        lang.select(.en)
        XCTAssertEqual(PDFBakeFormat.current, PDFBakeFormat.en)
    }

    // MARK: - J3 gate inputs (r1 D1)

    /// the psnr section's zoom rule, recomputed: baseMaxZoom / detailZoom per
    /// tilePx and the middle INSIDE tile per z. PDFBakeTests runs the gate on these
    func testPsnrGateInputs() throws {
        let p = try XCTUnwrap(Self.fx["psnr"] as? [String: Any])
        XCTAssertEqual(Self.dbl(p["gateDb"]), PDFTileConstants.bakePsnrGateDb)
        XCTAssertEqual((p["tilePx"] as? [Int]), [768, 512])
        for s in try XCTUnwrap(p["sheets"] as? [[String: Any]]) {
            let url = try XCTUnwrap(Self.testdataURL(s["file"] as! String))
            let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
            let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
            let fp = try PDFFootprint(georef: g, pageBox: PDFTileRenderer.pageBox(page))
            let policy = try PDFZoomPolicy(georef: g, footprint: fp)
            let plan = policy.baseRasterPlan(footprint: fp, budgetPixels: PDFTileConstants.baseBudgetPx)
            for chk in s["checks"] as? [[String: Any]] ?? [] {
                let tp = Self.int(chk["tilePx"])
                let bmz = policy.baseMaxZoom(plan: plan, tilePx: tp)
                let ctx = "\(s["sheet"]!) @\(tp)"
                XCTAssertEqual(bmz, Self.int(chk["baseMaxZoom"]), "\(ctx) baseMaxZoom")
                XCTAssertEqual(policy.detailZoom, Self.int(chk["detailZoom"]), "\(ctx) detailZoom")
                var zooms: [[String: Any]] = []
                for z in max(0, bmz)...policy.detailZoom {
                    let inside = try XCTUnwrap(fp.tiles(z: z)).filter { $0.coverage == .inside }.map(\.tile)
                    guard !inside.isEmpty else { continue }
                    let t = inside[inside.count / 2]
                    zooms.append(["z": z, "path": z <= bmz ? "raster" : "vector", "tile": [t.x, t.y], "insideCount": inside.count])
                }
                XCTAssertEqual(zooms as NSArray, chk["zooms"] as? NSArray, "\(ctx) zooms")
                XCTAssertEqual(zooms.filter { $0["path"] as? String == "vector" }.count, Self.int(chk["vectorZooms"]))
                XCTAssertGreaterThanOrEqual(Self.int(chk["vectorZooms"]), 1, "\(ctx) at least one vector zoom")
            }
        }
    }

    /// the J3 dense sheet both apps gate on
    func testDenseSheetMatchesTheFixture() throws {
        let d = try XCTUnwrap(Self.fx["dense"] as? [String: Any])
        let url = try XCTUnwrap(Self.testdataURL(d["file"] as! String))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.count, Self.int(d["bytes"]))
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), d["sha256"] as? String)
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let want = try XCTUnwrap(d["georef"] as? [String: Any])
        for (got, w) in zip(g.affine.coefficients, Self.dbls(want["affine"])) {
            XCTAssertEqual(got, w, accuracy: max(1e-6 * abs(w), 0.01 / 600), "dense affine")
        }
        XCTAssertEqual(g.crop, Self.pts(want["crop"]))
        var zp = try XCTUnwrap(d["zoomPolicy"] as? [String: Any])
        zp["sheet"] = "dense"
        try assertZoomPolicy(zp, georef: g)
    }

    // MARK: - bake estimate (J2)

    func testBakeEstimateSampleSelection() throws {
        let be = try XCTUnwrap(Self.fx["bakeEstimate"] as? [String: Any])
        let sel = try XCTUnwrap(be["sampleSelection"] as? [[String: Any]])
        XCTAssertEqual(sel.count, 8)
        for s in sel {
            let id = s["sheet"] as! String
            let (g, fp) = try Self.footprint(sheet: id)
            let z = Self.int(s["z"])
            let policy = try PDFZoomPolicy(georef: g, footprint: fp)
            XCTAssertEqual(PDFBakePlan.defaultOption(PDFBakePlan.options(detailZoom: policy.detailZoom, footprint: fp),
                                                     detailZoom: policy.detailZoom)?.maxZoom, z, "\(id) default z")
            let level = try XCTUnwrap(fp.tiles(z: z))
            let c = g.toWGS84(x: fp.clipMean.x, y: fp.clipMean.y).map { ($0.latitude, $0.longitude) }
            let pick = PDFBakeEstimator.sampleTiles(level: level, z: z, centre: c)
            XCTAssertEqual(pick.pool.rawValue, s["pool"] as? String, "\(id) pool")
            XCTAssertEqual(pick.poolCount, Self.int(s["poolCount"]), "\(id) pool count")
            let ct = Self.dbls(s["centreTile"])
            let tol = tolerance("bakeEstimateCentreTile")
            XCTAssertFalse(tol.isNaN, "bakeEstimateCentreTile tolerance")
            XCTAssertEqual(pick.centreTile?.x ?? .nan, ct[0], accuracy: tol, "\(id) cx")
            XCTAssertEqual(pick.centreTile?.y ?? .nan, ct[1], accuracy: tol, "\(id) cy")
            XCTAssertEqual(pick.tiles.map { [$0.x, $0.y] }, s["tiles"] as? [[Int]], "\(id) picks")
            let d2Tol = tolerance("bakeEstimateD2")
            XCTAssertFalse(d2Tol.isNaN, "bakeEstimateD2 tolerance")
            XCTAssertEqual(pick.d2.count, Self.dbls(s["d2"]).count, "\(id) d2 count")
            for (got, want) in zip(pick.d2, Self.dbls(s["d2"])) {
                XCTAssertEqual(got, want, accuracy: d2Tol, "\(id) d2")
            }
            // no WGS84 for the centre: the first 3 of the pool, row major
            let fallback = PDFBakeEstimator.sampleTiles(level: level, z: z, centre: nil)
            let pool = level.filter { $0.coverage == .inside }.map(\.tile)
            XCTAssertEqual(fallback.tiles, Array((pool.isEmpty ? level.map(\.tile) : pool).prefix(3)), "\(id) fallback")
        }
    }

    func testBakeEstimateCases() throws {
        let be = try XCTUnwrap(Self.fx["bakeEstimate"] as? [String: Any])
        let cases = try XCTUnwrap(be["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 10)
        var fps: [String: PDFFootprint] = [:]
        for c in cases {
            let label = c["label"] as? String ?? ""
            let id = c["sheet"] as! String
            if fps[id] == nil { fps[id] = try Self.footprint(sheet: id).1 }
            let fp = fps[id]!
            let options = (c["options"] as? [[String: Any]] ?? []).map {
                PDFBakeOption(maxZoom: Self.int($0["maxZoom"]), tiles: Self.int($0["tiles"]))
            }
            // D7: the case's inputs are this sheet's own: detailZoom, and each
            // option's tiles is the footprint sum over 0...m
            let g = try XCTUnwrap(Self.georef(sheet: id))
            XCTAssertEqual(try PDFZoomPolicy(georef: g, footprint: fp).detailZoom, Self.int(c["detailZoom"]), "\(label) detailZoom")
            for o in options {
                let sum = try (0...o.maxZoom).reduce(0) { $0 + (try XCTUnwrap(fp.tiles(z: $1))).count }
                XCTAssertEqual(o.tiles, sum, "\(label) z\(o.maxZoom) tiles")
            }
            let meansTol = tolerance("bakeEstimateMeansRel")
            XCTAssertFalse(meansTol.isNaN)
            let samples = (c["samples"] as? [[String: Any]] ?? []).map {
                PDFBakeSample(result: PDFBakeSample.Result(rawValue: $0["result"] as! String)!,
                              jobMs: Self.dbl($0["jobMs"]), bytes: ($0["bytes"] as? NSNumber)?.intValue,
                              encodeMs: ($0["encodeMs"] as? NSNumber)?.doubleValue)
            }
            var levels: [Int: [TileIndex]] = [:]
            let est = PDFBakeEstimator.estimate(
                options: options, defaultMaxZoom: Self.int(c["defaultMaxZoom"]),
                baseMaxZoom: (c["baseMaxZoom"] as? NSNumber)?.intValue ?? -1,
                levelTiles: { z in
                    if let l = levels[z] { return l }
                    let l = (fp.tiles(z: z) ?? []).map(\.tile)
                    levels[z] = l
                    return l
                },
                samples: samples, ewmaMs: (c["ewmaMs"] as? NSNumber)?.doubleValue,
                freeBytes: (c["freeBytes"] as? NSNumber)?.int64Value)
            let x = try XCTUnwrap(c["expected"] as? [String: Any])
            XCTAssertEqual(est.heavy, x["heavy"] as? Bool, "\(label) heavy")
            XCTAssertTrue(rel(est.jobMs, Self.dbl(x["jobMs"]), meansTol), "\(label) jobMs \(est.jobMs)")
            XCTAssertEqual(est.jobMsFrom.rawValue, x["jobMsFrom"] as? String, "\(label) jobMsFrom")
            XCTAssertTrue(rel(est.encodeMs, Self.dbl(x["encodeMs"]), meansTol), "\(label) encodeMs \(est.encodeMs)")
            XCTAssertEqual(est.encodeMsFrom.rawValue, x["encodeMsFrom"] as? String, "\(label) encodeMsFrom")
            XCTAssertTrue(rel(est.meanTileBytes, Self.dbl(x["meanTileBytes"]), meansTol), "\(label) meanTileBytes")
            XCTAssertEqual(est.meanTileBytesFrom.rawValue, x["meanTileBytesFrom"] as? String, "\(label) bytesFrom")
            let wantOpts = x["options"] as? [[String: Any]] ?? []
            XCTAssertEqual(est.options.count, wantOpts.count, "\(label) option count")
            for (o, w) in zip(est.options, wantOpts) {
                let ctx = "\(label) z\(o.maxZoom)"
                XCTAssertEqual(o.maxZoom, Self.int(w["maxZoom"]), ctx)
                XCTAssertEqual(o.tiles, Self.int(w["tiles"]), "\(ctx) tiles")
                XCTAssertEqual(o.jobsPerLevel, w["jobsPerLevel"] as? [Int], "\(ctx) jobsPerLevel")
                XCTAssertEqual(o.jobs, Self.int(w["jobs"]), "\(ctx) jobs")
                XCTAssertEqual(o.bytes, (w["bytes"] as? NSNumber)?.int64Value, "\(ctx) bytes")
                XCTAssertEqual(o.neededBytes, (w["neededBytes"] as? NSNumber)?.int64Value, "\(ctx) neededBytes")
                XCTAssertEqual(o.enoughSpace, w["enoughSpace"] as? Bool, "\(ctx) enoughSpace")
                XCTAssertTrue(rel(o.estimatedMs, Self.dbl(w["estimatedMs"]), meansTol), "\(ctx) estimatedMs \(o.estimatedMs)")
                XCTAssertEqual(o.minutes, Self.int(w["minutes"]), "\(ctx) minutes")
            }
            XCTAssertEqual(est.initialSelection, Self.int(x["initialSelection"]), "\(label) initial")
            XCTAssertEqual(est.generateEnabled, x["generateEnabled"] as? Bool, "\(label) generate")
        }
    }

    // MARK: - render PDFs + pure geometry targets

    func testRenderPdfsMatchTheirHashes() throws {
        for key in ["markers", "blank", "dense"] {
            let m = try XCTUnwrap(Self.fx[key] as? [String: Any])
            let url = try XCTUnwrap(Self.testdataURL(m["file"] as! String), key)
            let data = try Data(contentsOf: url)
            XCTAssertEqual(data.count, Self.int(m["bytes"]), "\(key) bytes")
            let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            XCTAssertEqual(sha, m["sha256"] as? String, "\(key) sha256")
        }
    }

    /// page point -> tile px through the georef alone, the 0.01 px expectation
    /// (the rendered centroid tests in PDFTileRendererTests use 0.5 px)
    private func assertTilePx(_ page: [Double], _ e: [String: Any], georef g: PdfGeoreference, _ ctx: String) throws {
        let ll = try XCTUnwrap(g.toWGS84(x: page[0], y: page[1]), ctx)
        let w = PDFTileMath.world0(lat: ll.latitude, lon: ll.longitude)
        let z = Self.int(e["z"]), tp = Double(Self.int(e["tilePx"]))
        let n = pow(2.0, Double(z))
        let tx = w.x * n / 256, ty = w.y * n / 256
        let tile = e["tile"] as! [Int]
        XCTAssertEqual(Int(tx.rounded(.down)), tile[0], "\(ctx) tile x")
        XCTAssertEqual(Int(ty.rounded(.down)), tile[1], "\(ctx) tile y")
        let px = Self.dbls(e["px"])
        let tol = tolerance("tilePxExpectPx")
        XCTAssertEqual((tx - Double(tile[0])) * tp, px[0], accuracy: tol, "\(ctx) px x")
        XCTAssertEqual((ty - Double(tile[1])) * tp, px[1], accuracy: tol, "\(ctx) px y")
    }

    func testMarkerRingAndAlphaTargetsArePureGeometry() throws {
        let m = try XCTUnwrap(Self.fx["markers"] as? [String: Any])
        let url = try XCTUnwrap(Self.testdataURL(m["file"] as! String))
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef, "markers georef")
        var n = 0
        var groups = (m["markers"] as? [[String: Any]] ?? [])
        for k in ["clippedMarker", "hiddenOcgSquare"] { if let o = m[k] as? [String: Any] { groups.append(o) } }
        for marker in groups {
            let page = Self.dbls(marker["page"])
            for e in marker["expected"] as? [[String: Any]] ?? [] {
                try assertTilePx(page, e, georef: g, "marker \(marker["label"] ?? "ocg") z\(e["z"]!)@\(e["tilePx"]!)")
                n += 1
            }
        }
        for a in m["alphaSamples"] as? [[String: Any]] ?? [] {
            try assertTilePx(Self.dbls(a["page"]), a, georef: g, "alpha \(a["label"]!) z\(a["z"]!)@\(a["tilePx"]!)")
            n += 1
        }
        for r in try XCTUnwrap(Self.fx["ringTargets"] as? [[String: Any]]) {
            let id = r["sheet"] as! String
            let rg = try XCTUnwrap(Self.georef(sheet: id))
            for e in r["expected"] as? [[String: Any]] ?? [] {
                try assertTilePx(Self.dbls(r["page"]), e, georef: rg, "ring \(id) \(r["id"]!) z\(e["z"]!)@\(e["tilePx"]!)")
                n += 1
            }
        }
        XCTAssertGreaterThan(n, 60)
    }

    // MARK: - camera zoom

    func testCameraZoom() throws {
        let cz = try XCTUnwrap(Self.fx["cameraZoom"] as? [String: Any])
        let tol = tolerance("cameraZoom")
        for p in cz["pinch"] as? [[String: Any]] ?? [] {
            let z = Self.dbl(p["zoom"]), sc = Self.dbl(p["scale"]), want = Self.dbl(p["result"])
            XCTAssertEqual(PDFTileMath.pinchZoom(z, scale: sc), want, accuracy: tol, "pinch \(z) x\(sc)")
            // and the real gesture path on the view
            let view = TileMapView(camera: MapCamera(center: .init(latitude: 37.77, longitude: -122.44), zoom: z,
                                                     headingDegrees: 0, viewportSize: CGSize(width: 390, height: 844)),
                                   cacheBytes: 1 << 20)
            view.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
            view.applyPinch(scale: sc, focal: CGPoint(x: 195, y: 422))
            XCTAssertEqual(view.camera.zoom, want, accuracy: 1e-9, "view pinch \(z) x\(sc)")
        }
        for t in cz["target"] as? [[String: Any]] ?? [] {
            XCTAssertEqual(PDFTileMath.clampCameraZoom(Self.dbl(t["target"])), Self.dbl(t["result"]), accuracy: tol)
        }
        for t in cz["tileZoom"] as? [[String: Any]] ?? [] {
            let c = Self.dbl(t["cameraZoom"])
            let tz = TileMath.tileZoom(for: c, minZoom: Self.int(t["minZoom"]), maxZoom: Self.int(t["maxZoom"]))
            XCTAssertEqual(tz, Self.int(t["tileZoom"]), "tileZoom \(c)")
            XCTAssertEqual(TileMath.isUnderzoomed(tileZoom: tz, cameraZoom: c), t["underzoomHidden"] as? Bool, "underzoom \(c)")
        }
    }
}

extension XCTestCase {
    func rel(_ got: Double, _ want: Double, _ tol: Double, floor: Double = 0) -> Bool {
        abs(got - want) <= max(tol * abs(want), floor)
    }

    func tolerance(_ k: String) -> Double { PDFTileRenderFixtureTests.dbl((PDFTileRenderFixtureTests.fx["tolerances"] as? [String: Any])?[k]) }

    /// shared by the markers sheet in PDFTileRendererTests
    func assertZoomPolicy(_ zp: [String: Any], georef g: PdfGeoreference) throws {
        let id = zp["sheet"] as? String ?? "?"
        let fp = try PDFFootprint(georef: g, pageBox: PDFTileRenderFixtureTests.rect(zp["pageBox"]))
        let want = PDFTileRenderFixtureTests.pts(zp["clipPolygon"])
        assertCyclicPolygon(fp.clipPolygon, want, tol: tolerance("clipPolygonPt"), id)
        let mean = PDFTileRenderFixtureTests.dbls(zp["clipMean"])
        XCTAssertEqual(fp.clipMean.x, mean[0], accuracy: tolerance("clipMeanPt"), "\(id) clipMean")
        XCTAssertEqual(fp.clipMean.y, mean[1], accuracy: tolerance("clipMeanPt"), "\(id) clipMean")
        XCTAssertEqual(abs(PDFClip.signedArea(fp.clipPolygon)), PDFTileRenderFixtureTests.dbl(zp["clipArea"]), accuracy: 1e-5, "\(id) area")
        let wb = PDFTileRenderFixtureTests.dbls(zp["footprintBboxWorld"])
        let ftol = tolerance("footprintWorld") * 10
        XCTAssertEqual(fp.worldMinX, wb[0], accuracy: ftol, "\(id) world bbox")
        XCTAssertEqual(fp.worldMinY, wb[1], accuracy: ftol, "\(id) world bbox")
        XCTAssertEqual(fp.worldMaxX, wb[2], accuracy: ftol, "\(id) world bbox")
        XCTAssertEqual(fp.worldMaxY, wb[3], accuracy: ftol, "\(id) world bbox")

        let policy = try PDFZoomPolicy(georef: g, footprint: fp)
        let mmpp = PDFTileRenderFixtureTests.dbl(zp["mercMetresPerPoint"])
        XCTAssertTrue(rel(policy.mercMetresPerPoint, mmpp, tolerance("mercMetresPerPointRel")),
                      "\(id) mmpp \(policy.mercMetresPerPoint) vs \(mmpp)")
        let jac = (zp["jacobian"] as? [[Any]] ?? []).map { $0.map(PDFTileRenderFixtureTests.dbl) }
        for i in 0..<2 { for j in 0..<2 {
            XCTAssertTrue(rel(policy.jacobian[i][j], jac[i][j], 1e-6, floor: 1e-7), "\(id) J[\(i)][\(j)]")
        } }
        XCTAssertEqual(policy.detailZoomRaw, PDFTileRenderFixtureTests.dbl(zp["detailZoomRaw"]), accuracy: tolerance("detailZoomRawAbs"), id)
        XCTAssertEqual(policy.detailZoom, PDFTileRenderFixtureTests.int(zp["detailZoom"]), id)
        XCTAssertEqual(PDFTileRenderFixtureTests.int(zp["maxZoom"]), policy.detailZoom)
        XCTAssertEqual(PDFTileRenderFixtureTests.int(zp["minZoom"]), 0)
        for row in zp["pxPerPt"] as? [[String: Any]] ?? [] {
            let tp = PDFTileRenderFixtureTests.int(row["tilePx"]), z = PDFTileRenderFixtureTests.int(row["z"]), v = PDFTileRenderFixtureTests.dbl(row["value"])
            XCTAssertTrue(rel(PDFZoomPolicy.pxPerPoint(mercMetresPerPoint: mmpp, z: z, tilePx: tp), v, tolerance("pxPerPtRel")),
                          "\(id) pxPerPt z\(z) @\(tp)")
            XCTAssertTrue(rel(policy.pxPerPoint(z: z, tilePx: tp), v, 1e-6), "\(id) own pxPerPt z\(z) @\(tp)")
        }
        let plans = try XCTUnwrap(zp["basePlan"] as? [String: Any])
        for key in ["normal", "lowRam"] {
            let bp = try XCTUnwrap(plans[key] as? [String: Any])
            let plan = policy.baseRasterPlan(footprint: fp, budgetPixels: PDFTileRenderFixtureTests.int(bp["budgetPx"]))
            let r = PDFTileRenderFixtureTests.dbls(bp["region"])
            XCTAssertEqual(Double(plan.region.minX), r[0], accuracy: 1e-6, "\(id) \(key) region")
            XCTAssertEqual(Double(plan.region.minY), r[1], accuracy: 1e-6, "\(id) \(key) region")
            XCTAssertEqual(Double(plan.region.maxX), r[2], accuracy: 1e-6, "\(id) \(key) region")
            XCTAssertEqual(Double(plan.region.maxY), r[3], accuracy: 1e-6, "\(id) \(key) region")
            XCTAssertTrue(rel(plan.scale, PDFTileRenderFixtureTests.dbl(bp["s"]), 1e-9), "\(id) \(key) s")
            XCTAssertEqual(plan.width, PDFTileRenderFixtureTests.int(bp["W"]), "\(id) \(key) W")
            XCTAssertEqual(plan.height, PDFTileRenderFixtureTests.int(bp["H"]), "\(id) \(key) H")
            XCTAssertTrue(rel(plan.densityPxPerPt, PDFTileRenderFixtureTests.dbl(bp["densityPxPerPt"]), 1e-9), "\(id) \(key) density")
            let mips = (bp["mips"] as? [[Int]]) ?? []
            XCTAssertEqual(plan.mips.map { [$0.w, $0.h] }, mips, "\(id) \(key) mips")
            for row in bp["baseMaxZoom"] as? [[String: Any]] ?? [] {
                let tp = PDFTileRenderFixtureTests.int(row["tilePx"])
                let want = (row["baseMaxZoom"] as? NSNumber)?.intValue ?? -1
                XCTAssertEqual(policy.baseMaxZoom(plan: plan, tilePx: tp), want, "\(id) \(key) baseMaxZoom @\(tp)")
            }
        }
    }

    func assertCyclicPolygon(_ got: [PdfPagePoint], _ want: [PdfPagePoint], tol: Double, _ ctx: String,
                                     file: StaticString = #filePath, line: UInt = #line) {
        guard got.count == want.count, !want.isEmpty else {
            return XCTFail("\(ctx): clip polygon \(got.count) vs \(want.count) vertices", file: file, line: line)
        }
        let n = got.count
        let ok = (0..<n).contains { off in
            (0..<n).allSatisfy { i in
                abs(got[(i + off) % n].x - want[i].x) <= tol && abs(got[(i + off) % n].y - want[i].y) <= tol
            }
        }
        XCTAssertTrue(ok, "\(ctx): clip polygon \(got) vs \(want)", file: file, line: line)
    }
}
