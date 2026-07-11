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

    static let relayBase = "wss://tacmap-sync.christianbrooker.workers.dev/room/"

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

    // Per-device Ed25519 signing identity. Seed sealed at rest; the public key
    // rides every presence so peers pin it (TOFU) and reject a room member
    // impersonating an established peer. Room state cleared on leave.
    private lazy var deviceSeed: Data = loadOrCreateDeviceSeed()
    private lazy var myPublicKey: String = SyncSigning.publicKey(deviceSeed) ?? ""
    private var peerKeys: [String: String] = [:]   // clientId -> pinned pubkey
    private var peerTs: [String: Int64] = [:]        // clientId -> last accepted ts
    private static let deviceSeedKey = "sync.deviceSeed"
    private static let deviceSeedLabel = "sync/deviceSeed"

    private let clientId: String
    private var clock = 0
    private var versions: [String: Int] = [:]
    private var lastContent: [String: String] = [:]
    private var kindById: [String: String] = [:]
    private var observers = Set<AnyCancellable>()

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
        // Derive routing id + room key + writer-auth token once from the code.
        let keys = SyncCrypto.deriveRoom(code)
        roomKey = keys.roomKey
        roomId = keys.roomId
        authToken = keys.authToken
        // Self-hosters repoint the relay in OPSEC settings; blank -> ours.
        let configured = OpsecSettings.shared.relayURL.trimmingCharacters(in: .whitespacesAndNewlines)
        relayEndpoint = configured.isEmpty ? Self.relayBase : configured
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
        roomKey = nil
        authToken = nil
        room = nil
        status = .offline
        versions.removeAll(); lastContent.removeAll(); kindById.removeAll()
        peers.removeAll()
        peerKeys.removeAll(); peerTs.removeAll()  // re-pin fresh in the next room
    }

    // MARK: Connection

    private func connect() {
        guard let roomId, let url = URL(string: relayEndpoint + roomId) else { return }
        status = .connecting
        var req = URLRequest(url: url)
        // Writer-auth: the relay 401s a socket with no bearer token. The token is
        // derived from the join code and only rides the handshake header (never
        // the URL or logs), so a leaked roomId alone can't write.
        if let authToken { req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization") }
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
            self?.syncLocalState(waypoints: wps, shapes: shapes, layers: layers)
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

    private func sendPut(id: String, v: Int, kind: String, content: String) {
        guard let key = roomKey,
              let sealed = SyncCrypto.seal(key, Data(content.utf8), aad: SyncCrypto.aad(id: id, v: v, kind: kind)) else { return }
        send(["t": "put", "id": id, "v": v, "by": clientId, "kind": kind,
              "ct": sealed.base64EncodedString()])
    }

    private func sendDel(id: String, v: Int) {
        send(["t": "del", "id": id, "v": v, "by": clientId])
    }

    private func send(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8) else { return }
        task?.send(.string(text)) { _ in }
    }

    // MARK: - Presence broadcasting

    /// Fire-and-forget the device's GPS position + presenceConfig as a
    /// `loc` message. Runs on the 5s presence timer while connected and
    /// location sharing is on.
    func sendPresence() {
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
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        switch obj["t"] as? String {
        case "snapshot":
            for item in (obj["items"] as? [[String: Any]] ?? []) { applyRecord(item) }
            // Populate peers from the snapshot's members array.
            if let members = obj["members"] as? [[String: Any]] {
                for member in members {
                    applyPresence(member)
                }
            }
        case "put":
            applyRecord(obj)
        case "del":
            applyDelete(id: obj["id"] as? String ?? "", v: intValue(obj["v"]))
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
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: Data("loc|\(cid)".utf8)),
              let p = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }

        let callsign = p["callsign"] as? String ?? ""
        let affiliation = p["affiliation"] as? String ?? "unknown"
        let echelon = p["echelon"] as? String ?? "team"
        let function = p["function"] as? String ?? "infantry"
        let isHQ = p["isHQ"] as? Bool ?? false
        let lat = doubleValue(p["lat"]); let lon = doubleValue(p["lon"])
        let heading = doubleValue(p["heading"]); let speed = doubleValue(p["speed"])
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
        guard let id = rec["id"] as? String, !id.isEmpty else { return }
        let v = intValue(rec["v"])
        if let known = versions[id], known >= v, lastContent[id] != nil { return } // stale
        clock = max(clock, v)
        if (rec["deleted"] as? Bool) == true { applyDelete(id: id, v: v); return }
        let recKind = rec["kind"] as? String ?? "unknown"
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: id, v: v, kind: recKind)) else { return }

        let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
        guard let parsed = try? GeoJSONImporter.parse(plain, existingLayers: drawingStore.layers,
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

    private func applyDelete(id: String, v: Int) {
        guard !id.isEmpty else { return }
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

    private func intValue(_ any: Any?) -> Int {
        if let i = any as? Int { return i }
        if let d = any as? Double { return Int(d) }
        if let n = any as? NSNumber { return n.intValue }
        return 0
    }

    private func doubleValue(_ any: Any?) -> Double {
        if let d = any as? Double { return d }
        if let i = any as? Int { return Double(i) }
        if let n = any as? NSNumber { return n.doubleValue }
        return 0
    }
}
