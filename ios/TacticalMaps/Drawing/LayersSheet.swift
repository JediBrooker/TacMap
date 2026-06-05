import SwiftUI

/// Modal listing toggleable overlay layers.
struct LayersSheet: View {
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var drawingStore: DrawingStore
    /// Closure invoked when the user requests fiduciary calibration for the
    /// currently-loaded PDF. ContentView dismisses this sheet and starts the
    /// CalibrationSession.
    var onCalibrate: () -> Void = {}
    @Environment(\.dismiss) private var dismiss

    @State private var showingNewLayerSheet = false
    @State private var pendingDeleteLayer: DrawingLayer? = nil
    @State private var renamingLayer: DrawingLayer? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section("Overlays") {
                    Toggle("Symbology",     isOn: $visibility.waypointsVisible)
                    Toggle("Drawings",      isOn: $visibility.drawingsVisible)
                    Toggle("User Location", isOn: $visibility.userLocationVisible)
                    Toggle("MGRS Grid",     isOn: $visibility.mgrsGridVisible)
                }

                Section("Labels") {
                    Toggle("Unit Labels",    isOn: $visibility.unitLabelsVisible)
                    Toggle("Task Labels",    isOn: $visibility.taskLabelsVisible)
                    Toggle("Drawing Labels", isOn: $visibility.drawingLabelsVisible)
                }

                drawingLayersSection

                Section("Imported Map") {
                    if let pdfSource = mapVM.mapSource as? PDFMapSource {
                        Toggle(isOn: $visibility.pdfOverlayVisible) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(pdfSource.displayName).font(.callout)
                                Text(pdfSource.bounds == nil
                                     ? "No georeferencing — using map-centre fallback"
                                     : (pdfSource.kind == .geoPDF
                                        ? "Georeferenced (GeoPDF LGIDict)"
                                        : "Manually placed bounds"))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Button {
                            dismiss()
                            onCalibrate()
                        } label: {
                            Label("Calibrate with fiduciaries…", systemImage: "scope")
                        }
                        if let fids = pdfSource.fiduciaries, !fids.isEmpty {
                            Text("Currently calibrated with \(fids.count) fiduciaries")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Button(role: .destructive) {
                            mapVM.mapSource = AppleSatelliteMapSource()
                            /// Clear the persisted entry too, otherwise the
                            /// PDF the user just unloaded resurrects on the
                            /// next launch.
                            PDFSessionStore.clear()
                        } label: {
                            Label("Unload PDF", systemImage: "xmark.circle")
                        }
                    } else if let tileSource = mapVM.mapSource as? OfflineTileMapSource {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tileSource.displayName).font(.callout)
                            Text("Offline MBTiles raster — no network needed")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Button(role: .destructive) {
                            mapVM.mapSource = AppleSatelliteMapSource()
                        } label: {
                            Label("Unload offline tiles", systemImage: "xmark.circle")
                        }
                    } else {
                        Label("None loaded", systemImage: "doc")
                            .foregroundStyle(.secondary)
                        Text("Import a PDF/GeoPDF via ☰ → Import PDF Map, or an MBTiles raster via ☰ → Import Offline Tiles.")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Section("Basemap") {
                    HStack {
                        Image(systemName: "globe.americas.fill")
                        Text("Apple Satellite")
                        Spacer()
                        Text(mapVM.mapSource is AppleSatelliteMapSource
                             ? "Active"
                             : "Hidden while an imported map is loaded")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Layers and Labels")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $showingNewLayerSheet) {
                NewLayerSheet { name, hex in
                    _ = drawingStore.addLayer(name: name, defaultColorHex: hex)
                }
            }
            .alert("Delete layer?",
                   isPresented: Binding(get: { pendingDeleteLayer != nil },
                                        set: { if !$0 { pendingDeleteLayer = nil } }),
                   presenting: pendingDeleteLayer) { layer in
                Button("Delete", role: .destructive) {
                    drawingStore.removeLayer(layer)
                    pendingDeleteLayer = nil
                }
                Button("Cancel", role: .cancel) { pendingDeleteLayer = nil }
            } message: { layer in
                let n = drawingStore.shapes(in: layer.id).count
                Text("This will permanently remove “\(layer.name)” and \(n) drawing\(n == 1 ? "" : "s") on it.")
            }
            .alert("Rename layer",
                   isPresented: Binding(get: { renamingLayer != nil },
                                        set: { if !$0 { renamingLayer = nil } }),
                   presenting: renamingLayer) { layer in
                RenameLayerAlert(initialName: layer.name) { newName in
                    drawingStore.renameLayer(layer, to: newName)
                    renamingLayer = nil
                }
                Button("Cancel", role: .cancel) { renamingLayer = nil }
            }
        }
    }

    @ViewBuilder
    private var drawingLayersSection: some View {
        Section {
            ForEach(drawingStore.layers) { layer in
                drawingLayerRow(layer)
            }
            Button {
                showingNewLayerSheet = true
            } label: {
                Label("New Layer…", systemImage: "plus.circle")
            }
        } header: {
            Text("Drawing Layers")
        } footer: {
            Text("Toggle to hide a layer without deleting it. Delete removes the layer and every drawing on it.")
                .font(.caption2)
        }
    }

    @ViewBuilder
    private func drawingLayerRow(_ layer: DrawingLayer) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color(hex: layer.defaultColorHex))
                .frame(width: 18, height: 18)
                .overlay(Circle().stroke(.secondary.opacity(0.4), lineWidth: 0.5))
            VStack(alignment: .leading, spacing: 2) {
                Text(layer.name)
                    .font(.callout)
                Text("\(drawingStore.shapes(in: layer.id).count) drawing\(drawingStore.shapes(in: layer.id).count == 1 ? "" : "s")")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { layer.visible },
                set: { drawingStore.setLayerVisible(layer, $0) }
            ))
            .labelsHidden()
        }
        .contentShape(Rectangle())
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            Button(role: .destructive) {
                pendingDeleteLayer = layer
            } label: {
                Label("Delete", systemImage: "trash")
            }
            Button {
                renamingLayer = layer
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            .tint(.indigo)
        }
        .contextMenu {
            Button {
                renamingLayer = layer
            } label: {
                Label("Rename", systemImage: "pencil")
            }
            Menu {
                ForEach(DrawingPalette.swatches) { swatch in
                    Button {
                        drawingStore.updateLayerColor(layer, to: swatch.hex)
                    } label: {
                        Label(swatch.name, systemImage: "circle.fill")
                    }
                    .tint(swatch.color)
                }
            } label: {
                Label("Change colour…", systemImage: "paintpalette")
            }
            Button(role: .destructive) {
                pendingDeleteLayer = layer
            } label: {
                Label("Delete layer", systemImage: "trash")
            }
        }
    }
}

/// Modal that asks for a name + colour for a new drawing layer.
private struct NewLayerSheet: View {
    let onCreate: (String, String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var hex: String  = DrawingPalette.swatches[0].hex

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("e.g. Friendly, Hostile, Civilian", text: $name)
                        .autocorrectionDisabled()
                }
                Section("Colour") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 6),
                              spacing: 12) {
                        ForEach(DrawingPalette.swatches) { swatch in
                            Button {
                                hex = swatch.hex
                            } label: {
                                ZStack {
                                    Circle()
                                        .fill(swatch.color)
                                        .frame(width: 36, height: 36)
                                    if swatch.hex.caseInsensitiveCompare(hex) == .orderedSame {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.white)
                                            .font(.headline.weight(.bold))
                                    }
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(swatch.name)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("New Layer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Create") {
                        let trimmed = name.trimmingCharacters(in: .whitespaces)
                        onCreate(trimmed.isEmpty ? "Layer" : trimmed, hex)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
    }
}

/// Helper that injects a TextField + OK button into a SwiftUI .alert.
/// Used by the rename flow so we don't pull in a whole second sheet.
private struct RenameLayerAlert: View {
    let initialName: String
    let onRename: (String) -> Void
    @State private var text: String = ""

    init(initialName: String, onRename: @escaping (String) -> Void) {
        self.initialName = initialName
        self.onRename = onRename
        _text = State(initialValue: initialName)
    }

    var body: some View {
        TextField("Layer name", text: $text)
        Button("Save") {
            let trimmed = text.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty { onRename(trimmed) }
        }
    }
}
