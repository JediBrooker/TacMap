import Foundation
import CoreLocation

/// One recorded fix on a GPX track.
struct TrackPoint {
    let coordinate: CLLocationCoordinate2D
    let elevation: Double?
    let time: Date
}

/// Truthful UI state for the user-started recording workflow. Permission
/// prompts and durable log preparation are intentionally distinct from an
/// active recording, so neither can accidentally display `REC`.
final class RecordingCoordinator: ObservableObject {
    enum State: Equatable {
        case idle
        case awaitingPermission
        case starting
        case recording
        case interrupted(String)
    }

    struct Guidance: Identifiable, Equatable {
        let id = UUID()
        let message: String
        let offersSettings: Bool

        static func == (lhs: Guidance, rhs: Guidance) -> Bool {
            lhs.message == rhs.message && lhs.offersSettings == rhs.offersSettings
        }
    }

    @Published private(set) var state: State = .idle
    @Published var guidance: Guidance?

    private let requestAuthorization: () -> Void
    private let initializeDurableRecording: () -> Bool
    private let stopRecording: () -> Void
    private let setBackgroundUpdates: (Bool) -> Void
    private let recordingError: () -> String?

    init(
        requestAuthorization: @escaping () -> Void,
        initializeDurableRecording: @escaping () -> Bool,
        stopRecording: @escaping () -> Void,
        setBackgroundUpdates: @escaping (Bool) -> Void,
        recordingError: @escaping () -> String?
    ) {
        self.requestAuthorization = requestAuthorization
        self.initializeDurableRecording = initializeDurableRecording
        self.stopRecording = stopRecording
        self.setBackgroundUpdates = setBackgroundUpdates
        self.recordingError = recordingError
    }

    func toggle(authorization: CLAuthorizationStatus) {
        switch state {
        case .recording, .awaitingPermission:
            stop()
        case .starting:
            break
        case .idle, .interrupted:
            start(authorization: authorization)
        }
    }

    func start(authorization: CLAuthorizationStatus) {
        guard state != .recording, state != .starting else { return }
        guidance = nil
        switch authorization {
        case .notDetermined:
            guard state != .awaitingPermission else { return }
            state = .awaitingPermission
            requestAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            beginDurableStart()
        case .denied, .restricted:
            state = .idle
            guidance = Self.permissionGuidance(interrupted: false)
        @unknown default:
            state = .idle
            guidance = Self.permissionGuidance(interrupted: false)
        }
    }

    func authorizationChanged(_ authorization: CLAuthorizationStatus) {
        switch authorization {
        case .authorizedAlways, .authorizedWhenInUse:
            if state == .awaitingPermission { beginDurableStart() }
        case .denied, .restricted:
            if state == .recording || state == .starting {
                interruptForPermissionLoss()
            } else if state == .awaitingPermission {
                state = .idle
                guidance = Self.permissionGuidance(interrupted: false)
            }
        case .notDetermined:
            if state == .recording || state == .starting {
                interruptForPermissionLoss()
            }
        @unknown default:
            if state == .recording || state == .starting {
                interruptForPermissionLoss()
            }
        }
    }

    func recorderDidStopUnexpectedly(error: String?) {
        guard state == .recording else { return }
        setBackgroundUpdates(false)
        let message = error ?? L10n.text("Track recording was interrupted. The saved track was preserved.")
        state = .interrupted(message)
    }

    func stop() {
        let wasActive = state == .recording || state == .starting
        if wasActive { stopRecording() }
        setBackgroundUpdates(false)
        state = .idle
        guidance = nil
    }

    private func beginDurableStart() {
        guard state != .recording, state != .starting else { return }
        state = .starting
        guard initializeDurableRecording() else {
            setBackgroundUpdates(false)
            state = .interrupted(
                recordingError() ?? L10n.text("Track recording could not start. No recording is active.")
            )
            return
        }
        // TrackRecorder has created and fsync'd the log and captured its scoped
        // key at this point. Only now enable the conditional background mode
        // and publish the state from which the UI renders `REC`.
        setBackgroundUpdates(true)
        state = .recording
    }

    private func interruptForPermissionLoss() {
        stopRecording()
        setBackgroundUpdates(false)
        let message = L10n.text("Location access changed, so track recording stopped. Your saved track was preserved. Re-enable Location access in Settings to record again.")
        state = .interrupted(message)
        guidance = Guidance(message: message, offersSettings: true)
    }

    private static func permissionGuidance(interrupted: Bool) -> Guidance {
        Guidance(
            message: interrupted
                ? L10n.text("Track recording stopped because Location access is unavailable. Your saved track was preserved. Re-enable it in Settings.")
                : L10n.text("TacMap needs Location access to record a track. Allow access in Settings, then try again."),
            offersSettings: true
        )
    }
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
    @Published private(set) var requiresUnlock = false

    /// Bound in as AEAD associated data. Matches Android's TrackLog.LABEL.
    private static let label = "tracks/recording.ndjson"

    /// Min spacing between stored fixes. Drops GPS jitter so a stationary
    /// device doesn't bloat the track.
    private let minSpacingMetres: Double = 2
    private let maxRecoveryBytes = 64 * 1024 * 1024
    /// Scoped copy used only by a recording explicitly started by the user.
    /// The app may clear DataKey's global cache on background/App Lock without
    /// interrupting the already-authorised background recording.
    private var recordingKey: Data?

#if DEBUG
    /// Boolean-only test seam for scoped-key lifetime. Key material never
    /// crosses this boundary.
    var hasScopedRecordingKeyForTesting: Bool { recordingKey != nil }
#endif

    private let fileURL: URL
    private let attributeReader: (String) throws -> [FileAttributeKey: Any]
    private let textReader: (URL) throws -> String
    private let requiresSealedPolicy: (String, Data) throws -> Bool
    private let markSealedPolicy: (String, Data) throws -> Void

    private static func defaultFileURL() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory,
                                            in: .userDomainMask).first!
        let dir = base.appendingPathComponent("tracks")
        return dir.appendingPathComponent("recording.ndjson")
    }

    /// On-disk line format. CLLocationCoordinate2D isn't Codable so we roll our own.
    private struct StoredPoint: Codable {
        let lat: Double
        let lon: Double
        let ele: Double?
        let t: Double // timeIntervalSince1970
    }

    init(
        fileURL: URL? = nil,
        attributeReader: @escaping (String) throws -> [FileAttributeKey: Any] = {
            try FileManager.default.attributesOfItem(atPath: $0)
        },
        textReader: @escaping (URL) throws -> String = {
            try String(contentsOf: $0, encoding: .utf8)
        },
        requiresSealedPolicy: @escaping (String, Data) throws -> Bool = {
            try SealedMigrationPolicy.requiresSealed($0, key: $1)
        },
        markSealedPolicy: @escaping (String, Data) throws -> Void = {
            try SealedMigrationPolicy.markSealed($0, key: $1)
        }
    ) {
        self.fileURL = fileURL ?? Self.defaultFileURL()
        self.attributeReader = attributeReader
        self.textReader = textReader
        self.requiresSealedPolicy = requiresSealedPolicy
        self.markSealedPolicy = markSealedPolicy
        recover()
    }

    @discardableResult
    func start() -> Bool {
        do {
            let key = try SafeStore.keyProvider()
            let dir = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            do {
                let existingAttributes = try attributeReader(fileURL.path)
                guard let size = existingAttributes[.size] as? NSNumber else {
                    throw CocoaError(.fileReadUnknown)
                }
                guard size.int64Value == 0 else {
                    persistError = L10n.text("A saved track already exists. Export or discard it before starting a new recording.")
                    return false
                }
            } catch {
                // Explicit absence is the only state in which creating a new
                // log is safe. Permission/protection/metadata errors must not
                // silently become size zero and overwrite unknown bytes.
                guard Self.isNoSuchFile(error) else { throw error }
            }
            try markSealedPolicy(policyID, key)
            // Establish and fsync the durable log before telling the UI or
            // CLLocationManager that recording has begun.
            try Data().write(to: fileURL,
                             options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            let handle = try FileHandle(forWritingTo: fileURL)
            try handle.synchronize()
            try handle.close()
            recordingKey = key
            points = []
            recovered = false
            persistError = nil
            requiresUnlock = false
            isRecording = true
            return true
        } catch {
            recordingKey = nil
            isRecording = false
            persistError = L10n.text("Recording did not start: %1$@", error.localizedDescription)
            return false
        }
    }

    func stop() {
        isRecording = false
        recordingKey = nil
        // Keep the log around so a finished-but-unexported track survives
        // process death until user exports or discards.
    }

    /// Clear the current (recorded or recovered) track and remove its file.
    func discard() {
        points = []
        recovered = false
        isRecording = false
        recordingKey = nil
        try? FileManager.default.removeItem(at: fileURL)
        try? FileManager.default.removeItem(at: legacyMarkerURL)
    }

    func ingest(_ location: CLLocation) {
        guard isRecording else { return }
        if let last = points.last {
            let prev = CLLocation(latitude: last.coordinate.latitude,
                                  longitude: last.coordinate.longitude)
            if prev.distance(from: location) < minSpacingMetres { return }
        }
        guard location.coordinate.latitude.isFinite,
              location.coordinate.longitude.isFinite,
              abs(location.coordinate.latitude) <= 90,
              abs(location.coordinate.longitude) <= 180 else { return }
        let point = TrackPoint(
            coordinate: location.coordinate,
            // verticalAccuracy < 0 means altitude is invalid.
            elevation: location.verticalAccuracy >= 0 ? location.altitude : nil,
            time: location.timestamp
        )
        do {
            try append(point) // persist before a shown fix becomes observable
            points.append(point)
            persistError = nil
        } catch {
            persistError = L10n.text("Track recording stopped because a fix could not be saved.")
            isRecording = false
            recordingKey = nil
        }
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
    private func append(_ point: TrackPoint) throws {
        let stored = StoredPoint(lat: point.coordinate.latitude,
                                 lon: point.coordinate.longitude,
                                 ele: point.elevation,
                                 t: point.time.timeIntervalSince1970)
        guard let recordingKey else { throw DataKey.LockedError() }
        let json = try JSONEncoder().encode(stored)
        let sealed = try SealedEnvelope.sealLine(key: recordingKey,
                                                 plaintext: json,
                                                 label: Self.label)
        guard let line = (sealed + "\n").data(using: .utf8) else {
            throw CocoaError(.fileWriteInapplicableStringEncoding)
        }
        if !FileManager.default.fileExists(atPath: fileURL.path) {
            guard FileManager.default.createFile(atPath: fileURL.path, contents: nil,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) else {
                throw CocoaError(.fileWriteUnknown)
            }
        }
        let handle = try FileHandle(forWritingTo: fileURL)
        defer { try? handle.close() }
        handle.seekToEndOfFile()
        try handle.write(contentsOf: line)
        try handle.synchronize() // fsync, force to stable storage
    }

    private func recover() {
        let attrs: [FileAttributeKey: Any]
        do {
            attrs = try attributeReader(fileURL.path)
        } catch {
            if Self.isNoSuchFile(error) {
                requiresUnlock = false
                return
            }
            markRecoveryUnavailable(error)
            return
        }
        guard let size = attrs[.size] as? NSNumber, size.int64Value >= 0 else {
            markRecoveryUnavailable(CocoaError(.fileReadUnknown))
            return
        }
        guard size.int64Value <= Int64(maxRecoveryBytes) else {
            persistError = L10n.text("Saved track is too large to recover safely.")
            return
        }
        let text: String
        do {
            text = try textReader(fileURL)
        } catch {
            if Self.isNoSuchFile(error) {
                requiresUnlock = false
                return
            }
            markRecoveryUnavailable(error)
            return
        }
        let key: Data
        do {
            key = try SafeStore.keyProvider()
        } catch {
            // Locked. The log is intact, we just can't read it yet. Say nothing
            // about "no track" because we genuinely don't know.
            persistError = L10n.text("Saved track is encrypted and locked. %1$@", error.localizedDescription)
            requiresUnlock = true
            return
        }
        requiresUnlock = false

        let decoder = JSONDecoder()
        let legacyMarker = FileManager.default.fileExists(atPath: legacyMarkerURL.path)
        if legacyMarker {
            do {
                try markSealedPolicy(policyID, key)
            } catch {
                markRecoveryUnavailable(error)
                return
            }
        }
        let sealedOnly: Bool
        do {
            sealedOnly = try requiresSealedPolicy(policyID, key)
        } catch {
            markRecoveryUnavailable(error)
            return
        }
        if sealedOnly && text.split(separator: "\n").contains(where: {
            !SealedEnvelope.isSealedLine(String($0))
        }) {
            persistError = L10n.text("Saved track failed its sealed-only integrity check.")
            return
        }
        var sawLegacyLine = false
        var invalidLegacyLine = false
        let restored: [TrackPoint] = text.split(separator: "\n").compactMap { raw in
            let line = String(raw)
            let json: Data?
            if SealedEnvelope.isSealedLine(line) {
                json = SealedEnvelope.openLine(key: key, line: line, label: Self.label)
            } else {
                sawLegacyLine = true // written by a pre-encryption build
                json = line.data(using: .utf8)
            }
            guard let json, let sp = try? decoder.decode(StoredPoint.self, from: json) else {
                if !SealedEnvelope.isSealedLine(line) { invalidLegacyLine = true }
                else { persistError = L10n.text("Saved track contains an authenticated line that could not be recovered.") }
                return nil
            }
            guard sp.lat.isFinite, sp.lon.isFinite, sp.t.isFinite,
                  abs(sp.lat) <= 90, abs(sp.lon) <= 180,
                  sp.ele?.isFinite != false else {
                if !SealedEnvelope.isSealedLine(line) { invalidLegacyLine = true }
                return nil
            }
            return TrackPoint(coordinate: CLLocationCoordinate2D(latitude: sp.lat, longitude: sp.lon),
                              elevation: sp.ele,
                              time: Date(timeIntervalSince1970: sp.t))
        }
        if sawLegacyLine {
            if invalidLegacyLine {
                persistError = L10n.text("Saved legacy track contains an invalid line and was preserved unchanged.")
                return
            } else {
                guard reseal(restored, key: key) else { return }
            }
        } else if !text.isEmpty {
            do {
                // Existing ciphertext from a build predating the authenticated
                // policy is not observable until the durable downgrade fence
                // exists. A policy-store failure leaves the log untouched and
                // retryable instead of silently accepting later plaintext.
                try markSealedPolicy(policyID, key)
            } catch {
                markRecoveryUnavailable(error)
                return
            }
            if legacyMarker { try? FileManager.default.removeItem(at: legacyMarkerURL) }
        }
        if !restored.isEmpty {
            points = restored
            recovered = true
        }
    }

    /// Rewrite a plaintext log from an older build with every line sealed. Goes
    /// via atomic write so a crash halfway can't leave us half a track.
    @discardableResult
    private func reseal(_ points: [TrackPoint], key: Data) -> Bool {
        do {
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
            // Fence first. If the subsequent atomic replacement fails, the
            // intact plaintext is deliberately no longer accepted on relaunch.
            try markSealedPolicy(policyID, key)
            try out.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            if FileManager.default.fileExists(atPath: legacyMarkerURL.path) {
                try? FileManager.default.removeItem(at: legacyMarkerURL)
            }
            return true
        } catch {
            requiresUnlock = true
            persistError = L10n.text("Could not encrypt the recovered track: %1$@", error.localizedDescription)
            return false
        }
    }

    func retryRecoveryAfterUnlock() {
        guard requiresUnlock else { return }
        persistError = nil
        recover()
    }

    private func markRecoveryUnavailable(_ error: Error) {
        requiresUnlock = true
        persistError = L10n.text("Saved track is protected or unavailable and was left untouched. Retry after unlocking the device. %1$@", error.localizedDescription)
    }

    private static func isNoSuchFile(_ error: Error) -> Bool {
        var pending = [error as NSError]
        var visited = Set<ObjectIdentifier>()

        while let nsError = pending.popLast() {
            guard visited.insert(ObjectIdentifier(nsError)).inserted else { continue }

            if nsError.domain == NSCocoaErrorDomain,
               (nsError.code == NSFileNoSuchFileError
                || nsError.code == CocoaError.Code.fileReadNoSuchFile.rawValue) {
                return true
            }
            if nsError.domain == NSPOSIXErrorDomain,
               nsError.code == POSIXError.Code.ENOENT.rawValue {
                return true
            }
            if let underlying = nsError.userInfo[NSUnderlyingErrorKey] as? NSError {
                pending.append(underlying)
            }
        }
        return false
    }

    private var legacyMarkerURL: URL { fileURL.appendingPathExtension("sealed-only") }
    private var policyID: String { "track:\(fileURL.standardizedFileURL.path):\(Self.label)" }
}
