import XCTest
import CoreLocation
@testable import TacticalMaps

/// Tests for the parts of the MGRS bridge that are ours (not NGA's):
/// crash-safety validation gate, display spacing, and round-trip stability.
/// The underlying coord conversion is NGA's problem and covered by their
/// own test suite.
final class MGRSFormatterTests: XCTestCase {

    // MARK: looksLikeMGRS - the regex gate that stops NGA's parser from
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
            "56ALH1234",          // band A is UPS, not a valid UTM band → NGA fatalError
            "56YLH1234",          // band Y is UPS, not a valid UTM band
            "00HLH1234",          // zone 00
            "61HLH1234",          // zone 61 (>60)
        ]
        for s in invalid {
            XCTAssertFalse(MGRSFormatter.looksLikeMGRS(s), "expected invalid: \(s)")
        }
    }

    // MARK: formatted - inserts the GZD / easting / northing spacing.

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

    // MARK: round-trip - format a coordinate, parse it back, expect ~= identity.

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
        XCTAssertNil(MGRSFormatter.coordinate(from: "BKM1234"),
                     "the vendored converter has no UPS parser")
    }

    func testGridResolverCentresShorthandAndFullMGRSAtEverySupportedPrecision() throws {
        let anchor = CLLocationCoordinate2D(latitude: -33.8688, longitude: 151.2093)
        let anchorGrid = MGRSFormatter.string(from: anchor, spaced: false)
        let prefix = String(anchorGrid.dropLast(10))
        let cases: [(String, Int)] = [
            ("1234", 1_000),
            ("123456", 100),
            ("12345678", 10),
            ("1234567890", 1),
        ]

        for (figures, squareSize) in cases {
            let shorthand = try MGRSFormatter.resolveGridReference(figures, relativeTo: anchor)
            let full = try MGRSFormatter.resolveGridReference(
                prefix + figures, relativeTo: .init(latitude: 0, longitude: 0))
            XCTAssertEqual(shorthand.figureCount, figures.count)
            XCTAssertEqual(shorthand.squareSizeMetres, squareSize)
            XCTAssertTrue(shorthand.usedLocalContext)
            XCTAssertFalse(full.usedLocalContext)
            XCTAssertEqual(shorthand.coordinate.latitude, full.coordinate.latitude, accuracy: 0.0000001)
            XCTAssertEqual(shorthand.coordinate.longitude, full.coordinate.longitude, accuracy: 0.0000001)

            let half = figures.count / 2
            let east = String(figures.prefix(half))
            let north = String(figures.suffix(half))
            let centredEast = half == 5 ? east : east + "5" + String(repeating: "0", count: 4 - half)
            let centredNorth = half == 5 ? north : north + "5" + String(repeating: "0", count: 4 - half)
            XCTAssertEqual(
                MGRSFormatter.string(from: shorthand.coordinate, spaced: false),
                prefix + centredEast + centredNorth,
                "\(figures) must resolve to the cell centre rather than its south-west corner"
            )
        }
    }

    func testGridResolverAllowsZeroZeroAsLegitimateLocalContext() throws {
        let anchor = CLLocationCoordinate2D(latitude: 0, longitude: 0)
        let resolved = try MGRSFormatter.resolveGridReference("1234", relativeTo: anchor)
        XCTAssertTrue(resolved.usedLocalContext)
        XCTAssertEqual(resolved.figureCount, 4)
        XCTAssertEqual(resolved.squareSizeMetres, 1_000)
    }

    func testGridResolverRejectsUnsupportedPrecisionAndUnavailableContext() {
        XCTAssertThrowsError(try MGRSFormatter.resolveGridReference(
            "12345", relativeTo: .init(latitude: -33, longitude: 151))) { error in
                XCTAssertEqual(error as? MGRSFormatter.GridReferenceError,
                               .unsupportedPrecision)
            }
        XCTAssertThrowsError(try MGRSFormatter.resolveGridReference(
            "1234", relativeTo: .init(latitude: 89, longitude: 0))) { error in
                XCTAssertEqual(error as? MGRSFormatter.GridReferenceError,
                               .unavailableLocalContext)
            }
        XCTAssertNil(MGRSFormatter.coordinate(from: "56HLH١٢٣٤"))
        XCTAssertThrowsError(try MGRSFormatter.resolveGridReference(
            "١٢٣٤", relativeTo: .init(latitude: -33, longitude: 151))) { error in
                XCTAssertEqual(error as? MGRSFormatter.GridReferenceError,
                               .invalidReference)
            }
    }

    func testGridResolverNormalizesLowercaseAndMixedWhitespace() throws {
        let anchor = CLLocationCoordinate2D(latitude: -33.8688, longitude: 151.2093)
        let prefix = String(MGRSFormatter.string(from: anchor, spaced: false).dropLast(10))
        let resolved = try MGRSFormatter.resolveGridReference(
            "\t\(prefix.lowercased()) 12\n34 ", relativeTo: anchor)
        XCTAssertEqual(resolved.formattedReference, "\(prefix) 12 34")
        XCTAssertEqual(resolved.squareSizeMetres, 1_000)
    }

    func testGridResolverAcceptsValidBoundaryCellCentres() throws {
        for reference in ["60EXU6744", "31XEP0028"] {
            let resolved = try MGRSFormatter.resolveGridReference(
                reference, relativeTo: .init(latitude: 0, longitude: 0))
            XCTAssertTrue((-90...90).contains(resolved.coordinate.latitude), reference)
            XCTAssertTrue((-180...180).contains(resolved.coordinate.longitude), reference)
            XCTAssertTrue(resolved.coordinate.latitude.isFinite, reference)
            XCTAssertTrue(resolved.coordinate.longitude.isFinite, reference)
        }
    }
}
