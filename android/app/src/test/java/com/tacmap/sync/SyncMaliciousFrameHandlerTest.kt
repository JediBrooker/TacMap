package com.tacmap.sync

import com.tacmap.waypoints.Waypoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * S4-12 / S6-09: the hostile corpus has to go through the production handler,
 * not a copy of its parsing rules. Every testdata/malicious_frames.json frame
 * is fed to a real SyncManager (fake socket) in each connection phase, plus
 * the v3 hostile cases the corpus doesn't have yet.
 */
class SyncMaliciousFrameHandlerTest {
    private val harnesses = ArrayList<SyncHarness>()

    @Before fun setUp() = SyncHarness.installStoreKey()

    @After fun tearDown() {
        harnesses.forEach { it.close() }
        SyncHarness.restoreStoreKey()
    }

    private data class Case(val name: String, val frame: String)

    private val corpus: List<Case> by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var text: String? = null
        repeat(8) {
            val f = File(dir, "testdata/malicious_frames.json")
            if (text == null && f.exists()) text = f.readText()
            dir = dir?.parentFile
        }
        Json.parseToJsonElement(checkNotNull(text)).jsonObject["cases"]!!.jsonArray.map {
            val o = it.jsonObject
            Case(o["name"]!!.jsonPrimitive.content, o["frame"]!!.jsonPrimitive.content)
        }
    }

    private enum class Phase { CONNECTING, SNAPSHOTTING, AWAITING_HELLO_ACK, CONNECTED }

    private fun harness(): SyncHarness = SyncHarness().also { harnesses += it }

    private fun v3At(phase: Phase): SyncHarness {
        val h = harness()
        h.join()
        h.socket.open()
        h.runCurrent()
        if (phase == Phase.CONNECTING) return h
        h.beginSnapshot(seq = 3)
        if (phase == Phase.SNAPSHOTTING) return h
        h.snapshotPage(emptyList())
        h.endSnapshot(seq = 3)
        if (phase == Phase.AWAITING_HELLO_ACK) return h
        val hello = h.socket.sentOfType("hello").single()
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")))
        check(h.manager.status.value == SyncManager.Status.CONNECTED)
        return h
    }

    private fun v2Connected(): SyncHarness {
        val h = harness()
        h.join(SyncHarness.CODE_V2)
        h.socket.open()
        h.runCurrent()
        h.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 1))
        h.deliver(JSONObject().put("t", "snapshot").put("items", JSONArray()).put("more", false).put("members", JSONArray()))
        h.deliver(JSONObject().put("t", "snapshot-end").put("seq", 1))
        check(h.manager.status.value == SyncManager.Status.CONNECTED)
        return h
    }

    private data class Fingerprint(
        val waypoints: Int,
        val features: Int,
        val localCounter: Long?,
        val lastSnapshotSeq: Long?,
        val peers: Int,
    )

    private fun fingerprint(h: SyncHarness) = Fingerprint(
        h.waypointStore.committedWaypoints.value.size,
        h.drawingStore.committedDocument.value.features.size,
        h.manager.replayStateForTests?.localCounter,
        h.manager.replayStateForTests?.lastSnapshotSeq,
        h.manager.peers.value.size,
    )

    private fun isFence(frame: String): Boolean =
        runCatching { JSONObject(frame).opt("t") as? String }.getOrNull() in setOf("snapshot", "snapshot-begin", "snapshot-end")

    @Test
    fun everyCorpusFrameIsDroppedByTheRealV3HandlerInEveryPhase() {
        assertTrue(corpus.size >= 20)
        for (phase in Phase.entries) for (case in corpus) {
            val h = v3At(phase)
            val socket = h.socket
            val before = fingerprint(h)
            socket.deliver(case.frame)
            h.runCurrent()
            val label = "${case.name} in $phase"
            assertTrue("$label crashed: ${h.uncaught}", h.uncaught.isEmpty())
            assertEquals(label, before, fingerprint(h))
            if (!isFence(case.frame)) {
                assertFalse("$label closed the socket", socket.terminal)
                assertNotEquals(label, SyncIssueKind.SECURITY, h.manager.currentIssueKind)
            }
        }
    }

    @Test
    fun everyCorpusFrameIsDroppedByTheRealV2Handler() {
        for (case in corpus) {
            val h = v2Connected()
            val before = fingerprint(h)
            h.socket.deliver(case.frame)
            h.runCurrent()
            assertTrue("${case.name}: ${h.uncaught}", h.uncaught.isEmpty())
            assertEquals(case.name, before, fingerprint(h))
            assertFalse(case.name, h.socket.terminal)
        }
    }

    /**
     * Mutation check for strictVersion: each malicious `v` gets a record that is
     * otherwise perfectly sealed and signed for the value a lenient parser would
     * read, so the strict version check is the only thing standing in the way.
     */
    @Test
    fun maliciousVersionsAreRejectedEvenWhenTheRestOfTheRecordIsValid() {
        val keys = SyncHarness.cachedV2(SyncHarness.CODE_V2.removePrefix("2:"))
        val versionCases = corpus.filter { it.name.startsWith("version_") || it.name == "array_as_version" }
        assertTrue(versionCases.size >= 8)
        for (case in versionCases) {
            val raw = JSONObject(case.frame).opt("v")
            val lenient: Long = when (raw) {
                is Number -> raw.toDouble().let { if (it.isNaN()) 0L else it.toLong() }
                is String -> raw.toLongOrNull() ?: 0L
                is Boolean -> if (raw) 1L else 0L
                else -> 1L
            }
            val h = v2Connected()
            val wp = Waypoint(name = "evil", latitude = 1.0, longitude = 2.0, createdAt = 1L)
            val content = FakeV3Peer.waypointContent(wp)
            val seed = SyncSigning.generateSeed()
            val by = "c0ffee00-0000-4000-8000-000000000002"
            val sig = SyncSigning.sign(seed, SyncSigning.objectMessage(wp.id, lenient, "waypoint", by, content))
            val inner = JSONObject().put("c", content).put("pub", SyncSigning.publicKey(seed)).put("sig", sig)
            val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(
                keys.roomKey, inner.toString().toByteArray(), SyncCrypto.aad(wp.id, lenient, "waypoint"),
            ))
            val frame = JSONObject(case.frame).put("id", wp.id).put("by", by).put("ct", ct).toString()
            h.socket.deliver(frame)
            h.runCurrent()
            assertTrue(case.name, h.waypointStore.committedWaypoints.value.isEmpty())
            assertTrue(case.name, h.uncaught.isEmpty())
        }
        // sanity: the same construction with an honest version does apply
        val h = v2Connected()
        val wp = Waypoint(name = "fine", latitude = 1.0, longitude = 2.0, createdAt = 1L)
        val content = FakeV3Peer.waypointContent(wp)
        val seed = SyncSigning.generateSeed()
        val by = "c0ffee00-0000-4000-8000-000000000003"
        val sig = SyncSigning.sign(seed, SyncSigning.objectMessage(wp.id, 5L, "waypoint", by, content))
        val inner = JSONObject().put("c", content).put("pub", SyncSigning.publicKey(seed)).put("sig", sig)
        val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(keys.roomKey, inner.toString().toByteArray(), SyncCrypto.aad(wp.id, 5L, "waypoint")))
        h.socket.deliver(JSONObject().put("t", "put").put("id", wp.id).put("v", 5).put("by", by).put("kind", "waypoint").put("ct", ct))
        h.runCurrent()
        assertEquals("fine", h.waypointStore.committedWaypoints.value.single().name)
    }

    // ---- v3 hostile cases that malicious_frames.json doesn't carry yet ----

    private fun assertStructuralReject(h: SyncHarness, socket: FakeSocket) {
        assertTrue(socket.terminal)
        assertTrue(socket.sentOfType("hello").isEmpty())
        assertEquals(-1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    @Test
    fun structuralSnapshotViolationsCommitNothing() {
        val keys = SyncHarness.cachedV3(SyncHarness.CODE.removePrefix("3:"))
        val peer = FakeV3Peer(keys)
        val wp = Waypoint(name = "x", latitude = 1.0, longitude = 2.0, createdAt = 1L)
        val good = peer.waypointRecord(wp, 3)
        val scripts: List<Pair<String, (SyncHarness) -> Unit>> = listOf(
            "duplicate ids" to { h -> h.beginSnapshot(); h.snapshotPage(listOf(good, good)); h.endSnapshot() },
            "end seq mismatch" to { h -> h.beginSnapshot(seq = 4); h.snapshotPage(listOf(good)); h.endSnapshot(seq = 5) },
            "page after final" to { h ->
                h.beginSnapshot(); h.snapshotPage(listOf(good)); h.snapshotPage(emptyList()); h.endSnapshot()
            },
            "page before begin" to { h ->
                h.socket.open(); h.runCurrent(); h.snapshotPage(listOf(good)); h.beginSnapshot(); h.endSnapshot()
            },
            "end before final page" to { h -> h.beginSnapshot(); h.snapshotPage(listOf(good), more = true); h.endSnapshot() },
            "second begin" to { h -> h.beginSnapshot(); h.beginSnapshot(seq = 2) },
            "items not an array" to { h ->
                h.beginSnapshot(); h.deliver(JSONObject().put("t", "snapshot").put("items", "nope").put("more", false))
            },
            "more not boolean" to { h ->
                h.beginSnapshot(); h.deliver(JSONObject().put("t", "snapshot").put("items", JSONArray()).put("more", "false"))
            },
            "item not an object" to { h -> h.beginSnapshot(); h.snapshotPage(listOf("str")); h.endSnapshot() },
            "non canonical id" to { h ->
                h.beginSnapshot(); h.snapshotPage(listOf(JSONObject(good.toString()).put("id", "not-base64url!"))); h.endSnapshot()
            },
            "fractional seq" to { h ->
                h.socket.open(); h.runCurrent(); h.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 1.5))
            },
            "too many items" to { h ->
                h.beginSnapshot()
                h.snapshotPage((0..10_000).map { JSONObject().put("id", SyncIdentity.urlB64(ByteArray(32) { b -> (it + b).toByte() })) })
                h.endSnapshot()
            },
        )
        for ((name, script) in scripts) {
            val h = harness()
            h.join()
            val socket = h.socket
            script(h)
            try {
                assertStructuralReject(h, socket)
            } catch (e: AssertionError) {
                throw AssertionError(name, e)
            }
        }
    }

    @Test
    fun perRecordGarbageIsSkippedNotFatal() {
        val keys = SyncHarness.cachedV3(SyncHarness.CODE.removePrefix("3:"))
        val peer = FakeV3Peer(keys)
        val wp = Waypoint(name = "x", latitude = 1.0, longitude = 2.0, createdAt = 1L)
        val good = peer.waypointRecord(wp, 3)
        fun variant(id: String, edit: JSONObject.() -> Unit) =
            JSONObject(good.toString()).put("id", SyncIdentity.urlB64(ByteArray(32) { (id.hashCode() + it).toByte() })).apply(edit)
        val garbage = listOf(
            variant("counter over 2^63") { put("vs", "8000000000000000:" + peer.actor) },
            variant("vs not a string") { put("vs", 5) },
            variant("by mismatch") { put("by", "A".repeat(43)) },
            variant("pub not canonical") { put("pub", "short") },
            variant("bad kind") { put("kind", "../../etc") },
            variant("deleted not boolean") { put("deleted", "yes") },
            variant("put marked deleted") { put("deleted", true) },
            variant("ct not base64") { put("ct", "!!!") },
            variant("ct too short") { put("ct", "AQID") },
            peer.record(SyncIdentity.urlB64(ByteArray(32) { 9 }), 4, "waypoint", FakeV3Peer.waypointContent(wp), badSignature = true),
            peer.record(SyncIdentity.urlB64(ByteArray(32) { 10 }), 5, "waypoint", "{}"),
            peer.record(SyncIdentity.urlB64(ByteArray(32) { 11 }), 6, "waypoint", ""),
        )
        val h = harness()
        h.join()
        h.completeHandshake(listOf(good) + garbage)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(listOf("x"), h.waypointStore.committedWaypoints.value.map { it.name })
        assertEquals(1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertTrue(h.uncaught.isEmpty())
        assertEquals(1, h.transport.sockets.size)
    }

    @Test
    fun deeplyNestedJsonDoesNotTakeTheProcessDown() {
        val h = v3At(Phase.CONNECTED)
        val depth = 200_000
        h.socket.deliver("[".repeat(depth) + "]".repeat(depth))
        h.socket.deliver("{\"t\":\"put\",\"x\":" + "[".repeat(depth / 2) + "]".repeat(depth / 2) + "}")
        h.runCurrent()
        assertTrue(h.uncaught.toString(), h.uncaught.isEmpty())
        assertFalse(h.socket.terminal)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
    }

    @Test
    fun forgedAcksLeavesAndChatsChangeNothing() {
        val h = v3At(Phase.AWAITING_HELLO_ACK)
        val hello = h.socket.sentOfType("hello").single()
        // hello-ack for the wrong session or stamp is ignored
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", SyncIdentity.urlB64(ByteArray(32) { 1 })).put("vs", hello.getString("vs")))
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", "0000000000000001:" + hello.getString("by")))
        assertEquals(SyncManager.Status.SNAPSHOTTING, h.manager.status.value)
        h.deliver(JSONObject().put("t", "hello-ack").put("by", hello.getString("by"))
            .put("sd", hello.getString("sd")).put("vs", hello.getString("vs")))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)

        h.addWaypoint("pending")
        h.advance(300)
        val put = h.socket.sentOfType("put").single()
        // op-ack with the wrong cth doesn't resolve the op, so it's retransmitted
        h.deliver(JSONObject().put("t", "op-ack").put("av", 1).put("rid", put.getString("rid"))
            .put("by", put.getString("by")).put("sd", put.getString("sd")).put("id", put.getString("id"))
            .put("vs", put.getString("vs")).put("kind", put.getString("kind"))
            .put("cth", SyncIdentity.urlB64(ByteArray(32) { 7 })))
        h.advance(5_100)
        assertEquals(2, h.socket.sentOfType("put").size)
        // leave for a session nobody announced, chat for an unknown kid
        h.deliver(JSONObject().put("t", "leave").put("by", "A".repeat(43)).put("sd", "B".repeat(43)).put("explicit", true))
        h.deliver(JSONObject().put("t", "chat").put("cv", 1).put("scope", "room").put("by", "A".repeat(43))
            .put("sd", "B".repeat(43)).put("vs", "0000000000000001:" + "A".repeat(43))
            .put("mid", UUID.randomUUID().toString()).put("fromKid", "C".repeat(43)).put("ct", "AAAA").put("sig", "x"))
        assertTrue(h.manager.chatMessages.value.isEmpty())
        assertFalse(h.socket.terminal)
        assertTrue(h.uncaught.isEmpty())
    }
}
