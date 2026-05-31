import XCTest
import CoreLocation
@testable import TacticalMaps

/// Tests for the parts of the MGRS bridge that are *ours* (not NGA's):
/// the crash-safety validation gate, the display spacing, and round-trip
/// stability. The underlying coordinate conversion is the NGA library's
/// responsibility and is covered by its own test suite.
final class MGRSFormatterTests: XCTestCase {

    // MARK: looksLikeMGRS — the regex gate that stops NGA's parser from
    // fatalError-ing on partial / garbage input.

    func testLooksLikeMGRS_acceptsValidShapes() {
        let valid = [
            "56HLH",              // GZD + 100km square, no digits
            "56HLH1322537516",   // 5+5 (1 m precision)
            "4QFJ1234",          // single-digit zone, 2+2
            "33UXP0500043000",   // 5+5
            "18TWL8040",         // 2+2
            "BKM1234",           // UPS polar form
        ]
        for s in valid {
            XCTAssertTrue(MGRSFormatter.looksLikeMGRS(s), "expected valid: \(s)")
        }
    }

    func testLooksLikeMGRS_rejectsMalformedShapes() {
        let invalid = [
            "",                   // empty
            "H",                  // single letter (used to fatalError NGA)
            "HELLO",              // place name
            "56",                 // digits only, no square
            "56HLH1",             // 1 trailing digit (odd)
            "56HLH123",           // 3 trailing digits (odd)
            "56ILH1234",          // band letter I is not permitted
            "560HLH",             // 3-digit zone
        ]
        for s in invalid {
            XCTAssertFalse(MGRSFormatter.looksLikeMGRS(s), "expected invalid: \(s)")
        }
    }

    // MARK: formatted — inserts the GZD / easting / northing spacing.

    func testFormatted_insertsTriadSpacing() {
        XCTAssertEqual(MGRSFormatter.formatted("56HLH1322537516"), "56HLH 13225 37516")
        XCTAssertEqual(MGRSFormatter.formatted("4QFJ12345678"), "4QFJ 1234 5678")
    }

    func testFormatted_normalisesExistingSpaces() {
        XCTAssertEqual(MGRSFormatter.formatted("  56HLH 13225 37516 "), "56HLH 13225 37516")
    }

    func testFormatted_oddDigitsFallBackToSingleSplit() {
        // splitDigits refuses to halve an odd run; it keeps the digits intact.
        XCTAssertEqual(MGRSFormatter.formatted("56HLH123"), "56HLH 123")
    }

    func testFormatted_passesThroughUnknownShapes() {
        XCTAssertEqual(MGRSFormatter.formatted("HELLO"), "HELLO")
    }

    // MARK: round-trip — format a coordinate, parse it back, expect ≈ identity.

    func testRoundTrip_coordinateThroughMGRSAndBack() throws {
        let sf = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
        let grid = MGRSFormatter.string(from: sf)
        let back = try XCTUnwrap(MGRSFormatter.coordinate(from: grid))
        // 1 m precision → well under 0.001° of error.
        XCTAssertEqual(back.latitude, sf.latitude, accuracy: 0.001)
        XCTAssertEqual(back.longitude, sf.longitude, accuracy: 0.001)
    }

    func testCoordinate_rejectsGarbageWithoutCrashing() {
        XCTAssertNil(MGRSFormatter.coordinate(from: "hello"))
        XCTAssertNil(MGRSFormatter.coordinate(from: ""))
        XCTAssertNil(MGRSFormatter.coordinate(from: "H"))
    }
}
