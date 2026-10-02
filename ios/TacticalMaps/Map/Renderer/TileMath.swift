import Foundation
import CoreGraphics

/// One XYZ tile address.
struct TileIndex: Equatable, Hashable {
    let z: Int
    let x: Int
    let y: Int
}

/// Works out which tiles a camera can see, and at what integer zoom.
enum TileMath {

    /// past this many levels of underzoom a source draws nothing (else an
    /// MBTiles with minZoom 10 at camera z2 would ask for thousands of tiles)
    static let maxUnderzoomLevels = PDFTileConstants.maxUnderzoomLevels

    /// Integer tile zoom for a fractional camera zoom, clamped to a source's
    /// range. We round rather than floor so we cross to the sharper level near
    /// the halfway point instead of staying blurry until the next whole zoom.
    /// Ties go up, camera zoom is always positive so .rounded() does that.
    static func tileZoom(for cameraZoom: Double, minZoom: Int, maxZoom: Int) -> Int {
        min(max(Int((cameraZoom).rounded()), minZoom), maxZoom)
    }

    static func isUnderzoomed(tileZoom: Int, cameraZoom: Double) -> Bool {
        PDFTileMath.underzoomHidden(tileZoom: tileZoom, cameraZoom: cameraZoom)
    }

    /// camera centre in z0 world units / 256 (0..1, y down). Heading doesnt
    /// move it, the viewport rotates about its centre. Same as Android's
    /// TileMath.viewportCentreUnit, feeds the PDF scheduler's distance order
    static func viewportCentreUnit(camera: MapCamera) -> CGPoint {
        let w = PDFTileMath.world0(lat: camera.center.latitude, lon: camera.center.longitude)
        return CGPoint(x: w.x / 256, y: w.y / 256)
    }

    /// Every tile whose square, grown by 1 pt, overlaps the rotated viewport
    /// (separating axis test, so a turned map doesnt pull in the bbox corners).
    /// Columns stay unwrapped for layout and the index wraps modulo 2^z, so
    /// panning across +/-180 puts each tile on its own side. Sorted by
    /// distance from the viewport centre, ties by row then col.
    static func visibleTiles(camera: MapCamera, tileZoom: Int) -> [VisibleTile] {
        let n = 1 << tileZoom
        let scale = pow(2.0, camera.zoom - Double(tileZoom))
        guard scale.isFinite, scale > 0 else { return [] }
        let hw = Double(camera.viewportSize.width) / 2
        let hh = Double(camera.viewportSize.height) / 2
        guard hw > 0, hh > 0 else { return [] }
        let c = WebMercator.worldPoint(camera.center, zoom: Double(tileZoom))
        let cwx = Double(c.x), cwy = Double(c.y)

        // flat (heading 0) offset = rotate(screen offset, +heading), see MapCamera
        let r = camera.headingDegrees * .pi / 180
        let cs = cos(r), sn = sin(r)
        // viewport axes in flat space
        let ux = cs, uy = sn          // screen +x
        let vx = -sn, vy = cs         // screen +y
        let ex = hw * abs(ux) + hh * abs(vx)
        let ey = hw * abs(uy) + hh * abs(vy)

        let ts = WebMercator.tileSize
        let inflate = PDFTileConstants.visibleTileInflate
        let minCol = Int(((cwx + (-ex - inflate) / scale) / ts).rounded(.down))
        let maxCol = Int(((cwx + (ex + inflate) / scale) / ts).rounded(.down))
        let minRow = max(0, Int(((cwy + (-ey - inflate) / scale) / ts).rounded(.down)))
        let maxRow = min(n - 1, Int(((cwy + (ey + inflate) / scale) / ts).rounded(.down)))
        guard minRow <= maxRow, minCol <= maxCol else { return [] }
        // a silly zoomed out viewport over a tiny world, keep it bounded
        guard (maxCol - minCol + 1) * (maxRow - minRow + 1) <= 4096 else { return [] }

        var out: [(VisibleTile, Double)] = []
        let edge = ts * scale
        for row in minRow...maxRow {
            for col in minCol...maxCol {
                // tile square in flat screen pt, relative to the viewport centre
                let x0 = (Double(col) * ts - cwx) * scale - inflate
                let y0 = (Double(row) * ts - cwy) * scale - inflate
                let x1 = x0 + edge + 2 * inflate
                let y1 = y0 + edge + 2 * inflate
                if x0 > ex || x1 < -ex || y0 > ey || y1 < -ey { continue }
                let mx = (x0 + x1) / 2, my = (y0 + y1) / 2
                let halfW = (x1 - x0) / 2, halfH = (y1 - y0) / 2
                // project onto the viewport's own axes
                let pu = mx * ux + my * uy, ru = halfW * abs(ux) + halfH * abs(uy)
                if pu - ru > hw || pu + ru < -hw { continue }
                let pv = mx * vx + my * vy, rv = halfW * abs(vx) + halfH * abs(vy)
                if pv - rv > hh || pv + rv < -hh { continue }
                let wrapped = ((col % n) + n) % n
                let cx = (Double(col) + 0.5) * ts - cwx, cy = (Double(row) + 0.5) * ts - cwy
                out.append((VisibleTile(index: TileIndex(z: tileZoom, x: wrapped, y: row), col: col, row: row),
                            cx * cx + cy * cy))
            }
        }
        out.sort { a, b in
            if a.1 != b.1 { return a.1 < b.1 }
            if a.0.row != b.0.row { return a.0.row < b.0.row }
            return a.0.col < b.0.col
        }
        return out.map(\.0)
    }
}
