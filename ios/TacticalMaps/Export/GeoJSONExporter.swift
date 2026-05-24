import Foundation

/// Serialises waypoints + drawings into a single GeoJSON FeatureCollection
/// (RFC 7946). Style metadata follows the **Mapbox simplestyle-spec**
/// (`stroke`, `stroke-width`, `fill`, `fill-opacity`, `marker-color`) so the
/// output renders correctly in GitHub gists, geojson.io, Mapbox, Felt,
/// Leaflet, QGIS, and any other tool that speaks the convention.
///
/// Tactical-specific metadata uses the `tacticalmaps:` prefix so it does not
/// collide with simplestyle keys.
enum GeoJSONExporter {

    /// Build the FeatureCollection as a pretty-printed JSON string.
    static func export(waypoints: [Waypoint] = [],
                       drawings:  [DrawingShape] = []) throws -> String {
        var features: [[String: Any]] = []
        features.reserveCapacity(waypoints.count + drawings.count)

        for wp in waypoints      { features.append(feature(for: wp)) }
        for shape in drawings    { features.append(feature(for: shape)) }

        let collection: [String: Any] = [
            "type":      "FeatureCollection",
            "generator": "TacticalMaps iOS prototype \(generatorVersion)",
            "generated_at": ISO8601DateFormatter().string(from: .now),
            "features":  features
        ]

        let data = try JSONSerialization.data(
            withJSONObject: collection,
            options: [.prettyPrinted, .sortedKeys]
        )
        return String(data: data, encoding: .utf8) ?? ""
    }

    /// Write the export to a temporary `.geojson` file and return the URL,
    /// suitable for handing to `ShareLink`.
    static func exportToFile(waypoints: [Waypoint],
                             drawings:  [DrawingShape]) throws -> URL {
        let json = try export(waypoints: waypoints, drawings: drawings)
        let dir  = FileManager.default.temporaryDirectory
        let stamp = ISO8601DateFormatter().string(from: .now)
            .replacingOccurrences(of: ":", with: "-")
        let url = dir.appendingPathComponent("TacticalMaps-\(stamp).geojson")
        try json.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    // MARK: - Feature builders

    private static func feature(for wp: Waypoint) -> [String: Any] {
        var props: [String: Any] = [
            "name":        wp.name,
            // simplestyle marker key
            "marker-color": markerColor(for: wp.kind),
            "marker-symbol": markerSymbol(for: wp.kind),
            // namespaced metadata
            "tacticalmaps:category": "waypoint",
            "tacticalmaps:kind":     wp.kind.rawValue,
            "tacticalmaps:created_at": ISO8601DateFormatter().string(from: wp.createdAt)
        ]
        if let n = wp.notes     { props["description"] = n }     // simplestyle uses "description"
        if let e = wp.elevation { props["tacticalmaps:elevation_m"] = e }

        return [
            "type": "Feature",
            "id":   wp.id.uuidString,
            "geometry": [
                "type":        "Point",
                "coordinates": [wp.longitude, wp.latitude]
            ],
            "properties": props
        ]
    }

    private static func feature(for shape: DrawingShape) -> [String: Any] {
        var props: [String: Any] = [
            "tacticalmaps:category":   "drawing",
            "tacticalmaps:kind":       shape.kind.rawValue,
            "tacticalmaps:created_at": ISO8601DateFormatter().string(from: shape.createdAt)
        ]
        if let n = shape.name  { props["name"]        = n }
        if let n = shape.notes { props["description"] = n }

        // simplestyle-spec keys.
        props["stroke"]       = shape.style.strokeColorHex
        props["stroke-width"] = shape.style.strokeWidth
        if shape.kind == .polygon {
            if let f = shape.style.fillColorHex { props["fill"] = f }
            props["fill-opacity"] = shape.style.fillOpacity
        }

        var geometry: [String: Any]
        switch shape.kind {
        case .point:
            let c = shape.coordinates.first ?? Coordinate2D(latitude: 0, longitude: 0)
            geometry = [
                "type":        "Point",
                "coordinates": [c.longitude, c.latitude]
            ]

        case .polyline:
            geometry = [
                "type":        "LineString",
                "coordinates": shape.coordinates.map { [$0.longitude, $0.latitude] }
            ]

        case .polygon:
            // GeoJSON rings must be closed (first == last). Close implicitly.
            var coords = shape.coordinates.map { [$0.longitude, $0.latitude] }
            if let first = coords.first, let last = coords.last, first != last {
                coords.append(first)
            }
            geometry = [
                "type":        "Polygon",
                "coordinates": [coords]  // a single outer ring; no holes
            ]
        }

        return [
            "type":       "Feature",
            "id":         shape.id.uuidString,
            "geometry":   geometry,
            "properties": props
        ]
    }

    // MARK: - Style helpers

    private static func markerColor(for kind: WaypointKind) -> String {
        switch kind {
        case .generic:     return "#FFD700"  // yellow
        case .camp:        return "#3CB371"  // green
        case .water:       return "#1E90FF"  // blue
        case .observation: return "#FF8C00"  // orange
        case .dropZone:    return "#FFD700"  // yellow
        case .hazard:      return "#DC143C"  // crimson
        }
    }

    private static func markerSymbol(for kind: WaypointKind) -> String {
        // Mapbox Maki icon names (used by geojson.io for marker glyphs).
        switch kind {
        case .generic:     return "marker"
        case .camp:        return "campsite"
        case .water:       return "drinking-water"
        case .observation: return "observation-tower"
        case .dropZone:    return "airport"
        case .hazard:      return "danger"
        }
    }

    private static let generatorVersion = "v0.2"
}
