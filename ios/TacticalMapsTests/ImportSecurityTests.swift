import XCTest
@testable import TacticalMaps

final class ImportSecurityTests: XCTestCase {
    private final class ScopeProbe {
        var active = false
        var parseObserved = false
    }

    private let fallback = UUID()

    func testGeoJSONRejectsFeatureBomb() throws {
        let feature: [String: Any] = [
            "type": "Feature",
            "geometry": ["type": "Point", "coordinates": [151.0, -33.0]],
            "properties": [:]
        ]
        let object: [String: Any] = [
            "type": "FeatureCollection",
            "features": Array(repeating: feature, count: 10_001)
        ]
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try GeoJSONImporter.parse(
            data, existingLayers: [], fallbackLayerID: fallback
        )) { error in
            guard case GeoJSONImporter.ImportError.limitExceeded = error else {
                return XCTFail("expected feature limit, got \(error)")
            }
        }
    }

    func testGeoJSONRejectsDeepNesting() throws {
        var nested: Any = "x"
        for _ in 0..<70 { nested = [nested] }
        let object: [String: Any] = [
            "type": "FeatureCollection",
            "features": [],
            "extra": nested
        ]
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertThrowsError(try GeoJSONImporter.parse(
            data, existingLayers: [], fallbackLayerID: fallback
        ))
    }

    func testKMLRejectsCoordinateBomb() throws {
        let tuple = "151.0,-33.0 "
        let coordinates = String(repeating: tuple, count: 100_001)
        let xml = "<kml><Placemark><LineString><coordinates>\(coordinates)</coordinates></LineString></Placemark></kml>"
        XCTAssertThrowsError(try KMLImporter.parse(
            Data(xml.utf8), existingLayers: [], fallbackLayerID: fallback
        )) { error in
            guard case KMLImporter.ImportError.limitExceeded = error else {
                return XCTFail("expected coordinate limit, got \(error)")
            }
        }
    }

    func testKMLSkipsNonFiniteAndOutOfRangeCoordinates() throws {
        let xml = """
        <kml><Placemark><Point><coordinates>151,-33</coordinates></Point></Placemark>
        <Placemark><Point><coordinates>999,95</coordinates></Point></Placemark></kml>
        """
        let result = try KMLImporter.parse(
            Data(xml.utf8), existingLayers: [], fallbackLayerID: fallback
        )
        XCTAssertEqual(result.waypoints.count, 1)
    }

    func testGeoJSONRejectsNonFiniteAndExtremePresentationNumbers() throws {
        let json = #"{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[151,-33]},"properties":{"tacticalmaps:category":"generic","tacticalmaps:scale_x":"nan","tacticalmaps:scale_y":"1e999","tacticalmaps:rotation_deg":"inf","tacticalmaps:elevation_m":"nan"}},{"type":"Feature","geometry":{"type":"LineString","coordinates":[[151,-33],[151.1,-33.1]]},"properties":{"tacticalmaps:category":"drawing","stroke-width":"nan","fill-opacity":"99"}}]}"#
        let result = try GeoJSONImporter.parse(
            Data(json.utf8), existingLayers: [], fallbackLayerID: fallback
        )
        let waypoint = try XCTUnwrap(result.waypoints.first)
        XCTAssertEqual(waypoint.scaleX, 1)
        XCTAssertEqual(waypoint.scaleY, 1)
        XCTAssertEqual(waypoint.rotation, 0)
        XCTAssertNil(waypoint.elevation)
        let drawing = try XCTUnwrap(result.drawings.first)
        XCTAssertTrue(drawing.style.strokeWidth.isFinite)
        XCTAssertTrue((0...1).contains(drawing.style.fillOpacity))
    }

    func testExternalGeoJSONRemintsCollisionButTrustedParserPreservesAuthenticatedID() throws {
        let occupiedDrawingID = UUID()
        let remintedID = UUID()
        let existingLayer = DrawingLayer(name: "Same name", defaultColorHex: "#000000")
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"\(occupiedDrawingID.uuidString)",
           "geometry":{"type":"Point","coordinates":[151,-33]},
           "properties":{"tacticalmaps:category":"generic",
                         "tacticalmaps:layer_id":"\(occupiedDrawingID.uuidString)",
                         "tacticalmaps:layer":"Same name"}}
        ]}
        """
        let data = Data(json.utf8)

        let trusted = try GeoJSONImporter.parse(
            data, existingLayers: [existingLayer], fallbackLayerID: fallback
        )
        XCTAssertEqual(trusted.waypoints.first?.id, occupiedDrawingID,
                       "Sync's authenticated parser must not remint embedded IDs")

        let external = try GeoJSONImporter.parseExternal(
            data,
            existingLayers: [existingLayer],
            fallbackLayerID: fallback,
            existingWaypointIDs: [],
            existingDrawingIDs: [occupiedDrawingID],
            batchKey: "external",
            idFactory: { remintedID }
        )
        XCTAssertEqual(external.result.waypoints.first?.id, remintedID)
        XCTAssertEqual(external.identityResolutions.first?.resolution, .existingCrossTypeCollision)
        XCTAssertEqual(external.result.waypoints.first?.layerID, occupiedDrawingID,
                       "valid external layer UUIDs are a separate namespace and stay unchanged")
        XCTAssertEqual(external.result.newLayers.first?.id, occupiedDrawingID)
    }

    func testExternalKMLUsesOneNamespaceAndPreservesLayerReferences() throws {
        let occupiedDrawingID = UUID()
        let duplicateID = UUID()
        let firstRemint = UUID()
        let secondRemint = UUID()
        var remints = [firstRemint, secondRemint].makeIterator()
        let xml = """
        <kml>
          <Placemark id="\(occupiedDrawingID.uuidString)"><Point><coordinates>151,-33</coordinates></Point></Placemark>
          <Placemark id="\(duplicateID.uuidString)"><Point><coordinates>151.1,-33.1</coordinates></Point></Placemark>
          <Placemark id="\(duplicateID.uuidString)"><LineString><coordinates>151,-33 151.2,-33.2</coordinates></LineString></Placemark>
        </kml>
        """
        let external = try KMLImporter.parseExternal(
            Data(xml.utf8),
            existingLayers: [],
            fallbackLayerID: fallback,
            existingWaypointIDs: [],
            existingDrawingIDs: [occupiedDrawingID],
            batchKey: "kml",
            idFactory: { remints.next()! }
        )
        XCTAssertEqual(external.identityResolutions.map(\.resolvedID),
                       [firstRemint, duplicateID, secondRemint])
        XCTAssertEqual(external.identityResolutions.map(\.resolution),
                       [.existingCrossTypeCollision, .preserved, .incomingDuplicate])
        XCTAssertEqual(external.result.waypoints.map(\.layerID), [fallback, fallback])
        XCTAssertEqual(external.result.drawings.map(\.layerID), [fallback])
    }

    func testSecurityScopeBalancesOnSuccessFailureAndUnacquiredAccess() throws {
        var starts = 0
        var stops = 0
        _ = SecurityScopedImportAccess.withBalancedScope(
            start: { starts += 1; return true },
            stop: { stops += 1 },
            operation: { 7 }
        )
        XCTAssertEqual(starts, 1)
        XCTAssertEqual(stops, 1)

        XCTAssertThrowsError(try SecurityScopedImportAccess.withBalancedScope(
            start: { starts += 1; return true },
            stop: { stops += 1 },
            operation: { throw CancellationError() }
        ))
        XCTAssertEqual(starts, 2)
        XCTAssertEqual(stops, 2)

        _ = SecurityScopedImportAccess.withBalancedScope(
            start: { starts += 1; return false },
            stop: { stops += 1 },
            operation: { 9 }
        )
        XCTAssertEqual(starts, 3)
        XCTAssertEqual(stops, 2, "stop is invalid when security access was not acquired")
    }

    func testExternalImportWorkerReadsAndParsesOffMainThread() async throws {
        let id = UUID()
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"\(id.uuidString)",
           "geometry":{"type":"Point","coordinates":[151,-33]},"properties":{}}
        ]}
        """
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("geojson")
        try Data(json.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let context = ExternalImportWorker.Context(
            existingLayers: [],
            fallbackLayerID: fallback,
            existingWaypointIDs: [],
            existingDrawingIDs: []
        )
        let payload = try await ExternalImportWorker.prepare(
            url: url,
            kind: .geoJSON,
            encodedContext: try JSONEncoder().encode(context),
            batchKey: "worker"
        )
        XCTAssertTrue(payload.performedWorkOffMainThread)
        let batch = try JSONDecoder().decode(GeoJSONImporter.ExternalBatch.self,
                                             from: payload.encodedBatch)
        XCTAssertEqual(batch.result.waypoints.map(\.id), [id])
        XCTAssertEqual(batch.result.waypoints.map(\.layerID), [fallback])
    }

    func testExternalImportWorkerParsesInsideCoordinatedScopeAndReturnsOwnedPayload() throws {
        let id = UUID()
        let json = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","id":"\(id.uuidString)",
           "geometry":{"type":"Point","coordinates":[151,-33]},"properties":{}}
        ]}
        """
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("geojson")
        try Data(json.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let context = ExternalImportWorker.Context(
            existingLayers: [],
            fallbackLayerID: fallback,
            existingWaypointIDs: [],
            existingDrawingIDs: []
        )
        let probe = ScopeProbe()

        let payload = try ExternalImportWorker.prepareSynchronously(
            url: url,
            kind: .geoJSON,
            encodedContext: try JSONEncoder().encode(context),
            batchKey: "scoped-worker",
            performedWorkOffMainThread: true,
            coordinatedAccess: { source, operation in
                XCTAssertFalse(probe.active)
                probe.active = true
                defer { probe.active = false }
                let encodedBatch = try operation(source)
                XCTAssertTrue(probe.parseObserved)
                // Removing the coordinated source before its closure returns
                // proves the value crossing the boundary is an owned encoding,
                // not the mapped input Data.
                try FileManager.default.removeItem(at: source)
                return encodedBatch
            },
            parseStarted: {
                XCTAssertTrue(probe.active)
                probe.parseObserved = true
            }
        )

        XCTAssertTrue(probe.parseObserved)
        XCTAssertFalse(probe.active)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        let batch = try JSONDecoder().decode(GeoJSONImporter.ExternalBatch.self,
                                             from: payload.encodedBatch)
        XCTAssertEqual(batch.result.waypoints.map(\.id), [id])
        XCTAssertEqual(batch.result.waypoints.map(\.layerID), [fallback])
    }

    func testExternalImportWorkerPropagatesCancellation() async throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension("geojson")
        try Data(#"{"type":"FeatureCollection","features":[]}"#.utf8).write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let context = ExternalImportWorker.Context(
            existingLayers: [],
            fallbackLayerID: fallback,
            existingWaypointIDs: [],
            existingDrawingIDs: []
        )
        let contextData = try JSONEncoder().encode(context)
        let task = Task {
            await Task.yield()
            return try await ExternalImportWorker.prepare(
                url: url,
                kind: .geoJSON,
                encodedContext: contextData,
                batchKey: "cancelled"
            )
        }
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("cancelled import unexpectedly completed")
        } catch is CancellationError {
            // expected
        }
    }
}
