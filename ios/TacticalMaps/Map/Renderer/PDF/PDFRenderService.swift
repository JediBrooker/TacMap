import Foundation
import CoreGraphics
import CryptoKit
import UIKit

enum PDFRenderBand: Int, Comparable {
    case visible = 0, fallback, bake
    static func < (a: Self, b: Self) -> Bool { a.rawValue < b.rawValue }
}

struct PDFDocumentIdentity: Hashable {
    let contentKey: String
    let pageIndex: Int
}

/// Everything a render needs, frozen. One per (pdf bytes, page, georef,
/// tilePx). Live source and bake both hold one; nothing in here is mutable.
final class PDFRenderContext {
    private static var nextID = 0
    private static let idLock = NSLock()

    let id: Int
    let url: URL
    let identity: PDFDocumentIdentity
    let georef: PdfGeoreference
    let footprint: PDFFootprint
    let policy: PDFZoomPolicy
    let tilePx: Int
    let basePlan: PDFBaseRasterPlan
    let baseMaxZoom: Int
    let guardToken: String
    let bakeKey: String

    init(url: URL, identity: PDFDocumentIdentity, georef: PdfGeoreference, pageBox: CGRect, tilePx: Int,
         guardToken: String, baseBudgetPx: Int) throws {
        Self.idLock.lock()
        Self.nextID += 1
        id = Self.nextID
        Self.idLock.unlock()
        self.url = url
        self.identity = identity
        self.georef = georef
        footprint = try PDFFootprint(georef: georef, pageBox: pageBox)
        policy = try PDFZoomPolicy(georef: georef, footprint: footprint)
        self.tilePx = tilePx
        basePlan = policy.baseRasterPlan(footprint: footprint, budgetPixels: baseBudgetPx)
        baseMaxZoom = policy.baseMaxZoom(plan: basePlan, tilePx: tilePx)
        self.guardToken = guardToken
        bakeKey = Self.bakeKey(georef: georef, tilePx: tilePx)
    }

    /// sha256("tacmap-bake-v1|" + canonical georef json + "|" + tilePx + "|" + rendererVersion).
    /// Compared on this device only, so "canonical" just means sorted keys.
    static func bakeKey(georef: PdfGeoreference, tilePx: Int) -> String {
        let enc = JSONEncoder()
        enc.outputFormatting = [.sortedKeys]
        let json = (try? enc.encode(georef)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
        let s = PDFTileConstants.bakeKeyPrefix + json + "|\(tilePx)|\(PDFTileRenderer.version)"
        return SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    var detailZoom: Int { policy.detailZoom }
}

/// Cancellable handle. Main thread only.
final class PDFRenderTicket: RasterTileRequest {
    private var handlers: [() -> Void] = []
    private(set) var isCancelled = false

    init() {}

    func cancel() {
        guard !isCancelled else { return }
        isCancelled = true
        let hs = handlers
        handlers = []
        hs.forEach { $0() }
    }

    /// adds a handler (they all run on cancel), runs it now if already cancelled
    func setOnCancel(_ c: @escaping () -> Void) {
        if isCancelled { c() } else { handlers.append(c) }
    }
}

enum PDFLaneWork {
    /// live vector job, direct or staged by cell count
    case tiles(TileJob, PDFRenderContext)
    /// same render, lowest band
    case bake(TileJob, PDFRenderContext)
    case base(PDFRenderContext)

    var isVector: Bool {
        switch self {
        case .tiles, .bake: return true
        case .base: return false
        }
    }
}

enum PDFLaneOutput {
    case tiles([TileIndex: TileCacheEntry], ms: Double)
    case base(PDFPageRaster)
}

/// what a waiter gets when its job failed. One job can have lots of waiters
/// (a heavy 3x2 job, several asks for one base raster) and the live failure
/// run counts jobs not waiters, so the id lets the source count it once
final class PDFJobError: Error {
    let underlying: Error
    let jobID: Int
    init(_ underlying: Error, jobID: Int) {
        self.underlying = underlying
        self.jobID = jobID
    }

    static func unwrap(_ e: Error) -> Error { (e as? PDFJobError)?.underlying ?? e }
}

// MARK: - scheduler

/// Pull based, main thread only. When a lane frees it takes the best pending
/// work by (band, distance to the viewport centre). Rules from contract E:
/// vector jobs wait for the tile zoom to sit still 150 ms, heavy sheets grow
/// jobs over pending visible tiles, a bake job never starts while anything
/// visible is pending and only one runs at a time, background stops the
/// live bands.
final class PDFRenderScheduler {
    struct Clock {
        var now: () -> Double
        var after: (Double, @escaping () -> Void) -> Void

        static let live = Clock(now: { CACurrentMediaTime() },
                                after: { d, f in DispatchQueue.main.asyncAfter(deadline: .now() + d, execute: f) })
    }

    typealias Execute = (Int, PDFLaneWork, @escaping (Result<PDFLaneOutput, Error>) -> Void) -> Void
    typealias TileCompletion = (Result<TileCacheEntry, Error>) -> Void
    /// R2: once per started job, waiters or not. the live source counts this,
    /// not the per waiter completions
    typealias JobReport = (Result<Void, Error>) -> Void
    typealias BakeCompletion = (Result<(tiles: [TileIndex: TileCacheEntry], ms: Double), Error>) -> Void

    private struct TileKey: Hashable {
        let ctx: Int
        let tile: TileIndex
    }

    private final class TileEntry {
        let ctx: PDFRenderContext
        var band: PDFRenderBand
        var waiters: [(ticket: PDFRenderTicket, done: TileCompletion)] = []
        var orphan: ((TileCacheEntry) -> Void)?
        var report: JobReport?
        init(ctx: PDFRenderContext, band: PDFRenderBand) { self.ctx = ctx; self.band = band }
    }

    private final class BaseEntry {
        let ctx: PDFRenderContext
        var band: PDFRenderBand
        var running = false
        var waiters: [(ticket: PDFRenderTicket, done: (Result<PDFPageRaster, Error>) -> Void)] = []
        init(ctx: PDFRenderContext, band: PDFRenderBand) { self.ctx = ctx; self.band = band }
    }

    private final class BakeEntry {
        let ticket: PDFRenderTicket
        let job: TileJob
        let ctx: PDFRenderContext
        let done: BakeCompletion
        init(ticket: PDFRenderTicket, job: TileJob, ctx: PDFRenderContext, done: @escaping BakeCompletion) {
            self.ticket = ticket; self.job = job; self.ctx = ctx; self.done = done
        }
    }

    let maxLanes: Int
    /// thermal / low power drop this to 1
    var laneLimit: Int { didSet { pump() } }
    var foreground = true { didSet { if foreground != oldValue { pump() } } }
    /// critical thermal state
    var bakePaused = false { didSet { if !bakePaused { pump() } } }

    private let clock: Clock
    private let execute: Execute
    private var laneBusy: [Bool]
    private var pending: [TileKey: TileEntry] = [:]
    private var running: [TileKey: TileEntry] = [:]
    private var bases: [String: BaseEntry] = [:]
    private var bakeQueue: [BakeEntry] = []
    private var bakeRunning = 0
    private var pumping = false
    private var wakeScheduled = false

    private(set) var tileZoom: Int?
    private var tileZoomChangedAt = -Double.infinity
    private var centre: (x: Double, y: Double, z: Int)?
    private(set) var ewmaMs: Double?
    private var nextJobID = 0

    /// hooks for the crash guard + tests
    var willDispatch: ((PDFLaneWork) -> Void)?
    var didFinish: ((PDFLaneWork, Bool) -> Void)?
    var onVectorTiming: ((Double) -> Void)?

    init(lanes: Int, clock: Clock = .live, execute: @escaping Execute) {
        maxLanes = max(1, lanes)
        laneLimit = max(1, lanes)
        laneBusy = Array(repeating: false, count: max(1, lanes))
        self.clock = clock
        self.execute = execute
    }

    var isHeavy: Bool { (ewmaMs ?? 0) > PDFTileConstants.heavyThresholdMs }
    /// test hook, world px at the tile zoom
    var viewportCentre: (x: Double, y: Double)? { centre.map { ($0.x, $0.y) } }
    var pendingTileCount: Int { pending.count }
    var runningTileCount: Int { running.count }
    var busyLanes: Int { laneBusy.filter { $0 }.count }
    var hasPendingBake: Bool { !bakeQueue.isEmpty || bakeRunning > 0 }

    func recordVectorTiming(_ ms: Double) {
        let a = PDFTileConstants.ewmaAlpha
        ewmaMs = ewmaMs.map { a * ms + (1 - a) * $0 } ?? ms
        onVectorTiming?(ms)
    }

    // MARK: viewport

    /// E1: one sample per vector job that actually drew something. all EMPTY
    /// jobs never touched the page so they say nothing about its cost
    private func recordIfDrawn(_ m: [TileIndex: TileCacheEntry], ms: Double) {
        let drew = m.values.contains { if case .image = $0 { return true } else { return false } }
        if drew { recordVectorTiming(ms) }
    }

    private func newJobID() -> Int {
        nextJobID &+= 1
        return nextJobID
    }

    /// where the view is looking, at its tile zoom. a tile zoom change restarts
    /// the 150 ms settle; bands of pending tiles follow the new zoom. centre is
    /// in world px at tz (the viewport centre, not the requested tiles)
    func setViewport(centreX: Double, centreY: Double, tileZoom tz: Int) {
        if tz != tileZoom {
            tileZoom = tz
            tileZoomChangedAt = clock.now()
        }
        centre = (centreX, centreY, tz)
        for (k, e) in pending where e.band != .bake {
            e.band = k.tile.z == tz ? .visible : .fallback
        }
        for (_, b) in bases where !b.running && b.band != .bake {
            b.band = tz <= b.ctx.baseMaxZoom ? .visible : .fallback
        }
        pump()
    }

    // MARK: requests

    @discardableResult
    func requestTile(_ t: TileIndex, ctx: PDFRenderContext, band: PDFRenderBand,
                     orphan: ((TileCacheEntry) -> Void)? = nil,
                     report: JobReport? = nil,
                     completion: @escaping TileCompletion) -> PDFRenderTicket {
        let ticket = PDFRenderTicket()
        let key = TileKey(ctx: ctx.id, tile: t)
        if let r = running[key] {
            r.waiters.append((ticket, completion))
            if r.orphan == nil { r.orphan = orphan }
            if r.report == nil { r.report = report }
        } else {
            let e = pending[key] ?? TileEntry(ctx: ctx, band: band)
            e.band = min(e.band, band)
            e.waiters.append((ticket, completion))
            if e.orphan == nil { e.orphan = orphan }
            if e.report == nil { e.report = report }
            pending[key] = e
        }
        ticket.setOnCancel { [weak self] in self?.cancelTile(key, ticket) }
        pump()
        return ticket
    }

    @discardableResult
    func requestBase(ctx: PDFRenderContext, band: PDFRenderBand,
                     completion: @escaping (Result<PDFPageRaster, Error>) -> Void) -> PDFRenderTicket {
        let ticket = PDFRenderTicket()
        let key = ctx.basePlan.key + "#\(ctx.identity.contentKey)"
        let e = bases[key] ?? BaseEntry(ctx: ctx, band: band)
        e.band = min(e.band, band)
        e.waiters.append((ticket, completion))
        bases[key] = e
        ticket.setOnCancel { [weak self] in
            guard let self, let b = self.bases[key] else { return }
            b.waiters.removeAll { $0.ticket === ticket }
            if b.waiters.isEmpty, !b.running { self.bases[key] = nil }
        }
        pump()
        return ticket
    }

    @discardableResult
    func requestBake(job: TileJob, ctx: PDFRenderContext, completion: @escaping BakeCompletion) -> PDFRenderTicket {
        let ticket = PDFRenderTicket()
        let entry = BakeEntry(ticket: ticket, job: job, ctx: ctx, done: completion)
        bakeQueue.append(entry)
        ticket.setOnCancel { [weak self] in self?.bakeQueue.removeAll { $0 === entry } }
        pump()
        return ticket
    }

    private func cancelTile(_ key: TileKey, _ ticket: PDFRenderTicket) {
        if let e = pending[key] {
            e.waiters.removeAll { $0.ticket === ticket }
            // nobody wants it any more and it hasnt started: drop it
            if e.waiters.isEmpty { pending[key] = nil }
        } else if let r = running[key] {
            // running jobs finish, the result goes to the orphan cache
            r.waiters.removeAll { $0.ticket === ticket }
        }
    }

    // MARK: pumping

    private enum Pick {
        case tile(TileKey, TileEntry)
        case base(String, BaseEntry)
        case bake(BakeEntry)
    }

    func pump() {
        guard !pumping else { return }
        pumping = true
        defer { pumping = false }
        while let lane = freeLane(), let pick = pickNext() {
            dispatch(pick, lane: lane)
        }
    }

    private func freeLane() -> Int? {
        let busy = laneBusy.filter { $0 }.count
        guard busy < laneLimit else { return nil }
        return laneBusy.firstIndex(of: false)
    }

    private func distance(_ t: TileIndex) -> Double {
        guard let c = centre else { return 0 }
        let s = pow(2.0, Double(c.z - t.z))
        let dx = (Double(t.x) + 0.5) * 256 * s - c.x, dy = (Double(t.y) + 0.5) * 256 * s - c.y
        return dx * dx + dy * dy
    }

    private func pickNext() -> Pick? {
        let settled = clock.now() - tileZoomChangedAt >= PDFTileConstants.vectorSettleMs / 1000
        var best: (band: PDFRenderBand, dist: Double, pick: Pick)?
        func consider(_ band: PDFRenderBand, _ dist: Double, _ p: Pick) {
            if let b = best, (b.band, b.dist) <= (band, dist) { return }
            best = (band, dist, p)
        }
        // in the background nothing visible can run, so it cant hold the bake up either
        var visiblePending = false
        for (k, b) in bases where !b.running && !b.waiters.isEmpty {
            if !foreground && b.band != .bake { continue }
            if b.band == .visible { visiblePending = true }
            consider(b.band, -1, .base(k, b))
        }
        var waitingOnSettle = false
        for (k, e) in pending {
            guard foreground else { continue }
            if e.band == .visible { visiblePending = true }
            if !settled { waitingOnSettle = true; continue }
            consider(e.band, distance(k.tile), .tile(k, e))
        }
        if waitingOnSettle { scheduleWake() }
        if !visiblePending, bakeRunning < PDFTileConstants.maxBakeJobsInFlight, !bakePaused,
           let first = bakeQueue.first {
            consider(.bake, 0, .bake(first))
        }
        return best?.pick
    }

    private func scheduleWake() {
        guard !wakeScheduled else { return }
        wakeScheduled = true
        let remaining = max(0.001, PDFTileConstants.vectorSettleMs / 1000 - (clock.now() - tileZoomChangedAt))
        clock.after(remaining) { [weak self] in
            self?.wakeScheduled = false
            self?.pump()
        }
    }

    private func dispatch(_ pick: Pick, lane: Int) {
        laneBusy[lane] = true
        switch pick {
        case .base(let key, let b):
            b.running = true
            let work = PDFLaneWork.base(b.ctx)
            let id = newJobID()
            willDispatch?(work)
            execute(lane, work) { [weak self] result in
                guard let self else { return }
                self.laneBusy[lane] = false
                self.bases[key] = nil
                self.didFinish?(work, (try? result.get()) != nil)
                let r: Result<PDFPageRaster, Error> = result.flatMap {
                    if case .base(let raster) = $0 { return .success(raster) }
                    return .failure(PDFRenderFailure.renderError)
                }.mapError { PDFJobError($0, jobID: id) }
                for w in b.waiters where !w.ticket.isCancelled { w.done(r) }
                self.pump()
            }

        case .tile(let seedKey, let seed):
            var job = TileJob(z: seedKey.tile.z, x0: seedKey.tile.x, y0: seedKey.tile.y, cols: 1, rows: 1)
            if seed.band == .visible, isHeavy {
                var pv = Set<TileIndex>()
                for (k, e) in pending where k.ctx == seedKey.ctx && e.band == .visible && k.tile.z == seedKey.tile.z {
                    pv.insert(k.tile)
                }
                job = PDFJobFormation.grow(seed: seedKey.tile, pendingVisible: pv)
            }
            var keys: [TileKey] = []
            for t in job.tiles {
                let k = TileKey(ctx: seedKey.ctx, tile: t)
                if let e = pending.removeValue(forKey: k) {
                    running[k] = e
                    keys.append(k)
                }
            }
            let work = PDFLaneWork.tiles(job, seed.ctx)
            let id = newJobID()
            willDispatch?(work)
            execute(lane, work) { [weak self] result in
                guard let self else { return }
                self.laneBusy[lane] = false
                self.didFinish?(work, (try? result.get()) != nil)
                var map: [TileIndex: TileCacheEntry] = [:]
                var failure: Error?
                switch result {
                case .success(.tiles(let m, let ms)):
                    map = m
                    // R2: a started job feeds the EWMA whoever is still waiting
                    self.recordIfDrawn(m, ms: ms)
                case .success:
                    failure = PDFJobError(PDFRenderFailure.renderError, jobID: id)
                case .failure(let e):
                    failure = PDFJobError(e, jobID: id)
                }
                // R2: counted once per started job, even with every waiter gone.
                // a tile the renderer didnt hand back is the job failing
                var outcome: Result<Void, Error> = failure.map { .failure($0) } ?? .success(())
                if failure == nil, keys.contains(where: { map[$0.tile] == nil }) {
                    outcome = .failure(PDFJobError(PDFRenderFailure.renderError, jobID: id))
                }
                keys.lazy.compactMap { self.running[$0]?.report }.first?(outcome)
                for k in keys {
                    guard let e = self.running.removeValue(forKey: k) else { continue }
                    let live = e.waiters.filter { !$0.ticket.isCancelled }
                    if let failure {
                        live.forEach { $0.done(.failure(failure)) }
                    } else if let entry = map[k.tile] {
                        if live.isEmpty { e.orphan?(entry) } else { live.forEach { $0.done(.success(entry)) } }
                    } else {
                        let missing = PDFJobError(PDFRenderFailure.renderError, jobID: id)
                        live.forEach { $0.done(.failure(missing)) }
                    }
                }
                self.pump()
            }

        case .bake(let b):
            bakeQueue.removeAll { $0 === b }
            bakeRunning += 1
            let work = PDFLaneWork.bake(b.job, b.ctx)
            willDispatch?(work)
            execute(lane, work) { [weak self] result in
                guard let self else { return }
                self.laneBusy[lane] = false
                self.bakeRunning -= 1
                self.didFinish?(work, (try? result.get()) != nil)
                switch result {
                case .success(.tiles(let m, let ms)):
                    self.recordIfDrawn(m, ms: ms)
                    if !b.ticket.isCancelled { b.done(.success((m, ms))) }
                case .success:
                    if !b.ticket.isCancelled { b.done(.failure(PDFRenderFailure.renderError)) }
                case .failure(let e):
                    if !b.ticket.isCancelled { b.done(.failure(e)) }
                }
                self.pump()
            }
        }
    }
}

// MARK: - lanes

/// Serial queues, each with its own lazily opened CGPDFDocument. CGPDF
/// objects never cross lanes and never touch main. Plain GCD on purpose:
/// these draws block for hundreds of ms and dont belong on the cooperative pool.
final class PDFRenderLanes {
    private final class Lane {
        let queue: DispatchQueue
        var key: String?
        var doc: CGPDFDocument?
        var page: CGPDFPage?
        // queue names, not display text
        private static let names = ["tacmap.pdf-lane-0", "tacmap.pdf-lane-1", "tacmap.pdf-lane-2", "tacmap.pdf-lane-3"]
        init(_ i: Int) {
            let name = Self.names[i % Self.names.count]
            queue = DispatchQueue(label: name, qos: .userInitiated)
        }
    }

    private let lanes: [Lane]
    var count: Int { lanes.count }

    init(count: Int) {
        lanes = (0..<max(1, count)).map(Lane.init)
    }

    func run(lane i: Int, work: PDFLaneWork, done: @escaping (Result<PDFLaneOutput, Error>) -> Void) {
        let lane = lanes[i % lanes.count]
        let qos: DispatchQoS
        if case .bake = work { qos = .utility } else { qos = .userInitiated }
        lane.queue.async(qos: qos) {
            let result = Result { try Self.perform(work, lane: lane) }
            DispatchQueue.main.async { done(result) }
        }
    }

    /// drop the per lane documents (memory warning), reopened on demand
    func trimMemory() {
        for lane in lanes {
            lane.queue.async { lane.doc = nil; lane.page = nil; lane.key = nil }
        }
    }

    private static func page(for ctx: PDFRenderContext, lane: Lane) throws -> CGPDFPage {
        let key = ctx.url.path + "#\(ctx.identity.pageIndex)"
        if lane.key == key, let p = lane.page { return p }
        lane.doc = nil; lane.page = nil; lane.key = nil
        let (doc, page) = try PDFTileRenderer.openPage(url: ctx.url, pageIndex: ctx.identity.pageIndex)
        lane.doc = doc; lane.page = page; lane.key = key
        return page
    }

    private static func perform(_ work: PDFLaneWork, lane: Lane) throws -> PDFLaneOutput {
        switch work {
        case .tiles(let job, let ctx), .bake(let job, let ctx):
            return try autoreleasepool {
                let page = try page(for: ctx, lane: lane)
                let t0 = CACurrentMediaTime()
                let plan = PDFTileWarp.plan(job: job, tilePx: ctx.tilePx, footprint: ctx.footprint, georef: ctx.georef)
                let kind: PDFRenderSourceKind = plan.cells.count <= 1 ? .vector(page) : .staged(page)
                let out = try PDFTileRenderer.renderJob(job, tilePx: ctx.tilePx, plan: plan, footprint: ctx.footprint, source: kind)
                return .tiles(out, ms: (CACurrentMediaTime() - t0) * 1000)
            }
        case .base(let ctx):
            // its own short lived document, so the full page caches go away after
            return try autoreleasepool {
                let (_, page) = try PDFTileRenderer.openPage(url: ctx.url, pageIndex: ctx.identity.pageIndex)
                return .base(try PDFTileRenderer.renderBaseRaster(page: page, plan: ctx.basePlan, footprint: ctx.footprint))
            }
        }
    }
}

// MARK: - service

/// One per PDF document identity. Owns the lanes, the scheduler and the base
/// raster. Main thread API. Refcounted registry with a 30 s idle linger so a
/// georef refit or a sheet reopen doesnt throw the base raster away.
final class PDFRenderService {
    private static var registry: [PDFDocumentIdentity: PDFRenderService] = [:]
    static let idleTTL: TimeInterval = 30

    static func acquire(_ identity: PDFDocumentIdentity, url: URL) -> PDFRenderService {
        if let s = registry[identity], s.url == url {
            s.refs += 1
            s.idleGeneration &+= 1
            return s
        }
        let s = PDFRenderService(identity: identity, url: url)
        s.refs = 1
        registry[identity] = s
        return s
    }

    /// test hook
    static func resetRegistry() { registry.removeAll() }

    func release() {
        refs -= 1
        guard refs <= 0 else { return }
        idleGeneration &+= 1
        let gen = idleGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.idleTTL) { [weak self] in
            guard let self, self.refs <= 0, self.idleGeneration == gen else { return }
            if Self.registry[self.identity] === self { Self.registry[self.identity] = nil }
            self.lanes.trimMemory()
        }
    }

    let identity: PDFDocumentIdentity
    let url: URL
    let lanes: PDFRenderLanes
    let scheduler: PDFRenderScheduler
    private var refs = 0
    /// test hook
    var refCount: Int { refs }
    private var idleGeneration: UInt64 = 0
    private(set) var baseRaster: PDFPageRaster?
    private var observers: [NSObjectProtocol] = []
    /// crash guard on/off, tests turn it off
    var usesCrashGuard = true
    var guardStore: PDFRenderGuard = .shared
    private(set) var foreground = true

    init(identity: PDFDocumentIdentity, url: URL,
         lanes laneCount: Int = PDFMemoryTier.iosVectorLanes(physicalMemory: ProcessInfo.processInfo.physicalMemory),
         clock: PDFRenderScheduler.Clock = .live, execute: PDFRenderScheduler.Execute? = nil) {
        self.identity = identity
        self.url = url
        let lanes = PDFRenderLanes(count: laneCount)
        self.lanes = lanes
        scheduler = PDFRenderScheduler(lanes: laneCount, clock: clock,
                                       execute: execute ?? { i, w, d in lanes.run(lane: i, work: w, done: d) })
        scheduler.willDispatch = { [weak self] w in self?.armGuard(for: w) }
        scheduler.didFinish = { [weak self] w, _ in self?.completeGuard(for: w) }
        foreground = UIApplication.shared.applicationState != .background
        scheduler.foreground = foreground
        applyThermal()
        let nc = NotificationCenter.default
        observers.append(nc.addObserver(forName: ProcessInfo.thermalStateDidChangeNotification, object: nil,
                                        queue: .main) { [weak self] _ in self?.applyThermal() })
        observers.append(nc.addObserver(forName: Notification.Name.NSProcessInfoPowerStateDidChange, object: nil,
                                        queue: .main) { [weak self] _ in self?.applyThermal() })
        observers.append(nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil,
                                        queue: .main) { [weak self] _ in self?.setForeground(false) })
        observers.append(nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil,
                                        queue: .main) { [weak self] _ in self?.setForeground(true) })
        observers.append(nc.addObserver(forName: UIApplication.didReceiveMemoryWarningNotification, object: nil,
                                        queue: .main) { [weak self] _ in self?.trimMemory() })
    }

    deinit { observers.forEach { NotificationCenter.default.removeObserver($0) } }

    private func applyThermal() {
        let p = ProcessInfo.processInfo
        let hot = p.thermalState.rawValue >= ProcessInfo.ThermalState.serious.rawValue
        scheduler.laneLimit = hot || p.isLowPowerModeEnabled ? 1 : scheduler.maxLanes
        scheduler.bakePaused = p.thermalState == .critical
    }

    /// background: live bands stop, the bake keeps going, any base/vector
    /// guard marker comes off (a jetsam kill in the background isnt our crash)
    func setForeground(_ fg: Bool) {
        foreground = fg
        scheduler.foreground = fg
        if !fg, usesCrashGuard { guardStore.disarmForBackground() }
    }

    func trimMemory() {
        lanes.trimMemory()
    }

    // MARK: base raster

    /// cached, or rendered once however many ask (single flight)
    @discardableResult
    func baseRaster(ctx: PDFRenderContext, band: PDFRenderBand,
                    _ done: @escaping (Result<PDFPageRaster, Error>) -> Void) -> PDFRenderTicket {
        if let r = baseRaster, r.planKey == ctx.basePlan.key {
            let t = PDFRenderTicket()
            DispatchQueue.main.async { if !t.isCancelled { done(.success(r)) } }
            return t
        }
        return scheduler.requestBase(ctx: ctx, band: band) { [weak self] result in
            if case .success(let r) = result { self?.baseRaster = r }
            done(result)
        }
    }

    /// hand off from the import probe so the first paint is instant
    func adoptBaseRaster(_ r: PDFPageRaster) {
        baseRaster = r
    }

    // MARK: crash guard

    private func armGuard(for work: PDFLaneWork) {
        guard usesCrashGuard, foreground else { return }
        switch work {
        case .base(let ctx):
            guardStore.arm(kind: .base, token: ctx.guardToken, foreground: true)
        case .tiles(_, let ctx):
            guardStore.arm(kind: .vector, token: ctx.guardToken, foreground: true)
        case .bake:
            break // the bake arms itself for the whole run
        }
    }

    private func completeGuard(for work: PDFLaneWork) {
        guard usesCrashGuard else { return }
        switch work {
        case .base(let ctx): guardStore.complete(kind: .base, token: ctx.guardToken)
        case .tiles(_, let ctx): guardStore.complete(kind: .vector, token: ctx.guardToken)
        case .bake: break
        }
    }
}
