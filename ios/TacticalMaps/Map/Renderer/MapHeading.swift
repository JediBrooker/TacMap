import Foundation
import CoreGraphics

/// Shared map-heading math for the rotation gesture and compass HUD.
///
/// Keeping the value in `[0, 360)` avoids an ever-growing camera angle and
/// gives the compass a stable value at the north wrap (for example 359° + 2°
/// becomes 1°, not 361°).
enum MapHeading {
    static func normalized(_ degrees: Double) -> Double {
        guard degrees.isFinite else { return 0 }
        let wrapped = degrees.truncatingRemainder(dividingBy: 360)
        return wrapped < 0 ? wrapped + 360 : wrapped
    }

    /// `UIRotationGestureRecognizer.rotation` uses the same positive direction
    /// as the map camera heading: a clockwise twist increases the heading.
    static func addingGestureRotation(_ radians: CGFloat, to degrees: Double) -> Double {
        guard radians.isFinite else { return normalized(degrees) }
        return normalized(degrees + Double(radians) * 180 / .pi)
    }

    /// NATO mils, 6400 per circle. The rounded value wraps 6400 back to 0000.
    static func mils(for degrees: Double) -> Int {
        Int(round(normalized(degrees) * (6400 / 360))) % 6400
    }

    static func milsString(for degrees: Double) -> String {
        String(format: "%04d", mils(for: degrees))
    }
}
