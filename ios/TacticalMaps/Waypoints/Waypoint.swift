import Foundation
import CoreLocation
import SwiftUI

/// A user-placed point of interest. Stored in WGS84 as lat/lon doubles (matches
/// the GeoJSON export schema and the Android model). The computed `coordinate`
/// adapts to CoreLocation/MapKit APIs.
struct Waypoint: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var notes: String?
    var latitude: Double
    var longitude: Double
    var elevation: Double?      // metres above sea level (optional)
    var kind: WaypointKind
    var createdAt: Date

    init(id: UUID = UUID(),
         name: String,
         notes: String? = nil,
         latitude: Double,
         longitude: Double,
         elevation: Double? = nil,
         kind: WaypointKind = .generic,
         createdAt: Date = .now) {
        self.id = id
        self.name = name
        self.notes = notes
        self.latitude = latitude
        self.longitude = longitude
        self.elevation = elevation
        self.kind = kind
        self.createdAt = createdAt
    }

    /// Convenience for callers working with CoreLocation/MapKit.
    init(id: UUID = UUID(),
         name: String,
         notes: String? = nil,
         coordinate: CLLocationCoordinate2D,
         elevation: Double? = nil,
         kind: WaypointKind = .generic,
         createdAt: Date = .now) {
        self.init(id: id, name: name, notes: notes,
                  latitude: coordinate.latitude, longitude: coordinate.longitude,
                  elevation: elevation, kind: kind, createdAt: createdAt)
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var subtitle: String? {
        elevation.map { String(format: "%.0f m", $0) }
    }
}

enum WaypointKind: String, Codable, CaseIterable, Hashable {
    case generic, camp, water, observation, dropZone, hazard

    var sfSymbol: String {
        switch self {
        case .generic:     return "mappin"
        case .camp:        return "triangle.fill"
        case .water:       return "drop.fill"
        case .observation: return "binoculars.fill"
        case .dropZone:    return "square.dashed"
        case .hazard:      return "exclamationmark.triangle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .generic:     return .yellow
        case .camp:        return .green
        case .water:       return .blue
        case .observation: return .orange
        case .dropZone:    return .yellow
        case .hazard:      return .red
        }
    }
}
