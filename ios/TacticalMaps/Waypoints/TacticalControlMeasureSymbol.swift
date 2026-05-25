import SwiftUI
import UIKit

/// Renders a `TacticalControlMeasure` from the bundled PNG / SVG asset
/// under `Assets.xcassets/AppSymbols/`. Pure black symbol on a
/// transparent background with a sharp 2pt white outline baked in
/// so the symbol reads against any basemap (satellite, terrain, dark
/// PDF).
///
/// The outline is built from eight `.shadow(radius: 0, x: ±2, y: ±2)`
/// passes — zero-blur, offset in every cardinal + diagonal direction.
/// The accumulated alpha makes a crisp ~2pt stroke around the
/// silhouette, far more legible than a single soft shadow.
struct TacticalControlMeasureSymbolView: View {
    let measure: TacticalControlMeasure
    /// Clockwise rotation in degrees. 0 = canonical orientation.
    var rotation: Double = 0
    var size: CGFloat = 56
    /// Extra room reserved around the symbol so the outline isn't
    /// clipped by the rendered bitmap's bounds.
    static let haloPadding: CGFloat = 3

    var body: some View {
        let canvas = size + 2 * Self.haloPadding
        return ZStack {
            applyHalo {
                Image("AppSymbols/\(measure.assetName)")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .foregroundStyle(.black)
                    .frame(width: size, height: size)
            }
            .rotationEffect(.degrees(rotation))
        }
        .frame(width: canvas, height: canvas)
    }

    /// Stack of zero-radius white shadows that adds a crisp 1pt
    /// outline around any opaque pixels in the content. Eight
    /// directions ensures the outline is uniform.
    ///
    /// 1pt was picked over 2pt because the outline scales 1:1 with
    /// the symbol via the annotation view's transform — at a 5×
    /// zoom-tracking scale a 1pt baked stroke is 5pt on screen
    /// (still visible, not overwhelming); a 2pt baked stroke would
    /// be 10pt which dominates the symbol.
    @ViewBuilder
    private func applyHalo<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        let r: CGFloat = 1
        content()
            .shadow(color: .white, radius: 0, x:  r, y:  0)
            .shadow(color: .white, radius: 0, x: -r, y:  0)
            .shadow(color: .white, radius: 0, x:  0, y:  r)
            .shadow(color: .white, radius: 0, x:  0, y: -r)
            .shadow(color: .white, radius: 0, x:  r, y:  r)
            .shadow(color: .white, radius: 0, x: -r, y: -r)
            .shadow(color: .white, radius: 0, x:  r, y: -r)
            .shadow(color: .white, radius: 0, x: -r, y:  r)
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
