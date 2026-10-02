import SwiftUI
import CoreLocation
import UIKit
import UniformTypeIdentifiers

/// Coordinate header for the user's position or map centre. The primary
/// representation is user-selectable; supporting status remains below it.
///
/// Tap to copy the displayed coordinate. Long-press to drop a waypoint here
/// (caller provides closure, nil disables it).
struct MGRSHeaderView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let mgrs: String
    let wgs84: String
    /// User-facing UTM readout (e.g. "33N 450000mE 6700000mN").
    /// nil or an unavailable marker falls back to WGS84 when UTM is selected.
    var utm: String? = nil
    var coordinateDisplayFormat: CoordinateDisplayFormat = .mgrs
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
    /// Straight-line distance from the latest user fix to the map crosshair.
    /// nil hides the readout while no location fix is available.
    var distanceFromUser: CLLocationDistance? = nil
    let elevation: CLLocationDistance?
    /// True when elevation is approximate (offline cache). Shown with
    /// leading "~". Defaults false (fresh/live reading).
    var elevationIsApproximate: Bool = false
    /// Coordinate currently displayed (live or crosshair). Used for
    /// the long-press "drop pin" action.
    var coordinate: CLLocationCoordinate2D? = nil
    var onDropPin: ((CLLocationCoordinate2D, String) -> Void)? = nil
    /// Calibrating (s7.8): .notGeoreferenced swaps the coordinate for a warning
    /// (the placement is a guess), .preview tags an unsaved fit.
    var calibrationReadout: CalibrationHeaderReadout? = nil

    @State private var showCopiedToast: Bool = false
    /// Grid-magnetic units. Mils by default (military standard); tapping the
    /// G-M readout flips it to degrees. Persisted across launches.
    @AppStorage("gridMagneticMils") private var gridMagneticMils = true

    private var notGeoreferenced: Bool { calibrationReadout == .notGeoreferenced }

    var body: some View {
        VStack(spacing: 3) {
            if notGeoreferenced {
                Text(Messages.calibrationNotGeoreferenced())
                    .font(.system(.title3, design: .monospaced).weight(.heavy))
                    .foregroundStyle(Color(red: 1, green: 0.65, blue: 0.18))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .accessibilityIdentifier("header.notGeoreferenced")
            } else {
                HStack(spacing: 6) {
                    coordinateText
                    if calibrationReadout == .preview(showTag: true) {
                        Text(Messages.calibrationPreviewTag())
                            .font(.caption2.weight(.heavy))
                            .foregroundStyle(.black)
                            .padding(.horizontal, 5).padding(.vertical, 2)
                            .background(Color(red: 1, green: 0.65, blue: 0.18), in: RoundedRectangle(cornerRadius: 4))
                            .accessibilityIdentifier("header.previewTag")
                    }
                }
            }
            if !notGeoreferenced {
                distanceAndElevation
            }
            statusRow
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
                Text(L10n.text("%1$@ copied", resolvedCoordinate.format.label))
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
            guard !notGeoreferenced else { return }
            UIPasteboard.general.setItems(
                [[UTType.plainText.identifier: resolvedCoordinate.text]],
                options: [
                    .expirationDate: Date().addingTimeInterval(120),
                    .localOnly: true
                ]
            )
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
            withAnimation { showCopiedToast = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.4) {
                withAnimation { showCopiedToast = false }
            }
        }
        .onLongPressGesture(minimumDuration: 0.4) {
            guard !notGeoreferenced, let coord = coordinate, let drop = onDropPin else { return }
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            drop(coord, resolvedCoordinate.text)
        }
        // OD-F15: while provisional there's no coordinate to copy, VoiceOver
        // reads NOT GEOREFERENCED and nothing about MGRS
        .accessibilityHint(
            notGeoreferenced ? "" : Messages.coordinateCopyHint(resolvedCoordinate.format.label)
        )
    }

    private var coordinateText: some View {
            Text(resolvedCoordinate.text)
                // Text-style not fixed 26pt so it scales with Dynamic Type.
                // Still shrinks to fit.
                .font(.system(.title, design: .monospaced).weight(.bold))
                .foregroundStyle(Color(red: 0.55, green: 0.95, blue: 0.55))
                .lineLimit(1)
                .minimumScaleFactor(0.5)
                .accessibilityLabel(
                    L10n.text("%1$@ coordinate %2$@", resolvedCoordinate.format.label, resolvedCoordinate.text)
                )
    }

    private var distanceAndElevation: some View {
            HStack(spacing: 8) {
                if let distanceFromUser {
                    Text(L10n.text("FROM ME %1$@", MeasureFormat.distance(distanceFromUser)))
                        .font(.caption2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.white.opacity(0.75))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer(minLength: 4)
                Text(elevationText)
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.85))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .layoutPriority(1)
            }
    }

    private var statusRow: some View {
            HStack(spacing: 6) {
                // The old Live Location / Map Centre label lived here, but the
                // card title already says which one, so this slot now carries the
                // basemap status (red online / green offline) instead.
                if let basemapLabel {
                    Text(basemapLabel)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(basemapColor)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                }
                Spacer()
                if syncConnected {
                    HStack(spacing: 4) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.caption2)
                        Text(L10n.text("Unit Sync"))
                            .font(.caption2.weight(.semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    .foregroundStyle(Color(red: 0.31, green: 0.66, blue: 1.0))
                    Spacer()
                }
                // Grid-magnetic angle (compass correction off the grid) replaces
                // the old accuracy readout here. Mils by default; tap to flip to
                // degrees (tap is scoped to this text so it doesn't copy MGRS).
                if let gridMagneticDegrees, !notGeoreferenced {
                    Text(GridMagnetic.label(degrees: gridMagneticDegrees, mils: gridMagneticMils))
                        .font(.caption2.monospacedDigit())
                        .foregroundStyle(.white.opacity(0.75))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        // Enlarge the tap target a little, and use a high-priority
                        // gesture so this wins over the card's copy-coordinate tap.
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                        .highPriorityGesture(TapGesture().onEnded { gridMagneticMils.toggle() })
                }
            }
            .padding(.top, 1)
    }

    private var resolvedCoordinate: CoordinateDisplayFormat.Resolved {
        coordinateDisplayFormat.resolve(mgrs: mgrs, wgs84: wgs84, utm: utm)
    }

    private var elevationText: String {
        guard let e = elevation else { return L10n.text("ELEV —") }
        let mark = elevationIsApproximate ? "~" : ""
        return L10n.text("ELEV %1$@", mark + (DisplayFormat.number(e, decimals: 0) + " m"))
    }
}

/// What the header shows while calibrating (s7.8)
enum CalibrationHeaderReadout: Equatable {
    case notGeoreferenced
    case preview(showTag: Bool)
}

#Preview {
    MGRSHeaderView(
        mgrs: "10SEG 51117 80976",
        wgs84: "37.77470° N, 122.41956° W",
        utm: "10N 551117mE 4180976mN",
        gridMagneticDegrees: 13.4,
        distanceFromUser: 1_275,
        elevation: 1856
    )
    .padding()
    .background(Color.gray)
}
