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
    private var placementViews: [[String: Any]] = []

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
                placementViews = try XCTUnwrap(json["placement"] as? [[String: Any]])
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

    func testOneLabelPerVisibleLineOnTheColumnAndRow() throws {
        XCTAssertFalse(placementViews.isEmpty)
        for view in placementViews {
            let name = view["name"] as? String ?? "?"
            let centre = try XCTUnwrap(MGRSFormatter.coordinate(from: try XCTUnwrap(view["centre"] as? String)))
            let mpp = try XCTUnwrap(view["metresPerPoint"] as? Double)
            let heading = try XCTUnwrap(view["heading"] as? Double) * .pi / 180
            let width = try XCTUnwrap(view["width"] as? Double)
            let height = try XCTUnwrap(view["height"] as? Double)
            let cosLat = cos(centre.latitude * .pi / 180)
            let project: (CLLocationCoordinate2D) -> CGPoint = { c in
                let dx = (c.longitude - centre.longitude) * cosLat * 111_320
                let dy = (c.latitude - centre.latitude) * 110_574
                let rx = dx * cos(heading) - dy * sin(heading)
                let ry = dx * sin(heading) + dy * cos(heading)
                return CGPoint(x: width / 2 + rx / mpp, y: height / 2 - ry / mpp)
            }
            // Same heading-proof square the map hosts build for.
            let diagonal = hypot(width, height)
            let half = diagonal / 2 * mpp
            let region = MKCoordinateRegion(
                center: centre,
                span: MKCoordinateSpan(latitudeDelta: 2 * half / 110_574,
                                       longitudeDelta: 2 * half / (111_320 * cosLat))
            )
            let labels = MGRSGridRenderer.build(for: region, mapWidthPoints: CGFloat(diagonal)).labels

            let placed = MGRSGridRenderer.placeLabels(labels, in: CGSize(width: width, height: height), project: project)
            let column = placed.filter { !$0.runsUpDown }.sorted { $0.point.y < $1.point.y }
            let row = placed.filter { $0.runsUpDown }.sorted { $0.point.x < $1.point.x }
            XCTAssertEqual(column.map(\.mark.text), view["column"] as? [String], "\(name) column")
            XCTAssertEqual(row.map(\.mark.text), view["row"] as? [String], "\(name) row")
            for label in column {
                XCTAssertEqual(Double(label.point.x), width * Double(MGRSGridRenderer.labelColumnFraction), accuracy: 0.01, name)
                XCTAssertTrue(
                    (Double(MGRSGridRenderer.labelTopInset)...(height - Double(MGRSGridRenderer.labelBottomInset)))
                        .contains(Double(label.point.y)),
                    "\(name) \(label.mark.text)"
                )
            }
            for label in row {
                XCTAssertEqual(Double(label.point.y), height * Double(MGRSGridRenderer.labelRowFraction), accuracy: 0.01, name)
                XCTAssertTrue((12...(width - 12)).contains(Double(label.point.x)), "\(name) \(label.mark.text)")
            }
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
