import XCTest
import CryptoKit
@testable import TacticalMaps

// Fakes for the SyncManager transport seam (S6-02). Everything runs on the
// main actor and on a manual clock, so a test can script a relay frame by
// frame and step time exactly.

struct FakeSocketError: Error {}

@MainActor
final class ManualSyncScheduler: SyncScheduler {
    private final class Entry: SyncCancellable {
        let id: Int
        let fireAtMs: Int64
        var work: (@MainActor () -> Void)?
        init(id: Int, fireAtMs: Int64, work: @escaping @MainActor () -> Void) {
            self.id = id
            self.fireAtMs = fireAtMs
            self.work = work
        }
        func cancel() { work = nil }
    }

    private(set) var nowMs: Int64 = 1_000_000
    /// unix ms at nowMs == 0, so wallMs lines up with the fixture's nowMs
    var wallOriginMs: Int64 = 1_790_812_800_000 - 1_000_000
    var wallMs: Int64 { wallOriginMs + nowMs }
    private var entries: [Entry] = []
    private var nextId = 0

    func schedule(afterMs: Int64, _ work: @escaping @MainActor () -> Void) -> SyncCancellable {
        nextId += 1
        let entry = Entry(id: nextId, fireAtMs: nowMs + max(0, afterMs), work: work)
        entries.append(entry)
        return entry
    }

    var pendingTimers: Int { entries.filter { $0.work != nil }.count }

    /// next live timer due time, if any
    var nextFireMs: Int64? {
        entries.filter { $0.work != nil }.map(\.fireAtMs).min()
    }

    /// Run everything due at the current time (zero delay hops included).
    func runDue() {
        advance(byMs: 0)
    }

    func advance(byMs delta: Int64) {
        let target = nowMs + max(0, delta)
        var guardCount = 0
        while true {
            entries.removeAll { $0.work == nil }
            guard let next = entries
                .filter({ $0.fireAtMs <= target })
                .min(by: { ($0.fireAtMs, $0.id) < ($1.fireAtMs, $1.id) }) else { break }
            entries.removeAll { $0 === next }
            nowMs = max(nowMs, next.fireAtMs)
            let work = next.work
            next.work = nil
            work?()
            guardCount += 1
            if guardCount > 200_000 { XCTFail("scheduler runaway"); break }
        }
        nowMs = target
    }

    func advance(to absoluteMs: Int64) {
        advance(byMs: absoluteMs - nowMs)
    }
}

@MainActor
final class FakeSyncSocket: SyncSocket {
    let request: URLRequest
    unowned let scheduler: ManualSyncScheduler
    var onOpen: (@MainActor () -> Void)?
    var closeCode: Int = 0
    var httpStatusCode: Int?

    var autoCompleteWrites = true
    var autoPong = true
    private(set) var resumed = false
    private(set) var cancelledWith: Int?
    /// every frame handed to send(), in order
    private(set) var sent: [String] = []
    private var pendingWrites: [(text: String, completion: @MainActor (Error?) -> Void)] = []
    private var receiveHandler: (@MainActor (Result<SyncSocketMessage, Error>) -> Void)?
    private var inbox: [Result<SyncSocketMessage, Error>] = []
    private(set) var pings = 0
    private var pendingPings: [@MainActor (Error?) -> Void] = []

    init(request: URLRequest, scheduler: ManualSyncScheduler) {
        self.request = request
        self.scheduler = scheduler
    }

    var isCancelled: Bool { cancelledWith != nil }
    var hasPendingReceive: Bool { receiveHandler != nil }

    func resume() { resumed = true }

    func send(text: String, completion: @escaping @MainActor (Error?) -> Void) {
        sent.append(text)
        if autoCompleteWrites {
            scheduler.schedule(afterMs: 0) { completion(nil) }
        } else {
            pendingWrites.append((text, completion))
        }
    }

    /// Finish the next n queued writes (slow uplink model).
    func completeWrites(_ count: Int = .max, error: Error? = nil) {
        var done = 0
        while done < count, !pendingWrites.isEmpty {
            let write = pendingWrites.removeFirst()
            write.completion(error)
            done += 1
        }
    }

    var unwrittenCount: Int { pendingWrites.count }

    func receive(completion: @escaping @MainActor (Result<SyncSocketMessage, Error>) -> Void) {
        if !inbox.isEmpty {
            let next = inbox.removeFirst()
            scheduler.schedule(afterMs: 0) { completion(next) }
            return
        }
        receiveHandler = completion
    }

    func sendPing(completion: @escaping @MainActor (Error?) -> Void) {
        pings += 1
        if autoPong {
            scheduler.schedule(afterMs: 0) { completion(nil) }
        } else {
            pendingPings.append(completion)
        }
    }

    func cancel(closeCode: Int, reason: Data?) {
        guard cancelledWith == nil else { return }
        cancelledWith = closeCode
        self.closeCode = closeCode
        // URLSession fails the outstanding receive after a cancel, async
        if let handler = receiveHandler {
            receiveHandler = nil
            scheduler.schedule(afterMs: 0) { handler(.failure(FakeSocketError())) }
        }
    }

    // MARK: relay side

    func open() { onOpen?() }

    func deliver(_ object: [String: Any]) {
        let data = try! JSONSerialization.data(withJSONObject: object)
        deliverText(String(data: data, encoding: .utf8)!)
    }

    func deliverText(_ text: String) {
        deliverResult(.success(.string(text)))
    }

    func deliverResult(_ result: Result<SyncSocketMessage, Error>) {
        if let handler = receiveHandler {
            receiveHandler = nil
            handler(result)
        } else {
            inbox.append(result)
        }
    }

    /// Relay closed the socket (or the link died with code 0).
    func remoteClose(code: Int) {
        closeCode = code
        deliverResult(.failure(FakeSocketError()))
    }

    /// Upgrade never succeeded.
    func upgradeFailed(httpStatus: Int?) {
        httpStatusCode = httpStatus
        closeCode = 0
        deliverResult(.failure(FakeSocketError()))
    }

    func answerPings(error: Error? = nil) {
        let pings = pendingPings
        pendingPings.removeAll()
        pings.forEach { $0(error) }
    }

    func sentObjects() -> [[String: Any]] {
        sent.compactMap { text in
            (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any]
        }
    }

    func sent(type: String) -> [[String: Any]] {
        sentObjects().filter { $0["t"] as? String == type }
    }
}

@MainActor
final class FakeSyncSocketFactory: SyncSocketFactory {
    unowned let scheduler: ManualSyncScheduler
    private(set) var sockets: [FakeSyncSocket] = []
    var configure: ((FakeSyncSocket) -> Void)?

    init(scheduler: ManualSyncScheduler) { self.scheduler = scheduler }

    func makeSocket(request: URLRequest) -> SyncSocket {
        let socket = FakeSyncSocket(request: request, scheduler: scheduler)
        configure?(socket)
        sockets.append(socket)
        return socket
    }
}

@MainActor
final class FakePathMonitor: SyncPathMonitoring {
    var onChange: (@MainActor (SyncPathStatus) -> Void)?
    private(set) var started = false
    func start() { started = true }
    func stop() { started = false }
    func emit(satisfied: Bool, interfaces: [String] = ["wifi"]) {
        onChange?(SyncPathStatus(satisfied: satisfied, interfaces: interfaces))
    }
}

/// Holds off-main work until the test says go, so a test can do things on
/// main while a snapshot is "still validating" or PBKDF2 is "still running".
@MainActor
final class ManualSyncOffMainExecutor: SyncOffMainExecutor {
    private var pending: [(work: () -> Void, completion: @MainActor () -> Void)] = []

    var pendingCount: Int { pending.count }

    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void) {
        pending.append((work, completion))
    }

    /// Run the oldest job, work then completion.
    func runNext() {
        guard !pending.isEmpty else { return }
        let job = pending.removeFirst()
        job.work()
        job.completion()
    }

    func runAll() {
        while !pending.isEmpty { runNext() }
    }

    /// Run the oldest job and time its two halves: the off-main work and the
    /// main-actor completion.
    @discardableResult
    func runNextTimed() -> (workMs: Int, completionMs: Int) {
        guard !pending.isEmpty else { return (0, 0) }
        let job = pending.removeFirst()
        let start = CFAbsoluteTimeGetCurrent()
        job.work()
        let mid = CFAbsoluteTimeGetCurrent()
        job.completion()
        let end = CFAbsoluteTimeGetCurrent()
        return (Int(((mid - start) * 1000).rounded()), Int(((end - mid) * 1000).rounded()))
    }
}

/// Counts every durable write the manager does, per store. Shared by
/// reference so the writer closures (built before the harness is) can bump it.
final class SyncWriteCounter {
    var replayWrites = 0
    var replayBytes = 0
    var journalWrites = 0
    var waypointWrites = 0
    var drawingWrites = 0

    func reset() {
        replayWrites = 0; replayBytes = 0; journalWrites = 0
        waypointWrites = 0; drawingWrites = 0
    }
}

/// A SyncManager wired to fakes, plus a second signing identity ("peer B")
/// that can build properly sealed and signed v3 records.
@MainActor
final class SyncManagerHarness {
    let writes = SyncWriteCounter()
    static let testKey = Data(repeating: 0x42, count: 32)

    /// PBKDF2 once per code per test run. Lock-guarded and nonisolated, the
    /// manager calls its deriver through the off-main executor.
    private final class KeyCache {
        static let shared = KeyCache()
        private let lock = NSLock()
        private var keys: [String: SyncCrypto.V3RoomKeys] = [:]

        func keys(_ rawCode: String) -> SyncCrypto.V3RoomKeys {
            lock.lock()
            if let cached = keys[rawCode] { lock.unlock(); return cached }
            lock.unlock()
            let derived = SyncCrypto.deriveRoomV3(rawCode)
            lock.lock(); keys[rawCode] = derived; lock.unlock()
            return derived
        }
    }

    nonisolated static func roomKeys(_ rawCode: String) -> SyncCrypto.V3RoomKeys {
        KeyCache.shared.keys(rawCode)
    }

    let joinCode: String
    let scheduler = ManualSyncScheduler()
    let factory: FakeSyncSocketFactory
    let path = FakePathMonitor()
    var randomValue = 0.5
    let directory: URL
    let suiteName: String
    private static var activeDefaultsSlots = Set<Int>()
    private let defaultsSlot: Int
    private var tornDown = false
    let defaults: UserDefaults
    let waypointStore: WaypointStore
    let drawingStore: DrawingStore
    let manager: SyncManager
    let keys: SyncCrypto.V3RoomKeys
    private let previousKeyProvider: () throws -> Data
    private var presenceSlot: Data?

    // peer B
    let peerSeed = Data(repeating: 0x0b, count: 32)
    let peerPub: String
    let peerActor: String
    let peerSessionRaw = Data(repeating: 0x5b, count: 32)
    var peerSession: String { SyncIdentity.urlB64Encode(peerSessionRaw) }

    init(joinCode: String = "3:sp2-harness-room-0001",
         offMainExecutor: SyncOffMainExecutor? = nil,
         persistenceExecutor: SyncOffMainExecutor? = nil,
         replayWriter: SyncReplayState.PersistenceWriter? = nil,
         realKeyDerivation: Bool = false,
         backgroundReconnectEnabled: Bool = false,
         locationService: LocationService? = nil) throws {
        self.joinCode = joinCode
        previousKeyProvider = SafeStore.keyProvider
        SafeStore.keyProvider = { SyncManagerHarness.testKey }
        SealedMigrationPolicy.resetForTests(key: Self.testKey)
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sync-harness-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let defaultsPoolSlot = (0..<64).first { !Self.activeDefaultsSlots.contains($0) }!
        Self.activeDefaultsSlots.insert(defaultsPoolSlot)
        defaultsSlot = defaultsPoolSlot
        // CFPreferences keeps domains cached even after removePersistentDomain.
        // Reuse a bounded set while keeping real durable preferences in tests.
        suiteName = "SyncManagerHarness.slot\(defaultsPoolSlot)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        _ = defaults.synchronize()
        let counter = writes
        waypointStore = WaypointStore(
            storageURL: directory.appendingPathComponent("waypoints.json"),
            persistenceWriter: { _, _, _ in counter.waypointWrites += 1 })
        drawingStore = DrawingStore(
            storageURL: directory.appendingPathComponent("drawings.json"),
            persistenceWriter: { _, _, _ in counter.drawingWrites += 1 })
        factory = FakeSyncSocketFactory(scheduler: scheduler)
        keys = Self.roomKeys(String(joinCode.dropFirst(2)))
        peerPub = SyncSigning.publicKey(peerSeed)!
        peerActor = SyncIdentity.actorId(roomIdRaw: keys.roomIdRaw, pubkeyRaw: SyncSigning.publicKeyRaw(peerSeed)!)
        var slot: Data?
        let presenceStore = DurableDefaultsDataStore(
            read: { slot },
            writeCandidate: { slot = $0; return true },
            restore: { slot = $0 })
        let schedulerRef = scheduler
        let factoryRef = factory
        let pathRef = path
        var randomBox: () -> Double = { 0.5 }
        manager = SyncManager(
            defaults: defaults,
            presenceStore: presenceStore,
            presenceSealedPolicy: PresenceConfigSealedPolicy(
                requiresSealed: { _, _ in false }, markSealed: { _, _ in }),
            socketFactory: factoryRef,
            scheduler: schedulerRef,
            pathMonitor: pathRef,
            randomUnit: { randomBox() },
            storageRoot: directory,
            relayURLProvider: { "ws://127.0.0.1:9" },
            roomKeyDeriver: realKeyDerivation ? nil : { SyncManagerHarness.roomKeys($0) },
            replayPersistenceWriter: { data, url, label in
                counter.replayWrites += 1
                counter.replayBytes += data.count
                if let replayWriter { try replayWriter(data, url, label) }
                else { try SafeStore.write(data, to: url, label: label) }
            },
            journalPersistenceWriter: { data, url, label in
                counter.journalWrites += 1
                try SafeStore.write(data, to: url, label: label)
            },
            // validation and PBKDF2 run inline so the manual clock stays the
            // only source of time, unless a test wants to hold them
            offMainExecutor: offMainExecutor ?? InlineSyncOffMainExecutor(),
            persistenceExecutor: persistenceExecutor ?? InlineSyncOffMainExecutor(),
            backgroundReconnectEnabled: backgroundReconnectEnabled
        )
        randomBox = { [weak self] in self?.randomValue ?? 0.5 }
        _ = slot
        manager.configure(waypointStore: waypointStore, drawingStore: drawingStore,
                          locationService: locationService)
    }

    func tearDown() {
        guard !tornDown else { return }
        tornDown = true
        manager.leave()
        SafeStore.keyProvider = previousKeyProvider
        defaults.removePersistentDomain(forName: suiteName)
        _ = defaults.synchronize()
        Self.activeDefaultsSlots.remove(defaultsSlot)
        try? FileManager.default.removeItem(at: directory)
    }

    var socket: FakeSyncSocket { factory.sockets.last! }
    var socketCount: Int { factory.sockets.count }

    func join() {
        manager.join(joinCode)
        scheduler.runDue()
    }

    func pump(_ ms: Int64 = 0) {
        scheduler.advance(byMs: ms)
    }

    /// Step the clock until the manager opens its next socket (a reconnect).
    /// Pumping a fixed minute would also run the new socket's own open
    /// watchdog and stack a second reconnect on top.
    func advanceUntilNewSocket(maxMs: Int64 = 600_000) {
        let start = socketCount
        let deadline = scheduler.nowMs + maxMs
        while socketCount == start, scheduler.nowMs < deadline {
            guard let next = scheduler.nextFireMs, next <= deadline else {
                scheduler.advance(to: deadline)
                break
            }
            scheduler.advance(to: max(next, scheduler.nowMs))
        }
    }

    // MARK: handshake scripting

    func beginSnapshot(seq: Int64 = 1, on target: FakeSyncSocket? = nil) {
        let sock = target ?? socket
        sock.open()
        sock.deliver(["t": "snapshot-begin", "seq": seq, "highWater": 0])
        pump()
    }

    func page(_ items: [[String: Any]], more: Bool = false) {
        socket.deliver(["t": "snapshot", "items": items, "more": more])
        pump()
    }

    func endSnapshot(seq: Int64 = 1) {
        socket.deliver(["t": "snapshot-end", "seq": seq])
        pump()
    }

    /// The hello the manager wrote on the current socket.
    var lastHello: [String: Any]? { socket.sent(type: "hello").last }

    func ackHello() {
        guard let hello = lastHello else { return XCTFail("no hello was sent") }
        socket.deliver(["t": "hello-ack", "by": hello["by"]!, "sd": hello["sd"]!, "vs": hello["vs"]!])
        pump()
    }

    /// Full happy handshake on the current socket.
    func connect(items: [[String: Any]] = [], seq: Int64 = 1) {
        beginSnapshot(seq: seq)
        page(items)
        endSnapshot(seq: seq)
        ackHello()
    }

    // MARK: peer B records

    func wireId(_ id: UUID) -> String {
        SyncIdentity.wireObjectId(metadataKey: keys.metadataKey, localUuidBytes: SyncIdentity.uuidToBytes(id))
    }

    func peerContent(waypoint: Waypoint, layers: [DrawingLayer] = []) -> String {
        try! GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layers)
    }

    func peerContent(drawing: DrawingShape, layers: [DrawingLayer]) -> String {
        try! GeoJSONExporter.export(waypoints: [], drawings: [drawing], layers: layers)
    }

    /// A sealed + signed v3 put as the relay stores it (snapshot item shape).
    func peerPut(
        wireId: String,
        content: String,
        counter: Int64,
        kind: String = "waypoint",
        ciphertextOverride: String? = nil,
        live: Bool = false
    ) -> [String: Any] {
        let vs = VersionStamp(counter: counter, actorId: peerActor).encode()
        let payloadHash = SyncIdentity.sha256(Data(content.utf8))
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPut, roomIdRaw: keys.roomIdRaw,
            actorId: peerActor, sessionDomain: peerSessionRaw,
            counterHex16: VersionStamp.counterHex16(counter),
            objectId: wireId, kind: kind, payloadHash: payloadHash)
        let sig = SyncSigning.sign(peerSeed, preimage)!
        let inner = try! JSONSerialization.data(withJSONObject: ["c": content, "sig": sig])
        let sealed = SyncCrypto.seal(keys.roomKey, inner, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vs, kind: kind))!
        var record: [String: Any] = [
            "id": wireId, "vs": vs, "by": peerActor, "kind": kind,
            "ct": ciphertextOverride ?? sealed.base64EncodedString(),
            "pub": peerPub, "sd": peerSession, "deleted": false
        ]
        if live { record["t"] = "put"; record["seq"] = 2 }
        return record
    }

    func peerWaypointPut(_ waypoint: Waypoint, counter: Int64, live: Bool = false) -> [String: Any] {
        peerPut(wireId: wireId(waypoint.id), content: peerContent(waypoint: waypoint), counter: counter, live: live)
    }

    func peerDel(wireId: String, counter: Int64, live: Bool = false) -> [String: Any] {
        let vs = VersionStamp(counter: counter, actorId: peerActor).encode()
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainDelete, roomIdRaw: keys.roomIdRaw,
            actorId: peerActor, sessionDomain: peerSessionRaw,
            counterHex16: VersionStamp.counterHex16(counter),
            objectId: wireId, kind: "del", payloadHash: SyncIdentity.sha256(Data()))
        let sig = SyncSigning.sign(peerSeed, preimage)!
        let inner = try! JSONSerialization.data(withJSONObject: ["sig": sig])
        let sealed = SyncCrypto.seal(keys.roomKey, inner, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vs, kind: "del"))!
        var record: [String: Any] = [
            "id": wireId, "vs": vs, "by": peerActor, "kind": "del",
            "ct": sealed.base64EncodedString(), "pub": peerPub, "sd": peerSession, "deleted": true
        ]
        if live { record["t"] = "del"; record["seq"] = 2 }
        return record
    }

    /// The signed hello peer B would send so the manager tracks its session.
    func peerHello(epoch: UInt64 = 7) -> [String: Any] {
        let epochHex = String(format: "%016llx", epoch)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainHello, roomIdRaw: keys.roomIdRaw,
            actorId: peerActor, sessionDomain: peerSessionRaw,
            counterHex16: epochHex, objectId: "", kind: "hello",
            payloadHash: SyncIdentity.sha256(SyncSigning.publicKeyRaw(peerSeed)!))
        return [
            "t": "hello", "by": peerActor, "pub": peerPub, "sd": peerSession,
            "vs": "\(epochHex):\(peerActor)", "sig": SyncSigning.sign(peerSeed, preimage)!
        ]
    }

    /// A fully signed and sealed v3 loc from peer B, the same envelope the
    /// manager itself sends. retentionSeconds adds the signed retention
    /// advert (a background peer sends > 45).
    func peerLoc(
        counter: Int64,
        lat: Double = -33.8601,
        lon: Double = 151.2101,
        callsign: String = "Bravo 2",
        retentionSeconds: Int? = nil
    ) -> [String: Any] {
        let vs = VersionStamp(counter: counter, actorId: peerActor).encode()
        let counterHex = VersionStamp.counterHex16(counter)
        let presence = SyncManager.PresencePayload(
            lat: lat, lon: lon, heading: 0, speed: 0, callsign: callsign,
            affiliation: "friend", echelon: "team", function: "infantry", isHQ: false)
        let payload = SyncManager.buildPresencePayloadBytes(presence)!
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: peerActor, sessionDomain: peerSessionRaw, counterHex16: counterHex,
            objectId: "", kind: "loc", payloadHash: SyncIdentity.sha256(payload))
        var envelope = SyncManager.makePresenceEnvelope(
            payload: presence, signedPayload: payload, publicKey: peerPub,
            signature: SyncSigning.sign(peerSeed, preimage)!)
        if let retentionSeconds {
            let retention = PresenceRetentionAdvertisement.encodePayload(seconds: retentionSeconds)!
            let retentionPreimage = SyncIdentity.buildPreimage(
                domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
                actorId: peerActor, sessionDomain: peerSessionRaw, counterHex16: counterHex,
                objectId: "", kind: PresenceRetentionAdvertisement.signatureKind,
                payloadHash: SyncIdentity.sha256(retention))
            envelope[PresenceRetentionAdvertisement.versionField] = PresenceRetentionAdvertisement.envelopeVersion
            envelope[PresenceRetentionAdvertisement.payloadField] = retention.base64EncodedString()
            envelope[PresenceRetentionAdvertisement.signatureField] = SyncSigning.sign(peerSeed, retentionPreimage)!
        }
        let plain = try! JSONSerialization.data(withJSONObject: envelope)
        let sealed = SyncCrypto.seal(keys.roomKey, plain, aad: SyncCrypto.aadPresenceV3(actorId: peerActor, vs: vs))!
        return ["t": "loc", "by": peerActor, "ct": sealed.base64EncodedString(),
                "pub": peerPub, "sd": peerSession, "vs": vs]
    }

    /// Peer B's chat-key advert so B becomes a selectable chat recipient.
    func peerChatKey() throws -> [String: Any] {
        let session = try TacMapChatCrypto.makeLocalSession(
            roomIdRaw: keys.roomIdRaw, actorId: peerActor,
            sessionDomain: peerSessionRaw, signingSeed: peerSeed)
        return session.advertisement
    }

    // MARK: our own frames

    func sentMutations(on target: FakeSyncSocket? = nil) -> [[String: Any]] {
        (target ?? socket).sentObjects().filter {
            let t = $0["t"] as? String
            return t == "put" || t == "del"
        }
    }

    func ack(_ frame: [String: Any], on target: FakeSyncSocket? = nil) {
        let ct = frame["ct"] as! String
        let cth = SyncIdentity.urlB64Encode(SyncIdentity.sha256(Data(ct.utf8)))
        (target ?? socket).deliver([
            "t": "op-ack", "av": 1, "rid": frame["rid"]!, "by": frame["by"]!, "sd": frame["sd"]!,
            "id": frame["id"]!, "vs": frame["vs"]!, "kind": frame["kind"]!, "cth": cth
        ])
        pump()
    }

    func nack(_ frame: [String: Any], code: String, retry: Bool, on target: FakeSyncSocket? = nil) {
        (target ?? socket).deliver([
            "t": "op-nack", "av": 1, "rid": frame["rid"]!, "by": frame["by"]!, "sd": frame["sd"]!,
            "code": code, "retry": retry
        ])
        pump()
    }

    func addLocalWaypoint(_ name: String = "Local") throws -> Waypoint {
        let waypoint = Waypoint(name: name, latitude: -33.86, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        _ = try waypointStore.addDurably(waypoint)
        return waypoint
    }
}
