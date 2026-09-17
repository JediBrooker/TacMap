import SwiftUI

/// Modal listing toggleable overlay layers.
struct LayersSheet: View {
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var waypointStore: WaypointStore
    /// Called when user wants to calibrate the current PDF. ContentView
    /// dismisses this sheet and kicks off CalibrationSession.
    var onCalibrate: () -> Void = {}
    @Environment(\.dismiss) private var dismiss

    @State private var showingNewLayerSheet = false
    @State private var pendingDeleteLayer: DrawingLayer? = nil
    @State private var editingLayer: DrawingLayer? = nil
    @State private var tilingProgress: PDFTiler.Progress? = nil
    @State private var tilingTask: Task<Void, Never>? = nil
    @State private var tilingError: String? = nil
    @State private var layerDeleteError: String? = nil
    @State private var layerMutationError: String? = nil
    /// Persisted imported map that's not currently active, so user can
    /// switch back after picking an online basemap.
    @State private var restorableImportedMap: MapSource? = nil
    @State private var retainedMapError: String? = nil
    @State private var confirmingImportedMapDeletion = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Overlays") {
                    Toggle("Symbology",     isOn: $visibility.waypointsVisible)
                    Toggle("Drawings",      isOn: $visibility.drawingsVisible)
                    Toggle("User Location", isOn: $visibility.userLocationVisible)
                    Toggle("MGRS Grid",     isOn: $visibility.mgrsGridVisible)
                    Toggle("Terrain Heat-map", isOn: $visibility.terrainHeatmapVisible)
                }

                Section("Labels") {
                    Toggle("Unit Labels",    isOn: $visibility.unitLabelsVisible)
                    Toggle("Unit Amplifiers", isOn: $visibility.unitAmplifiersVisible)
                    Toggle("Task Labels",    isOn: $visibility.taskLabelsVisible)
                    Toggle("Drawing Labels", isOn: $visibility.drawingLabelsVisible)
                }

                drawingLayersSection

                importedMapSection

                basemapSection
            }
            .onAppear {
                // Offer a "switch back" to a persisted imported map that isn't
                // the current source.
                refreshRetainedMap()
            }
            .navigationTitle("Layers and Labels")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .sheet(isPresented: $showingNewLayerSheet) {
                NewLayerSheet { name, hex in
                    _ = try drawingStore.addLayer(name: name, defaultColorHex: hex)
                }
            }
            .sheet(item: $editingLayer) { layer in
                EditLayerSheet(layer: layer) { name, hex in
                    try drawingStore.updateLayer(
                        layer,
                        name: name,
                        defaultColorHex: hex
                    )
                }
            }
            .alert("Delete layer?",
                   isPresented: Binding(get: { pendingDeleteLayer != nil },
                                        set: { if !$0 { pendingDeleteLayer = nil } }),
                   presenting: pendingDeleteLayer) { layer in
                Button("Delete", role: .destructive) {
                    do {
                        _ = try drawingStore.removeLayer(
                            layer,
                            reassigningWaypointsIn: waypointStore
                        )
                    } catch {
                        layerDeleteError = error.localizedDescription
                    }
                    pendingDeleteLayer = nil
                }
                Button("Cancel", role: .cancel) { pendingDeleteLayer = nil }
            } message: { layer in
                let drawings = drawingStore.shapes(in: layer.id).count
                let waypoints = waypointStore.waypoints.filter { $0.layerID == layer.id }.count
                Text("\(drawings) drawing\(drawings == 1 ? "" : "s") and \(waypoints) waypoint\(waypoints == 1 ? "" : "s") will move to Friendly before “\(layer.name)” is removed.")
            }
            .alert("Offline tiles",
                   isPresented: Binding(get: { tilingError != nil },
                                        set: { if !$0 { tilingError = nil } }),
                   presenting: tilingError) { _ in
                Button("OK", role: .cancel) { tilingError = nil }
            } message: { msg in Text(msg) }
            .alert("Layer change not saved",
                   isPresented: Binding(get: { layerMutationError != nil },
                                        set: { if !$0 { layerMutationError = nil } }),
                   presenting: layerMutationError) { _ in
                Button("OK", role: .cancel) { layerMutationError = nil }
            } message: { msg in Text(msg) }
            .alert("Delete imported map from this device?",
                   isPresented: $confirmingImportedMapDeletion) {
                Button("Delete Map", role: .destructive) { deleteRetainedImportedMap() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This deletes the app-private PDF or MBTiles copy and removes it from the map library. Mission objects are not affected. This cannot be undone.")
            }
            .background(
                EmptyView()
                    .alert("Map change not saved",
                           isPresented: Binding(
                            get: { mapVM.mapSelectionPersistenceIssue != nil },
                            set: { if !$0 { mapVM.dismissMapSelectionPersistenceIssue() } }
                           ),
                           presenting: mapVM.mapSelectionPersistenceIssue) { _ in
                        Button("Retry") {
                            if mapVM.retryMapSelectionPersistence() {
                                refreshRetainedMap()
                            }
                        }
                        Button("Not Now", role: .cancel) {
                            mapVM.dismissMapSelectionPersistenceIssue()
                        }
                    } message: { issue in
                        Text(issue.message)
                    }
            )
        }
    }

    /// Bake the calibrated PDF into offline MBTiles off main thread,
    /// then swap active source to the generated tiles.
    private func generateTiles(from pdf: PDFMapSource) {
        tilingProgress = PDFTiler.Progress(done: 0, total: 0)
        tilingTask = Task.detached(priority: .userInitiated) {
            let url = PDFTiler.generate(source: pdf) { p in
                Task { @MainActor in tilingProgress = p }
            }
            let cancelled = Task.isCancelled
            await MainActor.run {
                tilingProgress = nil
                tilingTask = nil
                if cancelled { return } // user cancelled, bail out
                if let url, let source = OfflineTileMapSource(url: url) {
                    if mapVM.selectMapSource(source) {
                        refreshRetainedMap()
                        dismiss()
                    }
                } else {
                    // don't fail silently, the bake didn't produce a usable set
                    tilingError = "Couldn't generate offline tiles. Check that the device has free storage and try again."
                }
            }
        }
    }

    /// Pulled out b/c the outer body was hitting SwiftUI's type-checker
    /// complexity limit.
    @ViewBuilder
    private var importedMapSection: some View {
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
                if let p = tilingProgress {
                    let progressValue: Double = p.total > 0
                        ? Double(p.done) / Double(p.total)
                        : 0
                    VStack(alignment: .leading, spacing: 6) {
                        ProgressView(value: progressValue)
                        Text(p.total > 0 ? "Generating offline tiles — \(p.done)/\(p.total)" : "Preparing…")
                            .font(.caption2).foregroundStyle(.secondary)
                        Button(role: .cancel) { tilingTask?.cancel() } label: {
                            Label("Cancel", systemImage: "xmark.circle")
                        }
                        .font(.caption)
                    }
                } else {
                    Button {
                        generateTiles(from: pdfSource)
                    } label: {
                        Label("Generate Offline Tiles…", systemImage: "square.stack.3d.down.right")
                    }
                    Text("Bakes this calibrated map into an offline tile set on-device — no desktop tools.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
                Button {
                    if mapVM.selectMapSource(OnlineRasterBasemapSource.makeDefault()) {
                        refreshRetainedMap()
                    }
                } label: {
                    Label("Switch to Online Basemap", systemImage: "globe")
                }
                Button(role: .destructive) {
                    confirmingImportedMapDeletion = true
                } label: {
                    Label("Delete PDF Map…", systemImage: "trash")
                }
            } else if let tileSource = mapVM.mapSource as? OfflineTileMapSource {
                VStack(alignment: .leading, spacing: 2) {
                    Text(tileSource.displayName).font(.callout)
                    Text("Offline MBTiles raster — no network needed")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Button {
                    if mapVM.selectMapSource(OnlineRasterBasemapSource.makeDefault()) {
                        refreshRetainedMap()
                    }
                } label: {
                    Label("Switch to Online Basemap", systemImage: "globe")
                }
                Button(role: .destructive) {
                    confirmingImportedMapDeletion = true
                } label: {
                    Label("Delete Offline Map…", systemImage: "trash")
                }
            } else if let stored = restorableImportedMap {
                VStack(alignment: .leading, spacing: 2) {
                    Text(stored.displayName).font(.callout)
                    Text("Saved locally and available from the Basemap section below")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Button(role: .destructive) {
                    confirmingImportedMapDeletion = true
                } label: {
                    Label("Delete Saved Imported Map…", systemImage: "trash")
                }
            } else {
                Label("None loaded", systemImage: "doc")
                    .foregroundStyle(.secondary)
                Text("Import a PDF/GeoPDF via ☰ → Import PDF Map, or an MBTiles raster via ☰ → Import Offline Tiles.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    @ViewBuilder
    private var basemapSection: some View {
        Section("Basemap") {
            let importedActive = mapVM.mapSource is PDFMapSource
                || mapVM.mapSource is OfflineTileMapSource
            // Switching basemap just changes the shown layer, doesn't
            // clobber the imported map's session anymore. Imported map
            // stays re-loadable and restores on next launch. Use
            // The explicit delete action above removes the retained file.
            // Four online basemaps. Keyed styles (all but OSM Topo) need the
            // ArcGIS key baked in at build time; no key -> hide them rather than
            // offer a basemap that would render blank.
            ForEach(BasemapStyle.allCases.filter {
                !$0.requiresEsriKey || EsriKey.isAvailable
            }, id: \.self) { style in
                basemapRow(title: style.displayName,
                           systemImage: basemapIcon(style),
                           isActive: (mapVM.mapSource as? OnlineRasterBasemapSource)?.style == style) {
                    if mapVM.selectOnlineBasemap(style) { refreshRetainedMap() }
                }
            }
            if !importedActive, let stored = restorableImportedMap {
                let title = "Imported map (" + stored.displayName + ")"
                let icon = stored is OfflineTileMapSource ? "square.stack.3d.up" : "doc.viewfinder"
                basemapRow(title: title, systemImage: icon, isActive: false) {
                    if mapVM.restoreRetainedMap(stored) { refreshRetainedMap() }
                }
            }
            if !importedActive, let retainedMapError {
                Label("Saved imported map unavailable", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
                Text(retainedMapError)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Button("Remove Saved Map Entry") {
                    if mapVM.removeUnavailableRetainedMapEntry() {
                        refreshRetainedMap()
                    }
                }
            }
            if importedActive {
                Text("An imported map is active — pick a basemap above to view it instead; the imported map stays available to switch back to.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func refreshRetainedMap() {
        // The current source already represents the retained imported map. Do
        // not open a second SQLite connection merely to build a hidden return
        // row while MBTiles is active.
        if mapVM.mapSource is PDFMapSource || mapVM.mapSource is OfflineTileMapSource {
            restorableImportedMap = nil
            retainedMapError = nil
            return
        }
        switch mapVM.restoreRetainedMapSelection() {
        case .noRetainedMap:
            restorableImportedMap = nil
            retainedMapError = nil
        case .restored(let source):
            restorableImportedMap = source
            retainedMapError = nil
        case .unavailable(let message):
            restorableImportedMap = nil
            retainedMapError = message
        }
    }

    private func deleteRetainedImportedMap() {
        let source = (mapVM.mapSource is PDFMapSource || mapVM.mapSource is OfflineTileMapSource)
            ? mapVM.mapSource
            : restorableImportedMap
        guard let source else { return }
        if mapVM.deleteRetainedImportedMap(source) {
            restorableImportedMap = nil
            refreshRetainedMap()
        }
    }

    private func basemapIcon(_ style: BasemapStyle) -> String {
        switch style {
        case .esriSatellite: return "globe.americas.fill"
        case .esriTopo:      return "mountain.2.fill"
        case .osmTopo:       return "mountain.2"
        case .osmStreet:     return "map.fill"
        }
    }

    private func basemapRow(title: String,
                            systemImage: String,
                            isActive: Bool,
                            select: @escaping () -> Void) -> some View {
        Button(action: select) {
            HStack {
                Image(systemName: systemImage)
                    .frame(width: 24)
                Text(title)
                    .foregroundStyle(.primary)
                Spacer()
                if isActive {
                    Image(systemName: "checkmark")
                        .foregroundStyle(.tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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
            Text("Toggle to hide a layer. Deleting a custom layer moves its waypoints and drawings to Friendly first. Default layers are always preserved.")
                .font(.caption2)
        }
        .alert("Layer not deleted",
               isPresented: Binding(get: { layerDeleteError != nil },
                                    set: { if !$0 { layerDeleteError = nil } }),
               presenting: layerDeleteError) { _ in
            Button("OK", role: .cancel) { layerDeleteError = nil }
        } message: { msg in Text(msg) }
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
                if drawingStore.needsLegacyProtectionReview(layer) {
                    Text("Legacy layer — classify before editing or deleting")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
            }
            Spacer()
            Toggle("", isOn: Binding(
                get: { layer.visible },
                set: { visible in
                    do {
                        try drawingStore.setLayerVisible(layer, visible)
                    } catch {
                        layerMutationError = error.localizedDescription
                    }
                }
            ))
            .labelsHidden()
            .accessibilityLabel("\(layer.name) visibility")
            .accessibilityValue(layer.visible ? "Visible" : "Hidden")
        }
        .contentShape(Rectangle())
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !drawingStore.isProtectedDefaultLayer(layer),
               !drawingStore.needsLegacyProtectionReview(layer) {
                Button(role: .destructive) {
                    pendingDeleteLayer = layer
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            }
            if !drawingStore.isProtectedDefaultLayer(layer),
               !drawingStore.needsLegacyProtectionReview(layer) {
                Button {
                    editingLayer = layer
                } label: {
                    Label("Edit", systemImage: "pencil")
                }
                .tint(.indigo)
            }
        }
        .contextMenu {
            if drawingStore.needsLegacyProtectionReview(layer) {
                Button {
                    do {
                        try drawingStore.resolveLegacyProtectionReview(
                            layer,
                            asProtectedDefault: false
                        )
                    } catch {
                        layerMutationError = error.localizedDescription
                    }
                } label: {
                    Label("Confirm as Custom Layer", systemImage: "checkmark.shield")
                }
                Button {
                    do {
                        try drawingStore.resolveLegacyProtectionReview(
                            layer,
                            asProtectedDefault: true
                        )
                    } catch {
                        layerMutationError = error.localizedDescription
                    }
                } label: {
                    Label("Keep as Default Layer", systemImage: "lock.shield")
                }
            } else if !drawingStore.isProtectedDefaultLayer(layer) {
                Button {
                    editingLayer = layer
                } label: {
                    Label("Edit name and colour", systemImage: "pencil")
                }
                Button(role: .destructive) {
                    pendingDeleteLayer = layer
                } label: {
                    Label("Delete layer", systemImage: "trash")
                }
            }
        }
    }
}

/// Asks for a name + colour for a new drawing layer.
private struct NewLayerSheet: View {
    let onCreate: (String, String) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var hex: String  = DrawingPalette.swatches[0].hex
    @State private var saveError: String?

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
                            .frame(minWidth: 44, minHeight: 44)
                            .buttonStyle(.plain)
                            .accessibilityLabel(swatch.name)
                            .accessibilityAddTraits(
                                swatch.hex.caseInsensitiveCompare(hex) == .orderedSame
                                    ? .isSelected
                                    : []
                            )
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
                        do {
                            try onCreate(trimmed, hex)
                            dismiss()
                        } catch {
                            saveError = error.localizedDescription
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .alert("Layer not created",
                   isPresented: Binding(get: { saveError != nil },
                                        set: { if !$0 { saveError = nil } }),
                   presenting: saveError) { _ in
                Button("OK", role: .cancel) { saveError = nil }
            } message: { message in Text(message) }
        }
    }
}

/// Edits a custom layer's name and colour as one durable transaction.
private struct EditLayerSheet: View {
    let layer: DrawingLayer
    let onSave: (String, String) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var hex: String
    @State private var saveError: String?

    init(layer: DrawingLayer,
         onSave: @escaping (String, String) throws -> Void) {
        self.layer = layer
        self.onSave = onSave
        _name = State(initialValue: layer.name)
        _hex = State(initialValue: layer.defaultColorHex)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Layer name", text: $name)
                        .autocorrectionDisabled()
                }
                Section("Colour") {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 6),
                        spacing: 12
                    ) {
                        ForEach(DrawingPalette.swatches) { swatch in
                            let selected = swatch.hex.caseInsensitiveCompare(hex) == .orderedSame
                            Button {
                                hex = swatch.hex
                            } label: {
                                ZStack {
                                    Circle()
                                        .fill(swatch.color)
                                        .frame(width: 36, height: 36)
                                    if selected {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.white)
                                            .font(.headline.weight(.bold))
                                    }
                                }
                                .frame(minWidth: 44, minHeight: 44)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(swatch.name)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
            .navigationTitle("Edit Layer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Save") {
                        do {
                            try onSave(name, hex)
                            dismiss()
                        } catch {
                            saveError = error.localizedDescription
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .alert("Layer not saved",
                   isPresented: Binding(get: { saveError != nil },
                                        set: { if !$0 { saveError = nil } }),
                   presenting: saveError) { _ in
                Button("OK", role: .cancel) { saveError = nil }
            } message: { message in Text(message) }
        }
    }
}
