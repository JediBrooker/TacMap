import SwiftUI
import CoreLocation
import UIKit

/// MGRS header - shows grid reference for user's position or map centre,
/// plus WGS84, elevation, and accuracy.
///
/// Tap to copy MGRS to clipboard. Long-press to drop a waypoint here
/// (caller provides closure, nil disables it).
struct MGRSHeaderView: View {
    let mgrs: String
    let wgs84: String
    /// User-facing UTM readout (e.g. "33N 450000mE 6700000mN"). nil hides the row.
    var utm: String? = nil
    /// True while connected to a Unit Sync room. Shows a blue indicator.
    var syncConnected: Bool = false
    /// Basemap status shown where the old Live Location/Map Centre label was.
    /// "Online basemap" (red) when pulling internet tiles, "Offline basemap"
    /// (green) when an imported pack/PDF is active, nil when neither.
    var basemapLabel: String? = nil
    var basemapColor: Color = .clear
    /// Grid-magnetic angle for compass work, raw degrees (+E / -W). Shown
    /// bottom-right in mils by default; tap it to flip to degrees. nil hides it.
    var gridMagneticDegrees: Double? = nil
    let elevation: CLLocationDistance?
    /// True when elevation is approximate (offline cache). Shown with
    /// leading "~". Defaults false (fresh/live reading).
    var elevationIsApproximate: Bool = false
    /// Coordinate currently displayed (live or crosshair). Used for
    /// the long-press "drop pin" action.
    var coordinate: CLLocationCoordinate2D? = nil
    var onDropPin: ((CLLocationCoordinate2D, String) -> Void)? = nil

    @State private var showCopiedToast: Bool = false
    /// Grid-magnetic units. Mils by default (military standard); tapping the
    /// G-M readout flips it to degrees. Persisted across launches.
    @AppStorage("gridMagneticMils") private var gridMagneticMils = true

    var body: some View {
        VStack(spacing: 3) {
            // The "MGRS (Map Centre)/(Your Location)" title used to sit here;
            // dropped as redundant (the big readout is obviously the grid ref).
            Text(mgrs)
                // Text-style not fixed 26pt so it scales with Dynamic Type.
                // Still shrinks to fit.
                .font(.system(.title, design: .monospaced).weight(.bold))
                .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                .lineLimit(1)
                .minimumScaleFactor(0.5)

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

            if let utm {
                HStack(spacing: 8) {
                    Text("UTM")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.white.opacity(0.6))
                    Text(utm)
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.white.opacity(0.85))
                    Spacer(minLength: 4)
                }
            }

            HStack(spacing: 6) {
                // The old Live Location / Map Centre label lived here, but the
                // card title already says which one, so this slot now carries the
                // basemap status (red online / green offline) instead.
                if let basemapLabel {
                    Text(basemapLabel)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(basemapColor)
                }
                Spacer()
                if syncConnected {
                    HStack(spacing: 4) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.caption2)
                        Text("Unit Sync")
                            .font(.caption2.weight(.semibold))
                    }
                    .foregroundStyle(Color(red: 0.31, green: 0.66, blue: 1.0))
                    Spacer()
                }
                // Grid-magnetic angle (compass correction off the grid) replaces
                // the old accuracy readout here. Mils by default; tap to flip to
                // degrees (tap is scoped to this text so it doesn't copy MGRS).
                if let gridMagneticDegrees {
                    Text(GridMagnetic.label(degrees: gridMagneticDegrees, mils: gridMagneticMils))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.white.opacity(0.75))
                        // Enlarge the tap target a little, and use a high-priority
                        // gesture so this wins over the card's tap-to-copy-MGRS.
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                        .highPriorityGesture(TapGesture().onEnded { gridMagneticMils.toggle() })
                }
            }
            .padding(.top, 1)
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

    private var elevationText: String {
        guard let e = elevation else { return "ELEV —" }
        let mark = elevationIsApproximate ? "~" : ""
        return String(format: "ELEV %@%.0f m", mark, e)
    }
}

#Preview {
    MGRSHeaderView(
        mgrs: "10SEG 51117 80976",
        wgs84: "37.77470° N, 122.41956° W",
        gridMagneticDegrees: 13.4,
        elevation: 1856
    )
    .padding()
    .background(Color.gray)
}
