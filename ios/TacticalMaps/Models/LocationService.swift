import Foundation
import CoreLocation
import Combine
import UIKit

enum LiveLocationPermissionPolicy {
    enum Action: Equatable {
        case requestPermission
        case centreOnLocation
        case openSettings
    }

    struct Control: Equatable {
        let title: String
        let systemImage: String
        let guidance: String
        let action: Action
    }

    static func shouldStartUpdates(for status: CLAuthorizationStatus) -> Bool {
        status == .authorizedAlways || status == .authorizedWhenInUse
    }

    static func shouldRequestOnInitialAppearance(for status: CLAuthorizationStatus) -> Bool {
        status == .notDetermined
    }

    static func control(for status: CLAuthorizationStatus) -> Control {
        switch status {
        case .notDetermined:
            return Control(
                title: "Enable Live Location",
                systemImage: "location.circle",
                guidance: "TacMap requests Location access on first launch. Tap to request it again if needed.",
                action: .requestPermission
            )
        case .authorizedAlways, .authorizedWhenInUse:
            return Control(
                title: "Centre on My Location",
                systemImage: "location.viewfinder",
                guidance: "Centres the map on your latest location.",
                action: .centreOnLocation
            )
        case .denied:
            return Control(
                title: "Open Location Settings",
                systemImage: "gearshape",
                guidance: "Location access is off. Opens Settings so you can enable it.",
                action: .openSettings
            )
        case .restricted:
            return Control(
                title: "Location Restricted",
                systemImage: "location.slash",
                guidance: "Location access is restricted. Review this device's Location settings.",
                action: .openSettings
            )
        @unknown default:
            return Control(
                title: "Location Settings",
                systemImage: "gearshape",
                guidance: "Review Location access in Settings.",
                action: .openSettings
            )
        }
    }
}

enum BackgroundLocationUse: Hashable {
    case trackRecording
    case unitSync
}

enum HeadingNorthReference: Equatable {
    case trueNorth
    case magneticNorth

    var displaySuffix: String {
        switch self {
        case .trueNorth: "T"
        case .magneticNorth: "M"
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .trueNorth: "true north"
        case .magneticNorth: "magnetic north"
        }
    }
}

/// Resolves competing background-location owners without letting one feature's
/// stop action disable another feature that is still active.
struct BackgroundLocationPolicy: Equatable {
    let enabled: Bool
    let desiredAccuracy: CLLocationAccuracy
    let distanceFilter: CLLocationDistance

    static func resolve(_ uses: Set<BackgroundLocationUse>) -> Self {
        if uses.contains(.trackRecording) {
            return Self(
                enabled: true,
                desiredAccuracy: kCLLocationAccuracyBest,
                distanceFilter: kCLDistanceFilterNone
            )
        }
        if uses.contains(.unitSync) {
            // Screen-off presence does not need track-grade fixes. A coarser
            // request and meaningful movement filter reduce GPS power while
            // Core Location keeps the foreground-started session alive.
            return Self(
                enabled: true,
                desiredAccuracy: kCLLocationAccuracyHundredMeters,
                distanceFilter: 50
            )
        }
        return Self(
            enabled: false,
            desiredAccuracy: kCLLocationAccuracyBest,
            distanceFilter: kCLDistanceFilterNone
        )
    }
}

/// Debounces forced refreshes when Core Location has stopped delivering new
/// fixes for a stationary device. `startUpdatingLocation()` is movement driven,
/// so a simulator (and sometimes a stationary phone) can otherwise keep one
/// cached fix indefinitely even though location services remain active.
struct LocationFixRefreshPolicy: Equatable {
    static let minimumRetryInterval: TimeInterval = 15

    private(set) var lastRestartUptime: TimeInterval?

    mutating func shouldRestart(
        fixTimestamp: Date?,
        maximumAge: TimeInterval,
        maximumFutureSkew: TimeInterval,
        now: Date = Date(),
        uptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> Bool {
        if let fixTimestamp {
            let age = now.timeIntervalSince(fixTimestamp)
            if age >= -maximumFutureSkew, age <= maximumAge { return false }
        }
        if let lastRestartUptime {
            let elapsed = uptime - lastRestartUptime
            if elapsed >= 0, elapsed < Self.minimumRetryInterval { return false }
        }
        lastRestartUptime = uptime
        return true
    }

}

/// Lightweight wrapper around CLLocationManager that publishes the most recent fix.
final class LocationService: NSObject, ObservableObject {
    private let manager = CLLocationManager()
    private var backgroundUses = Set<BackgroundLocationUse>()
    private var fixRefreshPolicy = LocationFixRefreshPolicy()

    @Published var lastLocation: CLLocation?
    @Published var lastAccuracy: CLLocationAccuracy?
    @Published var lastSpeed: CLLocationSpeed?
    @Published var lastAltitude: CLLocationDistance?
    @Published var lastUpdate: Date?
    @Published var authorisationStatus: CLAuthorizationStatus = .notDetermined
    @Published private(set) var deviceHeading: CLLocationDirection?
    @Published private(set) var headingNorthReference: HeadingNorthReference?

    var isHeadingAvailable: Bool { CLLocationManager.headingAvailable() }

    private var headingUpdatesActive = false

    override init() {
        super.init()
        manager.delegate = self
        authorisationStatus = manager.authorizationStatus
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.activityType = .fitness
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestAuthorisation() {
        manager.requestWhenInUseAuthorization()
    }

    func start() {
        guard LiveLocationPermissionPolicy.shouldStartUpdates(for: authorisationStatus) else { return }
        manager.startUpdatingLocation()
    }
    func stop()  { manager.stopUpdatingLocation() }

    /// Force Core Location to obtain a new initial fix when its latest callback
    /// is getting old. Apple documents that repeated `startUpdatingLocation()`
    /// calls do not generate a new event, while stop/start does. The debounce
    /// prevents a temporarily unavailable GPS from being restarted every timer
    /// tick. Existing accuracy/background leases remain on the same manager.
    @discardableResult
    func refreshLocationIfNeeded(
        maximumAge: TimeInterval,
        maximumFutureSkew: TimeInterval,
        now: Date = Date(),
        uptime: TimeInterval = ProcessInfo.processInfo.systemUptime
    ) -> Bool {
        guard LiveLocationPermissionPolicy.shouldStartUpdates(for: authorisationStatus),
              fixRefreshPolicy.shouldRestart(
                fixTimestamp: lastLocation?.timestamp,
                maximumAge: maximumAge,
                maximumFutureSkew: maximumFutureSkew,
                now: now,
                uptime: uptime
              ) else { return false }
        manager.stopUpdatingLocation()
        manager.startUpdatingLocation()
        return true
    }

    /// Heading updates are independently leased from GPS updates so leaving
    /// Heading Up never interferes with track recording or Unit Sync.
    func startHeadingUpdates() {
        guard isHeadingAvailable, !headingUpdatesActive else { return }
        invalidateHeading()
        refreshHeadingOrientation()
        manager.headingFilter = 1
        manager.startUpdatingHeading()
        headingUpdatesActive = true
    }

    func stopHeadingUpdates() {
        guard headingUpdatesActive else { return }
        manager.stopUpdatingHeading()
        headingUpdatesActive = false
    }

    private func invalidateHeading() {
        deviceHeading = nil
        headingNorthReference = nil
    }

    /// Core Location reports headings relative to a configurable device edge.
    /// Keep that edge aligned with the top of this app in portrait/landscape.
    @discardableResult
    private func refreshHeadingOrientation() -> Bool {
        let candidates = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .map { (state: $0.activationState, orientation: $0.interfaceOrientation) }
        guard let interfaceOrientation = Self.preferredInterfaceOrientation(from: candidates),
              let headingOrientation = Self.nextHeadingOrientation(
                current: manager.headingOrientation,
                interfaceOrientation: interfaceOrientation
              ) else { return false }
        manager.headingOrientation = headingOrientation
        return true
    }

    /// A foreground-active scene owns live map interaction. During transitions,
    /// fall back to foreground-inactive, but never borrow orientation from a
    /// background or merely connected scene.
    static func preferredInterfaceOrientation(
        from candidates: [(state: UIScene.ActivationState, orientation: UIInterfaceOrientation)]
    ) -> UIInterfaceOrientation? {
        candidates.first(where: { $0.state == .foregroundActive })?.orientation
            ?? candidates.first(where: { $0.state == .foregroundInactive })?.orientation
    }

    /// UI interface landscape names describe the content orientation; device
    /// orientation names describe the physical device turn, so they are inverse.
    static func headingOrientation(for orientation: UIInterfaceOrientation) -> CLDeviceOrientation? {
        switch orientation {
        case .portrait: .portrait
        case .portraitUpsideDown: .portraitUpsideDown
        case .landscapeLeft: .landscapeRight
        case .landscapeRight: .landscapeLeft
        default: nil
        }
    }

    /// Returns a value only when assigning it would change Core Location's
    /// reference edge. This keeps the delegate's discard decision testable.
    static func nextHeadingOrientation(
        current: CLDeviceOrientation,
        interfaceOrientation: UIInterfaceOrientation
    ) -> CLDeviceOrientation? {
        guard let desired = headingOrientation(for: interfaceOrientation),
              desired != current else { return nil }
        return desired
    }

    /// Recording's background-location lease. Unit Sync has an independent
    /// lease so stopping a recording cannot turn off opted-in screen-off sharing.
    func setBackgroundUpdates(_ enabled: Bool) {
        setBackgroundUpdates(enabled, for: .trackRecording)
    }

    /// Unit Sync's background-location lease. The caller enables this only
    /// while the app is inactive, a v3 room is joined, both OPSEC + presence
    /// sharing switches are on, and location permission remains available.
    func setUnitSyncBackgroundUpdates(_ enabled: Bool) {
        setBackgroundUpdates(enabled, for: .unitSync)
    }

    private func setBackgroundUpdates(_ enabled: Bool, for use: BackgroundLocationUse) {
        if enabled { backgroundUses.insert(use) }
        else { backgroundUses.remove(use) }
        applyBackgroundLocationPolicy()
    }

    private func applyBackgroundLocationPolicy() {
        let policy = BackgroundLocationPolicy.resolve(backgroundUses)
        manager.desiredAccuracy = policy.desiredAccuracy
        manager.distanceFilter = policy.distanceFilter
        manager.allowsBackgroundLocationUpdates = policy.enabled
        manager.showsBackgroundLocationIndicator = policy.enabled
    }
}

extension LocationService: CLLocationManagerDelegate {
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locs: [CLLocation]) {
        guard let loc = locs.last else { return }
        lastLocation = loc
        lastAccuracy = loc.horizontalAccuracy
        lastSpeed    = max(0, loc.speed)
        lastAltitude = loc.altitude
        lastUpdate   = loc.timestamp
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorisationStatus = manager.authorizationStatus
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        guard headingUpdatesActive else { return }
        guard newHeading.headingAccuracy >= 0,
              newHeading.headingAccuracy <= 45 else {
            invalidateHeading()
            return
        }
        // A sample delivered during an interface rotation was calculated using
        // the previous reference edge. Change the edge, discard that sample,
        // and consume only subsequent headings.
        if refreshHeadingOrientation() { return }
        // True north is coherent with the map whenever Core Location has a fix;
        // magnetic north remains a useful offline fallback when it does not.
        let hasTrueHeading = newHeading.trueHeading >= 0
        let raw = hasTrueHeading ? newHeading.trueHeading : newHeading.magneticHeading
        guard raw.isFinite else { return }
        let resolved = MapHeading.normalized(raw)
        let reference: HeadingNorthReference = hasTrueHeading ? .trueNorth : .magneticNorth
        if headingNorthReference != reference {
            headingNorthReference = reference
        }
        if deviceHeading.map({ abs(MapHeading.shortestDelta(from: $0, to: resolved)) >= 0.25 })
            ?? true {
            deviceHeading = resolved
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Just log it. UI handles this fine (header shows "No fix").
        print("[LocationService] error: \(error.localizedDescription)")
    }
}
