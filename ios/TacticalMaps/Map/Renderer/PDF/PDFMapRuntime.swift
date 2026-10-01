import Foundation
import UIKit
import Combine

/// Owns the live PDF tile source for the active map (MapViewModel holds one).
/// Caches it by (bytes, page, georef, tilePx) so SwiftUI re-renders dont
/// throw tiles away, publishes render status for the header, and builds the
/// render context off main. Main thread only.
final class PDFMapRuntime: ObservableObject {
    @Published private(set) var status: PDFRenderStatus = .none
    /// true once we've been preparing for 300 ms, so a quick open never flashes amber
    @Published private(set) var showPreparingLabel = false

    private(set) var source: PDFTileSource?
    private var sourceKey: String?
    /// bumped by Try Again. the failed source stays on the view (G1), so the
    /// container needs something in its key that says swap to the new one
    private(set) var retryGeneration = 0
    private var service: PDFRenderService?
    private var preparingWork: DispatchWorkItem?
    private var pendingBake: PDFBakeRecord?
    private weak var currentPDF: PDFMapSource?

    /// the container points this at the tile view
    var onNeedsLayout: (() -> Void)?

    /// device ram knobs, swappable for tests
    var physicalMemory: UInt64 = ProcessInfo.processInfo.physicalMemory
    /// the OD-F4 byte check, runs off main. tests swap it to order overlapping checks
    var fileCheck: (PDFMapSource) -> Bool = { ImportedMapLibrary.storedFileMatches($0) }
    /// OD-F4 verdict of the install on screen: true = the exact bytes are there
    /// (again), false = missing or changed. The library row follows it
    var onStoredFileVerdict: ((PDFMapSource, Bool) -> Void)?

    var currentContext: PDFRenderContext? { source?.context }
    /// key of the source the published status belongs to
    var activeSourceKey: String? { sourceKey }
    var renderService: PDFRenderService? { service }

    /// The source for this PDF at this screen scale, or nil when theres no
    /// georef at all. A georef change gets a fresh source but keeps the
    /// service (and its page space base raster).
    func tileSource(for pdf: PDFMapSource, screenScale: CGFloat) -> PDFTileSource? {
        let tilePx = PDFTileMath.tilePx(density: Double(screenScale))
        let key = Self.sourceKey(pdf: pdf, tilePx: tilePx)
        if key == sourceKey, let s = source { return s }
        install(pdf: pdf, tilePx: tilePx, key: key)
        return source
    }

    /// the file name is in here too: reimporting the same bytes gives a new
    /// copy (name-1.pdf) and the reconcile deletes the old one, a source still
    /// pointing at the old path would only ever fail with cannotOpen.
    /// OD2-R2-1: and the crash guard token. every import mints a new one, so a
    /// re-import of byte identical bytes under the same file name (old copy
    /// missing) still gets a fresh source instead of the sticky failed one.
    /// restore and recalibration keep the token, so they dont rebuild for nothing
    static func sourceKey(pdf: PDFMapSource, tilePx: Int) -> String {
        "pdf:\(pdf.contentKey ?? pdf.url.path):\(pdf.url.lastPathComponent):\(pdf.renderGuardToken):\(pdf.georef.page):\(PDFRenderContext.bakeKey(georef: pdf.georef, tilePx: tilePx))"
    }

    func sourceKey(for pdf: PDFMapSource, screenScale: CGFloat) -> String {
        Self.sourceKey(pdf: pdf, tilePx: PDFTileMath.tilePx(density: Double(screenScale)))
    }

    private func install(pdf: PDFMapSource, tilePx: Int, key: String, isRetry: Bool = false) {
        let identity = PDFDocumentIdentity(contentKey: pdf.contentKey ?? pdf.url.path, pageIndex: pdf.georef.page)
        let svc: PDFRenderService
        if let s = service, s.identity == identity, s.url == pdf.url {
            svc = s
        } else {
            service?.release()
            svc = PDFRenderService.acquire(identity, url: pdf.url)
            service = svc
        }
        let src = PDFTileSource(service: svc)
        src.onStatus = { [weak self, weak src] st in
            guard let self, let src, src === self.source else { return }
            self.setStatus(st)
        }
        src.onNeedsLayout = { [weak self, weak src] in
            guard let self, let src, src === self.source else { return }
            self.onNeedsLayout?()
        }
        source = src
        sourceKey = key
        currentPDF = pdf
        pendingBake = pdf.bake
        setStatus(.preparing)

        let url = pdf.url, georef = pdf.georef, token = pdf.renderGuardToken
        let budget = PDFMemoryTier.baseBudgetPx(physicalMemory: physicalMemory)
        let bake = pdf.bake
        // R3-3: Try Again always re-checks the bytes, not only on a source that
        // was already flagged. the hash is memoised on the file stamp so an
        // untouched file costs a stat. no content key = nothing to check against
        let needsFileCheck = pdf.storedFileUnavailable || (isRetry && pdf.contentKey != nil)
        let fileCheck = self.fileCheck
        DispatchQueue.global(qos: .userInitiated).async { [weak self, weak src, weak pdf] in
            let built: Result<PDFRenderContext, PDFRenderFailure>
            var reader: PDFBakeReader?
            var fileCameBack = false
            var fileGone = false
            do {
                // OD-F4: restored with its file missing or changed. only the exact
                // stored bytes ever get drawn, anything else is cannotOpen (G1)
                if needsFileCheck {
                    guard let pdf, fileCheck(pdf) else {
                        fileGone = true
                        throw PDFRenderFailure.cannotOpen
                    }
                    fileCameBack = true
                }
                let (_, page) = try PDFTileRenderer.openPage(url: url, pageIndex: georef.page)
                let ctx = try PDFRenderContext(url: url, identity: identity, georef: georef,
                                               pageBox: PDFTileRenderer.pageBox(page), tilePx: tilePx,
                                               guardToken: token, baseBudgetPx: budget)
                built = .success(ctx)
                if let bake, bake.bakeKey == ctx.bakeKey, bake.tilePx == ctx.tilePx {
                    reader = PDFBakeReader(url: PDFBakeReader.directoryURL().appendingPathComponent(bake.fileName),
                                           expectedKey: ctx.bakeKey, tilePx: ctx.tilePx)
                }
            } catch {
                built = .failure((error as? PDFRenderFailure) ?? .renderError)
            }
            DispatchQueue.main.async {
                // F5: only the install still on screen gets to say what the file
                // looks like. an older Try Again landing late would undo a newer one
                guard let self, let src, src === self.source else { reader?.close(); return }
                if fileCameBack { pdf?.storedFileUnavailable = false }
                // gone or swapped under a live map: flag it like a restore would,
                // so the bake gate and the next install check too
                if fileGone { pdf?.storedFileUnavailable = true }
                if let pdf, fileCameBack || fileGone { self.onStoredFileVerdict?(pdf, fileCameBack) }
                switch built {
                case .success(let ctx):
                    if let reader { src.attachBake(reader) }
                    src.install(ctx)
                case .failure(let f):
                    src.fail(f)
                }
            }
        }
    }

    /// Try Again: forget the failure and build everything again
    func retry() {
        guard let pdf = currentPDF else { return }
        let scale = source?.context.map { CGFloat($0.tilePx) / 256 } ?? UIScreen.main.scale
        sourceKey = nil
        source = nil
        retryGeneration += 1
        let tilePx = PDFTileMath.tilePx(density: Double(scale))
        install(pdf: pdf, tilePx: tilePx, key: Self.sourceKey(pdf: pdf, tilePx: tilePx), isRetry: true)
        onNeedsLayout?()
    }

    /// the background hash check (WP4 restore step 4) found other bytes under
    /// the map on screen: flag it and rebuild, the install's own check then
    /// fails it as cannotOpen (G1, sticky, Try Again)
    func storedFileChanged(for pdf: PDFMapSource) {
        // flagged either way, so the next install (and the bake gate) check too
        pdf.storedFileUnavailable = true
        guard currentPDF === pdf else { return }
        retry()
    }

    /// no PDF on screen any more
    func reset() {
        source = nil
        sourceKey = nil
        currentPDF = nil
        service?.release()
        service = nil
        setStatus(.none)
    }

    /// a finished bake for the current map, swap it in without a re-render
    func attachBake(_ record: PDFBakeRecord, for pdf: PDFMapSource) {
        guard let src = source, let ctx = src.context, currentPDF === pdf,
              record.bakeKey == ctx.bakeKey, record.tilePx == ctx.tilePx else { return }
        let url = PDFBakeReader.directoryURL().appendingPathComponent(record.fileName)
        DispatchQueue.global(qos: .userInitiated).async { [weak src] in
            let reader = PDFBakeReader(url: url, expectedKey: ctx.bakeKey, tilePx: ctx.tilePx)
            DispatchQueue.main.async { src?.attachBake(reader) }
        }
    }

    func detachBake() {
        source?.attachBake(nil)
    }

    /// import probe already drew the base raster, keep it so the first paint is instant
    func adoptBaseRaster(_ raster: PDFPageRaster, identity: PDFDocumentIdentity, url: URL) {
        let svc = PDFRenderService.acquire(identity, url: url)
        svc.adoptBaseRaster(raster)
        // the registry lingers 30 s after this release, plenty for the import commit to pick it up
        svc.release()
    }

    func trimMemory() { service?.trimMemory() }

    private func setStatus(_ s: PDFRenderStatus) {
        guard s != status else { return }
        status = s
        preparingWork?.cancel()
        preparingWork = nil
        if s == .preparing {
            showPreparingLabel = false
            let w = DispatchWorkItem { [weak self] in
                guard let self, self.status == .preparing else { return }
                self.showPreparingLabel = true
            }
            preparingWork = w
            DispatchQueue.main.asyncAfter(deadline: .now() + PDFTileConstants.preparingLabelDelayMs / 1000, execute: w)
        } else {
            showPreparingLabel = false
        }
    }
}
