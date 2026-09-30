import CoreLocation
import Grid
import MapKit
import MGRS
import XCTest
@testable import TacticalMaps

/// Grid line labels vs testdata/mgrs_grid_labels.json (same vectors as
/// Android MgrsGridLabelsTest). The 1 km line at northing 87000 used to read
/// "86" because the label came from truncating a round-tripped 86999.99.
final class MGRSGridLabelTests: XCTestCase {
    private var cases: [[String: Any]] = []

    override func setUpWithError() throws {
        try super.setUpWithError()
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/mgrs_grid_labels.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let json = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any]
                )
                cases = try XCTUnwrap(json["cases"] as? [[String: Any]])
                return
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/mgrs_grid_labels.json")
    }

    func testListedLinesCarryTheirOwnValue() throws {
        for testCase in cases {
            let name = testCase["name"] as? String ?? "?"
            let labels = try build(testCase)
            for line in try XCTUnwrap(testCase["lines"] as? [[String: Any]]) {
                let type = try gridType(line["gridType"])
                let vertical = try XCTUnwrap(line["vertical"] as? Bool)
                let anchor = MGRS.parse(try XCTUnwrap(line["mgrs"] as? String)).toUTM()
                let lineValue = across(anchor, vertical)
                // Anchor is the lat/lon midpoint, which sags off a long line
                // (~150 m on 100 km), so match within 1/20 of a square.
                let onLine = labels.filter { label in
                    label.gridType == type && label.isVertical == vertical &&
                        abs(across(utm(label, zoneOf: anchor), vertical) - lineValue) < interval(type) / 20
                }
                let what = "\(name): \(type) \(vertical ? "E" : "N") \(lineValue)"
                XCTAssertFalse(onLine.isEmpty, "\(what) has no label")
                for label in onLine {
                    XCTAssertEqual(label.text, line["label"] as? String, what)
                }
            }
        }
    }

    func testEveryNumericLabelMatchesTheLineUnderIt() throws {
        for testCase in cases {
            let name = testCase["name"] as? String ?? "?"
            var checked = 0
            for label in try build(testCase)
            where label.gridType == .KILOMETER || label.gridType == .TEN_KILOMETER {
                let step = interval(label.gridType)
                let value = across(UTM.from(label.coordinate), label.isVertical)
                let nearest = (value / step).rounded()
                guard abs(value - nearest * step) <= step / 20 else { continue }
                let index = Int(nearest)
                let expected = label.gridType == .KILOMETER
                    ? String(format: "%02d", index % 100)
                    : String(index % 10)
                XCTAssertEqual(label.text, expected, "\(name): \(label.gridType) at \(value)")
                checked += 1
            }
            XCTAssertGreaterThan(checked, 0, "\(name): no numeric labels checked")
        }
    }

    private func build(_ testCase: [String: Any]) throws -> [MGRSGridRenderer.LabelMark] {
        let bounds = try XCTUnwrap(testCase["bounds"] as? [String: Double])
        let south = try XCTUnwrap(bounds["south"]), north = try XCTUnwrap(bounds["north"])
        let west = try XCTUnwrap(bounds["west"]), east = try XCTUnwrap(bounds["east"])
        let region = MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: (south + north) / 2, longitude: (west + east) / 2),
            span: MKCoordinateSpan(latitudeDelta: north - south, longitudeDelta: east - west)
        )
        let width = try XCTUnwrap(testCase["mapWidth"] as? Double)
        return MGRSGridRenderer.build(for: region, mapWidthPoints: CGFloat(width)).labels
    }

    private func gridType(_ value: Any?) throws -> GridType {
        switch try XCTUnwrap(value as? String) {
        case "HUNDRED_KILOMETER": return .HUNDRED_KILOMETER
        case "TEN_KILOMETER": return .TEN_KILOMETER
        case "KILOMETER": return .KILOMETER
        case let other: return try XCTUnwrap(nil as GridType?, "unknown grid type \(other)")
        }
    }

    private func interval(_ type: GridType) -> Double {
        switch type {
        case .HUNDRED_KILOMETER: return 100_000
        case .TEN_KILOMETER: return 10_000
        default: return 1_000
        }
    }

    private func utm(_ label: MGRSGridRenderer.LabelMark, zoneOf anchor: UTM) -> UTM {
        UTM.from(
            GridPoint.degrees(label.coordinate.longitude, label.coordinate.latitude),
            anchor.zone,
            anchor.hemisphere
        )
    }

    private func across(_ utm: UTM, _ vertical: Bool) -> Double {
        vertical ? utm.easting : utm.northing
    }
}
