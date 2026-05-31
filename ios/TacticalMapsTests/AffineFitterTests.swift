import XCTest
import CoreGraphics
@testable import TacticalMaps

/// The fiduciary calibration solve is the highest-stakes math in the app:
/// a sign error here silently puts every overlay in the wrong place. These
/// tests generate fiduciaries from a *known* affine and assert the fitter
/// recovers it, plus cover the degenerate / too-few / inverse paths.
final class AffineFitterTests: XCTestCase {

    /// Translation + scale + a little rotation/shear so every coefficient is
    /// exercised (not just the diagonal).
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
        // Fiduciaries lie exactly on the affine → residual ~0.
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
}
