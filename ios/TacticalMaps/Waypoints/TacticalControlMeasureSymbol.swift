import SwiftUI
import UIKit

/// Renders a `TacticalControlMeasure` from the bundled PNG / SVG asset
/// under `Assets.xcassets/AppSymbols/`. Pure black symbol on a
/// transparent background. Stroke thickness comes from the **source
/// asset itself** (dilated PNGs + thickened SVG strokes) — no
/// render-time effects, so the lines stay crisp at any scale.
struct TacticalControlMeasureSymbolView: View {
    let measure: TacticalControlMeasure
    /// Clockwise rotation in degrees. 0 = canonical orientation.
    var rotation: Double = 0
    var size: CGFloat = 56
    /// Small bitmap padding so rotation doesn't clip the corners.
    static let haloPadding: CGFloat = 2

    var body: some View {
        let canvas = size + 2 * Self.haloPadding
        return ZStack {
            Image("AppSymbols/\(measure.assetName)")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
                .foregroundStyle(.black)
                .frame(width: size, height: size)
                .rotationEffect(.degrees(rotation))
        }
        .frame(width: canvas, height: canvas)
    }
}

@MainActor
enum TacticalControlMeasureRenderer {
    /// Canonical display size: a 1× scale symbol appears at this
    /// point size on screen. Used by MapViewModel default-scale math
    /// and by the annotation view's transform-scale calculation.
    static let logicalBaseSize: CGFloat = 64

    /// The SwiftUI view (and therefore the produced bitmap) is
    /// rendered at this point size — 3× the logical size, so when
    /// the bitmap is later transform-scaled up to large symbols
    /// (waypoint.scale × zoomScale = 5× → 320pt on screen) the GPU
    /// has enough source pixels to avoid the smudge that bilinear
    /// upsampling from a tiny bitmap produces.
    static let bitmapBaseSize: CGFloat = 192

    /// Backwards-compat alias — the old name still works for code
    /// that uses the canonical 1× display size.
    static let baseSize: CGFloat = logicalBaseSize

    /// Ratio of bitmap-render-size to logical-display-size. The
    /// annotation view divides its transform-scale by this so the
    /// on-screen display stays at `logicalBaseSize × waypoint.scale
    /// × zoomScale` even though the bitmap is 3× bigger.
    static let bitmapOversample: CGFloat = bitmapBaseSize / logicalBaseSize

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
            size: bitmapBaseSize
        )
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        let img = renderer.uiImage
        if let img { cache[key] = img }
        return img
    }
}
