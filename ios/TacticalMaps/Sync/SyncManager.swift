import Foundation
import Combine
import CryptoKit

/// Real-time shared-tactical-picture sync client (iOS). Mirrors the Android
/// `SyncManager`: connects to the E2E-blind relay for a join-code room and keeps
/// waypoints + drawings in step across the unit.
///
/// Each object is serialised as a single-feature GeoJSON document (the same
/// cross-platform schema TacMap already round-trips), encrypted with the room
/// key (`SyncCrypto`) and relayed as opaque ciphertext. Layers ride along in the
/// feature properties. Merge is last-write-wins on a per-object Lamport version;
/// echo is suppressed by tracking the last serialised form per id.
///
/// NOTE: compile-verified; convergence / no-echo should be confirmed on devices
/// against the live relay.
@MainActor
final class SyncManager: ObservableObject {
    enum Status { case offline, connecting, connected }

    @Published private(set) var status: Status = .offline
    @Published private(set) var room: String?

    static let relayBase = "wss://tacmap-sync.christianbrooker.workers.dev/room/"

    // Set once via configure() so this can be a @StateObject (created without
    // referencing the other @StateObject stores at declaration).
    private var waypointStore: WaypointStore!
    private var drawingStore: DrawingStore!

    private var task: URLSessionWebSocketTask?
    private var roomKey: SymmetricKey?
    private var wantConnected = false
    private var roomId: String?

    private let clientId: String
    private var clock = 0
    private var versions: [String: Int] = [:]
    private var lastContent: [String: String] = [:]
    private var kindById: [String: String] = [:]
    private var observers = Set<AnyCancellable>()

    init() {
        if let existing = UserDefaults.standard.string(forKey: "sync.clientId") {
            clientId = existing
        } else {
            let id = UUID().uuidString
            UserDefaults.standard.set(id, forKey: "sync.clientId")
            clientId = id
        }
    }

    /// Inject the shared stores once the view hierarchy exists. Safe to call
    /// repeatedly; only the first call binds.
    func configure(waypointStore: WaypointStore, drawingStore: DrawingStore) {
        guard self.waypointStore == nil else { return }
        self.waypointStore = waypointStore
        self.drawingStore = drawingStore
    }

    // MARK: API

    func join(_ joinCode: String) {
        let code = joinCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        leave()
        wantConnected = true
        roomKey = SyncCrypto.roomKey(code)
        roomId = SyncCrypto.roomId(code)
        room = code
        connect()
        observeStores()
    }

    func leave() {
        wantConnected = false
        observers.removeAll()
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
        roomKey = nil
        room = nil
        status = .offline
        versions.removeAll(); lastContent.removeAll(); kindById.removeAll()
    }

    // MARK: Connection

    private func connect() {
        guard let roomId, let url = URL(string: Self.relayBase + roomId) else { return }
        status = .connecting
        let t = URLSession.shared.webSocketTask(with: url)
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
              let sealed = SyncCrypto.seal(key, Data(content.utf8)) else { return }
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

    // MARK: Inbound

    private func handleMessage(_ text: String) {
        guard let data = text.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        switch obj["t"] as? String {
        case "snapshot":
            for item in (obj["items"] as? [[String: Any]] ?? []) { applyRecord(item) }
        case "put":
            applyRecord(obj)
        case "del":
            applyDelete(id: obj["id"] as? String ?? "", v: intValue(obj["v"]))
        default:
            break
        }
    }

    private func applyRecord(_ rec: [String: Any]) {
        guard let id = rec["id"] as? String, !id.isEmpty else { return }
        let v = intValue(rec["v"])
        if let known = versions[id], known >= v, lastContent[id] != nil { return } // stale
        clock = max(clock, v)
        if (rec["deleted"] as? Bool) == true { applyDelete(id: id, v: v); return }
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob) else { return }

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
    }

    private func upsert(waypoint wp: Waypoint) {
        if waypointStore.waypoints.contains(where: { $0.id == wp.id }) { waypointStore.update(wp) }
        else { waypointStore.add(wp) }
    }

    private func upsert(shape: DrawingShape) {
        if drawingStore.shapes.contains(where: { $0.id == shape.id }) { drawingStore.update(shape) }
        else { drawingStore.add(shape) }
    }

    /// Re-serialise the applied object so the next diff sees no spurious change.
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
}
