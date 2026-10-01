import Foundation
import CoreGraphics

/// Per-frame label placement for the MGRS grid. Runs against the live camera
/// and the visible viewport (inset 16 dp), never the oversized build square,
/// so labels are actually on screen. Rules are in testdata/README.md
/// (label region, line/square placement, declutter).
extension MGRSGridRenderer {

    /// normalised mercator -> screen for one camera. Same maths as
    /// MapCamera.screenPoint, just without redoing the centre every call.
    /// "local" = dp at the camera zoom relative to the centre, north-up.
    struct ScreenProjection {
        let size: Double
        let cx: Double
        let cy: Double
        let cosH: Double
        let sinH: Double
        let vcx: Double
        let vcy: Double

        init(camera: MapCamera) {
            size = MGRSGridRenderer.tileSizeDp * pow(2, camera.zoom)
            cx = MGRSGridRenderer.mercX(camera.center.longitude)
            cy = MGRSGridRenderer.mercY(camera.center.latitude)
            let r = -camera.headingDegrees * .pi / 180
            cosH = cos(r)
            sinH = sin(r)
            vcx = Double(camera.viewportSize.width) / 2
            vcy = Double(camera.viewportSize.height) / 2
        }

        func localX(_ x: Double) -> Double { (x - cx) * size }
        func localY(_ y: Double) -> Double { (y - cy) * size }

        func screen(localX lx: Double, localY ly: Double) -> CGPoint {
            CGPoint(x: vcx + lx * cosH - ly * sinH, y: vcy + lx * sinH + ly * cosH)
        }

        func screen(_ p: GridPoint) -> CGPoint { screen(localX: localX(p.x), localY: localY(p.y)) }

        func local(screen p: CGPoint) -> (x: Double, y: Double) {
            let dx = Double(p.x) - vcx, dy = Double(p.y) - vcy
            return (dx * cosH + dy * sinH, -dx * sinH + dy * cosH)
        }

        /// north-up bounding box (local dp) of a screen rect, any heading
        func localBounds(of rect: CGRect) -> LocalRect {
            let corners = [CGPoint(x: rect.minX, y: rect.minY), CGPoint(x: rect.maxX, y: rect.minY),
                           CGPoint(x: rect.maxX, y: rect.maxY), CGPoint(x: rect.minX, y: rect.maxY)]
                .map { local(screen: $0) }
            let xs = corners.map { $0.x }, ys = corners.map { $0.y }
            return LocalRect(x0: xs.min() ?? 0, y0: ys.min() ?? 0, x1: xs.max() ?? 0, y1: ys.max() ?? 0)
        }

        func localBounds(of b: MercBounds) -> LocalRect {
            LocalRect(x0: localX(b.minX), y0: localY(b.minY), x1: localX(b.maxX), y1: localY(b.maxY))
        }
    }

    struct LocalRect {
        let x0: Double
        let y0: Double
        let x1: Double
        let y1: Double

        var isEmpty: Bool { !(x0 < x1 && y0 < y1) }

        func intersects(_ o: LocalRect) -> Bool { x0 <= o.x1 && o.x0 <= x1 && y0 <= o.y1 && o.y0 <= y1 }
    }

    struct PlacedLabel {
        enum Kind: Hashable {
            case line(LineKey)
            case square(zone: Int, band: Character, easting: Int, northing: Int)
        }

        let kind: Kind
        let text: String
        /// owner level for lines, 100 km for squares. Sets the font.
        let level: Level
        /// screen point the text is centred on
        let anchor: CGPoint
        /// easting labels run along their line, rotated -90
        let isVertical: Bool
    }

    /// Label region of a cell in local dp: its lon span pulled in 16 dp at
    /// each zone edge, its band span as is. The inset viewport is applied
    /// separately in screen space.
    private static func labelRect(_ cell: Cell, _ proj: ScreenProjection) -> LocalRect {
        LocalRect(x0: proj.localX(mercX(cell.lonW)) + labelInsetDp,
                  y0: proj.localY(mercY(cell.latN)),
                  x1: proj.localX(mercX(cell.lonE)) - labelInsetDp,
                  y1: proj.localY(mercY(cell.latS)))
    }

    /// Place every visible label for this camera, decluttered. measure gives
    /// the unrotated text size for a label at its level.
    static func layoutLabels(_ grid: Grid, camera: MapCamera,
                             measure: (String, Level) -> CGSize) -> [PlacedLabel] {
        let w = Double(camera.viewportSize.width), h = Double(camera.viewportSize.height)
        let inset = labelInsetDp
        guard w > 2 * inset, h > 2 * inset, !grid.isEmpty else { return [] }
        let proj = ScreenProjection(camera: camera)
        let insetRect = CGRect(x: inset, y: inset, width: w - 2 * inset, height: h - 2 * inset)
        let viewLocal = proj.localBounds(of: insetRect)

        var squares: [(label: PlacedLabel, order: Int)] = []
        if grid.lod.labelsSquares {
            for (i, sq) in grid.squares.enumerated() {
                let rect = labelRect(sq.cell, proj)
                guard !rect.isEmpty, rect.intersects(viewLocal),
                      proj.localBounds(of: sq.bounds).intersects(rect),
                      proj.localBounds(of: sq.bounds).intersects(viewLocal) else { continue }
                if let label = squareLabel(sq, rect: rect, insetRect: insetRect, proj: proj) {
                    squares.append((label, i))
                }
            }
        }

        var lines: [PlacedLabel] = []
        var lineSort: [LineKey: Double] = [:]
        if let L = grid.lod.lineLabelLevel {
            var best: [LineKey: LineEnd] = [:]
            for piece in grid.pieces where piece.key.value % L.metres == 0 {
                let rect = labelRect(piece.cell, proj)
                guard !rect.isEmpty, rect.intersects(viewLocal),
                      proj.localBounds(of: piece.bounds).intersects(rect) else { continue }
                visibleEnds(piece, rect: rect, insetRect: insetRect, proj: proj, into: &best)
            }
            for (key, end) in best where end.length >= minLabelledLengthDp {
                guard let text = lineLabelText(value: key.value, lineLabelLevel: L) else { continue }
                lines.append(PlacedLabel(kind: .line(key), text: text, level: end.level,
                                         anchor: end.anchor, isVertical: key.axis == .easting))
                // eastings west to east, northings north to south
                lineSort[key] = key.axis == .easting ? end.local.x : end.local.y
            }
        }

        // declutter: squares first, then lines coarse to fine, eastings then northings
        lines.sort { a, b in
            if a.level != b.level { return a.level < b.level }
            guard case let .line(ka) = a.kind, case let .line(kb) = b.kind else { return false }
            if ka.axis != kb.axis { return ka.axis == .easting }
            let pa = lineSort[ka] ?? 0, pb = lineSort[kb] ?? 0
            if pa != pb { return pa < pb }
            return (ka.zone, ka.value) < (kb.zone, kb.value)
        }
        var placed: [(PlacedLabel, CGSize)] = []
        for label in squares.sorted(by: { $0.order < $1.order }).map({ $0.label }) + lines {
            var size = measure(label.text, label.level)
            if label.isVertical { size = CGSize(width: size.height, height: size.width) }
            let clash = placed.contains { entry in
                let (other, os) = entry
                return abs(label.anchor.x - other.anchor.x) < (size.width + os.width) / 2 + labelBoxPadDp &&
                    abs(label.anchor.y - other.anchor.y) < (size.height + os.height) / 2 + labelBoxPadDp
            }
            if !clash { placed.append((label, size)) }
        }
        return placed.map { $0.0 }
    }

    // MARK: - line labels

    private struct LineEnd {
        var anchor: CGPoint
        /// local dp of the anchor; y for eastings (north end = smallest), x for
        /// northings (west end = smallest)
        var local: (x: Double, y: Double)
        var level: Level
        var length: Double
    }

    /// Walk a piece's visible bits (cell label rect in local, then the inset
    /// viewport in screen) and keep the north end for eastings / west end for
    /// northings, per line key.
    private static func visibleEnds(_ piece: Piece, rect: LocalRect, insetRect: CGRect,
                                    proj: ScreenProjection, into best: inout [LineKey: LineEnd]) {
        let pts = piece.points
        guard pts.count >= 2 else { return }
        let easting = piece.key.axis == .easting
        var entry = best[piece.key]
        var ax = proj.localX(pts[0].x), ay = proj.localY(pts[0].y)
        for i in 1 ..< pts.count {
            let bx = proj.localX(pts[i].x), by = proj.localY(pts[i].y)
            defer { ax = bx; ay = by }
            guard let (s0, s1) = clipSegment(ax, ay, bx, by, rect.x0, rect.y0, rect.x1, rect.y1) else { continue }
            let p0 = (x: ax + (bx - ax) * s0, y: ay + (by - ay) * s0)
            let p1 = (x: ax + (bx - ax) * s1, y: ay + (by - ay) * s1)
            let q0 = proj.screen(localX: p0.x, localY: p0.y), q1 = proj.screen(localX: p1.x, localY: p1.y)
            guard let (u0, u1) = clipSegment(Double(q0.x), Double(q0.y), Double(q1.x), Double(q1.y),
                                             Double(insetRect.minX), Double(insetRect.minY),
                                             Double(insetRect.maxX), Double(insetRect.maxY)) else { continue }
            let length = hypot(Double(q1.x - q0.x), Double(q1.y - q0.y)) * (u1 - u0)
            for u in [u0, u1] {
                let l = (x: p0.x + (p1.x - p0.x) * u, y: p0.y + (p1.y - p0.y) * u)
                let metric = easting ? l.y : l.x
                let s = CGPoint(x: Double(q0.x) + Double(q1.x - q0.x) * u,
                                y: Double(q0.y) + Double(q1.y - q0.y) * u)
                if var e = entry {
                    if metric < (easting ? e.local.y : e.local.x) {
                        e.anchor = s
                        e.local = l
                    }
                    entry = e
                } else {
                    entry = LineEnd(anchor: s, local: l, level: piece.level, length: 0)
                }
            }
            entry?.length += length
        }
        if let entry { best[piece.key] = entry }
    }

    /// Liang-Barsky against an axis-aligned rect, returns the kept parameter range.
    private static func clipSegment(_ ax: Double, _ ay: Double, _ bx: Double, _ by: Double,
                                    _ x0: Double, _ y0: Double, _ x1: Double, _ y1: Double)
        -> (Double, Double)? {
        let dx = bx - ax, dy = by - ay
        var t0 = 0.0, t1 = 1.0
        for (p, q) in [(-dx, ax - x0), (dx, x1 - ax), (-dy, ay - y0), (dy, y1 - ay)] {
            if p == 0 {
                if q < 0 { return nil }
            } else {
                let t = q / p
                if p < 0 {
                    if t > t1 { return nil }
                    t0 = max(t0, t)
                } else {
                    if t < t0 { return nil }
                    t1 = min(t1, t)
                }
            }
        }
        return t1 > t0 ? (t0, t1) : nil
    }

    // MARK: - square labels

    /// P = square clipped to its cell's label region and the inset viewport.
    /// Skip if P's screen box is under 40 dp either way, else anchor 32 dp in
    /// from P's top-left-most vertex, or walk toward P's centroid if that
    /// point falls outside P (clipped slivers, odd shapes).
    private static func squareLabel(_ sq: Square, rect: LocalRect, insetRect: CGRect,
                                    proj: ScreenProjection) -> PlacedLabel? {
        var local = sq.ring.map { (x: proj.localX($0.x), y: proj.localY($0.y)) }
        local = clipPolygon(local, x0: rect.x0, y0: rect.y0, x1: rect.x1, y1: rect.y1)
        guard local.count >= 3 else { return nil }
        var poly = local.map { l -> (x: Double, y: Double) in
            let s = proj.screen(localX: l.x, localY: l.y)
            return (Double(s.x), Double(s.y))
        }
        poly = clipPolygon(poly, x0: Double(insetRect.minX), y0: Double(insetRect.minY),
                           x1: Double(insetRect.maxX), y1: Double(insetRect.maxY))
        guard poly.count >= 3 else { return nil }
        let xs = poly.map { $0.x }, ys = poly.map { $0.y }
        guard let minX = xs.min(), let maxX = xs.max(), let minY = ys.min(), let maxY = ys.max(),
              maxX - minX >= squareLabelMinDp, maxY - minY >= squareLabelMinDp else { return nil }
        guard let centroid = areaCentroid(poly) else { return nil }
        guard let t = poly.min(by: { $0.x + $0.y < $1.x + $1.y }) else { return nil }
        var a = (x: t.x + squareLabelOffsetDp, y: t.y + squareLabelOffsetDp)
        if !pointInPolygon(a, poly) {
            let d = hypot(centroid.x - t.x, centroid.y - t.y)
            let f = d > 0 ? min(1, squareLabelOffsetDp * 2.0.squareRoot() / d) : 0
            a = (t.x + (centroid.x - t.x) * f, t.y + (centroid.y - t.y) * f)
        }
        return PlacedLabel(kind: .square(zone: sq.cell.zone, band: sq.cell.band,
                                         easting: sq.easting, northing: sq.northing),
                           text: sq.text, level: .km100, anchor: CGPoint(x: a.x, y: a.y), isVertical: false)
    }

    /// Sutherland-Hodgman against an axis-aligned rect.
    private static func clipPolygon(_ poly: [(x: Double, y: Double)],
                                    x0: Double, y0: Double, x1: Double, y1: Double) -> [(x: Double, y: Double)] {
        typealias P = (x: Double, y: Double)
        func pass(_ pts: [P], _ inside: (P) -> Bool, _ cross: (P, P) -> P) -> [P] {
            guard !pts.isEmpty else { return [] }
            var out: [P] = []
            out.reserveCapacity(pts.count + 4)
            var prev = pts[pts.count - 1]
            var prevIn = inside(prev)
            for cur in pts {
                let curIn = inside(cur)
                if curIn {
                    if !prevIn { out.append(cross(prev, cur)) }
                    out.append(cur)
                } else if prevIn {
                    out.append(cross(prev, cur))
                }
                prev = cur
                prevIn = curIn
            }
            return out
        }
        func atX(_ xc: Double) -> (P, P) -> P {
            { p, q in (xc, p.y + (q.y - p.y) * (xc - p.x) / (q.x - p.x)) }
        }
        func atY(_ yc: Double) -> (P, P) -> P {
            { p, q in (p.x + (q.x - p.x) * (yc - p.y) / (q.y - p.y), yc) }
        }
        var pts = poly
        pts = pass(pts, { $0.x >= x0 }, atX(x0))
        pts = pass(pts, { $0.x <= x1 }, atX(x1))
        pts = pass(pts, { $0.y >= y0 }, atY(y0))
        pts = pass(pts, { $0.y <= y1 }, atY(y1))
        return pts
    }

    private static func areaCentroid(_ pts: [(x: Double, y: Double)]) -> (x: Double, y: Double)? {
        var a = 0.0, cx = 0.0, cy = 0.0
        for i in 0 ..< pts.count {
            let p = pts[i], q = pts[(i + 1) % pts.count]
            let c = p.x * q.y - q.x * p.y
            a += c
            cx += (p.x + q.x) * c
            cy += (p.y + q.y) * c
        }
        a *= 0.5
        guard abs(a) > 1e-12 else { return nil }
        return (cx / (6 * a), cy / (6 * a))
    }

    private static func pointInPolygon(_ p: (x: Double, y: Double), _ pts: [(x: Double, y: Double)]) -> Bool {
        var inside = false
        var j = pts.count - 1
        for i in 0 ..< pts.count {
            let a = pts[i], b = pts[j]
            if (a.y > p.y) != (b.y > p.y) {
                let xi = a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)
                if p.x < xi { inside.toggle() }
            }
            j = i
        }
        return inside
    }
}
