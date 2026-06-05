import SwiftUI
import MapKit
import UniformTypeIdentifiers

enum ImportedMapFileCopier {
    static func copyToDocuments(_ source: URL,
                                fileManager: FileManager = .default) throws -> URL {
        let docsDir = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first!
        return try copy(source, into: docsDir, fileManager: fileManager)
    }

    static func copy(_ source: URL,
                     into directory: URL,
                     fileManager: FileManager = .default) throws -> URL {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = uniqueDestination(for: source, in: directory, fileManager: fileManager)
        try fileManager.copyItem(at: source, to: destination)
        return destination
    }

    private static func uniqueDestination(for source: URL,
                                          in directory: URL,
                                          fileManager: FileManager) -> URL {
        let ext = source.pathExtension
        let rawStem = source.deletingPathExtension().lastPathComponent
        let stem = rawStem.isEmpty ? "Imported Map" : rawStem

        func candidate(_ suffix: Int?) -> URL {
            let name = suffix.map { "\(stem)-\($0)" } ?? stem
            let base = directory.appendingPathComponent(name, isDirectory: false)
            return ext.isEmpty ? base : base.appendingPathExtension(ext)
        }

        var next = candidate(nil)
        var suffix = 1
        while fileManager.fileExists(atPath: next.path) {
            next = candidate(suffix)
            suffix += 1
        }
        return next
    }
}

struct ContentView: View {
    @StateObject private var locationService = LocationService()
    @StateObject private var waypointStore   = WaypointStore()
    @StateObject private var drawingStore    = DrawingStore()
    @StateObject private var drawingSession  = DrawingSessionViewModel()
    @StateObject private var measureSession  = MeasureSession()
    @StateObject private var visibility      = LayerVisibility()
    @StateObject private var mapVM           = MapViewModel()
    @StateObject private var calibration     = CalibrationSession()

    /// Injected from the app gate so the menu can show trial status + offer
    /// the unlock on demand (the paywall otherwise only appears once the
    /// trial has expired).
    @ObservedObject var store: StoreManager
    private let trial = TrialManager()
    @State private var showPaywallSheet    = false

    @Environment(\.undoManager) private var undoManager
    @State private var canUndo = false
    @State private var canRedo = false

    @State private var showImporter        = false
    @State private var showMBTilesImporter = false
    @State private var showGeoJSONImporter = false
    @State private var importMessage: String? = nil
    @State private var showWaypointSheet   = false
    @State private var showDrawingsSheet   = false   // "All Drawings" list
    @State private var showLayersSheet     = false
    @State private var showExportSheet     = false
    @State private var showSearchSheet     = false
    @State private var showAboutSheet      = false
    @State private var drawingsPanelOpen   = false   // inline panel below hamburger

    var body: some View {
        GeometryReader { geo in
            ZStack {
                MapContainerView(
                    mapVM: mapVM,
                    locationService: locationService,
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    drawingSession: drawingSession,
                    measureSession: measureSession,
                    visibility: visibility,
                    calibration: calibration
                )
                .ignoresSafeArea()

                // SwiftUI overlay for tactical control measures. Sits
                // directly above the map so its symbols render WITHOUT
                // going through MKMapView's annotation pipeline —
                // gives us a real .shadow() halo, vector-crisp lines
                // at any zoom, and hit-testing that matches the
                // visible symbol pixels exactly.
                TacticalSymbolOverlay(
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    mapVM: mapVM,
                    visibility: visibility
                )
                .ignoresSafeArea()
                .allowsHitTesting(!drawingSession.isDrawing
                                  && !calibration.isCalibrating)

                // Tap-anywhere-else dismisses the drawings panel. Layered between
                // the map and the HUD so taps on HUD controls still work.
                if drawingsPanelOpen {
                    Color.black.opacity(0.001)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture { drawingsPanelOpen = false }
                }

                // (We don't put a tap-outside-dismiss overlay above the
                // map here because it would also absorb pan / pinch
                // gestures, preventing the user from panning the map
                // while the controls card is open. Dismissal on tap
                // is handled by the map's own tap recognizer — see
                // MapContainerView.Coordinator.handleTap.)

                // Crosshair: always visible (except while drawing — taps go
                // to vertex placement and the crosshair would compete with
                // the tap-target markers).
                if !drawingSession.isDrawing {
                    CrosshairOverlay().allowsHitTesting(false)
                }

                // Freehand capture overlay. Sits above the map but below the
                // HUD VStack so toolbar buttons remain interactive. Converts
                // every drag point to a map coordinate and streams it into the
                // session; auto-commits when the finger lifts.
                if drawingSession.activeKind == .freedraw {
                    Color.clear
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .gesture(
                            DragGesture(minimumDistance: 0, coordinateSpace: .local)
                                .onChanged { value in
                                    guard let convert = mapVM.screenToCoordinate else { return }
                                    drawingSession.addFreeDrawPoint(convert(value.location))
                                }
                                .onEnded { _ in
                                    if let shape = drawingSession.finish() {
                                        drawingStore.add(shape)
                                    }
                                }
                        )
                }

                VStack(spacing: 0) {
                    MGRSHeaderView(
                        mgrs: mapVM.headerMGRS,
                        wgs84: mapVM.headerWGS84,
                        isBrowsing: mapVM.isBrowsing,
                        accuracy: locationService.lastAccuracy,
                        // Crosshair elevation: prefer the DEM lookup so panning
                        // around shows real terrain heights; fall back to the
                        // GPS-reported altitude only if the DEM hasn't replied yet.
                        elevation: mapVM.centreElevation ?? locationService.lastAltitude,
                        // "~" marks an approximate height served from the offline
                        // cache when the DEM network call can't reach Open-Meteo.
                        elevationIsApproximate: mapVM.centreElevationIsApproximate,
                        coordinate: mapVM.isBrowsing
                            ? mapVM.cameraCentre
                            : locationService.lastLocation?.coordinate,
                        onDropPin: { coord, mgrs in
                            // Drop a generic waypoint at this MGRS — auto-named
                            // with the MGRS string so the user can see where the
                            // pin came from. The active layer takes ownership.
                            let layerID = drawingStore.activeLayerID
                                ?? drawingStore.layers.first?.id
                                ?? DrawingLayer.legacyFallbackID
                            let wp = Waypoint(
                                name: mgrs,
                                coordinate: coord,
                                kind: .generic,
                                layerID: layerID
                            )
                            waypointStore.add(wp)
                        }
                    )
                    .padding(.horizontal, 12)

                    // Hamburger (left) + compass (right).
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 8) {
                            HamburgerMenu(
                                isPurchased: store.isPurchased,
                                trialDaysRemaining: trial.daysRemaining(),
                                onUnlock: { showPaywallSheet = true },
                                onSearch:    {
                                    drawingsPanelOpen = false
                                    showSearchSheet = true
                                },
                                onWaypoints: {
                                    drawingsPanelOpen = false
                                    showWaypointSheet = true
                                },
                                onDrawings:  {
                                    showDrawingsSheet = false
                                    drawingsPanelOpen.toggle()
                                },
                                onLayers:    {
                                    drawingsPanelOpen = false
                                    showLayersSheet = true
                                },
                                onMeasure:   {
                                    drawingsPanelOpen = false
                                    drawingSession.cancel()
                                    measureSession.start()
                                },
                                onImport:    {
                                    drawingsPanelOpen = false
                                    showImporter = true
                                },
                                onImportTiles: {
                                    drawingsPanelOpen = false
                                    showMBTilesImporter = true
                                },
                                onImportGeoJSON: {
                                    drawingsPanelOpen = false
                                    showGeoJSONImporter = true
                                },
                                onExport:    {
                                    drawingsPanelOpen = false
                                    showExportSheet = true
                                },
                                onAbout:     {
                                    drawingsPanelOpen = false
                                    showAboutSheet = true
                                }
                            )

                            if drawingsPanelOpen {
                                DrawingsPanel(
                                    drawingStore: drawingStore,
                                    session: drawingSession,
                                    onShowAll: {
                                        drawingsPanelOpen = false
                                        showDrawingsSheet = true
                                    },
                                    onDismiss: { drawingsPanelOpen = false }
                                )
                                .transition(.move(edge: .top).combined(with: .opacity))
                            }
                        }
                        Spacer()
                        VStack(spacing: 6) {
                            CompassChip(heading: mapVM.heading) { mapVM.resetNorth() }
                            if canUndo || canRedo {
                                UndoRedoButtons(
                                    canUndo: canUndo,
                                    canRedo: canRedo,
                                    onUndo: { undoManager?.undo() },
                                    onRedo: { undoManager?.redo() }
                                )
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.top, 10)
                    .animation(.easeInOut(duration: 0.18), value: drawingsPanelOpen)
                    .animation(.easeInOut(duration: 0.18), value: canUndo)
                    .animation(.easeInOut(duration: 0.18), value: canRedo)

                    Spacer(minLength: 0)

                    if calibration.isCalibrating {
                        CalibrationOverlay(
                            session: calibration,
                            onFinish: finishCalibration,
                            onCancel: { calibration.cancel() }
                        )
                        .padding(.horizontal, 12)
                        .padding(.bottom, max(geo.safeAreaInsets.bottom, 8) + 6)
                    } else if drawingSession.isDrawing {
                        DrawToolbar(session: drawingSession) {
                            if let shape = drawingSession.finish() {
                                drawingStore.add(shape)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.bottom, max(geo.safeAreaInsets.bottom, 8) + 6)
                    } else if measureSession.isActive {
                        MeasureToolbar(session: measureSession)
                            .padding(.horizontal, 12)
                            .padding(.bottom, max(geo.safeAreaInsets.bottom, 8) + 6)
                    } else {
                        if let id = mapVM.selectedWaypointID {
                            // Floating controls card for the currently-
                            // tapped waypoint. Slides up from the bottom
                            // edge and covers the centre-on-location pill
                            // — we reclaim that ~40pt strip for the W/H
                            // sliders. The user can dismiss the card
                            // (X in the corner, or tap the map) to get
                            // the pill back.
                            SymbolControlsCard(
                                waypointStore: waypointStore,
                                drawingStore: drawingStore,
                                mapVM: mapVM,
                                waypointID: id,
                                onDismiss: { mapVM.selectedWaypointID = nil }
                            )
                            .padding(.horizontal, 12)
                            .padding(.bottom, max(geo.safeAreaInsets.bottom - 32, 0))
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        } else if let id = mapVM.selectedDrawingID {
                            DrawingControlsCard(
                                drawingStore: drawingStore,
                                drawingID: id,
                                onDismiss: { mapVM.selectedDrawingID = nil }
                            )
                            .padding(.horizontal, 12)
                            .padding(.bottom, max(geo.safeAreaInsets.bottom - 32, 0))
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                        } else {
                            // Centre pill only when no card is open.
                            // Vertically centred on the MKMapView
                            // "Maps / Legal" attribution chip (pill bottom
                            // sits ~32pt above the screen bottom).
                            CentreButton {
                                mapVM.centreOnUser(locationService.lastLocation)
                            }
                            .offset(y: max(geo.safeAreaInsets.bottom - 32, 0))
                        }
                    }
                }
                .animation(.easeInOut(duration: 0.18),
                           value: mapVM.selectedWaypointID)
                .animation(.easeInOut(duration: 0.18),
                           value: mapVM.selectedDrawingID)
                // HUD sits flush below the dynamic island. We let it bleed slightly
                // into the safe-area region by using a small negative-ish padding,
                // ignoring the safe area entirely on the top edge — the box's
                // rounded corners tuck under the island shape naturally.
                // Bottom inset is applied per-control above so the centre-on-
                // location pill can sit closer to the screen edge than the
                // calibration / draw toolbars (which want a full safe-area gap).
                .padding(.top, 4)
            }
        }
        .task {
            drawingStore.undoManager = undoManager
            waypointStore.undoManager = undoManager
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSUndoManagerDidCloseUndoGroup)) { _ in
            refreshUndoState()
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSUndoManagerDidUndoChange)) { _ in
            refreshUndoState()
        }
        .onReceive(NotificationCenter.default.publisher(for: .NSUndoManagerDidRedoChange)) { _ in
            refreshUndoState()
        }
        .onAppear {
            locationService.requestAuthorisation()
            locationService.start()
            /// Rehydrate the last-imported PDF (if any) so the user
            /// doesn't have to re-import after closing the app. The
            /// PDF file lives in Documents and the calibration is
            /// stored in UserDefaults — see PDFSessionStore.
            if let restored = PDFSessionStore.load() {
                NSLog("[Import] restored persisted PDF: \(restored.displayName)")
                mapVM.mapSource = restored
                /// Frame the restored map the same way an import does, so a
                /// PDF whose coverage doesn't contain the user doesn't open
                /// off-screen. (No fix yet at launch → frames the whole page;
                /// the first fix won't yank away if the user is off-map.)
                mapVM.frameCamera(
                    for: restored,
                    userLocation: locationService.lastLocation?.coordinate
                )
            }
        }
        .onReceive(locationService.$lastLocation.compactMap { $0 }) { loc in
            mapVM.userLocationDidUpdate(loc)
        }
        .sheet(isPresented: $showWaypointSheet) {
            WaypointListSheet(waypointStore: waypointStore, mapVM: mapVM)
                .padSheetSizing()
        }
        .sheet(isPresented: $showDrawingsSheet) {
            DrawingsSheet(drawingStore: drawingStore, session: drawingSession)
                .padSheetSizing()
        }
        .sheet(isPresented: $showLayersSheet) {
            LayersSheet(visibility: visibility,
                        mapVM: mapVM,
                        drawingStore: drawingStore,
                        onCalibrate: startCalibration)
                .padSheetSizing()
        }
        .sheet(isPresented: Binding(
            get: { calibration.pendingTap != nil },
            set: { if !$0 { calibration.clearPendingTap() } }
        )) {
            CalibrationInputSheet(
                session: calibration,
                onCancel: { calibration.clearPendingTap() },
                currentLocation: locationService.lastLocation?.coordinate
            )
            .padSheetSizing()
        }
        .sheet(isPresented: $showExportSheet) {
            ExportSheet(waypointStore: waypointStore, drawingStore: drawingStore)
                .padSheetSizing()
        }
        .sheet(isPresented: $showSearchSheet) {
            SearchSheet(mapVM: mapVM)
                .padSheetSizing()
        }
        .sheet(isPresented: $showAboutSheet) {
            AcknowledgementsView()
                .padSheetSizing()
        }
        .sheet(isPresented: $showPaywallSheet) {
            PaywallView(
                store: store,
                trialDaysRemaining: trial.daysRemaining(),
                onRestore: { Task { await store.restore() } },
                onClose: { showPaywallSheet = false }
            )
        }
        /// SwiftUI has a long-standing bug where two `.fileImporter`
        /// modifiers attached back-to-back on the same view silently
        /// shadow each other — only the last one ever presents,
        /// which is why "Import PDF Map" did nothing while
        /// "Import GeoJSON…" worked. Attaching each via an empty
        /// background view puts them on separate view nodes and
        /// they both fire independently.
        .background(
            EmptyView()
                .fileImporter(
                    isPresented: $showImporter,
                    allowedContentTypes: [.pdf],
                    allowsMultipleSelection: false
                ) { result in
                    handleImport(result)
                }
        )
        .background(
            EmptyView()
                .fileImporter(
                    isPresented: $showGeoJSONImporter,
                    allowedContentTypes: [
                        .json,
                        UTType(filenameExtension: "geojson") ?? .json
                    ],
                    allowsMultipleSelection: false
                ) { result in
                    handleGeoJSONImport(result)
                }
        )
        .background(
            EmptyView()
                .fileImporter(
                    isPresented: $showMBTilesImporter,
                    allowedContentTypes: [
                        UTType(filenameExtension: "mbtiles") ?? .database,
                        .database
                    ],
                    allowsMultipleSelection: false
                ) { result in
                    handleMBTilesImport(result)
                }
        )
        .alert("Import",
               isPresented: Binding(get: { importMessage != nil },
                                    set: { if !$0 { importMessage = nil } }),
               presenting: importMessage) { _ in
            Button("OK", role: .cancel) { importMessage = nil }
        } message: { msg in
            Text(msg)
        }
    }

    private func refreshUndoState() {
        canUndo = undoManager?.canUndo ?? false
        canRedo = undoManager?.canRedo ?? false
    }

    private func handleGeoJSONImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = "Import failed: \(err.localizedDescription)"
        case .success(let urls):
            guard let url = urls.first else { return }
            // Documents picker hands us a security-scoped URL.
            let didStart = url.startAccessingSecurityScopedResource()
            defer { if didStart { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let fallback = drawingStore.activeLayerID
                    ?? drawingStore.layers.first?.id
                    ?? DrawingLayer.legacyFallbackID
                let parsed = try GeoJSONImporter.parse(
                    data,
                    existingLayers: drawingStore.layers,
                    fallbackLayerID: fallback
                )
                for layer in parsed.newLayers {
                    drawingStore.addLayerVerbatim(layer)
                }
                for shape in parsed.drawings { drawingStore.add(shape) }
                for wp in parsed.waypoints { waypointStore.add(wp) }
                importMessage = "Imported \(parsed.waypoints.count) waypoint" +
                    "\(parsed.waypoints.count == 1 ? "" : "s") and " +
                    "\(parsed.drawings.count) drawing" +
                    "\(parsed.drawings.count == 1 ? "" : "s")."
            } catch {
                importMessage = "Couldn't parse this file as GeoJSON: \(error.localizedDescription)"
            }
        }
    }

    private func startCalibration() {
        guard let pdfSource = mapVM.mapSource as? PDFMapSource else { return }
        calibration.start(for: pdfSource)
    }

    private func finishCalibration() {
        guard let result = calibration.finish(),
              let source = calibration.source else { return }
        // Build a fresh source so MapContainerView rebuilds the overlay
        // (its sync logic keys on source.id).
        let newSource = PDFMapSource(url: source.url, bounds: nil, fromGeoPDF: false)
        newSource.applyCalibration(
            transform: result.transform,
            fiduciaries: calibration.fiduciaries
        )
        let bounds = newSource.bounds
        calibration.cancel()
        mapVM.mapSource = newSource
        /// Persist the freshly-calibrated source so the fiduciary fit
        /// survives an app restart. Without this the next launch would
        /// restore the pre-calibration import and silently drop the
        /// user's calibration work.
        PDFSessionStore.save(newSource)
        if let b = bounds {
            let span = MKCoordinateSpan(
                latitudeDelta:  abs(b.northEast.latitude  - b.southWest.latitude)  * 1.2,
                longitudeDelta: abs(b.northEast.longitude - b.southWest.longitude) * 1.2
            )
            mapVM.cameraRequests.send(MKCoordinateRegion(center: b.centre, span: span))
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure(let error) = result {
                importMessage = "Import failed: \(error.localizedDescription)"
            }
            return
        }

        // The file picker may hand us a security-scoped URL (file came from
        // outside the sandbox). Copy into Documents so we have stable access.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let dest: URL
        do {
            dest = try ImportedMapFileCopier.copyToDocuments(url)
        } catch {
            importMessage = "Couldn't import this PDF map: \(error.localizedDescription)"
            return
        }

        NSLog("[Import] picked \(url.lastPathComponent) -> dest=\(dest.path)")
        let cameraAtImport = mapVM.cameraCentre
        Task.detached(priority: .userInitiated) { [mapVM] in
            let parsed = GeoPDFReader.bounds(from: dest)
            NSLog("[Import] LGIDict/known-sheet parse: \(String(describing: parsed))")
            let resolvedBounds = parsed ?? GeoPDFReader.fallbackBounds(centeredOn: cameraAtImport)
            NSLog("[Import] resolved bounds SW=\(resolvedBounds.southWest.latitude),\(resolvedBounds.southWest.longitude) NE=\(resolvedBounds.northEast.latitude),\(resolvedBounds.northEast.longitude)")
            let fromGeoPDF = (parsed != nil)

            await MainActor.run {
                NSLog("[Import] installing PDFMapSource on MainActor")
                let source = PDFMapSource(
                    url: dest,
                    bounds: resolvedBounds,
                    fromGeoPDF: fromGeoPDF
                )
                // If this PDF was calibrated in a previous session, restore its
                // fiduciaries + affine so it re-imports already aligned.
                PDFSessionStore.applyCalibrationIfKnown(to: source)
                mapVM.mapSource = source
                PDFSessionStore.save(source)

                /// Frame the camera: snap to the user if they're inside the
                /// PDF's coverage, otherwise frame the whole page. Shared
                /// with the restore path via MapViewModel.frameCamera.
                mapVM.frameCamera(
                    for: source,
                    userLocation: locationService.lastLocation?.coordinate
                )
            }
        }
    }

    /// Import a local MBTiles raster pyramid as an offline basemap. Copies the
    /// picked file into Documents (stable sandbox access) and installs an
    /// `OfflineTileMapSource` — served with no network via an MKTileOverlay.
    private func handleMBTilesImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure(let error) = result {
                importMessage = "Import failed: \(error.localizedDescription)"
            }
            return
        }

        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let dest: URL
        do {
            dest = try ImportedMapFileCopier.copyToDocuments(url)
        } catch {
            importMessage = "Couldn't import this MBTiles map: \(error.localizedDescription)"
            return
        }

        guard let source = OfflineTileMapSource(url: dest) else {
            importMessage = "Couldn't open this file as an MBTiles map."
            return
        }
        mapVM.mapSource = source
        mapVM.frameCamera(
            for: source,
            userLocation: locationService.lastLocation?.coordinate
        )
        importMessage = "Loaded offline tiles: \(source.displayName)."
    }
}

#Preview {
    ContentView(store: StoreManager())
        .preferredColorScheme(.dark)
}
