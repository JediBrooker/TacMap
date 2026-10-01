import XCTest
import UIKit
@testable import TacticalMaps

/// Contract E with a fake clock and a fake lane executor: settle, heavy
/// growth, cancellation, orphans, bands, bake gating, EWMA, background.
final class PDFRenderSchedulerTests: XCTestCase {

    final class FakeClock {
        var t = 1000.0
        var timers: [(at: Double, f: () -> Void)] = []
        lazy var clock = PDFRenderScheduler.Clock(now: { [unowned self] in self.t },
                                                  after: { [unowned self] d, f in self.timers.append((self.t + d, f)) })
        func advance(_ s: Double) {
            t += s
            let due = timers.filter { $0.at <= t }
            timers.removeAll { $0.at <= t }
            due.forEach { $0.f() }
        }
    }

    struct Dispatch {
        let lane: Int
        let work: PDFLaneWork
        let done: (Result<PDFLaneOutput, Error>) -> Void
        var tiles: [TileIndex] {
            switch work {
            case .tiles(let j, _), .bake(let j, _): return j.tiles
            case .base: return []
            }
        }
        var job: TileJob? {
            switch work {
            case .tiles(let j, _), .bake(let j, _): return j
            case .base: return nil
            }
        }
    }

    var clock: FakeClock!
    var dispatched: [Dispatch] = []
    var scheduler: PDFRenderScheduler!
    static var ctx: PDFRenderContext?

    func context() throws -> PDFRenderContext {
        if let c = Self.ctx { return c }
        let g = try XCTUnwrap(PDFTileRenderFixtureTests.georef(sheet: "sf_iso"))
        let box = try XCTUnwrap(PDFTileRenderFixtureTests.pageBox(sheet: "sf_iso"))
        let c = try PDFRenderContext(url: URL(fileURLWithPath: "/dev/null"),
                                     identity: PDFDocumentIdentity(contentKey: "sha256:test", pageIndex: 0),
                                     georef: g, pageBox: box, tilePx: 512,
                                     guardToken: UUID().uuidString, baseBudgetPx: PDFTileConstants.baseBudgetPx)
        Self.ctx = c
        return c
    }

    override func setUp() {
        super.setUp()
        clock = FakeClock()
        dispatched = []
        scheduler = PDFRenderScheduler(lanes: 1, clock: clock.clock) { [unowned self] lane, work, done in
            self.dispatched.append(Dispatch(lane: lane, work: work, done: done))
        }
    }

    private func finish(_ d: Dispatch, ms: Double = 10) {
        var m: [TileIndex: TileCacheEntry] = [:]
        for t in d.tiles { m[t] = .empty }
        d.done(.success(.tiles(m, ms: ms)))
    }

    private let z = 16, bx = 10480, by = 25330

    func testVectorJobsWaitForTheTileZoomToSettle() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: Double(bx) * 256, centreY: Double(by) * 256, tileZoom: z)
        scheduler.requestTile(TileIndex(z: z, x: bx, y: by), ctx: ctx, band: .visible) { _ in }
        XCTAssertTrue(dispatched.isEmpty, "no vector job inside the 150 ms settle")
        clock.advance(0.1)
        XCTAssertTrue(dispatched.isEmpty)
        clock.advance(0.06)
        XCTAssertEqual(dispatched.count, 1)
        XCTAssertEqual(dispatched[0].job, TileJob(z: z, x0: bx, y0: by, cols: 1, rows: 1))
    }

    func testZoomChangeRestartsTheSettle() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: 15)
        clock.advance(0.2)
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: 16)
        scheduler.requestTile(TileIndex(z: 16, x: 1, y: 1), ctx: ctx, band: .visible) { _ in }
        XCTAssertTrue(dispatched.isEmpty)
        clock.advance(0.16)
        XCTAssertEqual(dispatched.count, 1)
    }

    func testHeavySheetsGrowJobsOverPendingVisibleTiles() throws {
        let ctx = try context()
        scheduler.recordVectorTiming(200)
        XCTAssertTrue(scheduler.isHeavy)
        scheduler.setViewport(centreX: (Double(bx) + 0.5) * 256, centreY: (Double(by) + 0.5) * 256, tileZoom: z)
        for dy in -2...2 { for dx in -2...2 {
            scheduler.requestTile(TileIndex(z: z, x: bx + dx, y: by + dy), ctx: ctx, band: .visible) { _ in }
        } }
        clock.advance(0.2)
        XCTAssertEqual(dispatched.count, 1)
        let job = try XCTUnwrap(dispatched[0].job)
        XCTAssertLessThanOrEqual(job.cols * job.rows, 6)
        XCTAssertLessThanOrEqual(max(job.cols, job.rows), 3)
        // seeded from the tile closest to the viewport centre, same as the fixture
        XCTAssertEqual(job, PDFJobFormation.grow(seed: TileIndex(z: z, x: bx, y: by),
                                                 pendingVisible: Set((-2...2).flatMap { dy in (-2...2).map { TileIndex(z: z, x: bx + $0, y: by + dy) } })))
        XCTAssertEqual(scheduler.pendingTileCount, 25 - job.cols * job.rows)
        XCTAssertEqual(scheduler.runningTileCount, job.cols * job.rows)
    }

    func testLightSheetsRenderOneTileAtATime() throws {
        let ctx = try context()
        scheduler.recordVectorTiming(20)
        XCTAssertFalse(scheduler.isHeavy)
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: z)
        for dx in 0..<3 { scheduler.requestTile(TileIndex(z: z, x: bx + dx, y: by), ctx: ctx, band: .visible) { _ in } }
        clock.advance(0.2)
        XCTAssertEqual(dispatched.last?.job?.cols, 1)
        XCTAssertEqual(dispatched.last?.job?.rows, 1)
    }

    func testEwmaFlipsAtSixtyMilliseconds() {
        XCTAssertNil(scheduler.ewmaMs)
        XCTAssertFalse(scheduler.isHeavy, "light until the first sample")
        scheduler.recordVectorTiming(50)
        XCTAssertFalse(scheduler.isHeavy)
        scheduler.recordVectorTiming(100)   // 0.3*100 + 0.7*50 = 65
        XCTAssertEqual(scheduler.ewmaMs ?? 0, 65, accuracy: 1e-9)
        XCTAssertTrue(scheduler.isHeavy)
        scheduler.recordVectorTiming(40)    // 0.3*40 + 0.7*65 = 57.5
        XCTAssertFalse(scheduler.isHeavy)
    }

    func testCancelBeforeStartDropsTheJob() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: z)
        let ticket = scheduler.requestTile(TileIndex(z: z, x: bx, y: by), ctx: ctx, band: .visible) { _ in
            XCTFail("cancelled request must not complete")
        }
        ticket.cancel()
        clock.advance(0.2)
        XCTAssertTrue(dispatched.isEmpty)
        XCTAssertEqual(scheduler.pendingTileCount, 0)
    }

    func testRunningJobsFinishAndUnwantedTilesBecomeOrphans() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: z)
        var orphaned: [TileCacheEntry] = []
        let t = TileIndex(z: z, x: bx, y: by)
        let ticket = scheduler.requestTile(t, ctx: ctx, band: .visible, orphan: { orphaned.append($0) }) { _ in
            XCTFail("cancelled while running, the waiter is gone")
        }
        clock.advance(0.2)
        XCTAssertEqual(dispatched.count, 1)
        ticket.cancel()
        finish(dispatched[0])
        XCTAssertEqual(orphaned.count, 1)
    }

    func testVisibleBeatsFallbackAndCloserBeatsFurther() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: (Double(bx) + 0.5) * 256, centreY: (Double(by) + 0.5) * 256, tileZoom: z)
        clock.advance(0.2)
        var order: [TileIndex] = []
        let far = TileIndex(z: z, x: bx + 5, y: by)
        let near = TileIndex(z: z, x: bx + 1, y: by)
        let fallback = TileIndex(z: z - 1, x: bx / 2, y: by / 2)
        // occupy the lane so everything queues
        scheduler.requestTile(TileIndex(z: z, x: bx, y: by), ctx: ctx, band: .visible) { _ in }
        for t in [fallback, far, near] {
            scheduler.requestTile(t, ctx: ctx, band: t.z == z ? .visible : .fallback) { _ in order.append(t) }
        }
        while let d = dispatched.first {
            dispatched.removeFirst()
            finish(d)
        }
        XCTAssertEqual(order, [near, far, fallback])
    }

    func testBakeWaitsForVisibleWorkAndRunsOneAtATime() throws {
        let ctx = try context()
        let two = PDFRenderScheduler(lanes: 2, clock: clock.clock) { [unowned self] lane, work, done in
            self.dispatched.append(Dispatch(lane: lane, work: work, done: done))
        }
        two.setViewport(centreX: 0, centreY: 0, tileZoom: z)
        two.requestTile(TileIndex(z: z, x: 1, y: 1), ctx: ctx, band: .visible) { _ in }
        two.requestBake(job: TileJob(z: 14, x0: 0, y0: 0, cols: 1, rows: 1), ctx: ctx) { _ in }
        two.requestBake(job: TileJob(z: 14, x0: 1, y0: 0, cols: 1, rows: 1), ctx: ctx) { _ in }
        XCTAssertTrue(dispatched.isEmpty, "visible pending (still settling) holds the bake")
        clock.advance(0.2)
        XCTAssertEqual(dispatched.count, 2, "visible + one bake, never two bakes")
        XCTAssertEqual(dispatched.filter { if case .bake = $0.work { return true } else { return false } }.count, 1)
        let firstBake = dispatched.first { if case .bake = $0.work { return true } else { return false } }!
        finish(firstBake)
        XCTAssertEqual(dispatched.filter { if case .bake = $0.work { return true } else { return false } }.count, 2)
    }

    func testBackgroundStopsLiveBandsButTheBakeKeepsGoing() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: z)
        clock.advance(0.2)
        scheduler.foreground = false
        scheduler.requestTile(TileIndex(z: z, x: 1, y: 1), ctx: ctx, band: .visible) { _ in }
        scheduler.requestBake(job: TileJob(z: 14, x0: 0, y0: 0, cols: 1, rows: 1), ctx: ctx) { _ in }
        XCTAssertEqual(dispatched.count, 1)
        guard case .bake = dispatched[0].work else { return XCTFail("bake should run in the background") }
        finish(dispatched[0])
        XCTAssertEqual(dispatched.count, 1, "visible stays parked while backgrounded")
        scheduler.foreground = true
        XCTAssertEqual(dispatched.count, 2)
    }

    func testThermalPauseHoldsTheBake() throws {
        let ctx = try context()
        scheduler.bakePaused = true
        scheduler.requestBake(job: TileJob(z: 14, x0: 0, y0: 0, cols: 1, rows: 1), ctx: ctx) { _ in }
        XCTAssertTrue(dispatched.isEmpty)
        scheduler.bakePaused = false
        XCTAssertEqual(dispatched.count, 1)
    }

    func testBaseRasterIsSingleFlight() throws {
        let ctx = try context()
        var got = 0
        scheduler.requestBase(ctx: ctx, band: .visible) { _ in got += 1 }
        scheduler.requestBase(ctx: ctx, band: .fallback) { _ in got += 1 }
        XCTAssertEqual(dispatched.count, 1)
        guard case .base = dispatched[0].work else { return XCTFail("expected the base raster") }
        dispatched[0].done(.failure(PDFRenderFailure.blank))
        XCTAssertEqual(got, 2)
    }

    static func pixel() -> CGImage {
        let cs = CGColorSpaceCreateDeviceRGB()
        let c = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: cs,
                          bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
        c.setFillColor(UIColor.red.cgColor)
        c.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        return c.makeImage()!
    }

    /// E1: an all EMPTY job says nothing about the draw cost, a bake job with
    /// an image does (any band feeds it), and a fresh document starts light
    func testAllEmptyJobsDontFeedTheEwmaButBakeJobsDo() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: (Double(bx) + 0.5) * 256, centreY: (Double(by) + 0.5) * 256, tileZoom: z)
        scheduler.requestTile(TileIndex(z: z, x: bx, y: by), ctx: ctx, band: .visible) { _ in }
        clock.advance(0.2)
        XCTAssertEqual(dispatched.count, 1)
        finish(dispatched[0], ms: 500)
        XCTAssertNil(scheduler.ewmaMs, "all EMPTY, no sample")
        XCTAssertFalse(scheduler.isHeavy)

        let t = TileIndex(z: z, x: bx + 1, y: by)
        scheduler.requestBake(job: TileJob(z: z, x0: t.x, y0: t.y, cols: 1, rows: 1), ctx: ctx) { _ in }
        XCTAssertEqual(dispatched.count, 2)
        dispatched[1].done(.success(.tiles([t: .image(Self.pixel())], ms: 80)))
        XCTAssertEqual(scheduler.ewmaMs ?? -1, 80, accuracy: 1e-12)
        XCTAssertTrue(scheduler.isHeavy)

        // a failed job never feeds it either
        scheduler.requestBake(job: TileJob(z: z, x0: t.x + 1, y0: t.y, cols: 1, rows: 1), ctx: ctx) { _ in }
        dispatched[2].done(.failure(PDFRenderFailure.renderError))
        XCTAssertEqual(scheduler.ewmaMs ?? -1, 80, accuracy: 1e-12)

        let other = PDFRenderService(identity: PDFDocumentIdentity(contentKey: "sha256:other", pageIndex: 0),
                                     url: URL(fileURLWithPath: "/dev/null"), lanes: 1)
        XCTAssertNil(other.scheduler.ewmaMs, "a newly opened document starts light")
    }

    func testJobFailureReachesEveryWaiter() throws {
        let ctx = try context()
        scheduler.setViewport(centreX: 0, centreY: 0, tileZoom: z)
        var failures = 0
        let t = TileIndex(z: z, x: 3, y: 3)
        scheduler.requestTile(t, ctx: ctx, band: .visible) { if case .failure = $0 { failures += 1 } }
        clock.advance(0.2)
        // a second ask while running just waits on the same job
        scheduler.requestTile(t, ctx: ctx, band: .visible) { if case .failure = $0 { failures += 1 } }
        XCTAssertEqual(dispatched.count, 1)
        dispatched[0].done(.failure(PDFRenderFailure.renderError))
        XCTAssertEqual(failures, 2)
    }
}
