import XCTest
import CoreLocation
@testable import TacticalMaps

/// Contract tables from testdata/sync_client_behaviour.json driven through
/// the real SyncManager (transport seam, S6-02), not just the pure policy
/// units. Close codes and upgrade statuses used to be thrown away (S2-11),
/// so every row gets a real socket that dies the way the row says.
@MainActor
final class SyncManagerContractTableTests: XCTestCase {

    private static func loadContract() -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let file = dir.appendingPathComponent("testdata/sync_client_behaviour.json")
            if let data = try? Data(contentsOf: file),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return object
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/sync_client_behaviour.json")
        return [:]
    }

    private lazy var contract: [String: Any] = Self.loadContract()
    private func section(_ key: String) -> [String: Any] { contract[key] as? [String: Any] ?? [:] }

    private func scenario(_ id: String) -> [String: Any] {
        let all = contract["scenarios"] as? [[String: Any]] ?? []
        guard let found = all.first(where: { $0["id"] as? String == id }) else {
            XCTFail("missing scenario \(id)")
            return [:]
        }
        return found
    }

    private func delay(_ harness: SyncManagerHarness) -> Int64? {
        harness.manager.reconnectDueMs.map { $0 - harness.scheduler.nowMs }
    }

    /// random = 1 at attempt 0: floor + min(cap - floor, spread). Makes every
    /// class land on its own distinct number.
    private func firstDelayAtRandomOne(_ name: String) -> Int64 {
        let p = SyncBackoffPolicy.params(SyncBackoffClass(rawValue: name)!)
        return Int64(p.floorMs + min(p.capMs - p.floorMs, p.spreadMs))
    }

    private func newHarness(_ code: String = "3:sp2-contract-table-room") throws -> SyncManagerHarness {
        let harness = try SyncManagerHarness(joinCode: code)
        harness.randomValue = 1.0
        return harness
    }

    // MARK: close codes (7.2)

    private func closeCode(for key: String) -> Int? {
        switch key {
        case "other_1xxx": return 1014
        case "other_4xxx": return 4321
        default: return Int(key)
        }
    }

    private func checkRow(
        _ label: String, row: [String: Any], harness: SyncManagerHarness,
        failAgain: () -> Void
    ) {
        let manager = harness.manager
        let action = row["action"] as? String ?? ""
        switch action {
        case "stop":
            XCTAssertEqual(manager.pausedForAction?.issue.rawValue, row["issue"] as? String, label)
            XCTAssertEqual(manager.pausedForAction?.retryOnForeground, row["retryOnForeground"] as? Bool ?? false, label)
            XCTAssertNil(manager.reconnectDueMs, label)
            let sockets = harness.socketCount
            harness.pump(600_000)
            XCTAssertEqual(harness.socketCount, sockets, "\(label): a stop never reconnects by itself")
            let issue = SyncIssueCode(rawValue: row["issue"] as? String ?? "")
            XCTAssertEqual(manager.lastIssueKind, issue?.isSecurity == true ? .security : .connection, label)
        default:
            // reconnect, escalate_epoch_then_reconnect, none_if_we_sent_leave_else_reconnect
            XCTAssertNil(manager.pausedForAction, label)
            let cls = row["backoffClass"] as? String ?? "transient"
            XCTAssertEqual(delay(harness), firstDelayAtRandomOne(cls), "\(label): backoff class \(cls)")
            XCTAssertNotEqual(manager.lastIssueKind, .security, "\(label): availability, never SECURITY")
            if let issue = row["issue"] as? String, let after = (row["surfaceAfterConsecutive"] as? NSNumber)?.intValue {
                let code = SyncIssueCode(rawValue: issue)!
                if after > 1 {
                    XCTAssertFalse(manager.surfacedIssueLog.contains(code), "\(label): not on the first one")
                    for _ in 1..<after {
                        harness.advanceUntilNewSocket()
                        failAgain()
                        harness.pump()
                    }
                }
                XCTAssertEqual(manager.surfacedIssueLog.filter { $0 == code }.count, 1, "\(label): \(issue) after \(after)")
            }
        }
    }

    func testEveryCloseCodeRowThroughTheManager() throws {
        let rows = section("closeCodeActions")
        XCTAssertGreaterThanOrEqual(rows.count, 18)
        for (key, raw) in rows {
            guard let code = closeCode(for: key) else { XCTFail("row \(key)"); continue }
            let row = raw as? [String: Any] ?? [:]
            let harness = try newHarness()
            defer { harness.tearDown() }
            harness.join()
            harness.connect()
            XCTAssertEqual(harness.manager.status, .connected, key)
            harness.socket.remoteClose(code: code)
            harness.pump()
            checkRow("close \(key)", row: row, harness: harness) {
                harness.socket.open()
                harness.socket.remoteClose(code: code)
            }
        }
    }

    func testOurOwnLeaveIsTheOnlyQuiet1000() throws {
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        harness.connect()
        let socket = harness.socket
        harness.manager.leave()
        socket.remoteClose(code: 1000)
        harness.pump(600_000)
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertNil(harness.manager.reconnectDueMs)
        XCTAssertEqual(socket.cancelledWith, SyncLocalCloseAction.leave.closeCode)
    }

    // MARK: upgrade statuses (7.2)

    func testEveryUpgradeStatusRowThroughTheManager() throws {
        let rows = section("httpStatusActions")
        for (key, raw) in rows where key != "statusSource" {
            let status: Int?
            switch key {
            case "other_5xx": status = 502
            case "other_4xx": status = 400
            case "none": status = nil
            default: status = Int(key)
            }
            if key != "none", status == nil { XCTFail("row \(key)"); continue }
            let row = raw as? [String: Any] ?? [:]
            let harness = try newHarness()
            defer { harness.tearDown() }
            harness.join()
            harness.socket.upgradeFailed(httpStatus: status)
            harness.pump()
            checkRow("status \(key)", row: row, harness: harness) {
                harness.socket.upgradeFailed(httpStatus: status)
            }
        }
    }

    /// A 4008 means we outran the relay. The next socket's pacer starts half
    /// full so the catch-up publish cant trip it again straight away.
    func testAfter4008TheNextSessionStartsWithAHalfFullPacer() throws {
        func putsAtHelloAck(closing code: Int) throws -> Int {
            let harness = try newHarness()
            defer { harness.tearDown() }
            harness.join()
            harness.connect()
            harness.socket.remoteClose(code: code)
            harness.pump()
            // edits made while offline all go out after the next hello-ack
            for index in 0..<40 {
                _ = try harness.waypointStore.addDurably(Waypoint(
                    name: "Offline \(index)", latitude: -33.0 - Double(index) / 1000, longitude: 151.0,
                    layerID: DrawingLayer.legacyFallbackID))
            }
            harness.advanceUntilNewSocket()
            harness.connect(seq: 2)
            // what left at the hello-ack instant, before any refill
            return harness.sentMutations().count
        }
        let normal = try putsAtHelloAck(closing: 1006)
        let after4008 = try putsAtHelloAck(closing: 4008)
        XCTAssertGreaterThanOrEqual(normal, 15, "a fresh socket has a full bucket")
        XCTAssertLessThanOrEqual(after4008, 5, "after 4008 the bucket starts at 15 frames / 512 KiB")
        XCTAssertLessThan(after4008, normal)
    }

    // MARK: local closes (7.2, 9, 11.3, 12): classified by our reason, never the echo

    private func drive(_ name: String, _ h: SyncManagerHarness) -> (expectedWireCode: Int?, issue: SyncIssueCode?)? {
        switch name {
        case "leave", "lifecyclePause":
            return nil // checked separately, they do nothing
        case "receiveBudgetExceeded":
            h.join(); h.connect()
            let loc: [String: Any] = ["t": "loc", "by": h.peerActor, "ct": "AAAA", "pub": h.peerPub, "sd": h.peerSession, "vs": "x"]
            for _ in 0...SyncReceiveBudget.roomLimits(sessions: 0).frames { h.socket.deliver(loc) }
            h.pump()
            return (1008, nil)
        case "oversizedInbound":
            h.join(); h.connect()
            h.socket.deliverText(String(repeating: "a", count: SyncInboundFramePolicy.maxFrameBytes + 1))
            h.pump()
            return (1009, nil)
        case "binaryInbound":
            h.join(); h.connect()
            h.socket.deliverResult(.success(.data(Data([0xff, 0xfe, 0xfd]))))
            h.pump()
            return (1003, nil)
        case "connectOpenTimeout":
            h.join()
            h.pump(SyncHandshakeWatchdog.connectOpenTimeoutMs - 1)
            XCTAssertFalse(h.socket.isCancelled)
            h.pump(1)
            return (nil, nil)
        case "handshakeStall":
            h.join(); h.beginSnapshot()
            h.pump(SyncHandshakeWatchdog.handshakeStallTimeoutMs - 1)
            XCTAssertFalse(h.socket.isCancelled)
            h.pump(1)
            return (nil, nil)
        case "helloAckTimeout":
            h.join(); h.beginSnapshot(); h.page([]); h.endSnapshot()
            XCTAssertNotNil(h.lastHello)
            h.pump(SyncHandshakeWatchdog.helloAckTimeoutMs - 1)
            XCTAssertFalse(h.socket.isCancelled)
            h.pump(1)
            return (nil, nil)
        case "handshakeAbsolute":
            h.join(); h.beginSnapshot()
            let start = h.scheduler.nowMs
            // a page every 50 s keeps the stall watchdog happy forever
            while !h.socket.isCancelled, h.scheduler.nowMs - start < 1_000_000 {
                h.pump(50_000)
                if !h.socket.isCancelled { h.page([], more: true) }
            }
            XCTAssertEqual(h.scheduler.nowMs - start, SyncHandshakeWatchdog.handshakeAbsoluteMaxMs,
                           "only the absolute cap ends a progressing handshake")
            return (nil, nil)
        case "livenessTimeout":
            h.join(); h.connect()
            h.socket.autoPong = false
            h.pump(SyncHeartbeatPolicy.foregroundPingIntervalMs)
            XCTAssertEqual(h.socket.pings, 1)
            h.pump(SyncHeartbeatPolicy.deadAfterMsWithoutPongOrFrame - 1)
            XCTAssertFalse(h.socket.isCancelled)
            h.pump(1)
            return (nil, nil)
        case "ackExhausted":
            h.join(); h.connect()
            _ = try? h.addLocalWaypoint("Never acked")
            h.pump(250)
            var guardCount = 0
            while !h.socket.isCancelled, guardCount < 200 { h.pump(1_000); guardCount += 1 }
            XCTAssertEqual(h.sentMutations().count, SyncAckTimer.maxAttempts)
            return (nil, .unconfirmedReconnect)
        case "structuralSnapshot":
            h.join(); h.beginSnapshot()
            let item: [String: Any] = ["id": SyncIdentity.urlB64Encode(Data(repeating: 4, count: 32))]
            h.page([item, item])
            return (nil, .snapshotStructural)
        case "liveWindowResync", "sessionNack", "helloRequiredNack":
            return nil // own tests below, they differ in what comes next
        default:
            XCTFail("no driver for local close \(name)")
            return nil
        }
    }

    func testEveryLocalCloseRowReconnectsByItsOwnReason() throws {
        let rows = section("localCloseActions")
        XCTAssertGreaterThanOrEqual(rows.count, 15)
        for (name, raw) in rows {
            let row = raw as? [String: Any] ?? [:]
            let harness = try newHarness()
            defer { harness.tearDown() }
            guard let outcome = drive(name, harness) else { continue }
            let socket = harness.socket
            XCTAssertTrue(socket.isCancelled, "\(name): we close it")
            if let wire = outcome.expectedWireCode ?? (row["closeCode"] as? NSNumber)?.intValue {
                XCTAssertEqual(socket.cancelledWith, wire, "\(name): close code on the wire")
            }
            XCTAssertEqual(row["action"] as? String, "reconnect", name)
            // most of our own closes go out as 1011, a manager that read the
            // echo would back off 60 s as slow_storage and call the relay busy
            XCTAssertEqual(delay(harness), firstDelayAtRandomOne(row["backoffClass"] as? String ?? "transient"),
                           "\(name): classified by our reason, not the transport echo")
            XCTAssertFalse(harness.manager.surfacedIssueLog.contains(.relayBusy), name)
            XCTAssertNil(harness.manager.pausedForAction, name)
            if let issue = outcome.issue {
                XCTAssertTrue(harness.manager.surfacedIssueLog.contains(issue), "\(name): \(issue)")
                XCTAssertEqual(row["issue"] as? String, issue.rawValue, name)
            }
            XCTAssertEqual(harness.manager.lastIssueKind,
                           outcome.issue?.isSecurity == true ? .security : .connection, name)
            harness.advanceUntilNewSocket()
            XCTAssertEqual(harness.socketCount, 2, name)
        }
    }

    func testLifecyclePauseClosesWithoutAReconnect() throws {
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        harness.connect()
        harness.manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: false, backgroundInterval: 900)
        harness.pump(600_000)
        XCTAssertTrue(harness.socket.isCancelled)
        XCTAssertEqual(harness.socketCount, 1)
        XCTAssertNil(harness.manager.reconnectDueMs)
        XCTAssertNil(harness.manager.lastError)
    }

    func testLiveWindowResyncIsNotAFailure() throws {
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        let far = Waypoint(name: "Far ahead", latitude: -33.8, longitude: 151.2, layerID: DrawingLayer.legacyFallbackID)
        harness.socket.deliver(harness.peerWaypointPut(far, counter: 20_001, live: true))
        harness.pump()
        XCTAssertEqual(harness.factory.sockets[0].cancelledWith, 1000, "clean close")
        XCTAssertEqual(harness.socketCount, 2, "reconnect_now, no backoff")
        // the attempt counter never moved: the next real failure is still attempt 0
        harness.socket.upgradeFailed(httpStatus: nil)
        harness.pump()
        XCTAssertEqual(delay(harness), firstDelayAtRandomOne("transient"))
    }

    func testHelloRequiredNackReconnectsWithTransientBackoff() throws {
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("Needs hello")
        harness.pump(250)
        harness.nack(try XCTUnwrap(harness.sentMutations().last), code: "hello-required", retry: true)
        XCTAssertTrue(harness.socket.isCancelled)
        XCTAssertEqual(delay(harness), firstDelayAtRandomOne("transient"))
        XCTAssertNil(harness.manager.pausedForAction)
        XCTAssertNotEqual(harness.manager.lastIssueKind, .security)
    }

    func testSessionMismatchNackCountsAsASessionConflict() throws {
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        for round in 0..<3 {
            harness.connect(seq: Int64(round + 1))
            _ = try harness.addLocalWaypoint("Fight \(round)")
            harness.pump(250)
            harness.nack(try XCTUnwrap(harness.sentMutations().last), code: "session-mismatch", retry: false)
            if round < 2 {
                // no op-ack ever lands, so the attempt counter keeps climbing
                XCTAssertEqual(delay(harness),
                               Int64(SyncBackoffPolicy.delayMs(.transient, attempt: round, random: 1)), "round \(round)")
                harness.advanceUntilNewSocket()
            }
        }
        XCTAssertEqual(harness.manager.pausedForAction?.issue, .sessionConflict)
        XCTAssertEqual(harness.manager.pausedForAction?.retryOnForeground, true)
    }

    // MARK: manager-level scripts from the fixture (23.3)

    func testStaleNackNoReconnectScript() throws {
        let steps = scenario("stale_nack_no_reconnect")["steps"] as? [[String: Any]] ?? []
        XCTAssertEqual(steps.map { $0["event"] as? String ?? "" }, ["connected", "localEdit", "inbound", "inbound"])
        let expect = steps[3]["expect"] as? [String: Any] ?? [:]
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        harness.connect()
        let local = try harness.addLocalWaypoint("A content")
        harness.pump(250)
        let ourPut = try XCTUnwrap(harness.sentMutations().last)
        let ours = try XCTUnwrap(VersionStamp.parse(ourPut["vs"] as! String))
        // B: same counter, higher actor (or one more when B sorts lower)
        let counter = harness.peerActor > ours.actorId ? ours.counter : ours.counter + 1
        var theirs = local
        theirs.name = "B content"
        harness.socket.deliver(harness.peerWaypointPut(theirs, counter: counter, live: true))
        harness.pump()
        XCTAssertEqual(harness.waypointStore.waypoints.first { $0.id == local.id }?.name,
                       (steps[2]["expect"] as? [String: Any])?["modelEquals"] as? String)
        harness.nack(ourPut, code: "stale", retry: false)
        harness.pump(120_000)
        XCTAssertEqual(expect["status"] as? String, "CONNECTED")
        XCTAssertEqual(harness.manager.status, .connected)
        XCTAssertEqual(Int64(harness.socketCount - 1), (expect["newSockets"] as? NSNumber)?.int64Value)
        XCTAssertTrue(expect["issue"] is NSNull)
        XCTAssertNil(harness.manager.lastError)
        XCTAssertEqual(expect["opResolved"] as? Bool, true)
        let wire = harness.wireId(local.id)
        let republished = harness.sentMutations().filter { $0["id"] as? String == wire }.count - 1
        XCTAssertEqual(Int64(republished), (expect["republishesOfX"] as? NSNumber)?.int64Value)
    }

    func testCounterWindowScriptKeepsPresenceGoing() throws {
        let steps = scenario("counter_window_pauses_mutations_once")["steps"] as? [[String: Any]] ?? []
        XCTAssertEqual(steps.map { $0["event"] as? String ?? "" }, ["inbound", "localEdit", "presenceDue", "reconnect"])
        let location = LocationService()
        location.authorisationStatus = .authorizedWhenInUse
        let harness = try SyncManagerHarness(joinCode: "3:sp2-contract-table-room", locationService: location)
        defer { harness.tearDown() }
        var config = harness.manager.presenceConfig
        config.shareLocation = true
        XCTAssertTrue(harness.manager.updatePresenceConfig(config))
        harness.join()
        harness.connect()
        _ = try harness.addLocalWaypoint("Before the reset")
        harness.pump(250)
        let put = try XCTUnwrap(harness.sentMutations().last)

        harness.nack(put, code: "counter-window", retry: false)
        let first = steps[0]["expect"] as? [String: Any] ?? [:]
        XCTAssertEqual(first["reconnect"] as? Bool, harness.socket.isCancelled)
        XCTAssertEqual(first["issue"] as? String, SyncIssueCode.roomResetChangesPaused.rawValue)
        let surfaced = { harness.manager.surfacedIssueLog.filter { $0 == .roomResetChangesPaused }.count }
        XCTAssertEqual(Int64(surfaced()), (first["surfacedCount"] as? NSNumber)?.int64Value)
        XCTAssertEqual(harness.manager.lastIssueKind, .connection)

        _ = try harness.addLocalWaypoint("After the reset")
        harness.pump(1_000)
        XCTAssertEqual(Int64(harness.sentMutations().count - 1),
                       ((steps[1]["expect"] as? [String: Any])?["putsSent"] as? NSNumber)?.int64Value)

        // presence still flows: only writes stopped
        let locsBefore = harness.socket.sent(type: "loc").count
        location.lastLocation = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: -33.86, longitude: 151.21), altitude: 10,
            horizontalAccuracy: 5, verticalAccuracy: 5, course: 0, speed: 0, timestamp: Date())
        harness.manager.locationDidUpdate()
        harness.pump()
        XCTAssertEqual(Int64(harness.socket.sent(type: "loc").count - locsBefore),
                       ((steps[2]["expect"] as? [String: Any])?["locSent"] as? NSNumber)?.int64Value)

        harness.socket.remoteClose(code: 1006)
        harness.advanceUntilNewSocket()
        harness.connect(seq: 2)
        harness.pump(1_000)
        XCTAssertTrue(harness.sentMutations().isEmpty, "still paused after a reconnect")
        XCTAssertEqual(Int64(surfaced()), ((steps[3]["expect"] as? [String: Any])?["surfacedCount"] as? NSNumber)?.int64Value)
    }

    func testSeqRegressionScript() throws {
        let steps = scenario("seq_regression_surfaced_once_per_join")["steps"] as? [[String: Any]] ?? []
        let begins = steps.filter { $0["event"] as? String == "snapshotBegin" }
        XCTAssertEqual(begins.count, 2)
        let harness = try newHarness()
        defer { harness.tearDown() }
        harness.join()
        let last = (begins[0]["lastSnapshotSeq"] as? NSNumber)?.int64Value ?? -1
        harness.connect(seq: last)
        for begin in begins {
            harness.socket.remoteClose(code: 1006)
            harness.advanceUntilNewSocket()
            harness.connect(seq: (begin["seq"] as? NSNumber)?.int64Value ?? -1)
            let expect = begin["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(expect["continue"] as? Bool, harness.manager.status == .connected)
            XCTAssertEqual(Int64(harness.manager.surfacedIssueLog.filter { $0 == .roomResetSuspected }.count),
                           (expect["surfacedCount"] as? NSNumber)?.int64Value)
            if let issue = expect["issue"] as? String {
                XCTAssertEqual(issue, SyncIssueCode.roomResetSuspected.rawValue)
                XCTAssertEqual(harness.manager.lastIssueKind, .security, "suspected rollback is a SECURITY notice")
            }
        }
    }
}
