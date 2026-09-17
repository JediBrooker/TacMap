import Foundation
import Combine
import CryptoKit
import CoreLocation

struct PendingOutboundDelivery: Equatable {
    let localId: String
    let requestId: String
    let connectionGeneration: Int64
    let actorId: String
    let sessionDomain: String?
    let wireObjectId: String
    let objectVersion: String
    let kind: String
    let ciphertextHash: String
    let desiredContentHash: String?
    let desiredContent: String?
    let frame: String
    var attempts: Int = 1
    var rejectionCode: String?
    var rejectionRetryable: Bool?
}

struct DeliveryAck {
    let version: Int
    let requestId: String
    let actorId: String
    let sessionDomain: String?
    let wireObjectId: String
    let objectVersion: String
    let kind: String
    let ciphertextHash: String
}

struct DeliveryNack {
    let version: Int
    let requestId: String
    let actorId: String
    let sessionDomain: String?
    let code: String
    let retryable: Bool
}

struct LegacyDeleteRecovery: Equatable {
    let localId: String
    let requestId: String
    let actorId: String
    let wireObjectId: String
    let objectVersion: String
    let kind: String
    let ciphertextHash: String
    var snapshotGeneration: Int64

    init(delivery: PendingOutboundDelivery, snapshotGeneration: Int64) {
        localId = delivery.localId
        requestId = delivery.requestId
        actorId = delivery.actorId
        wireObjectId = delivery.wireObjectId
        objectVersion = delivery.objectVersion
        kind = delivery.kind
        ciphertextHash = delivery.ciphertextHash
        self.snapshotGeneration = snapshotGeneration
    }

    func matchesVerifiedTombstone(
        localId: String,
        wireObjectId: String,
        actorId: String,
        objectVersion: String,
        kind: String,
        ciphertextHash: String,
        snapshotGeneration: Int64
    ) -> Bool {
        self.localId == localId && self.wireObjectId == wireObjectId &&
            self.actorId == actorId && self.objectVersion == objectVersion &&
            self.kind == kind && self.ciphertextHash == ciphertextHash &&
            self.snapshotGeneration == snapshotGeneration
    }
}

enum SyncReconnectAttemptGuard {
    static func shouldRun(
        scheduledGeneration: Int64,
        currentGeneration: Int64,
        wantsConnection: Bool,
        hasActiveSocket: Bool
    ) -> Bool {
        wantsConnection && !hasActiveSocket && scheduledGeneration == currentGeneration
    }
}

struct V2SnapshotBatch {
    let sequence: Int64
    let records: [[String: Any]]
    let members: [[String: Any]]
}

enum V2SnapshotGateEvent {
    case ignored
    case began
    case pageAccepted
    case completed(V2SnapshotBatch)
    case rejected(String)
}

/// Socket- and generation-bound v2 snapshot fence. Records remain buffered so
/// neither remote model writes nor local outbound diffs can escape before the
/// relay completes an exact begin/pages/end sequence.
final class V2SnapshotGate {
    private enum Phase { case idle, awaitingBegin, receiving, finalPage, complete, failed }

    private let maxItems: Int
    private let maxAggregateBytes: Int
    private var phase: Phase = .idle
    private var socketIdentifier: ObjectIdentifier?
    private var generation: Int64 = -1
    private var sequence: Int64?
    private var aggregateBytes = 0
    private var records: [[String: Any]] = []
    private var members: [[String: Any]] = []

    init(maxItems: Int, maxAggregateBytes: Int) {
        self.maxItems = maxItems
        self.maxAggregateBytes = maxAggregateBytes
    }

    func start(socketIdentity: AnyObject, generation: Int64) {
        socketIdentifier = ObjectIdentifier(socketIdentity)
        self.generation = generation
        phase = .awaitingBegin
        sequence = nil
        aggregateBytes = 0
        records.removeAll(keepingCapacity: true)
        members.removeAll(keepingCapacity: true)
    }

    func cancel() {
        phase = .idle
        socketIdentifier = nil
        generation = -1
        sequence = nil
        aggregateBytes = 0
        records.removeAll(keepingCapacity: true)
        members.removeAll(keepingCapacity: true)
    }

    func accept(
        socketIdentity: AnyObject,
        generation: Int64,
        message: [String: Any],
        frameBytes: Int
    ) -> V2SnapshotGateEvent {
        guard isCurrent(socketIdentity, generation: generation) else { return .ignored }
        guard phase != .idle, phase != .complete, phase != .failed else { return .ignored }
        guard frameBytes >= 0, aggregateBytes <= maxAggregateBytes - frameBytes else {
            return reject("The relay snapshot exceeded the safe size limit.")
        }
        aggregateBytes += frameBytes

        switch message["t"] as? String {
        case "snapshot-begin": return acceptBegin(message)
        case "snapshot": return acceptPage(message)
        case "snapshot-end": return acceptEnd(message)
        default: return reject("The relay sent live data before completing its snapshot fence.")
        }
    }

    func timeout(socketIdentity: AnyObject, generation: Int64) -> V2SnapshotGateEvent {
        guard isCurrent(socketIdentity, generation: generation) else { return .ignored }
        switch phase {
        case .awaitingBegin, .receiving, .finalPage:
            return reject("The relay did not complete its initial snapshot in time.")
        default:
            return .ignored
        }
    }

    private func acceptBegin(_ message: [String: Any]) -> V2SnapshotGateEvent {
        guard phase == .awaitingBegin else {
            return reject("The relay snapshot began out of order.")
        }
        guard let value = SyncManager.strictJSONInteger(
            message["seq"], minimum: 0, maximum: Int64.max
        ) else { return reject("The relay snapshot fence was malformed.") }
        sequence = value
        phase = .receiving
        return .began
    }

    private func acceptPage(_ message: [String: Any]) -> V2SnapshotGateEvent {
        guard phase == .receiving else {
            return reject("The relay snapshot page arrived out of order.")
        }
        guard let items = message["items"] as? [[String: Any]],
              let more = strictJSONBoolean(message["more"]),
              items.count <= maxItems - records.count else {
            return reject("The relay snapshot page was malformed or contained too many records.")
        }
        records.append(contentsOf: items)

        if message.keys.contains("members") {
            guard let pageMembers = message["members"] as? [[String: Any]],
                  pageMembers.count <= maxItems - members.count else {
                return reject("The relay snapshot member list was malformed or too large.")
            }
            members.append(contentsOf: pageMembers)
        }
        if !more { phase = .finalPage }
        return .pageAccepted
    }

    private func acceptEnd(_ message: [String: Any]) -> V2SnapshotGateEvent {
        guard phase == .finalPage else {
            return reject("The relay snapshot ended before its final page.")
        }
        guard let expected = sequence,
              let actual = SyncManager.strictJSONInteger(
                message["seq"], minimum: 0, maximum: Int64.max
              ) else { return reject("The relay snapshot end fence was malformed.") }
        guard actual == expected else {
            return reject("The relay snapshot fence changed before completion.")
        }
        phase = .complete
        return .completed(V2SnapshotBatch(
            sequence: expected, records: records, members: members
        ))
    }

    private func reject(_ reason: String) -> V2SnapshotGateEvent {
        phase = .failed
        records.removeAll(keepingCapacity: true)
        members.removeAll(keepingCapacity: true)
        return .rejected(reason)
    }

    private func isCurrent(_ socketIdentity: AnyObject, generation: Int64) -> Bool {
        socketIdentifier == ObjectIdentifier(socketIdentity) && self.generation == generation
    }

    private func strictJSONBoolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
}

/// One transport generation per local object. Queue acceptance never advances
/// the model baseline; only an exact, operation-bound relay acknowledgement can.
final class OutboundDeliveryTracker {
    private var byLocalId: [String: PendingOutboundDelivery] = [:]
    private var localIdByRequest: [String: String] = [:]
    private let maxAttempts: Int

    init(maxAttempts: Int = 5) { self.maxAttempts = maxAttempts }

    @discardableResult
    func register(_ delivery: PendingOutboundDelivery) -> PendingOutboundDelivery? {
        let prior = byLocalId.updateValue(delivery, forKey: delivery.localId)
        if let prior { localIdByRequest.removeValue(forKey: prior.requestId) }
        localIdByRequest[delivery.requestId] = delivery.localId
        return prior
    }

    func pending(localId: String) -> PendingOutboundDelivery? { byLocalId[localId] }
    func all() -> [PendingOutboundDelivery] { Array(byLocalId.values) }

    func acknowledge(_ ack: DeliveryAck) -> PendingOutboundDelivery? {
        guard ack.version == 1,
              let localId = localIdByRequest[ack.requestId],
              let pending = byLocalId[localId],
              pending.requestId == ack.requestId,
              pending.actorId == ack.actorId,
              pending.sessionDomain == ack.sessionDomain,
              pending.wireObjectId == ack.wireObjectId,
              pending.objectVersion == ack.objectVersion,
              pending.kind == ack.kind,
              pending.ciphertextHash == ack.ciphertextHash else { return nil }
        byLocalId.removeValue(forKey: localId)
        localIdByRequest.removeValue(forKey: ack.requestId)
        return pending
    }

    func reject(_ nack: DeliveryNack) -> PendingOutboundDelivery? {
        guard nack.version == 1,
              let localId = localIdByRequest[nack.requestId],
              var pending = byLocalId[localId],
              pending.actorId == nack.actorId,
              pending.sessionDomain == nack.sessionDomain else { return nil }
        pending.rejectionCode = nack.code
        pending.rejectionRetryable = nack.retryable
        byLocalId[localId] = pending
        return pending
    }

    func nextAttempt(requestId: String, generation: Int64, sessionDomain: String?) -> PendingOutboundDelivery? {
        guard let localId = localIdByRequest[requestId],
              var pending = byLocalId[localId],
              pending.connectionGeneration == generation,
              pending.sessionDomain == sessionDomain,
              pending.attempts < maxAttempts,
              pending.rejectionRetryable != false,
              pending.rejectionCode.map({ Self.retryableCodes.contains($0) }) ?? true else { return nil }
        pending.attempts += 1
        pending.rejectionCode = nil
        pending.rejectionRetryable = nil
        byLocalId[localId] = pending
        return pending
    }

    func resetForReconnect() -> Set<String> {
        let ids = Set(byLocalId.keys)
        byLocalId.removeAll(); localIdByRequest.removeAll()
        return ids
    }

    private static let retryableCodes: Set<String> = ["storage", "hello-required"]
}

func shouldResendRecoverableDelete(
    wireObjectId: String,
    stamp: VersionStamp,
    confirmedSnapshotDeletes: [String: String]
) -> Bool {
    confirmedSnapshotDeletes[wireObjectId] != stamp.encode()
}

enum SyncIssueKind { case connection, security }
struct SyncIssue { let message: String; let kind: SyncIssueKind; let generation: Int64 }

/// A same-generation hello acknowledgement cannot erase a rollback warning.
/// Only dismissal or a later clean verified snapshot may retire it.
final class SyncIssueLifecycle {
    private(set) var generation: Int64 = 0
    private var transientIssue: SyncIssue?
    private var persistentSecurityIssue: SyncIssue?
    var issue: SyncIssue? {
        if transientIssue?.kind == .security { return transientIssue }
        return persistentSecurityIssue ?? transientIssue
    }

    func beginConnection() -> Int64 { generation += 1; return generation }

    @discardableResult
    func report(_ message: String, kind: SyncIssueKind, generation: Int64? = nil) -> SyncIssue? {
        let at = generation ?? self.generation
        if let current = transientIssue {
            if current.kind == .security && kind == .connection { return issue }
            if current.kind == .security && kind == .security && at < current.generation { return issue }
            if current.kind == .connection && kind == .connection && at < current.generation { return issue }
        }
        transientIssue = SyncIssue(message: message, kind: kind, generation: at)
        return issue
    }

    /// A relay snapshot cannot prove that an on-disk migration succeeded.
    /// Keep this warning until a later local migration completes cleanly.
    @discardableResult
    func reportPersistentSecurity(
        _ message: String,
        generation: Int64? = nil
    ) -> SyncIssue? {
        persistentSecurityIssue = SyncIssue(
            message: message,
            kind: .security,
            generation: generation ?? self.generation
        )
        return issue
    }

    @discardableResult
    func clearPersistentSecurity() -> SyncIssue? {
        persistentSecurityIssue = nil
        return issue
    }

    func connectionSucceeded(generation: Int64, verifiedCleanSnapshot: Bool) -> SyncIssue? {
        guard let current = transientIssue else { return issue }
        switch current.kind {
        case .connection:
            if current.generation <= generation { transientIssue = nil }
        case .security:
            if verifiedCleanSnapshot && generation > current.generation {
                transientIssue = nil
            }
        }
        return issue
    }

    @discardableResult
    func dismiss() -> SyncIssue? {
        transientIssue = nil
        return issue
    }
}

/// A checked, rollback-capable data slot. `UserDefaults.set` changes the
/// process cache before its durable synchronization result is known, so a
/// failed candidate must restore the exact prior value before any observable
/// application state is published.
struct DurableDefaultsDataStore {
    let read: () -> Data?
    let writeCandidate: (Data) -> Bool
    let restore: (Data?) -> Void

    func commit(_ candidate: Data) -> Bool {
        let previous = read()
        guard writeCandidate(candidate), read() == candidate else {
            restore(previous)
            return false
        }
        return true
    }

    static func userDefaults(_ defaults: UserDefaults, key: String) -> Self {
        Self(
            read: { defaults.data(forKey: key) },
            writeCandidate: { value in
                defaults.set(value, forKey: key)
                return defaults.synchronize()
            },
            restore: { previous in
                if let previous {
                    defaults.set(previous, forKey: key)
                } else {
                    defaults.removeObject(forKey: key)
                }
                _ = defaults.synchronize()
            }
        )
    }
}

struct PresenceConfigSealedPolicy {
    let requiresSealed: (_ identifier: String, _ key: Data) throws -> Bool
    let markSealed: (_ identifier: String, _ key: Data) throws -> Void

    static let production = Self(
        requiresSealed: { try SealedMigrationPolicy.requiresSealed($0, key: $1) },
        markSealed: { try SealedMigrationPolicy.markSealed($0, key: $1) }
    )
}

enum SyncRemoteModelMutationError: LocalizedError {
    case invalidPayload
    case identityCollision(UUID)
    case persistence(Error)

    var errorDescription: String? {
        switch self {
        case .invalidPayload:
            return "The authenticated sync record did not contain exactly one valid mission object."
        case .identityCollision(let id):
            return "The authenticated sync record conflicts with another object type using ID \(id.uuidString)."
        case .persistence(let error):
            return "The authenticated sync update could not be saved: \(error.localizedDescription)"
        }
    }
}

/// The sole production model-application seam for authenticated v2/v3 remote
/// objects. Every store write completes before an `@Published` collection is
/// changed, so observers cannot upload or display a write that failed on disk.
@MainActor
enum SyncRemoteModelApplier {
    static func apply(_ parsed: GeoJSONImporter.Result,
                      waypointStore: WaypointStore,
                      drawingStore: DrawingStore) throws {
        guard parsed.invalidSkipped == 0,
              parsed.waypoints.count + parsed.drawings.count == 1 else {
            throw SyncRemoteModelMutationError.invalidPayload
        }
        if let waypoint = parsed.waypoints.first,
           drawingStore.shapes.contains(where: { $0.id == waypoint.id }) {
            throw SyncRemoteModelMutationError.identityCollision(waypoint.id)
        }
        if let shape = parsed.drawings.first,
           waypointStore.waypoints.contains(where: { $0.id == shape.id }) {
            throw SyncRemoteModelMutationError.identityCollision(shape.id)
        }

        do {
            // Layer metadata is durable first. If the object write then fails,
            // the replay marker remains pending and this idempotent step is a
            // no-op on retry; the pre-existing object's hash is unchanged
            // because only its own referenced layer participates in export.
            _ = try drawingStore.addLayersVerbatimDurably(parsed.newLayers)
            if let waypoint = parsed.waypoints.first {
                if waypointStore.waypoints.contains(where: { $0.id == waypoint.id }) {
                    _ = try waypointStore.commitEdit(waypoint, actionName: "Apply Synced Waypoint")
                } else {
                    _ = try waypointStore.addDurably(waypoint)
                }
            } else if let shape = parsed.drawings.first {
                if drawingStore.shapes.contains(where: { $0.id == shape.id }) {
                    _ = try drawingStore.commitEdit(shape, actionName: "Apply Synced Drawing")
                } else {
                    _ = try drawingStore.addDurably(shape)
                }
            }
        } catch let error as SyncRemoteModelMutationError {
            throw error
        } catch {
            throw SyncRemoteModelMutationError.persistence(error)
        }
    }

    static func delete(localID: String,
                       waypointStore: WaypointStore,
                       drawingStore: DrawingStore) throws {
        guard let uuid = UUID(uuidString: localID) else {
            throw SyncRemoteModelMutationError.invalidPayload
        }
        let waypoint = waypointStore.waypoints.first { $0.id == uuid }
        let drawing = drawingStore.shapes.first { $0.id == uuid }
        guard waypoint == nil || drawing == nil else {
            throw SyncRemoteModelMutationError.identityCollision(uuid)
        }
        do {
            if let waypoint { _ = try waypointStore.deleteDurably(waypoint) }
            if let drawing { _ = try drawingStore.deleteDurably(drawing) }
        } catch {
            throw SyncRemoteModelMutationError.persistence(error)
        }
    }
}

/// v2 has no durable pending-model journal, so its in-memory protocol metadata
/// must be finalized strictly after the shared durable model applier succeeds.
@MainActor
enum SyncVerifiedV2RecordCommitter {
    static func apply(_ parsed: GeoJSONImporter.Result,
                      waypointStore: WaypointStore,
                      drawingStore: DrawingStore,
                      afterDurableApply: () -> Void) throws {
        try SyncRemoteModelApplier.apply(
            parsed,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        afterDurableApply()
    }

    static func delete(localID: String,
                       waypointStore: WaypointStore,
                       drawingStore: DrawingStore,
                       afterDurableApply: () -> Void) throws {
        try SyncRemoteModelApplier.delete(
            localID: localID,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        afterDurableApply()
    }
}

struct UnitSyncPresenceCadence: Equatable {
    static let foregroundInterval: TimeInterval = 5
    static let backgroundPollInterval: TimeInterval = 30
    static let minimumBackgroundInterval: TimeInterval = 60
    static let maximumBackgroundInterval: TimeInterval = 60 * 60
    /// Ask Core Location for a replacement well before the relay's 45-second
    /// foreground retention expires. This matters for stationary devices and
    /// simulators, where the standard service may publish only one callback.
    static let preferredLocationAge: TimeInterval = 20
    static let maximumLocationAge: TimeInterval = 30
    static let maximumLocationFutureSkew: TimeInterval = 30

    private(set) var foregroundReady = true
    private(set) var backgroundEnabled = false
    private(set) var backgroundInterval: TimeInterval = 15 * 60
    private(set) var lastBroadcastUptime: TimeInterval?
    private(set) var lastBroadcastLocationTimestamp: Date?

    var canBroadcast: Bool { foregroundReady || backgroundEnabled }
    var timerInterval: TimeInterval {
        foregroundReady ? Self.foregroundInterval : Self.backgroundPollInterval
    }
    var advertisedRetentionSeconds: Int {
        PresenceRetentionAdvertisement.retentionSeconds(
            backgroundEnabled: backgroundEnabled,
            backgroundInterval: backgroundInterval
        )
    }

    mutating func configure(
        foregroundReady: Bool,
        backgroundEnabled: Bool,
        backgroundInterval: TimeInterval
    ) {
        let bounded = min(
            Self.maximumBackgroundInterval,
            max(Self.minimumBackgroundInterval, backgroundInterval)
        )
        if self.foregroundReady != foregroundReady
            || self.backgroundEnabled != backgroundEnabled
            || self.backgroundInterval != bounded {
            lastBroadcastUptime = nil
            lastBroadcastLocationTimestamp = nil
        }
        self.foregroundReady = foregroundReady
        self.backgroundEnabled = backgroundEnabled
        self.backgroundInterval = bounded
    }

    func isDue(at uptime: TimeInterval) -> Bool {
        guard canBroadcast else { return false }
        guard let lastBroadcastUptime else { return true }
        let elapsed = uptime - lastBroadcastUptime
        // Uptime is monotonic in production. Treat an injected rollback as a
        // reset so tests and future clock sources fail open to one fresh fix.
        let interval = foregroundReady ? Self.foregroundInterval : backgroundInterval
        return elapsed < 0 || elapsed >= interval
    }

    func canBroadcast(locationTimestamp: Date, at uptime: TimeInterval) -> Bool {
        guard isDue(at: uptime) else { return false }
        guard let lastBroadcastLocationTimestamp else { return true }
        return locationTimestamp > lastBroadcastLocationTimestamp
    }

    mutating func markBroadcast(locationTimestamp: Date, at uptime: TimeInterval) {
        lastBroadcastUptime = uptime
        lastBroadcastLocationTimestamp = locationTimestamp
    }

    /// A newly authenticated socket has no relay-visible presence yet. Permit
    /// one still-fresh fix to seed that session even if the same CLLocation was
    /// the final update sent on the socket it replaces.
    mutating func startAuthenticatedSession() {
        lastBroadcastUptime = nil
        lastBroadcastLocationTimestamp = nil
    }

    static func locationIsFresh(timestamp: Date, now: Date = Date()) -> Bool {
        let age = now.timeIntervalSince(timestamp)
        return age >= -maximumLocationFutureSkew && age <= maximumLocationAge
    }
}

enum SyncConnectionWatchdogPolicy {
    static let heartbeatInterval: TimeInterval = 20
    static let heartbeatTimeout: TimeInterval = 8
    static let handshakeTimeout: TimeInterval = 30

    static func isCurrent(
        scheduledGeneration: Int64,
        currentGeneration: Int64,
        wantsConnection: Bool,
        hasCurrentSocket: Bool
    ) -> Bool {
        scheduledGeneration == currentGeneration && wantsConnection && hasCurrentSocket
    }
}

/// Keeps the live-session seed ahead of potentially large reconciliation work
/// after a v3 hello acknowledgement. Presence and heartbeat must be queued
/// before replaying recoverable deletes or diffing the local mission model.
enum V3HelloAckWorkSequencer {
    static func run(
        activateLiveSession: () -> Void,
        reconcileMissionState: () -> Void
    ) {
        activateLiveSession()
        reconcileMissionState()
    }
}

/// Inbound and outbound Chat readiness deliberately have different fences.
/// The relay can route a frame to this socket immediately after registering
/// its advertised key while the acknowledgement is still queued locally.
/// Signature verification, exact session/key matching and AEAD open remain
/// the authority for inbound data; user sends still wait for the relay ACK.
enum TacMapChatReadinessPolicy {
    static func permitsInbound(hasLocalSession: Bool) -> Bool {
        hasLocalSession
    }

    static func permitsOutbound(
        hasLocalSession: Bool,
        relayAcknowledged: Bool
    ) -> Bool {
        hasLocalSession && relayAcknowledged
    }
}

/// Real-time sync client for shared tactical picture (iOS side). Basically
/// mirrors Android's `SyncManager`: connects to E2E-blind relay for a
/// join-code room and keeps waypoints + drawings in sync across the unit.
///
/// Each object gets serialised as single-feature GeoJSON (same cross-platform
/// schema we already use for import/export), encrypted with the room key and
/// relayed as opaque ciphertext. Layers ride along in feature properties.
/// Merge is LWW on per-object Lamport version; echo supressed by tracking
/// last serialised form per id.
///
/// Also handles ephemeral presence: foreground updates remain live while an
/// explicit OPSEC opt-in permits bounded screen-off updates.
@MainActor
final class SyncManager: ObservableObject {
    enum Status: Equatable { case offline, connecting, snapshotting, connected }

    @Published private(set) var status: Status = .offline
    @Published private(set) var room: String?
    @Published private(set) var roomName: String?
    @Published private(set) var lastError: String?
    @Published var peers: [String: PresencePeer] = [:]
    /// Signature-verified v3 sessions currently reported by the relay. This is
    /// independent of `peers`, which contains only shared map locations.
    @Published private(set) var onlineMembers: [String: OnlineMember] = [:]
    /// Only authenticated v3 sessions with a relay-acknowledged chat key are
    /// selectable. Values snapshot actor + session + key ID as one target.
    @Published private(set) var chatRecipients: [String: TacMapChatRecipient] = [:]
    @Published private(set) var chatSessionReady = false
    @Published private(set) var chatSessionIssue: String?
    let chatStore = TacMapChatStore()
    @Published private(set) var presenceConfig = PresenceConfig()
    private var presenceConfigDurable = false

    /// Fires a description whenever a remote change comes in, so the UI
    /// can flash a conflict/update notification.
    let remoteUpdateSubject = PassthroughSubject<String, Never>()

    static let relayBase = "wss://tacmap-sync.christianbrooker.workers.dev"

    nonisolated static func validatedRelayBaseForRuntime(
        _ value: String,
        allowInsecureLoopback: Bool = RelayEndpointPolicy.allowsInsecureLoopback
    ) -> String? {
        try? RelayEndpointPolicy.normalize(
            value,
            allowInsecureLoopback: allowInsecureLoopback
        )
    }

    // Set once via configure() so this can be a @StateObject. Needs to be
    // created without referencing other @StateObject stores at init time.
    private var waypointStore: WaypointStore!
    private var drawingStore: DrawingStore!
    /// Injected after construction so presence can read the current GPS fix.
    private(set) var locationService: LocationService?

    private var task: URLSessionWebSocketTask?
    private let webSocketSessionDelegate = SyncWebSocketSessionDelegate()
    private lazy var webSocketSession = SyncWebSocketTransport.makeSession(
        delegate: webSocketSessionDelegate
    )
    private let inboundFrameCloseGate = SyncInboundFrameCloseGate()
    private let liveReceiveBudget = SyncLiveReceiveBudget()
    private var roomKey: SymmetricKey?
    private var authToken: String?
    /// Resolved from OPSEC settings at join time so a self-hoster's relay is
    /// actually used; falls back to ours. Held for the reconnect path.
    private var relayEndpoint = SyncManager.relayBase
    private var wantConnected = false
    private var roomId: String?
    private let sessionDomainGenerator: () throws -> Data
    private let defaults: UserDefaults
    private let presenceStore: DurableDefaultsDataStore
    private let presenceSealedPolicy: PresenceConfigSealedPolicy

    // v3 protocol state
    private var protocolVersion: Int = 2
    private var v3Keys: SyncCrypto.V3RoomKeys?
    private var myActorId: String?
    private var replayState: SyncReplayState?
    private var sessionDomain: Data?
    private var myPublicKeyRaw: Data?
    private var presenceCounter: Int64 = 0
    private var snapshotSeq: Int64?
    private var snapshotRecords: [[String: Any]] = []
    private var snapshotInvalid = false
    private var snapshotSawFinalPage = false
    private var awaitingHelloAck = false
    private var localHelloVersion: String?
    private var snapshotAggregateBytes = 0
    private var snapshotWireIds = Set<String>()
    private var snapshotConfirmedLocalDeletes: [String: String] = [:]
    private var forcedLocalDiff = Set<String>()
    private var forcedLegacyDeletes: [String: LegacyDeleteRecovery] = [:]
    private var pendingLegacyDeleteConfirmations = Set<String>()
    private var resolvingPendingModel = false
    private var activeSessions: [String: V3ActiveSession] = [:]
    private let onlineMemberTracker = OnlineMemberTracker()
    private let outboundDeliveries = OutboundDeliveryTracker()
    private var deliveryRetryItems: [String: DispatchWorkItem] = [:]
    private var reconnectWorkItem: DispatchWorkItem?
    private var reconnectBackoff = SyncReconnectBackoff()
    private var v3HandshakeTimeoutItem: DispatchWorkItem?
    private var connectionHealthTimer: Timer?
    private var connectionPingTimeoutItem: DispatchWorkItem?
    private var connectionPingInFlight = false
    private let v2SnapshotGate = V2SnapshotGate(
        maxItems: 10_000,
        maxAggregateBytes: 54_525_952
    )
    private var v2SnapshotTimeoutItem: DispatchWorkItem?
    private var v2SnapshotFailureGeneration: Int64?
    private let issueLifecycle = SyncIssueLifecycle()
    private var activeConnectionGeneration: Int64 = 0
    private var lastGoodLocalPresenceFix: PresenceLocationFix?
    private var localPresenceCandidateCluster: PresenceCandidateCluster?
    private var remotePresenceCandidateClusters: [String: PresenceCandidateCluster] = [:]

    private struct ValidatedRecordV3 {
        let mutation: SyncReplayState.DurableMutation
        let parsed: GeoJSONImporter.Result?
        let localId: String?
        let expectedModelHash: String?
    }

    struct PresencePayload: Equatable {
        let lat: Double
        let lon: Double
        let heading: Double
        let speed: Double
        let callsign: String
        let affiliation: String
        let echelon: String
        let function: String
        let isHQ: Bool
    }

    struct PresenceEnvelope {
        let payload: PresencePayload
        let signedPayload: Data
        let publicKey: String
        let signature: String
    }

    private struct PendingChatSend {
        let frame: TacMapChatCrypto.Frame
    }

    // Per-device Ed25519 signing identity. Seed sealed at rest; the public key
    // rides every presence AND every object write so peers pin it (TOFU) and
    // reject a room member impersonating an established device. One identity per
    // clientId, shared by presence + object writes. Room state cleared on leave.
    private var deviceSeed: Data?
    private var myPublicKey: String = ""
    private var peerKeys: [String: String] = [:]   // clientId -> pinned pubkey
    private var peerTs: [String: Int64] = [:]        // clientId -> last accepted presence ts
    private static let deviceSeedKey = "sync.deviceSeed"
    private static let deviceSeedLabel = "sync/deviceSeed"

#if DEBUG && targetEnvironment(simulator)
    /// XCUITests share the app's real simulator defaults across test runs. This
    /// opt-in hook removes only the sealed Unit Sync signing identity so a test
    /// can model its first creation without erasing the simulator, Keychain, or
    /// unrelated mission data. Release and device builds do not contain it.
    static let simulatorUITestSigningIdentityResetEnvironmentKey =
        "TACMAP_UITEST_RESET_SIGNING_IDENTITY"

    @discardableResult
    static func resetSigningIdentityForSimulatorUITestIfRequested(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard environment[simulatorUITestSigningIdentityResetEnvironmentKey] == "1" else {
            return false
        }
        defaults.removeObject(forKey: deviceSeedKey)
        return defaults.synchronize() && defaults.data(forKey: deviceSeedKey) == nil
    }
#endif

    private let clientId: String
    private var clock: Int64 = 0
    private var versions: [String: Int64] = [:]
    private var lastContent: [String: String] = [:]
    private var kindById: [String: String] = [:]
    private var observers = Set<AnyCancellable>()
    private var revisionObservers = Set<AnyCancellable>()
    private var modelRevisionJournal: LocalModelRevisionJournal?
    private var revisionJournalAvailable = false
    private var observedModelHashes: [String: String] = [:]
    private var modelObservationInitialized = false

    // v2 containment ceilings (pending v3 protocol limits)
    private static let maxBase64Bytes = 1_048_576        // 1 MiB encoded ct
    private static let maxSnapshotItems = 10_000
    private static let maxSnapshotAggregateBytes = 54_525_952
    private static let maxVersion: Int64 = 1_000_000_000_000  // matches relay MAX_V

    /// Broadcasts `loc` messages every 5 seconds.
    private var presenceTimer: Timer?
    /// Sweeps stale peers every 30s.
    private var stalenessTimer: Timer?
    private var presenceCadence = UnitSyncPresenceCadence()
    private var presenceSendInFlight = false

    // TacMap Chat state is deliberately per authenticated WebSocket. X25519
    // private material never enters persistence and is released on every
    // disconnect, background lock, session replacement, and leave.
    private var chatLocalSession: TacMapChatCrypto.LocalSession?
    private var chatPeerKeys: [String: TacMapChatCrypto.PeerKey] = [:]
    private var pendingChatSends: [String: PendingChatSend] = [:]
    private var chatCounter: Int64 = 0
    private var chatKeyRetryItem: DispatchWorkItem?
    private var chatKeyAdvertAttempts = 0

    init(
        sessionDomainGenerator: @escaping () throws -> Data = {
            try SyncIdentity.generateSessionDomain()
        },
        defaults: UserDefaults = .standard,
        presenceStore: DurableDefaultsDataStore? = nil,
        presenceSealedPolicy: PresenceConfigSealedPolicy = .production
    ) {
        self.sessionDomainGenerator = sessionDomainGenerator
        self.defaults = defaults
        self.presenceStore = presenceStore ?? .userDefaults(
            defaults,
            key: Self.presenceConfigKey
        )
        self.presenceSealedPolicy = presenceSealedPolicy
        if let existing = defaults.string(forKey: "sync.clientId") {
            clientId = existing
        } else {
            let id = UUID().uuidString
            defaults.set(id, forKey: "sync.clientId")
            clientId = id
        }
        loadPresenceConfig()
    }

    /// Inject shared stores once the view hierarchy is up. Can call
    /// repeatedly, only first call actually binds.
    func configure(waypointStore: WaypointStore,
                   drawingStore: DrawingStore,
                   locationService: LocationService? = nil) {
        guard self.waypointStore == nil else { return }
        self.waypointStore = waypointStore
        self.drawingStore = drawingStore
        self.locationService = locationService
        let container = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        let journal = LocalModelRevisionJournal(containerURL: container)
        modelRevisionJournal = journal
        revisionJournalAvailable = journal.load()
        observeModelRevisions()
    }

    /// Keeps mission-state processing foreground-only while allowing an
    /// explicitly opted-in, already-authenticated v3 session to send presence
    /// with the screen locked. A foreground return always reconnects and takes
    /// a fresh verified snapshot because inbound background frames are dropped.
    func updateLifecycle(
        foregroundReady: Bool,
        backgroundPresenceEnabled: Bool,
        backgroundInterval: TimeInterval
    ) {
        let wasForegroundReady = presenceCadence.foregroundReady
        let wasBackgroundEnabled = presenceCadence.backgroundEnabled
        presenceCadence.configure(
            foregroundReady: foregroundReady,
            backgroundEnabled: backgroundPresenceEnabled,
            backgroundInterval: backgroundInterval
        )
        if !foregroundReady {
            clearChatSessionSecrets()
        }
        reschedulePresenceBroadcastTimer()

        if foregroundReady {
            // A previous signed presence may have advertised an extended
            // screen-off lifetime. Rotate the session when that eligibility is
            // revoked so peers remove it immediately, even if no fresh fix is
            // available to replace it with a 45-second foreground marker.
            if wasBackgroundEnabled, !backgroundPresenceEnabled,
               status == .connected, wantConnected {
                reconnectForForegroundReconciliation()
                return
            }
            if !wasForegroundReady, wantConnected {
                reconnectForForegroundReconciliation()
            } else {
                sendPresence()
            }
            return
        }

        // A background presence session may only continue an already verified
        // socket. Snapshot/authentication and reconnect persistence stay in the
        // unlocked foreground path.
        guard backgroundPresenceEnabled, status == .connected else {
            pauseConnectionForBackground()
            return
        }
        sendPresence()
    }

    /// Location callbacks are the most reliable wake source in both lifecycle
    /// states. `sendPresence()` coalesces frequent fixes to the active cadence,
    /// while allowing a freshly requested stationary fix to publish promptly.
    func locationDidUpdate() {
        guard presenceCadence.canBroadcast else { return }
        sendPresence()
    }

    /// A v3 peer may currently be retaining our sender-signed background
    /// marker. Replacing (or closing) the session is the authenticated
    /// withdrawal signal when the user turns location sharing off.
    private func withdrawSharedLocation() {
        guard protocolVersion == 3, wantConnected, status == .connected else { return }
        if presenceCadence.foregroundReady {
            reconnectForForegroundReconciliation()
        } else {
            pauseConnectionForBackground()
        }
    }

    // MARK: - Presence config persistence

    private static let presenceConfigKey = "sync.presenceConfig"
    /// AEAD label binding the presence blob so it can't open as another store.
    private static let presenceLabel = "sync/presenceConfig"
    private static let presencePolicyID = "defaults:sync.presenceConfig"

    @discardableResult
    func updatePresenceConfig(_ value: PresenceConfig) -> Bool {
        if value == presenceConfig, presenceConfigDurable { return true }
        guard persistPresenceConfig(value) else {
            reportPresencePersistenceIssue(
                "Could not save Unit Sync identity/location sharing. The previous setting remains active; check available storage and try again."
            )
            return false
        }
        let stoppedSharing = presenceConfig.shareLocation && !value.shareLocation
        presenceConfig = value
        presenceConfigDurable = true
        if stoppedSharing { withdrawSharedLocation() }
        return true
    }

    private func persistPresenceConfig(_ value: PresenceConfig) -> Bool {
        do {
            let data = try JSONEncoder().encode(value)
            // Callsign + affiliation/echelon/function/HQ is unit identity - seal
            // it at rest like mission data. Fence plaintext before replacing it;
            // after a crash or failed write it must never become an accepted
            // downgrade source again.
            let key = try SafeStore.keyProvider()
            let sealed = try SealedEnvelope.sealFile(
                key: key,
                plaintext: data,
                label: Self.presenceLabel
            )
            try presenceSealedPolicy.markSealed(Self.presencePolicyID, key)
            return presenceStore.commit(sealed)
        } catch {
            return false
        }
    }

    private func loadPresenceConfig() {
        guard let stored = presenceStore.read() else { return }
        do {
            let key = try SafeStore.keyProvider()
            let requiresSealed = try presenceSealedPolicy.requiresSealed(
                Self.presencePolicyID,
                key
            )
            if SealedEnvelope.isSealedFile(stored) {
                guard let plain = SealedEnvelope.openFile(
                    key: key,
                    blob: stored,
                    label: Self.presenceLabel
                ), let config = try? JSONDecoder().decode(PresenceConfig.self, from: plain) else {
                    reportPresencePersistenceIssue(
                        "Saved Unit Sync identity/location sharing is locked or damaged. Location sharing remains off."
                    )
                    return
                }
                // A valid sealed record establishes the durable downgrade
                // barrier before its contents become observable.
                try presenceSealedPolicy.markSealed(Self.presencePolicyID, key)
                presenceConfig = config
                presenceConfigDurable = true
                return
            }

            guard !requiresSealed,
                  let config = try? JSONDecoder().decode(PresenceConfig.self, from: stored) else {
                reportPresencePersistenceIssue(
                    "Saved Unit Sync identity/location sharing failed its sealed-storage policy. Location sharing remains off."
                )
                return
            }
            // Legacy plaintext is published only after a checked, marker-first
            // migration to authenticated ciphertext succeeds.
            guard persistPresenceConfig(config) else {
                reportPresencePersistenceIssue(
                    "Could not migrate Unit Sync identity/location sharing to encrypted storage. Location sharing remains off."
                )
                return
            }
            presenceConfig = config
            presenceConfigDurable = true
        } catch {
            reportPresencePersistenceIssue(
                "Saved Unit Sync identity/location sharing is locked or damaged. Location sharing remains off."
            )
        }
    }

    private func reportPresencePersistenceIssue(_ message: String) {
        lastError = issueLifecycle.report(
            message,
            kind: .security,
            generation: activeConnectionGeneration
        )?.message
    }

    /// Load the durable signing seed or create-and-persist one. Existing locked,
    /// malformed or undecryptable material is an error: silently rotating would
    /// turn a temporary device-lock condition into an actor identity swap.
    private func loadOrCreateDeviceSeed() throws -> Data {
        if let stored = defaults.data(forKey: Self.deviceSeedKey) {
            guard SealedEnvelope.isSealedFile(stored) else { throw IdentityError.invalidSeedStore }
            let key = try SafeStore.keyProvider()
            guard let seed = SealedEnvelope.openFile(key: key, blob: stored, label: Self.deviceSeedLabel),
                  seed.count == 32,
                  SyncSigning.publicKeyRaw(seed) != nil else { throw IdentityError.invalidSeedStore }
            return seed
        }
        let seed = SyncSigning.generateSeed()
        let key = try SafeStore.keyProvider()
        let sealed = try SealedEnvelope.sealFile(key: key, plaintext: seed, label: Self.deviceSeedLabel)
        defaults.set(sealed, forKey: Self.deviceSeedKey)
        guard defaults.synchronize(),
              defaults.data(forKey: Self.deviceSeedKey) == sealed else {
            throw IdentityError.seedPersistenceFailed
        }
        return seed
    }

    private enum IdentityError: Error {
        case invalidSeedStore
        case seedPersistenceFailed
        case invalidSessionRandom
    }

    // MARK: API

    func join(_ joinCode: String, roomName proposedRoomName: String? = nil) {
        let code = joinCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !code.isEmpty else { return }
        guard code.hasPrefix("3:") || code.hasPrefix("2:") else {
            lastError = "Join code must start with 3:. Legacy rooms require an explicit 2: prefix."
            return
        }
        guard let configuredRelay = Self.validatedRelayBaseForRuntime(
            OpsecSettings.shared.relayURL
        ) else {
            lastError = "The configured Unit Sync relay is unsafe or invalid. Correct it in Settings, Privacy & OPSEC."
            return
        }
        leave(clearLastError: false)
        do {
            let seed = try loadOrCreateDeviceSeed()
            guard let publicKey = SyncSigning.publicKey(seed),
                  let publicRaw = SyncSigning.publicKeyRaw(seed) else { throw IdentityError.invalidSeedStore }
            deviceSeed = seed
            myPublicKey = publicKey
            myPublicKeyRaw = publicRaw
        } catch {
            lastError = "Signing identity is locked or unavailable. Unlock the device and try again."
            return
        }
        wantConnected = true

        // Hold the already validated origin for reconnects; connect() validates
        // it again before every URLSession task is created.
        relayEndpoint = configuredRelay

        if code.hasPrefix("3:") {
            protocolVersion = 3
            let rawCode = String(code.dropFirst(2))
            let keys = SyncCrypto.deriveRoomV3(rawCode)
            v3Keys = keys
            roomKey = keys.roomKey
            roomId = keys.roomId
            authToken = keys.authToken
            guard let pubRaw = myPublicKeyRaw else {
                lastError = "Signing identity is unavailable."
                wantConnected = false
                return
            }
            let localActorId = SyncIdentity.actorId(roomIdRaw: keys.roomIdRaw, pubkeyRaw: pubRaw)
            myActorId = localActorId
            let containerURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            let rs = SyncReplayState(roomId: keys.roomId, containerURL: containerURL)
            guard rs.load(localActorId: localActorId, publicKey: myPublicKey) else {
                lastError = "Saved rollback-protection state is locked or damaged. Sync was not started."
                wantConnected = false
                roomKey = nil; authToken = nil; roomId = nil; v3Keys = nil; myActorId = nil
                return
            }
            replayState = rs
            do {
                try chatStore.open(roomId: keys.roomId)
                chatSessionIssue = nil
            } catch {
                // Unit Sync can still operate, but chat remains fail-closed: no
                // frame is sent unless its sealed history/replay document is
                // available for a durable-before-send commit.
                chatSessionIssue = error.localizedDescription
            }
        } else {
            protocolVersion = 2
            let keys = SyncCrypto.deriveRoom(String(code.dropFirst(2)))
            roomKey = keys.roomKey
            roomId = keys.roomId
            authToken = keys.authToken
            chatStore.close()
            chatSessionIssue = "TacMap Chat requires a secure v3 room."
        }

        if let activeRoomId = roomId {
            if let proposedRoomName,
               !PresenceConfig.normalizedRoomName(proposedRoomName).isEmpty {
                var updated = presenceConfig
                updated.setRoomName(proposedRoomName, for: activeRoomId)
                _ = updatePresenceConfig(updated)
            }
            roomName = presenceConfig.roomName(for: activeRoomId)
        }
        room = code
        connect()
        observeStores()
        startPresenceTimers()
    }

    func leave() {
        leave(clearLastError: true)
    }

    private func leave(clearLastError: Bool) {
        wantConnected = false
        reconnectBackoff.reset()
        clearChatSessionSecrets()
        chatStore.close()
        chatSessionIssue = nil
        observers.removeAll()
        stopPresenceTimers()
        stopConnectionHealthChecks()
        presenceSendInFlight = false
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        v2SnapshotTimeoutItem?.cancel()
        v2SnapshotTimeoutItem = nil
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        v2SnapshotGate.cancel()
        v2SnapshotFailureGeneration = nil
        let leavingTask = task
        let explicitLeaveFrame = signedExplicitLeaveFrame()
        task = nil
        if let leavingTask, let explicitLeaveFrame {
            leavingTask.send(.string(explicitLeaveFrame)) { _ in
                leavingTask.cancel(with: .goingAway, reason: nil)
            }
        } else {
            leavingTask?.cancel(with: .goingAway, reason: nil)
        }

        // v3: persist replay state but don't clear it (survives leave/restart)
        try? replayState?.save()

        roomKey = nil
        authToken = nil
        room = nil
        roomName = nil
        status = .offline
        clock = 0
        versions.removeAll(); lastContent.removeAll(); kindById.removeAll()
        forcedLocalDiff.removeAll(); resolvingPendingModel = false
        forcedLegacyDeletes.removeAll()
        pendingLegacyDeleteConfirmations.removeAll()
        clearOutboundDeliveries(markForReconciliation: false)
        peers.removeAll()
        activeSessions.removeAll()
        onlineMembers = onlineMemberTracker.clear()
        peerKeys.removeAll(); peerTs.removeAll()
        lastGoodLocalPresenceFix = nil
        localPresenceCandidateCluster = nil
        remotePresenceCandidateClusters.removeAll()

        // v3 state cleared per-session (not durable)
        v3Keys = nil
        myActorId = nil
        replayState = nil
        sessionDomain = nil
        presenceCounter = 0
        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        awaitingHelloAck = false
        localHelloVersion = nil
        myPublicKeyRaw = nil
        deviceSeed = nil
        myPublicKey = ""
        protocolVersion = 2
        if clearLastError { lastError = issueLifecycle.dismiss()?.message }
    }

    func acknowledgeLastError() {
        lastError = issueLifecycle.dismiss()?.message
    }

    // MARK: - TacMap Chat API

    /// Cold-upgrade cleanup for every historical per-room Chat/replay file, not
    /// just the room the user happens to rejoin. Call only after mission-data
    /// unlock so a locked DEK causes no filesystem mutation.
    func migrateLegacyLocalStoresAfterUnlock() {
        guard let support = FileManager.default.urls(
            for: .applicationSupportDirectory,
            in: .userDomainMask
        ).first else { return }
        do {
            _ = try SyncLocalStore.migrateAllLegacyStores(
                applicationSupportDirectory: support
            )
            lastError = issueLifecycle.clearPersistentSecurity()?.message
        } catch {
            lastError = issueLifecycle.reportPersistentSecurity(
                "Saved Unit Sync metadata could not be migrated to private filenames. Check available storage, then unlock mission data and try again.",
                generation: activeConnectionGeneration
            )?.message
        }
    }

    enum ChatSendError: LocalizedError {
        case secureRoomRequired
        case historyUnavailable
        case sessionUnavailable
        case recipientRequired
        case recipientChanged
        case counterExhausted
        case transportUnavailable

        var errorDescription: String? {
            switch self {
            case .secureRoomRequired: return "TacMap Chat requires a secure v3 Unit Sync room."
            case .historyUnavailable: return "Encrypted chat history is locked or unavailable."
            case .sessionUnavailable: return "Encrypted chat is still establishing a relay session."
            case .recipientRequired: return "Select a unit before sending this message."
            case .recipientChanged: return "That unit's secure session changed. Select it again; nothing was broadcast."
            case .counterExhausted: return "This chat session reached its message limit. Reconnect Unit Sync."
            case .transportUnavailable: return "The message could not be routed to the relay."
            }
        }
    }

    var chatAvailabilityMessage: String? {
        if room == nil { return "Join a secure Unit Sync room to use chat." }
        if protocolVersion != 3 { return "TacMap Chat requires a secure v3 room." }
        if let chatSessionIssue { return chatSessionIssue }
        if chatStore.activeRoomId != roomId { return "Encrypted chat history is unavailable." }
        if status != .connected { return "Unit Sync must be connected for live chat." }
        if !chatSessionReady { return "Establishing an encrypted chat session…" }
        return nil
    }

    /// Called from the app's DataKey lock notification. No chat key material or
    /// plaintext history remains reachable while mission data is locked.
    func lockChatForMissionData() {
        clearChatSessionSecrets()
        chatStore.lock()
        chatSessionIssue = "Unlock mission data to use TacMap Chat."
    }

    func restoreChatAfterMissionUnlock() {
        guard protocolVersion == 3, let roomId else { return }
        do {
            try chatStore.open(roomId: roomId)
            chatSessionIssue = nil
            if status == .connected, presenceCadence.foregroundReady {
                reconnectForForegroundReconciliation()
            }
        } catch {
            chatSessionIssue = error.localizedDescription
        }
    }

    @discardableResult
    func sendChat(body: String,
                  kind: TacMapChatContentKind,
                  scope: TacMapChatScope,
                  recipient: TacMapChatRecipient?) throws -> String {
        guard protocolVersion == 3, room?.hasPrefix("3:") == true,
              let keys = v3Keys, let roomKey, let seed = deviceSeed,
              let local = chatLocalSession else {
            throw protocolVersion == 3 ? ChatSendError.sessionUnavailable : ChatSendError.secureRoomRequired
        }
        guard chatStore.activeRoomId == roomId else { throw ChatSendError.historyUnavailable }
        guard status == .connected,
              TacMapChatReadinessPolicy.permitsOutbound(
                hasLocalSession: true,
                relayAcknowledged: chatSessionReady
              ) else { throw ChatSendError.sessionUnavailable }

        let targetKey: TacMapChatCrypto.PeerKey?
        switch scope {
        case .room:
            guard recipient == nil else { throw ChatSendError.recipientChanged }
            targetKey = nil
        case .direct:
            guard let recipient else { throw ChatSendError.recipientRequired }
            guard let current = chatRecipients[recipient.actorId],
                  current.actorId == recipient.actorId,
                  current.sessionDomain == recipient.sessionDomain,
                  current.keyId == recipient.keyId,
                  let key = chatPeerKeys[recipient.actorId],
                  key.sessionDomain == recipient.sessionDomain,
                  key.keyId == recipient.keyId else {
                throw ChatSendError.recipientChanged
            }
            targetKey = key
        }

        let normalizedBody = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let payload = TacMapChatPayload(kind: kind, body: normalizedBody)
        guard payload.isValid else { throw TacMapChatCrypto.CryptoError.invalidPayload }
        guard chatCounter < VersionStamp.maxCounter else { throw ChatSendError.counterExhausted }
        chatCounter += 1 // A failed send may waste a counter; reuse is forbidden.
        let messageId = try TacMapChatMessage.makeMessageID()
        let frame = try TacMapChatCrypto.seal(
            payload: payload,
            scope: scope,
            roomIdRaw: keys.roomIdRaw,
            roomKey: roomKey,
            localSession: local,
            signingSeed: seed,
            counter: chatCounter,
            messageId: messageId,
            recipient: targetKey
        )

        let senderName = boundedCallsign(presenceConfig.callsign)
        let message = TacMapChatMessage(
            id: messageId,
            roomId: keys.roomId,
            scope: scope,
            senderActorId: local.actorId,
            senderName: senderName,
            recipientActorId: recipient?.actorId,
            recipientName: recipient?.displayName,
            kind: kind,
            body: normalizedBody,
            sentAtMilliseconds: payload.createdAt,
            isOutgoing: true,
            deliveryState: .sending,
            failureCode: nil
        )
        try chatStore.appendOutgoing(message) // durable-before-send
        pendingChatSends[messageId] = PendingChatSend(frame: frame)
        guard send(frame.object, completion: { [weak self] succeeded in
            guard let self, !succeeded else { return }
            self.pendingChatSends.removeValue(forKey: messageId)
            _ = try? self.chatStore.updateDelivery(
                id: messageId,
                state: .failed,
                failureCode: "transport"
            )
        }) else {
            pendingChatSends.removeValue(forKey: messageId)
            _ = try? chatStore.updateDelivery(id: messageId, state: .failed, failureCode: "transport")
            throw ChatSendError.transportUnavailable
        }
        return messageId
    }

    private func startChatSessionV3() {
        guard protocolVersion == 3, status == .connected,
              chatStore.activeRoomId == roomId,
              !chatStore.isLocked,
              let keys = v3Keys, let actorId = myActorId,
              let sessionDomain, let seed = deviceSeed else {
            chatSessionIssue = chatStore.issue ?? "Encrypted chat history is unavailable."
            return
        }
        clearChatSessionSecrets()
        do {
            chatLocalSession = try TacMapChatCrypto.makeLocalSession(
                roomIdRaw: keys.roomIdRaw,
                actorId: actorId,
                sessionDomain: sessionDomain,
                signingSeed: seed
            )
            chatCounter = 0
            chatSessionIssue = nil
            sendChatKeyAdvertisement()
        } catch {
            clearChatSessionSecrets()
            chatSessionIssue = "Encrypted chat could not establish a session."
        }
    }

    private func sendChatKeyAdvertisement() {
        guard status == .connected, !chatSessionReady,
              let session = chatLocalSession else { return }
        chatKeyAdvertAttempts += 1
        _ = send(session.advertisement) { [weak self] succeeded in
            guard let self, !succeeded else { return }
            self.chatSessionIssue = "The chat-key advertisement could not reach the relay."
        }
        chatKeyRetryItem?.cancel()
        guard chatKeyAdvertAttempts < 4 else {
            let issue = "The relay did not acknowledge encrypted chat capability."
            clearChatSessionSecrets()
            chatSessionIssue = issue
            return
        }
        let expectedKeyId = session.keyId
        let retry = DispatchWorkItem { [weak self] in
            guard let self, self.chatLocalSession?.keyId == expectedKeyId,
                  !self.chatSessionReady else { return }
            self.sendChatKeyAdvertisement()
        }
        chatKeyRetryItem = retry
        DispatchQueue.main.asyncAfter(deadline: .now() + 2, execute: retry)
    }

    private func clearChatSessionSecrets() {
        chatKeyRetryItem?.cancel()
        chatKeyRetryItem = nil
        chatKeyAdvertAttempts = 0
        chatSessionReady = false
        chatLocalSession = nil
        chatPeerKeys.removeAll(keepingCapacity: false)
        chatRecipients.removeAll(keepingCapacity: false)
        chatCounter = 0
        for messageId in pendingChatSends.keys {
            _ = try? chatStore.updateDelivery(id: messageId, state: .failed, failureCode: "session-ended")
        }
        pendingChatSends.removeAll(keepingCapacity: false)
    }

    private func refreshChatRecipients() {
        var next: [String: TacMapChatRecipient] = [:]
        for (actorId, key) in chatPeerKeys {
            guard activeSessions[actorId]?.sessionDomain == key.sessionDomain else { continue }
            let displayName = onlineMembers[actorId]?.displayName
                ?? peers[actorId]?.callsign
                ?? "Unit \(String(actorId.suffix(6)).uppercased())"
            next[actorId] = TacMapChatRecipient(
                actorId: actorId,
                displayName: displayName,
                sessionDomain: key.sessionDomain,
                keyId: key.keyId
            )
        }
        chatRecipients = next
    }

    // MARK: Connection

    private func connect() {
        guard presenceCadence.foregroundReady, let roomId else { return }
        guard let base = Self.validatedRelayBaseForRuntime(relayEndpoint) else {
            wantConnected = false
            status = .offline
            lastError = "The configured Unit Sync relay is unsafe or invalid. Correct it in Settings, Privacy & OPSEC."
            return
        }
        relayEndpoint = base
        presenceSendInFlight = false
        stopConnectionHealthChecks()
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        v2SnapshotTimeoutItem?.cancel()
        v2SnapshotTimeoutItem = nil
        v2SnapshotGate.cancel()
        clearOutboundDeliveries(markForReconciliation: true)
        activeConnectionGeneration = issueLifecycle.beginConnection()
        forcedLegacyDeletes = forcedLegacyDeletes.mapValues { recovery in
            var rebound = recovery
            rebound.snapshotGeneration = activeConnectionGeneration
            return rebound
        }
        pendingLegacyDeleteConfirmations.removeAll()
        if protocolVersion == 3 {
            clearChatSessionSecrets()
            sessionDomain = nil
            do {
                let generated = try sessionDomainGenerator()
                guard generated.count == 32 else { throw IdentityError.invalidSessionRandom }
                sessionDomain = generated
            } catch {
                failClosedV3(
                    "Secure session randomness is unavailable. Unit Sync was not started."
                )
                return
            }
            presenceCounter = 0
            snapshotSeq = nil
            snapshotRecords.removeAll()
            snapshotInvalid = false
            snapshotSawFinalPage = false
            snapshotAggregateBytes = 0
            snapshotWireIds.removeAll()
            snapshotConfirmedLocalDeletes.removeAll()
            awaitingHelloAck = false
            localHelloVersion = nil
            activeSessions.removeAll()
            remotePresenceCandidateClusters.removeAll()
            onlineMembers = onlineMemberTracker.clear()
        } else {
            // A legacy relay may ignore delivery request IDs. Rebuild v2's
            // baseline from its reconnect snapshot or resend with a fresh
            // version; never suppress a merely queued edit permanently.
            versions.removeAll()
            lastContent.removeAll()
            kindById.removeAll()
        }
        let path = protocolVersion == 3 ? "/v3/room/\(roomId)" : "/room/\(roomId)"
        guard let url = URL(string: base + path) else { return }
        status = .connecting
        var req = URLRequest(url: url)
        if let authToken { req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization") }
        if protocolVersion == 3 {
            req.setValue("3", forHTTPHeaderField: "X-Protocol")
            req.setValue(roomId, forHTTPHeaderField: "X-Room-Id")
        }
        req = SyncWebSocketTransport.prepareRequest(req)
        let t = webSocketSession.webSocketTask(with: req)
        SyncWebSocketTransport.configure(t)
        task = t
        let connectionGeneration = activeConnectionGeneration
        if protocolVersion == 2 {
            v2SnapshotGate.start(socketIdentity: t, generation: connectionGeneration)
            scheduleV2SnapshotTimeout(socket: t, generation: connectionGeneration)
        }
        t.resume()
        receive(on: t, generation: connectionGeneration)
        if protocolVersion == 3 {
            scheduleV3HandshakeTimeout(socket: t, generation: connectionGeneration)
        }
    }

    private func receive(on socket: URLSessionWebSocketTask, generation: Int64) {
        socket.receive { [weak self, weak socket] result in
            Task { @MainActor in
                guard let self, let socket, self.task === socket,
                      self.activeConnectionGeneration == generation else { return }
                switch result {
                case .failure:
                    self.handleDisconnect(socket: socket)
                case .success(let message):
                    let decision: SyncInboundFrameDecision
                    switch message {
                    case .string(let text):
                        decision = SyncInboundFramePolicy.inspect(text: text)
                    case .data(let data):
                        // Bound binary payloads before any UTF-8 allocation.
                        decision = SyncInboundFramePolicy.inspect(data: data)
                    @unknown default:
                        decision = .reject(.invalidUTF8)
                    }
                    guard case .accept(let text, let data) = decision else {
                        let rejection: SyncInboundFrameRejection
                        if case .reject(let exact) = decision {
                            rejection = exact
                        } else {
                            rejection = .invalidUTF8
                        }
                        self.rejectInboundFrame(
                            socket: socket,
                            generation: generation,
                            rejection: rejection
                        )
                        return
                    }
                    if !self.liveReceiveBudget.admit(
                        generation: generation,
                        byteCount: data.count,
                        phase: self.status == .connected ? .live : .initial
                    ) {
                        self.rejectInboundFrame(
                            socket: socket,
                            generation: generation,
                            rejection: .rateLimited
                        )
                        return
                    }
                    // Mission objects, replay counters, acknowledgements, and
                    // membership state all have durable foreground-only work.
                    // Keep reading so the socket remains healthy, but discard
                    // frames while locked/backgrounded and reconcile by snapshot
                    // before processing resumes.
                    if self.presenceCadence.foregroundReady {
                        self.handleMessage(
                            text,
                            data: data,
                            socket: socket,
                            generation: generation
                        )
                    }
                    self.receive(on: socket, generation: generation)
                }
            }
        }
    }

    private func rejectInboundFrame(
        socket: URLSessionWebSocketTask,
        generation: Int64,
        rejection: SyncInboundFrameRejection
    ) {
        guard inboundFrameCloseGate.claimClose(generation: generation) else { return }
        let closeCode: URLSessionWebSocketTask.CloseCode
        switch rejection {
        case .oversized:
            closeCode = .messageTooBig
        case .invalidUTF8:
            closeCode = .invalidFramePayloadData
        case .rateLimited:
            closeCode = .policyViolation
        }
        socket.cancel(with: closeCode, reason: nil)
        handleDisconnect(socket: socket)
    }

    private func markPeersStale(nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        peers = peers.mapValues { PresenceExpiryPolicy.markedStale($0, nowUptime: nowUptime) }
    }

    private func handleDisconnect(socket: URLSessionWebSocketTask) {
        guard task === socket else { return }
        stopConnectionHealthChecks()
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        task = nil
        clearChatSessionSecrets()
        presenceSendInFlight = false
        status = .offline
        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        snapshotAggregateBytes = 0
        snapshotWireIds.removeAll()
        snapshotConfirmedLocalDeletes.removeAll()
        pendingLegacyDeleteConfirmations.removeAll()
        v2SnapshotTimeoutItem?.cancel()
        v2SnapshotTimeoutItem = nil
        v2SnapshotGate.cancel()
        awaitingHelloAck = false
        sessionDomain = nil
        presenceCounter = 0
        markPeersStale()
        activeSessions.removeAll()
        remotePresenceCandidateClusters.removeAll()
        onlineMembers = onlineMemberTracker.clear()
        // Background presence may continue only on the socket authenticated in
        // the foreground. If it drops, wait for foreground unlock rather than
        // touching durable replay/session state behind the mission-key lock.
        guard wantConnected, presenceCadence.foregroundReady else { return }
        clearOutboundDeliveries(markForReconciliation: true)
        if v2SnapshotFailureGeneration != activeConnectionGeneration {
            lastError = issueLifecycle.report(
                "Unit Sync disconnected. Check the relay or network; reconnecting automatically.",
                kind: .connection,
                generation: activeConnectionGeneration
            )?.message
        }
        reconnectWorkItem?.cancel()
        let disconnectedGeneration = activeConnectionGeneration
        let reconnectDelay = reconnectBackoff.nextDelay()
        let reconnect = DispatchWorkItem { [weak self] in
            guard let self,
                  SyncReconnectAttemptGuard.shouldRun(
                    scheduledGeneration: disconnectedGeneration,
                    currentGeneration: self.activeConnectionGeneration,
                    wantsConnection: self.wantConnected,
                    hasActiveSocket: self.task != nil
                  ) else { return }
            self.reconnectWorkItem = nil
            self.connect()
        }
        reconnectWorkItem = reconnect
        DispatchQueue.main.asyncAfter(
            deadline: .now() + reconnectDelay,
            execute: reconnect
        )
    }

    private func pauseConnectionForBackground() {
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        stopConnectionHealthChecks()
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        guard let socket = task else {
            status = .offline
            return
        }
        // `handleDisconnect` performs the normal volatile-session cleanup. Its
        // reconnect gate is closed while foregroundReady is false.
        handleDisconnect(socket: socket)
        socket.cancel(with: .goingAway, reason: nil)
    }

    private func reconnectForForegroundReconciliation() {
        guard wantConnected, roomId != nil else { return }
        clearChatSessionSecrets()
        stopConnectionHealthChecks()
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        presenceSendInFlight = false
        if let socket = task {
            task = nil
            socket.cancel(with: .goingAway, reason: nil)
        }
        status = .offline
        markPeersStale()
        activeSessions.removeAll()
        remotePresenceCandidateClusters.removeAll()
        onlineMembers = onlineMemberTracker.clear()
        connect()
    }

    private func scheduleV3HandshakeTimeout(
        socket: URLSessionWebSocketTask,
        generation: Int64
    ) {
        v3HandshakeTimeoutItem?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak socket] in
            guard let self, let socket,
                  self.task === socket,
                  self.status != .connected,
                  SyncConnectionWatchdogPolicy.isCurrent(
                    scheduledGeneration: generation,
                    currentGeneration: self.activeConnectionGeneration,
                    wantsConnection: self.wantConnected,
                    hasCurrentSocket: self.task === socket
                  ) else { return }
            self.v3HandshakeTimeoutItem = nil
            self.lastError = self.issueLifecycle.report(
                "Unit Sync handshake timed out. Reconnecting automatically.",
                kind: .connection,
                generation: generation
            )?.message
            self.handleDisconnect(socket: socket)
            socket.cancel(with: .goingAway, reason: nil)
        }
        v3HandshakeTimeoutItem = timeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + SyncConnectionWatchdogPolicy.handshakeTimeout,
            execute: timeout
        )
    }

    private func startConnectionHealthChecks() {
        stopConnectionHealthChecks()
        guard task != nil, status == .connected else { return }
        let timer = Timer(
            timeInterval: SyncConnectionWatchdogPolicy.heartbeatInterval,
            repeats: true
        ) { [weak self] _ in
            Task { @MainActor [weak self] in self?.pingConnection() }
        }
        connectionHealthTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func stopConnectionHealthChecks() {
        connectionHealthTimer?.invalidate()
        connectionHealthTimer = nil
        connectionPingTimeoutItem?.cancel()
        connectionPingTimeoutItem = nil
        connectionPingInFlight = false
    }

    private func pingConnection() {
        guard status == .connected,
              !connectionPingInFlight,
              let socket = task else { return }
        let generation = activeConnectionGeneration
        connectionPingInFlight = true

        let timeout = DispatchWorkItem { [weak self, weak socket] in
            guard let self, let socket,
                  self.connectionPingInFlight,
                  self.task === socket,
                  SyncConnectionWatchdogPolicy.isCurrent(
                    scheduledGeneration: generation,
                    currentGeneration: self.activeConnectionGeneration,
                    wantsConnection: self.wantConnected,
                    hasCurrentSocket: self.task === socket
                  ) else { return }
            self.connectionPingTimeoutItem = nil
            self.connectionPingInFlight = false
            self.handleDisconnect(socket: socket)
            socket.cancel(with: .goingAway, reason: nil)
        }
        connectionPingTimeoutItem = timeout
        DispatchQueue.main.asyncAfter(
            deadline: .now() + SyncConnectionWatchdogPolicy.heartbeatTimeout,
            execute: timeout
        )

        socket.sendPing { [weak self, weak socket] error in
            Task { @MainActor in
                guard let self, let socket,
                      self.task === socket,
                      self.activeConnectionGeneration == generation else { return }
                self.connectionPingTimeoutItem?.cancel()
                self.connectionPingTimeoutItem = nil
                self.connectionPingInFlight = false
                if error != nil {
                    self.handleDisconnect(socket: socket)
                    socket.cancel(with: .goingAway, reason: nil)
                }
            }
        }
    }

    private func scheduleV2SnapshotTimeout(
        socket: URLSessionWebSocketTask,
        generation: Int64
    ) {
        v2SnapshotTimeoutItem?.cancel()
        let timeout = DispatchWorkItem { [weak self, weak socket] in
            Task { @MainActor in
                guard let self, let socket else { return }
                if case .rejected(let reason) = self.v2SnapshotGate.timeout(
                    socketIdentity: socket,
                    generation: generation
                ) {
                    self.failV2Snapshot(
                        socket: socket,
                        generation: generation,
                        reason: reason
                    )
                }
            }
        }
        v2SnapshotTimeoutItem = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 10, execute: timeout)
    }

    private func failV2Snapshot(
        socket: URLSessionWebSocketTask,
        generation: Int64,
        reason: String
    ) {
        guard task === socket, activeConnectionGeneration == generation else { return }
        v2SnapshotTimeoutItem?.cancel()
        v2SnapshotTimeoutItem = nil
        v2SnapshotFailureGeneration = generation
        status = .offline
        lastError = issueLifecycle.report(
            "Unit Sync snapshot failed: \(reason) Verify the relay or network; reconnecting automatically.",
            kind: .connection,
            generation: generation
        )?.message
        socket.cancel(with: .internalServerError, reason: nil)
    }

    // MARK: Outbound

    private func observeStores() {
        Publishers.CombineLatest3(
            waypointStore.$waypoints,
            drawingStore.$shapes,
            drawingStore.$layers
        )
        .debounce(for: .milliseconds(250), scheduler: RunLoop.main)
        .sink { [weak self] wps, shapes, layers in
            guard let self else { return }
            if self.protocolVersion == 3 {
                self.syncLocalStateV3(waypoints: wps, shapes: shapes, layers: layers)
            } else {
                self.syncLocalState(waypoints: wps, shapes: shapes, layers: layers)
            }
        }
        .store(in: &observers)
    }

    /// Lifetime observer: records local ABA mutations even while no room is joined.
    private func observeModelRevisions() {
        Publishers.CombineLatest3(
            waypointStore.$waypoints,
            drawingStore.$shapes,
            drawingStore.$layers
        )
        .sink { [weak self] waypoints, shapes, layers in
            guard let self else { return }
            let current = self.modelHashes(waypoints: waypoints, shapes: shapes, layers: layers)
            guard self.presenceCadence.foregroundReady else {
                // No mission-object mutation should normally occur behind the
                // lock. Keep only an in-memory baseline and never touch the
                // auth-bound revision journal from background callbacks.
                self.observedModelHashes = current
                self.modelObservationInitialized = true
                return
            }
            if !self.modelObservationInitialized {
                self.observedModelHashes = current
                self.modelObservationInitialized = true
                return
            }
            if self.resolvingPendingModel {
                self.observedModelHashes = current
                return
            }
            let changed = Set(self.observedModelHashes.keys).union(current.keys).filter {
                self.observedModelHashes[$0] != current[$0]
            }
            guard !changed.isEmpty else { return }
            do {
                guard self.revisionJournalAvailable, let journal = self.modelRevisionJournal else {
                    throw SyncReplayState.ReplayError.invalidState
                }
                for id in changed { try journal.bump(id) }
                self.observedModelHashes = current
            } catch {
                self.revisionJournalAvailable = false
                self.lastError = "Local revision history could not be saved; sync is paused."
                self.failClosedV3(self.lastError!)
            }
        }
        .store(in: &revisionObservers)
    }

    private func modelHashes(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) -> [String: String] {
        var out: [String: String] = [:]
        for waypoint in waypoints {
            if let content = try? GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layers) {
                out[waypoint.id.uuidString] = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
            }
        }
        for shape in shapes {
            if let content = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers) {
                out[shape.id.uuidString] = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
            }
        }
        return out
    }

    private func refreshObservedModelBaseline(localId: String?) {
        guard let localId else { return }
        if let hash = modelContentHash(localId: localId) { observedModelHashes[localId] = hash }
        else { observedModelHashes.removeValue(forKey: localId) }
    }

    private func syncLocalState(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) {
        guard presenceCadence.foregroundReady, status == .connected else { return }
        var current: [String: (kind: String, content: String)] = [:]
        for wp in waypoints {
            if let s = try? GeoJSONExporter.export(waypoints: [wp], drawings: [], layers: layers) {
                current[wp.id.uuidString] = ("waypoint", s)
            }
        }
        for shape in shapes {
            if let s = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers) {
                current[shape.id.uuidString] = ("drawing", s)
            }
        }

        for (id, entry) in current {
            if forcedLegacyDeletes[id] != nil { forcedLegacyDeletes.removeValue(forKey: id) }
            if lastContent[id] == entry.content && !forcedLocalDiff.contains(id) { continue }
            let hash = contentHash(entry.content)
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == hash, pending.kind == entry.kind { continue }
            clock += 1
            versions[id] = clock
            sendPut(id: id, v: clock, kind: entry.kind, content: entry.content)
        }
        let gone = Set(lastContent.keys).union(forcedLocalDiff).union(forcedLegacyDeletes.keys)
            .union(outboundDeliveries.all().map(\.localId))
            .filter { current[$0] == nil && !$0.hasPrefix("wire:") }
        for id in gone {
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == nil, pending.kind == "del" { continue }
            clock += 1
            sendDel(id: id, v: clock)
        }
    }

    private func sendPut(id: String, v: Int64, kind: String, content: String) {
        guard let key = roomKey, let seed = deviceSeed else { return }
        // Sign the write, then seal {content, pub, sig} together. The signature
        // rides INSIDE the sealed blob so the relay stays E2E-blind to device
        // identity; a receiver proves room-key possession by opening it and
        // device authorship by verifying the sig against the pinned key.
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, kind, clientId, content)) ?? ""
        let inner: [String: Any] = ["c": content, "pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: id, v: v, kind: kind)) else { return }
        let ciphertext = sealed.base64EncodedString()
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "put", "id": id, "v": v, "by": clientId, "kind": kind,
            "ct": ciphertext, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return }
        queueDelivery(PendingOutboundDelivery(
            localId: id, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: clientId, sessionDomain: nil, wireObjectId: id,
            objectVersion: String(v), kind: kind, ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: contentHash(content), desiredContent: content, frame: frame
        ))
    }

    private func sendDel(id: String, v: Int64) {
        guard let key = roomKey, let seed = deviceSeed else { return }
        // Deletes used to be an unauthenticated {id,v} - a coerced relay could
        // forge one and silently remove a contact. Now seal a signed proof: only
        // a room-key holder can produce it (relay can't), and it's attributable
        // to a device. AAD "del" so it can't be replayed as a put.
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, "del", clientId, "")) ?? ""
        let inner: [String: Any] = ["pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: id, v: v, kind: "del")) else { return }
        let ciphertext = sealed.base64EncodedString()
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "del", "id": id, "v": v, "by": clientId,
            "ct": ciphertext, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return }
        queueDelivery(PendingOutboundDelivery(
            localId: id, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: clientId, sessionDomain: nil, wireObjectId: id,
            objectVersion: String(v), kind: "del", ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: nil, desiredContent: nil, frame: frame
        ))
    }

    @discardableResult
    private func send(
        _ obj: [String: Any],
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        guard let data = try? JSONSerialization.data(withJSONObject: obj),
              let text = String(data: data, encoding: .utf8),
              let task else { return false }
        let generation = activeConnectionGeneration
        task.send(.string(text)) { [weak self] error in
            Task { @MainActor in
                guard let self,
                      SyncConnectionWatchdogPolicy.isCurrent(
                        scheduledGeneration: generation,
                        currentGeneration: self.activeConnectionGeneration,
                        wantsConnection: self.wantConnected,
                        hasCurrentSocket: self.task === task
                      ) else { return }
                completion(error == nil)
                guard error != nil else { return }
                // A failed write is definitive transport evidence. Do not keep
                // presenting a half-open socket as Connected while retries are
                // silently discarded; the normal generation-bound reconnect
                // path will establish a fresh authenticated session.
                self.handleDisconnect(socket: task)
                task.cancel(with: .goingAway, reason: nil)
            }
        }
        return true
    }

    private func encodedFrame(_ object: [String: Any]) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return nil }
        return text
    }

    private func newDeliveryRequestId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "")
    }

    private func contentHash(_ content: String) -> String {
        SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
    }

    private func ciphertextHash(_ ciphertext: String) -> String {
        SyncIdentity.urlB64Encode(SyncIdentity.sha256(Data(ciphertext.utf8)))
    }

    private func queueDelivery(_ delivery: PendingOutboundDelivery) {
        if let prior = outboundDeliveries.register(delivery) {
            deliveryRetryItems.removeValue(forKey: prior.requestId)?.cancel()
        }
        task?.send(.string(delivery.frame)) { _ in }
        scheduleDeliveryRetry(delivery)
    }

    private func scheduleDeliveryRetry(_ delivery: PendingOutboundDelivery) {
        deliveryRetryItems.removeValue(forKey: delivery.requestId)?.cancel()
        let delay = min(8.0, pow(2.0, Double(max(0, delivery.attempts - 1))))
        let item = DispatchWorkItem { [weak self] in
            guard let self else { return }
            Task { @MainActor in
                guard let retry = self.outboundDeliveries.nextAttempt(
                    requestId: delivery.requestId,
                    generation: delivery.connectionGeneration,
                    sessionDomain: delivery.sessionDomain
                ) else {
                    if self.outboundDeliveries.pending(localId: delivery.localId)?.requestId == delivery.requestId {
                        self.lastError = self.issueLifecycle.report(
                            "A Unit Sync change is still unconfirmed after bounded retries. Reconnecting to reconcile it; the local edit remains saved.",
                            kind: .security,
                            generation: delivery.connectionGeneration
                        )?.message
                        self.task?.cancel(with: .internalServerError, reason: nil)
                    }
                    return
                }
                self.task?.send(.string(retry.frame)) { _ in }
                self.scheduleDeliveryRetry(retry)
            }
        }
        deliveryRetryItems[delivery.requestId] = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func clearOutboundDeliveries(markForReconciliation: Bool) {
        deliveryRetryItems.values.forEach { $0.cancel() }
        deliveryRetryItems.removeAll()
        let pending = outboundDeliveries.all()
        let unconfirmed = outboundDeliveries.resetForReconnect()
        if markForReconciliation {
            forcedLocalDiff.formUnion(unconfirmed.filter { !$0.hasPrefix("wire:") })
            if protocolVersion == 2 {
                for delivery in pending where
                    delivery.desiredContent == nil && !delivery.localId.hasPrefix("wire:") {
                    forcedLegacyDeletes[delivery.localId] = LegacyDeleteRecovery(
                        delivery: delivery,
                        snapshotGeneration: -1
                    )
                }
            }
        }
    }

    // MARK: - v3 Outbound

    private func syncLocalStateV3(waypoints: [Waypoint], shapes: [DrawingShape], layers: [DrawingLayer]) {
        guard presenceCadence.foregroundReady,
              status == .connected, let keys = v3Keys, let rs = replayState,
              revisionJournalAvailable,
              !resolvingPendingModel, !rs.hasPendingModelApplications() else { return }
        var current: [String: (kind: String, content: String, localId: UUID)] = [:]
        for wp in waypoints {
            if let s = try? GeoJSONExporter.export(waypoints: [wp], drawings: [], layers: layers) {
                current[wp.id.uuidString] = ("waypoint", s, wp.id)
            }
        }
        for shape in shapes {
            if let s = try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers) {
                current[shape.id.uuidString] = ("drawing", s, shape.id)
            }
        }

        for (id, entry) in current where lastContent[id] != entry.content || forcedLocalDiff.contains(id) {
            let wireId = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(entry.localId))
            let hash = SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(entry.content.utf8)))
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == hash, pending.kind == entry.kind { continue }
            let recovery = replayState?.recoverableLocalPut(
                wireObjectId: wireId, actorId: myActorId ?? "", pubkey: myPublicKey, contentHash: hash)
            _ = sendPutV3(localId: id, wireObjectId: wireId, kind: entry.kind,
                          content: entry.content, recoveryStamp: recovery)
        }
        let gone = Set(lastContent.keys).union(forcedLocalDiff)
            .union(outboundDeliveries.all().map(\.localId))
            .filter { current[$0] == nil && !$0.hasPrefix("wire:") }
        for id in gone {
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == nil, pending.kind == "del" { continue }
            if let uuid = UUID(uuidString: id) {
                let wireId = SyncIdentity.wireObjectId(
                    metadataKey: keys.metadataKey,
                    localUuidBytes: SyncIdentity.uuidToBytes(uuid))
                guard sendDelV3(localId: id, wireObjectId: wireId) else { continue }
            }
        }
    }

    @discardableResult
    private func sendPutV3(localId: String, wireObjectId: String, kind: String, content: String,
                           recoveryStamp: VersionStamp? = nil) -> Bool {
        guard let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return false }
        let counter: Int64
        if let recoveryStamp, recoveryStamp.actorId == actorId { counter = recoveryStamp.counter }
        else {
            do { counter = try rs.reserveNextCounter() }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()
        let contentData = Data(content.utf8)
        let payloadHash = SyncIdentity.sha256(contentData)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPut, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: kind, payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return false }
        let inner: [String: Any] = ["c": content, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: kind)) else { return false }
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireObjectId, stamp: VersionStamp(counter: counter, actorId: actorId),
            publicKey: myPublicKey, kind: .put(contentHash: SyncIdentity.bytesToHex(payloadHash)))
        if recoveryStamp == nil {
            do { guard try rs.commit(mutation) else { return false } }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        let ciphertext = sealed.base64EncodedString()
        let session = SyncIdentity.urlB64Encode(sd)
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "put", "id": wireObjectId, "vs": vs, "by": actorId, "kind": kind,
            "ct": ciphertext, "pub": myPublicKey, "sd": session, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return false }
        queueDelivery(PendingOutboundDelivery(
            localId: localId, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: actorId, sessionDomain: session, wireObjectId: wireObjectId,
            objectVersion: vs, kind: kind, ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: SyncIdentity.bytesToHex(payloadHash), desiredContent: content, frame: frame
        ))
        return true
    }

    @discardableResult
    private func sendDelV3(localId: String, wireObjectId: String, recoveryStamp: VersionStamp? = nil) -> Bool {
        guard let key = roomKey, let actorId = myActorId, let rs = replayState,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return false }
        let counter: Int64
        if let recoveryStamp, recoveryStamp.actorId == actorId { counter = recoveryStamp.counter }
        else {
            do { counter = try rs.reserveNextCounter() }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()
        let payloadHash = SyncIdentity.sha256(Data())
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainDelete, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: "del", payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return false }
        let inner: [String: Any] = ["sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: "del")) else { return false }
        let mutation = SyncReplayState.DurableMutation(
            wireObjectId: wireObjectId, stamp: VersionStamp(counter: counter, actorId: actorId),
            publicKey: myPublicKey, kind: .delete)
        if recoveryStamp == nil {
            do { guard try rs.commit(mutation) else { return false } }
            catch { failClosedV3("Rollback-protection state could not be saved."); return false }
        }
        let ciphertext = sealed.base64EncodedString()
        let session = SyncIdentity.urlB64Encode(sd)
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "del", "id": wireObjectId, "vs": vs, "by": actorId, "kind": "del",
            "ct": ciphertext, "pub": myPublicKey, "sd": session, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return false }
        queueDelivery(PendingOutboundDelivery(
            localId: localId, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: actorId, sessionDomain: session, wireObjectId: wireObjectId,
            objectVersion: vs, kind: "del", ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: nil, desiredContent: nil, frame: frame
        ))
        return true
    }

    private func sendHelloV3() {
        guard let actorId = myActorId, let sd = sessionDomain,
              let keys = v3Keys, let pubRaw = myPublicKeyRaw,
              let seed = deviceSeed, let rs = replayState else {
            failClosedV3("Could not construct authenticated hello."); return
        }
        let epoch: String
        do { epoch = try rs.reserveHelloEpoch(actorId: actorId, pubkey: myPublicKey) }
        catch { failClosedV3("Could not reserve authenticated session epoch."); return }
        let vs = "\(epoch):\(actorId)"
        localHelloVersion = vs
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainHello, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd,
            counterHex16: epoch, objectId: "",
            kind: "hello", payloadHash: SyncIdentity.sha256(pubRaw))
        guard let sig = SyncSigning.sign(seed, preimage) else {
            failClosedV3("Could not send authenticated hello."); return
        }
        let frame: [String: Any] = [
            "t": "hello", "by": actorId, "pub": myPublicKey,
            "sd": SyncIdentity.urlB64Encode(sd), "vs": vs, "sig": sig
        ]
        // Transport failures use the same socket/generation-bound reconnect
        // path as every other frame. A delayed error from a replaced hello must
        // never fail-close the newer authenticated session.
        guard send(frame, completion: { _ in }) else {
            failClosedV3("Could not send authenticated hello.")
            return
        }
    }

    private func signedExplicitLeaveFrame() -> String? {
        guard protocolVersion == 3,
              status == .connected,
              !awaitingHelloAck,
              let actorId = myActorId,
              let sessionDomain,
              let helloVersion = localHelloVersion,
              let keys = v3Keys,
              let seed = deviceSeed,
              let preimage = SyncIdentity.explicitLeavePreimage(
                roomIdRaw: keys.roomIdRaw,
                actorId: actorId,
                sessionDomain: sessionDomain,
                helloVersion: helloVersion
              ),
              let signature = SyncSigning.sign(seed, preimage),
              let data = try? JSONSerialization.data(withJSONObject: [
                "t": "leave",
                "lv": SyncIdentity.explicitLeaveVersion,
                "by": actorId,
                "sd": SyncIdentity.urlB64Encode(sessionDomain),
                "vs": helloVersion,
                "sig": signature,
              ]) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func sendPresenceV3(
        location loc: CLLocation,
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        guard let key = roomKey, let actorId = myActorId,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return false }

        let cfg = presenceConfig
        let callsign = boundedCallsign(cfg.callsign)
        let lat = loc.coordinate.latitude
        let lon = loc.coordinate.longitude
        let heading = max(0, loc.course)
        let speed = max(0, loc.speed)
        guard PresenceLocationQuality.hasValidWireValues(
                latitude: lat, longitude: lon, heading: heading, speed: speed
              ),
              presenceCounter < VersionStamp.maxCounter else { return false }
        presenceCounter += 1
        let counter = presenceCounter
        let counterHex = VersionStamp.counterHex16(counter)
        let vs = VersionStamp(counter: counter, actorId: actorId).encode()

        // Serialize the nine signed fields exactly once. The byte string itself
        // rides inside the sealed envelope, so another platform verifies these
        // exact bytes instead of rebuilding JSON with a different number writer.
        let presence = PresencePayload(
            lat: lat, lon: lon, heading: heading, speed: speed,
            callsign: callsign, affiliation: cfg.affiliation,
            echelon: cfg.echelon, function: cfg.function, isHQ: cfg.isHQ)
        let payload = Self.buildPresencePayloadBytes(presence)
        guard let payload else { return false }
        let payloadHash = SyncIdentity.sha256(payload)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: "", kind: "loc", payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return false }

        let retentionSeconds = presenceCadence.advertisedRetentionSeconds
        guard let retentionPayload = PresenceRetentionAdvertisement.encodePayload(
            seconds: retentionSeconds
        ) else { return false }
        let retentionPreimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: "", kind: PresenceRetentionAdvertisement.signatureKind,
            payloadHash: SyncIdentity.sha256(retentionPayload)
        )
        guard let retentionSignature = SyncSigning.sign(seed, retentionPreimage) else { return false }
        guard let accuracyPayload = PresenceAccuracyAdvertisement.encodePayload(
            horizontalAccuracyMetres: loc.horizontalAccuracy
        ) else { return false }
        let accuracyPreimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: "", kind: PresenceAccuracyAdvertisement.signatureKind,
            payloadHash: SyncIdentity.sha256(accuracyPayload)
        )
        guard let accuracySignature = SyncSigning.sign(seed, accuracyPreimage) else { return false }
        // Keep the flat fields so pre-envelope iOS clients can still consume an
        // iOS sender. New clients verify the exact standard-base64 `p` bytes.
        var sealedDict = Self.makePresenceEnvelope(
            payload: presence, signedPayload: payload,
            publicKey: myPublicKey, signature: sig)
        sealedDict[PresenceRetentionAdvertisement.versionField] =
            PresenceRetentionAdvertisement.envelopeVersion
        sealedDict[PresenceRetentionAdvertisement.payloadField] =
            retentionPayload.base64EncodedString()
        sealedDict[PresenceRetentionAdvertisement.signatureField] = retentionSignature
        sealedDict[PresenceAccuracyAdvertisement.versionField] =
            PresenceAccuracyAdvertisement.envelopeVersion
        sealedDict[PresenceAccuracyAdvertisement.payloadField] =
            accuracyPayload.base64EncodedString()
        sealedDict[PresenceAccuracyAdvertisement.signatureField] = accuracySignature
        guard let sealedData = try? JSONSerialization.data(withJSONObject: sealedDict),
              let sealed = SyncCrypto.seal(
                key, sealedData,
                aad: SyncCrypto.aadPresenceV3(actorId: actorId, vs: vs)
              ) else { return false }
        return send(
            ["t": "loc", "by": actorId, "ct": sealed.base64EncodedString(),
             "pub": myPublicKey, "sd": SyncIdentity.urlB64Encode(sd), "vs": vs],
            completion: completion
        )
    }

    private func failClosedV3(_ message: String) {
        clearChatSessionSecrets()
        stopConnectionHealthChecks()
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        lastError = issueLifecycle.report(
            message, kind: .security, generation: activeConnectionGeneration
        )?.message
        wantConnected = false
        task?.cancel(with: .internalServerError, reason: nil)
        task = nil
        status = .offline
        peers.removeAll()
        activeSessions.removeAll()
        remotePresenceCandidateClusters.removeAll()
        onlineMembers = onlineMemberTracker.clear()
    }

    private func reportRemoteModelPersistenceFailure(_ error: Error,
                                                     remainsPending: Bool) {
        let recovery: String
        switch error {
        case SyncRemoteModelMutationError.identityCollision:
            recovery = "Resolve the duplicate waypoint/drawing identity locally, then rejoin Unit Sync."
        case SyncRemoteModelMutationError.invalidPayload:
            recovery = "Ask the sender to update TacMap and send the mission object again before rejoining."
        default:
            recovery = remainsPending
                ? "The verified update remains pending. Unlock mission data or free device storage, then rejoin Unit Sync to retry it."
                : "Unlock mission data or free device storage, then leave and rejoin Unit Sync so the relay can resend it."
        }
        lastError = issueLifecycle.report(
            "\(error.localizedDescription) \(recovery)",
            kind: .security,
            generation: activeConnectionGeneration
        )?.message
        remoteUpdateSubject.send("Sync update not saved. Open Unit Sync for recovery guidance.")
    }

    func boundedCallsign(_ value: String) -> String {
        String(value.unicodeScalars.prefix(64))
    }

    func boundedRoomName(_ value: String) -> String {
        PresenceConfig.boundedRoomName(value)
    }

    /// Update encrypted client-local display metadata for the active room.
    /// The derived room ID is only a lookup key; the name never enters a frame,
    /// route, header, authentication input, or relay URL.
    func updateRoomName(_ value: String) {
        guard room != nil, let roomId else { return }
        var updated = presenceConfig
        updated.setRoomName(value, for: roomId)
        guard updatePresenceConfig(updated) else { return }
        roomName = updated.roomName(for: roomId)
    }

    /// The sender's exact nine-field JSON payload. It is signed and embedded in
    /// the v1 envelope; receivers must hash these bytes rather than reserialize.
    nonisolated static func buildPresencePayloadBytes(_ payload: PresencePayload) -> Data? {
        let canonical: [String: Any] = [
            "lat": payload.lat, "lon": payload.lon,
            "heading": payload.heading, "speed": payload.speed,
            "callsign": payload.callsign, "affiliation": payload.affiliation,
            "echelon": payload.echelon, "function": payload.function,
            "isHQ": payload.isHQ
        ]
        return try? JSONSerialization.data(withJSONObject: canonical, options: .sortedKeys)
    }

    nonisolated static func makePresenceEnvelope(
        payload: PresencePayload,
        signedPayload: Data,
        publicKey: String,
        signature: String
    ) -> [String: Any] {
        [
            "lat": payload.lat, "lon": payload.lon,
            "heading": payload.heading, "speed": payload.speed,
            "callsign": payload.callsign, "affiliation": payload.affiliation,
            "echelon": payload.echelon, "function": payload.function,
            "isHQ": payload.isHQ,
            "pv": 1,
            "p": signedPayload.base64EncodedString(),
            "pub": publicKey,
            "sig": signature
        ]
    }

    /// Decode the authenticated payload envelope. `pv=1` uses the exact embedded
    /// bytes. An envelope with no version fields takes the legacy same-platform
    /// reconstruction path; malformed/unknown version fields never downgrade.
    nonisolated static func decodePresenceEnvelope(_ inner: [String: Any]) -> PresenceEnvelope? {
        guard let publicKey = inner["pub"] as? String, !publicKey.isEmpty,
              let signature = inner["sig"] as? String, !signature.isEmpty else { return nil }

        if inner["pv"] != nil || inner["p"] != nil {
            guard strictJSONInteger(inner["pv"], minimum: 1, maximum: 1) == 1,
                  let encoded = inner["p"] as? String,
                  let signedPayload = Data(base64Encoded: encoded),
                  signedPayload.base64EncodedString() == encoded,
                  let payload = decodePresencePayloadBytes(signedPayload) else { return nil }
            return PresenceEnvelope(
                payload: payload, signedPayload: signedPayload,
                publicKey: publicKey, signature: signature)
        }

        guard let payload = decodeLegacyPresenceFields(inner),
              let signedPayload = buildPresencePayloadBytes(payload) else { return nil }
        return PresenceEnvelope(
            payload: payload, signedPayload: signedPayload,
            publicKey: publicKey, signature: signature)
    }

    nonisolated static func decodePresencePayloadBytes(_ data: Data) -> PresencePayload? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              Set(object.keys) == Set([
                "lat", "lon", "heading", "speed", "callsign",
                "affiliation", "echelon", "function", "isHQ"
              ]),
              let callsign = object["callsign"] as? String,
              callsign.unicodeScalars.count <= 64,
              let affiliation = object["affiliation"] as? String,
              let echelon = object["echelon"] as? String,
              let function = object["function"] as? String,
              let isHQNumber = object["isHQ"] as? NSNumber,
              CFGetTypeID(isHQNumber) == CFBooleanGetTypeID(),
              let lat = strictPresenceDouble(object["lat"]),
              let lon = strictPresenceDouble(object["lon"]),
              let heading = strictPresenceDouble(object["heading"]),
              let speed = strictPresenceDouble(object["speed"]),
              PresenceLocationQuality.hasValidWireValues(
                latitude: lat, longitude: lon, heading: heading, speed: speed
              ) else { return nil }
        return PresencePayload(
            lat: lat, lon: lon, heading: heading, speed: speed,
            callsign: callsign, affiliation: affiliation,
            echelon: echelon, function: function,
            isHQ: isHQNumber.boolValue)
    }

    private nonisolated static func decodeLegacyPresenceFields(_ object: [String: Any]) -> PresencePayload? {
        let callsign = object["callsign"] as? String ?? ""
        guard callsign.unicodeScalars.count <= 64,
              let lat = strictPresenceDouble(object["lat"]), abs(lat) <= 90,
              let lon = strictPresenceDouble(object["lon"]), abs(lon) <= 180 else { return nil }
        return PresencePayload(
            lat: lat, lon: lon,
            heading: strictPresenceDouble(object["heading"]) ?? 0,
            speed: strictPresenceDouble(object["speed"]) ?? 0,
            callsign: callsign,
            affiliation: object["affiliation"] as? String ?? "unknown",
            echelon: object["echelon"] as? String ?? "team",
            function: object["function"] as? String ?? "infantry",
            isHQ: object["isHQ"] as? Bool ?? false)
    }

    private nonisolated static func strictPresenceDouble(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
        let result = number.doubleValue
        return result.isFinite ? result : nil
    }

    // MARK: - Presence broadcasting

    /// Send a recent GPS fix. Foreground presence remains live; screen-off
    /// sends are coalesced to the OPSEC-selected interval. A failed socket send
    /// remains due so the next Core Location wake can retry promptly.
    func sendPresence() {
        guard status == .connected,
              presenceConfig.shareLocation,
              let locationService,
              LiveLocationPermissionPolicy.shouldStartUpdates(
                for: locationService.authorisationStatus
              ),
              !presenceSendInFlight,
              let activeTask = task else { return }
        let uptime = ProcessInfo.processInfo.systemUptime
        guard presenceCadence.isDue(at: uptime) else { return }

        let now = Date()
        // Standard Core Location delivery is movement driven. Proactively
        // restart it before a stationary fix approaches relay expiry; never
        // make an old coordinate look current merely to keep the marker alive.
        locationService.refreshLocationIfNeeded(
            maximumAge: UnitSyncPresenceCadence.preferredLocationAge,
            maximumFutureSkew: UnitSyncPresenceCadence.maximumLocationFutureSkew,
            now: now,
            uptime: uptime
        )
        guard let loc = locationService.lastLocation,
              UnitSyncPresenceCadence.locationIsFresh(
                timestamp: loc.timestamp,
                now: now
              ),
              presenceCadence.canBroadcast(
                locationTimestamp: loc.timestamp,
                at: uptime
              ) else { return }
        let heading = max(0, loc.course)
        let speed = max(0, loc.speed)
        guard let qualityFix = PresenceLocationQuality.fix(from: loc, uptime: uptime),
              PresenceLocationQuality.hasValidWireValues(
                latitude: qualityFix.latitude,
                longitude: qualityFix.longitude,
                heading: heading,
                speed: speed
              ) else { return }
        let qualityEvaluation = PresenceLocationQuality.evaluateJump(
            previous: lastGoodLocalPresenceFix,
            candidate: qualityFix,
            existingCluster: localPresenceCandidateCluster,
            allowSimulatorTeleport: PresenceLocationQuality.allowsSimulatorTeleport
        )
        localPresenceCandidateCluster = qualityEvaluation.nextCluster
        guard qualityEvaluation.accepted else { return }
        // This is the last GPS fix to pass the quality boundary, independent of
        // whether the current websocket enqueue later succeeds.
        lastGoodLocalPresenceFix = qualityFix

        presenceSendInFlight = true
        let generation = activeConnectionGeneration
        let locationTimestamp = loc.timestamp
        let completion: (Bool) -> Void = { [weak self, weak activeTask] succeeded in
            guard let self, let activeTask,
                  self.task === activeTask,
                  self.activeConnectionGeneration == generation else { return }
            self.presenceSendInFlight = false
            if succeeded {
                self.presenceCadence.markBroadcast(
                    locationTimestamp: locationTimestamp,
                    at: uptime
                )
            }
        }

        let queued = protocolVersion == 3
            ? sendPresenceV3(location: loc, completion: completion)
            : sendPresenceV2(location: loc, completion: completion)
        if !queued { presenceSendInFlight = false }
    }

    private func sendPresenceV2(
        location loc: CLLocation,
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        guard let key = roomKey, let seed = deviceSeed else { return false }

        let cfg = presenceConfig
        let callsign = boundedCallsign(cfg.callsign)
        // Milliseconds since epoch (Int64), matching Android, so the signed
        // canonical message and the ts freshness check line up cross-platform.
        let ts = Int64(Date().timeIntervalSince1970 * 1000)
        let lat = loc.coordinate.latitude
        let lon = loc.coordinate.longitude
        let heading = max(0, loc.course)
        let speed = max(0, loc.speed)
        guard PresenceLocationQuality.hasValidWireValues(
            latitude: lat, longitude: lon, heading: heading, speed: speed
        ) else { return false }
        // Sign identity+position+ts with this device's Ed25519 key. The sig rides
        // INSIDE the sealed payload (relay stays blind), so a room member can't
        // forge another peer's presence and a replayed old blob fails the ts check.
        let sig = SyncSigning.sign(seed, SyncSigning.presenceMessage(
            clientId, ts, lat, lon, heading, speed,
            callsign, cfg.affiliation, cfg.echelon, cfg.function, cfg.isHQ)) ?? ""
        let payload: [String: Any] = [
            "callsign": callsign,
            "affiliation": cfg.affiliation,
            "echelon": cfg.echelon,
            "function": cfg.function,
            "isHQ": cfg.isHQ,
            "lat": lat,
            "lon": lon,
            "heading": heading,
            "speed": speed,
            "ts": ts,
            "pub": myPublicKey,
            "sig": sig
        ]
        guard let payloadData = try? JSONSerialization.data(withJSONObject: payload),
              let sealed = SyncCrypto.seal(
                key, payloadData, aad: Data("loc|\(clientId)".utf8)
              ) else { return false }
        return send(
            ["t": "loc", "clientId": clientId,
             "ct": sealed.base64EncodedString()],
            completion: completion
        )
    }

    private func startPresenceTimers() {
        reschedulePresenceBroadcastTimer()
        // Sweep stale peers every 30 seconds.
        stalenessTimer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.sweepStalePeers()
            }
        }
    }

    private func reschedulePresenceBroadcastTimer() {
        presenceTimer?.invalidate()
        presenceTimer = nil
        guard room != nil, presenceCadence.canBroadcast else { return }
        let timer = Timer(timeInterval: presenceCadence.timerInterval, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.sendPresence()
            }
        }
        presenceTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func stopPresenceTimers() {
        presenceTimer?.invalidate()
        presenceTimer = nil
        stalenessTimer?.invalidate()
        stalenessTimer = nil
    }

    /// Foreground/legacy presence expires quickly. A last-known v3 location can
    /// bridge the selected background interval only while its exact signed
    /// session remains active, and never for more than 65 minutes.
    private func sweepStalePeers() {
        let nowUptime = ProcessInfo.processInfo.systemUptime
        peers = peers.reduce(into: [String: PresencePeer]()) { result, entry in
            let (clientId, originalPeer) = entry
            let peer = PresenceExpiryPolicy.withFreshness(
                originalPeer,
                nowUptime: nowUptime
            )
            if PresenceExpiryPolicy.shouldRetain(
                peer,
                activeSession: activeSessions[clientId],
                nowUptime: nowUptime
            ) {
                result[clientId] = peer
            }
        }
        onlineMembers = onlineMemberTracker.expireStaleMetadata()
        refreshChatRecipients()
    }

    // MARK: Inbound

    private func handleMessage(
        _ text: String,
        data: Data,
        socket: URLSessionWebSocketTask,
        generation: Int64
    ) {
        // Defense in depth: the receive boundary has already bounded these
        // exact bytes before constructing or parsing JSON.
        guard data.count <= SyncInboundFramePolicy.maxFrameBytes,
              String(data: data, encoding: .utf8) == text,
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return }
        // fail-closed: any unexpected throw from downstream parsing is swallowed
        // so a malformed frame never kills the receive loop
        do {
            try handleParsedMessage(
                obj,
                frameBytes: data.count,
                socket: socket,
                generation: generation
            )
        } catch {
            // silently drop — don't log the frame content (SEC-019)
        }
    }

    private func handleParsedMessage(
        _ obj: [String: Any],
        frameBytes: Int,
        socket: URLSessionWebSocketTask,
        generation: Int64
    ) throws {
        if protocolVersion == 3 { handleParsedMessageV3(obj, frameBytes: frameBytes); return }
        if status != .connected {
            switch v2SnapshotGate.accept(
                socketIdentity: socket,
                generation: generation,
                message: obj,
                frameBytes: frameBytes
            ) {
            case .ignored, .pageAccepted:
                break
            case .began:
                status = .snapshotting
            case .rejected(let reason):
                failV2Snapshot(socket: socket, generation: generation, reason: reason)
            case .completed(let batch):
                v2SnapshotTimeoutItem?.cancel()
                v2SnapshotTimeoutItem = nil
                for item in batch.records {
                    applyRecord(item, snapshotGeneration: generation)
                }
                for member in batch.members { applyPresence(member) }
                guard task === socket, activeConnectionGeneration == generation else { return }
                finalizeLegacyDeleteSnapshotConfirmations(snapshotGeneration: generation)
                v2SnapshotFailureGeneration = nil
                reconnectBackoff.reset()
                status = .connected
                lastError = issueLifecycle.connectionSucceeded(
                    generation: generation,
                    verifiedCleanSnapshot: false
                )?.message
                syncLocalState(
                    waypoints: waypointStore.waypoints,
                    shapes: drawingStore.shapes,
                    layers: drawingStore.layers
                )
                presenceCadence.startAuthenticatedSession()
                startConnectionHealthChecks()
                sendPresence()
            }
            return
        }
        switch obj["t"] as? String {
        case "put":
            applyRecord(obj)
        case "del":
            applyDelete(obj)
        case "loc":
            applyPresence(obj)
        case "op-ack":
            applyDeliveryAck(obj, v3: false)
        case "op-nack":
            applyDeliveryNack(obj, v3: false)
        case "leave":
            if let cid = obj["clientId"] as? String {
                peers.removeValue(forKey: cid)
            }
        default:
            break
        }
    }

    private func applyDeliveryAck(_ obj: [String: Any], v3: Bool) {
        guard Self.strictJSONInteger(obj["av"], minimum: 1, maximum: 1) == 1,
              let requestId = obj["rid"] as? String,
              requestId.range(of: "^[A-Za-z0-9_-]{16,64}$", options: .regularExpression) != nil,
              let actor = obj["by"] as? String, !actor.isEmpty,
              let wireId = obj["id"] as? String, !wireId.isEmpty,
              let kind = obj["kind"] as? String, !kind.isEmpty,
              let cth = obj["cth"] as? String,
              SyncIdentity.decodeCanonical32(cth) != nil else { return }
        let session: String?
        let objectVersion: String
        if v3 {
            guard let value = obj["sd"] as? String, !value.isEmpty,
                  let version = obj["vs"] as? String, !version.isEmpty else { return }
            session = value; objectVersion = version
        } else {
            guard let version = strictVersion(obj["v"]) else { return }
            session = nil; objectVersion = String(version)
        }
        guard let delivered = outboundDeliveries.acknowledge(DeliveryAck(
            version: 1, requestId: requestId, actorId: actor, sessionDomain: session,
            wireObjectId: wireId, objectVersion: objectVersion, kind: kind,
            ciphertextHash: cth
        )) else { return }
        deliveryRetryItems.removeValue(forKey: delivered.requestId)?.cancel()

        if let desired = delivered.desiredContent {
            if reexport(id: delivered.localId) == desired {
                lastContent[delivered.localId] = desired
                kindById[delivered.localId] = delivered.kind
                forcedLocalDiff.remove(delivered.localId)
                forcedLegacyDeletes.removeValue(forKey: delivered.localId)
            } else {
                forcedLocalDiff.insert(delivered.localId)
            }
        } else if !localObjectExists(delivered.localId) {
            lastContent.removeValue(forKey: delivered.localId)
            kindById.removeValue(forKey: delivered.localId)
            forcedLocalDiff.remove(delivered.localId)
            forcedLegacyDeletes.removeValue(forKey: delivered.localId)
        } else {
            forcedLocalDiff.insert(delivered.localId)
        }
        if status == .connected {
            if protocolVersion == 3 {
                syncLocalStateV3(waypoints: waypointStore.waypoints,
                                 shapes: drawingStore.shapes,
                                 layers: drawingStore.layers)
            } else {
                syncLocalState(waypoints: waypointStore.waypoints,
                               shapes: drawingStore.shapes,
                               layers: drawingStore.layers)
            }
        }
    }

    private func applyDeliveryNack(_ obj: [String: Any], v3: Bool) {
        guard Self.strictJSONInteger(obj["av"], minimum: 1, maximum: 1) == 1,
              let requestId = obj["rid"] as? String,
              requestId.range(of: "^[A-Za-z0-9_-]{16,64}$", options: .regularExpression) != nil,
              let actor = obj["by"] as? String, !actor.isEmpty,
              let code = obj["code"] as? String,
              code.range(of: "^[a-z-]{1,32}$", options: .regularExpression) != nil,
              let retryable = obj["retry"] as? Bool else { return }
        let session: String?
        if v3 {
            guard let value = obj["sd"] as? String, !value.isEmpty else { return }
            session = value
        } else { session = nil }
        guard let pending = outboundDeliveries.reject(DeliveryNack(
            version: 1, requestId: requestId, actorId: actor,
            sessionDomain: session, code: code, retryable: retryable
        )) else { return }
        let message: String
        switch code {
        case "quota":
            message = "The Unit Sync room is full, so this saved local change was not uploaded. Remove room content or use a new room, then reconnect."
        case "storage":
            message = "The Unit Sync relay could not durably save this change. It remains saved locally and will be retried."
        case "stale", "not-found", "counter-window":
            message = "The Unit Sync relay rejected an out-of-date change. The local edit remains saved; reconnecting will reconcile it from a verified snapshot."
        case "session-replaced", "session-mismatch", "hello-required":
            message = "This Unit Sync session can no longer confirm changes. The local edit remains saved; reconnecting with a fresh authenticated session."
        default:
            message = "The Unit Sync relay rejected a change as invalid. The local edit remains saved; open Unit Sync for recovery guidance."
        }
        lastError = issueLifecycle.report(
            message, kind: .security, generation: pending.connectionGeneration
        )?.message
        if retryable {
            scheduleDeliveryRetry(pending)
        } else {
            deliveryRetryItems.removeValue(forKey: pending.requestId)?.cancel()
            if ["stale", "not-found", "counter-window", "session-replaced", "session-mismatch", "hello-required"].contains(code) {
                task?.cancel(with: .internalServerError, reason: nil)
            }
        }
    }

    private func localObjectExists(_ localId: String) -> Bool {
        waypointStore.waypoints.contains { $0.id.uuidString == localId } ||
            drawingStore.shapes.contains { $0.id.uuidString == localId }
    }

    /// Decrypt and parse an incoming `loc` message (or a member from the
    /// `snapshot`) and update the `peers` dictionary.
    private func applyPresence(_ obj: [String: Any]) {
        guard let cid = obj["clientId"] as? String, !cid.isEmpty else { return }
        guard cid != clientId else { return }
        guard let key = roomKey,
              let ctB64 = obj["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: Data("loc|\(cid)".utf8)),
              let p = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }

        let callsign = p["callsign"] as? String ?? ""
        guard callsign.unicodeScalars.count <= 64 else { return }
        let affiliation = p["affiliation"] as? String ?? "unknown"
        let echelon = p["echelon"] as? String ?? "team"
        let function = p["function"] as? String ?? "infantry"
        let isHQ = p["isHQ"] as? Bool ?? false
        guard let lat = PresenceLocationQuality.strictWireDouble(p["lat"]),
              let lon = PresenceLocationQuality.strictWireDouble(p["lon"]),
              let heading = PresenceLocationQuality.strictWireDouble(p["heading"]),
              let speed = PresenceLocationQuality.strictWireDouble(p["speed"]),
              let ts = Self.strictJSONInteger(
            p["ts"], minimum: 0, maximum: Int64.max
        ), PresenceLocationQuality.hasValidWireValues(
            latitude: lat, longitude: lon, heading: heading, speed: speed
        ), PresenceLocationQuality.isPlausibleLegacyTimestamp(
            ts,
            nowMilliseconds: Int64(Date().timeIntervalSince1970 * 1_000)
        ) else { return }

        // Per-device auth: pin the peer's key on first sight (TOFU), then require
        // every later presence to be signed by that same key. A room member can't
        // impersonate an established peer; a changed key is rejected as a possible
        // swap; a relay replaying an old (signed) blob is caught by the ts check.
        guard let pub = p["pub"] as? String, !pub.isEmpty,
              let sig = p["sig"] as? String, !sig.isEmpty else { return }
        let pinned = peerKeys[cid]
        if let pinned, pinned != pub { return }
        let signed = SyncSigning.presenceMessage(cid, ts, lat, lon, heading, speed,
                                                 callsign, affiliation, echelon, function, isHQ)
        guard SyncSigning.verify(pub, signed, sig) else { return }
        // Pin only an actually verified first frame. A malformed first packet
        // must not poison this in-memory TOFU slot and hide the real unit.
        if pinned == nil { peerKeys[cid] = pub }
        if let last = peerTs[cid], ts <= last { return }  // replay / rollback
        peerTs[cid] = ts

        peers[cid] = PresencePeer(
            clientId: cid,
            callsign: callsign,
            affiliation: affiliation,
            echelon: echelon,
            function: function,
            isHQ: isHQ,
            lat: lat,
            lon: lon,
            heading: heading,
            speed: speed,
            ts: Double(ts),
            receivedAt: Date()
        )
    }

    private func applyRecord(_ rec: [String: Any], snapshotGeneration: Int64? = nil) {
        // Snapshot tombstones arrive as records with deleted=true; route them
        // through the same signed-delete verification as a live "del".
        if (rec["deleted"] as? Bool) == true {
            applyDelete(rec, snapshotGeneration: snapshotGeneration)
            return
        }
        guard let id = rec["id"] as? String, !id.isEmpty else { return }
        guard let v = strictVersion(rec["v"]) else { return }
        // Monotonic per-id version: reject anything <= the highest applied, and
        // keep rejecting after a delete (versions[id] survives as a tombstone) so
        // a relay can't resurrect a deleted object by replaying an older-but-
        // validly-signed put.
        if let known = versions[id], known >= v { return } // stale / superseded / post-delete
        let recKind = rec["kind"] as? String ?? "unknown"
        let by = rec["by"] as? String ?? ""
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: id, v: v, kind: recKind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }
        let content = inner["c"] as? String ?? ""
        // Device authorship: the write must be signed by the key pinned to `by`
        // (TOFU). A room member can't forge a write as another established
        // device; a key that doesn't match the pin is rejected as a swap.
        guard verifyObjectSig(by: by, inner: inner,
                              signed: SyncSigning.objectMessage(id, v, recKind, by, content)),
              let contentData = content.data(using: .utf8) else { return }
        let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
        guard let parsed = try? GeoJSONImporter.parse(contentData, existingLayers: drawingStore.layers,
                                                      fallbackLayerID: fallback) else { return }
        if forcedLegacyDeletes[id] != nil {
            clock = max(clock, v)
            versions[id] = v
            return
        }
        if forcedLocalDiff.contains(id) {
            let current = reexport(id: id)
            clock = max(clock, v)
            versions[id] = v
            if current == content {
                lastContent[id] = current
                kindById[id] = recKind
                forcedLocalDiff.remove(id)
            }
            return
        }
        do {
            try SyncVerifiedV2RecordCommitter.apply(
                parsed,
                waypointStore: waypointStore,
                drawingStore: drawingStore
            ) {
                // Advance v2 clocks and diff baselines only after the
                // authenticated model is durable. A failed write remains
                // eligible for a later relay snapshot/retry.
                clock = max(clock, v)
                versions[id] = v
                kindById[id] = parsed.waypoints.isEmpty ? "drawing" : "waypoint"
                lastContent[id] = reexport(id: id)
            }
        } catch {
            reportRemoteModelPersistenceFailure(error, remainsPending: false)
            return
        }

        // Let UI know a remote change landed.
        let kind = parsed.waypoints.isEmpty ? "Drawing" : "Waypoint"
        remoteUpdateSubject.send("\(kind) updated by another device")
    }

    private func applyDelete(_ rec: [String: Any], snapshotGeneration: Int64? = nil) {
        guard let id = rec["id"] as? String, !id.isEmpty else { return }
        guard let v = strictVersion(rec["v"]) else { return }
        if let known = versions[id], known >= v { return } // stale / already-superseded delete
        let by = rec["by"] as? String ?? ""
        // Open the sealed proof (proves room-key possession, so a relay with no
        // room key can't forge a delete) then verify the device signature.
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: id, v: v, kind: "del")),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              verifyObjectSig(by: by, inner: inner,
                              signed: SyncSigning.objectMessage(id, v, "del", by, "")) else { return }
        if let recovery = forcedLegacyDeletes[id] {
            let exactSnapshotConfirmation = snapshotGeneration.map {
                recovery.matchesVerifiedTombstone(
                    localId: id,
                    wireObjectId: id,
                    actorId: by,
                    objectVersion: String(v),
                    kind: rec["kind"] as? String ?? "del",
                    ciphertextHash: ciphertextHash(ctB64),
                    snapshotGeneration: $0
                )
            } ?? false
            clock = max(clock, v)
            versions[id] = v
            if exactSnapshotConfirmation { pendingLegacyDeleteConfirmations.insert(id) }
            return
        }
        if forcedLocalDiff.contains(id) && localObjectExists(id) {
            clock = max(clock, v)
            versions[id] = v
            return
        }
        do {
            try SyncVerifiedV2RecordCommitter.delete(
                localID: id,
                waypointStore: waypointStore,
                drawingStore: drawingStore
            ) {
                clock = max(clock, v)
                versions[id] = v
                lastContent[id] = nil
                kindById[id] = nil
            }
        } catch {
            reportRemoteModelPersistenceFailure(error, remainsPending: false)
            return
        }

        remoteUpdateSubject.send("Object removed by another device")
    }

    private func finalizeLegacyDeleteSnapshotConfirmations(snapshotGeneration: Int64) {
        for id in pendingLegacyDeleteConfirmations {
            guard let recovery = forcedLegacyDeletes[id],
                  recovery.snapshotGeneration == snapshotGeneration else { continue }
            deliveryRetryItems.removeValue(forKey: recovery.requestId)?.cancel()
            forcedLegacyDeletes.removeValue(forKey: id)
            lastContent.removeValue(forKey: id)
            kindById.removeValue(forKey: id)
            if localObjectExists(id) { forcedLocalDiff.insert(id) }
            else { forcedLocalDiff.remove(id) }
        }
        pendingLegacyDeleteConfirmations.removeAll()
    }

    /// TOFU-pin `by`'s signing key and verify `signed` under it. Shared by
    /// object puts and deletes and by presence, so a device has one identity per
    /// clientId. Missing/garbage fields, or a key that doesn't match the existing
    /// pin, return false (the write is rejected).
    private func verifyObjectSig(by: String, inner: [String: Any], signed: Data) -> Bool {
        guard !by.isEmpty,
              let pub = inner["pub"] as? String, !pub.isEmpty,
              let sig = inner["sig"] as? String, !sig.isEmpty else { return false }
        if let pinned = peerKeys[by] { if pinned != pub { return false } } else { peerKeys[by] = pub }
        return SyncSigning.verify(pub, signed, sig)
    }

    /// Re-serialise so the next diff doesn't see a spurious change.
    private func reexport(id: String) -> String {
        let layers = drawingStore.layers
        if let wp = waypointStore.waypoints.first(where: { $0.id.uuidString == id }) {
            return (try? GeoJSONExporter.export(waypoints: [wp], drawings: [], layers: layers)) ?? ""
        }
        if let shape = drawingStore.shapes.first(where: { $0.id.uuidString == id }) {
            return (try? GeoJSONExporter.export(waypoints: [], drawings: [shape], layers: layers)) ?? ""
        }
        return ""
    }

    // strict parsing helpers (SEC-006) — reject anything that isn't an exact,
    // in-range, finite value instead of silently coercing garbage

    /// Non-negative integer version in [0, maxVersion]. Rejects floats with
    /// fractional parts, NaN, infinity, out-of-range, and wrong types.
    private func strictVersion(_ any: Any?) -> Int64? {
        Self.strictJSONInteger(any, minimum: 0, maximum: Self.maxVersion)
    }

    /// Parse an integer produced by `JSONSerialization` without confusing the
    /// numeric values 0 and 1 with JSON booleans. Swift's normal `value is Bool`
    /// check returns true for those two `__NSCFNumber` values as well as for the
    /// distinct `__NSCFBoolean` singleton, which previously made an empty room's
    /// valid `snapshot-begin { seq: 0 }` fail closed before iOS sent its hello.
    /// Floating-point encodings are rejected even when their rounded value is an
    /// integer: accepting them would let precision loss change a signed protocol
    /// field before its range check.
    /// Internal so the production parser can be regression-tested with values
    /// that have actually passed through `JSONSerialization`.
    nonisolated static func strictJSONInteger(_ value: Any?, minimum: Int64, maximum: Int64) -> Int64? {
        guard minimum <= maximum, let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID(),
              !CFNumberIsFloatType(number),
              let integer = Int64(number.stringValue),
              integer >= minimum, integer <= maximum else { return nil }
        return integer
    }

    // MARK: - v3 Inbound

    private func handleParsedMessageV3(_ obj: [String: Any], frameBytes: Int) {
        switch obj["t"] as? String {
        case "snapshot-begin":
            guard status == .connecting,
                  let seq = strictNonNegativeInt64(obj["seq"]) else {
                failClosedV3("Invalid sync snapshot fence.")
                return
            }
            snapshotSeq = seq
            snapshotRecords.removeAll()
            snapshotInvalid = false
            snapshotSawFinalPage = false
            snapshotAggregateBytes = frameBytes
            snapshotWireIds.removeAll()
            status = .snapshotting
        case "snapshot":
            snapshotAggregateBytes += frameBytes
            guard status == .snapshotting, snapshotSeq != nil,
                  let items = obj["items"] as? [[String: Any]],
                  let more = obj["more"] as? Bool,
                  !snapshotSawFinalPage,
                  snapshotRecords.count <= Self.maxSnapshotItems - items.count,
                  snapshotAggregateBytes <= Self.maxSnapshotAggregateBytes else {
                snapshotInvalid = true
                return
            }
            for item in items {
                guard let wireId = item["id"] as? String,
                      SyncIdentity.decodeCanonical32(wireId) != nil,
                      snapshotWireIds.insert(wireId).inserted else {
                    snapshotInvalid = true; return
                }
            }
            snapshotRecords.append(contentsOf: items)
            snapshotSawFinalPage = !more
        case "snapshot-end":
            snapshotAggregateBytes += frameBytes
            if snapshotAggregateBytes > Self.maxSnapshotAggregateBytes { snapshotInvalid = true }
            finishSnapshotV3(obj)
        case "hello":
            guard snapshotHasBeenAppliedV3 else { return }
            applyHelloV3(obj)
        case "hello-ack":
            applyHelloAckV3(obj)
        case "chat-key":
            guard snapshotHasBeenAppliedV3 else { return }
            applyChatKeyV3(obj)
        case "chat-key-ack":
            applyChatKeyAckV3(obj)
        case "chat-key-nack":
            applyChatKeyNackV3(obj)
        case "chat":
            guard snapshotHasBeenAppliedV3 else { return }
            applyChatV3(obj)
        case "chat-ack":
            applyChatAckV3(obj)
        case "chat-nack":
            applyChatNackV3(obj)
        case "op-ack":
            applyDeliveryAck(obj, v3: true)
        case "op-nack":
            applyDeliveryNack(obj, v3: true)
        case "put":
            guard snapshotHasBeenAppliedV3 else { return }
            applyLiveRecordV3(obj, deleted: false)
        case "del":
            guard snapshotHasBeenAppliedV3 else { return }
            applyLiveRecordV3(obj, deleted: true)
        case "loc":
            guard snapshotHasBeenAppliedV3 else { return }
            applyPresenceV3(obj)
        case "leave":
            guard let departure = acceptedV3Departure(
                obj, activeSessions: activeSessions, ownActorId: myActorId) else { return }
            activeSessions.removeValue(forKey: departure.actorId)
            remotePresenceCandidateClusters.removeValue(forKey: departure.actorId)
            onlineMembers = onlineMemberTracker.remove(
                clientId: departure.actorId,
                sessionDomain: departure.sessionDomain
            )
            peers[departure.actorId] = presencePeerAfterV3Departure(
                peers[departure.actorId],
                departure: departure
            )
            if chatPeerKeys[departure.actorId]?.sessionDomain == departure.sessionDomain {
                chatPeerKeys.removeValue(forKey: departure.actorId)
                refreshChatRecipients()
            }
        default:
            break
        }
    }

    private func finishSnapshotV3(_ obj: [String: Any]) {
        guard status == .snapshotting,
              let expectedSeq = snapshotSeq,
              let endSeq = strictNonNegativeInt64(obj["seq"]),
              endSeq == expectedSeq,
              !snapshotInvalid,
              snapshotSawFinalPage,
              let rs = replayState else {
            failClosedV3("Sync snapshot authentication failed.")
            return
        }

        var validated: [ValidatedRecordV3] = []
        validated.reserveCapacity(snapshotRecords.count)
        for record in snapshotRecords {
            let deleted = (record["deleted"] as? Bool) == true
            guard let value = validateRecordV3(record, deleted: deleted) else {
                failClosedV3("Sync snapshot contained an unauthenticated record.")
                return
            }
            validated.append(value)
        }

        let wasStale = rs.lastSnapshotSeq >= 0 && endSeq < rs.lastSnapshotSeq
        do {
            let remotes = validated.map {
                SyncReplayState.RemoteMutation(
                    mutation: $0.mutation,
                    priorModelHash: modelContentHash(localId: $0.localId),
                    localModelId: $0.localId,
                    acceptedGeneration: modelRevisionJournal?.generation($0.localId) ?? 0,
                    expectedModelHash: $0.expectedModelHash)
            }
            _ = try rs.commitRemoteSnapshot(remotes, seq: endSeq)
            snapshotConfirmedLocalDeletes.removeAll()
            if let actorId = myActorId {
                for value in validated where value.parsed == nil && value.mutation.stamp.actorId == actorId {
                    snapshotConfirmedLocalDeletes[value.mutation.wireObjectId] = value.mutation.stamp.encode()
                }
            }
            resolvingPendingModel = true
            defer { resolvingPendingModel = false }
            for (index, value) in validated.enumerated() {
                guard try resolvePendingModelApplication(value, currentHash: remotes[index].priorModelHash) else {
                    failClosedV3("Rollback-protection state could not be saved.")
                    return
                }
            }
            guard try resolveUnmatchedPendingModelApplications() else {
                failClosedV3("Rollback-protection state could not be saved.")
                return
            }
        } catch let error as SyncRemoteModelMutationError {
            reportRemoteModelPersistenceFailure(error, remainsPending: true)
            failClosedV3(lastError ?? "A pending authenticated sync update could not be saved.")
            return
        } catch {
            failClosedV3("Rollback-protection state could not be saved.")
            return
        }

        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        snapshotAggregateBytes = 0
        snapshotWireIds.removeAll()
        if wasStale {
            lastError = issueLifecycle.report(
                "The relay served an older snapshot; newer authenticated local state was retained.",
                kind: .security,
                generation: activeConnectionGeneration
            )?.message
        }
        awaitingHelloAck = true
        // Remain snapshotting (and therefore outbound-gated) until the relay
        // confirms it has verified/persisted hello and attached the actor tuple.
        sendHelloV3()
    }

    private var snapshotHasBeenAppliedV3: Bool {
        snapshotSeq == nil && (status == .snapshotting || status == .connected)
    }

    private func applyHelloAckV3(_ obj: [String: Any]) {
        guard awaitingHelloAck, status == .snapshotting,
              let actorId = myActorId,
              let by = obj["by"] as? String,
              let sd = obj["sd"] as? String,
              let vs = obj["vs"] as? String,
              let expectedVs = localHelloVersion,
              let ownSession = sessionDomain,
              SyncIdentity.helloAckMatches(actorId: actorId, sessionDomain: ownSession, expectedVersion: expectedVs,
                                           frameActorId: by, frameSessionDomain: sd, frameVersion: vs) else { return }
        awaitingHelloAck = false
        v3HandshakeTimeoutItem?.cancel()
        v3HandshakeTimeoutItem = nil
        reconnectBackoff.reset()
        status = .connected
        lastError = issueLifecycle.connectionSucceeded(
            generation: activeConnectionGeneration,
            verifiedCleanSnapshot: true
        )?.message
        V3HelloAckWorkSequencer.run {
            presenceCadence.startAuthenticatedSession()
            startConnectionHealthChecks()
            sendPresence()
        } reconcileMissionState: {
            replayState?.recoverableLocalDeletes(actorId: actorId, pubkey: myPublicKey).forEach {
                if !shouldResendRecoverableDelete(
                    wireObjectId: $0.0,
                    stamp: $0.1,
                    confirmedSnapshotDeletes: snapshotConfirmedLocalDeletes
                ) { return }
                let localId = findLocalIdForWireId($0.0) ?? "wire:\($0.0)"
                _ = sendDelV3(localId: localId, wireObjectId: $0.0, recoveryStamp: $0.1)
            }
            snapshotConfirmedLocalDeletes.removeAll()
            syncLocalStateV3(waypoints: waypointStore.waypoints,
                             shapes: drawingStore.shapes,
                             layers: drawingStore.layers)
        }
        startChatSessionV3()
    }

    private func applyHelloV3(_ obj: [String: Any]) {
        guard let by = obj["by"] as? String,
              let pub = obj["pub"] as? String,
              let sd = obj["sd"] as? String,
              let vs = obj["vs"] as? String,
              let sig = obj["sig"] as? String,
              by != myActorId,
              let rs = replayState, let keys = v3Keys,
              SyncIdentity.verifyHello(actorId: by, publicKey: pub,
                                       sessionDomain: sd, versionStamp: vs,
                                       signature: sig, roomIdRaw: keys.roomIdRaw) else { return }
        let epoch = String(vs.prefix(16))
        do {
            guard try rs.acceptHello(
                actorId: by, pubkey: pub, sessionDomain: sd, epochHex: epoch) else { return }
            if activeSessions[by]?.sessionDomain != sd {
                chatPeerKeys.removeValue(forKey: by)
                remotePresenceCandidateClusters.removeValue(forKey: by)
            }
            if activeSessions[by]?.sessionDomain != sd,
               let peer = peers[by] {
                peers[by] = peer.sessionDomain == sd
                    ? PresenceExpiryPolicy.transportRestored(peer)
                    : PresenceExpiryPolicy.markedStale(peer)
            }
            activeSessions[by] = V3ActiveSession(publicKey: pub, sessionDomain: sd)
            onlineMembers = onlineMemberTracker.authenticatedHello(
                clientId: by, sessionDomain: sd)
            refreshChatRecipients()
        } catch {
            failClosedV3("Actor rollback-protection state could not be saved.")
        }
    }

    private func applyChatKeyV3(_ obj: [String: Any]) {
        guard let actorId = obj["by"] as? String,
              actorId != myActorId,
              let active = activeSessions[actorId],
              obj["sd"] as? String == active.sessionDomain,
              replayState?.getPinnedPubkey(actorId) == active.publicKey,
              let roomIdRaw = v3Keys?.roomIdRaw,
              let key = TacMapChatCrypto.decodeAndVerifyPeerKey(
                obj,
                roomIdRaw: roomIdRaw,
                signingPublicKey: active.publicKey
              ),
              key.actorId == actorId,
              key.sessionDomain == active.sessionDomain else { return }
        if let existing = chatPeerKeys[actorId], existing != key {
            // One immutable key per authenticated socket. A changed key must
            // arrive under a replacement hello/session, never in-place.
            return
        }
        chatPeerKeys[actorId] = key
        refreshChatRecipients()
    }

    private func applyChatKeyAckV3(_ obj: [String: Any]) {
        guard Set(obj.keys) == Set(["t", "cv", "by", "sd", "kid"]),
              obj["t"] as? String == "chat-key-ack",
              Self.strictJSONInteger(obj["cv"], minimum: 1, maximum: 1) == 1,
              let local = chatLocalSession,
              obj["by"] as? String == local.actorId,
              obj["sd"] as? String == local.sessionDomain,
              obj["kid"] as? String == local.keyId,
              status == .connected else { return }
        chatKeyRetryItem?.cancel()
        chatKeyRetryItem = nil
        chatSessionReady = true
        chatSessionIssue = nil
    }

    private func applyChatKeyNackV3(_ obj: [String: Any]) {
        guard Set(obj.keys) == Set(["t", "cv", "by", "sd", "code"]),
              obj["t"] as? String == "chat-key-nack",
              Self.strictJSONInteger(obj["cv"], minimum: 1, maximum: 1) == 1,
              let local = chatLocalSession,
              obj["by"] as? String == local.actorId,
              obj["sd"] as? String == local.sessionDomain,
              let code = obj["code"] as? String,
              code.range(of: "^[a-z0-9_-]{1,64}$", options: .regularExpression) != nil else { return }
        clearChatSessionSecrets()
        chatSessionIssue = "The relay rejected encrypted chat capability (\(code))."
    }

    private func applyChatV3(_ obj: [String: Any]) {
        guard TacMapChatReadinessPolicy.permitsInbound(
                hasLocalSession: chatLocalSession != nil
              ),
              let local = chatLocalSession,
              let keys = v3Keys,
              let roomKey else { return }
        do {
            // Decode only the strict outer routing shape to locate an already
            // authenticated hello + chat-key tuple. Signature verification is
            // then completed before TacMapChatCrypto attempts AEAD decryption.
            let outer = try TacMapChatCrypto.decodeFrame(obj)
            guard let active = activeSessions[outer.senderActorId],
                  active.sessionDomain == outer.senderSessionDomain,
                  replayState?.getPinnedPubkey(outer.senderActorId) == active.publicKey,
                  let peerKey = chatPeerKeys[outer.senderActorId],
                  peerKey.sessionDomain == active.sessionDomain,
                  peerKey.keyId == outer.senderKeyId else { return }
            let opened = try TacMapChatCrypto.open(
                obj,
                roomIdRaw: keys.roomIdRaw,
                roomKey: roomKey,
                localSession: local,
                senderKey: peerKey,
                senderSigningPublicKey: active.publicKey
            )
            let senderName = onlineMembers[outer.senderActorId]?.displayName
                ?? peers[outer.senderActorId]?.callsign
                ?? "Unit \(String(outer.senderActorId.suffix(6)).uppercased())"
            let message = TacMapChatMessage(
                id: outer.messageId,
                roomId: keys.roomId,
                scope: outer.scope,
                senderActorId: outer.senderActorId,
                senderName: senderName,
                recipientActorId: outer.scope == .direct ? local.actorId : nil,
                recipientName: outer.scope == .direct
                    ? boundedCallsign(presenceConfig.callsign) : nil,
                kind: opened.payload.kind,
                body: opened.payload.body,
                sentAtMilliseconds: opened.payload.createdAt,
                isOutgoing: false,
                deliveryState: .received,
                failureCode: nil
            )
            switch try chatStore.acceptInbound(
                message,
                actorId: outer.senderActorId,
                sessionDomain: outer.senderSessionDomain,
                keyId: outer.senderKeyId,
                counter: outer.counter,
                fingerprint: opened.fingerprint
            ) {
            case .accepted:
                let label = opened.payload.kind == .report ? "report" : "message"
                remoteUpdateSubject.send("New TacMap Chat \(label) from \(senderName).")
            case .duplicate, .rejected:
                break
            }
        } catch let error as TacMapChatStore.StoreError {
            clearChatSessionSecrets()
            chatSessionIssue = error.localizedDescription
        } catch {
            // Malformed, misrouted, unverifiable, or undecryptable frames are
            // dropped without logging their content (SEC-019).
        }
    }

    private func applyChatAckV3(_ obj: [String: Any]) {
        guard obj["t"] as? String == "chat-ack",
              Self.strictJSONInteger(obj["cv"], minimum: 1, maximum: 1) == 1,
              let messageId = obj["mid"] as? String,
              let pending = pendingChatSends[messageId] else { return }
        let frame = pending.frame
        let expectedKeys = frame.scope == .room
            ? Set(["t", "cv", "by", "sd", "vs", "mid", "scope", "fromKid"])
            : Set(["t", "cv", "by", "sd", "vs", "mid", "scope", "fromKid", "to", "toSd", "toKid"])
        guard Set(obj.keys) == expectedKeys,
              obj["by"] as? String == frame.senderActorId,
              obj["sd"] as? String == frame.senderSessionDomain,
              obj["vs"] as? String == frame.versionStamp,
              obj["scope"] as? String == frame.scope.rawValue,
              obj["fromKid"] as? String == frame.senderKeyId else { return }
        if frame.scope == .direct {
            guard obj["to"] as? String == frame.recipientActorId,
                  obj["toSd"] as? String == frame.recipientSessionDomain,
                  obj["toKid"] as? String == frame.recipientKeyId else { return }
        }
        pendingChatSends.removeValue(forKey: messageId)
        do {
            try chatStore.updateDelivery(id: messageId, state: .routed)
        } catch {
            clearChatSessionSecrets()
            chatSessionIssue = "The routed message status could not be saved securely."
        }
    }

    private func applyChatNackV3(_ obj: [String: Any]) {
        guard Set(obj.keys) == Set(["t", "cv", "mid", "by", "sd", "code", "retry"]),
              obj["t"] as? String == "chat-nack",
              Self.strictJSONInteger(obj["cv"], minimum: 1, maximum: 1) == 1,
              let messageId = obj["mid"] as? String,
              let pending = pendingChatSends[messageId],
              obj["by"] as? String == pending.frame.senderActorId,
              obj["sd"] as? String == pending.frame.senderSessionDomain,
              let code = obj["code"] as? String,
              code.range(of: "^[a-z0-9_-]{1,64}$", options: .regularExpression) != nil,
              strictJSONBoolean(obj["retry"]) != nil else { return }
        pendingChatSends.removeValue(forKey: messageId)
        do {
            try chatStore.updateDelivery(id: messageId, state: .failed, failureCode: code)
        } catch {
            clearChatSessionSecrets()
            chatSessionIssue = "The unrouted message status could not be saved securely."
        }
    }

    private func strictJSONBoolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    private func applyLiveRecordV3(_ rec: [String: Any], deleted: Bool) {
        guard let rs = replayState,
              let validated = validateRecordV3(rec, deleted: deleted) else { return }
        do {
            let priorHash = modelContentHash(localId: validated.localId)
            guard try rs.commitRemote(.init(
                mutation: validated.mutation,
                priorModelHash: priorHash,
                localModelId: validated.localId,
                acceptedGeneration: modelRevisionJournal?.generation(validated.localId) ?? 0,
                expectedModelHash: validated.expectedModelHash)) else { return }
            resolvingPendingModel = true
            defer { resolvingPendingModel = false }
            guard try resolvePendingModelApplication(validated, currentHash: priorHash) else {
                failClosedV3("Rollback-protection state could not be saved.")
                return
            }
        } catch let error as SyncRemoteModelMutationError {
            reportRemoteModelPersistenceFailure(error, remainsPending: true)
            failClosedV3(lastError ?? "A pending authenticated sync update could not be saved.")
            return
        } catch {
            failClosedV3("Rollback-protection state could not be saved.")
            return
        }
    }

    private func modelContentHash(localId: String?) -> String? {
        guard let localId else { return nil }
        let content = reexport(id: localId)
        guard !content.isEmpty else { return nil }
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
    }

    private func resolvePendingModelApplication(_ value: ValidatedRecordV3, currentHash: String?) throws -> Bool {
        guard let rs = replayState else { return false }
        let mutation = value.mutation
        switch rs.pendingModelDecision(
            mutation,
            currentModelHash: currentHash,
            currentGeneration: modelRevisionJournal?.generation(value.localId) ?? 0) {
        case .applyIncoming:
            try applyValidatedRecordV3(value)
            guard modelContentHash(localId: value.localId) == value.expectedModelHash else { return false }
            return try rs.clearPendingModelApplication(mutation)
        case .alreadyApplied:
            markModelBaseline(value)
            return try rs.clearPendingModelApplication(mutation)
        case .localDiverged:
            if let localId = value.localId { forcedLocalDiff.insert(localId) }
            return try rs.clearPendingModelApplication(mutation)
        case .none:
            if rs.isExactPersistedMutation(mutation) {
                if currentHash == value.expectedModelHash { markModelBaseline(value) }
                else if let localId = value.localId { forcedLocalDiff.insert(localId) }
            }
            return true
        }
    }

    private func markModelBaseline(_ value: ValidatedRecordV3) {
        guard let localId = value.localId else { return }
        if value.parsed == nil {
            lastContent[localId] = nil; kindById[localId] = nil
        } else {
            let content = reexport(id: localId)
            if !content.isEmpty {
                lastContent[localId] = content
                kindById[localId] = value.parsed?.waypoints.isEmpty == false ? "waypoint" : "drawing"
            }
        }
        forcedLocalDiff.remove(localId)
    }

    /// If the relay omitted or contradicted pending work, the authenticated
    /// ciphertext needed for repair is unavailable. Preserve the current model
    /// and force it to win with a new local stamp after hello acknowledgement.
    private func resolveUnmatchedPendingModelApplications() throws -> Bool {
        guard let rs = replayState else { return false }
        for remote in rs.pendingRemoteMutations() {
            let current = modelContentHash(localId: remote.localModelId)
            if current == remote.expectedModelHash {
                markCurrentModelBaseline(localId: remote.localModelId)
            } else if let localId = remote.localModelId {
                forcedLocalDiff.insert(localId)
            }
            guard try rs.clearPendingModelApplication(remote.mutation) else { return false }
        }
        return true
    }

    private func markCurrentModelBaseline(localId: String?) {
        guard let localId else { return }
        let content = reexport(id: localId)
        if content.isEmpty {
            lastContent[localId] = nil; kindById[localId] = nil
        } else {
            lastContent[localId] = content
            kindById[localId] = waypointStore.waypoints.contains { $0.id.uuidString == localId }
                ? "waypoint" : "drawing"
        }
        forcedLocalDiff.remove(localId)
    }

    /// Authenticate and parse without changing replay or application state.
    private func validateRecordV3(_ rec: [String: Any], deleted: Bool) -> ValidatedRecordV3? {
        guard let wireId = rec["id"] as? String,
              SyncIdentity.decodeCanonical32(wireId) != nil,
              let vsString = rec["vs"] as? String,
              let stamp = VersionStamp.parse(vsString),
              let by = rec["by"] as? String, stamp.actorId == by,
              let publicKey = rec["pub"] as? String,
              let sessionString = rec["sd"] as? String,
              let sessionRaw = SyncIdentity.decodeCanonical32(sessionString),
              let kind = rec["kind"] as? String,
              kind.count <= 32,
              deleted ? kind == "del" : (kind == "waypoint" || kind == "drawing"),
              let keys = v3Keys,
              SyncIdentity.actorBindingIsValid(actorId: by, publicKey: publicKey, roomIdRaw: keys.roomIdRaw),
              replayState?.actorKeyIsAcceptable(by, pubkey: publicKey) == true,
              let key = roomKey,
              let ctBase64 = rec["ct"] as? String,
              ctBase64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctBase64), blob.base64EncodedString() == ctBase64,
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadV3(wireObjectId: wireId, vs: vsString, kind: kind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              let signature = inner["sig"] as? String else { return nil }

        let contentData: Data
        let parsed: GeoJSONImporter.Result?
        let domain: UInt8
        let payloadHash: Data
        let localId: String?
        let expectedModelHash: String?
        if deleted {
            guard inner["c"] == nil else { return nil }
            contentData = Data()
            parsed = nil
            domain = SyncIdentity.domainDelete
            payloadHash = SyncIdentity.sha256(contentData)
            localId = findLocalIdForWireId(wireId)
            expectedModelHash = nil
        } else {
            guard let content = inner["c"] as? String,
                  let bytes = content.data(using: .utf8) else { return nil }
            contentData = bytes
            let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
            guard let imported = try? GeoJSONImporter.parse(bytes, existingLayers: drawingStore.layers,
                                                             fallbackLayerID: fallback),
                  imported.invalidSkipped == 0,
                  (kind == "waypoint" ? (imported.waypoints.count == 1 && imported.drawings.isEmpty)
                                      : (imported.drawings.count == 1 && imported.waypoints.isEmpty)) else { return nil }
            let embeddedID = kind == "waypoint" ? imported.waypoints[0].id : imported.drawings[0].id
            let computedWireID = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(embeddedID))
            guard computedWireID == wireId else { return nil }
            localId = embeddedID.uuidString
            parsed = imported
            expectedModelHash = receiverModelHash(parsed: imported, localId: embeddedID)
            guard expectedModelHash != nil else { return nil }
            domain = SyncIdentity.domainPut
            payloadHash = SyncIdentity.sha256(contentData)
        }

        let preimage = SyncIdentity.buildPreimage(
            domain: domain, roomIdRaw: keys.roomIdRaw, actorId: by,
            sessionDomain: sessionRaw,
            counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: wireId, kind: kind, payloadHash: payloadHash)
        guard SyncSigning.verify(publicKey, preimage, signature) else { return nil }

        let mutationKind: SyncReplayState.MutationKind = deleted
            ? .delete
            : .put(contentHash: SyncIdentity.bytesToHex(payloadHash))
        return ValidatedRecordV3(
            mutation: .init(wireObjectId: wireId, stamp: stamp,
                            publicKey: publicKey, kind: mutationKind),
            parsed: parsed, localId: localId, expectedModelHash: expectedModelHash)
    }

    /// Receiver-local fixed-point hash; the authenticated payload hash remains sender bytes.
    private func receiverModelHash(parsed: GeoJSONImporter.Result, localId: UUID) -> String? {
        var layers = drawingStore.layers
        for layer in parsed.newLayers where !layers.contains(where: { $0.id == layer.id }) { layers.append(layer) }
        let content: String?
        if let waypoint = parsed.waypoints.first(where: { $0.id == localId }) {
            content = try? GeoJSONExporter.export(waypoints: [waypoint], drawings: [], layers: layers)
        } else if let drawing = parsed.drawings.first(where: { $0.id == localId }) {
            content = try? GeoJSONExporter.export(waypoints: [], drawings: [drawing], layers: layers)
        } else {
            content = nil
        }
        guard let content else { return nil }
        return SyncIdentity.bytesToHex(SyncIdentity.sha256(Data(content.utf8)))
    }

    private func applyValidatedRecordV3(_ value: ValidatedRecordV3) throws {
        guard let parsed = value.parsed else {
            if let localId = value.localId {
                try SyncRemoteModelApplier.delete(
                    localID: localId,
                    waypointStore: waypointStore,
                    drawingStore: drawingStore
                )
                lastContent[localId] = nil
                kindById[localId] = nil
            }
            remoteUpdateSubject.send("Object removed by another device")
            refreshObservedModelBaseline(localId: value.localId)
            return
        }

        try SyncRemoteModelApplier.apply(
            parsed,
            waypointStore: waypointStore,
            drawingStore: drawingStore
        )
        if let waypoint = parsed.waypoints.first {
            let id = waypoint.id.uuidString
            kindById[id] = "waypoint"
            lastContent[id] = reexport(id: id)
            remoteUpdateSubject.send("Waypoint updated by another device")
        } else if let shape = parsed.drawings.first {
            let id = shape.id.uuidString
            kindById[id] = "drawing"
            lastContent[id] = reexport(id: id)
            remoteUpdateSubject.send("Drawing updated by another device")
        }
        refreshObservedModelBaseline(localId: value.localId)
    }

    private func applyPresenceV3(_ obj: [String: Any]) {
        guard let actorId = obj["by"] as? String,
              actorId != myActorId else { return }
        guard let pub = obj["pub"] as? String,
              let sdString = obj["sd"] as? String,
              let sd = SyncIdentity.decodeCanonical32(sdString),
              let vsStr = obj["vs"] as? String,
              let stamp = VersionStamp.parse(vsStr),
              stamp.actorId == actorId,
              let rs = replayState, let keys = v3Keys,
              SyncIdentity.actorBindingIsValid(actorId: actorId, publicKey: pub, roomIdRaw: keys.roomIdRaw),
              activeSessions[actorId] == V3ActiveSession(publicKey: pub, sessionDomain: sdString),
              rs.getPinnedPubkey(actorId) == pub,
              rs.activeSessionDomain(actorId) == sdString else { return }
        guard let key = roomKey,
              let ctB64 = obj["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              blob.base64EncodedString() == ctB64,
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aadPresenceV3(actorId: actorId, vs: vsStr)),
              let p = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }

        guard let envelope = Self.decodePresenceEnvelope(p),
              envelope.publicKey == pub else { return }
        let presence = envelope.payload
        let payloadHash = SyncIdentity.sha256(envelope.signedPayload)
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: VersionStamp.counterHex16(stamp.counter),
            objectId: "", kind: "loc", payloadHash: payloadHash)
        guard SyncSigning.verify(pub, preimage, envelope.signature) else { return }

        let retentionWindow: TimeInterval
        switch PresenceRetentionAdvertisement.decode(from: p) {
        case .absent:
            retentionWindow = PresenceExpiryPolicy.liveUpdateWindow
        case .invalid:
            return
        case .valid(let advertisement):
            let retentionPreimage = SyncIdentity.buildPreimage(
                domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
                actorId: actorId, sessionDomain: sd,
                counterHex16: VersionStamp.counterHex16(stamp.counter),
                objectId: "", kind: PresenceRetentionAdvertisement.signatureKind,
                payloadHash: SyncIdentity.sha256(advertisement.signedPayload)
            )
            guard SyncSigning.verify(pub, retentionPreimage, advertisement.signature) else { return }
            retentionWindow = TimeInterval(advertisement.seconds)
        }

        let horizontalAccuracyMetres: Double?
        switch PresenceAccuracyAdvertisement.decode(from: p) {
        case .absent:
            horizontalAccuracyMetres = nil
        case .invalid:
            return
        case .valid(let advertisement):
            let accuracyPreimage = SyncIdentity.buildPreimage(
                domain: SyncIdentity.domainPresence, roomIdRaw: keys.roomIdRaw,
                actorId: actorId, sessionDomain: sd,
                counterHex16: VersionStamp.counterHex16(stamp.counter),
                objectId: "", kind: PresenceAccuracyAdvertisement.signatureKind,
                payloadHash: SyncIdentity.sha256(advertisement.signedPayload)
            )
            guard SyncSigning.verify(pub, accuracyPreimage, advertisement.signature) else { return }
            horizontalAccuracyMetres = advertisement.horizontalAccuracyMetres
        }
        guard PresenceLocationQuality.hasValidWireValues(
            latitude: presence.lat,
            longitude: presence.lon,
            heading: presence.heading,
            speed: presence.speed
        ) else { return }
        let nowUptime = ProcessInfo.processInfo.systemUptime
        if let horizontalAccuracyMetres {
            let previous = peers[actorId].flatMap { old -> PresenceLocationFix? in
                guard let accuracy = old.horizontalAccuracyMetres else { return nil }
                return PresenceLocationFix(
                    latitude: old.lat,
                    longitude: old.lon,
                    horizontalAccuracyMetres: accuracy,
                    monotonicTime: old.receivedAtUptime
                )
            }
            let candidate = PresenceLocationFix(
                latitude: presence.lat,
                longitude: presence.lon,
                horizontalAccuracyMetres: horizontalAccuracyMetres,
                monotonicTime: nowUptime
            )
            let evaluation = PresenceLocationQuality.evaluateJump(
                previous: previous,
                candidate: candidate,
                existingCluster: remotePresenceCandidateClusters[actorId],
                allowSimulatorTeleport: PresenceLocationQuality.allowsSimulatorTeleport
            )
            guard evaluation.accepted else {
                remotePresenceCandidateClusters[actorId] = evaluation.nextCluster
                return
            }
            remotePresenceCandidateClusters.removeValue(forKey: actorId)
        } else {
            remotePresenceCandidateClusters.removeValue(forKey: actorId)
        }
        do {
            guard try rs.acceptPresence(
                actorId: actorId, sessionDomain: sdString, counter: stamp.counter) else { return }
        } catch {
            failClosedV3("Presence replay state could not be saved.")
            return
        }

        peers[actorId] = PresencePeer(
            clientId: actorId,
            callsign: presence.callsign,
            affiliation: presence.affiliation,
            echelon: presence.echelon,
            function: presence.function,
            isHQ: presence.isHQ,
            lat: presence.lat,
            lon: presence.lon,
            heading: presence.heading,
            speed: presence.speed,
            ts: Date().timeIntervalSince1970 * 1000,
            sessionDomain: sdString,
            retentionWindow: retentionWindow,
            horizontalAccuracyMetres: horizontalAccuracyMetres,
            receivedAt: Date(),
            receivedAtUptime: nowUptime
        )
        let now = Date()
        _ = onlineMemberTracker.authenticatedActivity(
            clientId: actorId, sessionDomain: sdString, now: now)
        onlineMembers = onlineMemberTracker.updatePresenceMetadata(
            clientId: actorId,
            sessionDomain: sdString,
            callsign: presence.callsign,
            affiliation: presence.affiliation,
            echelon: presence.echelon,
            function: presence.function,
            isHQ: presence.isHQ,
            now: now
        )
        refreshChatRecipients()
    }

    private func strictNonNegativeInt64(_ value: Any?) -> Int64? {
        Self.strictJSONInteger(value, minimum: 0, maximum: Int64.max)
    }

    /// Reverse-lookup: find the local UUID string that maps to a given wire object ID.
    private func findLocalIdForWireId(_ wireId: String) -> String? {
        guard let keys = v3Keys else { return nil }
        for id in lastContent.keys {
            guard let uuid = UUID(uuidString: id) else { continue }
            let computed = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(uuid))
            if computed == wireId { return id }
        }
        // also check stores directly
        for wp in waypointStore.waypoints {
            let computed = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(wp.id))
            if computed == wireId { return wp.id.uuidString }
        }
        for shape in drawingStore.shapes {
            let computed = SyncIdentity.wireObjectId(
                metadataKey: keys.metadataKey,
                localUuidBytes: SyncIdentity.uuidToBytes(shape.id))
            if computed == wireId { return shape.id.uuidString }
        }
        return nil
    }
}
