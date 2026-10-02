package com.tacmap.sync

import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Holds the real SafeStore naming/sealing path on an actual background executor. */
class SyncAsyncPersistenceTest {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "sync-real-persistence-test") }
    private val io = executor.asCoroutineDispatcher()
    private val main = VirtualTimeDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val dir = Files.createTempDirectory("sync-async-persistence").toFile()
    private var harness: SyncHarness? = null
    private var held: CountDownLatch? = null
    private val releases = ArrayList<CountDownLatch>()

    @Before fun setup() { SyncHarness.installStoreKey() }
    @After fun cleanup() {
        held?.countDown()
        releases.forEach { it.countDown() }
        harness?.manager?.dispose()
        harness?.scope?.cancel()
        scope.cancel()
        // Real worker callbacks can arrive after a virtual queue looks empty.
        // Alternate executor barriers and owner turns until cancelled durable
        // jobs have released their order locks before deleting files.
        repeat(8) {
            executor.submit { }.get(5, TimeUnit.SECONDS)
            harness?.runCurrent()
            main.runCurrent()
        }
        harness?.close()
        io.close()
        SyncHarness.restoreStoreKey()
        dir.deleteRecursively()
    }

    private data class Hold(val entered: CountDownLatch, val release: CountDownLatch)
    private fun holdNextRealWrite(fail: Boolean = false): Hold {
        val base = SafeStore.keyProvider
        val armed = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        held = release
        releases += release
        SafeStore.keyProvider = SafeStore.KeyProvider {
            if (Thread.currentThread().name.startsWith("sync-real-persistence-test") && armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS)) { "held writer timed out" }
                if (fail) throw IOException("injected durable writer failure")
            }
            base.key()
        }
        return Hold(entered, release)
    }

    private fun pump(run: () -> Unit = { main.runCurrent() }, ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < deadline) {
            run()
            Thread.sleep(2)
        }
        run()
        assertTrue("completion did not arrive", ready())
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
        return h
    }

    @Test fun explicitLegacyJoinDoesNotRequireAV3RevisionJournal() {
        val files = java.io.File(dir, "files").apply { mkdirs() }
        SafeStore.writeAtomically(java.io.File(files, "sync_model_revisions.json"), "sync/model-revisions", "invalid journal")
        val h = SyncHarness(dir = dir, persistenceWorker = io).also { harness = it }
        h.join(SyncHarness.CODE_V2)
        pump(h::runCurrent) { h.transport.sockets.isNotEmpty() }
        assertEquals(SyncHarness.CODE_V2, h.manager.room.value)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun heldForegroundJournalReloadCannotReactivateAfterASecondPause() {
        val h = connected()
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true, callsign = "Alpha")))
        assertTrue(LocalModelRevisionJournal(h.env.filesDir).bump(java.util.UUID.randomUUID().toString()))
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        h.manager.prepareForForegroundUnlock()
        val hold = holdNextRealWrite()
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        h.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertTrue(h.manager.suspendUntilForegroundStores())
        var workerSettled = false
        h.scope.launch { kotlinx.coroutines.withContext(io) { }; workerSettled = true }
        hold.release.countDown()
        pump(h::runCurrent) { workerSettled }
        assertTrue("second Activity pause keeps mission stores detached", h.manager.isBackgroundPresenceOnly)
        assertEquals("stale reload completion cannot reconnect", 1, h.transport.sockets.size)
        assertFalse(h.manager.chatSessionReady.value)
        assertTrue(h.manager.chatRecipients.value.isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun admittedBoundaryFrameCannotDispatchAfterBackgroundEntry() {
        val h = connected()
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true, callsign = "Alpha")))
        val socket = h.socket
        val peer = FakeV3Peer(h.keys())
        val hold = holdNextRealWrite()
        socket.deliver(peer.hello())
        socket.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 99))
        h.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        hold.release.countDown()
        pump(h::runCurrent) { !h.manager.replayStateForTests!!.isPersistenceInFlight }
        assertTrue(h.manager.isBackgroundPresenceOnly)
        assertFalse("old foreground boundary cannot fail the background transport", socket.terminal)
        assertFalse(h.manager.surfacedIssueCodesForTests.contains(SyncIssueCode.SNAPSHOT_STRUCTURAL.name))
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun admittedBoundaryFrameCannotDispatchAfterLeaveAndRejoin() {
        val h = connected()
        val oldSocket = h.socket
        val peer = FakeV3Peer(h.keys())
        val hold = holdNextRealWrite()
        // Both frames enter one drain: the valid hello stages a batch, and
        // snapshot-begin is already admitted when its boundary flush suspends.
        oldSocket.deliver(peer.hello())
        oldSocket.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 99))
        h.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        h.manager.leave()
        h.join()
        h.runCurrent()
        hold.release.countDown()
        pump(h::runCurrent) { h.transport.sockets.size == 2 }
        assertFalse(h.manager.surfacedIssueCodesForTests.contains(SyncIssueCode.SNAPSHOT_STRUCTURAL.name))
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        h.beginSnapshot(); h.snapshotPage(emptyList()); h.endSnapshot()
        pump(h::runCurrent) { h.socket.sentOfType("hello").isNotEmpty() }
        assertEquals(1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun replayCaptureAndAdoptionTouchOnlyEditedKeysInALargeRoom() {
        val room = SyncIdentity.urlB64(ByteArray(32) { 71 })
        val pub = SyncSigning.publicKey(SyncSigning.generateSeed())
        val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(room)!!, SyncIdentity.urlB64Decode32(pub)!!)
        val state = SyncReplayState(room, dir)
        state.beginBatch()
        repeat(2_000) { index ->
            val wire = SyncIdentity.urlB64(java.nio.ByteBuffer.allocate(32).putInt(index).array())
            assertNotNull(state.reserveLocalPut(wire, actor, pub, "a".repeat(64)))
        }
        assertTrue(state.commitBatch())
        val wire = SyncIdentity.urlB64(java.nio.ByteBuffer.allocate(32).putInt(1_999).array())
        val oldStamp = state.getStamp(wire)
        val hold = holdNextRealWrite()
        state.beginBatch()
        val next = state.reserveLocalPut(wire, actor, pub, "b".repeat(64))!!
        var result: Boolean? = null
        scope.launch { result = state.commitBatchOffMain(io) }
        main.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertEquals(oldStamp, state.getStamp(wire))
        assertEquals("capture visits only stamp/hash patches", 2, state.lastAsyncOwnerPatchCount)
        assertEquals(0, state.runtimeFullCopyCount)
        hold.release.countDown()
        pump { result != null }
        assertEquals(true, result)
        assertEquals(next, state.getStamp(wire))
        assertEquals("capture and adoption each visit two patches", 4, state.lastAsyncOwnerPatchCount)
        assertEquals(0, state.runtimeFullCopyCount)
        val disk = SyncReplayState(room, dir)
        assertTrue(disk.load())
        assertEquals(next, disk.getStamp(wire))
        assertEquals("b".repeat(64), disk.getContentHash(wire))
    }

    @Test fun managerStartupJournalReadDoesNotBlockTheOwnerOrExposeAnUnloadedJournal() {
        val files = java.io.File(dir, "files").apply { mkdirs() }
        assertTrue(LocalModelRevisionJournal(files).bump(java.util.UUID.randomUUID().toString()))
        val hold = holdNextRealWrite()
        val h = SyncHarness(dir = dir, persistenceWorker = io).also { harness = it }
        h.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        var tick = false
        h.scope.launch { tick = true }
        h.runCurrent()
        assertTrue(tick)
        h.join()
        h.runCurrent()
        assertTrue("transport waits for the ordered journal startup load", h.transport.sockets.isEmpty())
        hold.release.countDown()
        pump(h::runCurrent) { h.transport.sockets.isNotEmpty() }
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun heldSnapshotDoesNotAdvanceAuthorityAndLeavesOwnerResponsive() {
        val room = SyncIdentity.urlB64(ByteArray(32) { 33 })
        val pub = SyncSigning.publicKey(SyncSigning.generateSeed())
        val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(room)!!, SyncIdentity.urlB64Decode32(pub)!!)
        val state = SyncReplayState(room, dir)
        val mutation = SyncReplayState.AuthenticatedMutation(SyncIdentity.urlB64(ByteArray(32) { 5 }),
            VersionStamp(1, actor), pub, "a".repeat(64), false)
        val hold = holdNextRealWrite()
        var result: Boolean? = null
        scope.launch { result = state.commitRemoteSnapshotOffMain(
            listOf(SyncReplayState.RemoteMutation(mutation, null)), 7, io) }
        main.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertNull(state.getStamp(mutation.wireObjectId))
        assertEquals(-1L, state.lastSnapshotSeq)
        var tick = false
        scope.launch { tick = true }
        main.runCurrent()
        assertTrue("owner thread must run while the real writer is held", tick)
        assertNull(result)
        hold.release.countDown()
        pump { result != null }
        assertEquals(true, result)
        assertTrue(state.isExactPersistedMutation(mutation))
        val reloaded = SyncReplayState(room, dir)
        assertTrue(reloaded.load())
        assertEquals(7L, reloaded.lastSnapshotSeq)
        assertTrue(reloaded.hasPendingModelApplications())
    }

    @Test fun journalGenerationsAdvanceOnlyAfterRealWorkerSealAndFailedNextSealDoesNotAdvance() {
        val journal = LocalModelRevisionJournal(dir)
        val id = java.util.UUID.randomUUID().toString()
        val firstHold = holdNextRealWrite()
        var first: Boolean? = null
        scope.launch { first = journal.bumpAllOffMain(setOf(id), io) }
        main.runCurrent()
        assertTrue(firstHold.entered.await(5, TimeUnit.SECONDS))
        assertEquals(0L, journal.generation(id))
        var tick = false
        scope.launch { tick = true }
        main.runCurrent()
        assertTrue(tick)
        firstHold.release.countDown()
        pump { first != null }
        assertEquals(true, first)
        assertEquals(1L, journal.generation(id))
        val secondHold = holdNextRealWrite(fail = true)
        var second: Boolean? = null
        scope.launch { second = journal.bumpAllOffMain(setOf(id), io) }
        main.runCurrent()
        assertTrue(secondHold.entered.await(5, TimeUnit.SECONDS))
        assertEquals(1L, journal.generation(id))
        secondHold.release.countDown()
        pump { second != null }
        assertEquals(false, second)
        assertEquals(1L, journal.generation(id))
        val disk = LocalModelRevisionJournal(dir)
        assertTrue(disk.load())
        assertEquals(1L, disk.generation(id))
    }

    @Test fun liveHelloAndModelPublishOnlyAfterRealDurability() {
        val h = connected()
        val peer = FakeV3Peer(h.keys())
        val hold = holdNextRealWrite()
        h.deliver(peer.hello())
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        assertNull(h.manager.replayStateForTests!!.getPinnedPubkey(peer.actor))
        var tick = false
        h.scope.launch { tick = true }
        h.runCurrent()
        assertTrue(tick)
        hold.release.countDown()
        pump(h::runCurrent) { h.manager.onlineMembers.value.containsKey(peer.actor) }
        val wp = Waypoint(name = "accepted after seal", latitude = -35.0, longitude = 149.0)
        val modelHold = holdNextRealWrite()
        h.deliver(peer.waypointRecord(wp, 1, t = "put"))
        assertTrue(modelHold.entered.await(5, TimeUnit.SECONDS))
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertNull(h.manager.replayStateForTests!!.getStamp(peer.wireId(wp.id)))
        modelHold.release.countDown()
        pump(h::runCurrent) { h.waypointStore.committedWaypoints.value.any { it.id == wp.id } &&
            !h.manager.replayStateForTests!!.hasPendingModelApplications() }
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun failedRealLiveWritePublishesNeitherMembershipNorModels() {
        val h = connected()
        val peer = FakeV3Peer(h.keys())
        val hold = holdNextRealWrite(fail = true)
        h.deliver(peer.hello())
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        hold.release.countDown()
        pump(h::runCurrent) { h.manager.status.value == SyncManager.Status.OFFLINE }
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        assertNull(h.manager.replayStateForTests!!.getPinnedPubkey(peer.actor))
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun heldLiveWriteCannotPublishAfterBackgroundEntry() {
        val h = connected()
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true)))
        val peer = FakeV3Peer(h.keys())
        val hold = holdNextRealWrite()
        h.deliver(peer.hello())
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        hold.release.countDown()
        pump(h::runCurrent) { !h.manager.replayStateForTests!!.isPersistenceInFlight }
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        assertTrue(h.manager.peers.value.isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun heldOutboundReservationCannotSendAfterBackgroundEntry() {
        val h = connected()
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true)))
        val wp = h.addWaypoint("local before background")
        h.runCurrent()
        // The mutation journal is a prior write on the same serial executor.
        // Settle it before holding the actual outbound replay reservation.
        var journalSettled = false
        h.scope.launch {
            kotlinx.coroutines.withContext(io) { }
            journalSettled = true
        }
        pump(h::runCurrent) { journalSettled }
        val oldReplay = h.manager.replayStateForTests!!
        val wire = FakeV3Peer(h.keys()).wireId(wp.id)
        val hold = holdNextRealWrite()
        h.advance(300)
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        assertNull(oldReplay.getStamp(wire))
        assertTrue(h.socket.sentOfType("put").isEmpty())
        assertTrue(h.manager.enterBackgroundPresenceOnly(BackgroundUnitSyncInterval.DEFAULT))
        hold.release.countDown()
        pump(h::runCurrent) { !oldReplay.isPersistenceInFlight }
        assertNotNull("reservation is durable for foreground reconciliation", oldReplay.getStamp(wire))
        assertTrue("held reservation completion must not send while backgrounded", h.socket.sentOfType("put").isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun leaveAndRejoinLoadWaitsForDepartedDurableWrite() {
        val h = connected()
        val oldSocket = h.socket
        val oldReplay = h.manager.replayStateForTests!!
        val peer = FakeV3Peer(h.keys())
        val hold = holdNextRealWrite()
        h.deliver(peer.hello())
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        h.manager.leave()
        h.join()
        h.runCurrent()
        assertEquals("fresh load waits for departed writer", 1, h.transport.sockets.size)
        hold.release.countDown()
        pump(h::runCurrent) { h.transport.sockets.size == 2 }
        assertNotSame(oldReplay, h.manager.replayStateForTests)
        assertEquals(peer.pub, h.manager.replayStateForTests!!.getPinnedPubkey(peer.actor))
        assertTrue(h.manager.onlineMembers.value.isEmpty())
        assertEquals(1, oldSocket.sentOfType("hello").size)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test fun departedCleanPointCannotOverwriteNewJoinCounters() {
        val room = SyncIdentity.urlB64(ByteArray(32) { 63 })
        val pub = SyncSigning.publicKey(SyncSigning.generateSeed())
        val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(room)!!, SyncIdentity.urlB64Decode32(pub)!!)
        val sd = SyncIdentity.urlB64(ByteArray(32) { 4 })
        val wire = SyncIdentity.urlB64(ByteArray(32) { 9 })
        val old = SyncReplayState(room, dir)
        assertTrue(old.commitActorHello(actor, pub, sd, "0000000000000001"))
        assertTrue(old.commitPresence(actor, pub, sd, 1))
        val hold = holdNextRealWrite()
        old.beginBatch()
        assertNotNull(old.reserveLocalPut(wire, actor, pub, "c".repeat(64)))
        var first: Boolean? = null
        var clean: Boolean? = null
        var loaded: Boolean? = null
        val fresh = SyncReplayState(room, dir)
        scope.launch { first = old.commitBatchOffMain(io) }
        main.runCurrent()
        assertTrue(hold.entered.await(5, TimeUnit.SECONDS))
        scope.launch { clean = old.persistExactPresenceOffMain(io) }
        scope.launch { loaded = fresh.loadOffMain(actor, pub, io) }
        main.runCurrent()
        hold.release.countDown()
        pump { first != null && clean != null && loaded != null }
        assertEquals(true, first)
        assertEquals(true, loaded)
        fresh.beginBatch()
        val next = fresh.reserveLocalPut(wire, actor, pub, "d".repeat(64))!!
        var second: Boolean? = null
        scope.launch { second = fresh.commitBatchOffMain(io) }
        main.runCurrent()
        pump { second != null }
        assertEquals(true, second)
        var lateClean: Boolean? = null
        scope.launch { lateClean = old.persistExactPresenceOffMain(io) }
        main.runCurrent()
        pump { lateClean != null }
        // An old callback that tries to reserve again after the fresh authority
        // loaded must also fail; arrival order cannot resurrect that writer.
        old.beginBatch()
        assertNotNull(old.reserveLocalPut(wire, actor, pub, "e".repeat(64)))
        var staleWrite: Boolean? = null
        scope.launch { staleWrite = old.commitBatchOffMain(io) }
        main.runCurrent()
        pump { staleWrite != null }
        assertEquals(false, staleWrite)
        val disk = SyncReplayState(room, dir)
        assertTrue(disk.load())
        assertEquals(next, disk.getStamp(wire))
        assertEquals("d".repeat(64), disk.getContentHash(wire))
    }

    @Test fun successfulReservationThenFailedCleanPointCannotPublishANewerPresenceCandidate() {
        val room = SyncIdentity.urlB64(ByteArray(32) { 44 })
        val pub = SyncSigning.publicKey(SyncSigning.generateSeed())
        val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(room)!!, SyncIdentity.urlB64Decode32(pub)!!)
        val sd = SyncIdentity.urlB64(ByteArray(32) { 7 })
        val wire = SyncIdentity.urlB64(ByteArray(32) { 8 })
        var writes = 0
        val firstEntered = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val lastEntered = CountDownLatch(1)
        val lastRelease = CountDownLatch(1)
        releases += firstRelease
        releases += lastRelease
        held = firstRelease
        val state = SyncReplayState(room, persistOverride = {
            writes += 1
            when (writes) {
                3 -> { firstEntered.countDown(); check(firstRelease.await(5, TimeUnit.SECONDS)); true }
                4 -> false // exact clean point follows the successful reservation
                5 -> { lastEntered.countDown(); check(lastRelease.await(5, TimeUnit.SECONDS)); true }
                else -> true
            }
        })
        assertTrue(state.commitActorHello(actor, pub, sd, "0000000000000001"))
        assertTrue(state.commitPresence(actor, pub, sd, 1))
        state.beginBatch()
        assertNotNull(state.reserveLocalPut(wire, actor, pub, "b".repeat(64)))
        var reserved: Boolean? = null
        var clean: Boolean? = null
        var presence: Boolean? = null
        scope.launch { reserved = state.commitBatchOffMain(io) }
        main.runCurrent()
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        scope.launch { clean = state.persistExactPresenceOffMain(io) }
        scope.launch {
            state.awaitPersistence()
            state.beginBatch()
            assertTrue(state.commitPresence(actor, pub, sd, 17))
            presence = state.commitBatchOffMain(io)
        }
        main.runCurrent()
        assertEquals("queued clean point must not mutate or seal during the held reservation", 3, writes)
        assertNull(state.getStamp(wire))
        assertTrue(state.canAcceptPresence(actor, pub, sd, 17))
        firstRelease.countDown()
        pump { reserved == true && clean == false && lastEntered.count == 0L }
        held = lastRelease
        assertNotNull(state.getStamp(wire))
        assertTrue("later presence is private until its own seal completes", state.canAcceptPresence(actor, pub, sd, 17))
        assertNull(presence)
        lastRelease.countDown()
        pump { presence != null }
        assertEquals(true, presence)
        assertFalse(state.canAcceptPresence(actor, pub, sd, 17))
        assertTrue(state.canAcceptPresence(actor, pub, sd, 18))
        assertEquals(5, writes)
    }
}
