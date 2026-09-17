import Foundation
import SwiftUI

/// Named group of drawings. Basically lets users seperate, hide, or nuke
/// an entire category at once - e.g. friendly graphics on one layer,
/// hostile on another. New drawings get stamped with the active layer id;
/// visibility and deletion cascade via `DrawingStore`.
struct DrawingLayer: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    /// When `false` the layer's shapes are skipped during map rendering.
    /// `DrawingStore` ANDs this with `LayerVisibility.drawingsVisible` (the
    /// master kill-switch).
    var visible: Bool
    /// Default stroke colour for shapes on this layer. `DrawingSession`
    /// uses it when user hasn't picked a colour yet.
    var defaultColorHex: String
    var createdAt: Date

    init(id: UUID = UUID(),
         name: String,
         visible: Bool = true,
         defaultColorHex: String,
         createdAt: Date = .now) {
        self.id = id
        self.name = name
        self.visible = visible
        self.defaultColorHex = defaultColorHex
        self.createdAt = createdAt
    }

    /// Hardcoded id for migrating old drawings.json - shapes from before
    /// multi-layer just get shoved onto this fallback layer. Using a
    /// constant so the migration is repeatable and doesn't create dupes.
    static let legacyFallbackID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!
    static let hostileDefaultID = UUID(uuidString: "00000000-0000-0000-0000-000000000002")!
    static let unknownDefaultID = UUID(uuidString: "00000000-0000-0000-0000-000000000003")!
    static let civilianDefaultID = UUID(uuidString: "00000000-0000-0000-0000-000000000004")!

    /// Only deterministic built-in IDs with their original catalogue names are dynamic.
    /// Custom/legacy names stay verbatim, including a user-created layer named Friendly.
    private var builtinNameKey: String? {
        switch id {
        case Self.legacyFallbackID: "Friendly"
        case Self.hostileDefaultID: "Hostile"
        case Self.unknownDefaultID: "Unknown"
        case Self.civilianDefaultID: "Civilian"
        default: nil
        }
    }

    var displayName: String {
        guard let key = builtinNameKey else { return name }
        return L10n.isCatalogValue(name, for: key) ? L10n.text(key) : name
    }

    private static let seedCreatedAt = Date.now

    /// Default layers on fresh install, listed in UI order. Colours are
    /// loosely APP-6C affiliation palette but bumped to read on satellite.
    static var seedDefaults: [DrawingLayer] { [
        DrawingLayer(id: legacyFallbackID,
                     name: L10n.text("Friendly"),
                     defaultColorHex: "#4DA6FF", createdAt: seedCreatedAt),
        DrawingLayer(id: hostileDefaultID,
                     name: L10n.text("Hostile"),
                     defaultColorHex: "#E63946", createdAt: seedCreatedAt),
        DrawingLayer(id: unknownDefaultID,
                     name: L10n.text("Unknown"),
                     defaultColorHex: "#FFB000", createdAt: seedCreatedAt),
        DrawingLayer(id: civilianDefaultID,
                     name: L10n.text("Civilian"),
                     defaultColorHex: "#2A9D8F", createdAt: seedCreatedAt),
    ] }
}
