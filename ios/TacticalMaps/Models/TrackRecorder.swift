import Foundation
import CoreLocation

/// One recorded fix on a GPX track.
struct TrackPoint {
    let coordinate: CLLocationCoordinate2D
    let elevation: Double?
    let time: Date
}

/// Accumulates GPS fixes into a track while recording. Fed by ContentView from
/// `LocationService.lastLocation`. Foreground + background (the latter only
/// while recording, via `LocationService.setBackgroundUpdates`).
final class TrackRecorder: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var points: [TrackPoint] = []

    /// Minimum spacing between stored fixes — drops GPS jitter so a stationary
    /// device doesn't bloat the track.
    private let minSpacingMetres: Double = 2

    func start() {
        points = []
        isRecording = true
    }

    func stop() {
        isRecording = false
    }

    func ingest(_ location: CLLocation) {
        guard isRecording else { return }
        if let last = points.last {
            let prev = CLLocation(latitude: last.coordinate.latitude,
                                  longitude: last.coordinate.longitude)
            if prev.distance(from: location) < minSpacingMetres { return }
        }
        points.append(TrackPoint(
            coordinate: location.coordinate,
            // verticalAccuracy < 0 means altitude is invalid.
            elevation: location.verticalAccuracy >= 0 ? location.altitude : nil,
            time: location.timestamp
        ))
    }
}
