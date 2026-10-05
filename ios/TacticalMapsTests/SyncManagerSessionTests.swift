import XCTest
import CryptoKit
@testable import TacticalMaps

/// Drives the real SyncManager through the transport seam (S6-02) with a fake
/// relay socket and a manual clock. Each test is one audited defect, written
/// to fail against the pre-SP2 manager and kept as a regression.
@MainActor
final class SyncManagerSessionTests: XCTestCase {
    private var harness: SyncManagerHarness!

    override func setUp() async throws {
        harness = try SyncManagerHarness()
    }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
    }

    private var manager: SyncManager { harness.manager }

    private func myActor() -> String {
        harness.lastHello?["by"] as? String ?? ""
    }

    private func reconnectDelay() -> Int64? {
        manager.reconnectDueMs.map { $0 - harness.scheduler.nowMs }
    }

    // MARK: happy path (S6-02)

    func testHappyJoinReachesConnectedThroughTheSeam() {
        harness.join()
        XCTAssertEqual(harness.socketCount, 1)
        let request = harness.socket.request
        XCTAssertEqual(request.url?.path, "/v3/room/\(harness.keys.roomId)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Protocol"), "3")
        XCTAssertEqual(manager.status, .connecting)
        harness.connect()
        XCTAssertEqual(manager.status, .connected)
        XCTAssertEqual(harness.socket.sent(type: "hello").count, 1)
        XCTAssertNil(manager.lastError)
    }

    // MARK: op-nack (S6-03, S3-15, S3-02, S6-01)

    func testStaleNackIsAnLwwLossNotAReconnectOrSecurityEvent() throws {
        harness.join()
        harness.connect()
        let local = try harness.addLocalWaypoint("Mine")
        harness.pump(250)
        let ourPut = try XCTUnwrap(harness.sentMutations().last)
        let ourStamp = try XCTUnwrap(VersionStamp.parse(ourPut["vs"] as! String))
        let counter = harness.peerActor > ourStamp.actorId ? ourStamp.counter : ourStamp.counter + 1
        var theirs = local
        theirs.name = "Theirs"
        harness.socket.deliver(harness.peerWaypointPut(theirs, counter: counter, live: true))
        harness.pump()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == local.id }?.name, "Theirs")

        harness.nack(ourPut, code: "stale", retry: false)
        harness.pump(60_000)

        XCTAssertEqual(manager.status, .connected)
        XCTAssertEqual(harness.socketCount, 1, "losing an LWW race must not reconnect")
        XCTAssertFalse(harness.socket.isCancelled)
        XCTAssertNotEqual(manager.lastIssueKind, .security)
        XCTAssertEqual(harness.sentMutations().count, 1, "the rejected op is resolved, never republished")
    }

    func testCounterWindowNackPausesMutationsOnceAndKeepsTheSession() throws {
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("First")
        harness.pump(250)
        let put = try XCTUnwrap(harness.sentMutations().last)
        harness.nack(put, code: "counter-window", retry: false)
        harness.pump(1_000)

        XCTAssertFalse(harness.socket.isCancelled, "counter-window must not tear the socket down")
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .roomResetChangesPaused }.count, 1)
        XCTAssertNotEqual(manager.lastIssueKind, .security)

        _ = try harness.addLocalWaypoint("Second")
        harness.pump(1_000)
        XCTAssertEqual(harness.sentMutations().count, 1, "no put is reserved or sent while paused")

        // a reconnect keeps the join-scoped pause and does not surface again
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        XCTAssertEqual(harness.socketCount, 2)
        harness.connect(seq: 2)
        harness.pump(1_000)
        XCTAssertEqual(harness.sentMutations().count, 0)
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .roomResetChangesPaused }.count, 1)
    }

    func testQuotaNackResolvesTheOpAndItIsNotResentAfterReconnect() throws {
        harness.join()
        harness.connect()
        let local = try harness.addLocalWaypoint("Big room")
        harness.pump(250)
        let put = try XCTUnwrap(harness.sentMutations().last)
        harness.nack(put, code: "quota", retry: false)
        XCTAssertFalse(harness.socket.isCancelled)
        XCTAssertEqual(manager.lastIssueKind, .connection)
        XCTAssertTrue(manager.surfacedIssueLog.contains(.roomQuotaNack))

        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        harness.connect(seq: 2)
        harness.pump(1_000)
        let wire = harness.wireId(local.id)
        XCTAssertTrue(harness.sentMutations().filter { $0["id"] as? String == wire }.isEmpty,
                      "a quota-rejected object waits for a local edit")
    }

    func testStorageNackKeepsTheOpAndRetransmitsOnTheAckSchedule() throws {
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("Relay storage hiccup")
        harness.pump(250)
        let put = try XCTUnwrap(harness.sentMutations().last)
        harness.nack(put, code: "storage", retry: true)
        XCTAssertEqual(harness.sentMutations().count, 1)
        harness.pump(5_000)
        let copies = harness.sentMutations()
        XCTAssertEqual(copies.count, 2, "one retransmission once the 5 s ack timer runs out")
        XCTAssertEqual(copies.last?["rid"] as? String, put["rid"] as? String)
        harness.ack(copies[1])
        harness.pump(60_000)
        XCTAssertEqual(harness.sentMutations().count, 2)
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertNotEqual(manager.lastIssueKind, .security)
    }

    func testUnknownRetryableNackBehavesLikeStorage() throws {
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("Future relay")
        harness.pump(250)
        let put = try XCTUnwrap(harness.sentMutations().last)
        harness.nack(put, code: "brand-new-code", retry: true)
        harness.pump(5_000)
        XCTAssertEqual(harness.sentMutations().count, 2)
        XCTAssertFalse(harness.socket.isCancelled)
    }

    func testSessionReplacedThreeTimesInTenMinutesStops() throws {
        harness.join()
        for round in 0..<3 {
            harness.connect(seq: Int64(round + 1))
            harness.socket.remoteClose(code: 4015)
            harness.pump()
            if round < 2 { harness.advanceUntilNewSocket() }
        }
        XCTAssertEqual(manager.pausedForAction?.issue, .sessionConflict)
        let sockets = harness.socketCount
        harness.pump(600_000)
        XCTAssertEqual(harness.socketCount, sockets, "a paused session conflict must not keep reconnecting")
    }

    // MARK: snapshot verdicts (S2-10, S3-03, S4-01, S6-09)

    func testPoisonRecordsAreSkippedAndTheHandshakeCompletes() throws {
        let skippedLocal = try harness.addLocalWaypoint("Unreadable on the relay")
        let w1 = Waypoint(name: "W1", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        let validW1 = harness.peerWaypointPut(w1, counter: 2)
        let garbage = Data((0..<64).map { UInt8(truncatingIfNeeded: $0 &* 37 &+ 11) }).base64EncodedString()
        let w2 = harness.peerPut(
            wireId: harness.wireId(skippedLocal.id), content: "{}", counter: 3, ciphertextOverride: garbage)
        let w3Waypoint = Waypoint(name: "W3", latitude: -33.7, longitude: 151.1, layerID: DrawingLayer.legacyFallbackID)
        let w3 = harness.peerPut(
            wireId: harness.wireId(w3Waypoint.id),
            content: harness.peerContent(waypoint: w3Waypoint), counter: 4, kind: "route")

        harness.join()
        harness.connect(items: [validW1, w2, w3])

        XCTAssertEqual(manager.status, .connected)
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == w1.id })
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == w3Waypoint.id })
        XCTAssertTrue(manager.surfacedIssueLog.contains(.skippedUnverified))
        XCTAssertTrue(manager.surfacedIssueLog.contains(.skippedUnsupported))
        XCTAssertEqual(manager.lastIssueKind, .security, "an unverified skip is not a clean snapshot")
        harness.pump(1_000)
        let skippedWire = harness.wireId(skippedLocal.id)
        XCTAssertTrue(harness.sentMutations().filter { $0["id"] as? String == skippedWire }.isEmpty,
                      "an untouched local copy of a skipped record is never pushed over it")

        // same snapshot again after a reconnect: nothing new surfaces, no loop
        let logged = manager.surfacedIssueLog.count
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        XCTAssertEqual(harness.socketCount, 2)
        harness.connect(items: [validW1, w2, w3], seq: 2)
        XCTAssertEqual(manager.status, .connected)
        XCTAssertEqual(manager.surfacedIssueLog.count, logged)
        harness.pump(60_000)
        XCTAssertEqual(harness.socketCount, 2, "a skipped record never causes a reconnect")

        // a real local edit publishes it once
        var edited = skippedLocal
        edited.name = "Edited here"
        _ = try harness.waypointStore.commitEdit(edited)
        harness.pump(300)
        XCTAssertEqual(harness.sentMutations().filter { $0["id"] as? String == skippedWire }.count, 1)
    }

    func testDuplicateWireIdRejectsTheWholeSnapshotThenStopsAfterThree() throws {
        let w1 = Waypoint(name: "Dup", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        let record = harness.peerWaypointPut(w1, counter: 2)
        harness.join()
        for round in 1...3 {
            harness.beginSnapshot(seq: Int64(round))
            harness.page([record, record])
            harness.pump()
            XCTAssertNil(harness.lastHello, "round \(round): no hello after a structural failure")
            XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == w1.id },
                           "nothing from a rejected snapshot is committed")
            if round < 3 {
                XCTAssertNotNil(manager.reconnectDueMs, "round \(round) reconnects")
                harness.advanceUntilNewSocket()
                XCTAssertEqual(harness.socketCount, round + 1)
            }
        }
        XCTAssertEqual(manager.pausedForAction?.issue, .snapshotMalformedStopped)
        harness.pump(600_000)
        XCTAssertEqual(harness.socketCount, 3)
    }

    func testSnapshotLayerMetadataIsStagedInItemOrder() throws {
        let layerId = UUID()
        let recon = DrawingLayer(id: layerId, name: "Recon", defaultColorHex: "#112233")
        let scouts = DrawingLayer(id: layerId, name: "Scouts", defaultColorHex: "#445566")
        let d1 = DrawingShape(kind: .polyline, coordinates: [
            Coordinate2D(latitude: -33.8, longitude: 151.2), Coordinate2D(latitude: -33.81, longitude: 151.21)
        ], layerID: layerId)
        let d2 = DrawingShape(kind: .polyline, coordinates: [
            Coordinate2D(latitude: -33.7, longitude: 151.1), Coordinate2D(latitude: -33.71, longitude: 151.11)
        ], layerID: layerId)
        let r1 = harness.peerPut(wireId: harness.wireId(d1.id),
                                 content: harness.peerContent(drawing: d1, layers: [recon]), counter: 2, kind: "drawing")
        let r2 = harness.peerPut(wireId: harness.wireId(d2.id),
                                 content: harness.peerContent(drawing: d2, layers: [scouts]), counter: 3, kind: "drawing")
        harness.join()
        harness.connect(items: [r1, r2])
        XCTAssertEqual(manager.status, .connected)
        XCTAssertEqual(harness.drawingStore.layers.first { $0.id == layerId }?.name, "Recon")
        XCTAssertTrue(harness.drawingStore.shapes.contains { $0.id == d2.id && $0.layerID == layerId })
        XCTAssertNotEqual(manager.lastIssueKind, .security)
    }

    // MARK: backoff, close codes, statuses (S2-11, S2-14, S3-13, S3 note 2)

    func testBackoffIsNotResetByHelloAck() {
        harness.randomValue = 1.0
        harness.join()
        harness.connect()
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        XCTAssertEqual(reconnectDelay(), 1_000)
        harness.pump(1_000)
        harness.connect(seq: 2)
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        XCTAssertEqual(reconnectDelay(), 1_750, "a session that died without an op-ack keeps backing off")
    }

    func testFirstOpAckResetsBackoff() throws {
        harness.randomValue = 1.0
        harness.join()
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.pump(1_000)
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.pump(1_750)
        harness.connect()
        _ = try harness.addLocalWaypoint("Acked")
        harness.pump(250)
        harness.ack(try XCTUnwrap(harness.sentMutations().last))
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        XCTAssertEqual(reconnectDelay(), 1_000)
    }

    func test503UpgradeBacksOffSlowlyAndSurfacesBusyOnTheSecond() {
        harness.join()
        harness.socket.upgradeFailed(httpStatus: 503)
        harness.pump()
        let first = reconnectDelay() ?? 0
        XCTAssertGreaterThanOrEqual(first, 15_000)
        XCTAssertFalse(manager.surfacedIssueLog.contains(.relayBusy))
        harness.pump(first)
        harness.socket.upgradeFailed(httpStatus: 503)
        harness.pump()
        XCTAssertTrue(manager.surfacedIssueLog.contains(.relayBusy))
    }

    func testRoomFullCloseStopsAndRetriesOnTheNextForegroundReturn() {
        harness.join()
        harness.beginSnapshot()
        harness.page([])
        harness.endSnapshot()
        harness.socket.remoteClose(code: 4013)
        harness.pump()
        XCTAssertEqual(manager.pausedForAction?.issue, .roomFullCannotJoin)
        XCTAssertNil(manager.reconnectDueMs)
        harness.pump(600_000)
        XCTAssertEqual(harness.socketCount, 1, "4013 must not reconnect on its own")
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: false, backgroundInterval: 900)
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        harness.pump()
        XCTAssertEqual(harness.socketCount, 2)
        XCTAssertNil(manager.pausedForAction)
    }

    func testRelayRefusedRoomStopsUntilRetry() {
        harness.join()
        harness.socket.upgradeFailed(httpStatus: 403)
        harness.pump(600_000)
        XCTAssertEqual(manager.pausedForAction?.issue, .relayRefusedRoom)
        XCTAssertEqual(harness.socketCount, 1)
        manager.retryPausedConnection()
        harness.pump()
        XCTAssertEqual(harness.socketCount, 2)
    }

    func testIdentityRejectedIsTheOnlySecurityClose() {
        harness.join()
        harness.connect()
        harness.socket.remoteClose(code: 4011)
        harness.pump(600_000)
        XCTAssertEqual(manager.pausedForAction?.issue, .identityRejected)
        XCTAssertEqual(manager.lastIssueKind, .security)
        XCTAssertEqual(harness.socketCount, 1)
    }

    func testStaleHelloEpochEscalatesFromTheTimeFloor() throws {
        harness.join()
        harness.beginSnapshot()
        harness.page([])
        harness.endSnapshot()
        let firstVs = try XCTUnwrap(harness.lastHello?["vs"] as? String)
        let first = try XCTUnwrap(UInt64(firstVs.prefix(16), radix: 16))
        XCTAssertEqual(first, UInt64(harness.scheduler.wallMs / 60_000),
                       "a fresh replay state starts at the unix-minute floor, not 1")
        harness.socket.remoteClose(code: 4014)
        harness.advanceUntilNewSocket()
        XCTAssertEqual(harness.socketCount, 2)
        harness.beginSnapshot(seq: 2)
        harness.page([])
        harness.endSnapshot(seq: 2)
        let secondVs = try XCTUnwrap(harness.lastHello?["vs"] as? String)
        XCTAssertEqual(UInt64(secondVs.prefix(16), radix: 16), first * 2)
    }

    // MARK: watchdogs and heartbeat (S2-03, S3-05)

    func testSlowButProgressingSnapshotIsNotKilledByTheWatchdog() {
        harness.join()
        harness.beginSnapshot()
        for _ in 0..<3 {
            harness.pump(50_000)
            harness.page([], more: true)
        }
        harness.pump(50_000)
        harness.page([])
        harness.pump(40_000)
        harness.endSnapshot()
        harness.pump(20_000)
        harness.ackHello()
        XCTAssertEqual(manager.status, .connected)
        XCTAssertEqual(harness.socketCount, 1)
    }

    func testStalledHandshakeFiresAfterSixtySecondsWithoutProgress() {
        harness.join()
        harness.beginSnapshot()
        harness.pump(59_000)
        XCTAssertFalse(harness.socket.isCancelled)
        harness.pump(1_000)
        XCTAssertTrue(harness.socket.isCancelled)
        XCTAssertNotNil(manager.reconnectDueMs)
        XCTAssertEqual(manager.lastIssueKind, .connection)
    }

    func testHeartbeatCountsAnyInboundFrameAsLife() {
        harness.join()
        harness.connect()
        harness.socket.autoPong = false
        harness.pump(20_000) // ping goes out
        XCTAssertEqual(harness.socket.pings, 1)
        harness.pump(10_000)
        harness.socket.deliver(harness.peerHello())
        harness.pump(25_000)
        XCTAssertFalse(harness.socket.isCancelled, "a frame after the ping proves the link is alive")
    }

    // MARK: outbound pacing and retransmission (S3-06, S3-07, S5-08, S5-09, S6-07)

    func testRetransmitTimerStartsWhenTheFrameIsActuallyWritten() throws {
        harness.join()
        harness.connect()
        harness.socket.autoCompleteWrites = false
        _ = try harness.addLocalWaypoint("Slow uplink")
        harness.pump(250)
        XCTAssertEqual(harness.sentMutations().count, 1)
        harness.pump(20_000)
        XCTAssertEqual(harness.sentMutations().count, 1, "no copy while the first is still unwritten")
        harness.socket.completeWrites()
        harness.pump(4_900)
        XCTAssertEqual(harness.sentMutations().count, 1)
        harness.ack(harness.sentMutations()[0])
        harness.pump(60_000)
        XCTAssertEqual(harness.sentMutations().count, 1)
        XCTAssertFalse(harness.socket.isCancelled)
    }

    func testUnackedOpIsAConnectionIssueNotSecurity() throws {
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("Never acked")
        harness.pump(250)
        harness.pump(120_000)
        XCTAssertTrue(manager.surfacedIssueLog.contains(.unconfirmedReconnect))
        XCTAssertEqual(manager.lastIssueKind, .connection)
        XCTAssertGreaterThanOrEqual(harness.socketCount, 2)
        XCTAssertLessThanOrEqual(harness.sentMutations(on: harness.factory.sockets[0]).count, 3)
    }

    func testBulkPublishIsPacedBelowTheRelayWindow() throws {
        for index in 0..<500 {
            let waypoint = Waypoint(name: "Bulk \(index)", latitude: -33.0 - Double(index) / 1000,
                                    longitude: 151.0, layerID: DrawingLayer.legacyFallbackID)
            _ = try harness.waypointStore.addDurably(waypoint)
        }
        harness.join()
        harness.connect()
        let socket = harness.socket
        var sendTimes: [Int64] = []
        var unacked: [(at: Int64, frame: [String: Any])] = []
        var seen = 0
        var putIds = Set<String>()
        let start = harness.scheduler.nowMs
        func collect() {
            while seen < socket.sent.count {
                let text = socket.sent[seen]
                seen += 1
                sendTimes.append(harness.scheduler.nowMs)
                guard text.contains("\"t\":\"put\""),
                      let frame = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any] else { continue }
                putIds.insert(frame["id"] as! String)
                unacked.append((harness.scheduler.nowMs, frame))
            }
        }
        collect()
        while harness.scheduler.nowMs - start < 70_000 {
            harness.pump(50)
            collect()
            while let first = unacked.first, harness.scheduler.nowMs - first.at >= 200 {
                unacked.removeFirst()
                harness.ack(first.frame, on: socket)
                collect()
            }
        }
        XCTAssertFalse(socket.isCancelled)
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertEqual(putIds.count, 500)
        var worst = 0
        var lower = 0
        for upper in sendTimes.indices {
            while sendTimes[upper] - sendTimes[lower] >= 10_000 { lower += 1 }
            worst = max(worst, upper - lower + 1)
        }
        XCTAssertLessThanOrEqual(worst, 150, "frames in any 10 s window must stay under clientPacing")
    }

    // MARK: receive budget (S2-04, S4-02)

    func testPeerBulkImportDoesNotTripTheReceiveBudget() {
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        let loc: [String: Any] = ["t": "loc", "by": harness.peerActor, "ct": "AAAA",
                                  "pub": harness.peerPub, "sd": harness.peerSession, "vs": "x"]
        for _ in 0..<400 { harness.socket.deliver(loc) }
        let ack: [String: Any] = ["t": "op-ack", "av": 1, "rid": "unknown_request_id_00", "by": "x"]
        for _ in 0..<300 { harness.socket.deliver(ack) }
        harness.pump()
        XCTAssertFalse(harness.socket.isCancelled, "one member's import must not disconnect the room")
        // 600 + 25 for one session is the ceiling
        // hello + 400 + 224 = 625 room frames, exactly 600 + 25 for one session
        for _ in 0..<224 { harness.socket.deliver(loc) }
        harness.pump()
        XCTAssertFalse(harness.socket.isCancelled)
        harness.socket.deliver(loc)
        harness.pump()
        XCTAssertEqual(harness.socket.cancelledWith, 1008)
    }

    // MARK: reachability (S2-13)

    func testNetworkReturnPullsAPendingTransientReconnectIn() {
        harness.randomValue = 1.0
        harness.join()
        harness.path.emit(satisfied: true)
        for _ in 0..<7 {
            harness.socket.upgradeFailed(httpStatus: nil)
            harness.pump()
            harness.pump(reconnectDelay() ?? 0)
        }
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.pump()
        XCTAssertEqual(reconnectDelay(), 30_000)
        let sockets = harness.socketCount
        harness.path.emit(satisfied: false)
        harness.pump(500)
        harness.path.emit(satisfied: true)
        harness.pump(2_000)
        XCTAssertEqual(harness.socketCount, sockets + 1, "network back means try again within ~2 s")
    }

    // MARK: lifecycle (S2-12)

    func testTransientInactiveKeepsTheSessionAndChatKey() throws {
        harness.join()
        harness.connect()
        let advert = try XCTUnwrap(harness.socket.sent(type: "chat-key").last)
        harness.socket.deliver(["t": "chat-key-ack", "cv": 1, "by": advert["by"]!, "sd": advert["sd"]!, "kid": advert["kid"]!])
        harness.pump()
        XCTAssertTrue(manager.chatSessionReady)

        let inactive = SyncLifecyclePolicy.iosForegroundReady(
            phase: .inactive, dataKeyAuthBound: false, dataKeyUnlocked: true,
            appLockOverlay: false, storesLocked: false)
        XCTAssertTrue(inactive)
        manager.updateLifecycle(foregroundReady: inactive, backgroundPresenceEnabled: false, backgroundInterval: 900)
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        // ContentView runs this on every return to .active
        manager.restoreChatAfterMissionUnlock()
        harness.pump()
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertFalse(harness.socket.isCancelled)
        XCTAssertTrue(manager.chatSessionReady)
    }

    // MARK: live counter-window resync (SP1 review) and room reset (S6-01)

    func testSignedLiveFrameBeyondOurWindowTriggersOneResync() {
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        let farAhead = Waypoint(name: "Returning actor", latitude: -33.8, longitude: 151.2,
                                layerID: DrawingLayer.legacyFallbackID)
        harness.socket.deliver(harness.peerWaypointPut(farAhead, counter: 20_001, live: true))
        harness.pump()
        XCTAssertEqual(harness.socketCount, 2, "resync = fresh socket for a fresh snapshot")
        XCTAssertNil(manager.lastError)
        harness.connect(seq: 2)
        harness.socket.deliver(harness.peerHello(epoch: 8))
        let again = Waypoint(name: "Again", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        harness.socket.deliver(harness.peerWaypointPut(again, counter: 30_001, live: true))
        harness.pump(1_000)
        XCTAssertEqual(harness.socketCount, 2, "cooldown: at most one resync a minute")
        harness.pump(60_000)
        XCTAssertEqual(harness.socketCount, 3, "the remembered resync runs after the cooldown")
    }

    func testSeqRegressionIsSurfacedOncePerJoin() {
        harness.join()
        harness.connect(seq: 90)
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        harness.connect(seq: 4)
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .roomResetSuspected }.count, 1)
        XCTAssertEqual(manager.status, .connected, "keep syncing, replay rules still hold")
        manager.acknowledgeLastError()
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        harness.connect(seq: 5)
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .roomResetSuspected }.count, 1)
        XCTAssertNil(manager.lastError)
    }

    // MARK: pre-send size check (S1-09)

    func testObjectTooLargeIsNeitherReservedNorSent() throws {
        var coordinates: [Coordinate2D] = []
        for i in 0..<16_000 {
            coordinates.append(Coordinate2D(latitude: -33.0 - Double(i) * 0.000012345678,
                                            longitude: 151.0 + Double(i) * 0.000023456789))
        }
        let huge = DrawingShape(kind: .freedraw, coordinates: coordinates, layerID: DrawingLayer.legacyFallbackID)
        harness.join()
        harness.connect()
        _ = try harness.drawingStore.addDurably(huge)
        harness.pump(1_000)
        let wire = harness.wireId(huge.id)
        XCTAssertTrue(harness.sentMutations().filter { $0["id"] as? String == wire }.isEmpty)
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .objectTooLarge }.count, 1)
        XCTAssertFalse(harness.socket.isCancelled)
    }
}

/// S3-01 (iOS sent uppercase v2 ids, Android lowercase, iOS then echoed a
/// delete), S3-14 (equal-v tie went the other way from the relay) and the
/// 3.0.1 owner call to keep iOS v2 frame ids uppercase (gap-v2-room-2x-interop-1).
@MainActor
final class SyncLegacyV2SessionTests: XCTestCase {
    private var harness: SyncManagerHarness!
    private static let code = "sp2-v2-room-000001"
    private static var v2Keys: SyncCrypto.RoomKeys?
    private let androidSeed = Data(repeating: 0x0a, count: 32)

    override func setUp() async throws {
        harness = try SyncManagerHarness(joinCode: "2:" + Self.code)
        if Self.v2Keys == nil { Self.v2Keys = SyncCrypto.deriveRoom(Self.code) }
    }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
    }

    private func connectV2() {
        harness.join()
        harness.socket.deliver(["t": "snapshot-begin", "seq": 1])
        harness.socket.deliver(["t": "snapshot", "items": [], "more": false])
        harness.socket.deliver(["t": "snapshot-end", "seq": 1])
        harness.pump()
        XCTAssertEqual(harness.manager.status, .connected)
    }

    private func v2Put(id: String, v: Int64, by: String, waypoint: Waypoint, seed: Data) -> [String: Any] {
        let content = try! GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: [])
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, "waypoint", by, content))!
        let inner = try! JSONSerialization.data(withJSONObject: [
            "c": content, "pub": SyncSigning.publicKey(seed)!, "sig": sig
        ])
        let sealed = SyncCrypto.seal(Self.v2Keys!.roomKey, inner, aad: SyncCrypto.aad(id: id, v: v, kind: "waypoint"))!
        return ["t": "put", "id": id, "v": v, "by": by, "kind": "waypoint", "ct": sealed.base64EncodedString()]
    }

    /// Opens one of our own v2 frames the way a peer does, over its raw id.
    private func openOwn(_ frame: [String: Any]) -> [String: Any]? {
        guard let id = frame["id"] as? String, let v = (frame["v"] as? NSNumber)?.int64Value,
              let by = frame["by"] as? String, let ct = frame["ct"] as? String,
              let blob = Data(base64Encoded: ct) else { return nil }
        let kind = frame["t"] as? String == "del" ? "del" : frame["kind"] as? String ?? ""
        guard let plain = SyncCrypto.open(Self.v2Keys!.roomKey, blob, aad: SyncCrypto.aad(id: id, v: v, kind: kind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let pub = inner["pub"] as? String, let sig = inner["sig"] as? String else { return nil }
        let content = inner["c"] as? String ?? ""
        guard SyncSigning.verify(pub, SyncSigning.objectMessage(id, v, kind, by, content), sig) else { return nil }
        return inner
    }

    func testOutboundIdsAreUppercaseAndInboundRecordsAreNotEchoed() throws {
        connectV2()
        let local = try harness.addLocalWaypoint("iOS v2")
        harness.pump(300)
        let put = try XCTUnwrap(harness.socket.sent(type: "put").last)
        // uuidString like 2.x iOS, AAD and sig over the same id, embedded id lowercase
        XCTAssertEqual(put["id"] as? String, local.id.uuidString)
        let inner = try XCTUnwrap(openOwn(put))
        let parsed = try JSONSerialization.jsonObject(with: Data((inner["c"] as? String ?? "").utf8)) as? [String: Any]
        let feature = (parsed?["features"] as? [[String: Any]])?.first
        XCTAssertEqual(feature?["id"] as? String, local.id.uuidString.lowercased())

        let android = Waypoint(name: "Android v2", latitude: -33.9, longitude: 151.3, layerID: DrawingLayer.legacyFallbackID)
        let lower = android.id.uuidString.lowercased()
        harness.socket.deliver(v2Put(id: lower, v: 50, by: "android-client-0001", waypoint: android, seed: androidSeed))
        harness.pump(300)
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == android.id })

        // a shipped iOS sender still uses uppercase, accept that too
        let shipped = Waypoint(name: "Old iOS", latitude: -33.95, longitude: 151.35, layerID: DrawingLayer.legacyFallbackID)
        harness.socket.deliver(v2Put(id: shipped.id.uuidString, v: 51, by: "OLD-IOS-CLIENT", waypoint: shipped,
                                     seed: Data(repeating: 0x0c, count: 32)))
        harness.pump(300)
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == shipped.id })

        XCTAssertTrue(harness.socket.sent(type: "del").isEmpty, "applying a remote record must never echo a delete")
        let echoes = harness.socket.sent(type: "put").filter {
            let id = ($0["id"] as? String ?? "").lowercased()
            return id == lower || id == shipped.id.uuidString.lowercased()
        }
        XCTAssertTrue(echoes.isEmpty, "remote records are not republished under another casing")

        // an edit of the Android-made object still goes out uppercase, and the
        // delete too, whoever created it
        var edited = android
        edited.name = "Edited on iOS"
        try harness.waypointStore.commitEdit(edited)
        harness.pump(300)
        let editPut = try XCTUnwrap(harness.socket.sent(type: "put").last)
        XCTAssertEqual(editPut["id"] as? String, android.id.uuidString)
        XCTAssertGreaterThan((editPut["v"] as? NSNumber)?.int64Value ?? 0, 51)
        XCTAssertNotNil(openOwn(editPut))
        try harness.waypointStore.deleteDurably(edited)
        harness.pump(300)
        let del = try XCTUnwrap(harness.socket.sent(type: "del").last)
        XCTAssertEqual(del["id"] as? String, android.id.uuidString)
        XCTAssertNotNil(openOwn(del))
        XCTAssertEqual(harness.socket.sent(type: "del").count, 1)
    }

    private func ackV2(_ frame: [String: Any]) {
        let ct = frame["ct"] as! String
        harness.socket.deliver([
            "t": "op-ack", "av": 1, "rid": frame["rid"]!, "by": frame["by"]!, "id": frame["id"]!,
            "v": frame["v"]!, "kind": frame["kind"] ?? "del",
            "cth": SyncIdentity.urlB64Encode(SyncIdentity.sha256(Data(ct.utf8)))
        ])
        harness.pump()
    }

    /// Our del was still unacked when the socket dropped. The relay kept it
    /// under the uppercase id and the reconnect snapshot has to confirm it as
    /// is, not send it again.
    func testUnackedDeleteIsConfirmedByItsUppercaseTombstoneOnReconnect() throws {
        connectV2()
        let local = try harness.addLocalWaypoint("Gone soon")
        harness.pump(300)
        ackV2(try XCTUnwrap(harness.socket.sent(type: "put").last))
        try harness.waypointStore.deleteDurably(local)
        harness.pump(300)
        let del = try XCTUnwrap(harness.socket.sent(type: "del").last)
        XCTAssertEqual(del["id"] as? String, local.id.uuidString)

        harness.socket.remoteClose(code: 1006)
        harness.pump()
        harness.advanceUntilNewSocket()
        let tombstone: [String: Any] = [
            "id": del["id"]!, "v": del["v"]!, "by": del["by"]!, "kind": "del", "ct": del["ct"]!, "deleted": true
        ]
        harness.socket.deliver(["t": "snapshot-begin", "seq": 2])
        harness.socket.deliver(["t": "snapshot", "items": [tombstone], "more": false])
        harness.socket.deliver(["t": "snapshot-end", "seq": 2])
        harness.pump(300)
        XCTAssertEqual(harness.manager.status, .connected)
        XCTAssertTrue(harness.sentMutations().isEmpty, "the relay already has our delete")
    }

    /// The journal is keyed uppercase, v2 suppression lowercase. A too-big v2
    /// object used to stay unpublished for the rest of the join even after
    /// the user shrank it.
    func testTooLargeV2ObjectIsPublishedOnceTheUserShrinksIt() throws {
        connectV2()
        var coordinates: [Coordinate2D] = []
        for i in 0..<16_000 {
            coordinates.append(Coordinate2D(latitude: -33.0 - Double(i) * 0.000012345678,
                                            longitude: 151.0 + Double(i) * 0.000023456789))
        }
        var shape = DrawingShape(kind: .freedraw, coordinates: coordinates, layerID: DrawingLayer.legacyFallbackID)
        _ = try harness.drawingStore.addDurably(shape)
        harness.pump(300)
        XCTAssertTrue(harness.socket.sent(type: "put").isEmpty)
        XCTAssertEqual(harness.manager.surfacedIssueLog.filter { $0 == .objectTooLarge }.count, 1)

        shape.coordinates = Array(coordinates.prefix(20))
        _ = try harness.drawingStore.commitEdit(shape)
        harness.pump(300)
        let put = try XCTUnwrap(harness.socket.sent(type: "put").last)
        XCTAssertEqual(put["id"] as? String, shape.id.uuidString)
    }

    func testEqualVersionTieGoesToTheHigherWriterLikeTheRelay() throws {
        connectV2()
        let local = try harness.addLocalWaypoint("Mine")
        harness.pump(300)
        let put = try XCTUnwrap(harness.socket.sent(type: "put").last)
        let v = (put["v"] as! NSNumber).int64Value
        let wireId = put["id"] as! String
        var theirs = local
        theirs.name = "Winner"
        harness.socket.deliver(v2Put(id: wireId, v: v,
                                     by: "ffffffff-ffff-4fff-bfff-ffffffffffff", waypoint: theirs, seed: androidSeed))
        harness.pump(300)
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == local.id }?.name, "Winner")

        // and a lower writer at the same v loses
        var loser = local
        loser.name = "Loser"
        harness.socket.deliver(v2Put(id: wireId, v: v,
                                     by: "00000000-0000-4000-8000-000000000000", waypoint: loser,
                                     seed: Data(repeating: 0x0d, count: 32)))
        harness.pump(300)
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == local.id }?.name, "Winner")
    }
}

/// Relay v2 storage as the Durable Object keeps it: one record per raw id,
/// case sensitive, higher v wins, equal v goes to the higher by, and the
/// snapshot comes out in storage.list order.
private final class LegacyV2RelayModel {
    private(set) var records: [String: [String: Any]] = [:]

    /// The stored record, nil when the relay drops it as stale.
    func store(_ frame: [String: Any]) -> [String: Any]? {
        guard let id = frame["id"] as? String, let v = (frame["v"] as? NSNumber)?.int64Value,
              let by = frame["by"] as? String, let ct = frame["ct"] as? String else { return nil }
        if let existing = records[id], let ev = (existing["v"] as? NSNumber)?.int64Value,
           let eby = existing["by"] as? String {
            let newer = v > ev || (v == ev && Array(eby.utf8).lexicographicallyPrecedes(Array(by.utf8)))
            guard newer else { return nil }
        }
        let deleted = frame["t"] as? String == "del"
        let record: [String: Any] = [
            "id": id, "v": v, "by": by, "kind": deleted ? "del" : frame["kind"] as? String ?? "",
            "ct": ct, "deleted": deleted
        ]
        records[id] = record
        return record
    }

    func snapshot() -> [[String: Any]] {
        records.keys
            .sorted { Array(("obj:" + $0).utf8).lexicographicallyPrecedes(Array(("obj:" + $1).utf8)) }
            .compactMap { records[$0] }
    }

    static func live(_ record: [String: Any]) -> [String: Any] {
        var frame = record
        frame["t"] = record["deleted"] as? Bool == true ? "del" : "put"
        frame.removeValue(forKey: "deleted")
        return frame
    }

    var liveIds: Set<String> { Set(records.filter { $0.value["deleted"] as? Bool != true }.keys) }
    var deletedIds: Set<String> { Set(records.filter { $0.value["deleted"] as? Bool == true }.keys) }
}

/// Shipped 2.x iOS v2 sync, ids and versions only (fc6261c SyncManager.swift
/// applyRecord :3157, applyDelete :3229, syncLocalState :2070, reexport :3312).
/// Crypto is the real thing, over the raw id like 2.x did. Acks are assumed,
/// they leave lastContent at what was sent.
private final class Shipped2xIOSModel {
    let roomKey: SymmetricKey
    let seed: Data
    let by: String
    var objects: [UUID: String] = [:]
    private var versions: [String: Int64] = [:]
    private var lastContent: [String: String] = [:]
    private var clock: Int64 = 0
    private var peerKeys: [String: String] = [:]
    private(set) var rejected: [String] = []
    private(set) var sent: [[String: Any]] = []

    init(roomKey: SymmetricKey, seed: Data, by: String) {
        self.roomKey = roomKey
        self.seed = seed
        self.by = by
    }

    /// 2.x matched $0.id.uuidString == id, so a lowercase id re-exports as ""
    private func reexport(_ id: String) -> String {
        guard let uuid = UUID(uuidString: id), uuid.uuidString == id else { return "" }
        return objects[uuid] ?? ""
    }

    private func open(_ record: [String: Any], kind: String) -> (v: Int64, inner: [String: Any])? {
        guard let id = record["id"] as? String, let v = (record["v"] as? NSNumber)?.int64Value,
              let ct = record["ct"] as? String, let blob = Data(base64Encoded: ct),
              let plain = SyncCrypto.open(roomKey, blob, aad: SyncCrypto.aad(id: id, v: v, kind: kind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return nil }
        return (v, inner)
    }

    private func verified(_ record: [String: Any], kind: String, v: Int64, inner: [String: Any]) -> Bool {
        guard let id = record["id"] as? String, let writer = record["by"] as? String,
              let pub = inner["pub"] as? String, let sig = inner["sig"] as? String else { return false }
        if let pinned = peerKeys[writer], pinned != pub { return false }
        peerKeys[writer] = pub
        let content = inner["c"] as? String ?? ""
        return SyncSigning.verify(pub, SyncSigning.objectMessage(id, v, kind, writer, content), sig)
    }

    func apply(_ record: [String: Any]) {
        guard let id = record["id"] as? String, let v = (record["v"] as? NSNumber)?.int64Value else { return }
        if let known = versions[id], known >= v { return }
        let isDelete = record["deleted"] as? Bool == true || record["t"] as? String == "del"
        let kind = isDelete ? "del" : record["kind"] as? String ?? ""
        guard let opened = open(record, kind: kind), verified(record, kind: kind, v: v, inner: opened.inner) else {
            rejected.append(id)
            return
        }
        // the committers go by the parsed UUID, case does not matter there
        guard let uuid = UUID(uuidString: id) else { return }
        clock = max(clock, v)
        versions[id] = v
        if isDelete {
            objects.removeValue(forKey: uuid)
            lastContent[id] = nil
        } else {
            objects[uuid] = opened.inner["c"] as? String ?? ""
            lastContent[id] = reexport(id)
        }
    }

    /// The 250 ms debounced diff, current keyed by uuidString.
    func syncLocalState() -> [[String: Any]] {
        var out: [[String: Any]] = []
        var current: [String: String] = [:]
        for (uuid, content) in objects { current[uuid.uuidString] = content }
        for id in current.keys.sorted() where lastContent[id] != current[id] {
            clock += 1
            versions[id] = clock
            out.append(frame("put", id: id, v: clock, content: current[id]!))
            lastContent[id] = current[id]
        }
        for id in lastContent.keys.sorted() where current[id] == nil {
            clock += 1
            out.append(frame("del", id: id, v: clock, content: ""))
            lastContent[id] = nil
        }
        sent += out
        return out
    }

    /// 2.x cleared its v2 baselines on every connect, then took the snapshot.
    func reconnect(snapshot: [[String: Any]]) -> [[String: Any]] {
        versions.removeAll()
        lastContent.removeAll()
        snapshot.forEach(apply)
        return syncLocalState()
    }

    private func frame(_ t: String, id: String, v: Int64, content: String) -> [String: Any] {
        var out = signedFrame(id: id, v: v, content: t == "put" ? content : nil)
        out["rid"] = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        return out
    }

    /// One signed, sealed v2 frame under exactly this id, a del when content is nil.
    func signedFrame(id: String, v: Int64, content: String?) -> [String: Any] {
        let kind = content == nil ? "del" : "waypoint"
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, kind, by, content ?? ""))!
        var inner: [String: Any] = ["pub": SyncSigning.publicKey(seed)!, "sig": sig]
        if let content { inner["c"] = content }
        let data = try! JSONSerialization.data(withJSONObject: inner)
        let sealed = SyncCrypto.seal(roomKey, data, aad: SyncCrypto.aad(id: id, v: v, kind: kind))!
        return ["t": content == nil ? "del" : "put", "id": id, "v": v, "by": by, "kind": kind,
                "ct": sealed.base64EncodedString()]
    }
}

/// gap-v2-room-2x-interop-1: the real manager and a shipped 2.x iOS model
/// share a '2:' room through the relay model. 3.0.0 sent lowercase ids,
/// 2.x re-exported them as "", echoed put UPPER plus del lower, and the
/// object went for everyone.
@MainActor
final class SyncLegacyV2ShippedIOSInteropTests: XCTestCase {
    private var harness: SyncManagerHarness!
    private static let code = "sp2-v2-interop-room-01"
    private static var v2Keys: SyncCrypto.RoomKeys?
    private var relay: LegacyV2RelayModel!
    private var old: Shipped2xIOSModel!
    private var oldOnline = true
    private var routed = 0
    private var snapshotSeq: Int64 = 0
    private var ourMutations: [[String: Any]] = []

    override func setUp() async throws {
        if Self.v2Keys == nil { Self.v2Keys = SyncCrypto.deriveRoom(Self.code) }
        harness = try SyncManagerHarness(joinCode: "2:" + Self.code)
        relay = LegacyV2RelayModel()
        old = Shipped2xIOSModel(roomKey: Self.v2Keys!.roomKey, seed: Data(repeating: 0x0c, count: 32),
                                by: "OLD-2X-IOS-CLIENT")
    }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
    }

    private func content(_ waypoint: Waypoint) -> String {
        try! GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: [])
    }

    private func name(in content: String?) -> String? {
        guard let content else { return nil }
        return (try? GeoJSONImporter.parse(Data(content.utf8), existingLayers: [],
                                           fallbackLayerID: DrawingLayer.legacyFallbackID))?.waypoints.first?.name
    }

    private func deliverSnapshot(_ items: [[String: Any]]) {
        snapshotSeq += 1
        harness.socket.deliver(["t": "snapshot-begin", "seq": snapshotSeq])
        harness.socket.deliver(["t": "snapshot", "items": items, "more": false])
        harness.socket.deliver(["t": "snapshot-end", "seq": snapshotSeq])
        harness.pump()
        XCTAssertEqual(harness.manager.status, .connected)
    }

    private func joinManager() {
        harness.join()
        routed = 0
        deliverSnapshot(relay.snapshot())
    }

    /// A fresh socket and a fresh snapshot, what a relaunch or rejoin sees.
    private func reconnectManager() {
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        harness.advanceUntilNewSocket()
        routed = 0
        deliverSnapshot(relay.snapshot())
    }

    private func reconnectOld() {
        oldOnline = true
        forwardFromOld(old.reconnect(snapshot: relay.snapshot()))
    }

    private func forwardFromOld(_ frames: [[String: Any]]) {
        for frame in frames {
            guard let record = relay.store(frame) else { continue }
            harness.socket.deliver(LegacyV2RelayModel.live(record))
        }
    }

    /// Moves frames both ways until neither side has anything left to send.
    private func settle() {
        for _ in 0..<12 {
            harness.pump(300)
            var moved = false
            let frames = harness.socket.sentObjects()
            for frame in frames.dropFirst(routed) {
                let t = frame["t"] as? String
                guard t == "put" || t == "del" else { continue }
                moved = true
                ourMutations.append(frame)
                if let record = relay.store(frame) {
                    harness.socket.deliver([
                        "t": "op-ack", "av": 1, "rid": frame["rid"]!, "by": frame["by"]!, "id": frame["id"]!,
                        "v": frame["v"]!, "kind": record["kind"]!,
                        "cth": SyncIdentity.urlB64Encode(SyncIdentity.sha256(Data((frame["ct"] as! String).utf8)))
                    ])
                    if oldOnline { old.apply(LegacyV2RelayModel.live(record)) }
                } else {
                    harness.socket.deliver(["t": "op-nack", "av": 1, "rid": frame["rid"]!, "by": frame["by"]!,
                                            "code": "stale", "retry": false])
                }
            }
            routed = frames.count
            if oldOnline {
                let echoes = old.syncLocalState()
                if !echoes.isEmpty { moved = true }
                forwardFromOld(echoes)
            }
            harness.pump(300)
            if !moved { return }
        }
        XCTFail("the room never went quiet")
    }

    private func ourIds(_ t: String) -> [String] {
        ourMutations.filter { $0["t"] as? String == t }.compactMap { $0["id"] as? String }
    }

    func testCreateByThisIOSSurvivesAShipped2xIOSPeer() throws {
        joinManager()
        let local = try harness.addLocalWaypoint("Made on 3.0.1")
        settle()

        XCTAssertEqual(ourIds("put"), [local.id.uuidString])
        XCTAssertNotNil(old.objects[local.id], "2.x got it")
        XCTAssertTrue(old.sent.isEmpty, "2.x must not echo anything back")
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == local.id }, "and we kept it")
        XCTAssertEqual(relay.liveIds, [local.id.uuidString])
        XCTAssertTrue(relay.deletedIds.isEmpty)

        // the 2.x device reconnects, then this one does: still there everywhere
        reconnectOld()
        settle()
        reconnectManager()
        settle()
        XCTAssertNotNil(old.objects[local.id])
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == local.id })
        XCTAssertTrue(old.sent.isEmpty)
        XCTAssertTrue(ourIds("del").isEmpty)
        XCTAssertTrue(relay.deletedIds.isEmpty)
        XCTAssertTrue(old.rejected.isEmpty)
    }

    func testEditOfAShipped2xObjectSurvivesTheShippedPeer() throws {
        joinManager()
        let made = Waypoint(name: "Made on 2.x", latitude: -33.9, longitude: 151.3, layerID: DrawingLayer.legacyFallbackID)
        old.objects[made.id] = content(made)
        settle()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == made.id }?.name, "Made on 2.x")
        let baseline = old.sent.count

        var edited = try XCTUnwrap(harness.waypointStore.waypoints.first { $0.id == made.id })
        edited.name = "Edited on 3.0.1"
        try harness.waypointStore.commitEdit(edited)
        settle()

        XCTAssertEqual(ourIds("put"), [made.id.uuidString])
        XCTAssertEqual(name(in: old.objects[made.id]), "Edited on 3.0.1")
        XCTAssertEqual(old.sent.count, baseline, "2.x must not echo the edit")
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == made.id }?.name, "Edited on 3.0.1")
        XCTAssertEqual(relay.liveIds, [made.id.uuidString])

        // 2.x reconnects and edits back, still one object under one id
        reconnectOld()
        settle()
        var back = edited
        back.name = "Edited on 2.x"
        old.objects[made.id] = content(back)
        settle()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == made.id }?.name, "Edited on 2.x")
        reconnectManager()
        settle()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == made.id }?.name, "Edited on 2.x")
        XCTAssertEqual(name(in: old.objects[made.id]), "Edited on 2.x")
        XCTAssertTrue(ourIds("del").isEmpty)
        XCTAssertTrue(relay.deletedIds.isEmpty)
        XCTAssertTrue(old.rejected.isEmpty)
    }

    func testDeleteReachesTheShipped2xPeerWithoutAnEcho() throws {
        joinManager()
        let theirs = Waypoint(name: "2.x", latitude: -33.9, longitude: 151.3, layerID: DrawingLayer.legacyFallbackID)
        old.objects[theirs.id] = content(theirs)
        let mine = try harness.addLocalWaypoint("3.0.1")
        settle()
        XCTAssertNotNil(old.objects[mine.id])
        XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == theirs.id })
        let baseline = old.sent.count

        for id in [theirs.id, mine.id] {
            let waypoint = try XCTUnwrap(harness.waypointStore.waypoints.first { $0.id == id })
            try harness.waypointStore.deleteDurably(waypoint)
        }
        settle()

        XCTAssertEqual(Set(ourIds("del")), [theirs.id.uuidString, mine.id.uuidString])
        XCTAssertNil(old.objects[theirs.id])
        XCTAssertNil(old.objects[mine.id])
        XCTAssertEqual(old.sent.count, baseline, "2.x must not answer the deletes")
        XCTAssertEqual(relay.deletedIds, [theirs.id.uuidString, mine.id.uuidString])
        XCTAssertTrue(relay.liveIds.isEmpty)

        // nothing comes back on either reconnect
        reconnectOld()
        settle()
        reconnectManager()
        settle()
        XCTAssertTrue(old.objects.isEmpty)
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == theirs.id || $0.id == mine.id })
        XCTAssertEqual(old.sent.count, baseline)
        XCTAssertTrue(old.rejected.isEmpty)
    }

    /// 3.0.0 wrote every v2 record lowercase. Its per-id maps were memory
    /// only, so 3.0.1 starts from the relay: nothing may be re-sent or
    /// deleted because of the casing, and the next edit goes out uppercase
    /// above every old v.
    func testUpgradeFrom300LowercaseRelayStateResendsAndDeletesNothing() throws {
        let mine = Waypoint(name: "Mine on 3.0.0", latitude: -33.8, longitude: 151.1, layerID: DrawingLayer.legacyFallbackID)
        let mate = Waypoint(name: "Mate on 3.0.0", latitude: -33.81, longitude: 151.11, layerID: DrawingLayer.legacyFallbackID)
        let shipped = Waypoint(name: "2.x", latitude: -33.82, longitude: 151.12, layerID: DrawingLayer.legacyFallbackID)
        let gone = Waypoint(name: "Deleted on 3.0.0", latitude: -33.83, longitude: 151.13, layerID: DrawingLayer.legacyFallbackID)
        for waypoint in [mine, mate, shipped] { _ = try harness.waypointStore.addDurably(waypoint) }
        oldOnline = false

        harness.join()
        routed = 0
        // our own 3.0.0 records are signed with this install's identity
        let sealedSeed = try XCTUnwrap(harness.defaults.data(forKey: "sync.deviceSeed"))
        let seed = try XCTUnwrap(SealedEnvelope.openFile(key: SyncManagerHarness.testKey, blob: sealedSeed,
                                                         label: "sync/deviceSeed"))
        let clientId = try XCTUnwrap(harness.defaults.string(forKey: "sync.clientId"))
        let me = Shipped2xIOSModel(roomKey: Self.v2Keys!.roomKey, seed: seed, by: clientId)
        let teammate = Shipped2xIOSModel(roomKey: Self.v2Keys!.roomKey, seed: Data(repeating: 0x0e, count: 32),
                                         by: "MATE-3-0-0-CLIENT")
        // 3.0.0-shaped records: lowercase ids, AAD and sig over them
        _ = relay.store(me.signedFrame(id: mine.id.uuidString.lowercased(), v: 3, content: content(mine)))
        _ = relay.store(teammate.signedFrame(id: mate.id.uuidString.lowercased(), v: 4, content: content(mate)))
        _ = relay.store(me.signedFrame(id: gone.id.uuidString.lowercased(), v: 5, content: nil))
        _ = relay.store(old.signedFrame(id: shipped.id.uuidString, v: 2, content: content(shipped)))
        deliverSnapshot(relay.snapshot())
        settle()

        XCTAssertTrue(ourMutations.isEmpty, "a 3.0.0 room must not be re-sent or deleted: \(ourMutations)")
        for waypoint in [mine, mate, shipped] {
            XCTAssertTrue(harness.waypointStore.waypoints.contains { $0.id == waypoint.id })
        }
        XCTAssertFalse(harness.waypointStore.waypoints.contains { $0.id == gone.id })

        var edited = mine
        edited.name = "Mine on 3.0.1"
        try harness.waypointStore.commitEdit(edited)
        settle()
        XCTAssertEqual(ourIds("put"), [mine.id.uuidString])
        XCTAssertGreaterThan((ourMutations.last?["v"] as? NSNumber)?.int64Value ?? 0, 5)
        XCTAssertTrue(ourIds("del").isEmpty)
        // the old lowercase record is left alone, the uppercase one supersedes it
        XCTAssertEqual((relay.records[mine.id.uuidString.lowercased()]?["v"] as? NSNumber)?.int64Value, 3)
        XCTAssertEqual(relay.liveIds, [mine.id.uuidString.lowercased(), mine.id.uuidString,
                                       mate.id.uuidString.lowercased(), shipped.id.uuidString])
    }
}
