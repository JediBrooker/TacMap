package com.tacmap.sync

import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.util.SafeStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Presence-only screen-off reconnect (plans/04 section 21.4, S2-06, S2-07)
 * through the manager seam with the switch turned on. Production ships the
 * switch off until doc change D1, the last test pins that.
 */
class SyncBackgroundReconnectTest {
    private var harness: SyncHarness? = null
    private val sealedWrites = ConcurrentHashMap<String, AtomicInteger>()
    private val pausedAt = ArrayList<Long>()
    private var transportEnded = 0

    @Before fun setUp() {
        SyncHarness.installStoreKey()
        val base = SafeStore.migrationPolicy
        // every sealed write (and load) marks its label first
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = base.isSealedOnly(label)
            override fun markSealedOnly(label: String) {
                sealedWrites.getOrPut(label) { AtomicInteger() }.incrementAndGet()
                base.markSealedOnly(label)
            }
        }
    }

    @After fun tearDown() {
        harness?.close()
        SyncHarness.restoreStoreKey()
    }

    private fun durableTouches() = sealedWrites.values.sumOf { it.get() }

    private val interval = BackgroundUnitSyncInterval.DEFAULT
    private val intervalMs get() = interval.minutes * 60_000L

    /** Joined, connected, screen-off. Returns the epoch the foreground hello used. */
    private fun screenOff(reconnect: Boolean = true, optedIn: Boolean = true): Pair<SyncHarness, BigInteger> {
        val h = SyncHarness(backgroundReconnect = reconnect)
        harness = h
        h.manager.backgroundPresenceOptIn = { optedIn }
        h.manager.backgroundPresencePaused = { pausedAt += it }
        h.manager.backgroundTransportEnded = { transportEnded += 1 }
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true, callsign = "Alpha")))
        h.join()
        val hello = h.completeHandshake()
        assertTrue(h.manager.enterBackgroundPresenceOnly(interval))
        h.runCurrent()
        sealedWrites.clear()
        return h to epochOf(hello)
    }

    private fun epochOf(hello: JSONObject) = BigInteger(hello.getString("vs").substringBefore(':'), 16)

    private fun SyncHarness.fix(ageMs: Long = 0) = PresenceFixSample(
        provider = android.location.LocationManager.GPS_PROVIDER,
        latitude = -35.0,
        longitude = 149.0,
        accuracyMetres = 5.0,
        bearingDegrees = 0.0,
        speedMps = 0.0,
        elapsedRealtimeNanos = clock.elapsedRealtimeNanos() - ageMs * 1_000_000L,
    )

    private fun SyncHarness.drop() {
        socket.serverClose(1006, "gone")
        runCurrent()
    }

    /** The relay side of a presence-only handshake, up to snapshot-end. */
    private fun SyncHarness.drainSnapshot(socket: FakeSocket, items: List<Any>, seq: Long = 7) {
        socket.open()
        runCurrent()
        deliver(JSONObject().put("t", "snapshot-begin").put("seq", seq).put("highWater", "0000000000000000"), socket)
        deliver(JSONObject().put("t", "snapshot").put("items", JSONArray(items)).put("more", false), socket)
        deliver(JSONObject().put("t", "snapshot-end").put("seq", seq), socket)
    }

    private fun SyncHarness.helloAck(socket: FakeSocket) {
        val hello = socket.sentOfType("hello").single()
        deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")), socket)
    }

    @Test
    fun dropComesBackOnASpareEpochWithoutWritingOrReadingAnything() {
        val (h, foregroundEpoch) = screenOff()
        val foreground = h.socket
        val replay = h.manager.replayStateForTests!!
        val actor = h.manager.myActorIdForTests!!
        val persistedEpoch = replay.getHelloEpoch(actor)
        assertEquals(foregroundEpoch.add(BigInteger.valueOf(64)), BigInteger(persistedEpoch!!, 16))

        h.drop()
        assertEquals("the location service keeps running, it's the wake source", 0, transportEnded)
        assertTrue(pausedAt.isEmpty())
        assertEquals("nothing until the next fix", 1, h.transport.sockets.size)

        h.advance(intervalMs)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        assertEquals(2, h.transport.sockets.size)
        val bg = h.socket
        assertEquals(foreground.headers, bg.headers)
        assertTrue("handshake held awake", h.wakeLock.held)

        // records are never opened: junk and an authentic one are both just bytes
        val peer = FakeV3Peer(h.keys())
        val junk = JSONObject().put("id", "nope").put("ct", "!!!")
        val authentic = peer.waypointRecord(
            com.tacmap.waypoints.Waypoint(name = "x", latitude = -35.0, longitude = 149.0, createdAt = 1L), 9,
        )
        h.drainSnapshot(bg, listOf(junk, authentic))
        val hello = bg.sentOfType("hello").single()
        assertEquals("next spare, no reservation", foregroundEpoch.add(BigInteger.ONE), epochOf(hello))
        assertNotEquals(foreground.sentOfType("hello").single().getString("sd"), hello.getString("sd"))
        assertNull(h.manager.skippedCategoryForTests(authentic.getString("id")))
        assertNull(replay.getStamp(authentic.getString("id")))

        h.helloAck(bg)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals("the fix that woke us goes out", listOf("hello", "loc"), bg.sentFrames().map { it.getString("t") })
        assertFalse(h.wakeLock.held)
        assertEquals("no durable write or read in background", 0, durableTouches())
        assertEquals(persistedEpoch, replay.getHelloEpoch(actor))
        assertTrue(pausedAt.isEmpty())

        // later fixes ride the same session, still hello and loc only
        h.advance(intervalMs)
        bg.progress()
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        assertEquals(setOf("hello", "loc"), bg.sentFrames().map { it.getString("t") }.toSet())
        assertEquals(2, bg.sentOfType("loc").size)
        assertEquals(0, durableTouches())
    }

    @Test
    fun withoutSparesTheDropPausesRightAway() {
        val (h, _) = screenOff(optedIn = false)
        h.drop()
        assertEquals(1, pausedAt.size)
        assertEquals(1, transportEnded)
        h.advance(intervalMs)
        assertFalse(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        assertEquals(1, h.transport.sockets.size)
    }

    @Test
    fun threeFailedAttemptsPauseAndAttemptsStaySixtySecondsApart() {
        val (h, foregroundEpoch) = screenOff()
        h.drop()
        val epochs = ArrayList<BigInteger>()
        repeat(3) { attempt ->
            h.advance(BackgroundPresencePolicy.MIN_SPACING_MS)
            assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
            h.runCurrent()
            val bg = h.socket
            assertEquals(2 + attempt, h.transport.sockets.size)
            h.drainSnapshot(bg, emptyList())
            epochs += epochOf(bg.sentOfType("hello").single())
            // the relay drops it before hello-ack
            bg.serverClose(1011, "storage")
            h.runCurrent()
            // the same wake's fix is still an opportunity, but not inside 60 s
            assertEquals(2 + attempt, h.transport.sockets.size)
        }
        assertEquals((1..3).map { foregroundEpoch.add(BigInteger.valueOf(it.toLong())) }, epochs)
        assertEquals(1, pausedAt.size)
        assertEquals(1, transportEnded)
        assertFalse(h.wakeLock.held)
        h.advance(BackgroundPresencePolicy.MIN_SPACING_MS)
        assertFalse(h.manager.sendBackgroundPresence(h.fix(), interval))
        assertEquals(4, h.transport.sockets.size)
        assertEquals(0, durableTouches())
    }

    @Test
    fun aSnapshotOverFourMegabytesIsNotDrained() {
        val (h, _) = screenOff()
        h.drop()
        h.advance(intervalMs)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        val bg = h.socket
        bg.open(); h.runCurrent()
        h.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 3), bg)
        val pad = "a".repeat(950_000)
        repeat(5) {
            if (!bg.terminal) {
                h.deliver(JSONObject().put("t", "snapshot").put("items", JSONArray().put(JSONObject().put("pad", pad)))
                    .put("more", true), bg)
            }
        }
        assertTrue(bg.terminal)
        assertTrue(bg.sentOfType("hello").isEmpty())
        assertEquals(1, pausedAt.size)
        assertEquals(1, transportEnded)
        assertFalse(h.wakeLock.held)
    }

    @Test
    fun outOfOrderFenceFailsTheAttemptButNotTheNextOne() {
        val (h, _) = screenOff()
        h.drop()
        h.advance(intervalMs)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        val bg = h.socket
        bg.open(); h.runCurrent()
        // end before any page
        h.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 3), bg)
        h.deliver(JSONObject().put("t", "snapshot-end").put("seq", 3), bg)
        assertTrue(bg.terminal)
        assertTrue(bg.sentOfType("hello").isEmpty())
        assertTrue(pausedAt.isEmpty())
        h.advance(BackgroundPresencePolicy.MIN_SPACING_MS)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        h.drainSnapshot(h.socket, emptyList())
        h.helloAck(h.socket)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
    }

    @Test
    fun aDeadSocketFoundByTheWakeProbeComesBackInTheSameWake() {
        val (h, _) = screenOff()
        val foreground = h.socket
        h.advance(intervalMs)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.advance(BackgroundPresencePolicy.PROBE_PONG_TIMEOUT_MS + 500)
        assertTrue(foreground.terminal)
        assertEquals("reconnected on the fix that found the drop", 2, h.transport.sockets.size)
        assertTrue(pausedAt.isEmpty())
        h.drainSnapshot(h.socket, emptyList())
        h.helloAck(h.socket)
        assertEquals(1, h.socket.sentOfType("loc").size)
    }

    @Test
    fun networkComingBackIsAnOpportunityToo() {
        val (h, _) = screenOff()
        h.drop()
        assertEquals(1, h.transport.sockets.size)
        h.reachability.listener!!.onNetworkAvailable()
        h.runCurrent()
        assertEquals(2, h.transport.sockets.size)
        h.drainSnapshot(h.socket, emptyList())
        h.helloAck(h.socket)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        // no fix inside the two minute rule yet, so nothing to send until the next one
        assertTrue(h.socket.sentOfType("loc").isEmpty())
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        assertEquals(1, h.socket.sentOfType("loc").size)
    }

    @Test
    fun foregroundReturnReservesAboveEverySpare() {
        val (h, foregroundEpoch) = screenOff()
        h.drop()
        h.advance(intervalMs)
        assertTrue(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        h.drainSnapshot(h.socket, emptyList())
        h.helloAck(h.socket)
        val background = h.socket

        h.manager.prepareForForegroundUnlock()
        h.runCurrent()
        assertTrue(background.terminal)
        assertTrue(h.manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
        h.runCurrent()
        val hello = h.completeHandshake()
        assertTrue(epochOf(hello) > foregroundEpoch.add(BigInteger.valueOf(64)))
        assertTrue(pausedAt.isEmpty())
        assertNull("no background pause notice", h.manager.lastError.value)
    }

    @Test
    fun shippedSwitchStillPausesOnADrop() {
        assertFalse(BackgroundPresencePolicy.RECONNECT_ENABLED)
        val (h, _) = screenOff(reconnect = false)
        h.drop()
        assertEquals(1, pausedAt.size)
        assertEquals(1, transportEnded)
        h.advance(intervalMs)
        assertFalse(h.manager.sendBackgroundPresence(h.fix(), interval))
        h.runCurrent()
        assertEquals(1, h.transport.sockets.size)
    }
}
