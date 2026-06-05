import SwiftUI

/// Inline floating panel that drops down below the hamburger button when the
/// user picks "Drawings" from the menu. Replaces the modal `DrawingsSheet`
/// for the common start-a-new-drawing path; the full list is one tap away.
struct DrawingsPanel: View {
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var session: DrawingSessionViewModel
    let onShowAll: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text("DRAW")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.white.opacity(0.6))
                Spacer()
                Button { onDismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(.white.opacity(0.85))
                        /// 36pt visual chip, 44pt invisible hit area
                        /// via contentShape so the close button is
                        /// reliably tappable without ballooning the
                        /// panel header.
                        .frame(width: 36, height: 36)
                        .background(.white.opacity(0.10), in: Circle())
                        .contentShape(Rectangle().inset(by: -4))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 4)

            layerPicker

            row(.freedraw, label: "Free Draw", subtitle: "Draw freely with your finger")
            row(.polyline, label: "Line Tool", subtitle: "Tap points to trace a route")
            row(.polygon,  subtitle: "Mark out a boundary")
            row(.point,    subtitle: "Drop a single marker")

            if !drawingStore.shapes.isEmpty {
                Divider().background(.white.opacity(0.12)).padding(.vertical, 2)
                Text("SAVED")
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
                            Text("All Drawings (\(drawingStore.shapes.count))")
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
        .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(.white.opacity(0.12)))
        .frame(width: 250)
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
                Text("\(shape.coordinates.count) pt\(shape.coordinates.count == 1 ? "" : "s")")
                    .font(.system(size: 9))
                    .foregroundStyle(.white.opacity(0.55))
            }
            Spacer()
            Button {
                drawingStore.remove(shape)
            } label: {
                Image(systemName: "trash")
                    .font(.subheadline)
                    .foregroundStyle(.red.opacity(0.9))
                    .frame(width: 40, height: 40)
                    .background(.white.opacity(0.08), in: Circle())
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Delete \(shape.name ?? shape.kind.displayName)")
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
    }

    /// Layer chooser. Tapping opens a menu of every drawing layer (with
    /// its colour swatch) and updates `drawingStore.activeLayerID`, which
    /// each "New X" row reads when it kicks off the session.
    @ViewBuilder
    private var layerPicker: some View {
        let active = activeLayer
        Menu {
            ForEach(drawingStore.layers) { layer in
                Button {
                    drawingStore.activeLayerID = layer.id
                } label: {
                    Label(layer.name,
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
                    Text("LAYER")
                        .font(.system(size: 9).weight(.bold))
                        .foregroundStyle(.white.opacity(0.5))
                    Text(active?.name ?? "—")
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
            // New drawings inherit the active layer's default colour so
            // a Hostile-layer drawing starts red instead of forcing the
            // user to recolour from the palette default.
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
                    Text(label ?? "New \(kind.displayName)")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white)
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.65))
                }
                Spacer()
            }
            /// Vertical padding bumped from 6 → 10 so each draw-tool
            /// row is ~48pt tall (icon ~22pt + 2×10 padding + text
            /// metrics). At the previous 6pt the rows were ~34pt and
            /// missed taps were common.
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
