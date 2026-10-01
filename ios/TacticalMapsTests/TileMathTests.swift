import XCTest
import CoreLocation
import CoreGraphics
@testable import TacticalMaps

final class TileMathTests: XCTestCase {
    func testOpenTopoMapShardingIsStableAndUsesAllDocumentedHosts() {
        let tiles = (0..<9).map { TileIndex(z: 4, x: $0, y: 2) }
        XCTAssertEqual(Set(tiles.map(OnlineRasterTileSource.openTopoHost)), Set(["a", "b", "c"]))
        let tile = TileIndex(z: 8, x: 17, y: 23)
        XCTAssertEqual(OnlineRasterTileSource.openTopoHost(for: tile),
                       OnlineRasterTileSource.openTopoHost(for: tile))
    }


    private func camera(lat: Double = 0, lon: Double = 0, zoom: Double,
                        heading: Double = 0, size: CGSize = CGSize(width: 400, height: 600)) -> MapCamera {
        MapCamera(center: CLLocationCoordinate2D(latitude: lat, longitude: lon),
                  zoom: zoom, headingDegrees: heading, viewportSize: size)
    }

    func testTileZoomRoundsAndClamps() {
        XCTAssertEqual(TileMath.tileZoom(for: 4.4, minZoom: 0, maxZoom: 19), 4)
        XCTAssertEqual(TileMath.tileZoom(for: 4.6, minZoom: 0, maxZoom: 19), 5)
        XCTAssertEqual(TileMath.tileZoom(for: 25, minZoom: 0, maxZoom: 19), 19)  // clamp high
        XCTAssertEqual(TileMath.tileZoom(for: -3, minZoom: 3, maxZoom: 19), 3)   // clamp low
    }

    func testZoomZeroSeesTheSingleWorldTile() {
        // unwrapped columns repeat the world tile left/right, one index though
        let tiles = TileMath.visibleTiles(camera: camera(zoom: 0), tileZoom: 0)
        XCTAssertEqual(Set(tiles.map(\.index)), [TileIndex(z: 0, x: 0, y: 0)])
    }

    func testOriginAtZoomOneTouchesTheFourCentreTiles() {
        // Centred on (0,0) at z1, the viewport straddles the meeting point of
        // all four tiles, so it should see exactly {(0,0),(1,0),(0,1),(1,1)}.
        let tiles = Set(TileMath.visibleTiles(camera: camera(zoom: 1), tileZoom: 1).map(\.index))
        XCTAssertEqual(tiles, Set([
            TileIndex(z: 1, x: 0, y: 0), TileIndex(z: 1, x: 1, y: 0),
            TileIndex(z: 1, x: 0, y: 1), TileIndex(z: 1, x: 1, y: 1),
        ]))
    }

    func testAllVisibleTilesAreInRange() {
        for z in 1...12 {
            let tiles = TileMath.visibleTiles(camera: camera(lat: 37, lon: -122, zoom: Double(z)), tileZoom: z)
            let n = 1 << z
            XCTAssertFalse(tiles.isEmpty, "z=\(z) saw no tiles")
            for t in tiles.map(\.index) {
                XCTAssertTrue((0..<n).contains(t.x), "x out of range z=\(z): \(t)")
                XCTAssertTrue((0..<n).contains(t.y), "y out of range z=\(z): \(t)")
            }
        }
    }

    func testAntimeridianDoesNotCrashAndWrapsX() {
        // Centre right on the antimeridian; x indices must wrap into [0,n).
        let tiles = TileMath.visibleTiles(camera: camera(lat: 0, lon: 180, zoom: 5), tileZoom: 5)
        let n = 1 << 5
        XCTAssertFalse(tiles.isEmpty)
        XCTAssertTrue(tiles.allSatisfy { (0..<n).contains($0.index.x) })
        // west of the line sits left of east of it, not stacked on top (latent wrap bug)
        let west = tiles.filter { $0.index.x == n - 1 }, east = tiles.filter { $0.index.x == 0 }
        XCTAssertFalse(west.isEmpty); XCTAssertFalse(east.isEmpty)
        XCTAssertTrue(west.allSatisfy { w in east.allSatisfy { $0.col > w.col } })
    }

    func testRotatedViewportTilesMatchBruteForce() {
        // SAT filter vs sampling the rotated viewport densely: every tile a
        // sample lands in must be listed, and nothing far outside it
        for heading in [0.0, 17, 45, 90, 213] {
            let cam = camera(lat: 37, lon: -122, zoom: 10.4, heading: heading)
            let got = Set(TileMath.visibleTiles(camera: cam, tileZoom: 10).map { [$0.col, $0.row] })
            var sampled = Set<[Int]>()
            let w = Double(cam.viewportSize.width), h = Double(cam.viewportSize.height)
            for i in 0...80 {
                for j in 0...120 {
                    let p = WebMercator.worldPoint(cam.coordinate(for: CGPoint(x: w * Double(i) / 80, y: h * Double(j) / 120)), zoom: 10)
                    sampled.insert([Int((Double(p.x) / 256).rounded(.down)), Int((Double(p.y) / 256).rounded(.down))])
                }
            }
            XCTAssertTrue(sampled.isSubset(of: got), "heading \(heading) missing \(sampled.subtracting(got))")
            // the 1 pt inflate can add a sliver neighbour but never a whole extra ring
            XCTAssertLessThanOrEqual(got.count, sampled.count + 2 * Int((w + h) / 256 * pow(2, -0.4)) + 8, "heading \(heading)")
        }
    }

    func testVisibleTilesSortedFromTheCentre() {
        let cam = camera(lat: 37, lon: -122, zoom: 12.3, heading: 30)
        let tiles = TileMath.visibleTiles(camera: cam, tileZoom: 12)
        let c = WebMercator.worldPoint(cam.center, zoom: 12)
        let d = tiles.map { pow((Double($0.col) + 0.5) * 256 - Double(c.x), 2) + pow((Double($0.row) + 0.5) * 256 - Double(c.y), 2) }
        XCTAssertEqual(d, d.sorted())
    }

    func testUnderzoomGuard() {
        XCTAssertTrue(TileMath.isUnderzoomed(tileZoom: 10, cameraZoom: 7.9))
        XCTAssertFalse(TileMath.isUnderzoomed(tileZoom: 10, cameraZoom: 8.0))
        XCTAssertFalse(TileMath.isUnderzoomed(tileZoom: 16, cameraZoom: 18.2))
    }

    func testTileGridEdgeScalesWithFractionalZoom() {
        // At integer zoom the tile is drawn at its native 256pt; half a level in
        // it's 256*sqrt(2). Neighbours share edges exactly.
        let exact = TileGrid(camera: camera(lat: 37, lon: -122, zoom: 10), tileZoom: 10)
        XCTAssertEqual(exact.frame(col: 163, row: 395).width, 256, accuracy: 1e-6)
        let half = TileGrid(camera: camera(lat: 37, lon: -122, zoom: 10.5), tileZoom: 10)
        let a = half.frame(col: 163, row: 395), b = half.frame(col: 164, row: 395)
        XCTAssertEqual(a.width, 256 * pow(2, 0.5), accuracy: 1e-6)
        XCTAssertEqual(a.maxX, b.minX, accuracy: 1e-9)
    }

    func testTileGridMatchesCameraProjection() {
        let cam = camera(lat: 37.7, lon: -122.4, zoom: 14.37)
        let grid = TileGrid(camera: cam, tileZoom: 14)
        let tl = WebMercator.coordinate(fromWorld: CGPoint(x: 2620 * 256, y: 6333 * 256), zoom: 14)
        let p = cam.screenPoint(for: tl)
        let f = grid.frame(col: 2620, row: 6333)
        XCTAssertEqual(f.minX, p.x, accuracy: 1e-6)
        XCTAssertEqual(f.minY, p.y, accuracy: 1e-6)
    }
}
