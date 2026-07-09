import Foundation
import CoreLocation

/// One recorded fix on a GPX track.
struct TrackPoint {
    let coordinate: CLLocationCoordinate2D
    let elevation: Double?
    let time: Date
}

/// Accumulates GPS fixes into a track while recording. Fed by ContentView from
/// `LocationService.lastLocation`. Foreground + background (background only
/// while recording, via `LocationService.setBackgroundUpdates`).
///
/// Each fix gets appended + fsync'd to
/// `Application Support/tracks/recording.ndjson` as it arrives, so if the
/// process dies (OOM kill, crash, reboot) we lose at most one in-flight fix,
/// not the whole track. On init we check for a leftover log from a previous
/// session that wasnt cleanly discarded and recover it.
final class TrackRecorder: ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var points: [TrackPoint] = []
    /// True when `points` were recovered from a previous, un-discarded session.
    @Published private(set) var recovered = false

    /// Min spacing between stored fixes. Drops GPS jitter so a stationary
    /// device doesn't bloat the track.
    private let minSpacingMetres: Double = 2

    private let fileURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        let dir = base.appendingPathComponent("tracks")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("recording.ndjson")
    }()

    /// On-disk line format. CLLocationCoordinate2D isn't Codable so we roll our own.
    private struct StoredPoint: Codable {
        let lat: Double
        let lon: Double
        let ele: Double?
        let t: Double // timeIntervalSince1970
    }

    init() { recover() }

    func start() {
        points = []
        recovered = false
        // Nuke any existing log and start fresh. Encrypted at rest but still
        // readable after first unlock so background recording works.
        try? Data().write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        isRecording = true
    }

    func stop() {
        isRecording = false
        // Keep the log around so a finished-but-unexported track survives
        // process death until user exports or discards.
    }

    /// Clear the current (recorded or recovered) track and remove its file.
    func discard() {
        points = []
        recovered = false
        isRecording = false
        try? FileManager.default.removeItem(at: fileURL)
    }

    func ingest(_ location: CLLocation) {
        guard isRecording else { return }
        if let last = points.last {
            let prev = CLLocation(latitude: last.coordinate.latitude,
                                  longitude: last.coordinate.longitude)
            if prev.distance(from: location) < minSpacingMetres { return }
        }
        let point = TrackPoint(
            coordinate: location.coordinate,
            // verticalAccuracy < 0 means altitude is invalid.
            elevation: location.verticalAccuracy >= 0 ? location.altitude : nil,
            time: location.timestamp
        )
        points.append(point)
        append(point) // persist before returning so a shown fix is durable
    }

    // MARK: - Persistence

    private func append(_ point: TrackPoint) {
        let stored = StoredPoint(lat: point.coordinate.latitude,
                                 lon: point.coordinate.longitude,
                                 ele: point.elevation,
                                 t: point.time.timeIntervalSince1970)
        guard var line = try? JSONEncoder().encode(stored) else { return }
        line.append(0x0A) // newline
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            FileManager.default.createFile(atPath: fileURL.path, contents: nil,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        }
        guard let handle = try? FileHandle(forWritingTo: fileURL) else { return }
        defer { try? handle.close() }
        handle.seekToEndOfFile()
        handle.write(line)
        try? handle.synchronize() // fsync, force to stable storage
    }

    private func recover() {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
        let decoder = JSONDecoder()
        let restored: [TrackPoint] = text.split(separator: "\n").compactMap { line in
            guard let data = line.data(using: .utf8),
                  let sp = try? decoder.decode(StoredPoint.self, from: data) else { return nil }
            return TrackPoint(coordinate: CLLocationCoordinate2D(latitude: sp.lat, longitude: sp.lon),
                              elevation: sp.ele,
                              time: Date(timeIntervalSince1970: sp.t))
        }
        if !restored.isEmpty {
            points = restored
            recovered = true
        }
    }
}
