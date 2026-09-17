import SwiftUI

/// Floating panel that drops down from the hamburger menu when user
/// picks "Drawings". Replaces the full `DrawingsSheet` modal for the
/// common start-a-new-drawing path, full list is one tap away.
struct DrawingsPanel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var session: DrawingSessionViewModel
    let onShowAll: () -> Void
    let onDismiss: () -> Void
    @State private var mutationError: LocalizedMessage?

    var body: some View {
        ScrollView {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(L10n.text("DRAW"))
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white.opacity(0.6))
                Spacer()
                Button { onDismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white.opacity(0.85))
                        // 36pt visible, 44pt hit area via contentShape
                        // so close button is easy to tap without
                        // making the header huge
                        .frame(width: 36, height: 36)
                        .background(.white.opacity(0.10), in: Circle())
                        .contentShape(Rectangle().inset(by: -4))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L10n.text("Close"))
            }
            .padding(.horizontal, 4)

            layerPicker

            row(.freedraw, label: L10n.text("Free Draw"), subtitle: L10n.text("Draw freely with your finger"))
            row(.polyline, label: L10n.text("Line Tool"), subtitle: L10n.text("Tap points to trace a route"))
            row(.polygon,  subtitle: L10n.text("Mark out a boundary"))
            row(.point,    subtitle: L10n.text("Drop a single marker"))

            if !drawingStore.shapes.isEmpty {
                Divider().background(.white.opacity(0.12)).padding(.vertical, 2)
                Text(L10n.text("SAVED"))
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.leading, 4)

                // Up to 4 most-recent saved drawings with quick trash buttons.
                ForEach(drawingStore.shapes.suffix(4).reversed()) { shape in
                    savedRow(shape)
                }

                if drawingStore.shapes.count > 4 {
                    Button {
                        onShowAll()
                    } label: {
                        HStack {
                            Image(systemName: "list.bullet")
                                .foregroundStyle(.white.opacity(0.75))
                                .frame(width: 24)
                            Text(L10n.text("All Drawings (%1$@)", drawingStore.shapes.count))
                                .foregroundStyle(.white)
                                .font(.caption)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption)
                                .foregroundStyle(.white.opacity(0.5))
                        }
                        .padding(.horizontal, 8)
                        .padding(.vertical, 6)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(8)
        }
        .frame(maxHeight: dynamicTypeSize.isAccessibilitySize ? 430 : 520)
        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(.white.opacity(0.12)))
        .frame(width: dynamicTypeSize.isAccessibilitySize ? 320 : 250)
        .alert(L10n.text("Drawing Not Deleted"), isPresented: Binding(
            get: { mutationError != nil },
            set: { if !$0 { mutationError = nil } }
        )) {
            Button(Messages.acknowledge(), role: .cancel) { mutationError = nil }
        } message: {
            Text(mutationError?.text ?? L10n.text("The drawing could not be deleted. Check available storage, then try again."))
        }
    }

    @ViewBuilder
    private func savedRow(_ shape: DrawingShape) -> some View {
        HStack(spacing: 8) {
            Image(systemName: shape.kind.sfSymbol)
                .font(.subheadline)
                .foregroundStyle(Color(hex: shape.style.strokeColorHex))
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 0) {
                Text(shape.name ?? shape.kind.displayName)
                    .font(.caption)
                    .foregroundStyle(.white)
                Text(L10n.quantity("point", shape.coordinates.count))
                    .font(.system(size: 9))
                    .foregroundStyle(.white.opacity(0.55))
            }
            Spacer()
            Button {
                do {
                    _ = try drawingStore.deleteDurably(shape)
                } catch {
                    mutationError = Messages.displayCheckAvailableStorageThenTryAgainMessage("").withArgument(0, error.displayMessage)
                }
            } label: {
                Image(systemName: "trash")
                    .font(.subheadline)
                    .foregroundStyle(.red.opacity(0.9))
                    .frame(width: 40, height: 40)
                    .background(.white.opacity(0.08), in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L10n.text("Delete %1$@", shape.name ?? shape.kind.displayName))
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
    }

    /// Layer chooser. Opens a menu of every layer (with colour swatch),
    /// updates `drawingStore.activeLayerID` which the "New X" rows
    /// read when kicking off a session.
    @ViewBuilder
    private var layerPicker: some View {
        let active = activeLayer
        Menu {
            ForEach(drawingStore.layers) { layer in
                Button {
                    drawingStore.activeLayerID = layer.id
                } label: {
                    Label(layer.displayName,
                          systemImage: layer.id == active?.id
                              ? "largecircle.fill.circle"
                              : "circle.fill")
                }
                .tint(Color(hex: layer.defaultColorHex))
            }
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(Color(hex: active?.defaultColorHex ?? "#888888"))
                    .frame(width: 14, height: 14)
                    .overlay(Circle().stroke(.white.opacity(0.4), lineWidth: 1))
                VStack(alignment: .leading, spacing: 0) {
                    Text(L10n.text("LAYER"))
                        .font(.system(size: 9).weight(.bold))
                        .foregroundStyle(.white.opacity(0.5))
                    Text(active?.displayName ?? "—")
                        .fixedSize(horizontal: false, vertical: true)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                }
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .font(.caption2)
                    .foregroundStyle(.white.opacity(0.55))
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
        }
        .menuStyle(.borderlessButton)
    }

    private var activeLayer: DrawingLayer? {
        if let id = drawingStore.activeLayerID,
           let layer = drawingStore.layer(id: id) { return layer }
        return drawingStore.layers.first
    }

    @ViewBuilder
    private func row(_ kind: DrawingKind, label: String? = nil, subtitle: String) -> some View {
        Button {
            guard let layer = activeLayer else { return }
            // inherit active layer's colour so e.g. Hostile drawings
            // start red instead of forcing user to recolour
            session.strokeColorHex = layer.defaultColorHex
            session.start(kind: kind, layerID: layer.id)
            onDismiss()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: kind.sfSymbol)
                    .font(.title3)
                    .foregroundStyle(Color(red: 1, green: 0.65, blue: 0.18))
                    .frame(width: 28)
                VStack(alignment: .leading, spacing: 1) {
                    Text(label ?? L10n.text("New %1$@", kind.displayName))
                        .fixedSize(horizontal: false, vertical: true)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                    Text(subtitle)
                        .fixedSize(horizontal: false, vertical: true)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.65))
                }
                Spacer()
            }
            // bumped padding from 6 to 10 so rows are ~48pt tall.
            // at 6pt they were only ~34pt and missed taps were
            // definately an issue
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
