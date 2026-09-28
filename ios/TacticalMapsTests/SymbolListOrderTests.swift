import XCTest
import CoreLocation
@testable import TacticalMaps

/// Pins Symbology list grouping and ordering to testdata/symbol_list_order.json,
/// the same fixture the Android suite reads.
final class SymbolListOrderTests: XCTestCase {
    private var fixture: [String: Any]!

    override func setUpWithError() throws {
        try super.setUpWithError()
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("testdata/symbol_list_order.json")
            if FileManager.default.fileExists(atPath: candidate.path) {
                fixture = try XCTUnwrap(
                    JSONSerialization.jsonObject(with: Data(contentsOf: candidate)) as? [String: Any]
                )
                return
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/symbol_list_order.json")
    }

    private var reference: CLLocationCoordinate2D {
        let ref = fixture["reference"] as! [String: Any]
        return CLLocationCoordinate2D(latitude: (ref["lat"] as! NSNumber).doubleValue,
                                      longitude: (ref["lon"] as! NSNumber).doubleValue)
    }

    private var waypoints: [Waypoint] {
        (fixture["waypoints"] as! [[String: Any]]).map { json in
            let kind: WaypointKind
            switch json["kind"] as! String {
            case "military":
                let affiliation = SymbolAffiliation(rawValue: json["affiliation"] as! String)!
                kind = .military(MilitarySymbolSpec(affiliation: affiliation, echelon: .platoon))
            case "controlMeasure":
                kind = .controlMeasure(.block)
            default:
                kind = .generic
            }
            return Waypoint(
                id: UUID(uuidString: json["id"] as! String)!,
                name: json["name"] as! String,
                latitude: (json["lat"] as! NSNumber).doubleValue,
                longitude: (json["lon"] as! NSNumber).doubleValue,
                kind: kind,
                taskColor: (json["taskColor"] as? String).flatMap(TaskColor.init(rawValue:)) ?? .black,
                layerID: UUID(uuidString: json["layerID"] as! String)!,
                createdAt: Date(timeIntervalSince1970: (json["createdAtMs"] as! NSNumber).doubleValue / 1000)
            )
        }
    }

    func testEveryOrderMatchesSharedFixture() {
        let layerOrder = (fixture["layers"] as! [[String: Any]]).map { UUID(uuidString: $0["id"] as! String)! }
        let expected = fixture["expected"] as! [String: [[String: Any]]]
        for order in SymbolListOrder.allCases {
            let actual = SymbolListSorter.sections(waypoints, order: order,
                                                   layerOrder: layerOrder, reference: reference)
            let sections = expected[order.rawValue]!
            XCTAssertEqual(actual.count, sections.count, order.rawValue)
            for (want, got) in zip(sections, actual) {
                XCTAssertEqual(got.group, group(want["group"] as? String, order: order), order.rawValue)
                XCTAssertEqual(got.waypoints.map { $0.id.uuidString.lowercased() },
                               want["ids"] as! [String], order.rawValue)
            }
        }
    }

    func testHaversineDistancesMatchSharedFixture() {
        let distances = fixture["distanceMetres"] as! [String: NSNumber]
        for waypoint in waypoints {
            let metres = SymbolListSorter.distanceMetres(from: reference, to: waypoint.coordinate)
            XCTAssertEqual(metres, distances[waypoint.id.uuidString.lowercased()]!.doubleValue,
                           accuracy: 0.01, waypoint.name)
        }
    }

    func testNaturalNameOrderIsNumericAndCaseAndAccentInsensitive() {
        for pair in fixture["naturalNameOrder"] as! [[String: String]] {
            let lesser = pair["lesser"]!, greater = pair["greater"]!
            XCTAssertEqual(SymbolListSorter.naturalOrder(lesser, greater), .orderedAscending, "\(lesser) < \(greater)")
            XCTAssertEqual(SymbolListSorter.naturalOrder(greater, lesser), .orderedDescending, "\(greater) > \(lesser)")
        }
        for pair in fixture["naturalNameEqual"] as! [[String]] {
            XCTAssertEqual(SymbolListSorter.naturalOrder(pair[0], pair[1]), .orderedSame, "\(pair[0]) == \(pair[1])")
        }
    }

    func testUnknownStoredOrderFallsBackToNewest() {
        XCTAssertEqual(SymbolListOrder.stored("sideways"), .newest)
        XCTAssertEqual(SymbolListOrder.stored("layer"), .layer)
    }

    private func group(_ value: String?, order: SymbolListOrder) -> SymbolListGroup {
        guard let value else { return .all }
        if order == .affiliation { return .affiliation(SymbolListAffiliation(rawValue: value)!) }
        if value == "other" { return .otherLayer }
        return .layer(UUID(uuidString: value)!)
    }
}
