import XCTest
@testable import TacticalMaps

/// Prove-It regressions for SP2 defects the older session tests did not pin
/// down yet. Each one drives the real SyncManager through the fake relay
/// socket and manual clock, and would fail against the pre-SP2 manager.
@MainActor
final class SyncManagerProveItTests: XCTestCase {
    private var harness: SyncManagerHarness!

    override func setUp() async throws {
        harness = try SyncManagerHarness(joinCode: "3:sp2-prove-it-room-0001")
        harness.randomValue = 1.0
    }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
    }

    private var manager: SyncManager { harness.manager }

    private func delay() -> Int64? {
        manager.reconnectDueMs.map { $0 - harness.scheduler.nowMs }
    }

    /// Fake relay loop: pump in small steps and ack every put/del 200 ms after
    /// it hit the socket. Returns every mutation frame with its send time.
    @discardableResult
    private func runRelay(on socket: FakeSyncSocket, forMs duration: Int64,
                          ackAfterMs: Int64 = 200) -> [(at: Int64, frame: [String: Any])] {
        var seen = socket.sent.count
        var unacked: [(at: Int64, frame: [String: Any])] = []
        var sent: [(at: Int64, frame: [String: Any])] = []
        let end = harness.scheduler.nowMs + duration
        func collect() {
            while seen < socket.sent.count {
                let text = socket.sent[seen]
                seen += 1
                guard let frame = (try? JSONSerialization.jsonObject(with: Data(text.utf8))) as? [String: Any],
                      let t = frame["t"] as? String, t == "put" || t == "del" else { continue }
                sent.append((harness.scheduler.nowMs, frame))
                unacked.append((harness.scheduler.nowMs, frame))
            }
        }
        collect()
        while harness.scheduler.nowMs < end, !socket.isCancelled {
            harness.pump(50)
            collect()
            while let first = unacked.first, harness.scheduler.nowMs - first.at >= ackAfterMs {
                unacked.removeFirst()
                harness.ack(first.frame, on: socket)
                collect()
            }
        }
        return sent
    }

    private func worstWindow(_ times: [Int64], windowMs: Int64 = 10_000) -> Int {
        var worst = 0
        var lower = 0
        for upper in times.indices {
            while times[upper] - times[lower] >= windowMs { lower += 1 }
            worst = max(worst, upper - lower + 1)
        }
        return worst
    }

    // MARK: S2-11 / 7.1 close code vs a lost didOpen race

    /// URLSession reports didOpen through the delegate queue and the receive
    /// failure through the task, both hop to main on their own. A relay that
    /// upgrades and closes 4013 straight away can beat the open callback, and
    /// then the close got read as a failed upgrade (status 101, transient
    /// loop) instead of room full.
    func testACloseCodeIsReadEvenWhenTheOpenCallbackLostTheRace() {
        harness.join()
        let socket = harness.socket
        socket.httpStatusCode = 101
        socket.remoteClose(code: 4013)
        harness.pump()
        XCTAssertEqual(manager.pausedForAction?.issue, .roomFullCannotJoin)
        XCTAssertNil(manager.reconnectDueMs)
        harness.pump(600_000)
        XCTAssertEqual(harness.socketCount, 1)
    }

    func testANonZeroCloseCodeAloneMeansTheSocketOpened() {
        harness.join()
        harness.socket.remoteClose(code: 4011)
        harness.pump()
        XCTAssertEqual(manager.pausedForAction?.issue, .identityRejected)
        XCTAssertEqual(manager.lastIssueKind, .security)
    }

    // MARK: S2-13 reachability while connected and between attempts

    func testLosingTheNetworkWhileConnectedProbesTheLinkAndDropsADeadOne() {
        harness.join()
        harness.connect()
        harness.path.emit(satisfied: true)
        harness.socket.autoPong = false
        // the hello-ack itself was the last sign of life, let a second pass
        harness.pump(1_000)
        harness.path.emit(satisfied: false)
        XCTAssertEqual(harness.socket.pings, 1, "network change starts a liveness probe")
        harness.pump(SyncHeartbeatPolicy.pathChangeProbeTimeoutMs - 1)
        XCTAssertFalse(harness.socket.isCancelled)
        harness.pump(1)
        XCTAssertTrue(harness.socket.isCancelled, "no pong in 5 s is transport loss, not a 30 s wait")
        XCTAssertEqual(delay(), 1_000)
        XCTAssertNotEqual(manager.lastIssueKind, .security)
    }

    func testANetworkSwapWithALiveLinkKeepsTheSession() {
        harness.join()
        harness.connect()
        harness.path.emit(satisfied: true, interfaces: ["wifi"])
        harness.path.emit(satisfied: true, interfaces: ["cellular"])
        XCTAssertEqual(harness.socket.pings, 1)
        harness.pump(10_000)
        XCTAssertFalse(harness.socket.isCancelled)
        XCTAssertEqual(harness.socketCount, 1)
    }

    func testAnInterfaceSwapPullsInAPendingTransientReconnect() {
        harness.join()
        harness.path.emit(satisfied: true, interfaces: ["wifi"])
        for _ in 0..<7 {
            harness.socket.upgradeFailed(httpStatus: nil)
            harness.pump()
            harness.advanceUntilNewSocket()
        }
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.pump()
        XCTAssertEqual(delay(), 30_000)
        let sockets = harness.socketCount
        harness.path.emit(satisfied: true, interfaces: ["cellular"])
        harness.pump(2_000)
        XCTAssertEqual(harness.socketCount, sockets + 1, "wifi -> cellular is worth an early try")
    }

    func testNetworkReturnNeverShortensASlowClassBackoff() {
        harness.join()
        harness.path.emit(satisfied: true)
        harness.socket.upgradeFailed(httpStatus: 429)
        harness.pump()
        let due = manager.reconnectDueMs
        XCTAssertEqual(delay(), 90_000)
        harness.path.emit(satisfied: false)
        harness.path.emit(satisfied: true)
        harness.pump(5_000)
        XCTAssertEqual(manager.reconnectDueMs, due, "slow_rate floors are the relay asking us to wait")
        XCTAssertEqual(harness.socketCount, 1)
    }

    // MARK: SP1 review: departed-author tombstone burst goes through the pacer

    func testRecoverableOwnTombstonesAfterHelloAckArePacedAndKeepTheirStamps() throws {
        let count = 500 // the SP1 review number: 500 unconfirmed own tombstones
        var waypoints: [Waypoint] = []
        for index in 0..<count {
            let waypoint = Waypoint(name: "Gone \(index)", latitude: -33.0 - Double(index) / 1000,
                                    longitude: 151.0, layerID: DrawingLayer.legacyFallbackID)
            _ = try harness.waypointStore.addDurably(waypoint)
            waypoints.append(waypoint)
        }
        harness.join()
        harness.connect()
        let first = harness.socket
        let puts = runRelay(on: first, forMs: 60_000)
        XCTAssertEqual(Set(puts.compactMap { $0.frame["id"] as? String }).count, count)

        for waypoint in waypoints { _ = try harness.waypointStore.deleteDurably(waypoint) }
        harness.pump(250)
        // every tombstone stamp is reserved now, only the first window left
        var reserved: [String: String] = [:]
        for del in first.sent(type: "del") { reserved[del["id"] as! String] = del["vs"] as? String }
        XCTAssertFalse(reserved.isEmpty)
        XCTAssertLessThanOrEqual(reserved.count, 32, "in-flight window with no acks")
        first.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        XCTAssertEqual(harness.socketCount, 2)

        // the relay compacted them: the snapshot no longer has our tombstones,
        // so they all get resent after hello-ack
        harness.connect(seq: 2)
        let second = harness.socket
        var maxUnacked = 0
        var times: [Int64] = []
        var dels: [String: String] = [:]
        var seen = second.sent.count
        var unacked: [(at: Int64, frame: [String: Any])] = []
        let start = harness.scheduler.nowMs
        func collect() {
            while seen < second.sent.count {
                let frame = (try? JSONSerialization.jsonObject(with: Data(second.sent[seen].utf8))) as? [String: Any] ?? [:]
                seen += 1
                times.append(harness.scheduler.nowMs)
                guard frame["t"] as? String == "del" else { continue }
                let id = frame["id"] as! String
                XCTAssertNil(dels[id], "each tombstone goes out once")
                dels[id] = frame["vs"] as? String
                unacked.append((harness.scheduler.nowMs, frame))
                maxUnacked = max(maxUnacked, unacked.count)
            }
        }
        collect()
        while harness.scheduler.nowMs - start < 90_000, !second.isCancelled {
            harness.pump(50)
            collect()
            while let head = unacked.first, harness.scheduler.nowMs - head.at >= 200 {
                unacked.removeFirst()
                harness.ack(head.frame, on: second)
                collect()
            }
        }
        XCTAssertFalse(second.isCancelled)
        XCTAssertEqual(dels.count, count, "every own tombstone the snapshot did not confirm is resent")
        XCTAssertLessThanOrEqual(worstWindow(times), 150, "never more than clientPacing in any 10 s")
        XCTAssertLessThanOrEqual(maxUnacked, 32)
        for (id, vs) in reserved {
            XCTAssertEqual(dels[id], vs, "a recovery resend keeps the stamp that was reserved")
        }
        XCTAssertTrue(second.sent(type: "put").isEmpty)
    }

    // MARK: S3-06 / S6-02 ack loss

    func testALostAckRetransmitsTheSameCopyThenTheLateAckSettlesIt() throws {
        harness.join()
        // two failed attempts first, so the op-ack reset below is visible
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.advanceUntilNewSocket()
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.advanceUntilNewSocket()
        harness.connect()
        _ = try harness.addLocalWaypoint("Ack went missing")
        harness.pump(250)
        let original = try XCTUnwrap(harness.sentMutations().last)
        harness.pump(SyncAckTimer.timeoutMs(attempt: 1, aheadInFlight: 0) - 1)
        XCTAssertEqual(harness.sentMutations().count, 1)
        harness.pump(1)
        let copies = harness.sentMutations()
        XCTAssertEqual(copies.count, 2)
        for key in ["rid", "vs", "ct", "id"] {
            XCTAssertEqual(copies[1][key] as? String, original[key] as? String, "retransmit is the same frame (\(key))")
        }
        harness.ack(copies[1])
        // stay under the 30 s stable mark so only the op-ack can reset backoff
        harness.pump(20_000)
        XCTAssertEqual(harness.sentMutations().count, 2)
        XCTAssertFalse(harness.socket.isCancelled)
        XCTAssertNil(manager.lastError)
        // that op-ack proved the session: attempt 2 is back to 0
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        XCTAssertEqual(delay(), 1_000)
    }

    // MARK: S3-15 same-stamp recovery resend nacked stale

    func testStaleOnOurOwnRecoveryResendIsConfirmedNotAReconnect() throws {
        harness.join()
        harness.connect()
        let local = try harness.addLocalWaypoint("Stored, ack lost")
        harness.pump(250)
        let first = try XCTUnwrap(harness.sentMutations().last)
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        // relay stored it but this snapshot does not show it (or was built
        // just before): the diff resends at the stamp it already reserved
        harness.connect(seq: 2)
        harness.pump(250)
        let resend = try XCTUnwrap(harness.sentMutations().last)
        XCTAssertEqual(resend["id"] as? String, harness.wireId(local.id))
        XCTAssertEqual(resend["vs"] as? String, first["vs"] as? String, "recovery reuses the reserved stamp")
        harness.nack(resend, code: "stale", retry: false)
        harness.pump(120_000)
        XCTAssertFalse(harness.socket.isCancelled, "S3-15: this used to cancel and re-download the room")
        XCTAssertEqual(harness.socketCount, 2)
        XCTAssertNil(manager.lastError)
        XCTAssertEqual(harness.sentMutations().count, 1, "confirmed, so nothing to send again")
        // still nothing on the next session either
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        harness.connect(seq: 3)
        harness.pump(1_000)
        XCTAssertTrue(harness.sentMutations().isEmpty)
    }

    // MARK: S3-11 / S2-11 stale epoch

    func testStaleEpochDoublesThreeTimesThenStopsWithSessionCounterBehind() throws {
        harness.join()
        var epochs: [UInt64] = []
        for round in 0..<4 {
            harness.beginSnapshot(seq: Int64(round + 1))
            harness.page([])
            harness.endSnapshot(seq: Int64(round + 1))
            let vs = try XCTUnwrap(harness.lastHello?["vs"] as? String)
            epochs.append(try XCTUnwrap(UInt64(vs.prefix(16), radix: 16)))
            harness.socket.remoteClose(code: 4014)
            harness.pump()
            if round < 3 {
                XCTAssertNil(manager.pausedForAction, "round \(round)")
                harness.advanceUntilNewSocket()
            }
        }
        XCTAssertEqual(epochs[1], epochs[0] * 2)
        XCTAssertEqual(epochs[2], epochs[1] * 2)
        XCTAssertEqual(epochs[3], epochs[2] * 2)
        XCTAssertEqual(manager.pausedForAction?.issue, .sessionCounterBehind)
        XCTAssertEqual(manager.pausedForAction?.retryOnForeground, false)
        let sockets = harness.socketCount
        harness.pump(600_000)
        XCTAssertEqual(harness.socketCount, sockets)
    }

    // MARK: S3-13 quota nack is said once per session

    func testQuotaNackSurfacesOncePerSession() throws {
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("One")
        _ = try harness.addLocalWaypoint("Two")
        harness.pump(250)
        let puts = harness.sentMutations()
        XCTAssertEqual(puts.count, 2)
        puts.forEach { harness.nack($0, code: "quota", retry: false) }
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .roomQuotaNack }.count, 1)
        XCTAssertFalse(harness.socket.isCancelled)
        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        harness.connect(seq: 2)
        harness.pump(1_000)
        XCTAssertTrue(harness.sentMutations().isEmpty, "both wait for a local edit")
        _ = try harness.addLocalWaypoint("Three")
        harness.pump(250)
        harness.nack(try XCTUnwrap(harness.sentMutations().last), code: "quota", retry: false)
        XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == .roomQuotaNack }.count, 2, "new session, new notice")
        XCTAssertEqual(manager.lastIssueKind, .connection)
    }

    // MARK: S3 verifier note 2: stable session resets backoff

    func testThirtySecondsConnectedIsAStableSession() {
        harness.join()
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.advanceUntilNewSocket()
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.advanceUntilNewSocket()
        harness.connect()
        harness.pump(SyncBackoffPolicy.stableSessionMs - 1)
        XCTAssertEqual(harness.socketCount, 3)
        harness.pump(1)
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        XCTAssertEqual(delay(), 1_000, "30 s connected counts as proven, attempt back to 0")
    }

    func testASessionThatDiesBeforeThirtySecondsKeepsBackingOff() {
        harness.join()
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.advanceUntilNewSocket()
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.advanceUntilNewSocket()
        harness.connect()
        harness.pump(SyncBackoffPolicy.stableSessionMs - 1)
        harness.socket.remoteClose(code: 1006)
        harness.pump()
        XCTAssertEqual(delay(), Int64(SyncBackoffPolicy.delayMs(.transient, attempt: 2, random: 1)))
    }

    // MARK: contract 1: receive loop only re-arms once a frame is queued

    func testAFullInboundQueueStopsReadingUntilTheDrainMakesRoom() {
        harness.join()
        harness.beginSnapshot()
        let socket = harness.socket
        let filler = "{\"t\":\"x-future-frame\"}"
        for _ in 0..<(SyncManager.inboundQueueMaxFrames - 1) { socket.deliverText(filler) }
        XCTAssertTrue(socket.hasPendingReceive, "room left, keep reading")
        socket.deliverText(filler)
        XCTAssertFalse(socket.hasPendingReceive, "queue full: no receive armed, the socket backs up")
        for _ in 0..<200 { socket.deliverText(filler) }
        harness.pump()
        XCTAssertTrue(socket.hasPendingReceive, "drained, reading again")
        XCTAssertFalse(socket.isCancelled)
        harness.page([])
        harness.endSnapshot()
        harness.ackHello()
        XCTAssertEqual(manager.status, .connected)
    }
}
