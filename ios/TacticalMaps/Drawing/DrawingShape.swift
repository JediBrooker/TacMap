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
    /// Ordered vertices. For polygons, the ring is closed implicitly on export.
    var coordinates: [Coordinate2D]
    var style: DrawingStyle
    var createdAt: Date

    init(id: UUID = UUID(),
         name: String? = nil,
         notes: String? = nil,
         kind: DrawingKind,
         coordinates: [Coordinate2D] = [],
         style: DrawingStyle = .default,
         createdAt: Date = .now) {
        self.id = id
        self.name = name
        self.notes = notes
        self.kind = kind
        self.coordinates = coordinates
        self.style = style
        self.createdAt = createdAt
    }

    var clCoordinates: [CLLocationCoordinate2D] {
        coordinates.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
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
