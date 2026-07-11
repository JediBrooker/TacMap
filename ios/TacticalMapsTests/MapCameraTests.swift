import XCTest
import CoreLocation
import CoreGraphics
@testable import TacticalMaps

final class MapCameraTests: XCTestCase {

    private func camera(heading: Double = 0) -> MapCamera {
        MapCamera(center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
                  zoom: 4,
                  headingDegrees: heading,
                  viewportSize: CGSize(width: 400, height: 600))
    }

    func testCentreProjectsToViewportCentre() {
        let cam = camera()
        let p = cam.screenPoint(for: cam.center)
        XCTAssertEqual(p.x, 200, accuracy: 1e-6)
        XCTAssertEqual(p.y, 300, accuracy: 1e-6)
    }

    func testNorthIsUpAtHeadingZero() {
        // A point due north of centre lands directly above the centre.
        let cam = camera()
        let p = cam.screenPoint(for: CLLocationCoordinate2D(latitude: 1, longitude: 0))
        XCTAssertEqual(p.x, 200, accuracy: 1e-6, "due north stays on the vertical")
        XCTAssertLessThan(p.y, 300, "north is up")
    }

    func testEastIsRightAtHeadingZero() {
        let cam = camera()
        let p = cam.screenPoint(for: CLLocationCoordinate2D(latitude: 0, longitude: 1))
        XCTAssertEqual(p.y, 300, accuracy: 1e-6, "due east stays on the horizontal")
        XCTAssertGreaterThan(p.x, 200, "east is right")
    }

    func testHeadingNinetyPutsNorthOnTheLeft() {
        // Facing east (heading 90), north is on your left.
        let cam = camera(heading: 90)
        let p = cam.screenPoint(for: CLLocationCoordinate2D(latitude: 1, longitude: 0))
        XCTAssertLessThan(p.x, 200, "north swings to the left at heading 90")
        XCTAssertEqual(p.y, 300, accuracy: 1e-4, "north now along the horizontal")
    }

    func testScreenRoundTripsForEveryHeading() {
        for h in [0.0, 30, 90, 137, 270] {
            let cam = camera(heading: h)
            for screen in [CGPoint(x: 10, y: 20), CGPoint(x: 200, y: 300), CGPoint(x: 390, y: 590)] {
                let coord = cam.coordinate(for: screen)
                let back = cam.screenPoint(for: coord)
                XCTAssertEqual(back.x, screen.x, accuracy: 1e-4, "x h=\(h)")
                XCTAssertEqual(back.y, screen.y, accuracy: 1e-4, "y h=\(h)")
            }
        }
    }

    func testMetresPerPointMatchesGroundResolution() {
        let cam = camera()
        XCTAssertEqual(cam.metresPerPoint,
                       WebMercator.groundResolution(latitude: 0, zoom: 4),
                       accuracy: 1e-9)
    }
}
