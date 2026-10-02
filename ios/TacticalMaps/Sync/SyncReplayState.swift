import Foundation

/// Durable per-room replay state for v3. Security decisions are committed to
/// the sealed store before callers are allowed to mutate application models or
/// put a frame on the wire. A persistence error therefore fails closed.
final class SyncReplayState {
    static let advanceWindow: Int64 = 10_000
    typealias PersistenceWriter = (Data, URL, String) throws -> Void

    enum MutationKind {
        case put(contentHash: String)
        case delete
    }

    struct DurableMutation {
        let wireObjectId: String
        let stamp: VersionStamp
        let publicKey: String
        let kind: MutationKind
    }

    /// A nil prior hash means the object was absent before remote acceptance.
    struct RemoteMutation {
        let mutation: DurableMutation
        let priorModelHash: String?
        let localModelId: String?
        let acceptedGeneration: Int64
        let expectedModelHash: String?

        init(mutation: DurableMutation, priorModelHash: String?, localModelId: String? = nil,
             acceptedGeneration: Int64 = 0, expectedModelHash: String? = nil) {
            self.mutation = mutation
            self.priorModelHash = priorModelHash
            self.localModelId = localModelId
            self.acceptedGeneration = acceptedGeneration
            if let expectedModelHash { self.expectedModelHash = expectedModelHash }
            else if case .put(let hash) = mutation.kind { self.expectedModelHash = hash }
            else { self.expectedModelHash = nil }
        }
    }

    enum PendingModelDecision: Equatable { case none, applyIncoming, alreadyApplied, localDiverged }

    enum SnapshotMutationResult: Equatable {
        case newlyPersisted
        case exactAlreadyPersisted
        case conflictOrStale

        var shouldApplyToModel: Bool {
            self == .newlyPersisted || self == .exactAlreadyPersisted
        }
    }

    /// One outbound object for a diff pass. A recovery stamp means the exact
    /// write is already durable, so nothing new gets reserved for it.
    struct LocalReservation {
        let wireObjectId: String
        let kind: MutationKind
        let recoveryStamp: VersionStamp?
    }

    private(set) var localCounter: Int64 = 0
    private(set) var lastSnapshotSeq: Int64 = -1

    private var stamps: [String: VersionStamp] = [:]
    private var tombstones: [String: VersionStamp] = [:]
    private var contentHashes: [String: String] = [:]
    private var actors: [String: String] = [:]
    private var helloEpochs: [String: String] = [:]
    private var pendingModelApplications: [String: RemoteMutation] = [:]

    // The last authenticated live-session tuple for each remote actor. These
    // values are durable so reconnecting this client can safely reactivate the
    // relay's current (equal-epoch) hello without accepting a different session
    // at that epoch, and so a replayed presence counter stays rejected.
    private var presenceSeq: [String: Int64] = [:]
    private var sessionDomains: [String: String] = [:]
    // Chat pruning may run while a replacement hello is staged. Only a
    // successfully sealed session can retire another store's replay fence.
    private var durableSessionDomains: [String: String] = [:]

    /// Running max of every stamp/tombstone counter ever applied. Stamps only
    /// ever get replaced by higher ones, so this never has to rescan (S5 note 4).
    private var maxStampCounter: Int64 = 0

    /// Contract 17.1. false = the presence counters on disk may lag the ones we
    /// accepted by up to stride-1, so a reload floors them at persisted + 15.
    private(set) var presenceFenceExact = true
    private struct PersistedPresence: Equatable {
        let sessionDomain: String?
        let counter: Int64
    }
    /// presence counters as of the last durable write
    private var persistedPresence: [String: PersistedPresence] = [:]

    // Undo log instead of a deep copy of every map per transaction. Holds the
    // prior value of everything touched since the last durable write; a failed
    // write rolls all of it back so memory never runs ahead of disk.
    private enum Undo {
        case localCounter(Int64)
        case lastSnapshotSeq(Int64)
        case maxStampCounter(Int64)
        case stamp(String, VersionStamp?)
        case tombstone(String, VersionStamp?)
        case contentHash(String, String?)
        case actor(String, String?)
        case helloEpoch(String, String?)
        case pending(String, RemoteMutation?)
        case presenceSeq(String, Int64?)
        case sessionDomain(String, String?)
        case presenceFenceExact(Bool)
    }
    private var undoLog: [Undo] = []
    private var batchDepth = 0

    /// Bumped after every durable write, tests and the presence flush use it.
    private(set) var durableWriteCount = 0
    var onDurableWrite: (() -> Void)?

    let roomId: String
    private let containerURL: URL?
    private let persistenceWriter: PersistenceWriter

    init(
        roomId: String,
        containerURL: URL? = nil,
        persistenceWriter: @escaping PersistenceWriter = { data, url, label in
            try SyncPersistenceExecutor.write(data, to: url, label: label)
        }
    ) {
        self.roomId = roomId
        self.containerURL = containerURL
        self.persistenceWriter = persistenceWriter
    }

    func canAccept(_ wireObjectId: String, _ incoming: VersionStamp, enforceWindow: Bool = true) -> Bool {
        Self.canAccept(wireObjectId, incoming, enforceWindow: enforceWindow,
                       highWater: roomHighWater(), stamps: stamps, tombstones: tombstones)
    }

    private static func canAccept(
        _ wireObjectId: String, _ incoming: VersionStamp, enforceWindow: Bool,
        highWater: Int64, stamps: [String: VersionStamp], tombstones: [String: VersionStamp]
    ) -> Bool {
        if enforceWindow && incoming.counter > highWater && incoming.counter - highWater > advanceWindow { return false }
        if let existing = stamps[wireObjectId], incoming <= existing { return false }
        if let tomb = tombstones[wireObjectId], incoming <= tomb { return false }
        return true
    }

    /// Compatibility helpers used by focused replay tests. Production uses the
    /// throwing commit methods below so persistence is part of acceptance.
    func advance(_ wireObjectId: String, _ incoming: VersionStamp) -> Bool {
        guard canAccept(wireObjectId, incoming) else { return false }
        setStamp(wireObjectId, incoming)
        setTombstone(wireObjectId, nil)
        setLocalCounter(max(localCounter, incoming.counter))
        return true
    }

    func tombstone(_ wireObjectId: String, _ incoming: VersionStamp) -> Bool {
        guard canAccept(wireObjectId, incoming) else { return false }
        setStamp(wireObjectId, incoming)
        setTombstone(wireObjectId, incoming)
        setContentHash(wireObjectId, nil)
        setLocalCounter(max(localCounter, incoming.counter))
        return true
    }

    enum LiveAcceptance: Equatable {
        case accept
        /// does not beat what we already hold, drop it quietly
        case notNewer
        /// newer, but its counter is past roomHighWater + ADVANCE_WINDOW
        case outsideWindow
    }

    /// Splits the live check so a window rejection can trigger a resync
    /// instead of a silent drop. Acceptance itself is unchanged.
    func liveAcceptance(_ wireObjectId: String, _ incoming: VersionStamp) -> LiveAcceptance {
        guard canAccept(wireObjectId, incoming, enforceWindow: false) else { return .notNewer }
        return canAccept(wireObjectId, incoming, enforceWindow: true) ? .accept : .outsideWindow
    }

    func isTombstoned(_ wireObjectId: String) -> Bool { tombstones[wireObjectId] != nil }
    func getStamp(_ wireObjectId: String) -> VersionStamp? { stamps[wireObjectId] }
    func getContentHash(_ wireObjectId: String) -> String? { contentHashes[wireObjectId] }
    func getPinnedPubkey(_ actorId: String) -> String? { actors[actorId] }

    func recoverableLocalPut(wireObjectId: String, actorId: String, pubkey: String, contentHash: String) -> VersionStamp? {
        guard let stamp = stamps[wireObjectId], stamp.actorId == actorId,
              actors[actorId] == pubkey, tombstones[wireObjectId] == nil,
              contentHashes[wireObjectId] == contentHash else { return nil }
        return stamp
    }

    func recoverableLocalDeletes(actorId: String, pubkey: String) -> [(String, VersionStamp)] {
        tombstones.compactMap { id, tomb in
            guard tomb.actorId == actorId, actors[actorId] == pubkey,
                  stamps[id] == tomb, contentHashes[id] == nil else { return nil }
            return (id, tomb)
        }
    }

    func actorKeyIsAcceptable(_ actorId: String, pubkey: String) -> Bool {
        actors[actorId].map { $0 == pubkey } ?? true
    }

    func registerActor(_ actorId: String, pubkey: String) -> Bool {
        guard actorKeyIsAcceptable(actorId, pubkey: pubkey) else { return false }
        setActor(actorId, pubkey)
        return true
    }

    /// Persist the verified actor pin, then activate its signed session domain.
    /// Inside an inbound batch this joins the batch's single write (contract 17).
    @discardableResult
    func acceptHello(actorId: String, pubkey: String, sessionDomain: String, epochHex: String) throws -> Bool {
        guard actorKeyIsAcceptable(actorId, pubkey: pubkey) else { throw ReplayError.actorKeyMismatch }
        guard SyncIdentity.parseHelloEpoch(epochHex) != nil else { throw ReplayError.invalidState }
        if let oldEpoch = helloEpochs[actorId] {
            if epochHex < oldEpoch { return false }
            if epochHex == oldEpoch {
                return actors[actorId] == pubkey && sessionDomains[actorId] == sessionDomain
            }
        }
        setActor(actorId, pubkey)
        setHelloEpoch(actorId, epochHex)
        if sessionDomains[actorId] != sessionDomain { setPresenceSeq(actorId, nil) }
        setSessionDomain(actorId, sessionDomain)
        try persist()
        return true
    }

    /// Atomically pin the room-bound local signing key and reserve its next
    /// hello epoch. A cold restart must never observe an epoch without the
    /// actor record required to authenticate it.
    ///
    /// floor lets the caller skip ahead (unix-minute floor after state loss,
    /// doubling after a 4014, contract section 14). spare reserves that many
    /// extra epochs on top so later background reconnects never write. The
    /// epoch stays strictly increasing either way, which is all replay
    /// protection needs.
    func reserveHelloEpoch(actorId: String, pubkey: String, floor: UInt64 = 0, spare: UInt64 = 0,
                           deferPersistence: Bool = false) throws -> String {
        guard localActorBindingIsValid(actorId: actorId, pubkey: pubkey) else {
            throw ReplayError.invalidState
        }
        guard actorKeyIsAcceptable(actorId, pubkey: pubkey) else {
            throw ReplayError.actorKeyMismatch
        }
        let current = helloEpochs[actorId].flatMap { UInt64($0, radix: 16) } ?? 0
        guard current < UInt64.max else { throw ReplayError.counterExhausted }
        let next = max(current + 1, floor)
        let (persistedValue, overflow) = next.addingReportingOverflow(spare)
        guard !overflow else { throw ReplayError.counterExhausted }
        setActor(actorId, pubkey)
        setHelloEpoch(actorId, String(format: "%016llx", persistedValue))
        // Deferred manager calls flush before signing; direct callers persist.
        if deferPersistence { try persist() } else { try persistNow() }
        return String(format: "%016llx", next)
    }

    func getHelloEpoch(_ actorId: String) -> String? { helloEpochs[actorId] }

    func activeSessionDomain(_ actorId: String) -> String? { sessionDomains[actorId] }
    func durableSessionDomain(_ actorId: String) -> String? { durableSessionDomains[actorId] }

    /// Called only after actor binding, AEAD, signature and payload validation.
    /// Contract 17.1: a counter 16+ above the persisted one (or the first one
    /// after an exact clean-point write) is written before the caller may show
    /// the peer. Anything closer is covered by the crash floor and only goes
    /// out with the next write or the 60 s flush.
    func acceptPresence(actorId: String, sessionDomain: String, counter: Int64) throws -> Bool {
        guard sessionDomains[actorId] == sessionDomain, counter > 0 else { return false }
        let existing = presenceSeq[actorId] ?? 0
        guard counter > existing, counter - existing <= Self.advanceWindow else { return false }
        let persisted = persistedPresence[actorId].flatMap {
            $0.sessionDomain == sessionDomain ? $0.counter : nil
        } ?? 0
        let mustWrite = PresenceFencePersistence.mustPersistBeforeExposing(
            counter: counter, persisted: persisted, lastWriteWasExact: presenceFenceExact)
        if presenceFenceExact { setPresenceFenceExact(false) }
        // Not in the undo log on purpose: a failed write of something else
        // must never move an accepted counter back down in memory, that would
        // let the relay replay a position we already showed. A failed forced
        // write below fails the session closed anyway.
        presenceSeq[actorId] = counter
        if mustWrite { try persist() }
        return true
    }

    @MainActor
    func writeCleanPresenceFence(on executor: SyncOffMainExecutor, completion: @escaping (Error?) -> Void = { _ in }) {
        afterPendingPersistence {
            guard !self.presenceFenceExact || self.presenceCountersDirty || self.hasUnflushedChanges else {
                completion(nil); return
            }
            self.setPresenceFenceExact(true)
            self.batchNeedsWrite = true
            self.flushBatch(on: executor, completion: completion)
        }
    }

    @MainActor
    func flushPresenceCounters(on executor: SyncOffMainExecutor, completion: @escaping (Error?) -> Void) {
        guard presenceCountersDirty else { completion(nil); return }
        batchNeedsWrite = true
        flushBatch(on: executor, completion: completion)
    }

    /// Any accepted counter not on disk yet. Drives the 60 s flush.
    var presenceCountersDirty: Bool {
        if presenceSeq.count != persistedPresence.count { return true }
        for (actor, counter) in presenceSeq where persistedPresence[actor]?.counter != counter {
            return true
        }
        return false
    }

    /// The 60 s flush. Writes nothing when every counter is already on disk.
    func flushPresenceCounters() throws {
        guard presenceCountersDirty else { return }
        try persistNow()
    }

    /// Clean point (leave, background entry, disposal): exact counters with
    /// the flag set, so the next load does not need the crash floor.
    func writeCleanPresenceFence() throws {
        guard !presenceFenceExact || presenceCountersDirty || !undoLog.isEmpty else { return }
        setPresenceFenceExact(true)
        try persistNow()
    }

    /// Reserve and persist a durable counter before constructing/sending a
    /// local mutation. A failed crypto operation merely wastes the value.
    func reserveNextCounter() throws -> Int64 {
        guard localCounter < VersionStamp.maxCounter else { throw ReplayError.counterExhausted }
        setLocalCounter(localCounter + 1)
        try persistNow()
        return localCounter
    }

    /// Every stamp of one outbound diff pass in one durable write (contract
    /// 11.2 + 17). Counter and mutation land together, so a crash before the
    /// frame is sent leaves an exact record that recoverableLocalPut resends.
    /// nil for an entry means it could not be reserved (counter wasted).
    func reserveLocalMutations(
        _ requests: [LocalReservation],
        actorId: String,
        pubkey: String,
        deferPersistence: Bool = false
    ) throws -> [VersionStamp?] {
        var stampsOut: [VersionStamp?] = []
        stampsOut.reserveCapacity(requests.count)
        for request in requests {
            if let recovery = request.recoveryStamp, recovery.actorId == actorId {
                stampsOut.append(recovery)
                continue
            }
            guard localCounter < VersionStamp.maxCounter else {
                rollbackToDurable()
                throw ReplayError.counterExhausted
            }
            setLocalCounter(localCounter + 1)
            let stamp = VersionStamp(counter: localCounter, actorId: actorId)
            let mutation = DurableMutation(
                wireObjectId: request.wireObjectId, stamp: stamp, publicKey: pubkey, kind: request.kind)
            guard validMutation(mutation), actorKeyIsAcceptable(actorId, pubkey: pubkey),
                  canAccept(request.wireObjectId, stamp) else {
                stampsOut.append(nil)
                continue
            }
            apply(mutation)
            stampsOut.append(stamp)
        }
        // a pass that only resends recovery stamps reserves nothing
        if !undoLog.isEmpty {
            if deferPersistence { try persist() } else { try persistNow() }
        }
        return stampsOut
    }

    /// Commit a verified live or outbound mutation atomically with its actor
    /// pin and content/tombstone metadata.
    @discardableResult
    func commit(_ mutation: DurableMutation, enforceWindow: Bool = true) throws -> Bool {
        guard validMutation(mutation), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey),
              canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: enforceWindow) else { return false }
        apply(mutation)
        try persistNow()
        return true
    }

    /// Atomically accept a verified remote mutation and record model work.
    func commitRemote(_ remote: RemoteMutation, enforceWindow: Bool = true) throws -> Bool {
        let accepted = try commitRemoteBatch([remote], enforceWindow: enforceWindow)
        return accepted.first ?? false
    }

    /// Live records of one inbound batch (contract 1.3 + 17): every accepted
    /// record and its pending marker in one replay transaction. Inside an open
    /// batch the write waits for flushBatch(), which the manager calls before
    /// it touches the model.
    func commitRemoteBatch(_ remotes: [RemoteMutation], enforceWindow: Bool = true) throws -> [Bool] {
        var accepted: [Bool] = []
        accepted.reserveCapacity(remotes.count)
        for remote in remotes {
            let mutation = remote.mutation
            guard validRemote(remote), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey),
                  canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: enforceWindow) else {
                accepted.append(false)
                continue
            }
            apply(mutation)
            setPending(mutation.wireObjectId, remote)
            accepted.append(true)
        }
        if accepted.contains(true) { try persist() }
        return accepted
    }

    /// Apply an authenticated snapshot as one durable transaction. The caller
    /// validates every record first; stale records are ignored individually,
    /// while the authenticated maximum still raises the next local counter.
    func commitSnapshot(_ mutations: [DurableMutation], seq: Int64) throws -> [SnapshotMutationResult] {
        var accepted = [SnapshotMutationResult]()
        accepted.reserveCapacity(mutations.count)
        for mutation in mutations {
            guard validMutation(mutation), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey) else {
                rollbackToDurable()
                throw ReplayError.actorKeyMismatch
            }
            setLocalCounter(max(localCounter, mutation.stamp.counter))
            if isExactPersistedMutation(mutation) {
                accepted.append(.exactAlreadyPersisted)
            } else if canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: false) {
                apply(mutation)
                accepted.append(.newlyPersisted)
            } else {
                accepted.append(.conflictOrStale)
            }
        }
        setLastSnapshotSeq(max(lastSnapshotSeq, seq))
        try persist()
        return accepted
    }

    /// Snapshot transaction that records pending model work only for newly
    /// accepted mutations. Exact records retain an existing matching marker;
    /// without one, they were already resolved and cannot repair the model.
    func commitRemoteSnapshot(_ remotes: [RemoteMutation], seq: Int64) throws -> [SnapshotMutationResult] {
        var results: [SnapshotMutationResult] = []
        results.reserveCapacity(remotes.count)
        for remote in remotes {
            let mutation = remote.mutation
            guard validRemote(remote), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey) else {
                rollbackToDurable()
                throw ReplayError.actorKeyMismatch
            }
            setLocalCounter(max(localCounter, mutation.stamp.counter))
            if isExactPersistedMutation(mutation) {
                let hasMatchingPending = pendingModelApplications[mutation.wireObjectId]
                    .map { Self.mutationsEqual($0.mutation, mutation) } ?? false
                results.append(hasMatchingPending ? .exactAlreadyPersisted : .conflictOrStale)
            } else if canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: false) {
                apply(mutation)
                setPending(mutation.wireObjectId, remote)
                results.append(.newlyPersisted)
            } else {
                results.append(.conflictOrStale)
            }
        }
        setLastSnapshotSeq(max(lastSnapshotSeq, seq))
        try persist()
        return results
    }

    func hasPendingModelApplications() -> Bool { !pendingModelApplications.isEmpty }
    func pendingRemoteMutations() -> [RemoteMutation] { Array(pendingModelApplications.values) }

    func pendingModelDecision(_ mutation: DurableMutation, currentModelHash: String?,
                              currentGeneration: Int64 = 0) -> PendingModelDecision {
        guard let pending = pendingModelApplications[mutation.wireObjectId],
              Self.mutationsEqual(pending.mutation, mutation) else { return .none }
        let incomingHash = pending.expectedModelHash
        if currentModelHash == incomingHash { return .alreadyApplied }
        if currentGeneration != pending.acceptedGeneration { return .localDiverged }
        if currentModelHash == pending.priorModelHash { return .applyIncoming }
        return .localDiverged
    }

    func clearPendingModelApplication(_ mutation: DurableMutation) throws -> Bool {
        try clearPendingModelApplications([mutation]) == 1
    }

    /// Every resolved marker of a batch in one write (contract 1.3).
    @discardableResult
    func clearPendingModelApplications(_ mutations: [DurableMutation]) throws -> Int {
        var cleared = 0
        for mutation in mutations {
            guard let pending = pendingModelApplications[mutation.wireObjectId],
                  Self.mutationsEqual(pending.mutation, mutation) else { continue }
            setPending(mutation.wireObjectId, nil)
            cleared += 1
        }
        if cleared > 0 { try persist() }
        return cleared
    }

    // MARK: Batches (contract section 1)

    /// Open an inbound batch. Until the matching endBatch, deferred writes
    /// (hellos, live record commits, marker clears) collect into one write.
    /// Forced writes (reservations, presence stride, clean points) still go
    /// out immediately and take everything collected so far with them.
    func beginBatch() { batchDepth += 1 }

    var isBatching: Bool { batchDepth > 0 }
    private var batchNeedsWrite = false
    var hasUnflushedChanges: Bool { !undoLog.isEmpty || batchNeedsWrite }

    /// Close the batch, writing once if anything changed.
    func endBatch() throws {
        guard batchDepth > 0 else { return }
        batchDepth -= 1
        if batchDepth == 0, hasUnflushedChanges { try writeNow() }
    }

    /// Write what the open batch collected so far (before a model apply or
    /// anything that sends). No-op when nothing changed.
    func flushBatch() throws {
        guard hasUnflushedChanges else { return }
        try writeNow()
    }

    private var persistenceInFlight = false
    private var pendingPersistenceActions: [@MainActor () -> Void] = []

    @MainActor
    private func afterPendingPersistence(_ work: @escaping @MainActor () -> Void) {
        if persistenceInFlight { pendingPersistenceActions.append(work) }
        else { work() }
    }

    private final class PersistenceResult {
        var error: Error?
    }

    /// Capture on the protocol worker, encode and seal on the persistence
    /// worker, then finish durability on main before the manager publishes.
    @MainActor
    func flushBatch(on executor: SyncOffMainExecutor, completion: @escaping (Error?) -> Void) {
        if persistenceInFlight {
            pendingPersistenceActions.append { self.flushBatch(on: executor, completion: completion) }
            return
        }
        guard hasUnflushedChanges else { completion(nil); return }
        do {
            guard let url = try resolvedFileURL() else { didPersist(); completion(nil); return }
            let object = serializedObject()
            let writer = persistenceWriter
            let label = storeLabel
            let result = PersistenceResult()
            persistenceInFlight = true
            executor.execute({
                do {
                    let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
                    try writer(data, url, label)
                } catch { result.error = error }
            }) {
                if result.error != nil { self.rollbackToDurable() }
                else { self.didPersist() }
                self.persistenceInFlight = false
                completion(result.error)
                while !self.persistenceInFlight, !self.pendingPersistenceActions.isEmpty {
                    let next = self.pendingPersistenceActions.removeFirst()
                    next()
                }
            }
        } catch {
            rollbackToDurable()
            completion(error)
        }
    }

    /// Read-only copy for the off-main snapshot validator. Swift collections
    /// are copy-on-write, so this is O(1) here and the worker never races us.
    struct ReadView {
        fileprivate let stamps: [String: VersionStamp]
        fileprivate let tombstones: [String: VersionStamp]
        fileprivate let contentHashes: [String: String]
        fileprivate let actors: [String: String]
        fileprivate let pending: [String: RemoteMutation]
        fileprivate let highWater: Int64

        func actorKeyIsAcceptable(_ actorId: String, pubkey: String) -> Bool {
            actors[actorId].map { $0 == pubkey } ?? true
        }

        func canAcceptIgnoringWindow(_ wireObjectId: String, _ incoming: VersionStamp) -> Bool {
            SyncReplayState.canAccept(wireObjectId, incoming, enforceWindow: false,
                                      highWater: highWater, stamps: stamps, tombstones: tombstones)
        }

        func willApplyExact(_ mutation: DurableMutation, currentHash: String?, generation: Int64) -> Bool {
            guard let remote = pending[mutation.wireObjectId], SyncReplayState.mutationsEqual(remote.mutation, mutation) else { return false }
            return currentHash != remote.expectedModelHash && generation == remote.acceptedGeneration
                && currentHash == remote.priorModelHash
        }

        func isExactPersistedMutation(_ mutation: DurableMutation) -> Bool {
            SyncReplayState.isExact(mutation, stamps: stamps, tombstones: tombstones,
                                    contentHashes: contentHashes, actors: actors)
        }
    }

    func readView() -> ReadView {
        ReadView(stamps: stamps, tombstones: tombstones, contentHashes: contentHashes,
                 actors: actors, pending: pendingModelApplications, highWater: roomHighWater())
    }

    func save() throws { try persistNow() }

    @discardableResult
    func load() -> Bool {
        load(repairingLocalActor: nil)
    }

    /// Production load supplies the current local room-scoped identity so a
    /// narrowly identified state written by the former epoch-only reservation
    /// bug can be repaired before the manager connects.
    @discardableResult
    func load(localActorId: String, publicKey: String) -> Bool {
        load(repairingLocalActor: (actorId: localActorId, publicKey: publicKey))
    }

    private func load(repairingLocalActor localActor: (actorId: String, publicKey: String)?) -> Bool {
        if let localActor,
           !localActorBindingIsValid(actorId: localActor.actorId, pubkey: localActor.publicKey) {
            return false
        }
        let url: URL
        do {
            guard let resolved = try resolvedFileURL() else { return true }
            url = resolved
        } catch {
            return false
        }
        let result = SafeStore.read(url, label: storeLabel) { data -> [String: Any] in
            guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                throw ReplayError.invalidState
            }
            return value
        }
        switch result {
        case .empty:
            return true
        case .loaded(let dict):
            let old = snapshot()
            if decode(dict) {
                guard let localActor else { return true }
                if let pinned = actors[localActor.actorId], pinned != localActor.publicKey {
                    restore(old)
                    return false
                }
                return true
            }
            restore(old)
            guard let localActor else { return false }
            return repairOrphanedLocalHelloEpoch(
                dict,
                actorId: localActor.actorId,
                publicKey: localActor.publicKey
            )
        case .locked, .corrupt:
            return false
        }
    }

    func clear() {
        localCounter = 0
        lastSnapshotSeq = -1
        stamps.removeAll(); tombstones.removeAll(); contentHashes.removeAll(); actors.removeAll(); helloEpochs.removeAll()
        pendingModelApplications.removeAll()
        presenceSeq.removeAll(); sessionDomains.removeAll(); durableSessionDomains.removeAll()
        maxStampCounter = 0
        presenceFenceExact = true
        persistedPresence.removeAll()
        undoLog.removeAll()
        batchDepth = 0
        if let url = try? resolvedFileURL() {
            try? FileManager.default.removeItem(at: url)
            try? FileManager.default.removeItem(at: url.appendingPathExtension("sealed-only"))
        }
    }

    enum ReplayError: Error {
        case invalidState
        case actorKeyMismatch
        case counterExhausted
    }

    // MARK: - Internals

    /// Full copy, only used around load/repair where a whole document gets
    /// swapped in. Normal transactions use the undo log.
    private struct StateSnapshot {
        let localCounter: Int64
        let lastSnapshotSeq: Int64
        let stamps: [String: VersionStamp]
        let tombstones: [String: VersionStamp]
        let contentHashes: [String: String]
        let actors: [String: String]
        let helloEpochs: [String: String]
        let pendingModelApplications: [String: RemoteMutation]
        let presenceSeq: [String: Int64]
        let sessionDomains: [String: String]
        let durableSessionDomains: [String: String]
        let maxStampCounter: Int64
        let presenceFenceExact: Bool
        let persistedPresence: [String: PersistedPresence]
    }

    private func snapshot() -> StateSnapshot {
        StateSnapshot(localCounter: localCounter, lastSnapshotSeq: lastSnapshotSeq,
                      stamps: stamps, tombstones: tombstones,
                      contentHashes: contentHashes, actors: actors, helloEpochs: helloEpochs,
                      pendingModelApplications: pendingModelApplications,
                      presenceSeq: presenceSeq, sessionDomains: sessionDomains,
                      durableSessionDomains: durableSessionDomains,
                      maxStampCounter: maxStampCounter, presenceFenceExact: presenceFenceExact,
                      persistedPresence: persistedPresence)
    }

    private func restore(_ old: StateSnapshot) {
        localCounter = old.localCounter
        lastSnapshotSeq = old.lastSnapshotSeq
        stamps = old.stamps
        tombstones = old.tombstones
        contentHashes = old.contentHashes
        actors = old.actors
        helloEpochs = old.helloEpochs
        pendingModelApplications = old.pendingModelApplications
        presenceSeq = old.presenceSeq
        sessionDomains = old.sessionDomains
        durableSessionDomains = old.durableSessionDomains
        maxStampCounter = old.maxStampCounter
        presenceFenceExact = old.presenceFenceExact
        persistedPresence = old.persistedPresence
        undoLog.removeAll()
    }

    // MARK: logged setters

    private func setLocalCounter(_ value: Int64) {
        guard value != localCounter else { return }
        undoLog.append(.localCounter(localCounter))
        localCounter = value
    }

    private func setLastSnapshotSeq(_ value: Int64) {
        guard value != lastSnapshotSeq else { return }
        undoLog.append(.lastSnapshotSeq(lastSnapshotSeq))
        lastSnapshotSeq = value
    }

    private func noteStampCounter(_ counter: Int64) {
        guard counter > maxStampCounter else { return }
        undoLog.append(.maxStampCounter(maxStampCounter))
        maxStampCounter = counter
    }

    private func setStamp(_ id: String, _ value: VersionStamp?) {
        undoLog.append(.stamp(id, stamps[id]))
        stamps[id] = value
        if let value { noteStampCounter(value.counter) }
    }

    private func setTombstone(_ id: String, _ value: VersionStamp?) {
        let old = tombstones[id]
        guard old != value else { return }
        undoLog.append(.tombstone(id, old))
        tombstones[id] = value
        if let value { noteStampCounter(value.counter) }
    }

    private func setContentHash(_ id: String, _ value: String?) {
        let old = contentHashes[id]
        guard old != value else { return }
        undoLog.append(.contentHash(id, old))
        contentHashes[id] = value
    }

    private func setActor(_ id: String, _ value: String?) {
        let old = actors[id]
        guard old != value else { return }
        undoLog.append(.actor(id, old))
        actors[id] = value
    }

    private func setHelloEpoch(_ id: String, _ value: String?) {
        let old = helloEpochs[id]
        guard old != value else { return }
        undoLog.append(.helloEpoch(id, old))
        helloEpochs[id] = value
    }

    private func setPending(_ id: String, _ value: RemoteMutation?) {
        let old = pendingModelApplications[id]
        if old == nil && value == nil { return }
        undoLog.append(.pending(id, old))
        pendingModelApplications[id] = value
    }

    private func setPresenceSeq(_ id: String, _ value: Int64?) {
        let old = presenceSeq[id]
        guard old != value else { return }
        undoLog.append(.presenceSeq(id, old))
        presenceSeq[id] = value
    }

    private func setSessionDomain(_ id: String, _ value: String?) {
        let old = sessionDomains[id]
        guard old != value else { return }
        undoLog.append(.sessionDomain(id, old))
        sessionDomains[id] = value
    }

    private func setPresenceFenceExact(_ value: Bool) {
        guard value != presenceFenceExact else { return }
        undoLog.append(.presenceFenceExact(presenceFenceExact))
        presenceFenceExact = value
    }

    /// Back to exactly what the last durable write holds.
    private func rollbackToDurable() {
        batchNeedsWrite = false
        for entry in undoLog.reversed() {
            switch entry {
            case .localCounter(let v): localCounter = v
            case .lastSnapshotSeq(let v): lastSnapshotSeq = v
            case .maxStampCounter(let v): maxStampCounter = v
            case .stamp(let id, let v): stamps[id] = v
            case .tombstone(let id, let v): tombstones[id] = v
            case .contentHash(let id, let v): contentHashes[id] = v
            case .actor(let id, let v): actors[id] = v
            case .helloEpoch(let id, let v): helloEpochs[id] = v
            case .pending(let id, let v): pendingModelApplications[id] = v
            case .presenceSeq(let id, let v): presenceSeq[id] = v
            case .sessionDomain(let id, let v): sessionDomains[id] = v
            case .presenceFenceExact(let v): presenceFenceExact = v
            }
        }
        undoLog.removeAll(keepingCapacity: true)
    }

    private func apply(_ mutation: DurableMutation) {
        setActor(mutation.stamp.actorId, mutation.publicKey)
        setLocalCounter(max(localCounter, mutation.stamp.counter))
        setStamp(mutation.wireObjectId, mutation.stamp)
        switch mutation.kind {
        case .put(let hash):
            setTombstone(mutation.wireObjectId, nil)
            setContentHash(mutation.wireObjectId, hash)
        case .delete:
            setTombstone(mutation.wireObjectId, mutation.stamp)
            setContentHash(mutation.wireObjectId, nil)
        }
    }

    private func validMutation(_ mutation: DurableMutation) -> Bool {
        switch mutation.kind {
        case .delete: return true
        case .put(let hash):
            return Self.isHex64(hash)
        }
    }

    private static func isHex64(_ value: String) -> Bool {
        let utf8 = value.utf8
        guard utf8.count == 64 else { return false }
        return utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }

    private func validRemote(_ remote: RemoteMutation) -> Bool {
        let expectedValid: Bool
        switch remote.mutation.kind {
        case .delete: expectedValid = remote.expectedModelHash == nil
        case .put: expectedValid = remote.expectedModelHash.map(Self.isHex64) ?? false
        }
        return validMutation(remote.mutation) && expectedValid
            && (remote.priorModelHash.map(Self.isHex64) ?? true)
            && (remote.localModelId == nil || UUID(uuidString: remote.localModelId!) != nil)
            && remote.acceptedGeneration >= 0 && remote.acceptedGeneration <= VersionStamp.maxCounter
    }

    private static func mutationsEqual(_ lhs: DurableMutation, _ rhs: DurableMutation) -> Bool {
        guard lhs.wireObjectId == rhs.wireObjectId, lhs.stamp == rhs.stamp,
              lhs.publicKey == rhs.publicKey else { return false }
        switch (lhs.kind, rhs.kind) {
        case (.delete, .delete): return true
        case (.put(let a), .put(let b)): return a == b
        default: return false
        }
    }

    /// Exact durable identity used to recover a persist-before-model crash.
    /// Equal stamps with a different key, mutation kind, or content hash are
    /// conflicts and must never be treated as successfully persisted records.
    func isExactPersistedMutation(_ mutation: DurableMutation) -> Bool {
        guard validMutation(mutation) else { return false }
        return Self.isExact(mutation, stamps: stamps, tombstones: tombstones,
                            contentHashes: contentHashes, actors: actors)
    }

    private static func isExact(
        _ mutation: DurableMutation,
        stamps: [String: VersionStamp], tombstones: [String: VersionStamp],
        contentHashes: [String: String], actors: [String: String]
    ) -> Bool {
        guard stamps[mutation.wireObjectId] == mutation.stamp,
              actors[mutation.stamp.actorId] == mutation.publicKey else { return false }
        switch mutation.kind {
        case .delete:
            return tombstones[mutation.wireObjectId] == mutation.stamp &&
                contentHashes[mutation.wireObjectId] == nil
        case .put(let hash):
            guard isHex64(hash) else { return false }
            return tombstones[mutation.wireObjectId] == nil &&
                contentHashes[mutation.wireObjectId] == hash
        }
    }

    private func roomHighWater() -> Int64 {
        max(localCounter, maxStampCounter)
    }

    private var storeLabel: String { "sync/room/\(roomId)" }

    /// Deferred inside an inbound batch, immediate otherwise.
    private func persist() throws {
        batchNeedsWrite = true
        guard batchDepth == 0 else { return }
        try writeNow()
    }

    /// Always immediate (things that must be durable before they are sent
    /// or shown regardless of any open batch).
    private func persistNow() throws {
        try writeNow()
    }

    private func writeNow() throws {
        do {
            guard let url = try resolvedFileURL() else {
                didPersist()
                return
            }
            let data = try serialize()
            try persistenceWriter(data, url, storeLabel)
        } catch {
            rollbackToDurable()
            throw error
        }
        didPersist()
    }

    private func didPersist() {
        durableSessionDomains = sessionDomains
        batchNeedsWrite = false
        undoLog.removeAll(keepingCapacity: true)
        var persisted: [String: PersistedPresence] = [:]
        persisted.reserveCapacity(presenceSeq.count)
        for (actor, counter) in presenceSeq {
            persisted[actor] = PersistedPresence(sessionDomain: sessionDomains[actor], counter: counter)
        }
        persistedPresence = persisted
        durableWriteCount += 1
        onDurableWrite?()
    }

    private func resolvedFileURL() throws -> URL? {
        guard let containerURL else { return nil }
        let directory = containerURL.appendingPathComponent("sync_replay", isDirectory: true)
        let dataKey = try SafeStore.keyProvider()
        // Resolve only this room. Global unlock migration still scans and
        // reports inactive-room failures independently.
        return try SyncLocalStore.resolveFile(
            directory: directory,
            roomId: roomId,
            domain: .replay,
            dataKey: dataKey
        )
    }

    /// Full semantic validation used before a historical plaintext replay
    /// document is re-sealed during filename migration.
    static func validateLegacyPlaintextForMigration(_ data: Data, roomId: String) throws {
        guard let dict = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              SyncReplayState(roomId: roomId).decode(dict) else {
            throw ReplayError.invalidState
        }
    }

    private func localActorBindingIsValid(actorId: String, pubkey: String) -> Bool {
        guard let roomIdRaw = SyncIdentity.decodeCanonical32(roomId) else { return false }
        return SyncIdentity.actorBindingIsValid(
            actorId: actorId,
            publicKey: pubkey,
            roomIdRaw: roomIdRaw
        )
    }

    /// Repair only the exact shape emitted by the former local reservation
    /// bug: one orphan epoch, owned by the supplied room-derived local actor,
    /// in the current actor-record representation. Any remote/multiple orphan,
    /// malformed record, key mismatch or failed durable rewrite remains fatal.
    private func repairOrphanedLocalHelloEpoch(
        _ dict: [String: Any],
        actorId: String,
        publicKey: String
    ) -> Bool {
        guard localActorBindingIsValid(actorId: actorId, pubkey: publicKey),
              let epochs = dict["helloEpochs"] as? [String: String],
              var actorRecords = dict["actors"] as? [String: [String: Any]] else {
            return false
        }
        let orphanActors = Set(epochs.keys.filter { actorRecords[$0] == nil })
        guard orphanActors == Set([actorId]), actorRecords[actorId] == nil else { return false }

        // Prove that the orphan epoch is the document's only defect. The old
        // reservation bug could write that epoch without its actor pin, but it
        // could not legitimately write any session, presence, mutation or
        // pending-model state for the unpinned actor. Removing only the epoch
        // must therefore make the original document strictly decodable before
        // we are allowed to inject the missing actor record.
        var probe = dict
        var probeEpochs = epochs
        guard probeEpochs.removeValue(forKey: actorId) != nil else { return false }
        probe["helloEpochs"] = probeEpochs
        let orphanEpochIsSoleDefect: Bool = {
            let beforeProbe = snapshot()
            defer { restore(beforeProbe) }
            return decode(probe)
        }()
        guard orphanEpochIsSoleDefect else { return false }

        actorRecords[actorId] = ["pubkey": publicKey, "confirmed": true]
        var repaired = dict
        repaired["actors"] = actorRecords

        let old = snapshot()
        guard decode(repaired), actors[actorId] == publicKey else {
            restore(old)
            return false
        }
        do {
            try persistNow()
            return true
        } catch {
            restore(old)
            return false
        }
    }

    private func serializedObject() -> [String: Any] {
        let actorRecords = actors.mapValues { ["pubkey": $0, "confirmed": true] as [String: Any] }
        let dict: [String: Any] = [
            "schemaVersion": 3,
            "localCounter": VersionStamp.counterHex16(localCounter),
            "lastSnapshotSeq": lastSnapshotSeq,
            "stamps": stamps.mapValues { $0.encode() },
            "tombstones": tombstones.mapValues { $0.encode() },
            "contentHashes": contentHashes,
            "actors": actorRecords,
            "helloEpochs": helloEpochs,
            "pendingModelApplications": pendingModelApplications.mapValues(encodeRemote),
            "presenceSeq": presenceSeq.mapValues(VersionStamp.counterHex16),
            "presenceFenceExact": presenceFenceExact,
            "sessionDomains": sessionDomains
        ]
        return dict
    }

    private func serialize() throws -> Data {
        try JSONSerialization.data(withJSONObject: serializedObject(), options: [.sortedKeys])
    }

    private func encodeRemote(_ remote: RemoteMutation) -> [String: Any] {
        var value: [String: Any] = [
            "vs": remote.mutation.stamp.encode(),
            "pub": remote.mutation.publicKey,
            "priorHash": remote.priorModelHash ?? NSNull(),
            "expectedHash": remote.expectedModelHash ?? NSNull(),
            "localId": remote.localModelId ?? NSNull(),
            "generation": VersionStamp.counterHex16(remote.acceptedGeneration)
        ]
        switch remote.mutation.kind {
        case .delete: value["deleted"] = true
        case .put(let hash): value["deleted"] = false; value["hash"] = hash
        }
        return value
    }

    private func decode(_ dict: [String: Any]) -> Bool {
        guard dict["schemaVersion"] as? Int == 3,
              let counterHex = dict["localCounter"] as? String,
              counterHex.count == 16,
              let counter = UInt64(counterHex, radix: 16), counter <= UInt64(VersionStamp.maxCounter) else { return false }

        guard let seqNumber = dict["lastSnapshotSeq"] as? NSNumber,
              seqNumber.int64Value >= -1,
              let stampValues = dict["stamps"] as? [String: String],
              let tombValues = dict["tombstones"] as? [String: String],
              let hashValues = dict["contentHashes"] as? [String: String] else { return false }
        var decodedStamps: [String: VersionStamp] = [:]
        var decodedTombstones: [String: VersionStamp] = [:]
        for (id, encoded) in stampValues {
                guard SyncIdentity.decodeCanonical32(id) != nil, let stamp = VersionStamp.parse(encoded) else { return false }
                decodedStamps[id] = stamp
        }
        for (id, encoded) in tombValues {
                guard SyncIdentity.decodeCanonical32(id) != nil, let stamp = VersionStamp.parse(encoded) else { return false }
                decodedTombstones[id] = stamp
        }

        var decodedActors: [String: String] = [:]
        if let records = dict["actors"] as? [String: [String: Any]] {
            for (actor, record) in records {
                guard SyncIdentity.decodeCanonical32(actor) != nil,
                      let pub = record["pubkey"] as? String,
                      SyncIdentity.decodeCanonical32(pub) != nil else { return false }
                decodedActors[actor] = pub
            }
        } else if let legacy = dict["actors"] as? [String: String] {
            for (actor, pub) in legacy {
                guard SyncIdentity.decodeCanonical32(actor) != nil,
                      SyncIdentity.decodeCanonical32(pub) != nil else { return false }
                decodedActors[actor] = pub
            }
        } else { return false }

        let decodedEpochs = dict["helloEpochs"] as? [String: String] ?? [:]
        let decodedSessions = dict["sessionDomains"] as? [String: String] ?? [:]
        let presenceValues = dict["presenceSeq"] as? [String: String] ?? [:]
        // absent flag = file from before the stride rule, every write was exact
        let fenceExactValue = dict["presenceFenceExact"]
        let fenceExact: Bool
        if fenceExactValue == nil {
            fenceExact = true
        } else {
            guard let flag = fenceExactValue as? NSNumber,
                  CFGetTypeID(flag) == CFBooleanGetTypeID() else { return false }
            fenceExact = flag.boolValue
        }
        var decodedPresence: [String: Int64] = [:]
        for (actor, encoded) in presenceValues {
            guard encoded.range(of: "^[0-7][0-9a-f]{15}$", options: .regularExpression) != nil,
                  let value = UInt64(encoded, radix: 16),
                  value > 0, value <= UInt64(VersionStamp.maxCounter) else { return false }
            decodedPresence[actor] = Int64(value)
        }
        let pendingValues = dict["pendingModelApplications"] as? [String: [String: Any]] ?? [:]
        var decodedPending: [String: RemoteMutation] = [:]
        for (id, value) in pendingValues {
            guard let encoded = value["vs"] as? String,
                  let stamp = VersionStamp.parse(encoded),
                  let pub = value["pub"] as? String,
                  let deleted = value["deleted"] as? Bool else { return false }
            let kind: MutationKind
            if deleted {
                guard value["hash"] == nil else { return false }
                kind = .delete
            } else {
                guard let hash = value["hash"] as? String else { return false }
                kind = .put(contentHash: hash)
            }
            let prior = value["priorHash"] is NSNull ? nil : value["priorHash"] as? String
            if !(value["priorHash"] is NSNull) && prior == nil { return false }
            let localId = value["localId"] is NSNull ? nil : value["localId"] as? String
            if !(value["localId"] is NSNull) && localId == nil { return false }
            let generationHex = value["generation"] as? String ?? "0000000000000000"
            let expected: String?
            if value["expectedHash"] == nil {
                if case .put(let hash) = kind { expected = hash } else { expected = nil }
            } else if deleted {
                guard value["expectedHash"] is NSNull else { return false }
                expected = nil
            } else {
                guard let decodedExpected = value["expectedHash"] as? String else { return false }
                expected = decodedExpected
            }
            guard generationHex.range(of: "^[0-7][0-9a-f]{15}$", options: .regularExpression) != nil,
                  let generationRaw = UInt64(generationHex, radix: 16),
                  generationRaw <= UInt64(VersionStamp.maxCounter) else { return false }
            decodedPending[id] = RemoteMutation(
                mutation: DurableMutation(wireObjectId: id, stamp: stamp, publicKey: pub, kind: kind),
                priorModelHash: prior, localModelId: localId,
                acceptedGeneration: Int64(generationRaw), expectedModelHash: expected)
        }
        guard decodedEpochs.allSatisfy({ decodedActors[$0.key] != nil && SyncIdentity.parseHelloEpoch($0.value) != nil }),
              decodedSessions.allSatisfy({
                  decodedActors[$0.key] != nil && decodedEpochs[$0.key] != nil &&
                      SyncIdentity.decodeCanonical32($0.value) != nil
              }),
              decodedPresence.allSatisfy({
                  decodedSessions[$0.key] != nil && decodedActors[$0.key] != nil
              }),
              decodedStamps.allSatisfy({ decodedActors[$0.value.actorId] != nil }),
              decodedTombstones.allSatisfy({ decodedStamps[$0.key] == $0.value }),
              hashValues.allSatisfy({ decodedStamps[$0.key] != nil && decodedTombstones[$0.key] == nil && $0.value.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil }),
              decodedStamps.keys.allSatisfy({ (decodedTombstones[$0] != nil) != (hashValues[$0] != nil) }),
              Int64(counter) >= (decodedStamps.values.map(\.counter).max() ?? 0),
              decodedPending.allSatisfy({ id, remote in
                  guard id == remote.mutation.wireObjectId, validRemote(remote),
                        decodedActors[remote.mutation.stamp.actorId] == remote.mutation.publicKey,
                        decodedStamps[id] == remote.mutation.stamp else { return false }
                  switch remote.mutation.kind {
                  case .delete: return decodedTombstones[id] == remote.mutation.stamp && hashValues[id] == nil
                  case .put(let hash): return decodedTombstones[id] == nil && hashValues[id] == hash
                  }
              }) else { return false }

        localCounter = Int64(counter)
        lastSnapshotSeq = seqNumber.int64Value
        stamps = decodedStamps
        tombstones = decodedTombstones
        contentHashes = hashValues
        actors = decodedActors
        helloEpochs = decodedEpochs
        pendingModelApplications = decodedPending
        sessionDomains = decodedSessions
        durableSessionDomains = decodedSessions
        maxStampCounter = decodedStamps.values.map(\.counter).max() ?? 0
        var onDisk: [String: PersistedPresence] = [:]
        var effective: [String: Int64] = [:]
        for (actor, persisted) in decodedPresence {
            onDisk[actor] = PersistedPresence(sessionDomain: decodedSessions[actor], counter: persisted)
        }
        // 17.1 floor is for every session, incl one whose counter never made
        // it to disk (persisted 0). It could still have shown 1...15 before
        // the crash, so those stay rejected too.
        for actor in decodedSessions.keys {
            let floor = PresenceFencePersistence.loadFloor(persisted: decodedPresence[actor] ?? 0, exact: fenceExact)
            if floor > 0 { effective[actor] = floor }
        }
        presenceSeq = effective
        persistedPresence = onDisk
        presenceFenceExact = fenceExact
        undoLog.removeAll()
        return true
    }
}

/// App-global mutation generations, independent of room membership and Leave.
final class LocalModelRevisionJournal {
    typealias PersistenceWriter = (Data, URL, String) throws -> Void

    private var generations: [String: Int64] = [:]
    private let fileURL: URL?
    private let label = "sync/model-revisions"
    private let testKey: Data?
    private let persistenceWriter: PersistenceWriter

    init(containerURL: URL?, testKey: Data? = nil,
         persistenceWriter: @escaping PersistenceWriter = { data, url, label in
             try SyncPersistenceExecutor.write(data, to: url, label: label)
         }) {
        fileURL = containerURL?.appendingPathComponent("sync_model_revisions.json")
        self.testKey = testKey
        self.persistenceWriter = persistenceWriter
    }

    func generation(_ localId: String?) -> Int64 { localId.flatMap { generations[$0] } ?? 0 }

    func bump(_ localId: String) throws {
        try bumpAll([localId])
    }

    /// Every id touched by one store mutation event, one sealed write (iOS used
    /// to write once per id, so an N-object import was N full rewrites).
    func bumpAll<S: Sequence>(_ localIds: S) throws where S.Element == String {
        var previous: [String: Int64?] = [:]
        for localId in localIds where previous[localId] == nil {
            guard UUID(uuidString: localId) != nil else {
                restore(previous)
                throw SyncReplayState.ReplayError.invalidState
            }
            let current = generations[localId] ?? 0
            guard current < VersionStamp.maxCounter else {
                restore(previous)
                throw SyncReplayState.ReplayError.counterExhausted
            }
            // updateValue, a plain subscript assign of nil would drop the key
            previous.updateValue(generations[localId], forKey: localId)
            generations[localId] = current + 1
        }
        guard !previous.isEmpty else { return }
        do { try persist() } catch {
            restore(previous)
            throw error
        }
    }

    @MainActor
    func bumpAll(_ localIds: [String], on executor: SyncOffMainExecutor,
                 completion: @escaping (Error?) -> Void) {
        let old = generations
        do {
            for id in Set(localIds) {
                guard UUID(uuidString: id) != nil, generations[id, default: 0] < VersionStamp.maxCounter else {
                    throw SyncReplayState.ReplayError.invalidState
                }
                generations[id, default: 0] += 1
            }
            guard !localIds.isEmpty, let fileURL else { completion(nil); return }
            let candidate = generations
            let root: [String: Any] = ["schemaVersion": 1, "generations": candidate.mapValues(VersionStamp.counterHex16)]
            let writer = persistenceWriter, label = label, testKey = testKey
            let result = AsyncJournalResult()
            executor.execute({
                do {
                    let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
                    if let testKey {
                        try SealedEnvelope.sealFile(key: testKey, plaintext: data, label: label).write(to: fileURL, options: .atomic)
                    } else { try writer(data, fileURL, label) }
                } catch { result.error = error }
            }) {
                if result.error != nil, self.generations == candidate { self.generations = old }
                completion(result.error)
            }
        } catch { generations = old; completion(error) }
    }

    private final class AsyncJournalResult { var error: Error? }

    private func restore(_ previous: [String: Int64?]) {
        for (localId, value) in previous {
            if let value { generations[localId] = value } else { generations.removeValue(forKey: localId) }
        }
    }

    @discardableResult
    func load() -> Bool {
        guard let fileURL else { return true }
        if let testKey {
            guard FileManager.default.fileExists(atPath: fileURL.path) else { return true }
            guard let raw = try? Data(contentsOf: fileURL),
                  let plain = SealedEnvelope.openFile(key: testKey, blob: raw, label: label),
                  let values = decodeValues(plain) else { return false }
            generations = values
            return true
        }
        switch SafeStore.read(fileURL, label: label, decode: { data -> [String: String] in
            guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  root["schemaVersion"] as? Int == 1,
                  let values = root["generations"] as? [String: String] else {
                throw SyncReplayState.ReplayError.invalidState
            }
            return values
        }) {
        case .empty: return true
        case .locked, .corrupt: return false
        case .loaded(let values):
            var decoded: [String: Int64] = [:]
            for (id, value) in values {
                guard UUID(uuidString: id) != nil,
                      value.range(of: "^[0-7][0-9a-f]{15}$", options: .regularExpression) != nil,
                      let raw = UInt64(value, radix: 16), raw <= UInt64(VersionStamp.maxCounter) else { return false }
                decoded[id] = Int64(raw)
            }
            generations = decoded
            return true
        }
    }

    private func persist() throws {
        guard let fileURL else { return }
        let root: [String: Any] = [
            "schemaVersion": 1,
            "generations": generations.mapValues(VersionStamp.counterHex16)
        ]
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        if let testKey {
            try SealedEnvelope.sealFile(key: testKey, plaintext: data, label: label)
                .write(to: fileURL, options: .atomic)
        } else {
            try persistenceWriter(data, fileURL, label)
        }
    }

    private func decodeValues(_ data: Data) -> [String: Int64]? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["schemaVersion"] as? Int == 1,
              let values = root["generations"] as? [String: String] else { return nil }
        var decoded: [String: Int64] = [:]
        for (id, value) in values {
            guard UUID(uuidString: id) != nil,
                  value.range(of: "^[0-7][0-9a-f]{15}$", options: .regularExpression) != nil,
                  let raw = UInt64(value, radix: 16), raw <= UInt64(VersionStamp.maxCounter) else { return nil }
            decoded[id] = Int64(raw)
        }
        return decoded
    }
}
