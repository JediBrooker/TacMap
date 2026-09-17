import Foundation
import CoreLocation

/// Identity policy used only by user-initiated external imports. Unit Sync
/// continues to call `GeoJSONImporter.parse` and therefore preserves the
/// authenticated object ID embedded in its payload.
struct ExternalImportIdentityResolver {
    enum ObjectKind: String, Codable, Hashable {
        case waypoint
        case drawing
    }

    enum ResolutionReason: String, Codable, Hashable {
        case preserved
        case existingSameKindCollision = "existing_same_kind_collision"
        case existingCrossTypeCollision = "existing_cross_type_collision"
        case incomingDuplicate = "incoming_duplicate"
        case missingID = "missing_id"
        case invalidID = "invalid_id"
        case commitTimeCollision = "commit_time_collision"
    }

    struct Resolution: Codable, Hashable {
        let caseKey: String
        let kind: ObjectKind
        let sourceID: String?
        let resolvedID: UUID
        let resolution: ResolutionReason
    }

    private let existingKinds: [UUID: ObjectKind]
    private let priorByCaseKey: [String: Resolution]
    private let makeUUID: () -> UUID
    private var occupied: Set<UUID>
    private var acceptedIncoming: Set<UUID> = []
    private(set) var resolutions: [Resolution] = []

    init(existingWaypointIDs: Set<UUID>,
         existingDrawingIDs: Set<UUID>,
         priorResolutions: [Resolution] = [],
         idFactory: @escaping () -> UUID = UUID.init) {
        var kinds = Dictionary(uniqueKeysWithValues: existingWaypointIDs.map { ($0, ObjectKind.waypoint) })
        for id in existingDrawingIDs where kinds[id] == nil { kinds[id] = .drawing }
        self.existingKinds = kinds
        self.priorByCaseKey = Dictionary(uniqueKeysWithValues: priorResolutions.map { ($0.caseKey, $0) })
        self.makeUUID = idFactory
        self.occupied = existingWaypointIDs.union(existingDrawingIDs)
    }

    mutating func resolve(sourceID: String?, kind: ObjectKind, caseKey: String) -> UUID {
        if let prior = priorByCaseKey[caseKey], prior.kind == kind {
            occupied.insert(prior.resolvedID)
            acceptedIncoming.insert(prior.resolvedID)
            resolutions.append(prior)
            return prior.resolvedID
        }

        let parsed = sourceID.flatMap(UUID.init(uuidString:))
        let reason: ResolutionReason
        let resolved: UUID
        if sourceID == nil {
            reason = .missingID
            resolved = nextUniqueID()
        } else if parsed == nil {
            reason = .invalidID
            resolved = nextUniqueID()
        } else if let candidate = parsed, let existingKind = existingKinds[candidate] {
            reason = existingKind == kind ? .existingSameKindCollision : .existingCrossTypeCollision
            resolved = nextUniqueID()
        } else if let candidate = parsed, acceptedIncoming.contains(candidate) || occupied.contains(candidate) {
            reason = .incomingDuplicate
            resolved = nextUniqueID()
        } else {
            reason = .preserved
            resolved = parsed!
            occupied.insert(resolved)
        }

        acceptedIncoming.insert(resolved)
        resolutions.append(Resolution(caseKey: caseKey,
                                      kind: kind,
                                      sourceID: sourceID,
                                      resolvedID: resolved,
                                      resolution: reason))
        return resolved
    }

    private mutating func nextUniqueID() -> UUID {
        while true {
            let candidate = makeUUID()
            if occupied.insert(candidate).inserted { return candidate }
        }
    }
}

/// Parses a GeoJSON FeatureCollection back into domain objects.
///
/// Round-trips our own export via `tacticalmaps:*` properties. For foreign
/// GeoJSON it does its best: points become generic waypoints, lines/polygons
/// become drawings on the active layer.
enum GeoJSONImporter {

    static let maxInputBytes = 16 * 1024 * 1024
    private static let maxFeatures = 10_000
    private static let maxCoordinates = 100_000
    private static let maxJSONDepth = 64
    private static let maxJSONNodes = 300_000
    private static let parseDeadline: TimeInterval = 4

    struct Result: Codable {
        var waypoints: [Waypoint] = []
        var drawings:  [DrawingShape] = []
        /// Layers from the import that dont exist in the store yet.
        /// Caller needs to add these before importing shapes.
        var newLayers: [DrawingLayer] = []
        /// Features skipped b/c coordinates were non-finite or out of range.
        /// Surfaced in the import summary so we don't silently drop stuff.
        var invalidSkipped: Int = 0
    }

    struct ExternalBatch: Codable {
        let batchKey: String
        let result: Result
        let identityResolutions: [ExternalImportIdentityResolver.Resolution]
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

    enum ImportError: Error, LocalizedError, LocalizedMessageError {
        case invalidJSON
        case notAFeatureCollection
        case limitExceeded(LocalizedMessage)
        case cancelled

        var errorDescription: String? { localizedMessage.text }

        var localizedMessage: LocalizedMessage {
            switch self {
            case .invalidJSON: return Messages.displayThisFileIsnTValidGeojsonMessage()
            case .notAFeatureCollection: return Messages.displayThisGeojsonIsNotAFeaturecollectionMessage()
            case .limitExceeded(let reason): return Messages.displayImportSafetyLimitExceededMessage("").withArgument(0, reason)
            case .cancelled: return Messages.displayImportCancelledMessage()
            }
        }
    }

    /// Parse a `.geojson` file and return the reconstructed objects.
    /// `existingLayers` and `fallbackLayerID` decide where features land
    /// when no layer info is present in the file.
    static func parse(_ data: Data,
                      existingLayers: [DrawingLayer],
                      fallbackLayerID: UUID) throws -> Result {
        try parseCore(data,
                      existingLayers: existingLayers,
                      fallbackLayerID: fallbackLayerID,
                      preserveExternalLayerIDs: false,
                      resolveExternalID: nil)
    }

    /// Parses a user-selected file under the external-import identity policy.
    /// Layer IDs are deliberately absent from the occupied object set.
    static func parseExternal(_ data: Data,
                              existingLayers: [DrawingLayer],
                              fallbackLayerID: UUID,
                              existingWaypointIDs: Set<UUID>,
                              existingDrawingIDs: Set<UUID>,
                              batchKey: String,
                              priorResolutions: [ExternalImportIdentityResolver.Resolution] = [],
                              idFactory: @escaping () -> UUID = UUID.init) throws -> ExternalBatch {
        var resolver = ExternalImportIdentityResolver(
            existingWaypointIDs: existingWaypointIDs,
            existingDrawingIDs: existingDrawingIDs,
            priorResolutions: priorResolutions,
            idFactory: idFactory
        )
        let parsed = try parseCore(
            data,
            existingLayers: existingLayers,
            fallbackLayerID: fallbackLayerID,
            preserveExternalLayerIDs: true,
            resolveExternalID: { sourceID, kind, featureIndex in
                resolver.resolve(sourceID: sourceID,
                                 kind: kind,
                                 caseKey: "feature-\(featureIndex)")
            }
        )
        return ExternalBatch(batchKey: batchKey,
                             result: parsed,
                             identityResolutions: resolver.resolutions)
    }

    private static func parseCore(
        _ data: Data,
        existingLayers: [DrawingLayer],
        fallbackLayerID: UUID,
        preserveExternalLayerIDs: Bool,
        resolveExternalID: ((String?, ExternalImportIdentityResolver.ObjectKind, Int) -> UUID)?
    ) throws -> Result {
        guard data.count <= maxInputBytes else { throw ImportError.limitExceeded(Messages.displayFileIsOverMbMessage()) }
        let deadline = Date().addingTimeInterval(parseDeadline)
        guard let raw = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ImportError.invalidJSON
        }
        var nodeCount = 0
        try validateStructure(raw, depth: 0, nodes: &nodeCount)
        guard (raw["type"] as? String) == "FeatureCollection",
              let features = raw["features"] as? [[String: Any]] else {
            throw ImportError.notAFeatureCollection
        }
        guard features.count <= maxFeatures else { throw ImportError.limitExceeded(Messages.displayMoreThanFeaturesMessage()) }

        var result = Result()
        var layersByID = Dictionary(uniqueKeysWithValues: existingLayers.map { ($0.id.uuidString, $0) })
        var coordinateCount = 0

        for (featureIndex, feature) in features.enumerated() {
            if Date() > deadline { throw ImportError.limitExceeded(Messages.displayParsingTookTooLongMessage()) }
            if withUnsafeCurrentTask(body: { $0?.isCancelled ?? false }) { throw ImportError.cancelled }
            guard let geometry = feature["geometry"] as? [String: Any],
                  let geomType = geometry["type"] as? String else { continue }
            coordinateCount += coordinatePairCount(geometry)
            guard coordinateCount <= maxCoordinates else {
                throw ImportError.limitExceeded(Messages.displayMoreThanCoordinatesMessage())
            }
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
                fallback: fallbackLayerID,
                preserveValidID: preserveExternalLayerIDs
            )

            switch category {
            case "drawing":
                let id = resolvedObjectID(feature: feature,
                                          kind: .drawing,
                                          featureIndex: featureIndex,
                                          external: resolveExternalID)
                if let shape = parseDrawing(feature: feature,
                                            geometry: geometry,
                                            geomType: geomType,
                                            props: props,
                                            layerID: layerID,
                                            id: id) {
                    result.drawings.append(shape)
                }
            case "military", "controlMeasure", "generic", "marker":
                if geomType == "Point" {
                    let id = resolvedObjectID(feature: feature,
                                              kind: .waypoint,
                                              featureIndex: featureIndex,
                                              external: resolveExternalID)
                    if let wp = parseWaypoint(feature: feature,
                                              geometry: geometry,
                                              props: props,
                                              category: category,
                                              layerID: layerID,
                                              id: id) {
                        result.waypoints.append(wp)
                    }
                }
            default:
                // Foreign GeoJSON - just classify by geometry type.
                if geomType == "Point" {
                    let id = resolvedObjectID(feature: feature,
                                              kind: .waypoint,
                                              featureIndex: featureIndex,
                                              external: resolveExternalID)
                    if let wp = parseGenericPoint(feature: feature,
                                                  geometry: geometry,
                                                  props: props,
                                                  layerID: layerID,
                                                  id: id) {
                        result.waypoints.append(wp)
                    }
                } else {
                    let id = resolvedObjectID(feature: feature,
                                              kind: .drawing,
                                              featureIndex: featureIndex,
                                              external: resolveExternalID)
                    if let shape = parseDrawing(feature: feature,
                                                geometry: geometry,
                                                geomType: geomType,
                                                props: props,
                                                layerID: layerID,
                                                id: id) {
                        result.drawings.append(shape)
                    }
                }
            }
        }
        return result
    }

    private static func resolvedObjectID(
        feature: [String: Any],
        kind: ExternalImportIdentityResolver.ObjectKind,
        featureIndex: Int,
        external: ((String?, ExternalImportIdentityResolver.ObjectKind, Int) -> UUID)?
    ) -> UUID {
        let sourceID = feature["id"] as? String
        return external?(sourceID, kind, featureIndex)
            ?? sourceID.flatMap(UUID.init(uuidString:))
            ?? UUID()
    }

    private static func validateStructure(_ value: Any, depth: Int, nodes: inout Int) throws {
        guard depth <= maxJSONDepth else { throw ImportError.limitExceeded(Messages.displayJsonNestingIsTooDeepMessage()) }
        nodes += 1
        guard nodes <= maxJSONNodes else { throw ImportError.limitExceeded(Messages.displayJsonHasTooManyValuesMessage()) }
        if let dict = value as? [String: Any] {
            for (key, child) in dict {
                guard key.utf8.count <= 4_096 else { throw ImportError.limitExceeded(Messages.displayPropertyNameIsTooLongMessage()) }
                try validateStructure(child, depth: depth + 1, nodes: &nodes)
            }
        } else if let array = value as? [Any] {
            for child in array { try validateStructure(child, depth: depth + 1, nodes: &nodes) }
        } else if let string = value as? String, string.utf8.count > 1_048_576 {
            throw ImportError.limitExceeded(Messages.displayTextFieldIsOverMbMessage())
        }
    }

    private static func coordinatePairCount(_ geometry: [String: Any]) -> Int {
        switch geometry["type"] as? String {
        case "Point": return 1
        case "LineString": return (geometry["coordinates"] as? [[Any]])?.count ?? 0
        case "Polygon": return (geometry["coordinates"] as? [[[Any]]])?.reduce(0) { $0 + $1.count } ?? 0
        default: return 0
        }
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
                                       fallback: UUID,
                                       preserveValidID: Bool) -> UUID {
        let idStr = (props["tacticalmaps:layer_id"] as? String)
            ?? (props["layer_id"] as? String)
        // Case-insensitive id match (Android lowercase vs iOS uppercase UUIDs).
        if let idStr,
           let layer = existingLayersByID.first(where: { $0.key.caseInsensitiveCompare(idStr) == .orderedSame })?.value {
            return layer.id
        }
        let name = (props["tacticalmaps:layer"] as? String)
            ?? (props["layer_name"] as? String)
        if preserveValidID,
           let idStr,
           let uuid = UUID(uuidString: idStr) {
            let color = (props["tacticalmaps:layer_color"] as? String)
                ?? (props["layer_color"] as? String)
                ?? "#FFA500"
            let layer = DrawingLayer(id: uuid, name: name ?? L10n.text("Imported"), defaultColorHex: color)
            existingLayersByID[uuid.uuidString] = layer
            newLayers.append(layer)
            return uuid
        }
        // Before minting a new layer for an unknown id, try to adopt an existing
        // one with the same name. Otherwise devices with default layers under
        // different per-install ids proliferate duplicate "Friendly"/"Enemy"
        // layers on every import/sync. Ask me how I know.
        if let name,
           let match = existingLayersByID.values.first(where: { $0.name == name }) {
            return match.id
        }
        if let idStr,
           let uuid = UUID(uuidString: idStr) {
            let color = (props["tacticalmaps:layer_color"] as? String)
                ?? (props["layer_color"] as? String)
                ?? "#FFA500"
            let layer = DrawingLayer(id: uuid, name: name ?? L10n.text("Imported"), defaultColorHex: color)
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
                                     layerID: UUID,
                                     id: UUID) -> DrawingShape? {
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
        if let w = boundedDouble(props["stroke-width"], 0.1...100)
            ?? boundedDouble(props["stroke_width"], 0.1...100) {
            style.strokeWidth = w
        }
        if let o = boundedDouble(props["fill-opacity"], 0...1) { style.fillOpacity = o }
        // Dash style: shared namespaced key, falls back to Android legacy key.
        let strokeStyle = (props["tacticalmaps:stroke_style"] as? String) ?? (props["stroke_style"] as? String)
        if strokeStyle?.lowercased() == "dashed" { style.dashPattern = [8, 4] }
        if let lg = (props["tacticalmaps:line_graphic"] as? String).flatMap(LineGraphic.init(rawValue:)) {
            style.lineGraphic = lg
        }

        let name = props["name"] as? String
        let notes = props["description"] as? String
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
                                      layerID: UUID,
                                      id: UUID) -> Waypoint? {
        guard let c = geometry["coordinates"] as? [Double], c.count >= 2 else { return nil }
        let coord = CLLocationCoordinate2D(latitude: c[1], longitude: c[0])
        let name = (props["name"] as? String) ?? L10n.text("Imported")
        let notes = (props["description"] as? String) ?? (props["notes"] as? String)
        let elevation = boundedDouble(props["tacticalmaps:elevation_m"], -12_000...100_000)
            ?? boundedDouble(props["elevation_m"], -12_000...100_000)
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

        let rotation = boundedDouble(props["tacticalmaps:rotation_deg"], -3_600...3_600)
            ?? boundedDouble(props["rotation"], -3_600...3_600)
            ?? 0
        let scaleX = boundedDouble(props["tacticalmaps:scale_x"], 0.05...100)
            ?? boundedDouble(props["scale_x"], 0.05...100)
            ?? 1
        let scaleY = boundedDouble(props["tacticalmaps:scale_y"], 0.05...100)
            ?? boundedDouble(props["scale_y"], 0.05...100)
            ?? 1
        let taskColor = (props["tacticalmaps:task_color"] as? String)
            .flatMap { TaskColor(rawValue: $0.lowercased()) } ?? .black
        let higherFormation = UnitAmplifierText.normalized(
            props["tacticalmaps:higher_formation"] as? String,
            maximumLength: UnitAmplifierText.higherFormationMaxLength)
        let uniqueIdentifier = UnitAmplifierText.normalized(
            props["tacticalmaps:unique_identifier"] as? String,
            maximumLength: UnitAmplifierText.uniqueIdentifierMaxLength)
        let reinforcementStatus = (props["tacticalmaps:reinforcement_status"] as? String)
            .flatMap { ReinforcementStatus(rawValue: $0.lowercased()) } ?? .none
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
                        higherFormation: higherFormation,
                        uniqueIdentifier: uniqueIdentifier,
                        reinforcementStatus: reinforcementStatus,
                        layerID: layerID,
                        createdAt: createdAt ?? .now)
    }

    private static func parseGenericPoint(feature: [String: Any],
                                          geometry: [String: Any],
                                          props: [String: Any],
                                          layerID: UUID,
                                          id: UUID) -> Waypoint? {
        return parseWaypoint(feature: feature,
                             geometry: geometry,
                             props: props,
                             category: "generic",
                             layerID: layerID,
                             id: id)
    }

    private static func doubleValue(_ any: Any?) -> Double? {
        if let value = any as? Double { return value }
        if let value = any as? Int { return Double(value) }
        if let value = any as? NSNumber { return value.doubleValue }
        if let value = any as? String { return Double(value) }
        return nil
    }

    private static func boundedDouble(_ any: Any?, _ range: ClosedRange<Double>) -> Double? {
        guard let value = doubleValue(any), value.isFinite, range.contains(value) else { return nil }
        return value
    }

    private static func boolValue(_ any: Any?) -> Bool? {
        if let value = any as? Bool { return value }
        if let value = any as? NSNumber { return value.boolValue }
        if let value = any as? String { return Bool(value) }
        return nil
    }
}

struct ExternalImportCommitProgress {
    var batch: GeoJSONImporter.ExternalBatch
    var waypointStoreCommitted = false
    var drawingStoreCommitted = false
    var waypointCommit: BatchImportCommit?
    var drawingCommit: DrawingBatchImportCommit?
}

struct ExternalImportCommitReport {
    enum State: Equatable {
        case completed
        case failedBeforeAnyStore
        case partiallyCommitted
    }

    let state: State
    let progress: ExternalImportCommitProgress
    let pendingMessage: LocalizedMessage
    var message: String { pendingMessage.text }

    var insertedWaypointCount: Int { progress.waypointCommit?.insertedCount ?? 0 }
    var skippedWaypointCount: Int { progress.waypointCommit?.skippedExistingCount ?? 0 }
    var insertedDrawingCount: Int { progress.drawingCommit?.insertedDrawingCount ?? 0 }
    var skippedDrawingCount: Int { progress.drawingCommit?.skippedExistingDrawingCount ?? 0 }
}

/// Coordinates the intentionally non-atomic two-store import. The waypoint
/// store is committed first, and progress retains the already resolved batch
/// so a drawing-store failure can be retried without reparsing or reminting.
@MainActor
enum ExternalImportCommitter {
    static func attempt(_ initial: ExternalImportCommitProgress,
                        waypointStore: WaypointStore,
                        drawingStore: DrawingStore,
                        idFactory: () -> UUID = UUID.init) -> ExternalImportCommitReport {
        var progress = reconcileLiveCollisions(
            initial,
            waypointStore: waypointStore,
            drawingStore: drawingStore,
            idFactory: idFactory
        )
        let parsed = progress.batch.result

        if !progress.waypointStoreCommitted {
            do {
                progress.waypointCommit = try waypointStore.importBatch(
                    parsed.waypoints,
                    batchKey: progress.batch.batchKey
                )
                progress.waypointStoreCommitted = true
            } catch {
                return ExternalImportCommitReport(
                    state: .failedBeforeAnyStore,
                    progress: progress,
                    pendingMessage: Messages.displayNoImportedMissionObjectsWereSavedMessage("").withArgument(0, error.displayMessage)
                )
            }
        }

        if !progress.drawingStoreCommitted {
            do {
                progress.drawingCommit = try drawingStore.importBatch(
                    layers: parsed.newLayers,
                    drawings: parsed.drawings,
                    batchKey: progress.batch.batchKey
                )
                progress.drawingStoreCommitted = true
            } catch {
                let waypointCommit = progress.waypointCommit
                    ?? BatchImportCommit(insertedCount: 0, skippedExistingCount: 0)
                let handledWaypointCount = waypointCommit.insertedCount
                    + waypointCommit.skippedExistingCount
                let partial = handledWaypointCount > 0 && progress.waypointStoreCommitted
                let prefix = partial
                    ? Messages.displayWaypointStoreCommittedNewObjectsAndSkippedAlreadyPresentMessage(DisplayFormat.number(Double(waypointCommit.insertedCount), decimals: 0), DisplayFormat.number(Double(waypointCommit.skippedExistingCount), decimals: 0))
                    : Messages.displayNoImportedMissionObjectsWereSaved3176accfMessage()
                return ExternalImportCommitReport(
                    state: partial ? .partiallyCommitted : .failedBeforeAnyStore,
                    progress: progress,
                    pendingMessage: Messages.syncRecoveryDetailMessage("", "").withArgument(0, prefix).withArgument(1, error.displayMessage)
                )
            }
        }

        return ExternalImportCommitReport(
            state: .completed,
            progress: progress,
            pendingMessage: completionMessage(progress)
        )
    }

    private struct ObjectKey: Hashable {
        let kind: ExternalImportIdentityResolver.ObjectKind
        let id: UUID
    }

    /// Parsing runs off-main, so local or Sync mutations can land before the
    /// MainActor commit. Reconcile only the still-uncommitted half against the
    /// live global namespace, in original input order, and retain every remint
    /// in the batch used by later retries.
    private static func reconcileLiveCollisions(
        _ initial: ExternalImportCommitProgress,
        waypointStore: WaypointStore,
        drawingStore: DrawingStore,
        idFactory: () -> UUID
    ) -> ExternalImportCommitProgress {
        var progress = initial
        guard !progress.waypointStoreCommitted || !progress.drawingStoreCommitted else {
            return progress
        }

        var occupied = Set(waypointStore.waypoints.map(\.id))
            .union(drawingStore.shapes.map(\.id))
        var replacements: [ObjectKey: UUID] = [:]
        var resolutions = progress.batch.identityResolutions

        func isUncommitted(_ kind: ExternalImportIdentityResolver.ObjectKind) -> Bool {
            switch kind {
            case .waypoint: return !progress.waypointStoreCommitted
            case .drawing: return !progress.drawingStoreCommitted
            }
        }

        func nextUniqueID() -> UUID {
            while true {
                let candidate = idFactory()
                if occupied.insert(candidate).inserted { return candidate }
            }
        }

        for index in resolutions.indices where isUncommitted(resolutions[index].kind) {
            let resolution = resolutions[index]
            let key = ObjectKey(kind: resolution.kind, id: resolution.resolvedID)
            let resolvedID: UUID
            if occupied.contains(resolution.resolvedID) {
                resolvedID = nextUniqueID()
                resolutions[index] = ExternalImportIdentityResolver.Resolution(
                    caseKey: resolution.caseKey,
                    kind: resolution.kind,
                    sourceID: resolution.sourceID,
                    resolvedID: resolvedID,
                    resolution: .commitTimeCollision
                )
            } else {
                occupied.insert(resolution.resolvedID)
                resolvedID = resolution.resolvedID
            }
            replacements[key] = resolvedID
        }

        // Hand-built batches in tests and future importers may not carry an
        // identity record. Reconcile those objects after the ordered records.
        if !progress.waypointStoreCommitted {
            for waypoint in progress.batch.result.waypoints {
                let key = ObjectKey(kind: .waypoint, id: waypoint.id)
                guard replacements[key] == nil else { continue }
                if occupied.contains(waypoint.id) {
                    replacements[key] = nextUniqueID()
                } else {
                    occupied.insert(waypoint.id)
                    replacements[key] = waypoint.id
                }
            }
        }
        if !progress.drawingStoreCommitted {
            for drawing in progress.batch.result.drawings {
                let key = ObjectKey(kind: .drawing, id: drawing.id)
                guard replacements[key] == nil else { continue }
                if occupied.contains(drawing.id) {
                    replacements[key] = nextUniqueID()
                } else {
                    occupied.insert(drawing.id)
                    replacements[key] = drawing.id
                }
            }
        }

        var result = progress.batch.result
        result.waypoints = result.waypoints.map { waypoint in
            guard let id = replacements[ObjectKey(kind: .waypoint, id: waypoint.id)],
                  id != waypoint.id else { return waypoint }
            return Waypoint(id: id,
                            name: waypoint.name,
                            notes: waypoint.notes,
                            latitude: waypoint.latitude,
                            longitude: waypoint.longitude,
                            elevation: waypoint.elevation,
                            kind: waypoint.kind,
                            rotation: waypoint.rotation,
                            scaleX: waypoint.scaleX,
                            scaleY: waypoint.scaleY,
                            taskColor: waypoint.taskColor,
                            higherFormation: waypoint.higherFormation,
                            uniqueIdentifier: waypoint.uniqueIdentifier,
                            reinforcementStatus: waypoint.reinforcementStatus,
                            layerID: waypoint.layerID,
                            createdAt: waypoint.createdAt)
        }
        result.drawings = result.drawings.map { drawing in
            guard let id = replacements[ObjectKey(kind: .drawing, id: drawing.id)],
                  id != drawing.id else { return drawing }
            return DrawingShape(id: id,
                                name: drawing.name,
                                notes: drawing.notes,
                                kind: drawing.kind,
                                coordinates: drawing.coordinates,
                                style: drawing.style,
                                createdAt: drawing.createdAt,
                                layerID: drawing.layerID,
                                rotation: drawing.rotation,
                                scaleX: drawing.scaleX,
                                scaleY: drawing.scaleY)
        }
        progress.batch = GeoJSONImporter.ExternalBatch(
            batchKey: progress.batch.batchKey,
            result: result,
            identityResolutions: resolutions
        )
        return progress
    }

    private static func completionMessage(_ progress: ExternalImportCommitProgress) -> LocalizedMessage {
        let waypoint = progress.waypointCommit
            ?? BatchImportCommit(insertedCount: 0, skippedExistingCount: 0)
        let drawing = progress.drawingCommit
            ?? DrawingBatchImportCommit(insertedLayerCount: 0,
                                        insertedDrawingCount: 0,
                                        skippedExistingDrawingCount: 0)
        let skipped = waypoint.skippedExistingCount + drawing.skippedExistingDrawingCount
        let waypointCount = Messages.newWaypointCountMessage(waypoint.insertedCount)
        let drawingCount = Messages.newDrawingCountMessage(drawing.insertedDrawingCount)
        let layerCount = Messages.newLayerCountMessage(drawing.insertedLayerCount)
        let skippedText = DisplayFormat.number(Double(skipped), decimals: 0)
        let message: LocalizedMessage
        if drawing.insertedLayerCount > 0 && skipped > 0 {
            message = Messages.importCompleteLayersSkippedSummaryMessage("", "", "", skippedText).withArgument(2, layerCount)
        } else if drawing.insertedLayerCount > 0 {
            message = Messages.importCompleteLayersSummaryMessage("", "", "").withArgument(2, layerCount)
        } else if skipped > 0 {
            message = Messages.importCompleteSkippedSummaryMessage("", "", skippedText)
        } else {
            message = Messages.importCompleteSummaryMessage("", "")
        }
        return message.withArgument(0, waypointCount).withArgument(1, drawingCount)
    }
}
