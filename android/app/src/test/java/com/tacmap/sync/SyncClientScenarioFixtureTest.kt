package com.tacmap.sync

import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.export.GeoJsonExporter
import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The manager-level scenarios of testdata/sync_client_behaviour.json, driven
 * from the fixture's own steps and expectations through the S6-02 seam, plus a
 * registry that fails the build when a scenario id has no Android test.
 */
class SyncClientScenarioFixtureTest {
    private val harnesses = ArrayList<SyncHarness>()
    private val toasts = ArrayList<String>()

    @Before fun setUp() = SyncHarness.installStoreKey()

    @After fun tearDown() {
        harnesses.forEach { it.close() }
        SyncHarness.restoreStoreKey()
    }

    private fun harness(): SyncHarness = SyncHarness().also { h ->
        harnesses += h
        h.scope.launch { h.manager.remoteUpdates.collect { toasts += it } }
        h.runCurrent()
    }

    private fun fixtureFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/$name")
            if (f.exists()) return f
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name")
    }

    private val fixture: JsonObject by lazy {
        Json.parseToJsonElement(fixtureFile("sync_client_behaviour.json").readText()).jsonObject
    }

    private fun scenario(id: String): JsonObject =
        fixture.getValue("scenarios").jsonArray.map { it.jsonObject }.single { it.str("id") == id }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.steps() = getValue("steps").jsonArray.map { it.jsonObject }
    private fun JsonObject.expect(): JsonObject = getValue("expect").jsonObject
    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    private fun waypoint(name: String) =
        Waypoint(id = UUID.randomUUID().toString(), name = name, latitude = -35.0, longitude = 149.0, createdAt = 1_700_000_000_000L)

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

    private fun FakeSocket.putsFor(wireId: String) = sentOfType("put").filter { it.getString("id") == wireId }

    // ---- every scenario id has an Android test --------------------------

    /** Scenario id -> the Android test methods that assert it. */
    private val coverage: Map<String, List<String>> = mapOf(
        "backoff_full_jitter_transient" to listOf("SyncClientBehaviourFixtureTest#backoffFullJitterTransient"),
        "backoff_not_reset_at_hello_ack" to listOf(
            "SyncClientBehaviourFixtureTest#backoffNotResetAtHelloAck",
            "SyncManagerScenarioTest#backoffIsNotResetByHelloAck",
        ),
        "backoff_reset_after_stable_30s" to listOf(
            "SyncClientBehaviourFixtureTest#backoffResetAfterStable30s",
            "SyncManagerScenarioTest#firstOpAckResetsTheBackoff",
        ),
        "backoff_slow_busy_503" to listOf(
            "SyncClientBehaviourFixtureTest#backoffSlowBusy503",
            "SyncManagerScenarioTest#upgrade503UsesTheSlowBusyFloor",
        ),
        "close_and_status_table" to listOf(
            "SyncClientBehaviourFixtureTest#closeAndStatusTableScenario",
            "SyncClientBehaviourFixtureTest#closeCodeAndHttpTablesMatchEveryRow",
        ),
        "session_conflict_stops_after_three" to listOf(
            "SyncClientBehaviourFixtureTest#sessionConflictStopsAfterThree",
            "SyncManagerSp2Test#sessionReplacedNacksReconnectThenStopAfterThree",
        ),
        "stale_nack_no_reconnect" to listOf(
            "SyncClientScenarioFixtureTest#staleNackNoReconnect",
            "SyncManagerScenarioTest#staleNackDoesNotReconnectOrRaiseSecurity",
        ),
        "stale_on_own_persisted_stamp_is_confirmed" to listOf(
            "SyncClientBehaviourFixtureTest#staleOnOwnPersistedStampIsConfirmed",
            "SyncManagerScenarioTest#staleOnOurOwnPersistedStampIsTreatedAsConfirmed",
        ),
        "counter_window_pauses_mutations_once" to listOf(
            "SyncClientScenarioFixtureTest#counterWindowPausesMutationsOnce",
            "SyncManagerScenarioTest#counterWindowPausesMutationsOncePerJoin",
        ),
        "seq_regression_surfaced_once_per_join" to listOf(
            "SyncClientBehaviourFixtureTest#seqRegressionDetection",
            "SyncManagerScenarioTest#seqRegressionIsSurfacedOncePerJoin",
        ),
        "poison_record_skipped" to listOf(
            "SyncClientScenarioFixtureTest#poisonRecordSkipped",
            "SyncManagerScenarioTest#poisonRecordsAreSkippedNotFatal",
            "SyncManagerSp2Test#livePoisonRecordsAreSkippedAndSurfacedOncePerJoin",
            "SyncHostileRecordFixtureTest#everyHostileRecordInOneSnapshotIsSkippedThroughTheRealManager",
            "SyncHostileRecordFixtureTest#everyHostileRecordLiveIsSkippedWithoutAReconnect",
        ),
        "poison_embedded_id_skipped" to listOf(
            "SyncHostileRecordFixtureTest#everyEmbeddedIdCaseClassifiesExactlyAndTheHasherIsStrict",
            "SyncHostileRecordFixtureTest#everyEmbeddedIdCaseInASnapshotKeepsSyncRunning",
            "SyncHostileRecordFixtureTest#everyEmbeddedIdCaseLiveKeepsSyncRunning",
            "SyncHostileRecordFixtureTest#everyEmbeddedIdCasingCaseLandsOnOneObjectAndOurEditKeepsItsCasing",
            "SyncHostileRecordFixtureTest#aLegacyUppercaseObjectMetForTheFirstTimeGoesBackOutUppercase",
            "SyncManagerSp3Test#uppercaseEmbeddedIdKeepsItsCasingWhenTheSnapshotIsRestaged",
        ),
        "structural_snapshot_stops_after_three" to listOf(
            "SyncClientScenarioFixtureTest#structuralSnapshotStopsAfterThree",
            "SyncMaliciousFrameHandlerTest#structuralSnapshotViolationsCommitNothing",
            "SyncHostileRecordFixtureTest#snapshotBeginAfterConnectedIsFatalAndCommitsNothing",
        ),
        "layer_metadata_staged_in_item_order" to listOf(
            "SyncClientScenarioFixtureTest#layerMetadataStagedInItemOrder",
            "SyncManagerSp3Test#layerRenamedWhileTheSnapshotValidatesIsRestaged",
        ),
        "live_window_resync_cooldown" to listOf(
            "SyncClientBehaviourFixtureTest#liveWindowResyncCooldown",
            "SyncManagerScenarioTest#authenticatedLiveRecordPastTheWindowTriggersOneResync",
        ),
        "pacer_bulk_500_small" to listOf(
            "SyncClientBehaviourFixtureTest#pacerBulk500Small",
            "SyncManagerScenarioTest#bulkPublishStaysUnderClientPacing",
        ),
        "pacer_large_frames_bytes" to listOf("SyncClientBehaviourFixtureTest#pacerLargeFramesBytes"),
        "pacer_interactive_reserve" to listOf("SyncClientBehaviourFixtureTest#pacerInteractiveReserve"),
        "retransmit_starts_at_write" to listOf(
            "SyncClientBehaviourFixtureTest#retransmitStartsAtWriteAndTimeoutTable",
            "SyncManagerScenarioTest#retransmitWaitsForTheWriteToFinish",
        ),
        "no_retransmit_while_unwritten" to listOf(
            "SyncClientBehaviourFixtureTest#noRetransmitWhileUnwritten",
            "SyncManagerScenarioTest#retransmitWaitsForTheWriteToFinish",
        ),
        "receive_budget_bulk_import_no_close" to listOf(
            "SyncClientBehaviourFixtureTest#receiveBudgetBulkImportNoClose",
            "SyncManagerScenarioTest#bulkImportFromOnePeerDoesNotCloseUs",
        ),
        "background_discard_uses_background_budget" to listOf(
            "SyncClientBehaviourFixtureTest#backgroundDiscardUsesBackgroundBudget",
        ),
        "handshake_progress_watchdog" to listOf(
            "SyncClientBehaviourFixtureTest#handshakeProgressWatchdog",
            "SyncManagerScenarioTest#slowButProgressingSnapshotIsNotKilled",
        ),
        "handshake_stall_fires" to listOf(
            "SyncClientBehaviourFixtureTest#handshakeStallFires",
            "SyncManagerScenarioTest#transportByteProgressKeepsTheStallWatchdogQuiet",
        ),
        "android_connect_open_timeout" to listOf(
            "SyncClientBehaviourFixtureTest#androidConnectOpenTimeout",
            "SyncManagerScenarioTest#connectOpenTimeoutRecoversAStuckUpgrade",
        ),
        "hello_ack_timeout" to listOf(
            "SyncClientBehaviourFixtureTest#helloAckTimeout",
            "SyncManagerScenarioTest#helloAckTimeoutReconnects",
        ),
        "reachability_shortcuts_transient_only" to listOf(
            "SyncClientBehaviourFixtureTest#reachabilityShortcutsTransientOnly",
            "SyncManagerScenarioTest#networkAvailablePullsInATransientReconnect",
        ),
        "hello_epoch_vectors" to listOf(
            "SyncClientBehaviourFixtureTest#helloEpochVectors",
            "SyncClientBehaviourFixtureTest#helloEpochFloorGoesThroughTheReplayStatePrimitive",
            "SyncManagerScenarioTest#lostReplayStateStartsTheEpochAtTheTimeFloor",
            "SyncManagerScenarioTest#close4014DoublesTheRejectedEpochThenStopsAfterThree",
        ),
        "chat_fence_pruning" to listOf(
            "SyncClientBehaviourFixtureTest#chatFencePruning",
            "TacMapChatReplayBudgetTest#peerReconnectsNoLongerFillTheFenceTable",
        ),
        "chat_history_byte_prune" to listOf(
            "SyncClientBehaviourFixtureTest#chatHistoryBytePrune",
            "TacMapChatReplayBudgetTest#historyOverTwoMegabytesPrunesOldestInsteadOfBreakingChat",
        ),
        "presence_stationary_heartbeat" to listOf("SyncClientBehaviourFixtureTest#presenceStationaryHeartbeat"),
        "presence_moving_keeps_5s" to listOf("SyncClientBehaviourFixtureTest#presenceMovingKeeps5s"),
        "presence_config_change_sends" to listOf("SyncClientBehaviourFixtureTest#presenceConfigChangeSends"),
        "presence_fence_stride_and_crash_floor" to listOf("SyncClientBehaviourFixtureTest#presenceFenceStrideAndCrashFloor"),
        // iOS scene phases have no Android counterpart; see iosOnlyScenariosNameNoAndroidCase
        "ios_transient_inactive_keeps_session" to listOf("SyncClientScenarioFixtureTest#iosOnlyScenariosNameNoAndroidCase"),
        "android_pause_keeps_room" to listOf(
            "SyncClientBehaviourFixtureTest#androidPauseKeepsRoomPolicy",
            "SyncClientScenarioFixtureTest#androidPauseKeepsRoom",
        ),
        "background_entry_with_pending_delivery" to listOf(
            "SyncManagerSp3Test#backgroundEntryWithAPendingDeliveryNeverRetriesAndReconcilesOnReturn",
        ),
        "background_reconnect_uses_spare_epoch" to listOf(
            "SyncClientBehaviourFixtureTest#backgroundReconnectUsesSpareEpoch",
            "SyncBackgroundReconnectTest#dropComesBackOnASpareEpochWithoutWritingOrReadingAnything",
        ),
        "background_pauses_without_spare" to listOf(
            "SyncClientBehaviourFixtureTest#backgroundPausesWithoutSpare",
            "SyncBackgroundReconnectTest#withoutSparesTheDropPausesRightAway",
        ),
        "chat_blocked_to_background_peer" to listOf(
            "SyncClientBehaviourFixtureTest#chatBlockedToBackgroundPeer",
            "SyncManagerSp3Test#directChatToABackgroundPeerIsBlockedInsteadOfRouted",
        ),
        "object_too_large_not_reserved" to listOf(
            "SyncClientBehaviourFixtureTest#objectTooLargeNotReserved",
            "SyncManagerScenarioTest#objectTooLargeIsNeverReservedOrSent",
        ),
        "v2_casing_and_tie" to listOf(
            "SyncClientBehaviourFixtureTest#v2CasingAndTie",
            "SyncManagerScenarioTest#v2AcceptsAnIosUppercaseRecordId",
            "SyncManagerScenarioTest#v2EqualVersionGoesToTheLargerBy",
            "SyncManagerScenarioTest#v2EditAndMoveOfA2xIosObjectGoOutUnderItsUppercaseIdWithNoEcho",
            "SyncManagerScenarioTest#v2DeleteAndUndoOfA2xIosObjectKeepItsUppercaseId",
            "SyncManagerScenarioTest#v2RememberedUppercaseIdSurvivesARestart",
            "SyncManagerScenarioTest#v2RememberVectorsLearnThroughTheRealManager",
            "SyncManagerScenarioTest#v2ObjectsWeSentOrMetLowercaseStayVisibleTo2xAndroidAfterAnUppercaseIosEdit",
            "SyncManagerScenarioTest#v2OwnPinIsWrittenByTheDiffPassBeforeAnythingComesBack",
            "LegacyV2IdStoreTest#learningStopsAtTenThousandEntries",
            "LegacyV2IdStoreTest#ourOwnFirstSendPinsLowercaseAndALaterUppercaseRecordNeverFlipsItEvenAfterARestart",
            "LegacyV2IdStoreTest#aFullStorePinsNothingNewAndThoseKeysSendTheirLocalId",
        ),
    )

    @Test
    fun everyFixtureScenarioHasAnAndroidTest() {
        val ids = fixture.getValue("scenarios").jsonArray.map { it.jsonObject.str("id") }
        assertEquals("scenario ids are unique", ids.size, ids.toSet().size)
        assertEquals("registry out of date with the fixture", ids.toSet(), coverage.keys)
        for ((id, tests) in coverage) {
            assertTrue(id, tests.isNotEmpty())
            for (ref in tests) {
                val (cls, method) = ref.split('#')
                val m = runCatching { Class.forName("com.tacmap.sync.$cls").getMethod(method) }.getOrNull()
                assertNotNull("$id -> $ref doesn't exist", m)
                assertNotNull("$id -> $ref isn't a @Test", m!!.getAnnotation(Test::class.java))
            }
        }
    }

    @Test
    fun iosOnlyScenariosNameNoAndroidCase() {
        val cases = scenario("ios_transient_inactive_keeps_session").getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        // Android always locks the key on pause; android_pause_keeps_room covers it
        assertTrue(cases.all { it.str("platform") == "ios" })
    }

    // ---- stale_nack_no_reconnect -------------------------------------------

    @Test
    fun staleNackNoReconnect() {
        val h = harness()
        var put: JSONObject? = null
        var localId: String? = null
        for (step in scenario("stale_nack_no_reconnect").steps()) {
            when (step.str("event")) {
                "connected" -> {
                    h.join()
                    h.completeHandshake()
                }
                "localEdit" -> {
                    localId = h.addWaypoint("A").id
                    h.advance(300)
                    put = h.socket.sentOfType("put").single()
                }
                "inbound" -> {
                    val frame = step.str("frame")
                    val ours = VersionStamp.parse(put!!.getString("vs"))!!
                    when {
                        frame.startsWith("put") -> {
                            // same counter, bigger actor: B wins the LWW race
                            var peer = FakeV3Peer(h.keys())
                            while (VersionStamp(ours.counter, peer.actor) <= ours) peer = FakeV3Peer(h.keys())
                            h.deliver(peer.hello())
                            val theirs = h.waypointStore.committedWaypoints.value.single().copy(name = "B")
                            h.deliver(peer.waypointRecord(theirs, ours.counter, t = "put"))
                            if (step.expect().strOrNull("modelEquals") != null) {
                                assertEquals("B", h.waypointStore.committedWaypoints.value.single().name)
                            }
                        }
                        frame.startsWith("op-nack stale") -> {
                            h.nack(put, "stale", retry = false)
                            // long past every ack timeout and a few diff passes
                            h.advance(40_000)
                            val expect = step.expect()
                            assertEquals(expect.str("status"), h.manager.status.value.name)
                            assertEquals(1 + expect.getValue("newSockets").jsonPrimitive.int, h.transport.sockets.size)
                            assertFalse(h.socket.terminal)
                            if (expect["issue"] is JsonNull) assertNull(h.manager.currentIssueKind)
                            val copies = h.socket.putsFor(put.getString("id"))
                            if (expect.getValue("opResolved").jsonPrimitive.boolean) {
                                assertEquals("resolved op is never retransmitted", 1, copies.size)
                            }
                            assertEquals(expect.getValue("republishesOfX").jsonPrimitive.int, copies.size - 1)
                            assertEquals("B", h.waypointStore.committedWaypoints.value.single { it.id == localId }.name)
                        }
                        else -> error("unknown frame $frame")
                    }
                }
                else -> error(step.str("event"))
            }
        }
    }

    // ---- counter_window_pauses_mutations_once --------------------------------

    @Test
    fun counterWindowPausesMutationsOnce() {
        val h = harness()
        assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true, callsign = "Alpha")))
        h.manager.locationSampleProvider = {
            PresenceFixSample(
                provider = android.location.LocationManager.GPS_PROVIDER,
                latitude = -35.0, longitude = 149.0, accuracyMetres = 5.0,
                bearingDegrees = 0.0, speedMps = 0.0,
                elapsedRealtimeNanos = h.clock.elapsedRealtimeNanos(),
            )
        }
        h.join()
        h.completeHandshake()
        val pausedText = "reset by the relay"
        for (step in scenario("counter_window_pauses_mutations_once").steps()) {
            val expect = step.expect()
            when (step.str("event")) {
                "inbound" -> {
                    assertTrue(step.str("frame").startsWith("op-nack counter-window"))
                    h.addWaypoint("one")
                    h.advance(300)
                    h.nack(h.socket.sentOfType("put").single(), "counter-window", retry = false)
                    h.advance(5_000)
                    assertEquals(!expect.getValue("reconnect").jsonPrimitive.boolean, !h.socket.terminal)
                    assertEquals(1, h.transport.sockets.size)
                    assertEquals(expect.getValue("pauseMutationsForJoin").jsonPrimitive.boolean, h.manager.mutationsPausedForTests)
                    val issue = expect.str("issue")
                    assertTrue(issue in h.manager.surfacedIssueCodesForTests)
                    assertEquals(SyncIssueCode.valueOf(issue).kind, h.manager.currentIssueKind)
                    assertEquals(expect.getValue("surfacedCount").jsonPrimitive.int, toasts.count { it.contains(pausedText) })
                }
                "localEdit" -> {
                    val before = h.socket.sentOfType("put").size
                    h.addWaypoint("two")
                    h.advance(500)
                    assertEquals(expect.getValue("putsSent").jsonPrimitive.int, h.socket.sentOfType("put").size - before)
                }
                "presenceDue" -> {
                    // presence keeps going while writes are paused
                    val before = h.socket.sentOfType("loc").size
                    h.advance(25_000)
                    assertTrue(h.socket.sentOfType("loc").size - before >= expect.getValue("locSent").jsonPrimitive.int)
                    assertFalse(h.socket.terminal)
                }
                "reconnect" -> {
                    h.socket.serverClose(1006)
                    h.awaitNewSocket()
                    h.completeHandshake()
                    h.addWaypoint("three")
                    h.advance(1_000)
                    assertEquals(expect.getValue("pauseMutationsForJoin").jsonPrimitive.boolean, h.manager.mutationsPausedForTests)
                    assertTrue(h.socket.sentOfType("put").isEmpty())
                    assertTrue(h.socket.sentOfType("del").isEmpty())
                    assertEquals(expect.getValue("surfacedCount").jsonPrimitive.int, toasts.count { it.contains(pausedText) })
                }
                else -> error(step.str("event"))
            }
        }
    }

    // ---- poison_record_skipped -------------------------------------------------

    @Test
    fun poisonRecordSkipped() {
        val h = harness()
        val s = scenario("poison_record_skipped")
        val steps = s.steps()
        val peer = FakeV3Peer(h.keys())
        val objects = LinkedHashMap<String, Waypoint>()
        val records = ArrayList<JSONObject>()
        val wireName = Regex("\\bW\\d+\\b")
        for (label in steps.first().getValue("items").strings()) {
            val name = checkNotNull(wireName.find(label)?.value) { label }
            val wp = waypoint(name).also { objects[name] = it }
            records += when {
                label.contains("ct replaced") -> peer.record(
                    peer.wireId(wp.id), 4, "waypoint", FakeV3Peer.waypointContent(wp),
                    ctOverride = SyncCrypto.encodeBase64(ByteArray(64) { (it * 37 + 11).toByte() }),
                )
                label.contains("kind 'route'") -> peer.record(
                    peer.wireId(wp.id), 5, "route", """{"type":"FeatureCollection","features":[]}""",
                )
                label.startsWith("valid waypoint") -> peer.waypointRecord(wp, 3)
                else -> error("fixture item this test doesn't know how to build: $label")
            }
        }
        fun wire(name: String) = peer.wireId(objects.getValue(name).id)
        // the scenario later asserts an untouched local copy exists, so it exists from the start
        steps.filter { it.str("event") == "localObjectExistsFor" }.forEach { step ->
            val name = step.str("wireId")
            assertTrue(h.waypointStore.add(objects.getValue(name).copy(name = "old local copy")))
        }
        h.runCurrent()

        for (step in steps) {
            val expect = step.expect()
            when (step.str("event")) {
                "snapshot" -> {
                    h.join()
                    h.completeHandshake(records)
                    assertEquals("CONNECTED_after_hello_ack", expect.str("status"))
                    assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
                    val replay = h.manager.replayStateForTests!!
                    val names = h.waypointStore.committedWaypoints.value.associate { it.id to it.name }
                    for (applied in expect.getValue("applied").strings()) {
                        assertEquals(applied, names[objects.getValue(applied).id])
                    }
                    for (name in expect.getValue("skippedUnverified").strings()) {
                        assertEquals(name, SnapshotRecordCategory.SKIP_UNVERIFIED, h.manager.skippedCategoryForTests(wire(name)))
                    }
                    for (name in expect.getValue("skippedUnsupported").strings()) {
                        assertEquals(name, SnapshotRecordCategory.SKIP_UNSUPPORTED, h.manager.skippedCategoryForTests(wire(name)))
                    }
                    val committed = expect.getValue("replayCommitted").strings().toSet()
                    for (name in objects.keys) {
                        assertEquals(name, name in committed, replay.getStamp(wire(name)) != null)
                    }
                    assertEquals(expect.getValue("issues").strings().toSet(),
                        h.manager.surfacedIssueCodesForTests.filter { it.startsWith("SKIPPED_") }.toSet())
                    assertEquals(expect.getValue("verifiedCleanSnapshot").jsonPrimitive.boolean,
                        h.manager.lastSnapshotVerifiedCleanForTests)
                    h.advance(1_000)
                }
                "reconnect_same_snapshot" -> {
                    val notices = toasts.count { it.contains("Some synced objects") }
                    h.socket.serverClose(1006)
                    val socket = h.awaitNewSocket()
                    h.completeHandshake(records, socket = socket)
                    h.advance(5_000)
                    assertEquals(expect.getValue("newIssueToasts").jsonPrimitive.int,
                        toasts.count { it.contains("Some synced objects") } - notices)
                    assertEquals(2 + expect.getValue("reconnectsCausedBySkip").jsonPrimitive.int, h.transport.sockets.size)
                    assertFalse(socket.terminal)
                }
                "localObjectExistsFor" -> {
                    assertFalse(step.getValue("localGenerationAdvanced").jsonPrimitive.boolean)
                    h.advance(1_000)
                    val published = h.socket.putsFor(wire(step.str("wireId"))).isNotEmpty()
                    assertEquals(expect.getValue("published").jsonPrimitive.boolean, published)
                }
                "userEdits" -> {
                    val id = objects.getValue(step.str("wireId")).id
                    val current = h.waypointStore.committedWaypoints.value.first { it.id == id }
                    assertTrue(h.waypointStore.update(current.copy(name = "edited")))
                    h.advance(1_000)
                    val puts = h.socket.putsFor(wire(step.str("wireId")))
                    assertEquals(expect.getValue("published").jsonPrimitive.boolean, puts.isNotEmpty())
                    assertEquals("one publish per edit", 1, puts.size)
                }
                else -> error(step.str("event"))
            }
        }
    }

    // ---- structural_snapshot_stops_after_three ---------------------------------

    @Test
    fun structuralSnapshotStopsAfterThree() {
        val h = harness()
        h.randomValue = 1.0
        val peer = FakeV3Peer(h.keys())
        val rec = peer.waypointRecord(waypoint("dup"), 3)
        h.join()
        val backoffClass = SyncBackoffClass.TRANSIENT
        for ((attempt, step) in scenario("structural_snapshot_stops_after_three").steps().withIndex()) {
            assertTrue(step.getValue("duplicateWireId").jsonPrimitive.boolean)
            val expect = step.expect()
            val socket = h.socket
            h.beginSnapshot(socket = socket)
            h.snapshotPage(listOf(rec, rec), socket = socket)
            h.endSnapshot(socket = socket)
            expect["committed"]?.jsonPrimitive?.booleanOrNull?.let { committed ->
                assertEquals(committed, h.manager.replayStateForTests!!.lastSnapshotSeq >= 0)
                assertTrue(h.waypointStore.committedWaypoints.value.isEmpty())
            }
            assertTrue(socket.terminal)
            assertTrue("no hello on a rejected snapshot", socket.sentOfType("hello").isEmpty())
            val closedAt = h.now
            when (expect.str("action")) {
                "reconnect" -> {
                    expect.strOrNull("backoffClass")?.let { assertEquals(backoffClass.wireName, it) }
                    val next = h.awaitNewSocket()
                    val delay = next.createdAtMs - closedAt
                    val ceiling = backoffClass.floorMs + minOf(backoffClass.capMs - backoffClass.floorMs,
                        backoffClass.spreadMs shl attempt)
                    assertTrue("delay $delay", delay in backoffClass.floorMs..ceiling + 50)
                    assertFalse(h.manager.pausedActionRequired.value)
                }
                "stop" -> {
                    h.advance(30 * 60_000L)
                    assertEquals("stopped, no more sockets", attempt + 1, h.transport.sockets.size)
                    assertTrue(h.manager.pausedActionRequired.value)
                    val issue = SyncIssueCode.valueOf(expect.str("issue"))
                    assertEquals(issue.kind, h.manager.currentIssueKind)
                    assertTrue(toasts.any { it.contains("malformed room snapshot") })
                }
                else -> error(expect.str("action"))
            }
        }
    }

    // ---- layer_metadata_staged_in_item_order -------------------------------------

    @Test
    fun layerMetadataStagedInItemOrder() {
        val s = scenario("layer_metadata_staged_in_item_order")
        val given = s.getValue("given").jsonObject
        val expect = s.expect()
        assertTrue(given.getValue("localLayers").jsonArray.isEmpty())
        val h = harness()
        val keys = h.keys()
        val peer = FakeV3Peer(keys)
        val items = given.getValue("items").jsonArray.map { it.jsonObject }
        val features = LinkedHashMap<String, DrawingFeature>()
        val records = items.mapIndexed { i, item ->
            val layer = DrawingLayer(id = item.str("layerId"), name = item.str("layerName"), createdAt = 1)
            val feature = DrawingFeature(
                name = item.str("wire"), geometry = DrawingGeometry.LINE,
                points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)), layerId = layer.id,
            )
            features[item.str("wire")] = feature
            val content = GeoJsonExporter.export(emptyList(), listOf(feature), listOf(layer), 1f)
            peer.record(peer.wireId(feature.id), 3L + i, "drawing", content)
        }

        // the validator, in item order with staging, exactly like the snapshot worker
        val validator = SnapshotValidator(keys.roomKey, keys.roomIdRaw, keys.metadataKey, { null }, 1f)
        val staged = ArrayList<DrawingLayer>()
        for ((i, item) in items.withIndex()) {
            val check = SnapshotRecordClassifier.classify(validator, records[i], records[i].getString("id"), staged)
            val put = (check as V3Check.Valid).record as ValidatedV3.Put
            val want = expect.getValue(item.str("wire")).jsonObject
            assertEquals(item.str("wire"), want.getValue("newLayers").strings(),
                put.parsed.newLayers.map { "${it.id}/${it.name}" })
            want.strOrNull("exportsWithLayerName")?.let { name ->
                val layers = staged + put.parsed.newLayers
                val exported = GeoJsonExporter.export(emptyList(), put.parsed.drawings, layers, 1f)
                assertTrue(exported, exported.contains("\"$name\""))
                assertFalse(exported, exported.contains("\"${item.str("layerName")}\"") && name != item.str("layerName"))
            }
            SnapshotValidator.stage(staged, check)
        }
        validator.close()

        // and the real manager: one commit, every expected hash holds, no failure
        h.join()
        h.completeHandshake(records)
        assertEquals("CONNECTED_after_hello_ack", expect.str("status"))
        assertEquals(SyncManager.Status.CONNECTED, h.manager.status.value)
        assertEquals(expect.getValue("persistenceFailure").jsonPrimitive.boolean,
            h.manager.currentIssueKind == SyncIssueKind.SECURITY)
        val doc = h.drawingStore.committedDocument.value
        assertEquals(features.values.map { it.id }.toSet(), doc.features.map { it.id }.toSet())
        assertEquals("Recon", doc.layers.single { it.id == items.first().str("layerId") }.name)
    }

    // ---- android_pause_keeps_room -----------------------------------------------

    @Test
    fun androidPauseKeepsRoom() {
        for (case in scenario("android_pause_keeps_room").getValue("cases").jsonArray.map { it.jsonObject }) {
            assertEquals("android", case.str("platform"))
            val h = harness()
            val code = case.str("room") + SyncHarness.CODE.substringAfter(':')
            if (case.getValue("shareLocation").jsonPrimitive.boolean) {
                assertTrue(h.manager.updatePresenceConfig(PresenceConfig(shareLocation = true)))
            }
            h.join(code)
            h.socket.open()
            h.runCurrent()
            val manager = h.manager
            val before = h.transport.sockets.size
            for (event in case.getValue("events").strings()) when (event) {
                "onPause" -> assertTrue(manager.suspendUntilForegroundStores())
                "onResume" -> manager.prepareForForegroundUnlock()
                "storesAttached" -> assertTrue(manager.attachForegroundStores(h.waypointStore, h.drawingStore) { null })
                else -> error(event)
            }
            h.runCurrent()
            val expect = case.expect()
            assertEquals(expect.getValue("roomRetained").jsonPrimitive.boolean, manager.room.value == code)
            assertEquals(expect.getValue("sameManager").jsonPrimitive.boolean, !manager.isDisposed)
            assertEquals(expect.getValue("connectAttempts").jsonPrimitive.int, h.transport.sockets.size - before)
            expect["userInputNeeded"]?.jsonPrimitive?.booleanOrNull?.let {
                assertEquals(it, manager.pausedActionRequired.value)
            }
        }
    }
}
