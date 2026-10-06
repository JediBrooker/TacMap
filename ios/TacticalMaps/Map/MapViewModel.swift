import Foundation
import CoreLocation
import MapKit
import Combine

struct MapSelectionPersistenceIssue: Identifiable, Equatable {
    let id: UUID
    let pendingMessage: LocalizedMessage
    var message: String { pendingMessage.text }
}

enum LibraryLoadStatus: Equatable {
    /// restore hasn't run yet (or migration is still going)
    case unknown
    case loaded
    case locked
    case corrupt
}

/// Everything the map selection touches outside memory, injectable for tests.
struct LibraryDependencies {
    var load: () -> SafeStore.Load<LibraryState>
    var write: (LibraryState) throws -> Void
    var reconcile: (LibraryState) -> Bool
    var legacyPresent: () -> Bool
    var clearLegacy: () -> Void
    /// L2: the live legacy stores D8 may clear (never a .corrupt-* copy)
    var clearableLegacyPresent: () -> Bool = { false }
    /// L7: map files in the managed dirs nothing in flight owns. An Empty library
    /// next to them is a pending adoption, never an authoritative first launch
    var managedFiles: () -> Bool = { false }
    var fileStatus: (ImportedMapEntry) -> ImportedMapFileStatus
    var fileURL: (ImportedMapEntry) -> URL?
    /// SHA-256 the file on a utility queue, true when it still matches contentKey
    var verify: (URL, String, @escaping (Bool) -> Void) -> Void
    var unlink: (URL) -> Void
    var drafts: CalibrationDraftStoring
    var sweepBackup: () -> Void
    var recoverInterruptedImport: () -> Bool
    /// R3-2 bake only sweep, keeping these names. Only ever handed the names of
    /// an authoritative library read
    var sweepBakes: (Set<String>) -> Bool = { _ in false }
    /// Remove Offline Tiles / Delete: the bake file + sidecars, R3-5 names only
    var removeBakeFile: (PDFBakeRecord) -> Void = { _ in }
    /// S2: the candidate library rebuilt from the files on disk (slow, off main)
    var rebuild: (Set<URL>) -> LibraryState = { ImportedMapLibraryRecovery.rebuild(inFlight: $0) }
    /// S2: the rebuild's one write, never on top of unread bytes
    var writeRebuilt: (LibraryState) throws -> Void = ImportedMapLibrary.writeRebuilt
    /// where the slow rebuild runs, tests make it synchronous
    var background: (@escaping () -> Void) -> Void = { DispatchQueue.global(qos: .userInitiated).async(execute: $0) }
    var foreground: (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) }

    static let live = LibraryDependencies(
        load: ImportedMapLibrary.load,
        write: ImportedMapLibrary.write,
        reconcile: { ImportedMapLibrary.reconcile($0) },
        legacyPresent: { ImportedMapLibraryMigration.legacyPresent },
        clearLegacy: ImportedMapLibraryMigration.clearLegacy,
        clearableLegacyPresent: { ImportedMapLibraryMigration.clearableLegacyPresent },
        managedFiles: { ImportedMapLibraryRecovery.managedFilesPresent() },
        fileStatus: ImportedMapLibrary.fileStatus,
        fileURL: ImportedMapLibrary.fileURL,
        verify: { url, key, done in
            DispatchQueue.global(qos: .utility).async {
                let ok = PDFSessionStore.contentKey(for: url) == key
                DispatchQueue.main.async { done(ok) }
            }
        },
        unlink: { ImportedMapStorage.unlink($0) },
        drafts: CalibrationDraftStore.shared,
        sweepBackup: { DispatchQueue.global(qos: .utility).async { ImportedMapStorage.sweepBackupExclusion() } },
        recoverInterruptedImport: MapImportPipeline.recoverInterruptedImport,
        sweepBakes: { names in
            ManagedImportedMapFileLifecycle.sweepUnreferencedBakes(in: ImportedMapLibrary.offlineTilesDirectory,
                                                                   keepingNames: names)
        },
        removeBakeFile: { record in
            ManagedImportedMapFileLifecycle.removeGeneratedBake(record, in: ImportedMapLibrary.offlineTilesDirectory)
        }
    )
}

/// Owns map camera state, browse-mode toggle, MGRS readout, compass heading,
/// and crosshair-elevation lookups.
///
/// Browse mode = user panned/zoomed away from their position. While
/// browsing the header reads the map centre, otherwise reads user location.
final class MapViewModel: ObservableObject {

    // MARK: - Published state

    @Published var cameraCentre: CLLocationCoordinate2D = .init(latitude: 0, longitude: 0)
    @Published var heading: CLLocationDirection = 0
    @Published var isBrowsing: Bool = false
    /// Default basemap: Esri Satellite when we have a key, else the one style
    /// that needs none (OpenTopoMap) so a keyless dev build still shows a map.
    /// The native Apple basemap is no longer a selectable source.
    @Published private(set) var mapSource: MapSource
    @Published private(set) var mapSelectionPersistenceIssue: MapSelectionPersistenceIssue?

    /// Latest terrain-elevation reading for cameraCentre (metres + staleness).
    /// Fetched async from Open-Meteo via ElevationService. Offline-resilient -
    /// when network drops it holds the nearest cached height marked stale
    /// instead of going blank.
    @Published var centreElevationReading: ElevationReading? = nil

    /// Metres ASL for current centre, nil if unknown. Just a convenience
    /// so callers can keep reading a plain Double?.
    var centreElevation: Double? { centreElevationReading?.metres }

    /// True when centreElevation is an offline fallback, not a fresh DEM
    /// lookup. HUD prefixes with "~".
    var centreElevationIsApproximate: Bool { centreElevationReading?.isStale ?? false }

    /// Currently-selected waypoint (any kind: generic, military, or
    /// control measure). Set by map's didSelect. Drives the floating
    /// controls card in ContentView. nil = nothing selected.
    @Published var selectedWaypointID: UUID? = nil

    /// Selected drawing (polyline/polygon/point). Mutually exclusive
    /// with selectedWaypointID - setting one clears the other in
    /// ContentView's tap handler. Drives DrawingControlsCard.
    @Published var selectedDrawingID: UUID? = nil

    /// Current map metres-per-point (smaller = more zoomed in). Updated
    /// by MapContainerView.Coordinator on camera change. Drives
    /// defaultControlMeasureScale so new tactical symbols enter at a
    /// screen-relative size matching current zoom.
    @Published var currentMetresPerPoint: Double = 1.0

    /// Screen positions for every waypoint (MKMapView coord space, same
    /// as SwiftUI overlay since both fill the screen). Republished on
    /// every camera change. TacticalSymbolOverlay reads this to place
    /// each symbol view.
    @Published var waypointScreenPositions: [UUID: CGPoint] = [:]

    /// Zoom-derived scale factor (same value coordinator applies to
    /// symbol transform). SwiftUI overlay multiplies by waypoint.scale
    /// to get display size.
    @Published var zoomScaleFactor: CGFloat = 1.0

    /// Bridge from MapContainerView so SwiftUI overlay can convert
    /// screen points (e.g. end of a drag) back to geo coords without
    /// needing direct MKMapView access.
    var screenToCoordinate: ((CGPoint) -> CLLocationCoordinate2D)?

    /// Scale value for newly-placed tactical control measures so they
    /// render at roughly 10% of screen height at current zoom. Symbol
    /// keeps its geo footprint as user zooms in/out.
    ///
    /// Math: renderer produces 68pt bitmap (64 base + 2*2 halo).
    /// Transform = waypoint.scale * (1/metresPerPoint). We want ~80pt
    /// final width (~10% of 800pt screen), so:
    ///   waypoint.scale = (80/68) * metresPerPoint ~ 1.18 * metresPerPoint
    var defaultControlMeasureScale: Double {
        let raw = 1.18 * currentMetresPerPoint
        // Clamp to the slider range so the default is always editable.
        return max(0.1, min(raw, 20.0))
    }

    // MARK: - Camera signal channels

    let cameraRequests     = PassthroughSubject<MKCoordinateRegion, Never>()
    let resetNorthRequests = PassthroughSubject<Void, Never>()
    let headingRequests    = PassthroughSubject<CLLocationDirection, Never>()
    /// exact centre/zoom/heading, for the debug camera hook (zoom still clamped by the view)
    let exactCameraRequests = PassthroughSubject<(center: CLLocationCoordinate2D, zoom: Double, heading: Double), Never>()

    // MARK: - Imported PDF rendering (WP2)

    /// live PDF tile source + render status for the header
    let pdfRuntime = PDFMapRuntime()

    /// launch decided not to auto-draw this map (it was being drawn when the
    /// app died). The durable selection still points at it.
    @Published var pdfCrashSuspect: PDFMapSource?

    /// one shot launch notices from the crash guard. An interrupted import and
    /// an interrupted bake can both fire on one launch (K1), they queue and show
    /// one after the other, after the crash suspect alert if there is one
    enum PDFLaunchNotice: Equatable { case importInterrupted, bakeInterrupted }
    @Published private(set) var pdfLaunchNotice: PDFLaunchNotice?
    private(set) var queuedLaunchNotices: [PDFLaunchNotice] = []

    /// last online style actually shown this launch, H1 prefers it
    private var lastOnlineStyle: BasemapStyle?

    /// the guard decides once per launch, on the first restore that knows
    /// what map (if any) it restored
    private var pdfGuardDecided = false
    var pdfRenderGuard: PDFRenderGuard = .shared

    /// 3.0.2 M1: imported MBTiles get their own open guard (own file, own
    /// reducer), decided at the same moment as the PDF one
    var mbtilesOpenGuard: MBTilesOpenGuard = .shared
    private var mbtilesGuardDecided = false
    /// the restored pack launch held back, it was opening or drawing when the
    /// app died. Same alert as the PDF suspect, the durable selection stays put
    @Published private(set) var mbtilesCrashSuspect: ImportedMapEntry?
    /// the guard only ever arms in the foreground, tests pin it
    var guardIsForeground: () -> Bool = { UIApplication.shared.applicationState != .background }

    /// 3.0.2 M2: a pack is opened and admitted on packOpenQueue, never main,
    /// and lands back on packOpenMain. Tests swap all three
    var packOpenQueue: (@escaping () -> Void) -> Void = { DispatchQueue.global(qos: .userInitiated).async(execute: $0) }
    var packOpenMain: (@escaping () -> Void) -> Void = { DispatchQueue.main.async(execute: $0) }
    var packOpener: (URL, ImportedMapEntry) -> OfflineTileMapSource? = { url, e in
        OfflineTileMapSource(url: url, entryID: e.id, displayName: e.displayName)
    }
    /// the one open still allowed to land. Anything published meanwhile or a
    /// newer open drops it, its result gets closed instead
    private var pendingPackOpen: (ticket: Int, entryID: UUID)?
    private var packOpenTicket = 0
    var packOpenPending: Bool { pendingPackOpen != nil }

    /// admitted metadata, this process only, never persisted. Keyed on the
    /// entry + the file stamp a restore trusts, so other bytes are a miss
    private struct AdmittedPackKey: Hashable {
        let id: UUID, fileName: String, contentKey: String?, byteCount: Int64, modifiedAtMs: Int64
        init(_ e: ImportedMapEntry) {
            id = e.id; fileName = e.fileName; contentKey = e.contentKey
            byteCount = e.byteCount; modifiedAtMs = e.fileModifiedAtMs
        }
    }
    private var admittedPacks: [AdmittedPackKey: MBTilesStore.Metadata] = [:]

    /// what a pack open does once it lands
    private struct PackOpenPlan {
        /// blank placeholder while it opens (restore), else the old map stays up
        var placeholder: Bool
        /// activation: ActivateEntry re-reduced from the library, one write
        var writes: Bool
        var reframe: Bool
        var verify: Bool
        static let restore = PackOpenPlan(placeholder: true, writes: false, reframe: true, verify: true)
        static let calibrationReturn = PackOpenPlan(placeholder: true, writes: false, reframe: false, verify: false)
        static let openAnyway = PackOpenPlan(placeholder: false, writes: false, reframe: true, verify: true)
        static func activate(reframe: Bool) -> PackOpenPlan {
            PackOpenPlan(placeholder: false, writes: true, reframe: reframe, verify: false)
        }
    }
    private var backgroundObserver: NSObjectProtocol?
    /// whose estimate / bake gets cancelled when its map is deleted. tests swap it
    var bakeController: PDFBakeController = .shared
    private var pdfRuntimeSink: AnyCancellable?

    // MARK: - Dependencies

    private let elevationService = ElevationService()
    private var elevationCancellable: AnyCancellable?
    private var elevationTask: Task<Void, Never>?
    private let libraryDependencies: LibraryDependencies

    init(libraryDependencies: LibraryDependencies = .live,
         initialMapSource: MapSource = OnlineRasterBasemapSource.makeDefault()) {
        self.libraryDependencies = libraryDependencies
        self.mapSource = initialMapSource
        if let online = initialMapSource as? OnlineRasterBasemapSource { lastOnlineStyle = online.style }
        // header label + the map container follow the PDF render status
        pdfRuntimeSink = pdfRuntime.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        // next to the PDF guard's: a jetsam kill in the background isnt the pack's fault
        backgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main
        ) { [weak self] _ in self?.mbtilesOpenGuard.disarmForBackground() }
        // OD-F4: Try Again found the right bytes again (or didnt), the row follows
        pdfRuntime.onStoredFileVerdict = { [weak self] pdf, ok in
            guard let self, let id = pdf.entryID else { return }
            if ok {
                self.tamperedEntryIDs.remove(id)
                self.refreshFileStamp(id, url: pdf.url)
            } else if pdf.contentKey != nil {
                self.tamperedEntryIDs.insert(id)
            }
        }
        // Debounce camera-centre changes, only hit the DEM once user
        // stops panning for 400ms. Skips no-op changes (<0.0001deg ~ 11m).
        let settledCamera = $cameraCentre
            .removeDuplicates(by: Self.isApproximatelyEqual)
            .filter { !($0.latitude == 0 && $0.longitude == 0) }
            .debounce(for: .seconds(0.4), scheduler: DispatchQueue.main)
        elevationCancellable = Publishers.CombineLatest(
            settledCamera,
            OpsecSettings.shared.$onlineLookups.removeDuplicates()
        )
            .sink { [weak self] coord, enabled in
                guard let self else { return }
                if enabled {
                    self.fetchElevation(for: coord)
                } else {
                    self.elevationTask?.cancel()
                    self.elevationTask = nil
                    self.centreElevationReading = nil
                    Task { await self.elevationService.cancelInFlight() }
                }
            }
    }

    deinit {
        if let backgroundObserver { NotificationCenter.default.removeObserver(backgroundObserver) }
    }

    // MARK: - Imported map library (the ONE authority for the active map, s8.2)

    /// Loaded library, nil until restore ran (or while locked/corrupt).
    @Published private(set) var library: LibraryState?
    @Published private(set) var libraryStatus: LibraryLoadStatus = .unknown
    /// entries whose bytes stopped matching their content key this launch
    @Published private(set) var tamperedEntryIDs: Set<UUID> = []
    /// bumped whenever drafts change so Layers re-reads the subtitles
    @Published private(set) var draftsEpoch = 0

    /// Shown while a calibration runs (s2.3): it ignores the first fix and
    /// keeps the header on the crosshair.
    @Published var calibrationActive = false {
        didSet { if calibrationActive { isBrowsing = true } }
    }
    /// What "Add point" reads, wired by the map container (live camera, no debounce).
    var calibrationCapture: (() -> CalibrationCapture?)?
    let zoomStepRequests = PassthroughSubject<Double, Never>()
    let centreRequests = PassthroughSubject<CLLocationCoordinate2D, Never>()

    /// where to go back to after a non-durable calibration display
    private var calibrationReturn: MapSource?
    /// C3: the durable selection when that display went up. A restore that
    /// reads the same one back leaves the calibration alone
    private var calibrationDurableSelection: MapSelection?
    private var pendingRetry: (() -> Bool)?
    /// S7: an import copy whose library write failed, kept (in flight) for Retry.
    /// Not Now drops it
    private var pendingImportCopy: URL?
    /// K1 (3.0.3 DL-4): Retries of writes that hit the relock gate, oldest first.
    /// the unlock's own restore wipes pendingRetry, so these get run again once a
    /// restore has the library back. Not Now drops them with the copy
    private var relockedRetries: [() -> Bool] = []
    private var rebuildInFlight = false

    /// F3: Retry on a locked library re-runs the migration + restore, which
    /// ContentView owns (it's slow). Nil = just restore again
    var reloadRequested: (() -> Void)?
    var resumeCalibrationRequested: ((UUID) -> Void)?

    /// M10: E3 auto-resume waits while any crash suspect is pending, the
    /// durable one held back or a preview's (C8)
    var crashSuspectPending: Bool {
        pdfCrashSuspect != nil || pdfRenderGuard.suspect != nil || mbtilesCrashSuspect != nil
    }

    /// the crash alert is up for one of the two (PDF first, it's the older one)
    var crashSuspectAlertShowing: Bool { pdfCrashSuspect != nil || mbtilesCrashSuspect != nil }
    var crashSuspectDisplayName: String { pdfCrashSuspect?.displayName ?? mbtilesCrashSuspect?.displayName ?? "" }

    var drafts: CalibrationDraftStoring { libraryDependencies.drafts }

    func entry(_ id: UUID) -> ImportedMapEntry? { library?.entry(id) }

    func fileStatus(_ e: ImportedMapEntry) -> ImportedMapFileStatus {
        tamperedEntryIDs.contains(e.id) ? .sizeOrMtimeMismatch : libraryDependencies.fileStatus(e)
    }

    func fileURL(_ e: ImportedMapEntry) -> URL? { libraryDependencies.fileURL(e) }

    /// the source that shows an entry at its effective georef, nil when it can't.
    /// allowUnavailable (OD-F4, PDFs only): a missing / changed file still gets
    /// a source, flagged, so the renderer fails it as cannotOpen and Try Again
    /// can recover once the exact bytes are back. Never draws other bytes
    func source(for e: ImportedMapEntry, georef: PdfGeoreference? = nil, allowUnavailable: Bool = false) -> MapSource? {
        let status = fileStatus(e)
        guard let url = libraryDependencies.fileURL(e) else { return nil }
        switch e.kind {
        case .pdf:
            guard status == .ok || (allowUnavailable && e.contentKey != nil) else { return nil }
            guard let g = georef ?? e.pdf?.effectiveGeoref else { return nil }
            // a bake only ever belongs to the effective georef, a calibration
            // display draws live (its own key wouldnt match anyway)
            let bake = (georef == nil || georef == e.pdf?.effectiveGeoref) ? e.pdf?.validBake : nil
            let src = PDFMapSource(url: url, georef: g, contentKey: e.contentKey, entryID: e.id,
                                   displayName: e.displayName, renderGuardToken: e.renderGuardToken, bake: bake)
            src.storedFileUnavailable = status != .ok
            return src
        case .mbtiles:
            guard status == .ok else { return nil }
            // M2: never a fresh open here (main). Only metadata this process
            // already admitted, anything else goes through openPack
            return admittedPackSource(e, url: url)
        }
    }

    /// 3.0.2 M1: every MBTiles open that can end up on screen (restore,
    /// activation, Open Anyway, import) runs under the open guard. Armed durably
    /// before the open; a refused pack clears it right away, an admitted one once
    /// its first draw settles, or when the source is replaced, closed or dropped.
    /// This one's the lazy reader over admitted metadata, its open happens on the
    /// first tile read (renderer thread) so the first draw covers it
    private func admittedPackSource(_ e: ImportedMapEntry, url: URL) -> OfflineTileMapSource? {
        guard let meta = admittedPacks[AdmittedPackKey(e)] else { return nil }
        let token = e.id.uuidString
        let guardStore = mbtilesOpenGuard
        let armed = guardStore.arm(token: token, foreground: guardIsForeground())
        let src = OfflineTileMapSource(prevalidatedURL: url, metadata: meta, entryID: e.id, displayName: e.displayName)
        if armed { src.firstDraw = MBTilesFirstDrawWatch { guardStore.complete(token: token) } }
        return src
    }

    /// 3.0.2 M2: restore, activation and Open Anyway of a pack. Main arms the
    /// guard and, for a restore, puts up the blank placeholder. The open, the
    /// hardening, relation/shape/base checks and the admission aggregate all run
    /// off main. Back on main it only lands if it's still the newest ask for the
    /// same file in a loaded library, else its store is closed and the guard
    /// cleared. false = no file to open, nothing changed
    @discardableResult
    private func openPack(_ e: ImportedMapEntry, plan: PackOpenPlan) -> Bool {
        guard e.kind == .mbtiles, fileStatus(e) == .ok, let url = libraryDependencies.fileURL(e) else { return false }
        // already admitted this process: lazy reader, nothing to wait for
        if let src = admittedPackSource(e, url: url) {
            pendingPackOpen = nil
            return landPack(src, e, plan: plan)
        }
        if plan.placeholder {
            publishMapSource(MBTilesOpeningSource(entryID: e.id, displayName: e.displayName), reframe: false)
        }
        let token = e.id.uuidString
        let guardStore = mbtilesOpenGuard
        let armed = guardStore.arm(token: token, foreground: guardIsForeground())
        packOpenTicket &+= 1
        let ticket = packOpenTicket
        pendingPackOpen = (ticket, e.id)
        let opener = packOpener, main = packOpenMain
        packOpenQueue { [weak self] in
            let src = opener(url, e)
            main {
                guard let self else {
                    src?.closeForDeletion()
                    if armed { guardStore.complete(token: token) }
                    return
                }
                self.packOpened(src, asked: e, ticket: ticket, armed: armed, guardStore: guardStore, plan: plan)
            }
        }
        return true
    }

    /// main, an off main open came back
    private func packOpened(_ src: OfflineTileMapSource?, asked: ImportedMapEntry, ticket: Int, armed: Bool,
                            guardStore: MBTilesOpenGuard, plan: PackOpenPlan) {
        let token = asked.id.uuidString
        // superseded: something else went on screen, a newer open started, or
        // the library / the entry's file changed underneath it
        guard pendingPackOpen?.ticket == ticket, libraryStatus == .loaded,
              let e = library?.entry(asked.id), AdmittedPackKey(e) == AdmittedPackKey(asked) else {
            // still the newest ask but the library moved: nothing's coming for it now
            let wasNewest = pendingPackOpen?.ticket == ticket
            if wasNewest { pendingPackOpen = nil }
            src?.closeForDeletion()
            if armed { guardStore.complete(token: token) }
            // DL-3: if this was a tap over a restore's placeholder (say the tapped
            // entry got deleted meanwhile) the restore's result is already gone,
            // so put something real up. Locked library = leave it, the unlock's
            // restore reopens it
            if wasNewest, libraryStatus == .loaded { replaceOrphanedPlaceholder() }
            return
        }
        pendingPackOpen = nil
        guard let src else {
            // refused: nothing written. A restore goes online in memory like it
            // always did, an activation leaves the old map up. Unless the old map
            // is a restore's blank placeholder this tap took over from
            if armed { guardStore.complete(token: token) }
            if plan.placeholder {
                publishMapSource(OnlineRasterBasemapSource(library?.preferredStyle ?? OnlineRasterBasemapSource.defaultStyle))
            } else {
                replaceOrphanedPlaceholder(refused: e.id)
            }
            return
        }
        admittedPacks[AdmittedPackKey(e)] = src.store.metadata
        if armed { src.firstDraw = MBTilesFirstDrawWatch { guardStore.complete(token: token) } }
        landPack(src, e, plan: plan)
    }

    @discardableResult
    private func landPack(_ src: OfflineTileMapSource, _ e: ImportedMapEntry, plan: PackOpenPlan) -> Bool {
        guard plan.writes else {
            publishMapSource(src, reframe: plan.reframe)
            if plan.verify { verifyInBackground(e) }
            return true
        }
        let ok = execute(.activateEntry(e.id)) { _, _ in
            self.clearCalibrationReturn()
            self.publishMapSource(src, reframe: plan.reframe)
        }
        // this tap took over from a restore still opening and its write failed
        if !ok { replaceOrphanedPlaceholder() }
        return ok
    }

    /// an activation took over from a restore that was still opening, then
    /// didnt land (pack refused, its write failed, or its entry went or changed
    /// while it opened). The restore's result got
    /// dropped, so nothing is coming for the blank placeholder: open the restored
    /// pack again, online in memory when that can't happen or it's the very pack
    /// that just got refused. Never leave the blank up with nothing pending
    private func replaceOrphanedPlaceholder(refused: UUID? = nil) {
        guard pendingPackOpen == nil, let held = mapSource as? MBTilesOpeningSource else { return }
        if held.entryID != refused, let restored = library?.entry(held.entryID), openPack(restored, plan: .restore) {
            return
        }
        publishMapSource(OnlineRasterBasemapSource(library?.preferredStyle ?? OnlineRasterBasemapSource.defaultStyle))
    }

    private static func entryID(of s: MapSource) -> UUID? {
        (s as? PDFMapSource)?.entryID ?? (s as? OfflineTileMapSource)?.entryID ?? (s as? MBTilesOpeningSource)?.entryID
    }

    /// the placeholder counts, it's that entry being shown (just not drawn yet)
    var activeEntryID: UUID? { Self.entryID(of: mapSource) }

    /// an offline pack is up, or about to be (no online tiles either way)
    var showsOfflinePack: Bool { mapSource is OfflineTileMapSource || mapSource is MBTilesOpeningSource }

    @discardableResult
    func selectOnlineBasemap(_ style: BasemapStyle) -> Bool {
        execute(.selectOnline(style)) { _, _ in
            // C4: a calibration display has nothing to go back to now
            self.clearCalibrationReturn()
            self.publishMapSource(OnlineRasterBasemapSource(style))
        }
    }

    @discardableResult
    func activateLibraryEntry(_ id: UUID, reframe: Bool = true) -> Bool {
        guard let e = library?.entry(id) else { return false }
        if e.kind == .mbtiles {
            // picking the held back pack by hand is Open Anyway, before it opens
            resolveMBTilesSuspect(id, .openAnyway)
            // M2: opened off main, the old map stays up till it lands and the
            // write happens then. true = on its way
            return openPack(e, plan: .activate(reframe: reframe))
        }
        guard let source = source(for: e) else { return false }
        return execute(.activateEntry(id)) { _, _ in
            self.clearCalibrationReturn()
            // picking the held back map by hand is Open Anyway (same as Android)
            if let suspect = self.pdfCrashSuspect, suspect.entryID == id {
                self.pdfRenderGuard.resolveSuspect(.openAnyway)
                self.pdfCrashSuspect = nil
                self.showNextLaunchNotice(after: 0.35)
            }
            self.publishMapSource(source, reframe: reframe)
        }
    }

    /// import commit: one write adds the entry (+ makes it active). ownsCopy =
    /// the in-flight copy behind it: released once it's in the library, kept for
    /// the Retry when the write fails, dropped on Not Now (S7)
    /// packMetadata: what the import worker already admitted off main (M2), the
    /// pack is shown from that, never re-admitted here
    @discardableResult
    func addImportedEntry(_ e: ImportedMapEntry, activate: Bool, ownsCopy copy: URL? = nil,
                          packMetadata: MBTilesStore.Metadata? = nil) -> Bool {
        if e.kind == .mbtiles, let packMetadata { admittedPacks[AdmittedPackKey(e)] = packMetadata }
        let shown = activate ? source(for: e) : nil
        let ok = execute(.addEntry(e, activate: activate && shown != nil)) { _, _ in
            if let copy {
                InFlightImportFiles.unregister(copy)
                if self.pendingImportCopy == copy { self.pendingImportCopy = nil }
            }
            if let shown { self.publishMapSource(shown) }
        }
        if !ok, let copy {
            // a refused transition (library full etc) has no Retry, the copy goes now
            if mapSelectionPersistenceIssue == nil { ImportedMapStorage.unlink(copy); InFlightImportFiles.unregister(copy) } else { pendingImportCopy = copy }
        }
        // a pack with no admitted metadata to hand: the normal off main activation
        if ok, activate, shown == nil, e.kind == .mbtiles { _ = activateLibraryEntry(e.id) }
        return ok
    }

    /// E9: a re-import of an unavailable entry brought its exact bytes back.
    /// One write points the entry at the new copy, the old file goes with the
    /// reconcile after it, calibration and bake record stay
    @discardableResult
    func relinkLibraryEntry(_ id: UUID, to copy: URL, byteCount: Int64, modifiedAtMs: Int64) -> Bool {
        guard let rel = ImportedMapStorage.relativePath(for: copy) else { return false }
        let ok = execute(.relink(id, fileName: rel, byteCount: byteCount, modifiedAtMs: modifiedAtMs)) { _, _ in
            InFlightImportFiles.unregister(copy)
            if self.pendingImportCopy == copy { self.pendingImportCopy = nil }
            self.tamperedEntryIDs.remove(id)
        }
        if !ok {
            if mapSelectionPersistenceIssue == nil { ImportedMapStorage.unlink(copy); InFlightImportFiles.unregister(copy) } else { pendingImportCopy = copy }
        }
        return ok
    }

    /// Finish: manual calibration + active = entry in one write, then show it
    /// without moving the camera (s2.6)
    @discardableResult
    func commitCalibration(entryID: UUID, manual: ManualCalibration, contentKey: String, pageIndex: Int) -> Bool {
        // the session shows its own save-failed alert with Retry, no second one here
        execute(.commitCalibration(entryID, manual, contentKey: contentKey, pageIndex: pageIndex),
                reportFailure: false) { next, _ in
            self.clearCalibrationReturn()
            if let e = next.entry(entryID), let src = self.source(for: e) {
                self.publishMapSource(src, reframe: false)
            }
        }
    }

    @discardableResult
    func revertToEmbedded(_ id: UUID) -> Bool {
        execute(.revertToEmbedded(id)) { next, _ in
            guard self.activeEntryID == id else { return }
            if let e = next.entry(id), let src = self.source(for: e) {
                self.publishMapSource(src, reframe: false)
            } else {
                self.publishMapSource(OnlineRasterBasemapSource(next.preferredStyle))
            }
        }
    }

    /// P1 change page: new page + its own georef, the calibration and its drafts go
    @discardableResult
    func changePage(_ id: UUID, pageIndex: Int, rotate: Int, pageBox: [PdfPagePoint],
                    embedded: PdfGeoreference?, embeddedIssue: String?) -> Bool {
        let old = library?.entry(id)
        return execute(.changePage(id, pageIndex: pageIndex, rotate: rotate, pageBox: pageBox,
                                   embedded: embedded, embeddedIssue: embeddedIssue)) { next, _ in
            if let key = old?.contentKey, let oldPage = old?.pdf?.pageIndex {
                self.libraryDependencies.drafts.delete(contentKey: key, pageIndex: oldPage)
                self.draftsEpoch &+= 1
            }
            guard self.activeEntryID == id else { return }
            if let e = next.entry(id), let src = self.source(for: e) {
                self.publishMapSource(src)
            } else {
                self.publishMapSource(OnlineRasterBasemapSource(next.preferredStyle))
            }
        }
    }

    // MARK: - WP2 bake records (on the PDF entry, M1-M6)

    /// S1 publish step of a bake: the record lands on the entry only if it's
    /// still the bytes + token + georef the bake was made from. One write,
    /// the active map doesn't change. Main thread, same as the publish
    func attachBake(_ record: PDFBakeRecord, for pdf: PDFMapSource) -> PDFBakeController.AttachOutcome {
        // R6: a library we cant write is a write problem, not another map
        guard libraryStatus == .loaded, let current = library else { return .writeFailed }
        guard let id = pdf.entryID else { return .sourceChanged }
        let next: LibraryState
        do {
            next = try LibraryReducer.apply(.attachBake(id, record, contentKey: pdf.contentKey,
                                                        renderGuardToken: pdf.renderGuardToken), to: current).state
        } catch LibraryTransitionError.invalidBake {
            return .writeFailed
        } catch {
            return .sourceChanged
        }
        do {
            try libraryDependencies.write(next)
        } catch {
            return .writeFailed
        }
        removeKnownSupersededBakes(from: current, to: next)
        library = next
        // a previous bake of this map is unreferenced now, this reaps it
        if next.permitsCleanup { _ = libraryDependencies.reconcile(next) }
        return .attached
    }

    /// Remove Offline Tiles step 1: clear the record (one write). false = nothing
    /// changed and the usual Retry is up, the caller then deletes nothing
    func detachBake(_ record: PDFBakeRecord, from pdf: PDFMapSource) -> Bool {
        guard let id = pdf.entryID else { return false }
        return execute(.removeBake(id, record)) { _, _ in }
    }

    /// the runtime hashed the file back to its content key (OD-F4 recovery): a
    /// stale size/mtime in the entry would keep the row unavailable, take the
    /// new one. Nothing to do when the stamp still matches
    private func refreshFileStamp(_ id: UUID, url: URL) {
        guard let e = library?.entry(id), libraryDependencies.fileStatus(e) == .sizeOrMtimeMismatch,
              let v = try? url.resourceValues(forKeys: [.fileSizeKey]), let size = v.fileSize else { return }
        _ = execute(.refreshFileStamp(id, byteCount: Int64(size), modifiedAtMs: ImportedMapStorage.modifiedAtMs(url)),
                    reportFailure: false) { _, _ in }
    }

    /// what the R3-2 sweep may keep, nil when the library can't be trusted right now
    func bakeNamesForSweep() -> Set<String>? {
        guard libraryStatus == .loaded, let s = library, s.permitsCleanup else { return nil }
        return ImportedMapLibrary.bakeFileNames(s)
    }

    /// hooks the shared bake controller up to the library (ContentView does
    /// this once). Until then the controller fails closed
    func bindBakeController(_ c: PDFBakeController) {
        c.attachRecord = { [weak self] record, pdf, _ in self?.attachBake(record, for: pdf) ?? .writeFailed }
        c.detachRecord = { [weak self] record, pdf in self?.detachBake(record, from: pdf) ?? false }
        c.storedBake = { [weak self] in self?.bakeNamesForSweep() }
    }

    /// s8.2 delete: one write, publish online if it was showing, close SQLite,
    /// drop the drafts, unlink the files (+ sidecars) and their bakes, reconcile.
    /// WP2 F7: an estimate / bake for that PDF stops first. One write is the
    /// commit point, a failed one deletes nothing and offers Retry
    @discardableResult
    func deleteLibraryEntry(_ id: UUID) -> Bool {
        let showing = activeEntryID
        if let e = library?.entry(id), e.kind == .pdf,
           let pdf = source(for: e, allowUnavailable: true) as? PDFMapSource {
            bakeController.cancel(for: pdf)
        }
        return execute(.deleteEntry(id)) { next, removed in
            let ids = Set(removed.map(\.id))
            if let showing, ids.contains(showing) {
                self.clearCalibrationReturn()
                self.publishMapSource(OnlineRasterBasemapSource(next.preferredStyle))
            }
            // F4: the suspect went (from the crash alert, or its Retry after a
            // failed write): the guard hears deleted, the alert comes down
            if let suspect = self.pdfRenderGuard.suspect, removed.contains(where: { $0.renderGuardToken == suspect }) {
                self.pdfRenderGuard.resolveSuspect(.deleted)
                if self.pdfCrashSuspect != nil {
                    self.pdfCrashSuspect = nil
                    self.showNextLaunchNotice(after: 0.35)
                }
            }
            for e in removed {
                if let key = e.contentKey, e.kind == .pdf { self.libraryDependencies.drafts.deleteAll(contentKey: key) }
                if let url = self.libraryDependencies.fileURL(e) { self.libraryDependencies.unlink(url) }
                // its baked tiles go straight away, not on some later reconcile
                if let bake = e.pdf?.validBake { self.libraryDependencies.removeBakeFile(bake) }
            }
            if let suspect = self.pdfCrashSuspect?.entryID, ids.contains(suspect) { self.pdfCrashSuspect = nil }
            // same for a held back pack, its guard hears deleted
            for e in removed where e.kind == .mbtiles { self.resolveMBTilesSuspect(e.id, .deleted) }
            // R3-2: anything a failed unlink left behind goes too (same as Android)
            if next.permitsCleanup { _ = self.libraryDependencies.sweepBakes(ImportedMapLibrary.bakeFileNames(next)) }
            self.tamperedEntryIDs.subtract(ids)
            self.draftsEpoch &+= 1
        }
    }

    /// Show an entry at a calibration georef WITHOUT a library write: the
    /// provisional preview of an uncalibrated PDF, or the page-box display of a
    /// georeferenced one. Never makes an uncalibrated PDF a durable basemap.
    @discardableResult
    func beginCalibrationDisplay(entry e: ImportedMapEntry, georef: PdfGeoreference, reframe: Bool) -> Bool {
        guard let src = source(for: e, georef: georef) else { return false }
        if calibrationReturn == nil {
            calibrationReturn = mapSource
            calibrationDurableSelection = library?.active
        }
        publishMapSource(src, reframe: false)
        // frame the whole page, not 1.5 km round the user. the provisional
        // page is centred on the camera so the user is nearly always "inside" it
        if reframe { frameCamera(for: src, userLocation: nil) }
        return true
    }

    /// leave/suspend: back to what was showing, camera untouched
    func endCalibrationDisplay() {
        guard let back = calibrationReturn else { return }
        clearCalibrationReturn()
        // the durable entry may have been re-activated, rebuild it from the library
        if let id = Self.entryID(of: back), let e = library?.entry(id) {
            // a pack reopens off main (its old store closed when the display went up)
            if e.kind == .mbtiles, openPack(e, plan: .calibrationReturn) { return }
            if let fresh = source(for: e) {
                publishMapSource(fresh, reframe: false)
                return
            }
        }
        publishMapSource(back, reframe: false)
    }

    var inCalibrationDisplay: Bool { calibrationReturn != nil }

    /// C4: suspend, a durable change or Use Online Map: nothing to go back to
    func clearCalibrationReturn() {
        calibrationReturn = nil
        calibrationDurableSelection = nil
    }

    /// C2: starting calibration on the pending crash suspect is the user's
    /// Open Anyway, same as picking it in Layers. Any other entry leaves it be
    func resolveSuspectForCalibration(_ id: UUID) {
        guard let e = library?.entry(id), let suspect = pdfRenderGuard.suspect,
              suspect == e.renderGuardToken else { return }
        pdfRenderGuard.resolveSuspect(.openAnyway)
        if pdfCrashSuspect?.entryID == id {
            pdfCrashSuspect = nil
            showNextLaunchNotice(after: 0.35)
        }
    }

    /// the entry E3 would reopen right now: the active draft's, if it's still in the library
    func autoResumeEntry(in s: LibraryState? = nil) -> ImportedMapEntry? {
        guard let s = s ?? library, let d = drafts.activeDraft() else { return nil }
        return s.entries.first { $0.contentKey == d.contentKey && $0.pdf?.pageIndex == d.pageIndex }
    }

    func noteDraftsChanged() { draftsEpoch &+= 1 }

    // MARK: - restore

    enum RestoreOutcome: Equatable {
        case restored
        case nothing
        case locked
        case corrupt
    }

    /// Launch / unlock restore (s8.2): load, publish the active entry from a
    /// size+mtime check only, reconcile, prune drafts, then hash the active file
    /// once on a utility queue. Migration must have run already (it's slow).
    @discardableResult
    func restoreActiveMapSelection() -> RestoreOutcome {
        let load = libraryDependencies.load()
        // an Empty library is only a real first launch with no legacy stores and
        // no map files around. Otherwise the migration hop still owes a write
        // (key locked, or the write itself failed) and nothing gets cleaned up
        var migrationPending = false
        if case .empty = load {
            migrationPending = libraryDependencies.legacyPresent() || libraryDependencies.managedFiles()
        }
        // R3-2: bake only sweep on every restore (launch, unlock, Retry). Only on an
        // authoritative read, locked / corrupt / migration pending deletes nothing.
        // It doesn't need the PDF to be there
        if case .read(let names) = ImportedMapLibrary.bakeAuthority(load, legacyPresent: migrationPending) {
            _ = libraryDependencies.sweepBakes(names)
        }
        switch load {
        case .empty where migrationPending:
            // the old stores (or orphan map files) are still waiting for the
            // migration hop: key locked or its write failed. no writes until it
            // has run, or a fresh library would orphan them. Retry runs it again
            libraryStatus = .locked
            library = nil
            reportLockedIssue()
            return .locked
        case .empty:
            // load() only says empty when no library was ever written here (S1),
            // so this one is a real first launch and safe to reconcile from
            library = LibraryState()
            libraryStatus = .loaded
            mapSelectionPersistenceIssue = nil
            pendingRetry = nil
            _ = decideLaunchGuard(restoredToken: nil)
            _ = decideMBTilesLaunchGuard(restoredToken: nil)
            afterRestore(LibraryState())
            showNextLaunchNotice()
            resumeWorkParkedBehindRelock()
            return .nothing
        case .loaded(let s):
            library = s
            libraryStatus = .loaded
            mapSelectionPersistenceIssue = nil
            pendingRetry = nil
            // D8: a crash after the migration write left the old stores behind. Not
            // on a flagged (rebuilt / salvaged / adopted) library, its old stores
            // stay frozen, and never just for a .corrupt-* copy (L2, L8)
            if s.permitsCleanup, libraryDependencies.legacyPresent(), libraryDependencies.clearableLegacyPresent() {
                libraryDependencies.clearLegacy()
            }
            // C3: an unlock / Retry that reads back the selection a calibration
            // display started from isnt a map change, leave the display up
            let calibrationUntouched = calibrationReturn != nil && s.active == calibrationDurableSelection
            // 3.0.2 M1: the MBTiles guard decides right here too, restoredToken =
            // the durable active entry when it's a pack
            var restoredPack: ImportedMapEntry?
            if case .entry(let id)? = s.active, let e = s.entry(id), e.kind == .mbtiles { restoredPack = e }
            let pack = decideMBTilesLaunchGuard(restoredToken: restoredPack?.id.uuidString)
            switch calibrationUntouched ? nil : s.active {
            case .online(let style)?:
                _ = decideLaunchGuard(restoredToken: nil)
                if !(mapSource is OnlineRasterBasemapSource) || (mapSource as? OnlineRasterBasemapSource)?.style != style {
                    publishMapSource(OnlineRasterBasemapSource(style.requiresEsriKey && !EsriKey.isAvailable
                                                               ? OnlineRasterBasemapSource.defaultStyle : style))
                }
            case .entry(let id)?:
                if let e = s.entry(id), e.kind == .pdf,
                   let pdf = source(for: e, allowUnavailable: true) as? PDFMapSource {
                    if decideLaunchGuard(restoredToken: pdf.renderGuardToken) {
                        // crash loop breaker: online map in memory only, the durable
                        // selection and the PDF stay exactly as they are
                        pdfCrashSuspect = pdf
                        publishMapSource(preferredOnlineBasemap(), reframe: false)
                    } else if activeEntryID != id {
                        // OD-F4: a missing / changed file keeps the selection and
                        // fails as cannotOpen until the exact bytes are back
                        publishMapSource(pdf)
                        if !pdf.storedFileUnavailable { verifyInBackground(e) }
                    }
                } else if let e = restoredPack, e.id == id, pack.holdBack {
                    _ = decideLaunchGuard(restoredToken: nil)
                    // crash loop breaker, like the PDF one: online in memory only,
                    // the durable selection and the pack stay exactly as they are
                    if pack.ask { mbtilesCrashSuspect = e }
                    if pack.ask || !(mapSource is OnlineRasterBasemapSource) {
                        publishMapSource(preferredOnlineBasemap(), reframe: false)
                    }
                } else {
                    _ = decideLaunchGuard(restoredToken: nil)
                    // a placeholder whose open went away (library locked under
                    // it, say) isnt showing anything, this restore reopens it
                    let opening = mapSource is MBTilesOpeningSource && pendingPackOpen?.entryID == id
                    if activeEntryID != id || (mapSource is MBTilesOpeningSource && !opening) {
                        if let e = s.entry(id), e.kind == .mbtiles, openPack(e, plan: .restore) {
                            // M2: blank placeholder now, the pack once it's admitted off main
                        } else if let e = s.entry(id), let src = source(for: e) {
                            publishMapSource(src)
                            verifyInBackground(e)
                        } else {
                            publishMapSource(OnlineRasterBasemapSource(s.preferredStyle))
                        }
                    }
                }
            case nil:
                _ = decideLaunchGuard(restoredToken: nil)
            }
            afterRestore(s)
            showNextLaunchNotice()
            resumeWorkParkedBehindRelock()
            return .restored
        case .locked:
            libraryStatus = .locked
            library = nil
            reportLockedIssue()
            return .locked
        case .corrupt:
            // S1: nothing reconciled, swept or pruned from here. Retry rebuilds (S2)
            libraryStatus = .corrupt
            library = nil
            reportCorruptIssue()
            return .corrupt
        }
    }

    /// F3: Retry re-runs the migration + restore (ContentView's), same as an unlock
    private func reportLockedIssue() {
        reportIssue(Messages.displayTheSavedBasemapIsLockedOrUnreadableUnlockMissionMessage()) { [weak self] in
            guard let self else { return false }
            if let reload = self.reloadRequested {
                reload()
                return true
            }
            return self.restoreActiveMapSelection() == .restored
        }
    }

    private func reportCorruptIssue() {
        reportIssue(Messages.mapLibraryCorruptMessageMessage()) { [weak self] in
            self?.rebuildCorruptLibrary()
            return true
        }
    }

    /// S2 Retry on a corrupt library: rebuild the list from the files on disk
    /// (off main), ONE write, then the normal restore of what was written. A
    /// failed write changes nothing and the alert comes back. No user file or
    /// draft or bake is deleted. The sealed recovery flag also protects later launches.
    func rebuildCorruptLibrary(completion: ((Bool) -> Void)? = nil) {
        guard libraryStatus == .corrupt, !rebuildInFlight else { completion?(false); return }
        rebuildInFlight = true
        let deps = libraryDependencies
        let inFlight = InFlightImportFiles.snapshot
        deps.background { [weak self] in
            let rebuilt = deps.rebuild(inFlight)
            deps.foreground {
                guard let self else { return }
                self.rebuildInFlight = false
                // something else already loaded it meanwhile (unlock, a second Retry)
                guard self.libraryStatus == .corrupt else { completion?(false); return }
                do {
                    try deps.writeRebuilt(rebuilt)
                } catch {
                    NSLog("[MapVM] library rebuild write failed")
                    self.reportCorruptIssue()
                    completion?(false)
                    return
                }
                let restored = self.restoreActiveMapSelection() == .restored
                completion?(restored)
            }
        }
    }

    /// K1: the library is back (the unlock's restore, or a Retry after it). what
    /// the relock cut off goes in now, after the restored map is up so an import
    /// that activates still wins. Still relocked = each one just parks again, the
    /// gate throws before any Keychain read
    private func resumeWorkParkedBehindRelock() {
        let parked = relockedRetries
        relockedRetries = []
        for retry in parked { _ = retry() }
        bakeController.publishParkedBake()
    }

    private func afterRestore(_ s: LibraryState) {
        if s.permitsCleanup {
            _ = libraryDependencies.reconcile(s)
            libraryDependencies.drafts.prune(keepingContentKeys: Set(s.entries.compactMap(\.contentKey)))
        }
        libraryDependencies.sweepBackup()
        draftsEpoch &+= 1
    }

    /// a file that kept its size + mtime but changed its bytes gets marked
    /// unavailable and the map goes back online (s8.2 restore step 4)
    private func verifyInBackground(_ e: ImportedMapEntry) {
        guard let key = e.contentKey, let url = libraryDependencies.fileURL(e) else { return }
        libraryDependencies.verify(url, key) { [weak self] ok in
            guard let self, !ok else { return }
            self.tamperedEntryIDs.insert(e.id)
            guard self.activeEntryID == e.id else { return }
            if let pdf = self.mapSource as? PDFMapSource {
                // OD-F4: same as a restore that found it changed, the selection
                // stays and the map fails as cannotOpen (Try Again re-checks)
                self.pdfRuntime.storedFileChanged(for: pdf)
            } else {
                _ = self.selectOnlineBasemap(self.library?.preferredStyle ?? OnlineRasterBasemapSource.defaultStyle)
            }
        }
    }

    // MARK: - crash guard (WP2 s.I)

    /// true = suppress the restored PDF
    private func decideLaunchGuard(restoredToken: String?) -> Bool {
        guard !pdfGuardDecided else {
            // a later restore in the same launch (unlock, retry) still honours a standing suspect
            return restoredToken != nil && pdfRenderGuard.suspect == restoredToken && pdfCrashSuspect != nil
        }
        pdfGuardDecided = true
        // C8: the app died drawing the calibration preview of the draft E3 would
        // reopen now, that map isnt the durable one. Decide on its token so it
        // becomes the suspect, then E3 skips it instead of crashing again
        var token = restoredToken
        let previewEntry = autoResumeEntry()
        let snapshot = pdfRenderGuard.snapshot
        if let previewToken = previewEntry?.renderGuardToken, previewToken != restoredToken,
           snapshot.suspect == previewToken || ((snapshot.inProgress?.kind == .base || snapshot.inProgress?.kind == .vector)
                                               && snapshot.inProgress?.token == previewToken) {
            token = previewToken
        }
        let outcome = pdfRenderGuard.launchDecision(restoredToken: token)
        guard token == restoredToken else {
            if outcome.decision == .suppress, let entry = previewEntry, let info = entry.pdf,
               let key = entry.contentKey {
                let target = CalibrationTarget(entryID: entry.id, contentKey: key, pageIndex: info.pageIndex,
                                               pageBox: info.pageBox, rotate: info.rotate)
                let preview = PdfGeoreference.provisional(pageBox: target.pageRect, rotation: info.rotate,
                                                          centredOn: cameraCentre)
                pdfCrashSuspect = source(for: entry, georef: preview, allowUnavailable: true) as? PDFMapSource
            }
            if case .importInterrupted = outcome.decision { queuedLaunchNotices.append(.importInterrupted) }
            if outcome.bakeInterrupted {
                PDFBakeController.cleanWorkDirectory()
                queuedLaunchNotices.append(.bakeInterrupted)
            }
            return false
        }
        // the decision's alert goes first, then "Offline tiles not finished"
        if case .importInterrupted = outcome.decision { queuedLaunchNotices.append(.importInterrupted) }
        if outcome.bakeInterrupted {
            PDFBakeController.cleanWorkDirectory()
            queuedLaunchNotices.append(.bakeInterrupted)
        }
        return outcome.decision == .suppress
    }

    /// 3.0.2 M1, the MBTiles twin of decideLaunchGuard. Once per launch at the
    /// same moment. holdBack = don't open the restored pack, ask = this launch
    /// just found it, put the alert up
    private func decideMBTilesLaunchGuard(restoredToken: String?) -> (holdBack: Bool, ask: Bool) {
        guard !mbtilesGuardDecided else {
            // a later restore (unlock, Retry) still holds back a standing suspect
            return (restoredToken != nil && mbtilesOpenGuard.suspect == restoredToken, false)
        }
        mbtilesGuardDecided = true
        let held = mbtilesOpenGuard.launchDecision(restoredToken: restoredToken) == .suppress
        return (held, held)
    }

    /// the user opened (or deleted) a pack the guard holds back, it hears so
    /// and the alert comes down if it was for that pack
    private func resolveMBTilesSuspect(_ id: UUID, _ choice: PDFSuspectResolution) {
        guard mbtilesOpenGuard.suspect == id.uuidString || mbtilesCrashSuspect?.id == id else { return }
        mbtilesOpenGuard.resolveSuspect(choice)
        if mbtilesCrashSuspect?.id == id {
            mbtilesCrashSuspect = nil
            showNextLaunchNotice(after: 0.35)
        }
    }

    /// next queued notice, once nothing else is up. SwiftUI drops an alert
    /// presented in the same turn another one went away, hence the delay
    private func showNextLaunchNotice(after delay: Double = 0) {
        guard delay <= 0 else {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in self?.showNextLaunchNotice() }
            return
        }
        guard pdfLaunchNotice == nil, !crashSuspectAlertShowing, !queuedLaunchNotices.isEmpty else { return }
        pdfLaunchNotice = queuedLaunchNotices.removeFirst()
    }

    /// OK on a launch notice
    func dismissLaunchNotice() {
        guard pdfLaunchNotice != nil else { return }
        pdfLaunchNotice = nil
        showNextLaunchNotice(after: 0.35)
    }

    /// H1: the user's own online style (the library keeps it), in memory only
    func preferredOnlineBasemap() -> OnlineRasterBasemapSource {
        let style = lastOnlineStyle ?? library?.preferredStyle ?? OnlineRasterBasemapSource.defaultStyle
        return OnlineRasterBasemapSource(style.requiresEsriKey && !EsriKey.isAvailable ? OnlineRasterBasemapSource.defaultStyle : style)
    }

    /// failure alert "Use Online Map": a real, durable selection of that style (H1)
    @discardableResult
    func useOnlineMapAfterFailure() -> Bool {
        selectOnlineBasemap(preferredOnlineBasemap().style)
    }

    /// crash recovery alert: Open Anyway
    func openCrashSuspectAnyway() {
        if pdfCrashSuspect == nil, let e = mbtilesCrashSuspect {
            resolveMBTilesSuspect(e.id, .openAnyway)
            // then the normal guarded open (off main), from the library as it is now
            if let fresh = library?.entry(e.id) { openPack(fresh, plan: .openAnyway) }
            return
        }
        guard let pdf = pdfCrashSuspect else { return }
        pdfRenderGuard.resolveSuspect(.openAnyway)
        pdfCrashSuspect = nil
        if let entry = autoResumeEntry(), entry.id == pdf.entryID, let resume = resumeCalibrationRequested {
            resume(entry.id)
        } else {
            publishMapSource(pdf)
        }
        if let id = pdf.entryID, let e = library?.entry(id), !pdf.storedFileUnavailable { verifyInBackground(e) }
        showNextLaunchNotice(after: 0.35)
    }

    /// crash recovery alert: Not Now. the suspect stays so next launch asks again
    func dismissCrashSuspect() {
        if pdfCrashSuspect == nil, mbtilesCrashSuspect != nil {
            mbtilesOpenGuard.resolveSuspect(.notNow)
            mbtilesCrashSuspect = nil
        } else {
            pdfRenderGuard.resolveSuspect(.notNow)
            pdfCrashSuspect = nil
        }
        showNextLaunchNotice(after: 0.35)
    }

    /// crash recovery alert: Delete Map, through the normal (one write) delete
    @discardableResult
    func deleteCrashSuspect() -> Bool {
        if pdfCrashSuspect == nil, let e = mbtilesCrashSuspect {
            // the delete's own publish resolves the guard (deleted) and drops the alert
            return deleteLibraryEntry(e.id)
        }
        guard let id = pdfCrashSuspect?.entryID else { return false }
        guard deleteLibraryEntry(id) else { return false }
        pdfRenderGuard.resolveSuspect(.deleted)
        pdfCrashSuspect = nil
        showNextLaunchNotice(after: 0.35)
        return true
    }

    /// check for an import the app died inside of. true = say so once
    func recoverInterruptedImport() -> Bool { libraryDependencies.recoverInterruptedImport() }

    @discardableResult
    func retryMapSelectionPersistence() -> Bool {
        guard let retry = pendingRetry else { return false }
        return retry()
    }

    func dismissMapSelectionPersistenceIssue() {
        pendingRetry = nil
        // a parked commit would write an entry for the copy unlinked below
        relockedRetries = []
        mapSelectionPersistenceIssue = nil
        // S7: Not Now on a failed import commit, its copy isn't coming back
        if let copy = pendingImportCopy {
            pendingImportCopy = nil
            ImportedMapStorage.unlink(copy)
            InFlightImportFiles.unregister(copy)
        }
    }

    /// Build the candidate state, write it (the single commit point), then
    /// publish. A failed write publishes nothing and offers Retry.
    private func execute(_ t: LibraryTransition, reportFailure: Bool = true,
                         publish: @escaping (LibraryState, [ImportedMapEntry]) -> Void) -> Bool {
        guard libraryStatus == .loaded, let current = library else {
            reportIssue(Messages.displayTheSavedBasemapIsLockedOrUnreadableUnlockMissionMessage()) { [weak self] in
                guard let self, self.restoreActiveMapSelection() == .restored else { return false }
                return self.execute(t, publish: publish)
            }
            return false
        }
        let next: LibraryState, removed: [ImportedMapEntry]
        do {
            (next, removed) = try LibraryReducer.apply(t, to: current)
        } catch {
            NSLog("[MapVM] library transition refused: \(error)")
            return false
        }
        do {
            try libraryDependencies.write(next)
        } catch {
            guard reportFailure else { return false }
            let retry: () -> Bool = { [weak self] in self?.execute(t, publish: publish) ?? false }
            // the auth-bound key relocked under this (app left the front). the
            // unlock's restore clears the Retry below, park it so it runs after
            // that restore instead. an import commit vanished otherwise (DL-4)
            if DataKey.failedBehindRelock(error) { relockedRetries.append(retry) }
            reportIssue(Messages.displayTheBasemapChoiceCouldNotBeSavedThePreviousMessage(), retry: retry)
            return false
        }
        removeKnownSupersededBakes(from: current, to: next)
        library = next
        pendingRetry = nil
        mapSelectionPersistenceIssue = nil
        publish(next, removed)
        if next.permitsCleanup { _ = libraryDependencies.reconcile(next) }
        return true
    }

    private func removeKnownSupersededBakes(from previous: LibraryState, to next: LibraryState) {
        guard !next.permitsCleanup else { return }
        let kept = ImportedMapLibrary.bakeFileNames(next)
        for entry in previous.entries {
            if let bake = entry.pdf?.validBake, !kept.contains(bake.fileName) {
                libraryDependencies.removeBakeFile(bake)
            }
        }
    }

    private func reportIssue(_ message: LocalizedMessage, retry: @escaping () -> Bool) {
        pendingRetry = retry
        mapSelectionPersistenceIssue = MapSelectionPersistenceIssue(id: UUID(), pendingMessage: message)
    }

    private func publishMapSource(_ source: MapSource, reframe: Bool = true) {
        NSLog("[MapVM] map source changed -> kind=\(source.kind)")
        // M2: whatever goes up now is newer than a pack still opening
        pendingPackOpen = nil
        let previousSource = mapSource
        mapSource = source
        if let online = source as? OnlineRasterBasemapSource { lastOnlineStyle = online.style }
        if !(source is PDFMapSource) { pdfRuntime.reset() }
        if previousSource !== source {
            // replaced before its first draw settled: the guard clears here too
            (previousSource as? OfflineTileMapSource)?.closeForDeletion()
        }
        // M1: the no read clock of a guarded pack starts once it's on screen
        (source as? OfflineTileMapSource)?.firstDraw?.start()
        if reframe { frameCamera(for: source, userLocation: lastUserCoordinate) }
    }

    private static func isApproximatelyEqual(_ a: CLLocationCoordinate2D,
                                              _ b: CLLocationCoordinate2D) -> Bool {
        abs(a.latitude - b.latitude)   < 0.0001 &&
        abs(a.longitude - b.longitude) < 0.0001
    }

    private func fetchElevation(for coord: CLLocationCoordinate2D) {
        guard OpsecSettings.shared.onlineLookups else {
            centreElevationReading = nil
            return
        }
        elevationTask?.cancel()
        elevationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let reading = await self.elevationService.reading(for: coord)
            // Only commit if camera hasn't moved since we fired the request.
            if !Task.isCancelled, OpsecSettings.shared.onlineLookups,
               Self.isApproximatelyEqual(self.cameraCentre, coord) {
                self.centreElevationReading = reading
            }
        }
    }

    // MARK: - Header content

    var headerMGRS: String {
        MGRSFormatter.string(from: headerCoordinate)
    }

    var headerWGS84: String {
        Self.wgs84Text(for: headerCoordinate)
    }

    static func wgs84Text(for c: CLLocationCoordinate2D) -> String {
        String(format: "%.5f° %@, %.5f° %@",
               abs(c.latitude),  c.latitude  >= 0 ? "N" : "S",
               abs(c.longitude), c.longitude >= 0 ? "E" : "W")
    }

    var headerUTM: String {
        MGRSFormatter.utm(from: headerCoordinate)
    }

    private var headerCoordinate: CLLocationCoordinate2D {
        if isBrowsing { return cameraCentre }
        return lastUserCoordinate ?? cameraCentre
    }

    private var lastUserCoordinate: CLLocationCoordinate2D?

    // MARK: - Inputs from the rest of the app

    func userLocationDidUpdate(_ location: CLLocation, resetOrientation: Bool = true) {
        lastUserCoordinate = location.coordinate
        // calibrating: the first fix must not yank the camera off the sheet. it
        // counts as used, else the next fix after Finish would do the yank instead
        if !hasInitialFix && calibrationActive {
            hasInitialFix = true
            return
        }
        if !hasInitialFix {
            hasInitialFix = true
            // Centre on user on first fix, but if a bounded PDF is active
            // and user is off it, keep the framing from import/restore
            // so the PDF doesnt get yanked away.
            if let coverage = mapSource.coverage,
               !coverage.contains(location.coordinate) {
                return
            }
            centreOnUser(location, resetOrientation: resetOrientation)
        }
    }

    /// Frame camera for a new or restored map source. Snaps to userLocation
    /// if inside coverage, otherwise frames the whole coverage area.
    /// No-op for unbounded sources (satellite etc).
    ///
    /// Used to be inline in the import path, pulled out so restore
    /// frames consistently too.
    func frameCamera(for source: MapSource, userLocation: CLLocationCoordinate2D?) {
        guard let coverage = source.coverage else { return }
        if let user = userLocation, coverage.contains(user) {
            cameraRequests.send(MKCoordinateRegion(
                center: user,
                latitudinalMeters: 1500,
                longitudinalMeters: 1500
            ))
        } else {
            cameraRequests.send(coverage)
        }
    }

    private var hasInitialFix = false

    func mapRegionDidChange(_ region: MKCoordinateRegion, animated: Bool, byUser: Bool) {
        cameraCentre = region.center
        if byUser { isBrowsing = true }
    }

    func mapCameraDidChange(heading: CLLocationDirection) {
        let normalized = MapHeading.normalized(heading)
        if abs(self.heading - normalized) > 0.05 {
            self.heading = normalized
        }
    }

    func centreOnUser(_ location: CLLocation?, resetOrientation: Bool = true) {
        guard let coord = location?.coordinate ?? lastUserCoordinate else { return }
        let region = MKCoordinateRegion(
            center: coord,
            latitudinalMeters: 1500,
            longitudinalMeters: 1500
        )
        isBrowsing = false
        cameraCentre = coord
        cameraRequests.send(region)
        // Preserve the live phone heading while recentering in Heading Up.
        if resetOrientation { resetNorthRequests.send(()) }
    }

    /// Re-frame the loaded offline/imported map's coverage. Paired with
    /// centreOnUser so that after panning off (or centring on a distant live
    /// location) the user can jump straight back to where the map actually is.
    /// No-op for unbounded online basemaps.
    func centreOnMap() {
        guard let coverage = mapSource.coverage else { return }
        isBrowsing = true
        cameraCentre = coverage.center
        cameraRequests.send(coverage)
    }

    func resetNorth() {
        resetNorthRequests.send(())
    }

    func orientMap(to heading: CLLocationDirection) {
        guard heading.isFinite else { return }
        headingRequests.send(MapHeading.normalized(heading))
    }
}

extension MKCoordinateRegion {
    /// True when coordinate falls inside this region's lat/lng span.
    /// Uses shortest angular distance in longitude so spans straddling
    /// the antimeridian (centre near +/-180) work correctly.
    func contains(_ coordinate: CLLocationCoordinate2D) -> Bool {
        if abs(coordinate.latitude - center.latitude) > span.latitudeDelta / 2 {
            return false
        }
        var dLng = abs(coordinate.longitude - center.longitude)
        if dLng > 180 { dLng = 360 - dLng }
        return dLng <= span.longitudeDelta / 2
    }
}
