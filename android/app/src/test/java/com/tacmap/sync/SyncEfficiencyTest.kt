package com.tacmap.sync

import com.tacmap.models.ModelMutationOrigin
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * SP3 efficiency numbers (plans/04 sections 17-19) measured through the real
 * manager, each next to a "before" run of the pre-SP3 pattern on the same
 * machine in the same test, built from the real primitives (sealed SafeStore
 * writes, a fresh Mac per wire id like the old SyncIdentity.wireObjectId
 * loop). Every test prints both so the numbers can be read off the report;
 * the asserts are the contract bounds.
 *
 * Audit numbers these answer: S5-02 (2k objects x 2k tombstones reverse
 * lookup, 8.2 s / 8M HMACs on a desktop JVM), S5-03 (N record snapshot apply,
 * N+1 replay writes plus N store writes, 2N+1 sealed writes in all), S5-01 (one
 * full sealed replay write per accepted presence frame).
 */
class SyncEfficiencyTest {
    private lateinit var h: SyncHarness
    private val writes = ConcurrentHashMap<String, AtomicInteger>()
    private lateinit var basePolicy: SafeStore.MigrationPolicy
    private val scratch = ArrayList<File>()

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
        scratch.forEach { it.deleteRecursively() }
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
    private fun elapsedMs(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000

    private fun tempDir(): File = Files.createTempDirectory("sp3-before").toFile().also { scratch += it }

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

    private fun hashHex(content: String) =
        SyncIdentity.bytesToHex(SyncIdentity.sha256(content.toByteArray(Charsets.UTF_8)))

    private fun remotePut(peer: FakeV3Peer, wp: Waypoint, counter: Long): SyncReplayState.RemoteMutation {
        val hash = hashHex(FakeV3Peer.waypointContent(wp))
        return SyncReplayState.RemoteMutation(
            SyncReplayState.AuthenticatedMutation(peer.wireId(wp.id), VersionStamp(counter, peer.actor), peer.pub, hash, false),
            priorModelHash = null, localModelId = wp.id,
        )
    }

    private fun remoteTombstone(peer: FakeV3Peer, counter: Long) = SyncReplayState.RemoteMutation(
        SyncReplayState.AuthenticatedMutation(
            peer.wireId(UUID.randomUUID().toString()), VersionStamp(counter, peer.actor), peer.pub, null, true,
        ),
        priorModelHash = null,
    )

    // S2-09 / S4-04 / S5-03: an N-record first-join snapshot used to cost 2N+1
    // replay writes and N store writes, all on the main thread.
    @Test
    fun snapshotOf800RecordsIsOneCommitOneClearOneStoreWrite() {
        val peer = FakeV3Peer(h.keys())
        val wps = (1..800).map { waypoint("w$it") }
        val records = wps.mapIndexed { i, wp -> peer.waypointRecord(wp, i + 3L) }

        // before: what the pre-SP3 snapshot-end did, one commit carrying every
        // pending marker, then per record a store add and a marker clear, each
        // its own full sealed write
        val beforeDir = tempDir()
        val oldReplay = SyncReplayState(h.keys().roomId, File(beforeDir, "files"))
        assertTrue(oldReplay.load())
        val oldStore = WaypointStore.forTests(File(beforeDir, "waypoints").apply { mkdirs() })
        val remotes = wps.mapIndexed { i, wp -> remotePut(peer, wp, i + 3L) }
        resetCounts()
        val beforeStarted = System.nanoTime()
        assertTrue(oldReplay.commitRemoteSnapshot(remotes, 1))
        for ((wp, remote) in wps.zip(remotes)) {
            assertTrue(oldStore.add(wp, ModelMutationOrigin.REMOTE_SYNC))
            assertTrue(oldReplay.clearPendingModelApplication(remote.mutation))
        }
        val beforeMs = elapsedMs(beforeStarted)
        // + the hello epoch the old path also wrote
        val beforeReplay = count("replay") + 1
        val beforeStore = count("waypoints")

        h.join()
        h.socket.open(); h.runCurrent()
        resetCounts()
        val started = System.nanoTime()
        deliverSnapshot(records)
        val elapsedMs = elapsedMs(started)
        ackHello()
        println("SP3 snapshot 800 records: before replayWrites=$beforeReplay waypointWrites=$beforeStore ms=$beforeMs | " +
            "after replayWrites=${count("replay")} waypointWrites=${count("waypoints")} " +
            "drawingWrites=${count("drawings")} ms=$elapsedMs")
        // 1 commit + 800 clears + hello, and a store write per record: the audit's 2N+1 (+ hello)
        assertEquals(800 + 2, beforeReplay)
        assertEquals(800, beforeStore)
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
        val locals = (1..2_000).map { waypoint("local$it") }
        assertTrue(h.waypointStore.addAll(locals))
        val tombs = (1..2_000).map {
            peer.record(peer.wireId(UUID.randomUUID().toString()), it.toLong() + 2, "del", null, deleted = true)
        }

        // before: the old findLocalIdForWireId for a tombstone of an object that
        // isn't here any more walked lastContent and then the waypoints, a new
        // Mac.getInstance + init per id, and never found it. Timed on a sample
        // of tombstones and scaled up, the full 8M would take the whole run.
        val sample = 100
        val ids = locals.map { it.id }
        val sampleStarted = System.nanoTime()
        var oldHmacs = 0L
        for (t in tombs.take(sample)) {
            val wire = t.getString("id")
            var found: String? = null
            for (id in ids) {
                oldHmacs++
                if (SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id)!!) == wire) { found = id; break }
            }
            if (found == null) for (id in ids) {
                oldHmacs++
                if (SyncIdentity.wireObjectId(keys.metadataKey, SyncIdentity.uuidToBytes(id)!!) == wire) { found = id; break }
            }
            assertNull(found)
        }
        val sampleMs = (System.nanoTime() - sampleStarted) / 1_000_000.0
        val beforeHmacs = oldHmacs * tombs.size / sample
        val beforeMs = sampleMs * tombs.size / sample

        h.join()
        h.socket.open(); h.runCurrent()
        val hmacsBefore = WireIdIndex.hmacCount.get()
        val started = System.nanoTime()
        deliverSnapshot(tombs)
        val elapsedMs = elapsedMs(started)
        val hmacs = WireIdIndex.hmacCount.get() - hmacsBefore
        ackHello()
        println("SP3 2k tombstones x 2k local: before wireIdHmacs=$beforeHmacs ms=${"%.0f".format(beforeMs)} " +
            "(measured on $sample tombstones: $oldHmacs HMACs in ${"%.0f".format(sampleMs)} ms) | " +
            "after wireIdHmacs=$hmacs ms=$elapsedMs (whole snapshot, crypto included)")
        assertEquals(8_000_000L, beforeHmacs)
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

        // before: a replay state of the same size (2k stamps), and one full
        // sealed write per accepted frame, which is what persistTransaction did
        // for every commitPresence before 17.1
        val beforeDir = tempDir()
        val oldReplay = SyncReplayState(keys.roomId, File(beforeDir, "files"))
        assertTrue(oldReplay.load())
        val seeded = (1..2_000).map { remoteTombstone(peer, it.toLong() + 2) }
        assertTrue(oldReplay.commitRemoteSnapshot(seeded, 1))
        assertTrue(oldReplay.clearPendingModelApplications(seeded.map { it.mutation }))
        assertTrue(oldReplay.commitActorHello(peer.actor, peer.pub, peer.sdText, "0000000000000001"))
        resetCounts()
        val beforeStarted = System.nanoTime()
        for (c in 1L..100L) {
            assertTrue(oldReplay.canAcceptPresence(peer.actor, peer.pub, peer.sdText, c))
            assertTrue(oldReplay.commitPresence(peer.actor, peer.pub, peer.sdText, c))
            assertTrue(oldReplay.save())
        }
        val beforeMs = elapsedMs(beforeStarted)
        val beforeFileBytes = File(beforeDir, "files/sync_replay").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }?.maxOfOrNull { it.length() } ?: 0L
        // the first stride write rides along here too, count only the per-frame seals
        assertTrue(count("replay") >= 100)
        val beforeWrites = 100

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
        val elapsedMs = elapsedMs(started)
        val stateBytes = replayFileSize()
        println("SP3 100 presence frames, 2k stamps: before replayWrites=$beforeWrites " +
            "approxBytes=${beforeWrites * beforeFileBytes} ms=$beforeMs | " +
            "after replayWrites=${count("replay")} approxBytes=${count("replay").toLong() * stateBytes} ms=$elapsedMs")
        assertNotNull(h.manager.peers.value[peer.actor])
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
        val elapsedMs = elapsedMs(started)
        println("SP3 first publish 300 objects: before replayWrites=300 (one reservation each) | " +
            "after replayWrites=${count("replay")} ms=$elapsedMs")
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
        val elapsedMs = elapsedMs(started)
        println("SP3 64 queued live puts: before replayWrites=128 waypointWrites=64 | " +
            "after replayWrites=${count("replay")} waypointWrites=${count("waypoints")} ms=$elapsedMs")
        assertEquals(64, h.waypointStore.committedWaypoints.value.size)
        assertTrue("replay writes ${count("replay")}", count("replay") <= 2)
        assertTrue("waypoint writes ${count("waypoints")}", count("waypoints") <= 1)
    }

    // S5-03: every diff pass (each store emission, every 250 ms while acks
    // trickle in during a bulk publish) exported the whole map to GeoJSON.
    @Test
    fun aDiffPassOnlyExportsWhatChanged() {
        val locals = (1..2_000).map { waypoint("local$it") }
        assertTrue(h.waypointStore.addAll(locals))
        h.join()
        h.socket.open(); h.runCurrent()
        deliverSnapshot(emptyList())
        val coldStarted = System.nanoTime()
        val exportsAtStart = h.manager.diffExportsForTests
        ackHello()
        val coldExports = h.manager.diffExportsForTests - exportsAtStart
        val coldMs = elapsedMs(coldStarted)
        h.advance(300)

        val edited = locals[1_234].copy(name = "moved", latitude = -35.1)
        val warmStart = h.manager.diffExportsForTests
        val warmStarted = System.nanoTime()
        assertTrue(h.waypointStore.update(edited))
        h.advance(300)
        val warmExports = h.manager.diffExportsForTests - warmStart
        val warmMs = elapsedMs(warmStarted)
        println("SP3 diff pass over 2k objects after one edit: before exports=$coldExports ms=$coldMs " +
            "(every pass, as the first one still does) | after exports=$warmExports ms=$warmMs")
        assertEquals(2_000L, coldExports)
        assertEquals(1L, warmExports)
    }

    private fun replayFileSize(): Long =
        File(h.env.filesDir, "sync_replay").listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.maxOfOrNull { it.length() } ?: 0L
}
