import SwiftUI
import MapKit

/// Full list of saved drawings. Open via DrawingsPanel > "All Drawings".
/// Per-row delete via trash button + swipe action.
struct DrawingsSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var session: DrawingSessionViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var pendingDelete: DrawingShape? = nil
    @State private var renamingShape: DrawingShape? = nil
    @State private var renameDraft: String = ""
    @State private var mutationError: String? = nil

    var body: some View {
        NavigationStack {
            List {
                Section {
                    layerPicker
                } header: {
                    Text(L10n.text("Active Layer"))
                } footer: {
                    Text(L10n.text("New drawings are added to this layer."))
                        .font(.caption2)
                }

                Section(L10n.text("New Drawing")) {
                    newRow(.polyline, subtitle: L10n.text("Tap successive points on the map to trace a route"))
                    newRow(.polygon,  subtitle: L10n.text("Mark out an area or boundary"))
                    newRow(.point,    subtitle: L10n.text("Drop a single labelled point"))
                }

                // group by layer so user can scan one layer at a time
                // without having to filter
                ForEach(drawingStore.layers) { layer in
                    let shapesInLayer = drawingStore.shapes(in: layer.id)
                    if !shapesInLayer.isEmpty {
                        Section {
                            ForEach(shapesInLayer) { shape in
                                shapeRow(shape)
                            }
                        } header: {
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(Color(hex: layer.defaultColorHex))
                                    .frame(width: 10, height: 10)
                                Text("\(layer.name) (\(shapesInLayer.count))")
                                if !layer.visible {
                                    Text(L10n.text("hidden"))
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if drawingStore.shapes.isEmpty {
                    Section { Text(L10n.text("No drawings yet.")).foregroundStyle(.secondary).font(.callout) }
                }
            }
            .navigationTitle(L10n.text("Drawings"))
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Done")) { dismiss() }
                }
            }
            .alert(L10n.text("Delete drawing?"),
                   isPresented: Binding(get: { pendingDelete != nil },
                                        set: { if !$0 { pendingDelete = nil } }),
                   presenting: pendingDelete) { shape in
                Button(L10n.text("Delete"), role: .destructive) {
                    do {
                        _ = try drawingStore.deleteDurably(shape)
                        pendingDelete = nil
                    } catch {
                        pendingDelete = nil
                        mutationError = L10n.text("%1$@ Check available storage, then try again.", error.localizedDescription)
                    }
                }
                Button(L10n.text("Cancel"), role: .cancel) { pendingDelete = nil }
            } message: { shape in
                Text(L10n.text("This will permanently remove “%1$@” — %2$@.", shape.name ?? shape.kind.displayName, L10n.quantity("point", shape.coordinates.count)))
            }
            .alert(L10n.text("Rename drawing"),
                   isPresented: Binding(get: { renamingShape != nil },
                                        set: { if !$0 { renamingShape = nil } }),
                   presenting: renamingShape) { shape in
                TextField(L10n.text("Name"), text: $renameDraft).autocorrectionDisabled()
                Button(L10n.text("Save")) {
                    var updated = shape
                    let trimmed = renameDraft.trimmingCharacters(in: .whitespaces)
                    updated.name = trimmed.isEmpty ? nil : trimmed
                    do {
                        _ = try drawingStore.commitEdit(updated, actionName: L10n.text("Rename Drawing"))
                        renamingShape = nil
                    } catch {
                        renamingShape = nil
                        mutationError = L10n.text("%1$@ Check available storage, then try again.", error.localizedDescription)
                    }
                }
                Button(L10n.text("Cancel"), role: .cancel) { renamingShape = nil }
            }
            .alert(L10n.text("Drawing Not Saved"), isPresented: Binding(
                get: { mutationError != nil },
                set: { if !$0 { mutationError = nil } }
            )) {
                Button(Messages.acknowledge(), role: .cancel) { mutationError = nil }
            } message: {
                Text(mutationError ?? L10n.text("The drawing change could not be saved. Check available storage, then try again."))
            }
        }
    }

    @ViewBuilder
    private func newRow(_ kind: DrawingKind, subtitle: String) -> some View {
        Button {
            guard let layerID = activeLayerID else { return }
            if let layer = drawingStore.layer(id: layerID) {
                session.strokeColorHex = layer.defaultColorHex
            }
            session.start(kind: kind, layerID: layerID)
            dismiss()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: kind.sfSymbol)
                    .font(.title3)
                    .frame(width: 28)
                    .foregroundStyle(Color(red: 1, green: 0.65, blue: 0.18))
                VStack(alignment: .leading, spacing: 2) {
                    Text(L10n.text("New %1$@", kind.displayName))
                        .foregroundStyle(.primary)
                        .font(.body.weight(.semibold))
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .disabled(activeLayerID == nil)
    }

    @ViewBuilder
    private func shapeRow(_ shape: DrawingShape) -> some View {
        HStack(spacing: 10) {
            Image(systemName: shape.kind.sfSymbol)
                .foregroundStyle(Color(hex: shape.style.strokeColorHex))
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(shape.name ?? shape.kind.displayName)
                    .font(.callout)
                Text(L10n.quantity("point", shape.coordinates.count) + " · " + DisplayFormat.dateTime(shape.createdAt))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            // explicit rename button so users don't have to discover
            // swipe or long-press
            Button {
                renameDraft = shape.name ?? ""
                renamingShape = shape
            } label: {
                Image(systemName: "pencil")
                    .foregroundStyle(.indigo)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L10n.text("Rename %1$@", shape.name ?? shape.kind.displayName))
            Button(role: .destructive) {
                pendingDelete = shape
            } label: {
                Image(systemName: "trash")
                    .foregroundStyle(.red)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(L10n.text("Delete %1$@", shape.name ?? shape.kind.displayName))
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                pendingDelete = shape
            } label: {
                Label(L10n.text("Delete"), systemImage: "trash")
            }
            Button {
                renameDraft = shape.name ?? ""
                renamingShape = shape
            } label: {
                Label(L10n.text("Rename"), systemImage: "pencil")
            }
            .tint(.indigo)
        }
        .contextMenu {
            Button {
                renameDraft = shape.name ?? ""
                renamingShape = shape
            } label: {
                Label(L10n.text("Rename"), systemImage: "pencil")
            }
            Button(role: .destructive) {
                pendingDelete = shape
            } label: {
                Label(L10n.text("Delete"), systemImage: "trash")
            }
        }
    }

    @ViewBuilder
    private var layerPicker: some View {
        Picker(L10n.text("Layer"), selection: Binding(
            get: { drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? UUID() },
            set: { drawingStore.activeLayerID = $0 }
        )) {
            ForEach(drawingStore.layers) { layer in
                HStack {
                    Circle()
                        .fill(Color(hex: layer.defaultColorHex))
                        .frame(width: 12, height: 12)
                    Text(layer.name)
                }
                .tag(layer.id)
            }
        }
    }

    private var activeLayerID: UUID? {
        drawingStore.activeLayerID ?? drawingStore.layers.first?.id
    }
}
