import XCTest
@testable import TacticalMaps

/// SP3 half of testdata/sync_client_behaviour.json: constants and every SP3
/// scenario against its iOS policy unit (contract sections 17, 20, 21).
/// Manager-level SP3 scripts live in SyncEfficiencyTests.
final class SyncClientBehaviourSP3Tests: XCTestCase {

    private static func load(_ name: String) -> [String: Any] {
        var dir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<8 {
            let file = dir.appendingPathComponent("testdata").appendingPathComponent(name)
            if let data = try? Data(contentsOf: file),
               let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                return object
            }
            dir = dir.deletingLastPathComponent()
        }
        XCTFail("Could not locate testdata/\(name)")
        return [:]
    }

    private lazy var contract: [String: Any] = Self.load("sync_client_behaviour.json")

    private func section(_ key: String) -> [String: Any] { contract[key] as? [String: Any] ?? [:] }
    private func int(_ value: Any?) -> Int64 { (value as? NSNumber)?.int64Value ?? -1 }
    private func double(_ value: Any?) -> Double { (value as? NSNumber)?.doubleValue ?? .nan }

    private func scenario(_ id: String) -> [String: Any] {
        let all = contract["scenarios"] as? [[String: Any]] ?? []
        guard let found = all.first(where: { $0["id"] as? String == id }) else {
            XCTFail("missing scenario \(id)")
            return [:]
        }
        return found
    }

    // MARK: constants

    func testSp3SwiftConstantsMatchTheContract() {
        XCTAssertEqual((contract["persistenceBatching"] as? [String: Any])?["splitBeforeRepeatedMutationWireId"] as? Bool, true)

        let foreground = section("presence")["foreground"] as? [String: Any] ?? [:]
        XCTAssertEqual(PresenceSendPolicy.minIntervalMs, int(foreground["minIntervalMs"]))
        XCTAssertEqual(PresenceSendPolicy.stationaryHeartbeatMs, int(foreground["stationaryHeartbeatMs"]))
        XCTAssertEqual(PresenceSendPolicy.moveMinMetres, double(foreground["moveMinMetres"]))
        XCTAssertEqual(PresenceSendPolicy.courseChangeDegrees, double(foreground["courseChangeDegrees"]))
        XCTAssertEqual(PresenceSendPolicy.courseCheckMinSpeedMps, double(foreground["courseCheckMinSpeedMps"]))
        XCTAssertEqual(PresenceSendPolicy.speedChangeMps, double(foreground["speedChangeMps"]))
        XCTAssertTrue((foreground["distance"] as? String ?? "").contains("6371008.8"))
        XCTAssertEqual(PresenceSendPolicy.earthRadiusMetres, 6_371_008.8)

        let background = section("presence")["background"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(UnitSyncPresenceCadence.bridgeLocationAge * 1000), int(background["bridgeFixMaxAgeMs"]))

        let fence = section("presence")["fencePersistence"] as? [String: Any] ?? [:]
        XCTAssertEqual(PresenceFencePersistence.stride, int(fence["stride"]))
        XCTAssertEqual(PresenceFencePersistence.flushMs, int(fence["flushMs"]))
        XCTAssertEqual(PresenceFencePersistence.crashFloorAdd, int(fence["crashFloorAdd"]))
        XCTAssertEqual(fence["flag"] as? String, "presenceFenceExact")

        let batching = section("persistenceBatching")
        XCTAssertEqual(Int64(SyncManager.inboundBatchMaxFrames), int(batching["inboundBatchMaxFrames"]))
        XCTAssertEqual(Int64(SyncManager.inboundQueueMaxFrames), int(batching["inboundQueueMaxFrames"]))
        XCTAssertEqual(Int64(SyncManager.inboundQueueMaxBytes), int(batching["inboundQueueMaxBytes"]))
        let replayMax = batching["replayWritesMax"] as? [String: Any] ?? [:]
        XCTAssertEqual(int(replayMax["perSnapshot"]), 2, "commit + clear, the hello epoch is extra")
        XCTAssertEqual(int(replayMax["perLiveBatch"]), 2)
        XCTAssertEqual(int(replayMax["perOutboundDiffPass"]), 1)
        XCTAssertEqual(batching["acksTriggerSyncDiff"] as? Bool, false)
        XCTAssertEqual(batching["runningHighWater"] as? Bool, true)
        XCTAssertEqual(batching["noDeepCopyPerTransaction"] as? Bool, true)

        let gate = section("chat")["backgroundRecipient"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(ChatSendGate.foregroundRetentionSeconds), int(gate["foregroundRetentionSeconds"]))

        let reconnect = section("background")["reconnect"] as? [String: Any] ?? [:]
        XCTAssertEqual(BackgroundPresencePolicy.minSpacingMs, int(reconnect["minSpacingMs"]))
        XCTAssertEqual(Int64(BackgroundPresencePolicy.maxConsecutiveFailures), int(reconnect["maxConsecutiveFailures"]))
        XCTAssertEqual(Int64(BackgroundPresencePolicy.maxSnapshotDrainBytes), int(reconnect["maxSnapshotDrainBytes"]))
        XCTAssertEqual(Set(BackgroundPresencePolicy.allowedFrameTypes).isDisjoint(
            with: Set(reconnect["neverSend"] as? [String] ?? [])), true)
        XCTAssertEqual(Int64(HelloEpochPolicy.backgroundSpareBlock), int(section("helloEpoch")["backgroundSpareBlock"]))
        // ships off until doc change D1 (THREAT_MODEL section 7) lands
        XCTAssertFalse(BackgroundPresencePolicy.reconnectEnabled)
    }

    // MARK: 20.1 presence send policy

    /// Feeds one fix per everyMs through the policy, returns the send times.
    private func runPresence(_ scenarioId: String) -> (sent: [Int64], expected: [Int64]) {
        let s = scenario(scenarioId)
        let given = s["given"] as? [String: Any] ?? [:]
        let fixes = given["fixes"] as? [String: Any] ?? [:]
        let start = int(fixes["startMs"]), end = int(fixes["endMs"]), step = int(fixes["everyMs"])
        let lat = double(fixes["lat"]), lon = double(fixes["lon"])
        let speed = double(fixes["speedMps"]), course = double(fixes["courseDeg"])
        let accuracy = double(fixes["horizontalAccuracyM"])
        let configChangeAt = (given["configChangeAtMs"] as? NSNumber)?.int64Value
        var policy = PresenceSendPolicy()
        policy.startSession()
        var sent: [Int64] = []
        var t = start
        while t <= end {
            if let configChangeAt, configChangeAt == t { policy.configDidChange() }
            // east of start by speed * t, lon offset = metres / (111320 * cos(lat))
            let metres = speed * Double(t - start) / 1000
            let fix = PresenceSendPolicy.Fix(
                latitude: lat, longitude: lon + metres / (111_320 * cos(lat * .pi / 180)),
                speedMps: speed, courseDegrees: course, horizontalAccuracyMetres: accuracy)
            if policy.shouldSend(fix, nowMs: t) {
                policy.markSent(fix, atMs: t)
                sent.append(t)
            }
            t += step
        }
        let expected = ((s["expect"] as? [String: Any])?["sendAtMs"] as? [NSNumber] ?? []).map(\.int64Value)
        return (sent, expected)
    }

    func testPresenceStationaryHeartbeat() {
        let run = runPresence("presence_stationary_heartbeat")
        XCTAssertEqual(run.sent, run.expected)
        // S5-10: 60 fixes at 1 Hz used to give 12 sends, now 3
        XCTAssertEqual(run.sent.count, 3)
    }

    func testPresenceMovingKeepsFiveSeconds() {
        let run = runPresence("presence_moving_keeps_5s")
        XCTAssertEqual(run.sent, run.expected)
    }

    func testPresenceConfigChangeSends() {
        let run = runPresence("presence_config_change_sends")
        XCTAssertEqual(run.sent, run.expected)
    }

    func testPresenceCourseAndSpeedTriggers() {
        var policy = PresenceSendPolicy()
        let base = PresenceSendPolicy.Fix(latitude: -33.86, longitude: 151.2, speedMps: 2,
                                          courseDegrees: 350, horizontalAccuracyMetres: 5)
        XCTAssertTrue(policy.shouldSend(base, nowMs: 0))
        policy.markSent(base, atMs: 0)
        // 350 -> 15 is 25 degrees across north while moving
        let turned = PresenceSendPolicy.Fix(latitude: -33.86, longitude: 151.2, speedMps: 2,
                                            courseDegrees: 15, horizontalAccuracyMetres: 5)
        XCTAssertFalse(policy.shouldSend(turned, nowMs: 4_999), "never inside 5 s")
        XCTAssertTrue(policy.shouldSend(turned, nowMs: 5_000))
        // a slow walker turning does not count, speed under 1.5 m/s
        let slow = PresenceSendPolicy.Fix(latitude: -33.86, longitude: 151.2, speedMps: 1,
                                          courseDegrees: 15, horizontalAccuracyMetres: 5)
        var slowPolicy = PresenceSendPolicy()
        slowPolicy.markSent(PresenceSendPolicy.Fix(latitude: -33.86, longitude: 151.2, speedMps: 1,
                                                   courseDegrees: 350, horizontalAccuracyMetres: 5), atMs: 0)
        XCTAssertFalse(slowPolicy.shouldSend(slow, nowMs: 6_000))
        let faster = PresenceSendPolicy.Fix(latitude: -33.86, longitude: 151.2, speedMps: 3.6,
                                            courseDegrees: 350, horizontalAccuracyMetres: 5)
        XCTAssertTrue(policy.shouldSend(faster, nowMs: 6_000), "speed change over 1.5 m/s")
        // a poor fix has to move further than its own accuracy
        var fuzzy = PresenceSendPolicy()
        let fuzzyBase = PresenceSendPolicy.Fix(latitude: -33.86, longitude: 151.2, speedMps: 0,
                                               courseDegrees: -1, horizontalAccuracyMetres: 50)
        fuzzy.markSent(fuzzyBase, atMs: 0)
        let thirtyMetres = PresenceSendPolicy.Fix(latitude: -33.86 + 30 / 111_195.0, longitude: 151.2,
                                                  speedMps: 0, courseDegrees: -1, horizontalAccuracyMetres: 50)
        XCTAssertFalse(fuzzy.shouldSend(thirtyMetres, nowMs: 6_000))
    }

    // MARK: 17.1 presence fence persistence

    private let actor = SyncIdentity.urlB64Encode(Data(repeating: 0x61, count: 32))
    private let pub = SyncIdentity.urlB64Encode(Data(repeating: 0x62, count: 32))
    private let session = SyncIdentity.urlB64Encode(Data(repeating: 0x63, count: 32))

    private func withSandbox(_ body: (URL) throws -> Void) throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("sp3-fence-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let previous = SafeStore.keyProvider
        let key = Data(repeating: 0x5a, count: 32)
        SafeStore.keyProvider = { key }
        SealedMigrationPolicy.resetForTests(key: key)
        defer {
            SafeStore.keyProvider = previous
            try? FileManager.default.removeItem(at: directory)
        }
        try body(directory)
    }

    func testPresenceFenceStrideAndCrashFloor() throws {
        try withSandbox { directory in
            let room = "sp3-presence-fence"
            var writes = 0
            let writer: SyncReplayState.PersistenceWriter = { data, url, label in
                writes += 1
                try SafeStore.write(data, to: url, label: label)
            }
            var state = SyncReplayState(roomId: room, containerURL: directory, persistenceWriter: writer)
            for step in scenario("presence_fence_stride_and_crash_floor")["steps"] as? [[String: Any]] ?? [] {
                switch step["event"] as? String {
                case "load":
                    state = SyncReplayState(roomId: room, containerURL: directory, persistenceWriter: writer)
                    XCTAssertTrue(state.load())
                    if int(step["persisted"]) == 0 {
                        XCTAssertTrue(try state.acceptHello(actorId: actor, pubkey: pub,
                                                            sessionDomain: session, epochHex: "0000000000000001"))
                    }
                    if let floor = step["expectFloor"] as? NSNumber {
                        XCTAssertFalse(try state.acceptPresence(actorId: actor, sessionDomain: session,
                                                                counter: floor.int64Value), "floor \(floor)")
                    }
                case "acceptCounters":
                    var persistedAt: [Int64] = []
                    for counter in int(step["from"])...int(step["to"]) {
                        let before = writes
                        XCTAssertTrue(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: counter))
                        if writes > before { persistedAt.append(counter) }
                    }
                    let expected = (step["expectPersistBeforeExposing"] as? [NSNumber] ?? []).map(\.int64Value)
                    XCTAssertEqual(persistedAt, expected)
                    // 40 frames used to be 40 full reseals
                    XCTAssertEqual(persistedAt.count, 3)
                case "crashAndLoad":
                    // no clean point: a fresh instance over whatever is on disk
                    state = SyncReplayState(roomId: room, containerURL: directory, persistenceWriter: writer)
                    XCTAssertTrue(state.load())
                    XCTAssertFalse(state.presenceFenceExact)
                    let floor = int(step["expectFloor"])
                    XCTAssertEqual(floor, int(step["persisted"]) + PresenceFencePersistence.crashFloorAdd)
                case "offer":
                    let expect = (step["expect"] as? [String: Any])?["accepted"] as? Bool
                    XCTAssertEqual(try state.acceptPresence(actorId: actor, sessionDomain: session,
                                                            counter: int(step["counter"])), expect, "\(step)")
                case "cleanPoint":
                    let before = writes
                    try state.writeCleanPresenceFence()
                    XCTAssertEqual(writes, before + 1)
                    XCTAssertTrue(state.presenceFenceExact)
                    let reloaded = SyncReplayState(roomId: room, containerURL: directory)
                    XCTAssertTrue(reloaded.load())
                    XCTAssertTrue(reloaded.presenceFenceExact)
                    let counter = int((step["expectPersist"] as? [String: Any])?["counter"])
                    XCTAssertFalse(try reloaded.acceptPresence(actorId: actor, sessionDomain: session, counter: counter))
                default:
                    XCTFail("unknown step \(step)")
                }
            }
            // after the exact reload the next counter is accepted and, being the
            // first after an exact write, written before it is exposed
            let before = writes
            XCTAssertTrue(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: 50))
            XCTAssertEqual(writes, before + 1)
        }
    }

    func testAFailedWriteNeverMovesAnAcceptedPresenceCounterBack() throws {
        try withSandbox { directory in
            enum Boom: Error { case disk }
            var fail = false
            let state = SyncReplayState(roomId: "sp3-presence-rollback", containerURL: directory) { data, url, label in
                if fail { throw Boom.disk }
                try SafeStore.write(data, to: url, label: label)
            }
            XCTAssertTrue(try state.acceptHello(actorId: actor, pubkey: pub, sessionDomain: session,
                                                epochHex: "0000000000000001"))
            XCTAssertTrue(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: 1))
            XCTAssertTrue(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: 9))
            fail = true
            // a clean point that cannot be written rolls its flag back but must
            // not hand counter 9 back to the relay
            XCTAssertThrowsError(try state.writeCleanPresenceFence())
            XCTAssertFalse(state.presenceFenceExact)
            XCTAssertFalse(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: 5))
            XCTAssertFalse(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: 9))
        }
    }

    /// 17.1 says the crash floor is persisted + 15 for *every* session. A
    /// session whose counter never hit the disk (fresh hello, then 1..15 shown
    /// without a write because the fence was already non-exact) has persisted
    /// 0, so its floor is 15. Without that a crash hands those 15 already shown
    /// positions back to a replaying relay.
    func testCrashFloorAlsoCoversASessionWhoseCounterWasNeverWritten() throws {
        try withSandbox { directory in
            let room = "sp3-presence-unwritten-session"
            var writes = 0
            let writer: SyncReplayState.PersistenceWriter = { data, url, label in
                writes += 1
                try SafeStore.write(data, to: url, label: label)
            }
            let otherActor = SyncIdentity.urlB64Encode(Data(repeating: 0x64, count: 32))
            let otherPub = SyncIdentity.urlB64Encode(Data(repeating: 0x65, count: 32))
            let otherSession = SyncIdentity.urlB64Encode(Data(repeating: 0x66, count: 32))
            let newSession = SyncIdentity.urlB64Encode(Data(repeating: 0x67, count: 32))
            let state = SyncReplayState(roomId: room, containerURL: directory, persistenceWriter: writer)
            XCTAssertTrue(state.load())
            XCTAssertTrue(try state.acceptHello(actorId: actor, pubkey: pub, sessionDomain: session,
                                                epochHex: "0000000000000001"))
            // first counter after an exact write goes down first, fence is non-exact from here
            XCTAssertTrue(try state.acceptPresence(actorId: actor, sessionDomain: session, counter: 1))
            XCTAssertFalse(state.presenceFenceExact)

            // a second unit joins: hello durable, its counters are not
            XCTAssertTrue(try state.acceptHello(actorId: otherActor, pubkey: otherPub, sessionDomain: otherSession,
                                                epochHex: "0000000000000004"))
            // and the first one comes back on a fresh session
            XCTAssertTrue(try state.acceptHello(actorId: actor, pubkey: pub, sessionDomain: newSession,
                                                epochHex: "0000000000000002"))
            let before = writes
            for counter in Int64(1)...15 {
                XCTAssertTrue(try state.acceptPresence(actorId: otherActor, sessionDomain: otherSession, counter: counter))
                XCTAssertTrue(try state.acceptPresence(actorId: actor, sessionDomain: newSession, counter: counter))
            }
            XCTAssertEqual(writes, before, "inside the stride nothing is written")

            // crash, no clean point
            let reloaded = SyncReplayState(roomId: room, containerURL: directory, persistenceWriter: writer)
            XCTAssertTrue(reloaded.load())
            for counter in Int64(1)...15 {
                XCTAssertFalse(try reloaded.acceptPresence(actorId: otherActor, sessionDomain: otherSession,
                                                           counter: counter), "counter \(counter) was shown already")
                XCTAssertFalse(try reloaded.acceptPresence(actorId: actor, sessionDomain: newSession,
                                                           counter: counter), "counter \(counter) was shown already")
            }
            XCTAssertTrue(try reloaded.acceptPresence(actorId: otherActor, sessionDomain: otherSession, counter: 16))
            XCTAssertTrue(try reloaded.acceptPresence(actorId: actor, sessionDomain: newSession, counter: 16))

            // an exact file still has no floor for such a session
            try reloaded.writeCleanPresenceFence()
            let clean = SyncReplayState(roomId: room, containerURL: directory, persistenceWriter: writer)
            XCTAssertTrue(clean.load())
            XCTAssertTrue(clean.presenceFenceExact)
            XCTAssertFalse(try clean.acceptPresence(actorId: actor, sessionDomain: newSession, counter: 16))
            XCTAssertTrue(try clean.acceptPresence(actorId: actor, sessionDomain: newSession, counter: 17))
        }
    }

    // MARK: 21.6 chat send gate

    func testChatBlockedToBackgroundPeer() {
        for row in scenario("chat_blocked_to_background_peer")["cases"] as? [[String: Any]] ?? [] {
            let scope: TacMapChatScope = row["scope"] as? String == "direct" ? .direct : .room
            let verdict = ChatSendGate.check(
                scope: scope, recipientLatestRetentionSeconds: Int(int(row["recipientLatestRetentionSeconds"])))
            let expect = row["expect"] as? [String: Any] ?? [:]
            if expect["blocked"] as? Bool == true {
                XCTAssertEqual(verdict, .blocked(SyncIssueCode(rawValue: expect["reason"] as? String ?? "")!), "\(row)")
            } else {
                XCTAssertEqual(verdict, .allowed, "\(row)")
            }
        }
        XCTAssertEqual(ChatSendGate.check(scope: .direct, recipientLatestRetentionSeconds: nil), .allowed,
                       "no presence on that session, nothing to judge by")
    }

    // MARK: 21.4 / 21.5 background presence policy

    func testBackgroundReconnectUsesSpareEpoch() {
        let s = scenario("background_reconnect_uses_spare_epoch")
        let given = s["given"] as? [String: Any] ?? [:]
        var policy = BackgroundPresencePolicy(lastForegroundEpoch: 0x1000, spares: UInt64(int(given["spares"])),
                                              reconnectEnabled: true)
        for step in s["steps"] as? [[String: Any]] ?? [] {
            switch step["event"] as? String {
            case "socketClosed":
                policy.socketClosed(atMs: int(step["atMs"]))
            case "fixDue":
                let decision = policy.fixDue(atMs: int(step["atMs"]), eligible: true)
                let expect = step["expect"] as? [String: Any] ?? [:]
                XCTAssertEqual(expect["connect"] as? Bool, true)
                XCTAssertEqual(decision, .connect(epoch: 0x1001), "the next spare, nothing new is reserved")
                XCTAssertEqual(int(expect["durableWrites"]), 0)
                XCTAssertEqual(Set(expect["framesSent"] as? [String] ?? []), BackgroundPresencePolicy.allowedFrameTypes)
                XCTAssertEqual(expect["chatKeySent"] as? Bool, false)
                XCTAssertFalse(BackgroundPresencePolicy.allowedFrameTypes.contains("chat-key"))
                XCTAssertNotNil(policy.snapshotBytes(Int(int(given["snapshotBytes"])) + BackgroundPresencePolicy.maxSnapshotDrainBytes))
            default:
                XCTFail("unknown step \(step)")
            }
        }
        // spacing and the failure cap
        var spaced = BackgroundPresencePolicy(lastForegroundEpoch: 10, spares: 64, reconnectEnabled: true)
        spaced.socketClosed(atMs: 0)
        XCTAssertEqual(spaced.fixDue(atMs: 1_000, eligible: true), .connect(epoch: 11))
        spaced.attemptFailed()
        XCTAssertEqual(spaced.fixDue(atMs: 30_000, eligible: true), .wait, "at least 60 s apart")
        XCTAssertEqual(spaced.fixDue(atMs: 61_000, eligible: true), .connect(epoch: 12))
        spaced.attemptFailed()
        XCTAssertEqual(spaced.fixDue(atMs: 122_000, eligible: true), .connect(epoch: 13))
        spaced.attemptFailed()
        XCTAssertEqual(spaced.fixDue(atMs: 183_000, eligible: true), .pause(.tooManyFailures))
    }

    func testBackgroundPausesWithoutSpare() {
        let s = scenario("background_pauses_without_spare")
        let spares = UInt64(int((s["given"] as? [String: Any])?["spares"]))
        var policy = BackgroundPresencePolicy(lastForegroundEpoch: 0x2000, spares: spares, reconnectEnabled: true)
        for step in s["steps"] as? [[String: Any]] ?? [] {
            switch step["event"] as? String {
            case "socketClosed":
                policy.socketClosed(atMs: int(step["atMs"]))
            case "fixDue":
                let decision = policy.fixDue(atMs: int(step["atMs"]), eligible: true)
                let expect = step["expect"] as? [String: Any] ?? [:]
                XCTAssertEqual(expect["connect"] as? Bool, false)
                XCTAssertEqual(decision, .pause(.noSpareEpoch))
                XCTAssertEqual(decision.issue?.rawValue, expect["issue"] as? String)
            default:
                XCTFail("unknown step \(step)")
            }
        }
        // until doc change D1 ships the reconnect is off and a drop just pauses
        var shipped = BackgroundPresencePolicy(lastForegroundEpoch: 1, spares: 64)
        shipped.socketClosed(atMs: 0)
        XCTAssertEqual(shipped.fixDue(atMs: 900_000, eligible: true), .pause(.reconnectDisabled))
    }

    // MARK: coverage

    func testEverySp3ScenarioHasAnIosRunner() {
        let covered: Set<String> = [
            "presence_stationary_heartbeat", "presence_moving_keeps_5s", "presence_config_change_sends",
            "presence_fence_stride_and_crash_floor", "chat_blocked_to_background_peer",
            "background_reconnect_uses_spare_epoch", "background_pauses_without_spare",
            // SyncClientBehaviourTests.testBackgroundDiscardUsesItsOwnBudget
            "background_discard_uses_background_budget",
            // manager-level, SyncEfficiencyTests
            "background_entry_with_pending_delivery"
        ]
        for s in contract["scenarios"] as? [[String: Any]] ?? [] where s["requiredBy"] as? String == "SP3" {
            XCTAssertTrue(covered.contains(s["id"] as? String ?? ""), "no iOS runner for \(s["id"] ?? "?")")
        }
    }
}
