import SwiftUI

/// Centre crosshair shown while the user is in browse mode. Tactical orange
/// (same hue as the MGRS header's “Map Centre” status line) with a soft
/// double-shadow glow so it stays legible on any basemap.
struct CrosshairOverlay: View {
    /// Same colour as the MGRSHeaderView's `.foregroundStyle(.orange)` status row.
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
        // Stacked shadows produce a soft glow without GPU-heavy blur layers.
        .shadow(color: tactical.opacity(0.85), radius: 4, x: 0, y: 0)
        .shadow(color: tactical.opacity(0.55), radius: 9, x: 0, y: 0)
    }
}

#Preview {
    CrosshairOverlay()
        .background(Color(white: 0.2))
}
