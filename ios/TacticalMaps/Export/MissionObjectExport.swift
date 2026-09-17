import Foundation

/// Truthful boundary for the combined GeoJSON action. Recorded routes are not
/// accepted here; they remain a separate GPX workflow.
enum MissionObjectExport {
    static var actionTitle: String { L10n.text("Export All Mission Objects") }
    static var shareTitle: String { actionTitle }
    static let fileNamePrefix = "TacMap-MissionObjects"

    static func geoJSON(waypoints: [Waypoint],
                        drawings: [DrawingShape],
                        layers: [DrawingLayer]) throws -> String {
        let base = try GeoJSONExporter.export(
            waypoints: waypoints,
            drawings: drawings,
            layers: layers
        )
        guard let data = base.data(using: .utf8),
              var collection = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { throw CocoaError(.fileReadCorruptFile) }
        collection["tacticalmaps:layers"] = layers.map { layer in
            [
                "id": layer.id.uuidString.lowercased(),
                "name": layer.name,
                "color": layer.defaultColorHex.uppercased(),
                "visible": layer.visible,
                "created_at": ISO8601DateFormatter().string(from: layer.createdAt),
            ] as [String: Any]
        }
        let encoded = try JSONSerialization.data(
            withJSONObject: collection,
            options: [.prettyPrinted, .sortedKeys]
        )
        guard let result = String(data: encoded, encoding: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        return result
    }

    static func exportToFile(waypoints: [Waypoint],
                             drawings: [DrawingShape],
                             layers: [DrawingLayer]) throws -> URL {
        let json = try geoJSON(waypoints: waypoints, drawings: drawings, layers: layers)
        let stamp = ISO8601DateFormatter().string(from: .now)
            .replacingOccurrences(of: ":", with: "-")
        let url = try ExportFileSecurity.freshURL(
            fileName: "\(fileNamePrefix)-\(stamp).geojson"
        )
        guard let bytes = json.data(using: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        try bytes.write(to: url, options: [.atomic, .completeFileProtection])
        try ExportFileSecurity.protect(url)
        return url
    }
}
