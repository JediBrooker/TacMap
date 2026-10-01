import XCTest
@testable import TacticalMaps

/// Loads testdata/sync_client_behaviour.json (the machine twin of
/// plans/04-sync-client-contract.md) and runs every SP2 scenario against the
/// matching iOS policy unit. Android runs the same file.
final class SyncClientBehaviourTests: XCTestCase {

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
    private lazy var protocolFixture: [String: Any] = Self.load("sync_protocol_v3.json")

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

    // MARK: relay dependency + invariants

    func testRelayLimitsDependenciesMatchTheRelayFixture() throws {
        let deps = section("relayLimitsDependencies")
        let relay = try XCTUnwrap(protocolFixture["relayLimits"] as? [String: Any])
        let relayValues = try XCTUnwrap(relay["values"] as? [String: Any])
        for (key, value) in deps["values"] as? [String: Any] ?? [:] {
            XCTAssertEqual(int(value), int(relayValues[key]), "relayLimits.values.\(key) drifted")
        }
        var pacing = relay["clientPacing"] as? [String: Any] ?? [:]
        pacing.removeValue(forKey: "_comment")
        XCTAssertEqual(NSDictionary(dictionary: pacing), NSDictionary(dictionary: deps["clientPacing"] as? [String: Any] ?? [:]))
        for key in ["closeCodes", "upgradeStatus", "opNackCodes"] {
            let relayKeys = (relay[key] as? [String: Any] ?? [:]).keys.filter { !$0.hasPrefix("_") }.sorted()
            XCTAssertEqual(relayKeys, (deps[key] as? [String] ?? []).sorted(), key)
        }
    }

    func testSwiftConstantsMatchTheContract() {
        let pacer = section("pacer")
        let config = SyncOutboundPacer.Config.contract
        XCTAssertEqual(config.windowMs, double(pacer["windowMs"]))
        XCTAssertEqual(config.frameCapacity, double((pacer["frameBucket"] as? [String: Any])?["capacity"]))
        XCTAssertEqual(config.frameRefillPerWindow, double((pacer["frameBucket"] as? [String: Any])?["refillPerWindow"]))
        XCTAssertEqual(config.byteCapacity, double((pacer["byteBucket"] as? [String: Any])?["capacity"]))
        XCTAssertEqual(config.byteRefillPerWindow, double((pacer["byteBucket"] as? [String: Any])?["refillPerWindow"]))
        XCTAssertEqual(config.reserveFrames, double((pacer["interactiveReserve"] as? [String: Any])?["frames"]))
        XCTAssertEqual(config.reserveBytes, double((pacer["interactiveReserve"] as? [String: Any])?["bytes"]))
        XCTAssertEqual(Int64(config.maxInFlightMutations), int((pacer["inFlight"] as? [String: Any])?["maxMutations"]))
        XCTAssertEqual(Int64(config.maxInFlightMutationBytes), int((pacer["inFlight"] as? [String: Any])?["maxMutationBytes"]))
        XCTAssertEqual(config.after4008FrameTokens, double((pacer["after4008"] as? [String: Any])?["startFrameTokens"]))
        XCTAssertEqual(config.after4008ByteTokens, double((pacer["after4008"] as? [String: Any])?["startByteTokens"]))

        let retransmit = section("retransmit")
        XCTAssertEqual(SyncAckTimer.ackTimeoutBaseMs, int(retransmit["ackTimeoutBaseMs"]))
        XCTAssertEqual(SyncAckTimer.ackTimeoutPerAheadMs, int(retransmit["ackTimeoutPerAheadMs"]))
        XCTAssertEqual(SyncAckTimer.ackTimeoutCapMs, int(retransmit["ackTimeoutCapMs"]))
        XCTAssertEqual(Int64(SyncAckTimer.maxAttempts), int(retransmit["maxAttempts"]))

        let size = section("outboundSize")
        XCTAssertEqual(Int64(OutboundSizeCheck.objectCtMaxChars), int(size["objectCtMaxChars"]))
        XCTAssertEqual(Int64(OutboundSizeCheck.maxFrameBytes), int(size["maxFrameBytes"]))

        let budget = section("receiveBudget")
        let room = budget["room"] as? [String: Any] ?? [:]
        XCTAssertEqual(SyncReceiveBudget.windowMs, int(budget["windowMs"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.roomBaseFrames), int(room["baseFrames"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.roomPerSessionFrames), int(room["perSessionFrames"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.roomMaxFrames), int(room["maxFrames"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.roomBaseBytes), int(room["baseBytes"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.roomPerSessionBytes), int(room["perSessionBytes"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.roomMaxBytes), int(room["maxBytes"]))
        let selfResponse = budget["selfResponse"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(SyncReceiveBudget.selfResponseMaxFrames), int(selfResponse["maxFrames"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.selfResponseMaxBytes), int(selfResponse["maxBytes"]))
        XCTAssertEqual(SyncReceiveBudget.selfResponseTypes, Set(selfResponse["types"] as? [String] ?? []))
        XCTAssertEqual(Int64(SyncReceiveBudget.initialMaxFrames), int((budget["initial"] as? [String: Any])?["maxFrames"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.initialMaxBytes), int((budget["initial"] as? [String: Any])?["maxBytes"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.backgroundMaxFrames), int((budget["background"] as? [String: Any])?["maxFrames"]))
        XCTAssertEqual(Int64(SyncReceiveBudget.backgroundMaxBytes), int((budget["background"] as? [String: Any])?["maxBytes"]))

        let watchdogs = section("watchdogs")
        XCTAssertEqual(SyncHandshakeWatchdog.connectOpenTimeoutMs, int(watchdogs["connectOpenTimeoutMs"]))
        XCTAssertEqual(SyncHandshakeWatchdog.handshakeStallTimeoutMs, int(watchdogs["handshakeStallTimeoutMs"]))
        XCTAssertEqual(SyncHandshakeWatchdog.helloAckTimeoutMs, int(watchdogs["helloAckTimeoutMs"]))
        XCTAssertEqual(SyncHandshakeWatchdog.handshakeAbsoluteMaxMs, int(watchdogs["handshakeAbsoluteMaxMs"]))

        let heartbeat = section("heartbeat")
        XCTAssertEqual(SyncHeartbeatPolicy.foregroundPingIntervalMs,
                       int((heartbeat["foreground"] as? [String: Any])?["pingIntervalMs"]))
        XCTAssertEqual(SyncHeartbeatPolicy.deadAfterMsWithoutPongOrFrame,
                       int((heartbeat["foreground"] as? [String: Any])?["deadAfterMsWithoutPongOrFrame"]))
        XCTAssertEqual(SyncHeartbeatPolicy.backgroundPingIntervalMs,
                       int((heartbeat["background"] as? [String: Any])?["pingIntervalMs"]))
        XCTAssertEqual(SyncHeartbeatPolicy.pathChangeProbeTimeoutMs, int(heartbeat["pathChangeProbeTimeoutMs"]))

        let backoff = section("backoff")
        XCTAssertEqual(Int64(SyncBackoffPolicy.attemptCap), int(backoff["attemptCap"]))
        XCTAssertEqual(SyncBackoffPolicy.stableSessionMs, int(backoff["stableSessionMs"]))
        for (name, raw) in backoff["classes"] as? [String: Any] ?? [:] {
            let row = raw as? [String: Any] ?? [:]
            guard let backoffClass = SyncBackoffClass(rawValue: name) else { XCTFail("unknown class \(name)"); continue }
            let params = SyncBackoffPolicy.params(backoffClass)
            XCTAssertEqual(params.floorMs, double(row["floorMs"]), name)
            XCTAssertEqual(params.spreadMs, double(row["spreadMs"]), name)
            XCTAssertEqual(params.capMs, double(row["capMs"]), name)
        }
        XCTAssertEqual(SyncBackoffPolicy.reachabilityMinSpacingMs,
                       int(section("reachability")["minSpacingFromLastAttemptMs"]))

        let stops = section("stopThresholds")
        XCTAssertEqual(Int64(SyncCloseClassifier.structuralSnapshotFailuresBeforeStop), int(stops["structuralSnapshotFailuresBeforeStop"]))
        XCTAssertEqual(Int64(SyncCloseClassifier.sessionConflictsBeforeStop), int(stops["sessionConflictsBeforeStop"]))
        XCTAssertEqual(SyncCloseClassifier.sessionConflictWindowMs, int(stops["sessionConflictWindowMs"]))
        XCTAssertEqual(Int64(SyncCloseClassifier.staleEpochEscalationsBeforeStop), int(stops["staleEpochEscalationsBeforeStop"]))

        let resync = section("liveWindowResync")
        XCTAssertEqual(LiveWindowResyncPolicy.cooldownMs, int(resync["cooldownMs"]))
        XCTAssertEqual(Int64(LiveWindowResyncPolicy.maxPerHour), int(resync["maxPerHour"]))

        let epoch = section("helloEpoch")
        XCTAssertEqual(Int64(HelloEpochPolicy.backgroundSpareBlock), int(epoch["backgroundSpareBlock"]))

        let chat = section("chat")
        let fences = chat["replayFences"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(ChatReplayPruner.maxFences), int(fences["maxFences"]))
        XCTAssertEqual(Int64(TacMapChatStore.fingerprintsKeptOnWrite), int(fences["fingerprintsKeptOnWrite"]))
        XCTAssertEqual(Int64(TacMapChatStore.fingerprintsAcceptedOnLoad), int(fences["fingerprintsAcceptedOnLoad"]))
        let history = chat["history"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(ChatHistoryBudget.maxMessages), int(history["maxMessages"]))
        XCTAssertEqual(Int64(ChatHistoryBudget.maxEncodedBytes), int(history["maxEncodedBytes"]))
        XCTAssertEqual(Int64(ChatHistoryBudget.pruneTargetBytes), int(history["pruneTargetBytes"]))

        XCTAssertEqual(Int64(SyncReplayState.advanceWindow),
                       int((section("relayLimitsDependencies")["values"] as? [String: Any])?["ADVANCE_WINDOW"]))
    }

    func testEveryInvariantHolds() throws {
        let deps = section("relayLimitsDependencies")
        let values = deps["values"] as? [String: Any] ?? [:]
        let pacing = deps["clientPacing"] as? [String: Any] ?? [:]
        let config = SyncOutboundPacer.Config.contract
        let rateMax = int(values["RATE_MAX_MSGS"])
        let checks: [String: Bool] = [
            "frameBucketWithinClientPacing":
                config.frameCapacity + config.frameRefillPerWindow <= double(pacing["maxFramesPerWindow"]),
            "byteBucketWithinClientPacing":
                config.byteCapacity + config.byteRefillPerWindow <= double(pacing["maxBytesPerWindow"]),
            "byteBucketFitsOneFrame": config.byteCapacity >= double(values["MAX_FRAME_BYTES"]),
            "bunchingBelowRelayWindow":
                int(pacing["maxFramesPerWindow"]) + Int64(config.maxInFlightMutations) + 4 < rateMax,
            "inFlightBytesWellBelowRoomBacklog":
                Int64(config.maxInFlightMutationBytes) * 4 <= int(values["ROOM_PENDING_MAX_BYTES"]),
            "roomReceiveAbovePerSenderAllowance":
                Int64(SyncReceiveBudget.roomBaseFrames) >= 3 * rateMax
                && Int64(SyncReceiveBudget.roomBaseBytes) >= 3 * int(values["RATE_MAX_BYTES"]),
            "selfResponseCoversPacing":
                Int64(SyncReceiveBudget.selfResponseMaxFrames) >= 2 * int(pacing["maxFramesPerWindow"]),
            "everyRelayCloseCodeHasARow": true,
            "everyUpgradeStatusHasARow": true,
            "everyNackCodeHasARow": true,
            "presenceHeartbeatUnderRetention": true // SP3 presence policy, not in this phase
        ]
        for invariant in contract["invariants"] as? [[String: Any]] ?? [] {
            let name = invariant["name"] as? String ?? "?"
            XCTAssertEqual(checks[name], true, "invariant \(name)")
        }
        // the three "has a row" invariants, against the live classifier
        for code in deps["closeCodes"] as? [String] ?? [] {
            var classifier = SyncCloseClassifier()
            let decision = classifier.closed(code: Int(code)!, afterOwnLeave: false, nowMs: 0)
            XCTAssertNotNil(decision.backoffClass ?? decision.issue, "close \(code) needs a row")
        }
        for status in deps["upgradeStatus"] as? [String] ?? [] {
            var classifier = SyncCloseClassifier()
            let decision = classifier.upgradeFailed(httpStatus: Int(status)!, nowMs: 0)
            XCTAssertNotNil(decision.backoffClass ?? decision.issue, "status \(status) needs a row")
        }
        let nackRows = section("opNackActions")
        for code in deps["opNackCodes"] as? [String] ?? [] {
            XCTAssertNotNil(nackRows[code], "nack \(code) needs a row")
        }
    }

    // MARK: backoff (section 8)

    private func runBackoffSteps(_ id: String) {
        var policy = SyncBackoffPolicy()
        for step in scenario(id)["steps"] as? [[String: Any]] ?? [] {
            let at = int(step["atMs"]) < 0 ? 0 : int(step["atMs"])
            switch step["event"] as? String {
            case "failure":
                if step["attemptBefore"] != nil {
                    // jump the counter, but keep when the last attempt started
                    let started = policy.lastAttemptStartMs
                    policy = SyncBackoffPolicy(attempt: Int(int(step["attemptBefore"])))
                    if let started { policy.attemptStarted(atMs: started) }
                }
                let cls = SyncBackoffClass(rawValue: step["class"] as! String)!
                let delay = policy.failure(cls, random: double(step["random"]), nowMs: at)
                XCTAssertEqual(delay, double(step["expectDelayMs"]), accuracy: 0.001, "\(id) @\(at)")
            case "helloAck":
                policy.connected(atMs: at)
            case "opAck":
                policy.opAcknowledged()
            case "tick":
                policy.tick(nowMs: at)
                XCTAssertEqual(Int64(policy.attempt), int(step["expectAttempt"]), "\(id) @\(at)")
            case "attemptStarted":
                policy.attemptStarted(atMs: at)
            case "networkAvailable":
                let due = policy.reachabilityRegained(nowMs: at)
                XCTAssertEqual(due, int((step["expect"] as? [String: Any])?["connectAtMs"]), "\(id) @\(at)")
            default:
                XCTFail("unknown backoff event \(step)")
            }
        }
    }

    func testBackoffScenarios() {
        runBackoffSteps("backoff_full_jitter_transient")
        runBackoffSteps("backoff_not_reset_at_hello_ack")
        runBackoffSteps("backoff_reset_after_stable_30s")
        runBackoffSteps("backoff_slow_busy_503")
    }

    func testReachabilityScenario() {
        // straight off the fixture: only a pending transient reconnect gets
        // pulled in, never before lastAttemptStart + 2 s, slow classes stay put
        runBackoffSteps("reachability_shortcuts_transient_only")
    }

    func testJitterAtTheCapIsSpreadNotPiledUp() {
        var generator = SystemRandomNumberGenerator()
        var atCap = 0
        for _ in 0..<10_000 {
            var policy = SyncBackoffPolicy(attempt: 6 + Int.random(in: 0...10, using: &generator))
            let delay = policy.failure(.transient, random: Double.random(in: 0...1, using: &generator), nowMs: 0)
            if delay == 30_000 { atCap += 1 }
            XCTAssertGreaterThanOrEqual(delay, 250)
            XCTAssertLessThanOrEqual(delay, 30_000)
        }
        XCTAssertLessThan(Double(atCap) / 10_000, 0.01, "S2-14: about half used to land at exactly 30 s")
    }

    // MARK: close codes and statuses (section 7)

    func testCloseAndStatusTable() {
        for row in scenario("close_and_status_table")["cases"] as? [[String: Any]] ?? [] {
            var classifier = SyncCloseClassifier()
            let decision: SyncConnectionDecision
            if (row["opened"] as? Bool) == true {
                decision = classifier.closed(code: Int(int(row["closeCode"])),
                                             afterOwnLeave: (row["afterOwnLeave"] as? Bool) ?? false, nowMs: 0)
            } else {
                decision = classifier.upgradeFailed(httpStatus: (row["httpStatus"] as? NSNumber)?.intValue, nowMs: 0)
            }
            let expect = row["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(decision.action.rawValue, expect["action"] as? String, "\(row)")
            if let cls = expect["backoffClass"] as? String {
                XCTAssertEqual(decision.backoffClass?.rawValue, cls, "\(row)")
            }
            if let issue = expect["issue"] as? String {
                XCTAssertEqual(decision.issue?.rawValue, issue, "\(row)")
            }
            if expect["pacer"] as? String == "after4008" {
                XCTAssertTrue(decision.pacerAfter4008)
            }
        }
    }

    func testFixtureCloseCodeRowsMatchTheClassifier() {
        for (code, raw) in section("closeCodeActions") {
            guard let numeric = Int(code) else { continue }
            let row = raw as? [String: Any] ?? [:]
            var classifier = SyncCloseClassifier()
            let decision = classifier.closed(code: numeric, afterOwnLeave: false, nowMs: 0)
            switch row["action"] as? String {
            case "stop":
                XCTAssertEqual(decision.action, .stop, code)
                XCTAssertEqual(decision.issue?.rawValue, row["issue"] as? String, code)
                XCTAssertEqual(decision.retryOnForeground, row["retryOnForeground"] as? Bool ?? false, code)
            case "escalate_epoch_then_reconnect":
                XCTAssertEqual(decision.action, .escalateEpoch, code)
            default:
                XCTAssertEqual(decision.action, .reconnect, code)
                XCTAssertEqual(decision.backoffClass?.rawValue, row["backoffClass"] as? String, code)
            }
        }
        var classifier = SyncCloseClassifier()
        XCTAssertEqual(classifier.closed(code: 1099, afterOwnLeave: false, nowMs: 0).backoffClass, .transient)
        XCTAssertEqual(classifier.closed(code: 4321, afterOwnLeave: false, nowMs: 0).backoffClass, .slowUnknown)
        for (status, raw) in section("httpStatusActions") {
            guard let numeric = Int(status) else { continue }
            let row = raw as? [String: Any] ?? [:]
            var fresh = SyncCloseClassifier()
            let decision = fresh.upgradeFailed(httpStatus: numeric, nowMs: 0)
            XCTAssertEqual(decision.action.rawValue, row["action"] as? String, status)
            if let cls = row["backoffClass"] as? String { XCTAssertEqual(decision.backoffClass?.rawValue, cls, status) }
        }
    }

    func testSessionConflictStopsAfterThree() {
        var classifier = SyncCloseClassifier()
        for step in scenario("session_conflict_stops_after_three")["steps"] as? [[String: Any]] ?? [] {
            let at = int(step["atMs"])
            let decision = step["nack"] != nil
                ? classifier.sessionNack(nowMs: at)
                : classifier.closed(code: Int(int(step["closeCode"])), afterOwnLeave: false, nowMs: at)
            let expect = step["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(decision.action.rawValue, expect["action"] as? String, "@\(at)")
            if let issue = expect["issue"] as? String { XCTAssertEqual(decision.issue?.rawValue, issue) }
        }
        // spread out over more than ten minutes it never stops
        var spread = SyncCloseClassifier()
        for i in 0..<6 {
            XCTAssertEqual(spread.closed(code: 4015, afterOwnLeave: false, nowMs: Int64(i) * 400_000).action, .reconnect)
        }
    }

    func testStaleEpochEscalatesThreeTimesThenStops() {
        var classifier = SyncCloseClassifier()
        for _ in 0..<3 {
            XCTAssertEqual(classifier.closed(code: 4014, afterOwnLeave: false, nowMs: 0).action, .escalateEpoch)
        }
        let stop = classifier.closed(code: 4014, afterOwnLeave: false, nowMs: 0)
        XCTAssertEqual(stop.action, .stop)
        XCTAssertEqual(stop.issue, .sessionCounterBehind)
    }

    func testStructuralSnapshotStopsAfterThree() {
        var classifier = SyncCloseClassifier()
        let steps = scenario("structural_snapshot_stops_after_three")["steps"] as? [[String: Any]] ?? []
        for step in steps {
            let decision = classifier.structuralSnapshotFailure()
            let expect = step["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(decision.action.rawValue, expect["action"] as? String)
            if let issue = expect["issue"] as? String { XCTAssertEqual(decision.issue?.rawValue, issue) }
        }
        var reset = SyncCloseClassifier()
        _ = reset.structuralSnapshotFailure()
        _ = reset.structuralSnapshotFailure()
        reset.connectionSucceeded()
        XCTAssertEqual(reset.structuralSnapshotFailure().action, .reconnect, "hello-ack resets the count")
    }

    // MARK: nacks (section 6)

    func testStaleOnOwnPersistedStampIsConfirmed() {
        for row in scenario("stale_on_own_persisted_stamp_is_confirmed")["cases"] as? [[String: Any]] ?? [] {
            let outcome = SyncNackPolicy.decide(
                code: row["code"] as! String, retryable: false,
                rejectedStampIsOwnPersisted: row["rejectedStampEqualsOwnPersisted"] as! Bool,
                wireIdSkipped: row["wireIdSkippedThisJoin"] as! Bool)
            let expect = row["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(outcome == .confirmed, expect["markConfirmed"] as? Bool, "\(row)")
            XCTAssertEqual(outcome == .suppress(issue: nil), expect["suppress"] as? Bool, "\(row)")
        }
    }

    func testEveryNackRowMapsToTheContractOutcome() {
        XCTAssertEqual(SyncNackPolicy.decide(code: "not-found", retryable: false,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .suppress(issue: nil))
        XCTAssertEqual(SyncNackPolicy.decide(code: "counter-window", retryable: false,
                                             rejectedStampIsOwnPersisted: true, wireIdSkipped: false), .pauseMutations)
        XCTAssertEqual(SyncNackPolicy.decide(code: "quota", retryable: false,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .suppress(issue: .roomQuotaNack))
        XCTAssertEqual(SyncNackPolicy.decide(code: "invalid", retryable: false,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .suppress(issue: .relayInvalidNack))
        XCTAssertEqual(SyncNackPolicy.decide(code: "storage", retryable: true,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .retry)
        XCTAssertEqual(SyncNackPolicy.decide(code: "hello-required", retryable: true,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .reconnect(.helloRequiredNack))
        XCTAssertEqual(SyncNackPolicy.decide(code: "session-replaced", retryable: false,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .reconnect(.sessionNack))
        XCTAssertEqual(SyncNackPolicy.decide(code: "session-mismatch", retryable: false,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .reconnect(.sessionNack))
        XCTAssertEqual(SyncNackPolicy.decide(code: "brand-new", retryable: true,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .retry)
        XCTAssertEqual(SyncNackPolicy.decide(code: "brand-new", retryable: false,
                                             rejectedStampIsOwnPersisted: false, wireIdSkipped: false), .suppress(issue: .relayInvalidNack))
        for code in ["stale", "not-found", "counter-window", "quota", "invalid", "storage"] {
            if case .suppress(let issue) = SyncNackPolicy.decide(code: code, retryable: false,
                                                                 rejectedStampIsOwnPersisted: false, wireIdSkipped: false) {
                XCTAssertFalse(issue?.isSecurity ?? false, "no nack is ever a SECURITY issue")
            }
        }
    }

    // MARK: live window resync (section 4)

    func testLiveWindowResyncCooldown() {
        var policy = LiveWindowResyncPolicy()
        for step in scenario("live_window_resync_cooldown")["steps"] as? [[String: Any]] ?? [] {
            let at = int(step["atMs"])
            let expect = step["expect"] as? [String: Any] ?? [:]
            let now: Bool
            switch step["event"] as? String {
            case "windowRejection": now = policy.windowRejection(nowMs: at)
            default: now = policy.tick(nowMs: at)
            }
            XCTAssertEqual(now, expect["resyncNow"] as? Bool, "@\(at)")
            if let pending = expect["pending"] as? Bool { XCTAssertEqual(policy.pending, pending, "@\(at)") }
        }
        var hourly = LiveWindowResyncPolicy()
        for i in 0..<6 { XCTAssertTrue(hourly.windowRejection(nowMs: Int64(i) * 60_000)) }
        XCTAssertFalse(hourly.windowRejection(nowMs: 6 * 60_000), "max 6 an hour")
        XCTAssertEqual(hourly.nextEligibleMs(), 3_600_000)
    }

    // MARK: pacer (section 11)

    /// Event loop that mirrors the fixture generator: writes complete at once
    /// and every mutation is acked ackAfterMs later.
    private func simulatePacer(mutations: Int, frameBytes: Int, ackAfterMs: Double,
                               offers: [(at: Double, cls: SyncOutboundPacer.FrameClass, bytes: Int)] = [],
                               until: Double = .infinity) -> (mutationSends: [Double], offerSends: [Double], maxInFlight: Int) {
        let pacer = SyncOutboundPacer(nowMs: 0)
        for i in 0..<mutations { pacer.enqueue(.mutation, bytes: frameBytes, requestId: "r\(i)") }
        var acks: [(at: Double, rid: String)] = []
        var mutationSends: [Double] = []
        var offerSends: [Double] = Array(repeating: -1, count: offers.count)
        var offerIds: [Int: Int] = [:]
        var pendingOffers = offers.enumerated().map { ($0.offset, $0.element) }
        var now = 0.0
        var maxInFlight = 0
        var guardCount = 0
        while now <= until, pacer.queuedCount > 0 || !pendingOffers.isEmpty {
            guardCount += 1
            if guardCount > 100_000 { XCTFail("pacer loop runaway"); break }
            while let first = pendingOffers.first, first.1.at <= now + 1e-9 {
                pendingOffers.removeFirst()
                offerIds[pacer.enqueue(first.1.cls, bytes: first.1.bytes).id] = first.0
            }
            acks.removeAll { ack in
                if ack.at <= now + 1e-9 { pacer.settle(requestId: ack.rid); return true }
                return false
            }
            if let item = pacer.next(nowMs: now) {
                if let rid = item.requestId {
                    mutationSends.append(now)
                    acks.append((now + ackAfterMs, rid))
                } else if let offer = offerIds[item.id] {
                    offerSends[offer] = now
                }
                maxInFlight = max(maxInFlight, pacer.inFlightCount)
                continue
            }
            var candidates: [Double] = []
            if let wake = pacer.nextWakeMs(nowMs: now), wake > now + 1e-9 { candidates.append(wake) }
            if let ack = acks.map(\.at).min(), ack > now + 1e-9 { candidates.append(ack) }
            if let offer = pendingOffers.first?.1.at, offer > now + 1e-9 { candidates.append(offer) }
            guard let next = candidates.min() else { break }
            now = next
        }
        return (mutationSends, offerSends, maxInFlight)
    }

    private func maxInWindow(_ times: [Double], sizes: [Int], windowMs: Double) -> (frames: Int, bytes: Int) {
        var best = (0, 0)
        for (i, t) in times.enumerated() {
            var frames = 0, bytes = 0
            for j in i..<times.count where times[j] < t + windowMs {
                frames += 1
                bytes += sizes[j]
            }
            best = (max(best.0, frames), max(best.1, bytes))
        }
        return best
    }

    func testPacerBulkSmallFrames() {
        let s = scenario("pacer_bulk_500_small")
        let given = s["given"] as? [String: Any] ?? [:]
        let expect = s["expect"] as? [String: Any] ?? [:]
        let result = simulatePacer(mutations: Int(int(given["mutations"])), frameBytes: Int(int(given["frameBytes"])),
                                   ackAfterMs: double(given["ackAfterWriteMs"]))
        XCTAssertEqual(Int64(result.mutationSends.filter { $0 == 0 }.count), int(expect["sendsAtZero"]))
        let window = maxInWindow(result.mutationSends, sizes: Array(repeating: 2_000, count: result.mutationSends.count), windowMs: 10_000)
        XCTAssertEqual(Int64(window.frames), int(expect["maxFramesAnyWindow"]))
        XCTAssertLessThanOrEqual(Int64(window.frames), int(expect["maxFramesAnyWindowLimit"]))
        XCTAssertEqual(result.mutationSends.last ?? -1, double(expect["lastSendAtMs"]), accuracy: double(expect["toleranceMs"]))
        XCTAssertLessThanOrEqual(result.maxInFlight, 32)
    }

    func testPacerLargeFramesAreByteBound() {
        let s = scenario("pacer_large_frames_bytes")
        let given = s["given"] as? [String: Any] ?? [:]
        let expect = s["expect"] as? [String: Any] ?? [:]
        let result = simulatePacer(mutations: Int(int(given["mutations"])), frameBytes: Int(int(given["frameBytes"])),
                                   ackAfterMs: double(given["ackAfterWriteMs"]))
        let expected = (expect["sendTimesMs"] as? [NSNumber] ?? []).map(\.doubleValue)
        XCTAssertEqual(result.mutationSends.count, expected.count)
        for (got, want) in zip(result.mutationSends, expected) {
            XCTAssertEqual(got, want, accuracy: double(expect["toleranceMs"]))
        }
        let window = maxInWindow(result.mutationSends, sizes: Array(repeating: 700_000, count: result.mutationSends.count), windowMs: 10_000)
        XCTAssertEqual(Int64(window.bytes), int(expect["maxBytesAnyWindow"]))
        XCTAssertLessThanOrEqual(Int64(window.bytes), int(expect["maxBytesAnyWindowLimit"]))
    }

    func testPacerInteractiveReserveLetsPresenceAndChatThrough() {
        let s = scenario("pacer_interactive_reserve")
        let given = s["given"] as? [String: Any] ?? [:]
        var offers: [(at: Double, cls: SyncOutboundPacer.FrameClass, bytes: Int)] = []
        var expected: [Double] = []
        for step in s["steps"] as? [[String: Any]] ?? [] {
            let cls: SyncOutboundPacer.FrameClass = step["class"] as? String == "presence" ? .presence : .chat
            offers.append((double(step["atMs"]), cls, Int(int(step["bytes"]))))
            expected.append(double((step["expect"] as? [String: Any])?["sentAtMs"]))
        }
        let result = simulatePacer(mutations: Int(int(given["mutations"])), frameBytes: Int(int(given["frameBytes"])),
                                   ackAfterMs: double(given["ackAfterWriteMs"]), offers: offers, until: 6_000)
        XCTAssertEqual(result.offerSends, expected)
    }

    func testPacerPresenceKeepsOneSlotAndAfter4008StartsHalfFull() {
        let pacer = SyncOutboundPacer(nowMs: 0, after4008: true)
        XCTAssertEqual(pacer.frameTokens, 15)
        XCTAssertEqual(pacer.byteTokens, 524_288)
        let first = pacer.enqueue(.presence, bytes: 100)
        let second = pacer.enqueue(.presence, bytes: 100)
        XCTAssertEqual(second.replaced, first.id)
        XCTAssertEqual(pacer.queued(.presence).count, 1)
        // retries never add to the in-flight window
        pacer.enqueue(.retry, bytes: 100, requestId: "again")
        XCTAssertEqual(pacer.next(nowMs: 0)?.frameClass, .presence)
        XCTAssertEqual(pacer.next(nowMs: 0)?.frameClass, .retry)
        XCTAssertEqual(pacer.inFlightCount, 0)
    }

    // MARK: ack timer (section 11.3)

    func testAckTimeoutTable() {
        for row in scenario("retransmit_starts_at_write")["timeouts"] as? [[String: Any]] ?? [] {
            XCTAssertEqual(SyncAckTimer.timeoutMs(attempt: Int(int(row["attempt"])), aheadInFlight: Int(int(row["ahead"]))),
                           int(row["expectMs"]), "\(row)")
        }
    }

    private func runAckTimer(_ id: String) {
        var timer = SyncAckTimer()
        var retransmits = 0
        for step in scenario(id)["steps"] as? [[String: Any]] ?? [] {
            let at = int(step["atMs"])
            let expect = step["expect"] as? [String: Any] ?? [:]
            switch step["event"] as? String {
            case "enqueue":
                timer = SyncAckTimer()
            case "writeComplete":
                let deadline = timer.writeComplete(atMs: at, aheadInFlight: Int(int(step["aheadInFlight"])))
                if expect["ackDeadlineMs"] != nil { XCTAssertEqual(deadline, int(expect["ackDeadlineMs"]), "\(id) @\(at)") }
            case "ack":
                timer.acked()
                if expect["retransmits"] != nil { XCTAssertEqual(Int64(retransmits), int(expect["retransmits"])) }
            case "ackTimeout":
                let queued = timer.ackTimeout()
                if queued { retransmits += 1 }
                XCTAssertEqual(queued, expect["retransmitEnqueued"] as? Bool, "\(id) @\(at)")
            case "tick":
                // a tick while a copy is still unwritten must never queue another
                XCTAssertEqual(timer.copyUnwritten, step["copyStillUnwritten"] as? Bool)
                let queued = timer.copyUnwritten ? false : timer.ackTimeout()
                XCTAssertEqual(queued, expect["retransmitEnqueued"] as? Bool, "\(id) @\(at)")
                if expect["attempt"] != nil { XCTAssertEqual(Int64(timer.attempt), int(expect["attempt"])) }
            default:
                XCTFail("unknown ack event \(step)")
            }
        }
    }

    func testAckTimerScenarios() {
        runAckTimer("retransmit_starts_at_write")
        runAckTimer("no_retransmit_while_unwritten")
        var exhausted = SyncAckTimer()
        exhausted.writeComplete(atMs: 0, aheadInFlight: 0)
        XCTAssertTrue(exhausted.ackTimeout())
        exhausted.writeComplete(atMs: 1, aheadInFlight: 0)
        XCTAssertTrue(exhausted.ackTimeout())
        exhausted.writeComplete(atMs: 2, aheadInFlight: 0)
        XCTAssertFalse(exhausted.ackTimeout())
        XCTAssertEqual(exhausted.state, .exhausted)
    }

    // MARK: size check (section 11.4)

    func testObjectTooLargeBoundary() {
        for row in scenario("object_too_large_not_reserved")["cases"] as? [[String: Any]] ?? [] {
            let inner = Int(int(row["innerUtf8Bytes"]))
            let expect = row["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(Int64(OutboundSizeCheck.ciphertextChars(innerUtf8Bytes: inner)), int(expect["ctChars"]))
            XCTAssertEqual(OutboundSizeCheck.fits(innerUtf8Bytes: inner, frameBytesWithoutCiphertext: 300),
                           expect["send"] as? Bool)
        }
        XCTAssertFalse(OutboundSizeCheck.fits(innerUtf8Bytes: 100, frameBytesWithoutCiphertext: 1_048_576))
    }

    // MARK: receive budget (section 12)

    func testReceiveBudgetBulkImport() {
        let s = scenario("receive_budget_bulk_import_no_close")
        let given = s["given"] as? [String: Any] ?? [:]
        let sessions = Int(int(given["activeRemoteSessions"]))
        let sizes = given["frameBytes"] as? [String: Any] ?? [:]
        let limits = SyncReceiveBudget.roomLimits(sessions: sessions)
        let expect = s["expect"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(limits.frames), int(expect["roomFrameLimit"]))
        XCTAssertEqual(Int64(limits.bytes), int(expect["roomByteLimit"]))
        let budget = SyncReceiveBudget()
        var now: Int64 = 0
        for step in s["steps"] as? [[String: Any]] ?? [] {
            var results: [Bool] = []
            for group in ["room", "selfResponse"] {
                for (type, count) in step[group] as? [String: Any] ?? [:] {
                    for _ in 0..<int(count) {
                        results.append(budget.admit(generation: 1, phase: .live, frameType: type,
                                                    byteCount: Int(int(sizes[type])), activeSessions: sessions, nowMs: now))
                    }
                }
            }
            let stepExpect = step["expect"] as? [String: Any] ?? [:]
            if stepExpect["allAdmitted"] as? Bool == true { XCTAssertTrue(results.allSatisfy { $0 }) }
            if stepExpect["admitted"] as? Bool == false { XCTAssertEqual(results.last, false) }
            now += 1
        }
    }

    func testBackgroundDiscardUsesItsOwnBudget() {
        let budget = SyncReceiveBudget()
        for _ in 0..<1_000 {
            XCTAssertTrue(budget.admit(generation: 1, phase: .background, frameType: nil, byteCount: 1_500, activeSessions: 0, nowMs: 0))
        }
        var last = true
        for _ in 0..<3_001 {
            last = budget.admit(generation: 1, phase: .background, frameType: nil, byteCount: 1_500, activeSessions: 0, nowMs: 1)
        }
        XCTAssertFalse(last)
    }

    // MARK: watchdogs (section 9)

    private func runWatchdog(_ id: String) {
        var watchdog = SyncHandshakeWatchdog()
        for step in scenario(id)["steps"] as? [[String: Any]] ?? [] {
            let at = int(step["atMs"])
            let expect = step["expect"] as? [String: Any] ?? [:]
            if step["event"] as? String != "socketCreated", expect["timedOut"] as? Bool != true {
                XCTAssertNil(watchdog.check(nowMs: at), "\(id): fired early @\(at)")
            }
            switch step["event"] as? String {
            case "socketCreated": watchdog.socketCreated(atMs: at)
            case "open": watchdog.opened(atMs: at)
            case "snapshotBegin", "snapshotPage": watchdog.progress(atMs: at)
            case "snapshotEnd": watchdog.snapshotEnded(atMs: at)
            case "helloWritten": watchdog.helloWritten(atMs: at)
            case "helloAck": watchdog.connected()
            case "tick": break
            default: XCTFail("unknown watchdog event \(step)")
            }
            if expect["timedOut"] as? Bool == true {
                let fired = watchdog.check(nowMs: at)
                XCTAssertNotNil(fired, "\(id) @\(at)")
                XCTAssertEqual(fired?.localClose.rawValue, expect["localClose"] as? String, "\(id) @\(at)")
            }
            if expect["status"] as? String == "CONNECTED" {
                XCTAssertTrue(watchdog.finished)
            }
        }
    }

    func testWatchdogScenarios() {
        runWatchdog("handshake_progress_watchdog")
        runWatchdog("handshake_stall_fires")
        runWatchdog("android_connect_open_timeout")
        runWatchdog("hello_ack_timeout")
    }

    // MARK: hello epoch (section 14)

    func testHelloEpochVectors() {
        for vector in section("helloEpoch")["vectors"] as? [[String: Any]] ?? [] {
            let name = vector["name"] as? String ?? "?"
            let persisted = (vector["persisted"] as? String).flatMap { UInt64($0, radix: 16) }
            let rejected = (vector["rejected"] as? String).flatMap { UInt64($0, radix: 16) }
            do {
                let reservation = try HelloEpochPolicy.next(
                    persisted: persisted, nowMs: int(vector["nowMs"]),
                    after4014: vector["after4014"] as? Bool ?? false,
                    rejected: rejected, bgOptIn: vector["bgOptIn"] as? Bool ?? false)
                XCTAssertNil(vector["expectError"], name)
                XCTAssertEqual(HelloEpochPolicy.hex(reservation.next), vector["expectNext"] as? String, name)
                XCTAssertEqual(HelloEpochPolicy.hex(reservation.persisted), vector["expectPersisted"] as? String, name)
                if let spares = vector["expectSpares"] as? [String: Any] {
                    XCTAssertEqual(HelloEpochPolicy.hex(reservation.next + 1), spares["first"] as? String, name)
                    XCTAssertEqual(HelloEpochPolicy.hex(reservation.persisted), spares["last"] as? String, name)
                    XCTAssertEqual(Int64(reservation.persisted - reservation.next), int(spares["count"]), name)
                }
            } catch {
                XCTAssertNotNil(vector["expectError"], "\(name) threw \(error)")
            }
        }
    }

    func testReplayStateHonoursTheEpochFloorAndKeepsPlainIncrements() throws {
        let roomId = SyncIdentity.urlB64Encode(Data(repeating: 0x33, count: 32))
        let seed = Data(repeating: 0x44, count: 32)
        let pub = try XCTUnwrap(SyncSigning.publicKey(seed))
        let actor = SyncIdentity.actorId(roomIdRaw: Data(repeating: 0x33, count: 32),
                                         pubkeyRaw: try XCTUnwrap(SyncSigning.publicKeyRaw(seed)))
        let state = SyncReplayState(roomId: roomId)
        XCTAssertEqual(try state.reserveHelloEpoch(actorId: actor, pubkey: pub), "0000000000000001")
        XCTAssertEqual(try state.reserveHelloEpoch(actorId: actor, pubkey: pub, floor: 0x1c76d60), "0000000001c76d60")
        XCTAssertEqual(try state.reserveHelloEpoch(actorId: actor, pubkey: pub, floor: 5), "0000000001c76d61")
    }

    // MARK: chat (section 15)

    func testChatFencePruning() {
        let cases = scenario("chat_fence_pruning")["cases"] as? [[String: Any]] ?? []
        let first = cases[0]
        let fences = (first["fences"] as? [[String]] ?? []).map {
            ChatReplayPruner.FenceIdentity(actorId: $0[0], sessionDomain: $0[1], keyId: $0[2])
        }
        let durable = first["durableSessions"] as? [String: String] ?? [:]
        let retained = ChatReplayPruner.retained(fences, durableSessionDomain: { durable[$0] })
        let expected = (first["expectRetained"] as? [[String]] ?? []).map {
            ChatReplayPruner.FenceIdentity(actorId: $0[0], sessionDomain: $0[1], keyId: $0[2])
        }
        XCTAssertEqual(Set(retained), Set(expected))
        for row in cases.dropFirst() {
            let admission = ChatReplayPruner.admitNewIdentity(
                currentCount: Int(int(row["fenceCount"])), supersededCount: Int(int(row["superseded"])))
            let expect = row["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(admission == .accepted, expect["result"] as? String == "accepted", "\(row)")
        }
    }

    func testChatHistoryBytePrune() {
        let s = scenario("chat_history_byte_prune")
        let given = s["given"] as? [String: Any] ?? [:]
        let sizes = (given["messageEncodedBytes"] as? [NSNumber] ?? []).map(\.intValue)
        let drop = ChatHistoryBudget.dropCount(
            fixedOverheadBytes: Int(int(given["fixedOverheadBytes"])),
            separatorBytes: Int(int(given["separatorBytes"])),
            messageBytes: sizes)
        let expect = s["expect"] as? [String: Any] ?? [:]
        XCTAssertEqual(Int64(drop), int(expect["dropOldest"]))
        XCTAssertEqual(Int64(sizes.count - drop), int(expect["keep"]))
        let kept = sizes.dropFirst(drop)
        let total = Int(int(given["fixedOverheadBytes"])) + kept.reduce(0, +) + max(0, kept.count - 1)
        XCTAssertLessThanOrEqual(Int64(total), int(expect["resultBytesAtMost"]))
        XCTAssertEqual(ChatHistoryBudget.dropCount(fixedOverheadBytes: 10, separatorBytes: 1, messageBytes: [5, 5]), 0)
    }

    // MARK: lifecycle (section 13)

    func testIosTransientInactivePolicy() {
        for row in scenario("ios_transient_inactive_keeps_session")["cases"] as? [[String: Any]] ?? [] {
            let authBound = row["keyMode"] as? String == "auth"
            let appLock = row["appLock"] as? Bool ?? false
            var everNotReady = false
            for phaseName in row["phases"] as? [String] ?? [] {
                let phase = SyncScenePhase(rawValue: phaseName)!
                // RootGate locks an auth-bound key on any non-active phase,
                // App Lock only arms on background
                let unlocked = !(authBound && phase != .active)
                let overlay = appLock && phase == .background
                let ready = SyncLifecyclePolicy.iosForegroundReady(
                    phase: phase, dataKeyAuthBound: authBound, dataKeyUnlocked: unlocked,
                    appLockOverlay: overlay, storesLocked: false)
                if !ready { everNotReady = true }
            }
            let expect = row["expect"] as? [String: Any] ?? [:]
            XCTAssertEqual(everNotReady, expect["socketClosed"] as? Bool, "\(row)")
            XCTAssertEqual(everNotReady, expect["newGeneration"] as? Bool, "\(row)")
        }
    }

    // MARK: v2 (section 16)

    func testLegacyV2CasingAndTieVectors() {
        let v2 = section("v2")
        let vectors = v2["vectors"] as? [String: Any] ?? [:]
        for row in vectors["casing"] as? [[String: Any]] ?? [] {
            let key = LegacyV2Ids.stateKey(row["raw"] as! String)
            XCTAssertEqual(key != nil, row["accept"] as? Bool, "\(row)")
            if let expected = row["key"] as? String { XCTAssertEqual(key, expected) }
        }
        for row in vectors["tie"] as? [[String: Any]] ?? [] {
            let last = row["last"] as? [String: Any] ?? [:]
            let incoming = row["incoming"] as? [String: Any] ?? [:]
            XCTAssertEqual(LegacyV2Ids.beats(
                v: int(incoming["v"]), by: incoming["by"] as! String,
                lastV: int(last["v"]), lastBy: last["by"] as? String), row["apply"] as? Bool, "\(row)")
        }
        XCTAssertEqual(LegacyV2Ids.outboundId(UUID(uuidString: "3F2A1B4C-0D5E-4F60-8A7B-9C8D7E6F5A4B")!),
                       "3f2a1b4c-0d5e-4f60-8a7b-9c8d7e6f5a4b")
    }

    // MARK: coverage of the scenario list

    func testEverySp2ScenarioHasAnIosRunner() {
        // scenario -> the test methods that run it. Checked against the ObjC
        // runtime so a renamed or deleted runner fails here, not silently.
        let runners: [String: [(AnyClass, String)]] = [
            "backoff_full_jitter_transient": [(Self.self, "testBackoffScenarios")],
            "backoff_not_reset_at_hello_ack": [(Self.self, "testBackoffScenarios"),
                                               (SyncManagerSessionTests.self, "testBackoffIsNotResetByHelloAck")],
            "backoff_reset_after_stable_30s": [(Self.self, "testBackoffScenarios"),
                                               (SyncManagerProveItTests.self, "testThirtySecondsConnectedIsAStableSession")],
            "backoff_slow_busy_503": [(Self.self, "testBackoffScenarios"),
                                      (SyncManagerSessionTests.self, "test503UpgradeBacksOffSlowlyAndSurfacesBusyOnTheSecond")],
            "close_and_status_table": [(Self.self, "testCloseAndStatusTable"),
                                       (SyncManagerContractTableTests.self, "testEveryCloseCodeRowThroughTheManager"),
                                       (SyncManagerContractTableTests.self, "testEveryUpgradeStatusRowThroughTheManager")],
            "session_conflict_stops_after_three": [(Self.self, "testSessionConflictStopsAfterThree"),
                                                   (SyncManagerSessionTests.self, "testSessionReplacedThreeTimesInTenMinutesStops")],
            "stale_nack_no_reconnect": [(SyncManagerContractTableTests.self, "testStaleNackNoReconnectScript")],
            "stale_on_own_persisted_stamp_is_confirmed": [(Self.self, "testStaleOnOwnPersistedStampIsConfirmed"),
                                                          (SyncManagerProveItTests.self, "testStaleOnOurOwnRecoveryResendIsConfirmedNotAReconnect")],
            "counter_window_pauses_mutations_once": [(SyncManagerContractTableTests.self, "testCounterWindowScriptKeepsPresenceGoing")],
            "seq_regression_surfaced_once_per_join": [(SyncManagerContractTableTests.self, "testSeqRegressionScript")],
            "poison_record_skipped": [(SyncManagerSessionTests.self, "testPoisonRecordsAreSkippedAndTheHandshakeCompletes"),
                                      (SyncHostileRecordTests.self, "testEverySkipCategoryInsideASnapshotIsSkippedAndTheHandshakeCompletes")],
            "structural_snapshot_stops_after_three": [(Self.self, "testStructuralSnapshotStopsAfterThree"),
                                                      (SyncManagerSessionTests.self, "testDuplicateWireIdRejectsTheWholeSnapshotThenStopsAfterThree")],
            "layer_metadata_staged_in_item_order": [(SyncManagerSessionTests.self, "testSnapshotLayerMetadataIsStagedInItemOrder")],
            "live_window_resync_cooldown": [(Self.self, "testLiveWindowResyncCooldown"),
                                            (SyncManagerSessionTests.self, "testSignedLiveFrameBeyondOurWindowTriggersOneResync")],
            "pacer_bulk_500_small": [(Self.self, "testPacerBulkSmallFrames"),
                                     (SyncManagerSessionTests.self, "testBulkPublishIsPacedBelowTheRelayWindow")],
            "pacer_large_frames_bytes": [(Self.self, "testPacerLargeFramesAreByteBound")],
            "pacer_interactive_reserve": [(Self.self, "testPacerInteractiveReserveLetsPresenceAndChatThrough")],
            "retransmit_starts_at_write": [(Self.self, "testAckTimeoutTable"), (Self.self, "testAckTimerScenarios"),
                                           (SyncManagerSessionTests.self, "testRetransmitTimerStartsWhenTheFrameIsActuallyWritten")],
            "no_retransmit_while_unwritten": [(Self.self, "testAckTimerScenarios")],
            "receive_budget_bulk_import_no_close": [(Self.self, "testReceiveBudgetBulkImport"),
                                                    (SyncManagerSessionTests.self, "testPeerBulkImportDoesNotTripTheReceiveBudget")],
            "handshake_progress_watchdog": [(Self.self, "testWatchdogScenarios"),
                                            (SyncManagerSessionTests.self, "testSlowButProgressingSnapshotIsNotKilledByTheWatchdog")],
            "handshake_stall_fires": [(Self.self, "testWatchdogScenarios"),
                                      (SyncManagerSessionTests.self, "testStalledHandshakeFiresAfterSixtySecondsWithoutProgress")],
            "android_connect_open_timeout": [(Self.self, "testWatchdogScenarios"),
                                             (SyncManagerContractTableTests.self, "testEveryLocalCloseRowReconnectsByItsOwnReason")],
            "hello_ack_timeout": [(Self.self, "testWatchdogScenarios"),
                                  (SyncManagerContractTableTests.self, "testEveryLocalCloseRowReconnectsByItsOwnReason")],
            "reachability_shortcuts_transient_only": [(Self.self, "testReachabilityScenario"),
                                                      (SyncManagerProveItTests.self, "testNetworkReturnNeverShortensASlowClassBackoff")],
            "hello_epoch_vectors": [(Self.self, "testHelloEpochVectors"),
                                    (SyncManagerProveItTests.self, "testStaleEpochDoublesThreeTimesThenStopsWithSessionCounterBehind")],
            "chat_fence_pruning": [(Self.self, "testChatFencePruning"),
                                   (TacMapChatRetentionTests.self, "testSupersededFencesArePrunedSoANewSessionStillGetsThrough")],
            "chat_history_byte_prune": [(Self.self, "testChatHistoryBytePrune"),
                                        (TacMapChatRetentionTests.self, "testHistoryIsPrunedByEncodedBytesInsteadOfFailingForGood")],
            "ios_transient_inactive_keeps_session": [(Self.self, "testIosTransientInactivePolicy"),
                                                     (SyncManagerSessionTests.self, "testTransientInactiveKeepsTheSessionAndChatKey")],
            "object_too_large_not_reserved": [(Self.self, "testObjectTooLargeBoundary"),
                                              (SyncManagerSessionTests.self, "testObjectTooLargeIsNeitherReservedNorSent")],
            "v2_casing_and_tie": [(Self.self, "testLegacyV2CasingAndTieVectors"),
                                  (SyncLegacyV2SessionTests.self, "testOutboundIdsAreLowercaseAndAndroidRecordsAreNotEchoedAsDeletes"),
                                  (SyncLegacyV2SessionTests.self, "testEqualVersionTieGoesToTheHigherWriterLikeTheRelay")]
        ]
        // android only, its runner lives in the android suite
        let androidOnly: Set<String> = ["android_pause_keeps_room"]
        for s in contract["scenarios"] as? [[String: Any]] ?? [] where s["requiredBy"] as? String == "SP2" {
            let id = s["id"] as? String ?? "?"
            if androidOnly.contains(id) { continue }
            let list = runners[id] ?? []
            XCTAssertFalse(list.isEmpty, "no iOS runner for \(id)")
            for (cls, name) in list {
                // a throwing test bridges to ObjC as nameAndReturnError:
                let exists = cls.instancesRespond(to: NSSelectorFromString(name))
                    || cls.instancesRespond(to: NSSelectorFromString(name + "AndReturnError:"))
                XCTAssertTrue(exists, "\(id): \(cls).\(name) is gone")
            }
        }
    }
}
