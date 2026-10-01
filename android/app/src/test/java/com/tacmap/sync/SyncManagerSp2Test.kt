package com.tacmap.sync

import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File
import java.util.UUID

/**
 * More SP2 state machine scripts through the S6-02 seam (real SyncManager,
 * fake socket, virtual time). Each one is the regression for an audited
 * defect or contract row that SyncManagerScenarioTest doesn't drive yet.
 */
class SyncManagerSp2Test {
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

    private fun putsFor(socket: FakeSocket, wireId: String) =
        socket.sentOfType("put").filter { it.getString("id") == wireId }

    private fun delsFor(socket: FakeSocket, wireId: String) =
        socket.sentOfType("del").filter { it.getString("id") == wireId }

    private fun edit(id: String, name: String) {
        val current = h.waypointStore.committedWaypoints.value.first { it.id == id }
        assertTrue(h.waypointStore.update(current.copy(name = name)))
    }

    private fun relayLimit(key: String): Int {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/sync_protocol_v3.json")
            if (f.exists()) {
                val limits = Json.parseToJsonElement(f.readText()).jsonObject["relayLimits"]!!.jsonObject
                limits["clientPacing"]?.jsonObject?.get(key)?.let { return it.jsonPrimitive.int }
                return limits["values"]!!.jsonObject[key]!!.jsonPrimitive.int
            }
            dir = dir?.parentFile
        }
        error("no testdata/sync_protocol_v3.json")
    }

    // ---- pacer window: a newer copy of an in-flight object must still go out ----

    @Test
    fun editingABigObjectWhileItsFirstCopyIsInFlightStillPublishes() {
        connected()
        // notes go out twice (notes + description), so ~500 KB inner: fits CT_MAX,
        // but two copies don't fit the 1 MiB in-flight window together
        val big = waypoint("big").copy(notes = "n".repeat(250_000))
        assertTrue(h.waypointStore.add(big))
        h.advance(300)
        val first = h.socket.sentOfType("put").single()
        val wire = first.getString("id")
        edit(big.id, "big v2")
        h.advance(3_000)
        val puts = putsFor(h.socket, wire)
        assertEquals("the edit must not wait on an ack that can never match", 2, puts.size)
        assertNotEquals(first.getString("vs"), puts[1].getString("vs"))
        h.ackPut(puts[1])
        // the superseded copy is never retransmitted, the acked one is done
        h.advance(40_000)
        assertEquals(2, putsFor(h.socket, wire).size)
        assertFalse(h.socket.terminal)
        // and the window really is free again for the next object
        h.addWaypoint("after")
        h.advance(500)
        assertEquals(3, h.socket.sentOfType("put").size)
    }

    // ---- S3-13 / S1-09: final nacks resolve once and never loop across reconnects ----

    private fun finalNackIsResolvedAndSuppressed(code: String, text: String) {
        connected()
        val wp = h.addWaypoint("rejected")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        val wire = put.getString("id")
        h.nack(put, code, retry = false)
        h.advance(40_000)
        assertFalse("$code must not reconnect", h.socket.terminal)
        assertEquals(1, h.transport.sockets.size)
        assertEquals("$code is relay data, never SECURITY", SyncIssueKind.CONNECTION, h.manager.currentIssueKind)
        assertEquals(1, toasts.count { it.contains(text) })
        assertEquals("no retransmit of a resolved op", 1, putsFor(h.socket, wire).size)

        // old behaviour: the rejected op went to reconciliation and was re-sent forever
        h.socket.serverClose(1006)
        awaitNewSocket()
        h.completeHandshake()
        h.advance(1_000)
        assertTrue("$code: untouched object is not re-sent after reconnect", putsFor(h.socket, wire).isEmpty())

        // a real local edit releases it, once
        edit(wp.id, "edited")
        h.advance(500)
        assertEquals(1, putsFor(h.socket, wire).size)
    }

    @Test
    fun quotaNackIsResolvedOnceAndNotResentAfterReconnect() =
        finalNackIsResolvedAndSuppressed("quota", "room is full")

    @Test
    fun invalidNackIsResolvedOnceAndNotResentAfterReconnect() =
        finalNackIsResolvedAndSuppressed("invalid", "rejected a change as invalid")

    @Test
    fun storageNackKeepsTheOpAndRetriesOnTheAckSchedule() {
        connected()
        h.addWaypoint("storage hiccup")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        val wire = put.getString("id")
        h.advance(3_000)
        h.nack(put, "storage", retry = true)
        // next copy waits a full ack timeout from the nack, not from the first write
        h.advance(4_900)
        assertEquals(1, putsFor(h.socket, wire).size)
        h.advance(200)
        val copies = putsFor(h.socket, wire)
        assertEquals(2, copies.size)
        assertEquals("same op, same rid", put.getString("rid"), copies[1].getString("rid"))
        h.ackPut(copies[1])
        h.advance(60_000)
        assertEquals(2, putsFor(h.socket, wire).size)
        assertFalse(h.socket.terminal)
        assertNull(h.manager.currentIssueKind)
    }

    // ---- S2-11 / 7.4: session nacks reconnect and stop after three in 10 min ----

    @Test
    fun sessionReplacedNacksReconnectThenStopAfterThree() {
        connected()
        repeat(3) { round ->
            val socket = h.socket
            h.addWaypoint("w$round")
            h.advance(300)
            h.nack(socket.sentOfType("put").last(), "session-replaced", retry = false)
            assertTrue("round $round closes", socket.terminal)
            if (round < 2) {
                awaitNewSocket()
                h.completeHandshake()
            }
        }
        h.advance(30 * 60_000L)
        assertEquals(3, h.transport.sockets.size)
        assertTrue(h.manager.pausedActionRequired.value)
        assertEquals(1, toasts.count { it.contains("keeps replacing") })
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
    }

    @Test
    fun helloRequiredNackReconnectsWithoutCountingAsAConflict() {
        connected()
        repeat(4) {
            val socket = h.socket
            h.addWaypoint("again$it")
            h.advance(300)
            h.nack(socket.sentOfType("put").last(), "hello-required", retry = true)
            assertTrue(socket.terminal)
            awaitNewSocket()
            h.completeHandshake()
        }
        assertFalse(h.manager.pausedActionRequired.value)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
    }

    // ---- section 4.1: live records use the same skip rules, no reconnect ----

    @Test
    fun livePoisonRecordsAreSkippedAndSurfacedOncePerJoin() {
        connected()
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        val garbage = (1..3).map { i ->
            val wp = waypoint("garbage$i")
            peer.record(
                peer.wireId(wp.id), 3L + i, "waypoint", FakeV3Peer.waypointContent(wp), t = "put",
                ctOverride = SyncCrypto.encodeBase64(ByteArray(64) { (it * 13 + i).toByte() }),
            )
        }
        garbage.forEach { h.deliver(it) }
        val future = waypoint("future")
        h.deliver(peer.record(peer.wireId(future.id), 9, "route", """{"type":"FeatureCollection","features":[]}""", t = "put"))
        h.advance(1_000)

        assertFalse(h.socket.terminal)
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(1, toasts.count { it.contains("failed verification") })
        assertEquals(1, toasts.count { it.contains("can't display") })
        val replay = h.manager.replayStateForTests!!
        garbage.forEach { assertNull(replay.getStamp(it.getString("id"))) }
        assertNull(replay.getStamp(peer.wireId(future.id)))
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())

        // an honest record right after still lands
        val good = waypoint("good")
        h.deliver(peer.waypointRecord(good, 20, t = "put"))
        assertEquals(listOf("good"), h.waypointStore.committedWaypoints.value.map { it.name })
    }

    // ---- section 10: path change while connected probes the socket ----

    @Test
    fun pathChangeProbeClosesASilentSocketAndReconnects() {
        connected()
        val socket = h.socket
        // the snapshot itself counts as inbound progress, so move past it
        h.advance(20_000)
        h.reachability.listener!!.onNetworkLostOrChanged()
        h.runCurrent()
        assertEquals(1, socket.pings)
        h.advance(SyncManager.PATH_CHANGE_PROBE_TIMEOUT_MS - 100)
        assertFalse(socket.terminal)
        h.advance(200)
        assertTrue("no pong and no frame in 5 s is transport loss", socket.terminal)
        awaitNewSocket()
    }

    @Test
    fun pathChangeProbeKeepsASocketThatAnswers() {
        connected()
        val socket = h.socket
        h.advance(20_000)
        h.reachability.listener!!.onNetworkLostOrChanged()
        h.runCurrent()
        h.advance(1_000)
        socket.progress() // the pong
        h.advance(10_000)
        assertFalse(socket.terminal)
        assertEquals(1, h.transport.sockets.size)
    }

    // ---- section 7.2: busy and rate limited rows ----

    @Test
    fun close1013UsesTheSlowBusyFloorAndSaysBusyAfterTwo() {
        h.randomValue = 0.0
        connected()
        h.socket.serverClose(1013, "try later")
        h.runCurrent()
        assertEquals(0, toasts.count { it.contains("is busy") })
        h.advance(14_900)
        assertEquals("slow_busy floor is 15 s", 1, h.transport.sockets.size)
        h.advance(200)
        assertEquals(2, h.transport.sockets.size)
        h.socket.open()
        h.runCurrent()
        h.socket.serverClose(1013, "try later")
        h.runCurrent()
        assertEquals("second in a row is surfaced", 1, toasts.count { it.contains("is busy") })
        awaitNewSocket()
        h.socket.open()
        h.runCurrent()
        h.socket.serverClose(1011, "storage")
        h.runCurrent()
        assertEquals("once per chain", 1, toasts.count { it.contains("is busy") })
        assertEquals(SyncIssueKind.CONNECTION, h.manager.currentIssueKind)
        assertFalse(h.manager.pausedActionRequired.value)
    }

    @Test
    fun upgrade429WaitsAMinuteAndSaysSoOnce() {
        h.randomValue = 0.0
        h.join()
        h.socket.rejectUpgrade(429)
        h.runCurrent()
        assertEquals(1, toasts.count { it.contains("limiting new connections") })
        h.advance(59_900)
        assertEquals(1, h.transport.sockets.size)
        h.advance(200)
        assertEquals(2, h.transport.sockets.size)
        h.socket.rejectUpgrade(429)
        h.runCurrent()
        assertEquals(1, toasts.count { it.contains("limiting new connections") })
    }

    // ---- plans/03 SP2: departed author's tombstone backlog goes through the pacer ----

    @Test
    fun fiveHundredUnconfirmedOwnTombstonesArePacedUnderTheRelayWindow() {
        val hello = connected()
        val actor = hello.getString("by")
        val pub = hello.getString("pub")
        val replay = h.manager.replayStateForTests!!
        val wires = (0 until 500).map { i ->
            SyncIdentity.urlB64(ByteArray(32) { b -> ((i * 31 + b * 7) xor (i shr 3)).toByte() })
        }.distinct()
        assertEquals(500, wires.size)
        replay.beginBatch()
        wires.forEach { assertNotNull(replay.reserveLocalDelete(it, actor, pub)) }
        assertTrue(replay.commitBatch())

        // away long enough that the relay compacted them: the snapshot doesn't confirm any
        h.socket.serverClose(1006)
        val socket = awaitNewSocket()
        h.completeHandshake(socket = socket)

        val clientWindow = relayLimit("maxFramesPerWindow")
        val relayWindow = relayLimit("RATE_MAX_MSGS")
        val sentAt = ArrayList<Long>()
        val acked = HashSet<String>()
        var seen = 0
        var maxUnacked = 0
        repeat(600) {
            h.advance(250)
            while (seen < socket.sent.size) {
                sentAt += h.now
                seen += 1
            }
            val dels = socket.sentOfType("del")
            maxUnacked = maxOf(maxUnacked, dels.count { it.getString("rid") !in acked })
            for (frame in dels) if (acked.add(frame.getString("rid"))) h.ackPut(frame, socket)
        }
        assertFalse("relay would have closed 4008", socket.terminal)
        val dels = socket.sentOfType("del")
        assertEquals(wires.toSet(), dels.map { it.getString("id") }.toSet())
        assertEquals("one resend per tombstone, no duplicates", 500, dels.size)
        assertTrue("$maxUnacked unacked", maxUnacked <= SyncOutboundPacer.MAX_IN_FLIGHT_MUTATIONS)
        for (start in sentAt) {
            val inWindow = sentAt.count { it >= start && it < start + 10_000L }
            assertTrue("$inWindow frames in 10 s", inWindow <= clientWindow)
        }
        // the relay counts fixed 10 s windows from the upgrade
        val opened = socket.createdAtMs
        val perFixedWindow = sentAt.groupingBy { (it - opened) / 10_000L }.eachCount()
        assertTrue(perFixedWindow.toString(), perFixedWindow.values.all { it < relayWindow })
        // converged: nothing left to resend on the next reconnect's hello-ack
        assertEquals(500, acked.size)
    }

    // ---- an unconfirmed delete from before a pause is resent once, not twice ----

    @Test
    fun unconfirmedDeleteFromBeforeAPauseIsResentOnceAfterForegroundReturn() {
        connected()
        val wp = h.addWaypoint("doomed")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        h.ackPut(put)
        val wire = put.getString("id")
        assertTrue(h.waypointStore.remove(h.waypointStore.committedWaypoints.value.single()))
        h.advance(300)
        assertEquals(1, delsFor(h.socket, wire).size)
        // no ack: the screen locks and the stores go away
        assertTrue(h.manager.suspendUntilForegroundStores())
        h.runCurrent()
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        h.runCurrent()
        val socket = h.socket
        h.completeHandshake(socket = socket)
        h.advance(2_000)
        val resent = delsFor(socket, wire)
        assertEquals("one tombstone resend, not one per id key", 1, resent.size)
        h.ackPut(resent.single(), socket)
        h.advance(2_000)
        assertEquals(1, delsFor(socket, wire).size)
        assertNull(h.waypointStore.committedWaypoints.value.firstOrNull { it.id == wp.id })
    }

    // ---- 2.5 / 0.1: banner slots match iOS ----------------------------------------

    @Test
    fun skippedUnsupportedNoticeOutlivesTheHelloAckOfItsOwnSnapshot() {
        val peer = FakeV3Peer(h.keys())
        val future = waypoint("future")
        connected(listOf(peer.record(peer.wireId(future.id), 5, "route", """{"type":"FeatureCollection","features":[]}""")))
        // used to be retired by connectionSucceeded at the same generation, so
        // the "update TacMap" banner flashed and vanished
        assertEquals(SyncIssueKind.CONNECTION, h.manager.currentIssueKind)
        assertTrue(h.manager.lastError.value!!.text.contains("can't display"))
        h.socket.serverClose(1006)
        awaitNewSocket()
        h.completeHandshake()
        assertTrue("survives a clean reconnect", h.manager.lastError.value!!.text.contains("can't display"))
        h.manager.acknowledgeLastError()
        assertNull(h.manager.lastError.value)
    }

    @Test
    fun retryRetiresTheStopBanner() {
        connected()
        h.socket.serverClose(4013, "room quota")
        h.advance(1_000)
        assertTrue(h.manager.pausedActionRequired.value)
        val stopText = h.manager.lastError.value!!.text
        assertTrue(stopText, stopText.contains("full"))
        // dismiss doesn't make a stop go away, Retry does
        h.manager.acknowledgeLastError()
        assertEquals(stopText, h.manager.lastError.value?.text)
        h.manager.retryAfterPause()
        h.runCurrent()
        h.completeHandshake()
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertNull(h.manager.lastError.value)
    }

    @Test
    fun changesPausedStaysUntilLeaveEvenAfterDismiss() {
        connected()
        h.addWaypoint("one")
        h.advance(300)
        h.nack(h.socket.sentOfType("put").single(), "counter-window", retry = false)
        val text = h.manager.lastError.value!!.text
        assertTrue(text, text.contains("reset by the relay"))
        h.manager.acknowledgeLastError()
        assertEquals(text, h.manager.lastError.value?.text)
        h.socket.serverClose(1006)
        awaitNewSocket()
        h.completeHandshake()
        assertEquals(text, h.manager.lastError.value?.text)
        h.manager.leave()
        h.runCurrent()
        assertNull(h.manager.lastError.value)
    }

    // ---- 2.1: identity collision with an object of the other kind ----------------

    private fun localDrawing(name: String) = com.tacmap.drawings.DrawingFeature(
        name = name, geometry = com.tacmap.drawings.DrawingGeometry.LINE,
        points = listOf(com.tacmap.drawings.DrawingPoint(-35.0, 149.0), com.tacmap.drawings.DrawingPoint(-35.1, 149.1)),
    )

    private fun assertCollisionSkipped(drawingId: String, wire: String) {
        assertEquals("the local drawing is untouched", listOf(drawingId),
            h.drawingStore.committedDocument.value.features.map { it.id })
        assertTrue("no waypoint borrows the drawing's id",
            h.waypointStore.committedWaypoints.value.none { it.id == drawingId })
        assertEquals(SnapshotRecordCategory.SKIP_UNSUPPORTED, h.manager.skippedCategoryForTests(wire))
        assertEquals(1, toasts.count { it.contains("can't display") })
        assertFalse(h.socket.terminal)
    }

    @Test
    fun liveWaypointReusingALocalDrawingIdIsSkippedNotDuplicated() {
        val drawing = localDrawing("ours")
        assertTrue(h.drawingStore.addFeature(drawing))
        connected()
        h.advance(300)
        val ourPut = h.socket.sentOfType("put").single()
        h.ackPut(ourPut)
        val wire = ourPut.getString("id")
        val peer = FakeV3Peer(h.keys())
        h.deliver(peer.hello())
        val counter = VersionStamp.parse(ourPut.getString("vs"))!!.counter + 1
        h.deliver(peer.waypointRecord(waypoint("impostor", id = drawing.id), counter, t = "put"))
        assertCollisionSkipped(drawing.id, wire)
        assertNull("nothing committed for the colliding record",
            h.manager.replayStateForTests!!.getStamp(wire)?.takeIf { it.actorId == peer.actor })
        // and no publish ping-pong afterwards
        h.advance(30_000)
        assertEquals(1, h.socket.sentOfType("put").size)
    }

    @Test
    fun snapshotWaypointReusingALocalDrawingIdIsSkippedNotDuplicated() {
        val drawing = localDrawing("ours")
        assertTrue(h.drawingStore.addFeature(drawing))
        val peer = FakeV3Peer(h.keys())
        val wire = peer.wireId(drawing.id)
        // a later record in the same snapshot still applies with its own layers
        val other = waypoint("fine")
        connected(listOf(
            peer.waypointRecord(waypoint("impostor", id = drawing.id), 7),
            peer.waypointRecord(other, 8),
        ))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertCollisionSkipped(drawing.id, wire)
        assertEquals("fine", h.waypointStore.committedWaypoints.value.single().name)
        assertNull(h.manager.replayStateForTests!!.getStamp(wire))
        h.advance(30_000)
        // our drawing still goes out once (the relay never had it), nothing loops
        assertTrue(h.socket.sentOfType("put").count { it.getString("id") == wire } <= 1)
    }
}
