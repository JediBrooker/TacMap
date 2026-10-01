package com.tacmap.sync

import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SP3 efficiency numbers (plans/04 sections 17-19) measured through the real
 * manager. Each test prints its measurement so the before/after can be read off
 * the test report; the asserts are the contract bounds.
 *
 * Same tests run against the pre-SP3 manager (JVM, M-series, 2026-10-01):
 *  - 800 record snapshot: 802 replay writes, 800 waypoint writes, 6355 ms
 *  - 2k tombstones x 2k local objects: 28718 ms (about 4M wire id HMACs)
 *  - 100 presence frames over 2k stamps: 100 replay writes, ~43.7 MB sealed
 *  - first publish of 300 objects: 300 replay writes
 *  - 64 queued live puts: 128 replay writes, 64 waypoint writes
 * After: 3 / 1 / ~200 ms, ~180 ms with 2000 HMACs, 7 writes / ~3 MB, 1 write, 2 / 1.
 */
class SyncEfficiencyTest {
    private lateinit var h: SyncHarness
    private val writes = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var basePolicy: SafeStore.MigrationPolicy

    @Before fun setUp() {
        SyncHarness.installStoreKey()
        basePolicy = SafeStore.migrationPolicy
        // every sealed write marks its label first, so this counts writes per store
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = basePolicy.isSealedOnly(label)
            override fun markSealedOnly(label: String) {
                writes.getOrPut(bucket(label)) { AtomicInteger() }.incrementAndGet()
                basePolicy.markSealedOnly(label)
            }
        }
        h = SyncHarness()
    }

    @After fun tearDown() {
        h.close()
        SyncHarness.restoreStoreKey()
    }

    private fun bucket(label: String) = when {
        label.startsWith("sync/room/") -> "replay"
        label == "waypoints.json" -> "waypoints"
        label == "drawings.json" -> "drawings"
        else -> label
    }

    private fun count(name: String) = writes[name]?.get() ?: 0
    private fun resetCounts() = writes.clear()

    private fun waypoint(name: String) = Waypoint(
        id = UUID.randomUUID().toString(), name = name, latitude = -35.0, longitude = 149.0,
        createdAt = 1_700_000_000_000L,
    )

    /** Splits items into relay-sized pages, like the relay's 900 KB chunking. */
    private fun pages(items: List<JSONObject>, maxBytes: Int = 850_000): List<List<JSONObject>> {
        val out = ArrayList<List<JSONObject>>()
        var page = ArrayList<JSONObject>()
        var bytes = 0
        for (item in items) {
            val size = item.toString().length + 1
            if (page.isNotEmpty() && bytes + size > maxBytes) {
                out += page; page = ArrayList(); bytes = 0
            }
            page += item; bytes += size
        }
        if (page.isNotEmpty()) out += page
        return out
    }

    private fun deliverSnapshot(items: List<JSONObject>, seq: Long = 1) {
        h.beginSnapshot(seq)
        val chunks = pages(items)
        chunks.forEachIndexed { i, chunk -> h.snapshotPage(chunk, more = i < chunks.size - 1) }
        if (chunks.isEmpty()) h.snapshotPage(emptyList())
        h.endSnapshot(seq)
    }

    private fun ackHello() {
        val hello = h.socket.sentOfType("hello").last()
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")))
    }

    private fun loc(peer: FakeV3Peer, keys: SyncCrypto.V3RoomKeys, counter: Long): JSONObject {
        val payload = PresencePayloadV3("p", "FRIEND", "TEAM", "INFANTRY", false, -35.0 + counter * 1e-5, 149.0, 0.0, 0.0)
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

    // S2-09 / S4-04 / S5-03: an N-record first-join snapshot used to cost 2N+1
    // replay writes and N store writes, all on the main thread.
    @Test
    fun snapshotOf800RecordsIsOneCommitOneClearOneStoreWrite() {
        val peer = FakeV3Peer(h.keys())
        val records = (1..800).map { peer.waypointRecord(waypoint("w$it"), it.toLong() + 2) }
        h.join()
        h.socket.open(); h.runCurrent()
        resetCounts()
        val started = System.nanoTime()
        deliverSnapshot(records)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        ackHello()
        println("SP3 snapshot 800 records: replayWrites=${count("replay")} waypointWrites=${count("waypoints")} " +
            "drawingWrites=${count("drawings")} ms=$elapsedMs")
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(800, h.waypointStore.committedWaypoints.value.size)
        // commit + marker clear + hello epoch
        assertTrue("replay writes ${count("replay")}", count("replay") <= 3)
        assertTrue("waypoint writes ${count("waypoints")}", count("waypoints") <= 1)
        assertEquals(0, count("drawings"))
    }

    // S5-02 / S4-03 / S3-10: every tombstone used to scan every local object
    // with a fresh Mac per HMAC (~8M HMACs at 2k x 2k).
    @Test
    fun twoThousandTombstonesAgainstTwoThousandLocalObjects() {
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        assertTrue(h.waypointStore.addAll((1..2_000).map { waypoint("local$it") }))
        val tombs = (1..2_000).map {
            peer.record(peer.wireId(UUID.randomUUID().toString()), it.toLong() + 2, "del", null, deleted = true)
        }
        h.join()
        h.socket.open(); h.runCurrent()
        val hmacsBefore = WireIdIndex.hmacCount.get()
        val started = System.nanoTime()
        deliverSnapshot(tombs)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val hmacs = WireIdIndex.hmacCount.get() - hmacsBefore
        ackHello()
        println("SP3 2k tombstones x 2k local: wireIdHmacs=$hmacs ms=$elapsedMs")
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(2_000, h.waypointStore.committedWaypoints.value.size)
        // one HMAC per local object to build the index, none per tombstone
        assertTrue("hmacs $hmacs", hmacs <= 2_000)
    }

    // S5-01 / S3-12: every accepted presence frame resealed the whole replay file.
    @Test
    fun hundredPresenceFramesOver2kStampsUseTheStride() {
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        val tombs = (1..2_000).map {
            peer.record(peer.wireId(UUID.randomUUID().toString()), it.toLong() + 2, "del", null, deleted = true)
        }
        h.join()
        h.socket.open(); h.runCurrent()
        deliverSnapshot(tombs)
        ackHello()
        h.deliver(peer.hello())
        resetCounts()
        val started = System.nanoTime()
        for (c in 1L..100L) h.deliver(loc(peer, keys, c))
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val stateBytes = h.manager.replayStateForTests!!.let { replayFileSize() }
        println("SP3 100 presence frames, 2k stamps: replayWrites=${count("replay")} " +
            "approxBytes=${count("replay").toLong() * stateBytes} ms=$elapsedMs")
        assertEquals(100L, h.manager.peers.value[peer.actor]?.let { 100L })
        // stride 16: writes at 1, 17, 33, 49, 65, 81, 97
        assertTrue("replay writes ${count("replay")}", count("replay") <= 7)
    }

    // S5-03: the first publish of N local objects reserved each stamp with its own write.
    @Test
    fun firstPublishOf300ObjectsReservesInOneWrite() {
        assertTrue(h.waypointStore.addAll((1..300).map { waypoint("mine$it") }))
        h.join()
        h.socket.open(); h.runCurrent()
        deliverSnapshot(emptyList())
        resetCounts()
        val started = System.nanoTime()
        ackHello()
        h.advance(300)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("SP3 first publish 300 objects: replayWrites=${count("replay")} ms=$elapsedMs")
        assertTrue(h.socket.sentOfType("put").isNotEmpty())
        assertTrue("replay writes ${count("replay")}", count("replay") <= 2)
    }

    // S2-09 / S4-04: live records used to cost 2 replay writes + 1 store write each.
    @Test
    fun sixtyFourQueuedLiveRecordsAreOneBatch() {
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        h.join()
        h.completeHandshake()
        h.deliver(peer.hello())
        h.advance(300)
        resetCounts()
        val socket = h.socket
        val started = System.nanoTime()
        for (i in 1..64) socket.deliver(peer.waypointRecord(waypoint("live$i"), 100L + i, t = "put"))
        h.runCurrent()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        println("SP3 64 queued live puts: replayWrites=${count("replay")} waypointWrites=${count("waypoints")} ms=$elapsedMs")
        assertEquals(64, h.waypointStore.committedWaypoints.value.size)
        assertTrue("replay writes ${count("replay")}", count("replay") <= 2)
        assertTrue("waypoint writes ${count("waypoints")}", count("waypoints") <= 1)
    }

    private fun replayFileSize(): Long =
        java.io.File(h.env.filesDir, "sync_replay").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.maxOfOrNull { it.length() } ?: 0L
}
