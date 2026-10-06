package com.tacmap.sync

import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.util.DataKey
import com.tacmap.util.DataKeyCache
import com.tacmap.util.SafeStore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What MainActivity.lockForBackground does to Unit Sync's sealed writes: the pause queues a
 * presence clean point on the real persistence worker, UnitSyncRuntime waits (bounded) for the
 * worker, then the DEK locks. The key here is DataKey's own cache and relock gate over a test
 * DEK, so every unwrap (cache fill) and every refused use behind the lock is counted.
 */
class SyncKeyLockBoundaryTest {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sync-key-lock-test") }
    private val io = executor.asCoroutineDispatcher()
    private var harness: SyncHarness? = null
    private val releases = ArrayList<CountDownLatch>()

    /** DataKey's cache + relock gate, the ledger and both store hooks going through it like production. */
    private class InstrumentedDataKey {
        val cache = DataKeyCache()
        val unwraps = AtomicInteger()
        val refusedWhileLocked = AtomicInteger()
        /** auth mode once the 30 s keystore window is gone: the unwrap itself fails */
        @Volatile var authExpired = false
        private val dek = ByteArray(32) { (it * 7 + 3).toByte() }
        private val ledger = ConcurrentHashMap.newKeySet<String>()

        private fun unwrap(): ByteArray {
            if (authExpired) throw DataKey.LockedException()
            unwraps.incrementAndGet()
            return dek.copyOf()
        }

        fun key(): ByteArray = try {
            cache.get(::unwrap)
        } catch (e: DataKey.LockedException) {
            if (cache.isRelocked) refusedWhileLocked.incrementAndGet()
            throw e
        }

        fun lock() = cache.lock()
        fun unlock() = cache.unlock(::unwrap)

        fun install() {
            unlock()
            SafeStore.keyProvider = SafeStore.KeyProvider { key() }
            // the real ledger lives in DataKey's sentinel, so both of these need the DEK as well
            SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
                override fun isSealedOnly(label: String): Boolean {
                    key().fill(0)
                    return label in ledger
                }
                override fun markSealedOnly(label: String) {
                    key().fill(0)
                    ledger += label
                }
            }
        }
    }

    @After fun cleanup() {
        releases.forEach { it.countDown() }
        harness?.manager?.dispose()
        harness?.scope?.cancel()
        // durable completions hop between the worker and the owner, alternate till they're done
        repeat(8) {
            executor.submit { }.get(5, TimeUnit.SECONDS)
            harness?.runCurrent()
        }
        harness?.close()
        io.close()
        SyncHarness.restoreStoreKey()
    }

    private fun pump(run: () -> Unit, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            run()
            Thread.sleep(2)
        }
        run()
        assertTrue("completion did not arrive", ready())
    }

    private fun settle(h: SyncHarness) = repeat(8) {
        executor.submit { }.get(5, TimeUnit.SECONDS)
        h.runCurrent()
    }

    /** a sealed write already running on the worker when Home is pressed, everything queues behind it */
    private fun occupyWorker(): CountDownLatch {
        val release = CountDownLatch(1).also { releases += it }
        val started = CountDownLatch(1)
        executor.execute {
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        return release
    }

    private fun connected(): SyncHarness {
        val h = SyncHarness(persistenceWorker = io).also { harness = it }
        h.join()
        pump(h::runCurrent) { h.transport.sockets.isNotEmpty() }
        h.beginSnapshot(); h.snapshotPage(emptyList()); h.endSnapshot()
        pump(h::runCurrent) { h.socket.sentOfType("hello").isNotEmpty() }
        val hello = h.socket.sentOfType("hello").single()
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true, callsign = "Alpha")))
        return h
    }

    private fun loc(peer: FakeV3Peer, keys: SyncCrypto.V3RoomKeys, counter: Long): JSONObject {
        val payload = PresencePayloadV3("p", "FRIEND", "TEAM", "INFANTRY", false, -35.0, 149.0, 0.0, 0.0)
        val exact = PresencePayloadV3.encode(payload)
        val vs = VersionStamp(counter, peer.actor).encode()
        val preimage = SyncIdentity.buildPreimage(
            SyncIdentity.DOMAIN_PRESENCE, keys.roomIdRaw, peer.actor, peer.sd,
            VersionStamp.counterHex16(counter), "", "loc", SyncIdentity.sha256(exact.bytes),
        )
        val envelope = JSONObject()
        payload.putFlatFields(envelope)
        envelope.put("pv", PresencePayloadV3.ENVELOPE_VERSION)
        envelope.put("p", exact.standardBase64)
        envelope.put("pub", peer.pub)
        envelope.put("sig", SyncSigning.sign(peer.seed, preimage))
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(
            keys.roomKey, envelope.toString().toByteArray(Charsets.UTF_8), SyncCrypto.aadPresenceV3(peer.actor, vs),
        ))
        return JSONObject().put("t", "loc").put("by", peer.actor).put("ct", ct)
            .put("pub", peer.pub).put("sd", peer.sdText).put("vs", vs)
    }

    /** A teammate sharing location: counter 1 is the stride write (inexact on disk), 2 is memory only. */
    private fun teammateSharing(h: SyncHarness): FakeV3Peer {
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        h.deliver(peer.hello())
        pump(h::runCurrent) { h.manager.onlineMembers.value.containsKey(peer.actor) }
        h.deliver(loc(peer, keys, 1))
        pump(h::runCurrent) { h.manager.replayStateForTests!!.getPresenceCounter(peer.actor) == 1L }
        h.deliver(loc(peer, keys, 2))
        pump(h::runCurrent) { h.manager.replayStateForTests!!.getPresenceCounter(peer.actor) == 2L }
        pump(h::runCurrent) { !h.manager.replayStateForTests!!.isPersistenceInFlight }
        return peer
    }

    /** cold reload of what's on disk for the room, after an explicit unlock like the next launch */
    private fun counterOnDisk(h: SyncHarness, key: InstrumentedDataKey, actor: String): Long? {
        key.unlock()
        val disk = SyncReplayState(h.keys().roomId, h.env.filesDir)
        assertTrue(disk.load())
        return disk.getPresenceCounter(actor)
    }

    private fun fix() = PresenceFixSample(
        provider = android.location.LocationManager.GPS_PROVIDER,
        latitude = -35.0,
        longitude = 149.0,
        accuracyMetres = 5.0,
        bearingDegrees = 0.0,
        speedMps = 0.0,
        elapsedRealtimeNanos = harness!!.clock.elapsedRealtimeNanos(),
    )

    private fun assertBackgroundPresenceStillSends(h: SyncHarness) {
        assertTrue(h.manager.isBackgroundPresenceOnly)
        assertFalse("background socket still open", h.socket.terminal)
        assertNull(h.socket.localClose)
        val before = h.socket.sentOfType("loc").size
        assertTrue(h.manager.sendBackgroundPresence(fix(), BackgroundUnitSyncInterval.DEFAULT))
        h.runCurrent()
        assertEquals(before + 1, h.socket.sentOfType("loc").size)
    }

    private fun cleanPointSealsBeforeTheKeyLock(pause: (SyncHarness) -> Boolean) {
        val key = InstrumentedDataKey().also { it.install() }
        val h = connected()
        val peer = teammateSharing(h)
        h.socket.progress()
        h.runCurrent()
        val busy = occupyWorker()
        val releaser = Thread {
            Thread.sleep(250)
            busy.countDown()
        }.apply { start() }

        // MainActivity.lockForBackground: pause Unit Sync, drain the worker, lock the DEK
        assertTrue(pause(h))
        assertTrue("clean point landed inside the bound", h.manager.awaitPersistenceWorkerIdle(5_000))
        val unwrapsAtLock = key.unwraps.get()
        key.lock()
        releaser.join()
        settle(h)

        assertEquals("nothing went for the key behind the lock", 0, key.refusedWhileLocked.get())
        assertEquals("nothing unwrapped the DEK again", unwrapsAtLock, key.unwraps.get())
        assertFalse("general key cache stays empty", key.cache.isCached)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(h.uncaught.isEmpty())
        // the clean point is on disk: exact counter, no +15 crash floor on the next launch
        assertEquals(2L, counterOnDisk(h, key, peer.actor))
    }

    @Test fun suspendedRoomSealsItsCleanPointBeforeTheKeyLocks() =
        cleanPointSealsBeforeTheKeyLock { it.manager.suspendUntilForegroundStores() }

    @Test fun backgroundPresenceSealsItsCleanPointBeforeTheKeyLocks() =
        cleanPointSealsBeforeTheKeyLock { it.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT) }

    @Test fun writeStillSealingPastTheBoundFailsClosedInsteadOfRecachingTheKey() {
        val key = InstrumentedDataKey().also { it.install() }
        val h = connected()
        val peer = teammateSharing(h)
        h.socket.progress()
        h.runCurrent()
        val busy = occupyWorker()

        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        assertFalse("the bound gives up on a stuck worker", h.manager.awaitPersistenceWorkerIdle(100))
        val unwrapsAtLock = key.unwraps.get()
        key.lock()
        busy.countDown()
        pump(h::runCurrent) { !h.manager.replayStateForTests!!.isPersistenceInFlight }
        settle(h)

        assertEquals("no fresh unwrap behind the lock", unwrapsAtLock, key.unwraps.get())
        assertFalse("general key cache stays empty for the whole background", key.cache.isCached)
        assertTrue("the late clean point did go for the key and got refused", key.refusedWhileLocked.get() > 0)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertBackgroundPresenceStillSends(h)
        assertTrue(h.uncaught.isEmpty())
        // a failed clean point keeps the safe floor that was already on disk
        assertEquals(1L + PresenceFencePersistence.CRASH_FLOOR_ADD, counterOnDisk(h, key, peer.actor))
    }

    @Test fun authExpiredRevisionBumpBehindTheLockRaisesNoSecurityStop() {
        val key = InstrumentedDataKey().also { it.install() }
        val h = connected()
        h.socket.progress()
        h.runCurrent()
        val busy = occupyWorker()
        // an edit right before Home: the store write is done, its journal bump queues on the worker
        h.addWaypoint("edited right before Home")
        h.runCurrent()

        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        assertFalse(h.manager.awaitPersistenceWorkerIdle(100))
        key.authExpired = true
        val unwrapsAtLock = key.unwraps.get()
        key.lock()
        busy.countDown()
        settle(h)

        assertTrue("the bump did run behind the lock", key.refusedWhileLocked.get() > 0)
        assertEquals(unwrapsAtLock, key.unwraps.get())
        assertFalse(key.cache.isCached)
        assertNull("no security stop for a bump the pause cut off", h.manager.currentIssueKind)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertBackgroundPresenceStillSends(h)
        assertTrue(h.uncaught.isEmpty())
    }

    private fun generationOnDisk(h: SyncHarness, localId: String): Long {
        val disk = LocalModelRevisionJournal(h.env.filesDir)
        assertTrue(disk.load())
        return disk.generation(localId)
    }

    @Test fun revisionBumpsThePauseCutOffAreRedoneAfterTheUnlockBeforeReconnecting() {
        val key = InstrumentedDataKey().also { it.install() }
        val h = connected()
        h.socket.progress()
        settle(h)
        val busy = occupyWorker()
        // first one's bump is stuck on the worker, the second is committed but the observer
        // hasn't even run for it yet when Home lands
        val first = h.addWaypoint("edited right before Home")
        h.runCurrent()
        val second = h.addWaypoint("edited as Home was pressed")

        assertTrue(h.manager.suspendUntilForegroundStores())
        assertFalse(h.manager.awaitPersistenceWorkerIdle(100))
        val unwrapsAtLock = key.unwraps.get()
        key.lock()
        busy.countDown()
        settle(h)
        assertTrue("the bump did run behind the lock", key.refusedWhileLocked.get() > 0)
        assertEquals("nothing unwrapped the key while locked", unwrapsAtLock, key.unwraps.get())
        assertFalse(key.cache.isCached)
        assertNull(h.manager.currentIssueKind)
        assertEquals(1, h.transport.sockets.size)

        // the user's own unlock, then the stores come back
        key.unlock()
        h.manager.prepareForForegroundUnlock()
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        var atReconnect: Pair<Long, Long>? = null
        pump({
            h.runCurrent()
            if (atReconnect == null && h.transport.sockets.size == 2) {
                atReconnect = generationOnDisk(h, first.id) to generationOnDisk(h, second.id)
            }
        }) { atReconnect != null }
        assertEquals("both lost bumps landed before the reconnect", 1L to 1L, atReconnect)
        settle(h)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertEquals(1L, generationOnDisk(h, first.id))
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun revisionBumpsCutOffWithNoRoomAreRedoneByTheNextManager() {
        val key = InstrumentedDataKey().also { it.install() }
        val before = SyncHarness(persistenceWorker = io)
        settle(before)
        val busy = occupyWorker()
        val first = before.addWaypoint("edited right before Home")
        before.runCurrent()
        val second = before.addWaypoint("edited as Home was pressed")

        // no room joined, so UnitSyncRuntime drops the whole manager on the pause
        before.manager.dispose()
        key.lock()
        busy.countDown()
        settle(before)
        assertTrue(key.refusedWhileLocked.get() > 0)
        assertFalse(key.cache.isCached)
        before.close(deleteFiles = false)

        key.unlock()
        val after = SyncHarness(dir = before.dir, persistenceWorker = io).also { harness = it }
        settle(after)
        assertEquals(1L, generationOnDisk(after, first.id))
        assertEquals(1L, generationOnDisk(after, second.id))
        assertNull(after.manager.currentIssueKind)
        assertTrue(before.uncaught.isEmpty())
        assertTrue(after.uncaught.isEmpty())
    }

    @Test fun aCutOffBumpThatStillLandsAfterTheUnlockIsNotRedoneOnTop() {
        val key = InstrumentedDataKey().also { it.install() }
        val h = connected()
        h.socket.progress()
        settle(h)
        val busy = occupyWorker()
        // its bump is stuck on the worker when Home lands, holding the journal's mutex
        val edited = h.addWaypoint("edited right before Home")
        h.runCurrent()
        assertTrue(h.manager.suspendUntilForegroundStores())
        assertFalse(h.manager.awaitPersistenceWorkerIdle(100))
        key.lock()

        // the user's back before the worker gets to it: the attach notes the edit as stranded,
        // then the old bump lands with the key usable, ahead of the reload and its redo
        key.unlock()
        h.manager.prepareForForegroundUnlock()
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        busy.countDown()
        settle(h)
        pump(h::runCurrent) { h.transport.sockets.size == 2 }
        settle(h)

        assertEquals("one edit, one bump", 1L, generationOnDisk(h, edited.id))
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(h.uncaught.isEmpty())
    }
}
