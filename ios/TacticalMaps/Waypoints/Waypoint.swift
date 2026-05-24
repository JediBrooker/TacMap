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

/// All waypoint kinds, grouped by category. Order within each category is the
/// order shown in the picker.
enum WaypointKind: String, Codable, CaseIterable, Hashable {
    // MARK: Generic field-craft markers
    case generic, camp, water, observation, dropZone, hazard

    // MARK: Friendly units (APP-6 blue rectangle, echelon shown above)
    case friendlySection, friendlyPlatoon, friendlyCompany, friendlyRegiment, friendlyBrigade

    // MARK: Enemy units (APP-6 red diamond, echelon shown above)
    case enemySection, enemyPlatoon, enemyCompany, enemyRegiment, enemyBrigade

    // MARK: Tactical control measures (black)
    case axisOfAssault          // arrow showing direction of advance
    case supportByFire          // SBF position
    case attackByFire           // ABF position
    case formUpPoint            // FUP
    case rvPoint                // Rendezvous
    case axp                    // Ambulance Exchange Point
    case lz                     // Landing Zone

    // MARK: - Display

    var displayName: String {
        switch self {
        case .generic:           return "Waypoint"
        case .camp:              return "Camp"
        case .water:             return "Water Source"
        case .observation:       return "Observation Point"
        case .dropZone:          return "Drop Zone"
        case .hazard:            return "Hazard"

        case .friendlySection:   return "Friendly Section"
        case .friendlyPlatoon:   return "Friendly Platoon"
        case .friendlyCompany:   return "Friendly Company"
        case .friendlyRegiment:  return "Friendly Regiment"
        case .friendlyBrigade:   return "Friendly Brigade"

        case .enemySection:      return "Enemy Section"
        case .enemyPlatoon:      return "Enemy Platoon"
        case .enemyCompany:      return "Enemy Company"
        case .enemyRegiment:     return "Enemy Regiment"
        case .enemyBrigade:      return "Enemy Brigade"

        case .axisOfAssault:     return "Axis of Assault"
        case .supportByFire:     return "Support by Fire (SBF)"
        case .attackByFire:      return "Attack by Fire (ABF)"
        case .formUpPoint:       return "Form Up Point (FUP)"
        case .rvPoint:           return "Rendezvous (RV)"
        case .axp:               return "Ambulance Exchange (AXP)"
        case .lz:                return "Landing Zone (LZ)"
        }
    }

    var category: WaypointCategory {
        switch self {
        case .generic, .camp, .water, .observation, .dropZone, .hazard:
            return .field
        case .friendlySection, .friendlyPlatoon, .friendlyCompany,
             .friendlyRegiment, .friendlyBrigade:
            return .friendly
        case .enemySection, .enemyPlatoon, .enemyCompany,
             .enemyRegiment, .enemyBrigade:
            return .enemy
        case .axisOfAssault, .supportByFire, .attackByFire,
             .formUpPoint, .rvPoint, .axp, .lz:
            return .tactical
        }
    }

    /// SF Symbol used as the marker glyph. Pin colour is `tint`.
    var sfSymbol: String {
        switch self {
        case .generic:           return "mappin"
        case .camp:              return "triangle.fill"
        case .water:             return "drop.fill"
        case .observation:       return "binoculars.fill"
        case .dropZone:          return "square.dashed"
        case .hazard:            return "exclamationmark.triangle.fill"

        case .friendlySection:   return "1.circle.fill"
        case .friendlyPlatoon:   return "2.circle.fill"
        case .friendlyCompany:   return "rectangle.fill"
        case .friendlyRegiment:  return "rectangle.stack.fill"
        case .friendlyBrigade:   return "xmark.shield.fill"

        case .enemySection:      return "1.circle.fill"
        case .enemyPlatoon:      return "2.circle.fill"
        case .enemyCompany:      return "diamond.fill"
        case .enemyRegiment:     return "diamond.tophalf.filled"
        case .enemyBrigade:      return "xmark.diamond.fill"

        case .axisOfAssault:     return "arrow.up.right.circle.fill"
        case .supportByFire:     return "scope"
        case .attackByFire:      return "flame.fill"
        case .formUpPoint:       return "square.stack.fill"
        case .rvPoint:           return "person.3.fill"
        case .axp:               return "cross.case.fill"
        case .lz:                return "h.square.fill"
        }
    }

    /// Marker pin colour.
    var tint: Color {
        switch category {
        case .field:    return fieldTint
        case .friendly: return .blue
        case .enemy:    return .red
        case .tactical: return .black
        }
    }

    /// Per-kind override for the generic field markers, where each subtype
    /// has its own established colour convention.
    private var fieldTint: Color {
        switch self {
        case .generic:     return .yellow
        case .camp:        return .green
        case .water:       return .blue
        case .observation: return .orange
        case .dropZone:    return .yellow
        case .hazard:      return .red
        default:           return .yellow
        }
    }
}

enum WaypointCategory: String, CaseIterable, Hashable {
    case field, friendly, enemy, tactical

    var displayName: String {
        switch self {
        case .field:    return "Field Markers"
        case .friendly: return "Friendly Units (Blue)"
        case .enemy:    return "Enemy Units (Red)"
        case .tactical: return "Tactical Control Measures"
        }
    }

    var kinds: [WaypointKind] {
        WaypointKind.allCases.filter { $0.category == self }
    }
}
