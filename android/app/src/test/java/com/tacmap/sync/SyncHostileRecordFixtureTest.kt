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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    private val snapshotFixture by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var text: String? = null
        repeat(8) {
            val f = File(dir, "testdata/sync_client_behaviour.json")
            if (text == null && f.exists()) text = f.readText()
            dir = dir?.parentFile
        }
        Json.parseToJsonElement(checkNotNull(text)).jsonObject.getValue("snapshot").jsonObject
    }

    private val snapshotLists by lazy {
        listOf("fatalStructural", "skipUnverified", "skipUnsupported").associateWith { key ->
            snapshotFixture.getValue(key).jsonArray.map { it.jsonPrimitive.content }
        }
    }

    /** One row of snapshot.embeddedIdCases (plans/04 2.7). */
    private class EmbeddedIdCase(
        val id: String,
        val embeddedId: String,
        val outerBytesHex: String,
        val category: SnapshotRecordCategory?,
        val localId: String?,
        val stateKey: String?,
        val reason: String?,
        val paths: List<String>,
    ) {
        val valid: Boolean get() = category == null
    }

    private val embeddedIdCases by lazy {
        snapshotFixture.getValue("embeddedIdCases").jsonArray.map { el ->
            val row = el.jsonObject
            val expect = row.getValue("expect").jsonObject
            fun str(o: kotlinx.serialization.json.JsonObject, key: String) =
                (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            assertEquals("stopsSync", "false", expect.getValue("stopsSync").jsonPrimitive.content)
            assertEquals("persistenceFailure", "false", expect.getValue("persistenceFailure").jsonPrimitive.content)
            EmbeddedIdCase(
                id = str(row, "id")!!,
                embeddedId = str(row, "embeddedId")!!,
                outerBytesHex = str(row, "outerWireIdFromBytesHex")!!,
                category = when (val c = str(expect, "category")) {
                    "valid" -> null
                    "skipUnsupported" -> SnapshotRecordCategory.SKIP_UNSUPPORTED
                    "skipUnverified" -> SnapshotRecordCategory.SKIP_UNVERIFIED
                    else -> error("unknown category $c")
                },
                localId = str(expect, "localId"),
                stateKey = str(expect, "stateKey"),
                reason = str(expect, "reason"),
                paths = expect.getValue("paths").jsonArray.map { it.jsonPrimitive.content },
            )
        }.also { rows ->
            assertTrue(rows.any { it.valid } && rows.any { !it.valid })
        }
    }

    /** One row of snapshot.embeddedIdCasingCases (Android, plans/04 2.7, 3.0.2 amendment). */
    private class EmbeddedIdCasingCase(
        val id: String,
        val storedLocalId: String?,
        val embeddedId: String,
        val localId: String,
        val stateKey: String,
        val outboundEmbeddedId: String,
        val localObjects: Int,
    )

    private val embeddedIdCasingCases by lazy {
        snapshotFixture.getValue("embeddedIdCasingCases").jsonArray.map { it.jsonObject }
            .filter { row -> row.getValue("platforms").jsonArray.any { it.jsonPrimitive.content == "android" } }
            .map { row ->
                val expect = row.getValue("expect").jsonObject
                fun str(o: kotlinx.serialization.json.JsonObject, key: String) =
                    (o[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                EmbeddedIdCasingCase(
                    id = str(row, "id")!!,
                    storedLocalId = str(row, "storedLocalId"),
                    embeddedId = str(row, "embeddedId")!!,
                    localId = str(expect, "localId")!!,
                    stateKey = str(expect, "stateKey")!!,
                    outboundEmbeddedId = str(expect, "outboundEmbeddedId")!!,
                    localObjects = expect.getValue("localObjects").jsonPrimitive.content.toInt(),
                )
            }
    }

    /** The embedded feature id inside one of our own v3 puts. */
    private fun sentEmbeddedId(h: SyncHarness, frame: JSONObject): String {
        val keys = h.keys()
        val plain = SyncCrypto.open(
            keys.roomKey, SyncCrypto.decodeBase64(frame.getString("ct")),
            SyncCrypto.aadV3(frame.getString("id"), frame.getString("vs"), frame.getString("kind")),
        )!!
        val content = JSONObject(String(plain, Charsets.UTF_8)).getString("c")
        return JSONObject(content).getJSONArray("features").getJSONObject(0).getString("id")
    }

    private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A sealed, signed waypoint put whose feature id is the row's embeddedId and whose outer id
     * is the HMAC of the row's bytes, i.e. what 3.0.0's lenient hasher would have matched. */
    private fun embeddedIdRecord(
        peer: FakeV3Peer,
        keys: SyncCrypto.V3RoomKeys,
        row: EmbeddedIdCase,
        counter: Long,
        t: String? = null,
    ): JSONObject {
        val wire = SyncIdentity.wireObjectId(keys.metadataKey, hexBytes(row.outerBytesHex))
        val content = FakeV3Peer.waypointContent(waypoint("row ${row.id}", id = row.embeddedId))
        return peer.record(wire, counter, "waypoint", content, t = t)
    }

    /** Runs every row and then fails once listing all the rows that broke, not just the first. */
    private fun eachRow(rows: List<EmbeddedIdCase>, body: (EmbeddedIdCase) -> Unit) {
        assertTrue(rows.isNotEmpty())
        val failures = rows.mapNotNull { row ->
            try {
                body(row)
                null
            } catch (e: Throwable) {
                "${row.id}: $e"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertSyncStillRunning(h: SyncHarness, row: String) {
        assertEquals(row, SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(row, 1, h.transport.sockets.size)
        assertFalse(row, h.socket.terminal)
        assertNull(row, h.socket.localClose)
        assertNotEquals(row, SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(row, h.uncaught.isEmpty())
    }

    private fun assertEmbeddedIdOutcome(h: SyncHarness, row: EmbeddedIdCase, wire: String, good: Waypoint) {
        val ids = h.waypointStore.committedWaypoints.value.map { it.id }.toSet()
        val replay = h.manager.replayStateForTests!!
        if (row.valid) {
            // kept in the sender's casing with nothing local to resolve to, one object, nothing echoed back
            assertEquals(row.id, setOf(good.id, row.localId), ids)
            assertNull(row.id, h.manager.skippedCategoryForTests(wire))
            assertNotNull(row.id, replay.getStamp(wire))
            assertTrue(row.id, h.manager.surfacedIssueCodesForTests.none { it.startsWith("SKIPPED_") })
        } else {
            assertEquals(row.id, setOf(good.id), ids)
            assertEquals(row.id, row.category, h.manager.skippedCategoryForTests(wire))
            assertNull(row.id, replay.getStamp(wire))
            assertTrue(row.id, "SKIPPED_UNSUPPORTED" in h.manager.surfacedIssueCodesForTests)
        }
        assertTrue(row.id, h.socket.sentOfType("put").none { it.getString("id") == wire })
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

    // ---- poison_embedded_id_skipped (sync-android-2, plans/04 2.7) ----

    @Test
    fun everyEmbeddedIdCaseClassifiesExactlyAndTheHasherIsStrict() {
        val keys = harness().keys()
        val peer = FakeV3Peer(keys)
        val validator = SnapshotValidator(keys.roomKey, keys.roomIdRaw, keys.metadataKey, { null }, 1f)
        val hasher = WireIdHasher(keys.metadataKey)
        eachRow(embeddedIdCases) { row ->
            val rec = embeddedIdRecord(peer, keys, row, 20)
            val check = SnapshotRecordClassifier.classify(validator, rec, rec.getString("id"), emptyList())
            if (row.valid) {
                val put = (check as? V3Check.Valid)?.record as? ValidatedV3.Put
                assertNotNull("${row.id}: $check", put)
                assertEquals(row.id, row.localId, put!!.localId)
                assertEquals(row.id, listOf(row.localId), put.parsed.waypoints.map { it.id })
                // whatever the validator accepts the replay commit has to accept too, and it folds
                // to the state key every lookup goes through
                assertEquals(row.id, row.stateKey, UUID.fromString(put.localId).toString())
                assertEquals(row.id, row.stateKey, SyncIdentity.canonicalUuid(put.localId))
                assertEquals(row.id, row.outerBytesHex, SyncIdentity.bytesToHex(SyncIdentity.uuidToBytes(row.embeddedId)!!))
                assertEquals(row.id, rec.getString("id"), hasher.wireId(row.embeddedId))
            } else {
                assertTrue("${row.id}: $check", check is V3Check.Skip)
                assertEquals(row.id, row.reason, (check as V3Check.Skip).reason.wireName)
                assertEquals(row.id, row.category, check.reason.category)
                assertNull(row.id, SyncIdentity.uuidToBytes(row.embeddedId))
                assertNull(row.id, hasher.wireId(row.embeddedId))
            }
        }
        hasher.close()
        validator.close()
    }

    @Test
    fun everyEmbeddedIdCaseInASnapshotKeepsSyncRunning() {
        // one harness per row, most rows share the same outer wire id
        eachRow(embeddedIdCases.filter { "snapshot" in it.paths }) { row ->
            val h = harness()
            val peer = FakeV3Peer(h.keys())
            h.join()
            val rec = embeddedIdRecord(peer, h.keys(), row, 20)
            val good = waypoint("good")
            h.completeHandshake(listOf(rec, peer.waypointRecord(good, 21)))
            h.advance(1_000)
            assertSyncStillRunning(h, row.id)
            assertEmbeddedIdOutcome(h, row, rec.getString("id"), good)
        }
    }

    @Test
    fun everyEmbeddedIdCaseLiveKeepsSyncRunning() {
        eachRow(embeddedIdCases.filter { "live" in it.paths }) { row ->
            val h = harness()
            val peer = FakeV3Peer(h.keys())
            h.join()
            h.completeHandshake()
            h.deliver(peer.hello())
            val rec = embeddedIdRecord(peer, h.keys(), row, 20, t = "put")
            h.deliver(rec)
            // the room keeps going, a good record after it still lands
            val good = waypoint("good")
            h.deliver(peer.waypointRecord(good, 21, t = "put"))
            h.advance(1_000)
            assertSyncStillRunning(h, row.id)
            assertEmbeddedIdOutcome(h, row, rec.getString("id"), good)
        }
    }

    // ---- interop-v3-android-fold-duplicates-on-300: the received casing is kept ----

    @Test
    fun everyEmbeddedIdCasingCaseLandsOnOneObjectAndOurEditKeepsItsCasing() {
        val rows = embeddedIdCasingCases
        assertEquals(6, rows.size)
        val failures = ArrayList<String>()
        for (path in listOf("snapshot", "live")) for (row in rows) {
            try {
                val h = harness()
                val peer = FakeV3Peer(h.keys())
                row.storedLocalId?.let { assertTrue(h.waypointStore.add(waypoint("mine", id = it))) }
                h.join()
                val wire = peer.wireId(row.stateKey)
                val theirs = FakeV3Peer.waypointContent(waypoint("theirs", id = row.embeddedId))
                if (path == "snapshot") {
                    h.completeHandshake(listOf(peer.record(wire, 50, "waypoint", theirs)))
                } else {
                    h.completeHandshake()
                    // our stored copy went out on connect, let that settle first
                    h.socket.sentOfType("put").filter { it.getString("id") == wire }.forEach { h.ackPut(it) }
                    h.deliver(peer.hello())
                    h.deliver(peer.record(wire, 50, "waypoint", theirs, t = "put"))
                }
                h.advance(1_000)
                assertSyncStillRunning(h, row.id)

                val sameUuid = h.waypointStore.committedWaypoints.value.filter { it.id.lowercase() == row.stateKey }
                assertEquals(row.id, row.localObjects, sameUuid.size)
                assertEquals(row.id, row.localId, sameUuid.single().id)
                assertEquals(row.id, "theirs", sameUuid.single().name)

                // the next local edit exports the id as we keep it, under the same wire id
                val before = h.socket.sentOfType("put").size
                assertTrue(h.waypointStore.update(sameUuid.single().copy(name = "edited")))
                h.advance(1_000)
                val edit = h.socket.sentOfType("put").drop(before).single()
                assertEquals(row.id, wire, edit.getString("id"))
                assertEquals(row.id, row.outboundEmbeddedId, sentEmbeddedId(h, edit))
                assertEquals(row.id, row.localObjects, h.waypointStore.committedWaypoints.value.count { it.id.lowercase() == row.stateKey })
            } catch (e: Throwable) {
                failures += "$path ${row.id}: $e"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun aLegacyUppercaseObjectMetForTheFirstTimeGoesBackOutUppercase() {
        // the finding: shipped 3.0.0/2.x android holders upsert by exact id. if we fold their
        // 3F2A to 3f2a and edit it, every one of them grows a second copy
        val h = harness()
        val peer = FakeV3Peer(h.keys())
        val upper = UUID.randomUUID().toString().uppercase()
        h.join()
        h.completeHandshake()
        h.deliver(peer.hello())
        val wire = peer.wireId(upper)
        h.deliver(peer.record(wire, 50, "waypoint", FakeV3Peer.waypointContent(waypoint("legacy", id = upper)), t = "put"))
        h.advance(1_000)
        assertEquals(listOf(upper), h.waypointStore.committedWaypoints.value.map { it.id })

        assertTrue(h.waypointStore.update(h.waypointStore.committedWaypoints.value.single().copy(name = "moved")))
        h.advance(1_000)
        val edit = h.socket.sentOfType("put").single()
        assertEquals(wire, edit.getString("id"))
        assertEquals(upper, sentEmbeddedId(h, edit))
        assertSyncStillRunning(h, "legacy")
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
