import XCTest
@testable import TacticalMaps

final class ImportSecurityTests: XCTestCase {
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
}
