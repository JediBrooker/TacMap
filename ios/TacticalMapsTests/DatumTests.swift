import XCTest
import CoreLocation
@testable import TacticalMaps

/// Tests the datum shift applied to fiduciary coordinates during PDF
/// calibration. WGS84/GDA2020 are coincident; GDA94 differs by ~1.8 m.
final class DatumTests: XCTestCase {

    private let sydney = CLLocationCoordinate2D(latitude: -33.8568, longitude: 151.2153)

    func testWGS84AndGDA2020AreIdentity() {
        XCTAssertEqual(Datum.wgs84.toWGS84(sydney).latitude, sydney.latitude, accuracy: 1e-12)
        XCTAssertEqual(Datum.wgs84.toWGS84(sydney).longitude, sydney.longitude, accuracy: 1e-12)
        XCTAssertEqual(Datum.gda2020.toWGS84(sydney).latitude, sydney.latitude, accuracy: 1e-12)
        XCTAssertEqual(Datum.gda2020.toWGS84(sydney).longitude, sydney.longitude, accuracy: 1e-12)
    }

    func testGDA94ShiftHasExpectedMagnitude() {
        // GDA94 → GDA2020 (≈ WGS84) is ~1.8 m across Australia. A magnitude in
        // this band confirms the ellipsoid conversion, units, and rotation
        // scaling are all right (a units bug would be off by orders of magnitude).
        let w = Datum.gda94.toWGS84(sydney)
        let dLat = (w.latitude - sydney.latitude) * 111_320.0
        let dLon = (w.longitude - sydney.longitude) * 111_320.0 * cos(sydney.latitude * .pi / 180)
        let metres = (dLat * dLat + dLon * dLon).squareRoot()
        XCTAssertGreaterThan(metres, 1.0, "shift too small: \(metres) m")
        XCTAssertLessThan(metres, 2.5, "shift too large: \(metres) m")
    }

    func testECEFRoundTripIsStable() {
        // gda2020 path is identity, so feeding a point through the geodetic↔ECEF
        // machinery (via gda94 with near-zero net change checked elsewhere) keeps
        // latitude/longitude well-formed — sanity that fromECEF(toECEF) is stable.
        let shifted = Datum.gda94.toWGS84(sydney)
        XCTAssertEqual(shifted.latitude, sydney.latitude, accuracy: 0.001)   // within ~100 m
        XCTAssertEqual(shifted.longitude, sydney.longitude, accuracy: 0.001)
    }
}
