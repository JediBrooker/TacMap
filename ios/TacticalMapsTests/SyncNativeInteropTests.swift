import XCTest
import CoreLocation
import Darwin
@testable import TacticalMaps

@MainActor
final class SyncNativeInteropTests: XCTestCase {
    func testNativeAndroidRoundTripWhenLocalRelayIsProvided() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let relay = env["TACMAP_LIVE_RELAY"], let code = env["TACMAP_LIVE_JOIN_CODE"],
              let run = env["TACMAP_CHAT_INTEROP_RUN_ID"] else {
            throw XCTSkip("Local relay and interop peer were not configured")
        }
        let lifecycle = env["TACMAP_SYNC_LIFECYCLE_PHASE"]
        guard run.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }) else { throw XCTSkip("Use an alphanumeric test run ID") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("sync-native-\(lifecycle == nil ? UUID().uuidString : run)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let defaultsName = "SyncNativeInterop.\(lifecycle == nil ? UUID().uuidString : run)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        let previousKeyProvider = SafeStore.keyProvider
        let key = Data(repeating: 0x6c, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        let waypoints = WaypointStore(storageURL: directory.appendingPathComponent("waypoints.json"))
        let drawings = DrawingStore(storageURL: directory.appendingPathComponent("drawings.json"))
        let location = LocationService()
        let observer = NativeInteropSocketObserver(code: code)
        let manager = SyncManager(defaults: defaults, socketFactory: observer, storageRoot: directory, relayURLProvider: { relay })
        manager.configure(waypointStore: waypoints, drawingStore: drawings, locationService: location)
        let full = env["TACMAP_SYNC_FULL_INTEROP"] == "true"
        if full {
            XCTAssertTrue(LiveLocationPermissionPolicy.shouldStartUpdates(for: location.authorisationStatus),
                "Grant the isolated test simulator app location permission before full native presence interop")
        }
        var config = manager.presenceConfig
        config.callsign = "IOS_LIVE_CHAT"
        config.shareLocation = full
        XCTAssertTrue(manager.updatePresenceConfig(config))
        func freshFix() {
            location.lastLocation = CLLocation(coordinate: CLLocationCoordinate2D(latitude: -35.0, longitude: 149.0),
                altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5, course: 0, speed: 0, timestamp: Date())
            manager.locationDidUpdate()
        }
        func waitUntil(_ description: String, _ predicate: () -> Bool) async throws {
            let deadline = Date().addingTimeInterval(150)
            var nextDiagnostic = Date()
            while Date() < deadline {
                if Date() >= nextDiagnostic {
                    print("INTEROP_IOS_WAIT \(description) waypoints=\(waypoints.waypoints.map(\.name)) drawings=\(drawings.shapes.compactMap(\.name)) peers=\(manager.peers.keys.sorted()) members=\(manager.onlineMembers.keys.sorted()) status=\(manager.status) issue=\(manager.lastError ?? "none")")
                    let reflected = Mirror(reflecting: manager)
                    let peerKeyCount = reflected.children.first { $0.label == "chatPeerKeys" }.map { Mirror(reflecting: $0.value).children.count } ?? -1
                    print("INTEROP_IOS_MANAGER_CHAT peerKeys=\(peerKeyCount) recipients=\(manager.chatRecipients.count)")
                    print("INTEROP_IOS_CHAT issue=\(manager.chatSessionIssue ?? "none") messages=\(manager.chatStore.messages.map { "\($0.id)|\($0.body)|out=\($0.isOutgoing)|state=\($0.deliveryState)|failure=\($0.failureCode ?? "none")" })")
                    fflush(stdout)
                    nextDiagnostic = Date().addingTimeInterval(5)
                }
                if predicate() { return }
                if full { freshFix() }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            throw NSError(domain: "SyncNativeInterop", code: 1,
                userInfo: [NSLocalizedDescriptionKey: "Timed out: \(description), status=\(manager.status), issue=\(manager.lastError ?? "none")"])
        }
        func cleanup() async {
            manager.leave()
            for phase in 0..<2 {
                let drained = expectation(description: "interop persistence drained \(phase)")
                SyncPersistenceExecutor().execute({}) { drained.fulfill() }
                await fulfillment(of: [drained], timeout: 10)
            }
            SafeStore.keyProvider = previousKeyProvider
            SealedMigrationPolicy.resetForTests(key: key)
            defaults.removePersistentDomain(forName: defaultsName)
            try? FileManager.default.removeItem(at: directory)
        }
        if lifecycle == "resume" {
            XCTAssertEqual(waypoints.waypoints.count, 2)
            XCTAssertEqual(drawings.shapes.count, 2)
            XCTAssertEqual(Set(waypoints.waypoints.map(\.name)), Set(["LIFE_IOS_WP:\(run)", "LIFE_ANDROID_WP:\(run)"]))
            XCTAssertEqual(Set(drawings.shapes.compactMap(\.name)), Set(["LIFE_IOS_DRAW:\(run)", "LIFE_ANDROID_DRAW:\(run)"]))
            print("LIFECYCLE_IOS_PREJOIN_RECOVERED records=2 drawings=2 \(run)")
        }
        do {
            if full { freshFix() }
            manager.join(code)
            try await waitUntil("authenticated Android peer") {
                manager.status == .connected && manager.chatSessionReady && manager.chatRecipients.count == 1
            }
            let target = try XCTUnwrap(manager.chatRecipients.values.first)
            if full && lifecycle != "resume" {
                var waypoint = Waypoint(name: "T2_IOS_WP:\(run)", latitude: -35.0, longitude: 149.0,
                    layerID: DrawingLayer.legacyFallbackID)
                var drawing = DrawingShape(name: "T2_IOS_DRAW:\(run)", kind: .polyline, coordinates: [
                    Coordinate2D(latitude: -35.0, longitude: 149.0), Coordinate2D(latitude: -35.1, longitude: 149.1)
                ], layerID: DrawingLayer.legacyFallbackID)
                XCTAssertTrue(try waypoints.addDurably(waypoint))
                XCTAssertTrue(try drawings.addDurably(drawing))
                try await waitUntil("Android creations and signed presence") {
                    waypoints.waypoints.contains { $0.name == "T2_ANDROID_WP:\(run)" }
                    && drawings.shapes.contains { $0.name == "T2_ANDROID_DRAW:\(run)" }
                    && manager.peers[target.actorId] != nil
                }
                let peerWaypoint = try XCTUnwrap(waypoints.waypoints.first { $0.name == "T2_ANDROID_WP:\(run)" })
                let peerDrawing = try XCTUnwrap(drawings.shapes.first { $0.name == "T2_ANDROID_DRAW:\(run)" })
                func barrier(_ phase: String) async throws {
                    _ = try manager.sendChat(body: "T2_IOS_\(phase):\(run)", kind: .text, scope: .room, recipient: nil)
                    try await waitUntil("Android \(phase) barrier") {
                        manager.chatStore.messages.contains {
                            !$0.isOutgoing && $0.senderActorId == target.actorId && $0.body == "T2_ANDROID_\(phase):\(run)"
                        }
                    }
                }
                try await barrier("CREATED_SEEN")
                waypoint.name += ":edited"
                drawing.name = (drawing.name ?? "") + ":edited"
                XCTAssertTrue(try waypoints.commitEdit(waypoint))
                XCTAssertTrue(try drawings.commitEdit(drawing))
                try await waitUntil("Android edits") {
                    waypoints.waypoints.contains { $0.id == peerWaypoint.id && $0.name == "T2_ANDROID_WP:\(run):edited" }
                    && drawings.shapes.contains { $0.id == peerDrawing.id && $0.name == "T2_ANDROID_DRAW:\(run):edited" }
                }
                try await barrier("EDITED_SEEN")
                XCTAssertTrue(try waypoints.deleteDurably(waypoint))
                XCTAssertTrue(try drawings.deleteDurably(drawing))
                try await waitUntil("Android deletes") {
                    !waypoints.waypoints.contains { $0.id == peerWaypoint.id }
                    && !drawings.shapes.contains { $0.id == peerDrawing.id }
                }
                try await barrier("DELETED_SEEN")
                XCTAssertTrue(waypoints.waypoints.isEmpty)
                XCTAssertTrue(drawings.shapes.isEmpty)
                print("FULL_SYNC_INTEROP_IOS_OK presence waypointCRUD drawingCRUD \(run)")
            }
            if let lifecycle {
                func barrier(_ phase: String) async throws {
                    _ = try manager.sendChat(body: "LIFE_IOS_\(phase):\(run)", kind: .text, scope: .room, recipient: nil)
                    try await waitUntil("Android lifecycle \(phase)") {
                        manager.chatStore.messages.contains { !$0.isOutgoing && $0.senderActorId == target.actorId && $0.body == "LIFE_ANDROID_\(phase):\(run)" }
                    }
                }
                if lifecycle == "seed" {
                    XCTAssertTrue(try waypoints.addDurably(Waypoint(name: "LIFE_IOS_WP:\(run)", latitude: -35, longitude: 149)))
                    XCTAssertTrue(try drawings.addDurably(DrawingShape(name: "LIFE_IOS_DRAW:\(run)", kind: .polyline,
                        coordinates: [Coordinate2D(latitude: -35, longitude: 149), Coordinate2D(latitude: -35.1, longitude: 149.1)])))
                    try await waitUntil("Android durable lifecycle seed") {
                        waypoints.waypoints.contains { $0.name == "LIFE_ANDROID_WP:\(run)" }
                            && drawings.shapes.contains { $0.name == "LIFE_ANDROID_DRAW:\(run)" }
                    }
                    try await barrier("SEEDED")
                    XCTAssertEqual(waypoints.waypoints.count, 2)
                    XCTAssertEqual(drawings.shapes.count, 2)
                    print("LIFECYCLE_IOS_KILL_READY records=2 drawings=2 \(run)")
                    fflush(stdout)
                    // The runner is deliberately killed before teardown to prove durable recovery.
                    try await Task.sleep(nanoseconds: 180_000_000_000)
                    XCTFail("Host did not kill the iOS test process")
                } else {
                    XCTAssertEqual(waypoints.waypoints.count, 2)
                    XCTAssertEqual(drawings.shapes.count, 2)
                    print("LIFECYCLE_IOS_RELAUNCH_LOADED records=2 drawings=2 \(run)")
                    var own = try XCTUnwrap(waypoints.waypoints.first { $0.name == "LIFE_IOS_WP:\(run)" })
                    own.name += ":resumed"
                    XCTAssertTrue(try waypoints.commitEdit(own))
                    try await waitUntil("Android post-kill edit") {
                        waypoints.waypoints.contains { $0.name == "LIFE_ANDROID_WP:\(run):resumed" }
                    }
                    try await barrier("RESUMED")
                    let previousSession = try XCTUnwrap(manager.chatRecipients[target.actorId]?.sessionDomain)
                    print("LIFECYCLE_IOS_RELAY_RESTART_READY \(run)")
                    fflush(stdout)
                    try await waitUntil("new Android session after actual relay restart") {
                        manager.status == .connected && manager.chatSessionReady
                            && manager.chatRecipients[target.actorId]?.sessionDomain != nil
                            && manager.chatRecipients[target.actorId]?.sessionDomain != previousSession
                    }
                    try await barrier("RELAY_RESTARTED")
                    print("LIFECYCLE_IOS_RELAY_RESTART_OK \(run)")
                    try await waitUntil("Android background signed retention") {
                        (manager.peers[target.actorId]?.retentionWindow ?? 0) > 45
                    }
                    let backgroundTarget = try XCTUnwrap(manager.chatRecipients[target.actorId])
                    XCTAssertThrowsError(try manager.sendChat(body: "BLOCKED_BACKGROUND:\(run)", kind: .text,
                        scope: .direct, recipient: backgroundTarget)) { error in
                        guard case SyncManager.ChatSendError.recipientInBackground = error else {
                            XCTFail("Expected recipientInBackground, got \(error)")
                            return
                        }
                    }
                    let challengeID = try manager.sendChat(body: "LIFE_IOS_BACKGROUND_CHAT:\(run)", kind: .text,
                        scope: .room, recipient: nil)
                    own.name += ":background"
                    XCTAssertTrue(try waypoints.commitEdit(own))
                    try await waitUntil("background room challenge routed") {
                        manager.chatStore.messages.contains { $0.id == challengeID && $0.deliveryState == .routed }
                    }
                    print("LIFECYCLE_IOS_ANDROID_BACKGROUND_OK directBlocked roomChallenge modelChallenge \(run)")
                    try await waitUntil("Android foreground fresh chat session") {
                        manager.chatRecipients[target.actorId]?.sessionDomain != nil
                            && manager.chatRecipients[target.actorId]?.sessionDomain != backgroundTarget.sessionDomain
                            && (manager.peers[target.actorId]?.retentionWindow ?? 0) == 45
                    }
                    try await barrier("FOREGROUND")
                    print("LIFECYCLE_IOS_ANDROID_FOREGROUND_OK \(run)")
                }
            }
            let roomID = try manager.sendChat(body: "IOS_TO_ANDROID_ROOM::\(run)", kind: .report, scope: .room, recipient: nil)
            let directID = try manager.sendChat(body: "IOS_TO_ANDROID_DIRECT::\(run)", kind: .text, scope: .direct, recipient: manager.chatRecipients[target.actorId])
            try await waitUntil("routed iOS chat and authenticated Android replies") {
                let messages = manager.chatStore.messages
                return messages.contains { $0.id == roomID && $0.isOutgoing && $0.deliveryState == .routed }
                    && messages.contains { $0.id == directID && $0.isOutgoing && $0.deliveryState == .routed }
                    && messages.contains { !$0.isOutgoing && $0.body == "ANDROID_TO_IOS_ROOM::\(run)" && $0.scope == .room && $0.kind == .report }
                    && messages.contains { !$0.isOutgoing && $0.body == "ANDROID_TO_IOS_DIRECT::\(run)" && $0.scope == .direct && $0.kind == .text }
            }
            XCTAssertNotEqual(manager.lastIssueKind, .security)
            print("CHAT_INTEROP_IOS_OK room direct \(run)")
            // Keep both native processes alive until each has observed its acks.
            try await Task.sleep(nanoseconds: 2_000_000_000)
            await cleanup()
        } catch {
            await cleanup()
            throw error
        }
    }
}


/// Observes the real URLSession transport without changing frame contents or delivery.
@MainActor
private final class NativeInteropSocketObserver: SyncSocketFactory {
    let factory = URLSessionSyncSocketFactory()
    let keys: SyncCrypto.V3RoomKeys
    let local: TacMapChatCrypto.LocalSession
    var hellos: [String: [String: Any]] = [:]
    var peerKeys: [String: [String: Any]] = [:]
    init(code: String) {
        keys = SyncCrypto.deriveRoomV3(String(code.dropFirst(2)))
        local = try! TacMapChatCrypto.makeLocalSession(roomIdRaw: keys.roomIdRaw,
            actorId: Data(repeating: 0x11, count: 32).base64URLEncodedStringNoPad(),
            sessionDomain: Data(repeating: 0x12, count: 32), signingSeed: Data(repeating: 0x13, count: 32))
    }
    func makeSocket(request: URLRequest) -> SyncSocket {
        NativeInteropObservedSocket(base: factory.makeSocket(request: request), observer: self)
    }
    func observe(_ message: SyncSocketMessage) {
        let data: Data
        switch message { case .string(let text): data = Data(text.utf8); case .data(let value): data = value }
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = obj["t"] as? String else { return }
        let actor = obj["by"] as? String ?? ""
        if type == "hello" { hellos[actor] = obj }
        if type == "chat-key" { peerKeys[actor] = obj; print("INTEROP_IOS_RAW_KEY fields=\(obj.keys.sorted()) actor=\(actor) sd=\(obj["sd"] ?? "") kid=\(obj["kid"] ?? "")") }
        guard type == "chat" else { return }
        print("INTEROP_IOS_RAW_CHAT by=\(actor) sd=\(obj["sd"] ?? "") vs=\(obj["vs"] ?? "") fromKid=\(obj["fromKid"] ?? "") mid=\(obj["mid"] ?? "") hello=\(hellos[actor] != nil) peerKey=\(peerKeys[actor] != nil)")
        // The diagnostic local session is only valid for room frames; actual manager opens direct frames.
        guard obj["scope"] as? String == "room" else { return }
        if let hello = hellos[actor], let publicKey = hello["pub"] as? String,
           let peerObject = peerKeys[actor],
           let sd = peerObject["sd"] as? String, let kx = peerObject["kx"] as? String,
           let kid = peerObject["kid"] as? String, let sig = peerObject["sig"] as? String {
            let verified = TacMapChatCrypto.decodeAndVerifyPeerKey(peerObject, roomIdRaw: keys.roomIdRaw, signingPublicKey: publicKey) != nil
            print("INTEROP_IOS_RAW_CHAT_KEY verified=\(verified) sdMatches=\(sd == obj["sd"] as? String) kidMatches=\(kid == obj["fromKid"] as? String)")
            let peer = TacMapChatCrypto.PeerKey(actorId: actor, sessionDomain: sd, keyExchange: kx, keyId: kid, signature: sig)
            do {
                _ = try TacMapChatCrypto.open(obj, roomIdRaw: keys.roomIdRaw, roomKey: keys.roomKey,
                    localSession: local, senderKey: peer, senderSigningPublicKey: publicKey)
                print("INTEROP_IOS_RAW_CHAT_OPEN success=true")
            } catch { print("INTEROP_IOS_RAW_CHAT_OPEN success=false error=\(error)") }
        } else { print("INTEROP_IOS_RAW_CHAT_OPEN endpointVerified=false") }
        fflush(stdout)
    }
}

@MainActor
private final class NativeInteropObservedSocket: SyncSocket {
    let base: SyncSocket
    let observer: NativeInteropSocketObserver
    init(base: SyncSocket, observer: NativeInteropSocketObserver) { self.base = base; self.observer = observer }
    var onOpen: (@MainActor () -> Void)? { get { base.onOpen } set { base.onOpen = newValue } }
    var closeCode: Int { base.closeCode }
    var httpStatusCode: Int? { base.httpStatusCode }
    func resume() { base.resume() }
    func send(text: String, completion: @escaping @MainActor (Error?) -> Void) { base.send(text: text, completion: completion) }
    func receive(completion: @escaping @MainActor (Result<SyncSocketMessage, Error>) -> Void) {
        base.receive { [observer] result in
            if case .success(let message) = result { observer.observe(message) }
            completion(result)
        }
    }
    func sendPing(completion: @escaping @MainActor (Error?) -> Void) { base.sendPing(completion: completion) }
    func cancel(closeCode: Int, reason: Data?) { base.cancel(closeCode: closeCode, reason: reason) }
}
