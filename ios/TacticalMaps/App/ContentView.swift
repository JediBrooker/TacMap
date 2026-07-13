import SwiftUI
import MapKit
import PDFKit
import UniformTypeIdentifiers

enum ImportedMapFileCopier {
    static let maxPDFBytes = 512 * 1024 * 1024
    static let maxMBTilesBytes = 2 * 1024 * 1024 * 1024
    /// Copy an imported map (PDF/GeoPDF/MBTiles) into the app-private,
    /// file-protected ImportedMaps dir - NOT Documents. Keeps the picture of
    /// your AO off the Files.app / Finder file-sharing surface, where it used
    /// to sit readable to anyone with the unlocked device or a paired host.
    static func copyToImportedMaps(_ source: URL,
                                   maximumBytes: Int = maxPDFBytes,
                                   fileManager: FileManager = .default) throws -> URL {
        let dir = try importedMapsDirectory(fileManager: fileManager)
        let dest = try copy(source, into: dir, maximumBytes: maximumBytes, fileManager: fileManager)
        // After-first-unlock so a backgrounded map / recording read still
        // works; matches the migration path in PDFSessionStore.
        do {
            try fileManager.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: dest.path)
        } catch {
            try? fileManager.removeItem(at: dest)
            throw error
        }
        return dest
    }

    static func copy(_ source: URL,
                     into directory: URL,
                     maximumBytes: Int = maxPDFBytes,
                     fileManager: FileManager = .default) throws -> URL {
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]
        let before = try source.resourceValues(forKeys: keys)
        guard before.isRegularFile == true, before.isSymbolicLink != true,
              let size = before.fileSize, size >= 0, size <= maximumBytes else {
            throw CocoaError(.fileReadTooLarge)
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = uniqueDestination(for: source, in: directory, fileManager: fileManager)
        do {
            try fileManager.copyItem(at: source, to: destination)
            let copied = try destination.resourceValues(forKeys: keys)
            guard copied.isRegularFile == true, copied.isSymbolicLink != true,
                  let copiedSize = copied.fileSize, copiedSize == size, copiedSize <= maximumBytes else {
                throw CocoaError(.fileReadCorruptFile)
            }
        } catch {
            try? fileManager.removeItem(at: destination)
            throw error
        }
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

private enum BoundedImportReader {
    static func read(_ url: URL, maximumBytes: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
        guard values.isRegularFile == true else { throw CocoaError(.fileReadUnsupportedScheme) }
        guard let size = values.fileSize, size >= 0, size <= maximumBytes else {
            throw GeoJSONImporter.ImportError.limitExceeded("file is over \(maximumBytes / 1_048_576) MB")
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe, .uncached])
        guard data.count <= maximumBytes else {
            throw GeoJSONImporter.ImportError.limitExceeded("file changed while it was being read")
        }
        return data
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
    @State private var showOpsecSheet      = false
    @State private var showSyncSheet       = false
    @StateObject private var syncManager   = SyncManager()
    @State private var importMessage: String? = nil
    @State private var missionUnlockError: String? = nil
    @State private var dataKeyEpoch = 0
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
    @ObservedObject private var onlineTileHealth = OnlineTileHealth.shared

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

    /// An imported, location-bound basemap (offline pack or PDF/GeoPDF). These
    /// have coverage, so they get the "Centre on Map" button + green banner tag.
    private var importedMapLoaded: Bool {
        mapVM.mapSource is OfflineTileMapSource || mapVM.mapSource is PDFMapSource
    }

    /// Basemap status shown in the MGRS banner (replaces Live Location/Map Centre).
    private var basemapLabel: String? {
        if importedMapLoaded { return "Offline basemap" }
        if onlineTilesActive { return "Online basemap" }
        return nil
    }
    private var basemapColor: Color {
        importedMapLoaded
            ? Color(red: 0.45, green: 0.89, blue: 0.54)   // offline: green
            : Color(red: 1.0, green: 0.35, blue: 0.35)    // online: red
    }

    /// The coordinate the banner is reading out: the crosshair when browsing,
    /// else the live position. Drives the MGRS readout, drop-pin, and G-M angle.
    private var headerCoordinate: CLLocationCoordinate2D? {
        mapVM.isBrowsing ? mapVM.cameraCentre : locationService.lastLocation?.coordinate
    }

    private var missionDataLocked: Bool {
        _ = dataKeyEpoch
        return (DataKey.isAuthBound && !DataKey.isUnlocked)
            || waypointStore.locked || drawingStore.locked || trackRecorder.requiresUnlock
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                TileMapContainer(
                    mapVM: mapVM,
                    waypointStore: waypointStore,
                    drawingStore: drawingStore,
                    drawingSession: drawingSession,
                    measureSession: measureSession,
                    visibility: visibility,
                    locationService: locationService,
                    calibration: calibration,
                    graphicsLocked: graphicsLocked,
                    peers: syncManager.peers
                )
                .ignoresSafeArea()
                .overlay {
                    if basemapBlank { NoBasemapNotice() }
                }

                if onlineTilesActive && onlineTileHealth.temporarilyUnavailable {
                    VStack {
                        Text("Online basemap temporarily unavailable")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .background(.black.opacity(0.82), in: Capsule())
                            .padding(.top, 118)
                        Spacer()
                    }
                    .allowsHitTesting(false)
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
                // tap-target markers). Must ignore the safe area like the map
                // does - otherwise it centres on the safe-area rect (~14pt low
                // since the top inset > the bottom) and drifts below the user
                // dot, which sits at the map's true geometric centre.
                if !drawingSession.isDrawing {
                    CrosshairOverlay()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                }

                freehandCaptureOverlay

                hudOverlay(bottomInset: geo.safeAreaInsets.bottom)

                syncToastOverlay
            }
        }
        .overlay {
            if missionDataLocked {
                MissionDataUnlockView(
                    detail: missionUnlockError
                        ?? waypointStore.loadError
                        ?? drawingStore.loadError
                        ?? trackRecorder.persistError,
                    unlock: unlockMissionData
                )
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: DataKey.lockChanged)) { _ in
            dataKeyEpoch &+= 1
        }
        .alert("Track Recording", isPresented: Binding(
            get: { trackRecorder.persistError != nil && !trackRecorder.requiresUnlock },
            set: { if !$0 { trackRecorder.persistError = nil } }
        )) {
            if !trackRecorder.points.isEmpty || trackRecorder.recovered {
                Button("Discard Saved Track", role: .destructive) { trackRecorder.discard() }
            }
            Button("OK", role: .cancel) { trackRecorder.persistError = nil }
        } message: {
            Text(trackRecorder.persistError ?? "Track recording failed.")
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
                NSLog("[Import] restored persisted PDF")
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
        .sheet(isPresented: $showOpsecSheet) {
            OpsecSettingsView()
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
        .onChange(of: trackRecorder.isRecording) { active in
            // Background location follows the recorder's *durable* state. A
            // failed start or append immediately tears the capability down.
            locationService.setBackgroundUpdates(active)
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
                basemapLabel: basemapLabel,
                basemapColor: basemapColor,
                gridMagneticDegrees: GridMagnetic.angle(
                    latitude: headerCoordinate?.latitude,
                    longitude: headerCoordinate?.longitude),
                elevation: mapVM.centreElevation ?? locationService.lastAltitude,
                elevationIsApproximate: mapVM.centreElevationIsApproximate,
                coordinate: headerCoordinate,
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

            // The online-tiles warning used to sit here under the header, but
            // that's exactly where the live-tracking record badge goes, so they
            // collided. It's paired with the Centre button at the bottom now.

            if trackRecorder.isRecording {
                RecordingIndicator(
                    pointCount: trackRecorder.points.count,
                    onStop: {
                        trackRecorder.stop()
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
                            } else {
                                _ = trackRecorder.start()
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
                        onOpsec:     {
                            drawingsPanelOpen = false
                            showOpsecSheet = true
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
                // Basemap status now lives in the MGRS banner. When an imported
                // (offline/PDF) map is loaded, add a "Map" button beside the
                // recentre pill so centring on a distant live location doesn't
                // strand the user away from their map. Side by side to save height;
                // the location label shrinks when both show.
                HStack(spacing: 8) {
                    CentreButton(
                        title: importedMapLoaded ? "My Location" : "Centre on My Location"
                    ) {
                        mapVM.centreOnUser(locationService.lastLocation)
                    }
                    if importedMapLoaded {
                        CentreButton(title: "Map", systemImage: "map") {
                            mapVM.centreOnMap()
                        }
                    }
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

    private func unlockMissionData() {
        missionUnlockError = nil
        do {
            _ = try DataKey.key()
            dataKeyEpoch &+= 1
            waypointStore.reloadAfterUnlock()
            drawingStore.reloadAfterUnlock()
            trackRecorder.retryRecoveryAfterUnlock()
            guard !missionDataLocked else { throw DataKey.LockedError() }
            if !(mapVM.mapSource is PDFMapSource), let restored = PDFSessionStore.load() {
                mapVM.mapSource = restored
                mapVM.frameCamera(for: restored, userLocation: locationService.lastLocation?.coordinate)
            }
        } catch {
            missionUnlockError = error.localizedDescription
        }
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
                let data = try BoundedImportReader.read(url, maximumBytes: GeoJSONImporter.maxInputBytes)
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
                let data = try BoundedImportReader.read(url, maximumBytes: KMLImporter.maxInputBytes)
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
        // sandbox). Copy into app-private, file-protected storage for stable
        // access - never Documents, which is exposed via file sharing.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let dest: URL
        do {
            dest = try ImportedMapFileCopier.copyToImportedMaps(
                url, maximumBytes: ImportedMapFileCopier.maxPDFBytes)
        } catch {
            importMessage = "Couldn't import this PDF map: \(error.localizedDescription)"
            return
        }
        guard PDFDocument(url: dest) != nil else {
            try? FileManager.default.removeItem(at: dest)
            importMessage = "Couldn't import this file as a valid PDF map."
            return
        }

        NSLog("[Import] copied PDF into protected app storage")
        let cameraAtImport = mapVM.cameraCentre
        Task.detached(priority: .userInitiated) { [mapVM] in
            let parsed = GeoPDFReader.bounds(from: dest)
            NSLog("[Import] geospatial metadata parse complete")
            let resolvedBounds = parsed ?? GeoPDFReader.fallbackBounds(centeredOn: cameraAtImport)
            NSLog("[Import] map bounds resolved")
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

    /// Import local MBTiles raster pyramid as offline basemap. Copies the
    /// picked file into app-private, file-protected storage (not Documents),
    /// installs an OfflineTileMapSource served with no network.
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
            dest = try ImportedMapFileCopier.copyToImportedMaps(
                url, maximumBytes: ImportedMapFileCopier.maxMBTilesBytes)
        } catch {
            importMessage = "Couldn't import this MBTiles map: \(error.localizedDescription)"
            return
        }

        guard let source = OfflineTileMapSource(url: dest) else {
            try? FileManager.default.removeItem(at: dest)
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

private struct MissionDataUnlockView: View {
    let detail: String?
    let unlock: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "lock.shield.fill").font(.system(size: 46)).foregroundStyle(.green)
                Text("Mission data locked").font(.title2.bold()).foregroundStyle(.white)
                Text(detail ?? "Authenticate to load and edit encrypted mission data.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button("Unlock mission data", action: unlock)
                    .buttonStyle(.borderedProminent)
            }
            .padding(28)
        }
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
