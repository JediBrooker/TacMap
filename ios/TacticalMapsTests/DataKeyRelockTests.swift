import XCTest
@testable import TacticalMaps

// K1 / sync-ios-6. With the auth-bound key RootGate relocks the key whenever
// the scene leaves active. Nothing may read the Keychain again (so no Face ID or
// passcode prompt over Control Center, no key back in memory behind the lock)
// till the user's own unlock, and sync work still queued from before the lock
// fails quietly instead of stopping Unit Sync. The journal ids it carried go
// down after the unlock.

/// The relock gate DataKey runs its cache through, with a counting Keychain read.
final class DataKeyRelockTests: XCTestCase {
    private let dek = Data((1...32).map { UInt8($0) })
    private var reads = 0

    private func read() throws -> Data {
        reads += 1
        return dek
    }

    func testFirstUseReadsTheKeychainOnceThenServesTheCopy() throws {
        let cache = DataKeyCache()
        XCTAssertEqual(try cache.get(read), dek)
        XCTAssertEqual(try cache.get(read), dek)
        XCTAssertEqual(reads, 1)
        XCTAssertTrue(cache.isCached)
        XCTAssertFalse(cache.isRelocked)
    }

    func testAfterTheLockNothingReadsTheKeychainAgain() throws {
        let cache = DataKeyCache()
        _ = try cache.get(read)
        cache.lock()
        // every save that was still queued when the scene left active
        for _ in 0..<3 {
            XCTAssertThrowsError(try cache.get(read)) { error in
                XCTAssertTrue(error is DataKey.LockedError)
                XCTAssertTrue(DataKey.failedBehindRelock(error))
            }
        }
        XCTAssertEqual(reads, 1, "no Keychain read behind the lock, so no prompt and no re-cache")
        XCTAssertFalse(cache.isCached)
        XCTAssertTrue(cache.isRelocked)
    }

    func testOnlyTheUsersUnlockOpensItAgain() throws {
        let cache = DataKeyCache()
        cache.lock()
        XCTAssertThrowsError(try cache.get(read))
        XCTAssertEqual(reads, 0)
        XCTAssertEqual(try cache.unlock(read), dek)
        XCTAssertEqual(reads, 1)
        XCTAssertFalse(cache.isRelocked)
        XCTAssertEqual(try cache.get(read), dek)
        XCTAssertEqual(reads, 1, "served from the cache now")
    }

    func testAFailedUnlockKeepsTheGateShut() {
        let cache = DataKeyCache()
        cache.lock()
        // Face ID cancelled, then a Keychain hiccup
        XCTAssertThrowsError(try cache.unlock { throw DataKey.LockedError() })
        XCTAssertThrowsError(try cache.unlock { throw DataKey.UnrecoverableError(status: errSecIO) })
        XCTAssertTrue(cache.isRelocked)
        XCTAssertThrowsError(try cache.get(read))
        XCTAssertEqual(reads, 0)
    }

    func testDropMeansAFreshReadNotARelock() throws {
        let cache = DataKeyCache()
        _ = try cache.get(read)
        cache.drop()
        XCTAssertFalse(cache.isRelocked)
        XCTAssertEqual(try cache.get(read), dek)
        XCTAssertEqual(reads, 2)
    }

    func testOnlyTheGatesErrorCountsAsARelock() {
        // a prompt cancelled with the gate open (launch) is still the old fault path
        XCTAssertFalse(DataKey.failedBehindRelock(DataKey.LockedError()))
        XCTAssertFalse(DataKey.failedBehindRelock(DataKey.UnrecoverableError(status: errSecIO)))
        XCTAssertFalse(DataKey.failedBehindRelock(nil))
        XCTAssertTrue(DataKey.failedBehindRelock(DataKey.LockedError(relocked: true)))
    }

    /// The real statics on the test host's device-bound key. A DEVICE read never
    /// prompts and would just work, so a throw here means key() really stayed
    /// away from the Keychain till unlock().
    func testLockKeyShutsTheRealKeyTillUnlock() throws {
        try XCTSkipIf(DataKey.isAuthBound, "needs the device-bound key the test host installs, an auth-bound one would prompt")
        let provider = SafeStore.keyProvider
        SafeStore.keyProvider = { try DataKey.key() }
        defer {
            if DataKey.isRelocked { _ = try? DataKey.unlock() }
            SafeStore.keyProvider = provider
        }
        let before = try DataKey.key()
        XCTAssertTrue(DataKey.isUnlocked)

        DataKey.lockKey()
        XCTAssertTrue(DataKey.isRelocked)
        XCTAssertFalse(DataKey.isUnlocked)
        XCTAssertThrowsError(try DataKey.key()) { XCTAssertTrue(DataKey.failedBehindRelock($0)) }
        // what every sealed store goes through
        XCTAssertThrowsError(try SafeStore.keyProvider()) { XCTAssertTrue(DataKey.failedBehindRelock($0)) }

        XCTAssertEqual(try DataKey.unlock(), before)
        XCTAssertFalse(DataKey.isRelocked)
        XCTAssertTrue(DataKey.isUnlocked)
        XCTAssertEqual(try DataKey.key(), before)
    }
}

/// DataKey's cache and gate over the harness key, wired to SafeStore the way
/// production is, counting every Keychain read.
private final class InstrumentedDataKey {
    let cache = DataKeyCache()
    private let dek: Data
    private let mutex = NSLock()
    private var readCount = 0

    init(dek: Data) { self.dek = dek }

    var reads: Int {
        mutex.lock(); defer { mutex.unlock() }
        return readCount
    }

    private func read() throws -> Data {
        mutex.lock(); readCount += 1; mutex.unlock()
        return dek
    }

    /// after the harness set its fixed key
    func install() throws {
        try cache.unlock(read)
        SafeStore.keyProvider = { [self] in try cache.get(read) }
    }

    /// RootGate's lockKey() when the scene leaves active
    func relock() { cache.lock() }

    /// the Unlock button on the mission data screen
    func unlock() throws { try cache.unlock(read) }
}

/// Persistence on the spot like the harness default, until holding: then every
/// job waits like it would behind a busy serial worker, standing for seals that
/// are still queued when the scene leaves active.
@MainActor
private final class HoldablePersistenceExecutor: SyncOffMainExecutor {
    var holding = false
    private var queued: [(work: () -> Void, completion: @MainActor () -> Void)] = []
    var queuedCount: Int { queued.count }

    func execute(_ work: @escaping () -> Void, then completion: @escaping @MainActor () -> Void) {
        if holding || !queued.isEmpty {
            queued.append((work, completion))
            return
        }
        work()
        completion()
    }

    /// the oldest job, work then completion
    func runNext() {
        guard !queued.isEmpty else { return }
        let job = queued.removeFirst()
        job.work()
        job.completion()
    }

    /// the worker catches up and runs on the spot again
    func resume() {
        holding = false
        while !queued.isEmpty { runNext() }
    }
}

@MainActor
final class SyncRelockTests: XCTestCase {
    private var harness: SyncManagerHarness!
    private var manager: SyncManager { harness.manager }

    override func tearDown() async throws {
        harness?.tearDown()
        harness = nil
    }

    private func goBackground(_ key: InstrumentedDataKey, presence: Bool = false) {
        // auth-bound key: RootGate relocks, then ContentView ends the session
        key.relock()
        manager.updateLifecycle(foregroundReady: false, backgroundPresenceEnabled: presence, backgroundInterval: 900)
    }

    private func comeBack(_ key: InstrumentedDataKey, presence: Bool = false) throws {
        try key.unlock()
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: presence, backgroundInterval: 900)
        harness.pump()
    }

    private func journalOnDisk() -> LocalModelRevisionJournal {
        let journal = LocalModelRevisionJournal(containerURL: harness.directory)
        XCTAssertTrue(journal.load())
        return journal
    }

    private func assertNoStop(_ key: InstrumentedDataKey, reads: Int,
                              file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(key.reads, reads, "nothing behind the lock read the Keychain", file: file, line: line)
        XCTAssertFalse(key.cache.isCached, "and nothing put the key back", file: file, line: line)
        XCTAssertNil(manager.lastError, "a relock is lifecycle, not a fault", file: file, line: line)
        XCTAssertNotEqual(manager.lastIssueKind, .security, file: file, line: line)
    }

    func testAJournalSealQueuedBeforeTheRelockFailsQuietlyAndGoesDownAfterUnlock() throws {
        let executor = HoldablePersistenceExecutor()
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        let key = InstrumentedDataKey(dek: SyncManagerHarness.testKey)
        try key.install()
        harness.join()
        harness.connect()
        XCTAssertEqual(manager.status, .connected)
        let reads = key.reads

        // a local edit, its journal seal still waiting on the worker
        executor.holding = true
        let waypoint = try harness.addLocalWaypoint("Placed just before the app switcher")
        XCTAssertEqual(executor.queuedCount, 1)
        goBackground(key)
        executor.resume()
        assertNoStop(key, reads: reads)
        let journalURL = harness.directory.appendingPathComponent("sync_model_revisions.json")
        XCTAssertFalse(FileManager.default.fileExists(atPath: journalURL.path), "the seal never landed")

        try comeBack(key)
        XCTAssertEqual(journalOnDisk().generation(waypoint.id.uuidString), 1,
                       "the edit's revision record goes down after the unlock")
        XCTAssertEqual(harness.socketCount, 2, "still joined, so it reconnects")
        harness.connect(seq: 2)
        harness.pump(250)
        XCTAssertTrue(harness.sentMutations().contains { $0["id"] as? String == harness.wireId(waypoint.id) })
        XCTAssertNil(manager.lastError)
    }

    func testAPresenceFlushQueuedBeforeTheRelockKeepsTheBackgroundSessionAndSaysNothing() throws {
        let executor = HoldablePersistenceExecutor()
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        let key = InstrumentedDataKey(dek: SyncManagerHarness.testKey)
        try key.install()
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        harness.socket.deliver(harness.peerLoc(counter: 1))
        harness.pump()
        // inside the stride, memory only, so the 60 s flush picks it up
        harness.socket.deliver(harness.peerLoc(counter: 2))
        harness.pump()
        harness.pump(59_999)
        let replayWrites = harness.writes.replayWrites
        executor.holding = true
        harness.pump(1)
        XCTAssertEqual(executor.queuedCount, 1, "the flush seal is waiting on the worker")
        let reads = key.reads
        let socket = harness.socket

        goBackground(key, presence: true)
        executor.resume()
        XCTAssertEqual(harness.writes.replayWrites, replayWrites + 1, "the flush did run, behind the lock")
        assertNoStop(key, reads: reads)
        XCTAssertFalse(socket.isCancelled, "the background presence session carries on")
        XCTAssertEqual(manager.status, .connected)

        try comeBack(key, presence: true)
        XCTAssertEqual(harness.socketCount, 2)
        harness.connect(seq: 2)
        XCTAssertEqual(manager.status, .connected)
        XCTAssertNil(manager.lastError)
    }

    func testTheBackgroundCleanPointNeverReadsTheRelockedKeyAndKeepsTheSafeFloor() throws {
        harness = try SyncManagerHarness()
        let key = InstrumentedDataKey(dek: SyncManagerHarness.testKey)
        try key.install()
        harness.join()
        harness.connect()
        harness.socket.deliver(harness.peerHello())
        harness.socket.deliver(harness.peerLoc(counter: 1))
        harness.pump()
        // inside the stride, memory only, the clean point would make it exact
        harness.socket.deliver(harness.peerLoc(counter: 2))
        harness.pump()
        let reads = key.reads

        // sync-ios-6: enterBackground's clean point runs on main right after the relock
        goBackground(key)
        assertNoStop(key, reads: reads)

        try key.unlock()
        let disk = SyncReplayState(roomId: harness.keys.roomId, containerURL: harness.directory)
        XCTAssertTrue(disk.load())
        XCTAssertFalse(disk.presenceFenceExact, "the clean point failed, the crash floor stays")
        XCTAssertFalse(try disk.acceptPresence(actorId: harness.peerActor, sessionDomain: harness.peerSession, counter: 16))
        XCTAssertTrue(try disk.acceptPresence(actorId: harness.peerActor, sessionDomain: harness.peerSession, counter: 17))
    }

    func testAStartupJournalDrainCutOffByTheRelockResumesAfterUnlock() throws {
        let executor = HoldablePersistenceExecutor()
        executor.holding = true
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        let key = InstrumentedDataKey(dek: SyncManagerHarness.testKey)
        try key.install()
        let waypoint = try harness.addLocalWaypoint("Placed while the journal loads")
        harness.join()
        // the journal read, then the startup drain queues its seal behind the join hop
        executor.runNext()
        XCTAssertEqual(executor.queuedCount, 2)
        let reads = key.reads

        goBackground(key)
        executor.runNext()
        executor.runNext()
        assertNoStop(key, reads: reads)
        XCTAssertEqual(harness.socketCount, 0)

        try key.unlock()
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        executor.resume()
        XCTAssertEqual(journalOnDisk().generation(waypoint.id.uuidString), 1)
        XCTAssertEqual(harness.socketCount, 1, "the join waited for the journal and goes ahead now")
        harness.connect()
        harness.pump(250)
        XCTAssertEqual(manager.status, .connected)
        XCTAssertTrue(harness.sentMutations().contains { $0["id"] as? String == harness.wireId(waypoint.id) })
        XCTAssertNil(manager.lastError)
    }

    /// Join tapped just before leaving the app: its replay state read ran behind
    /// the relock. That used to abandon the join with "Saved rollback-protection
    /// state is locked or damaged" and the user had to join again
    func testAJoinWhoseReplayReadHitTheRelockFinishesAfterUnlock() throws {
        let executor = HoldablePersistenceExecutor()
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        let key = InstrumentedDataKey(dek: SyncManagerHarness.testKey)
        try key.install()
        // a room this device was in before, so there's a sealed state to read
        harness.join()
        harness.connect()
        XCTAssertEqual(manager.status, .connected)
        manager.leave()
        let reads = key.reads

        executor.holding = true
        harness.join()
        // the join's ordering hop, then the replay read, both on the worker
        executor.runNext()
        XCTAssertEqual(executor.queuedCount, 1, "the replay read is waiting on the worker")
        goBackground(key)
        executor.runNext()
        assertNoStop(key, reads: reads)
        XCTAssertEqual(manager.room, harness.joinCode, "still joined, not abandoned")
        XCTAssertEqual(harness.socketCount, 1, "no socket without the replay state")

        // the unlock queues the read again, the worker gets to it after
        try comeBack(key)
        XCTAssertEqual(executor.queuedCount, 1, "read again now")
        executor.resume()
        XCTAssertNil(manager.lastError)
        XCTAssertEqual(harness.socketCount, 2, "the join finished after the unlock")
        harness.connect()
        XCTAssertEqual(manager.status, .connected)
        XCTAssertNil(manager.lastError)
    }

    func testAJournalReadBehindTheRelockAtLaunchIsReadAgainAfterUnlock() throws {
        let executor = HoldablePersistenceExecutor()
        executor.holding = true
        harness = try SyncManagerHarness(persistenceExecutor: executor)
        // an earlier run's sealed journal
        let waypoint = Waypoint(name: "Edited last week", latitude: -33.8, longitude: 151.2,
                                layerID: DrawingLayer.legacyFallbackID)
        let journalURL = harness.directory.appendingPathComponent("sync_model_revisions.json")
        try SafeStore.write(JSONSerialization.data(withJSONObject: ["schemaVersion": 1,
            "generations": [waypoint.id.uuidString: "0000000000000004"]]), to: journalURL, label: "sync/model-revisions")
        let key = InstrumentedDataKey(dek: SyncManagerHarness.testKey)
        try key.install()
        // App Lock at launch: RootGate relocks before ContentView's task configures sync
        goBackground(key)
        let reads = key.reads
        executor.runNext()
        assertNoStop(key, reads: reads)

        // PIN, then the mission data unlock
        try key.unlock()
        manager.updateLifecycle(foregroundReady: true, backgroundPresenceEnabled: false, backgroundInterval: 900)
        XCTAssertEqual(executor.queuedCount, 1, "read again now")
        _ = try harness.waypointStore.addDurably(waypoint)
        executor.resume()
        XCTAssertEqual(journalOnDisk().generation(waypoint.id.uuidString), 5,
                       "the stored generation was read, not started over")
        harness.join()
        harness.connect()
        XCTAssertEqual(manager.status, .connected)
        XCTAssertNil(manager.lastError)
    }
}
