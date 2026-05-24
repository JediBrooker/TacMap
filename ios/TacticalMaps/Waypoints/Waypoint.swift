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

/// What a waypoint represents. Three top-level cases:
///   - `.generic`            : plain field marker
///   - `.military(spec)`     : APP-6C unit symbol (affiliation × echelon × function)
///   - `.controlMeasure(…)`  : tactical point-symbol control measure (FUP, RV, LZ, etc.)
enum WaypointKind: Hashable, Codable {
    case generic
    case military(MilitarySymbolSpec)
    case controlMeasure(TacticalControlMeasure)

    // MARK: Tagged Codable

    private enum CodingKeys: String, CodingKey { case type, spec, control }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let type = try c.decode(String.self, forKey: .type)
        switch type {
        case "generic":
            self = .generic
        case "military":
            self = .military(try c.decode(MilitarySymbolSpec.self, forKey: .spec))
        case "controlMeasure":
            self = .controlMeasure(try c.decode(TacticalControlMeasure.self, forKey: .control))
        default:
            self = .generic   // safe fallback for old/unknown persisted data
        }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        switch self {
        case .generic:
            try c.encode("generic", forKey: .type)
        case .military(let spec):
            try c.encode("military", forKey: .type)
            try c.encode(spec, forKey: .spec)
        case .controlMeasure(let m):
            try c.encode("controlMeasure", forKey: .type)
            try c.encode(m, forKey: .control)
        }
    }

    // MARK: Display

    /// Short, human-friendly summary.
    var displayName: String {
        switch self {
        case .generic:                return "Waypoint"
        case .military(let spec):
            // e.g. "Friendly Infantry Platoon"
            let prefix = spec.affiliation.displayName
            let role   = spec.function == .unspecified ? "" : spec.function.displayName + " "
            return "\(prefix) \(role)\(spec.echelon.displayName)"
        case .controlMeasure(let m):  return m.displayName
        }
    }

    /// Two-line category label used in the edit sheet.
    var categoryDisplayName: String {
        switch self {
        case .generic:         return "Field Marker"
        case .military:        return "Military Unit (APP-6C)"
        case .controlMeasure:  return "Tactical Control Measure"
        }
    }

    // MARK: Symbol accessors

    /// Non-nil for military kinds — used by the map and picker icon view.
    var militarySpec: MilitarySymbolSpec? {
        if case .military(let s) = self { return s }
        return nil
    }

    /// Tactical control measure (if any).
    var controlMeasure: TacticalControlMeasure? {
        if case .controlMeasure(let m) = self { return m }
        return nil
    }

    /// SF Symbol fallback for kinds without a custom drawing
    /// (generic + tactical control measures).
    var sfSymbol: String {
        switch self {
        case .generic:                  return "mappin"
        case .military:                 return "shield.fill"   // unused once militarySpec is wired
        case .controlMeasure(let m):    return m.sfSymbol
        }
    }

    /// Tint used when the kind falls back to an SF Symbol pin.
    var tint: Color {
        switch self {
        case .generic:        return .yellow
        case .military:       return .blue
        case .controlMeasure: return .black
        }
    }
}

// MARK: - Tactical control measures (point-symbol subset of APP-6C)

enum TacticalControlMeasure: String, Codable, Hashable, CaseIterable {
    case axisOfAssault          // arrow showing direction of advance
    case supportByFire          // SBF position
    case attackByFire           // ABF position
    case formUpPoint            // FUP
    case rvPoint                // Rendezvous
    case axp                    // Ambulance Exchange Point
    case lz                     // Landing Zone

    var displayName: String {
        switch self {
        case .axisOfAssault: return "Axis of Assault"
        case .supportByFire: return "Support by Fire (SBF)"
        case .attackByFire:  return "Attack by Fire (ABF)"
        case .formUpPoint:   return "Form Up Point (FUP)"
        case .rvPoint:       return "Rendezvous (RV)"
        case .axp:           return "Ambulance Exchange (AXP)"
        case .lz:            return "Landing Zone (LZ)"
        }
    }

    var sfSymbol: String {
        switch self {
        case .axisOfAssault: return "arrow.up.right.circle.fill"
        case .supportByFire: return "scope"
        case .attackByFire:  return "flame.fill"
        case .formUpPoint:   return "square.stack.fill"
        case .rvPoint:       return "person.3.fill"
        case .axp:           return "cross.case.fill"
        case .lz:            return "h.square.fill"
        }
    }
}
