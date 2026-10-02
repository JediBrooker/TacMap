import XCTest
import Combine
@testable import TacticalMaps

private final class HeldReplayWrite {
    private let lock = NSLock()
    private let releaseGate = DispatchSemaphore(value: 0)
    private var armed = false
    private var failing = false
    private(set) var started: XCTestExpectation?
    private(set) var ranOnMain = false

    func arm(failing: Bool = false) -> XCTestExpectation {
        lock.lock()
        armed = true
        self.failing = failing
        let expectation = XCTestExpectation(description: "persistence worker entered")
        started = expectation
        lock.unlock()
        return expectation
    }

    func write(_ data: Data, _ url: URL, _ label: String) throws {
        lock.lock()
        let hold = armed, fail = failing, expectation = started
        if hold { armed = false; ranOnMain = Thread.isMainThread }
        lock.unlock()
        if hold {
            expectation?.fulfill()
            _ = releaseGate.wait(timeout: .now() + 10)
            if fail { throw CocoaError(.fileWriteUnknown) }
        }
        try SafeStore.write(data, to: url, label: label)
    }

    func release() { releaseGate.signal() }
}

/// Hold only configure's journal load; its actual SafeStore read/migration
/// still executes on the production serial worker when released.
@MainActor
private final class HeldStartupLoadExecutor: SyncOffMainExecutor {
    private var first: (() -> Void, @MainActor () -> Void)?
    private var captured = false
    private var holdNext = false
    private var extra: (() -> Void, @MainActor () -> Void)?
    var hasHeldReplayLoad: Bool { extra != nil }
    private let worker = SyncPersistenceExecutor()
    private(set) var loadRanOnMain = true

    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void) {
        if !captured { captured = true; first = (work, completion) }
        else if holdNext { holdNext = false; extra = (work, completion) }
        else { worker.execute(work, then: completion) }
    }

    func holdNextReplayLoad() { holdNext = true }
    func releaseReplayLoad() {
        guard let job = extra else { return }
        extra = nil
        worker.execute(job.0, then: job.1)
    }

    func release() {
        guard let job = first else { return }
        first = nil
        let result = StartupThreadResult()
        worker.execute({ result.ranOnMain = Thread.isMainThread; job.0() }) {
            self.loadRanOnMain = result.ranOnMain
            job.1()
        }
    }

    private final class StartupThreadResult { var ranOnMain = true }
}

@MainActor
final class SyncAsyncPersistenceTests: XCTestCase {
    private var harness: SyncManagerHarness!
    private let writer = HeldReplayWrite()

    override func setUp() async throws {
        harness = try SyncManagerHarness(persistenceExecutor: SyncPersistenceExecutor(), replayWriter: writer.write)
    }

    override func tearDown() async throws {
        writer.release()
        harness?.manager.leave()
        // Drain the serial queue before the fixture removes its files and key.
        // The first barrier also lets a held flush's main completion enqueue
        // leave's clean point. The second waits for that clean point's seal.
        for phase in 0..<2 {
            let drained = expectation(description: "drained \(phase)")
            SyncPersistenceExecutor().execute({}) { drained.fulfill() }
            await fulfillment(of: [drained], timeout: 5)
        }
        harness?.tearDown()
        harness = nil
    }

    private func waitUntil(_ predicate: () -> Bool) async throws {
        for _ in 0..<250 {
            harness.pump()
            if predicate() { return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
        XCTFail("protocol did not reach the expected durable boundary")
    }

    private func connect() async throws {
        harness.join()
        try await waitUntil { self.harness.socketCount == 1 }
        harness.beginSnapshot()
        harness.page([])
        harness.endSnapshot()
        try await waitUntil { self.harness.lastHello != nil }
        harness.ackHello()
        try await waitUntil { self.harness.manager.status == .connected }
    }

    func testLegacyActorPinRepairDuringJoinSealsOffMainWithoutPublishingASocketEarly() async throws {
        try await connect()
        let actor = try XCTUnwrap(harness.lastHello?["by"] as? String)
        harness.manager.leave()
        for phase in 0..<2 {
            let drained = expectation(description: "clean replay \(phase)")
            SyncPersistenceExecutor().execute({}) { drained.fulfill() }
            await fulfillment(of: [drained], timeout: 5)
        }
        let url = harness.directory.appendingPathComponent("sync_replay", isDirectory: true)
            .appendingPathComponent(try SyncLocalStore.fileName(dataKey: SyncManagerHarness.testKey,
                roomId: harness.keys.roomId, domain: .replay))
        let label = "sync/room/\(harness.keys.roomId)"
        let plain = try XCTUnwrap(SealedEnvelope.openFile(key: SyncManagerHarness.testKey,
            blob: Data(contentsOf: url), label: label))
        var document = try XCTUnwrap(JSONSerialization.jsonObject(with: plain) as? [String: Any])
        var actors = try XCTUnwrap(document["actors"] as? [String: Any])
        XCTAssertNotNil(actors.removeValue(forKey: actor))
        document["actors"] = actors
        try SafeStore.write(JSONSerialization.data(withJSONObject: document, options: [.sortedKeys]), to: url, label: label)
        let socketCount = harness.socketCount
        let entered = writer.arm()
        harness.join()
        await fulfillment(of: [entered], timeout: 5)
        XCTAssertFalse(writer.ranOnMain)
        XCTAssertEqual(harness.socketCount, socketCount)
        let responsive = expectation(description: "UI runs during legacy repair")
        Task { @MainActor in responsive.fulfill() }
        await fulfillment(of: [responsive], timeout: 1)
        writer.release()
        try await waitUntil { self.harness.socketCount == socketCount + 1 }
    }

    func testSnapshotSealDoesNotBlockMainAndPublishesOnlyAfterDurability() async throws {
        harness.join()
        try await waitUntil { self.harness.socketCount == 1 }
        let waypoint = Waypoint(name: "Remote", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        harness.beginSnapshot()
        harness.page([harness.peerWaypointPut(waypoint, counter: 50)])
        let entered = writer.arm()
        harness.endSnapshot()
        await fulfillment(of: [entered], timeout: 5)
        XCTAssertFalse(writer.ranOnMain)
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
        XCTAssertNil(harness.lastHello)
        let responsive = expectation(description: "UI actor still runs while seal is held")
        Task { @MainActor in responsive.fulfill() }
        await fulfillment(of: [responsive], timeout: 1)
        writer.release()
        try await waitUntil { self.harness.lastHello != nil }
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
    }

    func testFailedHelloBatchNeverPublishesAuthenticatedMembers() async throws {
        try await connect()
        var advertised = 0
        let observer = harness.manager.$onlineMembers.sink { members in
            if members[self.harness.peerActor] != nil { advertised += 1 }
        }
        defer { observer.cancel() }
        let entered = writer.arm(failing: true)
        harness.socket.deliver(harness.peerHello())
        harness.socket.deliver(try harness.peerChatKey())
        harness.socket.deliver(harness.peerLoc(counter: 1))
        harness.pump()
        await fulfillment(of: [entered], timeout: 5)
        XCTAssertTrue(harness.manager.onlineMembers.isEmpty)
        XCTAssertTrue(harness.manager.chatRecipients.isEmpty)
        XCTAssertTrue(harness.manager.presence.peers.isEmpty)
        writer.release()
        try await waitUntil { self.harness.manager.status == .offline }
        XCTAssertEqual(advertised, 0)
        XCTAssertTrue(harness.manager.onlineMembers.isEmpty)
        XCTAssertTrue(harness.manager.chatRecipients.isEmpty)
        XCTAssertTrue(harness.manager.presence.peers.isEmpty)
    }

    func testAuthenticatedPeerChatKeyBeforeOwnHelloAckSurvivesLocalChatEstablishment() async throws {
        harness.join()
        try await waitUntil { self.harness.socketCount == 1 }
        harness.beginSnapshot()
        harness.page([])
        harness.endSnapshot()
        try await waitUntil { self.harness.lastHello != nil }
        let peer = try TacMapChatCrypto.makeLocalSession(roomIdRaw: harness.keys.roomIdRaw,
            actorId: harness.peerActor, sessionDomain: harness.peerSessionRaw, signingSeed: harness.peerSeed)
        harness.socket.deliver(harness.peerHello())
        harness.socket.deliver(peer.advertisement)
        try await waitUntil { self.harness.manager.chatRecipients[self.harness.peerActor] != nil }
        harness.ackHello()
        try await waitUntil { self.harness.manager.status == .connected }
        let advert = try XCTUnwrap(harness.socket.sent(type: "chat-key").last)
        harness.socket.deliver(["t": "chat-key-ack", "cv": 1, "by": advert["by"]!,
            "sd": advert["sd"]!, "kid": advert["kid"]!])
        try await waitUntil { self.harness.manager.chatSessionReady }
        let peerKeys = Mirror(reflecting: harness.manager).children.first { $0.label == "chatPeerKeys" }?.value
            as? [String: TacMapChatCrypto.PeerKey]
        XCTAssertEqual(peerKeys?[harness.peerActor], peer.peerKey,
            "published recipients must retain their actual authenticated current-session keys")
        let messageId = try TacMapChatMessage.makeMessageID()
        let frame = try TacMapChatCrypto.seal(payload: TacMapChatPayload(kind: .text, body: "Early peer key"),
            scope: .room, roomIdRaw: harness.keys.roomIdRaw, roomKey: harness.keys.roomKey,
            localSession: peer, signingSeed: harness.peerSeed, counter: 1, messageId: messageId, recipient: nil)
        harness.socket.deliver(frame.object)
        try await waitUntil { self.harness.manager.chatStore.messages.contains { $0.id == messageId } }
        XCTAssertEqual(harness.manager.chatRecipients[harness.peerActor]?.keyId, peer.keyId)
        XCTAssertEqual(harness.manager.chatStore.messages.first { $0.id == messageId }?.deliveryState, .received)
        let reopened = TacMapChatStore(containerURL: harness.directory)
        try reopened.open(roomId: harness.keys.roomId)
        XCTAssertTrue(reopened.messages.contains { $0.id == messageId && $0.body == "Early peer key" && !$0.isOutgoing })
    }

    func testChatSecretTeardownCannotRepublishStagedRecipient() async throws {
        try await connect()
        let peer = try TacMapChatCrypto.makeLocalSession(roomIdRaw: harness.keys.roomIdRaw,
            actorId: harness.peerActor, sessionDomain: harness.peerSessionRaw, signingSeed: harness.peerSeed)
        harness.socket.deliver(harness.peerHello())
        harness.socket.deliver(peer.advertisement)
        try await waitUntil { self.harness.manager.chatRecipients[self.harness.peerActor] != nil }
        let advert = try XCTUnwrap(harness.socket.sent(type: "chat-key").last)
        harness.socket.deliver(["t": "chat-key-nack", "cv": 1, "by": advert["by"]!,
            "sd": advert["sd"]!, "code": "session_unavailable"])
        harness.pump()
        XCTAssertFalse(harness.manager.chatSessionReady)
        XCTAssertNotNil(harness.manager.chatSessionIssue)
        XCTAssertTrue(harness.manager.chatRecipients.isEmpty,
            "the drain must not resurrect recipients after their secret keys were cleared")
        let peerKeys = Mirror(reflecting: harness.manager).children.first { $0.label == "chatPeerKeys" }?.value
            as? [String: TacMapChatCrypto.PeerKey]
        XCTAssertEqual(peerKeys?.count, 0, "actual teardown must remove the peer secret references")
        let frame = try TacMapChatCrypto.seal(payload: TacMapChatPayload(kind: .text, body: "After teardown"),
            scope: .room, roomIdRaw: harness.keys.roomIdRaw, roomKey: harness.keys.roomKey,
            localSession: peer, signingSeed: harness.peerSeed, counter: 1,
            messageId: TacMapChatMessage.makeMessageID(), recipient: nil)
        harness.socket.deliver(frame.object)
        harness.pump()
        XCTAssertTrue(harness.manager.chatStore.messages.isEmpty,
            "retaining early keys during startup must not retain chat authority after teardown")
        XCTAssertTrue(harness.manager.chatRecipients.isEmpty)
    }

    func testHeldFailedReplacementHelloCannotPruneTheDurableChatFence() async throws {
        try await connect()
        harness.socket.deliver(harness.peerHello())
        try await waitUntil { self.harness.manager.onlineMembers[self.harness.peerActor] != nil }
        let chat = harness.manager.chatStore
        let kid = SyncIdentity.urlB64Encode(Data(repeating: 0x81, count: 32))
        let fingerprint = SyncIdentity.urlB64Encode(Data(repeating: 0x82, count: 32))
        func message(_ byte: UInt8) -> TacMapChatMessage {
            TacMapChatMessage(id: SyncIdentity.urlB64Encode(Data(repeating: byte, count: 16)),
                roomId: harness.keys.roomId, scope: .room, senderActorId: harness.peerActor,
                senderName: "Peer", recipientActorId: nil, recipientName: nil, kind: .text,
                body: "Durable old session", sentAtMilliseconds: 1_720_000_000_000,
                isOutgoing: false, deliveryState: .received, failureCode: nil)
        }
        XCTAssertEqual(try chat.acceptInbound(message(1), actorId: harness.peerActor,
            sessionDomain: harness.peerSession, keyId: kid, counter: 5, fingerprint: fingerprint), .accepted)
        let nextRaw = Data(repeating: 0x83, count: 32)
        let next = SyncIdentity.urlB64Encode(nextRaw)
        let epoch = "0000000000000008"
        let preimage = SyncIdentity.buildPreimage(domain: SyncIdentity.domainHello,
            roomIdRaw: harness.keys.roomIdRaw, actorId: harness.peerActor, sessionDomain: nextRaw,
            counterHex16: epoch, objectId: "", kind: "hello",
            payloadHash: SyncIdentity.sha256(SyncSigning.publicKeyRaw(harness.peerSeed)!))
        let entered = writer.arm(failing: true)
        harness.socket.deliver(["t": "hello", "by": harness.peerActor, "pub": harness.peerPub,
            "sd": next, "vs": "\(epoch):\(harness.peerActor)",
            "sig": SyncSigning.sign(harness.peerSeed, preimage)!])
        harness.pump()
        await fulfillment(of: [entered], timeout: 5)
        // No presence is needed: a hello-only session also has durable identity.
        XCTAssertEqual(chat.durableSessionDomain?(harness.peerActor), harness.peerSession)
        XCTAssertTrue(try chat.markRead(scope: .room, directActorId: nil), "a real chat seal invokes pruning while hello is held")
        writer.release()
        try await waitUntil { self.harness.manager.status == .offline }
        let loaded = SyncReplayState(roomId: harness.keys.roomId, containerURL: harness.directory)
        XCTAssertTrue(loaded.load())
        XCTAssertEqual(loaded.durableSessionDomain(harness.peerActor), harness.peerSession)
        let reopened = TacMapChatStore(containerURL: harness.directory)
        reopened.durableSessionDomain = { loaded.durableSessionDomain($0) }
        try reopened.open(roomId: harness.keys.roomId)
        XCTAssertEqual(try reopened.acceptInbound(message(2), actorId: harness.peerActor,
            sessionDomain: harness.peerSession, keyId: kid, counter: 4, fingerprint: fingerprint), .rejected,
            "restart cannot accept an older chat with a different message id")
    }

    func testStartupJournalMigrationRetainsEditsAndFencesSupersededJoin() async throws {
        try await replaceWithHeldStartupHarness(backgroundBeforeRelease: false)
    }

    func testStartupJournalCompletionInBackgroundDefersQueuedEditSealsUntilForeground() async throws {
        try await replaceWithHeldStartupHarness(backgroundBeforeRelease: true)
    }

    func testEmptyStartupAndForegroundReconciliationCannotConnectBeforeReplayLoad() async throws {
        let initial = expectation(description: "initial fixture startup drained")
        SyncPersistenceExecutor().execute({}) { initial.fulfill() }
        await fulfillment(of: [initial], timeout: 5)
        harness.tearDown()
        let executor = HeldStartupLoadExecutor()
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        defer { executor.releaseReplayLoad() }
        harness.join()
        let joined = expectation(description: "join parked behind held journal startup")
        SyncPersistenceExecutor().execute({}) { joined.fulfill() }
        await fulfillment(of: [joined], timeout: 5)
        harness.manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: false, backgroundInterval: 900)
        executor.release()
        let loaded = expectation(description: "empty journal loaded in background")
        SyncPersistenceExecutor().execute({}) { loaded.fulfill() }
        await fulfillment(of: [loaded], timeout: 5)
        XCTAssertFalse(executor.loadRanOnMain)
        XCTAssertEqual(harness.socketCount, 0)
        executor.holdNextReplayLoad()
        harness.manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        XCTAssertTrue(executor.hasHeldReplayLoad)
        XCTAssertEqual(harness.socketCount, 0, "foreground reconciliation must wait for actual replay-state readiness")
        // A further pause/return while replay remains held must not bypass the
        // readiness barrier either.
        harness.manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: false, backgroundInterval: 900)
        harness.manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        XCTAssertEqual(harness.socketCount, 0)
        executor.releaseReplayLoad()
        try await waitUntil { self.harness.socketCount == 1 }
        XCTAssertNil(harness.manager.lastError)
        harness.beginSnapshot(); harness.page([]); harness.endSnapshot()
        try await waitUntil { self.harness.lastHello != nil }
        harness.ackHello()
        try await waitUntil { self.harness.manager.status == .connected }
    }

    private func replaceWithHeldStartupHarness(backgroundBeforeRelease: Bool) async throws {
        let drained = expectation(description: "initial fixture journal drained")
        SyncPersistenceExecutor().execute({}) { drained.fulfill() }
        await fulfillment(of: [drained], timeout: 5)
        harness.tearDown()
        let executor = HeldStartupLoadExecutor()
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        let waypoint = Waypoint(name: "During startup", latitude: -33.8, longitude: 151.2,
                                layerID: DrawingLayer.legacyFallbackID)
        let journalURL = harness.directory.appendingPathComponent("sync_model_revisions.json")
        try JSONSerialization.data(withJSONObject: ["schemaVersion": 1,
            "generations": [waypoint.id.uuidString: "0000000000000004"]]).write(to: journalURL)
        _ = try harness.waypointStore.addDurably(waypoint)
        var edited = waypoint
        edited.name = "Second event"
        _ = try harness.waypointStore.commitEdit(edited)
        harness.join()
        harness.manager.leave()
        harness.join() // only this join may resume after startup
        XCTAssertEqual(harness.socketCount, 0)
        XCTAssertEqual(harness.writes.journalWrites, 0)
        if backgroundBeforeRelease {
            harness.manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: false, backgroundInterval: 900)
        }
        let responsive = expectation(description: "UI while journal load held")
        Task { @MainActor in responsive.fulfill() }
        await fulfillment(of: [responsive], timeout: 1)
        executor.release()
        let loaded = expectation(description: "actual startup read completed")
        SyncPersistenceExecutor().execute({}) { loaded.fulfill() }
        await fulfillment(of: [loaded], timeout: 5)
        XCTAssertFalse(executor.loadRanOnMain)
        XCTAssertTrue(SealedEnvelope.isSealedFile(try Data(contentsOf: journalURL)), "legacy journal migration really sealed on the worker")
        if backgroundBeforeRelease {
            XCTAssertEqual(harness.writes.journalWrites, 0)
            XCTAssertEqual(harness.socketCount, 0)
            harness.manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        }
        try await waitUntil { self.harness.socketCount == 1 }
        let journal = LocalModelRevisionJournal(containerURL: harness.directory)
        XCTAssertTrue(journal.load())
        XCTAssertEqual(journal.generation(waypoint.id.uuidString), 6, "both local events survive migration of the existing generation")
        XCTAssertEqual(harness.writes.journalWrites, 2)
        XCTAssertNil(harness.manager.lastError)
        harness.pump(250)
        XCTAssertEqual(harness.socketCount, 1, "stale join continuation stays fenced")
    }

    func testLeaveAndRejoinWhileSealIsHeldNeverAppliesTheOldSnapshot() async throws {
        harness.join()
        try await waitUntil { self.harness.socketCount == 1 }
        let oldSocket = harness.socket
        let waypoint = Waypoint(name: "Old session", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        harness.beginSnapshot()
        harness.page([harness.peerWaypointPut(waypoint, counter: 50)])
        let entered = writer.arm()
        harness.endSnapshot()
        await fulfillment(of: [entered], timeout: 5)
        harness.manager.leave()
        harness.join()
        XCTAssertTrue(oldSocket.isCancelled)
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
        writer.release()
        try await waitUntil { self.harness.socketCount == 2 }
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
        XCTAssertNil(harness.lastHello, "old completion cannot send hello on the new socket")
        harness.beginSnapshot(seq: 2)
        harness.page([])
        harness.endSnapshot(seq: 2)
        try await waitUntil { self.harness.lastHello != nil }
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
    }
    func testBackgroundEntryDuringAnOutboundSealCannotSendTheReservedMutation() async throws {
        var config = harness.manager.presenceConfig
        config.shareLocation = true
        XCTAssertTrue(harness.manager.updatePresenceConfig(config))
        try await connect()
        let waypoint = Waypoint(name: "Foreground edit", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        _ = try harness.waypointStore.addDurably(waypoint)
        // The counter increments at writer entry, before the main completion
        // releases pendingJournalWrites. Drain durability before consuming the
        // single virtual diff timer; otherwise that timer can run too early.
        let journalDrained = expectation(description: "local revision journal durable")
        SyncPersistenceExecutor().execute({}) { journalDrained.fulfill() }
        await fulfillment(of: [journalDrained], timeout: 5)
        let entered = writer.arm()
        harness.pump(250)
        await fulfillment(of: [entered], timeout: 5)
        harness.manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: true, backgroundInterval: 900)
        writer.release()
        let drained = expectation(description: "reservation and clean point completed")
        SyncPersistenceExecutor().execute({}) { drained.fulfill() }
        await fulfillment(of: [drained], timeout: 5)
        XCTAssertTrue(harness.sentMutations().isEmpty, "foreground put cannot escape after the key locks")
        XCTAssertTrue(harness.manager.onlineMembers.isEmpty)
    }

    func testAnOverlappingCleanPointFailureKeepsTheActualDurableCrashFloor() async throws {
        let state = SyncReplayState(roomId: harness.keys.roomId, containerURL: harness.directory, persistenceWriter: writer.write)
        XCTAssertTrue(try state.acceptHello(actorId: harness.peerActor, pubkey: harness.peerPub,
                                           sessionDomain: harness.peerSession, epochHex: "0000000000000001"))
        XCTAssertTrue(try state.acceptPresence(actorId: harness.peerActor, sessionDomain: harness.peerSession, counter: 1))
        state.beginBatch()
        XCTAssertTrue(try state.acceptPresence(actorId: harness.peerActor, sessionDomain: harness.peerSession, counter: 17))
        let first = writer.arm()
        let committed = expectation(description: "stride committed")
        state.flushBatch(on: SyncPersistenceExecutor()) { error in
            XCTAssertNil(error)
            try? state.endBatch()
            committed.fulfill()
        }
        await fulfillment(of: [first], timeout: 5)
        let cleaned = expectation(description: "clean point failed")
        state.writeCleanPresenceFence(on: SyncPersistenceExecutor()) { error in
            XCTAssertNotNil(error)
            cleaned.fulfill()
        }
        XCTAssertFalse(state.presenceFenceExact, "queued clean point cannot be called durable yet")
        let second = writer.arm(failing: true)
        writer.release()
        await fulfillment(of: [committed, second], timeout: 5)
        writer.release()
        await fulfillment(of: [cleaned], timeout: 5)
        XCTAssertFalse(state.presenceFenceExact, "failure restores the last successful stride flag")
        let loaded = SyncReplayState(roomId: harness.keys.roomId, containerURL: harness.directory)
        XCTAssertTrue(loaded.load())
        XCTAssertFalse(try loaded.acceptPresence(actorId: harness.peerActor, sessionDomain: harness.peerSession, counter: 32))
        XCTAssertTrue(try loaded.acceptPresence(actorId: harness.peerActor, sessionDomain: harness.peerSession, counter: 33))
    }

}
