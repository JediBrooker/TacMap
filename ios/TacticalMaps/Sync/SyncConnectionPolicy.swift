import Foundation

// Pure connection policy for Unit Sync (contract plans/04-sync-client-contract.md
// sections 7, 8, 9, 12, 13, 14). No clocks, no sockets, no I/O in here, the
// manager feeds times in. Numbers mirror testdata/sync_client_behaviour.json
// and SyncClientBehaviourTests checks they still match.

enum SyncBackoffClass: String, CaseIterable, Equatable {
    case transient
    case slowBusy = "slow_busy"
    case slowRate = "slow_rate"
    case slowStorage = "slow_storage"
    case slowUnknown = "slow_unknown"
}

/// Issue keys from the contract. The manager maps each one to a message.
enum SyncIssueCode: String, CaseIterable, Equatable {
    case skippedUnsupported = "SKIPPED_UNSUPPORTED"
    case skippedUnverified = "SKIPPED_UNVERIFIED"
    case roomResetSuspected = "ROOM_RESET_SUSPECTED"
    case roomResetChangesPaused = "ROOM_RESET_CHANGES_PAUSED"
    case objectTooLarge = "OBJECT_TOO_LARGE"
    case roomQuotaNack = "ROOM_QUOTA_NACK"
    case relayInvalidNack = "RELAY_INVALID_NACK"
    case unconfirmedReconnect = "UNCONFIRMED_RECONNECT"
    case snapshotStructural = "SNAPSHOT_STRUCTURAL"
    case relayBusy = "RELAY_BUSY"
    case relayRateLimited = "RELAY_RATE_LIMITED"
    case roomFullCannotJoin = "ROOM_FULL_CANNOT_JOIN"
    case relayRefusedRoom = "RELAY_REFUSED_ROOM"
    case identityRejected = "IDENTITY_REJECTED"
    case sessionConflict = "SESSION_CONFLICT"
    case sessionCounterBehind = "SESSION_COUNTER_BEHIND"
    case snapshotMalformedStopped = "SNAPSHOT_MALFORMED_STOPPED"
    case backgroundPaused = "BACKGROUND_PAUSED"
    case chatReplayFull = "CHAT_REPLAY_FULL"
    case chatRecipientInBackground = "CHAT_RECIPIENT_IN_BACKGROUND"

    /// SECURITY vs CONNECTION. Chat ones are neither, they never hit the banner kind.
    var isSecurity: Bool {
        switch self {
        case .skippedUnverified, .roomResetSuspected, .snapshotStructural,
             .identityRejected, .snapshotMalformedStopped:
            return true
        default:
            return false
        }
    }
}

// MARK: - Backoff (section 8)

struct SyncBackoffPolicy {
    struct Params: Equatable {
        let floorMs: Double
        let spreadMs: Double
        let capMs: Double
    }

    struct PendingReconnect: Equatable {
        let backoffClass: SyncBackoffClass
        var dueMs: Int64
    }

    static let attemptCap = 20
    static let stableSessionMs: Int64 = 30_000
    static let reachabilityMinSpacingMs: Int64 = 2_000

    static func params(_ backoffClass: SyncBackoffClass) -> Params {
        switch backoffClass {
        case .transient: return Params(floorMs: 250, spreadMs: 750, capMs: 30_000)
        case .slowBusy: return Params(floorMs: 15_000, spreadMs: 15_000, capMs: 300_000)
        case .slowRate: return Params(floorMs: 60_000, spreadMs: 30_000, capMs: 300_000)
        case .slowStorage: return Params(floorMs: 30_000, spreadMs: 30_000, capMs: 300_000)
        case .slowUnknown: return Params(floorMs: 30_000, spreadMs: 30_000, capMs: 300_000)
        }
    }

    /// Full jitter between the floor and the exponential ceiling.
    static func delayMs(_ backoffClass: SyncBackoffClass, attempt: Int, random: Double) -> Double {
        let p = params(backoffClass)
        let unit = random.isFinite ? min(1, max(0, random)) : 0
        let ceiling = p.spreadMs * pow(2, Double(min(max(0, attempt), attemptCap)))
        return p.floorMs + unit * min(p.capMs - p.floorMs, ceiling)
    }

    private(set) var attempt: Int
    private(set) var connectedSinceMs: Int64?
    private(set) var lastAttemptStartMs: Int64?
    private(set) var pending: PendingReconnect?

    init(attempt: Int = 0) {
        self.attempt = min(max(0, attempt), Self.attemptCap)
    }

    mutating func attemptStarted(atMs now: Int64) {
        lastAttemptStartMs = now
        pending = nil
    }

    /// Schedule the next reconnect. Shared attempt counter across classes.
    @discardableResult
    mutating func failure(_ backoffClass: SyncBackoffClass, random: Double, nowMs: Int64) -> Double {
        let delay = Self.delayMs(backoffClass, attempt: attempt, random: random)
        attempt = min(attempt + 1, Self.attemptCap)
        connectedSinceMs = nil
        pending = PendingReconnect(
            backoffClass: backoffClass,
            dueMs: nowMs + Int64(delay.rounded(.up))
        )
        return delay
    }

    /// hello-ack. Starts the stable-session clock but does NOT reset anything.
    mutating func connected(atMs now: Int64) {
        connectedSinceMs = now
    }

    /// First op-ack of a session proves it works end to end.
    mutating func opAcknowledged() {
        attempt = 0
    }

    mutating func tick(nowMs: Int64) {
        if let since = connectedSinceMs, nowMs - since >= Self.stableSessionMs {
            attempt = 0
        }
    }

    mutating func sessionEnded() {
        connectedSinceMs = nil
    }

    /// Leave, rejoin or the user tapping Retry.
    mutating func reset() {
        attempt = 0
        connectedSinceMs = nil
        pending = nil
    }

    /// Network came back. Only a pending transient reconnect gets pulled in,
    /// never earlier than 2 s after the last attempt started. Returns the due
    /// time of whatever reconnect is pending (moved or not).
    @discardableResult
    mutating func reachabilityRegained(nowMs: Int64) -> Int64? {
        guard var current = pending else { return nil }
        guard current.backoffClass == .transient else { return current.dueMs }
        let earliest = max(nowMs, (lastAttemptStartMs ?? nowMs) + Self.reachabilityMinSpacingMs)
        if earliest < current.dueMs {
            current.dueMs = earliest
            pending = current
        }
        return current.dueMs
    }
}

// MARK: - Close codes and upgrade statuses (section 7)

struct SyncConnectionDecision: Equatable {
    enum Action: String, Equatable {
        case none
        case reconnect
        case reconnectNow = "reconnect_now"
        case stop
        case escalateEpoch = "escalate_epoch_then_reconnect"
    }

    var action: Action
    var backoffClass: SyncBackoffClass?
    /// Issue to show right now (a stop issue, or a surface threshold just hit).
    var issue: SyncIssueCode?
    var retryOnForeground = false
    var pacerAfter4008 = false

    static let none = SyncConnectionDecision(action: .none)

    static func reconnect(_ backoffClass: SyncBackoffClass) -> SyncConnectionDecision {
        SyncConnectionDecision(action: .reconnect, backoffClass: backoffClass)
    }

    static func stop(_ issue: SyncIssueCode, retryOnForeground: Bool) -> SyncConnectionDecision {
        SyncConnectionDecision(action: .stop, issue: issue, retryOnForeground: retryOnForeground)
    }
}

/// Why the client closed its own socket. The transport echo of our close is
/// never classified, only this reason is.
enum SyncLocalCloseAction: String, Equatable, CaseIterable {
    case leave
    case lifecyclePause
    case receiveBudgetExceeded
    case oversizedInbound
    case binaryInbound
    case connectOpenTimeout
    case handshakeStall
    case helloAckTimeout
    case handshakeAbsolute
    case livenessTimeout
    case ackExhausted
    case structuralSnapshot
    case liveWindowResync
    case sessionNack
    case helloRequiredNack

    /// RFC 6455 code we put on the wire for it.
    var closeCode: Int {
        switch self {
        case .receiveBudgetExceeded: return 1008
        case .oversizedInbound: return 1009
        case .binaryInbound: return 1003
        case .leave, .lifecyclePause: return 1001
        case .liveWindowResync: return 1000
        default: return 1011
        }
    }
}

struct SyncCloseClassifier {
    static let sessionConflictsBeforeStop = 3
    static let sessionConflictWindowMs: Int64 = 600_000
    static let staleEpochEscalationsBeforeStop = 3
    static let structuralSnapshotFailuresBeforeStop = 3

    private struct Row {
        var action: SyncConnectionDecision.Action
        var backoffClass: SyncBackoffClass?
        var issue: SyncIssueCode?
        var surfaceAfterConsecutive: Int?
        var retryOnForeground = false
        var pacerAfter4008 = false
        var sessionConflict = false
    }

    private var conflictTimes: [Int64] = []
    private var busyStreak: [SyncIssueCode: Int] = [:]
    private var surfacedThisChain = Set<SyncIssueCode>()
    private(set) var epochEscalations = 0
    private(set) var structuralFailures = 0

    /// Socket never opened. nil status = DNS/TCP/TLS/timeout.
    mutating func upgradeFailed(httpStatus: Int?, nowMs: Int64) -> SyncConnectionDecision {
        apply(Self.row(httpStatus: httpStatus), nowMs: nowMs)
    }

    /// Socket opened, then the relay (or the network) closed it.
    mutating func closed(code: Int, afterOwnLeave: Bool, nowMs: Int64) -> SyncConnectionDecision {
        if code == 1000 && afterOwnLeave { return .none }
        return apply(Self.row(closeCode: code), nowMs: nowMs)
    }

    /// session-mismatch / session-replaced nack.
    mutating func sessionNack(nowMs: Int64) -> SyncConnectionDecision {
        var row = Row(action: .reconnect, backoffClass: .transient)
        row.sessionConflict = true
        row.retryOnForeground = true
        return apply(row, nowMs: nowMs)
    }

    /// Structural snapshot failure. Reconnect, stop on the third in a row.
    mutating func structuralSnapshotFailure() -> SyncConnectionDecision {
        structuralFailures += 1
        if structuralFailures >= Self.structuralSnapshotFailuresBeforeStop {
            return .stop(.snapshotMalformedStopped, retryOnForeground: false)
        }
        return .reconnect(.transient)
    }

    /// hello-ack. Ends the failure chain.
    mutating func connectionSucceeded() {
        busyStreak.removeAll()
        surfacedThisChain.removeAll()
        structuralFailures = 0
    }

    /// Retry button / foreground retry / new join.
    mutating func reset() {
        conflictTimes.removeAll()
        busyStreak.removeAll()
        surfacedThisChain.removeAll()
        epochEscalations = 0
        structuralFailures = 0
    }

    private mutating func apply(_ row: Row, nowMs: Int64) -> SyncConnectionDecision {
        if row.sessionConflict {
            conflictTimes = conflictTimes.filter { nowMs - $0 < Self.sessionConflictWindowMs }
            conflictTimes.append(nowMs)
            if conflictTimes.count >= Self.sessionConflictsBeforeStop {
                return .stop(.sessionConflict, retryOnForeground: true)
            }
        }
        switch row.action {
        case .stop:
            return .stop(row.issue ?? .relayRefusedRoom, retryOnForeground: row.retryOnForeground)
        case .escalateEpoch:
            if epochEscalations >= Self.staleEpochEscalationsBeforeStop {
                return .stop(.sessionCounterBehind, retryOnForeground: false)
            }
            epochEscalations += 1
            return SyncConnectionDecision(action: .escalateEpoch, backoffClass: .transient)
        case .none, .reconnect, .reconnectNow:
            break
        }
        var decision = SyncConnectionDecision(
            action: row.action,
            backoffClass: row.backoffClass,
            pacerAfter4008: row.pacerAfter4008
        )
        if let issue = row.issue, let threshold = row.surfaceAfterConsecutive {
            let streak = (busyStreak[issue] ?? 0) + 1
            busyStreak = [issue: streak]
            if streak >= threshold, surfacedThisChain.insert(issue).inserted {
                decision.issue = issue
            }
        } else {
            // a different kind of failure breaks the busy streak
            busyStreak.removeAll()
        }
        return decision
    }

    private static func row(httpStatus: Int?) -> Row {
        guard let status = httpStatus else { return Row(action: .reconnect, backoffClass: .transient) }
        switch status {
        case 401, 403, 404, 426:
            return Row(action: .stop, issue: .relayRefusedRoom)
        case 429:
            return Row(action: .reconnect, backoffClass: .slowRate,
                       issue: .relayRateLimited, surfaceAfterConsecutive: 1)
        case 503:
            return Row(action: .reconnect, backoffClass: .slowBusy,
                       issue: .relayBusy, surfaceAfterConsecutive: 2)
        case 500...599:
            return Row(action: .reconnect, backoffClass: .transient)
        case 400...499:
            return Row(action: .reconnect, backoffClass: .slowUnknown)
        default:
            // 1xx/2xx/3xx without an open socket is just a broken upgrade
            return Row(action: .reconnect, backoffClass: .transient)
        }
    }

    private static func row(closeCode: Int) -> Row {
        switch closeCode {
        case 1011:
            return Row(action: .reconnect, backoffClass: .slowStorage,
                       issue: .relayBusy, surfaceAfterConsecutive: 2)
        case 1013:
            return Row(action: .reconnect, backoffClass: .slowBusy,
                       issue: .relayBusy, surfaceAfterConsecutive: 2)
        case 4008:
            var row = Row(action: .reconnect, backoffClass: .transient)
            row.pacerAfter4008 = true
            return row
        case 4009:
            return Row(action: .reconnect, backoffClass: .slowUnknown)
        case 4010, 4011:
            return Row(action: .stop, issue: .identityRejected)
        case 4012:
            return Row(action: .reconnect, backoffClass: .transient)
        case 4013:
            var row = Row(action: .stop, issue: .roomFullCannotJoin)
            row.retryOnForeground = true
            return row
        case 4014:
            return Row(action: .escalateEpoch, backoffClass: .transient)
        case 4015:
            var row = Row(action: .reconnect, backoffClass: .transient)
            row.sessionConflict = true
            row.retryOnForeground = true
            return row
        case 4000...4999:
            return Row(action: .reconnect, backoffClass: .slowUnknown)
        default:
            // 1000 (not ours), 1001, 1005, 1006, 1007, 1012, other 1xxx, and the
            // 0 iOS reports when the link just died
            return Row(action: .reconnect, backoffClass: .transient)
        }
    }
}

/// PAUSED_ACTION_REQUIRED: socket closed, room/keys/replay kept, the issue
/// sits on screen with Retry until the user taps it (or, for some rows, the
/// next foreground return retries once).
struct SyncPausedState: Equatable {
    let issue: SyncIssueCode
    let retryOnForeground: Bool
}

// MARK: - Handshake watchdog (section 9)

struct SyncHandshakeWatchdog {
    enum Fire: String, Equatable {
        case connectOpenTimeout
        case handshakeStall
        case helloAckTimeout
        case handshakeAbsolute

        var localClose: SyncLocalCloseAction {
            switch self {
            case .connectOpenTimeout: return .connectOpenTimeout
            case .handshakeStall: return .handshakeStall
            case .helloAckTimeout: return .helloAckTimeout
            case .handshakeAbsolute: return .handshakeAbsolute
            }
        }
    }

    static let connectOpenTimeoutMs: Int64 = 30_000
    static let handshakeStallTimeoutMs: Int64 = 60_000
    static let helloAckTimeoutMs: Int64 = 30_000
    static let handshakeAbsoluteMaxMs: Int64 = 900_000

    private var createdMs: Int64?
    private(set) var opened = false
    private var lastProgressMs: Int64?
    private var stallSuspended = false
    private var helloWrittenMs: Int64?
    private(set) var finished = false

    mutating func socketCreated(atMs now: Int64) {
        self = SyncHandshakeWatchdog()
        createdMs = now
    }

    mutating func opened(atMs now: Int64) {
        guard !opened else { return }
        opened = true
        lastProgressMs = now
    }

    /// Any complete inbound message, or a finished local validation step.
    mutating func progress(atMs now: Int64) {
        opened(atMs: now)
        lastProgressMs = now
    }

    /// Local validation and apply time never counts against the network.
    mutating func snapshotEnded(atMs now: Int64) {
        progress(atMs: now)
        stallSuspended = true
    }

    mutating func helloWritten(atMs now: Int64) {
        helloWrittenMs = now
        stallSuspended = true
    }

    mutating func connected() {
        finished = true
    }

    func nextDeadline() -> (atMs: Int64, fire: Fire)? {
        guard !finished, let createdMs else { return nil }
        var best: (atMs: Int64, fire: Fire) = (createdMs + Self.handshakeAbsoluteMaxMs, .handshakeAbsolute)
        func consider(_ at: Int64, _ fire: Fire) {
            if at < best.atMs { best = (at, fire) }
        }
        if !opened {
            consider(createdMs + Self.connectOpenTimeoutMs, .connectOpenTimeout)
        } else if !stallSuspended, let lastProgressMs {
            consider(lastProgressMs + Self.handshakeStallTimeoutMs, .handshakeStall)
        }
        if let helloWrittenMs {
            consider(helloWrittenMs + Self.helloAckTimeoutMs, .helloAckTimeout)
        }
        return best
    }

    func check(nowMs: Int64) -> Fire? {
        guard let deadline = nextDeadline(), nowMs >= deadline.atMs else { return nil }
        return deadline.fire
    }
}

/// Live heartbeat numbers (section 9 + 21.3).
enum SyncHeartbeatPolicy {
    static let foregroundPingIntervalMs: Int64 = 20_000
    static let backgroundPingIntervalMs: Int64 = 60_000
    static let deadAfterMsWithoutPongOrFrame: Int64 = 30_000
    static let pathChangeProbeTimeoutMs: Int64 = 5_000
}

// MARK: - Receive budget (section 12)

final class SyncReceiveBudget {
    enum Phase: Equatable { case initial, live, background }

    static let windowMs: Int64 = 10_000
    static let selfResponseTypes: Set<String> = [
        "op-ack", "op-nack", "chat-ack", "chat-nack", "chat-key-ack", "chat-key-nack", "hello-ack"
    ]
    static let initialMaxFrames = 10_000
    static let initialMaxBytes = 54_525_952
    static let selfResponseMaxFrames = 400
    static let selfResponseMaxBytes = 1_048_576
    static let backgroundMaxFrames = 4_000
    static let backgroundMaxBytes = 50_331_648
    static let roomBaseFrames = 600
    static let roomPerSessionFrames = 25
    static let roomMaxFrames = 4_000
    static let roomBaseBytes = 12_582_912
    static let roomPerSessionBytes = 262_144
    static let roomMaxBytes = 50_331_648

    static func roomLimits(sessions: Int) -> (frames: Int, bytes: Int) {
        let s = max(0, sessions)
        return (
            min(roomMaxFrames, roomBaseFrames + roomPerSessionFrames * s),
            min(roomMaxBytes, roomBaseBytes + roomPerSessionBytes * s)
        )
    }

    private var generation: Int64?
    private var phase: Phase?
    private var windowStartMs: Int64 = 0
    private var roomFrames = 0
    private var roomBytes = 0
    private var selfFrames = 0
    private var selfBytes = 0
    private var initialFrames = 0
    private var initialBytes = 0

    /// frameType is the parsed `t` (nil if the frame did not parse).
    func admit(
        generation newGeneration: Int64,
        phase newPhase: Phase,
        frameType: String?,
        byteCount: Int,
        activeSessions: Int,
        nowMs: Int64
    ) -> Bool {
        guard byteCount >= 0 else { return false }
        if generation != newGeneration || phase != newPhase {
            generation = newGeneration
            phase = newPhase
            windowStartMs = nowMs
            roomFrames = 0; roomBytes = 0
            selfFrames = 0; selfBytes = 0
            initialFrames = 0; initialBytes = 0
        }
        switch newPhase {
        case .initial:
            guard initialFrames < Self.initialMaxFrames,
                  byteCount <= Self.initialMaxBytes - initialBytes else { return false }
            initialFrames += 1
            initialBytes += byteCount
            return true
        case .background:
            rollWindow(nowMs)
            guard roomFrames < Self.backgroundMaxFrames,
                  byteCount <= Self.backgroundMaxBytes - roomBytes else { return false }
            roomFrames += 1
            roomBytes += byteCount
            return true
        case .live:
            rollWindow(nowMs)
            if let frameType, Self.selfResponseTypes.contains(frameType) {
                guard selfFrames < Self.selfResponseMaxFrames,
                      byteCount <= Self.selfResponseMaxBytes - selfBytes else { return false }
                selfFrames += 1
                selfBytes += byteCount
                return true
            }
            let limits = Self.roomLimits(sessions: activeSessions)
            guard roomFrames < limits.frames,
                  byteCount <= limits.bytes - roomBytes else { return false }
            roomFrames += 1
            roomBytes += byteCount
            return true
        }
    }

    private func rollWindow(_ nowMs: Int64) {
        if nowMs < windowStartMs || nowMs - windowStartMs >= Self.windowMs {
            windowStartMs = nowMs
            roomFrames = 0; roomBytes = 0
            selfFrames = 0; selfBytes = 0
        }
    }
}

// MARK: - Hello epoch (section 14)

enum HelloEpochPolicy {
    static let backgroundSpareBlock: UInt64 = 64
    static let maxEscalationsPerJoin = SyncCloseClassifier.staleEpochEscalationsBeforeStop

    enum Failure: Error, Equatable { case exhausted }

    struct Reservation: Equatable {
        let next: UInt64
        let persisted: UInt64
    }

    /// Lower bound the next epoch has to clear on top of persisted + 1.
    /// Time floor only for a fresh/lost state or after 4014, so normal
    /// reservations keep stepping by one.
    static func floor(persisted: UInt64?, nowMs: Int64, after4014: Bool, rejected: UInt64?) throws -> UInt64 {
        var result: UInt64 = 0
        if persisted == nil || after4014 {
            result = UInt64(max(0, nowMs) / 60_000)
        }
        if after4014, let rejected {
            let (doubled, overflow) = rejected.multipliedReportingOverflow(by: 2)
            guard !overflow else { throw Failure.exhausted }
            result = max(result, doubled)
        }
        return result
    }

    static func next(
        persisted: UInt64?,
        nowMs: Int64,
        after4014: Bool,
        rejected: UInt64?,
        bgOptIn: Bool
    ) throws -> Reservation {
        let base: UInt64
        if let persisted {
            guard persisted < UInt64.max else { throw Failure.exhausted }
            base = persisted + 1
        } else {
            base = 1
        }
        let next = max(base, try floor(persisted: persisted, nowMs: nowMs, after4014: after4014, rejected: rejected))
        guard bgOptIn else { return Reservation(next: next, persisted: next) }
        let (withSpare, overflow) = next.addingReportingOverflow(backgroundSpareBlock)
        guard !overflow else { throw Failure.exhausted }
        return Reservation(next: next, persisted: withSpare)
    }

    static func hex(_ value: UInt64) -> String { String(format: "%016llx", value) }
}

// MARK: - Lifecycle (section 13)

enum SyncScenePhase: String, Equatable {
    case active
    case inactive
    case background
}

enum SyncLifecyclePolicy {
    /// A lifecycle transition ends the socket session only when it locks the
    /// mission key, shows App Lock, or detaches the stores. A plain .inactive
    /// (control centre, notification shade) keeps everything going.
    static func iosForegroundReady(
        phase: SyncScenePhase,
        dataKeyAuthBound: Bool,
        dataKeyUnlocked: Bool,
        appLockOverlay: Bool,
        storesLocked: Bool
    ) -> Bool {
        phase != .background
            && !appLockOverlay
            && (!dataKeyAuthBound || dataKeyUnlocked)
            && !storesLocked
    }
}
