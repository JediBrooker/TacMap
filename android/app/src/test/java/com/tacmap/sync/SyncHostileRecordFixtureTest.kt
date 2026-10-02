package com.tacmap.sync

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.export.GeoJsonExporter
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * S4-12 / S6-09, v3 half: every record-level reason the contract lists in
 * testdata/sync_client_behaviour.json `snapshot` gets a hostile record built
 * for it, checked against the classifier for the exact reason and then pushed
 * through the real SyncManager snapshot and live paths. A new reason in the
 * fixture without a builder here fails the build.
 */
class SyncHostileRecordFixtureTest {
    private val harnesses = ArrayList<SyncHarness>()

    @Before fun setUp() = SyncHarness.installStoreKey()

    @After fun tearDown() {
        harnesses.forEach { it.close() }
        SyncHarness.restoreStoreKey()
    }

    private fun harness() = SyncHarness().also { harnesses += it }

    private val snapshotLists by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var text: String? = null
        repeat(8) {
            val f = File(dir, "testdata/sync_client_behaviour.json")
            if (text == null && f.exists()) text = f.readText()
            dir = dir?.parentFile
        }
        val snapshot = Json.parseToJsonElement(checkNotNull(text)).jsonObject.getValue("snapshot").jsonObject
        listOf("fatalStructural", "skipUnverified", "skipUnsupported").associateWith { key ->
            snapshot.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        }
    }

    private fun waypoint(name: String, id: String = UUID.randomUUID().toString()) =
        Waypoint(id = id, name = name, latitude = -35.0, longitude = 149.0, createdAt = 1_700_000_000_000L)

    /** Everything a builder needs, per harness so keys and pins line up. */
    private inner class Hostile(val h: SyncHarness) {
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        val other = FakeV3Peer(keys)
        /** A local drawing the identity collision record reuses the UUID of. */
        val localDrawing = DrawingFeature(
            name = "local", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        /** Pinned to a key the record doesn't carry. */
        val pinnedVictim = FakeV3Peer(keys)
        private var counter = 10L

        private fun next() = ++counter

        private fun base(name: String, edit: JSONObject.() -> Unit = {}): JSONObject {
            val wp = waypoint(name)
            return peer.waypointRecord(wp, next()).apply(edit)
        }

        private fun content(vararg waypoints: Waypoint) =
            GeoJsonExporter.export(waypoints.toList(), emptyList(), emptyList(), density = 1f)

        /** Reason wire name -> a record that must be skipped for exactly that reason. */
        val skipBuilders: Map<String, () -> JSONObject> = mapOf(
            "vs_unparseable" to { base("vs") { put("vs", "zz") } },
            "by_not_equal_vs_actor" to { base("by") { put("by", other.actor) } },
            "pub_or_sd_not_canonical" to { base("sd") { put("sd", "short") } },
            "actor_binding_mismatch" to { base("binding") { put("pub", other.pub) } },
            "pub_differs_from_pinned_key" to {
                val wp = waypoint("pinned")
                pinnedVictim.waypointRecord(wp, next())
            },
            "kind_syntax_invalid" to { base("kind") { put("kind", "../etc") } },
            "type_and_deleted_inconsistent" to { base("deleted") { put("deleted", "yes") } },
            "ct_not_canonical_or_too_short_or_too_long" to { base("short ct") { put("ct", "AQID") } },
            "aead_open_failed" to {
                base("aead") { put("ct", SyncCrypto.encodeBase64(ByteArray(64) { (it * 29 + 3).toByte() })) }
            },
            "inner_json_invalid" to {
                val wp = waypoint("inner")
                val wire = peer.wireId(wp.id)
                val vs = VersionStamp(next(), peer.actor).encode()
                val ct = SyncCrypto.encodeBase64(SyncCrypto.seal(
                    keys.roomKey, "definitely not json".toByteArray(), SyncCrypto.aadV3(wire, vs, "waypoint"),
                ))
                JSONObject().put("id", wire).put("vs", vs).put("by", peer.actor).put("kind", "waypoint")
                    .put("ct", ct).put("pub", peer.pub).put("sd", peer.sdText).put("deleted", false)
            },
            "signature_missing_or_invalid" to {
                val wp = waypoint("sig")
                peer.record(peer.wireId(wp.id), next(), "waypoint", content(wp), badSignature = true)
            },
            "tombstone_inner_has_extra_keys" to {
                val wp = waypoint("tomb")
                peer.record(peer.wireId(wp.id), next(), "del", null, deleted = true, innerExtra = mapOf("c" to "smuggled"))
            },
            "kind_unknown_but_authentic" to {
                peer.record(peer.wireId(UUID.randomUUID().toString()), next(), "route", """{"type":"FeatureCollection","features":[]}""")
            },
            "put_content_missing_or_empty" to {
                peer.record(peer.wireId(UUID.randomUUID().toString()), next(), "waypoint", "")
            },
            "importer_failed" to {
                peer.record(peer.wireId(UUID.randomUUID().toString()), next(), "waypoint", "{")
            },
            "importer_invalid_skipped_nonzero" to {
                val wp = waypoint("half valid")
                val doc = JSONObject(content(wp))
                doc.getJSONArray("features").put(JSONObject().put("type", "Feature")
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray(listOf(999, 999))))
                    .put("properties", JSONObject()))
                peer.record(peer.wireId(wp.id), next(), "waypoint", doc.toString())
            },
            "object_count_not_exactly_one" to {
                val a = waypoint("a")
                peer.record(peer.wireId(a.id), next(), "waypoint", content(a, waypoint("b")))
            },
            "kind_content_mismatch" to {
                val wp = waypoint("not a drawing")
                peer.record(peer.wireId(wp.id), next(), "drawing", content(wp))
            },
            "embedded_uuid_does_not_match_wire_id" to {
                val wp = waypoint("moved")
                peer.record(peer.wireId(UUID.randomUUID().toString()), next(), "waypoint", content(wp))
            },
            "identity_collision_with_other_object_kind" to {
                val wp = waypoint("impostor", id = localDrawing.id)
                peer.record(peer.wireId(wp.id), next(), "waypoint", content(wp))
            },
        )

        fun localKindOf(id: String): String? = if (id == localDrawing.id) "drawing" else null
    }

    /**
     * The importer and receiver hash use the same layers, so once a record
     * parsed and matched its wire ID the expected hash always exists. Kept in
     * the contract for parity with iOS, it just can't be built from the wire.
     */
    private val notConstructibleFromTheWire = setOf("expected_model_hash_unavailable")

    @Test
    fun everyRecordReasonHasAHostileBuilderAndClassifiesExactly() {
        val h = harness()
        h.join()
        val hostile = Hostile(h)
        val skipNames = snapshotLists.getValue("skipUnverified") + snapshotLists.getValue("skipUnsupported")
        assertEquals("builders out of date with the fixture",
            skipNames.toSet() - notConstructibleFromTheWire, hostile.skipBuilders.keys)
        val validator = SnapshotValidator(
            hostile.keys.roomKey, hostile.keys.roomIdRaw, hostile.keys.metadataKey,
            { actor -> if (actor == hostile.pinnedVictim.actor) hostile.other.pub else null }, 1f,
        )
        for ((name, build) in hostile.skipBuilders) {
            val rec = build()
            val check = SnapshotRecordClassifier.classify(validator, rec, rec.getString("id"), emptyList(), hostile::localKindOf)
            assertTrue("$name: $check", check is V3Check.Skip)
            assertEquals(name, name, (check as V3Check.Skip).reason.wireName)
            assertEquals(name, SnapshotRecordClassifier.category(name), check.reason.category)
        }
        validator.close()
    }

    @Test
    fun everyHostileRecordInOneSnapshotIsSkippedThroughTheRealManager() {
        val h = harness()
        val hostile = Hostile(h)
        assertTrue(h.drawingStore.addFeature(hostile.localDrawing))
        h.join()
        // a pin from an earlier session that the hostile record doesn't match
        assertTrue(h.manager.replayStateForTests!!.registerActor(hostile.pinnedVictim.actor, hostile.other.pub))
        val records = hostile.skipBuilders.mapValues { (_, build) -> build() }
        val good = waypoint("good")
        h.completeHandshake(records.values.toList() + hostile.peer.waypointRecord(good, 500))

        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(1, h.transport.sockets.size)
        assertFalse(h.socket.terminal)
        assertTrue(h.uncaught.isEmpty())
        assertEquals(listOf("good"), h.waypointStore.committedWaypoints.value.map { it.name })
        assertEquals(listOf(hostile.localDrawing.id), h.drawingStore.committedDocument.value.features.map { it.id })
        val replay = h.manager.replayStateForTests!!
        for ((name, rec) in records) {
            val wire = rec.getString("id")
            assertEquals(name, SnapshotRecordClassifier.category(name), h.manager.skippedCategoryForTests(wire))
            if (wire != hostile.peer.wireId(hostile.localDrawing.id)) assertNull(name, replay.getStamp(wire))
        }
        assertFalse("unverified skips mean not verified-clean", h.manager.lastSnapshotVerifiedCleanForTests)
        assertEquals(setOf("SKIPPED_UNVERIFIED", "SKIPPED_UNSUPPORTED"),
            h.manager.surfacedIssueCodesForTests.filter { it.startsWith("SKIPPED_") }.toSet())
    }

    @Test
    fun everyHostileRecordLiveIsSkippedWithoutAReconnect() {
        val h = harness()
        val hostile = Hostile(h)
        assertTrue(h.drawingStore.addFeature(hostile.localDrawing))
        h.join()
        assertTrue(h.manager.replayStateForTests!!.registerActor(hostile.pinnedVictim.actor, hostile.other.pub))
        h.completeHandshake()
        h.deliver(hostile.peer.hello())
        for ((name, build) in hostile.skipBuilders) {
            val rec = build()
            val live = JSONObject(rec.toString()).apply {
                val deleted = opt("deleted") == true
                remove("deleted")
                put("t", if (deleted) "del" else "put")
            }
            if (name == "type_and_deleted_inconsistent") live.put("deleted", "yes")
            h.deliver(live)
            assertEquals(name, SnapshotRecordClassifier.category(name), h.manager.skippedCategoryForTests(rec.getString("id")))
        }
        h.advance(1_000)
        assertFalse(h.socket.terminal)
        assertEquals(1, h.transport.sockets.size)
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
        assertTrue(h.uncaught.isEmpty())
    }

    // ---- structural: the two fatal rows SyncMaliciousFrameHandlerTest doesn't script ----

    @Test
    fun snapshotBeginAfterConnectedIsFatalAndCommitsNothing() {
        assertTrue("snapshot_begin_when_not_connecting" in snapshotLists.getValue("fatalStructural"))
        val h = harness()
        h.join()
        h.completeHandshake(seq = 7)
        val socket = h.socket
        h.deliver(JSONObject().put("t", "snapshot-begin").put("seq", 99))
        h.deliver(JSONObject().put("t", "snapshot").put("items", JSONArray()).put("more", false))
        h.deliver(JSONObject().put("t", "snapshot-end").put("seq", 99))
        assertTrue(socket.terminal)
        assertEquals(7L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test
    fun snapshotOverTheAggregateByteCeilingCommitsNothing() {
        assertTrue("aggregate_bytes_over_54525952" in snapshotLists.getValue("fatalStructural"))
        val h = harness()
        h.join()
        val socket = h.socket
        h.beginSnapshot()
        val pad = "p".repeat(1_000_000)
        var page = 0
        // the handshake receive budget has the same 54,525,952 byte ceiling and
        // counts the same frames, so whichever trips first, nothing may commit
        while (!socket.terminal && page < 60) {
            val item = JSONObject().put("id", SyncIdentity.urlB64(ByteArray(32) { (page + it).toByte() })).put("pad", pad)
            h.snapshotPage(listOf(item), more = true)
            page += 1
        }
        assertTrue("closed after $page pages", socket.terminal)
        assertTrue(page in 54..56)
        assertTrue(socket.sentOfType("hello").isEmpty())
        assertEquals(-1L, h.manager.replayStateForTests!!.lastSnapshotSeq)
        assertTrue(h.uncaught.isEmpty())
    }
}
