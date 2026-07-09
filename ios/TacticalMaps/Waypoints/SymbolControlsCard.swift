import SwiftUI
import CoreLocation

/// Floating card that appears when you tap a waypoint annotation on
/// the map. Shows relevant actions for the kind:
///   All: live preview, name, "Move to Crosshair", close
///   Control measures: rotation + size sliders
///   Military / generic: just move
///
/// Sits just above bottom safe-area so it doesn't overlap the "Centre
/// on My Location" pill. Tap-outside dismissal handled by ContentView,
/// this view only renders.
struct SymbolControlsCard: View {
    @ObservedObject var waypointStore: WaypointStore
    /// Shared layer model, used to render and pick the waypoint's layer.
    @ObservedObject var drawingStore: DrawingStore
    /// Map VM - need current crosshair coord for "Move to Crosshair".
    @ObservedObject var mapVM: MapViewModel
    /// ID of waypoint we're editing. Re-resolves from store every
    /// redraw so changes persist immediately and preview stays in sync.
    let waypointID: UUID
    let onDismiss: () -> Void

    @State private var showDeleteConfirm: Bool = false
    /// Tapping name in header opens full edit sheet so user can swap
    /// the kind (e.g. Ambush -> Block) without re-creating the waypoint.
    @State private var showingEdit: Bool = false

    var body: some View {
        if let wp = waypointStore.waypoints.first(where: { $0.id == waypointID }) {
            card(for: wp)
        }
    }

    private func card(for wp: Waypoint) -> some View {
        VStack(spacing: 8) {
            header(for: wp)

            // Rotation + width/height only for control measures.
            // Military symbols don't have orientation or per-instance
            // size. No dividers between sections, icons and spacing
            // carry enough structure.
            if case .controlMeasure = wp.kind {
                colorRow(for: wp)
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
        .sheet(isPresented: $showingEdit) {
            if let wp = waypointStore.waypoints.first(where: { $0.id == waypointID }) {
                WaypointEditSheet(
                    waypointStore: waypointStore,
                    original: wp,
                    defaultCoordinate: wp.coordinate
                )
            }
        }
    }

    // MARK: Rows

    private func header(for wp: Waypoint) -> some View {
        // Compact one-line header: icon + name (+ kind muted if they
        // differ) + close. Previous two-line layout wasted vertical
        // space and subtitle was usually the same string as the name
        // anyway (we auto-fill from kind displayName).
        let kindLabel = wp.kind.displayName
        let showKindSuffix = wp.name != kindLabel
        return HStack(spacing: 10) {
            // Military unit symbols have echelon (dots/bars/X) above
            // the frame plus a function glyph inside. 22pt in a 28pt
            // tile was an unreadable blob so give units a roomier
            // tile. Control measures + generic markers stay compact.
            let isUnit = wp.kind.militarySpec != nil
            let tile: CGFloat = isUnit ? 44 : 28
            let inner: CGFloat = isUnit ? 38 : 22
            WaypointKindIcon(
                kind: wp.kind,
                size: inner,
                rotation: wp.kind.controlMeasure == nil ? 0 : wp.rotation,
                taskColor: wp.taskColor
            )
            .frame(width: tile, height: tile)
            // White bg so the (mostly black) symbols stay legible
            // against the translucent material card.
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color.white)
            )

            Button {
                showingEdit = true
            } label: {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(wp.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        if showKindSuffix {
                            Text(kindLabel)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }
                    Image(systemName: "pencil")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Edit symbol type")
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

    /// Layer pill - colour swatch + name + count, opens menu to reassign.
    private func layerPill(for wp: Waypoint) -> some View {
        let current = drawingStore.layer(id: wp.layerID) ?? drawingStore.layers.first
        return Menu {
            ForEach(drawingStore.layers) { layer in
                let count = layerItemCount(layer)
                Button {
                    var updated = wp
                    updated.layerID = layer.id
                    waypointStore.update(updated)
                } label: {
                    Label("\(layer.name) (\(count))",
                          systemImage: layer.id == current?.id
                              ? "largecircle.fill.circle"
                              : "circle.fill")
                }
                .tint(Color(hex: layer.defaultColorHex))
            }
        } label: {
            HStack(spacing: 6) {
                Image(systemName: "square.stack.3d.up.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(hex: current?.defaultColorHex ?? "#888888"))
                Text(current.map { "\($0.name) (\(layerItemCount($0)))" } ?? "—")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: 140)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(.white.opacity(0.10), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Layer")
        .accessibilityValue(current?.name ?? "")
    }

    /// Count of drawings + waypoints assigned to a layer.
    private func layerItemCount(_ layer: DrawingLayer) -> Int {
        let drawings = drawingStore.shapes(in: layer.id).count
        let waypoints = waypointStore.waypoints.filter { $0.layerID == layer.id }.count
        return drawings + waypoints
    }

    /// Five-swatch colour picker for task graphic. Black default,
    /// others follow APP-6 affiliation palette. Mirrors Android's
    /// ControlMeasureControls colour row.
    private func colorRow(for wp: Waypoint) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "paintpalette")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(width: 16)
            HStack(spacing: 10) {
                ForEach(TaskColor.allCases, id: \.self) { tc in
                    Button {
                        var updated = wp
                        updated.taskColor = tc
                        waypointStore.update(updated)
                    } label: {
                        Circle()
                            .fill(tc.color)
                            .frame(width: 28, height: 28)
                            // White hairline so black swatches are readable
                            // on the translucent dark card.
                            .overlay(Circle().strokeBorder(.white.opacity(0.7), lineWidth: 1))
                            // Accent ring marks the current selection.
                            .overlay(Circle().strokeBorder(
                                Color.accentColor,
                                lineWidth: wp.taskColor == tc ? 3 : 0))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tc.label)
                    .accessibilityAddTraits(wp.taskColor == tc ? [.isSelected] : [])
                }
            }
            Spacer(minLength: 0)
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

    /// Move + Delete row. Move snaps waypoint to current map centre
    /// (the crosshair). Long-press-drag on the map also works. Delete
    /// shows confirm alert then dismisses card.
    private func actionRow(for wp: Waypoint) -> some View {
        HStack(spacing: 8) {
            layerPill(for: wp)
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

    /// One-line slider: [icon] [--slider--] [value] [reset]. Title is
    /// only used for accessibility on the reset button. Icon conveys
    /// whats being adjusted, dropping the redundant text label saves
    /// a whole row per slider.
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
