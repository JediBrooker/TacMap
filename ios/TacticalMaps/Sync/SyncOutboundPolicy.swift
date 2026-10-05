import Foundation

// Outbound side of the Unit Sync client contract (sections 4, 6, 11, 16).
// All pure: callers pass the time in, nothing here touches a socket.

// MARK: - Pacer (section 11.1 / 11.2)

/// Two token buckets per socket plus an in-flight window for put/del. Keeps
/// us under relayLimits.clientPacing so the relay never has to 4008 us and
/// peers never see a burst bigger than they budget for.
final class SyncOutboundPacer {
    enum FrameClass: Int, CaseIterable, Comparable {
        // order is priority, lowest raw value goes first
        case control
        case presence
        case chat
        case retry
        case mutation

        static func < (lhs: FrameClass, rhs: FrameClass) -> Bool { lhs.rawValue < rhs.rawValue }

        /// retries and new mutations may not dig into the interactive reserve
        var usesReserve: Bool { self == .retry || self == .mutation }
    }

    struct Config: Equatable {
        var windowMs: Double = 10_000
        var frameCapacity: Double = 30
        var frameRefillPerWindow: Double = 120
        var byteCapacity: Double = 1_048_576
        var byteRefillPerWindow: Double = 2_097_152
        var reserveFrames: Double = 10
        var reserveBytes: Double = 65_536
        var maxInFlightMutations = 32
        var maxInFlightMutationBytes = 1_048_576
        var after4008FrameTokens: Double = 15
        var after4008ByteTokens: Double = 524_288

        static let contract = Config()
    }

    struct Item: Equatable {
        let id: Int
        let frameClass: FrameClass
        let bytes: Int
        /// rid for put/del (and their retries), nil otherwise
        let requestId: String?
    }

    private static let epsilon = 1e-9

    let config: Config
    private(set) var frameTokens: Double
    private(set) var byteTokens: Double
    private var lastRefillMs: Double
    private var queues: [FrameClass: [Item]] = [:]
    private var inFlight: [String: Int] = [:]
    private var nextItemId = 1

    init(nowMs: Double, after4008: Bool = false, config: Config = .contract) {
        self.config = config
        frameTokens = after4008 ? config.after4008FrameTokens : config.frameCapacity
        byteTokens = after4008 ? config.after4008ByteTokens : config.byteCapacity
        lastRefillMs = nowMs
    }

    var inFlightCount: Int { inFlight.count }
    var inFlightBytes: Int { inFlight.values.reduce(0, +) }
    var queuedCount: Int { queues.values.reduce(0) { $0 + $1.count } }

    func queued(_ frameClass: FrameClass) -> [Item] { queues[frameClass] ?? [] }

    func isInFlight(_ requestId: String) -> Bool { inFlight[requestId] != nil }

    /// Presence keeps one slot: a newer fix replaces the queued one, and the
    /// replaced item id comes back so the caller can fail its completion.
    @discardableResult
    func enqueue(_ frameClass: FrameClass, bytes: Int, requestId: String? = nil) -> (id: Int, replaced: Int?) {
        let item = Item(id: nextItemId, frameClass: frameClass, bytes: max(0, bytes), requestId: requestId)
        nextItemId += 1
        var replaced: Int?
        if frameClass == .presence, let old = queues[.presence]?.first {
            replaced = old.id
            queues[.presence] = []
        }
        queues[frameClass, default: []].append(item)
        return (item.id, replaced)
    }

    /// Drop a queued (not yet written) item, e.g. a superseded reservation.
    @discardableResult
    func remove(id: Int) -> Bool {
        for frameClass in FrameClass.allCases {
            if let index = queues[frameClass]?.firstIndex(where: { $0.id == id }) {
                queues[frameClass]?.remove(at: index)
                return true
            }
        }
        return false
    }

    /// Ack, nack or local resolve. Frees the in-flight slot.
    func settle(requestId: String) {
        inFlight.removeValue(forKey: requestId)
    }

    /// Pops the next frame that may go out right now and charges for it.
    func next(nowMs: Double) -> Item? {
        refill(nowMs)
        guard let head = head() else { return nil }
        guard canSend(head) else { return nil }
        queues[head.frameClass]?.removeFirst()
        frameTokens -= 1
        byteTokens -= Double(head.bytes)
        if head.frameClass == .mutation, let rid = head.requestId {
            inFlight[rid] = head.bytes
        }
        return head
    }

    /// When the head of the queue becomes sendable on tokens alone. nil means
    /// nothing is queued or we are waiting on an ack to free the window.
    func nextWakeMs(nowMs: Double) -> Double? {
        refill(nowMs)
        guard let head = head() else { return nil }
        if head.frameClass == .mutation, !inFlightAllows(head) { return nil }
        let needs = tokenNeeds(head)
        let frameRate = config.frameRefillPerWindow / config.windowMs
        let byteRate = config.byteRefillPerWindow / config.windowMs
        var wait = 0.0
        if frameTokens < needs.frames - Self.epsilon {
            wait = max(wait, (needs.frames - frameTokens) / frameRate)
        }
        if byteTokens < needs.bytes - Self.epsilon {
            wait = max(wait, (needs.bytes - byteTokens) / byteRate)
        }
        return nowMs + wait
    }

    private func head() -> Item? {
        for frameClass in FrameClass.allCases {
            if let first = queues[frameClass]?.first { return first }
        }
        return nil
    }

    private func tokenNeeds(_ item: Item) -> (frames: Double, bytes: Double) {
        let reserveFrames = item.frameClass.usesReserve ? config.reserveFrames : 0
        let reserveBytes = item.frameClass.usesReserve ? config.reserveBytes : 0
        // a frame bigger than capacity minus reserve would wait forever, so cap it
        return (
            min(config.frameCapacity, 1 + reserveFrames),
            min(config.byteCapacity, Double(item.bytes) + reserveBytes)
        )
    }

    private func inFlightAllows(_ item: Item) -> Bool {
        if inFlight.isEmpty { return true } // one big frame may go alone
        return inFlight.count < config.maxInFlightMutations
            && inFlightBytes + item.bytes <= config.maxInFlightMutationBytes
    }

    private func canSend(_ item: Item) -> Bool {
        if item.frameClass == .mutation, !inFlightAllows(item) { return false }
        let needs = tokenNeeds(item)
        return frameTokens >= needs.frames - Self.epsilon && byteTokens >= needs.bytes - Self.epsilon
    }

    private func refill(_ nowMs: Double) {
        guard nowMs > lastRefillMs else { return }
        let elapsed = nowMs - lastRefillMs
        lastRefillMs = nowMs
        frameTokens = min(config.frameCapacity, frameTokens + elapsed * config.frameRefillPerWindow / config.windowMs)
        byteTokens = min(config.byteCapacity, byteTokens + elapsed * config.byteRefillPerWindow / config.windowMs)
    }
}

// MARK: - Ack timer (section 11.3)

/// Per-rid retransmit state. The timer only runs once a copy actually
/// finished writing, so a slow uplink never piles duplicate copies up.
struct SyncAckTimer: Equatable {
    static let ackTimeoutBaseMs: Int64 = 5_000
    static let ackTimeoutPerAheadMs: Int64 = 200
    static let ackTimeoutCapMs: Int64 = 30_000
    static let maxAttempts = 3

    enum State: Equatable {
        case queued
        case awaitingAck(deadlineMs: Int64)
        case acked
        case exhausted
    }

    static func timeoutMs(attempt: Int, aheadInFlight: Int) -> Int64 {
        let base = ackTimeoutBaseMs + ackTimeoutPerAheadMs * Int64(max(0, aheadInFlight))
        let shift = min(max(0, attempt - 1), 10)
        return min(ackTimeoutCapMs, base << shift)
    }

    private(set) var attempt = 1
    private(set) var state: State = .queued

    var copyUnwritten: Bool { state == .queued }

    @discardableResult
    mutating func writeComplete(atMs now: Int64, aheadInFlight: Int) -> Int64 {
        let deadline = now + Self.timeoutMs(attempt: attempt, aheadInFlight: aheadInFlight)
        state = .awaitingAck(deadlineMs: deadline)
        return deadline
    }

    /// true when a retransmission copy should be queued
    mutating func ackTimeout() -> Bool {
        guard case .awaitingAck = state else { return false }
        guard attempt < Self.maxAttempts else {
            state = .exhausted
            return false
        }
        attempt += 1
        state = .queued
        return true
    }

    mutating func acked() {
        state = .acked
    }
}

// MARK: - Pre-send size check (section 11.4)

enum OutboundSizeCheck {
    static let objectCtMaxChars = 700_000
    static let maxFrameBytes = 1_048_576
    static let signaturePlaceholderChars = 86
    /// 12-byte nonce + 16-byte GCM tag
    static let aeadOverheadBytes = 28

    static func ciphertextChars(innerUtf8Bytes: Int) -> Int {
        4 * ((innerUtf8Bytes + aeadOverheadBytes + 2) / 3)
    }

    /// frameBytesWithoutCiphertext is the outer frame encoded with ct = "".
    static func fits(innerUtf8Bytes: Int, frameBytesWithoutCiphertext: Int) -> Bool {
        let ct = ciphertextChars(innerUtf8Bytes: innerUtf8Bytes)
        return ct <= objectCtMaxChars && frameBytesWithoutCiphertext + ct <= maxFrameBytes
    }
}

// MARK: - op-nack reactions (section 6)

enum SyncNackPolicy {
    enum Outcome: Equatable {
        /// stale on our own persisted stamp: the relay already has our write
        case confirmed
        /// resolve the op and keep it from republishing until the user edits it
        case suppress(issue: SyncIssueCode?)
        /// counter-window: nothing more from us will ever fit, stop writing
        case pauseMutations
        /// keep the op, retransmit on the ack-timeout schedule
        case retry
        /// session level trouble, needs a fresh socket
        case reconnect(SyncLocalCloseAction)
    }

    static func decide(
        code: String,
        retryable: Bool,
        rejectedStampIsOwnPersisted: Bool,
        wireIdSkipped: Bool
    ) -> Outcome {
        switch code {
        case "stale", "not-found":
            return rejectedStampIsOwnPersisted && !wireIdSkipped ? .confirmed : .suppress(issue: nil)
        case "counter-window":
            return .pauseMutations
        case "quota":
            return .suppress(issue: .roomQuotaNack)
        case "invalid":
            return .suppress(issue: .relayInvalidNack)
        case "storage":
            return .retry
        case "hello-required":
            return .reconnect(.helloRequiredNack)
        case "session-mismatch", "session-replaced":
            return .reconnect(.sessionNack)
        default:
            return retryable ? .retry : .suppress(issue: .relayInvalidNack)
        }
    }
}

// MARK: - Live counter-window resync (section 4)

/// After relay expiry a fresh baseline can sit more than ADVANCE_WINDOW below
/// a returning actor. A signed live frame we reject for that reason means we
/// should take a new snapshot, but never more than once a minute / 6 an hour.
struct LiveWindowResyncPolicy: Equatable {
    static let cooldownMs: Int64 = 60_000
    static let maxPerHour = 6
    static let hourMs: Int64 = 3_600_000

    private var resyncTimes: [Int64] = []
    private(set) var pending = false

    mutating func windowRejection(nowMs: Int64) -> Bool {
        if canResync(nowMs) {
            record(nowMs)
            return true
        }
        pending = true
        return false
    }

    mutating func tick(nowMs: Int64) -> Bool {
        guard pending, canResync(nowMs) else { return false }
        record(nowMs)
        return true
    }

    /// When a remembered resync becomes allowed, for scheduling a tick.
    func nextEligibleMs() -> Int64? {
        guard pending, let last = resyncTimes.last else { return nil }
        var at = last + Self.cooldownMs
        if resyncTimes.count >= Self.maxPerHour {
            at = max(at, resyncTimes[resyncTimes.count - Self.maxPerHour] + Self.hourMs)
        }
        return at
    }

    private mutating func record(_ now: Int64) {
        resyncTimes.append(now)
        resyncTimes = resyncTimes.filter { now - $0 < Self.hourMs }
        pending = false
    }

    private func canResync(_ now: Int64) -> Bool {
        let recent = resyncTimes.filter { now - $0 < Self.hourMs }
        if let last = recent.last, now - last < Self.cooldownMs { return false }
        return recent.count < Self.maxPerHour
    }
}

// MARK: - v2 legacy ids (section 16)

enum LegacyV2Ids {
    private static let pattern =
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"

    /// Canonical UUID in either case -> lowercase state key, else nil.
    static func stateKey(_ raw: String) -> String? {
        guard raw.utf8.count == 36,
              raw.range(of: pattern, options: .regularExpression) != nil else { return nil }
        return raw.lowercased()
    }

    /// What every v2 per-id map is keyed by, whatever casing was on the wire.
    static func stateKey(_ uuid: UUID) -> String { uuid.uuidString.lowercased() }

    /// v2 frame id (and the AAD and sig message) is uuidString, uppercase, same
    /// as 2.x iOS sent. Shipped 2.x iOS re-exports by the uppercase string, so a
    /// lowercase id makes it echo a put UPPER plus a del of the lower id and the
    /// object gets deleted room wide (gap-v2-room-2x-interop-1).
    static func outboundId(_ uuid: UUID) -> String { uuid.uuidString }

    /// Wire id for a state key, nil if the key isnt a UUID.
    static func outboundId(stateKey: String) -> String? { UUID(uuidString: stateKey).map(outboundId) }

    /// Mirrors the relay's isNewer: higher v wins, equal v goes to the higher
    /// `by` compared as raw bytes (JS string order for these ASCII ids).
    static func beats(v: Int64, by: String, lastV: Int64?, lastBy: String?) -> Bool {
        guard let lastV else { return true }
        if v != lastV { return v > lastV }
        guard let lastBy else { return false }
        return Array(lastBy.utf8).lexicographicallyPrecedes(Array(by.utf8))
    }
}
