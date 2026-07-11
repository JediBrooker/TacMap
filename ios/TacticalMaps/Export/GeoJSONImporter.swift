import Foundation
import CoreLocation

/// Parses a GeoJSON FeatureCollection back into domain objects.
///
/// Round-trips our own export via `tacticalmaps:*` properties. For foreign
/// GeoJSON it does its best: points become generic waypoints, lines/polygons
/// become drawings on the active layer.
enum GeoJSONImporter {

    struct Result {
        var waypoints: [Waypoint] = []
        var drawings:  [DrawingShape] = []
        /// Layers from the import that dont exist in the store yet.
        /// Caller needs to add these before importing shapes.
        var newLayers: [DrawingLayer] = []
        /// Features skipped b/c coordinates were non-finite or out of range.
        /// Surfaced in the import summary so we don't silently drop stuff.
        var invalidSkipped: Int = 0
    }

    /// True if a single lon/lat pair is finite and in range.
    private static func validLonLat(_ lon: Double, _ lat: Double) -> Bool {
        lon.isFinite && lat.isFinite && abs(lat) <= 90 && abs(lon) <= 180
    }

    /// Checks that every coord in the geometry is finite and in range.
    private static func geometryValid(_ geometry: [String: Any]) -> Bool {
        switch geometry["type"] as? String {
        case "Point":
            guard let c = geometry["coordinates"] as? [Double], c.count >= 2 else { return false }
            return validLonLat(c[0], c[1])
        case "LineString":
            guard let arr = geometry["coordinates"] as? [[Double]], !arr.isEmpty else { return false }
            return arr.allSatisfy { $0.count >= 2 && validLonLat($0[0], $0[1]) }
        case "Polygon":
            guard let rings = geometry["coordinates"] as? [[[Double]]] else { return false }
            return rings.allSatisfy { $0.allSatisfy { $0.count >= 2 && validLonLat($0[0], $0[1]) } }
        default:
            return false
        }
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
            // Bail out on non-finite / out-of-range coords. Don't let a corrupt
            // file drop a symbol at NaN or somewhere off the globe.
            guard geometryValid(geometry) else { result.invalidSkipped += 1; continue }
            let props = feature["properties"] as? [String: Any] ?? [:]
            let category = resolveCategory(props)

            // Resolve target layer: existing by id, then newly-imported by id,
            // then create from name/color, then fallback.
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
            case "military", "controlMeasure", "generic", "marker":
                if geomType == "Point",
                   let wp = parseWaypoint(feature: feature,
                                          geometry: geometry,
                                          props: props,
                                          category: category,
                                          layerID: layerID) {
                    result.waypoints.append(wp)
                }
            default:
                // Foreign GeoJSON - just classify by geometry type.
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
        // Case-insensitive id match (Android lowercase vs iOS uppercase UUIDs).
        if let idStr,
           let layer = existingLayersByID.first(where: { $0.key.caseInsensitiveCompare(idStr) == .orderedSame })?.value {
            return layer.id
        }
        // Before minting a new layer for an unknown id, try to adopt an existing
        // one with the same name. Otherwise devices with default layers under
        // different per-install ids proliferate duplicate "Friendly"/"Enemy"
        // layers on every import/sync. Ask me how I know.
        let name = (props["tacticalmaps:layer"] as? String)
            ?? (props["layer_name"] as? String)
        if let name,
           let match = existingLayersByID.values.first(where: { $0.name == name }) {
            return match.id
        }
        if let idStr,
           let uuid = UUID(uuidString: idStr) {
            let color = (props["tacticalmaps:layer_color"] as? String)
                ?? (props["layer_color"] as? String)
                ?? "#FFA500"
            let layer = DrawingLayer(id: uuid, name: name ?? "Imported", defaultColorHex: color)
            existingLayersByID[uuid.uuidString] = layer
            newLayers.append(layer)
            return uuid
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
            // Outer ring only, we dont model holes.
            guard let rings = geometry["coordinates"] as? [[[Double]]],
                  let outer = rings.first, !outer.isEmpty else { return nil }
            kind = .polygon
            // Strip the GeoJSON ring-closure duplicate if present.
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
        // simplestyle first, then Android legacy keys (stroke_color etc. are
        // #AARRGGBB, drop the alpha to get #RRGGBB) so Android drawings keep
        // their styling on import.
        if let stroke = (props["stroke"] as? String) ?? rgbFromArgb(props["stroke_color"] as? String) {
            style.strokeColorHex = stroke
        }
        if let fill = (props["fill"] as? String) ?? rgbFromArgb(props["fill_color"] as? String) {
            style.fillColorHex = fill
        }
        if let w = doubleValue(props["stroke-width"]) ?? doubleValue(props["stroke_width"]) {
            style.strokeWidth = w
        }
        if let o = doubleValue(props["fill-opacity"]) { style.fillOpacity = o }
        // Dash style: shared namespaced key, falls back to Android legacy key.
        let strokeStyle = (props["tacticalmaps:stroke_style"] as? String) ?? (props["stroke_style"] as? String)
        if strokeStyle?.lowercased() == "dashed" { style.dashPattern = [8, 4] }
        if let lg = (props["tacticalmaps:line_graphic"] as? String).flatMap(LineGraphic.init(rawValue:)) {
            style.lineGraphic = lg
        }

        let name = props["name"] as? String
        let notes = props["description"] as? String
        let id = (feature["id"] as? String).flatMap { UUID(uuidString: $0) } ?? UUID()
        let createdAt = parseDate(props["tacticalmaps:created_at"]) ?? parseDate(props["created_at"])

        return DrawingShape(
            id: id,
            name: name,
            notes: notes,
            kind: kind,
            coordinates: coords,
            style: style,
            createdAt: createdAt ?? .now,
            layerID: layerID
        )
    }

    /// Android's `#AARRGGBB` to our `#RRGGBB` (just drop the alpha byte).
    private static func rgbFromArgb(_ hex: String?) -> String? {
        guard var h = hex else { return nil }
        h = h.hasPrefix("#") ? String(h.dropFirst()) : h
        if h.count == 8 { return "#" + h.suffix(6) }
        if h.count == 6 { return "#" + h }
        return nil
    }

    /// Parse ISO-8601 into a Date for created_at round-trip. Handles both
    /// fractional-second (`...00.123Z`, Android's ISO_INSTANT) and whole-second
    /// variants, otherwise Android objects reset their creation time on import
    /// and re-sync churns.
    private static func parseDate(_ any: Any?) -> Date? {
        guard let s = any as? String else { return nil }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = fractional.date(from: s) { return d }
        return ISO8601DateFormatter().date(from: s)
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
            // Unknown/corrupt affiliation -> .unknown, NOT .friend.
            // Fail-to-friendly could mask a hostile contact on mixed import.
            let aff = (props["tacticalmaps:affiliation"] as? String)
                .flatMap(SymbolAffiliation.init(rawValue:)) ?? .unknown
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
        case "marker":
            let set = (props["tacticalmaps:marker_set"] as? String)
                .flatMap(MarkerSet.init(rawValue:)) ?? .airsoft
            let symbolID = (props["tacticalmaps:marker_symbol"] as? String) ?? "team"
            let colorHex = (props["tacticalmaps:marker_color"] as? String) ?? "#3B7BE0"
            kind = .marker(MarkerSymbol(set: set, symbolID: symbolID, colorHex: colorHex))
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
        let taskColor = (props["tacticalmaps:task_color"] as? String)
            .flatMap { TaskColor(rawValue: $0.lowercased()) } ?? .black
        let createdAt = parseDate(props["tacticalmaps:created_at"]) ?? parseDate(props["created_at"])
        return Waypoint(id: id,
                        name: name,
                        notes: notes,
                        coordinate: coord,
                        elevation: elevation,
                        kind: kind,
                        rotation: rotation,
                        scaleX: scaleX,
                        scaleY: scaleY,
                        taskColor: taskColor,
                        layerID: layerID,
                        createdAt: createdAt ?? .now)
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
