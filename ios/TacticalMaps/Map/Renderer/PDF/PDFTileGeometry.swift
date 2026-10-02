import Foundation
import CoreGraphics
import CoreLocation

// Pure geometry for the PDF tile source. Every number and rule in here is
// pinned by testdata/pdf_tile_render.json (plans/WP2-render-shared_contract.md)
// and Android does the exact same thing, so dont "improve" anything without
// changing the fixture + both apps together.

/// The shared WP2 numbers. Fixture section constants asserts all of them.
enum PDFTileConstants {
    static let tileLogicalSize = 256.0
    static let densityCap = 3.0
    static let tilePxQuantum = 16
    static let cameraZoomMin = 2.0
    static let cameraZoomMax = 22.0
    static let maxUnderzoomLevels = 2.0
    static let visibleTileInflate = 1.0
    static let maxAncestorLevels = 4

    static let earthRadius = 6_378_137.0
    /// merc metres per 256-unit at z0
    static let metresPerUnitZ0 = 2 * Double.pi * earthRadius / 256
    static let footprintSegmentsPerEdge = 32
    static let clipMinAreaPt2 = 1.0
    static let clipDedupeEpsPt = 1e-9
    static let jacobianStepPt = 1.0
    static let detailOversample = 4.0
    static let detailZoomHysteresis = 0.05
    static let maxZoomCap = 22

    static let baseBudgetPx = 6_000_000
    static let lowRamBaseBudgetPx = 3_000_000
    static let lowRamBelowBytes: UInt64 = 3_758_096_384      // 3.5 GiB
    static let baseMaxSide = 4096.0
    static let baseMaxScale = 4.0
    static let mipStopSide = 256
    static let upsampleTolerance = 1.0

    static let stagedOversample = 1.25
    static let stagedMaxPixelsFactor = 2.0
    static let stagedShrinkFactor = 0.99
    /// pad = padPx / required pt, a couple of px of slack round the cells
    static let stagedPadPx = 2.0
    /// Avoid an extra raster sampling phase for small amended warp plans.
    static let vectorMaxCells = 4

    static let warpMaxErrorPx = 0.0625
    static let warpBaseDepth = 4
    static let warpMinCellPx = 32
    static let warpRootPadPx = 2

    static let vectorSettleMs = 150.0
    static let ewmaAlpha = 0.3
    static let heavyThresholdMs = 60.0
    static let jobMaxTiles = 6
    static let jobMaxSide = 3
    static let bakeBlockCols = 3
    static let bakeBlockRows = 2
    static let maxBakeJobsInFlight = 1
    static let orphanCacheTiles = 8
    static let iosVectorLanesMinRamBytes: UInt64 = 3_758_096_384

    static let preparingLabelDelayMs = 300.0
    static let renderErrorConsecutiveFailures = 3
    static let blankSampleStride = 4
    static let blankMinChannelMax = 250

    static let bakeMaxTiles = 6000
    static let bakeFreeSpaceFactor = 2.0
    static let bakeBytesSafety = 1.2
    static let bakeSampleTiles = 3
    static let bakeCommitEvery = 64
    static let rendererVersion = 2
    static let bakeKeyPrefix = "tacmap-bake-v1|"
    /// J3: iOS bakes opaque tiles as JPEG 2000 (edge tiles PNG). device local
    /// files, so the MBTiles format value is ours to pick
    static let bakeJpeg2000Quality = 0.9
    static let bakeOpaqueTypeIdentifier = "public.jpeg-2000"
    static let bakeMbtilesFormat = "jp2"
    /// J3 gate, baked vs live, the PSNR test reads this not a literal
    static let bakePsnrGateDb = 35.0
    /// F2: neutral MBTiles metadata name, never the sheet's display name (AO leak)
    static let bakeMbtilesName = "TacMap offline tiles"
    static let bakeMinZoom = 0
    static let bakeCandidateOffsets = [-2, -1, 0]
    static let bakeDefaultOffset = -1
    /// J2 estimate fallbacks when no sample delivered
    static let bakeFallbackJobMs = 100.0
    static let bakeFallbackEncodeMs = 5.0
    /// raised by the J3 USGS measurement (iOS jp2 mean 248349), fixture is the authority
    static let bakeFallbackTileBytes = 250000

    static let guardMaxVerifiedTokens = 16
    static let guardFileVersion = 1

    static let emptyEntryCostBytes = 64
}

/// RAM tiers from contract B/C/K. physicalMemory in bytes.
enum PDFMemoryTier {
    static let lowCacheBytes = 64 << 20
    static let midRamBelowBytes: UInt64 = 6 << 30
    static let midCacheBytes = 128 << 20
    static let highCacheBytes = 192 << 20

    static func tileCacheBytes(physicalMemory: UInt64, lowRamDevice: Bool = false) -> Int {
        if lowRamDevice || physicalMemory < PDFTileConstants.lowRamBelowBytes { return lowCacheBytes }
        if physicalMemory < midRamBelowBytes { return midCacheBytes }
        return highCacheBytes
    }

    static func baseBudgetPx(physicalMemory: UInt64, lowRamDevice: Bool = false) -> Int {
        lowRamDevice || physicalMemory < PDFTileConstants.lowRamBelowBytes
            ? PDFTileConstants.lowRamBaseBudgetPx : PDFTileConstants.baseBudgetPx
    }

    /// 2 lanes only with enough ram. thermal / low power drop it to 1 at runtime
    static func iosVectorLanes(physicalMemory: UInt64) -> Int {
        physicalMemory >= PDFTileConstants.iosVectorLanesMinRamBytes ? 2 : 1
    }
}

/// Why a PDF couldnt be drawn. Raw values are the shared reason ids.
enum PDFRenderFailure: String, Error, Equatable, CaseIterable {
    case cannotOpen, passwordProtected, pageMissing, pageGeometry, blank, outOfMemory, renderError
}

enum TileCoverage: String, Equatable {
    case outside, edge, inside
}

// MARK: - tile px + zoom helpers

enum PDFTileMath {
    /// round(256 * min(density, 3) / 16) * 16, ties up
    static func tilePx(density: Double) -> Int {
        let d = min(max(density, 0), PDFTileConstants.densityCap)
        let q = Double(PDFTileConstants.tilePxQuantum)
        return Int((256 * d / q + 0.5).rounded(.down)) * PDFTileConstants.tilePxQuantum
    }

    /// pinch result, clamped to the global camera range
    static func pinchZoom(_ zoom: Double, scale: Double) -> Double {
        clampCameraZoom(zoom + log2(scale))
    }

    static func clampCameraZoom(_ z: Double) -> Double {
        guard z.isFinite else { return PDFTileConstants.cameraZoomMin }
        return min(max(z, PDFTileConstants.cameraZoomMin), PDFTileConstants.cameraZoomMax)
    }

    static func underzoomHidden(tileZoom: Int, cameraZoom: Double) -> Bool {
        Double(tileZoom) - cameraZoom > PDFTileConstants.maxUnderzoomLevels
    }

    /// spherical web mercator z0 world units (0..256, y down)
    static func world0(lat: Double, lon: Double) -> (x: Double, y: Double) {
        let s = log(tan(Double.pi / 4 + lat * .pi / 180 / 2))
        return ((lon + 180) / 360 * 256, (1 - s / .pi) / 2 * 256)
    }

    static func mercMetres(lat: Double, lon: Double) -> (x: Double, y: Double) {
        let r = PDFTileConstants.earthRadius
        return (r * lon * .pi / 180, r * log(tan(Double.pi / 4 + lat * .pi / 180 / 2)))
    }

    /// contract A: job pixel (u, v) of a job whose top-left tile is (x0, y0) at z
    static func jobPixelToLatLon(z: Int, x0: Int, y0: Int, tilePx: Int, u: Double, v: Double) -> (lat: Double, lon: Double) {
        let n = pow(2.0, Double(z))
        let wx = Double(x0) * 256 + u * 256 / Double(tilePx)
        let wy = Double(y0) * 256 + v * 256 / Double(tilePx)
        let lon = wx / (256 * n) * 360 - 180
        let lat = atan(sinh(Double.pi * (1 - 2 * wy / (256 * n)))) * 180 / .pi
        return (lat, lon)
    }
}

// MARK: - clip polygon + footprint

enum PDFClip {
    /// Sutherland-Hodgman of subject against box (x0,y0,x1,y1), edges in
    /// the order x >= x0, x <= x1, y >= y0, y <= y1. On the edge counts as
    /// inside, an intersection only when S and E are on opposite sides, then
    /// consecutive dupes within 1e-9 dropped (wrap too). Same as the fixture gen.
    static func clip(_ subject: [PdfPagePoint], to box: CGRect) -> [PdfPagePoint] {
        let x0 = Double(box.minX), y0 = Double(box.minY), x1 = Double(box.maxX), y1 = Double(box.maxY)
        typealias P = PdfPagePoint
        let edges: [((P) -> Bool, (P, P) -> P)] = [
            ({ $0.x >= x0 }, { s, e in P(x: x0, y: s.y + (e.y - s.y) * (x0 - s.x) / (e.x - s.x)) }),
            ({ $0.x <= x1 }, { s, e in P(x: x1, y: s.y + (e.y - s.y) * (x1 - s.x) / (e.x - s.x)) }),
            ({ $0.y >= y0 }, { s, e in P(x: s.x + (e.x - s.x) * (y0 - s.y) / (e.y - s.y), y: y0) }),
            ({ $0.y <= y1 }, { s, e in P(x: s.x + (e.x - s.x) * (y1 - s.y) / (e.y - s.y), y: y1) }),
        ]
        var out = subject
        for (inside, cut) in edges {
            if out.isEmpty { break }
            let src = out
            out = []
            var s = src[src.count - 1]
            for e in src {
                if inside(e) {
                    if !inside(s) { out.append(cut(s, e)) }
                    out.append(e)
                } else if inside(s) {
                    out.append(cut(s, e))
                }
                s = e
            }
        }
        let eps = PDFTileConstants.clipDedupeEpsPt
        var ded: [P] = []
        for p in out {
            if let last = ded.last, abs(p.x - last.x) <= eps, abs(p.y - last.y) <= eps { continue }
            ded.append(p)
        }
        while ded.count > 1, abs(ded[0].x - ded[ded.count - 1].x) <= eps, abs(ded[0].y - ded[ded.count - 1].y) <= eps {
            ded.removeLast()
        }
        return ded
    }

    static func signedArea(_ poly: [PdfPagePoint]) -> Double {
        guard poly.count >= 3 else { return 0 }
        var a = 0.0
        for i in 0..<poly.count {
            let p = poly[i], q = poly[(i + 1) % poly.count]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2
    }

    /// crossing number, same as the generator. points exactly on an edge are
    /// whatever falls out, the fixture keeps everything well clear
    static func contains(_ poly: [(x: Double, y: Double)], _ px: Double, _ py: Double) -> Bool {
        var inside = false
        let n = poly.count
        guard n >= 3 else { return false }
        var j = n - 1
        for i in 0..<n {
            let a = poly[j], b = poly[i]
            if (a.y > py) != (b.y > py) {
                let xi = a.x + (py - a.y) * (b.x - a.x) / (b.y - a.y)
                if px < xi { inside.toggle() }
            }
            j = i
        }
        return inside
    }

    /// closed segment vs closed axis aligned rect, liang-barsky
    static func segmentHitsRect(_ px: Double, _ py: Double, _ qx: Double, _ qy: Double,
                                _ rx0: Double, _ ry0: Double, _ rx1: Double, _ ry1: Double) -> Bool {
        if rx1 < rx0 || ry1 < ry0 { return false }
        let dx = qx - px, dy = qy - py
        var t0 = 0.0, t1 = 1.0
        let pairs: [(Double, Double)] = [(-dx, px - rx0), (dx, rx1 - px), (-dy, py - ry0), (dy, ry1 - py)]
        for (pp, qq) in pairs {
            if pp == 0 {
                if qq < 0 { return false }
            } else {
                let r = qq / pp
                if pp < 0 {
                    if r > t1 { return false }
                    t0 = max(t0, r)
                } else {
                    if r < t0 { return false }
                    t1 = min(t1, r)
                }
            }
        }
        return t0 <= t1
    }

    /// do closed segments ab and cd touch
    static func segmentsIntersect(_ a: PdfPagePoint, _ b: PdfPagePoint, _ c: PdfPagePoint, _ d: PdfPagePoint) -> Bool {
        func orient(_ p: PdfPagePoint, _ q: PdfPagePoint, _ r: PdfPagePoint) -> Double {
            (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        }
        func onSeg(_ p: PdfPagePoint, _ q: PdfPagePoint, _ r: PdfPagePoint) -> Bool {
            min(p.x, q.x) <= r.x && r.x <= max(p.x, q.x) && min(p.y, q.y) <= r.y && r.y <= max(p.y, q.y)
        }
        let d1 = orient(c, d, a), d2 = orient(c, d, b), d3 = orient(a, b, c), d4 = orient(a, b, d)
        if ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0)) { return true }
        if d1 == 0 && onSeg(c, d, a) { return true }
        if d2 == 0 && onSeg(c, d, b) { return true }
        if d3 == 0 && onSeg(a, b, c) { return true }
        if d4 == 0 && onSeg(a, b, d) { return true }
        return false
    }

    /// page quad (cell corners) vs the clip polygon. works for non convex
    /// polygons too, for convex ones its the same answer as the generator's
    /// margin/SAT test
    static func classify(quad: [PdfPagePoint], against poly: [PdfPagePoint]) -> TileCoverage {
        let n = poly.count
        for i in 0..<4 {
            let a = quad[i], b = quad[(i + 1) % 4]
            for j in 0..<n {
                if segmentsIntersect(a, b, poly[j], poly[(j + 1) % n]) { return .edge }
            }
        }
        let polyXY = poly.map { (x: $0.x, y: $0.y) }
        if contains(polyXY, quad[0].x, quad[0].y) { return .inside }
        let quadXY = quad.map { (x: $0.x, y: $0.y) }
        if let p = poly.first, contains(quadXY, p.x, p.y) { return .edge }
        return .outside
    }
}

/// The part of the page that gets drawn, in page space and as a web mercator
/// footprint. clip = crop ∩ CropBox ∩ MediaBox because CG doesnt clip to the
/// CropBox for us and the USGS crop pokes out past its page box.
struct PDFFootprint {
    let pageBox: CGRect
    let clipPolygon: [PdfPagePoint]
    let clipMean: PdfPagePoint
    let clipBBox: CGRect
    let clipPath: CGPath
    /// densified clip polygon in z0 world units
    let world: [(x: Double, y: Double)]
    let worldMinX: Double, worldMinY: Double, worldMaxX: Double, worldMaxY: Double

    init(georef: PdfGeoreference, pageBox: CGRect) throws {
        self.pageBox = pageBox.standardized
        let clip = PDFClip.clip(georef.crop, to: self.pageBox)
        let area = clip.count >= 3 ? abs(PDFClip.signedArea(clip)) : 0
        guard area >= PDFTileConstants.clipMinAreaPt2, area.isFinite,
              clip.allSatisfy(\.isFinite) else { throw PDFRenderFailure.pageGeometry }
        clipPolygon = clip
        let n = Double(clip.count)
        clipMean = PdfPagePoint(x: clip.reduce(0) { $0 + $1.x } / n, y: clip.reduce(0) { $0 + $1.y } / n)
        let xs = clip.map(\.x), ys = clip.map(\.y)
        clipBBox = CGRect(x: xs.min()!, y: ys.min()!, width: xs.max()! - xs.min()!, height: ys.max()! - ys.min()!)
        let path = CGMutablePath()
        path.addLines(between: clip.map(\.cgPoint))
        path.closeSubpath()
        clipPath = path

        let segs = PDFTileConstants.footprintSegmentsPerEdge
        var w: [(x: Double, y: Double)] = []
        w.reserveCapacity(clip.count * segs)
        for i in 0..<clip.count {
            let a = clip[i], b = clip[(i + 1) % clip.count]
            for k in 0..<segs {
                let t = Double(k) / Double(segs)
                guard let ll = georef.toWGS84(x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t) else {
                    throw PDFRenderFailure.pageGeometry
                }
                let p = PDFTileMath.world0(lat: ll.latitude, lon: ll.longitude)
                guard p.x.isFinite, p.y.isFinite else { throw PDFRenderFailure.pageGeometry }
                w.append(p)
            }
        }
        world = w
        worldMinX = w.map(\.x).min()!
        worldMaxX = w.map(\.x).max()!
        worldMinY = w.map(\.y).min()!
        worldMaxY = w.map(\.y).max()!
    }

    /// tile square (closed) vs footprint. EDGE when the boundary touches it,
    /// INSIDE when it doesnt and the centre is inside, else OUTSIDE.
    func classify(_ t: TileIndex) -> TileCoverage {
        guard t.z >= 0, t.z <= 30 else { return .outside }
        let ts = 256.0 / pow(2.0, Double(t.z))
        let rx0 = Double(t.x) * ts, ry0 = Double(t.y) * ts
        let rx1 = rx0 + ts, ry1 = ry0 + ts
        if rx1 < worldMinX || rx0 > worldMaxX || ry1 < worldMinY || ry0 > worldMaxY { return .outside }
        let n = world.count
        for i in 0..<n {
            let p = world[i], q = world[(i + 1) % n]
            if max(p.x, q.x) < rx0 || min(p.x, q.x) > rx1 || max(p.y, q.y) < ry0 || min(p.y, q.y) > ry1 { continue }
            if PDFClip.segmentHitsRect(p.x, p.y, q.x, q.y, rx0, ry0, rx1, ry1) { return .edge }
        }
        return PDFClip.contains(world, (rx0 + rx1) / 2, (ry0 + ry1) / 2) ? .inside : .outside
    }

    /// every intersecting tile at z, sorted by y then x. Scanline for the
    /// insides so a big level doesnt do a full point-in-polygon per tile.
    /// Stops early (returns nil) past limit tiles, or once isCancelled says so
    /// (the caller knows which one it was)
    func tiles(z: Int, limit: Int = .max,
               isCancelled: () -> Bool = { false }) -> [(tile: TileIndex, coverage: TileCoverage)]? {
        let nTiles = 1 << z
        let ts = 256.0 / Double(nTiles)
        let ix0 = max(0, Int((worldMinX / ts).rounded(.down)))
        let ix1 = min(nTiles - 1, Int((worldMaxX / ts).rounded(.down)))
        let iy0 = max(0, Int((worldMinY / ts).rounded(.down)))
        let iy1 = min(nTiles - 1, Int((worldMaxY / ts).rounded(.down)))
        guard ix0 <= ix1, iy0 <= iy1 else { return [] }
        var edge = Set<TileIndex>()
        let n = world.count
        var polled = 0
        for i in 0..<n {
            let p = world[i], q = world[(i + 1) % n]
            let minX = min(p.x, q.x), maxX = max(p.x, q.x)
            let ax0 = max(ix0, Int((minX / ts).rounded(.down)) - 1)
            let ax1 = min(ix1, Int((maxX / ts).rounded(.down)) + 1)
            let ay0 = max(iy0, Int((min(p.y, q.y) / ts).rounded(.down)) - 1)
            let ay1 = min(iy1, Int((max(p.y, q.y) / ts).rounded(.down)) + 1)
            guard ax0 <= ax1, ay0 <= ay1 else { continue }
            for ix in ax0...ax1 {
                // F4: only the rows the segment can reach in this column (+-1 for
                // rounding), same as Android. a long diagonal over a huge bbox used
                // to walk rows x cols. segmentHitsRect still decides so the set
                // doesnt change
                var jy0 = ay0, jy1 = ay1
                if q.x != p.x {
                    let sx0 = min(max(Double(ix) * ts, minX), maxX)
                    let sx1 = min(max(Double(ix + 1) * ts, minX), maxX)
                    let ya = p.y + (sx0 - p.x) * (q.y - p.y) / (q.x - p.x)
                    let yb = p.y + (sx1 - p.x) * (q.y - p.y) / (q.x - p.x)
                    if ya.isFinite, yb.isFinite {
                        jy0 = max(ay0, Int((min(ya, yb) / ts).rounded(.down)) - 1)
                        jy1 = min(ay1, Int((max(ya, yb) / ts).rounded(.down)) + 1)
                    }
                }
                guard jy0 <= jy1 else { continue }
                for iy in jy0...jy1 {
                    polled &+= 1
                    if polled & 0xFFF == 0, isCancelled() { return nil }
                    let t = TileIndex(z: z, x: ix, y: iy)
                    if edge.contains(t) { continue }
                    let rx0 = Double(ix) * ts, ry0 = Double(iy) * ts
                    if PDFClip.segmentHitsRect(p.x, p.y, q.x, q.y, rx0, ry0, rx0 + ts, ry0 + ts) {
                        edge.insert(t)
                        // every edge tile is a level tile, no point going on
                        if edge.count > limit { return nil }
                    }
                }
            }
        }
        var out: [(tile: TileIndex, coverage: TileCoverage)] = []
        for iy in iy0...iy1 {
            if isCancelled() { return nil }
            let cy = (Double(iy) + 0.5) * ts
            // crossings of the row centre line, same formula as the PIP test
            var xs: [Double] = []
            var j = n - 1
            for i in 0..<n {
                let a = world[j], b = world[i]
                if (a.y > cy) != (b.y > cy) { xs.append(a.x + (cy - a.y) * (b.x - a.x) / (b.y - a.y)) }
                j = i
            }
            xs.sort()
            for ix in ix0...ix1 {
                let t = TileIndex(z: z, x: ix, y: iy)
                if edge.contains(t) {
                    out.append((t, .edge))
                } else {
                    let cx = (Double(ix) + 0.5) * ts
                    // inside when an odd number of crossings sit to the right of cx
                    var right = 0
                    for x in xs.reversed() { if cx < x { right += 1 } else { break } }
                    if right % 2 == 1 { out.append((t, .inside)) }
                }
                if out.count > limit { return nil }
            }
        }
        return out
    }

    /// job pixel bbox of the footprint, contract D root (before the job rect clamp)
    func jobPixelExtent(z: Int, x0: Int, y0: Int, tilePx: Int) -> (minU: Double, minV: Double, maxU: Double, maxV: Double) {
        let n = pow(2.0, Double(z))
        let k = Double(tilePx) / 256
        let ox = Double(x0) * 256, oy = Double(y0) * 256
        return ((worldMinX * n - ox) * k, (worldMinY * n - oy) * k, (worldMaxX * n - ox) * k, (worldMaxY * n - oy) * k)
    }
}

// MARK: - jobs + warp planner

/// A block of tiles rendered in one go. Bake blocks and heavy live jobs are > 1x1.
struct TileJob: Hashable {
    let z: Int
    let x0: Int
    let y0: Int
    let cols: Int
    let rows: Int

    var tiles: [TileIndex] {
        var out: [TileIndex] = []
        for r in 0..<rows { for c in 0..<cols { out.append(TileIndex(z: z, x: x0 + c, y: y0 + r)) } }
        return out
    }

    func contains(_ t: TileIndex) -> Bool {
        t.z == z && t.x >= x0 && t.x < x0 + cols && t.y >= y0 && t.y < y0 + rows
    }
}

/// One warp cell: an integer rect in job pixels plus the page -> job px affine
/// that holds inside it to the shared warp bound.
struct PDFWarpCell: Equatable {
    /// [l, t, r, b), job px, y down
    let l: Int, t: Int, r: Int, b: Int
    /// u = a*x + b*y + c, v = d*x + e*y + f
    let pageToPx: [Double]
    let errorPx: Double
    let coverage: TileCoverage
    let depth: Int
    /// page points at TL, TR, BR, BL
    let quad: [PdfPagePoint]

    var rect: CGRect { CGRect(x: l, y: t, width: r - l, height: b - t) }
    var transform: CGAffineTransform {
        CGAffineTransform(a: pageToPx[0], b: pageToPx[3], c: pageToPx[1], d: pageToPx[4], tx: pageToPx[2], ty: pageToPx[5])
    }
}

struct PDFWarpPlan {
    let root: (l: Int, t: Int, r: Int, b: Int)?
    let maxDepth: Int
    /// every leaf incl. OUTSIDE ones, depth first TL, TR, BL, BR
    let leaves: [PDFWarpCell]
    let dropped: Int

    var cells: [PDFWarpCell] { leaves.filter { $0.coverage != .outside } }
    var maxErrorPx: Double { cells.map(\.errorPx).max() ?? 0 }
    /// false when a split limit left a drawn cell over the shared bound
    var errorBoundMet: Bool { cells.allSatisfy { $0.errorPx <= PDFTileConstants.warpMaxErrorPx } }

    /// max column norm of the linear part over the drawn cells, px per pt
    var requiredPxPerPt: Double {
        var best = 0.0
        for c in cells {
            let m = c.pageToPx
            best = max(best, hypot(m[0], m[3]), hypot(m[1], m[4]))
        }
        return best
    }
}

enum PDFTileWarp {
    static func maxDepth(cols: Int, rows: Int) -> Int {
        PDFTileConstants.warpBaseDepth + Int(log2(Double(max(cols, rows))).rounded(.up))
    }

    /// contract D, line for line with the generator
    static func plan(job: TileJob, tilePx: Int, footprint: PDFFootprint,
                     pageForPixel: (Double, Double) -> PdfPagePoint?) -> PDFWarpPlan {
        let W = job.cols * tilePx, H = job.rows * tilePx
        let ext = footprint.jobPixelExtent(z: job.z, x0: job.x0, y0: job.y0, tilePx: tilePx)
        let pad = PDFTileConstants.warpRootPadPx
        let l = max(0, Int(ext.minU.rounded(.down)) - pad)
        let r = min(W, Int(ext.maxU.rounded(.up)) + pad)
        let t = max(0, Int(ext.minV.rounded(.down)) - pad)
        let b = min(H, Int(ext.maxV.rounded(.up)) + pad)
        let depthLimit = maxDepth(cols: job.cols, rows: job.rows)
        guard r > l, b > t else { return PDFWarpPlan(root: nil, maxDepth: depthLimit, leaves: [], dropped: 0) }

        var cache: [Int64: PdfPagePoint?] = [:]
        // keys are half pixel exact (centres sit on .5), so key on 2x
        func page(_ u: Double, _ v: Double) -> PdfPagePoint? {
            let key = Int64(u * 2) << 32 | Int64(v * 2) & 0xFFFF_FFFF
            if let hit = cache[key] { return hit }
            let p = pageForPixel(u, v)
            cache[key] = p
            return p
        }

        var leaves: [(l: Int, t: Int, r: Int, b: Int, inv: [Double], err: Double, depth: Int, quad: [PdfPagePoint])] = []
        var dropped = 0
        let minCell = PDFTileConstants.warpMinCellPx
        let maxErr = PDFTileConstants.warpMaxErrorPx

        func rec(_ l: Int, _ t: Int, _ r: Int, _ b: Int, _ depth: Int) {
            let w = Double(r - l), h = Double(b - t)
            let fl = Double(l), ft = Double(t), fr = Double(r), fb = Double(b)
            let pTL = page(fl, ft), pTR = page(fr, ft), pBL = page(fl, fb), pBR = page(fr, fb)
            let cu = (fl + fr) / 2, cv = (ft + fb) / 2
            let pC = page(cu, cv)
            let canSplit = depth < depthLimit && (r - l) >= minCell && (b - t) >= minCell
            var inv: [Double]?
            var err = Double.nan
            if let TL = pTL, let TR = pTR, let BL = pBL, let BR = pBR, let C = pC {
                let a11 = (TR.x - TL.x) / w, a12 = (BL.x - TL.x) / h
                let a21 = (TR.y - TL.y) / w, a22 = (BL.y - TL.y) / h
                let ox = TL.x - a11 * fl - a12 * ft
                let oy = TL.y - a21 * fl - a22 * ft
                let det = a11 * a22 - a12 * a21
                if det != 0, det.isFinite {
                    let ia = a22 / det, ib = -a12 / det, id = -a21 / det, ie = a11 / det
                    let m = [ia, ib, -(ia * ox + ib * oy), id, ie, -(id * ox + ie * oy)]
                    if m.allSatisfy(\.isFinite) {
                        inv = m
                        let ubr = (m[0] * BR.x + m[1] * BR.y + m[2], m[3] * BR.x + m[4] * BR.y + m[5])
                        let uc = (m[0] * C.x + m[1] * C.y + m[2], m[3] * C.x + m[4] * C.y + m[5])
                        err = max(hypot(ubr.0 - fr, ubr.1 - fb), hypot(uc.0 - cu, uc.1 - cv))
                    }
                }
            }
            guard let m = inv, err.isFinite else {
                if canSplit { split(l, t, r, b, depth) } else { dropped += 1 }
                return
            }
            if err > maxErr && canSplit {
                split(l, t, r, b, depth)
                return
            }
            leaves.append((l, t, r, b, m, err, depth, [pTL!, pTR!, pBR!, pBL!]))
        }

        func split(_ l: Int, _ t: Int, _ r: Int, _ b: Int, _ depth: Int) {
            let mx = l + (r - l) / 2, my = t + (b - t) / 2
            rec(l, t, mx, my, depth + 1)
            rec(mx, t, r, my, depth + 1)
            rec(l, my, mx, b, depth + 1)
            rec(mx, my, r, b, depth + 1)
        }

        rec(l, t, r, b, 0)
        let cells = leaves.map { lf in
            PDFWarpCell(l: lf.l, t: lf.t, r: lf.r, b: lf.b, pageToPx: lf.inv, errorPx: lf.err,
                        coverage: PDFClip.classify(quad: lf.quad, against: footprint.clipPolygon),
                        depth: lf.depth, quad: lf.quad)
        }
        return PDFWarpPlan(root: (l, t, r, b), maxDepth: depthLimit, leaves: cells, dropped: dropped)
    }

    /// the usual page lookup: job pixel -> lat/lon -> georef.toPage
    static func plan(job: TileJob, tilePx: Int, footprint: PDFFootprint, georef: PdfGeoreference) -> PDFWarpPlan {
        plan(job: job, tilePx: tilePx, footprint: footprint) { u, v in
            let ll = PDFTileMath.jobPixelToLatLon(z: job.z, x0: job.x0, y0: job.y0, tilePx: tilePx, u: u, v: v)
            guard let p = georef.toPage(lat: ll.lat, lon: ll.lon), p.x.isFinite, p.y.isFinite else { return nil }
            return PdfPagePoint(x: p.x, y: p.y)
        }
    }
}

// MARK: - zoom policy + base raster plan

struct PDFBaseRasterPlan: Equatable {
    /// page rect the raster covers (clip bbox)
    let region: CGRect
    let scale: Double
    let width: Int
    let height: Int
    /// mip sizes, finest first
    let mips: [(w: Int, h: Int)]
    let budgetPx: Int

    /// px per pt of the full res raster, the min of both axes
    var densityPxPerPt: Double {
        min(Double(width) / Double(region.width), Double(height) / Double(region.height))
    }

    var key: String { "\(region.minX),\(region.minY),\(region.width),\(region.height),\(width)x\(height)" }

    static func == (a: PDFBaseRasterPlan, b: PDFBaseRasterPlan) -> Bool {
        a.region == b.region && a.width == b.width && a.height == b.height && a.budgetPx == b.budgetPx
    }
}

struct PDFZoomPolicy {
    let mercMetresPerPoint: Double
    let jacobian: [[Double]]
    let detailZoomRaw: Double
    let detailZoom: Int

    init(georef: PdfGeoreference, footprint: PDFFootprint) throws {
        let c = footprint.clipMean
        let h = PDFTileConstants.jacobianStepPt
        func f(_ x: Double, _ y: Double) throws -> (x: Double, y: Double) {
            guard let ll = georef.toWGS84(x: x, y: y) else { throw PDFRenderFailure.pageGeometry }
            return PDFTileMath.mercMetres(lat: ll.latitude, lon: ll.longitude)
        }
        let fxp = try f(c.x + h, c.y), fxm = try f(c.x - h, c.y)
        let fyp = try f(c.x, c.y + h), fym = try f(c.x, c.y - h)
        let j = [[(fxp.x - fxm.x) / 2, (fyp.x - fym.x) / 2],
                 [(fxp.y - fxm.y) / 2, (fyp.y - fym.y) / 2]]
        let det = j[0][0] * j[1][1] - j[0][1] * j[1][0]
        let mmpp = abs(det).squareRoot()
        guard mmpp.isFinite, mmpp > 0 else { throw PDFRenderFailure.pageGeometry }
        jacobian = j
        mercMetresPerPoint = mmpp
        detailZoomRaw = log2(PDFTileConstants.detailOversample * PDFTileConstants.metresPerUnitZ0 / mmpp)
        let dz = (detailZoomRaw - PDFTileConstants.detailZoomHysteresis).rounded(.up)
        detailZoom = Int(min(max(dz.isFinite ? dz : 0, 0), Double(PDFTileConstants.maxZoomCap)))
    }

    static func pxPerPoint(mercMetresPerPoint mmpp: Double, z: Int, tilePx: Int) -> Double {
        (Double(tilePx) / 256) * mmpp * pow(2.0, Double(z)) / PDFTileConstants.metresPerUnitZ0
    }

    func pxPerPoint(z: Int, tilePx: Int) -> Double {
        Self.pxPerPoint(mercMetresPerPoint: mercMetresPerPoint, z: z, tilePx: tilePx)
    }

    func baseRasterPlan(footprint: PDFFootprint, budgetPixels: Int) -> PDFBaseRasterPlan {
        let region = footprint.clipBBox
        let w = Double(region.width), h = Double(region.height)
        let s = min((Double(budgetPixels) / (w * h)).squareRoot(),
                    PDFTileConstants.baseMaxSide / max(w, h), PDFTileConstants.baseMaxScale)
        let W = max(1, Int((w * s).rounded(.up))), H = max(1, Int((h * s).rounded(.up)))
        var mips = [(w: W, h: H)]
        while max(mips[mips.count - 1].w, mips[mips.count - 1].h) > PDFTileConstants.mipStopSide {
            let last = mips[mips.count - 1]
            mips.append(((last.w + 1) / 2, (last.h + 1) / 2))
        }
        return PDFBaseRasterPlan(region: region, scale: s, width: W, height: H, mips: mips, budgetPx: budgetPixels)
    }

    /// largest z in 0...detailZoom where the base raster isnt upsampled, -1 when none
    func baseMaxZoom(plan: PDFBaseRasterPlan, tilePx: Int) -> Int {
        let density = plan.densityPxPerPt * PDFTileConstants.upsampleTolerance
        var best = -1
        for z in 0...detailZoom where pxPerPoint(z: z, tilePx: tilePx) <= density { best = z }
        return best
    }
}

// MARK: - job formation + bake plan

enum PDFJobFormation {
    /// heavy live job: round robin right, down, left, up until a whole pass
    /// adds nothing. Every added tile has to be a pending VISIBLE tile at the
    /// seed's z. <= 6 tiles, <= 3 a side, no world wrap.
    static func grow(seed: TileIndex, pendingVisible: Set<TileIndex>,
                     maxTiles: Int = PDFTileConstants.jobMaxTiles,
                     maxSide: Int = PDFTileConstants.jobMaxSide) -> TileJob {
        let n = 1 << seed.z
        var x0 = seed.x, y0 = seed.y, cols = 1, rows = 1
        func ok(_ tiles: [TileIndex], _ nc: Int, _ nr: Int) -> Bool {
            nc <= maxSide && nr <= maxSide && nc * nr <= maxTiles && tiles.allSatisfy { pendingVisible.contains($0) }
        }
        let z = seed.z
        var grew = true
        while grew {
            grew = false
            if x0 + cols < n, ok((y0..<(y0 + rows)).map { TileIndex(z: z, x: x0 + cols, y: $0) }, cols + 1, rows) {
                cols += 1; grew = true
            }
            if y0 + rows < n, ok((x0..<(x0 + cols)).map { TileIndex(z: z, x: $0, y: y0 + rows) }, cols, rows + 1) {
                rows += 1; grew = true
            }
            if x0 > 0, ok((y0..<(y0 + rows)).map { TileIndex(z: z, x: x0 - 1, y: $0) }, cols + 1, rows) {
                x0 -= 1; cols += 1; grew = true
            }
            if y0 > 0, ok((x0..<(x0 + cols)).map { TileIndex(z: z, x: $0, y: y0 - 1) }, cols, rows + 1) {
                y0 -= 1; rows += 1; grew = true
            }
        }
        return TileJob(z: z, x0: x0, y0: y0, cols: cols, rows: rows)
    }

    static func bakeBlock(for t: TileIndex, heavy: Bool) -> TileJob {
        guard heavy else { return TileJob(z: t.z, x0: t.x, y0: t.y, cols: 1, rows: 1) }
        let n = 1 << t.z
        let bx = PDFTileConstants.bakeBlockCols, by = PDFTileConstants.bakeBlockRows
        let x0 = (t.x / bx) * bx, y0 = (t.y / by) * by
        return TileJob(z: t.z, x0: x0, y0: y0, cols: min(bx, n - x0), rows: min(by, n - y0))
    }

    /// one bake level's jobs (contract E2). tiles come in row major. raster
    /// sampled levels are always 1x1, above baseMaxZoom heavy gets read once
    /// right here and holds for the whole level, a flip mid level waits
    struct BakeLevel: Equatable {
        enum Path: String { case raster, vector }
        let path: Path
        let heavyUsed: Bool
        /// in dispatch order, wanted = how many of the level's tiles it writes
        let jobs: [(job: TileJob, wanted: Int)]

        static func == (a: Self, b: Self) -> Bool {
            a.path == b.path && a.heavyUsed == b.heavyUsed
                && a.jobs.map(\.job) == b.jobs.map(\.job) && a.jobs.map(\.wanted) == b.jobs.map(\.wanted)
        }
    }

    /// baseMaxZoom < 0 means no base raster, every level is vector then.
    /// heavy is a closure on purpose so its obvious it only gets asked once
    static func bakeLevel(z: Int, tiles: [TileIndex], baseMaxZoom: Int, heavy: () -> Bool) -> BakeLevel {
        let raster = baseMaxZoom >= 0 && z <= baseMaxZoom
        let useHeavy = raster ? false : heavy()
        let wanted = Set(tiles)
        var seen = Set<TileJob>()
        var jobs: [(job: TileJob, wanted: Int)] = []
        // blocks are aligned, so the first tile of a block is where it forms
        for t in tiles {
            let b = bakeBlock(for: t, heavy: useHeavy)
            guard seen.insert(b).inserted else { continue }
            jobs.append((b, b.tiles.filter { wanted.contains($0) }.count))
        }
        return BakeLevel(path: raster ? .raster : .vector, heavyUsed: useHeavy, jobs: jobs)
    }
}

struct PDFBakeOption: Equatable {
    let maxZoom: Int
    let tiles: Int
}

enum PDFBakePlan {
    /// per z tile counts for 0...maxZoom, nil when one blows past the cap
    static func counts(footprint: PDFFootprint, maxZoom: Int, cap: Int = PDFTileConstants.bakeMaxTiles) -> [Int]? {
        var out: [Int] = []
        var total = 0
        for z in 0...max(0, maxZoom) {
            guard let level = footprint.tiles(z: z, limit: cap - total) else { return nil }
            out.append(level.count)
            total += level.count
            if total > cap { return nil }
        }
        return out
    }

    /// candidates {D-2, D-1, D} clamped, kept when the sum over 0...m is <= 6000
    static func options(detailZoom d: Int, footprint: PDFFootprint,
                        isCancelled: () -> Bool = { false }) -> [PDFBakeOption] {
        let cands = Array(Set(PDFTileConstants.bakeCandidateOffsets.map {
            min(max(d + $0, 0), PDFTileConstants.maxZoomCap)
        })).sorted()
        guard let top = cands.last else { return [] }
        // one pass up to D, then sums. a level past the cap kills everything above it
        var perZ: [Int] = []
        var total = 0
        for z in 0...top {
            guard let level = footprint.tiles(z: z, limit: PDFTileConstants.bakeMaxTiles - total,
                                              isCancelled: isCancelled) else { break }
            perZ.append(level.count)
            total += level.count
            if total > PDFTileConstants.bakeMaxTiles { break }
        }
        var out: [PDFBakeOption] = []
        for m in cands where m < perZ.count {
            let tiles = perZ[0...m].reduce(0, +)
            if tiles <= PDFTileConstants.bakeMaxTiles { out.append(PDFBakeOption(maxZoom: m, tiles: tiles)) }
        }
        return out
    }

    static func defaultOption(_ options: [PDFBakeOption], detailZoom d: Int) -> PDFBakeOption? {
        options.first(where: { $0.maxZoom == d + PDFTileConstants.bakeDefaultOffset })
            ?? options.max(by: { $0.maxZoom < $1.maxZoom })
    }
}
