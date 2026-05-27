import Foundation
import SwiftUI

/// A named group of drawings. Layers let the user separate, hide, or wipe
/// out an entire category of work in one shot — e.g. friendly graphics on
/// one layer, hostile on another. Each new drawing is stamped with the
/// active layer's id; visibility and deletion cascade from layer to its
/// shapes via `DrawingStore`.
struct DrawingLayer: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    /// When `false` the layer's shapes are skipped during map rendering.
    /// `DrawingStore` ANDs this with `LayerVisibility.drawingsVisible` (the
    /// master kill-switch).
    var visible: Bool
    /// Default stroke colour suggested for shapes added to this layer.
    /// Used by `DrawingSession` when the user hasn't explicitly picked a
    /// colour yet.
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

    /// Stable id used when migrating old `drawings.json` files: any shape
    /// that was written before the multi-layer schema gets re-assigned to
    /// the "Drawings" default layer using this constant id so the
    /// migration is deterministic and idempotent.
    static let legacyFallbackID = UUID(uuidString: "00000000-0000-0000-0000-000000000001")!

    /// Seed layers created on a fresh install. Order is the order in
    /// which they'll be listed in the UI. Colours track the rough APP-6C
    /// affiliation palette but at a saturation that reads on satellite.
    static let seedDefaults: [DrawingLayer] = [
        DrawingLayer(id: legacyFallbackID,
                     name: "Friendly",
                     defaultColorHex: "#4DA6FF"),
        DrawingLayer(name: "Hostile",
                     defaultColorHex: "#E63946"),
        DrawingLayer(name: "Unknown",
                     defaultColorHex: "#FFB000"),
        DrawingLayer(name: "Civilian",
                     defaultColorHex: "#2A9D8F"),
    ]
}
