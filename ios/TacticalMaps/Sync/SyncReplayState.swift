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

    let roomId: String
    private let fileURL: URL?
    private let persistenceWriter: PersistenceWriter

    init(
        roomId: String,
        containerURL: URL? = nil,
        persistenceWriter: @escaping PersistenceWriter = { data, url, label in
            try SafeStore.write(data, to: url, label: label)
        }
    ) {
        self.roomId = roomId
        self.persistenceWriter = persistenceWriter
        if let container = containerURL {
            let dir = container.appendingPathComponent("sync_replay", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            fileURL = dir.appendingPathComponent("\(roomId).json")
        } else {
            fileURL = nil
        }
    }

    func canAccept(_ wireObjectId: String, _ incoming: VersionStamp, enforceWindow: Bool = true) -> Bool {
        let highWater = roomHighWater()
        if enforceWindow && incoming.counter > highWater && incoming.counter - highWater > Self.advanceWindow { return false }
        if let existing = stamps[wireObjectId], incoming <= existing { return false }
        if let tomb = tombstones[wireObjectId], incoming <= tomb { return false }
        return true
    }

    /// Compatibility helpers used by focused replay tests. Production uses the
    /// throwing commit methods below so persistence is part of acceptance.
    func advance(_ wireObjectId: String, _ incoming: VersionStamp) -> Bool {
        guard canAccept(wireObjectId, incoming) else { return false }
        stamps[wireObjectId] = incoming
        tombstones.removeValue(forKey: wireObjectId)
        localCounter = max(localCounter, incoming.counter)
        return true
    }

    func tombstone(_ wireObjectId: String, _ incoming: VersionStamp) -> Bool {
        guard canAccept(wireObjectId, incoming) else { return false }
        stamps[wireObjectId] = incoming
        tombstones[wireObjectId] = incoming
        contentHashes.removeValue(forKey: wireObjectId)
        localCounter = max(localCounter, incoming.counter)
        return true
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
        actors[actorId] = pubkey
        return true
    }

    /// Persist the verified actor pin, then activate its signed session domain.
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
        let old = snapshot()
        actors[actorId] = pubkey
        helloEpochs[actorId] = epochHex
        if sessionDomains[actorId] != sessionDomain { presenceSeq.removeValue(forKey: actorId) }
        sessionDomains[actorId] = sessionDomain
        do { try persist(); return true } catch { restore(old); throw error }
    }

    func reserveHelloEpoch(actorId: String) throws -> String {
        let current = helloEpochs[actorId].flatMap { UInt64($0, radix: 16) } ?? 0
        guard current < UInt64.max else { throw ReplayError.counterExhausted }
        let next = String(format: "%016llx", current + 1)
        let old = snapshot()
        helloEpochs[actorId] = next
        do { try persist(); return next } catch { restore(old); throw error }
    }

    func getHelloEpoch(_ actorId: String) -> String? { helloEpochs[actorId] }

    func activeSessionDomain(_ actorId: String) -> String? { sessionDomains[actorId] }

    /// Called only after actor binding, AEAD, signature and payload validation.
    /// The counter is persisted before the caller may expose the peer on the map.
    func acceptPresence(actorId: String, sessionDomain: String, counter: Int64) throws -> Bool {
        guard sessionDomains[actorId] == sessionDomain, counter > 0 else { return false }
        let existing = presenceSeq[actorId] ?? 0
        guard counter > existing, counter <= existing + Self.advanceWindow else { return false }
        let old = snapshot()
        presenceSeq[actorId] = counter
        do { try persist(); return true } catch { restore(old); throw error }
    }

    /// Reserve and persist a durable counter before constructing/sending a
    /// local mutation. A failed crypto operation merely wastes the value.
    func reserveNextCounter() throws -> Int64 {
        guard localCounter < VersionStamp.maxCounter else { throw ReplayError.counterExhausted }
        let old = snapshot()
        localCounter += 1
        do { try persist(); return localCounter } catch { restore(old); throw error }
    }

    /// Commit a verified live or outbound mutation atomically with its actor
    /// pin and content/tombstone metadata.
    @discardableResult
    func commit(_ mutation: DurableMutation, enforceWindow: Bool = true) throws -> Bool {
        guard validMutation(mutation), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey),
              canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: enforceWindow) else { return false }
        let old = snapshot()
        apply(mutation)
        do { try persist(); return true } catch { restore(old); throw error }
    }

    /// Atomically accept a verified remote mutation and record model work.
    func commitRemote(_ remote: RemoteMutation, enforceWindow: Bool = true) throws -> Bool {
        let mutation = remote.mutation
        guard validRemote(remote), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey),
              canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: enforceWindow) else { return false }
        let old = snapshot()
        apply(mutation)
        pendingModelApplications[mutation.wireObjectId] = remote
        do { try persist(); return true } catch { restore(old); throw error }
    }

    /// Apply an authenticated snapshot as one durable transaction. The caller
    /// validates every record first; stale records are ignored individually,
    /// while the authenticated maximum still raises the next local counter.
    func commitSnapshot(_ mutations: [DurableMutation], seq: Int64) throws -> [SnapshotMutationResult] {
        let old = snapshot()
        var accepted = [SnapshotMutationResult]()
        accepted.reserveCapacity(mutations.count)
        for mutation in mutations {
            guard validMutation(mutation), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey) else {
                restore(old)
                throw ReplayError.actorKeyMismatch
            }
            localCounter = max(localCounter, mutation.stamp.counter)
            if isExactPersistedMutation(mutation) {
                accepted.append(.exactAlreadyPersisted)
            } else if canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: false) {
                apply(mutation)
                accepted.append(.newlyPersisted)
            } else {
                accepted.append(.conflictOrStale)
            }
        }
        lastSnapshotSeq = max(lastSnapshotSeq, seq)
        do { try persist(); return accepted } catch { restore(old); throw error }
    }

    /// Snapshot transaction that records pending model work only for newly
    /// accepted mutations. Exact records retain an existing matching marker;
    /// without one, they were already resolved and cannot repair the model.
    func commitRemoteSnapshot(_ remotes: [RemoteMutation], seq: Int64) throws -> [SnapshotMutationResult] {
        let old = snapshot()
        var results: [SnapshotMutationResult] = []
        results.reserveCapacity(remotes.count)
        for remote in remotes {
            let mutation = remote.mutation
            guard validRemote(remote), actorKeyIsAcceptable(mutation.stamp.actorId, pubkey: mutation.publicKey) else {
                restore(old)
                throw ReplayError.actorKeyMismatch
            }
            localCounter = max(localCounter, mutation.stamp.counter)
            if isExactPersistedMutation(mutation) {
                let hasMatchingPending = pendingModelApplications[mutation.wireObjectId]
                    .map { mutationsEqual($0.mutation, mutation) } ?? false
                results.append(hasMatchingPending ? .exactAlreadyPersisted : .conflictOrStale)
            } else if canAccept(mutation.wireObjectId, mutation.stamp, enforceWindow: false) {
                apply(mutation)
                pendingModelApplications[mutation.wireObjectId] = remote
                results.append(.newlyPersisted)
            } else {
                results.append(.conflictOrStale)
            }
        }
        lastSnapshotSeq = max(lastSnapshotSeq, seq)
        do { try persist(); return results } catch { restore(old); throw error }
    }

    func hasPendingModelApplications() -> Bool { !pendingModelApplications.isEmpty }
    func pendingRemoteMutations() -> [RemoteMutation] { Array(pendingModelApplications.values) }

    func pendingModelDecision(_ mutation: DurableMutation, currentModelHash: String?,
                              currentGeneration: Int64 = 0) -> PendingModelDecision {
        guard let pending = pendingModelApplications[mutation.wireObjectId],
              mutationsEqual(pending.mutation, mutation) else { return .none }
        let incomingHash = pending.expectedModelHash
        if currentModelHash == incomingHash { return .alreadyApplied }
        if currentGeneration != pending.acceptedGeneration { return .localDiverged }
        if currentModelHash == pending.priorModelHash { return .applyIncoming }
        return .localDiverged
    }

    func clearPendingModelApplication(_ mutation: DurableMutation) throws -> Bool {
        guard let pending = pendingModelApplications[mutation.wireObjectId],
              mutationsEqual(pending.mutation, mutation) else { return false }
        let old = snapshot()
        pendingModelApplications.removeValue(forKey: mutation.wireObjectId)
        do { try persist(); return true } catch { restore(old); throw error }
    }

    func save() throws { try persist() }

    @discardableResult
    func load() -> Bool {
        guard let url = fileURL else { return true }
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
            return decode(dict)
        case .locked, .corrupt:
            return false
        }
    }

    func clear() {
        localCounter = 0
        lastSnapshotSeq = -1
        stamps.removeAll(); tombstones.removeAll(); contentHashes.removeAll(); actors.removeAll(); helloEpochs.removeAll()
        pendingModelApplications.removeAll()
        presenceSeq.removeAll(); sessionDomains.removeAll()
        if let url = fileURL {
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
    }

    private func snapshot() -> StateSnapshot {
        StateSnapshot(localCounter: localCounter, lastSnapshotSeq: lastSnapshotSeq,
                      stamps: stamps, tombstones: tombstones,
                      contentHashes: contentHashes, actors: actors, helloEpochs: helloEpochs,
                      pendingModelApplications: pendingModelApplications,
                      presenceSeq: presenceSeq, sessionDomains: sessionDomains)
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
    }

    private func apply(_ mutation: DurableMutation) {
        actors[mutation.stamp.actorId] = mutation.publicKey
        localCounter = max(localCounter, mutation.stamp.counter)
        stamps[mutation.wireObjectId] = mutation.stamp
        switch mutation.kind {
        case .put(let hash):
            tombstones.removeValue(forKey: mutation.wireObjectId)
            contentHashes[mutation.wireObjectId] = hash
        case .delete:
            tombstones[mutation.wireObjectId] = mutation.stamp
            contentHashes.removeValue(forKey: mutation.wireObjectId)
        }
    }

    private func validMutation(_ mutation: DurableMutation) -> Bool {
        switch mutation.kind {
        case .delete: return true
        case .put(let hash):
            return hash.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
        }
    }

    private func validRemote(_ remote: RemoteMutation) -> Bool {
        let expectedValid: Bool
        switch remote.mutation.kind {
        case .delete: expectedValid = remote.expectedModelHash == nil
        case .put: expectedValid = remote.expectedModelHash?.range(
            of: "^[0-9a-f]{64}$", options: .regularExpression) != nil
        }
        return validMutation(remote.mutation) && expectedValid && (remote.priorModelHash == nil ||
            remote.priorModelHash!.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil) &&
            (remote.localModelId == nil || UUID(uuidString: remote.localModelId!) != nil)
            && remote.acceptedGeneration >= 0 && remote.acceptedGeneration <= VersionStamp.maxCounter
    }

    private func mutationsEqual(_ lhs: DurableMutation, _ rhs: DurableMutation) -> Bool {
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
        guard validMutation(mutation), stamps[mutation.wireObjectId] == mutation.stamp,
              actors[mutation.stamp.actorId] == mutation.publicKey else { return false }
        switch mutation.kind {
        case .delete:
            return tombstones[mutation.wireObjectId] == mutation.stamp &&
                contentHashes[mutation.wireObjectId] == nil
        case .put(let hash):
            return tombstones[mutation.wireObjectId] == nil &&
                contentHashes[mutation.wireObjectId] == hash
        }
    }

    private func roomHighWater() -> Int64 {
        var result = localCounter
        for stamp in stamps.values { result = max(result, stamp.counter) }
        for stamp in tombstones.values { result = max(result, stamp.counter) }
        return result
    }

    private var storeLabel: String { "sync/room/\(roomId)" }

    private func persist() throws {
        guard let url = fileURL else { return }
        let data = try serialize()
        try persistenceWriter(data, url, storeLabel)
    }

    private func serialize() throws -> Data {
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
            "sessionDomains": sessionDomains
        ]
        return try JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
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
        presenceSeq = decodedPresence
        sessionDomains = decodedSessions
        return true
    }
}

/// App-global mutation generations, independent of room membership and Leave.
final class LocalModelRevisionJournal {
    private var generations: [String: Int64] = [:]
    private let fileURL: URL?
    private let label = "sync/model-revisions"
    private let testKey: Data?

    init(containerURL: URL?, testKey: Data? = nil) {
        fileURL = containerURL?.appendingPathComponent("sync_model_revisions.json")
        self.testKey = testKey
    }

    func generation(_ localId: String?) -> Int64 { localId.flatMap { generations[$0] } ?? 0 }

    func bump(_ localId: String) throws {
        guard UUID(uuidString: localId) != nil else { throw SyncReplayState.ReplayError.invalidState }
        let current = generations[localId] ?? 0
        guard current < VersionStamp.maxCounter else { throw SyncReplayState.ReplayError.counterExhausted }
        generations[localId] = current + 1
        do { try persist() } catch {
            if current == 0 { generations.removeValue(forKey: localId) } else { generations[localId] = current }
            throw error
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
            try SafeStore.write(data, to: fileURL, label: label)
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
