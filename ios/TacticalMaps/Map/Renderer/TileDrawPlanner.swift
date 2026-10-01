import Foundation
import CoreGraphics

// What the tile view draws each frame and what it asks sources for. Pure, no
// UIKit, pinned by testdata/pdf_tile_render.json drawPlan (same rules on
// Android). Works for every raster source, not just PDFs.

extension TileIndex {
    var parent: TileIndex? { z > 0 ? TileIndex(z: z - 1, x: x >> 1, y: y >> 1) : nil }

    func ancestor(levelsUp k: Int) -> TileIndex? {
        guard k >= 0, z - k >= 0 else { return nil }
        return TileIndex(z: z - k, x: x >> k, y: y >> k)
    }

    /// TL, TR, BL, BR
    var children: [TileIndex] {
        [TileIndex(z: z + 1, x: 2 * x, y: 2 * y), TileIndex(z: z + 1, x: 2 * x + 1, y: 2 * y),
         TileIndex(z: z + 1, x: 2 * x, y: 2 * y + 1), TileIndex(z: z + 1, x: 2 * x + 1, y: 2 * y + 1)]
    }

    /// where this tile sits inside ancestor a, 0..1, y down
    func unitRect(inAncestor a: TileIndex) -> CGRect {
        let k = z - a.z
        let m = 1 << k
        let s = 1.0 / Double(m)
        return CGRect(x: Double(x % m) * s, y: Double(y % m) * s, width: s, height: s)
    }
}

/// A tile the viewport can see. index.x is wrapped into 0..<2^z, col is
/// not, so a tile west of the antimeridian lands left of the one east of it.
struct VisibleTile: Hashable {
    let index: TileIndex
    let col: Int
    let row: Int
}

enum TileCacheState: Equatable {
    case image
    case empty
    case missing
}

struct TileDrawItem: Equatable {
    enum Kind: String { case ancestor, child, own }
    let kind: Kind
    let source: TileIndex
    let dest: VisibleTile
    /// sub rect of the source image, 0..1 y down
    let unitRect: CGRect
    /// sub rect of the dest frame, 0..1 y down (children fill a quarter)
    let destRect: CGRect
    let order: Int
}

struct TileDrawPlan: Equatable {
    var items: [TileDrawItem]
    /// fallbackZoom ancestors first (deduped), then missing visible tiles in visible order
    var requests: [TileIndex]
}

enum TileDrawPlanner {
    static let maxAncestorLevels = PDFTileConstants.maxAncestorLevels

    /// Per missing visible tile: nearest loaded ancestor up to 4 levels (or
    /// down to fallbackZoom if thats deeper), else its loaded children. An
    /// EMPTY ancestor stops the search and draws nothing. Draw order is
    /// ancestors coarsest first, then children, then own tiles.
    static func plan(visible: [VisibleTile], state: (TileIndex) -> TileCacheState,
                     fallbackZoom: Int?) -> TileDrawPlan {
        var anc: [(z: Int, vi: Int, item: TileDrawItem)] = []
        var child: [TileDrawItem] = []
        var own: [TileDrawItem] = []
        var reqFallback: [TileIndex] = []
        var reqFallbackSet = Set<TileIndex>()
        var reqVisible: [TileIndex] = []
        let full = CGRect(x: 0, y: 0, width: 1, height: 1)

        for (vi, v) in visible.enumerated() {
            let t = v.index
            switch state(t) {
            case .image:
                own.append(TileDrawItem(kind: .own, source: t, dest: v, unitRect: full, destRect: full, order: 0))
                continue
            case .empty:
                continue
            case .missing:
                break
            }
            reqVisible.append(t)
            let useFz: Bool
            if let fz = fallbackZoom, fz >= 0, fz < t.z { useFz = true } else { useFz = false }
            if useFz, let fz = fallbackZoom, let fa = t.ancestor(levelsUp: t.z - fz),
               state(fa) == .missing, !reqFallbackSet.contains(fa) {
                reqFallback.append(fa)
                reqFallbackSet.insert(fa)
            }
            let maxUp = useFz ? max(maxAncestorLevels, t.z - fallbackZoom!) : maxAncestorLevels
            var found = false
            var k = 1
            while k <= maxUp, t.z - k >= 0 {
                let a = t.ancestor(levelsUp: k)!
                let sa = state(a)
                if sa == .image {
                    anc.append((a.z, vi, TileDrawItem(kind: .ancestor, source: a, dest: v,
                                                      unitRect: t.unitRect(inAncestor: a), destRect: full, order: 0)))
                    found = true
                    break
                }
                if sa == .empty { found = true; break }
                k += 1
            }
            if found { continue }
            for (i, c) in t.children.enumerated() where state(c) == .image {
                let dx = Double(i % 2) / 2, dy = Double(i / 2) / 2
                child.append(TileDrawItem(kind: .child, source: c, dest: v, unitRect: full,
                                          destRect: CGRect(x: dx, y: dy, width: 0.5, height: 0.5), order: 0))
            }
        }
        anc.sort { $0.z != $1.z ? $0.z < $1.z : $0.vi < $1.vi }
        let ordered = anc.map(\.item) + child + own
        let items = ordered.enumerated().map { i, it in
            TileDrawItem(kind: it.kind, source: it.source, dest: it.dest, unitRect: it.unitRect,
                         destRect: it.destRect, order: i)
        }
        return TileDrawPlan(items: items, requests: reqFallback + reqVisible)
    }
}

/// Tile frames for one camera, all off a single origin so neighbouring
/// tiles share bit-identical edges. Frames are heading-flat; the tile root
/// layer carries the rotation.
struct TileGrid {
    let tileZoom: Int
    /// on-screen edge of one tile in pt
    let edge: Double
    let col0: Int
    let row0: Int
    /// flat screen position of the top-left corner of (col0, row0)
    let originX: Double
    let originY: Double

    init(camera: MapCamera, tileZoom: Int) {
        self.tileZoom = tileZoom
        let scale = pow(2.0, camera.zoom - Double(tileZoom))
        edge = WebMercator.tileSize * scale
        let c = WebMercator.worldPoint(camera.center, zoom: Double(tileZoom))
        col0 = Int((Double(c.x) / WebMercator.tileSize).rounded(.down))
        row0 = Int((Double(c.y) / WebMercator.tileSize).rounded(.down))
        originX = Double(camera.viewportSize.width) / 2 + (Double(col0) * WebMercator.tileSize - Double(c.x)) * scale
        originY = Double(camera.viewportSize.height) / 2 + (Double(row0) * WebMercator.tileSize - Double(c.y)) * scale
    }

    func frame(col: Int, row: Int) -> CGRect {
        CGRect(x: originX + Double(col - col0) * edge, y: originY + Double(row - row0) * edge, width: edge, height: edge)
    }

    func frame(_ v: VisibleTile) -> CGRect { frame(col: v.col, row: v.row) }
}
