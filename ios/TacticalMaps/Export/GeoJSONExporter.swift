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
            "tacticalmaps:category": kindCategory(wp.kind),
            "tacticalmaps:kind":     kindDescriptor(wp.kind),
            "tacticalmaps:created_at": ISO8601DateFormatter().string(from: wp.createdAt)
        ]
        // Carry the structured APP-6C spec verbatim for round-tripping into
        // other tools that may want to re-render the symbol.
        if let spec = wp.kind.militarySpec {
            props["tacticalmaps:affiliation"] = spec.affiliation.rawValue
            props["tacticalmaps:echelon"]     = spec.echelon.rawValue
            props["tacticalmaps:function"]    = spec.function.rawValue
        }
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
        case .generic:                  return "#FFD700"
        case .military(let spec):       return spec.affiliation.fillHex
        case .controlMeasure:           return "#1A1A1A"
        }
    }

    private static func markerSymbol(for kind: WaypointKind) -> String {
        switch kind {
        case .generic:                  return "marker"
        case .military(let spec):       return makiSymbol(for: spec)
        case .controlMeasure(let m):    return makiSymbol(for: m)
        }
    }

    /// Closest Mapbox Maki icon for a military spec. There's no real
    /// APP-6 equivalent in Maki so this is a best-effort hint for tools
    /// like geojson.io.
    private static func makiSymbol(for spec: MilitarySymbolSpec) -> String {
        switch spec.affiliation {
        case .friend:  return "square"
        case .hostile: return "square-stroked"
        case .neutral: return "square"
        case .unknown: return "circle"
        }
    }

    private static func makiSymbol(for m: TacticalControlMeasure) -> String {
        switch m {
        case .axisOfAssault: return "arrow"
        case .supportByFire: return "scope"
        case .attackByFire:  return "fire-station"
        case .formUpPoint:   return "square-stroked"
        case .rvPoint:       return "rally"
        case .axp:           return "hospital"
        case .lz:            return "heliport"
        }
    }

    private static func kindCategory(_ kind: WaypointKind) -> String {
        switch kind {
        case .generic:        return "generic"
        case .military:       return "military"
        case .controlMeasure: return "controlMeasure"
        }
    }

    private static func kindDescriptor(_ kind: WaypointKind) -> String {
        switch kind {
        case .generic:                return "generic"
        case .military(let spec):     return "\(spec.affiliation.rawValue).\(spec.function.rawValue).\(spec.echelon.rawValue)"
        case .controlMeasure(let m):  return m.rawValue
        }
    }

    private static let generatorVersion = "v0.2"
}
