import SwiftUI

/// Modal listing toggleable overlay layers.
struct LayersSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var visibility: LayerVisibility
    @ObservedObject var mapVM: MapViewModel
    @ObservedObject var drawingStore: DrawingStore
    @ObservedObject var waypointStore: WaypointStore
    /// Calibrate / refine an imported PDF. ContentView dismisses this sheet
    /// and runs the calibration start sequence.
    var onCalibrate: (UUID) -> Void = { _ in }
    /// Choose page... on a multi-page PDF entry
    var onChoosePage: (ImportedMapEntry) -> Void = { _ in }
    /// J1: while calibrating the imported map is forced on, the toggle shows
    /// that and cant be changed. its stored value comes back afterwards
    var isCalibrating: Bool = false
    @Environment(\.dismiss) private var dismiss

    @State private var showingNewLayerSheet = false
    @State private var pendingDeleteLayer: DrawingLayer? = nil
    @State private var editingLayer: DrawingLayer? = nil
    /// the bake lives app wide, the sheet just shows it (D3-10)
    @ObservedObject private var bake = PDFBakeController.shared
    @State private var layerDeleteError: LocalizedMessage? = nil
    @State private var layerMutationError: LocalizedMessage? = nil

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.text("Overlays")) {
                    Toggle(L10n.text("Symbology"),     isOn: $visibility.waypointsVisible)
                    Toggle(L10n.text("Drawings"),      isOn: $visibility.drawingsVisible)
                    Toggle(L10n.text("User Location"), isOn: $visibility.userLocationVisible)
                    Toggle(L10n.text("MGRS Grid"),     isOn: $visibility.mgrsGridVisible)
                    Toggle(L10n.text("Terrain Heat-map"), isOn: $visibility.terrainHeatmapVisible)
                }

                Section(L10n.text("Labels")) {
                    Toggle(L10n.text("Unit Labels"),    isOn: $visibility.unitLabelsVisible)
                    Toggle(L10n.text("Unit Amplifiers"), isOn: $visibility.unitAmplifiersVisible)
                    Toggle(L10n.text("Task Labels"),    isOn: $visibility.taskLabelsVisible)
                    Toggle(L10n.text("Drawing Labels"), isOn: $visibility.drawingLabelsVisible)
                }

                drawingLayersSection

                // the map on screen: Show Imported Map, render failure, its bake (WP2)
                importedMapSection

                ImportedMapsSection(
                    mapVM: mapVM,
                    bake: bake,
                    onCalibrate: { id in
                        dismiss()
                        onCalibrate(id)
                    },
                    onChoosePage: { entry in
                        dismiss()
                        onChoosePage(entry)
                    },
                    onGenerateTiles: generateTiles
                )

                basemapSection
            }
            .onDisappear {
                // an estimate / confirm belongs to this sheet, a running bake doesnt
                if !bake.isRunning, bakeError == nil { bake.dismiss() }
            }
            .navigationTitle(Messages.layersScreenTitle())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Done")) { dismiss() }
                }
            }
            .nightSheet(isPresented: $showingNewLayerSheet) {
                NewLayerSheet { name, hex in
                    _ = try drawingStore.addLayer(name: name, defaultColorHex: hex)
                }
            }
            .nightSheet(item: $editingLayer) { layer in
                EditLayerSheet(layer: layer) { name, hex in
                    try drawingStore.updateLayer(
                        layer,
                        name: name,
                        defaultColorHex: hex
                    )
                }
            }
            .alert(L10n.text("Delete layer?"),
                   isPresented: Binding(get: { pendingDeleteLayer != nil },
                                        set: { if !$0 { pendingDeleteLayer = nil } }),
                   presenting: pendingDeleteLayer) { layer in
                Button(L10n.text("Delete"), role: .destructive) {
                    do {
                        _ = try drawingStore.removeLayer(
                            layer,
                            reassigningWaypointsIn: waypointStore
                        )
                    } catch {
                        layerDeleteError = error.displayMessage
                    }
                    pendingDeleteLayer = nil
                }
                Button(L10n.text("Cancel"), role: .cancel) { pendingDeleteLayer = nil }
            } message: { layer in
                let drawings = drawingStore.shapes(in: layer.id).count
                let waypoints = waypointStore.waypoints.filter { $0.layerID == layer.id }.count
                Text(L10n.text("%1$@ and %2$@ will move to Friendly before “%3$@” is removed.", L10n.quantity("drawing", drawings), L10n.quantity("waypoint", waypoints), layer.displayName))
            }
            .alert(Messages.pdfBakeErrorTitle(),
                   isPresented: Binding(get: { bakeError != nil },
                                        set: { if !$0 { bake.dismiss() } })) {
                Button(Messages.acknowledge(), role: .cancel) { bake.dismiss() }
            } message: {
                Text(bakeError?.text ?? "")
            }
            // J1: "Estimating size…" with Cancel, then the radio rows + Generate /
            // Cancel, one modal that morphs so the two never fight over presenting
            .nightSheet(isPresented: Binding(get: { bakeSheetShowing },
                                             set: { if !$0, bakeSheetShowing { bake.cancel() } })) {
                PDFBakeConfirmSheet(bake: bake, onScreenToken: (mapVM.mapSource as? PDFMapSource)?.renderGuardToken)
            }
            .alert(L10n.text("Layer change not saved"),
                   isPresented: Binding(get: { layerMutationError != nil },
                                        set: { if !$0 { layerMutationError = nil } }),
                   presenting: layerMutationError) { _ in
                Button(Messages.acknowledge(), role: .cancel) { layerMutationError = nil }
            } message: { msg in Text(msg.text) }
            // the sheet hosts it while its up, ContentView stands down (OD3-R3-1)
            .mapSelectionIssueAlert(mapVM: mapVM, isActive: true) { _ in }
        }
    }

    private var bakeError: PDFBakeError? {
        if case .failed(let e) = bake.state { return e }
        return nil
    }

    /// estimating or confirming, for whichever entry the row menu picked. The
    /// bake never changes the active map (J), so it isnt only the one on screen
    private var bakeSheetShowing: Bool {
        guard bake.subjectToken != nil || bake.subjectURL != nil else { return false }
        switch bake.state {
        case .estimating, .confirming: return true
        default: return false
        }
    }

    /// row menu "Generate Offline Tiles…" (WP2 J1 flow) for that entry, live
    /// runtime only when its the map on screen
    private func generateTiles(_ entry: ImportedMapEntry) {
        guard let pdf = mapVM.source(for: entry) as? PDFMapSource else { return }
        let shown = mapVM.activeEntryID == entry.id ? mapVM.mapSource as? PDFMapSource : nil
        bake.prepare(pdf: shown ?? pdf, runtime: shown != nil ? mapVM.pdfRuntime : nil)
    }

    private func georefLabel(_ pdf: PDFMapSource) -> String {
        switch pdf.georef.origin {
        case .provisional: return L10n.text("No georeferencing — using map-centre fallback")
        case .adobeVP: return Messages.pdfGeorefAdobeLabel()
        case .lgiDict: return L10n.text("Georeferenced (GeoPDF LGIDict)")
        case .fiduciaries:
            // OD-F10: a calibrated library map says so the way its Layers row
            // does, "Manually placed bounds" is only the legacy bounds origin
            if let id = pdf.entryID, let e = mapVM.entry(id), e.pdf?.manual != nil {
                return ImportedMapStates.present(e, file: .ok, draftPoints: nil, parentName: nil).subtitle.text
            }
            return L10n.text("Manually placed bounds")
        }
    }

    /// The PDF on screen (WP2): Show Imported Map, the G1 failed row with Try
    /// Again, its offline tiles. Picking, calibrating and deleting maps is the
    /// Imported maps section below (WP4). Pulled out b/c the outer body was
    /// hitting SwiftUI's type-checker complexity limit.
    @ViewBuilder
    private var importedMapSection: some View {
        if let pdfSource = mapVM.mapSource as? PDFMapSource {
            Section(L10n.text("Imported Map")) {
                Toggle(isOn: isCalibrating ? .constant(true) : $visibility.importedMapVisible) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Messages.importedMapShowToggle()).font(.callout)
                        Text(pdfSource.displayName).font(.caption)
                        Text(georefLabel(pdfSource))
                            .font(.caption2)
                            .foregroundStyle(pdfSource.isUncalibrated ? Color.orange : Color.secondary)
                    }
                }
                .disabled(isCalibrating)
                PDFRenderFailureRow(runtime: mapVM.pdfRuntime)
                // a calibration preview isnt a map you can bake
                if !isCalibrating {
                    PDFBakeRows(pdf: pdfSource, bake: bake, runtime: mapVM.pdfRuntime)
                }
            }
        }
    }

    @ViewBuilder
    private var basemapSection: some View {
        Section(L10n.text("Basemap")) {
            let importedActive = mapVM.mapSource is PDFMapSource
                || mapVM.mapSource is OfflineTileMapSource
            // Four online basemaps. Keyed styles (all but OSM Topo) need the
            // ArcGIS key baked in at build time; no key -> hide them rather than
            // offer a basemap that would render blank. Imported maps are picked
            // in the section above and stay in the library either way.
            ForEach(BasemapStyle.allCases.filter {
                !$0.requiresEsriKey || EsriKey.isAvailable
            }, id: \.self) { style in
                basemapRow(title: style.displayName,
                           systemImage: basemapIcon(style),
                           isActive: (mapVM.mapSource as? OnlineRasterBasemapSource)?.style == style) {
                    _ = mapVM.selectOnlineBasemap(style)
                }
            }
            if importedActive {
                Text(L10n.text("An imported map is active — pick a basemap above to view it instead; the imported map stays available to switch back to."))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
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
                Label(L10n.text("New Layer…"), systemImage: "plus.circle")
            }
        } header: {
            Text(L10n.text("Drawing Layers"))
        } footer: {
            Text(L10n.text("Toggle to hide a layer. Deleting a custom layer moves its waypoints and drawings to Friendly first. Default layers are always preserved."))
                .font(.caption2)
        }
        .alert(L10n.text("Layer not deleted"),
               isPresented: Binding(get: { layerDeleteError != nil },
                                    set: { if !$0 { layerDeleteError = nil } }),
               presenting: layerDeleteError) { _ in
            Button(Messages.acknowledge(), role: .cancel) { layerDeleteError = nil }
        } message: { msg in Text(msg.text) }
    }

    @ViewBuilder
    private func drawingLayerRow(_ layer: DrawingLayer) -> some View {
        HStack(spacing: 12) {
            Circle()
                .fill(Color(hex: layer.defaultColorHex))
                .frame(width: 18, height: 18)
                .overlay(Circle().stroke(.secondary.opacity(0.4), lineWidth: 0.5))
            VStack(alignment: .leading, spacing: 2) {
                Text(layer.displayName)
                    .font(.callout)
                Text(L10n.quantity("drawing", drawingStore.shapes(in: layer.id).count))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if drawingStore.needsLegacyProtectionReview(layer) {
                    Text(L10n.text("Legacy layer — classify before editing or deleting"))
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
                        layerMutationError = error.displayMessage
                    }
                }
            ))
            .labelsHidden()
            .accessibilityLabel(L10n.text("%1$@ visibility", layer.displayName))
            .accessibilityValue(layer.visible ? L10n.text("Visible") : L10n.text("Hidden"))
        }
        .contentShape(Rectangle())
        .swipeActions(edge: .trailing, allowsFullSwipe: false) {
            if !drawingStore.isProtectedDefaultLayer(layer),
               !drawingStore.needsLegacyProtectionReview(layer) {
                Button(role: .destructive) {
                    pendingDeleteLayer = layer
                } label: {
                    Label(L10n.text("Delete"), systemImage: "trash")
                }
            }
            if !drawingStore.isProtectedDefaultLayer(layer),
               !drawingStore.needsLegacyProtectionReview(layer) {
                Button {
                    editingLayer = layer
                } label: {
                    Label(L10n.text("Edit"), systemImage: "pencil")
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
                        layerMutationError = error.displayMessage
                    }
                } label: {
                    Label(L10n.text("Confirm as Custom Layer"), systemImage: "checkmark.shield")
                }
                Button {
                    do {
                        try drawingStore.resolveLegacyProtectionReview(
                            layer,
                            asProtectedDefault: true
                        )
                    } catch {
                        layerMutationError = error.displayMessage
                    }
                } label: {
                    Label(L10n.text("Keep as Default Layer"), systemImage: "lock.shield")
                }
            } else if !drawingStore.isProtectedDefaultLayer(layer) {
                Button {
                    editingLayer = layer
                } label: {
                    Label(L10n.text("Edit name and colour"), systemImage: "pencil")
                }
                Button(role: .destructive) {
                    pendingDeleteLayer = layer
                } label: {
                    Label(L10n.text("Delete layer"), systemImage: "trash")
                }
            }
        }
    }
}

/// G1 failed row with Try Again. Its own view so it follows the runtime live
private struct PDFRenderFailureRow: View {
    @ObservedObject var runtime: PDFMapRuntime

    var body: some View {
        if let failure = runtime.status.failure {
            VStack(alignment: .leading, spacing: 4) {
                Label(Messages.pdfRenderFailedLabel(), systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(PDFRenderStatusColors.failed)
                Text(failure.localizedMessage.text)
                    .font(.caption2).foregroundStyle(.secondary)
                Button(Messages.pdfRenderTryAgain()) { runtime.retry() }
                    .font(.caption)
            }
        }
    }
}

/// Generate / progress / info for the PDF's offline tiles. The bake keeps the
/// PDF and the PDF stays the active map (D5-05). Watches the bake controller
/// and the runtime itself, so Remove (OD-F1) and a failed source (OD-F9)
/// show up while the sheet is open
private struct PDFBakeRows: View {
    let pdf: PDFMapSource
    @ObservedObject var bake: PDFBakeController
    @ObservedObject var runtime: PDFMapRuntime

    var body: some View {
        // the revision is what tells SwiftUI pdf.bake moved, read it
        let _ = bake.recordRevision
        let mine = bake.subjectToken == nil || bake.isFor(pdf)
        if mine, case .running(let done, let total) = bake.state {
            VStack(alignment: .leading, spacing: 6) {
                ProgressView(value: total > 0 ? Double(done) / Double(total) : 0)
                Text(Messages.pdfBakeRunning(PDFBakeFormat.tiles(done), PDFBakeFormat.tiles(total)))
                    .font(.caption2).foregroundStyle(.secondary)
                Button(role: .cancel) { bake.cancel() } label: {
                    Label(L10n.text("Cancel"), systemImage: "xmark.circle")
                }
                .font(.caption)
            }
        } else if let record = pdf.bake {
            // J1: once a bake exists its just the info row and Remove, Generate
            // comes back after the bake is removed
            VStack(alignment: .leading, spacing: 4) {
                Text(PDFBakeFormat.info(record))
                    .font(.caption2).foregroundStyle(.secondary)
                Button(role: .destructive) {
                    // R2-S2: removeBake clears the library record, then deletes the file itself
                    bake.removeBake(from: pdf, runtime: runtime)
                } label: {
                    Label(Messages.pdfBakeRemove(), systemImage: "square.stack.3d.down.right.fill")
                }
                .font(.caption)
            }
        } else {
            Button {
                bake.prepare(pdf: pdf, runtime: runtime)
            } label: {
                Label(Messages.pdfBakeGenerateButton(), systemImage: "square.stack.3d.down.right")
            }
            // baking a guessed placement would pass it off as a real basemap.
            // OD-F9: a failed source cant bake either, Try Again on the failed row is the way out.
            // R2-S4: nor a restored one still waiting on its hash check (.preparing)
            .disabled(bake.isRunning || (mine && bake.state == .estimating)
                      || PDFBakeController.generateBlocked(pdf: pdf, status: runtime.status))
            Text(pdf.isUncalibrated ? Messages.pdfBakeDisabledCaption() : Messages.pdfBakeCaption())
                .font(.caption2).foregroundStyle(.secondary)
        }
    }
}

/// J1, Android's flow: one radio row per kept option, Generate + Cancel.
/// Rows that dont fit the free space are disabled with the suffix, the
/// minutes follow the selected row. While estimating its a spinner + Cancel
private struct PDFBakeConfirmSheet: View {
    @ObservedObject var bake: PDFBakeController
    /// the map on screen, OD-F9 words it differently for any other entry
    var onScreenToken: String?

    private var proposal: PDFBakeProposal? {
        if case .confirming(let p) = bake.state { return p }
        return nil
    }

    var body: some View {
        NavigationStack {
            Group {
                if let p = proposal {
                    confirm(p)
                } else {
                    VStack(spacing: 18) {
                        ProgressView()
                        Text(Messages.pdfBakeEstimating()).font(.callout).foregroundStyle(.secondary)
                        Button(L10n.text("Cancel"), role: .cancel) { bake.cancel() }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            // OD-F7: same title while estimating ("Estimating size…" is the body), like Android
            .navigationTitle(Messages.pdfBakeConfirmTitle())
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }

    private func confirm(_ p: PDFBakeProposal) -> some View {
        let selected = bake.selectedMaxZoom.flatMap(p.option)
        return Form {
            // OD-F7: the message sits above the rows on both platforms
            Section {
                // OD-F9: a row menu bake of a map that isnt on screen doesnt promise
                // it "remains the active map"
                Text(bake.subjectToken == nil || bake.subjectToken == onScreenToken
                     ? Messages.pdfBakeConfirmMessage(p.pdfName, PDFBakeFormat.minutes(selected?.minutes ?? 1))
                     : Messages.pdfBakeConfirmMessageInactive(p.pdfName, PDFBakeFormat.minutes(selected?.minutes ?? 1)))
                    .font(.callout)
            }
            Section {
                ForEach(p.options) { o in
                    Button { bake.select(maxZoom: o.maxZoom) } label: {
                        HStack(spacing: 10) {
                            Image(systemName: o.maxZoom == selected?.maxZoom ? "largecircle.fill.circle" : "circle")
                                .foregroundStyle(o.enoughSpace ? Color.accentColor : Color.secondary)
                            Text(PDFBakeFormat.optionLabel(o))
                                .font(.callout)
                                .foregroundStyle(o.enoughSpace ? Color.primary : Color.secondary)
                        }
                    }
                    .disabled(!o.enoughSpace)
                    .accessibilityAddTraits(o.maxZoom == selected?.maxZoom ? .isSelected : [])
                }
            }
            Section {
                Button(Messages.pdfBakeGenerate()) { bake.generate() }
                    .disabled(!bake.canGenerate)
                Button(L10n.text("Cancel"), role: .cancel) { bake.dismiss() }
            }
        }
    }
}

/// Asks for a name + colour for a new drawing layer.
private struct NewLayerSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let onCreate: (String, String) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String = ""
    @State private var hex: String  = DrawingPalette.swatches[0].hex
    @State private var saveError: LocalizedMessage?

    var body: some View {
        NavigationStack {
            Form {
                Section(L10n.text("Name")) {
                    TextField(L10n.text("e.g. Friendly, Hostile, Civilian"), text: $name)
                        .autocorrectionDisabled()
                }
                Section(L10n.text("Colour")) {
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
            .navigationTitle(L10n.text("New Layer"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.text("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Create")) {
                        let trimmed = name.trimmingCharacters(in: .whitespaces)
                        do {
                            try onCreate(trimmed, hex)
                            dismiss()
                        } catch {
                            saveError = error.displayMessage
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .alert(L10n.text("Layer not created"),
                   isPresented: Binding(get: { saveError != nil },
                                        set: { if !$0 { saveError = nil } }),
                   presenting: saveError) { _ in
                Button(Messages.acknowledge(), role: .cancel) { saveError = nil }
            } message: { message in Text(message.text) }
        }
    }
}

/// Edits a custom layer's name and colour as one durable transaction.
private struct EditLayerSheet: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let layer: DrawingLayer
    let onSave: (String, String) throws -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var hex: String
    @State private var saveError: LocalizedMessage?

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
                Section(L10n.text("Name")) {
                    TextField(L10n.text("Layer name"), text: $name)
                        .autocorrectionDisabled()
                }
                Section(L10n.text("Colour")) {
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
            .navigationTitle(L10n.text("Edit Layer"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button(L10n.text("Cancel")) { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.text("Save")) {
                        do {
                            try onSave(name, hex)
                            dismiss()
                        } catch {
                            saveError = error.displayMessage
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .alert(L10n.text("Layer not saved"),
                   isPresented: Binding(get: { saveError != nil },
                                        set: { if !$0 { saveError = nil } }),
                   presenting: saveError) { _ in
                Button(Messages.acknowledge(), role: .cancel) { saveError = nil }
            } message: { message in Text(message.text) }
        }
    }
}

/// The decisions behind MapSelectionIssueAlert, kept out of the view so tests
/// can drive them
enum MapSelectionIssueAlertGate {
    /// what a host is waiting to show. either half changing cancels the pending show
    struct Key: Equatable {
        let issueID: UUID?
        let isActive: Bool
    }

    /// settle time before showing, so it never lands in the same turn another
    /// alert (the delete confirm) or a sheet goes away. swiftui drops that one
    static let settleNanoseconds: UInt64 = 350_000_000

    /// after the settle: the issue to put up, nil = nothing (host not on top,
    /// no issue, or a newer one replaced it meanwhile)
    static func issueToShow(for key: Key, current: MapSelectionPersistenceIssue?) -> MapSelectionPersistenceIssue? {
        guard key.isActive, let current, current.id == key.issueID else { return nil }
        return current
    }

    /// the root host only shows it with nothing presented on top of it. a
    /// presentation from under a sheet knocks the sheet down and gets lost
    static func rootHostIsActive(presentationsOnTop: [Bool]) -> Bool {
        !presentationsOnTop.contains(true)
    }

    enum AlertEnd { case dismissedBySystem, notNow }

    /// only an explicit Not Now (or a Retry, which replaces or clears it)
    /// drops the issue. a dropped or pre-empted presentation just hides it,
    /// it comes back next time a host takes over
    static func clearsIssue(_ end: AlertEnd) -> Bool { end == .notNow }
}

/// "Map change not saved" for a failed map transition (delete, switch, restore).
/// OD3-R3-1: ContentView and LayersSheet both bound the one issue. With the
/// sheet up the root copy tried to present over it, knocked the sheet down and
/// lost itself, and the binding then cleared the issue unseen, so a failed
/// Delete Map looked like it worked. Now only the host on top shows it, a
/// beat after it changes, and only Retry / Not Now ever clear it
struct MapSelectionIssueAlert: ViewModifier {
    typealias Gate = MapSelectionIssueAlertGate
    @ObservedObject var mapVM: MapViewModel
    let isActive: Bool
    /// Retry tapped, true when it went through
    let onRetry: (Bool) -> Void
    @State private var shown: MapSelectionPersistenceIssue?

    func body(content: Content) -> some View {
        let key = Gate.Key(issueID: mapVM.mapSelectionPersistenceIssue?.id, isActive: isActive)
        return content
            .background(
                EmptyView()
                    .alert(L10n.text("Map change not saved"),
                           isPresented: Binding(
                            get: { shown != nil },
                            set: { if !$0 { end(.dismissedBySystem) } }
                           ),
                           presenting: shown) { _ in
                        Button(L10n.text("Retry")) {
                            // a retry that fails again posts a new issue, shown in turn
                            shown = nil
                            onRetry(mapVM.retryMapSelectionPersistence())
                        }
                        Button(L10n.text("Not Now"), role: .cancel) { end(.notNow) }
                    } message: { issue in
                        Text(issue.message)
                    }
            )
            .task(id: key) {
                guard Gate.issueToShow(for: key, current: mapVM.mapSelectionPersistenceIssue) != nil else {
                    // not ours to show right now, or gone. the issue itself stays put
                    shown = nil
                    return
                }
                if shown?.id == key.issueID { return }
                // an older one still up comes down first, the new one follows the settle
                shown = nil
                try? await Task.sleep(nanoseconds: Gate.settleNanoseconds)
                guard !Task.isCancelled,
                      let issue = Gate.issueToShow(for: key, current: mapVM.mapSelectionPersistenceIssue) else { return }
                shown = issue
            }
    }

    private func end(_ how: Gate.AlertEnd) {
        shown = nil
        if Gate.clearsIssue(how) { mapVM.dismissMapSelectionPersistenceIssue() }
    }
}

extension View {
    func mapSelectionIssueAlert(mapVM: MapViewModel, isActive: Bool,
                                onRetry: @escaping (Bool) -> Void = { _ in }) -> some View {
        modifier(MapSelectionIssueAlert(mapVM: mapVM, isActive: isActive, onRetry: onRetry))
    }
}
