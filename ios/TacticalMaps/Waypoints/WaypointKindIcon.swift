import SwiftUI

/// Small icon view for pickers/lists.
/// Military units -> full APP-6 symbol via MilitarySymbolView
/// Control measures -> TacticalControlMeasureSymbolView
/// Generic -> SF Symbol fallback
struct WaypointKindIcon: View {
    let kind: WaypointKind
    var size: CGFloat = 32
    /// Clockwise rotation in degrees. Only applied to control measures,
    /// military units and SF Symbols don't rotate.
    var rotation: Double = 0
    /// Tint for task graphics. Controls card passes the waypoint's
    /// taskColor so preview recolours to match.
    var taskColor: TaskColor = .black

    var body: some View {
        if let spec = kind.militarySpec {
            MilitarySymbolView(spec: spec, size: size)
        } else if let m = kind.controlMeasure {
            TacticalControlMeasureSymbolView(measure: m,
                                             rotation: rotation,
                                             color: taskColor.color,
                                             size: size)
        } else if let mk = kind.markerSymbol {
            Image(uiImage: MarkerSymbolRenderer.image(for: mk, size: size))
                .frame(width: size, height: size)
        } else {
            Image(systemName: kind.sfSymbol)
                .font(.system(size: size * 0.58, weight: .semibold))
                .foregroundStyle(kind.tint)
                .frame(width: size, height: size)
        }
    }
}
