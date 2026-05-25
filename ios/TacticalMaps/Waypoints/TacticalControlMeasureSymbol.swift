import SwiftUI
import UIKit

/// Renders a `TacticalControlMeasure` from the bundled PNG / SVG asset
/// under `Assets.xcassets/AppSymbols/`. Pure black symbol on a
/// transparent background, optionally rotated around its centre.
///
/// A soft white halo (stacked shadows) is drawn behind the silhouette
/// so the black ink reads against any basemap — satellite, terrain,
/// dark imported PDF, etc. The halo is invisible against a white
/// background (preview / picker rows) so the symbol still looks
/// "clean" inside the edit sheet.
struct TacticalControlMeasureSymbolView: View {
    let measure: TacticalControlMeasure
    /// Clockwise rotation in degrees. 0 = canonical orientation.
    var rotation: Double = 0
    var size: CGFloat = 56
    /// Extra room reserved around the symbol for the halo bleed.
    /// Exposed as a constant so the map renderer can pad its
    /// `UIImage` bounds to match (otherwise the halo gets clipped).
    static let haloPadding: CGFloat = 6

    var body: some View {
        // The rotation lives inside a fixed-size frame so the produced
        // bitmap is always `size+2*haloPadding` square — independent
        // of rotation angle. (Without the outer frame, `.rotationEffect`
        // grows the view's intrinsic size at non-square-multiple
        // angles, producing different-sized images per rotation —
        // which downstream looked like the map was scaling the symbol.)
        let canvas = size + 2 * Self.haloPadding
        return ZStack {
            Image("AppSymbols/\(measure.assetName)")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(.black)
                .frame(width: size, height: size)
                // Three stacked white shadows build up an outer glow
                // without washing out the silhouette. Radius 2 gives
                // ~4pt visible halo width, which clears the symbol on
                // satellite imagery.
                .shadow(color: .white, radius: 2)
                .shadow(color: .white, radius: 2)
                .shadow(color: .white, radius: 1)
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
