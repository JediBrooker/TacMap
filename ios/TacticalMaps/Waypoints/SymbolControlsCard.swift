import SwiftUI

/// Floating compact card that appears when the user taps a tactical
/// control-measure waypoint on the map. Lets them dial in rotation
/// and size live (every slider tick persists via `WaypointStore`).
///
/// Designed to sit just above the bottom safe-area inset so it
/// doesn't overlap with the "Centre on My Location" pill. Tap-outside
/// dismissal is handled by `ContentView` — this view only renders.
struct SymbolControlsCard: View {
    @ObservedObject var waypointStore: WaypointStore
    /// ID of the waypoint we're editing. The view re-resolves the
    /// current Waypoint from the store on every redraw so changes
    /// persist immediately and the preview stays in sync.
    let waypointID: UUID
    let onDismiss: () -> Void

    var body: some View {
        if let wp = waypointStore.waypoints.first(where: { $0.id == waypointID }),
           let measure = wp.kind.controlMeasure {
            card(for: wp, measure: measure)
        }
    }

    private func card(for wp: Waypoint, measure: TacticalControlMeasure) -> some View {
        VStack(spacing: 12) {
            // Header: live preview + name + close button.
            HStack(spacing: 12) {
                TacticalControlMeasureSymbolView(
                    measure: measure,
                    rotation: wp.rotation,
                    size: 36 * wp.scale.clamped(to: 0.6...1.6)
                )
                .frame(width: 56, height: 56)

                VStack(alignment: .leading, spacing: 2) {
                    Text(wp.name)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(measure.displayName)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .symbolRenderingMode(.hierarchical)
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close symbol controls")
            }

            Divider()

            // Rotation row.
            sliderRow(
                icon: "arrow.clockwise.circle",
                title: "Rotation",
                valueLabel: "\(Int(wp.rotation.rounded()))°",
                value: Binding(
                    get: { wp.rotation },
                    set: { newValue in
                        var updated = wp
                        updated.rotation = newValue
                        waypointStore.update(updated)
                    }
                ),
                range: 0...360,
                step: 1,
                resetTo: 0
            )

            // Size row. Range goes up to 6× so the user can grow the
            // symbol enough to be readable on satellite imagery at wide
            // zoom levels (a 1× form-up point is ~64pt and disappears
            // visually against city blocks from 1km altitude).
            sliderRow(
                icon: "arrow.up.left.and.arrow.down.right.circle",
                title: "Size",
                valueLabel: String(format: "%.2f×", wp.scale),
                value: Binding(
                    get: { wp.scale },
                    set: { newValue in
                        var updated = wp
                        updated.scale = newValue
                        waypointStore.update(updated)
                    }
                ),
                range: 0.5...6.0,
                step: 0.05,
                resetTo: 1.0
            )
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
    }

    private func sliderRow(icon: String,
                           title: String,
                           valueLabel: String,
                           value: Binding<Double>,
                           range: ClosedRange<Double>,
                           step: Double,
                           resetTo defaultValue: Double) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Text(title)
                    .font(.subheadline.weight(.medium))
                Spacer()
                Text(valueLabel)
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.secondary)
                Button {
                    value.wrappedValue = defaultValue
                } label: {
                    Image(systemName: "arrow.counterclockwise")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.tint.opacity(0.15), in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Reset \(title)")
            }
            Slider(value: value, in: range, step: step)
        }
    }
}

private extension Comparable {
    func clamped(to limits: ClosedRange<Self>) -> Self {
        min(max(self, limits.lowerBound), limits.upperBound)
    }
}
