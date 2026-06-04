import XCTest
import Foundation
@testable import TacticalMaps

/// GeoJSON is the canonical export format and the only thing that leaves the
/// app. These tests pin the FeatureCollection shape: `[lon, lat]` ordering,
/// the geometry types, and the implicit polygon ring closure.
final class GeoJSONExporterTests: XCTestCase {

    private func parse(_ json: String) throws -> [String: Any] {
        let data = try XCTUnwrap(json.data(using: .utf8))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func nums(_ any: Any?) -> [Double] {
        ((any as? [Any]) ?? []).compactMap { ($0 as? NSNumber)?.doubleValue }
    }

    private func numPairs(_ any: Any?) -> [[Double]] {
        ((any as? [Any]) ?? []).map { nums($0) }
    }

    private func assertCoord(_ actual: [Double], _ expected: [Double],
                             accuracy: Double = 1e-9, _ msg: String = "") {
        XCTAssertEqual(actual.count, expected.count, msg)
        for (a, e) in zip(actual, expected) { XCTAssertEqual(a, e, accuracy: accuracy, msg) }
    }

    func testExport_featureCollectionStructure() throws {
        let wp = Waypoint(name: "OP North",
                          latitude: 37.7749, longitude: -122.4194,
                          elevation: 120, kind: .generic)
        let line = DrawingShape(kind: .polyline,
                                coordinates: [Coordinate2D(latitude: 1, longitude: 2),
                                              Coordinate2D(latitude: 3, longitude: 4)])
        let poly = DrawingShape(kind: .polygon,
                                coordinates: [Coordinate2D(latitude: 0, longitude: 0),
                                              Coordinate2D(latitude: 0, longitude: 1),
                                              Coordinate2D(latitude: 1, longitude: 1)])

        let json = try GeoJSONExporter.export(waypoints: [wp], drawings: [line, poly])
        let root = try parse(json)

        XCTAssertEqual(root["type"] as? String, "FeatureCollection")
        XCTAssertTrue((root["generator"] as? String ?? "").contains("TacticalMaps iOS prototype"))

        let features = try XCTUnwrap(root["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 3)

        // Waypoint → Point with [lon, lat] ordering.
        let wpGeom = try XCTUnwrap(features[0]["geometry"] as? [String: Any])
        XCTAssertEqual(wpGeom["type"] as? String, "Point")
        assertCoord(nums(wpGeom["coordinates"]), [-122.4194, 37.7749], accuracy: 1e-7)

        // Polyline → LineString, vertices in [lon, lat] order.
        let lineGeom = try XCTUnwrap(features[1]["geometry"] as? [String: Any])
        XCTAssertEqual(lineGeom["type"] as? String, "LineString")
        let lineCoords = numPairs(lineGeom["coordinates"])
        XCTAssertEqual(lineCoords.count, 2)
        assertCoord(lineCoords[0], [2, 1])
        assertCoord(lineCoords[1], [4, 3])

        // Polygon → single ring, closed implicitly (first == last).
        let polyGeom = try XCTUnwrap(features[2]["geometry"] as? [String: Any])
        XCTAssertEqual(polyGeom["type"] as? String, "Polygon")
        let rings = polyGeom["coordinates"] as? [Any]
        let ring = numPairs(rings?.first)
        XCTAssertEqual(ring.count, 4, "3 vertices + 1 closing point")
        assertCoord(ring.first ?? [], ring.last ?? [])
    }

    func testExportImport_roundTripsTacticalWaypointSchema() throws {
        let layerID = try XCTUnwrap(UUID(uuidString: "11111111-1111-1111-1111-111111111111"))
        let layer = DrawingLayer(id: layerID, name: "Alpha", defaultColorHex: "#123456")
        let militaryID = try XCTUnwrap(UUID(uuidString: "22222222-2222-2222-2222-222222222222"))
        let controlID = try XCTUnwrap(UUID(uuidString: "33333333-3333-3333-3333-333333333333"))
        let military = Waypoint(
            id: militaryID,
            name: "HQ",
            notes: "watch",
            latitude: -33.86,
            longitude: 151.21,
            elevation: 42,
            kind: .military(MilitarySymbolSpec(
                affiliation: .hostile,
                echelon: .battalionRegiment,
                function: .airDefence,
                isHeadquarters: true
            )),
            layerID: layerID
        )
        let control = Waypoint(
            id: controlID,
            name: "Attack axis",
            latitude: -33.87,
            longitude: 151.22,
            kind: .controlMeasure(.axisOfMainAttack),
            rotation: 42,
            scaleX: 2.5,
            scaleY: 0.75,
            layerID: layerID
        )

        let json = try GeoJSONExporter.export(waypoints: [military, control], layers: [layer])
        let data = try XCTUnwrap(json.data(using: .utf8))
        let parsed = try GeoJSONImporter.parse(
            data,
            existingLayers: [],
            fallbackLayerID: DrawingLayer.legacyFallbackID
        )

        XCTAssertEqual(parsed.newLayers.count, 1)
        XCTAssertEqual(parsed.newLayers.first?.id, layerID)
        XCTAssertEqual(parsed.newLayers.first?.name, "Alpha")
        XCTAssertEqual(parsed.newLayers.first?.defaultColorHex, "#123456")

        let importedMilitary = try XCTUnwrap(parsed.waypoints.first { $0.id == militaryID })
        XCTAssertEqual(importedMilitary.layerID, layerID)
        XCTAssertEqual(importedMilitary.notes, "watch")
        XCTAssertEqual(importedMilitary.elevation, 42)
        guard case .military(let spec) = importedMilitary.kind else {
            return XCTFail("Expected military waypoint")
        }
        XCTAssertEqual(spec.affiliation, .hostile)
        XCTAssertEqual(spec.echelon, .battalionRegiment)
        XCTAssertEqual(spec.function, .airDefence)
        XCTAssertTrue(spec.isHeadquarters)

        let importedControl = try XCTUnwrap(parsed.waypoints.first { $0.id == controlID })
        XCTAssertEqual(importedControl.layerID, layerID)
        guard case .controlMeasure(let measure) = importedControl.kind else {
            return XCTFail("Expected control measure waypoint")
        }
        XCTAssertEqual(measure, .axisOfMainAttack)
        XCTAssertEqual(importedControl.rotation, 42, accuracy: 1e-9)
        XCTAssertEqual(importedControl.scaleX, 2.5, accuracy: 1e-9)
        XCTAssertEqual(importedControl.scaleY, 0.75, accuracy: 1e-9)
    }
}
