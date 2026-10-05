import SwiftUI
import MapKit
import PDFKit
import UniformTypeIdentifiers

private enum BoundedImportReader {
    static func read(_ url: URL, maximumBytes: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else {
            throw CocoaError(.fileReadUnsupportedScheme)
        }
        guard let size = values.fileSize, size >= 0, size <= maximumBytes else {
            throw GeoJSONImporter.ImportError.limitExceeded(Messages.displayFileIsOverMb2a0bff1aMessage(DisplayFormat.number(Double(maximumBytes / 1_048_576), decimals: 0)))
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe, .uncached])
        guard data.count <= maximumBytes else {
            throw GeoJSONImporter.ImportError.limitExceeded(Messages.displayFileChangedWhileItWasBeingReadMessage())
        }
        return data
    }
}

enum SecurityScopedImportAccess {
    /// Small synchronous seam used by every detached import worker. The stop
    /// callback is guaranteed exactly once iff access was actually acquired.
    static func withBalancedScope<T>(start: () -> Bool,
                                     stop: () -> Void,
                                     operation: () throws -> T) rethrows -> T {
        let started = start()
        defer { if started { stop() } }
        return try operation()
    }

    static func coordinateReading<T>(at url: URL,
                                     operation: (URL) throws -> T) throws -> T {
        var coordinationError: NSError?
        var outcome: Result<T, Error>?
        let coordinator = NSFileCoordinator(filePresenter: nil)
        coordinator.coordinate(readingItemAt: url,
                               options: .withoutChanges,
                               error: &coordinationError) { coordinatedURL in
            outcome = Result { try operation(coordinatedURL) }
        }
        if let coordinationError { throw coordinationError }
        guard let outcome else { throw CocoaError(.fileReadUnknown) }
        return try outcome.get()
    }

    static func withCoordinatedRead<T>(of url: URL,
                                       operation: (URL) throws -> T) throws -> T {
        try withBalancedScope(
            start: { url.startAccessingSecurityScopedResource() },
            stop: { url.stopAccessingSecurityScopedResource() },
            operation: { try coordinateReading(at: url, operation: operation) }
        )
    }
}

/// Production drop-pin seam: construction and durable publication stay in one
/// testable operation, so a failed disk write cannot reach map or Sync observers.
enum DropPinMissionMutation {
    @discardableResult
    static func commit(coordinate: CLLocationCoordinate2D,
                       displayedCoordinate: String,
                       layerID: UUID,
                       to waypointStore: WaypointStore) throws -> Waypoint {
        let waypoint = Waypoint(
            name: displayedCoordinate,
            coordinate: coordinate,
            kind: .generic,
            layerID: layerID
        )
        _ = try waypointStore.addDurably(waypoint)
        return waypoint
    }
}

enum ExternalImportFileKind: Sendable {
    case geoJSON
    case kml
}

private func importWorkerIsOffMainThread() -> Bool {
    !Thread.isMainThread
}

enum ExternalImportWorker {
    struct Context: Codable {
        let existingLayers: [DrawingLayer]
        let fallbackLayerID: UUID
        let existingWaypointIDs: [UUID]
        let existingDrawingIDs: [UUID]
    }

    struct Payload: Sendable {
        let encodedBatch: Data
        let performedWorkOffMainThread: Bool
    }

    typealias CoordinatedAccess = (URL, (URL) throws -> Data) throws -> Data

    /// Synchronous core so tests can prove parsing happens before the
    /// coordinated/security-scoped closure returns. Only the freshly encoded
    /// batch leaves that closure; file-backed input Data never does.
    static func prepareSynchronously(
        url: URL,
        kind: ExternalImportFileKind,
        encodedContext: Data,
        batchKey: String,
        performedWorkOffMainThread: Bool,
        coordinatedAccess: CoordinatedAccess,
        parseStarted: @escaping () -> Void = {}
    ) throws -> Payload {
        try Task.checkCancellation()
        let context = try JSONDecoder().decode(Context.self, from: encodedContext)
        let encodedBatch = try coordinatedAccess(url) { coordinatedURL in
            try Task.checkCancellation()
            let maximum = kind == .geoJSON
                ? GeoJSONImporter.maxInputBytes
                : KMLImporter.maxInputBytes
            let input = try BoundedImportReader.read(coordinatedURL, maximumBytes: maximum)
            try Task.checkCancellation()
            parseStarted()
            let batch: GeoJSONImporter.ExternalBatch
            switch kind {
            case .geoJSON:
                batch = try GeoJSONImporter.parseExternal(
                    input,
                    existingLayers: context.existingLayers,
                    fallbackLayerID: context.fallbackLayerID,
                    existingWaypointIDs: Set(context.existingWaypointIDs),
                    existingDrawingIDs: Set(context.existingDrawingIDs),
                    batchKey: batchKey
                )
            case .kml:
                batch = try KMLImporter.parseExternal(
                    input,
                    existingLayers: context.existingLayers,
                    fallbackLayerID: context.fallbackLayerID,
                    existingWaypointIDs: Set(context.existingWaypointIDs),
                    existingDrawingIDs: Set(context.existingDrawingIDs),
                    batchKey: batchKey
                )
            }
            try Task.checkCancellation()
            return try JSONEncoder().encode(batch)
        }
        return Payload(encodedBatch: encodedBatch,
                       performedWorkOffMainThread: performedWorkOffMainThread)
    }

    static func prepare(url: URL,
                        kind: ExternalImportFileKind,
                        encodedContext: Data,
                        batchKey: String) async throws -> Payload {
        let worker = Task.detached(priority: .userInitiated) {
            let offMainThread = importWorkerIsOffMainThread()
            return try prepareSynchronously(
                url: url,
                kind: kind,
                encodedContext: encodedContext,
                batchKey: batchKey,
                performedWorkOffMainThread: offMainThread,
                coordinatedAccess: { source, operation in
                    try SecurityScopedImportAccess.withCoordinatedRead(of: source,
                                                                       operation: operation)
                }
            )
        }
        return try await withTaskCancellationHandler(
            operation: { try await worker.value },
            onCancel: { worker.cancel() }
        )
    }
}

struct ContentView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @StateObject private var locationService: LocationService
    @StateObject private var waypointStore   = WaypointStore()
    @StateObject private var drawingStore    = DrawingStore()
    @State private var ringFollower = RangeRingFollower()
    @StateObject private var drawingSession  = DrawingSessionViewModel()
    @StateObject private var measureSession  = MeasureSession()
    @StateObject private var visibility      = LayerVisibility()
    @StateObject private var mapVM           = MapViewModel()
    @StateObject private var calibration     = CalibrationSession()
    @StateObject private var importController = MapImportController()
    @StateObject private var trackRecorder: TrackRecorder
    @StateObject private var recordingCoordinator: RecordingCoordinator

    /// Injected from app gate so menu can show trial status + offer the
    /// unlock on demand (paywall otherwise only shows once trial expires).
    @ObservedObject var store: StoreManager
    private let trial = TrialManager()
    @State private var showPaywallSheet    = false

    @Environment(\.undoManager) private var undoManager
    @Environment(\.scenePhase) private var scenePhase
    @State private var canUndo = false
    /// library migration running off main, see restoreActiveBasemap
    @State private var basemapMigrationInFlight = false
    /// "x was never georeferenced" after the library migration, shown once
    @State private var migrationUncalibratedName: String?
    /// 3.0.1 L10: the migration salvaged or adopted orphan files, say so once
    @State private var libraryRecoveredNotice = false
    @State private var importInterrupted = false
    /// E3 decided for this launch (it runs once, after the first Loaded restore)
    @State private var autoResumeDecided = false
    #if DEBUG
    /// OD-F2: the import hook waits for a Loaded library instead of being dropped
    @State private var debugImportPending: URL?
    #endif
    /// whether the calibrated entry was a preview (non-durable) when it started
    @State private var calibrationDisplayWasPreview = false
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
    @State private var chatRoute: TacMapChatRoute?
    @State private var pendingChatRoute: TacMapChatRoute?
    @State private var appLockOverlayActive = AppLock.isEnabled
    @StateObject private var syncManager   = SyncManager()
    @State private var importMessage: LocalizedMessage? = nil
    @State private var pendingImportRetry: ExternalImportCommitProgress?
    @State private var importWorkTask: Task<Void, Never>?
    @State private var missionUnlockError: LocalizedMessage? = nil
    @State private var missionMutationMessage: LocalizedMessage? = nil
    @State private var dataKeyEpoch = 0
    /// Brief non-blocking status toast for sync and map controls.
    @State private var syncToast: String? = nil
    @State private var headingWatchdogTask: Task<Void, Never>?
    /// Share sheet URL for the combined mission-object GeoJSON action.
    @State private var missionObjectExportURL: URL? = nil
    @State private var missionObjectExportTitle = ""
    @State private var showWaypointSheet   = false
    @State private var showDrawingsSheet   = false   // "All Drawings" list
    @State private var showLayersSheet     = false
    @State private var showExportSheet     = false
    @State private var showSearchSheet     = false
    @State private var showAboutSheet      = false
    @State private var drawingsPanelOpen   = false   // inline panel below hamburger
    @State private var quickSymbolDraft: QuickSymbolDraft? = nil
    @State private var mapPressPoint: MapPressPoint? = nil
    @State private var profileRequest: ElevationProfileRequest? = nil
    /// The guided tour's current step, or nil when it is closed.
    @State private var tourStep: Int? = nil
    /// Set by "Replay Tour" in About; the tour starts once the sheet is gone.
    @State private var replayTourRequested = false
    /// View-owned drawing slider candidate. Rendered on the map without
    /// publishing through DrawingStore/Sync until the gesture ends.
    @State private var drawingControlsPreview: DrawingShape? = nil

    init(store: StoreManager) {
        self.store = store
        let locationService = LocationService()
        let trackRecorder = TrackRecorder()
        _locationService = StateObject(wrappedValue: locationService)
        _trackRecorder = StateObject(wrappedValue: trackRecorder)
        _recordingCoordinator = StateObject(wrappedValue: RecordingCoordinator(
            requestAuthorization: { locationService.requestAuthorisation() },
            initializeDurableRecording: { trackRecorder.start() },
            stopRecording: { trackRecorder.stop() },
            setBackgroundUpdates: { locationService.setBackgroundUpdates($0) },
            recordingError: { trackRecorder.pendingPersistError }
        ))
    }

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

    /// Plain PDF on its made-up placement: positions off it mean nothing yet.
    private var uncalibratedPDFLoaded: Bool {
        (mapVM.mapSource as? PDFMapSource)?.isUncalibrated == true
    }

    /// imported PDF is up but its renderer gave up (sticky until Try Again)
    private var pdfRenderFailed: Bool {
        mapVM.mapSource is PDFMapSource && mapVM.pdfRuntime.status.failure != nil
    }

    /// first draw of the imported PDF still going after 300 ms
    private var pdfPreparing: Bool {
        mapVM.mapSource is PDFMapSource && mapVM.pdfRuntime.showPreparingLabel
    }

    /// Basemap status shown in the MGRS banner (replaces Live Location/Map Centre).
    private var basemapLabel: String? {
        // calibrating reads calibrating (s7.8), a failure mid calibration still gets
        // its alert. Same order as Android
        if calibration.isCalibrating { return Messages.calibrationHeaderLabel() }
        if pdfRenderFailed { return Messages.pdfRenderFailedLabel() }
        if pdfPreparing { return Messages.pdfRenderDrawingLabel() }
        if uncalibratedPDFLoaded { return Messages.pdfMapUncalibratedLabel() }
        if importedMapLoaded { return L10n.text("Offline basemap") }
        if onlineTilesActive { return L10n.text("Online basemap") }
        return nil
    }
    private var basemapColor: Color {
        if calibration.isCalibrating { return Color(red: 1, green: 0.65, blue: 0.18) }  // amber, not a basemap yet
        if pdfRenderFailed { return PDFRenderStatusColors.failed }
        if pdfPreparing { return PDFRenderStatusColors.preparing }
        if uncalibratedPDFLoaded { return Color(red: 1, green: 0.65, blue: 0.18) }  // amber, not a basemap yet
        return importedMapLoaded
            ? PDFRenderStatusColors.ready                  // offline: the pinned green
            : Color(red: 1.0, green: 0.35, blue: 0.35)    // online: red
    }

    /// Calibrating: no coordinate on a provisional guess, PREVIEW on an unsaved fit (s7.8)
    private var calibrationHeaderReadout: CalibrationHeaderReadout? {
        guard calibration.isCalibrating, let d = calibration.display else { return nil }
        if d.isProvisional { return .notGeoreferenced }
        guard d.isFit else { return .preview(showTag: false) }
        let saved = calibration.entryID.flatMap { mapVM.entry($0)?.pdf?.manual?.georef }
        return .preview(showTag: saved.map { $0.affine != d.georef.affine || $0.crs != d.georef.crs || $0.datum != d.georef.datum } ?? true)
    }

    /// The coordinate the banner is reading out: the crosshair when browsing,
    /// else the live position. Drives the MGRS readout, drop-pin, and G-M angle.
    private var headerCoordinate: CLLocationCoordinate2D? {
        mapVM.isBrowsing ? mapVM.cameraCentre : locationService.lastLocation?.coordinate
    }

    /// Straight-line range from the latest device fix to the fixed map
    /// crosshair. CLLocation uses the geodesic distance between coordinates.
    private var distanceFromUserToCrosshair: CLLocationDistance? {
        guard let user = locationService.lastLocation,
              mapVM.cameraCentre.latitude.isFinite,
              mapVM.cameraCentre.longitude.isFinite,
              abs(mapVM.cameraCentre.latitude) <= 90,
              abs(mapVM.cameraCentre.longitude) <= 180 else { return nil }
        let crosshair = CLLocation(
            latitude: mapVM.cameraCentre.latitude,
            longitude: mapVM.cameraCentre.longitude
        )
        return user.distance(from: crosshair)
    }

    private var missionDataLocked: Bool {
        _ = dataKeyEpoch
        return (DataKey.isAuthBound && !DataKey.isUnlocked)
            || waypointStore.locked || drawingStore.locked || trackRecorder.requiresUnlock
    }

    private var unitSyncBackgroundPresenceEligible: Bool {
        opsec.backgroundUnitSyncLocation
            && syncManager.presenceConfig.shareLocation
            && syncManager.room?.hasPrefix("3:") == true
            // 21.4: a presence-only background reconnect keeps it eligible
            && (syncManager.status == .connected || syncManager.backgroundPresenceSustained)
            && LiveLocationPermissionPolicy.shouldStartUpdates(
                for: locationService.authorisationStatus
            )
    }

    /// A transient .inactive (control centre, notification shade, an alert)
    /// keeps Unit Sync and chat going when nothing actually locked. Only a
    /// key lock, App Lock or detached stores end the session (S2-12).
    private var syncForegroundReady: Bool {
        _ = dataKeyEpoch
        return SyncLifecyclePolicy.iosForegroundReady(
            phase: syncScenePhase,
            dataKeyAuthBound: DataKey.isAuthBound,
            dataKeyUnlocked: DataKey.isUnlocked,
            appLockOverlay: appLockOverlayActive,
            storesLocked: waypointStore.locked || drawingStore.locked
        )
    }

    private var syncScenePhase: SyncScenePhase {
        switch scenePhase {
        case .active: return .active
        case .inactive: return .inactive
        default: return .background
        }
    }

    private func refreshUnitSyncLifecycle() {
        let backgroundPresence = unitSyncBackgroundPresenceEligible
        locationService.setUnitSyncBackgroundUpdates(
            scenePhase != .active && backgroundPresence
        )
        syncManager.updateLifecycle(
            foregroundReady: syncForegroundReady,
            backgroundPresenceEnabled: backgroundPresence,
            backgroundInterval: opsec.backgroundUnitSyncInterval.seconds
        )
    }

    private func refreshHeadingLifecycle() {
        let shouldRun = scenePhase == .active
            && opsec.mapOrientationMode == .headingUp
            && locationService.isHeadingAvailable
        if shouldRun {
            locationService.startHeadingUpdates()
        } else {
            locationService.stopHeadingUpdates()
        }
        refreshHeadingWatchdog()
    }

    private func refreshHeadingWatchdog() {
        headingWatchdogTask?.cancel()
        headingWatchdogTask = nil
        guard scenePhase == .active,
              opsec.mapOrientationMode == .headingUp,
              locationService.isHeadingAvailable,
              locationService.deviceHeading == nil else { return }
        headingWatchdogTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled,
                  scenePhase == .active,
                  opsec.mapOrientationMode == .headingUp,
                  locationService.deviceHeading == nil else { return }
            _ = opsec.setMapOrientationMode(.northUp)
            showTransientToast(L10n.text("No reliable compass reading; switched to North Up."))
        }
    }

    private func showTransientToast(_ message: String) {
        syncToast = message
        UIAccessibility.post(notification: .announcement, argument: message)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            if syncToast == message { syncToast = nil }
        }
    }

    private func presentChat(_ route: TacMapChatRoute) {
        guard scenePhase == .active,
              !missionDataLocked,
              !appLockOverlayActive else {
            showTransientToast(L10n.text("Unlock TacMap before opening chat."))
            return
        }
        chatRoute = route
    }

    private func presentDirectChat(for actorId: String) {
        guard let recipient = syncManager.chatRecipients[actorId] else {
            showTransientToast(L10n.text("That unit is not currently ready for encrypted chat."))
            return
        }
        presentChat(.direct(recipient))
    }

    private func lockChatUIAndSecrets() {
        CustomSymbolStore.shared.clear()
        chatRoute = nil
        pendingChatRoute = nil
        syncManager.lockChatForMissionData()
    }

    private func restoreChatIfSecurityAllows() {
        guard scenePhase == .active,
              !appLockOverlayActive,
              !missionDataLocked else { return }
        syncManager.migrateLegacyLocalStoresAfterUnlock()
        syncManager.restoreChatAfterMissionUnlock()
    }

    private func handleCompassTap() {
        switch MapHeading.compassTapAction(
            headingUpEnabled: opsec.mapOrientationMode == .headingUp,
            currentHeading: mapVM.heading,
            headingAvailable: locationService.isHeadingAvailable
        ) {
        case .resetNorth:
            mapVM.resetNorth()
        case .enableHeadingUp:
            _ = opsec.setMapOrientationMode(.headingUp)
        case .disableHeadingUp:
            _ = opsec.setMapOrientationMode(.northUp)
        case .headingUnavailable:
            showTransientToast(L10n.text("Heading Up is unavailable on this device."))
        }
    }

    private var baseMapContent: some View {
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
                    drawingControlsPreview: drawingControlsPreview,
                    presence: syncManager.presence,
                    onPeerTap: presentDirectChat,
                    onMutationError: { missionMutationMessage = $0 },
                    onEmptyMapLongPress: { coordinate in
                        mapPressPoint = MapPressPoint(coordinate: coordinate,
                                                      format: opsec.coordinateDisplayFormat)
                    }
                )
                .ignoresSafeArea()
                .overlay {
                    if basemapBlank { NoBasemapNotice() }
                }
                .modifier(PDFRenderChrome(mapVM: mapVM, runtime: mapVM.pdfRuntime,
                                          bake: PDFBakeController.shared, visibility: visibility,
                                          calibration: calibration, layersSheetShowing: showLayersSheet))

                if onlineTilesActive && onlineTileHealth.temporarilyUnavailable {
                    VStack {
                        Text(L10n.text("Online basemap temporarily unavailable"))
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
                // symbology hidden while calibrating, the setting itself stays (s7.9)
                .opacity(calibration.isCalibrating ? 0 : 1)
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
                if calibration.isCalibrating {
                    // open centred reticle so the printed intersection shows through (s7.6)
                    CalibrationReticle()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                } else if !drawingSession.isDrawing {
                    CrosshairOverlay()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                }

                freehandCaptureOverlay

                hudOverlay(bottomInset: geo.safeAreaInsets.bottom)

                calibrationOverlays

                ImportProgressHUD(controller: importController)

                syncToastOverlay
            }
            .overlayPreferenceValue(TourAnchorKey.self) { anchors in
                Group {
                    if let step = tourStep {
                        MapTourOverlay(
                            steps: FirstRunTips.steps,
                            index: Binding(get: { step }, set: { tourStep = $0 }),
                            anchors: anchors,
                            onFinish: finishTour
                        )
                        .transition(.opacity)
                    }
                }
            }
        }
    }

    private var missionDataLock: MissionDataLock? {
        guard missionDataLocked else { return nil }
        return MissionDataLock(detail: missionUnlockError?.text
                               ?? waypointStore.loadError
                               ?? drawingStore.loadError
                               ?? trackRecorder.persistError)
    }

    private var lifecycleContent: some View {
        baseMapContent
        .overlay {
            // backstop only, a sheet that was already open sits on top of this.
            // the real one is in the cover window (SecurityCoverWindows)
            if let lock = missionDataLock {
                MissionDataUnlockView(detail: lock.detail, unlock: unlockMissionData)
            }
        }
        .onChange(of: missionDataLock) { lock in
            SecurityCoverWindows.shared.setMissionData(lock, unlock: unlockMissionData)
        }
        .onAppear { SecurityCoverWindows.shared.setMissionData(missionDataLock, unlock: unlockMissionData) }
        .onDisappear { SecurityCoverWindows.shared.setMissionData(nil, unlock: {}) }
        .onReceive(NotificationCenter.default.publisher(for: DataKey.lockChanged)) { _ in
            dataKeyEpoch &+= 1
            if DataKey.isAuthBound && !DataKey.isUnlocked {
                lockChatUIAndSecrets()
            }
            // RootGate may lock an auth-bound key after our scenePhase handler
            // already ran, so re-evaluate sync here too
            refreshUnitSyncLifecycle()
        }
        .onReceive(NotificationCenter.default.publisher(for: AppLock.stateChanged)) { note in
            guard let value = note.object as? NSNumber else { return }
            appLockOverlayActive = value.boolValue
            if value.boolValue {
                lockChatUIAndSecrets()
            } else {
                restoreChatIfSecurityAllows()
                presentTipsIfNeeded()
            }
            refreshUnitSyncLifecycle()
        }
        .alert(L10n.text("Mission Object Not Saved"), isPresented: Binding(
            get: { missionMutationMessage != nil },
            set: { if !$0 { missionMutationMessage = nil } }
        )) {
            Button(Messages.acknowledge(), role: .cancel) { missionMutationMessage = nil }
        } message: {
            Text(missionMutationMessage?.text ?? L10n.text("The change could not be saved. Check available storage, then try again."))
        }
        .alert(L10n.text("Track Recording"), isPresented: Binding(
            get: { trackRecorder.persistError != nil && !trackRecorder.requiresUnlock },
            set: { if !$0 { trackRecorder.persistError = nil } }
        )) {
            if !trackRecorder.points.isEmpty || trackRecorder.recovered {
                Button(L10n.text("Discard Saved Track"), role: .destructive) { trackRecorder.discard() }
            }
            Button(Messages.acknowledge(), role: .cancel) { trackRecorder.persistError = nil }
        } message: {
            Text(trackRecorder.persistError ?? L10n.text("Track recording failed."))
        }
        .alert(L10n.text("Location Permission"),
               isPresented: Binding(
                   get: { recordingCoordinator.guidance != nil },
                   set: { if !$0 { recordingCoordinator.guidance = nil } }
               ),
               presenting: recordingCoordinator.guidance) { guidance in
            if guidance.offersSettings {
                Button(L10n.text("Open Settings")) {
                    recordingCoordinator.guidance = nil
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            }
            Button(L10n.text("Not Now"), role: .cancel) { recordingCoordinator.guidance = nil }
        } message: { guidance in
            Text(guidance.message)
        }
        .task {
            drawingStore.undoManager = undoManager
            waypointStore.undoManager = undoManager
            ringFollower.attach(waypointStore: waypointStore, drawingStore: drawingStore)
            NightModeController.shared.start()
            presentTipsIfNeeded()
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
            if opsec.mapOrientationMode == .headingUp,
               !locationService.isHeadingAvailable {
                _ = opsec.setMapOrientationMode(.northUp)
            }
            refreshHeadingLifecycle()
            #if DEBUG
            let askForLocation = !DebugHooks.active
            #else
            let askForLocation = true
            #endif
            if askForLocation, LiveLocationPermissionPolicy.shouldRequestOnInitialAppearance(
                for: locationService.authorisationStatus
            ) {
                locationService.requestAuthorisation()
            } else if LiveLocationPermissionPolicy.shouldStartUpdates(
                for: locationService.authorisationStatus
            ) {
                locationService.start()
            }
            // the bake's record writes go through the library from here on
            mapVM.bindBakeController(PDFBakeController.shared)
            // OD-F2: wired before the restore and the debug hooks, an import with
            // no view model to talk to was silently dropped
            importController.mapVM = mapVM
            importController.startCalibration = { startCalibration(entryID: $0) }
            // F3: Retry on the locked library re-runs the migration too
            mapVM.reloadRequested = { restoreActiveBasemap() }
            mapVM.resumeCalibrationRequested = { startCalibration(entryID: $0, resumeSilently: true) }
            restoreActiveBasemap()
            // a bake (or its removal) that finished for an older object of the map on
            // screen, eg after switching away and back mid bake: keep the live one in step
            PDFBakeController.shared.onPublished = { [mapVM] pdf in
                guard let shown = mapVM.mapSource as? PDFMapSource, shown !== pdf,
                      shown.contentKey == pdf.contentKey, shown.georef == pdf.georef else { return }
                shown.bake = pdf.bake
                if let record = pdf.bake { mapVM.pdfRuntime.attachBake(record, for: shown) } else { mapVM.pdfRuntime.detachBake() }
            }
            #if DEBUG
            runDebugHooks()
            #endif
        }
        .onReceive(locationService.$lastLocation.compactMap { $0 }) { loc in
            mapVM.userLocationDidUpdate(
                loc,
                resetOrientation: opsec.mapOrientationMode == .northUp
            )
        }
        .onReceive(locationService.$deviceHeading.compactMap { $0 }) { heading in
            // calibrating pauses heading-up (rotation is the user's), the setting stays
            if opsec.mapOrientationMode == .headingUp && !calibration.isCalibrating {
                mapVM.orientMap(to: heading)
            }
        }
        .onChange(of: locationService.deviceHeading) { _ in
            refreshHeadingWatchdog()
        }
    }

    private var sheetContent: some View {
        lifecycleContent
        .nightSheet(isPresented: $showWaypointSheet) {
            WaypointListSheet(waypointStore: waypointStore,
                              drawingStore: drawingStore,
                              mapVM: mapVM)
                .padSheetSizing()
        }
        .modifier(MapPointMenuModifier(
            point: $mapPressPoint,
            drawingStore: drawingStore,
            canEdit: !graphicsLocked,
            onPlaceSymbol: { beginQuickSymbolCreation(at: $0) },
            onMeasure: { coordinate in
                drawingsPanelOpen = false
                drawingSession.cancel()
                measureSession.start()
                measureSession.addPoint(coordinate)
            },
            onCopied: { format in
                showTransientToast(L10n.text("%1$@ copied", format.label))
            }
        ))
        .nightSheet(item: $quickSymbolDraft) { draft in
            WaypointCreationSheet(
                waypointStore: waypointStore,
                defaultCoordinate: draft.coordinate,
                defaultScale: draft.scale,
                defaultLayerID: draft.layerID
            )
        }
        .nightSheet(isPresented: $showDrawingsSheet) {
            DrawingsSheet(drawingStore: drawingStore, session: drawingSession)
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showLayersSheet) {
            LayersSheet(visibility: visibility,
                        mapVM: mapVM,
                        drawingStore: drawingStore,
                        waypointStore: waypointStore,
                        onCalibrate: { id in
                            // the sheet animates out first, then calibration takes over
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { startCalibration(entryID: id) }
                        },
                        onChoosePage: { entry in
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { importController.choosePage(for: entry) }
                        },
                        isCalibrating: calibration.isCalibrating)
                .padSheetSizing()
        }
        .nightSheet(isPresented: Binding(
            get: { calibration.phase == .pointsSheet },
            set: { if !$0, calibration.phase == .pointsSheet { calibration.phase = .placing } }
        )) {
            CalibrationPointsSheet(session: calibration)
                .presentationDetents([.medium, .large])
        }
        .nightSheet(isPresented: Binding(
            get: { calibration.phase == .datumSheet },
            set: { if !$0, calibration.phase == .datumSheet { calibration.phase = .placing } }
        )) {
            CalibrationDatumSheet(session: calibration)
                .presentationDetents([.medium, .large])
        }
        .nightSheet(item: $importController.pagePicker) { req in
            PDFPagePickerSheet(request: req,
                               onPick: { importController.choosePage($0) },
                               onCancel: { importController.cancelPagePicker() })
                .interactiveDismissDisabled()
        }
        .nightSheet(isPresented: $showExportSheet) {
            ExportSheet(waypointStore: waypointStore, drawingStore: drawingStore)
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showGPXExporter) {
            GPXExportSheet(points: trackRecorder.points)
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showWeatherSheet) {
            WeatherSheet(coordinate: mapVM.cameraCentre)
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showAppLockSheet) {
            AppLockSetupView()
                .padSheetSizing()
        }
        .nightSheet(item: $profileRequest) { request in
            ElevationProfileSheet(request: request)
                .nightDragIndicator()
        }
        .nightSheet(isPresented: $showOpsecSheet, onDismiss: presentTipsIfNeeded) {
            OpsecSettingsView()
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showSyncSheet, onDismiss: {
            guard let route = pendingChatRoute else { return }
            pendingChatRoute = nil
            presentChat(route)
        }) {
            SyncSheet(manager: syncManager) { route in
                pendingChatRoute = route
                showSyncSheet = false
            }
                .padSheetSizing()
        }
        .nightSheet(item: $chatRoute) { route in
            TacMapChatView(
                manager: syncManager,
                store: syncManager.chatStore,
                initialRoute: route
            )
            .padSheetSizing()
        }
    }

    private var syncAndRecordingContent: some View {
        sheetContent
        .task {
            syncManager.configure(waypointStore: waypointStore,
                                  drawingStore: drawingStore,
                                  locationService: locationService)
            if !missionDataLocked {
                syncManager.migrateLegacyLocalStoresAfterUnlock()
            }
            refreshUnitSyncLifecycle()
        }
        // Remote sync update toast (conflict notif).
        .onReceive(syncManager.remoteUpdateSubject) { message in
            showTransientToast(message)
        }
        // Feed every fix into the track recorder. It just ignores them unless recording.
        .onReceive(locationService.$lastLocation.compactMap { $0 }) { loc in
            trackRecorder.ingest(loc)
            syncManager.locationDidUpdate()
        }
        .onChange(of: locationService.authorisationStatus) { status in
            recordingCoordinator.authorizationChanged(status)
            if LiveLocationPermissionPolicy.shouldStartUpdates(for: status) {
                locationService.start()
            } else {
                locationService.stop()
            }
            refreshUnitSyncLifecycle()
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                restoreChatIfSecurityAllows()
            } else if phase == .background || !syncForegroundReady {
                // a transient .inactive that locked nothing keeps the chat key
                lockChatUIAndSecrets()
            }
            refreshUnitSyncLifecycle()
            refreshHeadingLifecycle()
        }
        .onChange(of: opsec.mapOrientationMode) { mode in
            if mode == .headingUp, !locationService.isHeadingAvailable {
                _ = opsec.setMapOrientationMode(.northUp)
                return
            }
            refreshHeadingLifecycle()
            if mode == .headingUp {
                if let heading = locationService.deviceHeading {
                    mapVM.orientMap(to: heading)
                }
            } else {
                mapVM.resetNorth()
            }
        }
        .onChange(of: opsec.backgroundUnitSyncLocation) { _ in
            refreshUnitSyncLifecycle()
        }
        .onChange(of: opsec.backgroundUnitSyncInterval) { _ in
            refreshUnitSyncLifecycle()
        }
        .onChange(of: syncManager.presenceConfig.shareLocation) { _ in
            refreshUnitSyncLifecycle()
        }
        .onChange(of: syncManager.room) { _ in
            refreshUnitSyncLifecycle()
        }
        .onChange(of: syncManager.status) { _ in
            refreshUnitSyncLifecycle()
        }
        .onChange(of: syncManager.backgroundPresenceSustained) { _ in
            refreshUnitSyncLifecycle()
        }
        .onChange(of: dataKeyEpoch) { _ in
            if missionDataLocked {
                lockChatUIAndSecrets()
            } else {
                restoreChatIfSecurityAllows()
            }
            refreshUnitSyncLifecycle()
        }
        .onChange(of: trackRecorder.isRecording) { active in
            if !active {
                recordingCoordinator.recorderDidStopUnexpectedly(
                    error: trackRecorder.pendingPersistError
                )
            }
        }
        .nightSheet(isPresented: $showSearchSheet) {
            SearchSheet(
                mapVM: mapVM,
                waypointStore: waypointStore,
                drawingStore: drawingStore
            )
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showAboutSheet, onDismiss: presentTipsIfNeeded) {
            AcknowledgementsView(onReplayTour: { replayTourRequested = true })
                .padSheetSizing()
        }
        .nightSheet(isPresented: $showPaywallSheet) {
            PaywallView(
                store: store,
                trialDaysRemaining: trial.daysRemaining(),
                onRestore: { Task { await store.restore() } },
                onClose: { showPaywallSheet = false }
            )
        }
    }

    private var importerContent: some View {
        syncAndRecordingContent
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
    }

    /// every sheet / picker ContentView presents over itself. The map issue
    /// alert on the root waits while any of them is up (OD3-R3-1 F1). New
    /// presentations on ContentView go in here too
    private var rootPresentationsOnTop: [Bool] {
        [showWaypointSheet, quickSymbolDraft != nil, showDrawingsSheet, showLayersSheet,
         calibration.phase == .pointsSheet, calibration.phase == .datumSheet,
         importController.pagePicker != nil, showExportSheet, showGPXExporter, showWeatherSheet,
         showAppLockSheet, profileRequest != nil, showOpsecSheet, showSyncSheet, chatRoute != nil,
         showSearchSheet, showAboutSheet, showPaywallSheet, missionObjectExportURL != nil,
         showImporter, showGeoJSONImporter, showMBTilesImporter, showKMLImporter]
    }

    var body: some View {
        importerContent
        // OD3-R3-1: stands down while anything is presented over the root. Layers
        // hosts it itself while open, any other sheet just defers it until it closes.
        // commit handles its own retry now, so a Retry here never ends a calibration
        .mapSelectionIssueAlert(
            mapVM: mapVM,
            isActive: MapSelectionIssueAlertGate.rootHostIsActive(presentationsOnTop: rootPresentationsOnTop)
        ) { _ in }
        .alert(L10n.text("Import"),
               isPresented: Binding(get: { importMessage != nil },
                                    set: { if !$0 { importMessage = nil } }),
               presenting: importMessage) { _ in
            if pendingImportRetry != nil {
                Button(L10n.text("Retry")) { retryPendingExternalImport() }
                Button(L10n.text("Cancel"), role: .cancel) {
                    pendingImportRetry = nil
                    importMessage = nil
                }
            } else {
                Button(Messages.acknowledge(), role: .cancel) { importMessage = nil }
            }
        } message: { msg in
            Text(msg.text)
        }
        .modifier(MapImportAlerts(controller: importController,
                                  migrationUncalibratedName: $migrationUncalibratedName,
                                  libraryRecoveredNotice: $libraryRecoveredNotice,
                                  importInterrupted: $importInterrupted,
                                  onCalibrate: { startCalibration(entryID: $0) }))
        .modifier(CalibrationAlerts(session: calibration,
                                    onFinish: commitCalibration,
                                    onLeave: { keep in
                                        calibration.leave(keepDraft: keep)
                                        finishCalibrationSession()
                                    }))
        .onReceive(calibration.toasts) { showTransientToast($0) }
        .onReceive(importController.toasts) { showTransientToast($0) }
        .onReceive(calibration.flyRequests) { mapVM.centreRequests.send($0) }
        .onReceive(NotificationCenter.default.publisher(for: DataKey.lockChanged)) { _ in
            if !DataKey.isAuthBound || DataKey.isUnlocked { calibration.onDataKeyUnlocked() }
        }
        .onChange(of: mapVM.libraryStatus) { status in
            guard status == .loaded else { return }
            // a Retry / unlock / rebuild that made it Loaded: E3 gets its one go now
            maybeAutoResume()
            #if DEBUG
            if let url = debugImportPending {
                debugImportPending = nil
                importController.importPDF(url: url, completion: debugApplyCamera)
            }
            #endif
        }
        .onChange(of: mapVM.mapSource.id) { _ in
            // the shown map moved to another entry mid calibration (e.g. a Retry
            // of an older failed write): stop, keep the points (s2.8)
            guard calibration.isCalibrating else { return }
            let shownEntry = (mapVM.mapSource as? PDFMapSource)?.entryID
            if shownEntry != calibration.entryID {
                calibration.suspend()
                finishCalibrationSession(restoreDisplay: false)
            }
        }
        .onDisappear {
            importWorkTask?.cancel()
            headingWatchdogTask?.cancel()
            locationService.stopHeadingUpdates()
            locationService.setUnitSyncBackgroundUpdates(false)
            syncManager.updateLifecycle(
                foregroundReady: false,
                backgroundPresenceEnabled: false,
                backgroundInterval: opsec.backgroundUnitSyncInterval.seconds
            )
        }
        .nightSheet(isPresented: Binding(
            get: { missionObjectExportURL != nil },
            set: { if !$0 { missionObjectExportURL = nil } }
        )) {
            if let url = missionObjectExportURL {
                ShareSheetView(activityItems: [url], title: missionObjectExportTitle)
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
                                saveNewDrawing(shape)
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
                coordinateDisplayFormat: opsec.coordinateDisplayFormat,
                syncConnected: syncManager.status == .connected,
                basemapLabel: basemapLabel,
                basemapColor: basemapColor,
                gridMagneticDegrees: GridMagnetic.angle(
                    latitude: headerCoordinate?.latitude,
                    longitude: headerCoordinate?.longitude),
                distanceFromUser: distanceFromUserToCrosshair,
                // GPS altitude is only a stand-in while the banner reads out
                // your own position, never for a crosshair somewhere else.
                elevation: mapVM.centreElevation ?? (mapVM.isBrowsing ? nil : locationService.lastAltitude),
                elevationIsApproximate: mapVM.centreElevationIsApproximate,
                coordinate: headerCoordinate,
                onDropPin: calibrationHeaderReadout == .notGeoreferenced ? nil : { coord, displayedCoordinate in
                    let layerID = drawingStore.activeLayerID
                        ?? drawingStore.layers.first?.id
                        ?? DrawingLayer.legacyFallbackID
                    do {
                        try DropPinMissionMutation.commit(
                            coordinate: coord,
                            displayedCoordinate: displayedCoordinate,
                            layerID: layerID,
                            to: waypointStore
                        )
                    } catch {
                        missionMutationMessage = Messages.displayTheDroppedSymbolWasNotAddedCheckAvailableStorageMessage("").withArgument(0, error.displayMessage)
                    }
                },
                calibrationReadout: calibrationHeaderReadout
            )
            .tourTarget(.header)
            .padding(.horizontal, 12)

            // The online-tiles warning used to sit here under the header, but
            // that's exactly where the live-tracking record badge goes, so they
            // collided. It's paired with the Centre button at the bottom now.

            if recordingCoordinator.state != .idle {
                RecordingIndicator(
                    state: recordingCoordinator.state,
                    pointCount: trackRecorder.points.count,
                    onStop: {
                        recordingCoordinator.stop()
                    }
                )
                .padding(.top, 8)
            }

            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 8) {
                  if calibration.isCalibrating {
                    // the only way out, top left and well away from Finish (s10)
                    Button { calibration.leaveTapped(); if !calibration.isCalibrating { finishCalibrationSession() } } label: {
                        Image(systemName: "xmark")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(.white)
                            .frame(width: 56, height: 56)
                            .background(.black.opacity(0.85), in: Circle())
                            .overlay(Circle().stroke(.white.opacity(0.15)))
                    }
                    .accessibilityLabel(Messages.calibrationClose())
                    .accessibilityIdentifier("calibration.close")
                  } else {
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
                        recordingState: recordingCoordinator.state,
                        trackPointCount: trackRecorder.points.count,
                        onToggleTrackRecording: {
                            drawingsPanelOpen = false
                            recordingCoordinator.toggle(
                                authorization: locationService.authorisationStatus
                            )
                        },
                        onExportGPX: {
                            drawingsPanelOpen = false
                            showGPXExporter = true
                        },
                        onExportAll: {
                            drawingsPanelOpen = false
                            exportAllMissionObjects()
                        },
                        onExportKML: {
                            drawingsPanelOpen = false
                            exportKML(.kml)
                        },
                        onExportKMZ: {
                            drawingsPanelOpen = false
                            exportKML(.kmz)
                        },
                        onChat:      {
                            drawingsPanelOpen = false
                            presentChat(.room)
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
                        },
                        importsEnabled: !importController.isRunning
                    )
                    .tourTarget(.menu)
                  }

                    if syncManager.room?.hasPrefix("3:") == true, !calibration.isCalibrating {
                        TacMapChatShortcutButton(store: syncManager.chatStore) {
                            drawingsPanelOpen = false
                            presentChat(.room)
                        }
                    }

                    if !drawingSession.isDrawing,
                       !measureSession.isActive,
                       !calibration.isCalibrating,
                       !graphicsLocked,
                       !missionDataLocked {
                        QuickAddSymbolButton(action: beginQuickSymbolCreation)
                            .tourTarget(.add)
                    }

                    if !calibration.isCalibrating {
                        UnitLabelsToggle(active: visibility.unitLabelsVisible) {
                            visibility.unitLabelsVisible.toggle()
                        }
                        .tourTarget(.labels)
                    }

                    NightModeToggle(active: opsec.nightMode) {
                        _ = opsec.setNightMode(!opsec.nightMode)
                    }
                    .tourTarget(.night)

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
                    CompassChip(
                        heading: mapVM.heading,
                        orientationMode: opsec.mapOrientationMode,
                        headingAvailable: locationService.isHeadingAvailable,
                        northReference: locationService.headingNorthReference,
                        onTap: handleCompassTap
                    )
                    .tourTarget(.compass)
                    if (canUndo || canRedo) && !calibration.isCalibrating {
                        UndoRedoButtons(
                            canUndo: canUndo,
                            canRedo: canRedo,
                            onUndo: { undoManager?.undo() },
                            onRedo: { undoManager?.redo() }
                        )
                    }
                    if !calibration.isCalibrating {
                        LockButton(locked: graphicsLocked) {
                            graphicsLocked.toggle()
                            if graphicsLocked {
                                mapVM.selectedWaypointID = nil
                                mapVM.selectedDrawingID = nil
                            }
                        }
                        .tourTarget(.lock)
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
            if calibration.pendingEntry == nil {
                CalibrationPanel(session: calibration, mapVM: mapVM, onFinish: commitCalibration)
                    .padding(.horizontal, 12)
                    .padding(.bottom, max(bottomInset, 8) + 6)
            }
        } else if drawingSession.isDrawing {
            DrawToolbar(session: drawingSession) {
                if let shape = drawingSession.finish() {
                    saveNewDrawing(shape)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, max(bottomInset, 8) + 6)
        } else if measureSession.isActive {
            MeasureToolbar(session: measureSession) {
                profileRequest = ElevationProfileRequest(measureSession.points)
            }
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
                    crosshairCoordinate: mapVM.cameraCentre,
                    onPreview: { drawingControlsPreview = $0 },
                    onDismiss: {
                        drawingControlsPreview = nil
                        mapVM.selectedDrawingID = nil
                    }
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
                    let locationControl = LiveLocationPermissionPolicy.control(
                        for: locationService.authorisationStatus
                    )
                    CentreButton(
                        title: locationControl.action == .centreOnLocation && importedMapLoaded
                            ? L10n.text("My Location")
                            : locationControl.title,
                        systemImage: locationControl.systemImage
                    ) {
                        performLiveLocationAction(locationControl.action)
                    }
                    .accessibilityHint(locationControl.guidance)
                    if importedMapLoaded {
                        CentreButton(title: L10n.text("Map"), systemImage: "map") {
                            mapVM.centreOnMap()
                        }
                    }
                }
                .offset(y: max(bottomInset - 32, 0))
            }
        }
    }

    private func performLiveLocationAction(_ action: LiveLocationPermissionPolicy.Action) {
        switch action {
        case .requestPermission:
            locationService.requestAuthorisation()
        case .centreOnLocation:
            locationService.start()
            mapVM.centreOnUser(
                locationService.lastLocation,
                resetOrientation: opsec.mapOrientationMode == .northUp
            )
        case .openSettings:
            guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
            UIApplication.shared.open(url)
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
                    // clear of the calibration panel, it sat right on Add point
                    .padding(.bottom, calibration.isCalibrating ? 250 : 80)
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

    private func saveNewDrawing(_ shape: DrawingShape) {
        do {
            _ = try drawingStore.addDurably(shape)
        } catch {
            missionMutationMessage = Messages.displayTheDrawingWasNotAddedCheckAvailableStorageThenMessage("").withArgument(0, error.displayMessage)
        }
    }

    /// Open the fast symbol builder on a layer whose contents are visible.
    /// Preserve an already-visible active layer; if it is hidden, prefer another
    /// visible layer without changing the user's active-layer selection.
    private func beginQuickSymbolCreation() {
        beginQuickSymbolCreation(at: mapVM.cameraCentre)
    }

    /// First-run tips, once the map is visible and unlocked.
    /// Starts the guided tour on first run, or when About asked to replay it.
    private func presentTipsIfNeeded() {
        guard !appLockOverlayActive, !missionDataLocked, tourStep == nil else { return }
        #if DEBUG
        // scripted verification runs want the bare map
        if DebugHooks.active { return }
        #endif
        if replayTourRequested {
            replayTourRequested = false
        } else if !FirstRunTips.shouldShow() {
            return
        }
        drawingsPanelOpen = false
        mapPressPoint = nil
        mapVM.selectedWaypointID = nil
        mapVM.selectedDrawingID = nil
        withAnimation(.easeOut(duration: 0.25)) { tourStep = 0 }
    }

    private func finishTour() {
        FirstRunTips.markSeen()
        withAnimation(.easeOut(duration: 0.2)) { tourStep = nil }
    }

    /// Opens the symbol builder for a long-pressed map point.
    private func beginQuickSymbolCreation(at coordinate: CLLocationCoordinate2D) {
        drawingsPanelOpen = false
        mapVM.selectedWaypointID = nil
        mapVM.selectedDrawingID = nil
        visibility.waypointsVisible = true
        guard let layerID = visibleLayerIDForQuickSymbol() else { return }
        quickSymbolDraft = QuickSymbolDraft(
            coordinate: coordinate,
            layerID: layerID,
            scale: mapVM.defaultControlMeasureScale
        )
    }

    private func visibleLayerIDForQuickSymbol() -> UUID? {
        if let activeID = drawingStore.activeLayerID,
           let active = drawingStore.layer(id: activeID),
           active.visible {
            return active.id
        }
        if let visible = drawingStore.layers.first(where: \.visible) {
            return visible.id
        }
        if let activeID = drawingStore.activeLayerID,
           let active = drawingStore.layer(id: activeID) {
            do {
                try drawingStore.setLayerVisible(active, true)
                return active.id
            } catch {
                missionMutationMessage = Messages.displayTheSymbolLayerCouldNotBeMadeVisibleCheckMessage("").withArgument(0, error.displayMessage)
                return nil
            }
        }
        if let first = drawingStore.layers.first {
            if drawingStore.activeLayerID == nil {
                drawingStore.activeLayerID = first.id
            }
            do {
                try drawingStore.setLayerVisible(first, true)
                return first.id
            } catch {
                missionMutationMessage = Messages.displayTheSymbolLayerCouldNotBeMadeVisibleCheckMessage("").withArgument(0, error.displayMessage)
                return nil
            }
        }

        // DrawingStore normally guarantees seed layers. Recover defensively if
        // a sync/import transition presents a transient empty collection.
        let fallback = DrawingLayer.seedDefaults[0]
        do {
            _ = try drawingStore.addLayerVerbatim(fallback)
            return fallback.id
        } catch {
            missionMutationMessage = Messages.displayASymbolLayerCouldNotBeRestoredCheckAvailableMessage("").withArgument(0, error.displayMessage)
            return nil
        }
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
            restoreActiveBasemap()
            refreshUnitSyncLifecycle()
        } catch {
            missionUnlockError = error.displayMessage
        }
    }

    /// Restore the map that was actually selected, independently of the
    /// imported-PDF library. On an upgrade with no active-choice descriptor we
    /// keep the usable online default; the saved PDF remains available from
    /// Layers, but is no longer incorrectly assumed to have been active.
    private func restoreActiveBasemap() {
        // first launch after the WP5 upgrade moves the old stores into the
        // library, which can re-parse a v1 PDF (seconds on a big USGS sheet). do
        // that off main and leave the online default up meanwhile. Same hop for
        // a salvage or for map files sitting there with no library (3.0.1 L5, L7),
        // both hash and inspect every file
        guard !basemapMigrationInFlight else { return }
        if !ImportedMapLibrary.exists() && ImportedMapLibraryMigration.pending {
            basemapMigrationInFlight = true
            DispatchQueue.global(qos: .userInitiated).async {
                let result = ImportedMapLibraryMigration.migrateIfNeeded()
                DispatchQueue.main.async {
                    basemapMigrationInFlight = false
                    switch result {
                    case .migrated(let name?): migrationUncalibratedName = name
                    case .salvaged, .adoptedOrphans: libraryRecoveredNotice = true
                    default: break
                    }
                    publishRestoredBasemap()
                }
            }
            return
        }
        publishRestoredBasemap()
    }

    private func publishRestoredBasemap() {
        _ = mapVM.restoreActiveMapSelection()
        if mapVM.recoverInterruptedImport() { importInterrupted = true }
        maybeAutoResume()
    }

    /// E3: the app died mid calibration, put the user straight back (s2.1).
    /// Once per launch, after the first Loaded restore. Not while any crash
    /// suspect is pending, the resume would draw the very sheet it suspects
    /// (M10, C8); the draft stays active for next time
    private func maybeAutoResume() {
        guard !autoResumeDecided, !calibration.isCalibrating, mapVM.libraryStatus == .loaded else { return }
        let draft = mapVM.drafts.activeDraft()
        let entry = mapVM.autoResumeEntry()
        let action = CalibrationAutoResume.decide(draftActive: draft?.active,
                                                  entry: entry.map { ImportedMapStates.state($0, file: mapVM.fileStatus($0)) },
                                                  library: mapVM.libraryStatus,
                                                  suspectPending: mapVM.crashSuspectPending)
        switch action {
        case .deferUntilLoaded: return
        case .skip: autoResumeDecided = true
        case .resume:
            autoResumeDecided = true
            if let entry { startCalibration(entryID: entry.id, resumeSilently: true) }
        }
    }

    /// Export all waypoints + drawings + layers to GeoJSON and show
    /// system share sheet.
    private func exportAllMissionObjects() {
        do {
            let url = try MissionObjectExport.exportToFile(
                waypoints: waypointStore.waypoints,
                drawings: drawingStore.shapes,
                layers: drawingStore.layers
            )
            missionObjectExportTitle = MissionObjectExport.shareTitle
            missionObjectExportURL = url
        } catch {
            importMessage = Messages.displayExportFailedMessage("").withArgument(0, error.displayMessage)
        }
    }

    private func exportKML(_ format: KMZExporter.Format) {
        do {
            missionObjectExportTitle = format.shareTitle
            missionObjectExportURL = try KMZExporter.exportToFile(
                format: format,
                waypoints: waypointStore.waypoints,
                drawings: drawingStore.shapes,
                layers: drawingStore.layers
            )
        } catch {
            importMessage = Messages.displayExportFailedMessage("").withArgument(0, error.displayMessage)
        }
    }

    private func handleGeoJSONImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = Messages.importFailedMessage("").withArgument(0, err.displayMessage)
        case .success(let urls):
            guard let url = urls.first else { return }
            beginExternalImport(url: url, kind: .geoJSON, formatName: "GeoJSON")
        }
    }

    private func handleKMLImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = Messages.importFailedMessage("").withArgument(0, err.displayMessage)
        case .success(let urls):
            guard let url = urls.first else { return }
            beginExternalImport(url: url, kind: .kml, formatName: "KML")
        }
    }

    private func beginExternalImport(url: URL,
                                     kind: ExternalImportFileKind,
                                     formatName: String) {
        importWorkTask?.cancel()
        pendingImportRetry = nil
        let fallback = drawingStore.activeLayerID
            ?? drawingStore.layers.first?.id
            ?? DrawingLayer.legacyFallbackID
        let context = ExternalImportWorker.Context(
            existingLayers: drawingStore.layers,
            fallbackLayerID: fallback,
            existingWaypointIDs: waypointStore.waypoints.map(\.id),
            existingDrawingIDs: drawingStore.shapes.map(\.id)
        )
        let encodedContext: Data
        do {
            encodedContext = try JSONEncoder().encode(context)
        } catch {
            importMessage = Messages.displayCouldnTPrepareThisImportMessage(formatName, "").withArgument(1, error.displayMessage)
            return
        }
        let batchKey = UUID().uuidString
        importWorkTask = Task { @MainActor in
            do {
                let payload = try await ExternalImportWorker.prepare(
                    url: url,
                    kind: kind,
                    encodedContext: encodedContext,
                    batchKey: batchKey
                )
                try Task.checkCancellation()
                let batch = try JSONDecoder().decode(
                    GeoJSONImporter.ExternalBatch.self,
                    from: payload.encodedBatch
                )
                let report = ExternalImportCommitter.attempt(
                    ExternalImportCommitProgress(batch: batch),
                    waypointStore: waypointStore,
                    drawingStore: drawingStore
                )
                applyExternalImportReport(report)
            } catch is CancellationError {
                // A replacement import or view teardown intentionally cancelled it.
            } catch {
                pendingImportRetry = nil
                importMessage = Messages.displayCouldnTParseThisFileAsMessage(formatName, "").withArgument(1, error.displayMessage)
            }
        }
    }

    private func applyExternalImportReport(_ report: ExternalImportCommitReport) {
        switch report.state {
        case .completed:
            pendingImportRetry = nil
        case .failedBeforeAnyStore, .partiallyCommitted:
            pendingImportRetry = report.progress
        }
        importMessage = report.pendingMessage
    }

    private func retryPendingExternalImport() {
        guard let progress = pendingImportRetry else { return }
        importMessage = nil
        Task { @MainActor in
            await Task.yield()
            let report = ExternalImportCommitter.attempt(
                progress,
                waypointStore: waypointStore,
                drawingStore: drawingStore
            )
            applyExternalImportReport(report)
        }
    }

    // MARK: - calibration (WP4 s2)

    /// s2.3, the whole start sequence in one place. E1 (after import), E2
    /// (Layers) and E3 (auto-resume) all come through here.
    private func startCalibration(entryID: UUID, resumeSilently: Bool = false) {
        guard !calibration.isCalibrating, mapVM.libraryStatus == .loaded,
              let entry = mapVM.entry(entryID), entry.kind == .pdf, let pdf = entry.pdf,
              let key = entry.contentKey, mapVM.fileStatus(entry) == .ok,
              ImportedMapStates.state(entry, file: .ok) != .unavailable else { return }
        // C2: calibrating the held back map is the user's Open Anyway
        mapVM.resolveSuspectForCalibration(entryID)
        // 1-3: measure, drawing, free draw, selections, previews, menus and quick-add off
        drawingSession.cancel()
        measureSession.cancel()
        drawingsPanelOpen = false
        mapVM.selectedWaypointID = nil
        mapVM.selectedDrawingID = nil
        drawingControlsPreview = nil
        mapPressPoint = nil
        quickSymbolDraft = nil
        // 4-6: heading-up paused (the heading receiver checks), first fix ignored,
        // header on the crosshair. The orientation setting itself isn't touched
        mapVM.calibrationActive = true
        let target = CalibrationTarget(entryID: entryID, contentKey: key, pageIndex: pdf.pageIndex,
                                       pageBox: pdf.pageBox, rotate: pdf.rotate)
        let base: PdfGeoreference
        var wasPreview = false
        if let effective = pdf.effectiveGeoref {
            // 9: georeferenced and not active -> active durably, framed
            if mapVM.activeEntryID != entryID { _ = mapVM.activateLibraryEntry(entryID) }
            base = effective
            var shown = effective
            shown.crop = pdf.pageBox
            _ = mapVM.beginCalibrationDisplay(entry: entry, georef: shown, reframe: false)
        } else {
            // 8: no georef -> a NON durable provisional preview at the camera, framed
            // once. An uncalibrated PDF never becomes a durable basemap (D2-06, D5-02)
            // provisional() always says page 0, the tile renderer opens georef.page
            guard let g = PdfGeoreference.provisional(pageBox: target.pageRect, rotation: pdf.rotate,
                                                      centredOn: mapVM.cameraCentre).map({ p -> PdfGeoreference in
                      var onPage = p
                      onPage.page = pdf.pageIndex
                      return onPage
                  }),
                  mapVM.beginCalibrationDisplay(entry: entry, georef: g, reframe: true) else {
                mapVM.calibrationActive = false
                return
            }
            base = g
            wasPreview = true
        }
        calibrationDisplayWasPreview = wasPreview
        var embeddedDatum: String?
        if let d = pdf.embedded?.datum, !d.isCustom, CalibrationDatumChoice.order.contains(d.id) { embeddedDatum = d.id }
        calibration.start(target: target, entryName: entry.displayName, base: base,
                          seed: pdf.manual?.state ?? CalibrationState(), embeddedDatumID: embeddedDatum,
                          wasPreview: wasPreview, resumeSilently: resumeSilently)
    }

    /// after the session ended (leave, suspend, finish): chrome and heading back
    private func finishCalibrationSession(restoreDisplay: Bool = true) {
        mapVM.calibrationActive = false
        // C4: a suspend (or a commit that published) has nothing to go back to
        if restoreDisplay { mapVM.endCalibrationDisplay() } else { mapVM.clearCalibrationReturn() }
        mapVM.noteDraftsChanged()
        if opsec.mapOrientationMode == .headingUp, let heading = locationService.deviceHeading {
            mapVM.orientMap(to: heading)
        }
    }

    /// Finish (s2.6): ONE library write (manual + active), the camera stays put.
    /// A failed write keeps the session and the draft and offers Retry.
    private func commitCalibration(_ manual: ManualCalibration) {
        guard let t = calibration.target else { return }
        if mapVM.commitCalibration(entryID: t.entryID, manual: manual, contentKey: t.contentKey, pageIndex: t.pageIndex) {
            calibration.didCommit()
            finishCalibrationSession(restoreDisplay: false)
        } else {
            calibration.commitFailed()
        }
    }

    @ViewBuilder
    private var calibrationOverlays: some View {
        if calibration.isCalibrating {
            // [+] [-] on the right edge, +-1 zoom about the centre (s7.7)
            HStack {
                Spacer()
                VStack(spacing: 10) {
                    calibrationZoomButton(+1, symbol: "plus", label: Messages.calibrationZoomIn(), id: "calibration.zoomIn")
                    calibrationZoomButton(-1, symbol: "minus", label: Messages.calibrationZoomOut(), id: "calibration.zoomOut")
                }
                .padding(.trailing, 12)
            }
            .frame(maxHeight: .infinity)
            if calibration.pendingEntry != nil {
                VStack {
                    CalibrationEntryCard(session: calibration, mapVM: mapVM, location: locationService.lastLocation)
                        .padding(.horizontal, 12)
                        .padding(.top, 6)
                    Spacer()
                }
            }
        }
    }

    private func calibrationZoomButton(_ step: Double, symbol: String, label: String, id: String) -> some View {
        Button { mapVM.zoomStepRequests.send(step) } label: {
            Image(systemName: symbol)
                .font(.title2.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .background(.black.opacity(0.85), in: RoundedRectangle(cornerRadius: 14))
                .overlay(RoundedRectangle(cornerRadius: 14).stroke(.white.opacity(0.15)))
        }
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }

    // MARK: - map imports (WP5 s9)

    private func handleImport(_ result: Result<[URL], Error>) {
        importController.importPDF(result)
    }

    #if DEBUG
    /// launch env hooks for device verification, see docs/DEBUG_HOOKS.md
    private func runDebugHooks() {
        if DebugHooks.gridOn { visibility.mgrsGridVisible = true }
        if let url = DebugHooks.importPDF {
            // the same pipeline as the picker, probe and all. A migration still
            // running (or a locked library) means the library isn't Loaded yet:
            // wait for it instead of dropping the hook (OD-F2)
            if mapVM.libraryStatus == .loaded {
                importController.importPDF(url: url, completion: debugApplyCamera)
            } else {
                NSLog("[DebugHooks] import waits for the library to load")
                debugImportPending = url
            }
        } else {
            debugApplyCamera()
        }
    }

    private func debugApplyCamera() {
        guard DebugHooks.camera != nil || DebugHooks.calibrationPoint != nil else { return }
        // after the import has framed the sheet, so ours wins
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            mapVM.isBrowsing = true
            if let point = DebugHooks.calibrationPoint, let display = calibration.display,
               let centre = display.georef.toWGS84(x: point.x, y: point.y) {
                mapVM.exactCameraRequests.send((centre, DebugHooks.camera?.zoom ?? 18, 0))
            } else if let cam = DebugHooks.camera {
                mapVM.exactCameraRequests.send((CLLocationCoordinate2D(latitude: cam.latitude, longitude: cam.longitude),
                                                cam.zoom, cam.heading))
            }
        }
    }
    #endif

    /// Import a local MBTiles raster pyramid as an offline basemap: copied into
    /// app-private, file-protected storage under an opaque name, then added to
    /// the library and shown.
    private func handleMBTilesImport(_ result: Result<[URL], Error>) {
        importController.importMBTiles(result)
    }
}

/// also drawn by SecurityCoverWindows, above any sheet that was open
struct MissionDataUnlockView: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    let detail: String?
    let unlock: () -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 16) {
                Image(systemName: "lock.shield.fill").font(.system(size: 46)).foregroundStyle(.green)
                Text(L10n.text("Mission data locked")).font(.title2.bold()).foregroundStyle(.white)
                Text(detail ?? L10n.text("Authenticate to load and edit encrypted mission data."))
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button(L10n.text("Unlock mission data"), action: unlock)
                    .buttonStyle(.borderedProminent)
            }
            .padding(28)
        }
    }
}

/// With no offline pack and online basemaps gated off, the app draws
/// nothing at all. Explain that, b/c a blank map with no message reads as a
/// broken app rather than a deliberate OPSEC posture.
private struct NoBasemapNotice: View {
    @ObservedObject private var appLanguage = AppLanguage.shared
    var body: some View {
        VStack(spacing: 6) {
            Text(L10n.text("No basemap")).font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
            Text(Messages.onlineBasemapsDisabled())
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

private struct QuickSymbolDraft: Identifiable {
    let id = UUID()
    let coordinate: CLLocationCoordinate2D
    let layerID: UUID
    let scale: Double
}

#Preview {
    ContentView(store: StoreManager())
        .preferredColorScheme(.dark)
}

/// Import alerts: errors, the rejected-georef prompt, the one-time migration
/// and interrupted-import notices. Pulled out to keep ContentView's body
/// inside the type checker's budget.
private struct MapImportAlerts: ViewModifier {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var controller: MapImportController
    @Binding var migrationUncalibratedName: String?
    @Binding var libraryRecoveredNotice: Bool
    @Binding var importInterrupted: Bool
    var onCalibrate: (UUID) -> Void

    func body(content: Content) -> some View {
        content
            .alert(L10n.text("Import"),
                   isPresented: Binding(get: { controller.error != nil }, set: { if !$0 { controller.error = nil } }),
                   presenting: controller.error) { _ in
                Button(Messages.acknowledge(), role: .cancel) { controller.error = nil }
            } message: { e in
                Text(e.text)
            }
            .alert(Messages.pdfGeorefRejectedTitle(),
                   isPresented: Binding(get: { controller.rejectedPrompt != nil },
                                        set: { if !$0 { controller.rejectedPrompt = nil } }),
                   presenting: controller.rejectedPrompt) { prompt in
                Button(Messages.mapImportCalibrateNow()) { onCalibrate(prompt.entryID) }
                Button(Messages.mapImportLater(), role: .cancel) {}
            } message: { prompt in
                Text(prompt.message)
            }
            .alert(Messages.mapLibrarySection(),
                   isPresented: Binding(get: { migrationUncalibratedName != nil },
                                        set: { if !$0 { migrationUncalibratedName = nil } }),
                   presenting: migrationUncalibratedName) { _ in
                Button(Messages.acknowledge(), role: .cancel) { migrationUncalibratedName = nil }
            } message: { name in
                Text(Messages.mapMigrationUncalibrated(name))
            }
            .alert(Messages.mapLibrarySection(), isPresented: $libraryRecoveredNotice) {
                Button(Messages.acknowledge(), role: .cancel) {}
            } message: {
                Text(Messages.mapLibraryRecoveredNotice())
            }
            // E1: changing the page throws the hand calibration away, ask first
            .alert(Messages.mapActionChoosePage(),
                   isPresented: Binding(get: { controller.changePageConfirm != nil },
                                        set: { if !$0 { controller.changePageConfirm = nil } })) {
                Button(Messages.mapActionChoosePage(), role: .destructive) { controller.confirmChangePage() }
                Button(L10n.text("Cancel"), role: .cancel) { controller.changePageConfirm = nil }
            } message: {
                Text(Messages.mapChangePageConfirm())
            }
            .alert(L10n.text("Import"), isPresented: $importInterrupted) {
                Button(Messages.acknowledge(), role: .cancel) {}
            } message: {
                Text(Messages.mapImportInterrupted())
            }
    }
}

/// Calibration dialogs (s2.6, s2.7, s2.3 resume): leave, finish with
/// warnings, resume, save failed. All bound to the session phase.
private struct CalibrationAlerts: ViewModifier {
    @ObservedObject private var appLanguage = AppLanguage.shared
    @ObservedObject var session: CalibrationSession
    var onFinish: (ManualCalibration) -> Void
    var onLeave: (_ keepDraft: Bool) -> Void

    private func binding(_ match: @escaping (CalibrationSession.Phase) -> Bool) -> Binding<Bool> {
        Binding(get: { match(session.phase) },
                set: { if !$0, match(session.phase) { session.phase = .placing } })
    }

    private var confirmReasons: [CalibrationConfirmReason] {
        if case .finishConfirm(let r) = session.phase { return r }
        return []
    }

    func body(content: Content) -> some View {
        content
            // an alert, not a confirmationDialog: iOS 26 drops the cancel button
            // from the dialog and the contract wants all three on screen
            .alert(Messages.calibrationLeaveTitle(), isPresented: binding { $0 == .leaveDialog }) {
                Button(Messages.calibrationLeaveKeep()) { onLeave(true) }
                Button(Messages.calibrationLeaveDiscard(), role: .destructive) { onLeave(false) }
                Button(Messages.calibrationLeaveContinue(), role: .cancel) { session.continueCalibrating() }
            } message: {
                Text(Messages.calibrationLeaveMessage())
            }
            .alert(Messages.calibrationFinishConfirmTitle(),
                   isPresented: binding { if case .finishConfirm = $0 { return true } else { return false } }) {
                Button(Messages.calibrationAddMore(), role: .cancel) { session.phase = .placing }
                Button(Messages.calibrationFinishAnyway()) {
                    if let manual = session.finishAnyway() { onFinish(manual) }
                }
            } message: {
                Text(session.report.confirmMessages.map(\.text).joined(separator: "\n\n"))
            }
            .alert(Messages.calibrationResumeTitle(), isPresented: binding { $0 == .resumePrompt }) {
                Button(Messages.calibrationResume()) { session.resume() }
                Button(Messages.calibrationStartOver(), role: .destructive) { session.startOver() }
            } message: {
                let d = session.resumeDraft
                let age = d.map { draft -> String in
                    let f = RelativeDateTimeFormatter()
                    f.locale = DisplayFormat.currentLocale   // app language, not the phone's
                    return f.localizedString(for: Date(timeIntervalSince1970: Double(draft.updatedAtMs) / 1000), relativeTo: Date())
                } ?? ""
                Text(Messages.calibrationResumeMessage(Messages.calibrationPointCount(d?.points.count ?? 0), age))
            }
            .alert(L10n.text("Map change not saved"), isPresented: binding { $0 == .saveFailed }) {
                Button(L10n.text("Retry")) {
                    session.phase = .placing
                    if let manual = session.manualCalibration() { onFinish(manual) }
                }
                Button(L10n.text("Not Now"), role: .cancel) { session.phase = .placing }
            } message: {
                Text(Messages.calibrationSaveFailed())
            }
    }
}
