import SwiftUI
import UIKit

/// `Color(hex: "#RRGGBB")` / `"#RRGGBBAA"`. Falls back to white on parse
/// failure so we never crash the renderer with a bad colour string.
extension Color {
    init(hex: String) {
        let s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "#", with: "")
        var value: UInt64 = 0
        Scanner(string: s).scanHexInt64(&value)
        let r, g, b, a: Double
        switch s.count {
        case 6:
            r = Double((value & 0xFF0000) >> 16) / 255
            g = Double((value & 0x00FF00) >> 8)  / 255
            b = Double( value & 0x0000FF)        / 255
            a = 1
        case 8:
            r = Double((value & 0xFF000000) >> 24) / 255
            g = Double((value & 0x00FF0000) >> 16) / 255
            b = Double((value & 0x0000FF00) >> 8)  / 255
            a = Double( value & 0x000000FF)        / 255
        default:
            r = 1; g = 1; b = 1; a = 1
        }
        self.init(.sRGB, red: r, green: g, blue: b, opacity: a)
    }
}

extension UIColor {
    /// Bridge for MapKit overlay renderers that need UIColor.
    convenience init(hex: String, alpha: Double = 1.0) {
        let swiftColor = Color(hex: hex).opacity(alpha)
        self.init(swiftColor)
    }
}
