import Foundation
import CoreLocation

/// Parses a GeoJSON FeatureCollection back into our domain objects.
///
/// Round-trips our own export (uses `tacticalmaps:*` namespaced properties
/// to reconstruct waypoints, drawings, and layers). Falls back gracefully
/// for foreign GeoJSON: points become generic waypoints, LineStrings /
/// Polygons become drawings on the active layer.
enum GeoJSONImporter {

    struct Result {
        var waypoints: [Waypoint] = []
        var drawings:  [DrawingShape] = []
        /// Layers referenced by imported drawings that don't exist in the
        /// store yet. Caller should add them before importing the shapes.
        var newLayers: [DrawingLayer] = []
    }

    enum ImportError: Error {
        case invalidJSON
        case notAFeatureCollection
    }

    /// Parse a `.geojson` file and return the reconstructed objects.
    /// `existingLayers` and `fallbackLayerID` decide where features land
    /// when no layer info is present in the file.
    static func parse(_ data: Data,
                      existingLayers: [DrawingLayer],
                      fallbackLayerID: UUID) throws -> Result {
        guard let raw = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ImportError.invalidJSON
        }
        guard (raw["type"] as? String) == "FeatureCollection",
              let features = raw["features"] as? [[String: Any]] else {
            throw ImportError.notAFeatureCollection
        }

        var result = Result()
        var layersByID = Dictionary(uniqueKeysWithValues: existingLayers.map { ($0.id.uuidString, $0) })

        for feature in features {
            guard let geometry = feature["geometry"] as? [String: Any],
                  let geomType = geometry["type"] as? String else { continue }
            let props = feature["properties"] as? [String: Any] ?? [:]
            let category = resolveCategory(props)

            // Resolve target layer: existing-by-id → newly-imported-by-id →
            // create new layer from name/color → fallback.
            let layerID: UUID = resolveLayerID(
                props: props,
                existingLayersByID: &layersByID,
                newLayers: &result.newLayers,
                fallback: fallbackLayerID
            )

            switch category {
            case "drawing":
                if let shape = parseDrawing(feature: feature,
                                            geometry: geometry,
                                            geomType: geomType,
                                            props: props,
                                            layerID: layerID) {
                    result.drawings.append(shape)
                }
            case "military", "controlMeasure", "generic":
                if geomType == "Point",
                   let wp = parseWaypoint(feature: feature,
                                          geometry: geometry,
                                          props: props,
                                          category: category,
                                          layerID: layerID) {
                    result.waypoints.append(wp)
                }
            default:
                // Foreign GeoJSON: best-effort classification by geometry.
                if geomType == "Point" {
                    if let wp = parseGenericPoint(feature: feature,
                                                  geometry: geometry,
                                                  props: props,
                                                  layerID: layerID) {
                        result.waypoints.append(wp)
                    }
                } else if let shape = parseDrawing(feature: feature,
                                                   geometry: geometry,
                                                   geomType: geomType,
                                                   props: props,
                                                   layerID: layerID) {
                    result.drawings.append(shape)
                }
            }
        }
        return result
    }

    // MARK: - Layer resolution

    private static func resolveCategory(_ props: [String: Any]) -> String? {
        if let category = props["tacticalmaps:category"] as? String {
            return category
        }
        guard (props["source"] as? String) == "symbol" else {
            return props["source"] as? String
        }
        switch props["kind"] as? String {
        case "military":
            return "military"
        case "control_measure", "controlMeasure":
            return "controlMeasure"
        case "generic":
            return "generic"
        default:
            return "generic"
        }
    }

    private static func resolveLayerID(props: [String: Any],
                                       existingLayersByID: inout [String: DrawingLayer],
                                       newLayers: inout [DrawingLayer],
                                       fallback: UUID) -> UUID {
        let idStr = (props["tacticalmaps:layer_id"] as? String)
            ?? (props["layer_id"] as? String)
        if let idStr,
           let layer = existingLayersByID[idStr] {
            return layer.id
        }
        if let idStr,
           let uuid = UUID(uuidString: idStr) {
            let name = (props["tacticalmaps:layer"] as? String)
                ?? (props["layer_name"] as? String)
                ?? "Imported"
            let color = (props["tacticalmaps:layer_color"] as? String)
                ?? (props["layer_color"] as? String)
                ?? "#FFA500"
            let layer = DrawingLayer(id: uuid, name: name, defaultColorHex: color)
            existingLayersByID[uuid.uuidString] = layer
            newLayers.append(layer)
            return uuid
        }
        let name = (props["tacticalmaps:layer"] as? String)
            ?? (props["layer_name"] as? String)
        if let name,
           let match = existingLayersByID.values.first(where: { $0.name == name }) {
            return match.id
        }
        return fallback
    }

    // MARK: - Feature parsers

    private static func parseDrawing(feature: [String: Any],
                                     geometry: [String: Any],
                                     geomType: String,
                                     props: [String: Any],
                                     layerID: UUID) -> DrawingShape? {
        let (kind, coords): (DrawingKind, [Coordinate2D])
        switch geomType {
        case "Point":
            guard let c = geometry["coordinates"] as? [Double], c.count >= 2 else { return nil }
            kind = .point
            coords = [Coordinate2D(latitude: c[1], longitude: c[0])]
        case "LineString":
            guard let arr = geometry["coordinates"] as? [[Double]], !arr.isEmpty else { return nil }
            kind = .polyline
            coords = arr.compactMap { p in
                guard p.count >= 2 else { return nil }
                return Coordinate2D(latitude: p[1], longitude: p[0])
            }
        case "Polygon":
            // Outer ring only — we don't model holes.
            guard let rings = geometry["coordinates"] as? [[[Double]]],
                  let outer = rings.first, !outer.isEmpty else { return nil }
            kind = .polygon
            // Drop the GeoJSON ring-closure repeat if present.
            var pts = outer.compactMap { p -> Coordinate2D? in
                guard p.count >= 2 else { return nil }
                return Coordinate2D(latitude: p[1], longitude: p[0])
            }
            if pts.count > 1, let f = pts.first, let l = pts.last,
               f.latitude == l.latitude, f.longitude == l.longitude {
                pts.removeLast()
            }
            coords = pts
        default:
            return nil
        }

        guard !coords.isEmpty else { return nil }

        var style = DrawingStyle()
        if let stroke = props["stroke"] as? String { style.strokeColorHex = stroke }
        if let fill   = props["fill"]   as? String { style.fillColorHex   = fill }
        if let w      = props["stroke-width"] as? Double { style.strokeWidth = w }
        if let o      = props["fill-opacity"] as? Double { style.fillOpacity = o }

        let name = props["name"] as? String
        let notes = props["description"] as? String
        let id = (feature["id"] as? String).flatMap { UUID(uuidString: $0) } ?? UUID()

        return DrawingShape(
            id: id,
            name: name,
            notes: notes,
            kind: kind,
            coordinates: coords,
            style: style,
            layerID: layerID
        )
    }

    private static func parseWaypoint(feature: [String: Any],
                                      geometry: [String: Any],
                                      props: [String: Any],
                                      category: String?,
                                      layerID: UUID) -> Waypoint? {
        guard let c = geometry["coordinates"] as? [Double], c.count >= 2 else { return nil }
        let coord = CLLocationCoordinate2D(latitude: c[1], longitude: c[0])
        let name = (props["name"] as? String) ?? "Imported"
        let notes = (props["description"] as? String) ?? (props["notes"] as? String)
        let elevation = doubleValue(props["tacticalmaps:elevation_m"])
            ?? doubleValue(props["elevation_m"])
        let id = (feature["id"] as? String).flatMap { UUID(uuidString: $0) } ?? UUID()

        let kind: WaypointKind
        switch category {
        case "military":
            let aff = (props["tacticalmaps:affiliation"] as? String)
                .flatMap(SymbolAffiliation.init(rawValue:)) ?? .friend
            let ech = (props["tacticalmaps:echelon"] as? String)
                .flatMap(SymbolEchelon.init(rawValue:)) ?? .platoon
            let fn  = (props["tacticalmaps:function"] as? String)
                .flatMap(SymbolFunction.init(rawValue:)) ?? .infantry
            kind = .military(MilitarySymbolSpec(
                affiliation: aff,
                echelon: ech,
                function: fn,
                isHeadquarters: boolValue(props["tacticalmaps:is_hq"]) ?? false
            ))
        case "controlMeasure":
            if let raw = (props["tacticalmaps:tcm_asset"] as? String)
                ?? (props["tacticalmaps:kind"] as? String)
                ?? (props["kind"] as? String),
               let m = TacticalControlMeasure(rawValue: raw) {
                kind = .controlMeasure(m)
            } else {
                kind = .generic
            }
        default:
            kind = .generic
        }

        let rotation = doubleValue(props["tacticalmaps:rotation_deg"])
            ?? doubleValue(props["rotation"])
            ?? 0
        let scaleX = doubleValue(props["tacticalmaps:scale_x"])
            ?? doubleValue(props["scale_x"])
            ?? 1
        let scaleY = doubleValue(props["tacticalmaps:scale_y"])
            ?? doubleValue(props["scale_y"])
            ?? 1
        return Waypoint(id: id,
                        name: name,
                        notes: notes,
                        coordinate: coord,
                        elevation: elevation,
                        kind: kind,
                        rotation: rotation,
                        scaleX: scaleX,
                        scaleY: scaleY,
                        layerID: layerID)
    }

    private static func parseGenericPoint(feature: [String: Any],
                                          geometry: [String: Any],
                                          props: [String: Any],
                                          layerID: UUID) -> Waypoint? {
        return parseWaypoint(feature: feature,
                             geometry: geometry,
                             props: props,
                             category: "generic",
                             layerID: layerID)
    }

    private static func doubleValue(_ any: Any?) -> Double? {
        if let value = any as? Double { return value }
        if let value = any as? Int { return Double(value) }
        if let value = any as? NSNumber { return value.doubleValue }
        if let value = any as? String { return Double(value) }
        return nil
    }

    private static func boolValue(_ any: Any?) -> Bool? {
        if let value = any as? Bool { return value }
        if let value = any as? NSNumber { return value.boolValue }
        if let value = any as? String { return Bool(value) }
        return nil
    }
}
