import XCTest
import UIKit
import SQLite3
import CryptoKit
@testable import TacticalMaps

/// "Generate Offline Tiles" end to end on sf_iso: same renderer as live,
/// JPEG 2000 inside / PNG on the edge, keeps the PDF, cancel + sourceChanged
/// leave nothing behind, file protection class, MBTiles metadata.
final class PDFBakeTests: XCTestCase {
    typealias F = PDFTileRenderFixtureTests

    var dir: URL!
    var controller: PDFBakeController!
    var pdf: PDFMapSource!
    var persisted: [PDFMapSource] = []

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("bake-\(UUID())", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        controller = PDFBakeController()
        controller.finalDirectory = dir.appendingPathComponent("offline_tiles", isDirectory: true)
        controller.guardStore = PDFRenderGuard(url: dir.appendingPathComponent("guard.json"))
        controller.detachRecord = { [unowned self] _, p in self.persisted.append(p); return true }
        controller.attachRecord = { [unowned self] _, p, _ in self.persisted.append(p); return .attached }
        // the record writes are stubbed, so the library the sweep would read isnt ours
        controller.storedBake = { [] }
        persisted = []
    }

    override func tearDown() {
        controller.cancel()
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    func makePDF() throws -> PDFMapSource {
        let src = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let url = dir.appendingPathComponent("sheet-\(UUID()).pdf")
        try FileManager.default.copyItem(at: src, to: url)
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        return PDFMapSource(url: url, georef: g, contentKey: PDFSessionStore.contentKey(for: url))
    }

    func waitFor(_ what: String, timeout: Double = 60, _ cond: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !cond(), Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertTrue(cond(), "timed out waiting for \(what)")
    }

    struct EstimateDidntConfirm: Error {}

    /// D2: an estimate that doesnt reach confirming is a failure, never a skip
    func proposal(file: StaticString = #filePath, line: UInt = #line) throws -> PDFBakeProposal {
        controller.prepare(pdf: pdf, runtime: nil)
        waitFor("estimate") { if case .confirming = controller.state { return true }; return controller.state != .estimating }
        guard case .confirming(let p) = controller.state else {
            XCTFail("estimate ended in \(controller.state)", file: file, line: line)
            throw EstimateDidntConfirm()
        }
        return p
    }

    func testEstimateOffersTheFixtureOptions() throws {
        pdf = try makePDF()
        let p = try proposal()
        let want = (F.fx["bakeOptions"] as? [[String: Any]])?.first { $0["sheet"] as? String == "sf_iso" }
        XCTAssertEqual(p.options.map(\.maxZoom), want?["options"] as? [Int])
        XCTAssertEqual(p.defaultMaxZoom, F.int(want?["default"]))
        XCTAssertTrue(p.options.allSatisfy { $0.bytes > 0 && $0.estimatedMs >= 0 && $0.minutes >= 1 })
        XCTAssertEqual(p.pdfName, pdf.displayName)
        // bigger zoom, more tiles, more bytes
        XCTAssertEqual(p.options.map(\.tiles), p.options.map(\.tiles).sorted())
        XCTAssertEqual(PDFBakeFormat.minutes(1), Messages.pdfBakeMinutes("1"))
        XCTAssertEqual(PDFBakeFormat.minutes(0), Messages.pdfBakeMinutes("1"))
        // J2: real samples came back, so nothing fell back
        XCTAssertEqual(p.estimate.encodeMsFrom, .samples)
        XCTAssertEqual(p.estimate.meanTileBytesFrom, .samples)
        XCTAssertEqual(controller.selectedMaxZoom, p.defaultMaxZoom)
        XCTAssertTrue(controller.canGenerate)
        for o in p.options { XCTAssertEqual(o.neededBytes, 2 * o.bytes) }
    }

    /// J1/J2: nothing fits, the confirm still shows with every row disabled,
    /// the default stays selected and Generate is off
    func testNoRoomStillConfirmsWithEverythingDisabled() throws {
        pdf = try makePDF()
        controller.freeSpace = { 1 }
        let p = try proposal()
        XCTAssertTrue(p.options.allSatisfy { !$0.enoughSpace })
        XCTAssertEqual(controller.selectedMaxZoom, p.defaultMaxZoom)
        XCTAssertFalse(controller.canGenerate)
        controller.generate()
        XCTAssertFalse(controller.isRunning)
        controller.select(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        XCTAssertEqual(controller.selectedMaxZoom, p.defaultMaxZoom, "disabled rows cant be picked")
        // room for the smallest only: that becomes the pick
        let small = try XCTUnwrap(p.options.first)
        controller.dismiss()
        controller.freeSpace = { small.neededBytes }
        let p2 = try proposal()
        XCTAssertEqual(p2.options.filter(\.enoughSpace).map(\.maxZoom), [small.maxZoom])
        XCTAssertEqual(controller.selectedMaxZoom, small.maxZoom)
        XCTAssertTrue(controller.canGenerate)
    }

    /// J2: a full disk on the write path reports what the option needs
    func testFullDiskMapsToNoSpaceWithTheNeededBytes() {
        let enospc = NSError(domain: NSPOSIXErrorDomain, code: Int(ENOSPC))
        XCTAssertEqual(PDFBakeError.fromWrite(enospc, neededBytes: 1234), .noSpace(neededBytes: 1234))
        let wrapped = NSError(domain: NSCocoaErrorDomain, code: NSFileWriteUnknownError, userInfo: [NSUnderlyingErrorKey: enospc])
        XCTAssertEqual(PDFBakeError.fromWrite(wrapped, neededBytes: 9), .noSpace(neededBytes: 9))
        XCTAssertEqual(PDFBakeError.fromWrite(NSError(domain: NSCocoaErrorDomain, code: NSFileWriteOutOfSpaceError), neededBytes: 5),
                       .noSpace(neededBytes: 5))
        XCTAssertEqual(PDFBakeError.fromWrite(NSError(domain: NSPOSIXErrorDomain, code: Int(EACCES)), neededBytes: 5), .writeFailed)
        XCTAssertEqual(PDFBakeError.fromWrite(PDFBakeError.renderFailed, neededBytes: 5), .renderFailed)
    }

    func testRendererOneBakeCannotBeReusedAfterAccuracyRevision() throws {
        pdf = try makePDF()
        let tilePx = 768
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let georefJSON = String(data: try encoder.encode(pdf.georef), encoding: .utf8)!
        let oldInput = PDFTileConstants.bakeKeyPrefix + georefJSON + "|\(tilePx)|1"
        let oldKey = SHA256.hash(data: Data(oldInput.utf8)).map { String(format: "%02x", $0) }.joined()
        let file = dir.appendingPathComponent("renderer-one.mbtiles")
        let writer = try XCTUnwrap(MBTilesWriter(path: file.path))
        writer.writeMetadata(name: PDFTileConstants.bakeMbtilesName, minZoom: 0, maxZoom: 15,
                             minLon: -180, minLat: -85, maxLon: 180, maxLat: 85,
                             extra: ["tacmap_bake_key": oldKey, "tacmap_tile_px": String(tilePx), "tacmap_renderer": "1"])
        let pixels = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { context in
            UIColor.red.setFill(); context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        let png = try XCTUnwrap(pixels.pngData())
        writer.putTile(z: 0, x: 0, y: 0, data: png)
        writer.putTile(z: 15, x: 0, y: 0, data: png)
        XCTAssertFalse(writer.hadError)
        writer.close()
        let oldReader = try XCTUnwrap(PDFBakeReader(url: file, expectedKey: oldKey, tilePx: tilePx))
        XCTAssertNotNil(oldReader.tileImage(TileIndex(z: 15, x: 0, y: 0)))
        oldReader.close()
        let currentKey = PDFRenderContext.bakeKey(georef: pdf.georef, tilePx: tilePx)
        XCTAssertNotEqual(currentKey, oldKey, "The accuracy revision must invalidate renderer-one geometry")
        XCTAssertNil(PDFBakeReader(url: file, expectedKey: currentKey, tilePx: tilePx), "Old bakes must fall back to live rendering")
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path), "The original PDF stays available")
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path), "Reader rejection does not delete an old bake")
    }

    func testBakeKeepsThePdfAndWritesAValidPyramid() throws {
        pdf = try makePDF()
        let p = try proposal()
        let z = try XCTUnwrap(p.options.first).maxZoom
        controller.start(maxZoom: z)
        XCTAssertTrue(controller.isRunning)
        waitFor("bake") { !controller.isRunning }
        XCTAssertEqual(controller.state, .idle)
        XCTAssertTrue(controller.finishedMessage)
        let record = try XCTUnwrap(pdf.bake)
        XCTAssertEqual(record.maxZoom, z)
        XCTAssertEqual(record.minZoom, 0)
        XCTAssertTrue(persisted.first === pdf, "the record goes onto the library entry")
        XCTAssertTrue(FileManager.default.fileExists(atPath: pdf.url.path), "the PDF is kept (D5-05)")
        let file = controller.finalDirectory.appendingPathComponent(record.fileName)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        let attrs = try FileManager.default.attributesOfItem(atPath: file.path)
        if let prot = attrs[.protectionKey] as? FileProtectionType {
            XCTAssertEqual(prot, .completeUntilFirstUserAuthentication)
        }
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        // nothing left in the work dir
        let work = (try? FileManager.default.contentsOfDirectory(atPath: PDFBakeWorker.workDirectory.path)) ?? []
        XCTAssertTrue(work.filter { $0.hasSuffix(".partial") }.isEmpty)

        // the reader trusts it only with the right key + tile size
        let (_, page) = try PDFTileRenderer.openPage(url: pdf.url, pageIndex: 0)
        let tilePx = record.tilePx
        let ctx = try PDFRenderContext(url: pdf.url, identity: PDFDocumentIdentity(contentKey: pdf.contentKey!, pageIndex: 0),
                                       georef: pdf.georef, pageBox: PDFTileRenderer.pageBox(page), tilePx: tilePx,
                                       guardToken: pdf.renderGuardToken, baseBudgetPx: PDFTileConstants.baseBudgetPx)
        XCTAssertEqual(record.bakeKey, ctx.bakeKey)
        let reader = try XCTUnwrap(PDFBakeReader(url: file, expectedKey: ctx.bakeKey, tilePx: tilePx))
        XCTAssertNil(PDFBakeReader(url: file, expectedKey: String(repeating: "0", count: 64), tilePx: tilePx))
        XCTAssertNil(PDFBakeReader(url: file, expectedKey: ctx.bakeKey, tilePx: tilePx + 16))
        XCTAssertEqual(reader.maxZoom, z)
        let store = try XCTUnwrap(MBTilesStore(url: file))
        XCTAssertEqual(Self.metadataValue(file, "tacmap_renderer"), String(PDFTileRenderer.version))
        // F2: the plaintext file never names the sheet
        XCTAssertEqual(Self.metadataValue(file, "name"), PDFTileConstants.bakeMbtilesName)
        XCTAssertNotEqual(Self.metadataValue(file, "name"), pdf.displayName)
        XCTAssertEqual(store.metadata.minZoom, 0)
        XCTAssertEqual(store.metadata.maxZoom, z)

        // every intersecting tile, nothing else
        var edgeChecked = false, insideChecked = false
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        let base = try PDFTileRenderer.renderBaseRaster(page: page, plan: ctx.basePlan, footprint: ctx.footprint)
        for zz in 0...z {
            for (t, cov) in try XCTUnwrap(ctx.footprint.tiles(z: zz)) {
                let data = try XCTUnwrap(store.tileData(z: t.z, x: t.x, y: t.y), "missing \(t)")
                // JPEG 2000 file signature box, or PNG
                let isJP2 = data.starts(with: [0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20])
                let isPNG = data.starts(with: [0x89, 0x50, 0x4E, 0x47])
                XCTAssertTrue(isJP2 || isPNG)
                if cov == .edge, !edgeChecked, isPNG, zz == z {
                    let img = try XCTUnwrap(UIImage(data: data)?.cgImage)
                    XCTAssertFalse(PDFTileRenderer.isFullyOpaque(img), "edge tiles keep their transparency")
                    edgeChecked = true
                }
                if cov == .inside, !insideChecked, zz == z {
                    XCTAssertTrue(isJP2, "opaque interior tiles go JPEG 2000")
                    let baked = try XCTUnwrap(UIImage(data: data)?.cgImage)
                    let job = TileJob(z: t.z, x0: t.x, y0: t.y, cols: 1, rows: 1)
                    let plan = PDFTileWarp.plan(job: job, tilePx: tilePx, footprint: ctx.footprint, georef: ctx.georef)
                    let src: PDFRenderSourceKind = t.z <= ctx.baseMaxZoom ? .raster(base)
                        : (plan.cells.count == 1 ? .vector(page) : .staged(page))
                    let live = try PDFTileRenderer.renderJob(job, tilePx: tilePx, plan: plan, footprint: ctx.footprint, source: src)
                    guard case .image(let liveImg)? = live[t] else { return XCTFail("live render") }
                    XCTAssertGreaterThanOrEqual(psnr(baked, liveImg), PDFTileConstants.bakePsnrGateDb, "baked vs live")
                    insideChecked = true
                }
            }
            // a tile just off the sheet has no row
            if let first = try XCTUnwrap(ctx.footprint.tiles(z: zz)).first?.tile, first.y >= 1 {
                let above = TileIndex(z: zz, x: first.x, y: first.y - 1)
                XCTAssertEqual(ctx.footprint.classify(above), .outside)
                XCTAssertNil(store.tileData(z: zz, x: above.x, y: above.y))
            }
        }
        XCTAssertTrue(edgeChecked)
        XCTAssertTrue(insideChecked)
    }

    func testCancelLeavesNothingBehind() throws {
        pdf = try makePDF()
        let p = try proposal()
        controller.start(maxZoom: try XCTUnwrap(p.options.last).maxZoom)
        waitFor("some progress") { if case .running(let d, _) = controller.state { return d > 0 }; return !controller.isRunning }
        // OD-F5: journal_mode=OFF really is off, no -journal next to the .partial mid bake
        let work = (try? FileManager.default.contentsOfDirectory(atPath: PDFBakeWorker.workDirectory.path)) ?? []
        XCTAssertTrue(work.contains { $0.hasSuffix(".partial") }, "bake in progress")
        XCTAssertFalse(work.contains { $0.hasSuffix("-journal") }, "no rollback journal mid bake: \(work)")
        controller.cancel()
        waitFor("cancelled") { !controller.isRunning }
        XCTAssertEqual(controller.state, .idle)
        XCTAssertNil(pdf.bake)
        XCTAssertTrue(persisted.isEmpty)
        let finals = (try? FileManager.default.contentsOfDirectory(atPath: controller.finalDirectory.path)) ?? []
        XCTAssertTrue(finals.isEmpty)
        // the worker removes its .partial on the way out
        waitFor("partial cleanup", timeout: 10) {
            ((try? FileManager.default.contentsOfDirectory(atPath: PDFBakeWorker.workDirectory.path)) ?? [])
                .filter { $0.hasSuffix(".partial") }.isEmpty
        }
    }

    func testSourceChangedDiscardsTheBake() throws {
        pdf = try makePDF()
        controller.attachRecord = { _, _, _ in .sourceChanged }
        let p = try proposal()
        controller.start(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        waitFor("bake") { !controller.isRunning }
        XCTAssertEqual(controller.state, .failed(.sourceChanged))
        XCTAssertNil(pdf.bake)
        let finals = (try? FileManager.default.contentsOfDirectory(atPath: controller.finalDirectory.path)) ?? []
        XCTAssertTrue(finals.isEmpty)
    }

    func testGeorefChangeDropsTheBakeRecord() throws {
        pdf = try makePDF()
        pdf.bake = PDFBakeRecord(fileName: "x.mbtiles", bakeKey: String(repeating: "a", count: 64), minZoom: 0,
                                 maxZoom: 14, tilePx: 512, bytes: 1)
        let fids = [(72.0, 72.0, 37.73011820966, -122.47797518349), (752.3, 72.0, 37.72979703305, -122.4098881143),
                    (752.3, 979.0, 37.80189927136, -122.40931491011), (72.0, 979.0, 37.80222127862, -122.47746810775)]
            .map { Fiduciary(pdfX: $0.0, pdfY: $0.1, mgrs: "", latitude: $0.2, longitude: $0.3, label: nil) }
        pdf.applyCalibration(transform: try XCTUnwrap(pdf.georef.bestFitLatLonAffine()), fiduciaries: fids)
        XCTAssertNotNil(pdf.calibration)
        XCTAssertNil(pdf.bake, "a new georef can't use tiles baked from the old one")
    }

    /// S5: the controller lets go of its render service (and the pdf + ctx)
    /// once an estimate is dismissed, a bake finishes or anything is cancelled
    func testControllerReleasesTheRenderServiceWhenDone() throws {
        pdf = try makePDF()
        _ = try proposal()
        let svc = try XCTUnwrap(controller.heldService)
        XCTAssertEqual(svc.refCount, 1)
        controller.dismiss()
        XCTAssertNil(controller.heldService, "dismissed estimate")
        XCTAssertEqual(svc.refCount, 0)

        let p = try proposal()
        let svc2 = try XCTUnwrap(controller.heldService)
        controller.start(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        waitFor("bake") { !controller.isRunning }
        XCTAssertNil(controller.heldService, "finished bake")
        XCTAssertEqual(svc2.refCount, 0)

        let p3 = try proposal()
        let svc3 = try XCTUnwrap(controller.heldService)
        controller.start(maxZoom: try XCTUnwrap(p3.options.last).maxZoom)
        controller.cancel(for: pdf)
        XCTAssertNil(controller.heldService, "pdf going away")
        XCTAssertEqual(svc3.refCount, 0)

        controller.prepare(pdf: pdf, runtime: nil)
        let svc4 = try XCTUnwrap(controller.heldService)
        controller.cancel()
        XCTAssertNil(controller.heldService, "cancelled while estimating")
        XCTAssertEqual(svc4.refCount, 0)
    }

    func testUncalibratedCantBake() throws {
        let src = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_plain.pdf"))
        let g = try XCTUnwrap(PdfGeoreference.provisional(pageBox: CGRect(x: 0, y: 0, width: 824, height: 1051), rotation: 0,
                                                          centredOn: .init(latitude: 37.7, longitude: -122.4)))
        controller.prepare(pdf: PDFMapSource(url: src, georef: g), runtime: nil)
        XCTAssertEqual(controller.state, .failed(.notCalibrated))
    }

    func testWriterCreatesTheFileWithTheRightProtectionAndNoJournal() throws {
        let path = dir.appendingPathComponent("w.mbtiles.partial").path
        let w = try XCTUnwrap(MBTilesWriter(path: path))
        // OD-F5: the pragma has to answer off (or memory where defensive mode
        // ignores off, the iOS system SQLite), never a mode with a journal file
        XCTAssertTrue(["off", "memory"].contains(w.journalMode ?? ""), "journal_mode \(w.journalMode ?? "nil")")
        w.writeMetadata(name: "t", format: "jpg", minZoom: 0, maxZoom: 1, minLon: -1, minLat: -1, maxLon: 1, maxLat: 1,
                        extra: ["tacmap_tile_px": "512"])
        w.begin()
        for i in 0..<130 { w.putTile(z: 7, x: i, y: 1, data: Data(repeating: UInt8(i & 0xFF), count: 8192)) }
        // mid transaction is when a DELETE mode journal would be sitting there
        XCTAssertFalse(FileManager.default.fileExists(atPath: path + "-journal"), "no journal mid transaction")
        w.commit()
        XCTAssertFalse(w.hadError)
        w.close()
        let attrs = try FileManager.default.attributesOfItem(atPath: path)
        if let prot = attrs[.protectionKey] as? FileProtectionType {
            XCTAssertEqual(prot, .completeUntilFirstUserAuthentication)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: path + "-journal"))
        var db: OpaquePointer?
        XCTAssertEqual(sqlite3_open(path, &db), SQLITE_OK)
        defer { sqlite3_close(db) }
        var stmt: OpaquePointer?
        sqlite3_prepare_v2(db, "SELECT count(*) FROM tiles", -1, &stmt, nil)
        sqlite3_step(stmt)
        XCTAssertEqual(sqlite3_column_int(stmt, 0), 130)
        sqlite3_finalize(stmt)
    }

    /// C5 / R6: the first sqlite error is the one reported. a BEGIN failing
    /// after SQLITE_FULL must not turn noSpace into writeFailed
    func testWriterKeepsTheFirstErrorCode() throws {
        let path = dir.appendingPathComponent("full.mbtiles.partial").path
        let w = try XCTUnwrap(MBTilesWriter(path: path))
        w.writeMetadata(name: "t", format: "png", minZoom: 0, maxZoom: 1, minLon: -1, minLat: -1, maxLon: 1, maxLat: 1)
        w.limitPagesForTesting(16)
        w.begin()
        var i = 0
        while !w.hadError, i < 400 { w.putTile(z: 9, x: i, y: 1, data: Data(repeating: 7, count: 4096)); i += 1 }
        XCTAssertTrue(w.hadError)
        XCTAssertEqual(w.lastErrorCode, SQLITE_FULL)
        w.commit()
        w.begin()
        w.exec("THIS IS NOT SQL")
        XCTAssertEqual(w.lastErrorCode, SQLITE_FULL, "later errors dont replace the first")
        XCTAssertEqual(PDFBakeWorker.errorFor(w, neededBytes: 77), .noSpace(neededBytes: 77))
        w.close()
    }

    /// F1: a cancel that lands after the last tile, before the hop back to main,
    /// publishes nothing and leaves nothing on disk
    func testLateCancelAfterTheLastTilePublishesNothing() throws {
        pdf = try makePDF()
        let p = try proposal()
        controller.afterRunHook = { $0.cancel() }
        controller.start(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        waitFor("bake end") { !controller.isRunning }
        XCTAssertEqual(controller.state, .idle)
        XCTAssertFalse(controller.finishedMessage)
        XCTAssertNil(pdf.bake)
        XCTAssertTrue(persisted.isEmpty, "no record written")
        let finals = (try? FileManager.default.contentsOfDirectory(atPath: controller.finalDirectory.path)) ?? []
        XCTAssertTrue(finals.isEmpty, "nothing published: \(finals)")
        let work = (try? FileManager.default.contentsOfDirectory(atPath: PDFBakeWorker.workDirectory.path)) ?? []
        XCTAssertTrue(work.filter { $0.hasSuffix(".partial") }.isEmpty)
        XCTAssertNil(controller.subjectToken)
    }

    /// F3: the estimate holds the K1 bake slot from before its first sample
    /// till it ends, with the token the bake uses. a crash mid estimate =
    /// bakeInterrupted next launch
    func testEstimateArmsTheBakeSlot() throws {
        pdf = try makePDF()
        let guardURL = dir.appendingPathComponent("guard.json")
        controller.prepare(pdf: pdf, runtime: nil)
        XCTAssertEqual(controller.state, .estimating)
        XCTAssertEqual(controller.guardStore.snapshot.bakeInProgress, pdf.renderGuardToken)
        // what a relaunch would read off disk right now
        XCTAssertEqual(PDFRenderGuard(url: guardURL).snapshot.bakeInProgress, pdf.renderGuardToken)
        waitFor("confirm") { controller.state != .estimating }
        guard case .confirming = controller.state else { return XCTFail("\(controller.state)") }
        XCTAssertNil(controller.guardStore.snapshot.bakeInProgress, "estimate over, slot cleared")
        // Generate arms it again for the bake itself
        controller.generate()
        XCTAssertTrue(controller.isRunning)
        XCTAssertEqual(controller.guardStore.snapshot.bakeInProgress, pdf.renderGuardToken)
        controller.cancel(for: pdf)
        XCTAssertNil(controller.guardStore.snapshot.bakeInProgress)
        // cancelled mid estimate clears it too
        controller.prepare(pdf: pdf, runtime: nil)
        XCTAssertEqual(controller.guardStore.snapshot.bakeInProgress, pdf.renderGuardToken)
        controller.cancel()
        XCTAssertNil(controller.guardStore.snapshot.bakeInProgress)
        // and a "crash" mid estimate: the next launch sees it
        controller.prepare(pdf: pdf, runtime: nil)
        let relaunch = PDFRenderGuard(url: guardURL).launchDecision(restoredToken: nil)
        XCTAssertTrue(relaunch.bakeInterrupted)
        controller.cancel()
    }

    /// F7: Delete Map stops the bake of that PDF even with no contentKey, and
    /// even when the delete flow holds a different object for the same map
    func testDeleteStopsTheBakeByTokenOrFile() throws {
        pdf = try makePDF()
        let p = try proposal()
        controller.start(maxZoom: try XCTUnwrap(p.options.last).maxZoom)
        XCTAssertTrue(controller.isRunning)
        let other = PDFMapSource(url: URL(fileURLWithPath: "/nope.pdf"), georef: pdf.georef)
        controller.cancel(for: other)
        XCTAssertTrue(controller.isRunning, "some other map doesnt stop it")
        // same file, no contentKey, own token: what a restored retained entry looks like
        let sameFile = PDFMapSource(url: pdf.url, georef: pdf.georef, contentKey: nil)
        controller.cancel(for: sameFile)
        XCTAssertFalse(controller.isRunning)
        XCTAssertNil(controller.heldService)
        // and by token alone
        pdf = try makePDF()
        let p2 = try proposal()
        controller.start(maxZoom: try XCTUnwrap(p2.options.last).maxZoom)
        let sameToken = PDFMapSource(url: URL(fileURLWithPath: "/moved.pdf"), georef: pdf.georef, contentKey: nil,
                                     renderGuardToken: pdf.renderGuardToken)
        controller.cancel(for: sameToken)
        XCTAssertFalse(controller.isRunning)
    }

    /// OD-F1: Remove changes something the sheet observes, so it redraws at once
    func testRemoveAndPublishBumpTheRecordRevision() throws {
        pdf = try makePDF()
        pdf.bake = PDFBakeRecord(fileName: "x.mbtiles", bakeKey: String(repeating: "a", count: 64), minZoom: 0,
                                 maxZoom: 14, tilePx: 512, bytes: 1)
        let before = controller.recordRevision
        XCTAssertTrue(controller.removeBake(from: pdf))
        XCTAssertNil(pdf.bake)
        XCTAssertEqual(controller.recordRevision, before + 1)
        let p = try proposal()
        controller.start(maxZoom: try XCTUnwrap(p.options.first).maxZoom)
        waitFor("bake") { !controller.isRunning }
        XCTAssertNotNil(pdf.bake)
        XCTAssertEqual(controller.recordRevision, before + 2)
    }

    /// R4: a document level failure or a blank base raster in a sample sinks
    /// the whole estimate. anything else is just a skipped sample
    func testEstimateSampleDocumentFailuresFailTheEstimate() throws {
        let g = try XCTUnwrap(F.georef(sheet: "sf_iso"))
        let box = try XCTUnwrap(F.pageBox(sheet: "sf_iso"))
        func run(base: Result<PDFLaneOutput, Error>?, tiles: Result<PDFLaneOutput, Error>?, rasterLevel: Bool) throws
            -> PDFBakeController.MeasureEnd {
            let ctx = try PDFRenderContext(url: URL(fileURLWithPath: "/dev/null"),
                                           identity: PDFDocumentIdentity(contentKey: "r4:\(UUID())", pageIndex: 0),
                                           georef: g, pageBox: box, tilePx: 512, guardToken: UUID().uuidString,
                                           baseBudgetPx: PDFTileConstants.baseBudgetPx)
            let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 1, execute: { _, w, done in
                DispatchQueue.main.async {
                    switch w {
                    case .base: done(base ?? .failure(PDFRenderFailure.renderError))
                    default: done(tiles ?? .failure(PDFRenderFailure.renderError))
                    }
                }
            })
            svc.usesCrashGuard = false
            let z = rasterLevel ? ctx.baseMaxZoom : ctx.baseMaxZoom + 2
            let level = try XCTUnwrap(ctx.footprint.tiles(z: z)).filter { $0.coverage == .inside }.map(\.tile)
            let worker = PDFBakeWorker(ctx: ctx, service: svc, maxZoom: z)
            var end: PDFBakeController.MeasureEnd?
            DispatchQueue.global().async {
                let e = PDFBakeController.runSamples(picks: Array(level.prefix(2)), ctx: ctx, defaultZoom: z, worker: worker)
                DispatchQueue.main.async { end = e }
            }
            waitFor("samples") { end != nil }
            return try XCTUnwrap(end)
        }
        // vector samples failing with document reasons
        for f in [PDFRenderFailure.cannotOpen, .passwordProtected, .pageMissing, .pageGeometry] {
            XCTAssertEqual(try run(base: nil, tiles: .failure(f), rasterLevel: false), .documentFailure, "\(f)")
        }
        // an ordinary render error is a skipped sample, the fallbacks take over
        guard case .done(let s) = try run(base: nil, tiles: .failure(PDFRenderFailure.renderError), rasterLevel: false) else {
            return XCTFail("renderError sinks the estimate")
        }
        XCTAssertTrue(s.allSatisfy { $0.result == .failed })
        // raster level: a blank base raster, and a base that wont open
        let px = PDFRenderSchedulerTests.pixel()
        let blank = PDFPageRaster(levels: [px], region: box, densityPxPerPt: 1, blank: true,
                                  planKey: "x")
        XCTAssertEqual(try run(base: .success(.base(blank)), tiles: nil, rasterLevel: true), .documentFailure)
        XCTAssertEqual(try run(base: .failure(PDFRenderFailure.cannotOpen), tiles: nil, rasterLevel: true), .documentFailure)
    }

    // MARK: R2-S1 encode pipeline with a backlog

    /// encoder stand in. every call parks until the test lets it through,
    /// result gets the release order (1 = first one let out)
    final class GateEncoder {
        private let cond = NSCondition()
        private var permits = 0
        private var open = false
        private var calls = 0
        private var released = 0
        let result: (Int) -> Data?

        init(result: @escaping (Int) -> Data? = { _ in Data(repeating: 7, count: 2048) }) { self.result = result }

        func encode(_ img: CGImage) -> Data? {
            cond.lock()
            calls += 1
            while !open && permits == 0 { cond.wait() }
            if !open { permits -= 1 }
            released += 1
            let n = released
            cond.unlock()
            return result(n)
        }

        func allow(_ n: Int) { cond.lock(); permits += n; cond.broadcast(); cond.unlock() }
        func openAll() { cond.lock(); open = true; cond.broadcast(); cond.unlock() }
        var callCount: Int { cond.lock(); defer { cond.unlock() }; return calls }
    }

    enum Trigger { case cancel, releaseOneAtATime }

    /// start the biggest option with a gated encoder, wait till every lane is
    /// stuck and the backlog is full (so ops are sitting queued), pull the
    /// trigger, let the encoder go only once the pipeline has shut down. Any
    /// slot that doesnt come back traps when the pipeline is freed (R2-S1)
    @discardableResult
    func bakeWithFullBacklog(_ gate: GateEncoder, trigger: Trigger,
                             putHook: ((MBTilesWriter, Int) -> Void)? = nil) throws -> PDFBakeOptionEstimate {
        defer { gate.openAll() }
        pdf = try makePDF()
        let p = try proposal()
        let option = try XCTUnwrap(p.options.last)
        XCTAssertGreaterThan(option.tiles, PDFBakeWorker.encodeBacklog + PDFBakeWorker.encodeLanes)
        var worker: PDFBakeWorker?
        controller.configureWorker = { w in
            w.encodeTile = gate.encode
            w.beforePutHook = putHook
            worker = w
        }
        controller.start(maxZoom: option.maxZoom)
        let w = try XCTUnwrap(worker)
        waitFor("backlog full, lanes stuck") {
            w.encodesInFlight == PDFBakeWorker.encodeBacklog && gate.callCount == PDFBakeWorker.encodeLanes
        }
        XCTAssertTrue(controller.isRunning)
        var allowed = 0
        switch trigger {
        case .cancel:
            controller.cancel()
            waitFor("pipeline shut down", timeout: 10) { w.encodeStopped }
        case .releaseOneAtATime:
            // one encode at a time till the failure lands, the rest stay stuck or queued
            for _ in 0..<40 where !w.encodeStopped {
                gate.allow(1)
                allowed += 1
                let end = Date().addingTimeInterval(1)
                while !w.encodeStopped, Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
            }
            XCTAssertTrue(w.encodeStopped, "the failure never shut the pipeline")
        }
        XCTAssertTrue(w.encodesInFlight > PDFBakeWorker.encodeLanes - 1, "ops still pending at shutdown")
        gate.openAll()
        waitFor("bake end") { !controller.isRunning }
        waitFor("every op done", timeout: 10) { w.encodesInFlight == 0 }
        XCTAssertGreaterThan(w.encodesSkipped, 0, "queued ops ran but skipped their encode")
        // stuck lanes + one fresh op per release, nothing that was still queued at shutdown
        XCTAssertLessThanOrEqual(gate.callCount, PDFBakeWorker.encodeLanes + allowed, "nothing encoded after shutdown")
        assertNothingLeftBehind()
        return option
    }

    func assertNothingLeftBehind(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(pdf.bake, file: file, line: line)
        XCTAssertTrue(persisted.isEmpty, "no record written", file: file, line: line)
        let finals = (try? FileManager.default.contentsOfDirectory(atPath: controller.finalDirectory.path)) ?? []
        XCTAssertTrue(finals.isEmpty, "nothing published: \(finals)", file: file, line: line)
        let work = (try? FileManager.default.contentsOfDirectory(atPath: PDFBakeWorker.workDirectory.path)) ?? []
        XCTAssertTrue(work.filter { $0.contains(".partial") }.isEmpty, "left behind: \(work)", file: file, line: line)
    }

    func testCancelWithAFullEncodeBacklogEndsIdle() throws {
        try bakeWithFullBacklog(GateEncoder(), trigger: .cancel)
        XCTAssertEqual(controller.state, .idle)
        XCTAssertNil(controller.subjectToken)
        XCTAssertNil(controller.heldService)
    }

    func testNilEncodeMidBakeIsRenderFailed() throws {
        try bakeWithFullBacklog(GateEncoder(result: { $0 == 1 ? nil : Data(repeating: 7, count: 2048) }),
                                trigger: .releaseOneAtATime)
        XCTAssertEqual(controller.state, .failed(.renderFailed))
    }

    func testWriterErrorMidBakeIsWriteFailed() throws {
        var broke = false
        try bakeWithFullBacklog(GateEncoder(), trigger: .releaseOneAtATime) { w, _ in
            guard !broke else { return }
            broke = true
            w.exec("THIS IS NOT SQL")
        }
        XCTAssertTrue(broke)
        XCTAssertEqual(controller.state, .failed(.writeFailed))
    }

    func testFullDiskMidBakeIsNoSpace() throws {
        var limited = false
        let option = try bakeWithFullBacklog(GateEncoder(result: { _ in Data(repeating: 9, count: 64 * 1024) }),
                                             trigger: .releaseOneAtATime) { w, _ in
            guard !limited else { return }
            limited = true
            // cant grow past what it has now, the next row is SQLITE_FULL
            w.limitPagesForTesting(1)
        }
        XCTAssertTrue(limited)
        XCTAssertEqual(controller.state, .failed(.noSpace(neededBytes: option.neededBytes)))
    }

    // MARK: R2-S2 Remove deletes the file itself

    func testRemoveDeletesTheBakeAndSidecarsEvenWithThePdfMissing() throws {
        pdf = try makePDF()
        let tiles = controller.finalDirectory
        try FileManager.default.createDirectory(at: tiles, withIntermediateDirectories: true)
        let name = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let other = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let orphan = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let mine = [name, name + "-journal", name + "-wal", name + "-shm"]
        for n in mine + [other, orphan, orphan + "-wal"] { try Data([1]).write(to: tiles.appendingPathComponent(n)) }
        // R3-2: the library (another sheet, say) still names other. it stays,
        // the unnamed orphan goes with the Remove's sweep
        controller.storedBake = { [other] }
        let record = PDFBakeRecord(fileName: name, bakeKey: String(repeating: "a", count: 64), minZoom: 0,
                                   maxZoom: 14, tilePx: 512, bytes: 1)
        pdf.bake = record
        // OD-F4 state: the stored PDF is gone, the reconcile refuses to run like this
        try FileManager.default.removeItem(at: pdf.url)
        pdf.storedFileUnavailable = true
        XCTAssertTrue(controller.removeBake(from: pdf))
        XCTAssertNil(pdf.bake)
        XCTAssertTrue(persisted.first === pdf, "record cleared first")
        for n in mine {
            XCTAssertFalse(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(n).path), "\(n) still there")
        }
        XCTAssertTrue(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(other).path), "the named bake stays")
        for n in [orphan, orphan + "-wal"] {
            XCTAssertFalse(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(n).path), "\(n) swept")
        }

        // a record write that fails keeps the record and the file
        let keep = PDFBakeRecord(fileName: other, bakeKey: String(repeating: "b", count: 64), minZoom: 0,
                                 maxZoom: 14, tilePx: 512, bytes: 1)
        pdf.bake = keep
        controller.detachRecord = { _, _ in false }
        XCTAssertFalse(controller.removeBake(from: pdf))
        XCTAssertEqual(pdf.bake, keep)
        XCTAssertTrue(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(other).path))
    }

    func testRemoveNeverFollowsALinkOrLeavesTheDirectory() throws {
        let tiles = dir.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createDirectory(at: tiles, withIntermediateDirectories: true)
        let outside = dir.appendingPathComponent("precious.mbtiles")
        try Data([1]).write(to: outside)
        let name = "tacmap-bake-\(UUID().uuidString).mbtiles"
        try FileManager.default.createSymbolicLink(at: tiles.appendingPathComponent(name), withDestinationURL: outside)
        let link = PDFBakeRecord(fileName: name, bakeKey: String(repeating: "c", count: 64), minZoom: 0,
                                 maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertFalse(ManagedImportedMapFileLifecycle.removeGeneratedBake(link, in: tiles))
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.path))
        // a name that walks out of offline_tiles is refused before anything is touched
        let escape = PDFBakeRecord(fileName: "../precious.mbtiles", bakeKey: String(repeating: "c", count: 64),
                                   minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertFalse(ManagedImportedMapFileLifecycle.removeGeneratedBake(escape, in: tiles))
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.path))
        // nothing there at all is fine
        let gone = PDFBakeRecord(fileName: "tacmap-bake-gone.mbtiles", bakeKey: String(repeating: "c", count: 64),
                                 minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertTrue(ManagedImportedMapFileLifecycle.removeGeneratedBake(gone, in: tiles))
        // R3-5: a valid looking record without our prefix is never deleted
        let userFile = tiles.appendingPathComponent("training-area.mbtiles")
        try Data([1]).write(to: userFile)
        let foreign = PDFBakeRecord(fileName: userFile.lastPathComponent, bakeKey: String(repeating: "c", count: 64),
                                    minZoom: 0, maxZoom: 14, tilePx: 512, bytes: 1)
        XCTAssertTrue(PDFSessionStore.validBake(foreign))
        XCTAssertFalse(ManagedImportedMapFileLifecycle.removeGeneratedBake(foreign, in: tiles))
        XCTAssertTrue(FileManager.default.fileExists(atPath: userFile.path))
    }

    // MARK: R3-2 bake only sweep

    func testSweepDeletesOnlyUnreferencedGeneratedBakes() throws {
        let fm = FileManager.default
        let tiles = dir.appendingPathComponent("offline_tiles", isDirectory: true)
        try fm.createDirectory(at: tiles, withIntermediateDirectories: true)
        let keep = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let orphan = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let legacy = "tacmap-\(UUID().uuidString).mbtiles"   // pre WP2 tiler output
        let stays = [keep, keep + "-wal", legacy, "user.mbtiles", "tacmap-bake-notes.txt"]
        let goes = [orphan, orphan + "-wal", orphan + "-shm", orphan + "-journal"]
        for n in stays + goes { try Data([1]).write(to: tiles.appendingPathComponent(n)) }
        // a dir and a link wearing bake names are never followed
        let dirNamed = tiles.appendingPathComponent("tacmap-bake-dir.mbtiles", isDirectory: true)
        try fm.createDirectory(at: dirNamed, withIntermediateDirectories: true)
        try Data([1]).write(to: dirNamed.appendingPathComponent("inner"))
        let outside = dir.appendingPathComponent("precious.mbtiles")
        try Data([1]).write(to: outside)
        try fm.createSymbolicLink(at: tiles.appendingPathComponent("tacmap-bake-link.mbtiles"), withDestinationURL: outside)

        XCTAssertFalse(ManagedImportedMapFileLifecycle.sweepUnreferencedBakes(in: tiles, keeping: keep),
                       "the dir and the link make it incomplete")
        for n in stays { XCTAssertTrue(fm.fileExists(atPath: tiles.appendingPathComponent(n).path), "\(n) kept") }
        for n in goes { XCTAssertFalse(fm.fileExists(atPath: tiles.appendingPathComponent(n).path), "\(n) swept") }
        XCTAssertTrue(fm.fileExists(atPath: dirNamed.appendingPathComponent("inner").path))
        XCTAssertTrue(fm.fileExists(atPath: outside.path))
        XCTAssertNotNil(try? fm.destinationOfSymbolicLink(atPath: tiles.appendingPathComponent("tacmap-bake-link.mbtiles").path))
        // a missing directory is nothing to do
        XCTAssertTrue(ManagedImportedMapFileLifecycle.sweepUnreferencedBakes(
            in: dir.appendingPathComponent("nope", isDirectory: true), keeping: nil))
    }

    func testSweepNeverRunsOnAnUnreadableSession() throws {
        let tiles = dir.appendingPathComponent("offline_tiles", isDirectory: true)
        try FileManager.default.createDirectory(at: tiles, withIntermediateDirectories: true)
        let a = "tacmap-bake-\(UUID().uuidString).mbtiles"
        try Data([1]).write(to: tiles.appendingPathComponent(a))
        XCTAssertFalse(PDFBakeController.sweepUnreferencedBakes(in: tiles, storedBake: { nil }))
        XCTAssertTrue(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(a).path), "locked = untouched")
        XCTAssertTrue(PDFBakeController.sweepUnreferencedBakes(in: tiles, storedBake: { [a] }))
        XCTAssertTrue(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(a).path), "named = kept")
        XCTAssertTrue(PDFBakeController.sweepUnreferencedBakes(in: tiles, storedBake: { [] }))
        XCTAssertFalse(FileManager.default.fileExists(atPath: tiles.appendingPathComponent(a).path), "named by nobody = swept")
    }

    // MARK: R2-S4 flagged sources

    /// the estimate itself re-checks the bytes off main, the UI gate is not the only guard
    func testFlaggedSourceWhoseBytesDontCheckOutCantEstimate() throws {
        pdf = try makePDF()
        // outside ImportedMaps, so the stored file check can never pass
        pdf.storedFileUnavailable = true
        controller.prepare(pdf: pdf, runtime: nil)
        waitFor("estimate") { controller.state != .estimating }
        XCTAssertEqual(controller.state, .failed(.renderFailed))
        XCTAssertNil(controller.heldService)
    }

    func testGenerateIsBlockedWhileAFlaggedSourceIsStillPreparing() throws {
        pdf = try makePDF()
        XCTAssertFalse(PDFBakeController.generateBlocked(pdf: pdf, status: .ready))
        XCTAssertTrue(PDFBakeController.generateBlocked(pdf: pdf, status: .failed(.cannotOpen)))
        pdf.storedFileUnavailable = true
        // after Try Again the status is .preparing until the async hash check lands
        XCTAssertTrue(PDFBakeController.generateBlocked(pdf: pdf, status: .preparing))
        // the runtime clears the flag once the hash matched, then its ready
        pdf.storedFileUnavailable = false
        XCTAssertFalse(PDFBakeController.generateBlocked(pdf: pdf, status: .preparing))
        XCTAssertFalse(PDFBakeController.generateBlocked(pdf: pdf, status: .ready))
    }

    static func metadataValue(_ file: URL, _ key: String) -> String? {
        var db: OpaquePointer?
        guard sqlite3_open_v2(file.path, &db, SQLITE_OPEN_READONLY, nil) == SQLITE_OK else { return nil }
        defer { sqlite3_close(db) }
        var stmt: OpaquePointer?
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_prepare_v2(db, "SELECT value FROM metadata WHERE name = ?", -1, &stmt, nil) == SQLITE_OK else { return nil }
        sqlite3_bind_text(stmt, 1, key, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
        guard sqlite3_step(stmt) == SQLITE_ROW, let c = sqlite3_column_text(stmt, 0) else { return nil }
        return String(cString: c)
    }

    /// the work dir is outside the reconcile roots, a map switch mid bake cant eat it
    func testReconcileNeverTouchesTheWorkDirectory() throws {
        let support = dir.appendingPathComponent("support", isDirectory: true)
        let imported = support.appendingPathComponent("ImportedMaps", isDirectory: true)
        let tiles = support.appendingPathComponent("offline_tiles", isDirectory: true)
        let work = support.appendingPathComponent("pdf_bake_work", isDirectory: true)
        for d in [imported, tiles, work] { try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true) }
        let partial = work.appendingPathComponent("abc.mbtiles.partial")
        let stray = tiles.appendingPathComponent("old.mbtiles.partial")
        try Data([1]).write(to: partial)
        try Data([1]).write(to: stray)
        XCTAssertTrue(ManagedImportedMapFileLifecycle.reconcile(directories: [imported, tiles], keeping: []))
        XCTAssertTrue(FileManager.default.fileExists(atPath: partial.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: stray.path))
    }

    /// J3: RGB, 8 bit, peak 255, over the pixels that are opaque in the live
    /// tile (b). Same as Android's PdfBakeInstrumentedTest.psnr
    func psnr(_ a: CGImage, _ b: CGImage) -> Double {
        guard let pa = PDFTileRenderer.pixels(a), let pb = PDFTileRenderer.pixels(b),
              a.width == b.width, a.height == b.height else { return 0 }
        var se = 0.0, n = 0.0
        for y in 0..<a.height {
            for x in 0..<a.width {
                let i = y * pa.bytesPerRow + x * 4, j = y * pb.bytesPerRow + x * 4
                guard pb.data[j + 3] == 255 else { continue }
                for c in 0..<3 {
                    let d = Double(pa.data[i + c]) - Double(pb.data[j + c])
                    se += d * d
                    n += 1
                }
            }
        }
        guard n > 0 else { return 0 }
        let mse = se / n
        return mse == 0 ? 99 : 10 * log10(255 * 255 / mse)
    }

    /// live render of one tile the way the view would draw it
    func liveTile(_ t: TileIndex, ctx: PDFRenderContext, page: CGPDFPage, base: PDFPageRaster?) throws -> CGImage? {
        let job = TileJob(z: t.z, x0: t.x, y0: t.y, cols: 1, rows: 1)
        let plan = PDFTileWarp.plan(job: job, tilePx: ctx.tilePx, footprint: ctx.footprint, georef: ctx.georef)
        let src: PDFRenderSourceKind
        if t.z <= ctx.baseMaxZoom, let base { src = .raster(base) }
        else { src = plan.cells.count == 1 ? .vector(page) : .staged(page) }
        let out = try PDFTileRenderer.renderJob(job, tilePx: ctx.tilePx, plan: plan, footprint: ctx.footprint, source: src)
        if case .image(let img)? = out[t] { return img }
        return nil
    }

    func context(url: URL, georef: PdfGeoreference, tilePx: Int) throws -> (PDFRenderContext, CGPDFPage, CGPDFDocument) {
        let (doc, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        let ctx = try PDFRenderContext(url: url, identity: PDFDocumentIdentity(contentKey: "psnr:\(UUID())", pageIndex: 0),
                                       georef: georef, pageBox: PDFTileRenderer.pageBox(page), tilePx: tilePx,
                                       guardToken: UUID().uuidString, baseBudgetPx: PDFTileConstants.baseBudgetPx)
        return (ctx, page, doc)
    }

    /// J3 gate on exactly the fixture's psnr tiles (r1 D1): for tilePx 768 and
    /// 512, baseMaxZoom..D, the middle INSIDE tile per z, on rot5_iso and on
    /// the shared dense sheet (geopdf/tacmap_render_dense.pdf, the same file
    /// Android gates on). Baked vs live, worst over all of them
    func gatePSNR(sheet s: [String: Any]) throws -> (worst: Double, vectorZooms: Int, checked: Int) {
        let url = try XCTUnwrap(F.testdataURL(s["file"] as! String))
        let georef = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        var worst = 99.0, vector = 0, checked = 0
        for chk in s["checks"] as? [[String: Any]] ?? [] {
            let tilePx = F.int(chk["tilePx"])
            let (ctx, page, _) = try context(url: url, georef: georef, tilePx: tilePx)
            XCTAssertEqual(ctx.baseMaxZoom, F.int(chk["baseMaxZoom"]), "\(s["sheet"]!) @\(tilePx) baseMaxZoom")
            XCTAssertEqual(ctx.detailZoom, F.int(chk["detailZoom"]), "\(s["sheet"]!) @\(tilePx) detailZoom")
            let base = try PDFTileRenderer.renderBaseRaster(page: page, plan: ctx.basePlan, footprint: ctx.footprint)
            var log: [String] = []
            for zz in chk["zooms"] as? [[String: Any]] ?? [] {
                let z = F.int(zz["z"]), xy = try XCTUnwrap(zz["tile"] as? [Int])
                let t = TileIndex(z: z, x: xy[0], y: xy[1])
                XCTAssertEqual(ctx.footprint.classify(t), .inside, "\(t) is INSIDE")
                let isVector = z > ctx.baseMaxZoom
                XCTAssertEqual(isVector ? "vector" : "raster", zz["path"] as? String, "\(t) path")
                let live = try XCTUnwrap(try liveTile(t, ctx: ctx, page: page, base: base), "\(s["sheet"]!) \(t) live")
                let data = try XCTUnwrap(PDFBakeWorker.encode(live))
                let baked = try XCTUnwrap(UIImage(data: data)?.cgImage)
                let p = psnr(baked, live)
                log.append(String(format: "z%d %.2f dB %@ %d B", z, p, data.starts(with: [0x89, 0x50]) ? "png" : "jp2", data.count))
                worst = min(worst, p)
                checked += 1
                if isVector { vector += 1 }
            }
            print("[J3] \(s["sheet"]!) @\(tilePx): \(log.joined(separator: ", "))")
        }
        return (worst, vector, checked)
    }

    /// J3 gate, both suites, PSNR >= the production constant (== constants.bake.psnrGateDb)
    func testBakeEncodingClearsThePsnrGate() throws {
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        let p = try XCTUnwrap(F.fx["psnr"] as? [String: Any])
        XCTAssertEqual(F.dbl(p["gateDb"]), PDFTileConstants.bakePsnrGateDb)
        let sheets = try XCTUnwrap(p["sheets"] as? [[String: Any]])
        XCTAssertEqual(Set(sheets.compactMap { $0["sheet"] as? String }), ["rot5_iso", "dense"])
        for s in sheets {
            let r = try gatePSNR(sheet: s)
            let want = (s["checks"] as? [[String: Any]] ?? []).reduce(0) { $0 + (($1["zooms"] as? [Any])?.count ?? 0) }
            XCTAssertEqual(r.checked, want, "\(s["sheet"]!) every fixture tile checked")
            XCTAssertGreaterThanOrEqual(r.vectorZooms, 1, "\(s["sheet"]!) at least one vector zoom")
            print(String(format: "[J3] min PSNR %@ %.2f dB", s["sheet"] as! String, r.worst))
            XCTAssertGreaterThanOrEqual(r.worst, PDFTileConstants.bakePsnrGateDb, "\(s["sheet"]!)")
        }
    }

    /// J3 on grainy content: JP2 at a fixed quality is a byte budget, so an
    /// orthoimage / photographed paper map came out ~28 dB. the encoder has to
    /// notice and step up until the tile clears the gate
    func testPhotographicTilesStillClearThePsnrGate() throws {
        let live = try XCTUnwrap(Self.grainyTile(px: 768))
        XCTAssertTrue(PDFBakeWorker.isOpaque(live))
        let data = try XCTUnwrap(PDFBakeWorker.encode(live))
        let baked = try XCTUnwrap(UIImage(data: data)?.cgImage)
        XCTAssertGreaterThanOrEqual(psnr(baked, live), PDFTileConstants.bakePsnrGateDb)
    }

    func testLineworkTilesStayOnTheCheapRung() throws {
        let ctx = try XCTUnwrap(PDFTileRenderer.makeContext(width: 768, height: 768))
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fill(CGRect(x: 0, y: 0, width: 768, height: 768))
        ctx.setStrokeColor(UIColor.red.cgColor)
        ctx.setLineWidth(1)
        for i in stride(from: 0, to: 768, by: 24) {
            ctx.move(to: CGPoint(x: i, y: 0)); ctx.addLine(to: CGPoint(x: i, y: 768))
            ctx.move(to: CGPoint(x: 0, y: i)); ctx.addLine(to: CGPoint(x: 768, y: i))
        }
        ctx.strokePath()
        let live = try XCTUnwrap(ctx.makeImage())
        let data = try XCTUnwrap(PDFBakeWorker.encode(live))
        // q0.9 is ~320 KB a tile here, lossless JP2 is ~3x that
        XCTAssertLessThan(data.count, 400_000, "linework should not need the retry rungs")
        let baked = try XCTUnwrap(UIImage(data: data)?.cgImage)
        XCTAssertGreaterThanOrEqual(psnr(baked, live), PDFTileConstants.bakePsnrGateDb)
    }

    /// deterministic photo-ish texture: value noise octaves per channel plus per pixel grain
    static func grainyTile(px: Int) -> CGImage? {
        guard let ctx = PDFTileRenderer.makeContext(width: px, height: px), let base = ctx.data else { return nil }
        let p = base.assumingMemoryBound(to: UInt8.self)
        let bpr = ctx.bytesPerRow
        func h(_ x: Int, _ y: Int, _ s: Int) -> Double {
            var v = UInt64(truncatingIfNeeded: x &* 374761393 &+ y &* 668265263 &+ s &* 2147483647)
            v = (v ^ (v >> 13)) &* 1274126177
            v ^= v >> 16
            return Double(v & 0xffff) / 65535.0
        }
        func noise(_ x: Double, _ y: Double, _ s: Int) -> Double {
            let x0 = Int(floor(x)), y0 = Int(floor(y))
            let fx = x - floor(x), fy = y - floor(y)
            let a = h(x0, y0, s), b = h(x0 + 1, y0, s), c = h(x0, y0 + 1, s), d = h(x0 + 1, y0 + 1, s)
            return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
        }
        for y in 0..<px {
            for x in 0..<px {
                // BGRA premultiplied, opaque so premultiplied == straight
                for (ch, off) in [(0, 2), (1, 1), (2, 0)] {
                    var v = 0.0, amp = 0.5, f = 1.0 / 48.0
                    for o in 0..<6 { v += amp * noise(Double(x) * f, Double(y) * f, ch * 7 + o); amp *= 0.55; f *= 2.1 }
                    v += 0.16 * (h(x, y, ch + 99) - 0.5)
                    p[y * bpr + x * 4 + off] = UInt8(max(0, min(255, v * 300)))
                }
                p[y * bpr + x * 4 + 3] = 255
            }
        }
        return ctx.makeImage()
    }

    static func sampleURL(_ name: String) -> URL? {
        var d = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let c = d.appendingPathComponent("samples").appendingPathComponent(name)
            if FileManager.default.fileExists(atPath: c.path) { return c }
            d = d.deletingLastPathComponent()
        }
        return nil
    }

    /// J3 numbers for the contract table: the real USGS sheet at 768 px and the
    /// default maxZoom (z15), the 3 J2 sample tiles plus 10 more INSIDE tiles
    func testUSGSBakeBytesPerTile() throws {
        guard let url = Self.sampleURL("USGS_SF_North.pdf") else { throw XCTSkip("samples/USGS_SF_North.pdf not here") }
        PDFTileRenderer.assertsOffMainThread = false
        defer { PDFTileRenderer.assertsOffMainThread = true }
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let (ctx, page, _) = try context(url: url, georef: g, tilePx: 768)
        let opts = PDFBakePlan.options(detailZoom: ctx.detailZoom, footprint: ctx.footprint)
        let z = try XCTUnwrap(PDFBakePlan.defaultOption(opts, detailZoom: ctx.detailZoom)).maxZoom
        XCTAssertEqual(z, 15)
        let level = try XCTUnwrap(ctx.footprint.tiles(z: z))
        let c = g.toWGS84(x: ctx.footprint.clipMean.x, y: ctx.footprint.clipMean.y).map { ($0.latitude, $0.longitude) }
        let picks = PDFBakeEstimator.sampleTiles(level: level, z: z, centre: c).tiles
        let inside = level.filter { $0.coverage == .inside }.map(\.tile).filter { !picks.contains($0) }
        let step = max(1, inside.count / 10)
        let more = stride(from: step / 2, to: inside.count, by: step).prefix(10).map { inside[$0] }
        var sizes: [Int] = []
        var worst = 99.0
        for t in picks + more {
            let live = try XCTUnwrap(try liveTile(t, ctx: ctx, page: page, base: nil))
            let data = try XCTUnwrap(PDFBakeWorker.encode(live))
            sizes.append(data.count)
            worst = min(worst, psnr(try XCTUnwrap(UIImage(data: data)?.cgImage), live))
        }
        XCTAssertGreaterThanOrEqual(sizes.count, 13)
        let mean = Double(sizes.reduce(0, +)) / Double(sizes.count)
        print(String(format: "[J3] USGS z%d @768 n=%d mean %.0f B max %d B min PSNR %.2f dB",
                     z, sizes.count, mean, sizes.max() ?? 0, worst))
        XCTAssertGreaterThanOrEqual(worst, PDFTileConstants.bakePsnrGateDb)
    }
}
