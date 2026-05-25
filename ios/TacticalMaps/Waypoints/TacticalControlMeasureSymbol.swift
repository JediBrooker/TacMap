import SwiftUI
import UIKit

/// Renders a `TacticalControlMeasure` from the bundled PNG / SVG asset
/// under `Assets.xcassets/AppSymbols/`. Pure black symbol on a
/// transparent background with a single thin white shadow baked in
/// so the symbol reads against any basemap (satellite, terrain, dark
/// PDF). The halo is intentionally small (~2pt visible width at 1×)
/// so it doesn't dominate when the symbol is transform-scaled up by
/// the map's zoom-tracking logic.
struct TacticalControlMeasureSymbolView: View {
    let measure: TacticalControlMeasure
    /// Clockwise rotation in degrees. 0 = canonical orientation.
    var rotation: Double = 0
    var size: CGFloat = 56
    /// Extra room reserved around the symbol so the halo isn't clipped
    /// by the rendered bitmap's bounds. The annotation-view-size math
    /// in `LockedSizeAnnotationView.setSymbolImage` keys off the
    /// image's reported size, so this padding feeds straight through.
    static let haloPadding: CGFloat = 3

    var body: some View {
        let canvas = size + 2 * Self.haloPadding
        return ZStack {
            Image("AppSymbols/\(measure.assetName)")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(.black)
                .frame(width: size, height: size)
                // Single low-radius shadow — visible enough to outline
                // the silhouette on any background, gentle enough that
                // when the symbol is scaled 5× by the map's zoom-tracking
                // transform the halo doesn't read as a thick fuzz.
                .shadow(color: .white, radius: 1.5)
                .rotationEffect(.degrees(rotation))
        }
        .frame(width: canvas, height: canvas)
    }
}

@MainActor
enum TacticalControlMeasureRenderer {
    /// Canonical render size of every produced UIImage. All per-waypoint
    /// size variation (and all zoom-driven scaling) is applied via the
    /// annotation view's `transform` at render time — the renderer here
    /// always returns a base-size bitmap so the cache only ever holds
    /// (measure × rotation) variants, not (measure × rotation × scale).
    static let baseSize: CGFloat = 64

    private struct Key: Hashable {
        let measure: TacticalControlMeasure
        let rotationCentideg: Int   // 0..35999, 1/100 of a degree
    }
    private static var cache: [Key: UIImage] = [:]

    static func image(for measure: TacticalControlMeasure,
                      rotation: Double = 0) -> UIImage? {
        let normalized = ((rotation.truncatingRemainder(dividingBy: 360)) + 360)
            .truncatingRemainder(dividingBy: 360)
        let key = Key(
            measure: measure,
            rotationCentideg: Int((normalized * 100).rounded())
        )
        if let cached = cache[key] { return cached }
        let view = TacticalControlMeasureSymbolView(
            measure: measure,
            rotation: normalized,
            size: baseSize
        )
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        let img = renderer.uiImage
        if let img { cache[key] = img }
        return img
    }
}
