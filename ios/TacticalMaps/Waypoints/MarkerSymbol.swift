import SwiftUI
import UIKit

/// Non-military symbol sets for the app's other audiences: airsoft / milsim /
/// paintball, search & rescue, and a plain civilian POI set. A marker is a set
/// + a symbol id within it + a colour, rendered as a coloured badge with a glyph.
/// Kept deliberately simple (Codable, syncable) so it rides the same waypoint
/// pipeline as APP-6 military symbols.
struct MarkerSymbol: Codable, Hashable {
    var set: MarkerSet
    var symbolID: String
    var colorHex: String

    /// The catalog entry, or a safe fallback if an unknown id was persisted.
    var entry: MarkerCatalog.Entry {
        MarkerCatalog.entry(set: set, id: symbolID)
    }
}

enum MarkerSet: String, Codable, CaseIterable, Hashable {
    case airsoft
    case sar
    case poi

    var displayName: String {
        switch self {
        case .airsoft: return "Airsoft / Milsim"
        case .sar:     return "Search & Rescue"
        case .poi:     return "Points of Interest"
        }
    }
}

/// The symbol catalog: for each set, the ordered list of symbols with a display
/// name, an SF Symbol glyph, and a sensible default colour. Adding a symbol is a
/// one-line entry here.
enum MarkerCatalog {
    struct Entry: Hashable {
        let id: String
        let name: String
        let sfSymbol: String
        let defaultColorHex: String
    }

    /// Airsoft team colours the picker offers (the team IS the colour).
    static let teamColors: [(name: String, hex: String)] = [
        ("Red", "#E23B3B"), ("Blue", "#3B7BE0"), ("Green", "#3BC85A"),
        ("Yellow", "#EBC12E"), ("Orange", "#F2872E")
    ]

    static let airsoft: [Entry] = [
        // Team marker - colour carries the team, glyph is a person.
        .init(id: "team", name: "Team Member", sfSymbol: "person.fill", defaultColorHex: "#3B7BE0"),
        // Objectives
        .init(id: "capture", name: "Capture Point", sfSymbol: "target", defaultColorHex: "#F2872E"),
        .init(id: "flag", name: "Flag / CTF", sfSymbol: "flag.fill", defaultColorHex: "#3B7BE0"),
        .init(id: "objective", name: "Objective", sfSymbol: "scope", defaultColorHex: "#F2872E"),
        .init(id: "spawn", name: "Spawn", sfSymbol: "arrow.up.circle.fill", defaultColorHex: "#3BC85A"),
        .init(id: "respawn", name: "Respawn", sfSymbol: "arrow.clockwise.circle.fill", defaultColorHex: "#3BC85A"),
        .init(id: "safezone", name: "Safe Zone", sfSymbol: "checkmark.shield.fill", defaultColorHex: "#3BC85A"),
        .init(id: "staging", name: "Staging", sfSymbol: "tent.fill", defaultColorHex: "#8A93A6"),
        .init(id: "chrono", name: "Chrono / Marshalling", sfSymbol: "gauge.medium", defaultColorHex: "#8A93A6"),
        .init(id: "deadzone", name: "Dead Zone", sfSymbol: "xmark.octagon.fill", defaultColorHex: "#E23B3B"),
        .init(id: "oob", name: "Out of Bounds", sfSymbol: "nosign", defaultColorHex: "#E23B3B"),
        // Roles
        .init(id: "rifleman", name: "Rifleman", sfSymbol: "figure.walk", defaultColorHex: "#3B7BE0"),
        .init(id: "marksman", name: "Marksman / Sniper", sfSymbol: "scope", defaultColorHex: "#3B7BE0"),
        .init(id: "support", name: "Support / Gunner", sfSymbol: "flame.fill", defaultColorHex: "#3B7BE0"),
        .init(id: "medic", name: "Medic", sfSymbol: "cross.case.fill", defaultColorHex: "#E23B3B"),
        .init(id: "squadlead", name: "Squad Lead", sfSymbol: "star.fill", defaultColorHex: "#EBC12E"),
        .init(id: "grenadier", name: "Grenadier", sfSymbol: "burst.fill", defaultColorHex: "#F2872E"),
        .init(id: "breacher", name: "Breacher", sfSymbol: "hammer.fill", defaultColorHex: "#8A93A6"),
    ]

    static let sar: [Entry] = [
        .init(id: "pls", name: "Point Last Seen (PLS)", sfSymbol: "eye.fill", defaultColorHex: "#E23B3B"),
        .init(id: "lkp", name: "Last Known Position (LKP)", sfSymbol: "mappin.slash", defaultColorHex: "#E23B3B"),
        .init(id: "ipp", name: "Initial Planning Point (IPP)", sfSymbol: "mappin.and.ellipse", defaultColorHex: "#F2872E"),
        .init(id: "segment", name: "Search Segment", sfSymbol: "square.dashed", defaultColorHex: "#3B7BE0"),
        .init(id: "assignment", name: "Assignment", sfSymbol: "list.bullet.rectangle.fill", defaultColorHex: "#3B7BE0"),
        .init(id: "clue", name: "Clue / Find", sfSymbol: "magnifyingglass", defaultColorHex: "#EBC12E"),
        .init(id: "subject", name: "Subject Found", sfSymbol: "person.fill.checkmark", defaultColorHex: "#3BC85A"),
        .init(id: "casualty", name: "Casualty", sfSymbol: "cross.case.fill", defaultColorHex: "#E23B3B"),
        .init(id: "evac", name: "Evac Route", sfSymbol: "arrow.triangle.turn.up.right.diamond.fill", defaultColorHex: "#3BC85A"),
        .init(id: "icp", name: "Command Post (ICP)", sfSymbol: "flag.checkered", defaultColorHex: "#111417"),
        .init(id: "base", name: "Base", sfSymbol: "house.fill", defaultColorHex: "#3B7BE0"),
        .init(id: "staging", name: "Staging", sfSymbol: "tent.fill", defaultColorHex: "#8A93A6"),
        .init(id: "helispot", name: "Helispot / LZ", sfSymbol: "h.square.fill", defaultColorHex: "#3BC85A"),
        .init(id: "water", name: "Water", sfSymbol: "drop.fill", defaultColorHex: "#3B7BE0"),
        .init(id: "medical", name: "Medical", sfSymbol: "cross.case.fill", defaultColorHex: "#E23B3B"),
        .init(id: "hazard", name: "Hazard", sfSymbol: "exclamationmark.triangle.fill", defaultColorHex: "#EBC12E"),
        .init(id: "containment", name: "Containment", sfSymbol: "shield.lefthalf.filled", defaultColorHex: "#F2872E"),
        .init(id: "roadblock", name: "Road Block", sfSymbol: "hand.raised.fill", defaultColorHex: "#E23B3B"),
    ]

    static let poi: [Entry] = [
        .init(id: "medical", name: "Medical", sfSymbol: "cross.case.fill", defaultColorHex: "#E23B3B"),
        .init(id: "water", name: "Water", sfSymbol: "drop.fill", defaultColorHex: "#3B7BE0"),
        .init(id: "comms", name: "Comms", sfSymbol: "antenna.radiowaves.left.and.right", defaultColorHex: "#3B7BE0"),
        .init(id: "parking", name: "Parking", sfSymbol: "parkingsign", defaultColorHex: "#3B7BE0"),
        .init(id: "hazard", name: "Hazard", sfSymbol: "exclamationmark.triangle.fill", defaultColorHex: "#EBC12E"),
        .init(id: "checkpoint", name: "Checkpoint", sfSymbol: "checkmark.shield.fill", defaultColorHex: "#3BC85A"),
        .init(id: "pin", name: "Marker", sfSymbol: "mappin", defaultColorHex: "#EBC12E"),
    ]

    static func entries(for set: MarkerSet) -> [Entry] {
        switch set {
        case .airsoft: return airsoft
        case .sar:     return sar
        case .poi:     return poi
        }
    }

    static func entry(set: MarkerSet, id: String) -> Entry {
        entries(for: set).first { $0.id == id } ?? entries(for: set)[0]
    }
}

/// Renders a marker as a round coloured badge with a white glyph and a thin white
/// ring, so it reads on any basemap. One shared renderer for all three sets.
enum MarkerSymbolRenderer {
    static func image(for marker: MarkerSymbol, size: CGFloat = 34) -> UIImage {
        let entry = marker.entry
        let color = UIColor(hex: marker.colorHex)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
        return renderer.image { ctx in
            let cg = ctx.cgContext
            let inset: CGFloat = 1.5
            let disc = CGRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2)
            // Soft drop shadow, then filled disc + white ring.
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 2,
                         color: UIColor.black.withAlphaComponent(0.45).cgColor)
            color.setFill()
            cg.fillEllipse(in: disc)
            cg.setShadow(offset: .zero, blur: 0, color: nil)
            UIColor.white.setStroke()
            let ring = UIBezierPath(ovalIn: disc)
            ring.lineWidth = 2
            ring.stroke()
            // Glyph, ~58% of the badge, white.
            let glyphPt = size * 0.5
            let cfg = UIImage.SymbolConfiguration(pointSize: glyphPt, weight: .semibold)
            if let glyph = UIImage(systemName: entry.sfSymbol, withConfiguration: cfg)?
                .withTintColor(.white, renderingMode: .alwaysOriginal) {
                let gw = min(glyph.size.width, size * 0.62)
                let gh = min(glyph.size.height, size * 0.62)
                glyph.draw(in: CGRect(x: (size - gw) / 2, y: (size - gh) / 2, width: gw, height: gh))
            }
        }
    }
}
