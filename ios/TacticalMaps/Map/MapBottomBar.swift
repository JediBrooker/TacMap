import SwiftUI

/// The big pill button that re-centres the camera on the user.
struct CentreButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label("Centre on My Location", systemImage: "location.viewfinder")
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .background(.black.opacity(0.78), in: Capsule())
                .foregroundStyle(.white)
                .overlay(Capsule().stroke(.white.opacity(0.15)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Centre map on my location")
    }
}
