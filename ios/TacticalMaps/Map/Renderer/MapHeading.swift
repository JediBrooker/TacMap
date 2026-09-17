import Foundation
import CoreGraphics

enum CompassTapAction: Equatable {
    case resetNorth
    case enableHeadingUp
    case disableHeadingUp
    case headingUnavailable
}

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

    /// Smallest signed turn from one heading to another, in `[-180, 180)`.
    static func shortestDelta(from start: Double, to end: Double) -> Double {
        var delta = normalized(end) - normalized(start)
        if delta >= 180 { delta -= 360 }
        if delta < -180 { delta += 360 }
        return delta
    }

    /// Circular low-pass filter that takes the short path across north.
    static func smoothed(previous: Double?, measured: Double, factor: Double = 0.25) -> Double {
        let measured = normalized(measured)
        guard let previous, previous.isFinite else { return measured }
        let factor = min(max(factor, 0), 1)
        return normalized(previous + shortestDelta(from: previous, to: measured) * factor)
    }

    static func isApproximatelyNorth(_ degrees: Double, tolerance: Double = 1) -> Bool {
        abs(shortestDelta(from: degrees, to: 0)) <= tolerance
    }

    /// One compass control preserves the old reset gesture while making the
    /// automatic mode discoverable from an already north-facing map.
    static func compassTapAction(
        headingUpEnabled: Bool,
        currentHeading: Double,
        headingAvailable: Bool
    ) -> CompassTapAction {
        if headingUpEnabled { return .disableHeadingUp }
        if !isApproximatelyNorth(currentHeading) { return .resetNorth }
        return headingAvailable ? .enableHeadingUp : .headingUnavailable
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
