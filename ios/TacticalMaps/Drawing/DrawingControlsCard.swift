import SwiftUI

/// Compact card that pops up when user taps a finished drawing on the
/// map. Same idea as `SymbolControlsCard` for waypoints: name, colour,
/// solid/dashed, stroke width, delete.
struct DrawingControlsCard: View {
    @ObservedObject var drawingStore: DrawingStore
    let drawingID: UUID
    let onDismiss: () -> Void

    @State private var showDeleteConfirm = false
    @State private var showNameAlert     = false
    @State private var draftName: String = ""
    /// Rotation / width / height sliders visibility. They eat most of
    /// the card's vertical space so they hide behind a toggle.
    @State private var showTransforms    = false

    var body: some View {
        if let shape = drawingStore.shapes.first(where: { $0.id == drawingID }) {
            card(for: shape)
        }
    }

    private func card(for shape: DrawingShape) -> some View {
        VStack(spacing: 8) {
            header(for: shape)
            // Transform sliders behind a toggle - compact card only shows
            // colour, dash, layer, delete unless user explicitly wants
            // to transform. Points don't have transform controls obv.
            if shape.kind != .point && showTransforms {
                rotationRow(for: shape)
                widthRow(for: shape)
                heightRow(for: shape)
            }
            // Tactical line-graphic picker for line shapes (FLOT, boundary…).
            if shape.kind == .polyline || shape.kind == .freedraw {
                lineGraphicRow(for: shape)
            }
            actionRow(for: shape)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
        .alert("Delete drawing?", isPresented: $showDeleteConfirm) {
            Button("Delete", role: .destructive) {
                drawingStore.remove(shape)
                onDismiss()
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("This will permanently remove “\(shape.name ?? shape.kind.displayName)”.")
        }
        .alert("Drawing name", isPresented: $showNameAlert) {
            TextField("Name", text: $draftName)
                .autocorrectionDisabled()
            Button("Save") {
                var updated = shape
                let trimmed = draftName.trimmingCharacters(in: .whitespaces)
                updated.name = trimmed.isEmpty ? nil : trimmed
                drawingStore.update(updated)
            }
            Button("Cancel", role: .cancel) { }
        }
    }

    // MARK: Tactical line graphic

    /// NATO line-graphic picker for line shapes - plain, phase line,
    /// boundary, FLOT/FEBA, or axis of advance.
    private func lineGraphicRow(for shape: DrawingShape) -> some View {
        let current = shape.style.lineGraphic ?? .plain
        return Menu {
            ForEach(LineGraphic.allCases, id: \.self) { g in
                Button {
                    var updated = shape
                    updated.style.lineGraphic = (g == .plain) ? nil : g
                    drawingStore.update(updated)
                } label: {
                    Label(g.displayName, systemImage: g.symbolName)
                }
            }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: current.symbolName)
                    .font(.system(size: 14, weight: .medium))
                Text(current.displayName)
                    .font(.system(size: 13, weight: .medium))
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12)
            .frame(height: 38)
            .frame(maxWidth: .infinity)
            .background(.white.opacity(0.08), in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    // MARK: Header - name + close

    private func header(for shape: DrawingShape) -> some View {
        let layerName = drawingStore.layer(id: shape.layerID)?.name
        return HStack(spacing: 10) {
            // filled tile showing the drawing colour at a glance
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(Color(hex: shape.style.strokeColorHex).opacity(0.18))
                Image(systemName: shape.kind.sfSymbol)
                    .foregroundStyle(Color(hex: shape.style.strokeColorHex))
            }
            .frame(width: 36, height: 36)

            Button {
                draftName = shape.name ?? ""
                showNameAlert = true
            } label: {
                HStack(spacing: 6) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(shape.name ?? shape.kind.displayName)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                        Text("\(shape.kind.displayName)\(layerName.map { " · \($0)" } ?? "")")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                    Image(systemName: "pencil")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Rename drawing")

            Spacer(minLength: 4)
            Button(action: onDismiss) {
                Image(systemName: "xmark.circle.fill")
                    .font(.body)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close drawing controls")
        }
    }

    // MARK: Compact style controls (used in the action row)

    /// Tappable circle swatch that opens a palette menu.
    private func colourButton(for shape: DrawingShape) -> some View {
        Menu {
            ForEach(DrawingPalette.swatches) { swatch in
                Button {
                    var updated = shape
                    updated.style.strokeColorHex = swatch.hex
                    if updated.style.fillColorHex != nil {
                        updated.style.fillColorHex = swatch.hex
                    }
                    drawingStore.update(updated)
                } label: {
                    // .tint() on each row colours the Label's icon, without it
                    // Menu items ignore foregroundStyle() and the dots are
                    // all monochrome. annoying SwiftUI thing
                    Label(swatch.name,
                          systemImage: swatch.hex.caseInsensitiveCompare(shape.style.strokeColorHex) == .orderedSame
                              ? "largecircle.fill.circle"
                              : "circle.fill")
                }
                .tint(swatch.color)
            }
        } label: {
            Circle()
                .fill(Color(hex: shape.style.strokeColorHex))
                .frame(width: 30, height: 30)
                .overlay(Circle().stroke(.white.opacity(0.5), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Drawing colour")
    }

    /// Solid vs dashed toggle. Same look as the DrawToolbar version.
    private func dashedToggle(for shape: DrawingShape) -> some View {
        Button {
            var updated = shape
            updated.style.dashPattern = (shape.style.dashPattern == nil) ? [8, 6] : nil
            drawingStore.update(updated)
        } label: {
            ZStack {
                Circle().fill(.white.opacity(shape.style.dashPattern != nil ? 0.22 : 0.10))
                if shape.style.dashPattern != nil {
                    HStack(spacing: 2.5) {
                        ForEach(0..<3, id: \.self) { _ in
                            Capsule().fill(Color.primary).frame(width: 5, height: 2.5)
                        }
                    }
                } else {
                    Capsule().fill(Color.primary).frame(width: 20, height: 2.5)
                }
            }
            .frame(width: 30, height: 30)
            .overlay(Circle().stroke(.white.opacity(shape.style.dashPattern != nil ? 0.5 : 0), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Stroke style")
        .accessibilityValue(shape.style.dashPattern == nil ? "Solid" : "Dashed")
    }

    /// Layer pill - colour swatch + name + count, opens menu to reassign.
    /// Count is just drawings on that layer so user can see how busy it
    /// is before moving the current shape there.
    private func layerPill(for shape: DrawingShape) -> some View {
        let current = drawingStore.layer(id: shape.layerID) ?? drawingStore.layers.first
        return Menu {
            ForEach(drawingStore.layers) { layer in
                let count = layerItemCount(layer)
                Button {
                    var updated = shape
                    updated.layerID = layer.id
                    drawingStore.update(updated)
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

    private func layerItemCount(_ layer: DrawingLayer) -> Int {
        drawingStore.shapes(in: layer.id).count
    }

    // MARK: Geometric sliders - rotation, width, height

    private func rotationRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "arrow.clockwise.circle",
            title: "Rotation",
            valueLabel: "\(Int(shape.rotation.rounded()))°",
            value: Binding(
                get: { shape.rotation },
                set: { newValue in
                    var updated = shape
                    updated.rotation = newValue
                    drawingStore.update(updated)
                }
            ),
            range: 0...360,
            step: 1,
            resetTo: 0
        )
    }

    private func widthRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "arrow.left.and.right.circle",
            title: "Width",
            valueLabel: String(format: "%.2f×", shape.scaleX),
            value: Binding(
                get: { shape.scaleX },
                set: { newValue in
                    var updated = shape
                    updated.scaleX = newValue
                    drawingStore.update(updated)
                }
            ),
            range: 0.1...10.0,
            step: 0.05,
            resetTo: 1.0
        )
    }

    private func heightRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "arrow.up.and.down.circle",
            title: "Height",
            valueLabel: String(format: "%.2f×", shape.scaleY),
            value: Binding(
                get: { shape.scaleY },
                set: { newValue in
                    var updated = shape
                    updated.scaleY = newValue
                    drawingStore.update(updated)
                }
            ),
            range: 0.1...10.0,
            step: 0.05,
            resetTo: 1.0
        )
    }

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
            Slider(value: value, in: range, step: step, onEditingChanged: { editing in
                // group per-tick undo registrations into one step so Undo
                // undoes the whole drag, not each individual tick
                if editing {
                    drawingStore.undoManager?.beginUndoGrouping()
                } else {
                    drawingStore.undoManager?.endUndoGrouping()
                    drawingStore.undoManager?.setActionName(title)
                }
            })
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

    // MARK: Action row - style controls + delete

    private func actionRow(for shape: DrawingShape) -> some View {
        HStack(spacing: 8) {
            colourButton(for: shape)
            dashedToggle(for: shape)
            if shape.kind != .point {
                transformToggle()
            }
            layerPill(for: shape)
            Spacer(minLength: 0)
            Button {
                showDeleteConfirm = true
            } label: {
                Label {
                    Text("Delete")
                        .font(.footnote.weight(.semibold))
                        .lineLimit(1)
                        .fixedSize(horizontal: true, vertical: false)
                } icon: {
                    Image(systemName: "trash")
                        .font(.footnote)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 7)
                .background(Color.red.opacity(0.85),
                            in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(.plain)
        }
    }

    /// Show/hide the rotation + width + height sliders.
    private func transformToggle() -> some View {
        Button {
            withAnimation(.easeInOut(duration: 0.18)) {
                showTransforms.toggle()
            }
        } label: {
            ZStack {
                Circle().fill(.white.opacity(showTransforms ? 0.22 : 0.10))
                Image(systemName: "slider.horizontal.3")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.primary)
            }
            .frame(width: 30, height: 30)
            .overlay(
                Circle().stroke(.white.opacity(showTransforms ? 0.5 : 0), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Transform controls")
        .accessibilityValue(showTransforms ? "Expanded" : "Collapsed")
    }
}
