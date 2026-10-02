package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay state transactions after SP3 (plans/04 section 17): undo log instead
 * of a deep copy per transaction, running high water, batches with exactly one
 * sealed write, and rollback that leaves nothing behind.
 */
class SyncReplayStateBatchTest {
    private val roomId = SyncIdentity.urlB64(ByteArray(32) { 7 })
    private val seed = SyncSigning.generateSeed()
    private val pub = SyncSigning.publicKey(seed)
    private val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(roomId)!!, SyncIdentity.urlB64Decode32(pub)!!)
    private val peerSeed = SyncSigning.generateSeed()
    private val peerPub = SyncSigning.publicKey(peerSeed)
    private val peer = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(roomId)!!, SyncIdentity.urlB64Decode32(peerPub)!!)

    private fun wire(i: Int): String = SyncIdentity.urlB64(java.security.MessageDigest.getInstance("SHA-256")
        .digest("obj$i".toByteArray()))

    private fun hash(i: Int) = SyncIdentity.bytesToHex(SyncIdentity.sha256("c$i".toByteArray()))

    private fun remote(i: Int, counter: Long, deleted: Boolean = false) = SyncReplayState.RemoteMutation(
        SyncReplayState.AuthenticatedMutation(wire(i), VersionStamp(counter, peer), peerPub, if (deleted) null else hash(i), deleted),
        priorModelHash = null,
    )

    @Test
    fun batchIsOneWriteAndCommitsEverything() {
        var writes = 0
        val state = SyncReplayState(roomId, persistOverride = { writes += 1; true })
        assertTrue(state.runBatch {
            for (i in 1..50) assertNotNull(state.reserveLocalPut(wire(i), actor, pub, hash(i)))
            for (i in 51..60) assertTrue(state.commitRemoteAuthenticated(remote(i, 100L + i)))
        })
        assertEquals(1, writes)
        assertEquals(160L, state.roomHighWater())
        assertNotNull(state.getStamp(wire(1)))
        assertTrue(state.hasPendingModelApplications())
        // a clear of every marker is one more write, not one each
        val markers = state.pendingRemoteMutations().map { it.mutation }
        assertTrue(state.clearPendingModelApplications(markers))
        assertEquals(2, writes)
        assertFalse(state.hasPendingModelApplications())
    }

    @Test
    fun failedBatchWriteRollsBackEveryKeyAndCounter() {
        var ok = true
        val state = SyncReplayState(roomId, persistOverride = { ok })
        assertNotNull(state.reserveLocalPut(wire(1), actor, pub, hash(1)))
        val before = state.getStamp(wire(1))
        val counterBefore = state.localCounter
        ok = false
        assertFalse(state.runBatch {
            assertNotNull(state.reserveLocalDelete(wire(1), actor, pub))
            for (i in 2..20) assertNotNull(state.reserveLocalPut(wire(i), actor, pub, hash(i)))
            assertTrue(state.commitRemoteAuthenticated(remote(30, 5_000)))
        })
        assertEquals(before, state.getStamp(wire(1)))
        assertFalse(state.isTombstoned(wire(1)))
        assertEquals(hash(1), state.getContentHash(wire(1)))
        for (i in 2..20) assertNull(state.getStamp(wire(i)))
        assertNull(state.getStamp(wire(30)))
        assertNull(state.getPinnedPubkey(peer))
        assertFalse(state.hasPendingModelApplications())
        assertEquals(counterBefore, state.localCounter)
        assertEquals("running high water rolled back too", counterBefore, state.roomHighWater())
    }

    @Test
    fun abortedBatchLeavesNothingBehind() {
        val state = SyncReplayState(roomId, persistOverride = { true })
        state.beginBatch()
        assertNotNull(state.reserveLocalPut(wire(1), actor, pub, hash(1)))
        state.abortBatch()
        assertNull(state.getStamp(wire(1)))
        assertEquals(0L, state.localCounter)
        // and the next transaction is a normal one again
        assertNotNull(state.reserveLocalPut(wire(2), actor, pub, hash(2)))
        assertFalse(state.isInBatch)
    }

    @Test
    fun runningHighWaterTracksSnapshotsAndReservations() {
        val state = SyncReplayState(roomId, persistOverride = { true })
        assertTrue(state.commitRemoteSnapshot(listOf(remote(1, 40_000), remote(2, 7, deleted = true)), 3))
        assertEquals(40_000L, state.roomHighWater())
        assertEquals(40_001L, state.reserveLocalPut(wire(3), actor, pub, hash(3))!!.counter)
        assertEquals(40_001L, state.roomHighWater())
    }

    /**
     * The old transaction copied all nine maps first, so its cost grew with the
     * room. Measured here as a reservation on a 20k-entry state against just
     * doing the copy the old code did; the undo log only touches the keys it
     * changes.
     */
    @Test
    fun transactionCostDoesNotGrowWithTheState() {
        val state = SyncReplayState(roomId, persistOverride = { true })
        assertTrue(state.commitRemoteSnapshot((1..20_000).map { remote(it, it.toLong(), deleted = it % 2 == 0) }, 1))
        val markers = state.pendingRemoteMutations().map { it.mutation }
        assertTrue(state.clearPendingModelApplications(markers))

        val n = 2_000
        val started = System.nanoTime()
        for (i in 0 until n) assertNotNull(state.reserveLocalPut(wire(100_000 + i), actor, pub, hash(i)))
        val undoMs = (System.nanoTime() - started) / 1_000_000.0

        // what memorySnapshot() used to do before every one of those
        val stamps = HashMap<String, VersionStamp>()
        for (i in 1..20_000) stamps[wire(i)] = VersionStamp(i.toLong(), peer)
        val hashes = HashMap<String, String>()
        for (i in 1..10_000) hashes[wire(i)] = hash(i)
        val copyStarted = System.nanoTime()
        var sink = 0
        for (i in 0 until n) {
            sink += HashMap(stamps).size + HashMap(stamps).size / 2 + HashMap(hashes).size
        }
        val copyMs = (System.nanoTime() - copyStarted) / 1_000_000.0
        println("SP3 $n reservations on a 20k-stamp state: undoLogMs=${"%.1f".format(undoMs)} " +
            "oldDeepCopyAloneMs=${"%.1f".format(copyMs)} ($sink)")
        assertTrue("undo log $undoMs ms vs copy $copyMs ms", undoMs < copyMs)
    }
}
