import XCTest
import CoreLocation
import CoreGraphics
@testable import TacticalMaps

final class TileMathTests: XCTestCase {

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
        let tiles = TileMath.visibleTiles(camera: camera(zoom: 0), tileZoom: 0)
        XCTAssertEqual(tiles, [TileIndex(z: 0, x: 0, y: 0)])
    }

    func testOriginAtZoomOneTouchesTheFourCentreTiles() {
        // Centred on (0,0) at z1, the viewport straddles the meeting point of
        // all four tiles, so it should see exactly {(0,0),(1,0),(0,1),(1,1)}.
        let tiles = Set(TileMath.visibleTiles(camera: camera(zoom: 1), tileZoom: 1))
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
            for t in tiles {
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
        XCTAssertTrue(tiles.allSatisfy { (0..<n).contains($0.x) })
    }

    func testRotationCoversMoreTilesThanNorthUp() {
        // A 45deg-rotated viewport's bounding box is bigger, so it must cover at
        // least as many tiles as the north-up one - never fewer (that'd be holes).
        let flat = TileMath.visibleTiles(camera: camera(lat: 37, lon: -122, zoom: 10), tileZoom: 10)
        let turned = TileMath.visibleTiles(camera: camera(lat: 37, lon: -122, zoom: 10, heading: 45), tileZoom: 10)
        XCTAssertGreaterThanOrEqual(turned.count, flat.count)
    }

    func testTileFrameEdgeScalesWithFractionalZoom() {
        // At integer zoom the tile is drawn at its native 256pt; half a level in
        // it's 256*sqrt(2).
        let tile = TileIndex(z: 10, x: 163, y: 395)
        let exact = TileMath.tileFrame(tile, camera: camera(lat: 37, lon: -122, zoom: 10))
        XCTAssertEqual(exact.width, 256, accuracy: 1e-6)
        let half = TileMath.tileFrame(tile, camera: camera(lat: 37, lon: -122, zoom: 10.5))
        XCTAssertEqual(half.width, 256 * pow(2, 0.5), accuracy: 1e-6)
    }
}
