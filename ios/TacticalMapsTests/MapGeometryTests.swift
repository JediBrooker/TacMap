import XCTest
import CoreGraphics
@testable import TacticalMaps

/// Tests the pure hit-test + zoom geometry extracted from the map coordinator.
/// These ran inside an 84 KB UIViewRepresentable before and were untestable.
final class MapGeometryTests: XCTestCase {

    func testDistanceToSegment_onSegmentIsZero() {
        let d = MapGeometry.distance(from: CGPoint(x: 5, y: 0),
                                     toSegment: CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0))
        XCTAssertEqual(d, 0, accuracy: 1e-9)
    }

    func testDistanceToSegment_perpendicular() {
        let d = MapGeometry.distance(from: CGPoint(x: 5, y: 3),
                                     toSegment: CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0))
        XCTAssertEqual(d, 3, accuracy: 1e-9)
    }

    func testDistanceToSegment_beyondEndpointClamps() {
        // Projection parameter > 1 clamps to endpoint (10,0): hypot(3,4) = 5.
        let d = MapGeometry.distance(from: CGPoint(x: 13, y: 4),
                                     toSegment: CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0))
        XCTAssertEqual(d, 5, accuracy: 1e-9)
    }

    func testDistanceToSegment_degenerateSegment() {
        let d = MapGeometry.distance(from: CGPoint(x: 3, y: 4),
                                     toSegment: CGPoint(x: 0, y: 0), CGPoint(x: 0, y: 0))
        XCTAssertEqual(d, 5, accuracy: 1e-9)
    }

    func testPointInPolygon_insideAndOutside() {
        let square = [CGPoint(x: 0, y: 0), CGPoint(x: 10, y: 0),
                      CGPoint(x: 10, y: 10), CGPoint(x: 0, y: 10)]
        XCTAssertTrue(MapGeometry.pointInPolygon(CGPoint(x: 5, y: 5), vertices: square))
        XCTAssertFalse(MapGeometry.pointInPolygon(CGPoint(x: 15, y: 5), vertices: square))
    }

    func testPointInPolygon_tooFewVertices() {
        XCTAssertFalse(MapGeometry.pointInPolygon(
            CGPoint(x: 0, y: 0), vertices: [CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 1)]))
    }

    func testMetresPerPoint() {
        // 0.01 deg lat over 100 pt height: 0.01 * 111000 / 100 = 11.1 m/pt.
        XCTAssertEqual(MapGeometry.metresPerPoint(latitudeDelta: 0.01, viewHeightPoints: 100),
                       11.1, accuracy: 1e-9)
        // Zero height clamps the divisor to 1.
        XCTAssertEqual(MapGeometry.metresPerPoint(latitudeDelta: 0.01, viewHeightPoints: 0),
                       0.01 * 111_000, accuracy: 1e-6)
    }

    func testZoomScaleFactor_clampsAndScales() {
        XCTAssertEqual(MapGeometry.zoomScaleFactor(metresPerPoint: 1, reference: 1), 1, accuracy: 1e-9)
        XCTAssertEqual(MapGeometry.zoomScaleFactor(metresPerPoint: 0.5, reference: 1), 2, accuracy: 1e-9)
        XCTAssertEqual(MapGeometry.zoomScaleFactor(metresPerPoint: 1000, reference: 1), 0.005, accuracy: 1e-9)
        XCTAssertEqual(MapGeometry.zoomScaleFactor(metresPerPoint: 0.0001, reference: 1), 50, accuracy: 1e-9)
    }
}
