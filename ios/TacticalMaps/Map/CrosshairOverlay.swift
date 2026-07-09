import SwiftUI

/// Centre crosshair for browse mode. Tactical orange (matches the
/// MGRS header “Map Centre” line), double-shadow glow so its
/// readable on any basemap.
struct CrosshairOverlay: View {
    /// same orange as MGRSHeaderView's status row
    private let tactical = Color.orange

    var body: some View {
        ZStack {
            // Vertical + horizontal hairlines
            Rectangle()
                .fill(tactical.opacity(0.95))
                .frame(width: 1.5)
            Rectangle()
                .fill(tactical.opacity(0.95))
                .frame(height: 1.5)

            // Central ring
            Circle()
                .strokeBorder(tactical.opacity(0.95), lineWidth: 1.5)
                .frame(width: 26, height: 26)
        }
        // stacked shadows for a glow effect, avoids GPU-heavy blur
        .shadow(color: tactical.opacity(0.85), radius: 4, x: 0, y: 0)
        .shadow(color: tactical.opacity(0.55), radius: 9, x: 0, y: 0)
    }
}

#Preview {
    CrosshairOverlay()
        .background(Color(white: 0.2))
}
