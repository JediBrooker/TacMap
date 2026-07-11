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
    enum Status { case offline, connecting, connected }

    @Published private(set) var status: Status = .offline
    @Published private(set) var room: String?
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

    // Per-device Ed25519 signing identity. Seed sealed at rest; the public key
    // rides every presence AND every object write so peers pin it (TOFU) and
    // reject a room member impersonating an established device. One identity per
    // clientId, shared by presence + object writes. Room state cleared on leave.
    private lazy var deviceSeed: Data = loadOrCreateDeviceSeed()
    private lazy var myPublicKey: String = SyncSigning.publicKey(deviceSeed) ?? ""
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

    // v2 containment ceilings (pending v3 protocol limits)
    private static let maxFrameBytes = 1_048_576        // 1 MiB text
    private static let maxBase64Bytes = 1_048_576        // 1 MiB encoded ct
    private static let maxSnapshotItems = 10_000
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

    /// The device's Ed25519 signing seed, sealed at rest. Generated once and
    /// reused so this device keeps a stable identity across sessions (peers pin
    /// it). If the seal can't be persisted (locked auth-bound key), a fresh seed
    /// is used for this session only.
    private func loadOrCreateDeviceSeed() -> Data {
        if let stored = UserDefaults.standard.data(forKey: Self.deviceSeedKey),
           SealedEnvelope.isSealedFile(stored),
           let key = try? SafeStore.keyProvider(),
           let seed = SealedEnvelope.openFile(key: key, blob: stored, label: Self.deviceSeedLabel),
           seed.count == 32 {
            return seed
        }
        let seed = SyncSigning.generateSeed()
        if let key = try? SafeStore.keyProvider(),
           let sealed = try? SealedEnvelope.sealFile(key: key, plaintext: seed, label: Self.deviceSeedLabel) {
            UserDefaults.standard.set(sealed, forKey: Self.deviceSeedKey)
        }
        return seed
    }

    // MARK: API

    func join(_ joinCode: String) {
        let code = joinCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        leave()
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
            let pubRaw = SyncSigning.publicKeyRaw(deviceSeed) ?? Data()
            myPublicKeyRaw = pubRaw
            myActorId = SyncIdentity.actorId(roomIdRaw: keys.roomIdRaw, pubkeyRaw: pubRaw)
            let containerURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            let rs = SyncReplayState(roomId: keys.roomId, containerURL: containerURL)
            rs.load()
            replayState = rs
            sessionDomain = SyncIdentity.generateSessionDomain()
        } else {
            protocolVersion = 2
            let keys = SyncCrypto.deriveRoom(code)
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
        replayState?.save()

        roomKey = nil
        authToken = nil
        room = nil
        status = .offline
        clock = 0
        versions.removeAll(); lastContent.removeAll(); kindById.removeAll()
        peers.removeAll()
        peerKeys.removeAll(); peerTs.removeAll()

        // v3 state cleared per-session (not durable)
        v3Keys = nil
        myActorId = nil
        replayState = nil
        sessionDomain = nil
        myPublicKeyRaw = nil
        protocolVersion = 2
    }

    // MARK: Connection

    private func connect() {
        guard let roomId else { return }
        let path = protocolVersion == 3 ? "/v3/room/\(roomId)" : "/room/\(roomId)"
        guard let url = URL(string: relayEndpoint + path) else { return }
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
                    self.status = .connected
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
        guard let key = roomKey else { return }
        // Sign the write, then seal {content, pub, sig} together. The signature
        // rides INSIDE the sealed blob so the relay stays E2E-blind to device
        // identity; a receiver proves room-key possession by opening it and
        // device authorship by verifying the sig against the pinned key.
        let sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(id, v, kind, clientId, content)) ?? ""
        let inner: [String: Any] = ["c": content, "pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: id, v: v, kind: kind)) else { return }
        send(["t": "put", "id": id, "v": v, "by": clientId, "kind": kind,
              "ct": sealed.base64EncodedString()])
    }

    private func sendDel(id: String, v: Int64) {
        guard let key = roomKey else { return }
        // Deletes used to be an unauthenticated {id,v} - a coerced relay could
        // forge one and silently remove a contact. Now seal a signed proof: only
        // a room-key holder can produce it (relay can't), and it's attributable
        // to a device. AAD "del" so it can't be replayed as a put.
        let sig = SyncSigning.sign(deviceSeed, SyncSigning.objectMessage(id, v, "del", clientId, "")) ?? ""
        let inner: [String: Any] = ["pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: id, v: v, kind: "del")) else { return }
        send(["t": "del", "id": id, "v": v, "by": clientId, "ct": sealed.base64EncodedString()])
    }

    private func send(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    // MARK: - v3 Outbound

    private func syncLocalStateV3(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) {
        guard status == .connected, let keys = v3Keys else { return }
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

        for (id, entry) in current where lastContent[id] != entry.content {
            lastContent[id] = entry.content
            kindById[id] = entry.kind
            let wireId = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(entry.localId))
            sendPutV3(wireObjectId: wireId, kind: entry.kind, content: entry.content)
        }
        for id in lastContent.keys where current[id] == nil {
            if let uuid = UUID(uuidString: id) {
                let wireId = SyncIdentity.wireObjectId(
                    metadataKey: keys.metadataKey,
                    localUuidBytes: SyncIdentity.uuidToBytes(uuid))
                sendDelV3(wireObjectId: wireId, kind: kindById[id] ?? "unknown")
            }
            lastContent[id] = nil; kindById[id] = nil
        }
    }

    private func sendPutV3(wireObjectId: String, kind: String, content: String) {
        guard let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys else { return }
        let counter = rs.nextCounter()
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()
        let contentData = Data(content.utf8)
        let payloadHash = SyncIdentity.sha256(contentData)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPut, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: kind, payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(deviceSeed, preimage) else { return }
        let inner: [String: Any] = ["c": content, "pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: kind)) else { return }
        send(["t": "put", "id": wireObjectId, "vs": vs, "by": actorId, "kind": kind,
              "ct": sealed.base64EncodedString()])
        rs.save()
    }

    private func sendDelV3(wireObjectId: String, kind: String) {
        guard let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys else { return }
        let counter = rs.nextCounter()
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()
        let payloadHash = SyncIdentity.sha256(Data())
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainDelete, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: kind, payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(deviceSeed, preimage) else { return }
        let inner: [String: Any] = ["pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: "del")) else { return }
        send(["t": "del", "id": wireObjectId, "vs": vs, "by": actorId, "kind": kind,
              "ct": sealed.base64EncodedString()])
        rs.save()
    }

    private func sendPresenceV3() {
        guard status == .connected, presenceConfig.shareLocation,
              let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys,
              let loc = locationService?.lastLocation else { return }

        let cfg = presenceConfig
        let lat = loc.coordinate.latitude
        let lon = loc.coordinate.longitude
        let heading = max(0, loc.course)
        let speed = max(0, loc.speed)
        let counter = rs.nextCounter()
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()

        let payload: [String: Any] = [
            "callsign": cfg.callsign, "affiliation": cfg.affiliation,
            "echelon": cfg.echelon, "function": cfg.function, "isHQ": cfg.isHQ,
            "lat": lat, "lon": lon, "heading": heading, "speed": speed,
            "pub": myPublicKey
        ]
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payload, options: .sortedKeys) else { return }
        let payloadHash = SyncIdentity.sha256(payloadData)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: actorId, kind: "presence", payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(deviceSeed, preimage) else { return }
        var signedPayload = payload
        signedPayload["sig"] = sig
        guard let signedData = try? JSONSerialization.data(withJSONObject: signedPayload),
              let sealed = SyncCrypto.seal(key, signedData, aad: SyncCrypto.aadPresenceV3(actorId: actorId, vs: vs)) else { return }
        send(["t": "loc", "actorId": actorId, "vs": vs, "ct": sealed.base64EncodedString()])
        rs.save()
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
              let loc = locationService?.lastLocation else { return }

        let cfg = presenceConfig
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
        let sig = SyncSigning.sign(deviceSeed, SyncSigning.presenceMessage(
            clientId, ts, lat, lon, heading, speed,
            cfg.callsign, cfg.affiliation, cfg.echelon, cfg.function, cfg.isHQ)) ?? ""
        let payload: [String: Any] = [
            "callsign": cfg.callsign,
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
            try handleParsedMessage(obj)
        } catch {
            // silently drop — don't log the frame content (SEC-019)
        }
    }

    private func handleParsedMessage(_ obj: [String: Any]) throws {
        if protocolVersion == 3 { handleParsedMessageV3(obj); return }
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
        guard let n = any as? NSNumber, !(any is Bool) else { return nil }
        // NSJSONSerialization represents JSON integers as Int/Int64 and floats
        // as Double. Reject fractional doubles (1e100, 1.5, NaN, Inf).
        let d = n.doubleValue
        guard d.isFinite, d == d.rounded(.towardZero), d >= 0,
              d <= Double(Self.maxVersion) else { return nil }
        let v = n.int64Value
        guard v >= 0, v <= Self.maxVersion else { return nil }
        return v
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

    private func handleParsedMessageV3(_ obj: [String: Any]) {
        switch obj["t"] as? String {
        case "snapshot":
            let items = obj["items"] as? [[String: Any]] ?? []
            guard items.count <= Self.maxSnapshotItems else { return }
            for item in items {
                if (item["deleted"] as? Bool) == true { applyDeleteV3(item) }
                else { applyRecordV3(item) }
            }
            if let members = obj["members"] as? [[String: Any]] {
                for member in members.prefix(Self.maxSnapshotItems) {
                    applyPresenceV3(member)
                }
            }
        case "put":
            applyRecordV3(obj)
        case "del":
            applyDeleteV3(obj)
        case "loc":
            applyPresenceV3(obj)
        case "leave":
            if let aid = obj["actorId"] as? String {
                peers.removeValue(forKey: aid)
            }
        default:
            break
        }
    }

    private func applyRecordV3(_ rec: [String: Any]) {
        guard let wireId = rec["id"] as? String, !wireId.isEmpty,
              let vsStr = rec["vs"] as? String,
              let stamp = VersionStamp.parse(vsStr),
              let rs = replayState else { return }
        let by = rec["by"] as? String ?? ""
        let recKind = rec["kind"] as? String ?? "unknown"
        guard rs.advance(wireId, stamp) else { return }
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vsStr, kind: recKind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }
        let content = inner["c"] as? String ?? ""
        guard let pub = inner["pub"] as? String, !pub.isEmpty,
              let sig = inner["sig"] as? String, !sig.isEmpty else { return }
        if !rs.registerActor(by, pubkey: pub) { return }
        guard let contentData = content.data(using: .utf8) else { return }
        let payloadHash = SyncIdentity.sha256(contentData)
        guard let keys = v3Keys, let sd = rs.getSessionDomain(by).flatMap({ SyncIdentity.urlB64Decode($0) }) ?? sessionDomain else { return }
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPut, roomIdRaw: keys.roomIdRaw,
            actorId: by, sessionDomain: sd, counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: wireId, kind: recKind, payloadHash: payloadHash)
        guard SyncSigning.verify(pub, preimage, sig) else { return }

        let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
        guard let parsed = try? GeoJSONImporter.parse(contentData, existingLayers: drawingStore.layers,
                                                      fallbackLayerID: fallback) else { return }
        for layer in parsed.newLayers where !drawingStore.layers.contains(where: { $0.id == layer.id }) {
            drawingStore.addLayerVerbatim(layer)
        }
        for wp in parsed.waypoints { upsert(waypoint: wp) }
        for shape in parsed.drawings { upsert(shape: shape) }

        let localId = findLocalIdForWireId(wireId)
        if let lid = localId {
            kindById[lid] = parsed.waypoints.isEmpty ? "drawing" : "waypoint"
            lastContent[lid] = reexport(id: lid)
        }
        rs.save()
        let kind = parsed.waypoints.isEmpty ? "Drawing" : "Waypoint"
        remoteUpdateSubject.send("\(kind) updated by another device")
    }

    private func applyDeleteV3(_ rec: [String: Any]) {
        guard let wireId = rec["id"] as? String, !wireId.isEmpty,
              let vsStr = rec["vs"] as? String,
              let stamp = VersionStamp.parse(vsStr),
              let rs = replayState else { return }
        let by = rec["by"] as? String ?? ""
        let recKind = rec["kind"] as? String ?? "unknown"
        guard rs.tombstone(wireId, stamp) else { return }
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vsStr, kind: "del")),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }
        guard let pub = inner["pub"] as? String, !pub.isEmpty,
              let sig = inner["sig"] as? String, !sig.isEmpty else { return }
        if !rs.registerActor(by, pubkey: pub) { return }
        let payloadHash = SyncIdentity.sha256(Data())
        guard let keys = v3Keys, let sd = rs.getSessionDomain(by).flatMap({ SyncIdentity.urlB64Decode($0) }) ?? sessionDomain else { return }
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainDelete, roomIdRaw: keys.roomIdRaw,
            actorId: by, sessionDomain: sd, counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: wireId, kind: recKind, payloadHash: payloadHash)
        guard SyncSigning.verify(pub, preimage, sig) else { return }

        if let localId = findLocalIdForWireId(wireId) {
            if let wp = waypointStore.waypoints.first(where: { $0.id.uuidString == localId }) {
                waypointStore.remove(wp)
            }
            if let shape = drawingStore.shapes.first(where: { $0.id.uuidString == localId }) {
                drawingStore.remove(shape)
            }
            lastContent[localId] = nil; kindById[localId] = nil
        }
        rs.save()
        remoteUpdateSubject.send("Object removed by another device")
    }

    private func applyPresenceV3(_ obj: [String: Any]) {
        guard let actorId = obj["actorId"] as? String, !actorId.isEmpty,
              actorId != myActorId else { return }
        guard let vsStr = obj["vs"] as? String,
              let stamp = VersionStamp.parse(vsStr),
              let rs = replayState else { return }
        guard rs.advancePresence(actorId, counter: stamp.counter) else { return }
        guard let key = roomKey,
              let ctB64 = obj["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadPresenceV3(actorId: actorId, vs: vsStr)),
              let p = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }

        guard let pub = p["pub"] as? String, !pub.isEmpty,
              let sig = p["sig"] as? String, !sig.isEmpty else { return }
        if !rs.registerActor(actorId, pubkey: pub) { return }

        // rebuild payload without sig for hash verification
        var payloadForHash: [String: Any] = p
        payloadForHash.removeValue(forKey: "sig")
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payloadForHash, options: .sortedKeys),
              let keys = v3Keys, let sd = rs.getSessionDomain(actorId).flatMap({ SyncIdentity.urlB64Decode($0) }) ?? sessionDomain else { return }
        let payloadHash = SyncIdentity.sha256(payloadData)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: actorId, kind: "presence", payloadHash: payloadHash)
        guard SyncSigning.verify(pub, preimage, sig) else { return }

        let callsign = p["callsign"] as? String ?? ""
        let affiliation = p["affiliation"] as? String ?? "unknown"
        let echelon = p["echelon"] as? String ?? "team"
        let function = p["function"] as? String ?? "infantry"
        let isHQ = p["isHQ"] as? Bool ?? false
        guard let lat = strictDouble(p["lat"]),
              let lon = strictDouble(p["lon"]) else { return }
        let heading = strictDouble(p["heading"]) ?? 0
        let speed = strictDouble(p["speed"]) ?? 0

        peers[actorId] = PresencePeer(
            clientId: actorId,
            callsign: callsign,
            affiliation: affiliation,
            echelon: echelon,
            function: function,
            isHQ: isHQ,
            lat: lat,
            lon: lon,
            heading: heading,
            speed: speed,
            ts: Date().timeIntervalSince1970 * 1000,
            receivedAt: Date()
        )
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
