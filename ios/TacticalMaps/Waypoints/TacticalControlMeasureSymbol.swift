import SwiftUI
import UIKit

/// Renders a TacticalControlMeasure from bundled PNG/SVG asset under
/// AppSymbols/. Pure black on transparent. View bounds match symbol
/// size exactly so SwiftUI hit-testing lines up with visible pixels,
/// no padding-induced tap hijack.
struct TacticalControlMeasureSymbolView: View {
    let measure: TacticalControlMeasure
    /// Clockwise rotation in degrees. 0 = canonical orientation.
    var rotation: Double = 0
    /// Tint for the glyph. Asset is template-rendered so this
    /// recolours the black line art.
    var color: Color = .black
    var size: CGFloat = 56

    var body: some View {
        Image("AppSymbols/\(measure.assetName)")
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundStyle(color)
            .frame(width: size, height: size)
            .rotationEffect(.degrees(rotation))
    }
}

@MainActor
enum TacticalControlMeasureRenderer {
    /// Canonical size in points. Runtime scaling happens via the
    /// annotation view's transform.
    static let baseSize: CGFloat = 64

    private struct Key: Hashable {
        let measure: TacticalControlMeasure
        let rotationCentideg: Int   // 0..35999, 1/100 of a degree
        let color: TaskColor
    }
    private static var cache: [Key: UIImage] = [:]

    static func image(for measure: TacticalControlMeasure,
                      rotation: Double = 0,
                      color: TaskColor = .black) -> UIImage? {
        let normalized = ((rotation.truncatingRemainder(dividingBy: 360)) + 360)
            .truncatingRemainder(dividingBy: 360)
        let key = Key(
            measure: measure,
            rotationCentideg: Int((normalized * 100).rounded()),
            color: color
        )
        if let cached = cache[key] { return cached }
        let view = TacticalControlMeasureSymbolView(
            measure: measure,
            rotation: normalized,
            color: color.color,
            size: baseSize
        )
        let renderer = ImageRenderer(content: view)
        renderer.scale = UIScreen.main.scale
        let img = renderer.uiImage
        if let img { cache[key] = img }
        return img
    }
}
