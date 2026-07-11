import Foundation

/// Serialises waypoints + drawings into a GeoJSON FeatureCollection (RFC 7946).
/// Uses Mapbox simplestyle-spec for styling (`stroke`, `stroke-width`, etc.)
/// so it renders in GitHub gists, geojson.io, QGIS, and basically anything
/// that understands simplestyle.
///
/// Tactical-specific stuff goes under the `tacticalmaps:` prefix to avoid
/// clobbering simplestyle keys.
enum GeoJSONExporter {

    /// Build the FeatureCollection as a pretty-printed JSON string.
    /// Pass `layers` so drawing features can carry `tacticalmaps:layer`
    /// (name + colour) for round-tripping the grouping.
    static func export(waypoints: [Waypoint] = [],
                       drawings:  [DrawingShape] = [],
                       layers:    [DrawingLayer] = []) throws -> String {
        var features: [[String: Any]] = []
        features.reserveCapacity(waypoints.count + drawings.count)

        let layerByID = Dictionary(uniqueKeysWithValues: layers.map { ($0.id, $0) })
        for wp in waypoints      { features.append(feature(for: wp, layer: layerByID[wp.layerID])) }
        for shape in drawings    { features.append(feature(for: shape, layer: layerByID[shape.layerID])) }

        // No wall-clock `generated_at` here. Per-object export has to be a
        // pure function of object state, otherwise sync change-detection sees
        // a "change" on every serialisation. File exports just carry a
        // timestamp in the filename instead.
        let collection: [String: Any] = [
            "type":      "FeatureCollection",
            "generator": "TacMap",
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
                             drawings:  [DrawingShape],
                             layers:    [DrawingLayer] = []) throws -> URL {
        let json = try export(waypoints: waypoints, drawings: drawings, layers: layers)
        let dir  = FileManager.default.temporaryDirectory
        let stamp = ISO8601DateFormatter().string(from: .now)
            .replacingOccurrences(of: ":", with: "-")
        let url = dir.appendingPathComponent("TacMap-\(stamp).geojson")
        try json.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    // MARK: - Feature builders

    private static func feature(for wp: Waypoint, layer: DrawingLayer? = nil) -> [String: Any] {
        var props: [String: Any] = [
            "name":        wp.name,
            // Android legacy metadata.
            "source":      "symbol",
            "kind":        legacyKindDescriptor(wp.kind),
            "kind_display": wp.kind.displayName,
            // simplestyle marker key
            "marker-color": markerColor(for: wp.kind),
            "marker-symbol": markerSymbol(for: wp.kind),
            // namespaced metadata
            "tacticalmaps:category": kindCategory(wp.kind),
            "tacticalmaps:kind":     kindDescriptor(wp.kind),
            "tacticalmaps:created_at": ISO8601DateFormatter().string(from: wp.createdAt)
        ]
        props["created_at"] = props["tacticalmaps:created_at"]
        // Task/control-measure colour - shared lowercase token set so it
        // round-trips across platforms (black/blue/red/green/yellow).
        props["tacticalmaps:task_color"] = wp.taskColor.rawValue
        props["layer_id"] = wire(wp.layerID)
        props["tacticalmaps:layer_id"] = wire(wp.layerID)
        if let layer {
            props["layer_name"] = layer.name
            props["tacticalmaps:layer"] = layer.name
            props["tacticalmaps:layer_color"] = layer.defaultColorHex
        }
        // Stash the APP-6C spec as-is so other tools can re-render the symbol.
        if let spec = wp.kind.militarySpec {
            props["tacticalmaps:affiliation"] = spec.affiliation.rawValue
            props["tacticalmaps:echelon"]     = spec.echelon.rawValue
            props["tacticalmaps:function"]    = spec.function.rawValue
            if spec.isHeadquarters {
                props["tacticalmaps:is_hq"] = true
            }
        }
        if let m = wp.kind.controlMeasure {
            props["tacticalmaps:tcm_name"] = m.displayName
            props["tacticalmaps:tcm_asset"] = m.assetName
            props["rotation"] = wp.rotation
            props["scale_x"] = wp.scaleX
            props["scale_y"] = wp.scaleY
            props["tacticalmaps:scale_x"] = wp.scaleX
            props["tacticalmaps:scale_y"] = wp.scaleY
            if wp.rotation != 0 {
                // Round to 1 deg. Sub-degree precision is meaningless for a
                // hand-dialed slider and just clutters the diff.
                props["tacticalmaps:rotation_deg"] = (wp.rotation.rounded() as Double)
            }
        }
        // Marker set/symbol/colour, keyed the same as Android so a marker
        // waypoint round-trips across platforms (was previously dropped to a
        // generic pin on import).
        if case .marker(let mk) = wp.kind {
            props["tacticalmaps:marker_set"] = mk.set.rawValue
            props["tacticalmaps:marker_symbol"] = mk.symbolID
            props["tacticalmaps:marker_color"] = mk.colorHex
        }
        if let n = wp.notes {
            props["description"] = n     // simplestyle uses "description"
            props["notes"] = n
        }
        if let e = wp.elevation {
            props["tacticalmaps:elevation_m"] = e
            props["elevation_m"] = e
        }

        return [
            "type": "Feature",
            "id":   wire(wp.id),
            "geometry": [
                "type":        "Point",
                "coordinates": [wp.longitude, wp.latitude]
            ],
            "properties": props
        ]
    }

    /// Wire-format UUID: always lowercase. Swift's `uuidString` is uppercase
    /// but Android emits lowercase, so we just lowercase everything to keep
    /// ids byte-identical and avoid sync churn.
    private static func wire(_ id: UUID) -> String { id.uuidString.lowercased() }

    private static func feature(for shape: DrawingShape, layer: DrawingLayer? = nil) -> [String: Any] {
        var props: [String: Any] = [
            "source":                  "drawing",
            "kind":                    shape.kind.rawValue,
            "tacticalmaps:category":   "drawing",
            "tacticalmaps:kind":       shape.kind.rawValue,
            "tacticalmaps:created_at": ISO8601DateFormatter().string(from: shape.createdAt)
        ]
        props["created_at"] = props["tacticalmaps:created_at"]
        if let n = shape.name  { props["name"]        = n }
        if let n = shape.notes { props["description"] = n }
        if let layer {
            props["layer_id"]                 = wire(layer.id)
            props["layer_name"]               = layer.name
            props["tacticalmaps:layer"]       = layer.name
            props["tacticalmaps:layer_id"]    = wire(layer.id)
            props["tacticalmaps:layer_color"] = layer.defaultColorHex
        }

        // simplestyle-spec keys (interoperable + read by the Android importer).
        props["stroke"]       = shape.style.strokeColorHex
        props["stroke-width"] = shape.style.strokeWidth
        props["tacticalmaps:stroke_unit"] = "dp"
        if shape.kind == .polygon {
            if let f = shape.style.fillColorHex { props["fill"] = f }
            props["fill-opacity"] = shape.style.fillOpacity
        }
        // Dash style: shared namespaced key, both plaforms emit and read it.
        props["tacticalmaps:stroke_style"] = shape.style.dashPattern != nil ? "dashed" : "solid"
        if let lg = shape.style.lineGraphic, lg != .plain {
            props["tacticalmaps:line_graphic"] = lg.rawValue
        }

        // Export the RENDERED geometry, rotation + scale baked in (matching
        // Android). That way a rotated/stretched shape lands correctly in other
        // tools and sync diffs actually reflect transform edits.
        let baked = shape.effectiveCoordinates
        var geometry: [String: Any]
        switch shape.kind {
        case .point:
            let c = baked.first ?? Coordinate2D(latitude: 0, longitude: 0)
            geometry = [
                "type":        "Point",
                "coordinates": [c.longitude, c.latitude]
            ]

        case .polyline, .freedraw:
            geometry = [
                "type":        "LineString",
                "coordinates": baked.map { [$0.longitude, $0.latitude] }
            ]

        case .polygon:
            // GeoJSON rings must be closed (first == last), so close if needed.
            var coords = baked.map { [$0.longitude, $0.latitude] }
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
            "id":         wire(shape.id),
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
        case .marker(let mk):           return mk.colorHex
        }
    }

    private static func markerSymbol(for kind: WaypointKind) -> String {
        switch kind {
        case .generic:                  return "marker"
        case .military(let spec):       return makiSymbol(for: spec)
        case .controlMeasure(let m):    return makiSymbol(for: m)
        case .marker:                   return "marker"
        }
    }

    /// Best-effort Maki icon for a military spec. There's no real APP-6
    /// equivalent in Maki, just a rough hint for geojson.io and friends.
    private static func makiSymbol(for spec: MilitarySymbolSpec) -> String {
        switch spec.affiliation {
        case .friend:  return "square"
        case .hostile: return "square-stroked"
        case .neutral: return "square"
        case .unknown: return "circle"
        }
    }

    private static func makiSymbol(for m: TacticalControlMeasure) -> String {
        // No maki name for all 37 cases. Simplestyle viewers just get a
        // generic marker + the namespaced displayName for round-tripping.
        return "marker"
    }

    private static func kindCategory(_ kind: WaypointKind) -> String {
        switch kind {
        case .generic:        return "generic"
        case .military:       return "military"
        case .controlMeasure: return "controlMeasure"
        case .marker:         return "marker"
        }
    }

    private static func legacyKindDescriptor(_ kind: WaypointKind) -> String {
        switch kind {
        case .generic:        return "generic"
        case .military:       return "military"
        case .controlMeasure: return "control_measure"
        case .marker:         return "marker"
        }
    }

    private static func kindDescriptor(_ kind: WaypointKind) -> String {
        switch kind {
        case .generic:                return "generic"
        case .military(let spec):     return "\(spec.affiliation.rawValue).\(spec.function.rawValue).\(spec.echelon.rawValue)"
        case .controlMeasure(let m):  return m.rawValue
        case .marker(let mk):         return "\(mk.set.rawValue).\(mk.symbolID)"
        }
    }
}
