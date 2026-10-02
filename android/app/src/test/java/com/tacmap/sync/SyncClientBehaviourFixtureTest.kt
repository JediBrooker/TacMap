package com.tacmap.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * Loads testdata/sync_client_behaviour.json (the twin of
 * plans/04-sync-client-contract.md) and runs every policy-unit scenario the
 * Android client owns. iOS runs the same file.
 */
class SyncClientBehaviourFixtureTest {

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

    // read fresh on purpose, the relay agent may still be tuning these
    private fun relayLimits(): JsonObject =
        Json.parseToJsonElement(fixtureFile("sync_protocol_v3.json").readText()).jsonObject["relayLimits"]!!.jsonObject

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.arr(key: String) = getValue(key).jsonArray
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.strOrNull(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.dbl(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    private fun scenario(id: String): JsonObject =
        fixture.arr("scenarios").map { it.jsonObject }.single { it.str("id") == id }

    @Test
    fun relayLimitDependenciesMatchTheRelayFixture() {
        val deps = fixture.obj("relayLimitsDependencies")
        val relay = relayLimits()
        val relayValues = relay.obj("values")
        for ((key, value) in deps.obj("values")) {
            assertEquals("relayLimits.values.$key", relayValues.getValue(key), value)
        }
        assertEquals(relay.obj("clientPacing").filterKeys { !it.startsWith("_") },
            deps.obj("clientPacing").filterKeys { !it.startsWith("_") })
        assertEquals(relay.obj("closeCodes").keys.sorted(), deps.getValue("closeCodes").strings().sorted())
        assertEquals(relay.obj("upgradeStatus").keys.sorted(), deps.getValue("upgradeStatus").strings().sorted())
        val nackCodes = relay.getValue("opNackCodes").let { el ->
            (el as? JsonObject)?.keys?.toList() ?: el.strings()
        }
        assertEquals(nackCodes.sorted(), deps.getValue("opNackCodes").strings().sorted())
    }

    @Test
    fun everyContractInvariantHolds() {
        val values = fixture.obj("relayLimitsDependencies").obj("values")
        val pacing = fixture.obj("relayLimitsDependencies").obj("clientPacing")
        val names = fixture.arr("invariants").map { it.jsonObject.str("name") }.toSet()
        val checked = mutableSetOf<String>()
        fun check(name: String, ok: Boolean) {
            assertTrue("invariant $name", ok)
            checked += name
        }
        check("frameBucketWithinClientPacing",
            SyncOutboundPacer.FRAME_CAPACITY + SyncOutboundPacer.FRAME_REFILL_PER_WINDOW <= pacing.int("maxFramesPerWindow"))
        check("byteBucketWithinClientPacing",
            SyncOutboundPacer.BYTE_CAPACITY + SyncOutboundPacer.BYTE_REFILL_PER_WINDOW <= pacing.long("maxBytesPerWindow"))
        check("byteBucketFitsOneFrame", SyncOutboundPacer.BYTE_CAPACITY >= values.long("MAX_FRAME_BYTES"))
        check("bunchingBelowRelayWindow",
            pacing.int("maxFramesPerWindow") + SyncOutboundPacer.MAX_IN_FLIGHT_MUTATIONS + 4 < values.int("RATE_MAX_MSGS"))
        check("inFlightBytesWellBelowRoomBacklog",
            SyncOutboundPacer.MAX_IN_FLIGHT_BYTES * 4 <= values.long("ROOM_PENDING_MAX_BYTES"))
        check("roomReceiveAbovePerSenderAllowance",
            SyncReceiveBudget.ROOM_BASE_FRAMES >= 3 * values.int("RATE_MAX_MSGS") &&
                SyncReceiveBudget.ROOM_BASE_BYTES >= 3 * values.long("RATE_MAX_BYTES"))
        check("selfResponseCoversPacing", SyncReceiveBudget.SELF_MAX_FRAMES >= 2 * pacing.int("maxFramesPerWindow"))
        check("everyRelayCloseCodeHasARow",
            fixture.obj("relayLimitsDependencies").getValue("closeCodes").strings().all { it in fixture.obj("closeCodeActions") })
        check("everyUpgradeStatusHasARow",
            fixture.obj("relayLimitsDependencies").getValue("upgradeStatus").strings().all { it in fixture.obj("httpStatusActions") })
        check("everyNackCodeHasARow",
            fixture.obj("relayLimitsDependencies").getValue("opNackCodes").strings().all { it in fixture.obj("opNackActions") })
        // presence cadence is SP3 on Android, but the numbers still have to fit
        check("presenceHeartbeatUnderRetention",
            2 * fixture.obj("presence").obj("foreground").long("stationaryHeartbeatMs") < 45_000)
        assertEquals("every fixture invariant has an Android check", names, checked)
    }

    @Test
    fun constantsMatchTheFixture() {
        val pacer = fixture.obj("pacer")
        assertEquals(pacer.long("windowMs"), SyncOutboundPacer.WINDOW_MS)
        assertEquals(pacer.obj("frameBucket").int("capacity"), SyncOutboundPacer.FRAME_CAPACITY)
        assertEquals(pacer.obj("frameBucket").int("refillPerWindow"), SyncOutboundPacer.FRAME_REFILL_PER_WINDOW)
        assertEquals(pacer.obj("byteBucket").long("capacity"), SyncOutboundPacer.BYTE_CAPACITY)
        assertEquals(pacer.obj("byteBucket").long("refillPerWindow"), SyncOutboundPacer.BYTE_REFILL_PER_WINDOW)
        assertEquals(pacer.obj("interactiveReserve").int("frames"), SyncOutboundPacer.RESERVE_FRAMES)
        assertEquals(pacer.obj("interactiveReserve").long("bytes"), SyncOutboundPacer.RESERVE_BYTES)
        assertEquals(pacer.obj("inFlight").int("maxMutations"), SyncOutboundPacer.MAX_IN_FLIGHT_MUTATIONS)
        assertEquals(pacer.obj("inFlight").long("maxMutationBytes"), SyncOutboundPacer.MAX_IN_FLIGHT_BYTES)
        assertEquals(pacer.obj("after4008").int("startFrameTokens"), SyncOutboundPacer.AFTER_4008_FRAME_TOKENS)
        assertEquals(pacer.obj("after4008").long("startByteTokens"), SyncOutboundPacer.AFTER_4008_BYTE_TOKENS)
        assertEquals(pacer.getValue("priorities").strings(),
            SyncOutboundClass.entries.map { it.name.lowercase() })
        assertEquals(pacer.getValue("reserveAppliesTo").strings().toSet(),
            SyncOutboundClass.entries.filterNot { it.usesInteractiveReserve }.map { it.name.lowercase() }.toSet())

        val retransmit = fixture.obj("retransmit")
        assertEquals(retransmit.long("ackTimeoutBaseMs"), SyncAckTimer.BASE_MS)
        assertEquals(retransmit.long("ackTimeoutPerAheadMs"), SyncAckTimer.PER_AHEAD_MS)
        assertEquals(retransmit.long("ackTimeoutCapMs"), SyncAckTimer.CAP_MS)
        assertEquals(retransmit.int("maxAttempts"), SyncAckTimer.MAX_ATTEMPTS)
        assertEquals(retransmit.long("androidWritePollMs"), SyncAckTimer.ANDROID_WRITE_POLL_MS)

        val outbound = fixture.obj("outboundSize")
        assertEquals(outbound.long("objectCtMaxChars"), OutboundSizeCheck.CT_MAX_CHARS)
        assertEquals(outbound.long("maxFrameBytes"), OutboundSizeCheck.MAX_FRAME_BYTES)

        val receive = fixture.obj("receiveBudget")
        assertEquals(receive.long("windowMs"), SyncReceiveBudget.WINDOW_MS)
        receive.obj("androidTransportQueue").let {
            assertEquals(it.long("maxBytes"), SyncWebSocketTransport.MAX_OUTBOUND_QUEUE_BYTES)
            assertEquals(it.int("maxFrames"), SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW)
        }
        receive.obj("room").let {
            assertEquals(it.int("baseFrames"), SyncReceiveBudget.ROOM_BASE_FRAMES)
            assertEquals(it.int("perSessionFrames"), SyncReceiveBudget.ROOM_PER_SESSION_FRAMES)
            assertEquals(it.int("maxFrames"), SyncReceiveBudget.ROOM_MAX_FRAMES)
            assertEquals(it.long("baseBytes"), SyncReceiveBudget.ROOM_BASE_BYTES)
            assertEquals(it.long("perSessionBytes"), SyncReceiveBudget.ROOM_PER_SESSION_BYTES)
            assertEquals(it.long("maxBytes"), SyncReceiveBudget.ROOM_MAX_BYTES)
        }
        receive.obj("selfResponse").let {
            assertEquals(it.getValue("types").strings().toSet(), SyncReceiveBudget.SELF_RESPONSE_TYPES)
            assertEquals(it.int("maxFrames"), SyncReceiveBudget.SELF_MAX_FRAMES)
            assertEquals(it.long("maxBytes"), SyncReceiveBudget.SELF_MAX_BYTES)
        }
        receive.obj("initial").let {
            assertEquals(it.int("maxFrames"), SyncReceiveBudget.INITIAL_MAX_FRAMES)
            assertEquals(it.long("maxBytes"), SyncReceiveBudget.INITIAL_MAX_BYTES)
        }
        receive.obj("background").let {
            assertEquals(it.int("maxFrames"), SyncReceiveBudget.BACKGROUND_MAX_FRAMES)
            assertEquals(it.long("maxBytes"), SyncReceiveBudget.BACKGROUND_MAX_BYTES)
        }
        assertEquals(receive.obj("onExceed").int("localClose"),
            SyncCloseClassifier.classifyLocal(SyncLocalClose.RECEIVE_BUDGET_EXCEEDED).localCloseCode)

        val watchdogs = fixture.obj("watchdogs")
        assertEquals(watchdogs.long("connectOpenTimeoutMs"), SyncHandshakeWatchdog.CONNECT_OPEN_TIMEOUT_MS)
        assertEquals(watchdogs.long("handshakeStallTimeoutMs"), SyncHandshakeWatchdog.STALL_TIMEOUT_MS)
        assertEquals(watchdogs.long("helloAckTimeoutMs"), SyncHandshakeWatchdog.HELLO_ACK_TIMEOUT_MS)
        assertEquals(watchdogs.long("handshakeAbsoluteMaxMs"), SyncHandshakeWatchdog.ABSOLUTE_MAX_MS)
        assertEquals(watchdogs.long("androidTcpConnectTimeoutMs"), SyncWebSocketTransport.CONNECT_TIMEOUT_MS.toLong())
        assertEquals(watchdogs.long("inboundQueueSpaceTimeoutMs"), SyncWebSocketTransport.CALLBACK_DRAIN_TIMEOUT_MS)

        val heartbeat = fixture.obj("heartbeat")
        assertEquals(heartbeat.obj("foreground").long("pingIntervalMs"), SyncWebSocketTransport.KEEPALIVE_SECONDS * 1_000L)
        // Java-WebSocket declares the socket dead after 1.5 x the ping interval
        assertEquals(heartbeat.obj("foreground").long("deadAfterMsWithoutPongOrFrame"),
            SyncWebSocketTransport.KEEPALIVE_SECONDS * 1_500L)
        assertEquals(heartbeat.long("pathChangeProbeTimeoutMs"), SyncManager.PATH_CHANGE_PROBE_TIMEOUT_MS)

        val backoff = fixture.obj("backoff")
        assertEquals(backoff.int("attemptCap"), SyncBackoffPolicy.ATTEMPT_CAP)
        assertEquals(backoff.long("stableSessionMs"), SyncBackoffPolicy.STABLE_SESSION_MS)
        for ((name, row) in backoff.obj("classes")) {
            val cls = checkNotNull(SyncBackoffClass.fromWire(name)) { name }
            assertEquals(row.jsonObject.long("floorMs"), cls.floorMs)
            assertEquals(row.jsonObject.long("spreadMs"), cls.spreadMs)
            assertEquals(row.jsonObject.long("capMs"), cls.capMs)
        }
        assertEquals(backoff.obj("classes").keys, SyncBackoffClass.entries.map { it.wireName }.toSet())
        assertEquals(fixture.obj("reachability").long("minSpacingFromLastAttemptMs"),
            SyncBackoffPolicy.MIN_SPACING_FROM_LAST_ATTEMPT_MS)

        val stops = fixture.obj("stopThresholds")
        assertEquals(stops.int("structuralSnapshotFailuresBeforeStop"), SyncFailureCounters.STRUCTURAL_FAILURES_BEFORE_STOP)
        assertEquals(stops.int("sessionConflictsBeforeStop"), SyncFailureCounters.SESSION_CONFLICTS_BEFORE_STOP)
        assertEquals(stops.long("sessionConflictWindowMs"), SyncFailureCounters.SESSION_CONFLICT_WINDOW_MS)
        assertEquals(stops.int("staleEpochEscalationsBeforeStop"), SyncFailureCounters.STALE_EPOCH_ESCALATIONS_BEFORE_STOP)
        assertEquals(stops.int("busyFailuresBeforeSurface"), SyncCloseClassifier.BUSY_FAILURES_BEFORE_SURFACE)

        val live = fixture.obj("liveWindowResync")
        assertEquals(live.long("cooldownMs"), LiveWindowResyncPolicy.COOLDOWN_MS)
        assertEquals(live.int("maxPerHour"), LiveWindowResyncPolicy.MAX_PER_HOUR)

        val epoch = fixture.obj("helloEpoch")
        assertEquals(epoch.int("backgroundSpareBlock"), HelloEpochPolicy.BACKGROUND_SPARE_BLOCK)
        assertEquals(epoch.int("maxEscalationsPerJoin"), SyncFailureCounters.STALE_EPOCH_ESCALATIONS_BEFORE_STOP)
        assertEquals(epoch.str("max"), HelloEpochPolicy.hex(HelloEpochPolicy.MAX))

        val chat = fixture.obj("chat")
        chat.obj("replayFences").let {
            assertEquals(it.int("maxFences"), ChatReplayPruner.MAX_FENCES)
            assertEquals(it.int("fingerprintsKeptOnWrite"), ChatReplayPruner.FINGERPRINTS_KEPT_ON_WRITE)
            assertEquals(it.int("fingerprintsAcceptedOnLoad"), ChatReplayPruner.FINGERPRINTS_ACCEPTED_ON_LOAD)
            assertEquals(it.int("fingerprintsAcceptedOnLoad"), TacMapChatReplayRecord.MAX_RECENT_FINGERPRINTS)
        }
        chat.obj("history").let {
            assertEquals(it.int("maxMessages"), ChatHistoryBudget.MAX_MESSAGES)
            assertEquals(it.int("maxMessages"), TacMapChatHistoryStore.MAX_MESSAGES_PER_ROOM)
            assertEquals(it.long("maxEncodedBytes"), ChatHistoryBudget.MAX_ENCODED_BYTES)
            assertEquals(it.long("maxEncodedBytes"), TacMapChatHistoryStore.MAX_ENCODED_HISTORY_BYTES.toLong())
            assertEquals(it.long("pruneTargetBytes"), ChatHistoryBudget.PRUNE_TARGET_BYTES)
        }
    }

    @Test
    fun issueTableMatchesKindsAndStrings() {
        val issues = fixture.obj("issues")
        for (code in SyncIssueCode.entries) {
            val row = issues[code.name]?.jsonObject ?: error("fixture has no issue ${code.name}")
            val kind = row.str("kind")
            when (kind) {
                "SECURITY" -> assertEquals(code.name, SyncIssueKind.SECURITY, code.kind)
                "CONNECTION" -> assertEquals(code.name, SyncIssueKind.CONNECTION, code.kind)
                // chat availability is shown on the connection banner on Android
                "chat availability" -> assertEquals(code.name, SyncIssueKind.CONNECTION, code.kind)
                else -> error("unexpected kind $kind for ${code.name}")
            }
        }
        // the four the contract says may never be SECURITY
        for (name in listOf("ROOM_QUOTA_NACK", "RELAY_INVALID_NACK", "UNCONFIRMED_RECONNECT", "ROOM_RESET_CHANGES_PAUSED")) {
            assertEquals(SyncIssueKind.CONNECTION, SyncIssueCode.valueOf(name).kind)
        }
    }

    @Test
    fun closeCodeAndHttpTablesMatchEveryRow() {
        val closes = fixture.obj("closeCodeActions")
        for ((key, rowElement) in closes) {
            val row = rowElement.jsonObject
            val code = when (key) {
                "other_1xxx" -> 1099
                "other_4xxx" -> 4099
                else -> key.toInt()
            }
            val decision = SyncCloseClassifier.classifyClose(code, afterOwnLeave = false)
            val expectedAction = when (row.str("action")) {
                "none_if_we_sent_leave_else_reconnect" -> SyncCloseAction.RECONNECT
                "reconnect" -> SyncCloseAction.RECONNECT
                "stop" -> SyncCloseAction.STOP
                "escalate_epoch_then_reconnect" -> SyncCloseAction.ESCALATE_EPOCH_THEN_RECONNECT
                else -> error(row.str("action"))
            }
            assertEquals("close $key", expectedAction, decision.action)
            row.strOrNull("backoffClass")?.let { assertEquals("close $key", it, decision.backoffClass?.wireName) }
            (row.strOrNull("issue") ?: row.strOrNull("stopIssue"))?.let {
                assertEquals("close $key", it, decision.issue?.name)
            }
            row["retryOnForeground"]?.jsonPrimitive?.booleanOrNull?.let {
                assertEquals("close $key", it, decision.retryOnForeground)
            }
            row["surfaceAfterConsecutive"]?.jsonPrimitive?.intOrNull?.let {
                assertEquals("close $key", it, decision.surfaceAfterConsecutive)
            }
            if (row.strOrNull("countsAs") == "sessionConflict") assertTrue("close $key", decision.countsAsSessionConflict)
            if (row.strOrNull("pacer") == "after4008") assertTrue("close $key", decision.pacerAfter4008)
        }
        assertEquals(SyncCloseAction.NONE, SyncCloseClassifier.classifyClose(1000, afterOwnLeave = true).action)

        val http = fixture.obj("httpStatusActions")
        for ((key, rowElement) in http) {
            if (key == "statusSource") continue
            val row = rowElement.jsonObject
            val status = when (key) {
                "other_5xx" -> 502
                "other_4xx" -> 400
                "none" -> null
                else -> key.toInt()
            }
            val decision = SyncCloseClassifier.classifyHttp(status)
            val expected = if (row.str("action") == "stop") SyncCloseAction.STOP else SyncCloseAction.RECONNECT
            assertEquals("http $key", expected, decision.action)
            row.strOrNull("backoffClass")?.let { assertEquals("http $key", it, decision.backoffClass?.wireName) }
            row.strOrNull("issue")?.let { assertEquals("http $key", it, decision.issue?.name) }
            row["surfaceAfterConsecutive"]?.jsonPrimitive?.intOrNull?.let {
                assertEquals("http $key", it, decision.surfaceAfterConsecutive)
            }
            row["retryOnForeground"]?.jsonPrimitive?.booleanOrNull?.let {
                assertEquals("http $key", it, decision.retryOnForeground)
            }
        }
        assertEquals(503, SyncCloseClassifier.parseHandshakeStatus(
            "Invalid status code received: 503 Status line: HTTP/1.1 503 Room full"))
        assertNull(SyncCloseClassifier.parseHandshakeStatus("Connection refused"))

        val locals = fixture.obj("localCloseActions")
        for (reason in SyncLocalClose.entries) {
            if (reason == SyncLocalClose.PERSISTENCE_FAILURE) continue // ours, not in the contract table
            val row = locals[reason.wireName]?.jsonObject ?: error("fixture lacks local close ${reason.wireName}")
            val decision = SyncCloseClassifier.classifyLocal(reason)
            val expected = when (row.str("action")) {
                "none" -> SyncCloseAction.NONE
                "reconnect" -> SyncCloseAction.RECONNECT
                "reconnect_now" -> SyncCloseAction.RECONNECT_NOW
                else -> error(row.str("action"))
            }
            assertEquals(reason.wireName, expected, decision.action)
            row.strOrNull("backoffClass")?.let { assertEquals(reason.wireName, it, decision.backoffClass?.wireName) }
            row["closeCode"]?.jsonPrimitive?.intOrNull?.let { assertEquals(reason.wireName, it, decision.localCloseCode) }
            (row.strOrNull("issue") ?: row.strOrNull("stopIssue"))?.let {
                if (row.strOrNull("issue") != null) assertEquals(reason.wireName, it, decision.issue?.name)
            }
            if (row.strOrNull("countsAs") == "sessionConflict") assertTrue(decision.countsAsSessionConflict)
        }
        assertEquals(locals.keys, SyncLocalClose.entries.filter { it != SyncLocalClose.PERSISTENCE_FAILURE }
            .map { it.wireName }.toSet())
    }

    @Test
    fun nackTableMatchesEveryRow() {
        val rows = fixture.obj("opNackActions")
        fun resolve(name: String): JsonObject {
            val row = rows.getValue(name).jsonObject
            return row.strOrNull("sameAs")?.let(::resolve) ?: row
        }
        for (name in rows.keys) {
            if (rows.getValue(name) !is JsonObject) continue
            val row = resolve(name)
            val (code, retryFlag) = when (name) {
                "unknown_retry_true" -> "brand-new-code" to true
                "unknown_retry_false" -> "brand-new-code" to false
                else -> name to (name in setOf("storage", "hello-required"))
            }
            val decision = SyncNackPolicy.decide(code, retryFlag)
            assertEquals(name, row.getValue("resolveOp").jsonPrimitive.boolean, decision.resolveOp)
            row["reconnect"]?.jsonPrimitive?.booleanOrNull?.let { assertEquals(name, it, decision.reconnect) }
            row["pauseMutationsForJoin"]?.jsonPrimitive?.booleanOrNull?.let {
                assertEquals(name, it, decision.pauseMutationsForJoin)
            }
            row["suppressUntilLocalEdit"]?.jsonPrimitive?.booleanOrNull?.let {
                assertEquals(name, it, decision.suppressUntilLocalEdit)
            }
            row.strOrNull("localClose")?.let { assertEquals(name, it, decision.localClose?.wireName) }
            if (row.containsKey("issue")) assertEquals(name, row.strOrNull("issue"), decision.issue?.name)
            if (row.containsKey("retry")) assertTrue(name, decision.retry)
            decision.issue?.let { assertEquals("never SECURITY: $name", SyncIssueKind.CONNECTION, it.kind) }
        }
    }

    @Test
    fun snapshotClassificationListsMatchTheReasons() {
        val snap = fixture.obj("snapshot")
        val byName = SnapshotRecordReason.entries.associateBy { it.wireName }
        for ((list, category) in listOf(
            "fatalStructural" to SnapshotRecordCategory.FATAL,
            "skipUnverified" to SnapshotRecordCategory.SKIP_UNVERIFIED,
            "skipUnsupported" to SnapshotRecordCategory.SKIP_UNSUPPORTED,
        )) {
            for (name in snap.getValue(list).strings()) {
                val reason = byName[name] ?: error("no Android reason for $name")
                assertEquals(name, category, reason.category)
            }
        }
        val listed = listOf("fatalStructural", "skipUnverified", "skipUnsupported")
            .flatMap { snap.getValue(it).strings() }.toSet()
        assertEquals(listed, byName.keys)
    }

    // ---- scenarios -------------------------------------------------------

    private fun runBackoffSteps(id: String, startAttempt: Int = 0) {
        val steps = scenario(id).arr("steps").map { it.jsonObject }
        var nextRandom = 0.0
        val policy = SyncBackoffPolicy(random = { nextRandom })
        policy.forceAttempt(startAttempt)
        for (step in steps) {
            val at = step["atMs"]?.jsonPrimitive?.long ?: 0L
            when (step.str("event")) {
                "failure" -> {
                    step["attemptBefore"]?.jsonPrimitive?.int?.let(policy::forceAttempt)
                    nextRandom = step.dbl("random")
                    val cls = SyncBackoffClass.fromWire(step.str("class"))!!
                    val delay = policy.scheduleReconnect(cls, at)
                    assertEquals("$id at $at", step.dbl("expectDelayMs"), delay.toDouble(), 0.5)
                }
                "helloAck" -> policy.connected(at)
                "opAck" -> policy.opAcked()
                "tick" -> {
                    policy.tick(at)
                    step["expectAttempt"]?.jsonPrimitive?.int?.let { assertEquals("$id at $at", it, policy.attempt) }
                }
                "attemptStarted" -> policy.attemptStarted(at)
                "networkAvailable" -> {
                    val connectAt = policy.networkAvailable(at)
                    assertEquals("$id at $at", step.obj("expect").long("connectAtMs"), connectAt)
                }
                else -> error(step.str("event"))
            }
        }
    }

    @Test fun backoffFullJitterTransient() = runBackoffSteps("backoff_full_jitter_transient")
    @Test fun backoffNotResetAtHelloAck() = runBackoffSteps("backoff_not_reset_at_hello_ack")
    @Test fun backoffResetAfterStable30s() = runBackoffSteps("backoff_reset_after_stable_30s")
    @Test fun backoffSlowBusy503() = runBackoffSteps("backoff_slow_busy_503")
    @Test fun reachabilityShortcutsTransientOnly() = runBackoffSteps("reachability_shortcuts_transient_only")

    @Test
    fun backoffJitterStaysSpreadAtTheCap() {
        // S2-14: the old +-20% clamp put about half the mass at exactly 30 s
        val rng = java.util.Random(42)
        val policy = SyncBackoffPolicy(random = { rng.nextDouble() })
        var atCap = 0
        repeat(10_000) {
            policy.forceAttempt(6 + (it % 10))
            if (policy.nextDelay(SyncBackoffClass.TRANSIENT).toLong() == 30_000L) atCap += 1
        }
        assertTrue("$atCap of 10000 at exactly 30 s", atCap < 100)
    }

    @Test
    fun closeAndStatusTableScenario() {
        for (case in scenario("close_and_status_table").arr("cases").map { it.jsonObject }) {
            val expect = case.obj("expect")
            val decision = if (case.getValue("opened").jsonPrimitive.boolean) {
                SyncCloseClassifier.classifyClose(
                    case.int("closeCode"),
                    afterOwnLeave = case["afterOwnLeave"]?.jsonPrimitive?.boolean == true,
                )
            } else {
                SyncCloseClassifier.classifyHttp(case["httpStatus"]?.jsonPrimitive?.intOrNull)
            }
            val label = case.toString()
            assertEquals(label, expect.str("action"), when (decision.action) {
                SyncCloseAction.NONE -> "none"
                SyncCloseAction.RECONNECT -> "reconnect"
                SyncCloseAction.RECONNECT_NOW -> "reconnect_now"
                SyncCloseAction.STOP -> "stop"
                SyncCloseAction.ESCALATE_EPOCH_THEN_RECONNECT -> "escalate_epoch_then_reconnect"
            })
            expect.strOrNull("backoffClass")?.let { assertEquals(label, it, decision.backoffClass?.wireName) }
            expect.strOrNull("issue")?.let { assertEquals(label, it, decision.issue?.name) }
            if (expect.strOrNull("pacer") == "after4008") assertTrue(label, decision.pacerAfter4008)
        }
    }

    @Test
    fun sessionConflictStopsAfterThree() {
        val counters = SyncFailureCounters()
        for (step in scenario("session_conflict_stops_after_three").arr("steps").map { it.jsonObject }) {
            val decision = step["closeCode"]?.jsonPrimitive?.int?.let { SyncCloseClassifier.classifyClose(it) }
                ?: run {
                    val nack = SyncNackPolicy.decide(step.str("nack"), false)
                    SyncCloseClassifier.classifyLocal(nack.localClose!!)
                }
            assertTrue(decision.countsAsSessionConflict)
            val stop = counters.recordSessionConflict(step.long("atMs"))
            val expect = step.obj("expect")
            assertEquals(expect.str("action") == "stop", stop)
            if (stop) assertEquals(expect.str("issue"), decision.issue?.name)
        }
        // outside the 10 minute window they don't add up
        val spread = SyncFailureCounters()
        assertFalse(spread.recordSessionConflict(0))
        assertFalse(spread.recordSessionConflict(400_000))
        assertFalse(spread.recordSessionConflict(700_000))
    }

    @Test
    fun staleOnOwnPersistedStampIsConfirmed() {
        for (case in scenario("stale_on_own_persisted_stamp_is_confirmed").arr("cases").map { it.jsonObject }) {
            val decision = SyncNackPolicy.decide(
                case.str("code"), false,
                rejectedStampEqualsOwnPersisted = case.getValue("rejectedStampEqualsOwnPersisted").jsonPrimitive.boolean,
                wireIdSkippedThisJoin = case.getValue("wireIdSkippedThisJoin").jsonPrimitive.boolean,
            )
            val expect = case.obj("expect")
            assertEquals(case.toString(), expect.getValue("markConfirmed").jsonPrimitive.boolean, decision.markConfirmed)
            assertEquals(case.toString(), expect.getValue("suppress").jsonPrimitive.boolean, decision.suppressUntilLocalEdit)
            assertTrue(decision.resolveOp)
            assertFalse(decision.reconnect)
        }
    }

    @Test
    fun seqRegressionDetection() {
        val steps = scenario("seq_regression_surfaced_once_per_join").arr("steps").map { it.jsonObject }
        val begins = steps.filter { it.str("event") == "snapshotBegin" }
        for (step in begins) {
            assertTrue(SnapshotFence.isRegression(step.long("seq"), step.long("lastSnapshotSeq")))
        }
        assertFalse(SnapshotFence.isRegression(4, -1))
        assertFalse(SnapshotFence.isRegression(90, 90))
    }

    @Test
    fun liveWindowResyncCooldown() {
        val policy = LiveWindowResyncPolicy()
        for (step in scenario("live_window_resync_cooldown").arr("steps").map { it.jsonObject }) {
            val at = step.long("atMs")
            val expect = step.obj("expect")
            val now = when (step.str("event")) {
                "windowRejection" -> policy.windowRejection(at)
                "tick" -> policy.tick(at)
                else -> error(step.str("event"))
            }
            assertEquals("at $at", expect.getValue("resyncNow").jsonPrimitive.boolean, now)
            expect["pending"]?.jsonPrimitive?.boolean?.let { assertEquals("at $at", it, policy.hasPending) }
        }
        // and the hourly cap
        val capped = LiveWindowResyncPolicy()
        var t = 0L
        repeat(LiveWindowResyncPolicy.MAX_PER_HOUR) {
            assertTrue(capped.windowRejection(t))
            t += LiveWindowResyncPolicy.COOLDOWN_MS
        }
        assertFalse(capped.windowRejection(t))
        assertTrue(capped.hasPending)
    }

    // pacer simulator: writes complete instantly, every mutation acked a fixed time after it went out
    private data class Send(val atMs: Double, val bytes: Int)

    private fun simulatePacer(mutations: Int, frameBytes: Int, ackAfterMs: Double): List<Send> {
        val pacer = SyncOutboundPacer<Int>(0.0)
        repeat(mutations) { pacer.offer(SyncOutboundPacer.Entry(SyncOutboundClass.MUTATION, frameBytes, "m$it", it)) }
        val sends = ArrayList<Send>()
        val acks = java.util.TreeMap<Double, MutableList<String>>()
        var now = 0.0
        var maxInFlight = 0
        while (!pacer.isEmpty()) {
            acks.headMap(now, true).values.flatten().forEach(pacer::release)
            acks.headMap(now, true).clear()
            val entry = pacer.poll(now)
            if (entry != null) {
                sends += Send(now, entry.bytes)
                acks.getOrPut(now + ackAfterMs) { ArrayList() } += entry.key!!
                maxInFlight = maxOf(maxInFlight, pacer.inFlightCount)
                continue
            }
            val ready = pacer.nextReadyAtMs(now)
            val ack = acks.firstEntry()?.key
            now = listOfNotNull(ready, ack).filter { it > now }.minOrNull() ?: error("pacer stuck at $now")
        }
        assertTrue("in flight $maxInFlight", maxInFlight <= SyncOutboundPacer.MAX_IN_FLIGHT_MUTATIONS)
        return sends
    }

    private fun maxInAnyWindow(sends: List<Send>, measure: (Send) -> Long): Long {
        var best = 0L
        for (start in sends) {
            val total = sends.filter { it.atMs >= start.atMs && it.atMs < start.atMs + 10_000.0 }.sumOf(measure)
            best = maxOf(best, total)
        }
        return best
    }

    @Test
    fun pacerBulk500Small() {
        val s = scenario("pacer_bulk_500_small")
        val given = s.obj("given")
        val expect = s.obj("expect")
        val sends = simulatePacer(given.int("mutations"), given.int("frameBytes"), given.dbl("ackAfterWriteMs"))
        assertEquals(given.int("mutations"), sends.size)
        assertEquals(expect.int("sendsAtZero"), sends.count { it.atMs == 0.0 })
        val tolerance = expect.dbl("toleranceMs")
        assertEquals(expect.dbl("lastSendAtMs"), sends.last().atMs, tolerance)
        val maxFrames = maxInAnyWindow(sends) { 1L }
        assertEquals(expect.long("maxFramesAnyWindow"), maxFrames)
        assertTrue(maxFrames <= expect.long("maxFramesAnyWindowLimit"))
    }

    @Test
    fun pacerLargeFramesBytes() {
        val s = scenario("pacer_large_frames_bytes")
        val given = s.obj("given")
        val expect = s.obj("expect")
        val sends = simulatePacer(given.int("mutations"), given.int("frameBytes"), given.dbl("ackAfterWriteMs"))
        val expected = expect.arr("sendTimesMs").map { it.jsonPrimitive.double }
        assertEquals(expected.size, sends.size)
        expected.zip(sends).forEach { (want, got) -> assertEquals(want, got.atMs, expect.dbl("toleranceMs")) }
        val maxBytes = maxInAnyWindow(sends) { it.bytes.toLong() }
        assertEquals(expect.long("maxBytesAnyWindow"), maxBytes)
        assertTrue(maxBytes <= expect.long("maxBytesAnyWindowLimit"))
    }

    @Test
    fun pacerInteractiveReserve() {
        val s = scenario("pacer_interactive_reserve")
        val given = s.obj("given")
        val pacer = SyncOutboundPacer<String>(0.0)
        repeat(given.int("mutations")) {
            pacer.offer(SyncOutboundPacer.Entry(SyncOutboundClass.MUTATION, given.int("frameBytes"), "m$it", "m$it"))
        }
        // drain bulk work up to the first scripted offer, acking as we go
        val firstAt = s.arr("steps").first().jsonObject.long("atMs").toDouble()
        var now = 0.0
        val acks = java.util.TreeMap<Double, MutableList<String>>()
        while (now < firstAt) {
            acks.headMap(now, true).values.flatten().forEach(pacer::release)
            acks.headMap(now, true).clear()
            val e = pacer.poll(now)
            if (e != null) { acks.getOrPut(now + given.dbl("ackAfterWriteMs")) { ArrayList() } += e.key!!; continue }
            now = listOfNotNull(pacer.nextReadyAtMs(now), acks.firstEntry()?.key).filter { it > now }.minOrNull()
                ?.coerceAtMost(firstAt) ?: firstAt
        }
        for (step in s.arr("steps").map { it.jsonObject }) {
            val at = step.long("atMs").toDouble()
            val cls = SyncOutboundClass.valueOf(step.str("class").uppercase())
            pacer.offer(SyncOutboundPacer.Entry(cls, step.int("bytes"), null, cls.name))
            val sent = pacer.poll(at)
            assertNotNull("${cls.name} should go out at $at", sent)
            assertEquals(cls, sent!!.cls)
            assertEquals(step.obj("expect").long("sentAtMs").toDouble(), at, 0.0)
        }
    }

    @Test
    fun pacerPresenceReplacesAndAfter4008StartsHalfFull() {
        val pacer = SyncOutboundPacer<String>(0.0)
        pacer.offer(SyncOutboundPacer.Entry(SyncOutboundClass.PRESENCE, 100, null, "old"))
        pacer.offer(SyncOutboundPacer.Entry(SyncOutboundClass.PRESENCE, 100, null, "new"))
        assertEquals(1, pacer.queuedCount)
        assertEquals("new", pacer.poll(0.0)!!.payload)

        val throttled = SyncOutboundPacer<Int>(0.0, after4008 = true)
        repeat(30) { throttled.offer(SyncOutboundPacer.Entry(SyncOutboundClass.CONTROL, 10, null, it)) }
        var sent = 0
        while (throttled.poll(0.0) != null) sent += 1
        assertEquals(SyncOutboundPacer.AFTER_4008_FRAME_TOKENS, sent)
    }

    @Test
    fun retransmitStartsAtWriteAndTimeoutTable() {
        val s = scenario("retransmit_starts_at_write")
        for (row in s.arr("timeouts").map { it.jsonObject }) {
            assertEquals(row.toString(), row.long("expectMs"), SyncAckTimer.timeoutMs(row.int("attempt"), row.int("ahead")))
        }
        runAckTimerSteps(s.arr("steps").map { it.jsonObject })
    }

    @Test
    fun noRetransmitWhileUnwritten() = runAckTimerSteps(
        scenario("no_retransmit_while_unwritten").arr("steps").map { it.jsonObject }
    )

    private fun runAckTimerSteps(steps: List<JsonObject>) {
        val timer = SyncAckTimer()
        var retransmits = 0
        for (step in steps) {
            val at = step.long("atMs")
            val rid = step.strOrNull("rid") ?: "r1"
            val expect = step["expect"]?.jsonObject
            when (step.str("event")) {
                "enqueue" -> timer.enqueued(rid)
                "writeComplete" -> {
                    val deadline = timer.writeComplete(rid, at, step.int("aheadInFlight"))
                    expect?.get("ackDeadlineMs")?.jsonPrimitive?.long?.let { assertEquals(it, deadline) }
                }
                "ackTimeout", "tick" -> {
                    val due = timer.check(rid, at)
                    if (due == SyncAckTimer.Due.RETRANSMIT) retransmits += 1
                    expect?.get("retransmitEnqueued")?.jsonPrimitive?.boolean?.let {
                        assertEquals("at $at", it, due == SyncAckTimer.Due.RETRANSMIT)
                    }
                    expect?.get("attempt")?.jsonPrimitive?.int?.let { assertEquals(it, timer.attempt(rid)) }
                }
                "ack" -> {
                    timer.resolved(rid)
                    expect?.get("retransmits")?.jsonPrimitive?.int?.let { assertEquals(it, retransmits) }
                }
                else -> error(step.str("event"))
            }
        }
    }

    @Test
    fun ackTimerExhaustsAfterThreeAttempts() {
        val timer = SyncAckTimer()
        timer.enqueued("r")
        var now = 0L
        repeat(SyncAckTimer.MAX_ATTEMPTS - 1) {
            val deadline = timer.writeComplete("r", now, 0)!!
            assertEquals(SyncAckTimer.Due.RETRANSMIT, timer.check("r", deadline))
            now = deadline
        }
        val last = timer.writeComplete("r", now, 0)!!
        assertEquals(SyncAckTimer.Due.EXHAUSTED, timer.check("r", last))
    }

    @Test
    fun receiveBudgetBulkImportNoClose() {
        val s = scenario("receive_budget_bulk_import_no_close")
        val given = s.obj("given")
        val sessions = given.int("activeRemoteSessions")
        assertEquals(s.obj("expect").int("roomFrameLimit"), SyncReceiveBudget.roomFrameLimit(sessions))
        assertEquals(s.obj("expect").long("roomByteLimit"), SyncReceiveBudget.roomByteLimit(sessions))
        val sizes = given.obj("frameBytes")
        val budget = SyncReceiveBudget()
        var now = 0L
        for (step in s.arr("steps").map { it.jsonObject }) {
            var allAdmitted = true
            var last = true
            for (bucketName in listOf("room", "selfResponse")) {
                val counts = step[bucketName]?.jsonObject ?: continue
                for ((type, count) in counts) {
                    repeat(count.jsonPrimitive.int) {
                        last = budget.admit(
                            1L, sizes.int(type), SyncReceiveBudget.Phase.LIVE,
                            SyncReceiveBudget.bucketFor(type), sessions, now,
                        )
                        allAdmitted = allAdmitted && last
                    }
                }
            }
            val expect = step.obj("expect")
            expect["allAdmitted"]?.jsonPrimitive?.boolean?.let { assertEquals(step.toString(), it, allAdmitted) }
            expect["admitted"]?.jsonPrimitive?.boolean?.let { assertEquals(step.toString(), it, last) }
            now += step.long("withinMs")
        }
    }

    @Test
    fun backgroundDiscardUsesBackgroundBudget() {
        val s = scenario("background_discard_uses_background_budget")
        val budget = SyncReceiveBudget()
        var now = 0L
        for (step in s.arr("steps").map { it.jsonObject }) {
            var all = true
            var last = true
            repeat(step.obj("room").int("loc")) {
                last = budget.admit(1L, 1_500, SyncReceiveBudget.Phase.BACKGROUND, SyncReceiveBudget.Bucket.ROOM, 0, now)
                all = all && last
            }
            val expect = step.obj("expect")
            expect["allAdmitted"]?.jsonPrimitive?.boolean?.let { assertEquals(it, all) }
            expect["lastAdmitted"]?.jsonPrimitive?.boolean?.let { assertEquals(it, last) }
            now += step.long("withinMs")
        }
    }

    private fun runWatchdog(id: String) {
        val dog = SyncHandshakeWatchdog()
        for (step in scenario(id).arr("steps").map { it.jsonObject }) {
            val at = step.long("atMs")
            when (step.str("event")) {
                "socketCreated" -> dog.socketCreated(at)
                "open" -> dog.opened(at)
                "snapshotBegin", "snapshotPage" -> dog.progress(at)
                "snapshotEnd" -> dog.snapshotEnded(at)
                "helloWritten" -> dog.helloWritten(at)
                "helloAck" -> {
                    assertNull("$id at $at", dog.check(at))
                    dog.connected()
                }
                "tick" -> Unit
                else -> error(step.str("event"))
            }
            val expect = step["expect"]?.jsonObject ?: continue
            if (step.str("event") == "helloAck") {
                assertFalse(dog.isActive)
                continue
            }
            val fired = dog.check(at)
            assertEquals("$id at $at", expect.getValue("timedOut").jsonPrimitive.boolean, fired != null)
            expect.strOrNull("localClose")?.let { assertEquals("$id at $at", it, fired?.wireName) }
        }
    }

    @Test fun handshakeProgressWatchdog() = runWatchdog("handshake_progress_watchdog")
    @Test fun handshakeStallFires() = runWatchdog("handshake_stall_fires")
    @Test fun androidConnectOpenTimeout() = runWatchdog("android_connect_open_timeout")
    @Test fun helloAckTimeout() = runWatchdog("hello_ack_timeout")

    @Test
    fun watchdogAbsoluteCeiling() {
        val dog = SyncHandshakeWatchdog()
        dog.socketCreated(0)
        dog.opened(10)
        var t = 10L
        while (t < SyncHandshakeWatchdog.ABSOLUTE_MAX_MS) {
            dog.progress(t)
            assertNull(dog.check(t))
            t += 50_000
        }
        dog.progress(SyncHandshakeWatchdog.ABSOLUTE_MAX_MS)
        assertEquals(SyncLocalClose.HANDSHAKE_ABSOLUTE, dog.check(SyncHandshakeWatchdog.ABSOLUTE_MAX_MS))
    }

    @Test
    fun helloEpochVectors() {
        for (v in fixture.obj("helloEpoch").arr("vectors").map { it.jsonObject }) {
            val persisted = v.strOrNull("persisted")?.let { BigInteger(it, 16) }
            val rejected = v.strOrNull("rejected")?.let { BigInteger(it, 16) }
            val result = HelloEpochPolicy.next(
                persisted, v.long("nowMs"), v.getValue("after4014").jsonPrimitive.boolean, rejected,
                v.getValue("bgOptIn").jsonPrimitive.boolean,
            )
            val name = v.str("name")
            if (v.containsKey("expectError")) {
                assertEquals(name, HelloEpochPolicy.Result.Exhausted, result)
                continue
            }
            val next = result as HelloEpochPolicy.Result.Next
            assertEquals(name, v.str("expectNext"), next.nextHex)
            assertEquals(name, v.str("expectPersisted"), next.persistedHex)
            val spares = v.getValue("expectSpares")
            if (spares is JsonArray) {
                assertEquals(name, 0, next.spareCount)
            } else {
                val o = spares.jsonObject
                assertEquals(name, o.int("count"), next.spareCount)
                assertEquals(name, o.str("first"), HelloEpochPolicy.hex(next.next.add(BigInteger.ONE)))
                assertEquals(name, o.str("last"), next.persistedHex)
            }
        }
    }

    @Test
    fun helloEpochFloorGoesThroughTheReplayStatePrimitive() {
        val roomId = SyncIdentity.urlB64(ByteArray(32) { 3 })
        val seed = SyncSigning.generateSeed()
        val pub = SyncSigning.publicKey(seed)
        val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(roomId)!!, SyncIdentity.urlB64Decode32(pub)!!)
        val state = SyncReplayState(roomId, persistOverride = { true })
        // lost state: time floor
        val first = state.reserveHelloEpoch(actor, pub, floor = HelloEpochPolicy.floor(null, 1_790_812_800_000L, false, null))
        assertEquals("0000000001c76d60", first)
        // normal reconnect keeps +1
        assertEquals("0000000001c76d61", state.reserveHelloEpoch(actor, pub))
        // background spare block persists ahead but signs the next one
        assertEquals("0000000001c76d62", state.reserveHelloEpoch(actor, pub, spare = 64))
        assertEquals("0000000001c76da2", state.getHelloEpoch(actor))
        assertEquals("0000000001c76da3", state.reserveHelloEpoch(actor, pub))
    }

    @Test
    fun chatFencePruning() {
        val cases = scenario("chat_fence_pruning").arr("cases").map { it.jsonObject }
        val first = cases.first()
        val durable = first.obj("durableSessions").mapValues { it.value.jsonPrimitive.content }
        val fences = first.arr("fences").map { it.jsonArray.map { e -> e.jsonPrimitive.content } }
        val retained = ChatReplayPruner.prune(fences, { it[0] to it[1] }, durable::get)
        assertEquals(first.arr("expectRetained").map { it.jsonArray.map { e -> e.jsonPrimitive.content } }, retained)
        for (case in cases.drop(1)) {
            val count = case.int("fenceCount")
            val superseded = case.int("superseded")
            val all = (0 until count).map { listOf("actor$it", if (it < superseded) "old" else "sd$it", "k$it") }
            val sessions = (0 until superseded).associate { "actor$it" to "new" }
            val kept = ChatReplayPruner.prune(all, { it[0] to it[1] }, sessions::get)
            val admitted = ChatReplayPruner.canAdmitNewIdentity(kept.size)
            val expect = case.obj("expect")
            assertEquals(case.toString(), expect.str("result") == "accepted", admitted)
            expect["fenceCountAfter"]?.jsonPrimitive?.int?.let { assertEquals(it, kept.size + 1) }
        }
    }

    @Test
    fun chatHistoryBytePrune() {
        val s = scenario("chat_history_byte_prune")
        fun check(test: JsonObject) {
            val given = test.obj("given")
            val sizes = given.arr("messageEncodedBytes").map { it.jsonPrimitive.int }
            val drop = ChatHistoryBudget.dropCount(given.long("fixedOverheadBytes"), given.int("separatorBytes"), sizes)
            val expect = test.obj("expect")
            val kept = sizes.drop(drop).takeLast(ChatHistoryBudget.MAX_MESSAGES)
            assertEquals(expect.int("dropOldest"), drop)
            assertEquals(expect.int("keep"), kept.size)
            assertTrue(ChatHistoryBudget.encodedSize(given.long("fixedOverheadBytes"), given.int("separatorBytes"),
                kept) <= expect.long("resultBytesAtMost"))
        }
        check(s)
        check(s.obj("countBoundary"))
    }

    @Test
    fun objectTooLargeNotReserved() {
        for (case in scenario("object_too_large_not_reserved").arr("cases").map { it.jsonObject }) {
            val inner = case.long("innerUtf8Bytes")
            val expect = case.obj("expect")
            assertEquals(expect.long("ctChars"), OutboundSizeCheck.ctChars(inner))
            assertEquals(expect.getValue("send").jsonPrimitive.boolean, OutboundSizeCheck.fits(inner, 1_000L))
        }
    }

    @Test
    fun v2CasingAndTie() {
        val vectors = fixture.obj("v2").obj("vectors")
        for (case in vectors.arr("casing").map { it.jsonObject }) {
            val key = LegacyV2Ids.stateKey(case.str("raw"))
            if (case.getValue("accept").jsonPrimitive.boolean) assertEquals(case.str("key"), key) else assertNull(key)
        }
        for (case in vectors.arr("tie").map { it.jsonObject }) {
            val last = case.obj("last")
            val incoming = case.obj("incoming")
            assertEquals(case.toString(), case.getValue("apply").jsonPrimitive.boolean, LegacyV2Ids.beats(
                incoming.long("v"), incoming.str("by"), last.long("v"), last.str("by"),
            ))
        }
    }

    // ---- SP3 (plans/04 sections 17-21) ---------------------------------------

    @Test
    fun sp3ConstantsMatchTheFixture() {
        val presence = fixture.obj("presence")
        presence.obj("foreground").let {
            assertEquals(it.long("minIntervalMs"), PresenceSendPolicy.MIN_INTERVAL_MS)
            assertEquals(it.long("stationaryHeartbeatMs"), PresenceSendPolicy.STATIONARY_HEARTBEAT_MS)
            assertEquals(it.dbl("moveMinMetres"), PresenceSendPolicy.MOVE_MIN_METRES, 0.0)
            assertEquals(it.dbl("courseChangeDegrees"), PresenceSendPolicy.COURSE_CHANGE_DEGREES, 0.0)
            assertEquals(it.dbl("courseCheckMinSpeedMps"), PresenceSendPolicy.COURSE_CHECK_MIN_SPEED_MPS, 0.0)
            assertEquals(it.dbl("speedChangeMps"), PresenceSendPolicy.SPEED_CHANGE_MPS, 0.0)
        }
        assertEquals(presence.obj("background").long("bridgeFixMaxAgeMs"), BackgroundPresencePolicy.BRIDGE_FIX_MAX_AGE_MS)
        presence.obj("fencePersistence").let {
            assertEquals(it.long("stride"), PresenceFencePersistence.STRIDE)
            assertEquals(it.long("flushMs"), PresenceFencePersistence.FLUSH_MS)
            assertEquals(it.long("crashFloorAdd"), PresenceFencePersistence.CRASH_FLOOR_ADD)
        }
        fixture.obj("persistenceBatching").let {
            assertTrue(it.getValue("splitBeforeRepeatedMutationWireId").jsonPrimitive.boolean)
            assertEquals(it.int("inboundBatchMaxFrames"), SyncManager.INBOUND_BATCH_MAX_FRAMES)
            assertEquals(it.int("inboundQueueMaxFrames"), SyncManager.INBOUND_QUEUE_MAX_FRAMES)
            assertEquals(it.long("inboundQueueMaxBytes"), SyncManager.INBOUND_QUEUE_MAX_BYTES)
            assertEquals(it.long("diffDebounceMs"), SyncManager.DIFF_DEBOUNCE_MS)
        }
        val heartbeat = fixture.obj("heartbeat")
        heartbeat.obj("background").let {
            assertEquals(it.long("pingIntervalMs"), BackgroundPresencePolicy.BACKGROUND_PING_INTERVAL_MS)
            assertEquals(it.long("deadAfterMsWithoutPongOrFrame"), BackgroundPresencePolicy.BACKGROUND_DEAD_AFTER_MS)
        }
        heartbeat.obj("backgroundSendProbe").let {
            assertEquals(it.long("livenessMaxAgeMs"), BackgroundPresencePolicy.LIVENESS_MAX_AGE_MS)
            assertEquals(it.long("probePongTimeoutMs"), BackgroundPresencePolicy.PROBE_PONG_TIMEOUT_MS)
            assertEquals(it.long("androidWakeLockMaxMs"), BackgroundPresencePolicy.WAKE_LOCK_MAX_MS)
        }
        fixture.obj("background").obj("reconnect").let {
            assertEquals(it.long("minSpacingMs"), BackgroundPresencePolicy.MIN_SPACING_MS)
            assertEquals(it.int("maxConsecutiveFailures"), BackgroundPresencePolicy.MAX_CONSECUTIVE_FAILURES)
            assertEquals(it.long("maxSnapshotDrainBytes"), BackgroundPresencePolicy.MAX_SNAPSHOT_DRAIN_BYTES)
        }
        assertEquals(fixture.obj("chat").obj("backgroundRecipient").long("foregroundRetentionSeconds"),
            ChatSendGate.FOREGROUND_RETENTION_SECONDS)
    }

    /** Feeds 1 Hz fixes through the pure policy and returns when it would send. */
    private fun presenceSends(id: String): Pair<List<Long>, List<Long>> {
        val s = scenario(id)
        val given = s.obj("given")
        val fixes = given.obj("fixes")
        val configChangeAt = given["configChangeAtMs"]?.jsonPrimitive?.long
        val lat = fixes.dbl("lat")
        val lon = fixes.dbl("lon")
        val speed = fixes.dbl("speedMps")
        val course = fixes.dbl("courseDeg")
        val accuracy = fixes.dbl("horizontalAccuracyM")
        val policy = PresenceSendPolicy()
        val sends = ArrayList<Long>()
        var t = fixes.long("startMs")
        while (t <= fixes.long("endMs")) {
            // moving east: lon offset = metres / (111320 * cos(lat))
            val metres = speed * (t / 1_000.0)
            val fix = PresenceSendPolicy.Fix(
                lat = lat,
                lon = lon + metres / (111_320.0 * Math.cos(Math.toRadians(lat))),
                speedMps = speed,
                courseDeg = course,
                horizontalAccuracyM = accuracy,
            )
            val config = if (configChangeAt != null && t >= configChangeAt) "after" else "before"
            if (policy.shouldSend(fix, t, config)) {
                policy.recordSent(fix, t, config)
                sends += t
            }
            t += fixes.long("everyMs")
        }
        return sends to s.obj("expect").arr("sendAtMs").map { it.jsonPrimitive.long }
    }

    @Test fun presenceStationaryHeartbeat() = presenceSends("presence_stationary_heartbeat").let { (got, want) -> assertEquals(want, got) }
    @Test fun presenceMovingKeeps5s() = presenceSends("presence_moving_keeps_5s").let { (got, want) -> assertEquals(want, got) }
    @Test fun presenceConfigChangeSends() = presenceSends("presence_config_change_sends").let { (got, want) -> assertEquals(want, got) }

    @Test
    fun presenceFenceStrideAndCrashFloor() {
        SyncHarness.installStoreKey()
        val base = com.tacmap.util.SafeStore.migrationPolicy
        var writes = 0
        com.tacmap.util.SafeStore.migrationPolicy = object : com.tacmap.util.SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = base.isSealedOnly(label)
            override fun markSealedOnly(label: String) {
                if (label.startsWith("sync/room/")) writes += 1
                base.markSealedOnly(label)
            }
        }
        try {
            val dir = java.nio.file.Files.createTempDirectory("presence-fence").toFile()
            val roomId = SyncIdentity.urlB64(ByteArray(32) { 9 })
            val seed = SyncSigning.generateSeed()
            val pub = SyncSigning.publicKey(seed)
            val actor = SyncIdentity.actorId(SyncIdentity.urlB64Decode32(roomId)!!, SyncIdentity.urlB64Decode32(pub)!!)
            val sd = SyncIdentity.urlB64(ByteArray(32) { 4 })
            var state = SyncReplayState(roomId, dir)
            for (step in scenario("presence_fence_stride_and_crash_floor").arr("steps").map { it.jsonObject }) {
                when (step.str("event")) {
                    "load" -> {
                        if (step.long("persisted") == 0L) {
                            assertTrue(state.load())
                            assertTrue(state.commitActorHello(actor, pub, sd, "0000000000000001"))
                        } else {
                            state = SyncReplayState(roomId, dir)
                            assertTrue(state.load())
                        }
                        step["expectFloor"]?.jsonPrimitive?.long?.let { assertEquals(it, state.getPresenceCounter(actor)) }
                    }
                    "acceptCounters" -> {
                        val persistedAt = ArrayList<Long>()
                        for (c in step.long("from")..step.long("to")) {
                            val before = writes
                            assertTrue("counter $c", state.commitPresence(actor, pub, sd, c))
                            if (writes > before) persistedAt += c
                        }
                        assertEquals(step.arr("expectPersistBeforeExposing").map { it.jsonPrimitive.long }, persistedAt)
                    }
                    "crashAndLoad" -> {
                        // no clean point: drop the object and reload what's on disk
                        state = SyncReplayState(roomId, dir)
                        assertTrue(state.load())
                        assertEquals(step.long("expectFloor"), state.getPresenceCounter(actor))
                    }
                    "offer" -> {
                        val accepted = state.commitPresence(actor, pub, sd, step.long("counter"))
                        assertEquals(step.toString(), step.obj("expect").getValue("accepted").jsonPrimitive.boolean, accepted)
                    }
                    "cleanPoint" -> {
                        assertEquals(step.long("lastAccepted"), state.getPresenceCounter(actor))
                        val before = writes
                        assertTrue(state.persistExactPresence())
                        assertEquals(before + 1, writes)
                        // what's on disk now loads with no floor added
                        val reread = SyncReplayState(roomId, dir)
                        assertTrue(reread.load())
                        assertEquals(step.obj("expectPersist").long("counter"), reread.getPresenceCounter(actor))
                    }
                    else -> error(step.str("event"))
                }
            }
        } finally {
            SyncHarness.restoreStoreKey()
        }
    }

    @Test
    fun chatBlockedToBackgroundPeer() {
        for (case in scenario("chat_blocked_to_background_peer").arr("cases").map { it.jsonObject }) {
            val blocked = ChatSendGate.blockedForBackground(
                direct = case.str("scope") == "direct",
                recipientRetentionSeconds = case.long("recipientLatestRetentionSeconds"),
            )
            val expect = case.obj("expect")
            assertEquals(case.toString(), expect.getValue("blocked").jsonPrimitive.boolean, blocked)
            if (blocked) assertEquals("CHAT_RECIPIENT_IN_BACKGROUND", expect.str("reason"))
        }
    }

    private fun runBackgroundPolicy(id: String): BackgroundPresencePolicy.Action {
        val s = scenario(id)
        val spares = s.obj("given").int("spares")
        // the policy as it ships once D1 lands; the shipped flag is checked below
        val policy = BackgroundPresencePolicy(reconnectEnabled = true)
        var action: BackgroundPresencePolicy.Action? = null
        for (step in s.arr("steps").map { it.jsonObject }) {
            if (step.str("event") == "fixDue") {
                action = policy.onFixDue(step.long("atMs"), socketUsable = false, sparesLeft = spares, eligible = true)
            }
        }
        return checkNotNull(action)
    }

    @Test
    fun backgroundReconnectUsesSpareEpoch() {
        assertEquals(BackgroundPresencePolicy.Action.CONNECT, runBackgroundPolicy("background_reconnect_uses_spare_epoch"))
        // THREAT_MODEL section 7 still says pause until doc change D1 ships with the flag
        assertFalse(BackgroundPresencePolicy.RECONNECT_ENABLED)
        assertEquals(BackgroundPresencePolicy.Action.PAUSE,
            BackgroundPresencePolicy().onFixDue(900_000, socketUsable = false, sparesLeft = 64, eligible = true))
    }

    @Test
    fun backgroundPausesWithoutSpare() {
        assertEquals(BackgroundPresencePolicy.Action.PAUSE, runBackgroundPolicy("background_pauses_without_spare"))
        assertEquals("BACKGROUND_PAUSED", scenario("background_pauses_without_spare").arr("steps")
            .map { it.jsonObject }.last().obj("expect").str("issue"))
        assertEquals(SyncIssueKind.CONNECTION, SyncIssueCode.BACKGROUND_PAUSED.kind)
    }

    @Test
    fun androidPauseKeepsRoomPolicy() {
        for (case in scenario("android_pause_keeps_room").arr("cases").map { it.jsonObject }) {
            val action = SyncLifecyclePolicy.onActivityPausing(
                roomJoined = true,
                backgroundPresenceEligible = case.getValue("bgOptIn").jsonPrimitive.boolean &&
                    case.getValue("shareLocation").jsonPrimitive.boolean && case.str("room") == "3:",
            )
            assertEquals(case.toString(), SyncLifecyclePolicy.PauseAction.SUSPEND_KEEP_ROOM, action)
        }
        assertEquals(SyncLifecyclePolicy.PauseAction.DISPOSE,
            SyncLifecyclePolicy.onActivityPausing(roomJoined = false, backgroundPresenceEligible = false))
    }
}
