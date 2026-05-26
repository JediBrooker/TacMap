import SwiftUI
import CoreLocation

/// Floating compact card that appears when the user taps any waypoint
/// annotation on the map. Surfaces the actions that make sense for
/// the kind:
///   - All kinds: live preview, name, "Move to Crosshair", close.
///   - Tactical control measures: rotation + size sliders.
///   - Military / generic: just move.
///
/// Designed to sit just above the bottom safe-area inset so it doesn't
/// overlap with the "Centre on My Location" pill. Tap-outside dismissal
/// is handled by `ContentView` — this view only renders.
struct SymbolControlsCard: View {
    @ObservedObject var waypointStore: WaypointStore
    /// Map VM exposes the current crosshair coordinate (camera centre)
    /// for the "Move to Crosshair" action.
    @ObservedObject var mapVM: MapViewModel
    /// ID of the waypoint we're editing. The view re-resolves the
    /// current Waypoint from the store on every redraw so changes
    /// persist immediately and the preview stays in sync.
    let waypointID: UUID
    let onDismiss: () -> Void

    @State private var showDeleteConfirm: Bool = false

    var body: some View {
        if let wp = waypointStore.waypoints.first(where: { $0.id == waypointID }) {
            card(for: wp)
        }
    }

    private func card(for wp: Waypoint) -> some View {
        VStack(spacing: 8) {
            header(for: wp)

            // Rotation + width/height live only for tactical control
            // measures — military symbols don't have orientation or
            // per-instance size in the model. No dividers between
            // sections — the icons and spacing carry enough structure.
            if case .controlMeasure = wp.kind {
                rotationRow(for: wp)
                widthRow(for: wp)
                heightRow(for: wp)
            }

            actionRow(for: wp)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
        .alert("Delete symbol?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                if let wp = waypointStore.waypoints.first(where: { $0.id == waypointID }) {
                    waypointStore.remove(wp)
                }
                onDismiss()
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            let name = waypointStore.waypoints
                .first(where: { $0.id == waypointID })?.name ?? "this symbol"
            Text("This will permanently remove “\(name)”.")
        }
    }

    // MARK: Rows

    private func header(for wp: Waypoint) -> some View {
        // Compact one-line header: small icon + name (or name + kind
        // muted, if they differ) + close. The previous two-line layout
        // wasted vertical space and the subtitle was usually the same
        // string as the name (we auto-fill blank names from the kind's
        // display name).
        let kindLabel = wp.kind.displayName
        let showKindSuffix = wp.name != kindLabel
        return HStack(spacing: 10) {
            WaypointKindIcon(
                kind: wp.kind,
                size: 22,
                rotation: wp.kind.controlMeasure == nil ? 0 : wp.rotation
            )
            .frame(width: 28, height: 28)
            // White background so the (mostly black) symbols stay
            // legible against the translucent material card.
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.white)
            )

            HStack(spacing: 6) {
                Text(wp.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                if showKindSuffix {
                    Text(kindLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 4)
            Button(action: onDismiss) {
                Image(systemName: "xmark.circle.fill")
                    .font(.body)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close symbol controls")
        }
    }

    private func rotationRow(for wp: Waypoint) -> some View {
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
    }

    private func widthRow(for wp: Waypoint) -> some View {
        sliderRow(
            icon: "arrow.left.and.right.circle",
            title: "Width",
            valueLabel: String(format: "%.2f×", wp.scaleX),
            value: Binding(
                get: { wp.scaleX },
                set: { newValue in
                    var updated = wp
                    updated.scaleX = newValue
                    waypointStore.update(updated)
                }
            ),
            range: 0.1...20.0,
            step: 0.1,
            resetTo: 1.0
        )
    }

    private func heightRow(for wp: Waypoint) -> some View {
        sliderRow(
            icon: "arrow.up.and.down.circle",
            title: "Height",
            valueLabel: String(format: "%.2f×", wp.scaleY),
            value: Binding(
                get: { wp.scaleY },
                set: { newValue in
                    var updated = wp
                    updated.scaleY = newValue
                    waypointStore.update(updated)
                }
            ),
            range: 0.1...20.0,
            step: 0.1,
            resetTo: 1.0
        )
    }

    /// Move + Delete row. Move snaps the waypoint to the current map
    /// centre (where the crosshair sits) — long-press-drag on the map
    /// itself is also supported. Delete shows a confirm alert before
    /// removing the waypoint, then dismisses the card.
    private func actionRow(for wp: Waypoint) -> some View {
        HStack(spacing: 8) {
            Button {
                var updated = wp
                updated.latitude  = mapVM.cameraCentre.latitude
                updated.longitude = mapVM.cameraCentre.longitude
                waypointStore.update(updated)
            } label: {
                Label {
                    Text("Move to Crosshair")
                        .font(.footnote.weight(.semibold))
                        .lineLimit(1)
                } icon: {
                    Image(systemName: "scope")
                        .font(.footnote)
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 7)
                .background(Color.accentColor.opacity(0.85),
                            in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityHint("Pan the map first so the crosshair is at the new location, then tap.")

            Button {
                showDeleteConfirm = true
            } label: {
                Image(systemName: "trash")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 30)
                    .background(Color.red.opacity(0.85),
                                in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete symbol")
        }
    }

    // MARK: Slider primitive

    /// One-line slider: `[icon] [——slider——] [value] [reset]`. The
    /// `title` is used only for the reset button's accessibility
    /// label — the icon visually conveys what's being adjusted, and
    /// dropping the redundant text label saves a whole row per slider.
    private func sliderRow(icon: String,
                           title: String,
                           valueLabel: String,
                           value: Binding<Double>,
                           range: ClosedRange<Double>,
                           step: Double,
                           resetTo defaultValue: Double) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(width: 16)
            Slider(value: value, in: range, step: step)
            Text(valueLabel)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 44, alignment: .trailing)
            Button {
                value.wrappedValue = defaultValue
            } label: {
                Image(systemName: "arrow.counterclockwise")
                    .font(.caption2.weight(.semibold))
                    .frame(width: 22, height: 22)
                    .background(.tint.opacity(0.15), in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Reset \(title)")
        }
    }
}
