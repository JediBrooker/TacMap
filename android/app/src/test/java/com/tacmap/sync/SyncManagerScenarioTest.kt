package com.tacmap.sync

import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.export.GeoJsonExporter
import com.tacmap.export.GeoJsonImporter
import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.util.UUID

/**
 * State machine scripts driven through the S6-02 seam: real SyncManager, fake
 * socket, virtual time. Each test is the Prove-It for one audited defect and
 * stays as its regression.
 */
class SyncManagerScenarioTest {
    private lateinit var h: SyncHarness
    private val toasts = ArrayList<String>()

    @Before fun setUp() {
        SyncHarness.installStoreKey()
        h = SyncHarness()
        h.scope.launch { h.manager.remoteUpdates.collect { toasts += it } }
        h.runCurrent()
    }

    @After fun tearDown() {
        h.close()
        SyncHarness.restoreStoreKey()
    }

    private fun connected(items: List<JSONObject> = emptyList(), seq: Long = 1): JSONObject {
        h.join()
        return h.completeHandshake(items, seq)
    }

    private fun waypoint(name: String, id: String = UUID.randomUUID().toString()) =
        Waypoint(id = id, name = name, latitude = -35.0, longitude = 149.0, createdAt = 1_700_000_000_000L)

    /** Step virtual time until the manager opens another socket. */
    private fun awaitNewSocket(maxMs: Long = 600_000L): FakeSocket {
        val count = h.transport.sockets.size
        var waited = 0L
        while (h.transport.sockets.size == count) {
            check(waited < maxMs) { "no reconnect within $maxMs ms" }
            h.advance(50)
            waited += 50
        }
        return h.socket
    }

    private fun putsFor(socket: FakeSocket, wireId: String) = socket.sentOfType("put").filter { it.getString("id") == wireId }

    private fun ackEverything(socket: FakeSocket, already: MutableSet<String>) {
        for (frame in socket.sentFrames()) {
            if (frame.optString("t") !in setOf("put", "del")) continue
            if (!already.add(frame.getString("rid") + frame.getString("ct").hashCode())) continue
            h.ackPut(frame, socket)
        }
    }

    // ---- happy path ------------------------------------------------------

    @Test
    fun happyJoinReachesConnectedThroughTheSeam() {
        h.join()
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncManager.Status.CONNECTING, h.manager.status.value)
        h.completeHandshake()
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertTrue(h.socket.url.contains("/v3/room/"))
        assertEquals("3", h.socket.headers["X-Protocol"])
    }

    // ---- S6-03 / S3-15: stale is an LWW loss, not a security event ---------

    @Test
    fun staleNackDoesNotReconnectOrRaiseSecurity() {
        connected()
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        val local = h.addWaypoint("A")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        val counter = VersionStamp.parse(put.getString("vs"))!!.counter
        h.deliver(peer.waypointRecord(local.copy(name = "B"), counter + 1, t = "put"))
        assertEquals("B", h.waypointStore.committedWaypoints.value.single().name)

        h.nack(put, "stale", retry = false)
        h.advance(5_000)

        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(1, h.transport.sockets.size)
        assertFalse(h.socket.terminal)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertEquals("B", h.waypointStore.committedWaypoints.value.single().name)
        assertEquals("no republish of X", 1, putsFor(h.socket, put.getString("id")).size)
    }

    @Test
    fun staleOnOurOwnPersistedStampIsTreatedAsConfirmed() {
        connected()
        h.addWaypoint("mine")
        h.advance(300)
        val first = h.socket.sentOfType("put").single()
        // ack lost, socket drops, relay actually stored it
        h.socket.serverClose(1006)
        h.advance(2_000)
        h.completeHandshake()
        h.advance(300)
        val resend = h.socket.sentOfType("put").single()
        assertEquals(first.getString("vs"), resend.getString("vs"))
        h.nack(resend, "stale", retry = false)
        h.advance(5_000)
        assertFalse(h.socket.terminal)
        assertEquals(2, h.transport.sockets.size)
        assertEquals(1, h.socket.sentOfType("put").size)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
    }

    // ---- S2-10 / S3-03 / S4-01: one bad record must not brick the room ----

    @Test
    fun poisonRecordsAreSkippedNotFatal() {
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        val w1 = waypoint("good")
        val w2 = waypoint("garbage")
        val w3 = waypoint("future")
        // a stale local copy of the unreadable object must not be pushed over it
        assertTrue(h.waypointStore.add(w2.copy(name = "old local copy")))
        val good = peer.waypointRecord(w1, 3)
        val garbage = peer.record(
            peer.wireId(w2.id), 4, "waypoint", FakeV3Peer.waypointContent(w2),
            ctOverride = SyncCrypto.encodeBase64(ByteArray(64) { (it * 7).toByte() }),
        )
        val future = peer.record(peer.wireId(w3.id), 5, "route", """{"type":"FeatureCollection","features":[]}""")

        h.join()
        h.beginSnapshot()
        h.snapshotPage(listOf(good, garbage, future))
        h.endSnapshot()
        val hellos = h.socket.sentOfType("hello")
        assertEquals("hello must still be sent", 1, hellos.size)
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hellos[0].getString("by"))
            .put("sd", hellos[0].getString("sd")).put("vs", hellos[0].getString("vs")))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)

        val names = h.waypointStore.committedWaypoints.value.associate { it.id to it.name }
        assertEquals("good", names[w1.id])
        assertEquals("old local copy", names[w2.id])
        assertNull(names[w3.id])
        val replay = h.manager.replayStateForTests!!
        assertNotNull(replay.getStamp(peer.wireId(w1.id)))
        assertNull(replay.getStamp(peer.wireId(w2.id)))
        assertNull(replay.getStamp(peer.wireId(w3.id)))
        assertEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(toasts.any { it.contains("failed verification") })
        assertTrue(toasts.any { it.contains("can't display") })

        h.advance(500)
        assertTrue("untouched local copy stays put", putsFor(h.socket, peer.wireId(w2.id)).isEmpty())

        // same snapshot after a reconnect: nothing new to say, no extra reconnects
        val toastCount = toasts.count { it.contains("Some synced objects") }
        h.socket.serverClose(1006)
        h.advance(2_000)
        assertEquals(2, h.transport.sockets.size)
        h.completeHandshake(listOf(good, garbage, future))
        h.advance(500)
        assertEquals(toastCount, toasts.count { it.contains("Some synced objects") })
        assertEquals(2, h.transport.sockets.size)
        assertTrue(putsFor(h.socket, peer.wireId(w2.id)).isEmpty())

        // a real local edit does get published, once
        assertTrue(h.waypointStore.update(h.waypointStore.committedWaypoints.value.first { it.id == w2.id }.copy(name = "edited")))
        h.advance(500)
        assertEquals(1, putsFor(h.socket, peer.wireId(w2.id)).size)
    }

    @Test
    fun tombstoneWithExtraInnerKeysIsSkippedOnAndroidToo() {
        val peer = FakeV3Peer(h.keys())
        val wp = waypoint("x")
        val tomb = peer.record(peer.wireId(wp.id), 4, "del", null, deleted = true, innerExtra = mapOf("c" to "smuggled"))
        connected(listOf(tomb))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertNull(h.manager.replayStateForTests!!.getStamp(peer.wireId(wp.id)))
    }

    @Test
    fun duplicateWireIdRejectsWholeSnapshotThenStopsAfterThree() {
        val peer = FakeV3Peer(h.keys())
        val rec = peer.waypointRecord(waypoint("dup"), 3)
        h.join()
        repeat(3) { attempt ->
            assertEquals(attempt + 1, h.transport.sockets.size)
            val socket = h.socket
            h.beginSnapshot()
            h.snapshotPage(listOf(rec, rec))
            h.endSnapshot()
            assertTrue(socket.sentOfType("hello").isEmpty())
            assertTrue(socket.terminal)
            assertEquals(-1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
            if (attempt < 2) awaitNewSocket()
        }
        h.advance(30 * 60_000L)
        assertEquals("third structural failure stops", 3, h.transport.sockets.size)
        assertEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(h.manager.pausedActionRequired.value)
    }

    // ---- S3-02 / S6-01: counter-window pauses writes, no loop --------------

    @Test
    fun counterWindowPausesMutationsOncePerJoin() {
        connected()
        h.addWaypoint("one")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        h.nack(put, "counter-window", retry = false)
        h.advance(5_000)
        assertFalse("no reconnect", h.socket.terminal)
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncIssueKind.CONNECTION, h.manager.currentIssueKind)
        val surfaced = toasts.count { it.contains("reset by the relay") }
        assertEquals(1, surfaced)

        h.addWaypoint("two")
        h.advance(500)
        assertEquals(1, h.socket.sentOfType("put").size)

        h.socket.serverClose(1006)
        h.advance(2_000)
        h.completeHandshake()
        h.addWaypoint("three")
        h.advance(500)
        assertTrue(h.socket.sentOfType("put").isEmpty())
        assertTrue(h.socket.sentOfType("del").isEmpty())
        assertEquals(1, toasts.count { it.contains("reset by the relay") })
    }

    @Test
    fun seqRegressionIsSurfacedOncePerJoin() {
        connected(seq = 90)
        h.socket.serverClose(1006)
        h.advance(2_000)
        h.completeHandshake(seq = 4)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(1, toasts.count { it.contains("older") })
        h.socket.serverClose(1006)
        h.advance(5_000)
        h.completeHandshake(seq = 5)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(1, toasts.count { it.contains("older") })
        assertEquals(90L, h.manager.replayStateForTests!!.lastSnapshotSeq)
    }

    // ---- S2-11: close codes and upgrade statuses --------------------------

    @Test
    fun close4013StopsInsteadOfReconnecting() {
        connected()
        h.socket.serverClose(4013, "room quota")
        h.advance(30 * 60_000L)
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncManager.Status.OFFLINE, h.manager.status.value)
        assertTrue(h.manager.pausedActionRequired.value)
        assertTrue(toasts.any { it.contains("full") })
    }

    @Test
    fun upgrade503UsesTheSlowBusyFloor() {
        h.join()
        h.socket.rejectUpgrade(503)
        h.advance(14_000)
        assertEquals(1, h.transport.sockets.size)
        h.advance(30_000)
        assertEquals(2, h.transport.sockets.size)
    }

    @Test
    fun upgrade401StopsAndRetryReconnects() {
        h.join()
        h.socket.rejectUpgrade(401)
        h.advance(30 * 60_000L)
        assertEquals(1, h.transport.sockets.size)
        assertTrue(h.manager.pausedActionRequired.value)
        assertTrue(toasts.any { it.contains("refused this room") })
        h.manager.retryAfterPause()
        h.runCurrent()
        assertEquals(2, h.transport.sockets.size)
        assertFalse(h.manager.pausedActionRequired.value)
    }

    @Test
    fun close4011IsAnIdentitySecurityStop() {
        connected()
        h.socket.serverClose(4011, "invalid actor proof")
        h.advance(10 * 60_000L)
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
    }

    @Test
    fun close4008StartsTheNextSessionWithAHalfFullPacer() {
        h.waypointStore.addAll((1..40).map { waypoint("w$it") })
        connected()
        h.socket.serverClose(1006, "plain drop")
        val normal = awaitNewSocket()
        h.completeHandshake(socket = normal)
        val normalBurst = normal.sentOfType("put").size
        normal.serverClose(4008, "rate")
        val throttled = awaitNewSocket()
        h.completeHandshake(socket = throttled)
        // 15 frame tokens: hello and chat-key take two, mutations keep the 10 reserve
        val throttledBurst = throttled.sentOfType("put").size
        assertTrue("$throttledBurst after 4008", throttledBurst <= 3)
        assertTrue("$normalBurst after a plain drop", normalBurst > throttledBurst)
    }

    // ---- S2-02 / S2-03 / S3-05: watchdogs ------------------------------------

    @Test
    fun connectOpenTimeoutRecoversAStuckUpgrade() {
        h.join()
        h.advance(29_000)
        assertFalse(h.socket.terminal)
        h.advance(1_500)
        assertTrue(h.socket.terminal)
        h.advance(2_000)
        assertEquals(2, h.transport.sockets.size)
    }

    @Test
    fun slowButProgressingSnapshotIsNotKilled() {
        val peer = FakeV3Peer(h.keys())
        h.join()
        h.beginSnapshot()
        repeat(3) { i ->
            h.advance(25_000)
            h.snapshotPage(listOf(peer.waypointRecord(waypoint("p$i"), 3L + i)), more = i < 2)
        }
        h.advance(25_000)
        h.endSnapshot()
        val hello = h.socket.sentOfType("hello").single()
        h.advance(10_000)
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(1, h.transport.sockets.size)
    }

    @Test
    fun transportByteProgressKeepsTheStallWatchdogQuiet() {
        h.join()
        val socket = h.socket
        h.beginSnapshot()
        // one huge page trickling in: bytes arrive, no complete message for 90 s
        repeat(9) {
            h.advance(10_000)
            socket.progress()
        }
        h.runCurrent()
        assertFalse(socket.terminal)
        h.advance(61_000)
        assertTrue(socket.terminal)
    }

    @Test
    fun helloAckTimeoutReconnects() {
        h.join()
        val socket = h.socket
        h.beginSnapshot()
        h.snapshotPage(emptyList())
        h.endSnapshot()
        h.advance(29_000)
        assertFalse(socket.terminal)
        h.advance(2_000)
        assertTrue(socket.terminal)
        assertEquals(2, h.transport.sockets.size)
    }

    // ---- S2-04 / S4-02: receive budget scales with the room ------------------

    @Test
    fun bulkImportFromOnePeerDoesNotCloseUs() {
        connected()
        val peers = (1..5).map { FakeV3Peer(h.keys()) }
        peers.forEach { h.deliver(it.hello()) }
        repeat(199) { h.socket.deliver("""{"t":"put","id":"x"}""") }
        repeat(40) { h.socket.deliver("""{"t":"loc","by":"x"}""") }
        repeat(199) { h.socket.deliver("""{"t":"op-ack","av":1}""") }
        h.runCurrent()
        assertFalse(h.socket.terminal)
        // 725 room frames is the limit with 5 sessions; the hellos used 5 of them
        repeat(725 - 239 - 5) { h.socket.deliver("""{"t":"loc","by":"x"}""") }
        h.runCurrent()
        assertFalse(h.socket.terminal)
        h.socket.deliver("""{"t":"loc","by":"x"}""")
        h.runCurrent()
        assertTrue(h.socket.terminal)
        assertEquals(1008, h.socket.localClose?.first)
    }

    // ---- S3-06 / S5-09: retransmit timer starts at write ---------------------

    @Test
    fun retransmitWaitsForTheWriteToFinish() {
        connected()
        h.socket.unwrittenBytes = 50_000_000L
        val wp = h.addWaypoint("slow uplink")
        h.advance(300)
        val wire = h.socket.sentOfType("put").single().getString("id")
        h.advance(12_000)
        assertEquals("no copies while the first one is still queued", 1, putsFor(h.socket, wire).size)
        h.socket.unwrittenBytes = 0L
        h.advance(250)
        h.advance(4_600)
        assertEquals(1, putsFor(h.socket, wire).size)
        h.advance(400)
        assertEquals(2, putsFor(h.socket, wire).size)
        assertFalse(h.socket.terminal)
        assertNotNull(wp)
    }

    @Test
    fun ackLossRetriesTwiceThenReconnectsAsAConnectionIssue() {
        connected()
        h.addWaypoint("lost acks")
        h.advance(300)
        val wire = h.socket.sentOfType("put").single().getString("id")
        h.advance(5_100)
        assertEquals(2, putsFor(h.socket, wire).size)
        h.advance(10_100)
        assertEquals(3, putsFor(h.socket, wire).size)
        assertFalse(h.socket.terminal)
        h.advance(20_100)
        assertTrue(h.socket.terminal)
        assertEquals(SyncIssueKind.CONNECTION, h.manager.currentIssueKind)
        h.advance(2_000)
        assertEquals(2, h.transport.sockets.size)
    }

    // ---- S3-07 / S6-07: bulk publish is paced -------------------------------

    @Test
    fun bulkPublishStaysUnderClientPacing() {
        h.waypointStore.addAll((1..300).map { waypoint("bulk$it") })
        connected()
        val acked = HashSet<String>()
        val sendTimes = ArrayList<Long>()
        var seen = 0
        repeat(80) {
            h.advance(500)
            val frames = h.socket.sent
            while (seen < frames.size) {
                sendTimes += h.now
                seen += 1
            }
            ackEverything(h.socket, acked)
        }
        assertFalse(h.socket.terminal)
        assertEquals(300, h.socket.sentOfType("put").map { it.getString("id") }.toSet().size)
        for (start in sendTimes) {
            val inWindow = sendTimes.count { it >= start && it < start + 10_000 }
            assertTrue("$inWindow frames in 10 s", inWindow <= 150)
        }
    }

    // ---- S3 verifier note 2: backoff resets only on a stable session -----------

    @Test
    fun backoffIsNotResetByHelloAck() {
        h.randomValue = 1.0
        h.join()
        val t0 = h.now
        h.socket.fail()
        h.runCurrent()
        while (h.transport.sockets.size < 2) h.advance(50)
        val first = h.transport.sockets[1].createdAtMs - t0
        h.completeHandshake()
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        val t1 = h.now
        h.socket.serverClose(1006)
        h.runCurrent()
        while (h.transport.sockets.size < 3) h.advance(50)
        val second = h.transport.sockets[2].createdAtMs - t1
        assertTrue("delay grew: $first then $second", second > first)
    }

    @Test
    fun firstOpAckResetsTheBackoff() {
        h.randomValue = 1.0
        h.join()
        h.socket.fail()
        h.advance(1_100)
        h.completeHandshake()
        h.addWaypoint("acked")
        h.advance(300)
        h.ackPut(h.socket.sentOfType("put").single())
        val t1 = h.now
        h.socket.serverClose(1006)
        h.runCurrent()
        while (h.transport.sockets.size < 3) h.advance(50)
        assertTrue(h.transport.sockets[2].createdAtMs - t1 <= 1_050)
    }

    // ---- S3-08: snapshot layer metadata staged in item order -----------------

    @Test
    fun snapshotLayerMetadataIsStagedInItemOrder() {
        val peer = FakeV3Peer(h.keys())
        fun drawing(name: String) = DrawingFeature(
            name = name, geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)), layerId = "L",
        )
        val d1 = drawing("d1")
        val d2 = drawing("d2")
        val c1 = GeoJsonExporter.export(emptyList(), listOf(d1), listOf(DrawingLayer(id = "L", name = "Recon", createdAt = 1)), 1f)
        val c2 = GeoJsonExporter.export(emptyList(), listOf(d2), listOf(DrawingLayer(id = "L", name = "Scouts", createdAt = 1)), 1f)
        connected(listOf(
            peer.record(peer.wireId(d1.id), 3, "drawing", c1),
            peer.record(peer.wireId(d2.id), 4, "drawing", c2),
        ))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        val doc: DrawingDocument = h.drawingStore.committedDocument.value
        assertEquals(setOf(d1.id, d2.id), doc.features.map { it.id }.toSet())
        assertEquals("Recon", doc.layers.single { it.id == "L" }.name)
    }

    // ---- SP1 review: live window resync -------------------------------------

    @Test
    fun authenticatedLiveRecordPastTheWindowTriggersOneResync() {
        connected()
        val first = h.socket
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        h.deliver(peer.waypointRecord(waypoint("far"), 20_000, t = "put"))
        assertTrue(first.terminal)
        h.runCurrent()
        assertEquals("immediate reconnect", 2, h.transport.sockets.size)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        val second = h.socket
        h.completeHandshake(socket = second)
        h.deliver(peer.hello(), second)
        h.deliver(peer.waypointRecord(waypoint("far2"), 30_000, t = "put"), second)
        assertFalse("cooldown", second.terminal)
        h.advance(61_000)
        assertTrue("remembered resync after cooldown", second.terminal)
    }

    // ---- S3-11: hello epoch recovery ---------------------------------------

    @Test
    fun lostReplayStateStartsTheEpochAtTheTimeFloor() {
        val hello = connected()
        val epoch = BigInteger(hello.getString("vs").substringBefore(':'), 16)
        assertTrue(epoch >= BigInteger.valueOf(SyncHarness.WALL_MS / 60_000L))
    }

    @Test
    fun close4014DoublesTheRejectedEpochThenStopsAfterThree() {
        val hello = connected()
        var rejected = BigInteger(hello.getString("vs").substringBefore(':'), 16)
        repeat(3) {
            h.socket.serverClose(4014, "stale hello epoch")
            awaitNewSocket()
            h.beginSnapshot()
            h.snapshotPage(emptyList())
            h.endSnapshot()
            val next = BigInteger(h.socket.sentOfType("hello").single().getString("vs").substringBefore(':'), 16)
            assertTrue("$next >= 2 x $rejected", next >= rejected.shiftLeft(1))
            rejected = next
        }
        h.socket.serverClose(4014, "stale hello epoch")
        h.advance(10 * 60_000L)
        assertEquals(4, h.transport.sockets.size)
        assertTrue(h.manager.pausedActionRequired.value)
    }

    // ---- S1-09: pre-send size check ------------------------------------------

    @Test
    fun objectTooLargeIsNeverReservedOrSent() {
        connected()
        val counterBefore = h.manager.replayStateForTests!!.localCounter
        val big = waypoint("huge").copy(notes = "n".repeat(600_000))
        assertTrue(h.waypointStore.add(big))
        h.advance(500)
        assertTrue(h.socket.sentOfType("put").isEmpty())
        assertEquals(counterBefore, h.manager.replayStateForTests!!.localCounter)
        assertEquals(1, toasts.count { it.contains("too large") })
        h.addWaypoint("small")
        h.advance(500)
        assertEquals(1, h.socket.sentOfType("put").size)
        assertEquals(1, toasts.count { it.contains("too large") })
    }

    // ---- S2-13: reachability -------------------------------------------------

    @Test
    fun networkAvailablePullsInATransientReconnect() {
        h.randomValue = 1.0
        h.join()
        repeat(7) {
            h.socket.fail("no route")
            h.runCurrent()
            val count = h.transport.sockets.size
            while (h.transport.sockets.size == count) h.advance(100)
        }
        val start = h.now
        h.socket.fail("no route")
        h.runCurrent()
        val listener = h.reachability.listener
        assertNotNull("SyncManager must watch the default network", listener)
        h.advance(1_000)
        listener!!.onNetworkAvailable()
        h.runCurrent()
        h.advance(1_100)
        assertEquals(9, h.transport.sockets.size)
        assertTrue(h.now - start < 5_000)
    }

    // ---- S2-01: Android keeps the room across a pause --------------------------

    @Test
    fun pauseKeepsAV3RoomWithoutLocationOptIn() = pauseKeepsRoom(SyncHarness.CODE)

    @Test
    fun pauseKeepsAV2Room() = pauseKeepsRoom(SyncHarness.CODE_V2)

    private fun pauseKeepsRoom(code: String) {
        h.join(code)
        h.socket.open()
        h.runCurrent()
        val manager = h.manager
        assertTrue(manager.suspendUntilForegroundStores())
        h.runCurrent()
        assertEquals(code, manager.room.value)
        assertTrue(manager.isBackgroundPresenceOnly)
        val sockets = h.transport.sockets.size
        val reattached = manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null }
        h.runCurrent()
        assertTrue(reattached)
        assertEquals(code, manager.room.value)
        assertEquals(sockets + 1, h.transport.sockets.size)
    }

    // ---- S3-01 / S3-14: v2 legacy ---------------------------------------------

    private fun v2Record(rawId: String, v: Long, by: String, content: String, keys: SyncCrypto.RoomKeys): JSONObject {
        val seed = v2Seeds.getOrPut(by) { SyncSigning.generateSeed() }
        val pub = SyncSigning.publicKey(seed)
        val sig = SyncSigning.sign(seed, SyncSigning.objectMessage(rawId, v, "waypoint", by, content))
        val inner = JSONObject().put("c", content).put("pub", pub).put("sig", sig)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(keys.roomKey, inner.toString().toByteArray(), SyncCrypto.aad(rawId, v, "waypoint")))
        return JSONObject().put("id", rawId).put("v", v).put("by", by).put("kind", "waypoint").put("ct", ct)
    }
    private val v2Seeds = HashMap<String, ByteArray>()

    private fun v2Connected(items: List<JSONObject> = emptyList(), harness: SyncHarness = h) {
        harness.join(SyncHarness.CODE_V2)
        harness.socket.open()
        harness.runCurrent()
        harness.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 1))
        harness.deliver(JSONObject().put("t", "snapshot").put("items", org.json.JSONArray(items)).put("more", false)
            .put("members", org.json.JSONArray()))
        harness.deliver(JSONObject().put("t", "snapshot-end").put("seq", 1))
    }

    @Test
    fun v2AcceptsAnIosUppercaseRecordId() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val wp = waypoint("from ios")
        val content = FakeV3Peer.waypointContent(wp)
        v2Connected(listOf(v2Record(wp.id.uppercase(), 5, "c0ffee00-0000-4000-8000-000000000001", content, keys)))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals("from ios", h.waypointStore.committedWaypoints.value.singleOrNull { it.id == wp.id }?.name)
        h.advance(500)
        assertTrue("no echo delete", h.socket.sentOfType("del").isEmpty())
    }

    @Test
    fun v2UppercaseEmbeddedIdLandsOnTheLowercaseKeyWithoutAnEchoDelete() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val wp = waypoint("shouty")
        val content = FakeV3Peer.waypointContent(wp.copy(id = wp.id.uppercase()))
        v2Connected(listOf(v2Record(wp.id.uppercase(), 5, "c0ffee00-0000-4000-8000-000000000001", content, keys)))
        assertEquals(listOf(wp.id), h.waypointStore.committedWaypoints.value.map { it.id })
        h.advance(500)
        assertTrue(h.socket.sentOfType("del").isEmpty())
    }

    @Test
    fun v2EqualVersionGoesToTheLargerBy() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        v2Connected()
        val wp = h.addWaypoint("mine")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        val v = put.getLong("v")
        val theirs = wp.copy(name = "theirs")
        h.deliver(v2Record(wp.id, v, "ffffffff-ffff-4fff-bfff-ffffffffffff", FakeV3Peer.waypointContent(theirs), keys).put("t", "put"))
        assertEquals("theirs", h.waypointStore.committedWaypoints.value.single().name)
    }

    // ---- gap-v2-room-2x-interop-2: a 2.x iOS object keeps its uppercase v2 id ----

    private fun v2Del(rawId: String, v: Long, by: String, keys: SyncCrypto.RoomKeys): JSONObject {
        val seed = v2Seeds.getOrPut(by) { SyncSigning.generateSeed() }
        val sig = SyncSigning.sign(seed, SyncSigning.objectMessage(rawId, v, "del", by, ""))
        val inner = JSONObject().put("pub", SyncSigning.publicKey(seed)).put("sig", sig)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(keys.roomKey, inner.toString().toByteArray(), SyncCrypto.aad(rawId, v, "del")))
        return JSONObject().put("t", "del").put("id", rawId).put("v", v).put("by", by).put("ct", ct)
    }

    /**
     * Shipped 2.x iOS v2 sync (fc6261c SyncManager.swift), only the bits that
     * decide echoes. versions and lastContent are keyed by the raw wire id, the
     * model by UUID value, and reexport only matches the uppercase uuidString.
     * So a lowercase put leaves an empty baseline and the next diff echoes a put
     * of the uppercase id plus a del of the lowercase one. Acks land at once.
     */
    private class Shipped2xIos(private val keys: SyncCrypto.RoomKeys) {
        val clientId = "0F1E2D3C-4B5A-4978-8695-A4B3C2D1E0F9"
        private val seed = SyncSigning.generateSeed()
        private val pub = SyncSigning.publicKey(seed)
        private var clock = 0L
        private val versions = HashMap<String, Long>()
        private val lastContent = HashMap<String, String>()
        /** What the 2.x iOS user has, UUID value to GeoJSON. */
        val model = LinkedHashMap<UUID, String>()

        private fun reexport(id: String): String =
            model.entries.firstOrNull { it.key.toString().uppercase() == id }?.value ?: ""

        /** syncLocalState: a put per changed uuidString, a del per baseline that's gone. */
        fun diff(): List<JSONObject> {
            val current = model.entries.associate { it.key.toString().uppercase() to it.value }
            val out = ArrayList<JSONObject>()
            for ((id, content) in current) {
                if (lastContent[id] == content) continue
                clock += 1
                versions[id] = clock
                out += seal(id, clock, "waypoint", content)
                lastContent[id] = content
            }
            for (id in lastContent.keys.filter { it !in current }) {
                clock += 1
                out += seal(id, clock, "del", "")
                lastContent.remove(id)
            }
            return out
        }

        /** applyRecord / applyDelete, AEAD and signature over the raw id. */
        fun receive(frame: JSONObject) {
            val id = frame.getString("id")
            val v = frame.getLong("v")
            val del = frame.optString("t") == "del" || frame.optBoolean("deleted")
            val kind = if (del) "del" else frame.getString("kind")
            if ((versions[id] ?: -1L) >= v) return
            val plain = SyncCrypto.open(keys.roomKey, SyncCrypto.decodeBase64(frame.getString("ct")), SyncCrypto.aad(id, v, kind))
                ?: return
            val inner = JSONObject(String(plain, Charsets.UTF_8))
            val content = if (del) "" else inner.getString("c")
            val signed = SyncSigning.objectMessage(id, v, kind, frame.getString("by"), content)
            if (!SyncSigning.verify(inner.getString("pub"), signed, inner.getString("sig"))) return
            // UUID(uuidString:) takes either case
            val uuid = UUID.fromString(id)
            clock = maxOf(clock, v)
            versions[id] = v
            if (del) {
                model.remove(uuid)
                lastContent.remove(id)
            } else {
                model[uuid] = content
                lastContent[id] = reexport(id)
            }
        }

        private fun seal(id: String, v: Long, kind: String, content: String): JSONObject {
            val sig = SyncSigning.sign(seed, SyncSigning.objectMessage(id, v, kind, clientId, content))
            val inner = JSONObject().put("pub", pub).put("sig", sig)
            if (kind != "del") inner.put("c", content)
            val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(keys.roomKey, inner.toString().toByteArray(), SyncCrypto.aad(id, v, kind)))
            return JSONObject().put("t", if (kind == "del") "del" else "put").put("id", id).put("v", v)
                .put("by", clientId).apply { if (kind != "del") put("kind", kind) }.put("ct", ct)
        }
    }

    /** The v2 relay: newest (v, by) per raw id, case sensitive like obj:<id>. */
    private class V2Relay {
        val records = LinkedHashMap<String, JSONObject>()
        fun store(frame: JSONObject) {
            val prior = records[frame.getString("id")]
            if (prior != null && !LegacyV2Ids.beats(frame.getLong("v"), frame.getString("by"), prior.getLong("v"), prior.getString("by"))) return
            records[frame.getString("id")] = frame
        }
    }

    private fun snapshotItem(frame: JSONObject): JSONObject = JSONObject(frame.toString()).apply {
        val del = optString("t") == "del"
        remove("t"); remove("rid")
        if (del) put("deleted", true)
    }

    private fun v2Ack(frame: JSONObject): JSONObject {
        val cth = SyncIdentity.urlB64(SyncIdentity.sha256(frame.getString("ct").toByteArray(Charsets.UTF_8)))
        return JSONObject().put("t", "op-ack").put("av", 1).put("rid", frame.getString("rid"))
            .put("by", frame.getString("by")).put("id", frame.getString("id")).put("v", frame.getLong("v"))
            .put("kind", frame.optString("kind").ifEmpty { "del" }).put("cth", cth)
    }

    private var androidFramesRelayed = 0

    private fun iosWaypoint(content: String?): Waypoint? = content?.let {
        GeoJsonImporter.parse(it, existingLayers = emptyList(), fallbackLayerId = DrawingDocument.DEFAULT_LAYER_ID, density = 1f)
            .waypoints.singleOrNull()
    }

    private fun placed(wp: Waypoint?): List<Any?> =
        listOf(wp?.id?.lowercase(), wp?.name, wp?.latitude, wp?.longitude)

    /** Relay both ways until both sides go quiet. Returns whatever 2.x iOS sent back. */
    private fun exchange(ios: Shipped2xIos, relay: V2Relay): List<JSONObject> {
        val echoes = ArrayList<JSONObject>()
        repeat(8) {
            h.advance(500)
            val fresh = h.socket.sentFrames().drop(androidFramesRelayed).filter { it.optString("t") in setOf("put", "del") }
            androidFramesRelayed = h.socket.sent.size
            for (frame in fresh) {
                relay.store(frame)
                h.deliver(v2Ack(frame))
                ios.receive(frame)
            }
            val back = ios.diff()
            echoes += back
            for (frame in back) {
                relay.store(frame)
                h.deliver(frame)
            }
            if (fresh.isEmpty() && back.isEmpty()) return echoes
        }
        error("the room never went quiet: $echoes")
    }

    /** Bob on 2.x iOS places W, Carol on Android joins and gets it in the snapshot. */
    private fun joinAfterA2xIosUserPlacedAWaypoint(ios: Shipped2xIos, relay: V2Relay, wp: Waypoint) {
        ios.model[UUID.fromString(wp.id)] = FakeV3Peer.waypointContent(wp)
        val created = ios.diff().single()
        assertEquals(wp.id.uppercase(), created.getString("id"))
        relay.store(created)
        v2Connected(relay.records.values.map(::snapshotItem))
        assertEquals(listOf(wp.id), h.waypointStore.committedWaypoints.value.map { it.id })
        assertEquals(wp.id.uppercase(), h.manager.rememberedV2IdForTests(wp.id))
        assertTrue(exchange(ios, relay).isEmpty())
    }

    @Test
    fun shipped2xIosModelReproducesTheEchoDeleteOfALowercaseEdit() {
        // keeps the model honest: this is the finding as 3.0.0 android triggered it
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val ios = Shipped2xIos(keys)
        val wp = waypoint("bob")
        ios.model[UUID.fromString(wp.id)] = FakeV3Peer.waypointContent(wp)
        assertEquals(listOf(wp.id.uppercase()), ios.diff().map { it.getString("id") })
        ios.receive(v2Record(wp.id, 2, "c0ffee00-0000-4000-8000-000000000001", FakeV3Peer.waypointContent(wp.copy(name = "carol")), keys).put("t", "put"))
        assertEquals(
            listOf("put" to wp.id.uppercase(), "del" to wp.id),
            ios.diff().map { it.getString("t") to it.getString("id") },
        )
    }

    @Test
    fun v2EditAndMoveOfA2xIosObjectGoOutUnderItsUppercaseIdWithNoEcho() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val ios = Shipped2xIos(keys)
        val relay = V2Relay()
        val wp = waypoint("bob")
        val upper = wp.id.uppercase()
        joinAfterA2xIosUserPlacedAWaypoint(ios, relay, wp)

        // carol renames it, then drags it somewhere else
        val renamed = wp.copy(name = "carol")
        val moved = renamed.copy(latitude = -35.5, longitude = 149.5)
        for (edit in listOf(renamed, moved)) {
            assertTrue(h.waypointStore.update(edit))
            val putsBefore = h.socket.sentOfType("put").size
            val echoes = exchange(ios, relay)
            assertEquals(listOf(upper), h.socket.sentOfType("put").drop(putsBefore).map { it.getString("id") })
            assertTrue("2.x iOS echoed $echoes", echoes.isEmpty())
            assertEquals(placed(edit), placed(h.waypointStore.committedWaypoints.value.single()))
            assertEquals(placed(edit), placed(iosWaypoint(ios.model[UUID.fromString(wp.id)])))
        }

        // nothing got deleted anywhere and the relay only ever saw the uppercase id
        assertEquals(listOf(upper), relay.records.keys.toList())
        assertTrue(h.socket.sentOfType("del").isEmpty())
        // a 2.x iOS device joining now gets the moved waypoint and stays quiet
        val late = Shipped2xIos(keys)
        relay.records.values.forEach(late::receive)
        assertEquals(placed(moved), placed(iosWaypoint(late.model[UUID.fromString(wp.id)])))
        assertTrue(late.diff().isEmpty())

        // android's own objects stay lowercase, shipped 2.x android drops uppercase
        val own = h.addWaypoint("carol's own")
        h.advance(500)
        assertEquals(own.id, h.socket.sentOfType("put").last().getString("id"))
        assertNull(h.manager.rememberedV2IdForTests(own.id))
    }

    @Test
    fun v2DeleteAndUndoOfA2xIosObjectKeepItsUppercaseId() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val ios = Shipped2xIos(keys)
        val relay = V2Relay()
        val wp = waypoint("bob")
        val upper = wp.id.uppercase()
        joinAfterA2xIosUserPlacedAWaypoint(ios, relay, wp)

        assertTrue(h.waypointStore.remove(h.waypointStore.committedWaypoints.value.single()))
        val afterDelete = exchange(ios, relay)
        assertEquals(listOf(upper), h.socket.sentOfType("del").map { it.getString("id") })
        assertTrue("2.x iOS echoed $afterDelete", afterDelete.isEmpty())
        assertTrue(ios.model.isEmpty())
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())

        // the entry outlives the delete, so the undo comes back under the same id
        assertEquals(upper, h.manager.rememberedV2IdForTests(wp.id))
        assertTrue(h.waypointStore.undo())
        val afterUndo = exchange(ios, relay)
        assertEquals(upper, h.socket.sentOfType("put").last().getString("id"))
        assertTrue("2.x iOS echoed $afterUndo", afterUndo.isEmpty())
        assertEquals(setOf(UUID.fromString(wp.id)), ios.model.keys)
        assertEquals(listOf(wp.id), h.waypointStore.committedWaypoints.value.map { it.id })
        assertEquals(listOf(upper), relay.records.keys.toList())
        assertEquals("put", relay.records.getValue(upper).getString("t"))
    }

    @Test
    fun v2RememberedUppercaseIdSurvivesARestart() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val ios = Shipped2xIos(keys)
        val wp = waypoint("bob")
        val upper = wp.id.uppercase()
        ios.model[UUID.fromString(wp.id)] = FakeV3Peer.waypointContent(wp)
        val created = ios.diff().single()
        val dir = java.nio.file.Files.createTempDirectory("v2-restart").toFile()

        val first = SyncHarness(dir = dir)
        try {
            v2Connected(listOf(snapshotItem(created)), first)
            assertEquals(upper, first.manager.rememberedV2IdForTests(wp.id))
        } finally {
            first.close(deleteFiles = false)
        }

        // the relay purged the room, so only this device still knows the casing
        val second = SyncHarness(dir = dir)
        try {
            assertEquals(listOf(wp.id), second.waypointStore.committedWaypoints.value.map { it.id })
            v2Connected(harness = second)
            assertEquals(upper, second.manager.rememberedV2IdForTests(wp.id))
            second.advance(500)
            val reupload = second.socket.sentOfType("put").single()
            assertEquals(upper, reupload.getString("id"))
            // a 2.x iOS device picking that up applies it without an echo
            val fresh = Shipped2xIos(keys)
            fresh.receive(reupload)
            assertEquals(setOf(UUID.fromString(wp.id)), fresh.model.keys)
            assertTrue(fresh.diff().isEmpty())
        } finally {
            second.close()
        }
    }

    @Test
    fun v2RememberedIdWhoseWriteFailedIsWrittenByTheNextBatch() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val ios = Shipped2xIos(keys)
        val wp = waypoint("bob")
        val upper = wp.id.uppercase()
        ios.model[UUID.fromString(wp.id)] = FakeV3Peer.waypointContent(wp)
        val created = ios.diff().single()
        val dir = java.nio.file.Files.createTempDirectory("v2-write-failed").toFile()
        // the sealed write for the learned id fails (stands in for a full disk, or the key
        // relocking between the check and the write)
        val idsDir = java.io.File(java.io.File(dir, "files"), LegacyV2IdStore.DIRECTORY_NAME).apply { mkdirs() }
        assertTrue(idsDir.setWritable(false))

        val first = SyncHarness(dir = dir)
        try {
            v2Connected(listOf(snapshotItem(created)), first)
            assertEquals(upper, first.manager.rememberedV2IdForTests(wp.id))
            assertTrue("nothing should have landed", idsDir.listFiles().orEmpty().none { it.name.endsWith(".json") })
            assertTrue(idsDir.setWritable(true))
            // a later batch that teaches nothing new still has to write it
            first.deliver(JSONObject().put("t", "loc"))
            first.advance(500)
        } finally {
            idsDir.setWritable(true)
            first.close(deleteFiles = false)
        }

        // the relay purged the room, so only this device still knows the casing
        val second = SyncHarness(dir = dir)
        try {
            v2Connected(harness = second)
            assertEquals("the retry never wrote it", upper, second.manager.rememberedV2IdForTests(wp.id))
            second.advance(500)
            assertEquals(upper, second.socket.sentOfType("put").single().getString("id"))
        } finally {
            second.close()
        }
    }

    private fun v2Vectors(): JSONObject {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = java.io.File(dir, "testdata/sync_client_behaviour.json")
            if (f.exists()) return JSONObject(f.readText()).getJSONObject("v2").getJSONObject("vectors")
            dir = dir?.parentFile
        }
        error("testdata/sync_client_behaviour.json not found")
    }

    @Test
    fun v2RememberVectorsLearnThroughTheRealManager() {
        val rows = v2Vectors().getJSONArray("remember")
        assertEquals(7, rows.length())
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val by = "c0ffee00-0000-4000-8000-000000000001"
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val harness = SyncHarness()
            try {
                v2Connected(harness = harness)
                val events = row.getJSONArray("events")
                var stateKey: String? = null
                for (j in 0 until events.length()) {
                    val event = events.getJSONObject(j)
                    val raw = event.getString("raw")
                    val key = raw.lowercase().let { if (it.length == 32) "${it.substring(0, 8)}-${it.substring(8, 12)}-${it.substring(12, 16)}-${it.substring(16, 20)}-${it.substring(20)}" else it }
                    stateKey = key
                    val v = 10L + j
                    val frame = if (event.getString("t") == "del") {
                        v2Del(raw, v, by, keys)
                    } else {
                        v2Record(raw, v, by, FakeV3Peer.waypointContent(waypoint("vector", key)), keys).put("t", "put")
                    }
                    // newer than anything, so it passes beats, then fails AEAD
                    if (!event.getBoolean("accepted") && LegacyV2Ids.stateKey(raw) != null) frame.put("v", v + 1_000)
                    harness.deliver(frame)
                }
                val expected = row.opt("expectRemembered").takeUnless { it == JSONObject.NULL } as String?
                assertEquals(row.getString("id"), expected, harness.manager.rememberedV2IdForTests(stateKey!!))
            } finally {
                harness.close()
            }
        }
    }
}
