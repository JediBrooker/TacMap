import Foundation
import CoreLocation
import SwiftUI

/// A user-drawn shape (point, line, or area). All coordinates are WGS84 so the
/// shape renders the same on Apple satellite, a GeoPDF, or a calibrated PDF.
struct DrawingShape: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String?
    var notes: String?
    var kind: DrawingKind
    /// Vertices as drawn. Polygons get their ring closed on export.
    /// Rendering goes through `effectiveCoordinates` which applies
    /// rotation/scale around centroid non-destructively, so originals
    /// are preserved and you can always reset to 1x/0deg.
    var coordinates: [Coordinate2D]
    var style: DrawingStyle
    var createdAt: Date
    /// Which layer this shape belongs to. Required but Codable-optional
    /// so old drawings.json files from before multi-layer still decode
    /// (DrawingStore re-stamps them with the default layer id).
    var layerID: UUID
    /// Rotation around the centroid, in degrees clockwise. 0 = as drawn.
    var rotation: Double
    /// Horizontal scale around the centroid (1 = as drawn). Longitude axis.
    var scaleX: Double
    /// Vertical scale around the centroid (1 = as drawn). Latitude axis.
    var scaleY: Double

    init(id: UUID = UUID(),
         name: String? = nil,
         notes: String? = nil,
         kind: DrawingKind,
         coordinates: [Coordinate2D] = [],
         style: DrawingStyle = .default,
         createdAt: Date = .now,
         layerID: UUID = UUID(),
         rotation: Double = 0,
         scaleX: Double = 1,
         scaleY: Double = 1) {
        self.id = id
        self.name = name
        self.notes = notes
        self.kind = kind
        self.coordinates = coordinates
        self.style = style
        self.createdAt = createdAt
        self.layerID = layerID
        self.rotation = rotation
        self.scaleX = scaleX
        self.scaleY = scaleY
    }

    var clCoordinates: [CLLocationCoordinate2D] {
        coordinates.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
    }

    /// Coords with rotation + scale applied around centroid. Used by
    /// map renderer and tap hit-test. Single-point = just the original.
    var effectiveCoordinates: [Coordinate2D] {
        guard coordinates.count > 1,
              rotation != 0 || scaleX != 1 || scaleY != 1
        else { return coordinates }
        let lat0 = coordinates.map(\.latitude ).reduce(0, +) / Double(coordinates.count)
        let lon0 = coordinates.map(\.longitude).reduce(0, +) / Double(coordinates.count)
        // Negate so positive = CLOCKWISE on screen, matching Android
        // (Drawing.kt effectivePoints uses -rotationDegrees) and both
        // platforms' control-measure renderers. Without this iOS drawings
        // rotated the wrong way compared to everything else.
        let rad = -rotation * .pi / 180
        let cosR = cos(rad), sinR = sin(rad)
        // cos(lat) correction so 90deg rotation looks square on screen
        // at non-equatorial latitudes (without it shapes stretch b/c
        // 1deg longitude < 1deg latitude away from equator)
        let lonScale = max(cos(lat0 * .pi / 180), 0.001)
        return coordinates.map { c in
            let dx = (c.longitude - lon0) * lonScale * scaleX
            let dy = (c.latitude  - lat0)              * scaleY
            let rx = dx * cosR - dy * sinR
            let ry = dx * sinR + dy * cosR
            return Coordinate2D(
                latitude:  lat0 + ry,
                longitude: lon0 + rx / lonScale
            )
        }
    }

    var clEffectiveCoordinates: [CLLocationCoordinate2D] {
        effectiveCoordinates.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
    }

    /// Once user starts editing vertices, any rotation/scale from the
    /// controls card is no longer meaningful. The new vertex set IS the
    /// shape now. Apply transform to coords and reset rotation/scale.
    mutating func bakeTransformIfNeeded() {
        guard rotation != 0 || scaleX != 1 || scaleY != 1 else { return }
        coordinates = effectiveCoordinates
        rotation = 0
        scaleX = 1
        scaleY = 1
    }

    /// Move vertex at `index`. Bakes pending rotation/scale first so
    /// the dragged handle's position matches what gets stored.
    mutating func setEffectiveVertex(_ index: Int, to coord: Coordinate2D) {
        bakeTransformIfNeeded()
        guard index >= 0 && index < coordinates.count else { return }
        coordinates[index] = coord
    }

    /// Insert new vertex at `index` (shifts existing verts right).
    /// Used when user drags a midpoint handle.
    mutating func insertEffectiveVertex(_ coord: Coordinate2D, at index: Int) {
        bakeTransformIfNeeded()
        guard index >= 0 && index <= coordinates.count else { return }
        coordinates.insert(coord, at: index)
    }

    /// Drop the vertex at `index`. No-op when removing would put the
    /// shape below its kind's minimum vertex count (a polygon needs
    /// at least 3 points, a polyline at least 2).
    @discardableResult
    mutating func removeEffectiveVertex(at index: Int) -> Bool {
        let minCount = kind.minimumVertices
        guard coordinates.count > minCount,
              index >= 0, index < coordinates.count
        else { return false }
        bakeTransformIfNeeded()
        coordinates.remove(at: index)
        return true
    }

    /// Where to anchor the name-label on the map.
    /// Polygons = centroid, polylines = midpoint of central segment,
    /// points = the point itself.
    var labelAnchor: CLLocationCoordinate2D? {
        let coords = effectiveCoordinates
        guard !coords.isEmpty else { return nil }
        switch kind {
        case .point:
            return CLLocationCoordinate2D(latitude: coords[0].latitude,
                                          longitude: coords[0].longitude)
        case .polyline, .freedraw:
            guard coords.count >= 2 else { return nil }
            let mid = coords.count / 2
            let a = coords[mid - 1], b = coords[mid]
            return CLLocationCoordinate2D(latitude: (a.latitude + b.latitude) / 2,
                                          longitude: (a.longitude + b.longitude) / 2)
        case .polygon:
            let lat = coords.map(\.latitude ).reduce(0, +) / Double(coords.count)
            let lon = coords.map(\.longitude).reduce(0, +) / Double(coords.count)
            return CLLocationCoordinate2D(latitude: lat, longitude: lon)
        }
    }

    // backward compat: old drawings.json has no layerID / rotation /
    // scaleX / scaleY. Decode with sensible defaults.
    private enum CodingKeys: String, CodingKey {
        case id, name, notes, kind, coordinates, style, createdAt, layerID
        case rotation, scaleX, scaleY
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id          = try c.decode(UUID.self,        forKey: .id)
        self.name        = try c.decodeIfPresent(String.self, forKey: .name)
        self.notes       = try c.decodeIfPresent(String.self, forKey: .notes)
        self.kind        = try c.decode(DrawingKind.self, forKey: .kind)
        self.coordinates = try c.decode([Coordinate2D].self, forKey: .coordinates)
        self.style       = try c.decode(DrawingStyle.self,   forKey: .style)
        self.createdAt   = try c.decode(Date.self,           forKey: .createdAt)
        self.layerID     = try c.decodeIfPresent(UUID.self,   forKey: .layerID) ?? DrawingLayer.legacyFallbackID
        self.rotation    = try c.decodeIfPresent(Double.self, forKey: .rotation) ?? 0
        self.scaleX      = try c.decodeIfPresent(Double.self, forKey: .scaleX)   ?? 1
        self.scaleY      = try c.decodeIfPresent(Double.self, forKey: .scaleY)   ?? 1
    }
}

enum DrawingKind: String, Codable, CaseIterable, Hashable {
    case point, polyline, polygon, freedraw

    var sfSymbol: String {
        switch self {
        case .point:    return "mappin"
        case .polyline: return "line.diagonal.arrow"
        case .polygon:  return "hexagon"
        case .freedraw: return "scribble"
        }
    }

    var displayName: String {
        switch self {
        case .point:    return "Point"
        case .polyline: return "Line"
        case .polygon:  return "Area"
        case .freedraw: return "Free Draw"
        }
    }

    /// Minimum vertex count to enable the Finish action.
    var minimumVertices: Int {
        switch self {
        case .point:    return 1
        case .polyline: return 2
        case .polygon:  return 3
        case .freedraw: return 2
        }
    }
}

/// WGS84 lat/lon pair. Plain doubles so its trivially Codable
/// (CLLocationCoordinate2D isn't Codable, Apple only added that in iOS 17).
struct Coordinate2D: Codable, Hashable {
    var latitude: Double
    var longitude: Double
}

/// Tactical line-graphic for a polyline, decorates stroke as a NATO
/// operational graphic. `.plain` (or nil) is just a regular line.
enum LineGraphic: String, Codable, Hashable, CaseIterable {
    case plain            // ordinary line
    case phaseLine        // dashed control line (PL / report line)
    case boundary         // line with perpendicular tick marks
    case forwardEdge      // crenellated FLOT / FEBA / forward line of troops
    case axisOfAdvance    // line with an arrowhead at the end

    var displayName: String {
        switch self {
        case .plain:         return "Plain line"
        case .phaseLine:     return "Phase line"
        case .boundary:      return "Boundary"
        case .forwardEdge:   return "Forward line (FLOT)"
        case .axisOfAdvance: return "Axis of advance"
        }
    }
    var symbolName: String {
        switch self {
        case .plain:         return "line.diagonal"
        case .phaseLine:     return "ellipsis"
        case .boundary:      return "xmark"
        case .forwardEdge:   return "waveform.path"
        case .axisOfAdvance: return "arrow.up.right"
        }
    }
}

/// Follows Mapbox simplestyle-spec keys (stroke, stroke-width, fill,
/// fill-opacity) so GeoJSON export just works in GitHub, geojson.io,
/// Mapbox, Felt, Leaflet, etc.
struct DrawingStyle: Codable, Hashable {
    /// Stroke colour as a `#RRGGBB` hex string.
    var strokeColorHex: String = "#FFA500"  // tactical orange
    /// Fill colour for polygons (#RRGGBB, opacity is separate).
    var fillColorHex: String? = "#FFA500"
    /// Stroke width in points.
    var strokeWidth: Double = 3.0
    /// Fill opacity (0-1). Defaults to 0.2 for translucent area fill.
    var fillOpacity: Double = 0.2
    /// Optional dash pattern (in points, alternating on/off). Solid line if nil.
    var dashPattern: [Double]? = nil
    /// Tactical line-graphic decoration (FLOT, boundary, axis…). nil = plain.
    var lineGraphic: LineGraphic? = nil

    static let `default` = DrawingStyle()

}
