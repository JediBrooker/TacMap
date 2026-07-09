import Foundation
import CoreLocation
import SwiftUI

/// User-placed point of interest. Stored as WGS84 lat/lon doubles (matches
/// GeoJSON export and Android model). The computed `coordinate` just wraps
/// it for CoreLocation/MapKit.
struct Waypoint: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var notes: String?
    var latitude: Double
    var longitude: Double
    var elevation: Double?      // metres above sea level (optional)
    var kind: WaypointKind
    /// Symbol rotation in degrees (0-360, clockwise). Only matters for
    /// tactical control measures where orientation conveys direction
    /// (axis of advance, ambush, ABF, etc). Ignored otherwise.
    var rotation: Double
    /// Horizontal multiplier on symbol's default render size.
    /// 1.0 = canonical (~64pt on screen). UI range is 0.1-20x.
    /// Independent of scaleY so user can stretch a task graphic
    /// wider/thinner. Only applied to control measures.
    var scaleX: Double
    /// Vertical multiplier. See `scaleX`.
    var scaleY: Double
    /// Tint for tactical task graphics (control measures). Recolours
    /// the black line-art glyph. Default is black, others follow
    /// APP-6 affiliation palette. Ignored for units + generic markers.
    var taskColor: TaskColor
    /// Which layer this waypoint sits on. Shared model with DrawingShape,
    /// so toggling a layer hides both drawings and waypoints.
    /// Back-compat: old saves without layerID get legacyFallbackID
    /// which lands them in the default "Friendly" layer.
    var layerID: UUID
    var createdAt: Date

    init(id: UUID = UUID(),
         name: String,
         notes: String? = nil,
         latitude: Double,
         longitude: Double,
         elevation: Double? = nil,
         kind: WaypointKind = .generic,
         rotation: Double = 0,
         scaleX: Double = 1.0,
         scaleY: Double = 1.0,
         taskColor: TaskColor = .black,
         layerID: UUID = DrawingLayer.legacyFallbackID,
         createdAt: Date = .now) {
        self.id = id
        self.name = name
        self.notes = notes
        self.latitude = latitude
        self.longitude = longitude
        self.elevation = elevation
        self.kind = kind
        self.rotation = rotation
        self.scaleX = scaleX
        self.scaleY = scaleY
        self.taskColor = taskColor
        self.layerID = layerID
        self.createdAt = createdAt
    }

    /// Convenience init for CoreLocation/MapKit callers.
    init(id: UUID = UUID(),
         name: String,
         notes: String? = nil,
         coordinate: CLLocationCoordinate2D,
         elevation: Double? = nil,
         kind: WaypointKind = .generic,
         rotation: Double = 0,
         scaleX: Double = 1.0,
         scaleY: Double = 1.0,
         taskColor: TaskColor = .black,
         layerID: UUID = DrawingLayer.legacyFallbackID,
         createdAt: Date = .now) {
        self.init(id: id, name: name, notes: notes,
                  latitude: coordinate.latitude, longitude: coordinate.longitude,
                  elevation: elevation, kind: kind, rotation: rotation,
                  scaleX: scaleX, scaleY: scaleY, taskColor: taskColor,
                  layerID: layerID, createdAt: createdAt)
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    var subtitle: String? {
        elevation.map { String(format: "%.0f m", $0) }
    }

    /// Compact identity used by MapContainerView's refresh fingerprint.
    /// Same fingerprint = same annotation image, so map can skip rebuild.
    var kindFingerprint: String { kind.fingerprint }

    // MARK: Codable (custom to allow back-compat with files that pre-date `rotation`)

    private enum CodingKeys: String, CodingKey {
        case id, name, notes, latitude, longitude, elevation, kind,
             rotation, scale, scaleX, scaleY, taskColor, layerID, createdAt
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(UUID.self, forKey: .id)
        self.name = try c.decode(String.self, forKey: .name)
        self.notes = try c.decodeIfPresent(String.self, forKey: .notes)
        self.latitude = try c.decode(Double.self, forKey: .latitude)
        self.longitude = try c.decode(Double.self, forKey: .longitude)
        self.elevation = try c.decodeIfPresent(Double.self, forKey: .elevation)
        self.kind = try c.decode(WaypointKind.self, forKey: .kind)
        self.rotation = try c.decodeIfPresent(Double.self, forKey: .rotation) ?? 0
        // Migration: older saves had a single `scale` field, so if
        // scaleX/scaleY aren't present just populate both from that
        // (or default to 1.0).
        let legacyScale = try c.decodeIfPresent(Double.self, forKey: .scale)
        self.scaleX = try c.decodeIfPresent(Double.self, forKey: .scaleX)
            ?? legacyScale ?? 1.0
        self.scaleY = try c.decodeIfPresent(Double.self, forKey: .scaleY)
            ?? legacyScale ?? 1.0
        self.taskColor = try c.decodeIfPresent(TaskColor.self, forKey: .taskColor) ?? .black
        self.layerID = try c.decodeIfPresent(UUID.self, forKey: .layerID)
            ?? DrawingLayer.legacyFallbackID
        self.createdAt = try c.decode(Date.self, forKey: .createdAt)
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(name, forKey: .name)
        try c.encodeIfPresent(notes, forKey: .notes)
        try c.encode(latitude, forKey: .latitude)
        try c.encode(longitude, forKey: .longitude)
        try c.encodeIfPresent(elevation, forKey: .elevation)
        try c.encode(kind, forKey: .kind)
        try c.encode(rotation, forKey: .rotation)
        try c.encode(scaleX, forKey: .scaleX)
        try c.encode(scaleY, forKey: .scaleY)
        try c.encode(taskColor, forKey: .taskColor)
        try c.encode(layerID, forKey: .layerID)
        try c.encode(createdAt, forKey: .createdAt)
    }
}

/// What a waypoint represents:
///   .generic             - plain field marker
///   .military(spec)      - APP-6C unit symbol (affiliation x echelon x function)
///   .controlMeasure(..)  - tactical point symbol (FUP, RV, LZ, etc.)
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

    /// Short human-readable summary.
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

    /// Non-nil for military kinds. Used by map + picker icon view.
    var militarySpec: MilitarySymbolSpec? {
        if case .military(let s) = self { return s }
        return nil
    }

    /// Tactical control measure (if any).
    var controlMeasure: TacticalControlMeasure? {
        if case .controlMeasure(let m) = self { return m }
        return nil
    }

    /// SF Symbol fallback for kinds without a custom drawing.
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

    /// Compact repr for the map's refresh fingerprint. Distinguishes
    /// kinds that render to different images so we skip rebuilding
    /// when nothing visible changed.
    var fingerprint: String {
        switch self {
        case .generic:
            return "g"
        case .military(let s):
            return "m|\(s.affiliation.rawValue)|\(s.echelon.rawValue)|\(s.function.rawValue)|\(s.isHeadquarters)"
        case .controlMeasure(let m):
            return "c|\(m.rawValue)"
        }
    }
}

// MARK: - Task graphic colour

/// Colour applied to tactical task graphics (control measures). Bundled
/// glyphs are pure-black line art on transparent, renderer template-tints
/// them. Black is default; the other four follow APP-6 affiliation palette
/// (saturated for legibility on satellite + PDFs, b/c the affiliation
/// frame fills are too pastel for line art). Keep in sync with Android's
/// TaskColor.
enum TaskColor: String, Codable, Hashable, CaseIterable {
    case black, blue, red, green, yellow

    /// Tint for the glyph.
    var color: Color {
        switch self {
        case .black:  return .black
        case .blue:   return Color(red: 0x0E / 255, green: 0x5F / 255, blue: 0xD8 / 255)  // #0E5FD8
        case .red:    return Color(red: 0xD8 / 255, green: 0x28 / 255, blue: 0x1F / 255)  // #D8281F
        case .green:  return Color(red: 0x1E / 255, green: 0x8A / 255, blue: 0x34 / 255)  // #1E8A34
        case .yellow: return Color(red: 0xE2 / 255, green: 0xA4 / 255, blue: 0x00 / 255)  // #E2A400
        }
    }

    /// Picker label - colour + APP-6 affiliation meaning.
    var label: String {
        switch self {
        case .black:  return "Black"
        case .blue:   return "Blue (Friendly)"
        case .red:    return "Red (Hostile)"
        case .green:  return "Green (Neutral)"
        case .yellow: return "Yellow (Unknown)"
        }
    }
}

// MARK: - Tactical control measures (point-symbol subset of APP-6C)

/// Tactical mission tasks and control-measure point symbols.
/// Each case maps to a bundled asset under AppSymbols/<rawValue>.
/// Most are cropped from NATO MPSOTC Table E-I (Tactical graphics),
/// except assemblyArea and formUpPoint which are hand-rolled SVGs.
enum TacticalControlMeasure: String, Codable, Hashable, CaseIterable {
    // ---- Mission tasks (cropped from PDF spec) ----
    case block, breach, bypass, canalise, clear, contain
    case counterattack, counterattackByFire, delay, destroy
    case disrupt, fix, interdict, isolate, neutralise
    case occupy, penetrate, reliefInPlace, retain, secure
    case screen, `guard`, cover, seize, withdraw, withdrawUnderPressure
    // ---- Control-measure point symbols ----
    case landingZone, ccp, observationPostRecon
    case axisOfMainAttack, axisOfSupportingAttack
    case attackByFire, supportByFire, ambush
    case antipersonnelMinefield, turn
    // ---- Custom ----
    case assemblyArea, formUpPoint

    var displayName: String {
        switch self {
        case .block:                  return "Block"
        case .breach:                 return "Breach"
        case .bypass:                 return "Bypass"
        case .canalise:               return "Canalise"
        case .clear:                  return "Clear"
        case .contain:                return "Contain"
        case .counterattack:          return "Counter-Attack"
        case .counterattackByFire:    return "Counter-Attack by Fire"
        case .delay:                  return "Delay"
        case .destroy:                return "Destroy"
        case .disrupt:                return "Disrupt"
        case .fix:                    return "Fix"
        case .interdict:              return "Interdict"
        case .isolate:                return "Isolate"
        case .neutralise:             return "Neutralise"
        case .occupy:                 return "Occupy"
        case .penetrate:              return "Penetrate"
        case .reliefInPlace:          return "Relief in Place"
        case .retain:                 return "Retain"
        case .secure:                 return "Secure"
        case .screen:                 return "Screen"
        case .`guard`:                  return "Guard"
        case .cover:                  return "Cover"
        case .seize:                  return "Seize"
        case .withdraw:               return "Withdraw"
        case .withdrawUnderPressure:  return "Withdraw Under Pressure"
        case .landingZone:            return "Landing Zone"
        case .ccp:                    return "Casualty Collection Point"
        case .observationPostRecon:   return "Observation Post (Recon)"
        case .axisOfMainAttack:       return "Axis of Main Attack"
        case .axisOfSupportingAttack: return "Axis of Supporting Attack"
        case .attackByFire:           return "Attack by Fire"
        case .supportByFire:          return "Support by Fire"
        case .ambush:                 return "Ambush"
        case .antipersonnelMinefield: return "Anti-Personnel Minefield"
        case .turn:                   return "Turn"
        case .assemblyArea:           return "Assembly Area"
        case .formUpPoint:            return "Form-Up Point"
        }
    }

    /// Basename of the bundled image in `Assets.xcassets/AppSymbols/`.
    var assetName: String { rawValue }

    /// Picker order, alphabetised by displayName. Mirrors Android's
    /// TacticalControlMeasure.pickerEntries.
    static let pickerEntries: [TacticalControlMeasure] =
        allCases.sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
}
