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
    case rejected(LocalizedMessage)
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
            return reject(Messages.syncTheRelaySnapshotExceededTheSafeSizeLimitMessage())
        }
        aggregateBytes += frameBytes

        switch message["t"] as? String {
        case "snapshot-begin": return acceptBegin(message)
        case "snapshot": return acceptPage(message)
        case "snapshot-end": return acceptEnd(message)
        default: return reject(Messages.syncTheRelaySentLiveDataBeforeCompletingItsSnapshotMessage())
        }
    }

    func timeout(socketIdentity: AnyObject, generation: Int64) -> V2SnapshotGateEvent {
        guard isCurrent(socketIdentity, generation: generation) else { return .ignored }
        switch phase {
        case .awaitingBegin, .receiving, .finalPage:
            return reject(Messages.syncTheRelayDidNotCompleteItsInitialSnapshotInMessage())
        default:
            return .ignored
        }
    }

    private func acceptBegin(_ message: [String: Any]) -> V2SnapshotGateEvent {
        guard phase == .awaitingBegin else {
            return reject(Messages.syncTheRelaySnapshotBeganOutOfOrderMessage())
        }
        guard let value = SyncManager.strictJSONInteger(
            message["seq"], minimum: 0, maximum: Int64.max
        ) else { return reject(Messages.syncTheRelaySnapshotFenceWasMalformedMessage()) }
        sequence = value
        phase = .receiving
        return .began
    }

    private func acceptPage(_ message: [String: Any]) -> V2SnapshotGateEvent {
        guard phase == .receiving else {
            return reject(Messages.syncTheRelaySnapshotPageArrivedOutOfOrderMessage())
        }
        guard let items = message["items"] as? [[String: Any]],
              let more = strictJSONBoolean(message["more"]),
              items.count <= maxItems - records.count else {
            return reject(Messages.syncTheRelaySnapshotPageWasMalformedOrContainedTooMessage())
        }
        records.append(contentsOf: items)

        if message.keys.contains("members") {
            guard let pageMembers = message["members"] as? [[String: Any]],
                  pageMembers.count <= maxItems - members.count else {
                return reject(Messages.syncTheRelaySnapshotMemberListWasMalformedOrTooMessage())
            }
            members.append(contentsOf: pageMembers)
        }
        if !more { phase = .finalPage }
        return .pageAccepted
    }

    private func acceptEnd(_ message: [String: Any]) -> V2SnapshotGateEvent {
        guard phase == .finalPage else {
            return reject(Messages.syncTheRelaySnapshotEndedBeforeItsFinalPageMessage())
        }
        guard let expected = sequence,
              let actual = SyncManager.strictJSONInteger(
                message["seq"], minimum: 0, maximum: Int64.max
              ) else { return reject(Messages.syncTheRelaySnapshotEndFenceWasMalformedMessage()) }
        guard actual == expected else {
            return reject(Messages.syncTheRelaySnapshotFenceChangedBeforeCompletionMessage())
        }
        phase = .complete
        return .completed(V2SnapshotBatch(
            sequence: expected, records: records, members: members
        ))
    }

    private func reject(_ reason: LocalizedMessage) -> V2SnapshotGateEvent {
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
    func pending(requestId: String) -> PendingOutboundDelivery? {
        localIdByRequest[requestId].flatMap { byLocalId[$0] }
    }
    func all() -> [PendingOutboundDelivery] { Array(byLocalId.values) }

    /// A non-retryable nack settles the op for good: it leaves the tracker and
    /// never goes to reconciliation, so a rejected stamp isnt resent forever.
    @discardableResult
    func resolve(requestId: String) -> PendingOutboundDelivery? {
        guard let localId = localIdByRequest.removeValue(forKey: requestId),
              let pending = byLocalId[localId], pending.requestId == requestId else { return nil }
        byLocalId.removeValue(forKey: localId)
        return pending
    }

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
              // the relay's retry flag decides, so an unknown retry:true code
              // behaves like storage (contract section 6)
              pending.rejectionRetryable != false else { return nil }
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

}

func shouldResendRecoverableDelete(
    wireObjectId: String,
    stamp: VersionStamp,
    confirmedSnapshotDeletes: [String: String]
) -> Bool {
    confirmedSnapshotDeletes[wireObjectId] != stamp.encode()
}

enum SyncIssueKind { case connection, security }
struct SyncIssue {
    let pendingMessage: LocalizedMessage
    var message: String { pendingMessage.text }
    let kind: SyncIssueKind
    let generation: Int64
}

/// A same-generation hello acknowledgement cannot erase a rollback warning.
/// Only dismissal or a later clean verified snapshot may retire it.
final class SyncIssueLifecycle {
    private(set) var generation: Int64 = 0
    private var transientIssue: SyncIssue?
    private var persistentSecurityIssue: SyncIssue?
    /// Stays until leave or Retry (paused states, room reset).
    private var pinnedIssue: SyncIssue?
    /// Survives reconnects, gone on dismiss (skipped records, quota...).
    private var noticeIssue: SyncIssue?
    var issue: SyncIssue? {
        if transientIssue?.kind == .security { return transientIssue }
        if let persistentSecurityIssue { return persistentSecurityIssue }
        if pinnedIssue?.kind == .security { return pinnedIssue }
        return transientIssue ?? pinnedIssue ?? noticeIssue
    }

    @discardableResult
    func pin(_ message: LocalizedMessage, kind: SyncIssueKind) -> SyncIssue? {
        pinnedIssue = SyncIssue(pendingMessage: message, kind: kind, generation: generation)
        return issue
    }

    @discardableResult
    func clearPin() -> SyncIssue? {
        pinnedIssue = nil
        return issue
    }

    @discardableResult
    func notice(_ message: LocalizedMessage) -> SyncIssue? {
        noticeIssue = SyncIssue(pendingMessage: message, kind: .connection, generation: generation)
        return issue
    }

    func beginConnection() -> Int64 { generation += 1; return generation }

    @discardableResult
    func report(_ message: LocalizedMessage, kind: SyncIssueKind, generation: Int64? = nil) -> SyncIssue? {
        let at = generation ?? self.generation
        if let current = transientIssue {
            if current.kind == .security && kind == .connection { return issue }
            if current.kind == .security && kind == .security && at < current.generation { return issue }
            if current.kind == .connection && kind == .connection && at < current.generation { return issue }
        }
        transientIssue = SyncIssue(pendingMessage: message, kind: kind, generation: at)
        return issue
    }

    /// A relay snapshot cannot prove that an on-disk migration succeeded.
    /// Keep this warning until a later local migration completes cleanly.
    @discardableResult
    func reportPersistentSecurity(
        _ message: LocalizedMessage,
        generation: Int64? = nil
    ) -> SyncIssue? {
        persistentSecurityIssue = SyncIssue(
            pendingMessage: message,
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
        noticeIssue = nil
        return issue
    }

    /// Leave wipes everything tied to the join.
    @discardableResult
    func resetForLeave() -> SyncIssue? {
        pinnedIssue = nil
        noticeIssue = nil
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

enum SyncRemoteModelMutationError: LocalizedError, LocalizedMessageError {
    case invalidPayload
    case identityCollision(UUID)
    case persistence(Error)

    var errorDescription: String? { localizedMessage.text }

    var localizedMessage: LocalizedMessage {
        switch self {
        case .invalidPayload:
            return Messages.syncTheAuthenticatedSyncRecordDidNotContainExactlyOneMessage()
        case .identityCollision(let id):
            return Messages.syncTheAuthenticatedSyncRecordConflictsWithAnotherObjectTypeMessage(id.uuidString)
        case .persistence(let error):
            return Messages.syncTheAuthenticatedSyncUpdateCouldNotBeSavedMessage("").withArgument(0, error.displayMessage)
        }
    }
}

/// The sole production model-application seam for authenticated v2/v3 remote
/// objects. Every store write completes before an `@Published` collection is
/// changed, so observers cannot upload or display a write that failed on disk.
@MainActor
enum SyncRemoteModelApplier {
    /// True while a remote object is being written. Local follow-up edits
    /// (range rings following a symbol) must not run inside a remote apply:
    /// the peer that moved the symbol sends its own ring updates.
    private(set) static var isApplying = false

    static func apply(_ parsed: GeoJSONImporter.Result,
                      waypointStore: WaypointStore,
                      drawingStore: DrawingStore) throws {
        isApplying = true
        defer { isApplying = false }
        let suspended = suspendUndoRegistration(waypointStore, drawingStore)
        defer { suspended.forEach { $0.enableUndoRegistration() } }
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
                // only a write that changed something counts, a reconnect re-applying the
                // same record mustnt cancel your pending undo
                let changed = waypointStore.waypoints.contains(where: { $0.id == waypoint.id })
                    ? try waypointStore.commitEdit(waypoint, actionName: L10n.text("Apply Synced Waypoint"))
                    : try waypointStore.addDurably(waypoint)
                if changed { waypointStore.notePeerWrite(waypoint.id) }
            } else if let shape = parsed.drawings.first {
                let changed = drawingStore.shapes.contains(where: { $0.id == shape.id })
                    ? try drawingStore.commitEdit(shape, actionName: L10n.text("Apply Synced Drawing"))
                    : try drawingStore.addDurably(shape)
                if changed { drawingStore.notePeerWrite(shape.id) }
            }
        } catch let error as SyncRemoteModelMutationError {
            throw error
        } catch {
            throw SyncRemoteModelMutationError.persistence(error)
        }
    }

    enum BatchOp {
        case upsert(GeoJSONImporter.Result)
        case delete(String)
    }

    struct BatchOutcome {
        /// op index -> why that one record was refused (bad payload, or it
        /// collides with an object of the other kind). Refused ops are skipped,
        /// the rest of the batch still applies.
        var refused: [Int: SyncRemoteModelMutationError] = [:]
    }

    /// A whole inbound batch or snapshot in one durable write per store
    /// (contract 17). Same per-record checks as apply/delete above, done
    /// against the stores plus the earlier ops of the batch. The drawing
    /// document (layers + shapes) is written first so a waypoint never lands
    /// pointing at a layer that failed to save.
    static func applyBatch(_ ops: [BatchOp],
                           waypointStore: WaypointStore,
                           drawingStore: DrawingStore) throws -> BatchOutcome {
        isApplying = true
        defer { isApplying = false }
        var outcome = BatchOutcome()
        var waypointIDs = Set(waypointStore.waypoints.map(\.id))
        var drawingIDs = Set(drawingStore.shapes.map(\.id))
        var waypointUpserts: [Waypoint] = []
        var shapeUpserts: [DrawingShape] = []
        var waypointDeletes = Set<UUID>()
        var shapeDeletes = Set<UUID>()
        var newLayers: [DrawingLayer] = []
        for (index, op) in ops.enumerated() {
            switch op {
            case .upsert(let parsed):
                guard parsed.invalidSkipped == 0,
                      parsed.waypoints.count + parsed.drawings.count == 1 else {
                    outcome.refused[index] = .invalidPayload
                    continue
                }
                if let waypoint = parsed.waypoints.first {
                    guard !drawingIDs.contains(waypoint.id) else {
                        outcome.refused[index] = .identityCollision(waypoint.id)
                        continue
                    }
                    waypointUpserts.append(waypoint)
                    waypointIDs.insert(waypoint.id)
                    waypointDeletes.remove(waypoint.id)
                } else if let shape = parsed.drawings.first {
                    guard !waypointIDs.contains(shape.id) else {
                        outcome.refused[index] = .identityCollision(shape.id)
                        continue
                    }
                    shapeUpserts.append(shape)
                    drawingIDs.insert(shape.id)
                    shapeDeletes.remove(shape.id)
                }
                newLayers.append(contentsOf: parsed.newLayers)
            case .delete(let localID):
                guard let uuid = UUID(uuidString: localID) else {
                    outcome.refused[index] = .invalidPayload
                    continue
                }
                let isWaypoint = waypointIDs.contains(uuid)
                let isDrawing = drawingIDs.contains(uuid)
                guard !(isWaypoint && isDrawing) else {
                    outcome.refused[index] = .identityCollision(uuid)
                    continue
                }
                // no need to pull an earlier upsert of the same id back out of
                // the list (that scan was O(upserts) per delete): the store's
                // batch commit lets a delete win over any upsert of that id
                if isWaypoint {
                    waypointDeletes.insert(uuid)
                    waypointIDs.remove(uuid)
                }
                if isDrawing {
                    shapeDeletes.insert(uuid)
                    drawingIDs.remove(uuid)
                }
            }
        }
        do {
            try drawingStore.commitRemoteBatch(newLayers: newLayers, upserts: shapeUpserts, deletes: shapeDeletes)
            try waypointStore.commitRemoteBatch(upserts: waypointUpserts, deletes: waypointDeletes)
        } catch {
            throw SyncRemoteModelMutationError.persistence(error)
        }
        return outcome
    }

    static func delete(localID: String,
                       waypointStore: WaypointStore,
                       drawingStore: DrawingStore) throws {
        isApplying = true
        defer { isApplying = false }
        let suspended = suspendUndoRegistration(waypointStore, drawingStore)
        defer { suspended.forEach { $0.enableUndoRegistration() } }
        guard let uuid = UUID(uuidString: localID) else {
            throw SyncRemoteModelMutationError.invalidPayload
        }
        let waypoint = waypointStore.waypoints.first { $0.id == uuid }
        let drawing = drawingStore.shapes.first { $0.id == uuid }
        guard waypoint == nil || drawing == nil else {
            throw SyncRemoteModelMutationError.identityCollision(uuid)
        }
        do {
            if let waypoint, try waypointStore.deleteDurably(waypoint) {
                waypointStore.notePeerWrite(waypoint.id)
            }
            if let drawing, try drawingStore.deleteDurably(drawing) {
                drawingStore.notePeerWrite(drawing.id)
            }
        } catch {
            throw SyncRemoteModelMutationError.persistence(error)
        }
    }

    /// Peer writes arent ours to undo. Registering them (what we used to do) meant Undo reverted
    /// a teammate's work, e.g. deleted the unit they just placed room wide, while your own last
    /// action stayed put. Both stores normally share one UndoManager, dedupe so the disable /
    /// enable calls stay balanced. Android gets the same result by folding peer writes into its
    /// undo snapshots.
    private static func suspendUndoRegistration(_ waypointStore: WaypointStore,
                                                _ drawingStore: DrawingStore) -> [UndoManager] {
        var seen = Set<ObjectIdentifier>()
        let managers = [waypointStore.undoManager, drawingStore.undoManager]
            .compactMap { $0 }
            .filter { seen.insert(ObjectIdentifier($0)).inserted }
        managers.forEach { $0.disableUndoRegistration() }
        return managers
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

    /// 21.1: the bridge frame at background entry may use the newest fix up
    /// to two minutes old (the threat model's two-minute rule).
    static let bridgeLocationAge: TimeInterval = 120

    static func locationIsFresh(timestamp: Date, now: Date = Date(),
                                maximumAge: TimeInterval = maximumLocationAge) -> Bool {
        let age = now.timeIntervalSince(timestamp)
        return age >= -maximumLocationFutureSkew && age <= maximumAge
    }
}

enum SyncConnectionWatchdogPolicy {
    static let heartbeatInterval = TimeInterval(SyncHeartbeatPolicy.foregroundPingIntervalMs) / 1000
    // 8 s used to kill sockets stuck behind one big inbound frame on slow links
    static let heartbeatTimeout = TimeInterval(SyncHeartbeatPolicy.deadAfterMsWithoutPongOrFrame) / 1000

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
    @Published private var pendingLastError: LocalizedMessage?
    var lastErrorMessage: LocalizedMessage? { pendingLastError }
    var lastError: String? { pendingLastError?.text }
    /// Map presence lives in its own observable (contract 20.2) so a loc frame
    /// never republishes the manager, ContentView or the drawings overlay.
    let presence = SyncPresenceModel()
    var peers: [String: PresencePeer] {
        get { inboundPublication?.peers ?? presence.peers }
        set {
            if inboundPublication != nil { inboundPublication?.peers = newValue }
            else { presence.peers = newValue }
        }
    }
    private struct InboundPublication {
        var peers: [String: PresencePeer]
        var members: [String: OnlineMember]
        var recipients: [String: TacMapChatRecipient]
    }
    private var inboundPublication: InboundPublication?
    private var inboundPersistenceInFlight = false
    private var modelEpoch: UInt64 = 0
    private var pendingJournalWrites = 0
    private var outboundPersistenceInFlight = false
    private var pendingReplayCleanups = 0
    private var afterReplayCleanup: [@MainActor () -> Void] = []

    /// Signature-verified v3 sessions currently reported by the relay. This is
    /// independent of `peers`, which contains only shared map locations.
    @Published private(set) var onlineMembers: [String: OnlineMember] = [:]
    /// Only authenticated v3 sessions with a relay-acknowledged chat key are
    /// selectable. Values snapshot actor + session + key ID as one target.
    @Published private(set) var chatRecipients: [String: TacMapChatRecipient] = [:]
    @Published private(set) var chatSessionReady = false
    @Published private var pendingChatSessionIssue: LocalizedMessage?
    var chatSessionIssue: String? { pendingChatSessionIssue?.text }
    let chatStore: TacMapChatStore
    /// Set while sync is parked on PAUSED_ACTION_REQUIRED (relay refused us,
    /// room full, identity rejected...). The sheet shows Retry for it.
    @Published private(set) var pausedForAction: SyncPausedState?
    @Published private(set) var mutationsPaused = false
    /// Every contract issue code surfaced this join, in order. Tests read it,
    /// the UI uses lastError.
    private(set) var surfacedIssueLog: [SyncIssueCode] = []
    var lastIssueKind: SyncIssueKind? { issueLifecycle.issue?.kind }
    /// When the scheduled reconnect fires (scheduler clock), nil if none is armed.
    var reconnectDueMs: Int64? { reconnectTimer == nil ? nil : reconnectBackoff.pending?.dueMs }
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

    private var task: SyncSocket?
    private let socketFactory: SyncSocketFactory
    private let scheduler: SyncScheduler
    private let pathMonitor: SyncPathMonitoring
    private let randomUnit: () -> Double
    private let storageRoot: URL?
    private let relayURLProvider: @MainActor () -> String
    /// Runs off main (PBKDF2, 20.3), so it must not touch the main actor.
    private let roomKeyDeriver: (String) -> SyncCrypto.V3RoomKeys
    private let offMainExecutor: SyncOffMainExecutor
    private let persistenceExecutor: SyncOffMainExecutor
    /// Test seams for counting durable writes. nil = SafeStore.
    private let replayPersistenceWriter: SyncReplayState.PersistenceWriter?
    private let journalPersistenceWriter: LocalModelRevisionJournal.PersistenceWriter?
    private let inboundFrameCloseGate = SyncInboundFrameCloseGate()
    private let receiveBudget = SyncReceiveBudget()
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
    private let outboundDeliveries = OutboundDeliveryTracker(maxAttempts: SyncAckTimer.maxAttempts)
    private var reconnectTimer: SyncCancellable?
    private var reconnectBackoff = SyncBackoffPolicy()
    private var stableSessionTimer: SyncCancellable?
    private var closeClassifier = SyncCloseClassifier()
    private var handshakeWatchdog = SyncHandshakeWatchdog()
    private var handshakeTimer: SyncCancellable?
    private var heartbeatTimer: SyncCancellable?
    private var pingDeadlineTimer: SyncCancellable?
    private var lastInboundMs: Int64 = 0
    private var socketOpened = false
    private var lastPathStatus: SyncPathStatus?
    private var diffTimer: SyncCancellable?
    private let v2SnapshotGate = V2SnapshotGate(
        maxItems: 10_000,
        maxAggregateBytes: 54_525_952
    )
    private var v2SnapshotFailureGeneration: Int64?
    private let issueLifecycle = SyncIssueLifecycle()
    private var activeConnectionGeneration: Int64 = 0
    private var lastGoodLocalPresenceFix: PresenceLocationFix?
    private var localPresenceCandidateCluster: PresenceCandidateCluster?
    private var remotePresenceCandidateClusters: [String: PresenceCandidateCluster] = [:]

    // Outbound pacing and per-copy ack timers (contract section 11).
    private struct PacedFrame {
        let text: String
        let frameClass: SyncOutboundPacer.FrameClass
        let requestId: String?
        let completion: (Bool) -> Void
    }
    private var pacer: SyncOutboundPacer?
    private var pacedFrames: [Int: PacedFrame] = [:]
    private var queuedItemByRequest: [String: Int] = [:]
    private var pacerWakeTimer: SyncCancellable?
    private var ackStates: [String: SyncAckTimer] = [:]
    private var ackTimers: [String: SyncCancellable] = [:]
    /// rids of our own put/del copies that hit the wire and wait for an ack,
    /// in write order. Feeds the "k ahead" part of the ack timeout.
    private var writtenUnacked: [String] = []

    /// State that lives for one room membership, across reconnects.
    private struct JoinState {
        var surfacedKeys = Set<String>()
        var skippedWireIds: [String: SnapshotSkipCategory] = [:]
        /// localId -> journal generation when we stopped publishing it
        var suppressedUntilEdit: [String: Int64] = [:]
        var mutationsPaused = false
        var liveWindowResync = LiveWindowResyncPolicy()
        var after4014 = false
        var rejectedEpoch: UInt64?
        var pacerAfter4008 = false
    }
    private var joinState = JoinState()
    private typealias ValidatedRecordV3 = SyncValidatedRecordV3
    private var liveWindowResyncTimer: SyncCancellable?
    private var snapshotSeqRegressed = false
    private var snapshotVerifiedClean = true

    // SP3 efficiency state (contract sections 1, 17-21)

    /// id -> value maps + per-object export cache, fed by the store publishers
    private let modelIndex = SyncModelIndex()
    /// localId <-> wireId for the joined v3 room, nil when not joined
    private var wireIndex: SyncWireIdIndex?
    private var presenceSendPolicy = PresenceSendPolicy()
    /// bumped by join/leave so a PBKDF2 result for an old join is dropped
    private var joinToken = 0

    /// Inbound frames wait here between the receive loop and the drain
    /// (contract 1). The receive loop re-arms only while there is room, so a
    /// slow drain backs the socket up instead of growing without bound.
    private struct QueuedInbound {
        let object: [String: Any]
        let bytes: Int
        let socket: SyncSocket
        let generation: Int64
    }
    static let inboundBatchMaxFrames = 64
    static let inboundQueueMaxFrames = 1_024
    static let inboundQueueMaxBytes = 16_777_216
    private var inboundQueue: [QueuedInbound] = []
    private var inboundHead = 0
    private var inboundQueuedBytes = 0
    private var inboundDrainTimer: SyncCancellable?
    private var parkedReceive: (socket: SyncSocket, generation: Int64)?
    /// snapshot records are being validated off main, the drain waits
    private var snapshotValidationInFlight = false

    /// Live put/del of the current drain, committed in memory and applied to
    /// the model together (one commit write, one store write, one clear).
    private struct StagedLiveRecord {
        let value: ValidatedRecordV3
        let priorHash: String?
    }
    private var liveGroup: [StagedLiveRecord] = []
    private var liveGroupWireIds = Set<String>()
    /// layers adopted by earlier records of the group, same rule as a snapshot
    private var liveGroupStaging: SnapshotLayerStaging?

    /// 17.1: accepted presence counters that are not on disk yet get flushed
    /// at most once per 60 s
    private var presenceFlushTimer: SyncCancellable?
    private var lastReplayDurableMs: Int64 = 0

    /// When background sharing stopped without the user asking (21.5), shown
    /// on the next foreground return.
    private var backgroundPausedAt: Date?

    /// 21.4 switch. Shipped value is BackgroundPresencePolicy.reconnectEnabled
    /// (off until doc change D1), tests turn it on.
    private let backgroundReconnectEnabled: Bool
    /// epoch the last foreground hello used, and how many spares above it the
    /// persisted floor already covers
    private var lastForegroundHello: (epoch: UInt64, spares: UInt64)?
    private var backgroundPresencePolicy: BackgroundPresencePolicy?
    /// A presence-only background session is up or being re-established.
    /// ContentView keeps background location running while this holds.
    @Published private(set) var backgroundPresenceSustained = false
    /// The presence-only session of 21.4, nil for every normal session.
    private struct BackgroundSession {
        enum Phase { case awaitingBegin, receiving, finalPage, awaitingHelloAck, live }
        let epoch: UInt64
        var phase: Phase = .awaitingBegin
        var seq: Int64?
        var snapshotBytes = 0
    }
    private var backgroundSession: BackgroundSession?

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
    /// v2 only: the `by` that goes with versions[id], for the equal-v tie break
    private var versionsBy: [String: String] = [:]
    private var lastContent: [String: String] = [:]
    private var kindById: [String: String] = [:]
    private var observers = Set<AnyCancellable>()
    private var revisionObservers = Set<AnyCancellable>()
    private var modelRevisionJournal: LocalModelRevisionJournal?
    private var revisionJournalAvailable = false
    private var revisionJournalLoading = false
    private var startupRevisionWriteInFlight = false
    private var startupRevisionEvents: [[String]] = []
    private var afterRevisionJournalReady: [@MainActor () -> Void] = []

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
    private var chatKeyRetryItem: SyncCancellable?
    private var chatKeyAdvertAttempts = 0

    /// Everything after presenceSealedPolicy is the S6-02 test seam. Production
    /// passes nothing and gets URLSession, the main queue, NWPathMonitor and
    /// Application Support.
    init(
        sessionDomainGenerator: @escaping () throws -> Data = {
            try SyncIdentity.generateSessionDomain()
        },
        defaults: UserDefaults = .standard,
        presenceStore: DurableDefaultsDataStore? = nil,
        presenceSealedPolicy: PresenceConfigSealedPolicy = .production,
        socketFactory: SyncSocketFactory? = nil,
        scheduler: SyncScheduler? = nil,
        pathMonitor: SyncPathMonitoring? = nil,
        randomUnit: @escaping () -> Double = { Double.random(in: 0...1) },
        storageRoot: URL? = nil,
        relayURLProvider: (@MainActor () -> String)? = nil,
        roomKeyDeriver: ((String) -> SyncCrypto.V3RoomKeys)? = nil,
        replayPersistenceWriter: SyncReplayState.PersistenceWriter? = nil,
        journalPersistenceWriter: LocalModelRevisionJournal.PersistenceWriter? = nil,
        offMainExecutor: SyncOffMainExecutor? = nil,
        persistenceExecutor: SyncOffMainExecutor? = nil,
        backgroundReconnectEnabled: Bool = BackgroundPresencePolicy.reconnectEnabled
    ) {
        self.backgroundReconnectEnabled = backgroundReconnectEnabled
        self.replayPersistenceWriter = replayPersistenceWriter
        self.journalPersistenceWriter = journalPersistenceWriter
        self.offMainExecutor = offMainExecutor ?? DispatchSyncOffMainExecutor()
        self.persistenceExecutor = persistenceExecutor ?? SyncPersistenceExecutor()
        self.socketFactory = socketFactory ?? URLSessionSyncSocketFactory()
        self.scheduler = scheduler ?? DispatchSyncScheduler()
        self.pathMonitor = pathMonitor ?? NWPathSyncMonitor()
        self.randomUnit = randomUnit
        self.storageRoot = storageRoot
        self.relayURLProvider = relayURLProvider ?? { OpsecSettings.shared.relayURL }
        self.roomKeyDeriver = roomKeyDeriver ?? { SyncCrypto.deriveRoomV3($0) }
        self.chatStore = TacMapChatStore(containerURL: storageRoot)
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

    /// Where replay state, the revision journal and chat live. Tests point
    /// this at a temp dir so they never touch the app's real files.
    private var storageContainerURL: URL? {
        storageRoot ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
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
        let journal = journalPersistenceWriter.map {
            LocalModelRevisionJournal(containerURL: storageContainerURL, persistenceWriter: $0)
        } ?? LocalModelRevisionJournal(containerURL: storageContainerURL)
        revisionJournalLoading = true
        observeModelRevisions()
        let loaded = ReplayLoadResult()
        // SafeStore reads can migrate plaintext and persist a sealed-only
        // barrier. Keep this private instance on the persistence worker until
        // the load settles, while lifetime observers retain local edit events.
        persistenceExecutor.execute({ loaded.success = journal.load() }) { [weak self] in
            guard let self else { return }
            self.modelRevisionJournal = journal
            if loaded.success { self.drainStartupRevisionEvents() }
            else { self.finishRevisionJournalStartup(success: false) }
        }
    }

    private func drainStartupRevisionEvents() {
        guard presenceCadence.foregroundReady, !startupRevisionWriteInFlight else { return }
        guard !startupRevisionEvents.isEmpty, let journal = modelRevisionJournal else {
            finishRevisionJournalStartup(success: true)
            return
        }
        let ids = startupRevisionEvents.removeFirst()
        startupRevisionWriteInFlight = true
        journal.bumpAll(ids, on: persistenceExecutor) { [weak self] error in
            guard let self else { return }
            self.startupRevisionWriteInFlight = false
            if error == nil { self.drainStartupRevisionEvents() }
            else { self.finishRevisionJournalStartup(success: false) }
        }
    }

    private func finishRevisionJournalStartup(success: Bool) {
        revisionJournalLoading = false
        revisionJournalAvailable = success
        startupRevisionEvents.removeAll()
        if !success {
            pendingLastError = Messages.syncLocalRevisionHistoryCouldNotBeSavedSyncIsede036c2Message()
            if wantConnected { failClosedV3(pendingLastError!) }
        }
        let ready = afterRevisionJournalReady
        afterRevisionJournalReady.removeAll()
        ready.forEach { $0() }
        if success { markDiffDirty() }
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
            if wasForegroundReady { enterBackground() }
        }
        reschedulePresenceBroadcastTimer()

        if foregroundReady {
            if revisionJournalLoading, modelRevisionJournal != nil { drainStartupRevisionEvents() }
            if !wasForegroundReady {
                // a presence-only background session never carries on into
                // the foreground, the normal reconnect below replaces it
                endBackgroundPresence()
                surfaceBackgroundPauseIfAny()
            }
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
            } else if !wasForegroundReady, pausedForAction?.retryOnForeground == true {
                // room full / session conflict get one fresh try per return
                retryPausedConnection()
            } else {
                sendPresence()
            }
            return
        }

        // A background presence session may only continue an already verified
        // socket. Snapshot/authentication and reconnect persistence stay in the
        // unlocked foreground path.
        guard backgroundPresenceEnabled, status == .connected || backgroundPresenceSustained else {
            pauseConnectionForBackground()
            return
        }
        if wasForegroundReady {
            // 21.1: background heartbeat values, then one bridge frame so peers
            // see the long retention (and stop routing chat) right away
            startConnectionHealthChecks()
            armBackgroundPresencePolicy()
            sendPresence(bridge: true)
        } else {
            sendPresence()
        }
    }

    /// Foreground -> background (contract 21.1 / 21.2). Delivery retries stop
    /// here: acks are dropped with every other inbound frame in background,
    /// so retrying would only end in a cancelled socket and a false
    /// "unconfirmed" error. Pending ops wait for the foreground snapshot, which
    /// tells us which ones landed (S2-05).
    private func enterBackground() {
        clearOutboundDeliveries(markForReconciliation: true)
        // queued frames are foreground-only work, the next snapshot covers them
        resetInboundQueue(rearmParked: true)
        dropInboundAndLiveWork()
        presenceFlushTimer?.cancel()
        presenceFlushTimer = nil
        // 17.1 clean point. Best effort: an auth-bound key may be locked already,
        // then the stride flag just stays false which is the safe side.
        replayState?.writeCleanPresenceFence(on: persistenceExecutor)
    }

    /// 21.5: background sharing stopped without the user asking. Say when, once.
    private func surfaceBackgroundPauseIfAny() {
        guard let pausedAt = backgroundPausedAt else { return }
        backgroundPausedAt = nil
        surface(.backgroundPaused, scope: .backgroundPeriod(pausedAt), at: pausedAt)
    }

    /// Location callbacks are the most reliable wake source in both lifecycle
    /// states. `sendPresence()` coalesces frequent fixes to the active cadence,
    /// while allowing a freshly requested stationary fix to publish promptly.
    func locationDidUpdate() {
        guard presenceCadence.canBroadcast else { return }
        if !presenceCadence.foregroundReady, task == nil, backgroundPresencePolicy?.dropped == true {
            // 21.4: the next presence opportunity after a drop
            backgroundPresenceWake()
            return
        }
        sendPresence()
    }

    // MARK: Background presence reconnect (contract 21.4, gated on doc change D1)

    /// At background entry on a healthy opted-in session: remember which
    /// spare epochs the last foreground hello left us.
    private func armBackgroundPresencePolicy() {
        guard backgroundReconnectEnabled, let hello = lastForegroundHello else { return }
        backgroundPresencePolicy = BackgroundPresencePolicy(
            lastForegroundEpoch: hello.epoch, spares: hello.spares, reconnectEnabled: true)
        backgroundPresenceSustained = true
    }

    /// Background location wake or the network coming back after a drop.
    private func backgroundPresenceWake() {
        guard var policy = backgroundPresencePolicy, task == nil else { return }
        let eligible = wantConnected && presenceConfig.shareLocation && presenceCadence.backgroundEnabled
        let decision = policy.fixDue(atMs: scheduler.nowMs, eligible: eligible)
        backgroundPresencePolicy = policy
        switch decision {
        case .connect(let epoch):
            openBackgroundPresenceSession(epoch: epoch)
        case .wait:
            break
        case .pause:
            pauseBackgroundPresence()
        }
    }

    /// A background presence socket ended without us asking.
    private func backgroundPresenceDropped(failedAttempt: Bool) {
        guard var policy = backgroundPresencePolicy else {
            // reconnect off (or never armed): sharing just stops, say so later
            backgroundPausedAt = backgroundPausedAt ?? Date()
            return
        }
        if failedAttempt { policy.attemptFailed() } else { policy.socketClosed(atMs: scheduler.nowMs) }
        backgroundPresencePolicy = policy
    }

    private func pauseBackgroundPresence() {
        backgroundPausedAt = backgroundPausedAt ?? Date()
        endBackgroundPresence()
    }

    private func endBackgroundPresence() {
        backgroundPresencePolicy = nil
        backgroundSession = nil
        if backgroundPresenceSustained { backgroundPresenceSustained = false }
    }

    /// Fresh in-memory session domain and a spare epoch the foreground already
    /// made durable. Nothing in this session writes to disk, opens a record,
    /// or sends anything but hello and loc (21.4).
    private func openBackgroundPresenceSession(epoch: UInt64) {
        guard protocolVersion == 3, let roomId,
              let base = Self.validatedRelayBaseForRuntime(relayEndpoint),
              let generated = try? sessionDomainGenerator(), generated.count == 32 else {
            pauseBackgroundPresence()
            return
        }
        sessionDomain = generated
        activeConnectionGeneration = issueLifecycle.beginConnection()
        presenceCounter = 0
        localHelloVersion = nil
        awaitingHelloAck = false
        activeSessions.removeAll()
        backgroundSession = BackgroundSession(epoch: epoch)
        guard let socket = openRelaySocket(base: base, roomId: roomId) else {
            pauseBackgroundPresence()
            return
        }
        receive(on: socket, generation: activeConnectionGeneration)
    }

    /// 21.4 frames. The snapshot is drained, not processed: only fence order
    /// and the 4 MiB ceiling are checked, no record is opened, verified,
    /// applied or stored. Then hello, hello-ack, presence only.
    private func handleBackgroundSessionFrame(_ data: Data, socket: SyncSocket) {
        guard var session = backgroundSession else { return }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let type = object?["t"] as? String
        func fail() {
            backgroundSession = session
            endSession(socket: socket, cause: .local(.structuralSnapshot), issueReported: true)
        }
        switch session.phase {
        case .awaitingBegin, .receiving, .finalPage:
            session.snapshotBytes += data.count
            if var policy = backgroundPresencePolicy, policy.snapshotBytes(session.snapshotBytes) != nil {
                backgroundPresencePolicy = policy
                pauseBackgroundPresence()
                endSession(socket: socket, cause: .local(.lifecyclePause), issueReported: true)
                return
            }
            switch (session.phase, type) {
            case (.awaitingBegin, "snapshot-begin"):
                guard let seq = strictNonNegativeInt64(object?["seq"]) else { return fail() }
                session.seq = seq
                session.phase = .receiving
            case (.receiving, "snapshot"):
                guard let more = strictJSONBoolean(object?["more"]) else { return fail() }
                if !more { session.phase = .finalPage }
            case (.finalPage, "snapshot-end"):
                guard let seq = strictNonNegativeInt64(object?["seq"]), seq == session.seq else { return fail() }
                session.phase = .awaitingHelloAck
                backgroundSession = session
                handshakeWatchdog.snapshotEnded(atMs: scheduler.nowMs)
                armHandshakeTimer()
                writeHello(epochHex: HelloEpochPolicy.hex(session.epoch))
                return
            default:
                return fail()
            }
            backgroundSession = session
        case .awaitingHelloAck:
            guard type == "hello-ack", let object, let actorId = myActorId,
                  let expected = localHelloVersion, let ownSession = sessionDomain,
                  let by = object["by"] as? String, let sd = object["sd"] as? String,
                  let vs = object["vs"] as? String,
                  SyncIdentity.helloAckMatches(actorId: actorId, sessionDomain: ownSession,
                                               expectedVersion: expected, frameActorId: by,
                                               frameSessionDomain: sd, frameVersion: vs) else { return }
            session.phase = .live
            backgroundSession = session
            awaitingHelloAck = false
            cancelHandshakeWatchdog()
            backgroundPresencePolicy?.attemptSucceeded()
            status = .connected
            presenceCadence.startAuthenticatedSession()
            startConnectionHealthChecks()
            sendPresence(bridge: true)
        case .live:
            break
        }
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
                Messages.syncCouldNotSaveUnitSyncIdentityLocationSharingTheMessage()
            )
            return false
        }
        let stoppedSharing = presenceConfig.shareLocation && !value.shareLocation
        let identityChanged = presenceConfig.callsign != value.callsign
            || presenceConfig.affiliation != value.affiliation
            || presenceConfig.echelon != value.echelon
            || presenceConfig.function != value.function
            || presenceConfig.isHQ != value.isHQ
        presenceConfig = value
        presenceConfigDurable = true
        if stoppedSharing {
            withdrawSharedLocation()
        } else if identityChanged {
            // 20.1: a new callsign / symbol goes out on the next fix, not
            // after the stationary heartbeat
            presenceSendPolicy.configDidChange()
            sendPresence()
        }
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
                        Messages.syncSavedUnitSyncIdentityLocationSharingIsLockedOrMessage()
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
                    Messages.syncSavedUnitSyncIdentityLocationSharingFailedItsSealedMessage()
                )
                return
            }
            // Legacy plaintext is published only after a checked, marker-first
            // migration to authenticated ciphertext succeeds.
            guard persistPresenceConfig(config) else {
                reportPresencePersistenceIssue(
                    Messages.syncCouldNotMigrateUnitSyncIdentityLocationSharingToMessage()
                )
                return
            }
            presenceConfig = config
            presenceConfigDurable = true
        } catch {
            reportPresencePersistenceIssue(
                Messages.syncSavedUnitSyncIdentityLocationSharingIsLockedOrMessage()
            )
        }
    }

    private func reportPresencePersistenceIssue(_ message: LocalizedMessage) {
        pendingLastError = issueLifecycle.report(
            message,
            kind: .security,
            generation: activeConnectionGeneration
        )?.pendingMessage
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
            pendingLastError = Messages.syncJoinCodeMustStartWithLegacyRoomsRequireAnMessage()
            return
        }
        guard let configuredRelay = Self.validatedRelayBaseForRuntime(
            relayURLProvider()
        ) else {
            pendingLastError = Messages.syncConfiguredRelayUnavailableMessage()
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
            pendingLastError = Messages.syncSigningIdentityIsLockedOrUnavailableUnlockTheDeviceMessage()
            return
        }
        wantConnected = true

        // Hold the already validated origin for reconnects; connect() validates
        // it again before every URLSession task is created.
        relayEndpoint = configuredRelay

        // 20.3: the 210k-iteration PBKDF2 runs off main. CONNECTING shows right
        // away; the result only lands if this join is still the current one
        // (leave or another join bumps the token).
        joinToken &+= 1
        let token = joinToken
        let isV3 = code.hasPrefix("3:")
        protocolVersion = isV3 ? 3 : 2
        room = code
        status = .connecting
        let rawCode = String(code.dropFirst(2))
        let deriver = roomKeyDeriver
        let derived = DerivedJoinKeys()
        offMainExecutor.execute({
            if isV3 { derived.v3 = deriver(rawCode) } else { derived.v2 = SyncCrypto.deriveRoom(rawCode) }
        }) { [weak self] in
            guard let self, self.joinToken == token, self.wantConnected, self.room == code else { return }
            // A new join must load after the prior socket's queued seals and
            // clean point, so an immediate leave/rejoin cannot reuse a floor.
            let finish: @MainActor () -> Void = { [weak self] in
                guard let self, self.joinToken == token, self.wantConnected, self.room == code else { return }
                self.persistenceExecutor.execute({}) { [weak self] in
                    guard let self, self.joinToken == token, self.wantConnected, self.room == code else { return }
                    self.finishJoin(code: code, v3: derived.v3, v2: derived.v2, proposedRoomName: proposedRoomName)
                }
            }
            if self.pendingReplayCleanups > 0 { self.afterReplayCleanup.append(finish) }
            else { finish() }
        }
    }

    /// Box for the off-main key derivation result.
    private final class DerivedJoinKeys {
        var v3: SyncCrypto.V3RoomKeys?
        var v2: SyncCrypto.RoomKeys?
    }

    private func finishJoin(code: String,
                            v3: SyncCrypto.V3RoomKeys?,
                            v2: SyncCrypto.RoomKeys?,
                            proposedRoomName: String?) {
        if v3 != nil {
            if revisionJournalLoading {
                let token = joinToken
                afterRevisionJournalReady.append { [weak self] in
                    guard let self, self.joinToken == token, self.wantConnected, self.room == code,
                          self.presenceCadence.foregroundReady else { return }
                    self.finishJoin(code: code, v3: v3, v2: v2, proposedRoomName: proposedRoomName)
                }
                return
            }
            guard revisionJournalAvailable else {
                pendingLastError = Messages.syncLocalRevisionHistoryCouldNotBeSavedSyncIsede036c2Message()
                abandonJoin()
                return
            }
        }
        if let keys = v3 {
            v3Keys = keys
            roomKey = keys.roomKey
            roomId = keys.roomId
            authToken = keys.authToken
            guard let pubRaw = myPublicKeyRaw else {
                pendingLastError = Messages.syncSigningIdentityIsUnavailableMessage()
                abandonJoin()
                return
            }
            let localActorId = SyncIdentity.actorId(roomIdRaw: keys.roomIdRaw, pubkeyRaw: pubRaw)
            myActorId = localActorId
            let rs = replayPersistenceWriter.map {
                SyncReplayState(roomId: keys.roomId, containerURL: storageContainerURL, persistenceWriter: $0)
            } ?? SyncReplayState(roomId: keys.roomId, containerURL: storageContainerURL)
            let token = joinToken
            let publicKey = myPublicKey
            let loaded = ReplayLoadResult()
            // Loading may repair an old actor pin and seal the replay document.
            // Keep that repair on the same serial worker as later commits.
            persistenceExecutor.execute({
                loaded.success = rs.load(localActorId: localActorId, publicKey: publicKey)
            }) { [weak self] in
                guard let self, self.joinToken == token, self.wantConnected, self.room == code else { return }
                guard loaded.success else {
                    self.pendingLastError = Messages.syncSavedRollbackProtectionStateIsLockedOrDamagedSyncMessage()
                    self.abandonJoin()
                    return
                }
                self.finishV3Join(keys: keys, replay: rs)
                self.finishJoinedRoom(proposedRoomName: proposedRoomName)
            }
            return
        } else if let keys = v2 {
            roomKey = keys.roomKey
            roomId = keys.roomId
            authToken = keys.authToken
            chatStore.close()
            pendingChatSessionIssue = Messages.chatTacmapChatRequiresASecureVRoomMessage()
        } else {
            abandonJoin()
            return
        }

        finishJoinedRoom(proposedRoomName: proposedRoomName)
    }

    private final class ReplayLoadResult {
        var success = false
    }

    private func finishV3Join(keys: SyncCrypto.V3RoomKeys, replay rs: SyncReplayState) {
        rs.onDurableWrite = { [weak self] in
            guard let self else { return }
            self.lastReplayDurableMs = self.scheduler.nowMs
        }
        lastReplayDurableMs = scheduler.nowMs
        replayState = rs
        // contract 18: the wire-id index is built once, now that the keys
        // and the stores are both attached
        rebuildWireIndex(metadataKey: keys.metadataKey)
        // chat fences of sessions the replay state has moved past can go
        chatStore.durableSessionDomain = { [weak rs] actor in rs?.durableSessionDomain(actor) }
        do {
            try chatStore.open(roomId: keys.roomId)
            pendingChatSessionIssue = nil
        } catch {
            // Unit Sync can still operate, but chat remains fail-closed: no
            // frame is sent unless its sealed history/replay document is
            // available for a durable-before-send commit.
            pendingChatSessionIssue = (error as? TacMapChatStore.StoreError)?.localizedMessage
            ?? (error as? TacMapChatCrypto.CryptoError)?.localizedMessage
            ?? .literal(error.localizedDescription)
        }
    }

    private func finishJoinedRoom(proposedRoomName: String?) {
        if let activeRoomId = roomId {
            if let proposedRoomName,
               !PresenceConfig.normalizedRoomName(proposedRoomName).isEmpty {
                var updated = presenceConfig
                updated.setRoomName(proposedRoomName, for: activeRoomId)
                _ = updatePresenceConfig(updated)
            }
            roomName = presenceConfig.roomName(for: activeRoomId)
        }
        pathMonitor.onChange = { [weak self] status in self?.pathChanged(status) }
        pathMonitor.start()
        connect()
        observeStores()
        startPresenceTimers()
    }

    /// A join that could not finish (locked replay state, missing identity).
    private func abandonJoin() {
        wantConnected = false
        roomKey = nil; authToken = nil; roomId = nil; v3Keys = nil; myActorId = nil
        wireIndex = nil
        room = nil
        status = .offline
        protocolVersion = 2
    }

    private func rebuildWireIndex(metadataKey: Data) {
        let index = SyncWireIdIndex(metadataKey: metadataKey)
        index.insert(modelIndex.allIds)
        index.insert(lastContent.keys.compactMap(UUID.init(uuidString:)))
        wireIndex = index
    }

    func leave() {
        leave(clearLastError: true)
    }

    private func leave(clearLastError: Bool) {
        wantConnected = false
        reconnectBackoff.reset()
        closeClassifier.reset()
        joinState = JoinState()
        mutationsPaused = false
        pausedForAction = nil
        surfacedIssueLog.removeAll()
        stableSessionTimer?.cancel()
        stableSessionTimer = nil
        liveWindowResyncTimer?.cancel()
        liveWindowResyncTimer = nil
        pathMonitor.stop()
        pathMonitor.onChange = nil
        lastPathStatus = nil
        chatStore.durableSessionDomain = nil
        pendingLastError = issueLifecycle.resetForLeave()?.pendingMessage
        clearChatSessionSecrets()
        chatStore.close()
        pendingChatSessionIssue = nil
        observers.removeAll()
        stopPresenceTimers()
        stopConnectionHealthChecks()
        presenceSendInFlight = false
        reconnectTimer?.cancel()
        reconnectTimer = nil
        cancelHandshakeWatchdog()
        diffTimer?.cancel()
        diffTimer = nil
        v2SnapshotGate.cancel()
        v2SnapshotFailureGeneration = nil
        let leavingTask = task
        let explicitLeaveFrame = signedExplicitLeaveFrame()
        let leavingPacer = pacer
        task = nil
        resetOutboundPacing()
        if let leavingTask, let explicitLeaveFrame, let leavingPacer {
            sendPacedLeave(explicitLeaveFrame, socket: leavingTask, pacer: leavingPacer)
        } else {
            leavingTask?.cancel(closeCode: SyncLocalCloseAction.leave.closeCode, reason: nil)
        }

        // v3: replay state survives leave/restart. Leave is a 17.1 clean
        // point, so the counters go down exact and the next load needs no floor.
        dropInboundAndLiveWork()
        presenceFlushTimer?.cancel()
        presenceFlushTimer = nil
        if let state = replayState {
            pendingReplayCleanups += 1
            state.writeCleanPresenceFence(on: persistenceExecutor) { [weak self] _ in
                guard let self else { return }
                self.pendingReplayCleanups -= 1
                if self.pendingReplayCleanups == 0 {
                    let waiting = self.afterReplayCleanup
                    self.afterReplayCleanup.removeAll()
                    waiting.forEach { $0() }
                }
            }
        }
        replayState?.onDurableWrite = nil
        joinToken &+= 1
        wireIndex = nil
        backgroundPausedAt = nil
        endBackgroundPresence()
        lastForegroundHello = nil

        roomKey = nil
        authToken = nil
        room = nil
        roomName = nil
        status = .offline
        clock = 0
        versions.removeAll(); versionsBy.removeAll(); lastContent.removeAll(); kindById.removeAll()
        forcedLocalDiff.removeAll(); resolvingPendingModel = false
        forcedLegacyDeletes.removeAll()
        pendingLegacyDeleteConfirmations.removeAll()
        clearOutboundDeliveries(markForReconciliation: false)
        peers.removeAll()
        activeSessions.removeAll()
        setOnlineMembers(onlineMemberTracker.clear())
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
        if clearLastError { pendingLastError = issueLifecycle.dismiss()?.pendingMessage }
    }

    func acknowledgeLastError() {
        pendingLastError = issueLifecycle.dismiss()?.pendingMessage
    }

    // MARK: - TacMap Chat API

    /// Cold-upgrade cleanup for every historical per-room Chat/replay file, not
    /// just the room the user happens to rejoin. Call only after mission-data
    /// unlock so a locked DEK causes no filesystem mutation.
    func migrateLegacyLocalStoresAfterUnlock() {
        guard let support = storageContainerURL else { return }
        do {
            _ = try SyncLocalStore.migrateAllLegacyStores(
                applicationSupportDirectory: support
            )
            pendingLastError = issueLifecycle.clearPersistentSecurity()?.pendingMessage
        } catch {
            pendingLastError = issueLifecycle.reportPersistentSecurity(
                Messages.syncSavedUnitSyncMetadataCouldNotBeMigratedToMessage(),
                generation: activeConnectionGeneration
            )?.pendingMessage
        }
    }

    enum ChatSendError: LocalizedError, LocalizedMessageError {
        case secureRoomRequired
        case historyUnavailable
        case sessionUnavailable
        case recipientRequired
        case recipientChanged
        case counterExhausted
        case transportUnavailable
        case recipientInBackground

        var errorDescription: String? { localizedMessage.text }

        var localizedMessage: LocalizedMessage {
            switch self {
            case .secureRoomRequired: return Messages.chatTacmapChatRequiresASecureVUnitSyncRoomMessage()
            case .historyUnavailable: return Messages.chatEncryptedChatHistoryIsLockedOrUnavailableMessage()
            case .sessionUnavailable: return Messages.chatEncryptedChatIsStillEstablishingARelaySessionMessage()
            case .recipientRequired: return Messages.chatSelectAUnitBeforeSendingThisMessageMessage()
            case .recipientChanged: return Messages.chatThatUnitSSecureSessionChangedSelectItAgainMessage()
            case .counterExhausted: return Messages.chatThisChatSessionReachedItsMessageLimitReconnectUnitMessage()
            case .transportUnavailable: return Messages.chatTheMessageCouldNotBeRoutedToTheRelayMessage()
            case .recipientInBackground: return Messages.chatRecipientInBackgroundMessage()
            }
        }
    }

    var chatAvailabilityMessage: String? {
        if room == nil { return L10n.text("Join a secure Unit Sync room to use chat.") }
        if protocolVersion != 3 { return L10n.text("TacMap Chat requires a secure v3 room.") }
        if let chatSessionIssue { return chatSessionIssue }
        if chatStore.activeRoomId != roomId { return L10n.text("Encrypted chat history is unavailable.") }
        if status != .connected { return L10n.text("Unit Sync must be connected for live chat.") }
        if !chatSessionReady { return L10n.text("Establishing an encrypted chat session…") }
        return nil
    }

    /// Called from the app's DataKey lock notification. No chat key material or
    /// plaintext history remains reachable while mission data is locked.
    func lockChatForMissionData() {
        clearChatSessionSecrets()
        chatStore.lock()
        pendingChatSessionIssue = Messages.chatUnlockMissionDataToUseTacmapChat01a3669eMessage()
    }

    func restoreChatAfterMissionUnlock() {
        guard protocolVersion == 3, let roomId else { return }
        // A transient .inactive never locked chat, so there is nothing to
        // restore and no reason to rotate a healthy session (S2-12).
        guard chatStore.isLocked || chatStore.activeRoomId != roomId else { return }
        do {
            try chatStore.open(roomId: roomId)
            pendingChatSessionIssue = nil
            if status == .connected, presenceCadence.foregroundReady {
                reconnectForForegroundReconciliation()
            }
        } catch {
            pendingChatSessionIssue = (error as? TacMapChatStore.StoreError)?.localizedMessage
                ?? (error as? TacMapChatCrypto.CryptoError)?.localizedMessage
                ?? .literal(error.localizedDescription)
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
            // 21.6: a backgrounded peer drops every inbound frame, a direct
            // message to it would show Routed and vanish (S6-04)
            // per-send reason, the chat view shows the thrown error
            if case .blocked = ChatSendGate.check(
                scope: .direct,
                recipientLatestRetentionSeconds: latestAdvertisedRetention(recipient)
            ) {
                throw ChatSendError.recipientInBackground
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
        guard send(frame.object, frameClass: .chat, completion: { [weak self] succeeded in
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

    /// Retention advertised by the recipient's latest accepted presence on
    /// this exact session, nil when it has not shared a location.
    private func latestAdvertisedRetention(_ recipient: TacMapChatRecipient) -> Int? {
        guard let peer = peers[recipient.actorId],
              peer.sessionDomain == recipient.sessionDomain,
              peer.retentionWindow.isFinite else { return nil }
        return Int(peer.retentionWindow)
    }

    private func startChatSessionV3() {
        guard protocolVersion == 3, status == .connected,
              chatStore.activeRoomId == roomId,
              !chatStore.isLocked,
              let keys = v3Keys, let actorId = myActorId,
              let sessionDomain, let seed = deviceSeed else {
            pendingChatSessionIssue = chatStore.pendingIssue ?? Messages.chatHistoryUnavailableMessage()
            return
        }
        // Peers may announce their authenticated key before our hello-ack.
        // Starting this socket's local key must retain those current-session
        // endpoints; connection teardown still clears all local and peer keys.
        clearLocalChatSessionSecrets()
        do {
            chatLocalSession = try TacMapChatCrypto.makeLocalSession(
                roomIdRaw: keys.roomIdRaw,
                actorId: actorId,
                sessionDomain: sessionDomain,
                signingSeed: seed
            )
            chatCounter = 0
            pendingChatSessionIssue = nil
            sendChatKeyAdvertisement()
        } catch {
            clearChatSessionSecrets()
            pendingChatSessionIssue = Messages.chatEncryptedChatCouldNotEstablishASessionMessage()
        }
    }

    private func sendChatKeyAdvertisement() {
        guard status == .connected, !chatSessionReady,
              let session = chatLocalSession else { return }
        chatKeyAdvertAttempts += 1
        _ = send(session.advertisement, frameClass: .control) { [weak self] succeeded in
            guard let self, !succeeded else { return }
            self.pendingChatSessionIssue = Messages.chatTheChatKeyAdvertisementCouldNotReachTheRelayMessage()
        }
        chatKeyRetryItem?.cancel()
        guard chatKeyAdvertAttempts < 4 else {
            let issue = Messages.chatTheRelayDidNotAcknowledgeEncryptedChatCapabilityMessage()
            clearChatSessionSecrets()
            pendingChatSessionIssue = issue
            return
        }
        let expectedKeyId = session.keyId
        chatKeyRetryItem = scheduler.schedule(afterMs: 2_000) { [weak self] in
            guard let self, self.chatLocalSession?.keyId == expectedKeyId,
                  !self.chatSessionReady else { return }
            self.chatKeyRetryItem = nil
            self.sendChatKeyAdvertisement()
        }
    }

    private func clearChatSessionSecrets() {
        clearLocalChatSessionSecrets()
        chatPeerKeys.removeAll(keepingCapacity: false)
        inboundPublication?.recipients.removeAll(keepingCapacity: false)
        chatRecipients.removeAll(keepingCapacity: false)
    }

    private func clearLocalChatSessionSecrets() {
        chatKeyRetryItem?.cancel()
        chatKeyRetryItem = nil
        chatKeyAdvertAttempts = 0
        chatSessionReady = false
        chatLocalSession = nil
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
            let displayName = (inboundPublication?.members ?? onlineMembers)[actorId]?.displayName
                ?? peers[actorId]?.callsign
                ?? L10n.text("Unit %1$@", String(actorId.suffix(6)).uppercased())
            next[actorId] = TacMapChatRecipient(
                actorId: actorId,
                displayName: displayName,
                sessionDomain: key.sessionDomain,
                keyId: key.keyId
            )
        }
        // 20.2: an unchanged value must not republish the manager
        if inboundPublication != nil { inboundPublication?.recipients = next }
        else if next != chatRecipients { chatRecipients = next }
    }

    /// Assign only when something a view shows changed. The tracker bumps
    /// lastSeenAt on every frame, which nothing displays, and republishing it
    /// re-rendered the whole map per presence frame (S5-12).
    private func setOnlineMembers(_ next: [String: OnlineMember]) {
        if inboundPublication != nil { inboundPublication?.members = next; return }
        guard next.count != onlineMembers.count || next.contains(where: { id, member in
            guard let current = onlineMembers[id] else { return true }
            return !current.sameDisplay(as: member)
        }) else { return }
        onlineMembers = next
    }

    // MARK: Connection

    private func connect() {
        guard presenceCadence.foregroundReady, pausedForAction == nil, let roomId else { return }
        // Foreground reconciliation can race the journal startup callback:
        // finishJoin has room keys while its replay load is still queued.
        // Only the loaded, current room authority may open a v3 socket.
        if protocolVersion == 3 {
            guard revisionJournalAvailable, !revisionJournalLoading,
                  replayState?.roomId == roomId, v3Keys?.roomId == roomId else { return }
        }
        guard let base = Self.validatedRelayBaseForRuntime(relayEndpoint) else {
            wantConnected = false
            status = .offline
            pendingLastError = Messages.syncConfiguredRelayUnavailableMessage()
            return
        }
        relayEndpoint = base
        presenceSendInFlight = false
        stopConnectionHealthChecks()
        cancelHandshakeWatchdog()
        dropInboundAndLiveWork()
        reconnectTimer?.cancel()
        reconnectTimer = nil
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
                    Messages.syncSecureSessionRandomnessIsUnavailableUnitSyncWasNotMessage()
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
            setOnlineMembers(onlineMemberTracker.clear())
        } else {
            // A legacy relay may ignore delivery request IDs. Rebuild v2's
            // baseline from its reconnect snapshot or resend with a fresh
            // version; never suppress a merely queued edit permanently.
            versions.removeAll()
            versionsBy.removeAll()
            lastContent.removeAll()
            kindById.removeAll()
        }
        snapshotSeqRegressed = false
        snapshotVerifiedClean = true
        reconnectBackoff.attemptStarted(atMs: scheduler.nowMs)
        guard let socket = openRelaySocket(base: base, roomId: roomId) else { return }
        if protocolVersion == 2 {
            v2SnapshotGate.start(socketIdentity: socket, generation: activeConnectionGeneration)
        }
        receive(on: socket, generation: activeConnectionGeneration)
    }

    /// Build the upgrade request, create the socket and arm the per-socket
    /// machinery. Shared by the normal connect and the 21.4 background one.
    private func openRelaySocket(base: String, roomId: String) -> SyncSocket? {
        let path = protocolVersion == 3 ? "/v3/room/\(roomId)" : "/room/\(roomId)"
        guard let url = URL(string: base + path) else { return nil }
        status = .connecting
        var req = URLRequest(url: url)
        if let authToken { req.setValue("Bearer \(authToken)", forHTTPHeaderField: "Authorization") }
        if protocolVersion == 3 {
            req.setValue("3", forHTTPHeaderField: "X-Protocol")
            req.setValue(roomId, forHTTPHeaderField: "X-Room-Id")
        }
        req = SyncWebSocketTransport.prepareRequest(req)
        let socket = socketFactory.makeSocket(request: req)
        task = socket
        socketOpened = false
        startOutboundPacing()
        let connectionGeneration = activeConnectionGeneration
        socket.onOpen = { [weak self, weak socket] in
            guard let self, let socket, self.task === socket,
                  self.activeConnectionGeneration == connectionGeneration else { return }
            self.transportOpened()
        }
        startHandshakeWatchdog(socket: socket, generation: connectionGeneration)
        socket.resume()
        return socket
    }

    private func receive(on socket: SyncSocket, generation: Int64) {
        socket.receive { [weak self, weak socket] result in
            guard let self, let socket, self.task === socket,
                  self.activeConnectionGeneration == generation else { return }
            switch result {
            case .failure:
                self.endSession(socket: socket, cause: .remote)
            case .success(let message):
                self.handleInbound(message, socket: socket, generation: generation)
            }
        }
    }

    private func handleInbound(_ message: SyncSocketMessage, socket: SyncSocket, generation: Int64) {
        let now = scheduler.nowMs
        lastInboundMs = now
        socketOpened = true
        noteHandshakeProgress()
        let decision: SyncInboundFrameDecision
        switch message {
        case .string(let text):
            decision = SyncInboundFramePolicy.inspect(text: text)
        case .data(let data):
            // Bound binary payloads before any UTF-8 allocation.
            decision = SyncInboundFramePolicy.inspect(data: data)
        }
        guard case .accept(_, let data) = decision else {
            if case .reject(let exact) = decision {
                rejectInboundFrame(socket: socket, generation: generation, rejection: exact)
            } else {
                rejectInboundFrame(socket: socket, generation: generation, rejection: .invalidUTF8)
            }
            return
        }
        // Mission objects, replay counters, acknowledgements, and membership
        // state all have durable foreground-only work. Keep reading so the
        // socket stays healthy, but discard frames while locked/backgrounded
        // (unparsed) and reconcile by snapshot before processing resumes.
        guard presenceCadence.foregroundReady else {
            guard receiveBudget.admit(
                generation: generation, phase: .background, frameType: nil,
                byteCount: data.count, activeSessions: 0, nowMs: now
            ) else {
                rejectInboundFrame(socket: socket, generation: generation, rejection: .rateLimited)
                return
            }
            if backgroundSession != nil {
                handleBackgroundSessionFrame(data, socket: socket)
                guard task === socket, activeConnectionGeneration == generation else { return }
            }
            receive(on: socket, generation: generation)
            return
        }
        let parsed = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        guard receiveBudget.admit(
            generation: generation,
            phase: status == .connected ? .live : .initial,
            frameType: parsed?["t"] as? String,
            byteCount: data.count,
            activeSessions: activeSessions.count,
            nowMs: now
        ) else {
            rejectInboundFrame(socket: socket, generation: generation, rejection: .rateLimited)
            return
        }
        guard let parsed else {
            // not JSON: dropped like always, keep reading
            receive(on: socket, generation: generation)
            return
        }
        // Contract 1: the receive callback only enqueues. The drain processes
        // whatever is queued in batches, so a burst of live records costs one
        // replay commit and one store write instead of one each.
        enqueueInbound(QueuedInbound(object: parsed, bytes: data.count, socket: socket, generation: generation))
    }

    // MARK: Inbound queue and batch drain (contract 1, 17)

    private var queuedInboundCount: Int { inboundQueue.count - inboundHead }

    private func enqueueInbound(_ frame: QueuedInbound) {
        inboundQueue.append(frame)
        inboundQueuedBytes += frame.bytes
        if queuedInboundCount < Self.inboundQueueMaxFrames, inboundQueuedBytes < Self.inboundQueueMaxBytes {
            receive(on: frame.socket, generation: frame.generation)
        } else {
            // full: dont re-arm, the socket backs up until the drain makes room
            parkedReceive = (frame.socket, frame.generation)
        }
        scheduleInboundDrain()
    }

    private func scheduleInboundDrain() {
        guard inboundDrainTimer == nil, !snapshotValidationInFlight, !inboundPersistenceInFlight, !outboundPersistenceInFlight, queuedInboundCount > 0 else { return }
        inboundDrainTimer = scheduler.schedule(afterMs: 0) { [weak self] in
            guard let self else { return }
            self.inboundDrainTimer = nil
            self.drainInbound()
        }
    }

    /// One batch: at most 64 frames that are already queued, in arrival
    /// order, never waiting for more. Replay changes collect in one open
    /// replay batch; live records are applied to the model together at the
    /// end (or before any frame that needs the model or sends something).
    private func drainInbound() {
        guard !snapshotValidationInFlight, !inboundPersistenceInFlight, !outboundPersistenceInFlight else { return }
        let rs = replayState
        rs?.beginBatch()
        if protocolVersion == 3 {
            inboundPublication = InboundPublication(peers: presence.peers, members: onlineMembers, recipients: chatRecipients)
        }
        var processed = 0
        while processed < Self.inboundBatchMaxFrames, inboundHead < inboundQueue.count {
            let frame = inboundQueue[inboundHead]
            let type = frame.object["t"] as? String ?? ""
            let duplicate = (frame.object["id"] as? String).map { liveGroupWireIds.contains($0) } ?? false
            if protocolVersion == 3, (!Self.batchableInboundTypes.contains(type) || duplicate),
               rs?.hasUnflushedChanges == true || !liveGroup.isEmpty {
                break
            }
            inboundHead += 1
            inboundQueuedBytes -= frame.bytes
            // a frame from an ended session, or one that waited while we went
            // to background, is foreground work the next snapshot covers
            guard task === frame.socket, activeConnectionGeneration == frame.generation,
                  presenceCadence.foregroundReady else { continue }
            processed += 1
            // fail-closed: any unexpected throw from downstream parsing is
            // swallowed so a malformed frame never kills the receive loop
            do {
                try handleParsedMessage(frame.object, frameBytes: frame.bytes,
                                        socket: frame.socket, generation: frame.generation)
            } catch {
                // silently drop, dont log the frame content (SEC-019)
            }
            if snapshotValidationInFlight { break }
        }
        compactInboundQueue()
        if let rs, protocolVersion == 3 {
            finishInboundBatch(rs)
        } else {
            if let rs { try? rs.endBatch() }
            resumeParkedReceive()
            scheduleInboundDrain()
        }
    }

    private func finishInboundBatch(_ rs: SyncReplayState) {
        inboundPersistenceInFlight = true
        let generation = activeConnectionGeneration
        let token = joinToken
        let group = liveGroup
        liveGroup.removeAll()
        liveGroupWireIds.removeAll()
        liveGroupStaging = nil
        rs.flushBatch(on: persistenceExecutor) { [weak self] error in
            guard let self, self.replayState === rs, self.joinToken == token,
                  self.activeConnectionGeneration == generation, self.task != nil,
                  self.presenceCadence.foregroundReady else { try? rs.endBatch(); return }
            if let error { self.snapshotPersistenceFailed(error); return }
            do {
                if !group.isEmpty {
                    let outcome = try self.applyAcceptedRecords(group.map {
                        ($0.value, self.modelContentHash(localId: $0.value.localId))
                    })
                    try rs.clearPendingModelApplications(outcome.clears)
                    if !outcome.unsupported.isEmpty {
                        self.surface(.skippedUnsupported, scope: .join, count: outcome.unsupported.count)
                    }
                }
                rs.flushBatch(on: self.persistenceExecutor) { [weak self] error in
                    guard let self, self.replayState === rs, self.joinToken == token,
                          self.activeConnectionGeneration == generation, self.task != nil,
                  self.presenceCadence.foregroundReady else { try? rs.endBatch(); return }
                    if let error { self.snapshotPersistenceFailed(error); return }
                    do { try rs.endBatch() } catch { self.snapshotPersistenceFailed(error); return }
                    self.inboundPersistenceInFlight = false
                    let publication = self.inboundPublication
                    self.inboundPublication = nil
                    if let publication {
                        if self.presence.peers != publication.peers { self.presence.peers = publication.peers }
                        self.setOnlineMembers(publication.members)
                        if self.chatRecipients != publication.recipients { self.chatRecipients = publication.recipients }
                    }
                    self.resumeParkedReceive()
                    self.scheduleInboundDrain()
                }
            } catch { self.snapshotPersistenceFailed(error) }
        }
    }

    private func compactInboundQueue() {
        if inboundHead >= inboundQueue.count {
            inboundQueue.removeAll(keepingCapacity: true)
            inboundHead = 0
            inboundQueuedBytes = 0
        } else if inboundHead > 256 {
            inboundQueue.removeFirst(inboundHead)
            inboundHead = 0
        }
    }

    private func resumeParkedReceive() {
        guard let parked = parkedReceive,
              queuedInboundCount < Self.inboundQueueMaxFrames,
              inboundQueuedBytes < Self.inboundQueueMaxBytes else { return }
        parkedReceive = nil
        guard task === parked.socket, activeConnectionGeneration == parked.generation else { return }
        receive(on: parked.socket, generation: parked.generation)
    }

    /// Throw away queued frames. rearmParked keeps a live socket reading (the
    /// background case), otherwise the socket is going away anyway.
    private func resetInboundQueue(rearmParked: Bool) {
        inboundDrainTimer?.cancel()
        inboundDrainTimer = nil
        inboundQueue.removeAll(keepingCapacity: false)
        inboundHead = 0
        inboundQueuedBytes = 0
        let parked = parkedReceive
        parkedReceive = nil
        if rearmParked, let parked, task === parked.socket, activeConnectionGeneration == parked.generation {
            receive(on: parked.socket, generation: parked.generation)
        }
    }

    /// Session over: nothing queued or staged survives into the next one.
    /// Staged live records keep their durable pending markers, the next
    /// snapshot resolves those exactly like after a crash.
    private func dropInboundAndLiveWork() {
        resetInboundQueue(rearmParked: false)
        liveGroup.removeAll()
        liveGroupWireIds.removeAll()
        liveGroupStaging = nil
        snapshotValidationInFlight = false
        inboundPersistenceInFlight = false
        outboundPersistenceInFlight = false
        inboundPublication = nil
    }

    private func rejectInboundFrame(
        socket: SyncSocket,
        generation: Int64,
        rejection: SyncInboundFrameRejection
    ) {
        guard inboundFrameCloseGate.claimClose(generation: generation) else { return }
        let action: SyncLocalCloseAction
        switch rejection {
        case .oversized: action = .oversizedInbound
        case .invalidUTF8: action = .binaryInbound
        case .rateLimited: action = .receiveBudgetExceeded
        }
        endSession(socket: socket, cause: .local(action))
    }

    private func markPeersStale(nowUptime: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        peers = peers.mapValues { PresenceExpiryPolicy.markedStale($0, nowUptime: nowUptime) }
    }

    private enum SessionEndCause {
        /// receive/send/ping failed: read close code or HTTP status off the socket
        case remote
        /// we closed it ourselves for this reason
        case local(SyncLocalCloseAction)
    }

    /// Volatile per-socket cleanup shared by every way a session ends.
    private func tearDownSession() {
        stopConnectionHealthChecks()
        cancelHandshakeWatchdog()
        dropInboundAndLiveWork()
        task = nil
        resetOutboundPacing()
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
        v2SnapshotGate.cancel()
        awaitingHelloAck = false
        sessionDomain = nil
        presenceCounter = 0
        markPeersStale()
        activeSessions.removeAll()
        remotePresenceCandidateClusters.removeAll()
        setOnlineMembers(onlineMemberTracker.clear())
    }

    /// The one place a socket session ends. Local closes are classified by
    /// our own reason, remote ones by the relay's close code (or the upgrade
    /// status when the socket never opened), see contract section 7.
    private func endSession(socket: SyncSocket, cause: SessionEndCause, issueReported: Bool = false) {
        guard task === socket else { return }
        let remoteCode = socket.closeCode
        let httpStatus = socket.httpStatusCode
        // didOpen and the receive failure hop to main separately, so a relay
        // that upgrades and closes right away (4013, 4011..) can beat onOpen.
        // A close code or a 101 only exist on a socket that did open.
        let opened = socketOpened || remoteCode != 0 || httpStatus == 101
        let closedHelloEpoch = localHelloVersion.flatMap { UInt64($0.prefix(16), radix: 16) }
        let wasBackgroundPresence = wantConnected && !presenceCadence.foregroundReady
            && presenceCadence.backgroundEnabled && status == .connected
        let backgroundAttemptFailed = backgroundSession.map { $0.phase != .live } ?? false
        backgroundSession = nil
        tearDownSession()
        if case .local(let action) = cause {
            socket.cancel(closeCode: action.closeCode, reason: nil)
        }
        reconnectBackoff.sessionEnded()
        stableSessionTimer?.cancel()
        stableSessionTimer = nil
        // Background presence may continue only on the socket authenticated in
        // the foreground. If it drops, wait for foreground unlock rather than
        // touching durable replay/session state behind the mission-key lock.
        guard wantConnected, presenceCadence.foregroundReady else {
            // 21.4 / 21.5: a dropped background session waits for the next
            // presence opportunity to reconnect (only once doc change D1
            // ships), otherwise sharing pauses and the next foreground return
            // says so. Our own lifecycle pause or leave is not a drop.
            if wasBackgroundPresence || backgroundAttemptFailed, !Self.isOwnLifecycleClose(cause) {
                backgroundPresenceDropped(failedAttempt: backgroundAttemptFailed)
            }
            return
        }
        clearOutboundDeliveries(markForReconciliation: true)

        let now = scheduler.nowMs
        let decision: SyncConnectionDecision
        switch cause {
        case .remote:
            decision = opened
                ? closeClassifier.closed(code: remoteCode == 0 ? 1006 : remoteCode, afterOwnLeave: false, nowMs: now)
                : closeClassifier.upgradeFailed(httpStatus: httpStatus, nowMs: now)
        case .local(let action):
            switch action {
            case .leave, .lifecyclePause:
                decision = .none
            case .liveWindowResync:
                decision = SyncConnectionDecision(action: .reconnectNow)
            case .sessionNack:
                decision = closeClassifier.sessionNack(nowMs: now)
            case .structuralSnapshot:
                decision = closeClassifier.structuralSnapshotFailure()
            default:
                decision = .reconnect(.transient)
            }
        }

        switch decision.action {
        case .none:
            return
        case .stop:
            enterPausedForAction(decision.issue ?? .relayRefusedRoom, retryOnForeground: decision.retryOnForeground)
            return
        case .reconnectNow:
            // resync: clean close, straight back for a fresh snapshot, no backoff
            reconnectTimer?.cancel()
            reconnectTimer = scheduler.schedule(afterMs: 0) { [weak self] in
                guard let self, self.wantConnected, self.task == nil else { return }
                self.reconnectTimer = nil
                self.connect()
            }
            return
        case .escalateEpoch:
            joinState.after4014 = true
            joinState.rejectedEpoch = closedHelloEpoch
        case .reconnect:
            break
        }
        if decision.pacerAfter4008 { joinState.pacerAfter4008 = true }
        if let issue = decision.issue {
            surface(issue, scope: .failureChain)
        } else if !issueReported, v2SnapshotFailureGeneration != activeConnectionGeneration {
            pendingLastError = issueLifecycle.report(
                Messages.syncUnitSyncDisconnectedCheckTheRelayOrNetworkReconnectingMessage(),
                kind: .connection,
                generation: activeConnectionGeneration
            )?.pendingMessage
        }
        scheduleReconnect(decision.backoffClass ?? .transient)
    }

    private static func isOwnLifecycleClose(_ cause: SessionEndCause) -> Bool {
        if case .local(let action) = cause { return action == .leave || action == .lifecyclePause }
        return false
    }

    /// PAUSED_ACTION_REQUIRED: room, keys, replay state and chat binding stay,
    /// the socket does not come back until Retry (or a foreground return for
    /// the rows that allow it).
    private func enterPausedForAction(_ issue: SyncIssueCode, retryOnForeground: Bool) {
        wantConnected = false
        reconnectTimer?.cancel()
        reconnectTimer = nil
        if let socket = task {
            tearDownSession()
            clearOutboundDeliveries(markForReconciliation: true)
            socket.cancel(closeCode: 1000, reason: nil)
        }
        status = .offline
        pausedForAction = SyncPausedState(issue: issue, retryOnForeground: retryOnForeground)
        surfacedIssueLog.append(issue)
        pendingLastError = issueLifecycle.pin(
            message(for: issue), kind: issue.isSecurity ? .security : .connection
        )?.pendingMessage
    }

    /// The Retry action. Resets every failure counter and connects.
    func retryPausedConnection() {
        guard pausedForAction != nil, room != nil, roomId != nil else { return }
        pausedForAction = nil
        pendingLastError = issueLifecycle.clearPin()?.pendingMessage
        closeClassifier.reset()
        reconnectBackoff.reset()
        joinState.after4014 = false
        joinState.rejectedEpoch = nil
        wantConnected = true
        connect()
    }

    private func scheduleReconnect(_ backoffClass: SyncBackoffClass) {
        let now = scheduler.nowMs
        reconnectBackoff.failure(backoffClass, random: randomUnit(), nowMs: now)
        scheduleReconnectTimer(atMs: reconnectBackoff.pending?.dueMs ?? now)
    }

    private func scheduleReconnectTimer(atMs dueMs: Int64) {
        reconnectTimer?.cancel()
        let disconnectedGeneration = activeConnectionGeneration
        reconnectTimer = scheduler.schedule(afterMs: max(0, dueMs - scheduler.nowMs)) { [weak self] in
            guard let self,
                  SyncReconnectAttemptGuard.shouldRun(
                    scheduledGeneration: disconnectedGeneration,
                    currentGeneration: self.activeConnectionGeneration,
                    wantsConnection: self.wantConnected,
                    hasActiveSocket: self.task != nil
                  ) else { return }
            self.reconnectTimer = nil
            self.connect()
        }
    }

    private func pauseConnectionForBackground() {
        endBackgroundPresence()
        reconnectTimer?.cancel()
        reconnectTimer = nil
        stopConnectionHealthChecks()
        cancelHandshakeWatchdog()
        guard let socket = task else {
            status = .offline
            return
        }
        // endSession does the normal volatile cleanup. Its reconnect gate is
        // closed while foregroundReady is false.
        endSession(socket: socket, cause: .local(.lifecyclePause))
    }

    private func reconnectForForegroundReconciliation() {
        guard wantConnected, roomId != nil else { return }
        clearChatSessionSecrets()
        stopConnectionHealthChecks()
        cancelHandshakeWatchdog()
        reconnectTimer?.cancel()
        reconnectTimer = nil
        presenceSendInFlight = false
        dropInboundAndLiveWork()
        if let socket = task {
            task = nil
            resetOutboundPacing()
            socket.cancel(closeCode: SyncLocalCloseAction.lifecyclePause.closeCode, reason: nil)
        }
        status = .offline
        markPeersStale()
        activeSessions.removeAll()
        remotePresenceCandidateClusters.removeAll()
        setOnlineMembers(onlineMemberTracker.clear())
        connect()
    }

    // MARK: Handshake watchdog

    private func startHandshakeWatchdog(socket: SyncSocket, generation: Int64) {
        handshakeWatchdog.socketCreated(atMs: scheduler.nowMs)
        armHandshakeTimer()
    }

    private func cancelHandshakeWatchdog() {
        handshakeTimer?.cancel()
        handshakeTimer = nil
        handshakeWatchdog.connected()
    }

    private func transportOpened() {
        socketOpened = true
        guard !handshakeWatchdog.finished else { return }
        handshakeWatchdog.opened(atMs: scheduler.nowMs)
        armHandshakeTimer()
    }

    /// Any complete inbound message counts as progress (iOS cant see partial
    /// frames), so a slow but moving snapshot never times out (S2-03, S3-05).
    private func noteHandshakeProgress() {
        guard !handshakeWatchdog.finished else { return }
        handshakeWatchdog.progress(atMs: scheduler.nowMs)
        armHandshakeTimer()
    }

    private func armHandshakeTimer() {
        handshakeTimer?.cancel()
        handshakeTimer = nil
        guard let deadline = handshakeWatchdog.nextDeadline(), let socket = task else { return }
        let generation = activeConnectionGeneration
        handshakeTimer = scheduler.schedule(afterMs: max(0, deadline.atMs - scheduler.nowMs)) { [weak self, weak socket] in
            guard let self, let socket, self.task === socket,
                  self.activeConnectionGeneration == generation else { return }
            self.handshakeTimer = nil
            guard let fire = self.handshakeWatchdog.check(nowMs: self.scheduler.nowMs) else {
                self.armHandshakeTimer()
                return
            }
            self.handshakeWatchdogFired(fire, socket: socket, generation: generation)
        }
    }

    private func handshakeWatchdogFired(_ fire: SyncHandshakeWatchdog.Fire, socket: SyncSocket, generation: Int64) {
        if backgroundSession != nil {
            // a failed background attempt is counted by the policy, the user
            // hears about it only if sharing ends up paused
            endSession(socket: socket, cause: .local(fire.localClose), issueReported: true)
            return
        }
        if protocolVersion == 2,
           case .rejected(let reason) = v2SnapshotGate.timeout(socketIdentity: socket, generation: generation) {
            failV2Snapshot(socket: socket, generation: generation, reason: reason)
            return
        }
        pendingLastError = issueLifecycle.report(
            Messages.syncUnitSyncHandshakeTimedOutReconnectingAutomaticallyMessage(),
            kind: .connection,
            generation: generation
        )?.pendingMessage
        endSession(socket: socket, cause: .local(fire.localClose), issueReported: true)
    }

    // MARK: Heartbeat

    private func startConnectionHealthChecks() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        guard task != nil, status == .connected else { return }
        let interval = presenceCadence.foregroundReady
            ? SyncHeartbeatPolicy.foregroundPingIntervalMs
            : SyncHeartbeatPolicy.backgroundPingIntervalMs
        heartbeatTimer = scheduler.schedule(afterMs: interval) { [weak self] in
            guard let self else { return }
            self.heartbeatTimer = nil
            self.sendLivenessProbe(timeoutMs: SyncHeartbeatPolicy.deadAfterMsWithoutPongOrFrame)
            self.startConnectionHealthChecks()
        }
    }

    private func stopConnectionHealthChecks() {
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        pingDeadlineTimer?.cancel()
        pingDeadlineTimer = nil
    }

    /// Ping and give the link timeoutMs to show any sign of life (a pong or
    /// any inbound frame). Nothing back means the socket is dead. 8 s used to
    /// kill sockets stuck behind one big inbound frame on a slow link.
    private func sendLivenessProbe(timeoutMs: Int64) {
        guard status == .connected, pingDeadlineTimer == nil, let socket = task else { return }
        let generation = activeConnectionGeneration
        let startedMs = scheduler.nowMs
        pingDeadlineTimer = scheduler.schedule(afterMs: timeoutMs) { [weak self, weak socket] in
            guard let self, let socket, self.task === socket,
                  self.activeConnectionGeneration == generation else { return }
            self.pingDeadlineTimer = nil
            guard self.lastInboundMs < startedMs else { return }
            self.endSession(socket: socket, cause: .local(.livenessTimeout))
        }
        socket.sendPing { [weak self, weak socket] error in
            guard let self, let socket, self.task === socket,
                  self.activeConnectionGeneration == generation else { return }
            if error != nil {
                self.endSession(socket: socket, cause: .remote)
                return
            }
            self.lastInboundMs = max(self.lastInboundMs, self.scheduler.nowMs)
            self.pingDeadlineTimer?.cancel()
            self.pingDeadlineTimer = nil
        }
    }

    // MARK: Reachability (S2-13)

    private func pathChanged(_ status: SyncPathStatus) {
        let previous = lastPathStatus
        lastPathStatus = status
        guard let previous else { return } // first report is just the baseline
        let regained = !previous.satisfied && status.satisfied
        let swapped = previous.satisfied && status.satisfied && previous.interfaces != status.interfaces
        let lost = previous.satisfied && !status.satisfied
        if regained, !presenceCadence.foregroundReady, task == nil, backgroundPresencePolicy?.dropped == true {
            // 21.4: network back is the other reconnect opportunity
            backgroundPresenceWake()
            return
        }
        if self.status == .connected, lost || swapped {
            sendLivenessProbe(timeoutMs: SyncHeartbeatPolicy.pathChangeProbeTimeoutMs)
        }
        guard regained || swapped, wantConnected, pausedForAction == nil,
              presenceCadence.foregroundReady, task == nil, reconnectTimer != nil,
              let due = reconnectBackoff.reachabilityRegained(nowMs: scheduler.nowMs) else { return }
        scheduleReconnectTimer(atMs: due)
    }

    private func failV2Snapshot(
        socket: SyncSocket,
        generation: Int64,
        reason: LocalizedMessage
    ) {
        guard task === socket, activeConnectionGeneration == generation else { return }
        v2SnapshotFailureGeneration = generation
        pendingLastError = issueLifecycle.report(
            Messages.syncUnitSyncSnapshotFailedVerifyTheRelayOrNetworkMessage("").withArgument(0, reason),
            kind: .connection,
            generation: generation
        )?.pendingMessage
        endSession(socket: socket, cause: .local(.structuralSnapshot))
    }

    // MARK: Outbound

    private func observeStores() {
        Publishers.CombineLatest3(
            waypointStore.$waypoints,
            drawingStore.$shapes,
            drawingStore.$layers
        )
        .sink { [weak self] _, _, _ in
            self?.markDiffDirty()
        }
        .store(in: &observers)
    }

    /// 250 ms debounce for the local diff, on the injected clock so tests can
    /// step it. Acks also land here instead of diffing synchronously.
    private func markDiffDirty() {
        guard diffTimer == nil else { return }
        diffTimer = scheduler.schedule(afterMs: 250) { [weak self] in
            guard let self else { return }
            self.diffTimer = nil
            self.runLocalDiff()
        }
    }

    private func runLocalDiff() {
        guard waypointStore != nil, drawingStore != nil else { return }
        if protocolVersion == 3 {
            syncLocalStateV3()
        } else {
            syncLocalState()
        }
    }

    /// Lifetime observer: records local ABA mutations even while no room is joined.
    ///
    /// One subscription per store publisher, so each store mutation event
    /// becomes exactly one journal write with every touched id in it (contract
    /// 17). Only objects whose value or layer actually changed get looked at,
    /// instead of exporting and hashing the whole model on every publish.
    private func observeModelRevisions() {
        _ = modelIndex.updateWaypoints(waypointStore.waypoints)
        _ = modelIndex.updateShapes(drawingStore.shapes)
        _ = modelIndex.updateLayers(drawingStore.layers)
        // @Published sends on willSet, so read the new value off the event,
        // never back off the store
        waypointStore.$waypoints.dropFirst()
            .sink { [weak self] waypoints in self?.waypointsDidChange(waypoints) }
            .store(in: &revisionObservers)
        drawingStore.$shapes.dropFirst()
            .sink { [weak self] shapes in self?.shapesDidChange(shapes) }
            .store(in: &revisionObservers)
        drawingStore.$layers.dropFirst()
            .sink { [weak self] layers in self?.layersDidChange(layers) }
            .store(in: &revisionObservers)
    }

    private func waypointsDidChange(_ next: [Waypoint]) {
        let previous = modelIndex.updateWaypoints(next)
        guard !previous.isEmpty else { return }
        var changed = Set<UUID>()
        for (id, old) in previous {
            // added or removed always counts, no export needed for that
            guard let old, modelIndex.contains(id) else { changed.insert(id); continue }
            // old side first, the new export replaces the cache entry
            let oldHash = modelIndex.exportHash(waypoint: old)
            if oldHash != modelIndex.export(id)?.hash { changed.insert(id) }
        }
        modelObjectsChanged(changed, added: previous.filter { $0.value == nil }.map(\.key))
    }

    private func shapesDidChange(_ next: [DrawingShape]) {
        let previous = modelIndex.updateShapes(next)
        guard !previous.isEmpty else { return }
        var changed = Set<UUID>()
        for (id, old) in previous {
            // added or removed always counts, no export needed for that
            guard let old, modelIndex.contains(id) else { changed.insert(id); continue }
            // old side first, the new export replaces the cache entry
            let oldHash = modelIndex.exportHash(shape: old)
            if oldHash != modelIndex.export(id)?.hash { changed.insert(id) }
        }
        modelObjectsChanged(changed, added: previous.filter { $0.value == nil }.map(\.key))
    }

    private func layersDidChange(_ next: [DrawingLayer]) {
        // a renamed / recoloured / added / removed layer changes the export of
        // every object on it, nothing else does
        modelObjectsChanged(modelIndex.updateLayers(next), added: [])
    }

    private func modelObjectsChanged(_ changed: Set<UUID>, added: [UUID]) {
        if !changed.isEmpty { modelEpoch &+= 1 }
        wireIndex?.insert(added)
        guard !changed.isEmpty else { return }
        // No mission-object mutation should normally occur behind the lock,
        // and a remote apply is not a local edit. Both just move the baseline
        // (the index) and never touch the auth-bound revision journal.
        guard presenceCadence.foregroundReady, !resolvingPendingModel else { return }
        if revisionJournalLoading {
            startupRevisionEvents.append(changed.map(\.uuidString))
            return
        }
        do {
            guard revisionJournalAvailable, let journal = modelRevisionJournal else {
                throw SyncReplayState.ReplayError.invalidState
            }
            pendingJournalWrites += 1
            journal.bumpAll(changed.map(\.uuidString), on: persistenceExecutor) { [weak self] error in
                guard let self else { return }
                self.pendingJournalWrites -= 1
                if error != nil {
                    self.revisionJournalAvailable = false
                    self.pendingLastError = Messages.syncLocalRevisionHistoryCouldNotBeSavedSyncIsede036c2Message()
                    self.failClosedV3(self.pendingLastError!)
                } else if self.pendingJournalWrites == 0 { self.markDiffDirty() }
            }
        } catch {
            revisionJournalAvailable = false
            pendingLastError = Messages.syncLocalRevisionHistoryCouldNotBeSavedSyncIsede036c2Message()
            failClosedV3(pendingLastError!)
        }
    }

    private func syncLocalState() {
        guard presenceCadence.foregroundReady, status == .connected, !joinState.mutationsPaused else { return }
        // v2 state is keyed by the lowercase key, only sendPut/sendDel turn it
        // into the uppercase wire id 2.x iOS expects.
        // Exports come off the cache, an unchanged object is not re-exported.
        var current: [String: (kind: String, content: String)] = [:]
        for uuid in modelIndex.allIds {
            guard let export = modelIndex.export(uuid), let kind = modelIndex.kind(of: uuid) else { continue }
            current[LegacyV2Ids.stateKey(uuid)] = (kind.rawValue, export.content)
        }

        for (id, entry) in current {
            if forcedLegacyDeletes[id] != nil { forcedLegacyDeletes.removeValue(forKey: id) }
            if lastContent[id] == entry.content && !forcedLocalDiff.contains(id) { continue }
            if isSuppressedUntilLocalEdit(id) { continue }
            let hash = contentHash(entry.content)
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == hash, pending.kind == entry.kind { continue }
            clock += 1
            versions[id] = clock
            versionsBy[id] = clientId
            sendPut(id: id, v: clock, kind: entry.kind, content: entry.content)
        }
        let gone = Set(lastContent.keys).union(forcedLocalDiff).union(forcedLegacyDeletes.keys)
            .union(outboundDeliveries.all().map(\.localId))
            .filter { current[$0] == nil && !$0.hasPrefix("wire:") }
        for id in gone {
            if isSuppressedUntilLocalEdit(id) { continue }
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == nil, pending.kind == "del" { continue }
            clock += 1
            versions[id] = clock
            versionsBy[id] = clientId
            sendDel(id: id, v: clock)
        }
    }

    /// id is the state key, the frame goes out under the uppercase wire id
    private func sendPut(id: String, v: Int64, kind: String, content: String) {
        guard let key = roomKey, let seed = deviceSeed,
              let wireId = LegacyV2Ids.outboundId(stateKey: id) else { return }
        let probe: [String: Any] = [
            "t": "put", "id": wireId, "v": v, "by": clientId, "kind": kind, "rid": String(repeating: "0", count: 32)
        ]
        guard fitsOnTheWire(innerWithoutSignature: ["c": content, "pub": myPublicKey], outer: probe) else {
            suppressUntilLocalEdit(id)
            surface(.objectTooLarge, scope: .object(id))
            return
        }
        // Sign the write, then seal {content, pub, sig} together. The signature
        // rides INSIDE the sealed blob so the relay stays E2E-blind to device
        // identity; a receiver proves room-key possession by opening it and
        // device authorship by verifying the sig against the pinned key.
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(wireId, v, kind, clientId, content)) ?? ""
        let inner: [String: Any] = ["c": content, "pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: wireId, v: v, kind: kind)) else { return }
        let ciphertext = sealed.base64EncodedString()
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "put", "id": wireId, "v": v, "by": clientId, "kind": kind,
            "ct": ciphertext, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return }
        queueDelivery(PendingOutboundDelivery(
            localId: id, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: clientId, sessionDomain: nil, wireObjectId: wireId,
            objectVersion: String(v), kind: kind, ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: contentHash(content), desiredContent: content, frame: frame
        ))
    }

    private func sendDel(id: String, v: Int64) {
        guard let key = roomKey, let seed = deviceSeed,
              let wireId = LegacyV2Ids.outboundId(stateKey: id) else { return }
        // Deletes used to be an unauthenticated {id,v} - a coerced relay could
        // forge one and silently remove a contact. Now seal a signed proof: only
        // a room-key holder can produce it (relay can't), and it's attributable
        // to a device. AAD "del" so it can't be replayed as a put.
        let sig = SyncSigning.sign(seed, SyncSigning.objectMessage(wireId, v, "del", clientId, "")) ?? ""
        let inner: [String: Any] = ["pub": myPublicKey, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aad(id: wireId, v: v, kind: "del")) else { return }
        let ciphertext = sealed.base64EncodedString()
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "del", "id": wireId, "v": v, "by": clientId,
            "ct": ciphertext, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return }
        queueDelivery(PendingOutboundDelivery(
            localId: id, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: clientId, sessionDomain: nil, wireObjectId: wireId,
            objectVersion: String(v), kind: "del", ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: nil, desiredContent: nil, frame: frame
        ))
    }

    @discardableResult
    private func send(
        _ obj: [String: Any],
        frameClass: SyncOutboundPacer.FrameClass,
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        guard let text = encodedFrame(obj), task != nil else { return false }
        return enqueueFrame(text, frameClass: frameClass, requestId: nil, completion: completion)
    }

    private func encodedFrame(_ object: [String: Any]) -> String? {
        // unescaped slashes keep base64 ct at its real size, which is what the
        // pre-send size check measures
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes]),
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

    // MARK: Outbound pacing (contract section 11)

    private func sendPacedLeave(_ text: String, socket: SyncSocket, pacer: SyncOutboundPacer) {
        // Keep the old socket's buckets, but discard all work except leave.
        for frameClass in SyncOutboundPacer.FrameClass.allCases {
            for item in pacer.queued(frameClass) { pacer.remove(id: item.id) }
        }
        pacer.enqueue(.control, bytes: text.utf8.count)
        guard pacer.next(nowMs: Double(scheduler.nowMs)) != nil else {
            socket.cancel(closeCode: SyncLocalCloseAction.leave.closeCode, reason: nil)
            return
        }
        socket.send(text: text) { _ in
            socket.cancel(closeCode: SyncLocalCloseAction.leave.closeCode, reason: nil)
        }
    }

    private func startOutboundPacing() {
        resetOutboundPacing()
        pacer = SyncOutboundPacer(nowMs: Double(scheduler.nowMs), after4008: joinState.pacerAfter4008)
        joinState.pacerAfter4008 = false
    }

    /// Drops the socket's queue. Completions of frames that never left are
    /// not called, same as a write on a cancelled task never reporting back.
    private func resetOutboundPacing() {
        pacerWakeTimer?.cancel()
        pacerWakeTimer = nil
        pacer = nil
        pacedFrames.removeAll()
        queuedItemByRequest.removeAll()
    }

    /// Every application frame goes through here (hello, chat-key, chat, loc,
    /// put, del and their retries), so one socket never outruns
    /// relayLimits.clientPacing.
    @discardableResult
    private func enqueueFrame(
        _ text: String,
        frameClass: SyncOutboundPacer.FrameClass,
        requestId: String?,
        completion: @escaping (Bool) -> Void
    ) -> Bool {
        guard let pacer, task != nil else { return false }
        let queued = pacer.enqueue(frameClass, bytes: text.utf8.count, requestId: requestId)
        if let replaced = queued.replaced, let old = pacedFrames.removeValue(forKey: replaced) {
            old.completion(false)
        }
        pacedFrames[queued.id] = PacedFrame(
            text: text, frameClass: frameClass, requestId: requestId, completion: completion)
        if let requestId { queuedItemByRequest[requestId] = queued.id }
        pumpOutbound()
        return true
    }

    private func pumpOutbound() {
        guard let pacer, let socket = task else { return }
        let generation = activeConnectionGeneration
        while let item = pacer.next(nowMs: Double(scheduler.nowMs)) {
            guard let frame = pacedFrames.removeValue(forKey: item.id) else { continue }
            if let rid = item.requestId, queuedItemByRequest[rid] == item.id {
                queuedItemByRequest.removeValue(forKey: rid)
            }
            socket.send(text: frame.text) { [weak self, weak socket] error in
                guard let self, let socket, self.task === socket,
                      self.activeConnectionGeneration == generation else { return }
                frame.completion(error == nil)
                guard error == nil else {
                    // A failed write is definitive transport evidence. Do not
                    // keep showing a half-open socket as Connected.
                    self.endSession(socket: socket, cause: .remote)
                    return
                }
                if let rid = frame.requestId { self.mutationCopyWritten(rid) }
            }
        }
        schedulePacerWake()
    }

    private func schedulePacerWake() {
        pacerWakeTimer?.cancel()
        pacerWakeTimer = nil
        guard let pacer, let wake = pacer.nextWakeMs(nowMs: Double(scheduler.nowMs)) else { return }
        let delay = max(1, Int64((wake - Double(scheduler.nowMs)).rounded(.up)))
        pacerWakeTimer = scheduler.schedule(afterMs: delay) { [weak self] in
            guard let self else { return }
            self.pacerWakeTimer = nil
            self.pumpOutbound()
        }
    }

    private func queueDelivery(_ delivery: PendingOutboundDelivery) {
        if let prior = outboundDeliveries.register(delivery) {
            // Superseding settles the old window slot even if its ack is lost.
            // Drop it without pumping until the new reservation is queued.
            dropUnwrittenCopy(prior.requestId)
            ackTimers.removeValue(forKey: prior.requestId)?.cancel()
            ackStates.removeValue(forKey: prior.requestId)
            writtenUnacked.removeAll { $0 == prior.requestId }
            pacer?.settle(requestId: prior.requestId)
        }
        ackStates[delivery.requestId] = SyncAckTimer()
        enqueueFrame(delivery.frame, frameClass: .mutation, requestId: delivery.requestId) { _ in }
    }

    private func dropUnwrittenCopy(_ requestId: String) {
        guard let id = queuedItemByRequest.removeValue(forKey: requestId) else { return }
        pacer?.remove(id: id)
        pacedFrames.removeValue(forKey: id)
    }

    /// The ack clock for a copy starts when that copy is on the wire, never
    /// when it was queued (S3-06, S5-09).
    private func mutationCopyWritten(_ requestId: String) {
        guard var state = ackStates[requestId], state.copyUnwritten,
              outboundDeliveries.pending(requestId: requestId) != nil else { return }
        let ahead = writtenUnacked.filter { $0 != requestId }.count
        if !writtenUnacked.contains(requestId) { writtenUnacked.append(requestId) }
        let deadline = state.writeComplete(atMs: scheduler.nowMs, aheadInFlight: ahead)
        ackStates[requestId] = state
        ackTimers.removeValue(forKey: requestId)?.cancel()
        let generation = activeConnectionGeneration
        ackTimers[requestId] = scheduler.schedule(afterMs: max(0, deadline - scheduler.nowMs)) { [weak self] in
            guard let self, self.activeConnectionGeneration == generation else { return }
            self.ackTimers.removeValue(forKey: requestId)
            self.ackTimedOut(requestId)
        }
    }

    private func ackTimedOut(_ requestId: String) {
        guard var state = ackStates[requestId],
              let pending = outboundDeliveries.pending(requestId: requestId) else { return }
        if state.ackTimeout(),
           let retry = outboundDeliveries.nextAttempt(
            requestId: requestId,
            generation: pending.connectionGeneration,
            sessionDomain: pending.sessionDomain
           ) {
            ackStates[requestId] = state
            enqueueFrame(retry.frame, frameClass: .retry, requestId: requestId) { _ in }
            return
        }
        ackStates[requestId] = state
        // still unconfirmed after bounded retries: availability, not security
        surface(.unconfirmedReconnect, scope: .session)
        if let socket = task {
            endSession(socket: socket, cause: .local(.ackExhausted), issueReported: true)
        }
    }

    /// Ack, nack or local resolve: the op is done with this socket.
    private func settleDelivery(_ requestId: String) {
        ackTimers.removeValue(forKey: requestId)?.cancel()
        ackStates.removeValue(forKey: requestId)
        writtenUnacked.removeAll { $0 == requestId }
        dropUnwrittenCopy(requestId)
        pacer?.settle(requestId: requestId)
        pumpOutbound()
    }

    private func clearOutboundDeliveries(markForReconciliation: Bool) {
        ackTimers.values.forEach { $0.cancel() }
        ackTimers.removeAll()
        ackStates.removeAll()
        writtenUnacked.removeAll()
        let pending = outboundDeliveries.all()
        for delivery in pending {
            dropUnwrittenCopy(delivery.requestId)
            pacer?.settle(requestId: delivery.requestId)
        }
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

    /// One outbound diff pass (contract 11.2, 17, 18). Unchanged objects come
    /// off the export cache and are skipped on a lastContent compare before
    /// anything is hashed or HMACed, wire ids come off the index, and every
    /// stamp of the pass is reserved in a single replay write before the
    /// first frame is queued.
    private func syncLocalStateV3() {
        guard presenceCadence.foregroundReady,
              status == .connected, let keys = v3Keys, let rs = replayState,
              let actorId = myActorId, let sd = sessionDomain,
              revisionJournalAvailable,
              // counter-window proved the relay cant take our writes anymore
              !joinState.mutationsPaused,
              !resolvingPendingModel, !inboundPersistenceInFlight, !snapshotValidationInFlight, !outboundPersistenceInFlight, pendingJournalWrites == 0,
              !rs.hasPendingModelApplications() else { return }
        let index = ensureWireIndex(keys)
        let sessionString = SyncIdentity.urlB64Encode(sd)

        struct Candidate {
            let localId: String
            let wireId: String
            let kind: String
            let content: String?
            let contentHash: String?
            let recovery: VersionStamp?
        }
        var candidates: [Candidate] = []
        for uuid in modelIndex.allIds {
            let id = uuid.uuidString
            guard let export = modelIndex.export(uuid),
                  lastContent[id] != export.content || forcedLocalDiff.contains(id) else { continue }
            if isSuppressedUntilLocalEdit(id) { continue }
            let kind = modelIndex.kind(of: uuid)?.rawValue ?? "drawing"
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == export.hash, pending.kind == kind { continue }
            let wireId = index.wireId(for: uuid)
            let probe: [String: Any] = [
                "t": "put", "id": wireId, "vs": String(repeating: "0", count: 17) + actorId,
                "by": actorId, "kind": kind, "pub": myPublicKey, "sd": sessionString,
                "rid": String(repeating: "0", count: 32)
            ]
            guard fitsOnTheWire(innerWithoutSignature: ["c": export.content], outer: probe) else {
                // reserve nothing, send nothing, wait for the user to shrink it
                suppressUntilLocalEdit(id)
                surface(.objectTooLarge, scope: .object(id))
                continue
            }
            let recovery = rs.recoverableLocalPut(
                wireObjectId: wireId, actorId: actorId, pubkey: myPublicKey, contentHash: export.hash)
            candidates.append(Candidate(localId: id, wireId: wireId, kind: kind, content: export.content,
                                        contentHash: export.hash, recovery: recovery))
        }
        let gone = Set(lastContent.keys).union(forcedLocalDiff)
            .union(outboundDeliveries.all().map(\.localId))
            .filter { !$0.hasPrefix("wire:") && !localObjectExists($0) }
        for id in gone {
            if isSuppressedUntilLocalEdit(id) { continue }
            if let pending = outboundDeliveries.pending(localId: id),
               pending.desiredContentHash == nil, pending.kind == "del" { continue }
            guard let uuid = UUID(uuidString: id) else { continue }
            candidates.append(Candidate(localId: id, wireId: index.wireId(for: uuid), kind: "del",
                                        content: nil, contentHash: nil, recovery: nil))
        }
        guard !candidates.isEmpty else { return }

        let stamps: [VersionStamp?]
        rs.beginBatch()
        do {
            stamps = try rs.reserveLocalMutations(candidates.map {
                SyncReplayState.LocalReservation(
                    wireObjectId: $0.wireId,
                    kind: $0.contentHash.map { .put(contentHash: $0) } ?? .delete,
                    recoveryStamp: $0.recovery)
            }, actorId: actorId, pubkey: myPublicKey, deferPersistence: true)
        } catch {
            failClosedV3(Messages.syncRollbackProtectionStateCouldNotBeSavedMessage())
            return
        }
        outboundPersistenceInFlight = true
        let generation = activeConnectionGeneration
        let token = joinToken
        rs.flushBatch(on: persistenceExecutor) { [weak self] error in
            guard let self, self.replayState === rs, self.joinToken == token,
                  self.activeConnectionGeneration == generation, self.task != nil,
                  self.presenceCadence.foregroundReady else {
                try? rs.endBatch(); return
            }
            self.outboundPersistenceInFlight = false
            if let error { self.snapshotPersistenceFailed(error); return }
            do { try rs.endBatch() } catch { self.snapshotPersistenceFailed(error); return }
            for (candidate, stamp) in zip(candidates, stamps) {
                guard let stamp else { continue }
                // A model edit during the seal gets a new reservation next pass.
                guard self.modelContentHash(localId: candidate.localId) == candidate.contentHash else { continue }
                if let content = candidate.content {
                    self.queuePutV3(localId: candidate.localId, wireObjectId: candidate.wireId,
                                    kind: candidate.kind, content: content, stamp: stamp)
                } else {
                    self.queueDelV3(localId: candidate.localId, wireObjectId: candidate.wireId, stamp: stamp)
                }
            }
            self.scheduleInboundDrain()
            self.markDiffDirty()
        }
    }

    // MARK: Suppression and per-join bookkeeping (contract 0.1, 2.3, 6)

    /// True while the user has not touched this object since we decided not
    /// to publish it (skipped record, too large, quota/invalid/stale nack).
    private func isSuppressedUntilLocalEdit(_ localId: String) -> Bool {
        guard let generation = joinState.suppressedUntilEdit[localId] else { return false }
        if journalGeneration(localId) == generation { return true }
        joinState.suppressedUntilEdit.removeValue(forKey: localId)
        return false
    }

    private func suppressUntilLocalEdit(_ localId: String) {
        guard !localId.hasPrefix("wire:") else { return }
        joinState.suppressedUntilEdit[localId] = journalGeneration(localId) ?? 0
        forcedLocalDiff.remove(localId)
    }

    /// The journal is bumped by uuidString (uppercase) but v2 suppression is
    /// keyed by the lowercase state key, so look it up by the UUID or a v2
    /// suppression never lifts on an edit.
    private func journalGeneration(_ localId: String) -> Int64? {
        modelRevisionJournal?.generation(UUID(uuidString: localId)?.uuidString ?? localId)
    }

    private func recordSkip(_ wireId: String, _ category: SnapshotSkipCategory) {
        if joinState.skippedWireIds[wireId] == nil || category == .unverified {
            joinState.skippedWireIds[wireId] = category
        }
        // an untouched local copy must never be pushed over the record we
        // could not read, that turns version skew into data loss
        if let localId = findLocalIdForWireId(wireId) { suppressUntilLocalEdit(localId) }
    }

    private enum IssueScope {
        case join
        case session
        case failureChain
        case object(String)
        /// one background period, keyed by when it paused
        case backgroundPeriod(Date)
    }

    /// Report a contract issue once per scope. The classifier already makes
    /// failure-chain issues fire once.
    private func surface(_ issue: SyncIssueCode, scope: IssueScope, count: Int = 0, at: Date? = nil) {
        let key: String?
        switch scope {
        case .join: key = issue.rawValue
        case .session: key = "\(issue.rawValue)#\(activeConnectionGeneration)"
        case .failureChain: key = nil
        case .object(let id): key = "\(issue.rawValue)#\(id)"
        case .backgroundPeriod(let pausedAt): key = "\(issue.rawValue)#\(pausedAt.timeIntervalSince1970)"
        }
        if let key, !joinState.surfacedKeys.insert(key).inserted { return }
        surfacedIssueLog.append(issue)
        let text = message(for: issue, count: count, at: at)
        switch issue {
        case .roomResetChangesPaused:
            pendingLastError = issueLifecycle.pin(text, kind: .connection)?.pendingMessage
        case .chatReplayFull, .chatRecipientInBackground:
            break
        case .unconfirmedReconnect, .relayBusy, .relayRateLimited:
            pendingLastError = issueLifecycle.report(
                text, kind: .connection, generation: activeConnectionGeneration)?.pendingMessage
        default:
            if issue.isSecurity {
                pendingLastError = issueLifecycle.report(
                    text, kind: .security, generation: activeConnectionGeneration)?.pendingMessage
            } else {
                pendingLastError = issueLifecycle.notice(text)?.pendingMessage
            }
        }
        remoteUpdateSubject.send(text.text)
    }

    private func message(for issue: SyncIssueCode, count: Int = 0, at: Date? = nil) -> LocalizedMessage {
        switch issue {
        case .skippedUnsupported: return Messages.syncRecordsSkippedUnsupportedMessage(String(count))
        case .skippedUnverified: return Messages.syncRecordsSkippedUnverifiedMessage(String(count))
        case .roomResetSuspected: return Messages.syncRoomResetSuspectedMessage()
        case .roomResetChangesPaused: return Messages.syncRoomResetChangesPausedMessage()
        case .objectTooLarge: return Messages.syncObjectTooLargeMessage()
        case .roomQuotaNack: return Messages.syncTheUnitSyncRoomIsFullSoThisSavedMessage()
        case .relayInvalidNack: return Messages.syncTheUnitSyncRelayRejectedAChangeAsInvalidMessage()
        case .unconfirmedReconnect: return Messages.syncAUnitSyncChangeIsStillUnconfirmedAfterBoundedMessage()
        case .snapshotStructural: return Messages.syncSyncSnapshotAuthenticationFailedMessage()
        case .relayBusy: return Messages.syncRelayBusyMessage()
        case .relayRateLimited: return Messages.syncRelayRateLimitedMessage()
        case .roomFullCannotJoin: return Messages.syncRoomFullCannotJoinMessage()
        case .relayRefusedRoom: return Messages.syncRelayRefusedRoomMessage()
        case .identityRejected: return Messages.syncIdentityRejectedMessage()
        case .sessionConflict: return Messages.syncSessionConflictMessage()
        case .sessionCounterBehind: return Messages.syncSessionCounterBehindMessage()
        case .snapshotMalformedStopped: return Messages.syncSnapshotMalformedStoppedMessage()
        case .backgroundPaused:
            let time = DateFormatter.localizedString(from: at ?? Date(), dateStyle: .none, timeStyle: .short)
            return Messages.syncBackgroundPausedMessage(time)
        case .chatReplayFull: return Messages.chatReplayTableFullMessage()
        case .chatRecipientInBackground: return Messages.chatRecipientInBackgroundMessage()
        }
    }

    /// Exact frame bytes against CT_MAX / MAX_FRAME_BYTES before a stamp is
    /// reserved (S1-09). The signature is a placeholder of the real length.
    private func fitsOnTheWire(innerWithoutSignature inner: [String: Any], outer: [String: Any]) -> Bool {
        var probe = inner
        probe["sig"] = String(repeating: "A", count: OutboundSizeCheck.signaturePlaceholderChars)
        guard let innerData = try? JSONSerialization.data(withJSONObject: probe) else { return false }
        var frame = outer
        frame["ct"] = ""
        guard let frameData = try? JSONSerialization.data(withJSONObject: frame, options: [.withoutEscapingSlashes]) else {
            return false
        }
        return OutboundSizeCheck.fits(innerUtf8Bytes: innerData.count, frameBytesWithoutCiphertext: frameData.count)
    }

    /// Sign, seal and queue one put whose stamp is already durable (reserved
    /// by the diff pass, or a recovery stamp from the replay state).
    private func queuePutV3(localId: String, wireObjectId: String, kind: String, content: String,
                            stamp: VersionStamp) {
        guard let key = roomKey, let actorId = myActorId, stamp.actorId == actorId,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return }
        let counterHex = VersionStamp.counterHex16(stamp.counter)
        let vs = stamp.encode()
        let payloadHash = SyncIdentity.sha256(Data(content.utf8))
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainPut, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: kind, payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return }
        let inner: [String: Any] = ["c": content, "sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: kind)) else { return }
        let ciphertext = sealed.base64EncodedString()
        let session = SyncIdentity.urlB64Encode(sd)
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "put", "id": wireObjectId, "vs": vs, "by": actorId, "kind": kind,
            "ct": ciphertext, "pub": myPublicKey, "sd": session, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return }
        queueDelivery(PendingOutboundDelivery(
            localId: localId, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: actorId, sessionDomain: session, wireObjectId: wireObjectId,
            objectVersion: vs, kind: kind, ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: SyncIdentity.bytesToHex(payloadHash), desiredContent: content, frame: frame
        ))
    }

    /// Same for a delete. The post hello-ack resend of recoverable deletes
    /// comes through here with the tombstone's own stamp, so it writes nothing.
    private func queueDelV3(localId: String, wireObjectId: String, stamp: VersionStamp) {
        guard let key = roomKey, let actorId = myActorId, stamp.actorId == actorId,
              let sd = sessionDomain, let keys = v3Keys,
              let seed = deviceSeed else { return }
        let counterHex = VersionStamp.counterHex16(stamp.counter)
        let vs = stamp.encode()
        let payloadHash = SyncIdentity.sha256(Data())
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainDelete, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd, counterHex16: counterHex,
            objectId: wireObjectId, kind: "del", payloadHash: payloadHash)
        guard let sig = SyncSigning.sign(seed, preimage) else { return }
        let inner: [String: Any] = ["sig": sig]
        guard let innerData = try? JSONSerialization.data(withJSONObject: inner),
              let sealed = SyncCrypto.seal(key, innerData, aad: SyncCrypto.aadV3(wireObjectId: wireObjectId, vs: vs, kind: "del")) else { return }
        let ciphertext = sealed.base64EncodedString()
        let session = SyncIdentity.urlB64Encode(sd)
        let requestId = newDeliveryRequestId()
        let object: [String: Any] = [
            "t": "del", "id": wireObjectId, "vs": vs, "by": actorId, "kind": "del",
            "ct": ciphertext, "pub": myPublicKey, "sd": session, "rid": requestId
        ]
        guard let frame = encodedFrame(object) else { return }
        queueDelivery(PendingOutboundDelivery(
            localId: localId, requestId: requestId, connectionGeneration: activeConnectionGeneration,
            actorId: actorId, sessionDomain: session, wireObjectId: wireObjectId,
            objectVersion: vs, kind: "del", ciphertextHash: ciphertextHash(ciphertext),
            desiredContentHash: nil, desiredContent: nil, frame: frame
        ))
    }

    private func sendHelloV3() {
        guard let actorId = myActorId, sessionDomain != nil,
              v3Keys != nil, myPublicKeyRaw != nil,
              deviceSeed != nil, let rs = replayState else {
            failClosedV3(Messages.syncCouldNotConstructAuthenticatedHelloMessage()); return
        }
        // Lost/fresh replay state starts at the unix-minute floor and a 4014
        // doubles the rejected epoch, so one attempt usually clears the
        // relay's stored epoch instead of N full snapshots (S3-11).
        let persisted = rs.getHelloEpoch(actorId).flatMap { UInt64($0, radix: 16) }
        // 14 / 21.4: with background presence opted in, persist next + 64 so a
        // background reconnect can use a spare without ever writing
        let spare: UInt64 = backgroundReconnectEnabled && presenceConfig.shareLocation
            ? HelloEpochPolicy.backgroundSpareBlock : 0
        let epoch: String
        do {
            let floor = try HelloEpochPolicy.floor(
                persisted: persisted,
                nowMs: scheduler.wallMs,
                after4014: joinState.after4014,
                rejected: joinState.rejectedEpoch)
            rs.beginBatch()
            epoch = try rs.reserveHelloEpoch(actorId: actorId, pubkey: myPublicKey, floor: floor, spare: spare, deferPersistence: true)
        } catch HelloEpochPolicy.Failure.exhausted, SyncReplayState.ReplayError.counterExhausted {
            enterPausedForAction(.sessionCounterBehind, retryOnForeground: false)
            return
        } catch {
            failClosedV3(Messages.syncCouldNotReserveAuthenticatedSessionEpochMessage()); return
        }
        snapshotValidationInFlight = true
        let generation = activeConnectionGeneration, token = joinToken
        rs.flushBatch(on: persistenceExecutor) { [weak self] error in
            guard let self, self.replayState === rs, self.joinToken == token,
                  self.activeConnectionGeneration == generation, self.task != nil,
                  self.presenceCadence.foregroundReady else {
                try? rs.endBatch(); return
            }
            if let error { self.snapshotPersistenceFailed(error); return }
            do { try rs.endBatch() } catch { self.snapshotPersistenceFailed(error); return }
            self.snapshotValidationInFlight = false
            self.joinState.after4014 = false
            self.joinState.rejectedEpoch = nil
            if let value = UInt64(epoch, radix: 16) { self.lastForegroundHello = (value, spare) }
            self.writeHello(epochHex: epoch)
            self.scheduleInboundDrain()
        }
    }

    /// Sign and queue a hello for an epoch that is already durable (the one
    /// just reserved, or a spare from that reservation in background).
    private func writeHello(epochHex epoch: String) {
        guard let actorId = myActorId, let sd = sessionDomain,
              let keys = v3Keys, let pubRaw = myPublicKeyRaw,
              let seed = deviceSeed else {
            failClosedV3(Messages.syncCouldNotConstructAuthenticatedHelloMessage()); return
        }
        let vs = "\(epoch):\(actorId)"
        localHelloVersion = vs
        let preimage = SyncIdentity.buildPreimage(
            domain: SyncIdentity.domainHello, roomIdRaw: keys.roomIdRaw,
            actorId: actorId, sessionDomain: sd,
            counterHex16: epoch, objectId: "",
            kind: "hello", payloadHash: SyncIdentity.sha256(pubRaw))
        guard let sig = SyncSigning.sign(seed, preimage) else {
            failClosedV3(Messages.syncCouldNotSendAuthenticatedHelloMessage()); return
        }
        let frame: [String: Any] = [
            "t": "hello", "by": actorId, "pub": myPublicKey,
            "sd": SyncIdentity.urlB64Encode(sd), "vs": vs, "sig": sig
        ]
        // Transport failures use the same socket/generation-bound reconnect
        // path as every other frame. A delayed error from a replaced hello must
        // never fail-close the newer authenticated session.
        guard send(frame, frameClass: .control, completion: { [weak self] written in
            guard let self, written, !self.handshakeWatchdog.finished else { return }
            // hello-ack clock starts when the hello is actually on the wire
            self.handshakeWatchdog.helloWritten(atMs: self.scheduler.nowMs)
            self.armHandshakeTimer()
        }) else {
            failClosedV3(Messages.syncCouldNotSendAuthenticatedHelloMessage())
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
            frameClass: .presence,
            completion: completion
        )
    }

    private func failClosedV3(_ message: LocalizedMessage) {
        clearChatSessionSecrets()
        stopConnectionHealthChecks()
        cancelHandshakeWatchdog()
        dropInboundAndLiveWork()
        endBackgroundPresence()
        pendingLastError = issueLifecycle.report(
            message, kind: .security, generation: activeConnectionGeneration
        )?.pendingMessage
        wantConnected = false
        task?.cancel(closeCode: 1011, reason: nil)
        task = nil
        resetOutboundPacing()
        status = .offline
        peers.removeAll()
        activeSessions.removeAll()
        remotePresenceCandidateClusters.removeAll()
        setOnlineMembers(onlineMemberTracker.clear())
    }

    private func reportRemoteModelPersistenceFailure(_ error: Error,
                                                     remainsPending: Bool) {
        let recovery: LocalizedMessage
        switch error {
        case SyncRemoteModelMutationError.identityCollision:
            recovery = Messages.syncResolveTheDuplicateWaypointDrawingIdentityLocallyThenRejoinMessage()
        case SyncRemoteModelMutationError.invalidPayload:
            recovery = Messages.syncAskTheSenderToUpdateTacmapAndSendTheMessage()
        default:
            recovery = remainsPending
                ? Messages.syncTheVerifiedUpdateRemainsPendingUnlockMissionDataOrMessage()
                : Messages.syncUnlockMissionDataOrFreeDeviceStorageThenLeaveMessage()
        }
        pendingLastError = issueLifecycle.report(
            Messages.syncRecoveryDetailMessage("", "")
                .withArgument(0, (error as? SyncRemoteModelMutationError)?.localizedMessage ?? .literal(error.localizedDescription))
                .withArgument(1, recovery),
            kind: .security,
            generation: activeConnectionGeneration
        )?.pendingMessage
        remoteUpdateSubject.send(L10n.text("Sync update not saved. Open Unit Sync for recovery guidance."))
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

    /// Send a recent GPS fix. Foreground presence follows PresenceSendPolicy
    /// (contract 20.1: moving units every 5 s, a parked one every 20 s);
    /// screen-off sends are coalesced to the OPSEC-selected interval. A failed
    /// socket send remains due so the next Core Location wake can retry.
    /// bridge = the one frame at background entry (21.1): background retention,
    /// fix up to 120 s old, no cadence wait.
    func sendPresence(bridge: Bool = false) {
        guard status == .connected,
              presenceConfig.shareLocation,
              let locationService,
              LiveLocationPermissionPolicy.shouldStartUpdates(
                for: locationService.authorisationStatus
              ),
              // the bridge may overtake a frame still in flight, the pacer
              // keeps only the newest queued presence anyway
              !presenceSendInFlight || bridge,
              let activeTask = task else { return }
        let uptime = ProcessInfo.processInfo.systemUptime
        let nowMs = scheduler.nowMs
        let foreground = presenceCadence.foregroundReady
        if foreground {
            guard presenceSendPolicy.minIntervalElapsed(nowMs: nowMs) else { return }
        } else {
            guard bridge || presenceCadence.isDue(at: uptime) else { return }
        }

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
                now: now,
                maximumAge: bridge ? UnitSyncPresenceCadence.bridgeLocationAge
                    : UnitSyncPresenceCadence.maximumLocationAge
              ),
              bridge || presenceCadence.canBroadcast(
                locationTimestamp: loc.timestamp,
                at: uptime
              ) else { return }
        let fix = PresenceSendPolicy.Fix(
            latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude,
            speedMps: loc.speed, courseDegrees: loc.course,
            horizontalAccuracyMetres: loc.horizontalAccuracy)
        // stationary suppression is foreground only, background keeps its interval
        if foreground, !presenceSendPolicy.shouldSend(fix, nowMs: nowMs) { return }
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
                self.presenceSendPolicy.markSent(fix, atMs: nowMs)
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
            frameClass: .presence,
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
        setOnlineMembers(onlineMemberTracker.expireStaleMetadata())
        refreshChatRecipients()
    }

    // MARK: Inbound

    private func handleParsedMessage(
        _ obj: [String: Any],
        frameBytes: Int,
        socket: SyncSocket,
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
                cancelHandshakeWatchdog()
                for item in batch.records {
                    applyRecord(item, snapshotGeneration: generation)
                }
                for member in batch.members { applyPresence(member) }
                guard task === socket, activeConnectionGeneration == generation else { return }
                finalizeLegacyDeleteSnapshotConfirmations(snapshotGeneration: generation)
                v2SnapshotFailureGeneration = nil
                // v2 has no hello-ack, snapshot completion is its "connected".
                // Still not a stable session though, same rule as v3.
                reconnectBackoff.connected(atMs: scheduler.nowMs)
                armStableSessionTimer()
                closeClassifier.connectionSucceeded()
                status = .connected
                pendingLastError = issueLifecycle.connectionSucceeded(
                    generation: generation,
                    verifiedCleanSnapshot: false
                )?.pendingMessage
                syncLocalState()
                presenceCadence.startAuthenticatedSession()
                presenceSendPolicy.startSession()
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
        )) else {
            // a superseded copy still frees its in-flight slot
            if pacer?.isInFlight(requestId) == true, actor == (v3 ? myActorId : clientId) {
                settleDelivery(requestId)
            }
            return
        }
        settleDelivery(delivered.requestId)
        // the first op-ack proves this session works end to end
        reconnectBackoff.opAcknowledged()
        markDeliveryConfirmed(delivered)
        // never a synchronous full diff from an ack, the debounce picks it up
        if status == .connected { markDiffDirty() }
    }

    /// The relay holds exactly what we sent: make it the echo baseline when
    /// the model still matches, otherwise the next diff republishes.
    private func markDeliveryConfirmed(_ delivered: PendingOutboundDelivery) {
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
    }

    /// op-nack (contract section 6). Nacks are relay statements: they settle
    /// or retry our own op, never touch replay state, never SECURITY.
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
        let outcome = SyncNackPolicy.decide(
            code: code,
            retryable: retryable,
            rejectedStampIsOwnPersisted: isOwnPersistedStamp(pending),
            wireIdSkipped: joinState.skippedWireIds[pending.wireObjectId] != nil
        )
        switch outcome {
        case .retry:
            // keep it, the running ack timer retransmits through the pacer
            break
        case .reconnect(let action):
            if let socket = task { endSession(socket: socket, cause: .local(action)) }
        case .confirmed:
            outboundDeliveries.resolve(requestId: requestId)
            settleDelivery(requestId)
            markDeliveryConfirmed(pending)
        case .suppress(let issue):
            outboundDeliveries.resolve(requestId: requestId)
            settleDelivery(requestId)
            suppressUntilLocalEdit(pending.localId)
            if let issue { surface(issue, scope: .session) }
        case .pauseMutations:
            outboundDeliveries.resolve(requestId: requestId)
            settleDelivery(requestId)
            pauseMutationsForJoin()
        }
    }

    /// stale on a stamp that is still our own persisted one means the relay
    /// already stored this exact write (S3-15), it just came from an older sd.
    private func isOwnPersistedStamp(_ pending: PendingOutboundDelivery) -> Bool {
        if protocolVersion == 3 {
            guard let actorId = myActorId, pending.actorId == actorId,
                  let stamp = replayState?.getStamp(pending.wireObjectId) else { return false }
            return stamp.actorId == actorId && stamp.encode() == pending.objectVersion
        }
        guard let v = Int64(pending.objectVersion) else { return false }
        return versions[pending.localId] == v && versionsBy[pending.localId] == clientId
    }

    /// counter-window: the relay's high water sits below our counters for
    /// good (expired or purged room). Keep presence, chat and inbound, stop
    /// writing for the rest of the join and say so once.
    private func pauseMutationsForJoin() {
        guard !joinState.mutationsPaused else { return }
        joinState.mutationsPaused = true
        mutationsPaused = true
        for delivery in outboundDeliveries.all() {
            outboundDeliveries.resolve(requestId: delivery.requestId)
            settleDelivery(delivery.requestId)
        }
        surface(.roomResetChangesPaused, scope: .join)
    }

    private func localObjectExists(_ localId: String) -> Bool {
        guard let uuid = UUID(uuidString: localId) else { return false }
        return modelIndex.contains(uuid)
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
        // Verify with the id exactly as sent, key our state by its lowercase
        // form so shipped uppercase iOS senders and Android meet (S3-01).
        guard let rawId = rec["id"] as? String, let id = LegacyV2Ids.stateKey(rawId) else { return }
        guard let v = strictVersion(rec["v"]) else { return }
        let recKind = rec["kind"] as? String ?? "unknown"
        let by = rec["by"] as? String ?? ""
        // Monotonic per-id (v, by): a relay cant resurrect a deleted object by
        // replaying an older-but-validly-signed put, and an equal v goes to the
        // higher writer exactly like the relay picks it (S3-14).
        guard LegacyV2Ids.beats(v: v, by: by, lastV: versions[id], lastBy: versionsBy[id]) else { return }
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: rawId, v: v, kind: recKind)),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any] else { return }
        let content = inner["c"] as? String ?? ""
        // Device authorship: the write must be signed by the key pinned to `by`
        // (TOFU). A room member can't forge a write as another established
        // device; a key that doesn't match the pin is rejected as a swap.
        guard verifyObjectSig(by: by, inner: inner,
                              signed: SyncSigning.objectMessage(rawId, v, recKind, by, content)),
              let contentData = content.data(using: .utf8) else { return }
        let fallback = drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
        guard let parsed = try? GeoJSONImporter.parse(contentData, existingLayers: drawingStore.layers,
                                                      fallbackLayerID: fallback) else { return }
        let embedded = parsed.waypoints.first?.id ?? parsed.drawings.first?.id
        guard embedded.map({ LegacyV2Ids.stateKey($0) }) == id else { return }
        if forcedLegacyDeletes[id] != nil {
            clock = max(clock, v)
            versions[id] = v
            versionsBy[id] = by
            return
        }
        if forcedLocalDiff.contains(id) {
            let current = reexport(id: id)
            clock = max(clock, v)
            versions[id] = v
            versionsBy[id] = by
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
                versionsBy[id] = by
                kindById[id] = parsed.waypoints.isEmpty ? "drawing" : "waypoint"
                lastContent[id] = reexport(id: id)
            }
        } catch {
            reportRemoteModelPersistenceFailure(error, remainsPending: false)
            return
        }

        // Let UI know a remote change landed.
        let kind = parsed.waypoints.isEmpty ? L10n.text("Drawing") : L10n.text("Waypoint")
        remoteUpdateSubject.send(L10n.text("%1$@ updated by another device", kind))
    }

    private func applyDelete(_ rec: [String: Any], snapshotGeneration: Int64? = nil) {
        guard let rawId = rec["id"] as? String, let id = LegacyV2Ids.stateKey(rawId) else { return }
        guard let v = strictVersion(rec["v"]) else { return }
        let by = rec["by"] as? String ?? ""
        guard LegacyV2Ids.beats(v: v, by: by, lastV: versions[id], lastBy: versionsBy[id]) else { return }
        // Open the sealed proof (proves room-key possession, so a relay with no
        // room key can't forge a delete) then verify the device signature.
        guard let key = roomKey,
              let ctB64 = rec["ct"] as? String,
              ctB64.utf8.count <= Self.maxBase64Bytes,
              let blob = Data(base64Encoded: ctB64),
              let plain = SyncCrypto.open(key, blob, aad: SyncCrypto.aad(id: rawId, v: v, kind: "del")),
              let inner = try? JSONSerialization.jsonObject(with: plain) as? [String: Any],
              verifyObjectSig(by: by, inner: inner,
                              signed: SyncSigning.objectMessage(rawId, v, "del", by, "")) else { return }
        if let recovery = forcedLegacyDeletes[id] {
            let exactSnapshotConfirmation = snapshotGeneration.map {
                // our own del went out under the uppercase wire id
                recovery.matchesVerifiedTombstone(
                    localId: id,
                    wireObjectId: rawId,
                    actorId: by,
                    objectVersion: String(v),
                    kind: rec["kind"] as? String ?? "del",
                    ciphertextHash: ciphertextHash(ctB64),
                    snapshotGeneration: $0
                )
            } ?? false
            clock = max(clock, v)
            versions[id] = v
            versionsBy[id] = by
            if exactSnapshotConfirmation { pendingLegacyDeleteConfirmations.insert(id) }
            return
        }
        if forcedLocalDiff.contains(id) && localObjectExists(id) {
            clock = max(clock, v)
            versions[id] = v
            versionsBy[id] = by
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
                versionsBy[id] = by
                lastContent[id] = nil
                kindById[id] = nil
            }
        } catch {
            reportRemoteModelPersistenceFailure(error, remainsPending: false)
            return
        }

        remoteUpdateSubject.send(L10n.text("Object removed by another device"))
    }

    private func finalizeLegacyDeleteSnapshotConfirmations(snapshotGeneration: Int64) {
        for id in pendingLegacyDeleteConfirmations {
            guard let recovery = forcedLegacyDeletes[id],
                  recovery.snapshotGeneration == snapshotGeneration else { continue }
            ackTimers.removeValue(forKey: recovery.requestId)?.cancel()
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

    /// Re-serialise so the next diff doesn't see a spurious change. Comes
    /// off the export cache, an unchanged object is never exported twice.
    private func reexport(id: String) -> String {
        // UUID compare so v2's lowercase keys and v3's uppercase ones both hit
        guard let uuid = UUID(uuidString: id) else { return "" }
        return modelIndex.export(uuid)?.content ?? ""
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

    /// Frames that only touch in-memory session state or the replay batch.
    /// Anything else (acks, snapshot fences, chat, hello-ack...) reads the
    /// model, sends, or writes another store, so the batch is made durable and
    /// the staged live records applied first.
    static let batchableInboundTypes: Set<String> = ["put", "del", "loc", "hello", "leave", "chat-key"]

    private func handleParsedMessageV3(_ obj: [String: Any], frameBytes: Int) {
        let type = obj["t"] as? String
        switch type {
        case "snapshot-begin":
            // a second begin, a begin mid-session or a bad seq is a fence
            // violation: reject the whole snapshot, never part of it
            guard status == .connecting, snapshotSeq == nil,
                  let seq = strictNonNegativeInt64(obj["seq"]) else {
                structuralSnapshotFailure()
                return
            }
            snapshotSeq = seq
            snapshotRecords.removeAll()
            snapshotInvalid = false
            snapshotSawFinalPage = false
            snapshotAggregateBytes = frameBytes
            snapshotWireIds.removeAll()
            status = .snapshotting
            // seq below what we already saw: relay rolled back, or the room was
            // purged/wiped. Say it once per join and keep syncing, the replay
            // rules already reject anything older (contract section 5).
            if let rs = replayState, rs.lastSnapshotSeq >= 0, seq < rs.lastSnapshotSeq {
                snapshotSeqRegressed = true
                surface(.roomResetSuspected, scope: .join)
            }
        case "snapshot":
            snapshotAggregateBytes += frameBytes
            guard status == .snapshotting, snapshotSeq != nil, !snapshotSawFinalPage,
                  let items = obj["items"] as? [Any],
                  let more = strictJSONBoolean(obj["more"]),
                  snapshotRecords.count <= Self.maxSnapshotItems - items.count,
                  snapshotAggregateBytes <= Self.maxSnapshotAggregateBytes else {
                structuralSnapshotFailure()
                return
            }
            var page: [[String: Any]] = []
            page.reserveCapacity(items.count)
            for raw in items {
                guard let item = raw as? [String: Any],
                      let wireId = item["id"] as? String,
                      SyncIdentity.decodeCanonical32(wireId) != nil,
                      snapshotWireIds.insert(wireId).inserted else {
                    structuralSnapshotFailure()
                    return
                }
                page.append(item)
            }
            snapshotRecords.append(contentsOf: page)
            snapshotSawFinalPage = !more
        case "snapshot-end":
            snapshotAggregateBytes += frameBytes
            guard status == .snapshotting,
                  let expectedSeq = snapshotSeq,
                  let endSeq = strictNonNegativeInt64(obj["seq"]),
                  endSeq == expectedSeq,
                  snapshotSawFinalPage,
                  snapshotAggregateBytes <= Self.maxSnapshotAggregateBytes else {
                structuralSnapshotFailure()
                return
            }
            finishSnapshotV3(seq: endSeq)
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
            setOnlineMembers(onlineMemberTracker.remove(
                clientId: departure.actorId,
                sessionDomain: departure.sessionDomain
            ))
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

    /// Fence violation (contract 2.4): nothing from this snapshot is
    /// committed, say so (SECURITY, once per session), close and retry with
    /// transient backoff. Three in a row without a hello-ack parks sync.
    private func structuralSnapshotFailure() {
        guard let socket = task else { return }
        surface(.snapshotStructural, scope: .session)
        snapshotRecords.removeAll()
        snapshotWireIds.removeAll()
        endSession(socket: socket, cause: .local(.structuralSnapshot), issueReported: true)
    }

    /// Classification context for live records, on main. Collision checks
    /// and the reverse wire lookup are O(1) off the indexes, the layers are
    /// the committed ones plus whatever earlier records of the current live
    /// group adopted (same first-wins rule as a snapshot, S3-08).
    private func liveRecordContext() -> SnapshotRecordContext? {
        guard let keys = v3Keys, let roomKey, let rs = replayState else { return nil }
        let index = ensureWireIndex(keys)
        let model = modelIndex
        return SnapshotRecordContext(
            keys: keys,
            roomKey: roomKey,
            actorKeyIsAcceptable: { rs.actorKeyIsAcceptable($0, pubkey: $1) },
            layers: liveGroupStaging?.layers ?? drawingStore.layers,
            fallbackLayerID: fallbackLayerID,
            isWaypointID: { model.waypoints[$0] != nil },
            isDrawingID: { model.shapes[$0] != nil },
            localIdForWireId: { [weak self] in self?.findLocalIdForWireId($0) },
            wireIdForUUID: { index.wireId(for: $0) })
    }

    private var fallbackLayerID: UUID {
        drawingStore.activeLayerID ?? drawingStore.layers.first?.id ?? DrawingLayer.legacyFallbackID
    }

    @discardableResult
    private func ensureWireIndex(_ keys: SyncCrypto.V3RoomKeys) -> SyncWireIdIndex {
        if let wireIndex { return wireIndex }
        rebuildWireIndex(metadataKey: keys.metadataKey)
        return wireIndex!
    }

    /// snapshot-end (contract 19): per-record crypto, importer parse and the
    /// expected hashes run off main, in item order against the staged layers.
    /// Frames that arrive meanwhile wait in the inbound queue. Nothing is
    /// committed until the validated result is back on main.
    private func finishSnapshotV3(seq endSeq: Int64) {
        guard replayState != nil, v3Keys != nil, roomKey != nil, let socket = task else {
            failClosedV3(Messages.syncSyncSnapshotAuthenticationFailedMessage())
            return
        }
        // local validation and apply time never counts against the network
        handshakeWatchdog.snapshotEnded(atMs: scheduler.nowMs)
        armHandshakeTimer()
        let records = snapshotRecords
        snapshotRecords.removeAll()
        snapshotWireIds.removeAll()
        validateSnapshot(records, seq: endSeq, socket: socket, attempt: 0)
    }

    private final class SnapshotValidationBox {
        var result = SnapshotValidationResult()
    }

    private func validateSnapshot(_ records: [[String: Any]], seq: Int64, socket: SyncSocket, attempt: Int) {
        guard let rs = replayState, let keys = v3Keys, let roomKey else {
            failClosedV3(Messages.syncSyncSnapshotAuthenticationFailedMessage())
            return
        }
        let index = ensureWireIndex(keys)
        let ids = modelIndex.allIds
        let hashes = Dictionary(uniqueKeysWithValues: ids.compactMap { id in
            modelIndex.export(id).map { (id.uuidString, $0.hash) }
        })
        let generations = Dictionary(uniqueKeysWithValues: ids.map {
            ($0.uuidString, modelRevisionJournal?.generation($0.uuidString) ?? 0)
        })
        let input = SnapshotValidationInput(
            records: records,
            keys: keys,
            roomKey: roomKey,
            replay: rs.readView(),
            committedLayers: drawingStore.layers,
            fallbackLayerID: fallbackLayerID,
            waypointIDs: Set(modelIndex.waypoints.keys),
            modelHashes: hashes, modelGenerations: generations, modelEpoch: modelEpoch,
            drawingIDs: Set(modelIndex.shapes.keys),
            knownWireIds: index.forward,
            hasher: index.hasher)
        let generation = activeConnectionGeneration
        let box = SnapshotValidationBox()
        snapshotValidationInFlight = true
        offMainExecutor.execute({
            box.result = SnapshotValidator.validate(input)
        }) { [weak self, weak socket] in
            guard let self, let socket, self.task === socket,
                  self.activeConnectionGeneration == generation else { return }
            // The user edited a layer while we were validating: the staged
            // layers and expected hashes describe an old model. Validate again
            // against the current one rather than commit stale hashes (19).
            if self.modelEpoch != input.modelEpoch || self.drawingStore.layers != input.committedLayers
                || Set(self.modelIndex.waypoints.keys) != input.waypointIDs
                || Set(self.modelIndex.shapes.keys) != input.drawingIDs {
                self.validateSnapshot(records, seq: seq, socket: socket, attempt: attempt + 1)
                return
            }
            self.commitValidatedSnapshot(box.result, seq: seq)
        }
    }

    /// Back on main with every verdict (contract 19 + 1.3): re-resolve
    /// tombstone local ids against the live index, take prior hashes and
    /// journal generations now, then one commit write, one write per store,
    /// hash verification, one marker-clear write, and the hello.
    private func commitValidatedSnapshot(_ result: SnapshotValidationResult, seq endSeq: Int64) {
        guard let rs = replayState else {
            failClosedV3(Messages.syncSyncSnapshotAuthenticationFailedMessage())
            return
        }
        handshakeWatchdog.progress(atMs: scheduler.nowMs)
        let validated = result.validated.map { value -> ValidatedRecordV3 in
            guard value.parsed == nil else { return value }
            return ValidatedRecordV3(mutation: value.mutation, parsed: nil,
                                     localId: findLocalIdForWireId(value.mutation.wireObjectId),
                                     expectedModelHash: nil)
        }
        var unverified = result.unverified
        var unsupported = result.unsupported
        unverified.forEach { recordSkip($0, .unverified) }
        unsupported.forEach { recordSkip($0, .unsupported) }

        let remotes = validated.map {
            SyncReplayState.RemoteMutation(
                mutation: $0.mutation,
                priorModelHash: modelContentHash(localId: $0.localId),
                localModelId: $0.localId,
                acceptedGeneration: modelRevisionJournal?.generation($0.localId) ?? 0,
                expectedModelHash: $0.expectedModelHash)
        }
        rs.beginBatch()
        do { _ = try rs.commitRemoteSnapshot(remotes, seq: endSeq) }
        catch { snapshotPersistenceFailed(error); return }
        let generation = activeConnectionGeneration
        let token = joinToken
        rs.flushBatch(on: persistenceExecutor) { [weak self] error in
            guard let self, self.replayState === rs, self.joinToken == token,
                  self.activeConnectionGeneration == generation, self.task != nil,
                  self.presenceCadence.foregroundReady else { try? rs.endBatch(); return }
            if let error { self.snapshotPersistenceFailed(error); return }
            do {
                self.snapshotConfirmedLocalDeletes.removeAll()
                if let actorId = self.myActorId {
                    for value in validated where value.parsed == nil && value.mutation.stamp.actorId == actorId {
                        self.snapshotConfirmedLocalDeletes[value.mutation.wireObjectId] = value.mutation.stamp.encode()
                    }
                }
                // Read current hashes and generations after the seal. A local
                // edit made while it ran must win over the pending remote record.
                var outcome = try self.applyAcceptedRecords(validated.map {
                    ($0, self.modelContentHash(localId: $0.localId))
                })
                unsupported.append(contentsOf: outcome.unsupported)
                let resolved = Set(outcome.clears.map(\.wireObjectId))
                for remote in rs.pendingRemoteMutations() where !resolved.contains(remote.mutation.wireObjectId) {
                    if self.modelContentHash(localId: remote.localModelId) == remote.expectedModelHash {
                        self.markCurrentModelBaseline(localId: remote.localModelId)
                    } else if let localId = remote.localModelId { self.forcedLocalDiff.insert(localId) }
                    outcome.clears.append(remote.mutation)
                }
                try rs.clearPendingModelApplications(outcome.clears)
                rs.flushBatch(on: self.persistenceExecutor) { [weak self] error in
                    guard let self, self.replayState === rs, self.joinToken == token,
                          self.activeConnectionGeneration == generation, self.task != nil,
                          self.presenceCadence.foregroundReady else { try? rs.endBatch(); return }
                    if let error { self.snapshotPersistenceFailed(error); return }
                    do { try rs.endBatch() } catch { self.snapshotPersistenceFailed(error); return }
                    self.finishSnapshotCommit(seq: endSeq, unverified: unverified, unsupported: unsupported)
                }
            } catch { self.snapshotPersistenceFailed(error) }
        }
    }

    private func snapshotPersistenceFailed(_ error: Error) {
        if let error = error as? SyncRemoteModelMutationError {
            reportRemoteModelPersistenceFailure(error, remainsPending: true)
        }
        failClosedV3(pendingLastError ?? Messages.syncRollbackProtectionStateCouldNotBeSavedMessage())
    }

    private func finishSnapshotCommit(seq: Int64, unverified: [String], unsupported: [String]) {
        snapshotValidationInFlight = false
        snapshotSeq = nil
        snapshotRecords.removeAll()
        snapshotInvalid = false
        snapshotSawFinalPage = false
        snapshotAggregateBytes = 0
        snapshotWireIds.removeAll()
        let unverified = Array(Set(unverified))
        if !unverified.isEmpty { surface(.skippedUnverified, scope: .join, count: unverified.count) }
        if !unsupported.isEmpty { surface(.skippedUnsupported, scope: .join, count: Set(unsupported).count) }
        snapshotVerifiedClean = unverified.isEmpty && !snapshotSeqRegressed
        awaitingHelloAck = true
        // Remain snapshotting (and therefore outbound-gated) until the relay
        // confirms it has verified/persisted hello and attached the actor tuple.
        sendHelloV3()
        scheduleInboundDrain()
    }

    private enum RecordApplyError: Error {
        /// the model after apply is not what the verified record said it would be
        case hashMismatch
    }

    private struct RecordApplyOutcome {
        var clears: [SyncReplayState.DurableMutation] = []
        var unsupported: [String] = []
    }

    /// Shared by the snapshot and live batches: decide per record against its
    /// pending marker, apply every incoming record to the stores in one write
    /// per store, verify each expected hash, and return the markers that can
    /// now be cleared (one write by the caller). Throws on a persistence error
    /// or a hash mismatch, both fail closed with the markers still pending.
    private func applyAcceptedRecords(_ items: [(ValidatedRecordV3, String?)]) throws -> RecordApplyOutcome {
        guard let rs = replayState else { throw SyncReplayState.ReplayError.invalidState }
        resolvingPendingModel = true
        defer { resolvingPendingModel = false }
        var outcome = RecordApplyOutcome()
        var ops: [SyncRemoteModelApplier.BatchOp] = []
        var opValues: [ValidatedRecordV3] = []
        for (value, priorHash) in items {
            let mutation = value.mutation
            switch rs.pendingModelDecision(
                mutation,
                currentModelHash: priorHash,
                currentGeneration: modelRevisionJournal?.generation(value.localId) ?? 0) {
            case .applyIncoming:
                if let parsed = value.parsed {
                    ops.append(.upsert(parsed))
                    opValues.append(value)
                } else if let localId = value.localId {
                    ops.append(.delete(localId))
                    opValues.append(value)
                } else {
                    // tombstone for something we never had, nothing to remove
                    outcome.clears.append(mutation)
                }
            case .alreadyApplied:
                markModelBaseline(value)
                outcome.clears.append(mutation)
            case .localDiverged:
                if let localId = value.localId { forcedLocalDiff.insert(localId) }
                outcome.clears.append(mutation)
            case .none:
                if rs.isExactPersistedMutation(mutation) {
                    if priorHash == value.expectedModelHash { markModelBaseline(value) }
                    else if let localId = value.localId { forcedLocalDiff.insert(localId) }
                }
            }
        }
        guard !ops.isEmpty else { return outcome }
        let applied = try SyncRemoteModelApplier.applyBatch(
            ops, waypointStore: waypointStore, drawingStore: drawingStore)
        var lastMessage: String?
        for (index, value) in opValues.enumerated() {
            if applied.refused[index] != nil {
                // the classifier pre-checks both, this only catches a race with
                // the local stores. Unsupported, not fatal.
                outcome.unsupported.append(value.mutation.wireObjectId)
                recordSkip(value.mutation.wireObjectId, .unsupported)
                outcome.clears.append(value.mutation)
                continue
            }
            guard modelContentHash(localId: value.localId) == value.expectedModelHash else {
                throw RecordApplyError.hashMismatch
            }
            if let parsed = value.parsed, let localId = value.localId, let uuid = UUID(uuidString: localId) {
                let isWaypoint = !parsed.waypoints.isEmpty
                kindById[localId] = isWaypoint ? "waypoint" : "drawing"
                lastContent[localId] = reexport(id: localId)
                wireIndex?.wireId(for: uuid)
                lastMessage = isWaypoint
                    ? L10n.text("Waypoint updated by another device")
                    : L10n.text("Drawing updated by another device")
            } else {
                if let localId = value.localId {
                    lastContent[localId] = nil
                    kindById[localId] = nil
                }
                lastMessage = L10n.text("Object removed by another device")
            }
            outcome.clears.append(value.mutation)
        }
        // one toast per batch, not one per record
        if let lastMessage { remoteUpdateSubject.send(lastMessage) }
        return outcome
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
        cancelHandshakeWatchdog()
        // hello-ack is not proof the session works, backoff only resets on the
        // first op-ack or after 30 s connected (S3 verifier note 2)
        reconnectBackoff.connected(atMs: scheduler.nowMs)
        armStableSessionTimer()
        closeClassifier.connectionSucceeded()
        status = .connected
        pendingLastError = issueLifecycle.connectionSucceeded(
            generation: activeConnectionGeneration,
            verifiedCleanSnapshot: snapshotVerifiedClean
        )?.pendingMessage
        V3HelloAckWorkSequencer.run {
            presenceCadence.startAuthenticatedSession()
            presenceSendPolicy.startSession()
            startConnectionHealthChecks()
            sendPresence()
        } reconcileMissionState: {
            guard !joinState.mutationsPaused else { return }
            replayState?.recoverableLocalDeletes(actorId: actorId, pubkey: myPublicKey).forEach {
                if !shouldResendRecoverableDelete(
                    wireObjectId: $0.0,
                    stamp: $0.1,
                    confirmedSnapshotDeletes: snapshotConfirmedLocalDeletes
                ) { return }
                let localId = findLocalIdForWireId($0.0) ?? "wire:\($0.0)"
                queueDelV3(localId: localId, wireObjectId: $0.0, stamp: $0.1)
            }
            snapshotConfirmedLocalDeletes.removeAll()
            syncLocalStateV3()
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
            setOnlineMembers(onlineMemberTracker.authenticatedHello(
                clientId: by, sessionDomain: sd))
            refreshChatRecipients()
        } catch {
            failClosedV3(Messages.syncActorRollbackProtectionStateCouldNotBeSavedMessage())
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
        pendingChatSessionIssue = nil
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
        pendingChatSessionIssue = Messages.chatTheRelayRejectedEncryptedChatCapabilityMessage(code)
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
                ?? L10n.text("Unit %1$@", String(outer.senderActorId.suffix(6)).uppercased())
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
                let label = opened.payload.kind == .report ? L10n.text("report") : L10n.text("message")
                remoteUpdateSubject.send(L10n.text("New TacMap Chat %1$@ from %2$@.", label, senderName))
            case .rejectedReplayFull:
                // 256 live sessions, nothing superseded to prune: tell the
                // user once per join instead of dropping chat silently
                surface(.chatReplayFull, scope: .join)
            case .duplicate, .rejected:
                break
            }
        } catch let error as TacMapChatStore.StoreError {
            clearChatSessionSecrets()
            pendingChatSessionIssue = (error as? TacMapChatStore.StoreError)?.localizedMessage
                ?? (error as? TacMapChatCrypto.CryptoError)?.localizedMessage
                ?? .literal(error.localizedDescription)
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
            pendingChatSessionIssue = Messages.chatTheRoutedMessageStatusCouldNotBeSavedSecurelyMessage()
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
            pendingChatSessionIssue = Messages.chatTheUnroutedMessageStatusCouldNotBeSavedSecurelyMessage()
        }
    }

    private func strictJSONBoolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    private func applyLiveRecordV3(_ rec: [String: Any], deleted: Bool) {
        guard let rs = replayState, let context = liveRecordContext() else { return }
        let validated: ValidatedRecordV3
        switch SnapshotRecordClassifier.classify(rec, deleted: deleted, context: context) {
        case .valid(let value):
            validated = value
        case .skip(let category, _):
            // same verdicts as the snapshot, a live skip is surfaced once per join
            if let wireId = rec["id"] as? String, SyncIdentity.decodeCanonical32(wireId) != nil {
                recordSkip(wireId, category)
            }
            surface(category == .unverified ? .skippedUnverified : .skippedUnsupported, scope: .join, count: 1)
            return
        }
        switch rs.liveAcceptance(validated.mutation.wireObjectId, validated.mutation.stamp) {
        case .notNewer:
            return
        case .outsideWindow:
            // signed and newer, but past our own counter window: our baseline
            // is stale (relay expiry/compaction), go get a fresh snapshot
            liveWindowRejection()
            return
        case .accept:
            break
        }
        let priorHash = modelContentHash(localId: validated.localId)
        do {
            // Joins the logical batch, made durable before any model apply.
            // before anything touches the model
            guard try rs.commitRemoteBatch([.init(
                mutation: validated.mutation,
                priorModelHash: priorHash,
                localModelId: validated.localId,
                acceptedGeneration: modelRevisionJournal?.generation(validated.localId) ?? 0,
                expectedModelHash: validated.expectedModelHash)]).first == true else { return }
        } catch {
            failClosedV3(Messages.syncRollbackProtectionStateCouldNotBeSavedMessage())
            return
        }
        liveGroup.append(StagedLiveRecord(value: validated, priorHash: priorHash))
        liveGroupWireIds.insert(validated.mutation.wireObjectId)
        if let parsed = validated.parsed {
            var staging = liveGroupStaging ?? SnapshotLayerStaging(committed: drawingStore.layers)
            staging.adopt(parsed.newLayers)
            liveGroupStaging = staging
        }
    }

    // MARK: Live counter-window resync (contract section 4)

    private func liveWindowRejection() {
        if joinState.liveWindowResync.windowRejection(nowMs: scheduler.nowMs) {
            triggerLiveWindowResync()
        } else {
            scheduleLiveWindowResyncTick()
        }
    }

    private func scheduleLiveWindowResyncTick() {
        guard liveWindowResyncTimer == nil, let at = joinState.liveWindowResync.nextEligibleMs() else { return }
        liveWindowResyncTimer = scheduler.schedule(afterMs: max(0, at - scheduler.nowMs)) { [weak self] in
            guard let self else { return }
            self.liveWindowResyncTimer = nil
            if self.joinState.liveWindowResync.tick(nowMs: self.scheduler.nowMs) {
                self.triggerLiveWindowResync()
            } else {
                self.scheduleLiveWindowResyncTick()
            }
        }
    }

    private func triggerLiveWindowResync() {
        guard let socket = task, status == .connected else { return }
        endSession(socket: socket, cause: .local(.liveWindowResync), issueReported: true)
    }

    private func armStableSessionTimer() {
        stableSessionTimer?.cancel()
        stableSessionTimer = scheduler.schedule(afterMs: SyncBackoffPolicy.stableSessionMs) { [weak self] in
            guard let self else { return }
            self.stableSessionTimer = nil
            self.reconnectBackoff.tick(nowMs: self.scheduler.nowMs)
        }
    }

    private func modelContentHash(localId: String?) -> String? {
        guard let localId, let uuid = UUID(uuidString: localId) else { return nil }
        return modelIndex.export(uuid)?.hash
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

    private func markCurrentModelBaseline(localId: String?) {
        guard let localId else { return }
        let content = reexport(id: localId)
        if content.isEmpty {
            lastContent[localId] = nil; kindById[localId] = nil
        } else {
            lastContent[localId] = content
            kindById[localId] = UUID(uuidString: localId).flatMap(modelIndex.kind(of:))?.rawValue ?? "drawing"
        }
        forcedLocalDiff.remove(localId)
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
            // 17.1: writes (all counters, one write) only when this counter is
            // 16+ past the persisted one, before the peer is shown
            guard try rs.acceptPresence(
                actorId: actorId, sessionDomain: sdString, counter: stamp.counter) else { return }
        } catch {
            failClosedV3(Messages.syncPresenceReplayStateCouldNotBeSavedMessage())
            return
        }
        schedulePresenceFlush()

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
        setOnlineMembers(onlineMemberTracker.updatePresenceMetadata(
            clientId: actorId,
            sessionDomain: sdString,
            callsign: presence.callsign,
            affiliation: presence.affiliation,
            echelon: presence.echelon,
            function: presence.function,
            isHQ: presence.isHQ,
            now: now
        ))
        refreshChatRecipients()
    }

    /// 17.1: counters that are only in memory go to disk at most once per
    /// 60 s, measured from the last replay write of any kind (every write
    /// carries all counters).
    private func schedulePresenceFlush() {
        guard presenceFlushTimer == nil, replayState?.presenceCountersDirty == true else { return }
        let busy = inboundPersistenceInFlight || snapshotValidationInFlight || outboundPersistenceInFlight
        let due = max(busy ? 1_000 : 0, lastReplayDurableMs + PresenceFencePersistence.flushMs - scheduler.nowMs)
        presenceFlushTimer = scheduler.schedule(afterMs: due) { [weak self] in
            guard let self else { return }
            self.presenceFlushTimer = nil
            guard self.presenceCadence.foregroundReady, let rs = self.replayState,
                  rs.presenceCountersDirty else { return }
            if self.scheduler.nowMs - self.lastReplayDurableMs < PresenceFencePersistence.flushMs {
                // something else wrote meanwhile and took the counters along
                self.schedulePresenceFlush()
                return
            }
            guard !self.inboundPersistenceInFlight, !self.snapshotValidationInFlight,
                  !self.outboundPersistenceInFlight else { self.schedulePresenceFlush(); return }
            self.inboundPersistenceInFlight = true
            let generation = self.activeConnectionGeneration
            rs.flushPresenceCounters(on: self.persistenceExecutor) { [weak self] error in
                guard let self, self.replayState === rs, self.activeConnectionGeneration == generation else { return }
                self.inboundPersistenceInFlight = false
                if error != nil { self.failClosedV3(Messages.syncPresenceReplayStateCouldNotBeSavedMessage()) }
                else { self.scheduleInboundDrain() }
            }
        }
    }

    private func strictNonNegativeInt64(_ value: Any?) -> Int64? {
        Self.strictJSONInteger(value, minimum: 0, maximum: Int64.max)
    }

    /// Reverse lookup through the room's wire-id index, O(1). A miss means no
    /// local object, there is no fallback scan (contract 18, S5-02). Same
    /// answer set as before: a current store object or one we still hold a
    /// sync baseline for.
    private func findLocalIdForWireId(_ wireId: String) -> String? {
        guard let uuid = wireIndex?.uuid(forWireId: wireId) else { return nil }
        let id = uuid.uuidString
        return modelIndex.contains(uuid) || lastContent[id] != nil ? id : nil
    }
}
