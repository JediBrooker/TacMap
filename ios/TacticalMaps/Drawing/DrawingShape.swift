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
    /// Ordered vertices as drawn. For polygons, the ring is closed
    /// implicitly on export. Rendering uses `effectiveCoordinates`,
    /// which applies `rotation` and `scaleX`/`scaleY` non-destructively
    /// around the shape's centroid — so the original vertices are
    /// preserved and the controls can be reset back to 1×, 0°.
    var coordinates: [Coordinate2D]
    var style: DrawingStyle
    var createdAt: Date
    /// Which DrawingLayer this shape belongs to. Required, but kept Codable-
    /// optional so older `drawings.json` files written before multi-layer
    /// support can still decode (DrawingStore re-stamps them with the
    /// default layer's id on first read).
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

    /// Coordinates with rotation + scale applied around the shape's
    /// centroid. Used by the map renderer and tap hit-test. For a single-
    /// point shape this is just the original coordinate.
    var effectiveCoordinates: [Coordinate2D] {
        guard coordinates.count > 1,
              rotation != 0 || scaleX != 1 || scaleY != 1
        else { return coordinates }
        let lat0 = coordinates.map(\.latitude ).reduce(0, +) / Double(coordinates.count)
        let lon0 = coordinates.map(\.longitude).reduce(0, +) / Double(coordinates.count)
        let rad = rotation * .pi / 180
        let cosR = cos(rad), sinR = sin(rad)
        // Apply a cos(latitude) correction so a 90° rotation actually
        // looks square on screen at non-equatorial latitudes (without it
        // the shape stretches because 1° of longitude < 1° of latitude
        // away from the equator).
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

    // Backward-compat: legacy drawings.json files have no `layerID` /
    // rotation / scaleX / scaleY. Decode them with sensible defaults.
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
    case point, polyline, polygon

    var sfSymbol: String {
        switch self {
        case .point:    return "mappin"
        case .polyline: return "scribble.variable"
        case .polygon:  return "hexagon"
        }
    }

    var displayName: String {
        switch self {
        case .point:    return "Point"
        case .polyline: return "Line"
        case .polygon:  return "Area"
        }
    }

    /// Minimum vertex count to enable the Finish action.
    var minimumVertices: Int {
        switch self {
        case .point:    return 1
        case .polyline: return 2
        case .polygon:  return 3
        }
    }
}

/// WGS84 lat/lon pair. Plain doubles so the model is trivially Codable
/// (CLLocationCoordinate2D isn't, and Apple's retroactive conformance landed
/// only in iOS 17 SDKs).
struct Coordinate2D: Codable, Hashable {
    var latitude: Double
    var longitude: Double
}

/// Style follows the Mapbox simplestyle-spec keys (`stroke`, `stroke-width`,
/// `fill`, `fill-opacity`) so the GeoJSON export renders out-of-the-box in
/// GitHub, geojson.io, Mapbox, Felt, Leaflet, etc.
struct DrawingStyle: Codable, Hashable {
    /// Stroke colour as a `#RRGGBB` hex string.
    var strokeColorHex: String = "#FFA500"  // tactical orange
    /// Optional fill colour for polygons (`#RRGGBB` — opacity handled separately).
    var fillColorHex: String? = "#FFA500"
    /// Stroke width in points.
    var strokeWidth: Double = 3.0
    /// Fill opacity (0–1). Defaults to 0.2 for a translucent area fill.
    var fillOpacity: Double = 0.2
    /// Optional dash pattern (in points, alternating on/off). Solid line if nil.
    var dashPattern: [Double]? = nil

    static let `default` = DrawingStyle()

}
