import SwiftUI

/// 12-colour palette offered to the user when drawing. Hues are chosen to
/// stay legible on both Apple satellite imagery and rasterised GeoPDFs at
/// the line widths the app uses (1.5–10 pt). Hex strings are stored in
/// `DrawingStyle.strokeColorHex` and round-trip through GeoJSON export
/// unchanged.
enum DrawingPalette {

    struct Swatch: Identifiable, Hashable {
        let id: String      // == hex; keeps SwiftUI ForEach stable
        let name: String
        let hex: String
        var color: Color { Color(hex: hex) }

        init(_ name: String, _ hex: String) {
            self.id   = hex
            self.name = name
            self.hex  = hex
        }
    }

    /// Default colour for a fresh drawing session.
    static let `default` = swatches[0]

    /// Ordered list of available swatches. Order is the grid order the
    /// palette menu will use — 4 columns × 3 rows.
    static let swatches: [Swatch] = [
        .init("Orange",  "#FFA500"),
        .init("Red",     "#E03434"),
        .init("Crimson", "#B30000"),
        .init("Yellow",  "#FFD500"),
        .init("Green",   "#2ECC40"),
        .init("Teal",    "#00C7BE"),
        .init("Cyan",    "#5AC8FA"),
        .init("Blue",    "#1F75FE"),
        .init("Purple",  "#AF52DE"),
        .init("Magenta", "#FF2D92"),
        .init("White",   "#FFFFFF"),
        .init("Black",   "#1A1A1A"),
    ]

    /// Look up a swatch by hex (case-insensitive). Used to map a persisted
    /// `DrawingStyle.strokeColorHex` back to its display name.
    static func swatch(forHex hex: String) -> Swatch? {
        let needle = hex.uppercased()
        return swatches.first { $0.hex.uppercased() == needle }
    }
}
