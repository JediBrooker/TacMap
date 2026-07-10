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

    /// App Support dir for imported maps. Stored here (not Documents/) so
    /// they stay hidden from Files.app and get stronger file protection.
    static func importedMapsDirectory(fileManager: FileManager = .default) throws -> URL {
        let appSupport = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = appSupport.appendingPathComponent("ImportedMaps", isDirectory: true)
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true,
                                        attributes: [.protectionKey: FileProtectionType.complete])
        return dir
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
    @StateObject private var trackRecorder   = TrackRecorder()

    /// Injected from app gate so menu can show trial status + offer the
    /// unlock on demand (paywall otherwise only shows once trial expires).
    @ObservedObject var store: StoreManager
    private let trial = TrialManager()
    @State private var showPaywallSheet    = false

    @Environment(\.undoManager) private var undoManager
    @State private var canUndo = false
    @State private var canRedo = false
    /// Freeze all graphic interaction (select/drag/vertex-edit/settings).
    @State private var graphicsLocked = false

    @State private var showImporter        = false
    @State private var showMBTilesImporter = false
    @State private var showGeoJSONImporter = false
    @State private var showKMLImporter     = false
    @State private var showGPXExporter     = false
    @State private var showWeatherSheet    = false
    @State private var showAppLockSheet    = false
    @State private var showSyncSheet       = false
    @StateObject private var syncManager   = SyncManager()
    @State private var importMessage: String? = nil
    /// Brief toast for remote sync updates (conflict notification).
    @State private var syncToast: String? = nil
    /// Share sheet URL for "Export All Data"
    @State private var exportAllURL: URL? = nil
    @State private var showWaypointSheet   = false
    @State private var showDrawingsSheet   = false   // "All Drawings" list
    @State private var showLayersSheet     = false
    @State private var showExportSheet     = false
    @State private var showSearchSheet     = false
    @State private var showAboutSheet      = false
    @State private var drawingsPanelOpen   = false   // inline panel below hamburger

    @ObservedObject private var opsec = OpsecSettings.shared

    /// Is anything on screen actually pulling tiles off the internet right now?
    /// Apple's basemap counts: an imported PDF draws in a subview above it, so
    /// "a PDF is loaded" does not mean "nothing is being fetched".
    private var onlineTilesActive: Bool {
        guard opsec.onlineBasemaps else { return false }
        return !(mapVM.mapSource is OfflineTileMapSource)
    }

    /// Nothing to draw at all: no imported map, and online tiles are gated off.
    private var basemapBlank: Bool {
        !opsec.onlineBasemaps
            && !(mapVM.mapSource is OfflineTileMapSource)
            && !(mapVM.mapSource is PDFMapSource)
    }

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
                    calibration: calibration,
                    graphicsLocked: graphicsLocked,
                    peers: syncManager.peers
                )
                .ignoresSafeArea()
                .overlay {
                    if basemapBlank { NoBasemapNotice() }
                }

                // SwiftUI overlay for tactical control measures. Sits
                // directly above the map so symbols render WITHOUT
                // going through MKMapView's annotation pipeline -
                // gives us real .shadow() halo, vector-crisp lines
                // at any zoom, and hit-testing that matches visible
                // symbol pixels exactly.
                TacticalSymbolOverlay(
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    mapVM: mapVM,
                    visibility: visibility
                )
                .ignoresSafeArea()
                .allowsHitTesting(!drawingSession.isDrawing
                                  && !calibration.isCalibrating)

                // Tap-anywhere-else dismisses drawings panel. Layered between
                // map and HUD so taps on HUD controls still work.
                if drawingsPanelOpen {
                    Color.black.opacity(0.001)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture { drawingsPanelOpen = false }
                }

                // Don't put a tap-outside-dismiss overlay above the map
                // b/c it would also absorb pan/pinch gestures and the
                // user couldn't pan the map while controls card is open.
                // Dismissal on tap is handled by map's own tap recognizer,
                // see MapContainerView.Coordinator.handleTap.

                // Crosshair: always visible except while drawing (taps go
                // to vertex placement, crosshair would compete with
                // tap-target markers).
                if !drawingSession.isDrawing {
                    CrosshairOverlay().allowsHitTesting(false)
                }

                freehandCaptureOverlay

                hudOverlay(bottomInset: geo.safeAreaInsets.bottom)

                syncToastOverlay
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
            /// Rehydrate last-imported PDF (if any) so user doesn't
            /// have to re-import after closing the app. PDF file
            /// lives in Documents, calibration is in UserDefaults -
            /// see PDFSessionStore.
            if let restored = PDFSessionStore.load() {
                NSLog("[Import] restored persisted PDF: \(restored.displayName)")
                mapVM.mapSource = restored
                /// Frame restored map same way an import does so a PDF
                /// whose coverage doesn't contain user doesn't open
                /// off-screen. No fix yet at launch so it frames the
                /// whole page; first fix won't yank away if user is off-map.
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
        .sheet(isPresented: $showGPXExporter) {
            GPXExportSheet(points: trackRecorder.points)
                .padSheetSizing()
        }
        .sheet(isPresented: $showWeatherSheet) {
            WeatherSheet(coordinate: mapVM.cameraCentre)
                .padSheetSizing()
        }
        .sheet(isPresented: $showAppLockSheet) {
            AppLockSetupView()
                .padSheetSizing()
        }
        .sheet(isPresented: $showSyncSheet) {
            SyncSheet(manager: syncManager)
                .padSheetSizing()
        }
        .task {
            syncManager.configure(waypointStore: waypointStore,
                                  drawingStore: drawingStore,
                                  locationService: locationService)
        }
        // Remote sync update toast (conflict notif).
        .onReceive(syncManager.remoteUpdateSubject) { message in
            syncToast = message
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                if syncToast == message { syncToast = nil }
            }
        }
        // Feed every fix into the track recorder. It just ignores them unless recording.
        .onReceive(locationService.$lastLocation.compactMap { $0 }) { loc in
            trackRecorder.ingest(loc)
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
        /// SwiftUI has a long-standing bug where two .fileImporter
        /// modifiers on the same view silently shadow each other -
        /// only the last one ever presents. Thats why "Import PDF
        /// Map" did nothing while "Import GeoJSON" worked. Attaching
        /// each via an empty background view puts them on seperate
        /// view nodes and they both fire independently.
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
        .background(
            EmptyView()
                .fileImporter(
                    isPresented: $showKMLImporter,
                    allowedContentTypes: [
                        UTType(filenameExtension: "kml") ?? .xml,
                        UTType(filenameExtension: "kmz") ?? .zip
                    ],
                    allowsMultipleSelection: false
                ) { result in
                    handleKMLImport(result)
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
        .sheet(isPresented: Binding(
            get: { exportAllURL != nil },
            set: { if !$0 { exportAllURL = nil } }
        )) {
            if let url = exportAllURL {
                ShareSheetView(activityItems: [url])
                    .padSheetSizing()
            }
        }
    }

    /// Freehand capture overlay. Above map but below HUD VStack
    /// so toolbar buttons stay interactive. Converts every drag
    /// point to a map coord and streams into the session,
    /// auto-commits when finger lifts.
    @ViewBuilder
    private var freehandCaptureOverlay: some View {
        if drawingSession.activeKind == .freedraw {
            Color.clear
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 0, coordinateSpace: .global)
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
    }

    /// Full HUD: header, hamburger menu, compass, bottom toolbar /
    /// selection cards, recording indicator. Extracted from body to
    /// keep ZStack under Swift's type-checker complexity budget.
    @ViewBuilder
    private func hudOverlay(bottomInset: CGFloat) -> some View {
        VStack(spacing: 0) {
            MGRSHeaderView(
                mgrs: mapVM.headerMGRS,
                wgs84: mapVM.headerWGS84,
                utm: mapVM.headerUTM,
                syncConnected: syncManager.status == .connected,
                isBrowsing: mapVM.isBrowsing,
                accuracy: locationService.lastAccuracy,
                elevation: mapVM.centreElevation ?? locationService.lastAltitude,
                elevationIsApproximate: mapVM.centreElevationIsApproximate,
                coordinate: mapVM.isBrowsing
                    ? mapVM.cameraCentre
                    : locationService.lastLocation?.coordinate,
                onDropPin: { coord, mgrs in
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

            // Compact online-tiles warning, tucked UNDER the header so it
            // doesn't shove it down. A small pill, not a full-width strip.
            if onlineTilesActive {
                OnlineTilesPill().padding(.top, 4)
            }

            if trackRecorder.isRecording {
                RecordingIndicator(
                    pointCount: trackRecorder.points.count,
                    onStop: {
                        trackRecorder.stop()
                        locationService.setBackgroundUpdates(false)
                    }
                )
                .padding(.top, 8)
            }

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
                        onWeather:   {
                            drawingsPanelOpen = false
                            showWeatherSheet = true
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
                        onImportKML: {
                            drawingsPanelOpen = false
                            showKMLImporter = true
                        },
                        onExport:    {
                            drawingsPanelOpen = false
                            showExportSheet = true
                        },
                        isRecordingTrack: trackRecorder.isRecording,
                        trackPointCount: trackRecorder.points.count,
                        onToggleTrackRecording: {
                            drawingsPanelOpen = false
                            if trackRecorder.isRecording {
                                trackRecorder.stop()
                                locationService.setBackgroundUpdates(false)
                            } else {
                                trackRecorder.start()
                                locationService.setBackgroundUpdates(true)
                            }
                        },
                        onExportGPX: {
                            drawingsPanelOpen = false
                            showGPXExporter = true
                        },
                        onExportAll: {
                            drawingsPanelOpen = false
                            exportAllData()
                        },
                        onSync:      {
                            drawingsPanelOpen = false
                            showSyncSheet = true
                        },
                        onAppLock:   {
                            drawingsPanelOpen = false
                            showAppLockSheet = true
                        },
                        onAbout:     {
                            drawingsPanelOpen = false
                            showAboutSheet = true
                        }
                    )

                    UnitLabelsToggle(active: visibility.unitLabelsVisible) {
                        visibility.unitLabelsVisible.toggle()
                    }

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
                    LockButton(locked: graphicsLocked) {
                        graphicsLocked.toggle()
                        if graphicsLocked {
                            mapVM.selectedWaypointID = nil
                            mapVM.selectedDrawingID = nil
                        }
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 10)
            .animation(.easeInOut(duration: 0.18), value: drawingsPanelOpen)
            .animation(.easeInOut(duration: 0.18), value: canUndo)
            .animation(.easeInOut(duration: 0.18), value: canRedo)

            Spacer(minLength: 0)

            hudBottomBar(bottomInset: bottomInset)
        }
        .animation(.easeInOut(duration: 0.18),
                   value: mapVM.selectedWaypointID)
        .animation(.easeInOut(duration: 0.18),
                   value: mapVM.selectedDrawingID)
        .padding(.top, 4)
    }

    /// Bottom toolbar: calibration, drawing, measure, selection cards,
    /// or centre-on-location pill. Extracted to keep hudOverlay under
    /// type-checker limit.
    @ViewBuilder
    private func hudBottomBar(bottomInset: CGFloat) -> some View {
        if calibration.isCalibrating {
            CalibrationOverlay(
                session: calibration,
                onFinish: finishCalibration,
                onCancel: { calibration.cancel() }
            )
            .padding(.horizontal, 12)
            .padding(.bottom, max(bottomInset, 8) + 6)
        } else if drawingSession.isDrawing {
            DrawToolbar(session: drawingSession) {
                if let shape = drawingSession.finish() {
                    drawingStore.add(shape)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, max(bottomInset, 8) + 6)
        } else if measureSession.isActive {
            MeasureToolbar(session: measureSession)
                .padding(.horizontal, 12)
                .padding(.bottom, max(bottomInset, 8) + 6)
        } else {
            if let id = mapVM.selectedWaypointID {
                SymbolControlsCard(
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    mapVM: mapVM,
                    waypointID: id,
                    onDismiss: { mapVM.selectedWaypointID = nil }
                )
                .padding(.horizontal, 12)
                .padding(.bottom, max(bottomInset - 32, 0))
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else if let id = mapVM.selectedDrawingID {
                DrawingControlsCard(
                    drawingStore: drawingStore,
                    drawingID: id,
                    onDismiss: { mapVM.selectedDrawingID = nil }
                )
                .padding(.horizontal, 12)
                .padding(.bottom, max(bottomInset - 32, 0))
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                CentreButton {
                    mapVM.centreOnUser(locationService.lastLocation)
                }
                .offset(y: max(bottomInset - 32, 0))
            }
        }
    }

    /// Brief overlay toast when a remote sync change arrives.
    @ViewBuilder
    private var syncToastOverlay: some View {
        if let toast = syncToast {
            VStack {
                Spacer()
                Text(toast)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(.black.opacity(0.78), in: Capsule())
                    .padding(.bottom, 80)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            .allowsHitTesting(false)
            .animation(.easeInOut(duration: 0.25), value: syncToast)
        }
    }

    private func refreshUndoState() {
        canUndo = undoManager?.canUndo ?? false
        canRedo = undoManager?.canRedo ?? false
    }

    /// Export all waypoints + drawings + layers to GeoJSON and show
    /// system share sheet.
    private func exportAllData() {
        do {
            let url = try GeoJSONExporter.exportToFile(
                waypoints: waypointStore.waypoints,
                drawings: drawingStore.shapes,
                layers: drawingStore.layers
            )
            exportAllURL = url
        } catch {
            importMessage = "Export failed: \(error.localizedDescription)"
        }
    }

    private func handleGeoJSONImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = "Import failed: \(err.localizedDescription)"
        case .success(let urls):
            guard let url = urls.first else { return }
            // Docs picker gives us a security-scoped URL.
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

    private func handleKMLImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = "Import failed: \(err.localizedDescription)"
        case .success(let urls):
            guard let url = urls.first else { return }
            let didStart = url.startAccessingSecurityScopedResource()
            defer { if didStart { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                let fallback = drawingStore.activeLayerID
                    ?? drawingStore.layers.first?.id
                    ?? DrawingLayer.legacyFallbackID
                let parsed = try KMLImporter.parse(
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
                importMessage = "Couldn't parse this file as KML: \(error.localizedDescription)"
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
        // Build fresh source so MapContainerView rebuilds overlay
        // (sync logic keys on source.id).
        let newSource = PDFMapSource(url: source.url, bounds: nil, fromGeoPDF: false)
        newSource.applyCalibration(
            transform: result.transform,
            fiduciaries: calibration.fiduciaries
        )
        let bounds = newSource.bounds
        calibration.cancel()
        mapVM.mapSource = newSource
        /// Persist the freshly-calibrated source so fiduciary fit
        /// survives app restart. Without this next launch would
        /// restore pre-calibration import and silently clobber the
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

        // File picker may hand us a security-scoped URL (came from outside
        // sandbox). Copy into Documents for stable access.
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
                // If PDF was calibrated in a previous session, restore
                // fiduciaries + affine so it re-imports already aligned.
                PDFSessionStore.applyCalibrationIfKnown(to: source)
                mapVM.mapSource = source
                PDFSessionStore.save(source)

                /// Frame camera - snap to user if they're inside PDF
                /// coverage, otherwise frame the whole page. Shared
                /// with restore path via MapViewModel.frameCamera.
                mapVM.frameCamera(
                    for: source,
                    userLocation: locationService.lastLocation?.coordinate
                )
            }
        }
    }

    /// Import local MBTiles raster pyramid as offline basemap. Copies
    /// picked file into Documents (stable sandbox access), installs an
    /// OfflineTileMapSource served with no network via MKTileOverlay.
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

/// Compact warning while the map is pulling tiles from the internet - the
/// provider learns your area of interest from the tiles you request, so it
/// shouldn't be something you find out by accident. Used to be a full-width
/// strip that shoved the MGRS card down; now a small pill tucked under it.
/// The full sentence lives in the accessibility label + THREAT_MODEL.
private struct OnlineTilesPill: View {
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 9, weight: .bold))
            Text("Online basemap")
                .font(.system(size: 10, weight: .bold))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 9)
        .padding(.vertical, 3)
        .background(Color(red: 0.69, green: 0, blue: 0.13).opacity(0.92), in: Capsule())
        .accessibilityLabel("Online basemap active. Tile requests reveal your area of interest.")
    }
}

/// Fresh install with no offline pack and online basemaps gated off draws
/// nothing at all. Explain that, b/c a blank map with no message reads as a
/// broken app rather than a deliberate OPSEC posture.
private struct NoBasemapNotice: View {
    var body: some View {
        VStack(spacing: 6) {
            Text("No basemap").font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
            Text("Online basemaps are off. Import an offline map pack, or enable "
                 + "online basemap tiles in Privacy & OPSEC.")
                .font(.system(size: 11))
                .foregroundStyle(Color(white: 0.73))
                .multilineTextAlignment(.center)
        }
        .padding(16)
        .background(Color.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 8))
        .padding(24)
        .allowsHitTesting(false)
    }
}

#Preview {
    ContentView(store: StoreManager())
        .preferredColorScheme(.dark)
}
