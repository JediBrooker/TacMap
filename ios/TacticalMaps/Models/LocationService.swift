import Foundation
import CoreLocation
import Combine

/// Lightweight wrapper around CLLocationManager that publishes the most recent fix.
final class LocationService: NSObject, ObservableObject {
    private let manager = CLLocationManager()

    @Published var lastLocation: CLLocation?
    @Published var lastAccuracy: CLLocationAccuracy?
    @Published var lastSpeed: CLLocationSpeed?
    @Published var lastAltitude: CLLocationDistance?
    @Published var lastUpdate: Date?
    @Published var authorisationStatus: CLAuthorizationStatus = .notDetermined

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.activityType = .fitness
        manager.pausesLocationUpdatesAutomatically = false
    }

    func requestAuthorisation() {
        manager.requestWhenInUseAuthorization()
    }

    func start() { manager.startUpdatingLocation() }
    func stop()  { manager.stopUpdatingLocation() }

    /// Enable background fixes only while a GPX track is recording. Requires
    /// UIBackgroundModes:[location] (declared in Info.plist) — toggling it off
    /// when not recording keeps us off background location the rest of the time.
    /// Safe under When-In-Use authorization (shows the system blue bar).
    func setBackgroundUpdates(_ enabled: Bool) {
        manager.allowsBackgroundLocationUpdates = enabled
        manager.showsBackgroundLocationIndicator = enabled
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

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Surface in console; UI degrades gracefully (header shows "No fix").
        print("[LocationService] error: \(error.localizedDescription)")
    }
}
