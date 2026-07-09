import SwiftUI

/// 12-colour palette for drawing. Hues picked to stay legible on satellite
/// imagery and rasterised GeoPDFs at the stroke widths we use (1.5-10 pt).
/// Hex strings go into `DrawingStyle.strokeColorHex` and survive GeoJSON
/// round-trip unchanged.
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

    /// Swatches in grid order (4 cols x 3 rows in the palette menu).
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

    /// Look up swatch by hex (case-insensitive) to get its display name.
    static func swatch(forHex hex: String) -> Swatch? {
        let needle = hex.uppercased()
        return swatches.first { $0.hex.uppercased() == needle }
    }
}
