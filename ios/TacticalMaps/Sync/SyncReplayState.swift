import Foundation

/// Durable per-room replay state for v3 protocol. Survives leave/restart so
/// the relay can't roll back a reconnecting client.
final class SyncReplayState {
    static let advanceWindow: Int64 = 10_000

    private(set) var localCounter: Int64 = 0
    private(set) var lastSnapshotSeq: Int64 = -1

    private var stamps: [String: VersionStamp] = [:]
    private var tombstones: [String: VersionStamp] = [:]
    private var contentHashes: [String: String] = [:]
    private var actors: [String: String] = [:]
    private var presenceSeq: [String: Int64] = [:]
    private var sessionDomains: [String: String] = [:]

    let roomId: String
    private let fileURL: URL?

    init(roomId: String, containerURL: URL? = nil) {
        self.roomId = roomId
        if let container = containerURL {
            let dir = container.appendingPathComponent("sync_replay", isDirectory: true)
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            self.fileURL = dir.appendingPathComponent("\(roomId).json")
        } else {
            self.fileURL = nil
        }
    }

    /// Try to advance the stamp for wireObjId. Returns true if incoming is newer.
    func advance(_ wireObjId: String, _ incoming: VersionStamp) -> Bool {
        if incoming.counter > roomHighWater() + Self.advanceWindow { return false }
        if let existing = stamps[wireObjId], incoming <= existing { return false }
        if let tomb = tombstones[wireObjId], incoming <= tomb { return false }
        stamps[wireObjId] = incoming
        return true
    }

    /// Record a tombstone. Returns true if the delete stamp is newer.
    func tombstone(_ wireObjId: String, _ incoming: VersionStamp) -> Bool {
        if incoming.counter > roomHighWater() + Self.advanceWindow { return false }
        if let existing = stamps[wireObjId], incoming <= existing { return false }
        if let tomb = tombstones[wireObjId], incoming <= tomb { return false }
        tombstones[wireObjId] = incoming
        stamps[wireObjId] = incoming
        return true
    }

    func isTombstoned(_ wireObjId: String) -> Bool { tombstones[wireObjId] != nil }
    func getStamp(_ wireObjId: String) -> VersionStamp? { stamps[wireObjId] }

    /// TOFU-pin actorId -> pubkey. Returns true if ok (new or same key).
    func registerActor(_ actorId: String, pubkey: String) -> Bool {
        if let pinned = actors[actorId] { return pinned == pubkey }
        actors[actorId] = pubkey
        return true
    }

    func getPinnedPubkey(_ actorId: String) -> String? { actors[actorId] }

    func updateSessionDomain(_ actorId: String, sd: String) {
        sessionDomains[actorId] = sd
    }

    func getSessionDomain(_ actorId: String) -> String? { sessionDomains[actorId] }

    /// Check + advance presence counter. Returns true if strictly higher.
    func advancePresence(_ actorId: String, counter: Int64) -> Bool {
        let existing = presenceSeq[actorId] ?? -1
        guard counter > existing else { return false }
        presenceSeq[actorId] = counter
        return true
    }

    func setContentHash(_ wireObjId: String, hash: String) {
        contentHashes[wireObjId] = hash
    }

    func getContentHash(_ wireObjId: String) -> String? { contentHashes[wireObjId] }

    /// Allocate the next counter value for a local write.
    func nextCounter() -> Int64 {
        localCounter += 1
        return localCounter
    }

    func updateSnapshotSeq(_ seq: Int64) {
        lastSnapshotSeq = seq
    }

    private func roomHighWater() -> Int64 {
        var max = localCounter
        for (_, vs) in stamps where vs.counter > max { max = vs.counter }
        for (_, vs) in tombstones where vs.counter > max { max = vs.counter }
        return max
    }

    // -- Persistence (sealed at rest via SafeStore) --

    private var storeLabel: String { "sync/room/\(roomId)" }

    private func serialize() -> Data? {
        var dict: [String: Any] = [
            "schemaVersion": 3,
            "localCounter": VersionStamp.counterHex16(localCounter),
            "lastSnapshotSeq": lastSnapshotSeq
        ]
        dict["stamps"] = stamps.mapValues { $0.encode() }
        dict["tombstones"] = tombstones.mapValues { $0.encode() }
        dict["contentHashes"] = contentHashes
        dict["actors"] = actors
        dict["presenceSeq"] = presenceSeq.mapValues { VersionStamp.counterHex16($0) }
        dict["sessionDomains"] = sessionDomains
        return try? JSONSerialization.data(withJSONObject: dict, options: [.sortedKeys])
    }

    func save() {
        guard let url = fileURL, let data = serialize() else { return }
        try? SafeStore.write(data, to: url, label: storeLabel)
    }

    @discardableResult
    func load() -> Bool {
        guard let url = fileURL else { return false }
        let result = SafeStore.read(url, label: storeLabel) { data -> [String: Any]? in
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        }
        guard case .loaded(let dict?) = result,
              dict["schemaVersion"] as? Int == 3 else { return false }

        if let lc = dict["localCounter"] as? String {
            localCounter = Int64(UInt64(lc, radix: 16) ?? 0)
        }
        lastSnapshotSeq = dict["lastSnapshotSeq"] as? Int64 ?? -1

        if let s = dict["stamps"] as? [String: String] {
            for (k, v) in s { if let vs = VersionStamp.parse(v) { stamps[k] = vs } }
        }
        if let t = dict["tombstones"] as? [String: String] {
            for (k, v) in t { if let vs = VersionStamp.parse(v) { tombstones[k] = vs } }
        }
        if let h = dict["contentHashes"] as? [String: String] { contentHashes = h }
        if let a = dict["actors"] as? [String: String] { actors = a }
        if let p = dict["presenceSeq"] as? [String: String] {
            for (k, v) in p { presenceSeq[k] = Int64(UInt64(v, radix: 16) ?? 0) }
        }
        if let sd = dict["sessionDomains"] as? [String: String] { sessionDomains = sd }
        return true
    }

    func clear() {
        localCounter = 0
        lastSnapshotSeq = -1
        stamps.removeAll(); tombstones.removeAll()
        contentHashes.removeAll(); actors.removeAll()
        presenceSeq.removeAll(); sessionDomains.removeAll()
        if let url = fileURL { try? FileManager.default.removeItem(at: url) }
    }
}
