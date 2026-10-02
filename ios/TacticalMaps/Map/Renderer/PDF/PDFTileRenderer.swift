import Foundation
import CoreGraphics
import UIKit

/// Whole page (clip bbox) rasterised once, plus a mip chain. Low zooms are
/// sampled from this instead of touching the PDF again. Page space region
/// maps onto [0, W] x [0, H] exactly, row 0 = top of the region.
final class PDFPageRaster {
    /// finest first
    let levels: [CGImage]
    let region: CGRect
    /// min(W/w, H/h) of the full res level
    let densityPxPerPt: Double
    let blank: Bool
    let planKey: String

    init(levels: [CGImage], region: CGRect, densityPxPerPt: Double, blank: Bool, planKey: String) {
        self.levels = levels
        self.region = region
        self.densityPxPerPt = densityPxPerPt
        self.blank = blank
        self.planKey = planKey
    }

    var byteCount: Int { levels.reduce(0) { $0 + $1.bytesPerRow * $1.height } }

    /// smallest level that still has at least required px per pt
    func level(forRequiredPxPerPt required: Double) -> CGImage {
        var best = levels[0]
        for img in levels {
            let d = min(Double(img.width) / Double(region.width), Double(img.height) / Double(region.height))
            if d >= required { best = img } else { break }
        }
        return best
    }
}

/// immutable after init, safe to hand between the import worker and main
extension PDFPageRaster: @unchecked Sendable {}

enum PDFRenderSourceKind {
    /// Small plans: draw the page straight through each cell affine.
    case vector(CGPDFPage)
    /// Larger plans: draw the page once into a page aligned bitmap, warp that.
    case staged(CGPDFPage)
    /// z <= baseMaxZoom: sample the base raster
    case raster(PDFPageRaster)
}

/// Contract C step list for the staged bitmap (G3 + r1 R5), pinned by fixture
/// stagedRegion. Plain doubles, evaluated exactly in the written order so both
/// apps land on the same W x H. nil = renderError
struct PDFStagedRegion: Equatable {
    enum DFrom: String { case oversample, cap }
    let pad: Double
    /// x0, y0, x1, y1
    let region: [Double]
    let rw: Double
    let rh: Double
    let cap: Double
    let dFrom: DFrom
    let d0: Double
    let W0: Int
    let H0: Int
    let maxPx: Int
    let shrinkSteps: Int
    let d: Double
    let W: Int
    let H: Int

    static func compute(cellBBox b: [Double]?, required: Double, clipBBox c: [Double], jobPixels: Int) -> PDFStagedRegion? {
        guard let b, b.count == 4, c.count == 4, required.isFinite, required > 0 else { return nil }
        let pad = PDFTileConstants.stagedPadPx / required
        let x0 = max(b[0] - pad, c[0]), y0 = max(b[1] - pad, c[1])
        let x1 = min(b[2] + pad, c[2]), y1 = min(b[3] + pad, c[3])
        let rw = x1 - x0, rh = y1 - y0
        guard rw.isFinite, rh.isFinite, rw > 0, rh > 0 else { return nil }
        let jp = Double(jobPixels)
        let cap = ((PDFTileConstants.stagedMaxPixelsFactor * jp) / (rw * rh)).squareRoot()
        let over = PDFTileConstants.stagedOversample * required
        var d = min(over, cap)
        let from: DFrom = over <= cap ? .oversample : .cap
        func size(_ d: Double) -> (Int, Int) {
            (max(1, Int((rw * d).rounded(.up))), max(1, Int((rh * d).rounded(.up))))
        }
        let (w0, h0) = size(d)
        var (w, h) = (w0, h0)
        let maxPx = Int((PDFTileConstants.stagedMaxPixelsFactor * jp).rounded(.down))
        var steps = 0
        while w * h > maxPx, d > 0 {
            d = d * PDFTileConstants.stagedShrinkFactor
            (w, h) = size(d)
            steps += 1
        }
        return PDFStagedRegion(pad: pad, region: [x0, y0, x1, y1], rw: rw, rh: rh, cap: cap, dFrom: from,
                               d0: min(over, cap), W0: w0, H0: h0, maxPx: maxPx, shrinkSteps: steps, d: d, W: w, H: h)
    }
}

/// CoreGraphics only, no PDFKit. Every entry point is meant for a lane or a
/// background queue, never main (D3-13).
enum PDFTileRenderer {
    static let version = PDFTileConstants.rendererVersion

    #if DEBUG
    /// unit tests render on the test thread, everyone else has to stay off main
    static var assertsOffMainThread = true
    #endif

    @inline(__always) static func checkOffMain() {
        #if DEBUG
        if assertsOffMainThread { dispatchPrecondition(condition: .notOnQueue(.main)) }
        #endif
    }

    static let colorSpace = CGColorSpace(name: CGColorSpace.sRGB)!
    static let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue

    static func makeContext(width: Int, height: Int) -> CGContext? {
        guard width > 0, height > 0, width <= 16384, height <= 16384 else { return nil }
        let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                            space: colorSpace, bitmapInfo: bitmapInfo)
        ctx?.setShouldSmoothFonts(false)
        ctx?.setAllowsFontSmoothing(false)
        ctx?.setShouldAntialias(true)
        ctx?.interpolationQuality = .high
        return ctx
    }

    /// open + unlock + page lookup with the shared failure reasons
    static func openPage(url: URL, pageIndex: Int) throws -> (CGPDFDocument, CGPDFPage) {
        guard let doc = CGPDFDocument(url as CFURL) else { throw PDFRenderFailure.cannotOpen }
        if doc.isEncrypted && !doc.isUnlocked {
            _ = doc.unlockWithPassword("")
            guard doc.isUnlocked else { throw PDFRenderFailure.passwordProtected }
        }
        guard pageIndex >= 0, pageIndex < doc.numberOfPages, let page = doc.page(at: pageIndex + 1) else {
            throw PDFRenderFailure.pageMissing
        }
        return (doc, page)
    }

    /// CropBox ∩ MediaBox, the page box the clip polygon is built against
    static func pageBox(_ page: CGPDFPage) -> CGRect {
        GeoPDFReader.pageGeometry(page).cropBox
    }

    // MARK: - page aligned rasters (base + staged)

    /// draw region of the page at the given size, clipped to the footprint,
    /// paper white under the content, transparent outside
    static func renderRegion(page: CGPDFPage, region: CGRect, width: Int, height: Int,
                             footprint: PDFFootprint) throws -> CGContext {
        guard let ctx = makeContext(width: width, height: height) else { throw PDFRenderFailure.outOfMemory }
        // page space is y up like the bitmap's user space, no flip
        ctx.scaleBy(x: CGFloat(width) / region.width, y: CGFloat(height) / region.height)
        ctx.translateBy(x: -region.minX, y: -region.minY)
        ctx.addPath(footprint.clipPath)
        ctx.clip()
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fill(footprint.clipBBox.intersection(region))
        ctx.drawPDFPage(page)
        return ctx
    }

    static func renderBaseRaster(page: CGPDFPage, plan: PDFBaseRasterPlan, footprint: PDFFootprint) throws -> PDFPageRaster {
        checkOffMain()
        let ctx = try renderRegion(page: page, region: plan.region, width: plan.width, height: plan.height,
                                   footprint: footprint)
        let blank = isBlank(ctx)
        guard let full = ctx.makeImage() else { throw PDFRenderFailure.outOfMemory }
        var levels = [full]
        for size in plan.mips.dropFirst() {
            guard let c = makeContext(width: size.w, height: size.h) else { throw PDFRenderFailure.outOfMemory }
            c.interpolationQuality = .high
            c.draw(levels[levels.count - 1], in: CGRect(x: 0, y: 0, width: size.w, height: size.h))
            guard let img = c.makeImage() else { throw PDFRenderFailure.outOfMemory }
            levels.append(img)
        }
        return PDFPageRaster(levels: levels, region: plan.region, densityPxPerPt: plan.densityPxPerPt,
                             blank: blank, planKey: plan.key)
    }

    /// every 4th pixel both ways: blank when nothing has alpha and a channel <= 250
    static func isBlank(_ ctx: CGContext) -> Bool {
        guard let data = ctx.data else { return true }
        let w = ctx.width, h = ctx.height, bpr = ctx.bytesPerRow
        let p = data.assumingMemoryBound(to: UInt8.self)
        let stride = PDFTileConstants.blankSampleStride
        let limit = PDFTileConstants.blankMinChannelMax
        var y = 0
        while y < h {
            let row = p + y * bpr
            var x = 0
            while x < w {
                let px = row + x * 4
                let a = Int(px[3])
                if a > 0 {
                    // BGRA premultiplied, undo it before comparing
                    let b = Int(px[0]) * 255 / a, g = Int(px[1]) * 255 / a, r = Int(px[2]) * 255 / a
                    if min(r, g, b) <= limit { return false }
                }
                x += stride
            }
            y += stride
        }
        return true
    }

    // MARK: - jobs

    /// Render one job (cols x rows tiles) through its warp plan. Tiles the
    /// footprint doesnt touch come back .empty and nothing gets drawn for them.
    static func renderJob(_ job: TileJob, tilePx: Int, plan: PDFWarpPlan, footprint: PDFFootprint,
                          source: PDFRenderSourceKind,
                          isCancelled: () -> Bool = { false }) throws -> [TileIndex: TileCacheEntry] {
        checkOffMain()
        var out: [TileIndex: TileCacheEntry] = [:]
        let tiles = job.tiles
        let coverage = Dictionary(uniqueKeysWithValues: tiles.map { ($0, footprint.classify($0)) })
        let cells = plan.cells
        if cells.isEmpty || coverage.values.allSatisfy({ $0 == .outside }) {
            for t in tiles { out[t] = .empty }
            return out
        }
        let W = job.cols * tilePx, H = job.rows * tilePx
        guard let ctx = makeContext(width: W, height: H) else { throw PDFRenderFailure.outOfMemory }
        // job px are y down
        ctx.translateBy(x: 0, y: CGFloat(H))
        ctx.scaleBy(x: 1, y: -1)

        switch source {
        case .vector(let page):
            for cell in cells {
                if isCancelled() { throw CancellationError() }
                drawCell(cell, ctx: ctx, footprint: footprint) { ctx.drawPDFPage(page) }
            }
        case .staged(let page):
            let staged = try renderStaged(page: page, plan: plan, footprint: footprint, jobPixels: W * H)
            if isCancelled() { throw CancellationError() }
            ctx.interpolationQuality = .high
            for cell in cells {
                drawCell(cell, ctx: ctx, footprint: footprint) { ctx.draw(staged.image, in: staged.region) }
            }
        case .raster(let raster):
            let img = raster.level(forRequiredPxPerPt: plan.requiredPxPerPt)
            ctx.interpolationQuality = .medium
            for cell in cells {
                if isCancelled() { throw CancellationError() }
                drawCell(cell, ctx: ctx, footprint: footprint) { ctx.draw(img, in: raster.region) }
            }
        }
        if isCancelled() { throw CancellationError() }
        guard let data = ctx.data else { throw PDFRenderFailure.outOfMemory }
        let bpr = ctx.bytesPerRow
        for t in tiles {
            if coverage[t] == .outside { out[t] = .empty; continue }
            let c = t.x - job.x0, r = t.y - job.y0
            guard let img = copyTile(data: data, bytesPerRow: bpr, col: c, row: r, tilePx: tilePx) else {
                throw PDFRenderFailure.outOfMemory
            }
            out[t] = .image(img)
        }
        return out
    }

    /// integer cell clip, antialiased clip polygon, paper white, then the content
    private static func drawCell(_ cell: PDFWarpCell, ctx: CGContext, footprint: PDFFootprint, draw: () -> Void) {
        ctx.saveGState()
        ctx.clip(to: cell.rect)
        ctx.concatenate(cell.transform)
        ctx.addPath(footprint.clipPath)
        ctx.clip()
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fill(footprint.clipBBox)
        draw()
        ctx.restoreGState()
    }

    /// page bbox [x0, y0, x1, y1] of every emitted cell's quad, nil = no cell
    static func cellBBox(_ plan: PDFWarpPlan) -> [Double]? {
        guard !plan.cells.isEmpty else { return nil }
        var minX = Double.infinity, minY = Double.infinity, maxX = -Double.infinity, maxY = -Double.infinity
        for c in plan.cells {
            for p in c.quad {
                minX = min(minX, p.x); maxX = max(maxX, p.x)
                minY = min(minY, p.y); maxY = max(maxY, p.y)
            }
        }
        return [minX, minY, maxX, maxY]
    }

    /// staged path: page bbox of the drawn cells (∩ clip bbox) at
    /// min(1.25 * required, cap), cap keeps it <= 2x the job's pixels
    static func stagedPlan(plan: PDFWarpPlan, footprint: PDFFootprint, jobPixels: Int) -> (region: CGRect, width: Int, height: Int)? {
        let c = footprint.clipBBox
        let r = PDFStagedRegion.compute(cellBBox: cellBBox(plan), required: plan.requiredPxPerPt,
                                        clipBBox: [Double(c.minX), Double(c.minY), Double(c.maxX), Double(c.maxY)],
                                        jobPixels: jobPixels)
        guard let r else { return nil }
        return (CGRect(x: r.region[0], y: r.region[1], width: r.rw, height: r.rh), r.W, r.H)
    }

    private static func renderStaged(page: CGPDFPage, plan: PDFWarpPlan, footprint: PDFFootprint,
                                     jobPixels: Int) throws -> (image: CGImage, region: CGRect) {
        // R5: nothing to stage, or a junk density, is a counted renderError.
        // never a paper white tile pretending it drew
        guard let sp = stagedPlan(plan: plan, footprint: footprint, jobPixels: jobPixels) else {
            throw PDFRenderFailure.renderError
        }
        let ctx = try renderRegion(page: page, region: sp.region, width: sp.width, height: sp.height, footprint: footprint)
        guard let img = ctx.makeImage() else { throw PDFRenderFailure.outOfMemory }
        return (img, sp.region)
    }

    /// own buffer per tile so no tile keeps the whole job bitmap alive
    private static func copyTile(data: UnsafeMutableRawPointer, bytesPerRow: Int, col: Int, row: Int,
                                 tilePx: Int) -> CGImage? {
        let tileBpr = tilePx * 4
        var buf = Data(count: tileBpr * tilePx)
        buf.withUnsafeMutableBytes { dst in
            guard let d = dst.baseAddress else { return }
            for y in 0..<tilePx {
                let src = data + (row * tilePx + y) * bytesPerRow + col * tileBpr
                memcpy(d + y * tileBpr, src, tileBpr)
            }
        }
        guard let provider = CGDataProvider(data: buf as CFData) else { return nil }
        return CGImage(width: tilePx, height: tilePx, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: tileBpr,
                       space: colorSpace, bitmapInfo: CGBitmapInfo(rawValue: bitmapInfo), provider: provider,
                       decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }

    // MARK: - helpers for tests + bake

    /// raw BGRA premultiplied bytes of an image we made, row major, top row first
    static func pixels(_ img: CGImage) -> (data: [UInt8], bytesPerRow: Int)? {
        guard let ctx = makeContext(width: img.width, height: img.height) else { return nil }
        ctx.setBlendMode(.copy)
        ctx.draw(img, in: CGRect(x: 0, y: 0, width: img.width, height: img.height))
        guard let d = ctx.data else { return nil }
        let n = ctx.bytesPerRow * img.height
        return (Array(UnsafeBufferPointer(start: d.assumingMemoryBound(to: UInt8.self), count: n)), ctx.bytesPerRow)
    }

    static func isFullyOpaque(_ img: CGImage) -> Bool {
        guard let px = pixels(img) else { return false }
        for y in 0..<img.height {
            let row = y * px.bytesPerRow
            var x = 0
            while x < img.width {
                if px.data[row + x * 4 + 3] != 255 { return false }
                x += 1
            }
        }
        return true
    }
}
