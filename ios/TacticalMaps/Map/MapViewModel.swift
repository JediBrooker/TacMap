import Foundation
import CoreLocation
import MapKit
import Combine

/// Owns map camera state, browse-mode toggle, MGRS readout, compass heading,
/// and crosshair-elevation lookups.
///
/// Browse mode = user has panned/zoomed away from their own position. While
/// browsing the header reads the **map centre**; otherwise it reads the user.
final class MapViewModel: ObservableObject {

    // MARK: - Published state

    @Published var cameraCentre: CLLocationCoordinate2D = .init(latitude: 0, longitude: 0)
    @Published var heading: CLLocationDirection = 0
    @Published var isBrowsing: Bool = false
    @Published var mapSource: MapSource = AppleSatelliteMapSource() {
        didSet { NSLog("[MapVM] mapSource changed -> kind=\(mapSource.kind) name=\(mapSource.displayName)") }
    }

    /// Terrain elevation (metres) for the current `cameraCentre`. Fetched async
    /// from Open-Meteo — see `ElevationService`. Stays at its previous value
    /// while a new request is in flight (so panning doesn’t flash “—”), and is
    /// cleared to nil only when the centre changes by more than ~10m.
    @Published var centreElevation: Double? = nil

    /// ID of the currently-selected waypoint of any kind (generic,
    /// military, or tactical control measure). Set by the map's
    /// `didSelect` delegate. Drives the floating controls card in
    /// `ContentView`. nil = no selection.
    @Published var selectedWaypointID: UUID? = nil

    /// Current map metres-per-point (the smaller number is more zoomed in).
    /// Updated by `MapContainerView.Coordinator` whenever the camera
    /// changes. Drives `defaultControlMeasureScale` so newly-placed
    /// tactical symbols enter at a screen-relative size that matches
    /// the current zoom level.
    @Published var currentMetresPerPoint: Double = 1.0

    /// The waypoint scale value that, applied to a newly-placed tactical
    /// control measure, makes the symbol render at roughly 10% of the
    /// screen height at the *current* zoom level. The symbol then keeps
    /// its geographic footprint as the user zooms in / out (so its
    /// on-screen size scales naturally with the map).
    ///
    /// Math: the renderer produces a 64pt-wide bitmap (baseSize). The
    /// annotation view's transform scale is `waypoint.scale * zoomScale`
    /// where `zoomScale = 1.0 / metresPerPoint` (referenceMetresPerPoint
    /// = 1.0). We want final pixel width ≈ 80pt (≈10% of an 800pt
    /// screen), so:
    ///   80 = 64 * waypoint.scale * (1.0 / metresPerPoint)
    ///   waypoint.scale = (80 / 64) * metresPerPoint ≈ 1.25 * metresPerPoint
    var defaultControlMeasureScale: Double {
        let raw = 1.25 * currentMetresPerPoint
        // Clamp to the slider range so the default is always editable.
        return max(0.1, min(raw, 20.0))
    }

    // MARK: - Camera signal channels

    let cameraRequests     = PassthroughSubject<MKCoordinateRegion, Never>()
    let resetNorthRequests = PassthroughSubject<Void, Never>()

    // MARK: - Dependencies

    private let elevationService = ElevationService()
    private var elevationCancellable: AnyCancellable?

    init() {
        // Debounce camera-centre changes; only ask the DEM once the user has
        // stopped panning for 400ms. Skips no-op changes (<0.0001° ≈ 11m).
        elevationCancellable = $cameraCentre
            .removeDuplicates(by: Self.isApproximatelyEqual)
            .filter { !($0.latitude == 0 && $0.longitude == 0) }
            .debounce(for: .seconds(0.4), scheduler: DispatchQueue.main)
            .sink { [weak self] coord in
                self?.fetchElevation(for: coord)
            }
    }

    private static func isApproximatelyEqual(_ a: CLLocationCoordinate2D,
                                              _ b: CLLocationCoordinate2D) -> Bool {
        abs(a.latitude - b.latitude)   < 0.0001 &&
        abs(a.longitude - b.longitude) < 0.0001
    }

    private func fetchElevation(for coord: CLLocationCoordinate2D) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let value = await self.elevationService.elevation(for: coord)
            // Guard against stale responses: only commit if the camera hasn’t
            // moved meaningfully since we kicked off the request.
            if Self.isApproximatelyEqual(self.cameraCentre, coord) {
                self.centreElevation = value
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

    private var headerCoordinate: CLLocationCoordinate2D {
        if isBrowsing { return cameraCentre }
        return lastUserCoordinate ?? cameraCentre
    }

    private var lastUserCoordinate: CLLocationCoordinate2D?

    // MARK: - Inputs from the rest of the app

    func userLocationDidUpdate(_ location: CLLocation) {
        lastUserCoordinate = location.coordinate
        if !hasInitialFix {
            hasInitialFix = true
            centreOnUser(location)
        }
    }

    private var hasInitialFix = false

    func mapRegionDidChange(_ region: MKCoordinateRegion, animated: Bool, byUser: Bool) {
        cameraCentre = region.center
        if byUser { isBrowsing = true }
    }

    func mapCameraDidChange(heading: CLLocationDirection) {
        if abs(self.heading - heading) > 0.05 {
            self.heading = heading
        }
    }

    func centreOnUser(_ location: CLLocation?) {
        guard let coord = location?.coordinate ?? lastUserCoordinate else { return }
        let region = MKCoordinateRegion(
            center: coord,
            latitudinalMeters: 1500,
            longitudinalMeters: 1500
        )
        isBrowsing = false
        cameraCentre = coord
        cameraRequests.send(region)
    }

    func resetNorth() {
        resetNorthRequests.send(())
    }
}
