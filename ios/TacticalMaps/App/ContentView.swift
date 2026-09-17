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
                                   preferredExtension: String? = nil,
                                   fileManager: FileManager = .default) throws -> URL {
        let dir = try importedMapsDirectory(fileManager: fileManager)
        let dest = try copy(
            source,
            into: dir,
            maximumBytes: maximumBytes,
            preferredExtension: preferredExtension,
            fileManager: fileManager
        )
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
                     preferredExtension: String? = nil,
                     fileManager: FileManager = .default,
                     chunkSize: Int = 1_048_576,
                     beforeCreate: ((URL) throws -> Void)? = nil,
                     afterChunk: (() throws -> Void)? = nil) throws -> URL {
        try Task.checkCancellation()
        let keys: Set<URLResourceKey> = [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey]
        let before = try source.resourceValues(forKeys: keys)
        guard before.isRegularFile == true, before.isSymbolicLink != true,
              let size = before.fileSize, size >= 0, size <= maximumBytes else {
            throw CocoaError(.fileReadTooLarge)
        }
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let destination = uniqueDestination(
            for: source,
            in: directory,
            preferredExtension: preferredExtension,
            fileManager: fileManager
        )
        var ownsDestination = false
        do {
            // Create without replacing an import that won the same-name race.
            // The chunk loop provides prompt task cancellation for large maps;
            // any failure removes the partial private copy below.
            try beforeCreate?(destination)
            try Data().write(to: destination, options: .withoutOverwriting)
            ownsDestination = true
            let input = try FileHandle(forReadingFrom: source)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: destination)
            defer { try? output.close() }
            let readSize = max(1, chunkSize)
            var copiedBytes = 0
            while true {
                try Task.checkCancellation()
                guard let chunk = try input.read(upToCount: readSize), !chunk.isEmpty else { break }
                guard copiedBytes <= size,
                      chunk.count <= size - copiedBytes,
                      copiedBytes <= maximumBytes,
                      chunk.count <= maximumBytes - copiedBytes else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                try output.write(contentsOf: chunk)
                copiedBytes += chunk.count
                try afterChunk?()
            }
            try Task.checkCancellation()
            guard copiedBytes == size else { throw CocoaError(.fileReadCorruptFile) }
            try output.synchronize()
            let copied = try destination.resourceValues(forKeys: keys)
            guard copied.isRegularFile == true, copied.isSymbolicLink != true,
                  let copiedSize = copied.fileSize, copiedSize == size, copiedSize <= maximumBytes else {
                throw CocoaError(.fileReadCorruptFile)
            }
        } catch {
            if ownsDestination { try? fileManager.removeItem(at: destination) }
            throw error
        }
        return destination
    }

    private static func uniqueDestination(for source: URL,
                                          in directory: URL,
                                          preferredExtension: String?,
                                          fileManager: FileManager) -> URL {
        let ext = preferredExtension ?? source.pathExtension
        let rawStem = source.deletingPathExtension().lastPathComponent
        let stem = rawStem.isEmpty ? L10n.text("Imported Map") : rawStem

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
        let values = try url.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else {
            throw CocoaError(.fileReadUnsupportedScheme)
        }
        guard let size = values.fileSize, size >= 0, size <= maximumBytes else {
            throw GeoJSONImporter.ImportError.limitExceeded(L10n.text("file is over %1$@ MB", maximumBytes / 1_048_576))
        }
        let data = try Data(contentsOf: url, options: [.mappedIfSafe, .uncached])
        guard data.count <= maximumBytes else {
            throw GeoJSONImporter.ImportError.limitExceeded(L10n.text("file changed while it was being read"))
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

struct PDFBoundsPayload: Sendable {
    struct Affine: Sendable {
        let a: Double
        let b: Double
        let c: Double
        let d: Double
        let e: Double
        let f: Double
    }

    let south: Double
    let west: Double
    let north: Double
    let east: Double
    let cropX: Double?
    let cropY: Double?
    let cropWidth: Double?
    let cropHeight: Double?
    let affine: Affine?

    init(_ bounds: GeoPDFReader.Bounds) {
        south = bounds.southWest.latitude
        west = bounds.southWest.longitude
        north = bounds.northEast.latitude
        east = bounds.northEast.longitude
        cropX = bounds.pdfCropRect.map { Double($0.origin.x) }
        cropY = bounds.pdfCropRect.map { Double($0.origin.y) }
        cropWidth = bounds.pdfCropRect.map { Double($0.size.width) }
        cropHeight = bounds.pdfCropRect.map { Double($0.size.height) }
        affine = bounds.placementAffine.map {
            Affine(a: $0.a, b: $0.b, c: $0.c, d: $0.d, e: $0.e, f: $0.f)
        }
    }

    func makeBounds() -> GeoPDFReader.Bounds {
        let crop: CGRect?
        if let cropX, let cropY, let cropWidth, let cropHeight {
            crop = CGRect(x: cropX, y: cropY, width: cropWidth, height: cropHeight)
        } else {
            crop = nil
        }
        let placement = affine.map {
            AffineTransform2D(a: $0.a, b: $0.b, c: $0.c, d: $0.d, e: $0.e, f: $0.f)
        }
        return GeoPDFReader.Bounds(
            southWest: CLLocationCoordinate2D(latitude: south, longitude: west),
            northEast: CLLocationCoordinate2D(latitude: north, longitude: east),
            pdfCropRect: crop,
            placementAffine: placement
        )
    }
}

struct PDFRectPayload: Sendable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double

    init(_ rect: CGRect) {
        x = Double(rect.origin.x)
        y = Double(rect.origin.y)
        width = Double(rect.size.width)
        height = Double(rect.size.height)
    }

    var rect: CGRect { CGRect(x: x, y: y, width: width, height: height) }
}

enum ImportedMapWorker {
    struct PDFPayload: Sendable {
        let destination: URL
        let geoBounds: PDFBoundsPayload?
        let mediaBox: PDFRectPayload
        let contentKey: String
        let performedWorkOffMainThread: Bool
    }

    struct MBTilesPayload: Sendable {
        let destination: URL
        let metadata: MBTilesStore.Metadata
        let performedWorkOffMainThread: Bool
    }

    enum WorkerError: LocalizedError {
        case invalidMBTiles

        var errorDescription: String? {
            switch self {
            case .invalidMBTiles: return L10n.text("Couldn't open this file as an MBTiles map.")
            }
        }
    }

    static func preparePDF(url: URL) async throws -> PDFPayload {
        let worker = Task.detached(priority: .userInitiated) {
            let offMainThread = importWorkerIsOffMainThread()
            var destination: URL?
            do {
                try Task.checkCancellation()
                let prepared = try SecurityScopedImportAccess.withCoordinatedRead(of: url) { coordinatedURL in
                    let copied = try PDFMapImporter.copyAndValidate(coordinatedURL)
                    destination = copied
                    try Task.checkCancellation()
                    guard let document = PDFDocument(url: copied),
                          let page = document.page(at: 0) else {
                        throw PDFMapImportError.invalidPDF
                    }
                    let mediaBox = PDFRectPayload(page.bounds(for: .mediaBox))
                    let geoBounds = GeoPDFReader.bounds(from: copied).map(PDFBoundsPayload.init)
                    guard let contentKey = PDFSessionStore.contentKey(for: copied) else {
                        throw PDFMapImportError.invalidPDF
                    }
                    return (copied, geoBounds, mediaBox, contentKey)
                }
                try Task.checkCancellation()
                return PDFPayload(destination: prepared.0,
                                  geoBounds: prepared.1,
                                  mediaBox: prepared.2,
                                  contentKey: prepared.3,
                                  performedWorkOffMainThread: offMainThread)
            } catch {
                if let destination { try? FileManager.default.removeItem(at: destination) }
                throw error
            }
        }
        return try await withTaskCancellationHandler(
            operation: { try await worker.value },
            onCancel: { worker.cancel() }
        )
    }

    static func prepareMBTiles(url: URL) async throws -> MBTilesPayload {
        let worker = Task.detached(priority: .userInitiated) {
            let offMainThread = importWorkerIsOffMainThread()
            var destination: URL?
            do {
                try Task.checkCancellation()
                let prepared = try SecurityScopedImportAccess.withCoordinatedRead(of: url) { coordinatedURL in
                    let copied = try ImportedMapFileCopier.copyToImportedMaps(
                        coordinatedURL,
                        maximumBytes: ImportedMapFileCopier.maxMBTilesBytes,
                        preferredExtension: "mbtiles"
                    )
                    destination = copied
                    try Task.checkCancellation()
                    guard let store = MBTilesStore(url: copied) else {
                        throw WorkerError.invalidMBTiles
                    }
                    return (copied, store.metadata)
                }
                try Task.checkCancellation()
                return MBTilesPayload(destination: prepared.0,
                                      metadata: prepared.1,
                                      performedWorkOffMainThread: offMainThread)
            } catch {
                if let destination { try? FileManager.default.removeItem(at: destination) }
                throw error
            }
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
    @StateObject private var drawingSession  = DrawingSessionViewModel()
    @StateObject private var measureSession  = MeasureSession()
    @StateObject private var visibility      = LayerVisibility()
    @StateObject private var mapVM           = MapViewModel()
    @StateObject private var calibration     = CalibrationSession()
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
    @State private var importMessage: String? = nil
    @State private var pendingImportRetry: ExternalImportCommitProgress?
    @State private var importWorkTask: Task<Void, Never>?
    @State private var missionUnlockError: String? = nil
    @State private var missionMutationMessage: String? = nil
    @State private var dataKeyEpoch = 0
    /// Brief non-blocking status toast for sync and map controls.
    @State private var syncToast: String? = nil
    @State private var headingWatchdogTask: Task<Void, Never>?
    /// Share sheet URL for the combined mission-object GeoJSON action.
    @State private var missionObjectExportURL: URL? = nil
    @State private var showWaypointSheet   = false
    @State private var showDrawingsSheet   = false   // "All Drawings" list
    @State private var showLayersSheet     = false
    @State private var showExportSheet     = false
    @State private var showSearchSheet     = false
    @State private var showAboutSheet      = false
    @State private var drawingsPanelOpen   = false   // inline panel below hamburger
    @State private var quickSymbolDraft: QuickSymbolDraft? = nil
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
            recordingError: { trackRecorder.persistError }
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

    /// Basemap status shown in the MGRS banner (replaces Live Location/Map Centre).
    private var basemapLabel: String? {
        if importedMapLoaded { return L10n.text("Offline basemap") }
        if onlineTilesActive { return L10n.text("Online basemap") }
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
            && syncManager.status == .connected
            && LiveLocationPermissionPolicy.shouldStartUpdates(
                for: locationService.authorisationStatus
            )
    }

    private var syncForegroundReady: Bool {
        scenePhase == .active
            && !appLockOverlayActive
            && (!DataKey.isAuthBound || DataKey.isUnlocked)
            && !waypointStore.locked
            && !drawingStore.locked
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
                    peers: syncManager.peers,
                    onPeerTap: presentDirectChat,
                    onMutationError: { missionMutationMessage = $0 }
                )
                .ignoresSafeArea()
                .overlay {
                    if basemapBlank { NoBasemapNotice() }
                }

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
    }

    private var lifecycleContent: some View {
        baseMapContent
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
            if DataKey.isAuthBound && !DataKey.isUnlocked {
                lockChatUIAndSecrets()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: AppLock.stateChanged)) { note in
            guard let value = note.object as? NSNumber else { return }
            appLockOverlayActive = value.boolValue
            if value.boolValue {
                lockChatUIAndSecrets()
            } else {
                restoreChatIfSecurityAllows()
            }
            refreshUnitSyncLifecycle()
        }
        .alert(L10n.text("Mission Object Not Saved"), isPresented: Binding(
            get: { missionMutationMessage != nil },
            set: { if !$0 { missionMutationMessage = nil } }
        )) {
            Button(L10n.text("OK"), role: .cancel) { missionMutationMessage = nil }
        } message: {
            Text(missionMutationMessage ?? L10n.text("The change could not be saved. Check available storage, then try again."))
        }
        .alert(L10n.text("Track Recording"), isPresented: Binding(
            get: { trackRecorder.persistError != nil && !trackRecorder.requiresUnlock },
            set: { if !$0 { trackRecorder.persistError = nil } }
        )) {
            if !trackRecorder.points.isEmpty || trackRecorder.recovered {
                Button(L10n.text("Discard Saved Track"), role: .destructive) { trackRecorder.discard() }
            }
            Button(L10n.text("OK"), role: .cancel) { trackRecorder.persistError = nil }
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
            if LiveLocationPermissionPolicy.shouldRequestOnInitialAppearance(
                for: locationService.authorisationStatus
            ) {
                locationService.requestAuthorisation()
            } else if LiveLocationPermissionPolicy.shouldStartUpdates(
                for: locationService.authorisationStatus
            ) {
                locationService.start()
            }
            restoreActiveBasemap()
        }
        .onReceive(locationService.$lastLocation.compactMap { $0 }) { loc in
            mapVM.userLocationDidUpdate(
                loc,
                resetOrientation: opsec.mapOrientationMode == .northUp
            )
        }
        .onReceive(locationService.$deviceHeading.compactMap { $0 }) { heading in
            if opsec.mapOrientationMode == .headingUp {
                mapVM.orientMap(to: heading)
            }
        }
        .onChange(of: locationService.deviceHeading) { _ in
            refreshHeadingWatchdog()
        }
    }

    private var sheetContent: some View {
        lifecycleContent
        .sheet(isPresented: $showWaypointSheet) {
            WaypointListSheet(waypointStore: waypointStore,
                              drawingStore: drawingStore,
                              mapVM: mapVM)
                .padSheetSizing()
        }
        .sheet(item: $quickSymbolDraft) { draft in
            WaypointCreationSheet(
                waypointStore: waypointStore,
                defaultCoordinate: draft.coordinate,
                defaultScale: draft.scale,
                defaultLayerID: draft.layerID
            )
        }
        .sheet(isPresented: $showDrawingsSheet) {
            DrawingsSheet(drawingStore: drawingStore, session: drawingSession)
                .padSheetSizing()
        }
        .sheet(isPresented: $showLayersSheet) {
            LayersSheet(visibility: visibility,
                        mapVM: mapVM,
                        drawingStore: drawingStore,
                        waypointStore: waypointStore,
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
        .sheet(isPresented: $showSyncSheet, onDismiss: {
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
        .sheet(item: $chatRoute) { route in
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
            } else {
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
                    error: trackRecorder.persistError
                )
            }
        }
        .sheet(isPresented: $showSearchSheet) {
            SearchSheet(
                mapVM: mapVM,
                waypointStore: waypointStore,
                drawingStore: drawingStore
            )
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

    var body: some View {
        importerContent
        .background(
            EmptyView()
                .alert(L10n.text("Map change not saved"),
                       isPresented: Binding(
                        get: { mapVM.mapSelectionPersistenceIssue != nil },
                        set: { if !$0 { mapVM.dismissMapSelectionPersistenceIssue() } }
                       ),
                       presenting: mapVM.mapSelectionPersistenceIssue) { _ in
                    Button(L10n.text("Retry")) {
                        if mapVM.retryMapSelectionPersistence(), calibration.isCalibrating {
                            calibration.cancel()
                        }
                    }
                    Button(L10n.text("Not Now"), role: .cancel) {
                        mapVM.dismissMapSelectionPersistenceIssue()
                    }
                } message: { issue in
                    Text(issue.message)
                }
        )
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
                Button(L10n.text("OK"), role: .cancel) { importMessage = nil }
            }
        } message: { msg in
            Text(msg)
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
        .sheet(isPresented: Binding(
            get: { missionObjectExportURL != nil },
            set: { if !$0 { missionObjectExportURL = nil } }
        )) {
            if let url = missionObjectExportURL {
                ShareSheetView(activityItems: [url], title: MissionObjectExport.shareTitle)
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
                elevation: mapVM.centreElevation ?? locationService.lastAltitude,
                elevationIsApproximate: mapVM.centreElevationIsApproximate,
                coordinate: headerCoordinate,
                onDropPin: { coord, displayedCoordinate in
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
                        missionMutationMessage = L10n.text("The dropped symbol was not added. %1$@ Check available storage, then try again.", error.localizedDescription)
                    }
                }
            )
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
                        }
                    )

                    if syncManager.room?.hasPrefix("3:") == true {
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
                    }

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
                    CompassChip(
                        heading: mapVM.heading,
                        orientationMode: opsec.mapOrientationMode,
                        headingAvailable: locationService.isHeadingAvailable,
                        northReference: locationService.headingNorthReference,
                        onTap: handleCompassTap
                    )
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
                    saveNewDrawing(shape)
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

    private func saveNewDrawing(_ shape: DrawingShape) {
        do {
            _ = try drawingStore.addDurably(shape)
        } catch {
            missionMutationMessage = L10n.text("The drawing was not added. %1$@ Check available storage, then draw it again.", error.localizedDescription)
        }
    }

    /// Open the fast symbol builder on a layer whose contents are visible.
    /// Preserve an already-visible active layer; if it is hidden, prefer another
    /// visible layer without changing the user's active-layer selection.
    private func beginQuickSymbolCreation() {
        drawingsPanelOpen = false
        mapVM.selectedWaypointID = nil
        mapVM.selectedDrawingID = nil
        visibility.waypointsVisible = true
        guard let layerID = visibleLayerIDForQuickSymbol() else { return }
        quickSymbolDraft = QuickSymbolDraft(
            coordinate: mapVM.cameraCentre,
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
                missionMutationMessage = L10n.text("The symbol layer could not be made visible. %1$@ Check available storage, then try again.", error.localizedDescription)
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
                missionMutationMessage = L10n.text("The symbol layer could not be made visible. %1$@ Check available storage, then try again.", error.localizedDescription)
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
            missionMutationMessage = L10n.text("A symbol layer could not be restored. %1$@ Check available storage, then try again.", error.localizedDescription)
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
            missionUnlockError = error.localizedDescription
        }
    }

    /// Restore the map that was actually selected, independently of the
    /// imported-PDF library. On an upgrade with no active-choice descriptor we
    /// keep the usable online default; the saved PDF remains available from
    /// Layers, but is no longer incorrectly assumed to have been active.
    private func restoreActiveBasemap() {
        guard let source = mapVM.restoreActiveMapSelection() else { return }
        NSLog("[MapVM] restored active basemap -> kind=\(source.kind)")
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
            missionObjectExportURL = url
        } catch {
            importMessage = L10n.text("Export failed: %1$@", error.localizedDescription)
        }
    }

    private func handleGeoJSONImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = L10n.text("Import failed: %1$@", err.localizedDescription)
        case .success(let urls):
            guard let url = urls.first else { return }
            beginExternalImport(url: url, kind: .geoJSON, formatName: "GeoJSON")
        }
    }

    private func handleKMLImport(_ result: Result<[URL], Error>) {
        switch result {
        case .failure(let err):
            importMessage = L10n.text("Import failed: %1$@", err.localizedDescription)
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
            importMessage = L10n.text("Couldn't prepare this %1$@ import: %2$@", formatName, error.localizedDescription)
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
                importMessage = L10n.text("Couldn't parse this file as %1$@: %2$@", formatName, error.localizedDescription)
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
        importMessage = report.message
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

    private func startCalibration() {
        guard let pdfSource = mapVM.mapSource as? PDFMapSource else { return }
        calibration.start(for: pdfSource)
    }

    private func finishCalibration() {
        guard let result = calibration.finish(),
              let source = calibration.source else { return }
        // Build fresh source so MapContainerView rebuilds overlay
        // (sync logic keys on source.id).
        let newSource = PDFMapSource(
            url: source.url,
            bounds: nil,
            fromGeoPDF: false,
            contentKey: source.contentKey
        )
        newSource.applyCalibration(
            transform: result.transform,
            fiduciaries: calibration.fiduciaries
        )
        let bounds = newSource.bounds
        guard mapVM.selectMapSource(newSource) else { return }
        calibration.cancel()
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
                importMessage = L10n.text("Import failed: %1$@", error.localizedDescription)
            }
            return
        }
        let cameraAtImport = mapVM.cameraCentre
        importWorkTask?.cancel()
        importWorkTask = Task { @MainActor in
            var copiedURL: URL?
            do {
                let payload = try await ImportedMapWorker.preparePDF(url: url)
                copiedURL = payload.destination
                try Task.checkCancellation()
                let parsedBounds = payload.geoBounds?.makeBounds()
                let bounds = parsedBounds ?? GeoPDFReader.fallbackBounds(centeredOn: cameraAtImport)
                let source = PDFMapSource(
                    url: payload.destination,
                    bounds: bounds,
                    fromGeoPDF: parsedBounds != nil,
                    preflightMediaBox: payload.mediaBox.rect,
                    contentKey: payload.contentKey
                )
                // If PDF was calibrated in a previous session, restore
                // fiduciaries + affine so it re-imports already aligned.
                PDFSessionStore.applyCalibrationIfKnown(to: source)
                guard mapVM.selectMapSource(source) else {
                    // The retry transition owns this private copy now.
                    copiedURL = nil
                    return
                }
                copiedURL = nil
            } catch is CancellationError {
                if let copiedURL { try? FileManager.default.removeItem(at: copiedURL) }
            } catch let error as PDFMapImportError {
                importMessage = error.localizedDescription
            } catch {
                if let copiedURL { try? FileManager.default.removeItem(at: copiedURL) }
                importMessage = L10n.text("Couldn't import this PDF map: %1$@", error.localizedDescription)
            }
        }
    }

    /// Import local MBTiles raster pyramid as offline basemap. Copies the
    /// picked file into app-private, file-protected storage (not Documents),
    /// installs an OfflineTileMapSource served with no network.
    private func handleMBTilesImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure(let error) = result {
                importMessage = L10n.text("Import failed: %1$@", error.localizedDescription)
            }
            return
        }

        importWorkTask?.cancel()
        importWorkTask = Task { @MainActor in
            var copiedURL: URL?
            do {
                let payload = try await ImportedMapWorker.prepareMBTiles(url: url)
                let destination = payload.destination
                copiedURL = destination
                try Task.checkCancellation()
                let source = OfflineTileMapSource(
                    prevalidatedURL: destination,
                    metadata: payload.metadata
                )
                guard mapVM.selectMapSource(source) else {
                    // Preserve the validated private copy for the Retry action.
                    copiedURL = nil
                    return
                }
                copiedURL = nil
                importMessage = L10n.text("Loaded offline tiles: %1$@.", source.displayName)
            } catch is CancellationError {
                if let copiedURL { try? FileManager.default.removeItem(at: copiedURL) }
            } catch {
                if let copiedURL { try? FileManager.default.removeItem(at: copiedURL) }
                importMessage = L10n.text("Couldn't import this MBTiles map: %1$@", error.localizedDescription)
            }
        }
    }
}

private struct MissionDataUnlockView: View {
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
            Text(L10n.text("Online basemaps are off. Import an offline map pack, or enable ")
                 + L10n.text("online basemap tiles in Settings, Privacy & OPSEC."))
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
