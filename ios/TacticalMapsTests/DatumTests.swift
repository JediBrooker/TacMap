import XCTest
import CoreLocation
@testable import TacticalMaps

/// Datum shift applied to fiduciary coords during PDF calibration.
/// WGS84/GDA2020 are coincident; GDA94 differs by ~1.8 m.
final class DatumTests: XCTestCase {

    private let sydney = CLLocationCoordinate2D(latitude: -33.8568, longitude: 151.2153)

    func testWGS84AndGDA2020AreIdentity() {
        XCTAssertEqual(Datum.wgs84.toWGS84(sydney).latitude, sydney.latitude, accuracy: 1e-12)
        XCTAssertEqual(Datum.wgs84.toWGS84(sydney).longitude, sydney.longitude, accuracy: 1e-12)
        XCTAssertEqual(Datum.gda2020.toWGS84(sydney).latitude, sydney.latitude, accuracy: 1e-12)
        XCTAssertEqual(Datum.gda2020.toWGS84(sydney).longitude, sydney.longitude, accuracy: 1e-12)
    }

    func testGDA94ShiftHasExpectedMagnitudeAndDirection() {
        // GDA94 to GDA2020 (basically WGS84) is ~1.8 m across Australia.
        // If we're in this band, ellipsoid conversion + units + rotation scaling
        // are all good (a units bug would be off by orders of magnitude).
        let w = Datum.gda94.toWGS84(sydney)
        let dLat = (w.latitude - sydney.latitude) * 111_320.0
        let dLon = (w.longitude - sydney.longitude) * 111_320.0 * cos(sydney.latitude * .pi / 180)
        let metres = (dLat * dLat + dLon * dLon).squareRoot()
        XCTAssertGreaterThan(metres, 1.0, "shift too small: \(metres) m")
        XCTAssertLessThan(metres, 2.5, "shift too large: \(metres) m")
        // GDA2020 (~WGS84) sits ~1.8 m NORTH-EAST of GDA94 (Geoscience
        // Australia). Check each component's sign so a transposed / sign-flipped
        // Helmert rotation gets caught. Magnitude alone would pass either way.
        XCTAssertGreaterThan(dLat, 0, "expected a northward shift")
        XCTAssertGreaterThan(dLon, 0, "expected an eastward shift")
    }

    func testECEFRoundTripIsStable() {
        // gda2020 path is identity, so feeding a point through the geodetic/ECEF
        // round-trip (via gda94 with near-zero net change checked elsewhere) keeps
        // lat/lon well-formed. Sanity check that fromECEF(toECEF) is stable.
        let shifted = Datum.gda94.toWGS84(sydney)
        XCTAssertEqual(shifted.latitude, sydney.latitude, accuracy: 0.001)   // within ~100 m
        XCTAssertEqual(shifted.longitude, sydney.longitude, accuracy: 0.001)
    }

    // MARK: - M29: GeoPDF legacy-datum geocentric-translation shifts

    // Horizontal displacement in metres that DatumShift.toWGS84 applies at a point.
    private func shiftMetres(lat: Double, lon: Double, code: String) -> Double {
        let w = DatumShift.toWGS84(lat: lat, lon: lon, datumCode: code)
        let dLat = (w.lat - lat) * 111_320.0
        let dLon = (w.lon - lon) * 111_320.0 * cos(lat * .pi / 180)
        return (dLat * dLat + dLon * dLon).squareRoot()
    }

    func testModernDatumsAreIdentity() {
        // WGS84 / GDA94 / NAD83 are coincident with WGS84 to sub-metre, so the
        // fast-path must return the input byte-for-byte (zero shift).
        for code in ["WE", "WD", "GD", "NA", "ZZ" /* unknown → identity */] {
            let w = DatumShift.toWGS84(lat: 51.5, lon: -0.12, datumCode: code)
            XCTAssertEqual(w.lat, 51.5, accuracy: 1e-12, "\(code) should be identity")
            XCTAssertEqual(w.lon, -0.12, accuracy: 1e-12, "\(code) should be identity")
        }
    }

    func testLegacyDatumShiftsMatchKnownHorizontalOffsets() {
        // Expected values are the horizontal surface shift from the 3-param
        // geocentric translation at each point (measured on the real simulator).
        // Much smaller than raw |dx,dy,dz| because most of a translation goes
        // into ellipsoidal height which we discard. Lines up with published datum
        // offsets, notably Tokyo's famous ~450 m. Tight bands catch a params typo
        // (10x / sign flip lands way outside) without being brittle. Points are
        // inside each datum's region of validity.
        XCTAssertEqual(shiftMetres(lat: 51.48, lon:  -0.10, code: "OS"), 140, accuracy: 25) // OSGB36, London
        XCTAssertEqual(shiftMetres(lat: 43.30, lon:   5.40, code: "EU"), 145, accuracy: 25) // ED50, Marseille
        XCTAssertEqual(shiftMetres(lat: 39.00, lon: -95.00, code: "NS"),  22, accuracy: 8)  // NAD27, Kansas
        XCTAssertEqual(shiftMetres(lat: 35.68, lon: 139.77, code: "TC"), 464, accuracy: 50) // Tokyo, Tokyo (~450 m)
        XCTAssertEqual(shiftMetres(lat: 46.95, lon:   7.44, code: "CH"), 164, accuracy: 30) // CH1903, Bern
        XCTAssertEqual(shiftMetres(lat: 48.85, lon:   2.35, code: "NT"),  54, accuracy: 15) // NTF, Paris
        XCTAssertEqual(shiftMetres(lat: 55.75, lon:  37.62, code: "KK"), 127, accuracy: 25) // SK-42, Moscow
    }

    func testDatumShiftPreservesLatLonWellFormed() {
        // The shifted coordinate must stay a valid lat/lon (no NaN / wraparound
        // from a broken ECEF round-trip) and near the input.
        let w = DatumShift.toWGS84(lat: 51.48, lon: -0.10, datumCode: "OS")
        XCTAssertTrue(w.lat.isFinite && w.lon.isFinite)
        XCTAssertEqual(w.lat, 51.48, accuracy: 0.01)   // within ~1 km
        XCTAssertEqual(w.lon, -0.10, accuracy: 0.01)
    }

    // MARK: - M29 (inline /Datum dict): embedded ellipsoid + ToWGS84 params

    // Horizontal displacement (m) from the explicit-params overload, i.e. the
    // path a GeoPDF's inline /Datum dictionary feeds.
    private func shiftMetres(lat: Double, lon: Double, ellipsoid: Ellipsoid,
                             dx: Double, dy: Double, dz: Double) -> Double {
        let w = DatumShift.toWGS84(lat: lat, lon: lon, sourceEllipsoid: ellipsoid, dx: dx, dy: dy, dz: dz)
        let dLat = (w.lat - lat) * 111_320.0
        let dLon = (w.lon - lon) * 111_320.0 * cos(lat * .pi / 180)
        return (dLat * dLat + dLon * dLon).squareRoot()
    }

    func testFortIrwinInlineNAD83ShiftIsSmallButNonZero() {
        // TacMap-FortIrwin-USTopo.pdf expresses /Datum as a dictionary:
        //   /Ellipsoid /SemiMajorAxis 6378137 /InvFlattening 298.257222101  (GRS80)
        //   /ToWGS84   /dx 0.9738 /dy -1.9453 /dz -0.5486
        // NAD83 is basically WGS84, so shift at Fort Irwin (~35.3N, 116.7W) is
        // just a few metres - but not zero anymore (the dict form used to bail
        // out to WGS84 identity, ignoring these params).
        let grs80 = Ellipsoid(a: 6_378_137, f: 1.0 / 298.257222101)
        let m = shiftMetres(lat: 35.30, lon: -116.70, ellipsoid: grs80,
                            dx: 0.9738, dy: -1.9453, dz: -0.5486)
        XCTAssertGreaterThan(m, 0.3, "expected a small non-zero NAD83→WGS84 shift, got \(m) m")
        XCTAssertLessThan(m, 5.0, "NAD83→WGS84 should be a few metres, got \(m) m")
    }

    func testInlineParamsMatchNamedCodeForSameDatum() {
        // Inline-dict path and named-code path must agree when they describe the
        // same datum: OSGB36 = Airy1830 + (446.448, -125.157, 542.06).
        let byName = DatumShift.toWGS84(lat: 51.48, lon: -0.10, datumCode: "OS")
        let byDict = DatumShift.toWGS84(lat: 51.48, lon: -0.10,
                                        sourceEllipsoid: .airy1830,
                                        dx: 446.448, dy: -125.157, dz: 542.06)
        XCTAssertEqual(byName.lat, byDict.lat, accuracy: 1e-9)
        XCTAssertEqual(byName.lon, byDict.lon, accuracy: 1e-9)
    }

    func testInlineZeroTranslationIsIdentity() {
        // A /Datum dict with no /ToWGS84 (dx=dy=dz=0) must be a byte-for-byte
        // identity fast-path.
        let w = DatumShift.toWGS84(lat: 35.3, lon: -116.7,
                                   sourceEllipsoid: .grs80, dx: 0, dy: 0, dz: 0)
        XCTAssertEqual(w.lat, 35.3, accuracy: 1e-12)
        XCTAssertEqual(w.lon, -116.7, accuracy: 1e-12)
    }
}
