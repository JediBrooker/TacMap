import XCTest
import CoreGraphics
import CoreLocation
@testable import TacticalMaps

/// Fiduciary calibration is the highest-stakes math in the app - a sign
/// error here silently puts every overlay in the wrong place. Tests
/// generate fiduciaries from a known affine and check the fitter recovers
/// it. Also covers degenerate / too-few / inverse paths.
final class AffineFitterTests: XCTestCase {

    // Translation + scale + some rotation/shear so we exercise every
    // coefficient, not just the diagonal.
    private let known = AffineTransform2D(
        a: 0.0001, b: 0.00002, c: -122.5,
        d: -0.00003, e: 0.00009, f: 37.7
    )

    private func fiduciary(x: Double, y: Double, using t: AffineTransform2D) -> Fiduciary {
        let c = t.apply(CGPoint(x: x, y: y))
        return Fiduciary(pdfX: x, pdfY: y, mgrs: "",
                         latitude: c.latitude, longitude: c.longitude)
    }

    func testFit_recoversKnownTransform() throws {
        let fids = [
            fiduciary(x: 0,    y: 0,   using: known),
            fiduciary(x: 1000, y: 0,   using: known),
            fiduciary(x: 0,    y: 800, using: known),
            fiduciary(x: 1000, y: 800, using: known),
        ]
        let result = try AffineFitter.fit(fids)
        XCTAssertEqual(result.transform.a, known.a, accuracy: 1e-9)
        XCTAssertEqual(result.transform.b, known.b, accuracy: 1e-9)
        XCTAssertEqual(result.transform.c, known.c, accuracy: 1e-5)
        XCTAssertEqual(result.transform.d, known.d, accuracy: 1e-9)
        XCTAssertEqual(result.transform.e, known.e, accuracy: 1e-9)
        XCTAssertEqual(result.transform.f, known.f, accuracy: 1e-5)
        // Fiduciaries lie exactly on the affine, so residual should be ~0.
        XCTAssertLessThan(result.rmsMetres, 1e-4)
    }

    func testFit_overDeterminedReportsNonNegativeRMS() throws {
        var fids = [
            fiduciary(x: 0,    y: 0,   using: known),
            fiduciary(x: 1000, y: 0,   using: known),
            fiduciary(x: 0,    y: 800, using: known),
        ]
        // A 4th point nudged ~1 m off the model so the fit can't be exact.
        var noisy = fiduciary(x: 500, y: 400, using: known)
        noisy = Fiduciary(pdfX: noisy.pdfX, pdfY: noisy.pdfY, mgrs: "",
                          latitude: noisy.latitude + 0.00001, longitude: noisy.longitude)
        fids.append(noisy)
        let result = try AffineFitter.fit(fids)
        XCTAssertGreaterThan(result.rmsMetres, 0)
        XCTAssertTrue(result.rmsMetres.isFinite)
    }

    func testFit_colinearThrowsDegenerate() {
        let fids = [
            Fiduciary(pdfX: 0,   pdfY: 0,   mgrs: "", latitude: 0, longitude: 0),
            Fiduciary(pdfX: 100, pdfY: 100, mgrs: "", latitude: 1, longitude: 1),
            Fiduciary(pdfX: 200, pdfY: 200, mgrs: "", latitude: 2, longitude: 2),
        ]
        XCTAssertThrowsError(try AffineFitter.fit(fids)) { error in
            guard case AffineFitError.degenerate = error else {
                return XCTFail("expected .degenerate, got \(error)")
            }
        }
    }

    func testFit_nearColinearThrowsDegenerate() {
        // Nearly colinear at realistic pixel scale. The old absolute-determinant
        // guard let these through b/c the normal-equations determinant is huge at
        // pixel magnitudes, but the perpendicular direction is basically
        // unconstrained. Scale-invariant guard should reject.
        let fids = [
            Fiduciary(pdfX: 0,    pdfY: 0, mgrs: "", latitude: 0,    longitude: 0),
            Fiduciary(pdfX: 1000, pdfY: 1, mgrs: "", latitude: 0.01, longitude: 1),
            Fiduciary(pdfX: 2000, pdfY: 0, mgrs: "", latitude: 0,    longitude: 2),
        ]
        XCTAssertThrowsError(try AffineFitter.fit(fids)) { error in
            guard case AffineFitError.degenerate = error else {
                return XCTFail("expected .degenerate for near-colinear points, got \(error)")
            }
        }
    }

    func testFit_threePointsNotCrossValidated_fourAre() throws {
        let three = [
            fiduciary(x: 0,    y: 0,   using: known),
            fiduciary(x: 1000, y: 0,   using: known),
            fiduciary(x: 0,    y: 800, using: known),
        ]
        XCTAssertFalse(try AffineFitter.fit(three).crossValidated,
                       "a 3-point fit is exact, RMS is not evidence of accuracy")
        let four = three + [fiduciary(x: 1000, y: 800, using: known)]
        XCTAssertTrue(try AffineFitter.fit(four).crossValidated)
    }

    func testFit_tooFewFiduciariesThrows() {
        let fids = [
            Fiduciary(pdfX: 0, pdfY: 0, mgrs: "", latitude: 0, longitude: 0),
            Fiduciary(pdfX: 1, pdfY: 1, mgrs: "", latitude: 1, longitude: 1),
        ]
        XCTAssertThrowsError(try AffineFitter.fit(fids)) { error in
            guard case AffineFitError.tooFewFiduciaries = error else {
                return XCTFail("expected .tooFewFiduciaries, got \(error)")
            }
        }
    }

    // MARK: AffineTransform2D.inverted()

    func testInverted_roundTripsPoint() throws {
        let inv = try XCTUnwrap(known.inverted())
        let p = CGPoint(x: 321, y: 654)
        let fwd = known.apply(p)                       // (lon, lat)
        let back = inv.apply(CGPoint(x: fwd.longitude, y: fwd.latitude))
        XCTAssertEqual(back.longitude, Double(p.x), accuracy: 1e-6)
        XCTAssertEqual(back.latitude,  Double(p.y), accuracy: 1e-6)
    }

    func testInverted_singularReturnsNil() {
        let singular = AffineTransform2D(a: 0, b: 0, c: 1, d: 0, e: 0, f: 2)
        XCTAssertNil(singular.inverted())
    }

    func testFit_rejectsMaliciousNonFiniteAndExtremeControlPoints() {
        let safe = [
            fiduciary(x: 0, y: 0, using: known),
            fiduciary(x: 1000, y: 0, using: known),
            fiduciary(x: 0, y: 800, using: known),
        ]
        var nan = safe
        nan[0].pdfX = .nan
        var extreme = safe
        extreme[0].pdfY = .greatestFiniteMagnitude
        var infinite = safe
        infinite[0].latitude = .infinity
        var offEarth = safe
        offEarth[0].longitude = 181

        for controls in [nan, extreme, infinite, offEarth] {
            XCTAssertThrowsError(try AffineFitter.fit(controls)) { error in
                guard case AffineFitError.invalidInput = error else {
                    return XCTFail("expected .invalidInput, got \(error)")
                }
            }
        }
    }

    func testNonFiniteOrOverflowingTransformsCannotBeInvertedOrPublished() {
        let bounds = GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: -34, longitude: 150),
            northEast: CLLocationCoordinate2D(latitude: -33, longitude: 151),
            pdfCropRect: CGRect(x: 0, y: 0, width: 600, height: 400)
        )
        let source = PDFMapSource(
            url: FileManager.default.temporaryDirectory.appendingPathComponent("malicious.pdf"),
            bounds: bounds,
            preflightMediaBox: CGRect(x: 0, y: 0, width: 600, height: 400)
        )
        let controls = [
            Fiduciary(pdfX: 0, pdfY: 0, mgrs: "a", latitude: -34, longitude: 150),
            Fiduciary(pdfX: 600, pdfY: 0, mgrs: "b", latitude: -34, longitude: 151),
            Fiduciary(pdfX: 0, pdfY: 400, mgrs: "c", latitude: -33, longitude: 150),
        ]
        let nan = AffineTransform2D(a: .nan, b: 0, c: 0, d: 0, e: 1, f: 0)
        let overflow = AffineTransform2D(a: .greatestFiniteMagnitude, b: 0, c: 0,
                                         d: 0, e: .greatestFiniteMagnitude, f: 0)

        XCTAssertNil(nan.inverted())
        XCTAssertNil(overflow.inverted())
        source.applyCalibration(transform: nan, fiduciaries: controls)
        XCTAssertNil(source.calibration)
        source.applyCalibration(transform: overflow, fiduciaries: controls)
        XCTAssertNil(source.calibration)
        XCTAssertEqual(source.bounds, bounds, "failed publication must leave prior bounds intact")
    }

    func testPDFOverlayPlacementPreservesAFullShearedAffineBasis() throws {
        let topLeft = CGPoint(x: 20, y: 30)
        let topRight = CGPoint(x: 140, y: 60)
        let bottomLeft = CGPoint(x: 70, y: 190)
        let bottomRight = CGPoint(x: 190, y: 220)
        let placement = try XCTUnwrap(pdfAffineOverlayPlacement(
            topLeft: topLeft,
            topRight: topRight,
            bottomLeft: bottomLeft,
            bottomRight: bottomRight
        ))

        func mapped(_ local: CGPoint) -> CGPoint {
            let relative = CGPoint(
                x: local.x - placement.boundsSize.width / 2,
                y: local.y - placement.boundsSize.height / 2
            ).applying(placement.transform)
            return CGPoint(x: placement.center.x + relative.x,
                           y: placement.center.y + relative.y)
        }

        let mappedTopLeft = mapped(.zero)
        let mappedTopRight = mapped(CGPoint(x: placement.boundsSize.width, y: 0))
        let mappedBottomLeft = mapped(CGPoint(x: 0, y: placement.boundsSize.height))
        let mappedBottomRight = mapped(CGPoint(
            x: placement.boundsSize.width,
            y: placement.boundsSize.height
        ))
        XCTAssertEqual(mappedTopLeft.x, topLeft.x, accuracy: 1e-10)
        XCTAssertEqual(mappedTopLeft.y, topLeft.y, accuracy: 1e-10)
        XCTAssertEqual(mappedTopRight.x, topRight.x, accuracy: 1e-10)
        XCTAssertEqual(mappedTopRight.y, topRight.y, accuracy: 1e-10)
        XCTAssertEqual(mappedBottomLeft.x, bottomLeft.x, accuracy: 1e-10)
        XCTAssertEqual(mappedBottomLeft.y, bottomLeft.y, accuracy: 1e-10)
        XCTAssertEqual(mappedBottomRight.x, bottomRight.x, accuracy: 1e-10)
        XCTAssertEqual(mappedBottomRight.y, bottomRight.y, accuracy: 1e-10)

        // A rotation-only placement forces the down axis perpendicular to the
        // right axis. This fixture deliberately is not perpendicular, proving
        // the regression exercises shear rather than just scale + rotation.
        let right = CGVector(dx: topRight.x - topLeft.x, dy: topRight.y - topLeft.y)
        let down = CGVector(dx: bottomLeft.x - topLeft.x, dy: bottomLeft.y - topLeft.y)
        XCTAssertNotEqual(right.dx * down.dx + right.dy * down.dy, 0, accuracy: 1e-10)
    }

    func testPDFOverlayPlacementRejectsNonFiniteAndCollapsedGeometry() {
        XCTAssertNil(pdfAffineOverlayPlacement(
            topLeft: CGPoint(x: CGFloat.nan, y: 0),
            topRight: CGPoint(x: 100, y: 0),
            bottomLeft: CGPoint(x: 0, y: 100),
            bottomRight: CGPoint(x: 100, y: 100)
        ))
        XCTAssertNil(pdfAffineOverlayPlacement(
            topLeft: .zero,
            topRight: CGPoint(x: 100, y: 0),
            bottomLeft: CGPoint(x: 200, y: 0),
            bottomRight: CGPoint(x: 300, y: 0)
        ))
    }
}
