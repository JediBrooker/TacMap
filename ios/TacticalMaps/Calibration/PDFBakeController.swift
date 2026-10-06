import Foundation
import UIKit
import Combine

/// One option in the confirm step: bake up to this zoom. Every number comes
/// out of PDFBakeEstimator, the same formula Android runs (contract J2).
struct PDFBakeOptionEstimate: Equatable, Identifiable {
    var id: Int { maxZoom }
    let maxZoom: Int
    let tiles: Int
    let jobsPerLevel: [Int]
    let jobs: Int
    let bytes: Int64
    /// free space it wants, 2x bytes
    let neededBytes: Int64
    let enoughSpace: Bool
    let estimatedMs: Double
    /// "about N min", never under 1
    let minutes: Int
}

/// what one estimate sample did. only an image that also encoded counts
struct PDFBakeSample: Equatable {
    enum Result: String { case image, empty, failed }
    let result: Result
    let jobMs: Double
    let bytes: Int?
    let encodeMs: Double?
}

struct PDFBakeEstimate: Equatable {
    enum From: String { case ewma, samples, fallback }
    let heavy: Bool
    let jobMs: Double
    let jobMsFrom: From
    let encodeMs: Double
    let encodeMsFrom: From
    let meanTileBytes: Double
    let meanTileBytesFrom: From
    let options: [PDFBakeOptionEstimate]
    let initialSelection: Int
    /// Generate for the initial selection, the sheet re-checks per selection
    let generateEnabled: Bool
}

/// Contract J2 as one pure function plus the sample picker. Only the samples
/// and the free space read touch the outside world, both come in as values.
enum PDFBakeEstimator {
    struct SamplePick: Equatable {
        enum Pool: String { case inside, all }
        let pool: Pool
        let poolCount: Int
        /// cx, cy in tiles at z, nil when the clip mean wouldnt go to WGS84
        let centreTile: (x: Double, y: Double)?
        let tiles: [TileIndex]
        let d2: [Double]

        static func == (a: Self, b: Self) -> Bool {
            a.pool == b.pool && a.poolCount == b.poolCount && a.tiles == b.tiles && a.d2 == b.d2
                && a.centreTile?.x == b.centreTile?.x && a.centreTile?.y == b.centreTile?.y
        }
    }

    /// up to 3 tiles at z nearest the clip centre. INSIDE ones if there are
    /// any, else every crop intersecting tile. ties by y then x. level comes
    /// in row major (y then x), which is also the fallback order
    static func sampleTiles(level: [(tile: TileIndex, coverage: TileCoverage)], z: Int,
                            centre: (latitude: Double, longitude: Double)?) -> SamplePick {
        let inside = level.filter { $0.coverage == .inside }.map(\.tile)
        let pool = inside.isEmpty ? level.map(\.tile) : inside
        let kind: SamplePick.Pool = inside.isEmpty ? .all : .inside
        let k = PDFTileConstants.bakeSampleTiles
        guard let c = centre else {
            return SamplePick(pool: kind, poolCount: pool.count, centreTile: nil, tiles: Array(pool.prefix(k)), d2: [])
        }
        let w = PDFTileMath.world0(lat: c.latitude, lon: c.longitude)
        let n = pow(2.0, Double(z))
        let cx = w.x * n / 256, cy = w.y * n / 256
        func d2(_ t: TileIndex) -> Double {
            let dx = Double(t.x) + 0.5 - cx, dy = Double(t.y) + 0.5 - cy
            return dx * dx + dy * dy
        }
        let ranked = pool.map { ($0, d2($0)) }.sorted { a, b in
            if a.1 != b.1 { return a.1 < b.1 }
            if a.0.y != b.0.y { return a.0.y < b.0.y }
            return a.0.x < b.0.x
        }.prefix(k)
        return SamplePick(pool: kind, poolCount: pool.count, centreTile: (cx, cy),
                          tiles: ranked.map(\.0), d2: ranked.map(\.1))
    }

    /// options = the kept J options (maxZoom, tiles). levelTiles(z) = the
    /// level's crop intersecting tiles, row major. baseMaxZoom < 0 = no base raster
    static func estimate(options: [PDFBakeOption], defaultMaxZoom: Int, baseMaxZoom: Int,
                         levelTiles: (Int) -> [TileIndex], samples: [PDFBakeSample],
                         ewmaMs: Double?, freeBytes: Int64?) -> PDFBakeEstimate {
        let counted = samples.filter { $0.result == .image && $0.bytes != nil && $0.encodeMs != nil }
        let n = Double(counted.count)
        let jobMs: Double, jobFrom: PDFBakeEstimate.From
        if let e = ewmaMs {
            (jobMs, jobFrom) = (e, .ewma)
        } else if !counted.isEmpty {
            (jobMs, jobFrom) = (counted.reduce(0.0) { $0 + $1.jobMs } / n, .samples)
        } else {
            (jobMs, jobFrom) = (PDFTileConstants.bakeFallbackJobMs, .fallback)
        }
        let encodeMs: Double, meanBytes: Double, sampleFrom: PDFBakeEstimate.From
        if !counted.isEmpty {
            encodeMs = counted.reduce(0.0) { $0 + $1.encodeMs! } / n
            meanBytes = Double(counted.reduce(0) { $0 + $1.bytes! }) / n
            sampleFrom = .samples
        } else {
            encodeMs = PDFTileConstants.bakeFallbackEncodeMs
            meanBytes = Double(PDFTileConstants.bakeFallbackTileBytes)
            sampleFrom = .fallback
        }
        let heavy = ewmaMs.map { $0 > PDFTileConstants.heavyThresholdMs } ?? false
        var perLevel: [Int: Int] = [:]
        func jobs(at z: Int) -> Int {
            if let j = perLevel[z] { return j }
            let j = PDFJobFormation.bakeLevel(z: z, tiles: levelTiles(z), baseMaxZoom: baseMaxZoom, heavy: { heavy }).jobs.count
            perLevel[z] = j
            return j
        }
        let opts = options.map { o -> PDFBakeOptionEstimate in
            let per = (0...max(0, o.maxZoom)).map(jobs(at:))
            let jobCount = per.reduce(0, +)
            let bytes = Int64((Double(o.tiles) * meanBytes * PDFTileConstants.bakeBytesSafety).rounded(.up))
            let need = Int64(PDFTileConstants.bakeFreeSpaceFactor) * bytes
            let ms = Double(jobCount) * jobMs + Double(o.tiles) * encodeMs
            return PDFBakeOptionEstimate(maxZoom: o.maxZoom, tiles: o.tiles, jobsPerLevel: per, jobs: jobCount,
                                         bytes: bytes, neededBytes: need,
                                         enoughSpace: freeBytes.map { $0 >= need } ?? true,
                                         estimatedMs: ms, minutes: max(1, Int((ms / 60000).rounded(.up))))
        }
        let fits = opts.filter(\.enoughSpace).map(\.maxZoom)
        let initial = fits.contains(defaultMaxZoom) ? defaultMaxZoom : (fits.max() ?? defaultMaxZoom)
        return PDFBakeEstimate(heavy: heavy, jobMs: jobMs, jobMsFrom: jobFrom, encodeMs: encodeMs, encodeMsFrom: sampleFrom,
                               meanTileBytes: meanBytes, meanTileBytesFrom: sampleFrom, options: opts,
                               initialSelection: initial, generateEnabled: fits.contains(initial))
    }
}

struct PDFBakeProposal: Equatable {
    let pdfName: String
    let contentKey: String
    let estimate: PDFBakeEstimate
    let defaultMaxZoom: Int
    var options: [PDFBakeOptionEstimate] { estimate.options }

    func option(_ maxZoom: Int) -> PDFBakeOptionEstimate? { options.first { $0.maxZoom == maxZoom } }
}

/// "Generate Offline Tiles". App lifetime singleton so the bake survives
/// the Layers sheet going away (D3-10, D5-06): state, progress and cancel
/// live here, not in a view. The PDF stays the active map and stays on
/// disk (D5-05), the bake just gets attached to it.
final class PDFBakeController: ObservableObject {
    static let shared = PDFBakeController()

    enum State: Equatable {
        case idle
        case estimating
        case confirming(PDFBakeProposal)
        case running(done: Int, total: Int)
        case failed(PDFBakeError)
    }

    enum AttachOutcome: Equatable { case attached, sourceChanged, writeFailed }

    /// a document level failure in any sample sinks the whole estimate (R4)
    static let documentFailures = PDFFailureTracker.stickyAtOnce

    @Published private(set) var state: State = .idle
    /// the radio row picked on the confirm step (J1)
    @Published private(set) var selectedMaxZoom: Int?
    /// one shot "Offline tiles ready", the chip/toast clears it
    @Published var finishedMessage = false
    /// F7: which PDF the estimate / bake is for, by its crash guard token and
    /// file. contentKey is optional on old sessions, these never are
    @Published private(set) var subjectToken: String?
    @Published private(set) var subjectURL: URL?
    /// bumped whenever a PDF's bake record changes (publish, remove) so an
    /// open Layers sheet redraws straight away (OD-F1), the map object isnt observable
    @Published private(set) var recordRevision = 0

    private var worker: PDFBakeWorker?
    /// the estimate's sample worker, so Cancel can stop it mid sample
    private var estimateWorker: PDFBakeWorker?
    private var estimateGeneration = 0
    /// F4: lets Cancel stop the footprint scans of a running estimate
    private var estimateCancel: PDFCancelFlag?
    /// F3: the estimate holds the K1 bake slot too, same token as the bake
    private var estimateGuardToken: String?
    private var pdf: PDFMapSource?
    private weak var runtime: PDFMapRuntime?
    private var service: PDFRenderService?
    private var ctx: PDFRenderContext?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var guardToken: String?
    var guardStore: PDFRenderGuard = .shared

    /// a finished bake that came back while the key was relocked (auth-bound,
    /// app out of the front). its record write could only fail behind the lock
    /// and bin the lot, so the .partial waits in the work dir (no sweep looks
    /// there) till the user's unlock. Android parks the same way since 3.0.1
    private struct ParkedBake {
        let out: (url: URL, bytes: Int64)
        let pdf: PDFMapSource
        let ctx: PDFRenderContext
        let maxZoom: Int
        let neededBytes: Int64
    }
    private var parked: ParkedBake?
    /// the relock gate (3.0.2 K1), tests stub it. read on main, where lockKey()
    /// runs too, so nothing can relock between this and the attach
    var keyRelocked: () -> Bool = { DataKey.isRelocked }

    /// done baking, waiting on the unlock to record it (state stays running)
    var waitingForUnlock: Bool { parked != nil }
    /// does the bake hold the idle timer off. a parked one is done working, so
    /// no: otherwise the device never auto locks while it waits on the unlock
    var keepsScreenAwake: Bool { isRunning && parked == nil }
    /// tests point this somewhere private
    var finalDirectory: URL = PDFBakeReader.directoryURL()
    // The three below go through the sealed imported-map library (the one
    // authority since the WP4 merge). MapViewModel.bindBakeController wires
    // them, until then they fail closed. Tests stub them.

    /// Remove step 1: clears the record on the library entry. false = not
    /// persisted, nothing gets deleted
    var detachRecord: (PDFBakeRecord, PDFMapSource) -> Bool = { _, _ in false }
    /// every bake name the library vouches for, for the sweep after Remove.
    /// nil = the library cant be trusted right now (locked etc), skip
    var storedBake: () -> Set<String>? = { nil }
    /// S1: puts only the bake record on the entry, and only while that entry is
    /// still the bytes + token + georef the bake was made from
    var attachRecord: (PDFBakeRecord, PDFMapSource, PDFRenderContext) -> AttachOutcome = { _, _, _ in .writeFailed }
    /// free bytes, nil = unknown. tests stub it
    var freeSpace: () -> Int64? = { PDFBakeController.freeSpaceBytes() }
    var onPublished: ((PDFMapSource) -> Void)?
    /// test hook: runs on the bake thread once the worker returns, before the
    /// hop back to main. lets a test land a cancel right at the end (F1)
    var afterRunHook: ((PDFBakeWorker) -> Void)?
    /// test hook: sees the bake worker before it starts (encoder / writer seams)
    var configureWorker: ((PDFBakeWorker) -> Void)?

    /// test hook, the render service this controller still holds a ref on
    var heldService: PDFRenderService? { service }

    var isRunning: Bool {
        if case .running = state { return true }
        return false
    }

    /// Generate stays off for a guessed placement, a failed source (OD-F9) and
    /// a restored source whose bytes havent been re-checked yet (R2-S4: the
    /// runtime clears the flag once the hash matches, Try Again included)
    static func generateBlocked(pdf: PDFMapSource, status: PDFRenderStatus) -> Bool {
        pdf.isUncalibrated || status.failure != nil || pdf.storedFileUnavailable
    }

    var progressFraction: Double {
        if case .running(let d, let t) = state, t > 0 { return Double(d) / Double(t) }
        return 0
    }

    /// is the current estimate / bake about this PDF
    func isFor(_ pdf: PDFMapSource) -> Bool {
        guard subjectToken != nil || subjectURL != nil else { return false }
        return subjectToken == pdf.renderGuardToken || subjectURL?.standardizedFileURL == pdf.url.standardizedFileURL
    }

    private func setSubject(_ pdf: PDFMapSource?) {
        subjectToken = pdf?.renderGuardToken
        subjectURL = pdf?.url
    }

    /// F3: the estimate is over (confirming, failed, cancelled): its K1 marker comes off
    private func endEstimateGuard() {
        if let t = estimateGuardToken { guardStore.complete(kind: .bake, token: t) }
        estimateGuardToken = nil
    }

    /// S5: let go of the service (its base raster + lane docs) and the map
    /// objects as soon as nothing needs them, so the 30 s idle TTL can run
    private func releaseResources() {
        estimateCancel?.cancel()
        estimateCancel = nil
        estimateWorker?.cancel()
        estimateWorker = nil
        endEstimateGuard()
        service?.release()
        service = nil
        ctx = nil
        pdf = nil
    }

    private func fail(_ e: PDFBakeError) {
        state = .failed(e)
        selectedMaxZoom = nil
        releaseResources()
    }

    // MARK: estimate

    /// render + encode up to 3 tiles near the crop centre at the default
    /// maxZoom, then price every option. Ends in .confirming or .failed.
    func prepare(pdf: PDFMapSource, runtime: PDFMapRuntime?) {
        guard !isRunning else { return }
        releaseResources()
        guard !pdf.isUncalibrated else { return fail(.notCalibrated) }
        estimateGeneration &+= 1
        let gen = estimateGeneration
        self.pdf = pdf
        self.runtime = runtime
        setSubject(pdf)
        selectedMaxZoom = nil
        state = .estimating
        // F3: a crash mid estimate is reported as an unfinished bake next launch
        if guardStore.arm(kind: .bake, token: pdf.renderGuardToken) { estimateGuardToken = pdf.renderGuardToken }
        let identity = PDFDocumentIdentity(contentKey: pdf.contentKey ?? pdf.url.path, pageIndex: pdf.georef.page)
        let svc = PDFRenderService.acquire(identity, url: pdf.url)
        service = svc
        let tilePx = runtime?.currentContext?.tilePx ?? PDFTileMath.tilePx(density: Double(UIScreen.main.scale))
        let url = pdf.url, georef = pdf.georef, token = pdf.renderGuardToken, name = pdf.displayName
        let key = pdf.contentKey ?? url.path
        let budget = PDFMemoryTier.baseBudgetPx(physicalMemory: ProcessInfo.processInfo.physicalMemory)
        let flag = PDFCancelFlag()
        estimateCancel = flag
        let needsFileCheck = pdf.storedFileUnavailable
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let built: Result<(PDFRenderContext, [PDFBakeOption], PDFBakeOption, [[TileIndex]], [TileIndex]), PDFBakeError>
            do {
                // R2-S4: a restored source flagged missing / changed only bakes once
                // the file is back with the exact stored bytes. the UI gate alone
                // isnt enough, a bake off swapped bytes would draw over the right ones
                if needsFileCheck, !ImportedMapLibrary.storedFileMatches(pdf) { throw PDFBakeError.renderFailed }
                let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: georef.page)
                let ctx = try PDFRenderContext(url: url, identity: identity, georef: georef,
                                               pageBox: PDFTileRenderer.pageBox(page), tilePx: tilePx,
                                               guardToken: token, baseBudgetPx: budget)
                let options = PDFBakePlan.options(detailZoom: ctx.detailZoom, footprint: ctx.footprint,
                                                  isCancelled: { flag.isCancelled })
                if flag.isCancelled { return }
                if let def = PDFBakePlan.defaultOption(options, detailZoom: ctx.detailZoom),
                   let top = options.map(\.maxZoom).max() {
                    // kept options are <= 6000 tiles all in, so these lists stay small
                    var levels: [[TileIndex]] = []
                    var defLevel: [(tile: TileIndex, coverage: TileCoverage)] = []
                    for z in 0...top {
                        let l = ctx.footprint.tiles(z: z, limit: PDFTileConstants.bakeMaxTiles,
                                                    isCancelled: { flag.isCancelled }) ?? []
                        if flag.isCancelled { return }
                        levels.append(l.map(\.tile))
                        if z == def.maxZoom { defLevel = l }
                    }
                    let c = ctx.georef.toWGS84(x: ctx.footprint.clipMean.x, y: ctx.footprint.clipMean.y)
                    let pick = PDFBakeEstimator.sampleTiles(level: defLevel, z: def.maxZoom,
                                                            centre: c.map { ($0.latitude, $0.longitude) })
                    built = .success((ctx, options, def, levels, pick.tiles))
                } else {
                    built = .failure(.tooLarge)
                }
            } catch {
                built = .failure(.renderFailed)
            }
            DispatchQueue.main.async {
                guard let self, self.estimateGeneration == gen, self.state == .estimating else { return }
                switch built {
                case .failure(let e): self.fail(e)
                case .success(let (ctx, options, def, levels, picks)):
                    self.ctx = ctx
                    self.measure(picks: picks, ctx: ctx, options: options, defaultZoom: def.maxZoom,
                                 levels: levels, name: name, key: key, gen: gen)
                }
            }
        }
    }

    enum MeasureEnd: Equatable { case done([PDFBakeSample]), cancelled, documentFailure }

    /// each sample is a 1x1 BAKE band job down the bake path (raster sample
    /// at or under baseMaxZoom, vector above, which feeds the EWMA), then the
    /// bake encoder. jobMs is the E1 timing (page draw through the warp), the
    /// base raster is fetched before any sample so its never in there (R3)
    private func measure(picks: [TileIndex], ctx: PDFRenderContext, options: [PDFBakeOption],
                         defaultZoom: Int, levels: [[TileIndex]], name: String, key: String, gen: Int) {
        guard let svc = service else { return }
        let worker = PDFBakeWorker(ctx: ctx, service: svc, maxZoom: defaultZoom)
        estimateWorker = worker
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let end = Self.runSamples(picks: picks, ctx: ctx, defaultZoom: defaultZoom, worker: worker)
            DispatchQueue.main.async {
                guard let self, self.estimateGeneration == gen, self.state == .estimating else { return }
                self.estimateWorker = nil
                let samples: [PDFBakeSample]
                switch end {
                // cancellation aborts the estimate, nothing to report
                case .cancelled: return self.dismiss()
                // R4: the page wont open / is blank, no point pricing anything
                case .documentFailure: return self.fail(.renderFailed)
                case .done(let s): samples = s
                }
                let est = PDFBakeEstimator.estimate(options: options, defaultMaxZoom: defaultZoom,
                                                    baseMaxZoom: ctx.baseMaxZoom,
                                                    levelTiles: { $0 < levels.count ? levels[$0] : [] },
                                                    samples: samples, ewmaMs: svc.scheduler.ewmaMs,
                                                    freeBytes: self.freeSpace())
                self.endEstimateGuard()
                self.selectedMaxZoom = est.initialSelection
                self.state = .confirming(PDFBakeProposal(pdfName: name, contentKey: key, estimate: est,
                                                         defaultMaxZoom: defaultZoom))
            }
        }
    }

    /// blocking, bake thread
    static func runSamples(picks: [TileIndex], ctx: PDFRenderContext, defaultZoom: Int,
                                   worker: PDFBakeWorker) -> MeasureEnd {
        var samples: [PDFBakeSample] = []
        var raster: PDFPageRaster?
        let rasterLevel = ctx.baseMaxZoom >= 0 && defaultZoom <= ctx.baseMaxZoom
        if rasterLevel {
            do {
                raster = try worker.fetchBaseRaster()
            } catch is CancellationError {
                return .cancelled
            } catch {
                if isDocumentFailure(error) { return .documentFailure }
            }
            if raster?.blank == true { return .documentFailure }
        }
        for t in picks {
            if worker.isCancelled { return .cancelled }
            if rasterLevel, raster == nil {
                samples.append(PDFBakeSample(result: .failed, jobMs: 0, bytes: nil, encodeMs: nil))
                continue
            }
            let out: (tiles: [TileIndex: TileCacheEntry], ms: Double)
            do {
                out = try worker.renderBlock(TileJob(z: t.z, x0: t.x, y0: t.y, cols: 1, rows: 1), raster: raster)
            } catch is CancellationError {
                return .cancelled
            } catch {
                if isDocumentFailure(error) { return .documentFailure }
                samples.append(PDFBakeSample(result: .failed, jobMs: 0, bytes: nil, encodeMs: nil))
                continue
            }
            guard case .image(let img)? = out.tiles[t] else {
                samples.append(PDFBakeSample(result: .empty, jobMs: out.ms, bytes: nil, encodeMs: nil))
                continue
            }
            let t1 = CACurrentMediaTime()
            guard let data = PDFBakeWorker.encode(img) else {
                samples.append(PDFBakeSample(result: .failed, jobMs: out.ms, bytes: nil, encodeMs: nil))
                continue
            }
            samples.append(PDFBakeSample(result: .image, jobMs: out.ms, bytes: data.count,
                                         encodeMs: (CACurrentMediaTime() - t1) * 1000))
        }
        return worker.isCancelled ? .cancelled : .done(samples)
    }

    static func isDocumentFailure(_ e: Error) -> Bool {
        guard let f = PDFJobError.unwrap(e) as? PDFRenderFailure else { return false }
        return documentFailures.contains(f)
    }

    /// tap on a radio row. rows without the space are disabled (J1)
    func select(maxZoom: Int) {
        guard case .confirming(let p) = state, p.option(maxZoom)?.enoughSpace == true else { return }
        selectedMaxZoom = maxZoom
    }

    /// Generate is on only when the selected option fits
    var canGenerate: Bool {
        guard case .confirming(let p) = state, let z = selectedMaxZoom else { return false }
        return p.option(z)?.enoughSpace == true
    }

    func generate() {
        guard canGenerate, let z = selectedMaxZoom else { return }
        start(maxZoom: z)
    }

    /// Cancel on the confirm step, or dismiss an error
    func dismiss() {
        guard !isRunning else { return }
        estimateGeneration &+= 1
        state = .idle
        setSubject(nil)
        selectedMaxZoom = nil
        releaseResources()
    }

    // MARK: run

    func start(maxZoom: Int) {
        guard case .confirming(let proposal) = state, let pdf, let svc = service, let ctx,
              let option = proposal.option(maxZoom), option.enoughSpace else { return }
        state = .running(done: 0, total: option.tiles)
        selectedMaxZoom = nil
        guardToken = pdf.renderGuardToken
        // a kill mid bake comes back as "Offline tiles not finished" next launch
        guardStore.arm(kind: .bake, token: pdf.renderGuardToken)
        beginBackgroundWork()
        let worker = PDFBakeWorker(ctx: ctx, service: svc, maxZoom: maxZoom, noSpaceNeededBytes: option.neededBytes)
        configureWorker?(worker)
        self.worker = worker
        let partial = PDFBakeWorker.workDirectory.appendingPathComponent("\(UUID().uuidString).mbtiles.partial")
        let hook = afterRunHook
        DispatchQueue.global(qos: .utility).async { [weak self] in
            let result: Result<(url: URL, bytes: Int64), Error> = Result {
                try worker.run(partial: partial) { p in
                    DispatchQueue.main.async {
                        guard let self, self.worker === worker, self.isRunning else { return }
                        self.state = .running(done: p.done, total: p.total)
                    }
                }
            }
            hook?(worker)
            DispatchQueue.main.async {
                guard let self, self.worker === worker else {
                    try? FileManager.default.removeItem(at: partial)
                    return
                }
                self.finishRun(result, worker: worker, partial: partial, pdf: pdf, ctx: ctx, maxZoom: maxZoom,
                               neededBytes: option.neededBytes)
            }
        }
    }

    private func finishRun(_ result: Result<(url: URL, bytes: Int64), Error>, worker: PDFBakeWorker, partial: URL,
                           pdf: PDFMapSource, ctx: PDFRenderContext, maxZoom: Int, neededBytes: Int64) {
        self.worker = nil
        defer {
            // parked keeps its guard slot, a kill before the unlock is an unfinished bake
            if parked == nil { endGuard() }
            endBackgroundWork()
            releaseResources()
        }
        let fm = FileManager.default
        switch result {
        case .failure(let e):
            try? fm.removeItem(at: partial)
            if e is CancellationError {
                state = .idle
                setSubject(nil)
            } else {
                // render + encode map to renderFailed in the worker, anything
                // nobody classified is a write problem
                state = .failed((e as? PDFBakeError) ?? .writeFailed)
            }
        case .success(let out):
            // F1: Cancel landed after the last tile. its still a cancel, nothing published
            if worker.isCancelled {
                try? fm.removeItem(at: out.url)
                try? fm.removeItem(at: partial)
                state = .idle
                setSubject(nil)
                return
            }
            // cheap early out, the stored session check below is the real one
            guard PDFRenderContext.bakeKey(georef: pdf.georef, tilePx: ctx.tilePx) == ctx.bakeKey,
                  pdf.contentKey == ctx.identity.contentKey else {
                try? fm.removeItem(at: partial)
                state = .failed(.sourceChanged)
                return
            }
            // relocked (3.0.2 K1): no record write behind it, wait for the user's unlock
            if keyRelocked() {
                parked = ParkedBake(out: out, pdf: pdf, ctx: ctx, maxZoom: maxZoom, neededBytes: neededBytes)
                return
            }
            publishFinished(out, pdf: pdf, ctx: ctx, maxZoom: maxZoom, neededBytes: neededBytes)
        }
    }

    private func publishFinished(_ out: (url: URL, bytes: Int64), pdf: PDFMapSource, ctx: PDFRenderContext,
                                 maxZoom: Int, neededBytes: Int64) {
        let name = "tacmap-bake-\(UUID().uuidString).mbtiles"
        let final = finalDirectory.appendingPathComponent(name)
        // the move and the record land together under the managed files lock,
        // a reconcile in between would reap a file nothing points at yet
        ManagedImportedMapFileLifecycle.withManagedFilesLock {
            publish(out, final: final, name: name, pdf: pdf, ctx: ctx, maxZoom: maxZoom, neededBytes: neededBytes)
        }
    }

    /// the unlock's restore has the library back: a bake parked behind the
    /// relock gets its record now, same publish as always (sourceChanged etc
    /// still apply). nothing parked or still relocked = no-op. main
    func publishParkedBake() {
        guard let p = parked, !keyRelocked() else { return }
        parked = nil
        // the scenePhase pass may have put the timer back on for us, give it back
        defer { endGuard(); endBackgroundWork() }
        publishFinished(p.out, pdf: p.pdf, ctx: p.ctx, maxZoom: p.maxZoom, neededBytes: p.neededBytes)
    }

    /// cancelled or its PDF deleted while parked: the finished file goes
    private func dropParked() {
        guard let p = parked else { return }
        parked = nil
        try? FileManager.default.removeItem(at: p.out.url)
        endGuard()
        endBackgroundWork()
    }

    private func endGuard() {
        if let t = guardToken { guardStore.complete(kind: .bake, token: t) }
        guardToken = nil
    }

    private func publish(_ out: (url: URL, bytes: Int64), final: URL, name: String, pdf: PDFMapSource,
                         ctx: PDFRenderContext, maxZoom: Int, neededBytes: Int64) {
        let fm = FileManager.default
        do {
            try PDFBakeWorker.prepareDirectory(finalDirectory)
            try fm.moveItem(at: out.url, to: final)
            try fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                                 ofItemAtPath: final.path)
            var u = final
            var v = URLResourceValues()
            v.isExcludedFromBackup = true
            try? u.setResourceValues(v)
            try Self.syncDirectory(finalDirectory)
        } catch {
            try? fm.removeItem(at: out.url)
            try? fm.removeItem(at: final)
            state = .failed(PDFBakeError.fromWrite(error, neededBytes: neededBytes))
            return
        }
        let record = PDFBakeRecord(fileName: name, bakeKey: ctx.bakeKey, minZoom: PDFTileConstants.bakeMinZoom,
                                   maxZoom: maxZoom, tilePx: ctx.tilePx, bytes: out.bytes)
        switch attachRecord(record, pdf, ctx) {
        case .attached:
            break
        case .sourceChanged:
            try? fm.removeItem(at: final)
            state = .failed(.sourceChanged)
            return
        case .writeFailed:
            try? fm.removeItem(at: final)
            state = .failed(.writeFailed)
            return
        }
        // the library entry has it now, bring the object in memory along.
        // the previous bake (if any) is unreferenced, reconcile reaps it
        pdf.bake = record
        recordRevision &+= 1
        runtime?.attachBake(record, for: pdf)
        onPublished?(pdf)
        state = .idle
        setSubject(nil)
        finishedMessage = true
    }

    /// rename durability: the directory entry has to hit the disk before the
    /// session points at it
    private static func syncDirectory(_ dir: URL) throws {
        let fd = open(dir.path, O_RDONLY)
        guard fd >= 0 else { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
        defer { close(fd) }
        if fsync(fd) != 0 { throw NSError(domain: NSPOSIXErrorDomain, code: Int(errno)) }
    }

    func cancel() {
        if let w = worker { return w.cancel() }
        if parked != nil {
            dropParked()
            state = .idle
            setSubject(nil)
            return
        }
        switch state {
        case .confirming:
            dismiss()
        case .estimating:
            estimateGeneration &+= 1
            state = .idle
            setSubject(nil)
            selectedMaxZoom = nil
            releaseResources()
        default:
            break
        }
    }

    /// F7: the PDF is going away (Delete Map): stop anything for it. matched
    /// on the guard token or the file, never on the optional contentKey
    func cancel(for pdf: PDFMapSource) {
        guard isFor(pdf) else { return }
        if let w = worker {
            w.cancel()
            worker = nil
            endGuard()
            endBackgroundWork()
        }
        dropParked()
        estimateGeneration &+= 1
        state = .idle
        setSubject(nil)
        selectedMaxZoom = nil
        releaseResources()
    }

    /// "Remove Offline Tiles": drop the record, then delete the file and its
    /// sidecars right here (R2-S2). Doesnt lean on the reconcile, that one
    /// refuses to run while the stored PDF is missing and the plaintext tiles
    /// would sit there orphaned. A failed delete still leaves no record, the
    /// bake only sweep right after (and the next launch's) gets it (R3-2)
    @discardableResult
    func removeBake(from pdf: PDFMapSource, runtime live: PDFMapRuntime? = nil) -> Bool {
        guard let old = pdf.bake else { return true }
        guard detachRecord(old, pdf) else { return false }
        pdf.bake = nil
        recordRevision &+= 1
        // the sheet hands in the live runtime, ours is only set by an estimate
        (live ?? runtime)?.detachBake()
        ManagedImportedMapFileLifecycle.removeGeneratedBake(old, in: finalDirectory)
        Self.sweepUnreferencedBakes(in: finalDirectory, storedBake: storedBake)
        onPublished?(pdf)
        return true
    }

    /// R3-2: delete tacmap-bake-* files in offline_tiles the library doesnt
    /// name. The names are read under the managed files lock, same as publish
    /// (move + record), so a bake landing right now is never seen half done.
    /// Locked / unreadable library = skip, never delete on a guess
    @discardableResult
    static func sweepUnreferencedBakes(in directory: URL = PDFBakeReader.directoryURL(),
                                       storedBake: () -> Set<String>?) -> Bool {
        ManagedImportedMapFileLifecycle.withManagedFilesLock {
            guard let keep = storedBake() else { return false }
            return ManagedImportedMapFileLifecycle.sweepUnreferencedBakes(in: directory, keepingNames: keep)
        }
    }

    // MARK: launch housekeeping

    static func cleanWorkDirectory() { PDFBakeWorker.cleanWorkDirectory() }

    static func freeSpaceBytes() -> Int64? {
        let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let v = try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return v?.volumeAvailableCapacityForImportantUsage
    }

    // MARK: background + screen

    /// keep going a bit after backgrounding; when iOS calls time we only end
    /// the assertion (the process may be suspended and resume, or get killed
    /// and the launch guard reports it). Screen stays on while baking in front.
    private func beginBackgroundWork() {
        if backgroundTask == .invalid {
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "tacmap.pdf-bake") { [weak self] in
                guard let self else { return }
                UIApplication.shared.endBackgroundTask(self.backgroundTask)
                self.backgroundTask = .invalid
            }
        }
        UIApplication.shared.isIdleTimerDisabled = true
    }

    private func endBackgroundWork() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
        UIApplication.shared.isIdleTimerDisabled = OpsecSettings.shared.keepScreenOn
    }
}
