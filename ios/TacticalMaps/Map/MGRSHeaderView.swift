import SwiftUI
import CoreLocation
import UIKit

/// Tactical-style header rendering the MGRS grid reference of either the user's
/// position (default) or the map centre (browse mode), plus WGS84, elevation,
/// and a status/accuracy strip.
///
/// Tap → copy MGRS to clipboard. Long-press → drop a waypoint at this MGRS
/// (closure provided by the caller; nil disables the affordance).
struct MGRSHeaderView: View {
    let mgrs: String
    let wgs84: String
    let isBrowsing: Bool
    let accuracy: CLLocationAccuracy?
    let elevation: CLLocationDistance?
    /// Caller hands us the coordinate currently displayed (live or crosshair).
    /// We use it for the long-press "drop pin" action.
    var coordinate: CLLocationCoordinate2D? = nil
    var onDropPin: ((CLLocationCoordinate2D, String) -> Void)? = nil

    @State private var showCopiedToast: Bool = false

    var body: some View {
        VStack(spacing: 3) {
            Text(isBrowsing ? "MGRS (Map Centre)" : "MGRS (Your Location)")
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.7))

            Text(mgrs)
                .font(.system(size: 26, weight: .bold, design: .monospaced))
                .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            HStack(spacing: 8) {
                Text("WGS84")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white.opacity(0.6))
                Text(wgs84)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.85))
                Spacer(minLength: 4)
                Text(elevationText)
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.85))
            }

            HStack(spacing: 6) {
                Image(systemName: "scope").font(.caption2)
                Text(isBrowsing ? "Map Centre" : "Live Location")
                    .font(.caption.weight(.semibold))
                Spacer()
                Text(accuracyText)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.75))
            }
            .padding(.top, 1)
            .foregroundStyle(.orange)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.black.opacity(0.78))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(.white.opacity(0.08), lineWidth: 1)
        )
        .overlay(alignment: .top) {
            if showCopiedToast {
                Text("MGRS copied")
                    .font(.caption2.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 4)
                    .background(.green.opacity(0.85), in: Capsule())
                    .foregroundStyle(.black)
                    .offset(y: -22)
                    .transition(.opacity)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            UIPasteboard.general.string = mgrs
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            withAnimation { showCopiedToast = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                withAnimation { showCopiedToast = false }
            }
        }
        .onLongPressGesture(minimumDuration: 0.4) {
            guard let coord = coordinate, let drop = onDropPin else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            drop(coord, mgrs)
        }
        .accessibilityHint("Tap to copy MGRS. Long-press to drop a pin here.")
    }

    private var accuracyText: String {
        guard let acc = accuracy, acc >= 0 else { return "Accuracy N/A" }
        return String(format: "Accuracy \u{00B1}%.0fm", acc)
    }

    private var elevationText: String {
        guard let e = elevation else { return "ELEV —" }
        return String(format: "ELEV %.0f m", e)
    }
}

#Preview {
    MGRSHeaderView(
        mgrs: "10SEG 51117 80976",
        wgs84: "37.77470° N, 122.41956° W",
        isBrowsing: true,
        accuracy: 5,
        elevation: 1856
    )
    .padding()
    .background(Color.gray)
}
