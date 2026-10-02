import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import SQLite3
import UIKit

enum PDFBakeError: Error, Equatable {
    case notCalibrated
    case tooLarge
    case noSpace(neededBytes: Int64)
    case writeFailed
    case renderFailed
    case sourceChanged
    case interrupted
}

extension PDFBakeError {
    /// contract J2: ENOSPC / SQLITE_FULL anywhere on the write path is noSpace
    /// with what the chosen option needs, any other fs error is writeFailed
    static func fromWrite(_ e: Error, neededBytes: Int64) -> PDFBakeError {
        if let b = e as? PDFBakeError { return b }
        return isNoSpace(e) ? .noSpace(neededBytes: neededBytes) : .writeFailed
    }

    static func isNoSpace(_ e: Error) -> Bool {
        let ns = e as NSError
        if ns.domain == NSPOSIXErrorDomain, ns.code == Int(ENOSPC) { return true }
        if ns.domain == NSCocoaErrorDomain, ns.code == NSFileWriteOutOfSpaceError { return true }
        if let u = ns.userInfo[NSUnderlyingErrorKey] as? Error { return isNoSpace(u) }
        return false
    }
}

/// a cancel any thread can set and any thread can poll
final class PDFCancelFlag {
    private let lock = NSLock()
    private var cancelled = false

    var isCancelled: Bool {
        lock.lock(); defer { lock.unlock() }
        return cancelled
    }

    func cancel() {
        lock.lock(); cancelled = true; lock.unlock()
    }
}

/// first answer wins. the scheduler completion and the ticket's cancel handler
/// can both fire for one job (cancel landing right as it finishes), only one
/// of them gets to hand the result to the waiting bake thread (S7)
private final class OneShot<T> {
    private let lock = NSLock()
    private var value: T?
    private let sem = DispatchSemaphore(value: 0)

    func put(_ v: T) {
        lock.lock()
        guard value == nil else { lock.unlock(); return }
        value = v
        lock.unlock()
        sem.signal()
    }

    func wait() -> T {
        sem.wait()
        lock.lock(); defer { lock.unlock() }
        return value!
    }
}

/// OD-F2: encodes run on their own small concurrent queue so the next block
/// renders while the last one is still turning into JPEG 2000. Bounded both
/// ways: lanes encodes at once, backlog tiles held in memory waiting. The bake
/// thread is the only one that submits, takes, writes and shuts down.
///
/// R2-S1: every op that took a slot gives it back, always. Ops are never
/// cancelled off the queue (a queued op that never runs never signals and
/// freeing the semaphore below its start value traps). Shutdown flips stopped,
/// whatever hasnt started skips the encode, and we wait for all of them
private final class EncodePipeline {
    private let queue = OperationQueue()
    private let slots: DispatchSemaphore
    private let lock = NSLock()
    private let doneSignal = DispatchSemaphore(value: 0)
    private let encode: (CGImage) -> Data?
    private let cancelled: () -> Bool
    private var finished: [(TileIndex, Data?)] = []
    private var outstanding = 0
    private var stopped = false
    private var skipped = 0

    init(lanes: Int, backlog: Int, encode: @escaping (CGImage) -> Data?, cancelled: @escaping () -> Bool) {
        queue.name = "tacmap.pdf-bake-encode"
        queue.maxConcurrentOperationCount = max(1, lanes)
        queue.qualityOfService = .utility
        slots = DispatchSemaphore(value: max(1, backlog))
        self.encode = encode
        self.cancelled = cancelled
    }

    /// test peeks: submitted not finished, shut down yet, ops that skipped their encode
    var inFlight: Int { lock.lock(); defer { lock.unlock() }; return outstanding }
    var isStopped: Bool { lock.lock(); defer { lock.unlock() }; return stopped }
    var skippedCount: Int { lock.lock(); defer { lock.unlock() }; return skipped }

    /// waits for a free backlog slot first. stop() lets a cancel get out of that wait
    func submit(_ t: TileIndex, _ img: CGImage, stop: () -> Bool) {
        while slots.wait(timeout: .now() + 0.25) == .timedOut {
            if stop() { return }
        }
        lock.lock(); outstanding += 1; lock.unlock()
        // strong self on purpose, the op has to live long enough to hand its slot back
        queue.addOperation {
            // a cancelled bake throws the tile away anyway, dont burn the cpu on it
            let cancelled = self.cancelled()
            self.lock.lock()
            let skip = self.stopped || cancelled
            if skip { self.skipped += 1 }
            self.lock.unlock()
            let data = skip ? nil : autoreleasepool { self.encode(img) }
            self.lock.lock()
            // a skipped tile never shows up as done, a nil here would read as renderFailed
            if !skip, !self.stopped { self.finished.append((t, data)) }
            self.outstanding -= 1
            self.lock.unlock()
            self.slots.signal()
            self.doneSignal.signal()
        }
    }

    /// whats done so far, or (waitForAll) everything once the queue drains
    func take(waitForAll: Bool) -> [(TileIndex, Data?)] {
        if waitForAll {
            while true {
                lock.lock()
                let left = outstanding
                lock.unlock()
                if left == 0 { break }
                doneSignal.wait()
            }
        }
        lock.lock(); defer { lock.unlock() }
        let out = finished
        finished.removeAll()
        return out
    }

    /// no cancelAllOperations here (R2-S1), queued ops still run, just skip the work
    func shutdown() {
        lock.lock()
        stopped = true
        finished.removeAll()
        lock.unlock()
        queue.waitUntilAllOperationsAreFinished()
    }
}

/// Bakes one PDF georef into an MBTiles pyramid with the exact same render
/// function the live view uses (replaces PDFTiler). Runs on its own thread,
/// vector jobs go through the shared scheduler at the BAKE band so the
/// live map always wins. Writes to pdf_bake_work/<uuid>.mbtiles.partial,
/// outside the reconcile roots, so switching maps mid-bake cant delete it.
final class PDFBakeWorker {
    struct Progress: Equatable { let done: Int; let total: Int }

    let ctx: PDFRenderContext
    let service: PDFRenderService
    let maxZoom: Int
    /// what noSpace reports: neededBytes of the option being baked
    let noSpaceNeededBytes: Int64
    private let cancelLock = NSLock()
    private var _cancelled = false
    /// main thread only
    private var inFlight: PDFRenderTicket?
    /// the current (or last) run's encoder, under cancelLock. kept after the
    /// run so tests can read what it did
    private var pipeline: EncodePipeline?

    /// test seams: what turns a tile into bytes, and a look at the writer
    /// right before each tile row goes in (done = tiles counted so far, empties too)
    var encodeTile: (CGImage) -> Data? = PDFBakeWorker.encode
    var beforePutHook: ((MBTilesWriter, Int) -> Void)?

    /// test hooks on the encode pipeline
    var encodesInFlight: Int { currentPipeline?.inFlight ?? 0 }
    var encodeStopped: Bool { currentPipeline?.isStopped ?? false }
    var encodesSkipped: Int { currentPipeline?.skippedCount ?? 0 }

    private var currentPipeline: EncodePipeline? {
        cancelLock.lock(); defer { cancelLock.unlock() }
        return pipeline
    }

    var isCancelled: Bool {
        cancelLock.lock(); defer { cancelLock.unlock() }
        return _cancelled
    }

    /// OD-F2: JPEG 2000 encode is most of a bake, so it runs beside the render
    /// on its own bounded queue. encodeLanes at once, encodeBacklog tiles
    /// rendered but not written yet (768 px tile ~2.4 MB, so ~19 MB tops)
    static let encodeLanes = 3
    static let encodeBacklog = 8

    init(ctx: PDFRenderContext, service: PDFRenderService, maxZoom: Int, noSpaceNeededBytes: Int64 = 0) {
        self.ctx = ctx
        self.service = service
        self.maxZoom = maxZoom
        self.noSpaceNeededBytes = noSpaceNeededBytes
    }

    func cancel() {
        cancelLock.lock()
        _cancelled = true
        cancelLock.unlock()
        DispatchQueue.main.async { [weak self] in self?.inFlight?.cancel() }
    }

    static var workDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("pdf_bake_work", isDirectory: true)
    }

    /// leftovers from a bake that never finished (killed, crashed)
    static func cleanWorkDirectory() {
        try? FileManager.default.removeItem(at: workDirectory)
    }

    static func prepareDirectory(_ dir: URL) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true,
                               attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        try? fm.setAttributes([.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication], ofItemAtPath: dir.path)
        var u = dir
        var v = URLResourceValues()
        v.isExcludedFromBackup = true
        try? u.setResourceValues(v)
    }

    // MARK: encode

    /// fully opaque -> JPEG 2000, anything with alpha (sheet edge) -> PNG.
    /// J3: plain JPEG tops out around 32 dB on USGS and 26 dB on dense red
    /// hairlines whatever the quality (ImageIO always subsamples chroma), so it
    /// cant make the 35 dB gate. ImageIO has no WebP encoder either.
    /// ImageIO's JP2 quality is really a byte budget (q0.9 is ~320 KB a 768 px
    /// tile whatever's in it), so linework sails past the gate but grainy stuff
    /// like the USGS orthoimage or a photo of a paper map lands at 27-33 dB. so
    /// every tile gets checked: q0.9, then q0.99, then lossless JP2, then PNG
    static func encode(_ img: CGImage) -> Data? {
        guard isOpaque(img) else { return write(img, type: UTType.png.identifier, quality: nil) }
        let jp2 = PDFTileConstants.bakeOpaqueTypeIdentifier
        for q in [PDFTileConstants.bakeJpeg2000Quality, PDFTileConstants.bakeJpeg2000RetryQuality, 1.0] {
            guard let data = write(img, type: jp2, quality: q) else { return nil }
            if clearsGate(data, live: img) { return data }
        }
        // lossless JP2 should never miss, but if the codec ever disagrees dont ship a soft tile
        return write(img, type: UTType.png.identifier, quality: nil)
    }

    private static func write(_ img: CGImage, type: String, quality: Double?) -> Data? {
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, type as CFString, 1, nil) else { return nil }
        let props: [CFString: Any] = quality.map { [kCGImageDestinationLossyCompressionQuality: $0] } ?? [:]
        CGImageDestinationAddImage(dest, img, props as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return data as Data
    }

    /// decode what we're about to store and hold it to the J3 gate against the live render
    static func clearsGate(_ data: Data, live: CGImage) -> Bool {
        let opts = [kCGImageSourceShouldCacheImmediately: true] as CFDictionary
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let back = CGImageSourceCreateImageAtIndex(src, 0, opts) else { return false }
        return psnr(back, live: live) >= PDFTileConstants.bakePsnrGateDb
    }

    /// J3 PSNR: RGB, 8 bit, peak 255, over the pixels that are opaque in live
    static func psnr(_ a: CGImage, live b: CGImage) -> Double {
        guard a.width == b.width, a.height == b.height,
              let pa = PDFTileRenderer.pixels(a), let pb = PDFTileRenderer.pixels(b) else { return 0 }
        var se = 0.0, n = 0.0
        for y in 0..<a.height {
            let ra = y * pa.bytesPerRow, rb = y * pb.bytesPerRow
            for x in 0..<a.width {
                let i = ra + x * 4, j = rb + x * 4
                guard pb.data[j + 3] == 255 else { continue }
                for c in 0..<3 {
                    let d = Double(pa.data[i + c]) - Double(pb.data[j + c])
                    se += d * d
                }
                n += 3
            }
        }
        guard n > 0 else { return 0 }
        let mse = se / n
        return mse == 0 ? 99 : 10 * log10(255 * 255 / mse)
    }

    /// our own tiles are BGRA premultiplied, read the alpha bytes straight off
    static func isOpaque(_ img: CGImage) -> Bool {
        if img.bitsPerPixel == 32, img.alphaInfo == .premultipliedFirst,
           img.bitmapInfo.contains(.byteOrder32Little), let cf = img.dataProvider?.data,
           let p = CFDataGetBytePtr(cf) {
            let bpr = img.bytesPerRow
            for y in 0..<img.height {
                let row = p + y * bpr
                for x in 0..<img.width where row[x * 4 + 3] != 255 { return false }
            }
            return true
        }
        return PDFTileRenderer.isFullyOpaque(img)
    }

    // MARK: render one block

    /// render a bake block the same way the live view would: base raster for
    /// z <= baseMaxZoom (on this thread), a BAKE band lane job above that.
    /// ms is the E1 timing, page draw through the warp (r1 R3): the lane's own
    /// number for vector, the sample itself for raster. queue wait, page open
    /// and the base fetch arent in it. Blocks the calling (bake) thread, never main
    func renderBlock(_ job: TileJob, raster: PDFPageRaster?) throws -> (tiles: [TileIndex: TileCacheEntry], ms: Double) {
        if job.z <= ctx.baseMaxZoom, let raster {
            return try autoreleasepool {
                let t0 = CACurrentMediaTime()
                let plan = PDFTileWarp.plan(job: job, tilePx: ctx.tilePx, footprint: ctx.footprint, georef: ctx.georef)
                // a bake cancel is the one thing allowed to stop a job mid run
                let out = try PDFTileRenderer.renderJob(job, tilePx: ctx.tilePx, plan: plan, footprint: ctx.footprint,
                                                        source: .raster(raster), isCancelled: { self.isCancelled })
                return (out, (CACurrentMediaTime() - t0) * 1000)
            }
        }
        let box = OneShot<Result<(tiles: [TileIndex: TileCacheEntry], ms: Double), Error>>()
        DispatchQueue.main.async {
            if self.isCancelled { return box.put(.failure(CancellationError())) }
            let ticket = self.service.scheduler.requestBake(job: job, ctx: self.ctx) { r in box.put(r) }
            self.inFlight = ticket
            ticket.setOnCancel { box.put(.failure(CancellationError())) }
        }
        return try box.wait().get()
    }

    /// base raster for the low zooms, rendered at BAKE band if nobody has one
    func fetchBaseRaster() throws -> PDFPageRaster? {
        guard ctx.baseMaxZoom >= 0 else { return nil }
        let box = OneShot<Result<PDFPageRaster, Error>>()
        DispatchQueue.main.async {
            if self.isCancelled { return box.put(.failure(CancellationError())) }
            let ticket = self.service.baseRaster(ctx: self.ctx, band: .bake) { r in box.put(r) }
            self.inFlight = ticket
            ticket.setOnCancel { box.put(.failure(CancellationError())) }
        }
        return try box.wait().get()
    }

    /// heavy as the scheduler sees it right now. scheduler is main only
    private func readHeavy() -> Bool {
        if Thread.isMainThread { return service.scheduler.isHeavy }
        return DispatchQueue.main.sync { service.scheduler.isHeavy }
    }

    // MARK: run

    /// the whole bake. returns the .partial on success, throws PDFBakeError
    func run(partial: URL, progress: @escaping (Progress) -> Void) throws -> (url: URL, bytes: Int64) {
        var levels: [[TileIndex]] = []
        for z in 0...maxZoom {
            guard let tiles = ctx.footprint.tiles(z: z, limit: PDFTileConstants.bakeMaxTiles) else {
                throw PDFBakeError.tooLarge
            }
            levels.append(tiles.map(\.tile))
        }
        let total = levels.reduce(0) { $0 + $1.count }
        guard total > 0, total <= PDFTileConstants.bakeMaxTiles else { throw PDFBakeError.tooLarge }

        do { try Self.prepareDirectory(partial.deletingLastPathComponent()) }
        catch { throw PDFBakeError.fromWrite(error, neededBytes: noSpaceNeededBytes) }
        guard let writer = MBTilesWriter(path: partial.path) else { throw PDFBakeError.writeFailed }
        defer { writer.close() }
        let w = ctx.footprint
        let west = w.worldMinX / 256 * 360 - 180, east = w.worldMaxX / 256 * 360 - 180
        let north = atan(sinh(Double.pi * (1 - 2 * w.worldMinY / 256))) * 180 / .pi
        let south = atan(sinh(Double.pi * (1 - 2 * w.worldMaxY / 256))) * 180 / .pi
        // F2: the plaintext file never says which sheet (AO) it came from
        writer.writeMetadata(name: PDFTileConstants.bakeMbtilesName, format: PDFTileConstants.bakeMbtilesFormat,
                             minZoom: PDFTileConstants.bakeMinZoom, maxZoom: maxZoom,
                             minLon: west, minLat: south, maxLon: east, maxLat: north,
                             extra: ["tacmap_bake_key": ctx.bakeKey, "tacmap_tile_px": String(ctx.tilePx),
                                     "tacmap_renderer": String(PDFTileRenderer.version)])
        if writer.hadError { throw writeError(writer) }

        let raster: PDFPageRaster?
        do { raster = try fetchBaseRaster() } catch is CancellationError { throw CancellationError() }
        catch { throw PDFBakeError.renderFailed }
        if raster?.blank == true { throw PDFBakeError.renderFailed }

        var done = 0
        var sinceCommit = 0
        let encoder = EncodePipeline(lanes: Self.encodeLanes, backlog: Self.encodeBacklog, encode: encodeTile,
                                     cancelled: { [weak self] in self?.isCancelled ?? true })
        cancelLock.lock(); pipeline = encoder; cancelLock.unlock()
        // nothing an encode op holds outlives the run, whichever way it ends.
        // waits for every op, queued ones included, so all slots come back
        defer { encoder.shutdown() }
        /// write whatever finished encoding. a tile only counts as done once its row is in
        func flush(waitForAll: Bool) throws {
            for (t, data) in encoder.take(waitForAll: waitForAll) {
                guard let data else { throw PDFBakeError.renderFailed }
                beforePutHook?(writer, done)
                writer.putTile(z: t.z, x: t.x, y: t.y, data: data)
                if writer.hadError { throw writeError(writer) }
                done += 1
                sinceCommit += 1
                if sinceCommit >= PDFTileConstants.bakeCommitEvery {
                    writer.commit()
                    writer.begin()
                    sinceCommit = 0
                    if writer.hadError { throw writeError(writer) }
                }
            }
        }
        writer.begin()
        if writer.hadError { throw writeError(writer) }
        for (z, tiles) in levels.enumerated() where !tiles.isEmpty {
            let wanted = Set(tiles)
            // E2: raster levels 1x1, vector levels read heavy once here and keep it
            let level = PDFJobFormation.bakeLevel(z: z, tiles: tiles, baseMaxZoom: ctx.baseMaxZoom, heavy: readHeavy)
            for (block, _) in level.jobs {
                if isCancelled { throw CancellationError() }
                waitWhileTooHot()
                let out: [TileIndex: TileCacheEntry]
                do { out = try renderBlock(block, raster: raster).tiles }
                catch is CancellationError { throw CancellationError() }
                catch { throw PDFBakeError.renderFailed }
                // only tiles that touch the sheet get a row
                for t in block.tiles where wanted.contains(t) {
                    guard case .image(let img)? = out[t] else { done += 1; continue }
                    // blocks here when the backlog is full, thats the memory cap
                    encoder.submit(t, img) { self.isCancelled }
                    if isCancelled { throw CancellationError() }
                }
                try flush(waitForAll: false)
                progress(Progress(done: done, total: total))
            }
        }
        try flush(waitForAll: true)
        progress(Progress(done: done, total: total))
        // F1: a cancel that lands after the last tile still means nothing gets published
        if isCancelled { throw CancellationError() }
        writer.commit()
        if writer.hadError { throw writeError(writer) }
        writer.close()
        // flush before the rename publishes it. a failed fsync is a failed write
        do {
            let h = try FileHandle(forUpdating: partial)
            defer { try? h.close() }
            try h.synchronize()
        } catch {
            throw PDFBakeError.fromWrite(error, neededBytes: noSpaceNeededBytes)
        }
        let bytes = (try? FileManager.default.attributesOfItem(atPath: partial.path)[.size] as? NSNumber)?.int64Value ?? 0
        return (partial, bytes)
    }

    /// J2: a full disk reports what the chosen option needs, not the free bytes
    private func writeError(_ w: MBTilesWriter) -> PDFBakeError {
        Self.errorFor(w, neededBytes: noSpaceNeededBytes)
    }

    /// the writer keeps its first error, so a full disk stays noSpace (R6)
    static func errorFor(_ w: MBTilesWriter, neededBytes: Int64) -> PDFBakeError {
        w.lastErrorCode == SQLITE_FULL ? .noSpace(neededBytes: neededBytes) : .writeFailed
    }

    /// contract: the bake pauses at critical thermal state. lane jobs are held
    /// by the scheduler, this covers the raster sampled low zooms
    private func waitWhileTooHot() {
        while ProcessInfo.processInfo.thermalState == .critical, !isCancelled {
            Thread.sleep(forTimeInterval: 0.5)
        }
    }
}
