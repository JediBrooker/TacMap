package com.tacmap.sync

import com.tacmap.models.ModelMutationOrigin
import com.tacmap.waypoints.Waypoint
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Local objects whose id is an uppercase canonical UUID. Android up to 1.2.2 kept a GeoJSON
 * import's raw feature id and iOS 1.0 exported uppercase, so devices out there have them, and
 * our own exporter puts them on the wire as the embedded id. A v3 record for one has to land
 * on that object, whatever casing the record carries (plans/04 2.7). Folding straight to
 * lowercase made a second copy for a peer's record and tombstoned our own one room wide.
 */
class SyncStoredIdCasingTest {
    private val harnesses = ArrayList<SyncHarness>()

    @Before fun setUp() = SyncHarness.installStoreKey()

    @After fun tearDown() {
        harnesses.forEach { it.close() }
        SyncHarness.restoreStoreKey()
    }

    private fun harness(separateWorkers: Boolean = false) = SyncHarness(separateWorkers = separateWorkers).also { harnesses += it }

    private fun waypoint(id: String, name: String) =
        Waypoint(id = id, name = name, latitude = -35.0, longitude = 149.0, createdAt = 1_700_000_000_000L)

    private fun SyncHarness.awaitNewSocket(maxMs: Long = 600_000L): FakeSocket {
        val count = transport.sockets.size
        var waited = 0L
        while (transport.sockets.size == count) {
            check(waited < maxMs) { "no reconnect within $maxMs ms" }
            advance(50)
            waited += 50
        }
        return socket
    }

    private fun asSnapshotRecord(frame: JSONObject): JSONObject =
        JSONObject().put("id", frame.getString("id")).put("vs", frame.getString("vs"))
            .put("by", frame.getString("by")).put("kind", frame.getString("kind"))
            .put("ct", frame.getString("ct")).put("pub", frame.getString("pub"))
            .put("sd", frame.getString("sd")).put("deleted", false)

    private fun FakeSocket.framesFor(t: String, wire: String) = sentOfType(t).filter { it.getString("id") == wire }

    private fun assertStillSyncing(h: SyncHarness) {
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(h.uncaught.isEmpty())
    }

    @Test
    fun ourOwnUppercaseObjectSurvivesAReconnectWithNoTombstone() {
        val h = harness()
        val lower = UUID.randomUUID().toString()
        val upper = lower.uppercase()
        assertTrue(h.waypointStore.add(waypoint(upper, "legacy import")))
        h.join()
        h.completeHandshake()
        h.advance(1_000)
        val wire = FakeV3Peer(h.keys()).wireId(lower)
        val put = h.socket.framesFor("put", wire).single()
        h.ackPut(put)
        h.advance(1_000)

        h.socket.serverClose(1006)
        h.awaitNewSocket()
        // the relay hands our own record back, embedded id uppercase like we sent it
        h.completeHandshake(listOf(asSnapshotRecord(put)), seq = 2)
        h.advance(5_000)

        assertEquals("our own object got tombstoned", emptyList<JSONObject>(), h.socket.framesFor("del", wire))
        assertEquals("nothing changed, nothing to send", emptyList<JSONObject>(), h.socket.framesFor("put", wire))
        assertEquals(listOf(upper), h.waypointStore.committedWaypoints.value.map { it.id })
        assertStillSyncing(h)
    }

    @Test
    fun aPeerSnapshotRecordUpdatesTheUppercaseObjectInPlace() {
        // either casing on the wire: 3.0.0 Android sends the stored id, 3.0.1 a lowercase fold
        for (upperOnWire in listOf(true, false)) {
            val h = harness()
            val lower = UUID.randomUUID().toString()
            val upper = lower.uppercase()
            assertTrue(h.waypointStore.add(waypoint(upper, "mine")))
            h.join()
            val peer = FakeV3Peer(h.keys())
            val wire = peer.wireId(lower)
            val theirs = waypoint(if (upperOnWire) upper else lower, "theirs")
            h.completeHandshake(listOf(peer.record(wire, 5, "waypoint", FakeV3Peer.waypointContent(theirs))))
            h.advance(5_000)

            val stored = h.waypointStore.committedWaypoints.value
            assertEquals("second copy for one UUID", listOf(upper), stored.map { it.id })
            assertEquals(listOf("theirs"), stored.map { it.name })
            assertEquals("re-put our copy over theirs", emptyList<JSONObject>(), h.socket.framesFor("put", wire))
            assertEquals(emptyList<JSONObject>(), h.socket.framesFor("del", wire))
            assertStillSyncing(h)
        }
    }

    @Test
    fun aPeerLivePutUpdatesTheUppercaseObjectInPlace() {
        val h = harness()
        val lower = UUID.randomUUID().toString()
        val upper = lower.uppercase()
        assertTrue(h.waypointStore.add(waypoint(upper, "mine")))
        h.join()
        h.completeHandshake()
        h.advance(1_000)
        val peer = FakeV3Peer(h.keys())
        val wire = peer.wireId(lower)
        h.ackPut(h.socket.framesFor("put", wire).single())
        h.advance(1_000)
        h.deliver(peer.hello())
        val sentBefore = h.socket.sentFrames().size

        h.deliver(peer.record(wire, 50, "waypoint", FakeV3Peer.waypointContent(waypoint(lower, "theirs")), t = "put"))
        h.advance(5_000)

        val stored = h.waypointStore.committedWaypoints.value
        assertEquals("second copy for one UUID", listOf(upper), stored.map { it.id })
        assertEquals(listOf("theirs"), stored.map { it.name })
        val after = h.socket.sentFrames().drop(sentBefore).filter { it.optString("id") == wire }
        assertEquals("echoed something for the peer's edit", emptyList<JSONObject>(), after)
        assertStillSyncing(h)
    }

    @Test
    fun anUppercaseObjectThatTurnsUpDuringValidationStillGetsThePut() {
        // the import lands after the worker checked the record against the empty stores
        val h = harness(separateWorkers = true)
        val peer = FakeV3Peer(h.keys())
        val lower = UUID.randomUUID().toString()
        val upper = lower.uppercase()
        val wire = peer.wireId(lower)
        h.join()
        h.deriveDispatcher.runCurrent(); h.runCurrent()
        h.beginSnapshot()
        h.snapshotPage(listOf(peer.record(wire, 5, "waypoint", FakeV3Peer.waypointContent(waypoint(lower, "theirs")))))
        h.validationDispatcher.runCurrent()
        assertTrue(h.waypointStore.add(waypoint(upper, "mine")))
        h.endSnapshot()
        repeat(6) {
            h.validationDispatcher.runCurrent()
            h.persistenceDispatcher.runCurrent()
            h.runCurrent()
        }

        assertEquals(1, h.socket.sentOfType("hello").size)
        assertEquals("second copy for one UUID", listOf(upper), h.waypointStore.committedWaypoints.value.map { it.id })
        assertNotEquals(SyncIssueKind.SECURITY, h.manager.currentIssueKind)
        assertTrue(h.uncaught.isEmpty())
    }

    // ---- R1-V3-STALE-BASELINE: a delete the diff never sent vs a teammate's edit in the other casing ----

    private fun content(id: String, name: String) = FakeV3Peer.waypointContent(waypoint(id, name))

    private fun SyncHarness.copiesOf(lower: String) = waypointStore.committedWaypoints.value.filter { it.id.lowercase() == lower }

    /**
     * We hold it uppercase and the edit comes lowercase, then the mirror, then matching casings
     * as the control (that one always behaved). Every failure gets reported, not just the first.
     */
    private fun eachCasing(block: (held: String, edited: String, label: String) -> Unit) {
        val failures = ArrayList<String>()
        for ((heldUpper, editedUpper) in listOf(true to false, false to true, false to false)) {
            val lower = UUID.randomUUID().toString()
            val held = if (heldUpper) lower.uppercase() else lower
            val edited = if (editedUpper) lower.uppercase() else lower
            try {
                block(held, edited, "held ${if (heldUpper) "upper" else "lower"}, edit ${if (editedUpper) "upper" else "lower"}")
            } catch (e: AssertionError) {
                failures += e.message.toString()
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun trackedAliasesOnlyCoverUuidsNothingStores() {
        val gone = UUID.randomUUID().toString()
        val storedUpper = UUID.randomUUID().toString()
        val storedLower = UUID.randomUUID().toString()
        val stored = setOf(storedUpper.uppercase(), storedLower)
        val aliases = SnapshotValidator.caseAliases(stored, stored::contains)
        val tracked = SnapshotValidator.trackedAliases(
            sequenceOf(gone.uppercase(), gone, storedUpper, storedLower.uppercase(), storedLower, "wire:abc", "not-a-uuid"),
            stored::contains,
            aliases,
        )
        // first casing wins; a UUID stored in any casing, wire: placeholders and junk stay out
        assertEquals(mapOf(gone to gone.uppercase()), tracked)
    }

    @Test
    fun anOfflineDeleteNeverTombstonesATeammatesNewerEditInTheOtherCasing() {
        // we keep X under one casing (received verbatim, 3.0.2), delete it while the socket is
        // down, and the reconnect snapshot has a teammate's newer edit in the other casing. the
        // put used to land under a second local id, so the old one looked gone to the diff and we
        // tombstoned the object room wide while still showing it ourselves
        eachCasing { held, edited, label ->
            val h = harness()
            val lower = held.lowercase()
            h.join()
            val creator = FakeV3Peer(h.keys())
            val wire = creator.wireId(lower)
            h.completeHandshake(listOf(creator.record(wire, 5, "waypoint", content(held, "first"))))
            h.advance(1_000)
            assertEquals(label, listOf(held), h.copiesOf(lower).map { it.id })

            h.socket.serverClose(1006)
            h.runCurrent()
            assertNotEquals(label, SyncManager.Status.CONNECTED, h.manager.status.value)
            assertTrue(label, h.waypointStore.remove(h.copiesOf(lower).single()))
            val socket = h.awaitNewSocket()
            val teammate = FakeV3Peer(h.keys())
            h.completeHandshake(listOf(teammate.record(wire, 50, "waypoint", content(edited, "theirs"))), seq = 2)
            h.advance(5_000)

            assertEquals("$label: tombstoned the teammate's edit", emptyList<JSONObject>(), socket.framesFor("del", wire))
            assertEquals(label, emptyList<JSONObject>(), socket.framesFor("put", wire))
            assertEquals("$label: one copy, under the id we had", listOf(held), h.copiesOf(lower).map { it.id })
            assertEquals(label, listOf("theirs"), h.copiesOf(lower).map { it.name })
            assertTrue("$label: our own tombstone", !h.manager.replayStateForTests!!.isTombstoned(wire))
            assertStillSyncing(h)
        }
    }

    @Test
    fun aDeleteTheDiffHasntSentYetNeverTombstonesALiveEditInTheOtherCasing() {
        // same thing without a reconnect: the teammate's live put lands before the debounced diff
        // picks up our delete
        eachCasing { held, edited, label ->
            val h = harness()
            val lower = held.lowercase()
            h.join()
            h.completeHandshake()
            val creator = FakeV3Peer(h.keys())
            val wire = creator.wireId(lower)
            h.deliver(creator.hello())
            h.deliver(creator.record(wire, 5, "waypoint", content(held, "first"), t = "put"))
            h.advance(1_000)
            assertEquals(label, listOf(held), h.copiesOf(lower).map { it.id })
            val teammate = FakeV3Peer(h.keys())
            h.deliver(teammate.hello())
            val sentBefore = h.socket.sentFrames().size

            assertTrue(label, h.waypointStore.remove(h.copiesOf(lower).single()))
            // the revision bump lands, the 250 ms diff debounce hasn't run
            h.runCurrent()
            h.deliver(teammate.record(wire, 50, "waypoint", content(edited, "theirs"), t = "put"))
            h.advance(5_000)

            val after = h.socket.sentFrames().drop(sentBefore).filter { it.optString("id") == wire }
            assertEquals("$label: sent something for the teammate's edit", emptyList<String>(), after.map { it.optString("t") })
            assertEquals("$label: one copy, under the id we had", listOf(held), h.copiesOf(lower).map { it.id })
            assertEquals(label, listOf("theirs"), h.copiesOf(lower).map { it.name })
            assertStillSyncing(h)
        }
    }

    @Test
    fun aTombstoneForADashlessLegacyIdNeverStopsSync() {
        // the delete side of sync-android-2: the lenient hasher gave a dashless store id a wire
        // id, a peer's tombstone resolved to it, validRemote refused it and sync stopped for good
        val hex = "3f2a1b4c0d5e4f608a7b9c8d7e6f5a4b"
        for (path in listOf("live", "snapshot")) {
            val h = harness()
            // already on disk from an old import, not a local edit (the revision journal only
            // takes UUIDs, editing one is its own problem)
            assertTrue(h.waypointStore.add(waypoint(hex, "legacy"), ModelMutationOrigin.REMOTE_SYNC))
            h.join()
            val peer = FakeV3Peer(h.keys())
            val wire = SyncIdentity.wireObjectId(h.keys().metadataKey, SyncIdentity.hexToBytes(hex))
            val tombstone = peer.record(wire, 9, "waypoint", null, deleted = true, t = if (path == "live") "del" else null)
            if (path == "live") {
                h.completeHandshake()
                h.deliver(peer.hello())
                h.deliver(tombstone)
            } else {
                h.completeHandshake(listOf(tombstone))
            }
            h.advance(1_000)

            assertEquals(path, 1, h.transport.sockets.size)
            assertTrue(path, !h.socket.terminal)
            assertStillSyncing(h)
            assertEquals(path, listOf(hex), h.waypointStore.committedWaypoints.value.map { it.id })
        }
    }
}
