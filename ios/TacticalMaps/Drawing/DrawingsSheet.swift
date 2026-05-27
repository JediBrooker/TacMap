import SwiftUI
import MapKit

/// Full-management view for saved drawings. Open via DrawingsPanel → "All
/// Drawings". Supports per-row delete (explicit trash button + swipe action).
struct DrawingsSheet: View {
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var session: DrawingSessionViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var pendingDelete: DrawingShape? = nil

    var body: some View {
        NavigationStack {
            List {
                Section {
                    layerPicker
                } header: {
                    Text("Active Layer")
                } footer: {
                    Text("New drawings are added to this layer.")
                        .font(.caption2)
                }

                Section("New Drawing") {
                    newRow(.polyline, subtitle: "Tap successive points on the map to trace a route")
                    newRow(.polygon,  subtitle: "Mark out an area or boundary")
                    newRow(.point,    subtitle: "Drop a single labelled point")
                }

                // Group saved shapes by layer. Each non-empty layer becomes
                // its own section so the user can scan a single layer's
                // shapes without filtering.
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
                                    Text("hidden")
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                if drawingStore.shapes.isEmpty {
                    Section { Text("No drawings yet.").foregroundStyle(.secondary).font(.callout) }
                }
            }
            .navigationTitle("Drawings")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .alert("Delete drawing?",
                   isPresented: Binding(get: { pendingDelete != nil },
                                        set: { if !$0 { pendingDelete = nil } }),
                   presenting: pendingDelete) { shape in
                Button("Delete", role: .destructive) {
                    drawingStore.remove(shape)
                    pendingDelete = nil
                }
                Button("Cancel", role: .cancel) { pendingDelete = nil }
            } message: { shape in
                Text("This will permanently remove “\(shape.name ?? shape.kind.displayName)” — \(shape.coordinates.count) point\(shape.coordinates.count == 1 ? "" : "s").")
            }
        }
    }

    @ViewBuilder
    private func newRow(_ kind: DrawingKind, subtitle: String) -> some View {
        Button {
            guard let layerID = activeLayerID else { return }
            session.start(kind: kind, layerID: layerID)
            dismiss()
        } label: {
            HStack(spacing: 10) {
                Image(systemName: kind.sfSymbol)
                    .font(.title3)
                    .frame(width: 28)
                    .foregroundStyle(Color(red: 1, green: 0.65, blue: 0.18))
                VStack(alignment: .leading, spacing: 2) {
                    Text("New \(kind.displayName)")
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
                Text("\(shape.coordinates.count) point\(shape.coordinates.count == 1 ? "" : "s") · \(shape.createdAt.formatted(date: .abbreviated, time: .shortened))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Button(role: .destructive) {
                pendingDelete = shape
            } label: {
                Image(systemName: "trash")
                    .foregroundStyle(.red)
                    .frame(width: 32, height: 32)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("Delete \(shape.name ?? shape.kind.displayName)")
        }
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button(role: .destructive) {
                pendingDelete = shape
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    @ViewBuilder
    private var layerPicker: some View {
        Picker("Layer", selection: Binding(
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
