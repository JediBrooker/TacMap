import XCTest
import CoreGraphics
@testable import TacticalMaps

/// Finishing a calibration reports why it can't fit (so the map can say so)
/// and refining an existing calibration shows how good the earlier fit was.
final class CalibrationSessionTests: XCTestCase {

    private func fiduciary(_ x: Double, _ y: Double, _ lat: Double, _ lon: Double) -> Fiduciary {
        Fiduciary(pdfX: x, pdfY: y, mgrs: "", latitude: lat, longitude: lon)
    }

    func testFitRMSNeedsThreePointsNotInALine() {
        XCTAssertNil(CalibrationSession.fitRMS(of: []))
        XCTAssertNil(CalibrationSession.fitRMS(of: [fiduciary(0, 0, 0, 0), fiduciary(100, 0, 0, 0.001)]))
        let inALine = [fiduciary(0, 0, 0, 0), fiduciary(100, 100, 0.001, 0.001), fiduciary(200, 200, 0.002, 0.002)]
        XCTAssertNil(CalibrationSession.fitRMS(of: inALine))
        let triangle = [fiduciary(0, 0, 0, 0), fiduciary(1000, 0, 0, 0.01), fiduciary(0, 1000, 0.01, 0)]
        XCTAssertEqual(try XCTUnwrap(CalibrationSession.fitRMS(of: triangle)), 0, accuracy: 1e-3)
    }

    func testFinishThrowsDegenerateForPointsInALine() {
        let session = CalibrationSession()
        // MGRS strings that parse; the PDF taps lie on one diagonal.
        let taps: [(CGPoint, String)] = [
            (CGPoint(x: 0, y: 0), "56HLH1000010000"),
            (CGPoint(x: 100, y: 100), "56HLH1100011000"),
            (CGPoint(x: 200, y: 200), "56HLH1200012000"),
        ]
        for (point, mgrs) in taps {
            session.recordTap(pdfPoint: point, screenPoint: point)
            XCTAssertTrue(session.confirmFiduciary(mgrs: mgrs))
        }
        XCTAssertTrue(session.canFinish)
        XCTAssertThrowsError(try session.finish()) { error in
            guard case AffineFitError.degenerate = error else {
                return XCTFail("Expected .degenerate, got \(error)")
            }
        }
        XCTAssertNil(session.lastFitRMSMetres)
    }

    func testFinishRecordsTheFitError() throws {
        let session = CalibrationSession()
        let taps: [(CGPoint, String)] = [
            (CGPoint(x: 0, y: 0), "56HLH1000010000"),
            (CGPoint(x: 1000, y: 0), "56HLH1100010000"),
            (CGPoint(x: 0, y: 1000), "56HLH1000011000"),
        ]
        for (point, mgrs) in taps {
            session.recordTap(pdfPoint: point, screenPoint: point)
            XCTAssertTrue(session.confirmFiduciary(mgrs: mgrs))
        }
        let result = try session.finish()
        XCTAssertEqual(session.lastFitRMSMetres, result.rmsMetres)
    }
}
