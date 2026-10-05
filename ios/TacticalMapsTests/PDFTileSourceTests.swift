import XCTest
import UIKit
import MapKit
@testable import TacticalMaps

/// PDFTileSource + PDFMapRuntime on real lanes against the fixture sheets:
/// load order, status, sticky failure, fallbackZoom, runtime caching.
final class PDFTileSourceTests: XCTestCase {
    typealias F = PDFTileRenderFixtureTests

    #if DEBUG
    func testDeviceAuditWeakBitmapFollowsCacheLifetimeWithoutRetainingIt() throws {
        var retainedBitmap: CGImage?
        weak var temporaryWrapper: UIImage?
        var observation: PDFTileSource.DeviceAuditDeliveredImage?
        try autoreleasepool {
            let context = try XCTUnwrap(CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8,
                                                 bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                                 bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
            let image = try XCTUnwrap(context.makeImage())
            let wrapper = UIImage(cgImage: image)
            temporaryWrapper = wrapper
            retainedBitmap = wrapper.cgImage
            observation = PDFTileSource.DeviceAuditDeliveredImage(image: image, origin: "decoded-bake")
        }
        XCTAssertNil(temporaryWrapper, "The delivery wrapper may expire while its cached bitmap remains")
        XCTAssertNotNil(observation?.image)
        XCTAssertTrue(observation?.image === retainedBitmap)
        XCTAssertEqual(observation?.origin, "decoded-bake")
        retainedBitmap = nil
        XCTAssertNil(observation?.image, "Audit metadata must not keep bitmap contents alive")
    }
    #endif

    func waitFor(_ what: String, timeout: Double = 20, _ cond: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !cond(), Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertTrue(cond(), "timed out waiting for \(what)")
    }

    func context(sheet: String = "sf_iso", file: String = "geopdf/tacmap_grid_sf_iso.pdf",
                 tilePx: Int = 512) throws -> PDFRenderContext {
        let url = try XCTUnwrap(F.testdataURL(file))
        let g = try XCTUnwrap(F.georef(sheet: sheet) ?? GeoPDFReader.read(url: url)?.georef)
        return try PDFRenderContext(url: url, identity: PDFDocumentIdentity(contentKey: "test:\(sheet):\(UUID())", pageIndex: 0),
                                    georef: g, pageBox: try XCTUnwrap(F.pageBox(sheet: sheet)), tilePx: tilePx,
                                    guardToken: UUID().uuidString, baseBudgetPx: PDFTileConstants.baseBudgetPx)
    }

    func service(for ctx: PDFRenderContext) -> PDFRenderService {
        let s = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 1)
        s.usesCrashGuard = false
        return s
    }

    /// tile at z holding the clip centre
    func centreTile(_ ctx: PDFRenderContext, z: Int) throws -> TileIndex {
        let c = try XCTUnwrap(ctx.georef.toWGS84(x: ctx.footprint.clipMean.x, y: ctx.footprint.clipMean.y))
        let w = PDFTileMath.world0(lat: c.latitude, lon: c.longitude)
        let n = pow(2.0, Double(z))
        return TileIndex(z: z, x: Int((w.x * n / 256).rounded(.down)), y: Int((w.y * n / 256).rounded(.down)))
    }

    /// centre of t in z0 world / 256, what the view would pass
    func unit(_ t: TileIndex) -> CGPoint {
        let n = pow(2.0, Double(t.z))
        return CGPoint(x: (Double(t.x) + 0.5) / n, y: (Double(t.y) + 0.5) / n)
    }

    func want(_ src: PDFTileSource, _ tiles: [TileIndex], tz: Int? = nil) {
        let z = tz ?? tiles.first?.z ?? 0
        src.wantedTilesDidChange(tiles, tileZoom: z, centreUnit: unit(tiles.first ?? TileIndex(z: z, x: 0, y: 0)))
    }

    func load(_ src: PDFTileSource, _ t: TileIndex) -> UIImage?? {
        var out: UIImage?? = .none
        let req = src.loadTile(t) { out = .some($0) }
        if req == nil { return .some(nil) }
        waitFor("tile \(t)") { out != nil }
        return out
    }

    func testLoadOrderStatusAndFallback() throws {
        let ctx = try context()
        let src = PDFTileSource(service: service(for: ctx))
        var statuses: [PDFRenderStatus] = []
        src.onStatus = { statuses.append($0) }
        XCTAssertEqual(src.status, .preparing)
        XCTAssertFalse(src.hasContent(try centreTile(ctx, z: 12)), "nothing before the context lands")
        src.install(ctx)
        XCTAssertEqual(src.maxZoom, ctx.detailZoom)
        XCTAssertEqual(src.minZoom, 0)
        XCTAssertNil(src.fallbackZoom(forTileZoom: 16), "no fallback until the base raster exists")
        waitFor("base raster") { src.status == .ready }
        XCTAssertEqual(statuses, [.ready])
        XCTAssertEqual(src.fallbackZoom(forTileZoom: 16), min(15, ctx.baseMaxZoom))
        XCTAssertEqual(src.fallbackZoom(forTileZoom: 3), 2)
        XCTAssertNil(src.fallbackZoom(forTileZoom: 0))

        // raster sampled low zoom
        let low = try centreTile(ctx, z: ctx.baseMaxZoom)
        guard case .some(.some(let lowImg)) = load(src, low) else { return XCTFail("no base raster tile") }
        XCTAssertFalse(lowImg === RasterTileSourceEmpty.image)
        XCTAssertEqual(lowImg.cgImage?.width, 512)

        // vector, through the scheduler (settle has to pass)
        let high = try centreTile(ctx, z: ctx.detailZoom)
        want(src, [high])
        guard case .some(.some(let hiImg)) = load(src, high) else { return XCTFail("no vector tile") }
        XCTAssertFalse(hiImg === RasterTileSourceEmpty.image)

        // off the sheet: empty, and the view never asks
        let off = TileIndex(z: 14, x: high.x / 4 + 40, y: high.y / 4)
        XCTAssertFalse(src.hasContent(off))
        guard case .some(.some(let offImg)) = load(src, off) else { return XCTFail("no empty answer") }
        XCTAssertTrue(offImg === RasterTileSourceEmpty.image)
    }

    func testFailureIsStickyAndStopsAllWork() throws {
        let ctx = try context()
        let src = PDFTileSource(service: service(for: ctx))
        src.install(ctx)
        src.fail(.renderError)
        XCTAssertEqual(src.status, .failed(.renderError))
        src.fail(.blank)
        XCTAssertEqual(src.status, .failed(.renderError), "first failure sticks")
        let t = try centreTile(ctx, z: 14)
        // G1: the failed source stays on the view, coverage is plain geometry so
        // cached tiles and fallbacks keep drawing, it just never delivers again
        XCTAssertTrue(src.hasContent(t))
        XCTAssertNil(src.loadTile(t) { _ in XCTFail("failed source must not deliver") })
        // not even EMPTY for a tile off the sheet
        XCTAssertNil(src.loadTile(TileIndex(z: 14, x: t.x + 50, y: t.y)) { _ in XCTFail("no EMPTY either") })
    }

    /// G1 + G2: a job still running when the source goes sticky is dropped,
    /// and the source stays the one on the view until Try Again
    func testRunningJobLandingAfterTheFailureIsDropped() throws {
        let ctx = try context()
        var held: [(work: PDFLaneWork, done: (Result<PDFLaneOutput, Error>) -> Void)] = []
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 3,
                                   execute: { _, w, d in held.append((w, d)) })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        let a = try centreTile(ctx, z: ctx.detailZoom)
        want(src, [a])
        var got: [UIImage?] = []
        _ = src.loadTile(a) { got.append($0) }
        waitFor("job running") { held.contains { $0.work.isVector } }
        src.fail(.pageGeometry)
        let h = try XCTUnwrap(held.first { $0.work.isVector })
        h.done(.success(.tiles([a: .image(PDFRenderSchedulerTests.pixel())], ms: 5)))
        XCTAssertEqual(got.count, 1)
        XCTAssertNil(got.first ?? UIImage(), "dropped, not delivered")
        XCTAssertEqual(src.orphanCount, 0)
        XCTAssertEqual(src.status, .failed(.pageGeometry))
    }

    /// G1: the container keeps the failed source on the view (cache and
    /// fallbacks keep drawing), Try Again swaps in a fresh one
    func testContainerKeepsTheFailedSourceUntilTryAgain() throws {
        // R3-3: Try Again re-checks the stored bytes now, so the sheet has to
        // live where imported maps do and carry its real content key
        // (the library's ImportedMaps since the WP4 merge)
        let fixture = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let support = FileManager.default.temporaryDirectory.appendingPathComponent("g1-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        let oldSupport = ImportedMapStorage.applicationSupportProvider
        ImportedMapStorage.applicationSupportProvider = { support }
        defer {
            ImportedMapStorage.applicationSupportProvider = oldSupport
            try? FileManager.default.removeItem(at: support)
        }
        let url = try ImportedMapStorage.importedMapsDirectory().appendingPathComponent("map-sheet.pdf")
        try FileManager.default.copyItem(at: fixture, to: url)
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let pdf = PDFMapSource(url: url, georef: g, contentKey: PDFSessionStore.contentKey(for: url))
        let view = TileMapView(camera: MapCamera(center: .init(latitude: 37.766, longitude: -122.44), zoom: 14,
                                                 headingDegrees: 0, viewportSize: CGSize(width: 300, height: 300)),
                               cacheBytes: 16 << 20)
        view.frame = CGRect(x: 0, y: 0, width: 300, height: 300)
        let mapVM = MapViewModel()
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: mapVM)
        coordinator.syncSource(view: view, mapSource: pdf, onlineBasemaps: false)
        waitFor("source installed") { view.source != nil }
        let first = try XCTUnwrap(view.source as? PDFTileSource)
        waitFor("some tiles") { view.cachedTileCount > 0 }
        first.fail(.renderError)
        XCTAssertEqual(mapVM.pdfRuntime.status, .failed(.renderError))
        coordinator.syncSource(view: view, mapSource: pdf, onlineBasemaps: false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        XCTAssertTrue(view.source === first, "failed source stays attached")
        XCTAssertGreaterThan(view.cachedTileCount, 0, "cache kept")
        mapVM.pdfRuntime.retry()
        coordinator.syncSource(view: view, mapSource: pdf, onlineBasemaps: false)
        waitFor("new source") { view.source !== first && view.source != nil }
        XCTAssertNil((view.source as? PDFTileSource)?.status.failure)
    }

    /// OD2-R2-1: restored with its file gone (sticky cannotOpen), then a fresh
    /// import of byte identical bytes lands under the same file name. It has to
    /// draw straight away, no Try Again, no relaunch
    func testReimportOfIdenticalBytesForAMissingStoredPdfRecoversAtOnce() throws {
        let fixture = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("reimp-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("usgs.pdf")
        let g = try XCTUnwrap(GeoPDFReader.read(url: fixture)?.georef)
        let key = try XCTUnwrap(PDFSessionStore.contentKey(for: fixture))
        let restored = PDFMapSource(url: url, georef: g, contentKey: key)
        restored.storedFileUnavailable = true
        let view = TileMapView(camera: MapCamera(center: .init(latitude: 37.766, longitude: -122.44), zoom: 14,
                                                 headingDegrees: 0, viewportSize: CGSize(width: 300, height: 300)),
                               cacheBytes: 16 << 20)
        view.frame = CGRect(x: 0, y: 0, width: 300, height: 300)
        let mapVM = MapViewModel()
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: mapVM)
        coordinator.syncSource(view: view, mapSource: restored, onlineBasemaps: false)
        waitFor("G1") { mapVM.pdfRuntime.status == .failed(.cannotOpen) }
        let failed = try XCTUnwrap(view.source as? PDFTileSource)
        let generation = mapVM.pdfRuntime.retryGeneration

        // the import: same bytes, same name (the old copy is gone), its own new token
        try FileManager.default.copyItem(at: fixture, to: url)
        let again = PDFMapSource(url: url, georef: g, contentKey: key)
        XCTAssertNotEqual(mapVM.pdfRuntime.sourceKey(for: again, screenScale: 2),
                          mapVM.pdfRuntime.sourceKey(for: restored, screenScale: 2))
        coordinator.syncSource(view: view, mapSource: again, onlineBasemaps: false)
        waitFor("fresh source drawing") { view.source !== failed && mapVM.pdfRuntime.status == .ready }
        XCTAssertNil((view.source as? PDFTileSource)?.status.failure)
        XCTAssertEqual(mapVM.pdfRuntime.retryGeneration, generation, "no Try Again needed")
        // restore / recalibration keep the token, so the same map doesnt rebuild for nothing
        let sameMap = PDFMapSource(url: url, georef: g, contentKey: key, renderGuardToken: again.renderGuardToken)
        XCTAssertEqual(mapVM.pdfRuntime.sourceKey(for: sameMap, screenScale: 2),
                       mapVM.pdfRuntime.sourceKey(for: again, screenScale: 2))
    }

    /// OD2-R2-2: once per source + reason, and any non failure (new source
    /// preparing, recovered, no PDF any more) takes a stale alert down
    func testFailureAlertFollowsTheActiveSource() {
        typealias G = PDFFailureAlertGate
        let a = G.next(status: .failed(.cannotOpen), sourceKey: "A", hasPDF: true, shownFor: nil)
        XCTAssertEqual(a.change, .show(.cannotOpen))
        XCTAssertEqual(a.alert(current: nil), .cannotOpen)
        // same source same reason again (Not Now, then a redraw): not twice
        let again = G.next(status: .failed(.cannotOpen), sourceKey: "A", hasPDF: true, shownFor: a.shownFor)
        XCTAssertEqual(again.change, .keep)
        XCTAssertNil(again.alert(current: nil))
        // a different PDF comes in: preparing takes the old alert down
        let swap = G.next(status: .preparing, sourceKey: "B", hasPDF: true, shownFor: a.shownFor)
        XCTAssertEqual(swap.change, .dismiss)
        XCTAssertNil(swap.alert(current: .cannotOpen))
        // recovered
        XCTAssertNil(G.next(status: .ready, sourceKey: "A", hasPDF: true, shownFor: a.shownFor).alert(current: .cannotOpen))
        // online map now, runtime reset
        XCTAssertNil(G.next(status: .none, sourceKey: nil, hasPDF: false, shownFor: a.shownFor).alert(current: .cannotOpen))
        // the fresh source fails with the same reason: it does alert again
        XCTAssertEqual(G.next(status: .failed(.cannotOpen), sourceKey: "B", hasPDF: true, shownFor: a.shownFor).change,
                       .show(.cannotOpen))
    }

    /// OD2-R2-4 / G1 on the real tile view: after the source goes sticky the
    /// tiles already cached keep drawing, missing ones draw their fallback
    /// ancestors out of the cache, and new loads deliver nothing at all
    func testStickyFailedSourceKeepsDrawingCacheAndFallbacksButLoadsNothing() throws {
        let ctx = try context()
        let src = PDFTileSource(service: service(for: ctx))
        src.install(ctx)
        waitFor("base raster") { src.status == .ready }
        let z = ctx.baseMaxZoom
        XCTAssertGreaterThan(z, 0)
        let c = try XCTUnwrap(ctx.georef.toWGS84(x: ctx.footprint.clipMean.x, y: ctx.footprint.clipMean.y))
        let view = TileMapView(camera: MapCamera(center: c, zoom: Double(z), headingDegrees: 0,
                                                 viewportSize: CGSize(width: 300, height: 300)),
                               cacheBytes: 32 << 20)
        view.frame = CGRect(x: 0, y: 0, width: 300, height: 300)
        view.source = src
        view.layoutTiles()
        let centre = try centreTile(ctx, z: z)
        waitFor("centre tile cached") { view.cacheState(centre) == .image && view.inFlightCount == 0 }
        let cached = view.cachedTileCount

        src.fail(.renderError)
        XCTAssertEqual(src.status, .failed(.renderError))
        XCTAssertTrue(view.source === src, "the failed source stays on the view")
        // same zoom: everything cached still draws as its own tile
        view.layoutTiles()
        let own = view.lastPlan.items.filter { $0.kind == .own }
        XCTAssertTrue(own.contains { $0.source == centre }, "cached tile still drawn")
        XCTAssertEqual(view.inFlightCount, 0)

        // one in: nothing at z+1 is cached, so its the z ancestors that draw
        view.camera = MapCamera(center: c, zoom: Double(z + 1), headingDegrees: 0,
                                viewportSize: CGSize(width: 300, height: 300))
        view.layoutTiles()
        XCTAssertEqual(src.fallbackZoom(forTileZoom: z + 1), z, "fallback level survives the failure")
        let plan = view.lastPlan
        XCTAssertFalse(plan.items.isEmpty, "not dark")
        XCTAssertTrue(plan.items.allSatisfy { $0.kind == .ancestor && $0.source.z <= z })
        XCTAssertTrue(plan.items.allSatisfy { view.cacheState($0.source) == .image })
        XCTAssertFalse(plan.requests.isEmpty, "the view still asks")
        // and the failed source answers nothing, not even EMPTY
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        XCTAssertEqual(view.inFlightCount, 0)
        XCTAssertEqual(view.cachedTileCount, cached, "no new tile landed")
        for t in plan.requests where t.z == z + 1 { XCTAssertEqual(view.cacheState(t), .missing, "\(t)") }
    }

    /// E3: EMPTY never goes in the orphan cache, only images do
    func testEmptyResultsAreNeverParkedAsOrphans() throws {
        let ctx = try context()
        var held: [(work: PDFLaneWork, done: (Result<PDFLaneOutput, Error>) -> Void)] = []
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 3,
                                   execute: { _, w, d in held.append((w, d)) })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        let a = try centreTile(ctx, z: ctx.detailZoom)
        let b = TileIndex(z: a.z, x: a.x + 1, y: a.y)
        want(src, [a, b])
        let ra = src.loadTile(a) { _ in }
        let rb = src.loadTile(b) { _ in }
        waitFor("both jobs running") { held.filter { $0.work.isVector }.count == 2 }
        ra?.cancel()
        rb?.cancel()
        for h in held {
            guard case .tiles(let job, _) = h.work, let t = job.tiles.first else { continue }
            h.done(.success(.tiles([t: t == a ? .empty : .image(PDFRenderSchedulerTests.pixel())], ms: 10)))
        }
        XCTAssertEqual(src.orphanCount, 1, "only the image gets parked")
    }

    /// E3: setViewport gets the tile zoom even when nothing at tz is wanted
    func testViewportReachesTheSchedulerWithNothingRequestedAtTileZoom() throws {
        let ctx = try context()
        let svc = service(for: ctx)
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        let parent = try centreTile(ctx, z: 12)
        src.wantedTilesDidChange([parent], tileZoom: 14, centreUnit: CGPoint(x: 0.25, y: 0.75))
        XCTAssertEqual(svc.scheduler.tileZoom, 14)
        XCTAssertEqual(svc.scheduler.viewportCentre?.x ?? 0, 0.25 * 256 * 16384, accuracy: 1e-6)
        XCTAssertEqual(svc.scheduler.viewportCentre?.y ?? 0, 0.75 * 256 * 16384, accuracy: 1e-6)
        // nothing requested at all still moves the tile zoom + centre
        src.wantedTilesDidChange([], tileZoom: 15, centreUnit: CGPoint(x: 0.5, y: 0.5))
        XCTAssertEqual(svc.scheduler.tileZoom, 15)
        XCTAssertEqual(svc.scheduler.viewportCentre?.x ?? 0, 0.5 * 256 * 32768, accuracy: 1e-6)
    }

    func testBlankPdfFailsWithBlank() throws {
        let b = try XCTUnwrap(F.fx["blank"] as? [String: Any])
        let url = try XCTUnwrap(F.testdataURL(b["file"] as! String))
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: 0)
        let ctx = try PDFRenderContext(url: url, identity: PDFDocumentIdentity(contentKey: "blank\(UUID())", pageIndex: 0),
                                       georef: g, pageBox: PDFTileRenderer.pageBox(page), tilePx: 512,
                                       guardToken: UUID().uuidString, baseBudgetPx: 3_000_000)
        let src = PDFTileSource(service: service(for: ctx))
        src.install(ctx)
        waitFor("blank failure") { src.status.failure != nil }
        XCTAssertEqual(src.status, .failed(.blank))
    }

    func testCropThatMissesThePageIsAGeometryFailure() throws {
        var g = try XCTUnwrap(F.georef(sheet: "sf_iso"))
        g.crop = [PdfPagePoint(x: 5000, y: 5000), PdfPagePoint(x: 5100, y: 5000), PdfPagePoint(x: 5100, y: 5100)]
        XCTAssertThrowsError(try PDFRenderContext(url: URL(fileURLWithPath: "/dev/null"),
                                                  identity: PDFDocumentIdentity(contentKey: "x", pageIndex: 0),
                                                  georef: g, pageBox: CGRect(x: 0, y: 0, width: 800, height: 1000),
                                                  tilePx: 512, guardToken: UUID().uuidString, baseBudgetPx: 1000)) {
            XCTAssertEqual($0 as? PDFRenderFailure, .pageGeometry)
        }
    }

    func testRepeatedJobFailuresBecomeRenderError() throws {
        let ctx = try context()
        // every lane job fails, base raster too
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 1,
                                   execute: { _, _, done in DispatchQueue.main.async { done(.failure(PDFRenderFailure.renderError)) } })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        // G2: a base raster render error is counted, not sticky
        waitFor("base failure counted") { src.failureRunLength == 1 }
        XCTAssertEqual(src.status, .preparing)
        let a = try centreTile(ctx, z: ctx.detailZoom)
        let b = TileIndex(z: a.z, x: a.x + 1, y: a.y)
        want(src, [a, b])
        _ = load(src, a)
        XCTAssertNil(src.status.failure, "two in a row still isnt sticky")
        _ = load(src, b)
        waitFor("third failure") { src.status.failure != nil }
        XCTAssertEqual(src.status, .failed(.renderError))
    }

    /// R2: a started job counts once whoever is still waiting. every waiter
    /// gone before it lands, it still fails the run / resets it / feeds the EWMA
    func testAbandonedStartedJobsStillCount() throws {
        let ctx = try context()
        var held: [(work: PDFLaneWork, done: (Result<PDFLaneOutput, Error>) -> Void)] = []
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 3,
                                   execute: { _, w, d in held.append((w, d)) })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        waitFor("base dispatched") { held.contains { if case .base = $0.work { return true }; return false } }
        let a = try centreTile(ctx, z: ctx.detailZoom)
        let b = TileIndex(z: a.z, x: a.x + 1, y: a.y)
        want(src, [a, b])
        let ra = src.loadTile(a) { _ in XCTFail("cancelled, never delivered") }
        waitFor("a running") { held.contains { $0.work.isVector } }
        ra?.cancel()
        let ja = try XCTUnwrap(held.first { $0.work.isVector })
        ja.done(.failure(PDFRenderFailure.renderError))
        XCTAssertEqual(src.failureRunLength, 1, "abandoned failure counted")
        XCTAssertNil(svc.scheduler.ewmaMs, "failures never feed the EWMA")
        let rb = src.loadTile(b) { _ in XCTFail("cancelled, never delivered") }
        waitFor("b running") { held.filter { $0.work.isVector }.count == 2 }
        rb?.cancel()
        let jb = try XCTUnwrap(held.last { $0.work.isVector })
        jb.done(.success(.tiles([b: .image(PDFRenderSchedulerTests.pixel())], ms: 70)))
        XCTAssertEqual(src.failureRunLength, 0, "abandoned success resets the run")
        XCTAssertEqual(svc.scheduler.ewmaMs, 70, "and feeds the EWMA")
        XCTAssertEqual(src.orphanCount, 1, "its tile is parked for later")
        // cancelled before it ever started: nothing at all
        let c = TileIndex(z: a.z, x: a.x + 2, y: a.y)
        // a tile zoom change restarts the 150 ms settle, so it cant start yet
        want(src, [c], tz: a.z - 1)
        let before = held.count
        src.loadTile(c) { _ in }?.cancel()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        XCTAssertEqual(held.count, before, "never dispatched")
        XCTAssertEqual(src.failureRunLength, 0)
    }

    /// R1 on the real source: one base attempt per source, a failure counts
    /// once, wanted above baseMaxZoom never restarts it, a wanted at or below
    /// does (counted again), success resets the run and re-plans. Loads at
    /// raster zooms wait on that one attempt instead of rendering their own
    func testBaseRasterLifecycle() throws {
        let ctx = try context()
        var held: [(work: PDFLaneWork, done: (Result<PDFLaneOutput, Error>) -> Void)] = []
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 3,
                                   execute: { _, w, d in held.append((w, d)) })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        var layouts = 0
        src.onNeedsLayout = { layouts += 1 }
        func bases() -> [(work: PDFLaneWork, done: (Result<PDFLaneOutput, Error>) -> Void)] {
            held.filter { if case .base = $0.work { return true }; return false }
        }
        src.install(ctx)
        XCTAssertEqual(src.baseStatus, .inFlight)
        XCTAssertEqual(src.baseAttempts, 1, "init kick")
        waitFor("base 1") { bases().count == 1 }
        bases()[0].done(.failure(PDFRenderFailure.renderError))
        XCTAssertEqual(src.baseStatus, .failed)
        XCTAssertEqual(src.failureRunLength, 1, "counted once")
        XCTAssertEqual(src.status, .preparing, "not sticky")
        // above baseMaxZoom: never restarted, however often the view asks
        let high = try centreTile(ctx, z: ctx.baseMaxZoom + 2)
        for _ in 0..<3 { want(src, [high]) }
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        XCTAssertEqual(src.baseAttempts, 1)
        XCTAssertEqual(bases().count, 1)
        // at baseMaxZoom: attempt 2, and a load there waits on it
        let low = try centreTile(ctx, z: ctx.baseMaxZoom)
        want(src, [low])
        XCTAssertEqual(src.baseAttempts, 2)
        var got: UIImage?
        _ = src.loadTile(low) { got = $0 }
        waitFor("base 2") { bases().count == 2 }
        XCTAssertEqual(src.baseAttempts, 2, "the load joined attempt 2, didnt start a third")
        PDFTileRenderer.assertsOffMainThread = false
        let real = try PDFTileRenderer.renderBaseRaster(page: try PDFTileRenderer.openPage(url: ctx.url, pageIndex: 0).1,
                                                        plan: ctx.basePlan, footprint: ctx.footprint)
        PDFTileRenderer.assertsOffMainThread = true
        let before = layouts
        bases()[1].done(.success(.base(real)))
        XCTAssertEqual(src.baseStatus, .ready)
        XCTAssertEqual(src.status, .ready)
        XCTAssertEqual(src.failureRunLength, 0, "a good base resets the run")
        XCTAssertGreaterThan(layouts, before, "re-plan asked for")
        waitFor("parked tile") { got != nil }
        XCTAssertFalse(got === RasterTileSourceEmpty.image)
        want(src, [low])
        XCTAssertEqual(src.baseAttempts, 2, "ready never restarts")
    }

    /// R1: three failed base attempts in a row go sticky outOfMemory
    func testThreeFailedBaseAttemptsGoSticky() throws {
        let ctx = try context()
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 1, execute: { _, _, done in
            DispatchQueue.main.async { done(.failure(PDFRenderFailure.outOfMemory)) }
        })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        let low = try centreTile(ctx, z: ctx.baseMaxZoom)
        for n in 1...2 {
            waitFor("attempt \(n) failed") { src.baseStatus == .failed && src.baseAttempts == n }
            want(src, [low])
        }
        waitFor("sticky") { src.status.failure != nil }
        XCTAssertEqual(src.status, .failed(.outOfMemory))
        XCTAssertEqual(src.baseAttempts, 3)
        want(src, [low])
        XCTAssertEqual(src.baseAttempts, 3, "nothing starts once sticky")
    }

    /// R5: a job that cant stage throws renderError, and a renderError from a
    /// lane counts like any other (a document reason from the base is sticky)
    func testStagedRenderErrorIsCounted() throws {
        let ctx = try context()
        let svc = PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 1, execute: { _, w, done in
            DispatchQueue.main.async {
                if case .base = w { return done(.failure(PDFRenderFailure.cannotOpen)) }
                done(.failure(PDFRenderFailure.renderError))
            }
        })
        svc.usesCrashGuard = false
        let src = PDFTileSource(service: svc)
        src.install(ctx)
        waitFor("base sticky") { src.status.failure != nil }
        XCTAssertEqual(src.status, .failed(.cannotOpen), "a document reason from the base is sticky at once")
        // and the pure step list says renderError for the R5 inputs
        XCTAssertNil(PDFStagedRegion.compute(cellBBox: nil, required: 2, clipBBox: [0, 0, 10, 10], jobPixels: 100))
        XCTAssertNil(PDFStagedRegion.compute(cellBBox: [0, 0, 1, 1], required: .nan, clipBBox: [0, 0, 10, 10], jobPixels: 100))
        let src2 = PDFTileSource(service: PDFRenderService(identity: ctx.identity, url: ctx.url, lanes: 1, execute: { _, w, done in
            DispatchQueue.main.async {
                if case .base = w { return done(.failure(PDFRenderFailure.renderError)) }
                done(.failure(PDFRenderFailure.renderError))
            }
        }))
        src2.service.usesCrashGuard = false
        src2.install(ctx)
        waitFor("base counted") { src2.failureRunLength == 1 }
        let a = try centreTile(ctx, z: ctx.detailZoom - 1)
        want(src2, [a])
        _ = load(src2, a)
        XCTAssertEqual(src2.failureRunLength, 2)
    }

    func testRuntimeCachesTheSourcePerGeorefAndRetryRebuilds() throws {
        let url = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let pdf = PDFMapSource(url: url, georef: g, contentKey: "sha256:" + String(repeating: "b", count: 64))
        let runtime = PDFMapRuntime()
        var layouts = 0
        runtime.onNeedsLayout = { layouts += 1 }
        let a = try XCTUnwrap(runtime.tileSource(for: pdf, screenScale: 2))
        XCTAssertTrue(runtime.tileSource(for: pdf, screenScale: 2) === a, "same key, same source")
        XCTAssertEqual(runtime.status, .preparing)
        waitFor("runtime ready") { runtime.status == .ready }
        XCTAssertEqual(a.context?.tilePx, 512)
        XCTAssertGreaterThan(layouts, 0)
        XCTAssertFalse(runtime.tileSource(for: pdf, screenScale: 3) === a, "tile size is part of the key")
        let c = try XCTUnwrap(runtime.tileSource(for: pdf, screenScale: 3))
        runtime.retry()
        XCTAssertFalse(runtime.source === c)
        runtime.reset()
        XCTAssertEqual(runtime.status, .none)
    }

    func testPreparingLabelOnlyAfter300ms() throws {
        let url = try XCTUnwrap(F.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(GeoPDFReader.read(url: url)?.georef)
        let pdf = PDFMapSource(url: url, georef: g, contentKey: "sha256:" + String(repeating: "c", count: 64))
        let runtime = PDFMapRuntime()
        _ = runtime.tileSource(for: pdf, screenScale: 2)
        XCTAssertFalse(runtime.showPreparingLabel)
        if runtime.status == .preparing {
            RunLoop.main.run(until: Date().addingTimeInterval(0.1))
            if runtime.status == .preparing { XCTAssertFalse(runtime.showPreparingLabel) }
        }
        waitFor("ready") { runtime.status == .ready }
        XCTAssertFalse(runtime.showPreparingLabel)
    }
}

/// the file side of the crash guard
final class PDFRenderGuardStoreTests: XCTestCase {
    var dir: URL!

    override func setUp() {
        super.setUp()
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("guard-\(UUID())", isDirectory: true)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: dir)
        super.tearDown()
    }

    func testMarkerSurvivesARelaunchAndOnlyHoldsUUIDs() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let tok = UUID().uuidString
        let g1 = PDFRenderGuard(url: url)
        XCTAssertTrue(g1.arm(kind: .vector, token: tok))
        // "crash": a fresh process reads the file
        let g2 = PDFRenderGuard(url: url)
        XCTAssertEqual(g2.snapshot.inProgress?.token, tok)
        XCTAssertEqual(g2.launchDecision(restoredToken: tok), PDFLaunchOutcome(decision: .suppress, bakeInterrupted: false))
        XCTAssertEqual(PDFRenderGuard(url: url).suspect, tok)
        let text = try String(contentsOf: url, encoding: .utf8)
        let re = try NSRegularExpression(pattern: "[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}")
        let uuids = re.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
        XCTAssertGreaterThan(uuids, 0)
        XCTAssertFalse(text.contains(".pdf"))
        let v = try url.resourceValues(forKeys: [.isExcludedFromBackupKey])
        XCTAssertEqual(v.isExcludedFromBackup, true)
        XCTAssertFalse(g2.arm(kind: .base, token: "not-a-uuid"))
    }

    /// 3.0.1 (threat-model-2): on a restore the install's openPage is the first
    /// thing to read the stored file, and it runs the OCMD pre-pass over the raw
    /// bytes. it has to run with the base marker down, so dying in there gets the
    /// map suppressed next launch instead of a crash loop
    func testRestoreInstallOpensThePageUnderTheGuard() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let store = PDFRenderGuard(url: url)
        let src = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(GeoPDFReader.read(url: src)?.georef)
        let pdf = PDFMapSource(url: src, georef: g, contentKey: "sha256:" + String(repeating: "e", count: 64))
        let token = pdf.renderGuardToken
        final class Seen { var marker: PDFRenderGuardState.InProgress?; var onDisk: Data?; var calls = 0 }
        let seen = Seen()
        let runtime = PDFMapRuntime()
        runtime.guardStore = store
        runtime.openPage = { u, i in
            seen.calls += 1
            seen.marker = store.snapshot.inProgress
            seen.onDisk = try? Data(contentsOf: url)
            return try PDFTileRenderer.openPage(url: u, pageIndex: i)
        }
        _ = runtime.tileSource(for: pdf, screenScale: 2)
        let end = Date().addingTimeInterval(20)
        while runtime.status != .ready, Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertEqual(runtime.status, .ready)
        XCTAssertEqual(seen.calls, 1)
        XCTAssertEqual(seen.marker?.kind, .base)
        XCTAssertEqual(seen.marker?.token, token)
        // what a crash right there leaves for the next launch
        let crashed = dir.appendingPathComponent("crashed.json")
        try XCTUnwrap(seen.onDisk).write(to: crashed)
        XCTAssertEqual(PDFRenderGuard(url: crashed).launchDecision(restoredToken: token).decision, .suppress)
        // got through: marker off (on disk too), nothing verified, the base render does that itself
        XCTAssertNil(store.snapshot.inProgress)
        XCTAssertNil(PDFRenderGuard(url: url).snapshot.inProgress)
        XCTAssertFalse(store.isVerified(kind: .base, token: token))
        runtime.reset()
    }

    func testDisarmTakesOnlyItsOwnMarkerOffAndVerifiesNothing() {
        let a = UUID().uuidString, b = UUID().uuidString
        var s = PDFRenderGuardState()
        XCTAssertTrue(PDFRenderGuardReducer.arm(&s, kind: .base, token: a))
        PDFRenderGuardReducer.disarm(&s, kind: .base, token: b)
        XCTAssertEqual(s.inProgress?.token, a, "someone elses marker stays")
        PDFRenderGuardReducer.disarm(&s, kind: .vector, token: a)
        XCTAssertEqual(s.inProgress?.kind, .base)
        PDFRenderGuardReducer.disarm(&s, kind: .base, token: a)
        XCTAssertNil(s.inProgress)
        XCTAssertTrue(s.verifiedKinds(a).isEmpty)
        PDFRenderGuardReducer.arm(&s, kind: .import, token: a)
        PDFRenderGuardReducer.disarm(&s, kind: .import, token: a)
        XCTAssertEqual(s.inProgress?.kind, .import, "never an import marker")
    }

    func testJunkOnDiskStartsClean() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Data("{\"v\":1,\"inProgress\":{\"kind\":\"vector\",\"token\":\"/etc/passwd\"},\"suspect\":\"x\"}".utf8).write(to: url)
        let g = PDFRenderGuard(url: url)
        XCTAssertNil(g.snapshot.inProgress)
        XCTAssertNil(g.snapshot.suspect)
        XCTAssertEqual(g.launchDecision(restoredToken: UUID().uuidString).decision, .none)
    }

    func testMapViewModelSuppressesTheSuspectAndOpenAnywayClearsIt() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let store = PDFRenderGuard(url: url)
        let src = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(GeoPDFReader.read(url: src)?.georef)
        let (entry, deps) = Self.activePDFLibrary(url: src, georef: g, contentKey: "sha256:" + String(repeating: "d", count: 64))
        store.arm(kind: .vector, token: entry.renderGuardToken)
        let vm = MapViewModel(libraryDependencies: deps, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = store
        _ = vm.restoreActiveMapSelection()
        XCTAssertEqual(vm.pdfCrashSuspect?.entryID, entry.id)
        XCTAssertEqual(vm.pdfCrashSuspect?.renderGuardToken, entry.renderGuardToken)
        XCTAssertTrue(vm.mapSource is OnlineRasterBasemapSource, "suppressed: online in memory only")
        XCTAssertEqual(vm.library?.activeEntryID, entry.id, "the durable selection is untouched")
        vm.openCrashSuspectAnyway()
        XCTAssertEqual((vm.mapSource as? PDFMapSource)?.entryID, entry.id)
        XCTAssertNil(vm.pdfCrashSuspect)
        XCTAssertNil(store.suspect)
    }

    func testNotNowKeepsTheSuspectForTheNextLaunch() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let store = PDFRenderGuard(url: url)
        let tok = UUID().uuidString
        store.arm(kind: .base, token: tok)
        XCTAssertEqual(store.launchDecision(restoredToken: tok).decision, .suppress)
        store.resolveSuspect(.notNow)
        let next = PDFRenderGuard(url: url)
        XCTAssertEqual(next.launchDecision(restoredToken: tok).decision, .suppress)
        next.resolveSuspect(.openAnyway)
        XCTAssertEqual(PDFRenderGuard(url: url).launchDecision(restoredToken: tok).decision, .none)
    }

    /// K1 on disk: an import arm and complete during a bake leave the bake
    /// marker alone, so a crash later in the bake still reports it
    func testImportDuringABakeKeepsTheBakeMarker() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let bakeTok = UUID().uuidString, importTok = UUID().uuidString
        let g = PDFRenderGuard(url: url)
        g.arm(kind: .bake, token: bakeTok)
        g.arm(kind: .import, token: importTok)
        g.complete(kind: .import, token: importTok)
        XCTAssertEqual(PDFRenderGuard(url: url).snapshot.bakeInProgress, bakeTok)
        let text = try String(contentsOf: url, encoding: .utf8)
        XCTAssertTrue(text.contains("\"bakeInProgress\""))
        let out = PDFRenderGuard(url: url).launchDecision(restoredToken: nil)
        XCTAssertEqual(out, PDFLaunchOutcome(decision: .none, bakeInterrupted: true))
        XCTAssertNil(PDFRenderGuard(url: url).snapshot.bakeInProgress, "cleared after launch")
    }

    /// H1: the suppress path shows the user's own online style, not the default
    func testSuppressPublishesThePreferredOnlineStyle() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let store = PDFRenderGuard(url: url)
        let src = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let g = try XCTUnwrap(GeoPDFReader.read(url: src)?.georef)
        let preferred = try XCTUnwrap(BasemapStyle.allCases.first { $0 != OnlineRasterBasemapSource.defaultStyle && !$0.requiresEsriKey }
            ?? BasemapStyle.allCases.first { $0 != OnlineRasterBasemapSource.defaultStyle })
        // H1: the library keeps the preferred style next to an active imported map
        let (entry, deps) = Self.activePDFLibrary(url: src, georef: g, contentKey: "sha256:" + String(repeating: "f", count: 64),
                                                  preferredStyle: preferred)
        store.arm(kind: .base, token: entry.renderGuardToken)
        // a non online initial source, so the in memory last style can't leak in
        let vm = MapViewModel(libraryDependencies: deps, initialMapSource: PDFMapSource(url: src, georef: g))
        vm.pdfRenderGuard = store
        _ = vm.restoreActiveMapSelection()
        XCTAssertEqual(vm.pdfCrashSuspect?.entryID, entry.id)
        let shown = try XCTUnwrap(vm.mapSource as? OnlineRasterBasemapSource)
        let usable = preferred.requiresEsriKey && !EsriKey.isAvailable ? OnlineRasterBasemapSource.defaultStyle : preferred
        XCTAssertEqual(shown.style, usable)
        XCTAssertEqual(vm.preferredOnlineBasemap().style, usable, "in memory last online style wins")
        let fresh = MapViewModel(libraryDependencies: Self.noLibrary(), initialMapSource: PDFMapSource(url: src, georef: g))
        XCTAssertEqual(fresh.preferredOnlineBasemap().style, OnlineRasterBasemapSource.defaultStyle, "nothing stored: the default")
    }

    func testInterruptedImportAndBakeAreReported() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        PDFRenderGuard(url: url).arm(kind: .import, token: UUID().uuidString)
        let vmImport = MapViewModel(libraryDependencies: Self.noLibrary(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vmImport.pdfRenderGuard = PDFRenderGuard(url: url)
        _ = vmImport.restoreActiveMapSelection()
        XCTAssertEqual(vmImport.pdfLaunchNotice, .importInterrupted)

        PDFRenderGuard(url: url).arm(kind: .bake, token: UUID().uuidString)
        let vmBake = MapViewModel(libraryDependencies: Self.noLibrary(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vmBake.pdfRenderGuard = PDFRenderGuard(url: url)
        _ = vmBake.restoreActiveMapSelection()
        XCTAssertEqual(vmBake.pdfLaunchNotice, .bakeInterrupted)
    }

    /// K1: import probe crashed while a bake ran, both notices, the import first
    func testBothLaunchNoticesShowOneAfterTheOther() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let g = PDFRenderGuard(url: url)
        g.arm(kind: .bake, token: UUID().uuidString)
        g.arm(kind: .import, token: UUID().uuidString)
        let vm = MapViewModel(libraryDependencies: Self.noLibrary(), initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: url)
        _ = vm.restoreActiveMapSelection()
        XCTAssertEqual(vm.pdfLaunchNotice, .importInterrupted)
        XCTAssertEqual(vm.queuedLaunchNotices, [.bakeInterrupted])
        vm.dismissLaunchNotice()
        XCTAssertNil(vm.pdfLaunchNotice)
        let end = Date().addingTimeInterval(3)
        while vm.pdfLaunchNotice == nil, Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertEqual(vm.pdfLaunchNotice, .bakeInterrupted)
        vm.dismissLaunchNotice()
        XCTAssertTrue(vm.queuedLaunchNotices.isEmpty)
    }

    /// K1: a crash in a first render during a bake suppresses the map, and the
    /// bake notice waits until the crash alert is answered
    func testBakeNoticeWaitsForTheCrashSuspectAlert() throws {
        let url = dir.appendingPathComponent("pdf_render_guard.json")
        let src = try XCTUnwrap(PDFTileRenderFixtureTests.testdataURL("geopdf/tacmap_grid_sf_iso.pdf"))
        let geo = try XCTUnwrap(GeoPDFReader.read(url: src)?.georef)
        let (entry, deps) = Self.activePDFLibrary(url: src, georef: geo, contentKey: "sha256:" + String(repeating: "9", count: 64))
        let g = PDFRenderGuard(url: url)
        g.arm(kind: .bake, token: UUID().uuidString)
        XCTAssertTrue(g.arm(kind: .vector, token: entry.renderGuardToken), "a bake marker doesnt block a first render")
        let vm = MapViewModel(libraryDependencies: deps, initialMapSource: OnlineRasterBasemapSource(.osmTopo))
        vm.pdfRenderGuard = PDFRenderGuard(url: url)
        _ = vm.restoreActiveMapSelection()
        XCTAssertEqual(vm.pdfCrashSuspect?.entryID, entry.id)
        XCTAssertNil(vm.pdfLaunchNotice, "the decision's alert goes first")
        vm.dismissCrashSuspect()
        let end = Date().addingTimeInterval(3)
        while vm.pdfLaunchNotice == nil, Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertEqual(vm.pdfLaunchNotice, .bakeInterrupted)
    }

    /// in memory library with nothing in it: no disk, no legacy stores
    static func noLibrary() -> LibraryDependencies {
        inMemoryLibrary(nil, fileURL: { _ in nil })
    }

    /// one georeferenced PDF entry, active, its file at url (size + mtime always ok)
    static func activePDFLibrary(url: URL, georef: PdfGeoreference, contentKey: String,
                                 preferredStyle: BasemapStyle? = nil) -> (ImportedMapEntry, LibraryDependencies) {
        let info = ImportedMapEntry.PDFInfo(pageCount: 1, pageIndex: georef.page, rotate: 0, pageBox: georef.crop,
                                            embedded: georef, embeddedIssue: nil, manual: nil, firstRenderPending: nil,
                                            renderGuardToken: UUID().uuidString)
        let entry = ImportedMapEntry(id: UUID(), kind: .pdf, fileName: "ImportedMaps/map-test.pdf", displayName: "Test sheet",
                                     contentKey: contentKey, byteCount: 0, fileModifiedAtMs: 0, importedAtMs: 0,
                                     derivedFromId: nil, pdf: info)
        var state = LibraryState()
        state.active = .entry(entry.id)
        if let preferredStyle { state.preferredOnlineStyle = preferredStyle.rawValue }
        state.entries = [entry]
        return (entry, inMemoryLibrary(state, fileURL: { _ in url }))
    }

    static func inMemoryLibrary(_ initial: LibraryState?, fileURL: @escaping (ImportedMapEntry) -> URL?) -> LibraryDependencies {
        final class Box { var state: LibraryState? }
        let box = Box()
        box.state = initial
        return LibraryDependencies(
            load: { box.state.map { .loaded($0) } ?? .empty },
            write: { box.state = $0 },
            reconcile: { _ in true },
            legacyPresent: { false },
            clearLegacy: {},
            fileStatus: { _ in .ok },
            fileURL: fileURL,
            verify: { _, _, done in done(true) },
            unlink: { _ in },
            drafts: InMemoryCalibrationDraftStore(),
            sweepBackup: {},
            recoverInterruptedImport: { false })
    }
}

final class PDFDebugAndMemoTests: XCTestCase {
    #if DEBUG
    func testDebugCameraHookParsing() {
        XCTAssertEqual(DebugHooks.parseCamera("37.76,-122.44,16"),
                       .init(latitude: 37.76, longitude: -122.44, zoom: 16, heading: 0))
        XCTAssertEqual(DebugHooks.parseCamera(" 37.76, -122.44 , 16.5, 30"),
                       .init(latitude: 37.76, longitude: -122.44, zoom: 16.5, heading: 30))
        XCTAssertNil(DebugHooks.parseCamera("37.76,-122.44"))
        XCTAssertNil(DebugHooks.parseCamera("91,0,10"))
        XCTAssertNil(DebugHooks.parseCamera("nan,0,10"))
        XCTAssertNil(DebugHooks.parseCamera(nil))
    }
    #endif

    func testContentKeyIsHashedOncePerIdentity() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("memo-\(UUID()).pdf")
        defer { try? FileManager.default.removeItem(at: url) }
        try Data(repeating: 7, count: 300_000).write(to: url)
        let before = PDFSessionStore.contentKeyHashCount
        let a = PDFSessionStore.contentKey(for: url)
        let b = PDFSessionStore.contentKey(for: url)
        XCTAssertEqual(a, b)
        XCTAssertEqual(PDFSessionStore.contentKeyHashCount - before, 1)
        // different bytes (and size) -> new hash
        try Data(repeating: 9, count: 300_001).write(to: url)
        let c = PDFSessionStore.contentKey(for: url)
        XCTAssertNotEqual(a, c)
        XCTAssertEqual(PDFSessionStore.contentKeyHashCount - before, 2)
    }

    /// a cold restore frames the saved sheet before the map view has been
    /// laid out. bounds were zero there and the fit landed near z4, so the
    /// restored PDF showed up as one dark pixel under the crosshair
    func testFramingBeforeFirstLayoutStillFitsTheSheet() {
        let cam = MapCamera(center: .init(latitude: 20, longitude: 0), zoom: 4, headingDegrees: 0, viewportSize: .zero)
        let view = TileMapView(camera: cam, cacheBytes: 1 << 20)
        XCTAssertEqual(view.bounds.height, 0)
        let mapVM = MapViewModel()
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: mapVM)
        // the sf sheet, 8 km tall
        let region = MKCoordinateRegion(center: .init(latitude: 37.766, longitude: -122.4437),
                                        span: MKCoordinateSpan(latitudeDelta: 0.072, longitudeDelta: 0.068))
        mapVM.cameraRequests.send(region)
        XCTAssertEqual(view.camera.center.latitude, 37.766, accuracy: 1e-9)
        XCTAssertGreaterThan(view.camera.zoom, 12, "fit against the screen, not a zero height view")
        XCTAssertLessThan(view.camera.zoom, 15)
        // once laid out the same request lands on the same kind of zoom
        view.frame = UIScreen.main.bounds
        view.layoutIfNeeded()
        let before = view.camera.zoom
        mapVM.cameraRequests.send(region)
        XCTAssertEqual(view.camera.zoom, before, accuracy: 0.01)
    }

    /// OD-IMPORT: framing fits the whole sheet both ways. a wide sheet on a
    /// portrait phone used to fit its height and lose its sides
    func testImportFramingFitsTheWholeSheet() throws {
        let region = MKCoordinateRegion(center: .init(latitude: 37.766, longitude: -122.44),
                                        span: MKCoordinateSpan(latitudeDelta: 0.03, longitudeDelta: 0.2))
        let size = CGSize(width: 402, height: 874)
        let fit = try XCTUnwrap(MapExtentFit.fit(region, viewport: size))
        let world = 256 * pow(2.0, fit.zoom)
        XCTAssertLessThanOrEqual(region.span.longitudeDelta / 360 * world, Double(size.width) + 1e-6, "every side on screen")
        XCTAssertGreaterThan(region.span.longitudeDelta / 360 * world, Double(size.width) * 0.99, "and no smaller than it has to be")
        XCTAssertEqual(fit.centre.latitude, region.center.latitude, accuracy: 1e-12)
        // tall sheet: the height decides
        let tall = MKCoordinateRegion(center: region.center, span: MKCoordinateSpan(latitudeDelta: 0.2, longitudeDelta: 0.01))
        let tf = try XCTUnwrap(MapExtentFit.fit(tall, viewport: size))
        let yN = WebMercator.worldPoint(.init(latitude: 37.866, longitude: 0), zoom: tf.zoom).y
        let yS = WebMercator.worldPoint(.init(latitude: 37.666, longitude: 0), zoom: tf.zoom).y
        XCTAssertEqual(Double(yS - yN), Double(size.height), accuracy: 0.5)
        XCTAssertEqual(MapExtentFit.fit(region, viewport: .zero)?.zoom, nil)
        // and the view gets exactly that
        let view = TileMapView(camera: MapCamera(center: .init(latitude: 0, longitude: 0), zoom: 4, headingDegrees: 0,
                                                 viewportSize: size), cacheBytes: 1 << 20)
        view.frame = CGRect(origin: .zero, size: size)
        let mapVM = MapViewModel()
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: mapVM)
        mapVM.cameraRequests.send(region)
        XCTAssertEqual(view.camera.zoom, fit.zoom, accuracy: 1e-9)
    }

    /// F4: the footprint scan gives up as soon as its told to
    func testFootprintScanStopsWhenCancelled() throws {
        let (_, fp) = try PDFTileRenderFixtureTests.footprint(sheet: "usgs_sf_north")
        XCTAssertNil(fp.tiles(z: 18, isCancelled: { true }))
        XCTAssertNotNil(fp.tiles(z: 12, isCancelled: { false }))
    }

    func testCancelledGridBuildReturnsNothing() {
        let cam = MapCamera(center: .init(latitude: 37.8, longitude: -122.45), zoom: 13, headingDegrees: 0,
                            viewportSize: CGSize(width: 393, height: 852))
        let req = MGRSGridRenderer.BuildRequest(camera: cam, pxPerDp: 3)
        XCTAssertNil(MGRSGridRenderer.build(req, isCancelled: { true }))
        XCTAssertNotNil(MGRSGridRenderer.build(req, isCancelled: { false }))
    }

    /// (b): during a pinch the container never queues more than one rebuild,
    /// the newest request replaces the parked one and is the one installed
    @MainActor
    func testGridBuildsAreLatestWins() {
        var cam = MapCamera(center: .init(latitude: 37.8, longitude: -122.45), zoom: 12, headingDegrees: 0,
                            viewportSize: CGSize(width: 393, height: 852))
        let view = TileMapView(camera: cam, cacheBytes: 1 << 20)
        view.frame = CGRect(origin: .zero, size: cam.viewportSize)
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: MapViewModel())
        view.onCameraChange = { _ in coordinator.reprojectOverlays() }
        let presence = SyncPresenceModel()
        coordinator.observePresence(presence)
        coordinator.updateOverlays(drawings: [], gridVisible: true,
                                   decorations: DrawingDecorationsOverlayView.Model(), handles: [], graphicsLocked: false)
        XCTAssertTrue(coordinator.isGridBuildInFlight)
        // a pinch: lots of rebuild worthy zoom steps while the first build runs
        for step in 1...8 {
            cam.zoom = 12 + Double(step) * 0.6
            view.camera = cam
        }
        XCTAssertTrue(coordinator.isGridBuildInFlight)
        XCTAssertTrue(coordinator.hasQueuedGridBuild, "the newest request waits, nothing else queues")
        let gridView = view.subviews.compactMap { $0 as? MGRSGridOverlayView }.first
        let end = Date().addingTimeInterval(15)
        while (coordinator.isGridBuildInFlight || coordinator.hasQueuedGridBuild), Date() < end {
            RunLoop.main.run(until: Date().addingTimeInterval(0.02))
        }
        XCTAssertFalse(coordinator.isGridBuildInFlight)
        XCTAssertEqual(gridView?.grid.lod.drawn,
                       MGRSGridRenderer.lod(zoom: cam.zoom, latitude: cam.center.latitude).drawn)
    }
}
