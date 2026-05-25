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
        case .controlMeasure:           return "flag.fill"
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

/// MIL-STD-2525D / APP-6 tactical control measures and mission tasks,
/// rendered via SVG assets generated from the official US Army C5ISR
/// renderer (mil-sym-ts, MIT-licensed). 37 cases — flat list, no
/// sub-categorisation.
///
/// `assetName` is the basename inside `Assets.xcassets/AppSymbols/` and
/// `sidc` is the underlying MIL-STD-2525D 8-char symbol ID for export.
enum TacticalControlMeasure: String, Codable, Hashable, CaseIterable {
    // ---- Mission tasks ----
    case block, breach, bypass, canalize, clear
    case counterattack, counterattackByFire, delay, destroy, disrupt
    case fix, followAndAssume, followAndSupport, interdict, isolate
    case neutralize, occupy, penetrate, reliefInPlace, retire
    case secure, cover, `guard`, screen, seize
    case withdraw, withdrawUnderPressure
    case cordonAndKnock, cordonAndSearch, suppress
    // ---- Control-measure point/area symbols ----
    case axisOfAdvance
    case supportByFire, attackByFire
    case landingZone, assemblyArea, rallyPoint, ambulanceExchange

    var displayName: String {
        switch self {
        case .block:                  return "Block"
        case .breach:                 return "Breach"
        case .bypass:                 return "Bypass"
        case .canalize:               return "Canalize"
        case .clear:                  return "Clear"
        case .counterattack:          return "Counter-Attack"
        case .counterattackByFire:    return "Counter-Attack by Fire"
        case .delay:                  return "Delay"
        case .destroy:                return "Destroy"
        case .disrupt:                return "Disrupt"
        case .fix:                    return "Fix"
        case .followAndAssume:        return "Follow and Assume"
        case .followAndSupport:       return "Follow and Support"
        case .interdict:              return "Interdict"
        case .isolate:                return "Isolate"
        case .neutralize:             return "Neutralise"
        case .occupy:                 return "Occupy"
        case .penetrate:              return "Penetrate"
        case .reliefInPlace:          return "Relief in Place"
        case .retire:                 return "Retire"
        case .secure:                 return "Secure"
        case .cover:                  return "Cover"
        case .guard:                  return "Guard"
        case .screen:                 return "Screen"
        case .seize:                  return "Seize"
        case .withdraw:               return "Withdraw"
        case .withdrawUnderPressure:  return "Withdraw Under Pressure"
        case .cordonAndKnock:         return "Cordon and Knock"
        case .cordonAndSearch:        return "Cordon and Search"
        case .suppress:               return "Suppress"
        case .axisOfAdvance:          return "Axis of Advance"
        case .supportByFire:          return "Support by Fire"
        case .attackByFire:           return "Attack by Fire"
        case .landingZone:            return "Landing Zone"
        case .assemblyArea:           return "Assembly Area"
        case .rallyPoint:             return "Rally Point"
        case .ambulanceExchange:      return "Ambulance Exchange Point"
        }
    }

    /// Basename of the bundled SVG in `Assets.xcassets/AppSymbols/`.
    var assetName: String { rawValue }

    /// MIL-STD-2525D 8-char symbol ID for export.
    var sidc: String {
        switch self {
        case .block:                  return "25340100"
        case .breach:                 return "25340200"
        case .bypass:                 return "25340300"
        case .canalize:               return "25340400"
        case .clear:                  return "25340500"
        case .counterattack:          return "25340600"
        case .counterattackByFire:    return "25340700"
        case .delay:                  return "25340800"
        case .destroy:                return "25340900"
        case .disrupt:                return "25341000"
        case .fix:                    return "25341100"
        case .followAndAssume:        return "25341200"
        case .followAndSupport:       return "25341300"
        case .interdict:              return "25341400"
        case .isolate:                return "25341500"
        case .neutralize:             return "25341600"
        case .occupy:                 return "25341700"
        case .penetrate:              return "25341800"
        case .reliefInPlace:          return "25341900"
        case .retire:                 return "25342000"
        case .secure:                 return "25342100"
        case .cover:                  return "25342201"
        case .guard:                  return "25342202"
        case .screen:                 return "25342203"
        case .seize:                  return "25342300"
        case .withdraw:               return "25342400"
        case .withdrawUnderPressure:  return "25342500"
        case .cordonAndKnock:         return "25342600"
        case .cordonAndSearch:        return "25342700"
        case .suppress:               return "25342800"
        case .axisOfAdvance:          return "25151400"
        case .supportByFire:          return "25152100"
        case .attackByFire:           return "25152000"
        case .landingZone:            return "25150800"
        case .assemblyArea:           return "25150200"
        case .rallyPoint:             return "25131400"
        case .ambulanceExchange:      return "25320101"
        }
    }
}
