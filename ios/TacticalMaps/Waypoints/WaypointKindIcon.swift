import SwiftUI

/// Small icon view used in pickers / lists.
/// - Military units: full APP-6 unit symbol via `MilitarySymbolView`.
/// - Tactical control measures: milsymbol-style shape + abbreviation via
///   `TacticalControlMeasureSymbolView`.
/// - Generic waypoint: falls back to an SF Symbol.
struct WaypointKindIcon: View {
    let kind: WaypointKind
    var size: CGFloat = 32

    var body: some View {
        if let spec = kind.militarySpec {
            MilitarySymbolView(spec: spec, size: size)
        } else if let m = kind.controlMeasure {
            TacticalControlMeasureSymbolView(measure: m, size: size)
        } else {
            Image(systemName: kind.sfSymbol)
                .font(.system(size: size * 0.58, weight: .semibold))
                .foregroundStyle(kind.tint)
                .frame(width: size, height: size)
        }
    }
}
