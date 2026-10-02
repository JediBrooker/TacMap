import Foundation
import UIKit

/// A finished bake attached to its PDF. Lives in the sealed session.
struct PDFBakeRecord: Codable, Hashable, Sendable {
    var fileName: String
    var bakeKey: String
    var minZoom: Int
    var maxZoom: Int
    var tilePx: Int
    var bytes: Int64
}

/// Read side of a bake. Only trusted when its metadata still matches the
/// live georef + tile size, otherwise the live renderer draws everything.
final class PDFBakeReader {
    let url: URL
    let minZoom: Int
    let maxZoom: Int
    private let store: MBTilesStore

    init?(url: URL, expectedKey: String, tilePx: Int) {
        guard let store = MBTilesStore(url: url),
              store.extensionMetadata("tacmap_bake_key") == expectedKey,
              store.extensionMetadata("tacmap_tile_px") == String(tilePx),
              let maxZ = store.metadata.maxZoom else { return nil }
        self.url = url
        self.store = store
        minZoom = store.metadata.minZoom ?? 0
        maxZoom = maxZ
    }

    func covers(_ z: Int) -> Bool { z >= minZoom && z <= maxZoom }

    /// background only. nil = no row, the caller renders it live
    func tileImage(_ t: TileIndex) -> CGImage? {
        guard let data = store.tileData(z: t.z, x: t.x, y: t.y) else { return nil }
        return decodedTileImage(data)?.cgImage
    }

    func close() { store.closeForDeletion() }

    /// the same offline_tiles the library reconciles and sweeps
    static func directoryURL() -> URL { ImportedMapLibrary.offlineTilesDirectory }
}

/// Live failure accounting, contract G2, pinned by fixture failureAccounting.
/// Pure so the fixture drives the exact thing the tile source runs.
struct PDFFailureTracker: Equatable {
    enum Event: Equatable {
        case jobOk
        /// nil = some error we cant classify, counts as renderError
        case jobFailed(PDFRenderFailure?)
        case jobCancelled
        /// bake + bake estimate jobs, they report to the bake not here
        case bakeJob
        case documentFailure(PDFRenderFailure)
        case blankVerdict
        case retry
    }

    /// the file itself is the problem, retrying wont help
    static let stickyAtOnce: Set<PDFRenderFailure> = [.cannotOpen, .passwordProtected, .pageMissing, .pageGeometry]

    private(set) var failed: PDFRenderFailure?
    private(set) var consecutiveFailures = 0

    mutating func apply(_ e: Event) {
        if failed != nil {
            // sticky, only Try Again gets out
            if e == .retry { failed = nil; consecutiveFailures = 0 }
            return
        }
        switch e {
        case .jobOk, .retry:
            consecutiveFailures = 0
        case .jobCancelled, .bakeJob:
            break
        case .documentFailure(let r):
            stick(r)
        case .blankVerdict:
            stick(.blank)
        case .jobFailed(let r):
            if let r, Self.stickyAtOnce.contains(r) { return stick(r) }
            consecutiveFailures += 1
            if consecutiveFailures >= PDFTileConstants.renderErrorConsecutiveFailures {
                stick(r == .outOfMemory ? .outOfMemory : .renderError)
            }
        }
    }

    private mutating func stick(_ r: PDFRenderFailure) {
        failed = r
        consecutiveFailures = 0
    }
}

/// R1 base raster lifecycle on top of the G2 run, one pure reducer. The live
/// source runs exactly this, fixture failureAccounting drives it.
/// Started once per source (the init kick counts as a wanted at any z), never
/// again from a callback above baseMaxZoom. A failed attempt counts once and
/// is only retried when something at z <= baseMaxZoom is wanted and nothing
/// is in flight. No half budget retry, the plan never changes mid session.
struct PDFLiveAccounting: Equatable {
    enum BaseStatus: String { case notStarted, inFlight, ready, blank, failed }
    enum BaseResult: Equatable {
        case ok, blank
        /// nil = unclassified, renderError
        case failed(PDFRenderFailure?)
    }
    enum Event: Equatable {
        case job(PDFFailureTracker.Event)
        /// a non ignored wanted callback at this tile zoom, or a load at z
        case wanted(z: Int)
        case baseDone(BaseResult)
    }
    struct Effects: Equatable {
        var startsBaseAttempt = false
        /// base landed, ask the view to plan again
        var replan = false
    }

    /// < 0 = no base raster, every zoom draws vector
    var baseMaxZoom: Int
    private(set) var tracker = PDFFailureTracker()
    private(set) var baseStatus: BaseStatus = .notStarted
    private(set) var baseAttempts = 0

    init(baseMaxZoom: Int = -1) { self.baseMaxZoom = baseMaxZoom }

    var failed: PDFRenderFailure? { tracker.failed }
    var consecutiveFailures: Int { tracker.consecutiveFailures }

    @discardableResult
    mutating func apply(_ e: Event) -> Effects {
        var fx = Effects()
        switch e {
        case .job(let j):
            tracker.apply(j)
            // Try Again is a whole new source, base included
            if j == .retry { baseStatus = .notStarted; baseAttempts = 0 }
        case .wanted(let z):
            guard tracker.failed == nil, baseMaxZoom >= 0 else { break }
            if baseStatus == .notStarted || (baseStatus == .failed && z <= baseMaxZoom) {
                baseStatus = .inFlight
                baseAttempts += 1
                fx.startsBaseAttempt = true
            }
        case .baseDone(let r):
            guard baseStatus == .inFlight else { break }
            let wasFailed = tracker.failed != nil
            switch r {
            case .ok:
                baseStatus = .ready
                tracker.apply(.jobOk)
                fx.replan = !wasFailed
            case .blank:
                baseStatus = .blank
                tracker.apply(.blankVerdict)
            case .failed(let f):
                baseStatus = .failed
                tracker.apply(.jobFailed(f))
            }
        }
        return fx
    }
}

/// The imported PDF as an ordinary raster tile source: Web Mercator tiles
/// warped out of the page on demand (contract F). Main thread API.
///
/// Load order: failed -> nothing, OUTSIDE -> EMPTY, orphan cache, bake row,
/// z <= baseMaxZoom -> sample the base raster, else a vector job on a lane.
/// A failed source stays on the view (G1): whatever is cached keeps drawing,
/// it just never starts or delivers anything new.
final class PDFTileSource: RasterTileSource {
    let service: PDFRenderService
    private(set) var context: PDFRenderContext?
    private(set) var status: PDFRenderStatus = .preparing {
        didSet { if status != oldValue { onStatus?(status) } }
    }
    var onStatus: ((PDFRenderStatus) -> Void)?
    /// poke the tile view: base raster landed, bake attached, failure
    var onNeedsLayout: (() -> Void)?

    private(set) var baseRaster: PDFPageRaster?
    private var baseTicket: PDFRenderTicket?
    private(set) var bake: PDFBakeReader?
    private var accounting = PDFLiveAccounting()
    /// raster level loads waiting on the base attempt in flight
    private var parked: [(ticket: PDFRenderTicket, tile: TileIndex, done: (UIImage?) -> Void)] = []
    private var orphans: [(TileIndex, TileCacheEntry)] = []
    private var currentTileZoom: Int?
    private var coverage: [TileIndex: TileCoverage] = [:]

    /// base raster sampling and bake reads. never waits behind a vector job
    static let rasterQueue: OperationQueue = {
        let q = OperationQueue()
        q.name = "tacmap.pdf-raster"
        q.maxConcurrentOperationCount = 2
        q.qualityOfService = .userInitiated
        return q
    }()

    init(service: PDFRenderService) {
        self.service = service
    }

    deinit {
        baseTicket?.cancel()
        bake?.close()
    }

    // MARK: lifecycle

    /// the context gets built off main (opening the PDF for its page box),
    /// this lands it. R1: the init kick starts base attempt 1 straight away
    func install(_ ctx: PDFRenderContext) {
        guard status.failure == nil else { return }
        context = ctx
        coverage.removeAll()
        accounting.baseMaxZoom = ctx.baseMaxZoom
        // < 0 is a tiny or super detailed sheet, every zoom draws vector
        apply(.wanted(z: currentTileZoom ?? 0))
        onNeedsLayout?()
    }

    /// document level failure (open, parse, page geometry), sticky at once.
    /// nothing new gets rendered until the runtime builds a new source
    func fail(_ reason: PDFRenderFailure) {
        record(.documentFailure(reason))
    }

    private func record(_ e: PDFFailureTracker.Event) {
        apply(.job(e))
    }

    private func apply(_ e: PDFLiveAccounting.Event) {
        let fx = accounting.apply(e)
        if let f = accounting.failed {
            guard status.failure == nil else { return }
            status = .failed(f)
            baseTicket?.cancel()
            baseTicket = nil
            orphans.removeAll()
            drainParked()
            onNeedsLayout?()
            return
        }
        if fx.startsBaseAttempt { startBaseAttempt() }
        if fx.replan { onNeedsLayout?() }
    }

    private func startBaseAttempt() {
        guard let ctx = context else { return }
        let band: PDFRenderBand = (currentTileZoom ?? ctx.baseMaxZoom) <= ctx.baseMaxZoom ? .visible : .fallback
        baseTicket = service.baseRaster(ctx: ctx, band: band) { [weak self] result in
            guard let self else { return }
            self.baseTicket = nil
            switch result {
            case .success(let r) where r.blank:
                self.apply(.baseDone(.blank))
            case .success(let r):
                if self.status.failure == nil {
                    if self.baseRaster == nil { self.baseRaster = r }
                    if self.status == .preparing { self.status = .ready }
                }
                // a good base raster resets the run (R1), then the view plans again
                self.apply(.baseDone(.ok))
            case .failure(let e):
                // counted once, not sticky unless the file itself is the problem
                self.apply(.baseDone(.failed(Self.reason(e))))
            }
            self.drainParked()
        }
    }

    /// parked raster loads: sample them now the base is here, else they miss
    /// and the view asks again later
    private func drainParked() {
        let waiting = parked
        parked.removeAll()
        for p in waiting where !p.ticket.isCancelled {
            if status.failure == nil, let raster = baseRaster, let ctx = context {
                sample(p.tile, ctx, raster, p.ticket, p.done)
            } else {
                p.done(nil)
            }
        }
    }

    static func reason(_ err: Error) -> PDFRenderFailure? {
        PDFJobError.unwrap(err) as? PDFRenderFailure
    }

    func attachBake(_ reader: PDFBakeReader?) {
        bake?.close()
        bake = reader
        onNeedsLayout?()
    }

    // MARK: RasterTileSource

    var minZoom: Int { 0 }
    var maxZoom: Int { context?.detailZoom ?? 0 }
    var tilePixelSize: Int { context?.tilePx ?? 256 }

    /// plain geometry, failed or not, so a failed sheet keeps its cached
    /// tiles and fallbacks on screen instead of going dark (G1)
    func hasContent(_ t: TileIndex) -> Bool {
        guard let ctx = context else { return false }
        return classify(t, ctx) != .outside
    }

    private func classify(_ t: TileIndex, _ ctx: PDFRenderContext) -> TileCoverage {
        if let c = coverage[t] { return c }
        let c = ctx.footprint.classify(t)
        if coverage.count > 4096 { coverage.removeAll(keepingCapacity: true) }
        coverage[t] = c
        return c
    }

    func fallbackZoom(forTileZoom tz: Int) -> Int? {
        guard let ctx = context else { return nil }
        let fz: Int
        if let bake {
            fz = min(tz - 1, bake.maxZoom)
        } else if baseRaster != nil, ctx.baseMaxZoom >= 0 {
            fz = min(tz - 1, ctx.baseMaxZoom)
        } else {
            return nil
        }
        return fz >= 0 ? fz : nil
    }

    /// E3: the real viewport centre and the tile zoom, every time, even when
    /// nothing at tz is in the list. The view doesnt call this while hidden
    /// or underzoomed. R1: a wanted at z <= baseMaxZoom may restart a failed base
    func wantedTilesDidChange(_ ordered: [TileIndex], tileZoom: Int, centreUnit: CGPoint) {
        currentTileZoom = tileZoom
        let scale = 256 * pow(2.0, Double(tileZoom))
        service.scheduler.setViewport(centreX: Double(centreUnit.x) * scale, centreY: Double(centreUnit.y) * scale,
                                      tileZoom: tileZoom)
        if context != nil { apply(.wanted(z: tileZoom)) }
    }

    /// G1: failed delivers nothing, not even EMPTY. nil request = miss, the
    /// tile stays missing and its fallback keeps drawing
    func loadTile(_ t: TileIndex, completion: @escaping (UIImage?) -> Void) -> RasterTileRequest? {
        guard status.failure == nil, let ctx = context else { return nil }
        let ticket = PDFRenderTicket()
        if classify(t, ctx) == .outside {
            DispatchQueue.main.async { if !ticket.isCancelled { completion(RasterTileSourceEmpty.image) } }
            return ticket
        }
        if let i = orphans.firstIndex(where: { $0.0 == t }) {
            let entry = orphans.remove(at: i).1
            DispatchQueue.main.async { [weak self] in
                guard let self, !ticket.isCancelled else { return }
                // B4: went sticky in between, nothing new gets delivered
                guard self.status.failure == nil else { return completion(nil) }
                self.deliver(entry, completion)
            }
            return ticket
        }
        if let bake, bake.covers(t.z) {
            let op = BlockOperation()
            op.addExecutionBlock { [weak op] in
                guard let op, !op.isCancelled else { return }
                let img = bake.tileImage(t)
                DispatchQueue.main.async { [weak self] in
                    guard let self, !ticket.isCancelled else { return }
                    guard self.status.failure == nil else { return completion(nil) }
                    if let img {
                        self.deliver(.image(img), completion)
                    } else {
                        // missing row for an intersecting tile: draw it live
                        self.loadLive(t, ctx, ticket, completion)
                    }
                }
            }
            ticket.setOnCancel { op.cancel() }
            Self.rasterQueue.addOperation(op)
            return ticket
        }
        loadLive(t, ctx, ticket, completion)
        return ticket
    }

    private func loadLive(_ t: TileIndex, _ ctx: PDFRenderContext, _ ticket: PDFRenderTicket,
                          _ completion: @escaping (UIImage?) -> Void) {
        guard status.failure == nil else { return completion(nil) }
        if t.z <= ctx.baseMaxZoom {
            if let raster = baseRaster {
                sample(t, ctx, raster, ticket, completion)
                return
            }
            // R1: no second render of the same base, wait on the one attempt.
            // a failed base only restarts for a wanted tile at this zoom range
            apply(.wanted(z: t.z))
            if accounting.baseStatus == .inFlight, status.failure == nil {
                parked.append((ticket, t, completion))
                ticket.setOnCancel { [weak self] in self?.parked.removeAll { $0.ticket === ticket } }
            } else {
                DispatchQueue.main.async { if !ticket.isCancelled { completion(nil) } }
            }
            return
        }
        let band: PDFRenderBand = t.z == currentTileZoom ? .visible : .fallback
        let inner = service.scheduler.requestTile(t, ctx: ctx, band: band, orphan: { [weak self] e in
            self?.addOrphan(t, e)
        }, report: { [weak self] outcome in
            // R2: one count per started job, whoever is still waiting on it
            guard let self else { return }
            switch outcome {
            case .success: self.record(.jobOk)
            case .failure(let e): self.jobFailed(e)
            }
        }) { [weak self] result in
            guard let self else { return }
            // a job that was still running when we went sticky gets dropped
            guard self.status.failure == nil else { return completion(nil) }
            switch result {
            case .success(let e): self.deliver(e, completion)
            case .failure: completion(nil)
            }
        }
        ticket.setOnCancel { inner.cancel() }
    }

    /// z <= baseMaxZoom: warp the base raster, no PDF work at all
    private func sample(_ t: TileIndex, _ ctx: PDFRenderContext, _ raster: PDFPageRaster,
                        _ ticket: PDFRenderTicket, _ completion: @escaping (UIImage?) -> Void) {
        let op = BlockOperation()
        op.addExecutionBlock { [weak op] in
            // cancelled before it started: no effect at all (R2). once going it
            // runs to the end (R2a), no mid job cancel hooks on live paths
            guard let op, !op.isCancelled else { return }
            let job = TileJob(z: t.z, x0: t.x, y0: t.y, cols: 1, rows: 1)
            let result = Result {
                try autoreleasepool { () -> TileCacheEntry in
                    let plan = PDFTileWarp.plan(job: job, tilePx: ctx.tilePx, footprint: ctx.footprint, georef: ctx.georef)
                    let out = try PDFTileRenderer.renderJob(job, tilePx: ctx.tilePx, plan: plan, footprint: ctx.footprint,
                                                            source: .raster(raster))
                    return out[t] ?? .empty
                }
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                guard self.status.failure == nil else {
                    if !ticket.isCancelled { completion(nil) }
                    return
                }
                switch result {
                case .success(let e):
                    // the raster sample path counts too, success resets the run
                    self.record(.jobOk)
                    if ticket.isCancelled { self.addOrphan(t, e) } else { self.deliver(e, completion) }
                case .failure(let err):
                    self.jobFailed(err)
                    if !ticket.isCancelled { completion(nil) }
                }
            }
        }
        ticket.setOnCancel { op.cancel() }
        Self.rasterQueue.addOperation(op)
    }

    private func deliver(_ e: TileCacheEntry, _ completion: (UIImage?) -> Void) {
        switch e {
        case .empty:
            completion(RasterTileSourceEmpty.image)
        case .image(let img):
            if status == .preparing { status = .ready }
            completion(UIImage(cgImage: img))
        }
    }

    /// G2 table: sticky reasons stick, the rest count toward the run of 3
    private func jobFailed(_ err: Error) {
        if PDFJobError.unwrap(err) is CancellationError { return record(.jobCancelled) }
        record(.jobFailed(Self.reason(err)))
    }

    /// E3: only images get parked, an EMPTY answer is cheap to work out again
    private func addOrphan(_ t: TileIndex, _ e: TileCacheEntry) {
        guard case .image = e, status.failure == nil else { return }
        orphans.removeAll { $0.0 == t }
        orphans.append((t, e))
        if orphans.count > PDFTileConstants.orphanCacheTiles { orphans.removeFirst() }
    }

    var orphanCount: Int { orphans.count }
    /// test hooks: the current run of counted failures, and the R1 base state
    var failureRunLength: Int { accounting.consecutiveFailures }
    var baseStatus: PDFLiveAccounting.BaseStatus { accounting.baseStatus }
    var baseAttempts: Int { accounting.baseAttempts }
}
