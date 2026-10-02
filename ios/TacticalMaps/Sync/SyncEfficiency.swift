import Foundation
import Combine
import CryptoKit

// SP3 client efficiency units (plans/04-sync-client-contract.md sections 17-21).
// Pure policy where possible so SyncClientBehaviourTests can drive them straight
// off testdata/sync_client_behaviour.json, plus the two caches the manager uses
// so it stops doing O(N) work per frame / per record.

// MARK: - Presence fence persistence (17.1)

/// Presence counters are written in strides instead of per frame. The crash
/// floor (persisted + 15 when the last write was not exact) keeps every counter
/// we ever showed rejected after a crash, see the contract's argument in 17.1.
enum PresenceFencePersistence {
    static let stride: Int64 = 16
    static let flushMs: Int64 = 60_000
    static let crashFloorAdd: Int64 = 15

    /// true = write all counters (flag false) before the peer may be shown.
    static func mustPersistBeforeExposing(counter: Int64, persisted: Int64, lastWriteWasExact: Bool) -> Bool {
        lastWriteWasExact || counter - persisted >= stride
    }

    /// Highest counter that must still be rejected after loading `persisted`.
    static func loadFloor(persisted: Int64, exact: Bool) -> Int64 {
        exact ? persisted : persisted + min(VersionStamp.maxCounter - persisted, crashFloorAdd)
    }
}

// MARK: - Foreground presence send policy (20.1)

/// Stationary suppression for foreground presence. Moving units keep the 5 s
/// cadence, a parked one sends a 20 s heartbeat (two chances inside the 45 s
/// receiver retention). No wire change, old receivers dont notice.
struct PresenceSendPolicy: Equatable {
    static let minIntervalMs: Int64 = 5_000
    static let stationaryHeartbeatMs: Int64 = 20_000
    static let moveMinMetres = 10.0
    static let courseChangeDegrees = 20.0
    static let courseCheckMinSpeedMps = 1.5
    static let speedChangeMps = 1.5
    static let earthRadiusMetres = 6_371_008.8

    struct Fix: Equatable {
        let latitude: Double
        let longitude: Double
        /// metres per second, negative = unknown
        let speedMps: Double
        /// degrees, negative = unknown
        let courseDegrees: Double
        let horizontalAccuracyMetres: Double
    }

    private(set) var lastSent: Fix?
    private(set) var lastSentAtMs: Int64?
    private(set) var configChanged = false

    /// New authenticated session: the first frame always goes.
    mutating func startSession() {
        lastSent = nil
        lastSentAtMs = nil
        configChanged = false
    }

    /// callsign / affiliation / echelon / function / HQ changed
    mutating func configDidChange() {
        configChanged = true
    }

    /// Cheap pre-check before touching the location service.
    func minIntervalElapsed(nowMs: Int64) -> Bool {
        guard let lastSentAtMs else { return true }
        let elapsed = nowMs - lastSentAtMs
        return elapsed < 0 || elapsed >= Self.minIntervalMs
    }

    func shouldSend(_ fix: Fix, nowMs: Int64) -> Bool {
        guard let lastSent, let lastSentAtMs else { return true }
        let elapsed = nowMs - lastSentAtMs
        // monotonic in production, a rollback just fails open to one send
        if elapsed < 0 { return true }
        guard elapsed >= Self.minIntervalMs else { return false }
        if configChanged || elapsed >= Self.stationaryHeartbeatMs { return true }
        let moved = Self.haversineMetres(lastSent, fix)
        if moved > max(Self.moveMinMetres, max(0, fix.horizontalAccuracyMetres)) { return true }
        if fix.speedMps >= Self.courseCheckMinSpeedMps, fix.courseDegrees >= 0, lastSent.courseDegrees >= 0,
           Self.angleBetween(fix.courseDegrees, lastSent.courseDegrees) > Self.courseChangeDegrees {
            return true
        }
        let speedNow = max(0, fix.speedMps)
        let speedThen = max(0, lastSent.speedMps)
        return abs(speedNow - speedThen) > Self.speedChangeMps
    }

    mutating func markSent(_ fix: Fix, atMs: Int64) {
        lastSent = fix
        lastSentAtMs = atMs
        configChanged = false
    }

    static func haversineMetres(_ a: Fix, _ b: Fix) -> Double {
        let lat1 = a.latitude * .pi / 180, lat2 = b.latitude * .pi / 180
        let dLat = lat2 - lat1
        let dLon = (b.longitude - a.longitude) * .pi / 180
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * earthRadiusMetres * asin(min(1, sqrt(h)))
    }

    static func angleBetween(_ a: Double, _ b: Double) -> Double {
        let diff = abs(a - b).truncatingRemainder(dividingBy: 360)
        return diff > 180 ? 360 - diff : diff
    }
}

// MARK: - Chat to a backgrounded peer (21.6)

/// A session whose latest accepted presence advertises more than 45 s of
/// retention is in background presence mode and processes no chat, so a
/// selected-unit send to it would only ever show Routed and get dropped.
enum ChatSendGate {
    static let foregroundRetentionSeconds = 45

    enum Verdict: Equatable {
        case allowed
        case blocked(SyncIssueCode)
    }

    static func check(scope: TacMapChatScope, recipientLatestRetentionSeconds: Int?) -> Verdict {
        guard scope == .direct, let retention = recipientLatestRetentionSeconds,
              retention > foregroundRetentionSeconds else { return .allowed }
        return .blocked(.chatRecipientInBackground)
    }
}

// MARK: - Background presence reconnect (21.4 / 21.5)

/// Decides what a backgrounded, opted-in device does after its presence
/// socket dropped. The reconnect itself relaxes THREAT_MODEL section 7 and is
/// gated on doc change D1 (reconnectEnabled), until then a drop pauses
/// background sharing and surfaces BACKGROUND_PAUSED on the next foreground.
struct BackgroundPresencePolicy: Equatable {
    /// BG_RECONNECT_ENABLED. Ships true only together with doc change D1.
    static let reconnectEnabled = false
    static let minSpacingMs: Int64 = 60_000
    static let maxConsecutiveFailures = 3
    static let maxSnapshotDrainBytes = 4_194_304

    enum PauseReason: String, Equatable {
        case noSpareEpoch
        case tooManyFailures
        case snapshotTooLarge
        case notEligible
        case reconnectDisabled
    }

    enum Decision: Equatable {
        /// open a presence-only session using this hello epoch (no durable write)
        case connect(epoch: UInt64)
        case wait
        case pause(PauseReason)

        /// every pause is surfaced the same way on the next foreground return
        var issue: SyncIssueCode? {
            if case .pause = self { return .backgroundPaused }
            return nil
        }
    }

    /// The only frames a background presence session may ever send. Never
    /// chat-key, chat, put or del (contract 21.4).
    static let allowedFrameTypes: Set<String> = ["hello", "loc"]

    /// next unused spare epoch, and how many are left above the one the last
    /// foreground hello used (all already covered by the persisted floor)
    private(set) var nextSpare: UInt64
    private(set) var sparesLeft: UInt64
    private(set) var failures = 0
    private(set) var lastAttemptMs: Int64?
    private(set) var dropped = false
    private(set) var paused: PauseReason?
    let reconnectEnabled: Bool

    init(lastForegroundEpoch: UInt64, spares: UInt64, reconnectEnabled: Bool = BackgroundPresencePolicy.reconnectEnabled) {
        // never hand out a value past ffffffffffffffff
        sparesLeft = min(spares, UInt64.max - lastForegroundEpoch)
        nextSpare = sparesLeft > 0 ? lastForegroundEpoch + 1 : lastForegroundEpoch
        self.reconnectEnabled = reconnectEnabled
    }

    mutating func socketClosed(atMs _: Int64) {
        dropped = true
    }

    /// A background location wake (or the network coming back).
    mutating func fixDue(atMs now: Int64, eligible: Bool) -> Decision {
        if let paused { return .pause(paused) }
        guard dropped else { return .wait }
        guard eligible else { return pause(.notEligible) }
        guard reconnectEnabled else { return pause(.reconnectDisabled) }
        guard failures < Self.maxConsecutiveFailures else { return pause(.tooManyFailures) }
        guard sparesLeft > 0 else { return pause(.noSpareEpoch) }
        if let last = lastAttemptMs, now - last < Self.minSpacingMs { return .wait }
        lastAttemptMs = now
        let epoch = nextSpare
        sparesLeft -= 1
        if sparesLeft > 0 { nextSpare += 1 }
        return .connect(epoch: epoch)
    }

    mutating func attemptSucceeded() {
        failures = 0
        dropped = false
    }

    mutating func attemptFailed() {
        failures += 1
        dropped = true
    }

    mutating func snapshotBytes(_ total: Int) -> Decision? {
        total > Self.maxSnapshotDrainBytes ? pause(.snapshotTooLarge) : nil
    }

    private mutating func pause(_ reason: PauseReason) -> Decision {
        paused = reason
        return .pause(reason)
    }
}

// MARK: - Wire-id index (18)

/// HMAC wire ids for one room key, built off the main thread when needed.
/// The key is made once instead of per object.
struct SyncWireIdHasher {
    let key: SymmetricKey

    init(metadataKey: Data) {
        key = SymmetricKey(data: metadataKey)
    }

    func wireId(_ uuid: UUID) -> String {
        SyncIdentity.wireObjectId(key: key, localUuidBytes: SyncIdentity.uuidToBytes(uuid))
    }
}

/// localId <-> wireId for the joined room. Built once after the keys and
/// stores are attached, kept up to date from store mutation events and remote
/// applies, thrown away on leave / join. Both directions are O(1) and a reverse
/// miss means "no local object", there is no fallback scan (S5-02, S4-03).
final class SyncWireIdIndex {
    let hasher: SyncWireIdHasher
    private(set) var forward: [UUID: String] = [:]
    private var reverse: [String: UUID] = [:]

    init(metadataKey: Data) {
        hasher = SyncWireIdHasher(metadataKey: metadataKey)
    }

    @discardableResult
    func wireId(for uuid: UUID) -> String {
        if let known = forward[uuid] { return known }
        let wire = hasher.wireId(uuid)
        forward[uuid] = wire
        reverse[wire] = uuid
        return wire
    }

    func insert<S: Sequence>(_ uuids: S) where S.Element == UUID {
        for uuid in uuids { wireId(for: uuid) }
    }

    func uuid(forWireId wireId: String) -> UUID? { reverse[wireId] }

    var count: Int { forward.count }
}

// MARK: - Model index + export cache (17, 18, 20)

/// The kind tag the diff and lastContent use for an object.
enum SyncModelObjectKind: String {
    case waypoint
    case drawing
}

/// id -> value maps of the mission stores plus a per-object GeoJSON export
/// cache. Kept current from the store publishers, so the diff, the journal
/// observer and reexport stop exporting + hashing the whole model on every
/// change (S5-03, S3-10). An object's export only depends on its own value and
/// its own layer's name/colour, which is exactly what the cache keys on.
final class SyncModelIndex {
    private(set) var waypoints: [UUID: Waypoint] = [:]
    private(set) var shapes: [UUID: DrawingShape] = [:]
    private(set) var layers: [UUID: DrawingLayer] = [:]

    private struct LayerKey: Equatable {
        let name: String
        let color: String
    }

    private enum Value: Equatable {
        case waypoint(Waypoint)
        case drawing(DrawingShape)
    }

    private struct Cached {
        let value: Value
        let layer: LayerKey?
        let content: String
        let hash: String
    }

    private var cache: [UUID: Cached] = [:]

    var count: Int { waypoints.count + shapes.count }
    var allIds: [UUID] { Array(waypoints.keys) + Array(shapes.keys) }

    func contains(_ id: UUID) -> Bool { waypoints[id] != nil || shapes[id] != nil }

    func kind(of id: UUID) -> SyncModelObjectKind? {
        if waypoints[id] != nil { return .waypoint }
        if shapes[id] != nil { return .drawing }
        return nil
    }

    /// Replace the waypoint map. Returns ids whose value changed, plus the
    /// old values so the caller can compare exports.
    func updateWaypoints(_ next: [Waypoint]) -> [UUID: Waypoint?] {
        var map: [UUID: Waypoint] = [:]
        map.reserveCapacity(next.count)
        var changed: [UUID: Waypoint?] = [:]
        for waypoint in next {
            map[waypoint.id] = waypoint
            let old = waypoints[waypoint.id]
            if old != waypoint { changed.updateValue(old, forKey: waypoint.id) }
        }
        for (id, old) in waypoints where map[id] == nil {
            changed.updateValue(old, forKey: id)
            cache.removeValue(forKey: id)
        }
        waypoints = map
        return changed
    }

    func updateShapes(_ next: [DrawingShape]) -> [UUID: DrawingShape?] {
        var map: [UUID: DrawingShape] = [:]
        map.reserveCapacity(next.count)
        var changed: [UUID: DrawingShape?] = [:]
        for shape in next {
            map[shape.id] = shape
            let old = shapes[shape.id]
            if old != shape { changed.updateValue(old, forKey: shape.id) }
        }
        for (id, old) in shapes where map[id] == nil {
            changed.updateValue(old, forKey: id)
            cache.removeValue(forKey: id)
        }
        shapes = map
        return changed
    }

    /// Replace the layer map. Returns every object whose export changes
    /// because its layer was added, removed, renamed or recoloured
    /// (visibility toggles do not touch the export).
    func updateLayers(_ next: [DrawingLayer]) -> Set<UUID> {
        var map: [UUID: DrawingLayer] = [:]
        for layer in next where map[layer.id] == nil { map[layer.id] = layer }
        var changedLayers = Set<UUID>()
        for (id, layer) in map where layers[id].map(Self.key) != Self.key(layer) { changedLayers.insert(id) }
        for id in layers.keys where map[id] == nil { changedLayers.insert(id) }
        layers = map
        guard !changedLayers.isEmpty else { return [] }
        var affected = Set<UUID>()
        for (id, waypoint) in waypoints where changedLayers.contains(waypoint.layerID) { affected.insert(id) }
        for (id, shape) in shapes where changedLayers.contains(shape.layerID) { affected.insert(id) }
        return affected
    }

    /// Current export of one object, nil when it is not in the stores.
    func export(_ id: UUID) -> (content: String, hash: String)? {
        if let waypoint = waypoints[id] { return export(.waypoint(waypoint), id: id, layerID: waypoint.layerID) }
        if let shape = shapes[id] { return export(.drawing(shape), id: id, layerID: shape.layerID) }
        return nil
    }

    /// Export of a value that may already be gone from the index (the old
    /// side of a change). Uses the cache when it still holds that exact value.
    func exportHash(waypoint: Waypoint) -> String? {
        export(.waypoint(waypoint), id: waypoint.id, layerID: waypoint.layerID, store: false)?.hash
    }

    func exportHash(shape: DrawingShape) -> String? {
        export(.drawing(shape), id: shape.id, layerID: shape.layerID, store: false)?.hash
    }

    func clearCache() { cache.removeAll() }

    private static func key(_ layer: DrawingLayer) -> LayerKey {
        LayerKey(name: layer.name, color: layer.defaultColorHex)
    }

    private func export(_ value: Value, id: UUID, layerID: UUID, store: Bool = true) -> (content: String, hash: String)? {
        let layer = layers[layerID]
        let layerKey = layer.map(Self.key)
        if let cached = cache[id], cached.value == value, cached.layer == layerKey {
            return (cached.content, cached.hash)
        }
        let content: String?
        switch value {
        case .waypoint(let waypoint):
            content = try? GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layer.map { [$0] } ?? [])
        case .drawing(let shape):
            content = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layer.map { [$0] } ?? [])
        }
        guard let content else { return nil }
        let hash = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
        if store { cache[id] = Cached(value: value, layer: layerKey, content: content, hash: hash) }
        return (content, hash)
    }
}

// MARK: - Presence-only observable (20.2)

/// Peers live here instead of on SyncManager so a presence frame never
/// republishes the manager (and with it ContentView and every drawing on the
/// map). Only the presence overlay and the Sync sheet observe this.
@MainActor
final class SyncPresenceModel: ObservableObject {
    @Published var peers: [String: PresencePeer] = [:]
}

// MARK: - Off-main work (19, 20.3)

/// Runs CPU-heavy sync work (snapshot validation, PBKDF2) off the main thread
/// and hands the result back on main. Tests inject an inline one so the state
/// machine stays deterministic on the manual clock.
@MainActor
protocol SyncOffMainExecutor: AnyObject {
    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void)
}

@MainActor
final class DispatchSyncOffMainExecutor: SyncOffMainExecutor {
    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void) {
        // results land on main behind a generation / join-token check, so
        // jobs never need to be serial with each other
        DispatchQueue.global(qos: .userInitiated).async {
            work()
            Task { @MainActor in completion() }
        }
    }
}

/// All replay seals share this queue, including clean-point writes during a
/// lifecycle transition. Snapshot and live commits return without blocking UI.
final class SyncPersistenceExecutor: SyncOffMainExecutor {
    private static let queueLabel = "com.tacticalmaps.sync.persistence"
    private static let queue = DispatchQueue(label: queueLabel, qos: .userInitiated)
    private static let key = DispatchSpecificKey<Bool>()
    private static let configured: Void = queue.setSpecific(key: key, value: true)

    nonisolated static func write(_ data: Data, to url: URL, label: String) throws {
        _ = configured
        if DispatchQueue.getSpecific(key: key) == true {
            try SafeStore.write(data, to: url, label: label)
        } else {
            try queue.sync { try SafeStore.write(data, to: url, label: label) }
        }
    }

    @MainActor
    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void) {
        _ = Self.configured
        Self.queue.async {
            work()
            Task { @MainActor in completion() }
        }
    }
}

/// Runs everything right away on the caller. Test seam only.
@MainActor
final class InlineSyncOffMainExecutor: SyncOffMainExecutor {
    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void) {
        work()
        completion()
    }
}

// MARK: - Off-main snapshot validation (3, 19)

/// Everything the validator needs, captured on main. Collections are
/// copy-on-write, so capturing them is O(1) and a later main-thread mutation
/// copies instead of racing the worker.
struct SnapshotValidationInput {
    let records: [[String: Any]]
    let keys: SyncCrypto.V3RoomKeys
    let roomKey: SymmetricKey
    let replay: SyncReplayState.ReadView
    let committedLayers: [DrawingLayer]
    let fallbackLayerID: UUID
    let waypointIDs: Set<UUID>
    let modelHashes: [String: String]
    let modelGenerations: [String: Int64]
    let modelEpoch: UInt64
    let drawingIDs: Set<UUID>
    let knownWireIds: [UUID: String]
    let hasher: SyncWireIdHasher
}

struct SnapshotValidationResult {
    var validated: [SyncValidatedRecordV3] = []
    var unverified: [String] = []
    var unsupported: [String] = []
}

/// Per-record AEAD, signature, importer parse against the staged layers and
/// the expected receiver hash, sequentially in item order (contract 3 + 19).
/// Pure, runs on the off-main executor. Tombstone local ids are resolved
/// later at commit against the live index.
enum SnapshotValidator {
    static func validate(_ input: SnapshotValidationInput) -> SnapshotValidationResult {
        var result = SnapshotValidationResult()
        result.validated.reserveCapacity(input.records.count)
        var staging = SnapshotLayerStaging(committed: input.committedLayers)
        let known = input.knownWireIds
        let hasher = input.hasher
        let replay = input.replay
        let waypointIDs = input.waypointIDs
        let drawingIDs = input.drawingIDs
        let base = SnapshotRecordContext(
            keys: input.keys,
            roomKey: input.roomKey,
            actorKeyIsAcceptable: { replay.actorKeyIsAcceptable($0, pubkey: $1) },
            layers: staging.layers,
            fallbackLayerID: input.fallbackLayerID,
            isWaypointID: { waypointIDs.contains($0) },
            isDrawingID: { drawingIDs.contains($0) },
            localIdForWireId: { _ in nil },
            wireIdForUUID: { known[$0] ?? hasher.wireId($0) })
        for record in input.records {
            let wireId = record["id"] as? String ?? ""
            let deletedField = record["deleted"]
            if deletedField != nil, strictJSONBoolean(deletedField) == nil {
                result.unverified.append(wireId)
                continue
            }
            let deleted = strictJSONBoolean(deletedField) ?? false
            switch SnapshotRecordClassifier.classify(record, deleted: deleted, context: base.withLayers(staging.layers)) {
            case .valid(let value):
                result.validated.append(value)
                if let parsed = value.parsed,
                   replay.canAcceptIgnoringWindow(value.mutation.wireObjectId, value.mutation.stamp)
                    || replay.willApplyExact(value.mutation,
                        currentHash: value.localId.flatMap { input.modelHashes[$0] },
                        generation: value.localId.flatMap { input.modelGenerations[$0] } ?? 0) {
                    staging.adopt(parsed.newLayers)
                }
            case .skip(.unverified, _):
                result.unverified.append(wireId)
            case .skip(.unsupported, _):
                result.unsupported.append(wireId)
            }
        }
        return result
    }

    static func strictJSONBoolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
}
