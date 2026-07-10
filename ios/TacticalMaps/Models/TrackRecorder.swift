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

    /// Non-nil when a fix couldn't be written to disk. The UI has to say so: a
    /// recording that looks live but isn't hitting the disk is the worst
    /// possible failure for a field tool.
    @Published var persistError: String?

    /// Bound in as AEAD associated data. Matches Android's TrackLog.LABEL.
    private static let label = "tracks/recording.ndjson"

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
        persistError = nil
        // Nuke any existing log and start fresh. Sealed per line, and the
        // platform protection class still lets us append with the screen off.
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

    /// Each line is sealed on its own rather than sealing the whole file, for
    /// two reasons. Appending to a whole-file envelope would mean decrypt-all,
    /// re-encrypt-all, rewrite-all on every single GPS fix, which is O(n^2) over
    /// a patrol. And per-line sealing keeps the property we actually care about:
    /// a torn or garbled line fails its tag check and gets skipped, and every
    /// other line still opens. One bad byte costs you one fix, not the track.
    ///
    /// So we don't bind line ordering into the AAD. Someone with write access to
    /// the app sandbox could reorder or drop lines undetected. Took that trade
    /// knowingly: binding the index would make one torn line invalidate the whole
    /// tail, and an attacker inside our sandbox has already beaten at-rest crypto.
    private func append(_ point: TrackPoint) {
        let stored = StoredPoint(lat: point.coordinate.latitude,
                                 lon: point.coordinate.longitude,
                                 ele: point.elevation,
                                 t: point.time.timeIntervalSince1970)
        do {
            let json = try JSONEncoder().encode(stored)
            let sealed = try SealedEnvelope.sealLine(key: try SafeStore.keyProvider(),
                                                     plaintext: json,
                                                     label: Self.label)
            guard let line = (sealed + "\n").data(using: .utf8) else { return }
            if !FileManager.default.fileExists(atPath: fileURL.path) {
                FileManager.default.createFile(atPath: fileURL.path, contents: nil,
                    attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            }
            guard let handle = try? FileHandle(forWritingTo: fileURL) else {
                persistError = "Track fix not saved to disk: could not open the log."
                return
            }
            defer { try? handle.close() }
            handle.seekToEndOfFile()
            handle.write(line)
            try handle.synchronize() // fsync, force to stable storage
            persistError = nil
        } catch {
            persistError = "Track fix not saved to disk: \(error.localizedDescription)"
        }
    }

    private func recover() {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return }
        let key: Data
        do {
            key = try SafeStore.keyProvider()
        } catch {
            // Locked. The log is intact, we just can't read it yet. Say nothing
            // about "no track" because we genuinely don't know.
            persistError = "Saved track is encrypted and locked. \(error.localizedDescription)"
            return
        }

        let decoder = JSONDecoder()
        var sawLegacyLine = false
        let restored: [TrackPoint] = text.split(separator: "\n").compactMap { raw in
            let line = String(raw)
            let json: Data?
            if SealedEnvelope.isSealedLine(line) {
                json = SealedEnvelope.openLine(key: key, line: line, label: Self.label)
            } else {
                sawLegacyLine = true // written by a pre-encryption build
                json = line.data(using: .utf8)
            }
            guard let json, let sp = try? decoder.decode(StoredPoint.self, from: json) else { return nil }
            return TrackPoint(coordinate: CLLocationCoordinate2D(latitude: sp.lat, longitude: sp.lon),
                              elevation: sp.ele,
                              time: Date(timeIntervalSince1970: sp.t))
        }
        if !restored.isEmpty {
            points = restored
            recovered = true
        }
        if sawLegacyLine { reseal(restored) }
    }

    /// Rewrite a plaintext log from an older build with every line sealed. Goes
    /// via atomic write so a crash halfway can't leave us half a track.
    private func reseal(_ points: [TrackPoint]) {
        do {
            let key = try SafeStore.keyProvider()
            let encoder = JSONEncoder()
            var out = Data()
            for point in points {
                let stored = StoredPoint(lat: point.coordinate.latitude,
                                         lon: point.coordinate.longitude,
                                         ele: point.elevation,
                                         t: point.time.timeIntervalSince1970)
                let sealed = try SealedEnvelope.sealLine(key: key,
                                                         plaintext: try encoder.encode(stored),
                                                         label: Self.label)
                out.append(contentsOf: Array((sealed + "\n").utf8))
            }
            try out.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            persistError = "Could not encrypt the recovered track: \(error.localizedDescription)"
        }
    }
}
