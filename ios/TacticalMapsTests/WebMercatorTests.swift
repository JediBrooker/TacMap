import XCTest
import CoreLocation
@testable import TacticalMaps

/// Fixtures are hand-computed canonical slippy-map values, not echoes of the
/// implementation, so a sign flip or a wrong constant fails here.
final class WebMercatorTests: XCTestCase {

    private let eps = 1e-6

    func testOriginMapsToWorldCentre() {
        // (0,0) sits dead centre of the square map at every zoom.
        let z0 = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: 0, longitude: 0), zoom: 0)
        XCTAssertEqual(z0.x, 128, accuracy: eps)   // 256/2
        XCTAssertEqual(z0.y, 128, accuracy: eps)
        let z1 = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: 0, longitude: 0), zoom: 1)
        XCTAssertEqual(z1.x, 256, accuracy: eps)   // 512/2
        XCTAssertEqual(z1.y, 256, accuracy: eps)
    }

    func testAntimeridianAndEquatorEdges() {
        let west = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: 0, longitude: -180), zoom: 3)
        XCTAssertEqual(west.x, 0, accuracy: eps)
        let east = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: 0, longitude: 180), zoom: 3)
        XCTAssertEqual(east.x, WebMercator.mapSize(zoom: 3), accuracy: eps) // 2048
    }

    func testGroundResolutionAtEquatorZoom0() {
        // The textbook figure: 156543.03 metres per pixel at the equator, z0.
        let r = WebMercator.groundResolution(latitude: 0, zoom: 0)
        XCTAssertEqual(r, 156_543.033_9, accuracy: 1e-3)
        // Halves each zoom level in.
        XCTAssertEqual(WebMercator.groundResolution(latitude: 0, zoom: 1), r / 2, accuracy: 1e-3)
        // Shrinks with cos(lat): at 60deg it's half the equator value.
        XCTAssertEqual(WebMercator.groundResolution(latitude: 60, zoom: 0), r * 0.5, accuracy: 1e-1)
    }

    func testForwardInverseRoundTripsAcrossTheGlobe() {
        let samples = [
            CLLocationCoordinate2D(latitude: 0, longitude: 0),
            CLLocationCoordinate2D(latitude: 51.5074, longitude: -0.1278),   // London
            CLLocationCoordinate2D(latitude: -33.8688, longitude: 151.2093), // Sydney
            CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194), // SF
            CLLocationCoordinate2D(latitude: 84.9, longitude: 179.9),        // near Mercator limit
        ]
        for z in [3.0, 8.0, 14.0, 19.0] {
            for c in samples {
                let w = WebMercator.worldPoint(c, zoom: z)
                let back = WebMercator.coordinate(fromWorld: w, zoom: z)
                XCTAssertEqual(back.latitude, c.latitude, accuracy: 1e-6, "lat z=\(z)")
                XCTAssertEqual(back.longitude, c.longitude, accuracy: 1e-6, "lon z=\(z)")
            }
        }
    }

    func testLatitudeIsClampedNotInfinite() {
        // Beyond the Mercator limit y would diverge; we clamp latitude to the
        // limit, whose world point is exactly the top edge (y == 0). So the
        // north pole maps to the top of the map, finite and in-bounds - not NaN,
        // not negative, not off the edge.
        let size = WebMercator.mapSize(zoom: 5)
        let p = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: 89.9, longitude: 0), zoom: 5)
        XCTAssertTrue(p.y.isFinite)
        XCTAssertEqual(p.y, 0, accuracy: 1e-6, "clamped north pole sits on the top edge")
        XCTAssertLessThanOrEqual(p.y, size)
    }

    func testInverseClampsPointsBeyondEveryWorldEdge() {
        let size = WebMercator.mapSize(zoom: 5)
        let northWest = WebMercator.coordinate(
            fromWorld: CGPoint(x: -2 * size, y: -3 * size),
            zoom: 5
        )
        XCTAssertEqual(northWest.latitude, WebMercator.latLimit, accuracy: eps)
        XCTAssertEqual(northWest.longitude, -180, accuracy: eps)

        let southEast = WebMercator.coordinate(
            fromWorld: CGPoint(x: 3 * size, y: 4 * size),
            zoom: 5
        )
        XCTAssertEqual(southEast.latitude, -WebMercator.latLimit, accuracy: eps)
        XCTAssertEqual(southEast.longitude, 180, accuracy: eps)
    }

    func testNorthIsSmallerY() {
        // y increases southward. A northern point has a smaller world y.
        let north = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: 10, longitude: 0), zoom: 5)
        let south = WebMercator.worldPoint(CLLocationCoordinate2D(latitude: -10, longitude: 0), zoom: 5)
        XCTAssertLessThan(north.y, south.y)
    }
}
