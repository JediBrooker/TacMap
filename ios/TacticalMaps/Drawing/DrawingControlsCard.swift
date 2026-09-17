import SwiftUI
import CoreLocation

/// View-owned transaction for continuous controls. Slider ticks only mutate
/// this candidate; `finish` hands one final shape to the durable store.
struct DrawingSliderTransaction {
    private(set) var original: DrawingShape?
    private(set) var candidate: DrawingShape?

    @discardableResult
    mutating func update(from durable: DrawingShape,
                         _ mutation: (inout DrawingShape) -> Void) -> DrawingShape {
        if original == nil {
            original = durable
            candidate = durable
        }
        var next = candidate ?? durable
        mutation(&next)
        candidate = next
        return next
    }

    @discardableResult
    mutating func finish(_ commit: (DrawingShape) throws -> Bool) throws -> Bool {
        guard let candidate else { return false }
        defer { cancel() }
        return try commit(candidate)
    }

    mutating func cancel() {
        original = nil
        candidate = nil
    }
}

/// Compact card that pops up when user taps a finished drawing on the
/// map. Same idea as `SymbolControlsCard` for waypoints: name, colour,
/// solid/dashed, stroke width, delete.
struct DrawingControlsCard: View {
    @ObservedObject var drawingStore: DrawingStore
    let drawingID: UUID
    let crosshairCoordinate: CLLocationCoordinate2D
    let onPreview: (DrawingShape?) -> Void
    let onDismiss: () -> Void

    @State private var showDeleteConfirm = false
    @State private var showNameAlert     = false
    @State private var draftName: String = ""
    @State private var mutationError: String?
    @State private var sliderTransaction = DrawingSliderTransaction()
    /// Rotation / width / height sliders visibility. They eat most of
    /// the card's vertical space so they hide behind a toggle.
    @State private var showTransforms    = false
    @State private var showEditor        = false

    var body: some View {
        if let durable = drawingStore.shapes.first(where: { $0.id == drawingID }) {
            card(for: sliderTransaction.candidate ?? durable)
                .onDisappear { cancelSliderTransaction() }
        }
    }

    private func card(for shape: DrawingShape) -> some View {
        VStack(spacing: 8) {
            header(for: shape)
            quickActionRow(for: shape)
            // Continuous style/transform controls stay behind a toggle so the
            // resting card remains compact. Every tick is an in-memory preview;
            // the gesture end performs one checked durable commit.
            if showEditor && shape.kind != .point && showTransforms {
                strokeWidthRow(for: shape)
                if shape.kind == .polygon {
                    fillOpacityRow(for: shape)
                }
                rotationRow(for: shape)
                widthRow(for: shape)
                heightRow(for: shape)
            }
            // Tactical line-graphic picker for line shapes (FLOT, boundary…).
            if showEditor && (shape.kind == .polyline || shape.kind == .freedraw) {
                lineGraphicRow(for: shape)
            }
            if showEditor {
                actionRow(for: shape)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(.white.opacity(0.15), lineWidth: 0.5)
        )
        .shadow(color: .black.opacity(0.25), radius: 10, y: 4)
        .alert(L10n.text("Delete drawing?"), isPresented: $showDeleteConfirm) {
            Button(L10n.text("Delete"), role: .destructive) {
                do {
                    _ = try drawingStore.deleteDurably(shape)
                    onDismiss()
                } catch {
                    mutationError = L10n.text("%1$@ Check available storage, then try again.", error.localizedDescription)
                }
            }
            Button(L10n.text("Cancel"), role: .cancel) { }
        } message: {
            Text(L10n.text("This will permanently remove “%1$@”.", shape.name ?? shape.kind.displayName))
        }
        .alert(L10n.text("Drawing name"), isPresented: $showNameAlert) {
            TextField(L10n.text("Name"), text: $draftName)
                .autocorrectionDisabled()
            Button(L10n.text("Save")) {
                var updated = shape
                let trimmed = draftName.trimmingCharacters(in: .whitespaces)
                updated.name = trimmed.isEmpty ? nil : trimmed
                commit(updated, actionName: L10n.text("Rename Drawing"))
            }
            Button(L10n.text("Cancel"), role: .cancel) { }
        }
        .alert(L10n.text("Drawing Not Saved"), isPresented: Binding(
            get: { mutationError != nil },
            set: { if !$0 { mutationError = nil } }
        )) {
            Button("OK", role: .cancel) { mutationError = nil }
        } message: {
            Text(mutationError ?? L10n.text("The drawing change could not be saved. Check available storage, then try again."))
        }
    }

    private func quickActionRow(for shape: DrawingShape) -> some View {
        HStack(spacing: 8) {
            Button {
                withAnimation(.easeInOut(duration: 0.18)) { showEditor.toggle() }
            } label: {
                Label(L10n.text("Edit Drawing"), systemImage: "slider.horizontal.3")
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityValue(showEditor ? L10n.text("Expanded") : L10n.text("Collapsed"))

            Button {
                commit(shape.moved(to: crosshairCoordinate), actionName: L10n.text("Move Drawing to Crosshair"))
            } label: {
                Label(L10n.text("Move to Crosshair"), systemImage: "scope")
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel(L10n.text("Move drawing to crosshair"))
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
                    commit(updated, actionName: L10n.text("Change Line Graphic"))
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
            .accessibilityHint(L10n.text("Rename drawing"))

            Spacer(minLength: 4)
            Button(action: dismissControls) {
                Image(systemName: "xmark.circle.fill")
                    .font(.body)
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("Close drawing controls"))
        }
    }

    // MARK: Compact style controls (used in the action row)

    /// Tappable circle swatch that opens a palette menu.
    private func strokeColourButton(for shape: DrawingShape) -> some View {
        Menu {
            ForEach(DrawingPalette.swatches) { swatch in
                Button {
                    var updated = shape
                    updated.style.setStrokeHue(swatch.hex)
                    commit(updated, actionName: L10n.text("Change Drawing Colour"))
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
        .accessibilityLabel(L10n.text("Stroke colour"))
        .accessibilityValue(DrawingPalette.swatch(forHex: shape.style.strokeColorHex)?.name
                            ?? shape.style.strokeColorHex)
    }

    /// Polygon fill controls are independent of the stroke. This menu exposes
    /// both fill hue and opacity without expanding the compact controls card.
    private func fillStyleButton(for shape: DrawingShape) -> some View {
        let fillHex = shape.style.fillColorHex ?? DrawingPalette.default.hex
        return Menu {
            Section(L10n.text("Fill colour")) {
                ForEach(DrawingPalette.swatches) { swatch in
                    Button {
                        var updated = shape
                        updated.style.setFillHue(swatch.hex)
                        commit(updated, actionName: L10n.text("Change Fill Colour"))
                    } label: {
                        Label(swatch.name,
                              systemImage: fillHex.caseInsensitiveCompare(swatch.hex) == .orderedSame
                                  ? "largecircle.fill.circle"
                                  : "circle.fill")
                    }
                    .tint(swatch.color)
                }
            }
            Section(L10n.text("Fill opacity")) {
                ForEach([0.0, 0.1, 0.2, 0.4, 0.6, 0.8, 1.0], id: \.self) { opacity in
                    Button {
                        var updated = shape
                        updated.style.setFillOpacity(opacity)
                        commit(updated, actionName: L10n.text("Change Fill Opacity"))
                    } label: {
                        Label("\(Int(opacity * 100))%",
                              systemImage: abs(shape.style.fillOpacity - opacity) < 0.001
                                  ? "checkmark.circle.fill"
                                  : "circle")
                    }
                }
            }
        } label: {
            ZStack {
                Circle().fill(Color(hex: fillHex).opacity(shape.style.fillOpacity))
                Circle().stroke(.white.opacity(0.5), lineWidth: 1)
                Image(systemName: "paintbrush.pointed.fill")
                    .font(.caption2)
                    .foregroundStyle(.white)
                    .shadow(radius: 1)
            }
            .frame(width: 30, height: 30)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L10n.text("Fill style"))
        .accessibilityValue(L10n.text("%1$@, %2$@ percent", DrawingPalette.swatch(forHex: fillHex)?.name ?? fillHex, Int(shape.style.fillOpacity * 100)))
    }

    /// Solid vs dashed toggle. Same look as the DrawToolbar version.
    private func dashedToggle(for shape: DrawingShape) -> some View {
        Button {
            var updated = shape
            updated.style.dashPattern = (shape.style.dashPattern == nil) ? [8, 6] : nil
            commit(updated, actionName: L10n.text("Change Stroke Style"))
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
        .accessibilityLabel(L10n.text("Stroke style"))
        .accessibilityValue(shape.style.dashPattern == nil ? L10n.text("Solid") : L10n.text("Dashed"))
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
                    commit(updated, actionName: L10n.text("Move Drawing to Layer"))
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
        .accessibilityLabel(L10n.text("Layer"))
        .accessibilityValue(current?.name ?? "")
    }

    private func layerItemCount(_ layer: DrawingLayer) -> Int {
        drawingStore.shapes(in: layer.id).count
    }

    // MARK: Continuous style + geometric sliders

    private func strokeWidthRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "scribble.variable",
            title: L10n.text("Stroke width"),
            valueLabel: String(format: "%.1f pt", shape.style.strokeWidth),
            value: shape.style.strokeWidth,
            range: 0.5...16,
            step: 0.5,
            onPreview: { value in
                previewChange(from: shape) { $0.style.strokeWidth = value }
            },
            onCommit: { finishSliderTransaction(actionName: L10n.text("Change Stroke Width")) },
            onReset: {
                commitReset(actionName: L10n.text("Reset Stroke Width")) {
                    $0.style.strokeWidth = DrawingStyle.default.strokeWidth
                }
            }
        )
    }

    private func fillOpacityRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "circle.lefthalf.filled",
            title: L10n.text("Fill opacity"),
            valueLabel: "\(Int((shape.style.fillOpacity * 100).rounded()))%",
            value: shape.style.fillOpacity,
            range: 0...1,
            step: 0.05,
            onPreview: { value in
                previewChange(from: shape) { $0.style.setFillOpacity(value) }
            },
            onCommit: { finishSliderTransaction(actionName: L10n.text("Change Fill Opacity")) },
            onReset: {
                commitReset(actionName: L10n.text("Reset Fill Opacity")) {
                    $0.style.setFillOpacity(DrawingStyle.default.fillOpacity)
                }
            }
        )
    }

    private func rotationRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "arrow.clockwise.circle",
            title: L10n.text("Rotation"),
            valueLabel: "\(Int(shape.rotation.rounded()))°",
            value: shape.rotation,
            range: 0...360,
            step: 1,
            onPreview: { value in
                previewChange(from: shape) { $0.rotation = value }
            },
            onCommit: { finishSliderTransaction(actionName: L10n.text("Rotate Drawing")) },
            onReset: { commitReset(actionName: L10n.text("Reset Drawing Rotation")) { $0.rotation = 0 } }
        )
    }

    private func widthRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "arrow.left.and.right.circle",
            title: L10n.text("Width"),
            valueLabel: String(format: "%.2f×", shape.scaleX),
            value: shape.scaleX,
            range: 0.1...10.0,
            step: 0.05,
            onPreview: { value in
                previewChange(from: shape) { $0.scaleX = value }
            },
            onCommit: { finishSliderTransaction(actionName: L10n.text("Resize Drawing Width")) },
            onReset: { commitReset(actionName: L10n.text("Reset Drawing Width")) { $0.scaleX = 1 } }
        )
    }

    private func heightRow(for shape: DrawingShape) -> some View {
        sliderRow(
            icon: "arrow.up.and.down.circle",
            title: L10n.text("Height"),
            valueLabel: String(format: "%.2f×", shape.scaleY),
            value: shape.scaleY,
            range: 0.1...10.0,
            step: 0.05,
            onPreview: { value in
                previewChange(from: shape) { $0.scaleY = value }
            },
            onCommit: { finishSliderTransaction(actionName: L10n.text("Resize Drawing Height")) },
            onReset: { commitReset(actionName: L10n.text("Reset Drawing Height")) { $0.scaleY = 1 } }
        )
    }

    private func sliderRow(icon: String,
                           title: String,
                           valueLabel: String,
                           value: Double,
                           range: ClosedRange<Double>,
                           step: Double,
                           onPreview: @escaping (Double) -> Void,
                           onCommit: @escaping () -> Void,
                           onReset: @escaping () -> Void) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(width: 16)
            Slider(
                value: Binding(get: { value }, set: onPreview),
                in: range,
                step: step,
                onEditingChanged: { editing in if !editing { onCommit() } }
            )
            Text(valueLabel)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(minWidth: 44, alignment: .trailing)
            Button(action: onReset) {
                Image(systemName: "arrow.counterclockwise")
                    .font(.caption2.weight(.semibold))
                    .frame(width: 22, height: 22)
                    .background(.tint.opacity(0.15), in: Circle())
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("Reset %1$@", title))
        }
    }

    // MARK: Action row - style controls + delete

    private func actionRow(for shape: DrawingShape) -> some View {
        HStack(spacing: 8) {
            strokeColourButton(for: shape)
            if shape.kind == .polygon {
                fillStyleButton(for: shape)
            }
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
                    Text(L10n.text("Delete"))
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

    /// Show/hide continuous style and transform sliders.
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
        .accessibilityLabel(L10n.text("Transform controls"))
        .accessibilityValue(showTransforms ? L10n.text("Expanded") : L10n.text("Collapsed"))
    }

    private func previewChange(from shape: DrawingShape,
                               mutation: (inout DrawingShape) -> Void) {
        let preview = sliderTransaction.update(from: shape, mutation)
        onPreview(preview)
    }

    private func finishSliderTransaction(actionName: String) {
        do {
            _ = try sliderTransaction.finish { candidate in
                try drawingStore.commitEdit(candidate, actionName: actionName)
            }
            onPreview(nil)
        } catch {
            // commitEdit is durable-before-publish, so clearing the transient
            // candidate immediately restores the last known-good store shape.
            onPreview(nil)
            mutationError = L10n.text("%1$@ The previous drawing is still active. Check available storage, then try again.", error.localizedDescription)
        }
    }

    private func commitReset(actionName: String,
                             mutation: (inout DrawingShape) -> Void) {
        cancelSliderTransaction()
        guard var durable = drawingStore.shapes.first(where: { $0.id == drawingID }) else {
            mutationError = L10n.text("That drawing no longer exists. Close its controls and try again.")
            return
        }
        mutation(&durable)
        commit(durable, actionName: actionName)
    }

    private func cancelSliderTransaction() {
        sliderTransaction.cancel()
        onPreview(nil)
    }

    private func dismissControls() {
        cancelSliderTransaction()
        onDismiss()
    }

    private func commit(_ shape: DrawingShape, actionName: String) {
        cancelSliderTransaction()
        do {
            _ = try drawingStore.commitEdit(shape, actionName: actionName)
        } catch {
            mutationError = L10n.text("%1$@ Check available storage, then try again.", error.localizedDescription)
        }
    }
}
