import SwiftUI

/// A pill button that re-centres the camera. Defaults to "Centre on My
/// Location"; pass a shorter title + icon for the paired "Map" button that
/// reframes an imported offline map.
struct CentreButton: View {
    var title: String = "Centre on My Location"
    var systemImage: String = "location.viewfinder"
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
                .background(.black.opacity(0.78), in: Capsule())
                .foregroundStyle(.white)
                .overlay(Capsule().stroke(.white.opacity(0.15)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
    }
}
