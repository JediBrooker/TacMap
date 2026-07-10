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

    /// Integer tile zoom for a fractional camera zoom, clamped to a source's
    /// range. We round rather than floor so we cross to the sharper level near
    /// the halfway point instead of staying blurry until the next whole zoom.
    static func tileZoom(for cameraZoom: Double, minZoom: Int, maxZoom: Int) -> Int {
        min(max(Int((cameraZoom).rounded()), minZoom), maxZoom)
    }

    /// Every tile touching the viewport at `tileZoom`. Samples the four viewport
    /// corners and takes their world-point bounding box, so a rotated camera
    /// still gets full coverage (at the cost of a few extra corner tiles, which
    /// is the right trade - a missing tile is a visible hole, an extra one isn't).
    ///
    /// y is clamped to the pyramid; x wraps around the antimeridian via modulo,
    /// so panning across +/-180 doesn't tear.
    static func visibleTiles(camera: MapCamera, tileZoom: Int) -> [TileIndex] {
        let n = 1 << tileZoom
        let size = WebMercator.mapSize(zoom: Double(tileZoom))

        let corners = [
            CGPoint(x: 0, y: 0),
            CGPoint(x: camera.viewportSize.width, y: 0),
            CGPoint(x: 0, y: camera.viewportSize.height),
            CGPoint(x: camera.viewportSize.width, y: camera.viewportSize.height),
        ]
        let world = corners.map { corner -> CGPoint in
            WebMercator.worldPoint(camera.coordinate(for: corner), zoom: Double(tileZoom))
        }

        let minTX = Int(floor((world.map { $0.x }.min() ?? 0) / WebMercator.tileSize))
        let maxTX = Int(floor((world.map { $0.x }.max() ?? 0) / WebMercator.tileSize))
        let minTYraw = Int(floor((world.map { $0.y }.min() ?? 0) / WebMercator.tileSize))
        let maxTYraw = Int(floor((world.map { $0.y }.max() ?? 0) / WebMercator.tileSize))
        // y has no wrap (poles), clamp it.
        let minTY = max(0, minTYraw)
        let maxTY = min(n - 1, maxTYraw)

        // At low zoom the viewport can be wider than the whole world, so
        // several screen columns map onto the same wrapped tile. Dedup, else
        // the tile view draws the same tile several times.
        var seen = Set<TileIndex>()
        var tiles: [TileIndex] = []
        var tx = minTX
        while tx <= maxTX {
            let wrappedX = ((tx % n) + n) % n // handle antimeridian + negatives
            var ty = minTY
            while ty <= maxTY {
                let t = TileIndex(z: tileZoom, x: wrappedX, y: ty)
                if seen.insert(t).inserted { tiles.append(t) }
                ty += 1
            }
            tx += 1
        }
        return tiles
    }

    /// Top-left screen point and on-screen size of a tile, for laying it out.
    /// Size is the same for every tile at a zoom (before camera rotation, which
    /// the view applies as a layer transform), so this returns the unrotated
    /// origin + edge length; the tile view rotates the whole layer by heading.
    static func tileFrame(_ tile: TileIndex, camera: MapCamera) -> CGRect {
        // Scale factor between the tile's integer zoom and the camera's
        // fractional zoom, so tiles grow/shrink smoothly between levels.
        let scale = pow(2, camera.zoom - Double(tile.z))
        let edge = WebMercator.tileSize * scale
        let tileWorldTopLeft = CGPoint(x: Double(tile.x) * WebMercator.tileSize,
                                       y: Double(tile.y) * WebMercator.tileSize)
        let coord = WebMercator.coordinate(fromWorld: tileWorldTopLeft, zoom: Double(tile.z))
        // Unrotated placement: project the tile's NW corner ignoring heading,
        // because the view rotates the tile layer as a whole.
        let flat = MapCamera(center: camera.center, zoom: camera.zoom,
                             headingDegrees: 0, viewportSize: camera.viewportSize)
        let origin = flat.screenPoint(for: coord)
        return CGRect(x: origin.x, y: origin.y, width: edge, height: edge)
    }
}
