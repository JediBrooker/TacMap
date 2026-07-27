import Foundation
import Combine
import CryptoKit
import CoreLocation

/// Real-time sync client for shared tactical picture (iOS side). Basically
/// mirrors Android's `SyncManager`: connects to E2E-blind relay for a
/// join-code room and keeps waypoints + drawings in sync across the unit.
///
/// Each object gets serialised as single-feature GeoJSON (same cross-platform
/// schema we already use for import/export), encrypted with the room key and
/// relayed as opaque ciphertext. Layers ride along in feature properties.
/// Merge is LWW on per-object Lamport version; echo supressed by tracking
/// last serialised form per id.
///
/// Also handles ephemeral presence: broadcasts device location + callsign
/// every 5s when sharing is on, and tracks remote peers for map annotations.
@MainActor
final class SyncManager: ObservableObject {
    enum Status { case offline, connecting, snapshotting, connected }

    @Published private(set) var status: Status = .offline
    @Published private(set) var room: String?
    @Published private(set) var lastError: String?
    @Published var peers: [String: PresencePeer] = [:]
    @Published var presenceConfig = PresenceConfig() {
        didSet { savePresenceConfig() }
    }

    /// Fires a description whenever a remote change comes in, so the UI
    /// can flash a conflict/update notification.
    let remoteUpdateSubject = PassthroughSubject<String, Never>()

    static let relayBase = "wss://tacmap-sync.christianbrooker.workers.dev"

    // Set once via configure() so this can be a @StateObject. Needs to be
    // created without referencing other @StateObject stores at init time.
    private var waypointStore: WaypointStore!
    private var drawingStore: DrawingStore!
    /// Injected after construction so presence can read the current GPS fix.
    private(set) var locationService: LocationService?

    private var task: URLSessionWebSocketTask?
    private var roomKey: SymmetricKey?
    private var authToken: String?
    /// Resolved from OPSEC settings at join time so a self-hoster's relay is
    /// actually used; falls back to ours. Held for the reconnect path.
    private var relayEndpoint = SyncManager.relayBase
    private var wantConnected = false
    private var roomId: String?

    // v3 protocol state
    private var protocolVersion: Int = 2
    private var v3Keys: SyncCrypto.V3RoomKeys?
    private var myActorId: String?
    private var replayState: SyncReplayState?
    private var sessionDomain: Data?
    private var myPublicKeyRaw: Data?
    private var presenceCounter: Int64 = 0
    private var snapshotSeq: Int64?
    private var snapshotRecords: [[String: Any]] = []
    private var snapshotInvalid = false
    private var snapshotSawFinalPage = false
    private var awaitingHelloAck = false
    private var localHelloVersion: String?
    private var snapshotAggregateBytes = 0
    private var snapshotWireIds = Set<String>()
    private var forcedLocalDiff = Set<String>()
    private var resolvingPendingModel = false

    private struct ValidatedRecordV3 {
        let mutation: SyncReplayState.DurableMutation
        let parsed: GeoJSONImporter.Result?
        let localId: String?
        let expectedModelHash: String?
    }

    struct PresencePayload: Equatable {
        let lat: Double
        let lon: Double
        let heading: Double
        let speed: Double
        let callsign: String
        let affiliation: String
        let echelon: String
        let function: String
        let isHQ: Bool
    }

    struct PresenceEnvelope {
        let payload: PresencePayload
        let signedPayload: Data
        let publicKey: String
        let signature: String
    }

    // Per-device Ed25519 signing identity. Seed sealed at rest; the public key
    // rides every presence AND every object write so peers pin it (TOFU) and
    // reject a room member impersonating an established device. One identity per
    // clientId, shared by presence + object writes. Room state cleared on leave.
    private var deviceSeed: Data?
    private var myPublicKey: String = ""
    private var peerKeys: [String: String] = [:]   // clientId -> pinned pubkey
    private var peerTs: [String: Int64] = [:]        // clientId -> last accepted presence ts
    private static let deviceSeedKey = "sync.deviceSeed"
    private static let deviceSeedLabel = "sync/deviceSeed"

    private let clientId: String
    private var clock: Int64 = 0
    private var versions: [String: Int64] = [:]
    private var lastContent: [String: String] = [:]
    private var kindById: [String: String] = [:]
    private var observers = Set<AnyCancellable>()
    private var revisionObservers = Set<AnyCancellable>()
    private var modelRevisionJournal: LocalModelRevisionJournal?
    private var revisionJournalAvailable = false
    private var observedModelHashes: [String: String] = [:]
    private var modelObservationInitialized = false

    // v2 containment ceilings (pending v3 protocol limits)
    private static let maxFrameBytes = 1_048_576        // 1 MiB text
    private static let maxBase64Bytes = 1_048_576        // 1 MiB encoded ct
    private static let maxSnapshotItems = 10_000
    private static let maxSnapshotAggregateBytes = 54_525_952
    private static let maxVersion: Int64 = 1_000_000_000_000  // matches relay MAX_V

    /// Broadcasts `loc` messages every 5 seconds.
    private var presenceTimer: Timer?
    /// Sweeps stale peers every 30s.
    private var stalenessTimer: Timer?

    init() {
        if let existing = UserDefaults.standard.string(forKey: "sync.clientId") {
            clientId = existing
        } else {
            let id = UUID().uuidString
            UserDefaults.standard.set(id, forKey: "sync.clientId")
            clientId = id
        }
        loadPresenceConfig()
    }

    /// Inject shared stores once the view hierarchy is up. Can call
    /// repeatedly, only first call actually binds.
    func configure(waypointStore: WaypointStore,
                   drawingStore: DrawingStore,
                   locationService: LocationService? = nil) {
        guard self.waypointStore == nil else { return }
        self.waypointStore = waypointStore
        self.drawingStore = drawingStore
        self.locationService = locationService
        let container = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        let journal = LocalModelRevisionJournal(containerURL: container)
        modelRevisionJournal = journal
        revisionJournalAvailable = journal.load()
        observeModelRevisions()
    }

    // MARK: - Presence config persistence

    private static let presenceConfigKey = "sync.presenceConfig"
    /// AEAD label binding the presence blob so it can't open as another store.
    private static let presenceLabel = "sync/presenceConfig"

    private func savePresenceConfig() {
        guard let data = try? JSONEncoder().encode(presenceConfig) else { return }
        // Callsign + affiliation/echelon/function/HQ is unit identity - seal it
        // at rest like waypoints instead of leaving it plaintext in UserDefaults
        // (which is also captured in device/iCloud backups, unlike the DEK).
        guard let key = try? SafeStore.keyProvider(),
              let sealed = try? SealedEnvelope.sealFile(
                key: key, plaintext: data, label: Self.presenceLabel) else {
            return  // locked/unavailable: keep what's stored, don't downgrade to plaintext
        }
        UserDefaults.standard.set(sealed, forKey: Self.presenceConfigKey)
    }

    private func loadPresenceConfig() {
        guard let stored = UserDefaults.standard.data(forKey: Self.presenceConfigKey) else { return }
        // Sealed (current) or legacy bare-JSON (pre-sealing) - the magic prefix
        // tells them apart.
        let wasLegacyPlaintext = !SealedEnvelope.isSealedFile(stored)
        let plain: Data? = wasLegacyPlaintext
            ? stored
            : (try? SafeStore.keyProvider()).flatMap {
                SealedEnvelope.openFile(key: $0, blob: stored, label: Self.presenceLabel)
              }
        guard let plain,
              let config = try? JSONDecoder().decode(PresenceConfig.self, from: plain) else { return }
        presenceConfig = config
        // Migrate a legacy plaintext blob to sealed at rest (idempotent).
        if wasLegacyPlaintext { savePresenceConfig() }
    }

    /// Load the durable signing seed or create-and-persist one. Existing locked,
    /// malformed or undecryptable material is an error: silently rotating would
    /// turn a temporary device-lock condition into an actor identity swap.
    private func loadOrCreateDeviceSeed() throws -> Data {
        if let stored = UserDefaults.standard.data(forKey: Self.deviceSeedKey) {
            guard SealedEnvelope.isSealedFile(stored) else { throw IdentityError.invalidSeedStore }
            let key = try SafeStore.keyProvider()
            guard let seed = SealedEnvelope.openFile(key: key, blob: stored, label: Self.deviceSeedLabel),
                  seed.count == 32,
                  SyncSigning.publicKeyRaw(seed) != nil else { throw IdentityError.invalidSeedStore }
            return seed
        }
        let seed = SyncSigning.generateSeed()
        let key = try SafeStore.keyProvider()
        let sealed = try SealedEnvelope.sealFile(key: key, plaintext: seed, label: Self.deviceSeedLabel)
        UserDefaults.standard.set(sealed, forKey: Self.deviceSeedKey)
        guard UserDefaults.standard.synchronize(),
              UserDefaults.standard.data(forKey: Self.deviceSeedKey) == sealed else {
            throw IdentityError.seedPersistenceFailed
        }
        return seed
    }

    private enum IdentityError: Error { case invalidSeedStore, seedPersistenceFailed }

    // MARK: API

    func join(_ joinCode: String) {
        let code = joinCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        guard code.hasPrefix("3:") || code.hasPrefix("2:") else {
            lastError = "Join code must start with 3:. Legacy rooms require an explicit 2: prefix."
            return
        }
        leave()
        lastError = nil
        do {
            let seed = try loadOrCreateDeviceSeed()
            guard let publicKey = SyncSigning.publicKey(seed),
                  let publicRaw = SyncSigning.publicKeyRaw(seed) else { throw IdentityError.invalidSeedStore }
            deviceSeed = seed
            myPublicKey = publicKey
            myPublicKeyRaw = publicRaw
        } catch {
            lastError = "Signing identity is locked or unavailable. Unlock the device and try again."
            return
        }
        wantConnected = true

        // Self-hosters repoint the relay in OPSEC settings; blank -> ours.
        let configured = OpsecSettings.shared.relayURL.trimmingCharacters(in: .whitespacesAndNewlines)
        relayEndpoint = configured.isEmpty ? Self.relayBase : configured

        if code.hasPrefix("3:") {
            protocolVersion = 3
            let rawCode = String(code.dropFirst(2))
            let keys = SyncCrypto.deriveRoomV3(rawCode)
            v3Keys = keys
            roomKey = keys.roomKey
            roomId = keys.roomId
            authToken = keys.authToken
            guard let pubRaw = myPublicKeyRaw else {
                lastError = "Signing identity is unavailable."
                wantConnected = false
                return
            }
            myActorId = SyncIdentity.actorId(roomIdRaw: keys.roomIdRaw, pubkeyRaw: pubRaw)
            let containerURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            let rs = SyncReplayState(roomId: keys.roomId, containerURL: containerURL)
            guard rs.load() else {
                lastError = "Saved rollback-protection state is locked or damaged. Sync was not started."
                wantConnected = false
                roomKey = nil; authToken = nil; roomId = nil; v3Keys = nil; myActorId = nil
                return
            }
            replayState = rs
        } else {
            protocolVersion = 2
            let keys = SyncCrypto.deriveRoom(String(code.dropFirst(2)))
            roomKey = keys.roomKey
            roomId = keys.roomId
            authToken = keys.authToken
        }

        room = code
        connect()
        observeStores()
        startPresenceTimers()
    }

    func leave() {
        wantConnected = false
        observers.removeAll()
        stopPresenceTimers()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil

        // v3: persist replay state but don't clear it (survives leave/restart)
        try? replayState?.save()

        roomKey = nil
        authToken = nil
        room = nil
        status = .offline
        clock = 0
        versions.removeAll(); lastContent.removeAll(); kindById.removeAll()
        forcedLocalDiff.removeAll(); resolvingPendingModel = false
        peers.removeAll()
        peerKeys.removeAll(); peerTs.removeAll()

        // v3 state cleared per-session (not durable)
        v3Keys = nil
        myActorId = nil
        replayState = nil
        sessionDomain = nil
        presenceCounter = 0
        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        awaitingHelloAck = false
        localHelloVersion = nil
        myPublicKeyRaw = nil
        deviceSeed = nil
        myPublicKey = ""
        protocolVersion = 2
    }

    // MARK: Connection

    private func connect() {
        guard let roomId else { return }
        if protocolVersion == 3 {
            sessionDomain = SyncIdentity.generateSessionDomain()
            presenceCounter = 0
            snapshotSeq = nil
            snapshotRecords.removeAll()
            snapshotInvalid = false
            snapshotSawFinalPage = false
            snapshotAggregateBytes = 0
            snapshotWireIds.removeAll()
            awaitingHelloAck = false
            localHelloVersion = nil
            peers.removeAll()
        }
        // Normalize the base before appending the room path. OpsecSettings'
        // default relay (and older persisted self-host URLs) end in "/room/", but
        // we append the full "/room/<id>" here - without stripping it we'd request
        // "…/room//room/<id>", which the relay's strict ^/room/<id>$ route 404s and
        // the socket never connects. Strip a trailing "/room/" and/or slash so the
        // path is single regardless of how the base was entered.
        var base = relayEndpoint
        if base.hasSuffix("/room/") { base = String(base.dropLast(6)) }
        while base.hasSuffix("/") { base = String(base.dropLast()) }
        let path = protocolVersion == 3 ? "/v3/room/\(roomId)" : "/room/\(roomId)"
        guard let url = URL(string: base + path) else { return }
        status = .connecting
        var req = URLRequest(url: url)
        if let authToken { req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization") }
        if protocolVersion == 3 {
            req.setValue("3", forHTTPHeaderField: "X-Protocol")
            req.setValue(roomId, forHTTPHeaderField: "X-Room-Id")
        }
        let t = URLSession.shared.webSocketTask(with: req)
        task = t
        t.resume()
        receive()
    }

    private func receive() {
        task?.receive { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .failure:
                    self.handleDisconnect()
                case .success(let message):
                    if self.protocolVersion == 2 { self.status = .connected }
                    switch message {
                    case .string(let text): self.handleMessage(text)
                    case .data(let d): if let t = String(data: d, encoding: .utf8) { self.handleMessage(t) }
                    @unknown default: break
                    }
                    self.receive()
                }
            }
        }
    }

    private func handleDisconnect() {
        status = .offline
        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        snapshotAggregateBytes = 0
        snapshotWireIds.removeAll()
        awaitingHelloAck = false
        sessionDomain = nil
        presenceCounter = 0
        guard wantConnected else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            guard let self, self.wantConnected else { return }
            self.connect()
        }
    }

    // MARK: Outbound

    private func observeStores() {
        Publishers.CombineLatest3(
            waypointStore.$waypoints,
            drawingStore.$shapes,
            drawingStore.$layers
        )
        .debounce(for: .milliseconds(250), scheduler: RunLoop.main)
        .sink { [weak self] wps, shapes, layers in
            guard let self else { return }
            if self.protocolVersion == 3 {
                self.syncLocalStateV3(waypoints: wps, shapes: shapes, layers: layers)
            } else {
                self.syncLocalState(waypoints: wps, shapes: shapes, layers: layers)
            }
        }
        .store(in: &observers)
    }

    /// Lifetime observer: records local ABA mutations even while no room is joined.
    private func observeModelRevisions() {
        Publishers.CombineLatest3(
            waypointStore.$waypoints,
            drawingStore.$shapes,
            drawingStore.$layers
        )
        .sink { [weak self] waypoints, shapes, layers in
            guard let self else { return }
            let current = self.modelHashes(waypoints: waypoints, shapes: shapes, layers: layers)
            if !self.modelObservationInitialized {
                self.observedModelHashes = current
                self.modelObservationInitialized = true
                return
            }
            if self.resolvingPendingModel {
                self.observedModelHashes = current
                return
            }
            let changed = Set(self.observedModelHashes.keys).union(current.keys).filter {
                self.observedModelHashes[$0] != current[$0]
            }
            guard !changed.isEmpty else { return }
            do {
                guard self.revisionJournalAvailable, let journal = self.modelRevisionJournal else {
                    throw SyncReplayState.ReplayError.invalidState
                }
                for id in changed { try journal.bump(id) }
                self.observedModelHashes = current
            } catch {
                self.revisionJournalAvailable = false
                self.lastError = "Local revision history could not be saved; sync is paused."
                self.failClosedV3(self.lastError!)
            }
        }
        .store(in: &revisionObservers)
    }

    private func modelHashes(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) -> [String: String] {
        var out: [String: String] = [:]
        for waypoint in waypoints {
            if let content = try? GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layers) {
                out[waypoint.id.uuidString] = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
            }
        }
        for shape in shapes {
            if let content = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers) {
                out[shape.id.uuidString] = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
            }
        }
        return out
    }

    private func refreshObservedModelBaseline(localId: String?) {
        guard let localId else { return }
        if let hash = modelContentHash(localId: localId) { observedModelHashes[localId] = hash }
        else { observedModelHashes.removeValue(forKey: localId) }
    }

    private func syncLocalState(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) {
        guard status == .connected else { return }
        var current: [String: (kind: String, content: String)] = [:]
        for wp in waypoints {
            if let s = try? GeoJSONExporter.export(waypoints: [wp], drawings: [], layers: layers) {
                current[wp.id.uuidString] = ("waypoint", s)
            }
        }
        for shape in shapes {
            if let s = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers) {
                current[shape.id.uuidString] = ("drawing", s)
            }
        }

        for (id, entry) in current where lastContent[id] != entry.content {
            clock += 1
            versions[id] = clock
            lastContent[id] = entry.content
            kindById[id] = entry.kind
            sendPut(id: id, v: clock, kind: entry.kind, content: entry.content)
        }
        for id in lastContent.keys where current[id] == nil {
            clock += 1
            sendDel(id: id, v: clock)
            lastContent[id] = nil; versions[id] = nil; kindById[id] = nil
        }
    }

    private func sendPut(id: String, v: Int64, kind: String, content: String) {
        guard let key = roomKey, let seed = deviceSeed else { return }
        // Sign the write, then seal {content, pub, sig} together. The signature
        // rides INSIDE the sealed blob so the relay stays E2E-blind to device
        // identity; a receiver proves room-key possession by opening it and
        // device authorship by verifying the sig against the pinned key.
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, kind, clientId, content)) ?? ""
        let inner: [String: Any] = ["c": content, "pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: id, v: v, kind: kind)) else { return }
        send(["t": "put", "id": id, "v": v, "by": clientId, "kind": kind,
              "ct": sealed.base64EncodedString()])
    }

    private func sendDel(id: String, v: Int64) {
        guard let key = roomKey, let seed = deviceSeed else { return }
        // Deletes used to be an unauthenticated {id,v} - a coerced relay could
        // forge one and silently remove a contact. Now seal a signed proof: only
        // a room-key holder can produce it (relay can't), and it's attributable
        // to a device. AAD "del" so it can't be replayed as a put.
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, "del", clientId, "")) ?? ""
        let inner: [String: Any] = ["pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: id, v: v, kind: "del")) else { return }
        send(["t": "del", "id": id, "v": v, "by": clientId, "ct": sealed.base64EncodedString()])
    }

    private func send(_ obj: [String: Any], completion: (() -> Void)? = nil) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { error in
            guard error == nil, let completion else { return }
            Task { @MainActor in completion() }
        }
    }

    // MARK: - v3 Outbound

    private func syncLocalStateV3(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) {
        guard status == .connected, let keys = v3Keys, let rs = replayState,
              revisionJournalAvailable,
              !resolvingPendingModel, !rs.hasPendingModelApplications() else { return }
        var current: [String: (kind: String, content: String, localId: UUID)] = [:]
        for wp in waypoints {
            if let s = try? GeoJSONExporter.export(waypoints: [wp], drawings: [], layers: layers) {
                current[wp.id.uuidString] = ("waypoint", s, wp.id)
            }
        }
        for shape in shapes {
            if let s = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers) {
                current[shape.id.uuidString] = ("drawing", s, shape.id)
            }
        }

        for (id, entry) in current where lastContent[id] != entry.content || forcedLocalDiff.contains(id) {
            let wireId = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(entry.localId))
            let hash = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(entry.content.utf8)))
            let recovery = replayState?.recoverableLocalPut(
                wireObjectId: wireId, actorId: myActorId ?? "", pubkey: myPublicKey, contentHash: hash)
            if sendPutV3(wireObjectId: wireId, kind: entry.kind, content: entry.content, recoveryStamp: recovery) {
                lastContent[id] = entry.content
                kindById[id] = entry.kind
                forcedLocalDiff.remove(id)
            }
        }
        let gone = Set(lastContent.keys).union(forcedLocalDiff).filter { current[$0] == nil }
        for id in gone {
            if let uuid = UUID(uuidString: id) {
                let wireId = SyncIdentity.wireObjectId(
                    metadataKey: keys.metadataKey,
                    localUuidBytes: SyncIdentity.uuidToBytes(uuid))
                guard sendDelV3(wireObjectId: wireId) else { continue }
            }
            lastContent[id] = nil; kindById[id] = nil
            forcedLocalDiff.remove(id)
        }
    }

    @discardableResult
    private func sendPutV3(wireObjectId: String, kind: String, content: String, recoveryStamp: VersionStamp? = nil) -> Bool {
        guard let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return false }
        let counter: Int64
        if let recoveryStamp, recoveryStamp.actorId == actorId { counter = recoveryStamp.counter }
        else {
            do { counter = try rs.reserveNextCounter() }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()
        let contentData = Data(content.utf8)
        let payloadHash = SyncIdentity.sha256(contentData)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPut, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: kind, payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return false }
        let inner: [String: Any] = ["c": content, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: kind)) else { return false }
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireObjectId, stamp: VersionStamp(counter: counter, actorId: actorId),
            publicKey: myPublicKey, kind: .put(contentHash: SyncIdentity.bytesToHex(payloadHash)))
        if recoveryStamp == nil {
            do { guard try rs.commit(mutation) else { return false } }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        send(["t": "put", "id": wireObjectId, "vs": vs, "by": actorId, "kind": kind,
              "ct": sealed.base64EncodedString(), "pub": myPublicKey,
              "sd": SyncIdentity.urlB64Encode(sd)])
        return true
    }

    @discardableResult
    private func sendDelV3(wireObjectId: String, recoveryStamp: VersionStamp? = nil) -> Bool {
        guard let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return false }
        let counter: Int64
        if let recoveryStamp, recoveryStamp.actorId == actorId { counter = recoveryStamp.counter }
        else {
            do { counter = try rs.reserveNextCounter() }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()
        let payloadHash = SyncIdentity.sha256(Data())
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainDelete, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: "del", payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return false }
        let inner: [String: Any] = ["sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: "del")) else { return false }
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireObjectId, stamp: VersionStamp(counter: counter, actorId: actorId),
            publicKey: myPublicKey, kind: .delete)
        if recoveryStamp == nil {
            do { guard try rs.commit(mutation) else { return false } }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        send(["t": "del", "id": wireObjectId, "vs": vs, "by": actorId, "kind": "del",
              "ct": sealed.base64EncodedString(), "pub": myPublicKey,
              "sd": SyncIdentity.urlB64Encode(sd)])
        return true
    }

    private func sendHelloV3() {
        guard let actorId = myActorId, let sd = sessionDomain,
              let keys = v3Keys, let pubRaw = myPublicKeyRaw,
              let seed = deviceSeed, let rs = replayState else {
            failClosedV3("Could not construct authenticated hello."); return
        }
        let epoch: String
        do { epoch = try rs.reserveHelloEpoch(actorId: actorId) }
        catch { failClosedV3("Could not reserve authenticated session epoch."); return }
        let vs = "\(epoch):\(actorId)"
        localHelloVersion = vs
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainHello, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd,
            counterHex16: epoch, objectId: "",
            kind: "hello", payloadHash: SyncIdentity.sha256(pubRaw))
        guard let sig = SyncSigning.sign(seed, preimage), let task,
              let data = try? JSONSerialization.data(withJSONObject: [
                "t": "hello", "by": actorId, "pub": myPublicKey,
                "sd": SyncIdentity.urlB64Encode(sd), "vs": vs, "sig": sig
              ]), let text = String(data: data, encoding: .utf8) else {
            failClosedV3("Could not send authenticated hello."); return
        }
        task.send(.string(text)) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor in self?.failClosedV3("Could not send authenticated hello.") }
        }
    }

    private func sendPresenceV3() {
        guard status == .connected, presenceConfig.shareLocation,
              let key = roomKey, let actorId = myActorId,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed,
              let loc = locationService?.lastLocation else { return }

        let cfg = presenceConfig
        let callsign = boundedCallsign(cfg.callsign)
        let lat = loc.coordinate.latitude
        let lon = loc.coordinate.longitude
        let heading = max(0, loc.course)
        let speed = max(0, loc.speed)
        guard presenceCounter < VersionStamp.maxCounter else { return }
        presenceCounter += 1
        let counter = presenceCounter
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()

        // Serialize the nine signed fields exactly once. The byte string itself
        // rides inside the sealed envelope, so another platform verifies these
        // exact bytes instead of rebuilding JSON with a different number writer.
        let presence = PresencePayload(
            lat: lat, lon: lon, heading: heading, speed: speed,
            callsign: callsign, affiliation: cfg.affiliation,
            echelon: cfg.echelon, function: cfg.function, isHQ: cfg.isHQ)
        let payload = Self.buildPresencePayloadBytes(presence)
        guard let payload else { return }
        let payloadHash = SyncIdentity.sha256(payload)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: "", kind: "loc", payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return }
        // Keep the flat fields so pre-envelope iOS clients can still consume an
        // iOS sender. New clients verify the exact standard-base64 `p` bytes.
        let sealedDict = Self.makePresenceEnvelope(
            payload: presence, signedPayload: payload,
            publicKey: myPublicKey, signature: sig)
        guard let sealedData = try? JSONSerialization.data(withJSONObject: sealedDict),
              let sealed = SyncCrypto.seal(key, sealedData, aad: SyncCrypto.aadPresenceV3(actorId: actorId, vs: vs)) else { return }
        send(["t": "loc", "by": actorId, "ct": sealed.base64EncodedString(),
              "pub": myPublicKey, "sd": SyncIdentity.urlB64Encode(sd), "vs": vs])
    }

    private func failClosedV3(_ message: String) {
        lastError = message
        wantConnected = false
        task?.cancel(with: .internalServerError, reason: nil)
        task = nil
        status = .offline
    }

    func boundedCallsign(_ value: String) -> String {
        String(value.unicodeScalars.prefix(64))
    }

    /// The sender's exact nine-field JSON payload. It is signed and embedded in
    /// the v1 envelope; receivers must hash these bytes rather than reserialize.
    nonisolated static func buildPresencePayloadBytes(_ payload: PresencePayload) -> Data? {
        let canonical: [String: Any] = [
            "lat": payload.lat, "lon": payload.lon,
            "heading": payload.heading, "speed": payload.speed,
            "callsign": payload.callsign, "affiliation": payload.affiliation,
            "echelon": payload.echelon, "function": payload.function,
            "isHQ": payload.isHQ
        ]
        return try? JSONSerialization.data(withJSONObject: canonical, options: .sortedKeys)
    }

    nonisolated static func makePresenceEnvelope(
        payload: PresencePayload,
        signedPayload: Data,
        publicKey: String,
        signature: String
    ) -> [String: Any] {
        [
            "lat": payload.lat, "lon": payload.lon,
            "heading": payload.heading, "speed": payload.speed,
            "callsign": payload.callsign, "affiliation": payload.affiliation,
            "echelon": payload.echelon, "function": payload.function,
            "isHQ": payload.isHQ,
            "pv": 1,
            "p": signedPayload.base64EncodedString(),
            "pub": publicKey,
            "sig": signature
        ]
    }

    /// Decode the authenticated payload envelope. `pv=1` uses the exact embedded
    /// bytes. An envelope with no version fields takes the legacy same-platform
    /// reconstruction path; malformed/unknown version fields never downgrade.
    nonisolated static func decodePresenceEnvelope(_ inner: [String: Any]) -> PresenceEnvelope? {
        guard let publicKey = inner["pub"] as? String, !publicKey.isEmpty,
              let signature = inner["sig"] as? String, !signature.isEmpty else { return nil }

        if inner["pv"] != nil || inner["p"] != nil {
            guard strictJSONInteger(inner["pv"], minimum: 1, maximum: 1) == 1,
                  let encoded = inner["p"] as? String,
                  let signedPayload = Data(base64Encoded: encoded),
                  signedPayload.base64EncodedString() == encoded,
                  let payload = decodePresencePayloadBytes(signedPayload) else { return nil }
            return PresenceEnvelope(
                payload: payload, signedPayload: signedPayload,
                publicKey: publicKey, signature: signature)
        }

        guard let payload = decodeLegacyPresenceFields(inner),
              let signedPayload = buildPresencePayloadBytes(payload) else { return nil }
        return PresenceEnvelope(
            payload: payload, signedPayload: signedPayload,
            publicKey: publicKey, signature: signature)
    }

    nonisolated static func decodePresencePayloadBytes(_ data: Data) -> PresencePayload? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set([
                "lat", "lon", "heading", "speed", "callsign",
                "affiliation", "echelon", "function", "isHQ"
              ]),
              let callsign = object["callsign"] as? String,
              callsign.unicodeScalars.count <= 64,
              let affiliation = object["affiliation"] as? String,
              let echelon = object["echelon"] as? String,
              let function = object["function"] as? String,
              let isHQNumber = object["isHQ"] as? NSNumber,
              CFGetTypeID(isHQNumber) == CFBooleanGetTypeID(),
              let lat = strictPresenceDouble(object["lat"]), abs(lat) <= 90,
              let lon = strictPresenceDouble(object["lon"]), abs(lon) <= 180,
              let heading = strictPresenceDouble(object["heading"]),
              let speed = strictPresenceDouble(object["speed"]) else { return nil }
        return PresencePayload(
            lat: lat, lon: lon, heading: heading, speed: speed,
            callsign: callsign, affiliation: affiliation,
            echelon: echelon, function: function,
            isHQ: isHQNumber.boolValue)
    }

    private nonisolated static func decodeLegacyPresenceFields(_ object: [String: Any]) -> PresencePayload? {
        let callsign = object["callsign"] as? String ?? ""
        guard callsign.unicodeScalars.count <= 64,
              let lat = strictPresenceDouble(object["lat"]), abs(lat) <= 90,
              let lon = strictPresenceDouble(object["lon"]), abs(lon) <= 180 else { return nil }
        return PresencePayload(
            lat: lat, lon: lon,
            heading: strictPresenceDouble(object["heading"]) ?? 0,
            speed: strictPresenceDouble(object["speed"]) ?? 0,
            callsign: callsign,
            affiliation: object["affiliation"] as? String ?? "unknown",
            echelon: object["echelon"] as? String ?? "team",
            function: object["function"] as? String ?? "infantry",
            isHQ: object["isHQ"] as? Bool ?? false)
    }

    private nonisolated static func strictPresenceDouble(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let result = number.doubleValue
        return result.isFinite ? result : nil
    }

    // MARK: - Presence broadcasting

    /// Fire-and-forget the device's GPS position + presenceConfig as a
    /// `loc` message. Runs on the 5s presence timer while connected and
    /// location sharing is on.
    func sendPresence() {
        if protocolVersion == 3 { sendPresenceV3(); return }
        guard status == .connected,
              presenceConfig.shareLocation,
              let key = roomKey,
              let seed = deviceSeed,
              let loc = locationService?.lastLocation else { return }

        let cfg = presenceConfig
        let callsign = boundedCallsign(cfg.callsign)
        // Milliseconds since epoch (Int64), matching Android, so the signed
        // canonical message and the ts freshness check line up cross-platform.
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let lat = loc.coordinate.latitude
        let lon = loc.coordinate.longitude
        let heading = max(0, loc.course)
        let speed = max(0, loc.speed)
        // Sign identity+position+ts with this device's Ed25519 key. The sig rides
        // INSIDE the sealed payload (relay stays blind), so a room member can't
        // forge another peer's presence and a replayed old blob fails the ts check.
        let sig = SyncSigning.sign(seed, SyncSigning.presenceMessage(
            clientId, ts, lat, lon, heading, speed,
            callsign, cfg.affiliation, cfg.echelon, cfg.function, cfg.isHQ)) ?? ""
        let payload: [String: Any] = [
            "callsign": callsign,
            "affiliation": cfg.affiliation,
            "echelon": cfg.echelon,
            "function": cfg.function,
            "isHQ": cfg.isHQ,
            "lat": lat,
            "lon": lon,
            "heading": heading,
            "speed": speed,
            "ts": ts,
            "pub": myPublicKey,
            "sig": sig
        ]
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payload),
              let sealed = SyncCrypto.seal(key, payloadData, aad: Data("loc|\(clientId)".utf8)) else { return }
        send(["t": "loc", "clientId": clientId, "ct": sealed.base64EncodedString()])
    }

    private func startPresenceTimers() {
        // Broadcast own location every 5 seconds.
        presenceTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.sendPresence()
            }
        }
        // Sweep stale peers every 30 seconds.
        stalenessTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.sweepStalePeers()
            }
        }
    }

    private func stopPresenceTimers() {
        presenceTimer?.invalidate()
        presenceTimer = nil
        stalenessTimer?.invalidate()
        stalenessTimer = nil
    }

    /// Drop peers whose last `loc` is older than 45s.
    private func sweepStalePeers() {
        let cutoff = Date().addingTimeInterval(-45)
        peers = peers.filter { $0.value.receivedAt > cutoff }
    }

    // MARK: Inbound

    private func handleMessage(_ text: String) {
        // frame ceiling: drop anything bigger than 1 MiB before even parsing
        guard text.utf8.count <= Self.maxFrameBytes else { return }
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        // fail-closed: any unexpected throw from downstream parsing is swallowed
        // so a malformed frame never kills the receive loop
        do {
            try handleParsedMessage(obj, frameBytes: data.count)
        } catch {
            // silently drop — don't log the frame content (SEC-019)
        }
    }

    private func handleParsedMessage(_ obj: [String: Any], frameBytes: Int) throws {
        if protocolVersion == 3 { handleParsedMessageV3(obj, frameBytes: frameBytes); return }
        switch obj["t"] as? String {
        case "snapshot":
            let items = obj["items"] as? [[String: Any]] ?? []
            guard items.count <= Self.maxSnapshotItems else { return }
            for item in items { applyRecord(item) }
            if let members = obj["members"] as? [[String: Any]] {
                for member in members.prefix(Self.maxSnapshotItems) {
                    applyPresence(member)
                }
            }
        case "put":
            applyRecord(obj)
        case "del":
            applyDelete(obj)
        case "loc":
            applyPresence(obj)
        case "leave":
            if let cid = obj["clientId"] as? String {
                peers.removeValue(forKey: cid)
            }
        default:
            break
        }
    }

    /// Decrypt and parse an incoming `loc` message (or a member from the
    /// `snapshot`) and update the `peers` dictionary.
    private func applyPresence(_ obj: [String: Any]) {
        guard let cid = obj["clientId"] as? String, !cid.isEmpty else { return }
        guard cid != clientId else { return }
        guard let key = roomKey,
              let ctB64 = obj["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: Data("loc|\(cid)".utf8)),
              let p = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }

        let callsign = p["callsign"] as? String ?? ""
        guard callsign.unicodeScalars.count <= 64 else { return }
        let affiliation = p["affiliation"] as? String ?? "unknown"
        let echelon = p["echelon"] as? String ?? "team"
        let function = p["function"] as? String ?? "infantry"
        let isHQ = p["isHQ"] as? Bool ?? false
        guard let lat = strictDouble(p["lat"]),
              let lon = strictDouble(p["lon"]) else { return }
        let heading = strictDouble(p["heading"]) ?? 0
        let speed = strictDouble(p["speed"]) ?? 0
        let ts = (p["ts"] as? NSNumber)?.int64Value ?? 0

        // Per-device auth: pin the peer's key on first sight (TOFU), then require
        // every later presence to be signed by that same key. A room member can't
        // impersonate an established peer; a changed key is rejected as a possible
        // swap; a relay replaying an old (signed) blob is caught by the ts check.
        guard let pub = p["pub"] as? String, !pub.isEmpty,
              let sig = p["sig"] as? String, !sig.isEmpty else { return }
        if let pinned = peerKeys[cid] { if pinned != pub { return } } else { peerKeys[cid] = pub }
        let signed = SyncSigning.presenceMessage(cid, ts, lat, lon, heading, speed,
                                                 callsign, affiliation, echelon, function, isHQ)
        guard SyncSigning.verify(pub, signed, sig) else { return }
        if let last = peerTs[cid], ts <= last { return }  // replay / rollback
        peerTs[cid] = ts

        peers[cid] = PresencePeer(
            clientId: cid,
            callsign: callsign,
            affiliation: affiliation,
            echelon: echelon,
            function: function,
            isHQ: isHQ,
            lat: lat,
            lon: lon,
            heading: heading,
            speed: speed,
            ts: Double(ts),
            receivedAt: Date()
        )
    }

    private func applyRecord(_ rec: [String: Any]) {
        // Snapshot tombstones arrive as records with deleted=true; route them
        // through the same signed-delete verification as a live "del".
        if (rec["deleted"] as? Bool) == true { applyDelete(rec); return }
        guard let id = rec["id"] as? String, !id.isEmpty else { return }
        guard let v = strictVersion(rec["v"]) else { return }
        // Monotonic per-id version: reject anything <= the highest applied, and
        // keep rejecting after a delete (versions[id] survives as a tombstone) so
        // a relay can't resurrect a deleted object by replaying an older-but-
        // validly-signed put.
        if let known = versions[id], known >= v { return } // stale / superseded / post-delete
        let recKind = rec["kind"] as? String ?? "unknown"
        let by = rec["by"] as? String ?? ""
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: id, v: v, kind: recKind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }
        let content = inner["c"] as? String ?? ""
        // Device authorship: the write must be signed by the key pinned to `by`
        // (TOFU). A room member can't forge a write as another established
        // device; a key that doesn't match the pin is rejected as a swap.
        guard verifyObjectSig(by: by, inner: inner,
                              signed: SyncSigning.objectMessage(id, v, recKind, by, content)),
              let contentData = content.data(using: .utf8) else { return }
        // advance clock only AFTER AEAD + sig pass — a hostile relay can't
        // poison the clock with a forged message it can't sign
        clock = max(clock, v)

        let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
        guard let parsed = try? GeoJSONImporter.parse(contentData, existingLayers: drawingStore.layers,
                                                      fallbackLayerID: fallback) else { return }
        for layer in parsed.newLayers where !drawingStore.layers.contains(where: { $0.id == layer.id }) {
            drawingStore.addLayerVerbatim(layer)
        }
        for wp in parsed.waypoints { upsert(waypoint: wp) }
        for shape in parsed.drawings { upsert(shape: shape) }

        versions[id] = v
        kindById[id] = parsed.waypoints.isEmpty ? "drawing" : "waypoint"
        lastContent[id] = reexport(id: id)

        // Let UI know a remote change landed.
        let kind = parsed.waypoints.isEmpty ? "Drawing" : "Waypoint"
        remoteUpdateSubject.send("\(kind) updated by another device")
    }

    private func applyDelete(_ rec: [String: Any]) {
        guard let id = rec["id"] as? String, !id.isEmpty else { return }
        guard let v = strictVersion(rec["v"]) else { return }
        if let known = versions[id], known >= v { return } // stale / already-superseded delete
        let by = rec["by"] as? String ?? ""
        // Open the sealed proof (proves room-key possession, so a relay with no
        // room key can't forge a delete) then verify the device signature.
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: id, v: v, kind: "del")),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              verifyObjectSig(by: by, inner: inner,
                              signed: SyncSigning.objectMessage(id, v, "del", by, "")) else { return }
        clock = max(clock, v)
        if let wp = waypointStore.waypoints.first(where: { $0.id.uuidString == id }) {
            waypointStore.remove(wp)
        }
        if let shape = drawingStore.shapes.first(where: { $0.id.uuidString == id }) {
            drawingStore.remove(shape)
        }
        versions[id] = v
        lastContent[id] = nil; kindById[id] = nil

        remoteUpdateSubject.send("Object removed by another device")
    }

    /// TOFU-pin `by`'s signing key and verify `signed` under it. Shared by
    /// object puts and deletes and by presence, so a device has one identity per
    /// clientId. Missing/garbage fields, or a key that doesn't match the existing
    /// pin, return false (the write is rejected).
    private func verifyObjectSig(by: String, inner: [String: Any], signed: Data) -> Bool {
        guard !by.isEmpty,
              let pub = inner["pub"] as? String, !pub.isEmpty,
              let sig = inner["sig"] as? String, !sig.isEmpty else { return false }
        if let pinned = peerKeys[by] { if pinned != pub { return false } } else { peerKeys[by] = pub }
        return SyncSigning.verify(pub, signed, sig)
    }

    private func upsert(waypoint wp: Waypoint) {
        if waypointStore.waypoints.contains(where: { $0.id == wp.id }) { waypointStore.update(wp) }
        else { waypointStore.add(wp) }
    }

    private func upsert(shape: DrawingShape) {
        if drawingStore.shapes.contains(where: { $0.id == shape.id }) { drawingStore.update(shape) }
        else { drawingStore.add(shape) }
    }

    /// Re-serialise so the next diff doesn't see a spurious change.
    private func reexport(id: String) -> String {
        let layers = drawingStore.layers
        if let wp = waypointStore.waypoints.first(where: { $0.id.uuidString == id }) {
            return (try? GeoJSONExporter.export(waypoints: [wp], drawings: [], layers: layers)) ?? ""
        }
        if let shape = drawingStore.shapes.first(where: { $0.id.uuidString == id }) {
            return (try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers)) ?? ""
        }
        return ""
    }

    // strict parsing helpers (SEC-006) — reject anything that isn't an exact,
    // in-range, finite value instead of silently coercing garbage

    /// Non-negative integer version in [0, maxVersion]. Rejects floats with
    /// fractional parts, NaN, infinity, out-of-range, and wrong types.
    private func strictVersion(_ any: Any?) -> Int64? {
        Self.strictJSONInteger(any, minimum: 0, maximum: Self.maxVersion)
    }

    /// Parse an integer produced by `JSONSerialization` without confusing the
    /// numeric values 0 and 1 with JSON booleans. Swift's normal `value is Bool`
    /// check returns true for those two `__NSCFNumber` values as well as for the
    /// distinct `__NSCFBoolean` singleton, which previously made an empty room's
    /// valid `snapshot-begin { seq: 0 }` fail closed before iOS sent its hello.
    /// Floating-point encodings are rejected even when their rounded value is an
    /// integer: accepting them would let precision loss change a signed protocol
    /// field before its range check.
    /// Internal so the production parser can be regression-tested with values
    /// that have actually passed through `JSONSerialization`.
    nonisolated static func strictJSONInteger(_ value: Any?, minimum: Int64, maximum: Int64) -> Int64? {
        guard minimum <= maximum, let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              !CFNumberIsFloatType(number),
              let integer = Int64(number.stringValue),
              integer >= minimum, integer <= maximum else { return nil }
        return integer
    }

    /// Finite double, or nil for NaN/infinity/wrong type.
    private func strictDouble(_ any: Any?) -> Double? {
        if let d = any as? Double { return d.isFinite ? d : nil }
        if let i = any as? Int { return Double(i) }
        if let n = any as? NSNumber {
            let d = n.doubleValue
            return d.isFinite ? d : nil
        }
        return nil
    }

    // MARK: - v3 Inbound

    private func handleParsedMessageV3(_ obj: [String: Any], frameBytes: Int) {
        switch obj["t"] as? String {
        case "snapshot-begin":
            guard status == .connecting,
                  let seq = strictNonNegativeInt64(obj["seq"]) else {
                failClosedV3("Invalid sync snapshot fence.")
                return
            }
            snapshotSeq = seq
            snapshotRecords.removeAll()
            snapshotInvalid = false
            snapshotSawFinalPage = false
            snapshotAggregateBytes = frameBytes
            snapshotWireIds.removeAll()
            status = .snapshotting
        case "snapshot":
            snapshotAggregateBytes += frameBytes
            guard status == .snapshotting, snapshotSeq != nil,
                  let items = obj["items"] as? [[String: Any]],
                  let more = obj["more"] as? Bool,
                  !snapshotSawFinalPage,
                  snapshotRecords.count <= Self.maxSnapshotItems - items.count,
                  snapshotAggregateBytes <= Self.maxSnapshotAggregateBytes else {
                snapshotInvalid = true
                return
            }
            for item in items {
                guard let wireId = item["id"] as? String,
                      SyncIdentity.decodeCanonical32(wireId) != nil,
                      snapshotWireIds.insert(wireId).inserted else {
                    snapshotInvalid = true; return
                }
            }
            snapshotRecords.append(contentsOf: items)
            snapshotSawFinalPage = !more
        case "snapshot-end":
            snapshotAggregateBytes += frameBytes
            if snapshotAggregateBytes > Self.maxSnapshotAggregateBytes { snapshotInvalid = true }
            finishSnapshotV3(obj)
        case "hello":
            guard snapshotHasBeenAppliedV3 else { return }
            applyHelloV3(obj)
        case "hello-ack":
            applyHelloAckV3(obj)
        case "put":
            guard snapshotHasBeenAppliedV3 else { return }
            applyLiveRecordV3(obj, deleted: false)
        case "del":
            guard snapshotHasBeenAppliedV3 else { return }
            applyLiveRecordV3(obj, deleted: true)
        case "loc":
            guard snapshotHasBeenAppliedV3 else { return }
            applyPresenceV3(obj)
        case "leave":
            if let aid = obj["by"] as? String {
                peers.removeValue(forKey: aid)
            }
        default:
            break
        }
    }

    private func finishSnapshotV3(_ obj: [String: Any]) {
        guard status == .snapshotting,
              let expectedSeq = snapshotSeq,
              let endSeq = strictNonNegativeInt64(obj["seq"]),
              endSeq == expectedSeq,
              !snapshotInvalid,
              snapshotSawFinalPage,
              let rs = replayState else {
            failClosedV3("Sync snapshot authentication failed.")
            return
        }

        var validated: [ValidatedRecordV3] = []
        validated.reserveCapacity(snapshotRecords.count)
        for record in snapshotRecords {
            let deleted = (record["deleted"] as? Bool) == true
            guard let value = validateRecordV3(record, deleted: deleted) else {
                failClosedV3("Sync snapshot contained an unauthenticated record.")
                return
            }
            validated.append(value)
        }

        let wasStale = rs.lastSnapshotSeq >= 0 && endSeq < rs.lastSnapshotSeq
        do {
            let remotes = validated.map {
                SyncReplayState.RemoteMutation(
                    mutation: $0.mutation,
                    priorModelHash: modelContentHash(localId: $0.localId),
                    localModelId: $0.localId,
                    acceptedGeneration: modelRevisionJournal?.generation($0.localId) ?? 0,
                    expectedModelHash: $0.expectedModelHash)
            }
            _ = try rs.commitRemoteSnapshot(remotes, seq: endSeq)
            resolvingPendingModel = true
            defer { resolvingPendingModel = false }
            for (index, value) in validated.enumerated() {
                guard try resolvePendingModelApplication(value, currentHash: remotes[index].priorModelHash) else {
                    failClosedV3("Rollback-protection state could not be saved.")
                    return
                }
            }
            guard try resolveUnmatchedPendingModelApplications() else {
                failClosedV3("Rollback-protection state could not be saved.")
                return
            }
        } catch {
            failClosedV3("Rollback-protection state could not be saved.")
            return
        }

        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        snapshotAggregateBytes = 0
        snapshotWireIds.removeAll()
        if wasStale { lastError = "The relay served an older snapshot; newer authenticated local state was retained." }
        awaitingHelloAck = true
        // Remain snapshotting (and therefore outbound-gated) until the relay
        // confirms it has verified/persisted hello and attached the actor tuple.
        sendHelloV3()
    }

    private var snapshotHasBeenAppliedV3: Bool {
        snapshotSeq == nil && (status == .snapshotting || status == .connected)
    }

    private func applyHelloAckV3(_ obj: [String: Any]) {
        guard awaitingHelloAck, status == .snapshotting,
              let actorId = myActorId,
              let by = obj["by"] as? String,
              let sd = obj["sd"] as? String,
              let vs = obj["vs"] as? String,
              let expectedVs = localHelloVersion,
              let ownSession = sessionDomain,
              SyncIdentity.helloAckMatches(actorId: actorId, sessionDomain: ownSession, expectedVersion: expectedVs,
                                           frameActorId: by, frameSessionDomain: sd, frameVersion: vs) else { return }
        awaitingHelloAck = false
        status = .connected
        replayState?.recoverableLocalDeletes(actorId: actorId, pubkey: myPublicKey).forEach {
            _ = sendDelV3(wireObjectId: $0.0, recoveryStamp: $0.1)
        }
        syncLocalStateV3(waypoints: waypointStore.waypoints,
                         shapes: drawingStore.shapes,
                         layers: drawingStore.layers)
    }

    private func applyHelloV3(_ obj: [String: Any]) {
        guard let by = obj["by"] as? String,
              let pub = obj["pub"] as? String,
              let sd = obj["sd"] as? String,
              let vs = obj["vs"] as? String,
              let sig = obj["sig"] as? String,
              by != myActorId,
              let rs = replayState, let keys = v3Keys,
              SyncIdentity.verifyHello(actorId: by, publicKey: pub,
                                       sessionDomain: sd, versionStamp: vs,
                                       signature: sig, roomIdRaw: keys.roomIdRaw) else { return }
        let epoch = String(vs.prefix(16))
        do { _ = try rs.acceptHello(actorId: by, pubkey: pub, sessionDomain: sd, epochHex: epoch) }
        catch { failClosedV3("Actor rollback-protection state could not be saved.") }
    }

    private func applyLiveRecordV3(_ rec: [String: Any], deleted: Bool) {
        guard let rs = replayState,
              let validated = validateRecordV3(rec, deleted: deleted) else { return }
        do {
            let priorHash = modelContentHash(localId: validated.localId)
            guard try rs.commitRemote(.init(
                mutation: validated.mutation,
                priorModelHash: priorHash,
                localModelId: validated.localId,
                acceptedGeneration: modelRevisionJournal?.generation(validated.localId) ?? 0,
                expectedModelHash: validated.expectedModelHash)) else { return }
            resolvingPendingModel = true
            defer { resolvingPendingModel = false }
            guard try resolvePendingModelApplication(validated, currentHash: priorHash) else {
                failClosedV3("Rollback-protection state could not be saved.")
                return
            }
        } catch {
            failClosedV3("Rollback-protection state could not be saved.")
            return
        }
    }

    private func modelContentHash(localId: String?) -> String? {
        guard let localId else { return nil }
        let content = reexport(id: localId)
        guard !content.isEmpty else { return nil }
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
    }

    private func resolvePendingModelApplication(_ value: ValidatedRecordV3, currentHash: String?) throws -> Bool {
        guard let rs = replayState else { return false }
        let mutation = value.mutation
        switch rs.pendingModelDecision(
            mutation,
            currentModelHash: currentHash,
            currentGeneration: modelRevisionJournal?.generation(value.localId) ?? 0) {
        case .applyIncoming:
            applyValidatedRecordV3(value)
            guard modelContentHash(localId: value.localId) == value.expectedModelHash else { return false }
            return try rs.clearPendingModelApplication(mutation)
        case .alreadyApplied:
            markModelBaseline(value)
            return try rs.clearPendingModelApplication(mutation)
        case .localDiverged:
            if let localId = value.localId { forcedLocalDiff.insert(localId) }
            return try rs.clearPendingModelApplication(mutation)
        case .none:
            if rs.isExactPersistedMutation(mutation) {
                if currentHash == value.expectedModelHash { markModelBaseline(value) }
                else if let localId = value.localId { forcedLocalDiff.insert(localId) }
            }
            return true
        }
    }

    private func markModelBaseline(_ value: ValidatedRecordV3) {
        guard let localId = value.localId else { return }
        if value.parsed == nil {
            lastContent[localId] = nil; kindById[localId] = nil
        } else {
            let content = reexport(id: localId)
            if !content.isEmpty {
                lastContent[localId] = content
                kindById[localId] = value.parsed?.waypoints.isEmpty == false ? "waypoint" : "drawing"
            }
        }
        forcedLocalDiff.remove(localId)
    }

    /// If the relay omitted or contradicted pending work, the authenticated
    /// ciphertext needed for repair is unavailable. Preserve the current model
    /// and force it to win with a new local stamp after hello acknowledgement.
    private func resolveUnmatchedPendingModelApplications() throws -> Bool {
        guard let rs = replayState else { return false }
        for remote in rs.pendingRemoteMutations() {
            let current = modelContentHash(localId: remote.localModelId)
            if current == remote.expectedModelHash {
                markCurrentModelBaseline(localId: remote.localModelId)
            } else if let localId = remote.localModelId {
                forcedLocalDiff.insert(localId)
            }
            guard try rs.clearPendingModelApplication(remote.mutation) else { return false }
        }
        return true
    }

    private func markCurrentModelBaseline(localId: String?) {
        guard let localId else { return }
        let content = reexport(id: localId)
        if content.isEmpty {
            lastContent[localId] = nil; kindById[localId] = nil
        } else {
            lastContent[localId] = content
            kindById[localId] = waypointStore.waypoints.contains { $0.id.uuidString == localId }
                ? "waypoint" : "drawing"
        }
        forcedLocalDiff.remove(localId)
    }

    /// Authenticate and parse without changing replay or application state.
    private func validateRecordV3(_ rec: [String: Any], deleted: Bool) -> ValidatedRecordV3? {
        guard let wireId = rec["id"] as? String,
              SyncIdentity.decodeCanonical32(wireId) != nil,
              let vsString = rec["vs"] as? String,
              let stamp = VersionStamp.parse(vsString),
              let by = rec["by"] as? String, stamp.actorId == by,
              let publicKey = rec["pub"] as? String,
              let sessionString = rec["sd"] as? String,
              let sessionRaw = SyncIdentity.decodeCanonical32(sessionString),
              let kind = rec["kind"] as? String,
              kind.count <= 32,
              deleted ? kind == "del" : (kind == "waypoint" || kind == "drawing"),
              let keys = v3Keys,
              SyncIdentity.actorBindingIsValid(actorId: by, publicKey: publicKey, roomIdRaw: keys.roomIdRaw),
              replayState?.actorKeyIsAcceptable(by, pubkey: publicKey) == true,
              let key = roomKey,
              let ctBase64 = rec["ct"] as? String,
              ctBase64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctBase64), blob.base64EncodedString() == ctBase64,
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vsString, kind: kind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let signature = inner["sig"] as? String else { return nil }

        let contentData: Data
        let parsed: GeoJSONImporter.Result?
        let domain: UInt8
        let payloadHash: Data
        let localId: String?
        let expectedModelHash: String?
        if deleted {
            guard inner["c"] == nil else { return nil }
            contentData = Data()
            parsed = nil
            domain = SyncIdentity.domainDelete
            payloadHash = SyncIdentity.sha256(contentData)
            localId = findLocalIdForWireId(wireId)
            expectedModelHash = nil
        } else {
            guard let content = inner["c"] as? String,
                  let bytes = content.data(using: .utf8) else { return nil }
            contentData = bytes
            let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
            guard let imported = try? GeoJSONImporter.parse(bytes, existingLayers: drawingStore.layers,
                                                             fallbackLayerID: fallback),
                  imported.invalidSkipped == 0,
                  (kind == "waypoint" ? (imported.waypoints.count == 1 && imported.drawings.isEmpty)
                                      : (imported.drawings.count == 1 && imported.waypoints.isEmpty)) else { return nil }
            let embeddedID = kind == "waypoint" ? imported.waypoints[0].id : imported.drawings[0].id
            let computedWireID = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(embeddedID))
            guard computedWireID == wireId else { return nil }
            localId = embeddedID.uuidString
            parsed = imported
            expectedModelHash = receiverModelHash(parsed: imported, localId: embeddedID)
            guard expectedModelHash != nil else { return nil }
            domain = SyncIdentity.domainPut
            payloadHash = SyncIdentity.sha256(contentData)
        }

        let preimage = SyncIdentity.buildPreimage(
            domain: domain, roomIdRaw: keys.roomIdRaw, actorId: by,
            sessionDomain: sessionRaw,
            counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: wireId, kind: kind, payloadHash: payloadHash)
        guard SyncSigning.verify(publicKey, preimage, signature) else { return nil }

        let mutationKind: SyncReplayState.MutationKind = deleted
            ? .delete
            : .put(contentHash: SyncIdentity.bytesToHex(payloadHash))
        return ValidatedRecordV3(
            mutation: .init(wireObjectId: wireId, stamp: stamp,
                            publicKey: publicKey, kind: mutationKind),
            parsed: parsed, localId: localId, expectedModelHash: expectedModelHash)
    }

    /// Receiver-local fixed-point hash; the authenticated payload hash remains sender bytes.
    private func receiverModelHash(parsed: GeoJSONImporter.Result, localId: UUID) -> String? {
        var layers = drawingStore.layers
        for layer in parsed.newLayers where !layers.contains(where: { $0.id == layer.id }) { layers.append(layer) }
        let content: String?
        if let waypoint = parsed.waypoints.first(where: { $0.id == localId }) {
            content = try? GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layers)
        } else if let drawing = parsed.drawings.first(where: { $0.id == localId }) {
            content = try? GeoJSONExporter.export(waypoints: [], drawings: [drawing], layers: layers)
        } else {
            content = nil
        }
        guard let content else { return nil }
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
    }

    private func applyValidatedRecordV3(_ value: ValidatedRecordV3) {
        guard let parsed = value.parsed else {
            if let localId = value.localId {
                if let wp = waypointStore.waypoints.first(where: { $0.id.uuidString == localId }) { waypointStore.remove(wp) }
                if let shape = drawingStore.shapes.first(where: { $0.id.uuidString == localId }) { drawingStore.remove(shape) }
                lastContent[localId] = nil
                kindById[localId] = nil
            }
            remoteUpdateSubject.send("Object removed by another device")
            refreshObservedModelBaseline(localId: value.localId)
            return
        }

        for layer in parsed.newLayers where !drawingStore.layers.contains(where: { $0.id == layer.id }) {
            drawingStore.addLayerVerbatim(layer)
        }
        for waypoint in parsed.waypoints { upsert(waypoint: waypoint) }
        for shape in parsed.drawings { upsert(shape: shape) }
        if let waypoint = parsed.waypoints.first {
            let id = waypoint.id.uuidString
            kindById[id] = "waypoint"
            lastContent[id] = reexport(id: id)
            remoteUpdateSubject.send("Waypoint updated by another device")
        } else if let shape = parsed.drawings.first {
            let id = shape.id.uuidString
            kindById[id] = "drawing"
            lastContent[id] = reexport(id: id)
            remoteUpdateSubject.send("Drawing updated by another device")
        }
        refreshObservedModelBaseline(localId: value.localId)
    }

    private func applyPresenceV3(_ obj: [String: Any]) {
        guard let actorId = obj["by"] as? String,
              actorId != myActorId else { return }
        guard let pub = obj["pub"] as? String,
              let sdString = obj["sd"] as? String,
              let sd = SyncIdentity.decodeCanonical32(sdString),
              let vsStr = obj["vs"] as? String,
              let stamp = VersionStamp.parse(vsStr),
              stamp.actorId == actorId,
              let rs = replayState, let keys = v3Keys,
              SyncIdentity.actorBindingIsValid(actorId: actorId, publicKey: pub, roomIdRaw: keys.roomIdRaw),
              rs.getPinnedPubkey(actorId) == pub,
              rs.activeSessionDomain(actorId) == sdString else { return }
        guard let key = roomKey,
              let ctB64 = obj["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              blob.base64EncodedString() == ctB64,
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadPresenceV3(actorId: actorId, vs: vsStr)),
              let p = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }

        guard let envelope = Self.decodePresenceEnvelope(p),
              envelope.publicKey == pub else { return }
        let presence = envelope.payload
        let payloadHash = SyncIdentity.sha256(envelope.signedPayload)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: "", kind: "loc", payloadHash: payloadHash)
        guard SyncSigning.verify(pub, preimage, envelope.signature) else { return }
        do {
            guard try rs.acceptPresence(
                actorId: actorId, sessionDomain: sdString, counter: stamp.counter) else { return }
        } catch {
            failClosedV3("Presence replay state could not be saved.")
            return
        }

        peers[actorId] = PresencePeer(
            clientId: actorId,
            callsign: presence.callsign,
            affiliation: presence.affiliation,
            echelon: presence.echelon,
            function: presence.function,
            isHQ: presence.isHQ,
            lat: presence.lat,
            lon: presence.lon,
            heading: presence.heading,
            speed: presence.speed,
            ts: Date().timeIntervalSince1970 * 1000,
            receivedAt: Date()
        )
    }

    private func strictNonNegativeInt64(_ value: Any?) -> Int64? {
        Self.strictJSONInteger(value, minimum: 0, maximum: Int64.max)
    }

    /// Reverse-lookup: find the local UUID string that maps to a given wire object ID.
    private func findLocalIdForWireId(_ wireId: String) -> String? {
        guard let keys = v3Keys else { return nil }
        for id in lastContent.keys {
            guard let uuid = UUID(uuidString: id) else { continue }
            let computed = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(uuid))
            if computed == wireId { return id }
        }
        // also check stores directly
        for wp in waypointStore.waypoints {
            let computed = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(wp.id))
            if computed == wireId { return wp.id.uuidString }
        }
        for shape in drawingStore.shapes {
            let computed = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(shape.id))
            if computed == wireId { return shape.id.uuidString }
        }
        return nil
    }
}
