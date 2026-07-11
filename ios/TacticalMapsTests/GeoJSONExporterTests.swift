import XCTest
import Foundation
@testable import TacticalMaps

/// GeoJSON is the only export format that leaves the app. Tests pin the
/// FeatureCollection shape - [lon, lat] ordering, geometry types, and
/// implicit polygon ring closure.
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
        // generator is the fixed string "TacMap" - no platform/version leak (F10).
        XCTAssertEqual(root["generator"] as? String, "TacMap")

        let features = try XCTUnwrap(root["features"] as? [[String: Any]])
        XCTAssertEqual(features.count, 3)

        // Waypoint becomes a Point with [lon, lat] ordering.
        let wpGeom = try XCTUnwrap(features[0]["geometry"] as? [String: Any])
        XCTAssertEqual(wpGeom["type"] as? String, "Point")
        assertCoord(nums(wpGeom["coordinates"]), [-122.4194, 37.7749], accuracy: 1e-7)

        // Polyline to LineString, vertices in [lon, lat] order.
        let lineGeom = try XCTUnwrap(features[1]["geometry"] as? [String: Any])
        XCTAssertEqual(lineGeom["type"] as? String, "LineString")
        let lineCoords = numPairs(lineGeom["coordinates"])
        XCTAssertEqual(lineCoords.count, 2)
        assertCoord(lineCoords[0], [2, 1])
        assertCoord(lineCoords[1], [4, 3])

        // Polygon to single ring, closed implicitly (first == last).
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

    /// Per-object export must be deterministic, no wall-clock generated_at.
    func testExport_isDeterministic() throws {
        let wp = Waypoint(name: "A", latitude: 1, longitude: 2, kind: .generic)
        let e1 = try GeoJSONExporter.export(waypoints: [wp])
        let e2 = try GeoJSONExporter.export(waypoints: [wp])
        XCTAssertEqual(e1, e2)
    }

    // Strongest parity gaurantee: export, import, export again = byte-identical.
    // Proves determinism, baked geometry w/ identity transform, lowercase UUID
    // ids, and style / dash / task-colour / created_at all round-trip.
    func testRoundTrip_exportImportExportIsIdempotent() throws {
        let layer = DrawingLayer(id: UUID(uuidString: "AABBCCDD-1111-2222-3333-444455556666")!,
                                 name: "Alpha", defaultColorHex: "#112233")
        var style = DrawingStyle()
        style.strokeColorHex = "#00FF00"
        style.fillColorHex = "#00FF00"
        style.fillOpacity = 0.5
        style.dashPattern = [8, 4]
        let poly = DrawingShape(
            name: "AO",
            notes: "watch the tree line",
            kind: .polygon,
            coordinates: [Coordinate2D(latitude: -33, longitude: 151),
                          Coordinate2D(latitude: -33, longitude: 151.1),
                          Coordinate2D(latitude: -33.1, longitude: 151.1)],
            style: style,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            layerID: layer.id, rotation: 30, scaleX: 2, scaleY: 1.5)
        let wp = Waypoint(name: "Axis", latitude: -33.5, longitude: 151.5,
                          kind: .controlMeasure(.axisOfMainAttack),
                          taskColor: .blue, layerID: layer.id,
                          createdAt: Date(timeIntervalSince1970: 1_700_000_000))

        let export1 = try GeoJSONExporter.export(waypoints: [wp], drawings: [poly], layers: [layer])
        let data1 = try XCTUnwrap(export1.data(using: .utf8))
        let reimported = try GeoJSONImporter.parse(data1, existingLayers: [], fallbackLayerID: layer.id)
        XCTAssertEqual(reimported.waypoints.first?.taskColor, .blue)
        XCTAssertNotNil(reimported.drawings.first?.style.dashPattern)
        let export2 = try GeoJSONExporter.export(waypoints: reimported.waypoints,
                                                 drawings: reimported.drawings,
                                                 layers: reimported.newLayers)
        XCTAssertEqual(export1, export2)
    }

    /// Android-format drawing (legacy `stroke_color`/`fill_color` ARGB keys)
    /// keeps its styling when imported here.
    func testImport_androidStyleDrawingPreservesStyle() throws {
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"aabbccdd-1111-2222-3333-444455556666",
           "geometry":{"type":"Polygon","coordinates":[[[151.0,-33.0],[151.1,-33.0],[151.1,-33.1],[151.0,-33.0]]]},
           "properties":{"tacticalmaps:category":"drawing","stroke_color":"#FF00FF00",
             "fill_color":"#8000FF00","stroke_width":6,"stroke_style":"dashed"}}]}
        """
        let data = try XCTUnwrap(json.data(using: .utf8))
        let r = try GeoJSONImporter.parse(data, existingLayers: [], fallbackLayerID: UUID())
        let d = try XCTUnwrap(r.drawings.first)
        XCTAssertEqual(d.style.strokeColorHex.uppercased(), "#00FF00") // #FF00FF00 → drop alpha
        XCTAssertEqual(d.style.strokeWidth, 6)
        XCTAssertNotNil(d.style.dashPattern) // "dashed"
    }

    /// Android's ISO_INSTANT emits fractional seconds; the iOS importer must
    /// parse them, not reset createdAt to import time (which would churn sync).
    func testImport_parsesFractionalSecondCreatedAt() throws {
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"aabbccdd-1111-2222-3333-444455556666",
           "geometry":{"type":"Point","coordinates":[151,-33]},
           "properties":{"tacticalmaps:category":"generic","tacticalmaps:created_at":"2023-11-14T22:13:20.123Z"}}]}
        """
        let data = try XCTUnwrap(json.data(using: .utf8))
        let r = try GeoJSONImporter.parse(data, existingLayers: [], fallbackLayerID: UUID())
        let wp = try XCTUnwrap(r.waypoints.first)
        XCTAssertEqual(wp.createdAt.timeIntervalSince1970, 1_700_000_000.123, accuracy: 0.01)
    }

    func testImport_skipsInvalidCoordinates() throws {
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"a","geometry":{"type":"Point","coordinates":[999,10]},"properties":{"tacticalmaps:category":"generic"}},
          {"type":"Feature","id":"b","geometry":{"type":"Point","coordinates":[151,-33]},"properties":{"tacticalmaps:category":"generic"}}]}
        """
        let data = try XCTUnwrap(json.data(using: .utf8))
        let r = try GeoJSONImporter.parse(data, existingLayers: [], fallbackLayerID: UUID())
        XCTAssertEqual(r.waypoints.count, 1)
        XCTAssertEqual(r.invalidSkipped, 1)
    }
}
