import Foundation
import CoreLocation
import MapKit
import Combine

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
    @Published var mapSource: MapSource = OnlineRasterBasemapSource.makeDefault() {
        didSet { NSLog("[MapVM] mapSource changed -> kind=\(mapSource.kind) name=\(mapSource.displayName)") }
    }

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

    // MARK: - Dependencies

    private let elevationService = ElevationService()
    private var elevationCancellable: AnyCancellable?

    init() {
        // Debounce camera-centre changes, only hit the DEM once user
        // stops panning for 400ms. Skips no-op changes (<0.0001deg ~ 11m).
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
            let reading = await self.elevationService.reading(for: coord)
            // Only commit if camera hasn't moved since we fired the request.
            if Self.isApproximatelyEqual(self.cameraCentre, coord) {
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

    func userLocationDidUpdate(_ location: CLLocation) {
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
            centreOnUser(location)
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
        // Re-orient north when recentering, so the map is always readable
        // north-up after a "Centre on My Location".
        resetNorthRequests.send(())
    }

    func resetNorth() {
        resetNorthRequests.send(())
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
