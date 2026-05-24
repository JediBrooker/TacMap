import SwiftUI

/// Centre crosshair shown while the user is in browse mode.
struct CrosshairOverlay: View {
    var body: some View {
        ZStack {
            Rectangle().fill(Color.white.opacity(0.85)).frame(width: 1)
            Rectangle().fill(Color.white.opacity(0.85)).frame(height: 1)
            Circle()
                .strokeBorder(Color.white.opacity(0.9), lineWidth: 1.5)
                .frame(width: 24, height: 24)
        }
    }
}

#Preview {
    CrosshairOverlay()
        .background(Color.gray)
}
