import XCTest
import CoreLocation
import CoreGraphics
import UIKit
import Grid
@testable import TacticalMaps

final class MapInteractionRegressionTests: XCTestCase {
    func testCoordinateDisplayFormatResolvesSelectedPrimaryCoordinate() {
        let mgrs = "56HLH 13225 37516"
        let wgs84 = "33.86880° S, 151.20930° E"
        let utm = "56S 334369mE 6250948mN"

        XCTAssertEqual(
            CoordinateDisplayFormat.mgrs.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm),
            .init(format: .mgrs, text: mgrs)
        )
        XCTAssertEqual(
            CoordinateDisplayFormat.wgs84.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm),
            .init(format: .wgs84, text: wgs84)
        )
        XCTAssertEqual(
            CoordinateDisplayFormat.utm.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm),
            .init(format: .utm, text: utm)
        )
    }

    func testUnavailableUTMPrimaryFallsBackToWGS84() {
        let wgs84 = "85.00000° N, 0.00000° E"
        for unavailable in [nil, "", "   ", "N/A (>84°N)"] as [String?] {
            XCTAssertEqual(
                CoordinateDisplayFormat.utm.resolve(
                    mgrs: "N/A (>84°N)",
                    wgs84: wgs84,
                    utm: unavailable
                ),
                .init(format: .wgs84, text: wgs84)
            )
        }
    }

    func testCoordinateDisplayPreferenceDefaultsToMGRSAndPersists() throws {
        let suiteName = "CoordinateDisplayPreferenceTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }

        XCTAssertEqual(CoordinateDisplayFormat.stored(in: defaults), .mgrs)
        CoordinateDisplayFormat.utm.persist(in: defaults)
        XCTAssertEqual(CoordinateDisplayFormat.stored(in: defaults), .utm)

        defaults.set("unknown-future-value", forKey: CoordinateDisplayFormat.defaultsKey)
        XCTAssertEqual(CoordinateDisplayFormat.stored(in: defaults), .mgrs)
    }

    func testHeadingNormalizesAcrossNorthAndConvertsToMils() {
        XCTAssertEqual(MapHeading.normalized(361), 1, accuracy: 1e-12)
        XCTAssertEqual(MapHeading.normalized(-90), 270, accuracy: 1e-12)
        XCTAssertEqual(MapHeading.mils(for: 0), 0)
        XCTAssertEqual(MapHeading.mils(for: 90), 1600)
        XCTAssertEqual(MapHeading.mils(for: 180), 3200)
        XCTAssertEqual(MapHeading.mils(for: 270), 4800)
        XCTAssertEqual(MapHeading.milsString(for: 359.99), "0000")
    }

    func testRotationDeltaPublishesNormalizedCameraHeading() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21),
            zoom: 12,
            headingDegrees: 350,
            viewportSize: CGSize(width: 390, height: 844)
        )
        let view = TileMapView(camera: camera)
        var published: MapCamera?
        view.onCameraChange = { published = $0 }
        let gesture = UIRotationGestureRecognizer()
        gesture.rotation = 20 * .pi / 180

        view.consumeRotationGestureDelta(gesture)

        XCTAssertEqual(view.camera.headingDegrees, 10, accuracy: 1e-9)
        XCTAssertEqual(published?.headingDegrees ?? -1, 10, accuracy: 1e-9)
        XCTAssertEqual(gesture.rotation, 0, accuracy: 1e-12)
    }

    func testCoordinateOnlyWaypointMoveRepublishesScreenPosition() {
        let camera = MapCamera(
            center: CLLocationCoordinate2D(latitude: 0, longitude: 0),
            zoom: 10,
            headingDegrees: 0,
            viewportSize: CGSize(width: 400, height: 600)
        )
        let view = TileMapView(camera: camera)
        let mapVM = MapViewModel()
        let coordinator = TileMapContainer.Coordinator()
        coordinator.attach(view: view, mapVM: mapVM)

        var waypoint = Waypoint(
            name: "Unit",
            coordinate: CLLocationCoordinate2D(latitude: 0, longitude: 0)
        )
        coordinator.syncWaypoints([waypoint], view: view)
        let first = expectation(description: "initial position published")
        DispatchQueue.main.async {
            XCTAssertEqual(mapVM.waypointScreenPositions[waypoint.id]?.x ?? -1, 200, accuracy: 1e-6)
            first.fulfill()
        }
        wait(for: [first], timeout: 1)

        waypoint.longitude = 0.5
        coordinator.syncWaypoints([waypoint], view: view)
        let moved = expectation(description: "coordinate change published")
        DispatchQueue.main.async {
            XCTAssertGreaterThan(mapVM.waypointScreenPositions[waypoint.id]?.x ?? 0, 200)
            moved.fulfill()
        }
        wait(for: [moved], timeout: 1)
    }

    func testAppliedMGRSGridAddsExactlyOnePhysicalPixel() {
        let base = MGRSGridRenderer.lineWidth(for: .HUNDRED_KILOMETER)
        XCTAssertEqual(
            MGRSGridRenderer.appliedLineWidth(
                for: .HUNDRED_KILOMETER,
                screenScale: 2
            ),
            base + 0.5,
            accuracy: 1e-12
        )
        XCTAssertEqual(
            MGRSGridRenderer.appliedLineWidth(
                for: .HUNDRED_KILOMETER,
                screenScale: 3
            ),
            base + 1.0 / 3.0,
            accuracy: 1e-12
        )
    }

    func testDistanceFormattingUsedByFromMeReadout() {
        XCTAssertEqual(MeasureFormat.distance(428), "428 m")
        XCTAssertEqual(MeasureFormat.distance(1_500), "1.50 km")
    }
}
