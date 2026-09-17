import Foundation
import CoreLocation
import MapKit
import Combine

struct MapSelectionPersistenceIssue: Identifiable, Equatable {
    let id: UUID
    let pendingMessage: LocalizedMessage
    var message: String { pendingMessage.text }
}

enum MapSelectionCommitFailure: Equatable {
    case pdfSession
    case selector
    case pdfSessionRollback
}

enum MapSelectionCommitResult: Equatable {
    case succeeded
    case failed(MapSelectionCommitFailure)
}

/// Single persist-before-publication boundary for every active-map change.
/// Generic source values keep the ordering contract independently testable
/// without replacing production MapSource implementations with test doubles.
final class ActiveMapSelectionCommitCoordinator<Source> {
    private let persistSelection: (Source, Bool) -> Bool
    private let publish: (Source) -> Void

    init(persistSelection: @escaping (Source, Bool) -> Bool,
         publish: @escaping (Source) -> Void) {
        self.persistSelection = persistSelection
        self.publish = publish
    }

    func select(_ source: Source,
                clearRetained: Bool = false) -> MapSelectionCommitResult {
        guard persistSelection(source, clearRetained) else {
            return .failed(.selector)
        }
        publish(source)
        return .succeeded
    }

    func activatePDF(_ source: Source,
                     persistSession: () -> Bool,
                     rollbackSession: () -> Bool) -> MapSelectionCommitResult {
        // PDF metadata and the active/retained selector live in separate sealed
        // stores. Session-first prevents a durable `.pdf` selector from ever
        // pointing at missing metadata; an in-process selector failure restores
        // the exact prior encrypted session. A process kill between these two
        // writes is the bounded crash window: no uncommitted source reaches the
        // running UI, but v2's generic `.pdf` selector cannot distinguish two
        // PDF sessions if the previous active source was also a PDF. The test
        // below locks this ordering down; closing that process-death-only window
        // fully would require a future journaled schema shared by both stores.
        guard persistSession() else {
            return .failed(rollbackSession() ? .pdfSession : .pdfSessionRollback)
        }
        guard persistSelection(source, false) else {
            return .failed(rollbackSession() ? .selector : .pdfSessionRollback)
        }
        publish(source)
        return .succeeded
    }

    /// Cold restore publishes a source only after the persistence adapter has
    /// authenticated and resolved its already-durable descriptor.
    func publishRestored(_ source: Source) {
        publish(source)
    }
}

struct MapSelectionDependencies {
    var persistSelection: (MapSource, Bool) -> Bool
    var restoreActive: () -> ActiveMapSelectionStore.RestoreResult
    var restoreRetained: () -> ActiveMapSelectionStore.RetainedRestoreResult
    var removeRetained: (Bool) throws -> Void
    var snapshotPDFSession: () -> PDFSessionStore.ActiveSessionSnapshot
    var persistPDFSession: (PDFMapSource) -> Bool
    var restorePDFSession: (PDFSessionStore.ActiveSessionSnapshot) -> Bool
    var reconcileManagedMapFiles: () -> Bool = { false }

    static let live = MapSelectionDependencies(
        persistSelection: { ActiveMapSelectionStore.save($0, clearRetained: $1) },
        restoreActive: ActiveMapSelectionStore.restore,
        restoreRetained: ActiveMapSelectionStore.restoreRetained,
        removeRetained: ActiveMapSelectionStore.removeRetainedMap,
        snapshotPDFSession: PDFSessionStore.snapshotActiveSession,
        persistPDFSession: PDFSessionStore.save,
        restorePDFSession: PDFSessionStore.restoreActiveSession,
        reconcileManagedMapFiles: ActiveMapSelectionStore.reconcileManagedImportedMapFiles
    )
}

private enum PendingMapSelectionTransition {
    case activate(MapSource, pdfSessionAlreadyPersisted: Bool)
    case restoreActive
    case deleteImported(MapSource, OnlineRasterBasemapSource)
    case removeUnavailableRetainedEntry
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

    // MARK: - Dependencies

    private let elevationService = ElevationService()
    private var elevationCancellable: AnyCancellable?
    private var elevationTask: Task<Void, Never>?
    private let mapSelectionDependencies: MapSelectionDependencies
    private var pendingMapSelectionTransition: PendingMapSelectionTransition?
    private lazy var mapSelectionCoordinator = ActiveMapSelectionCommitCoordinator<MapSource>(
        persistSelection: mapSelectionDependencies.persistSelection,
        publish: { [weak self] source in self?.publishMapSource(source) }
    )

    init(mapSelectionDependencies: MapSelectionDependencies = .live,
         initialMapSource: MapSource = OnlineRasterBasemapSource.makeDefault()) {
        self.mapSelectionDependencies = mapSelectionDependencies
        self.mapSource = initialMapSource
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

    // MARK: - Durable map selection

    /// Select/import a source. PDF metadata and the selector are committed as
    /// one publication transaction; MBTiles sandbox validation remains inside
    /// ActiveMapSelectionStore before its descriptor can become active.
    @discardableResult
    func selectMapSource(_ source: MapSource) -> Bool {
        executeMapSelectionTransition(
            .activate(source, pdfSessionAlreadyPersisted: false)
        )
    }

    @discardableResult
    func selectOnlineBasemap(_ style: BasemapStyle) -> Bool {
        selectMapSource(OnlineRasterBasemapSource(style))
    }

    @discardableResult
    func restoreRetainedMap(_ source: MapSource) -> Bool {
        executeMapSelectionTransition(
            .activate(source, pdfSessionAlreadyPersisted: source is PDFMapSource)
        )
    }

    /// Authenticates/resolves the durable active descriptor before publication.
    /// Locked or corrupt storage leaves the known-good in-memory default visible
    /// and exposes the same retry transition used by write failures.
    @discardableResult
    func restoreActiveMapSelection() -> MapSource? {
        switch mapSelectionDependencies.restoreActive() {
        case .restored(let source):
            mapSelectionCoordinator.publishRestored(source)
            pendingMapSelectionTransition = nil
            mapSelectionPersistenceIssue = nil
            _ = mapSelectionDependencies.reconcileManagedMapFiles()
            return source
        case .noSelection:
            pendingMapSelectionTransition = nil
            _ = mapSelectionDependencies.reconcileManagedMapFiles()
            return nil
        case .unavailable:
            reportMapSelectionIssue(
                transition: .restoreActive,
                message: Messages.displayTheSavedBasemapIsLockedOrUnreadableUnlockMissionMessage()
            )
            return nil
        }
    }

    func restoreRetainedMapSelection() -> ActiveMapSelectionStore.RetainedRestoreResult {
        let result = mapSelectionDependencies.restoreRetained()
        _ = mapSelectionDependencies.reconcileManagedMapFiles()
        return result
    }

    /// Switches to an online source durably before releasing an active MBTiles
    /// SQLite handle. Only then may the store validate and delete its managed
    /// backing file.
    @discardableResult
    func deleteRetainedImportedMap(_ retainedSource: MapSource,
                                   returningTo style: BasemapStyle = OnlineRasterBasemapSource.defaultStyle) -> Bool {
        executeMapSelectionTransition(
            .deleteImported(retainedSource, OnlineRasterBasemapSource(style))
        )
    }

    @discardableResult
    func removeUnavailableRetainedMapEntry() -> Bool {
        executeMapSelectionTransition(.removeUnavailableRetainedEntry)
    }

    @discardableResult
    func retryMapSelectionPersistence() -> Bool {
        guard let pendingMapSelectionTransition else { return false }
        return executeMapSelectionTransition(pendingMapSelectionTransition)
    }

    func dismissMapSelectionPersistenceIssue() {
        mapSelectionPersistenceIssue = nil
    }

    private func executeMapSelectionTransition(_ transition: PendingMapSelectionTransition) -> Bool {
        let outcome: MapSelectionCommitResult
        switch transition {
        case .activate(let source, let pdfSessionAlreadyPersisted):
            if let pdf = source as? PDFMapSource, !pdfSessionAlreadyPersisted {
                let snapshot = mapSelectionDependencies.snapshotPDFSession()
                outcome = mapSelectionCoordinator.activatePDF(
                    source,
                    persistSession: { self.mapSelectionDependencies.persistPDFSession(pdf) },
                    rollbackSession: {
                        self.mapSelectionDependencies.restorePDFSession(snapshot)
                    }
                )
            } else {
                outcome = mapSelectionCoordinator.select(source)
            }

        case .restoreActive:
            return restoreActiveMapSelection() != nil

        case .deleteImported(let retainedSource, let onlineSource):
            // If the imported source is visible, commit and publish online first
            // so its renderer releases the SQLite/PDF resource before deletion.
            if mapSource is PDFMapSource || mapSource is OfflineTileMapSource {
                let switchOutcome = mapSelectionCoordinator.select(onlineSource)
                guard switchOutcome == .succeeded else {
                    reportMapSelectionFailure(switchOutcome, transition: transition)
                    return false
                }
            }
            (retainedSource as? OfflineTileMapSource)?.closeForDeletion()
            do {
                try mapSelectionDependencies.removeRetained(true)
                _ = mapSelectionDependencies.reconcileManagedMapFiles()
                pendingMapSelectionTransition = nil
                mapSelectionPersistenceIssue = nil
                return true
            } catch {
                reportMapSelectionIssue(
                    transition: transition,
                    message: Messages.displayTheImportedMapCouldNotBeDeletedSecurelyTheMessage("").withArgument(0, error.displayMessage)
                )
                return false
            }

        case .removeUnavailableRetainedEntry:
            do {
                try mapSelectionDependencies.removeRetained(false)
                _ = mapSelectionDependencies.reconcileManagedMapFiles()
                pendingMapSelectionTransition = nil
                mapSelectionPersistenceIssue = nil
                return true
            } catch {
                reportMapSelectionIssue(
                    transition: transition,
                    message: Messages.displayTheUnavailableSavedMapEntryCouldNotBeRemovedMessage("").withArgument(0, error.displayMessage)
                )
                return false
            }
        }

        guard outcome == .succeeded else {
            reportMapSelectionFailure(outcome, transition: transition)
            return false
        }
        pendingMapSelectionTransition = nil
        mapSelectionPersistenceIssue = nil
        _ = mapSelectionDependencies.reconcileManagedMapFiles()
        return true
    }

    private func reportMapSelectionFailure(_ outcome: MapSelectionCommitResult,
                                           transition: PendingMapSelectionTransition) {
        guard case .failed(let reason) = outcome else { return }
        let message: LocalizedMessage
        switch reason {
        case .pdfSession:
            message = Messages.displayThePdfMapCouldNotBeSavedForRelaunchMessage()
        case .selector:
            message = Messages.displayTheBasemapChoiceCouldNotBeSavedThePreviousMessage()
        case .pdfSessionRollback:
            message = Messages.displayMapStorageRecoveryDidNotCompleteKeepTheAppMessage()
        }
        reportMapSelectionIssue(transition: transition, message: message)
    }

    private func reportMapSelectionIssue(transition: PendingMapSelectionTransition,
                                         message: LocalizedMessage) {
        pendingMapSelectionTransition = transition
        mapSelectionPersistenceIssue = MapSelectionPersistenceIssue(
            id: UUID(),
            pendingMessage: message
        )
    }

    private func publishMapSource(_ source: MapSource) {
        NSLog("[MapVM] map source changed -> kind=\(source.kind)")
        let previousSource = mapSource
        mapSource = source
        if previousSource !== source {
            (previousSource as? OfflineTileMapSource)?.closeForDeletion()
        }
        frameCamera(for: source, userLocation: lastUserCoordinate)
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
        let c = headerCoordinate
        return String(format: "%.5f° %@, %.5f° %@",
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
