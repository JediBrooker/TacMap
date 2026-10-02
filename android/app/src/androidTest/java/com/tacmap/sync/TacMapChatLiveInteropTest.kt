package com.tacmap.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.drawings.DrawingStore
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingPoint
import com.tacmap.settings.OpsecSettings
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.WaypointStore
import com.tacmap.waypoints.Waypoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

/**
 * Opt-in, two-platform production-manager chat proof.
 *
 * Run this test concurrently with the iOS live interop test against a fresh v3
 * room. It deliberately keeps SyncManager, the bounded production WebSocket
 * transport, relay routing, signatures, key exchange, and chat encryption on
 * their production paths.
 * Only local persistence is redirected to a throw-away app-private sandbox so
 * an interop run cannot read, overwrite, quarantine, or migrate user data.
 */
@RunWith(AndroidJUnit4::class)
class TacMapChatLiveInteropTest {

    @Test
    // The production manager and UI-owned stores remain on Main; delay-based waits yield Main.
    fun roomAndDirectMessagesRoundTripWithIosProductionManager() = runBlocking(Dispatchers.Main.immediate) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val relay = requiredArgument(arguments = arguments, names = RELAY_ARGUMENTS)
        val joinCode = requiredArgument(arguments = arguments, names = JOIN_CODE_ARGUMENTS)
        val runId = requiredArgument(arguments = arguments, names = RUN_ID_ARGUMENTS)
        val fullSync = arguments.getString("syncFullInterop") == "true"
        val lifecycle = arguments.getString("syncLifecyclePhase")
        require(runId.all { it.isLetterOrDigit() || it == '-' })

        require(joinCode.startsWith("3:")) { "Live TacMap Chat interop requires a v3 join code" }
        require(runId.toByteArray(Charsets.UTF_8).size in 1..MAX_RUN_ID_UTF8_BYTES) {
            "TacMap Chat interop run ID must be 1..$MAX_RUN_ID_UTF8_BYTES UTF-8 bytes"
        }

        val targetContext = instrumentation.targetContext
        val sandbox = InteropSandboxContext(targetContext, if (lifecycle == null) null else runId)
        val previousKeyProvider = SafeStore.keyProvider
        val previousMigrationPolicy = SafeStore.migrationPolicy
        val previousOpsecSettings = OpsecSettings.shared
        val fixedStoreKey = MessageDigest.getInstance("SHA-256")
            .digest("TacMap Chat Android live interop::$runId".toByteArray(Charsets.UTF_8))
        val isolatedSealedLabels = Collections.synchronizedSet(mutableSetOf<String>())
        val parentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var manager: SyncManager? = null

        try {
            SafeStore.keyProvider = SafeStore.KeyProvider { fixedStoreKey.copyOf() }
            SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
                override fun isSealedOnly(label: String): Boolean = label in isolatedSealedLabels

                override fun markSealedOnly(label: String) {
                    isolatedSealedLabels += label
                }
            }

            val opsecSettings = OpsecSettings(sandbox).apply { setRelayUrl(relay) }
            assertEquals(relay.trim(), opsecSettings.relayUrl.value)

            val waypoints = WaypointStore(sandbox)
            val drawings = DrawingStore(sandbox)
            val syncManager = SyncManager(
                waypointStore = waypoints,
                drawingStore = drawings,
                parentScope = parentScope,
                context = sandbox,
            )
            manager = syncManager
            assertTrue(
                syncManager.updatePresenceConfig(
                    syncManager.presenceConfig.copy(
                        callsign = ANDROID_CALLSIGN,
                        shareLocation = fullSync,
                    )
                )
            )
            if (fullSync) syncManager.locationProvider = {
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = -35.0; longitude = 149.0; accuracy = 5f
                    speed = 0f; bearing = 0f
                    time = System.currentTimeMillis() - 100
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 100_000_000L
                }
            }
            if (lifecycle == "resume") {
                assertEquals(2, waypoints.committedWaypoints.value.size)
                assertEquals(2, drawings.committedDocument.value.features.size)
                assertEquals(setOf("LIFE_ANDROID_WP:$runId", "LIFE_IOS_WP:$runId"), waypoints.committedWaypoints.value.map { it.name }.toSet())
                assertEquals(setOf("LIFE_ANDROID_DRAW:$runId", "LIFE_IOS_DRAW:$runId"), drawings.committedDocument.value.features.map { it.name }.toSet())
                println("LIFECYCLE_ANDROID_PREJOIN_RECOVERED records=2 drawings=2 $runId")
            }
            syncManager.join(joinCode)

            waitUntil(syncManager, "one authenticated iOS chat recipient") {
                val recipients = syncManager.chatRecipients.value
                if (recipients.size > 1) {
                    throw AssertionError(
                        "Expected one iOS interop peer, found ${recipients.size}: " +
                            recipients.keys.sorted().joinToString()
                    )
                }
                syncManager.status.value == SyncManager.Status.CONNECTED &&
                    syncManager.chatSessionReady.value &&
                    recipients.size == 1
            }
            val iosTarget = syncManager.chatRecipients.value.values.single()
            if (fullSync && lifecycle != "resume") objectsAndPresenceRoundTrip(syncManager, waypoints, drawings, iosTarget.actorId, runId)

            if (lifecycle != null) {
                suspend fun barrier(phase: String) {
                    sentMessageId(syncManager.sendChat(TacMapChatTarget.EntireRoom, TacMapChatContentKind.TEXT,
                        "LIFE_ANDROID_${phase}:$runId"), phase)
                    waitUntil(syncManager, "iOS lifecycle $phase", timeoutMs = 150_000) {
                        syncManager.chatMessages.value.any { !it.isOutgoing && it.senderActorId == iosTarget.actorId && it.body == "LIFE_IOS_${phase}:$runId" }
                    }
                }
                if (lifecycle == "seed") {
                    assertTrue(waypoints.add(Waypoint(name = "LIFE_ANDROID_WP:$runId", latitude = -35.0, longitude = 149.0)))
                    assertTrue(drawings.addFeature(DrawingFeature(name = "LIFE_ANDROID_DRAW:$runId", geometry = DrawingGeometry.LINE,
                        points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)))))
                    waitUntil(syncManager, "iOS durable lifecycle seed") {
                        waypoints.committedWaypoints.value.any { it.name == "LIFE_IOS_WP:$runId" } &&
                            drawings.committedDocument.value.features.any { it.name == "LIFE_IOS_DRAW:$runId" }
                    }
                    barrier("SEEDED")
                    assertEquals(2, waypoints.committedWaypoints.value.size)
                    assertEquals(2, drawings.committedDocument.value.features.size)
                    println("LIFECYCLE_ANDROID_KILL_READY records=2 drawings=2 $runId")
                    delay(180_000)
                    throw AssertionError("Host did not kill the Android test process")
                } else {
                    val own = waypoints.committedWaypoints.value.single { it.name == "LIFE_ANDROID_WP:$runId" }
                    assertTrue(waypoints.update(own.copy(name = "${own.name}:resumed")))
                    waitUntil(syncManager, "iOS post-kill edit") {
                        waypoints.committedWaypoints.value.any { it.name == "LIFE_IOS_WP:$runId:resumed" }
                    }
                    barrier("RESUMED")
                    val oldSession = syncManager.chatRecipients.value.getValue(iosTarget.actorId).sessionDomain
                    println("LIFECYCLE_ANDROID_RELAY_RESTART_READY $runId")
                    waitUntil(syncManager, "new iOS session after actual relay restart", timeoutMs = 150_000) {
                        syncManager.status.value == SyncManager.Status.CONNECTED && syncManager.chatSessionReady.value &&
                            syncManager.chatRecipients.value[iosTarget.actorId]?.sessionDomain?.let { it != oldSession } == true
                    }
                    barrier("RELAY_RESTARTED")
                    println("LIFECYCLE_ANDROID_RELAY_RESTART_OK $runId")
                    assertTrue(syncManager.enterBackgroundPresenceOnly(com.tacmap.settings.BackgroundUnitSyncInterval.ONE_MINUTE))
                    assertTrue(syncManager.isBackgroundPresenceOnly)
                    assertFalse(syncManager.chatSessionReady.value)
                    assertTrue(syncManager.chatRecipients.value.isEmpty())
                    println("LIFECYCLE_ANDROID_BACKGROUND_ENTERED missionDetached chatPaused $runId")
                    delay(20_000)
                    assertTrue(syncManager.isBackgroundPresenceOnly)
                    assertFalse(syncManager.chatSessionReady.value)
                    assertTrue(syncManager.chatMessages.value.none { it.body == "LIFE_IOS_BACKGROUND_CHAT:$runId" })
                    assertTrue(waypoints.committedWaypoints.value.none { it.name == "LIFE_IOS_WP:$runId:resumed:background" })
                    println("LIFECYCLE_ANDROID_BACKGROUND_CHALLENGES_DROPPED roomChat modelApply $runId")
                    syncManager.prepareForForegroundUnlock()
                    val restoredWaypoints = WaypointStore(sandbox)
                    val restoredDrawings = DrawingStore(sandbox)
                    assertTrue(restoredWaypoints.committedWaypoints.value.none { it.name == "LIFE_IOS_WP:$runId:resumed:background" })
                    assertTrue(syncManager.attachForegroundStores(restoredWaypoints, restoredDrawings) {
                        Location(LocationManager.GPS_PROVIDER).apply {
                            latitude = -35.0; longitude = 149.0; accuracy = 5f
                            speed = 0f; bearing = 0f
                            time = System.currentTimeMillis() - 100; elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - 100_000_000L
                        }
                    })
                    waitUntil(syncManager, "fresh foreground snapshot/chat") {
                        syncManager.status.value == SyncManager.Status.CONNECTED && syncManager.chatSessionReady.value &&
                            syncManager.chatRecipients.value.size == 1
                    }
                    waitUntil(syncManager, "foreground snapshot converges background model challenge") {
                        restoredWaypoints.committedWaypoints.value.any { it.name == "LIFE_IOS_WP:$runId:resumed:background" }
                    }
                    assertTrue(syncManager.chatMessages.value.none { it.body == "LIFE_IOS_BACKGROUND_CHAT:$runId" })
                    assertEquals(2, restoredWaypoints.committedWaypoints.value.size)
                    assertEquals(2, restoredDrawings.committedDocument.value.features.size)
                    barrier("FOREGROUND")
                    println("LIFECYCLE_ANDROID_FOREGROUND_OK $runId")
                }
            }

            val androidRoomBody = "ANDROID_TO_IOS_ROOM::$runId"
            val androidDirectBody = "ANDROID_TO_IOS_DIRECT::$runId"
            val iosRoomBody = "IOS_TO_ANDROID_ROOM::$runId"
            val iosDirectBody = "IOS_TO_ANDROID_DIRECT::$runId"

            val androidRoomId = sentMessageId(
                syncManager.sendChat(
                    target = TacMapChatTarget.EntireRoom,
                    kind = TacMapChatContentKind.REPORT,
                    body = androidRoomBody,
                ),
                "Android-to-iOS room report",
            )
            val androidDirectId = sentMessageId(
                syncManager.sendChat(
                    target = syncManager.chatRecipients.value.getValue(iosTarget.actorId),
                    kind = TacMapChatContentKind.TEXT,
                    body = androidDirectBody,
                ),
                "Android-to-iOS direct text",
            )

            waitUntil(syncManager, "routed Android messages and authenticated iOS replies") {
                val messages = syncManager.chatMessages.value
                messages.any {
                    it.id == androidRoomId &&
                        it.isOutgoing &&
                        it.scope == TacMapChatScope.ROOM &&
                        it.kind == TacMapChatContentKind.REPORT &&
                        it.body == androidRoomBody &&
                        it.deliveryState == TacMapChatDeliveryState.ROUTED
                } &&
                    messages.any {
                        it.id == androidDirectId &&
                            it.isOutgoing &&
                            it.scope == TacMapChatScope.DIRECT &&
                            it.kind == TacMapChatContentKind.TEXT &&
                            it.body == androidDirectBody &&
                            it.deliveryState == TacMapChatDeliveryState.ROUTED
                    } &&
                    messages.any {
                        !it.isOutgoing &&
                            it.scope == TacMapChatScope.ROOM &&
                            it.kind == TacMapChatContentKind.REPORT &&
                            it.body == iosRoomBody
                    } &&
                    messages.any {
                        !it.isOutgoing &&
                            it.scope == TacMapChatScope.DIRECT &&
                            it.kind == TacMapChatContentKind.TEXT &&
                            it.body == iosDirectBody
                    }
            }

            val messages = syncManager.chatMessages.value
            val androidRoom = messages.single { it.id == androidRoomId }
            assertOutgoingMessage(
                message = androidRoom,
                expectedScope = TacMapChatScope.ROOM,
                expectedKind = TacMapChatContentKind.REPORT,
                expectedBody = androidRoomBody,
                expectedRecipientActorId = null,
            )
            val androidDirect = messages.single { it.id == androidDirectId }
            assertOutgoingMessage(
                message = androidDirect,
                expectedScope = TacMapChatScope.DIRECT,
                expectedKind = TacMapChatContentKind.TEXT,
                expectedBody = androidDirectBody,
                expectedRecipientActorId = iosTarget.actorId,
            )

            val iosRoom = messages.single { !it.isOutgoing && it.body == iosRoomBody }
            assertInboundMessage(
                message = iosRoom,
                expectedScope = TacMapChatScope.ROOM,
                expectedKind = TacMapChatContentKind.REPORT,
                expectedBody = iosRoomBody,
                expectedSenderActorId = iosTarget.actorId,
                expectsRecipient = false,
            )
            val iosDirect = messages.single { !it.isOutgoing && it.body == iosDirectBody }
            assertInboundMessage(
                message = iosDirect,
                expectedScope = TacMapChatScope.DIRECT,
                expectedKind = TacMapChatContentKind.TEXT,
                expectedBody = iosDirectBody,
                expectedSenderActorId = iosTarget.actorId,
                expectsRecipient = true,
            )
        } finally {
            try {
                val replayBeforeDispose = manager?.replayStateForTests
                manager?.dispose()
                replayBeforeDispose?.awaitPersistence()
                parentScope.cancel()
            } finally {
                OpsecSettings.shared = previousOpsecSettings
                SafeStore.keyProvider = previousKeyProvider
                SafeStore.migrationPolicy = previousMigrationPolicy
                fixedStoreKey.fill(0)
                if (lifecycle != "seed") sandbox.close()
            }
        }
    }

    /** Chat barriers ensure both peers observed each state before either deletes it. */
    private suspend fun objectsAndPresenceRoundTrip(
        manager: SyncManager,
        waypoints: WaypointStore,
        drawings: DrawingStore,
        peerActor: String,
        runId: String,
    ) {
        val waypoint = Waypoint(name = "T2_ANDROID_WP:$runId", latitude = -35.0, longitude = 149.0)
        val drawing = DrawingFeature(
            name = "T2_ANDROID_DRAW:$runId", geometry = DrawingGeometry.LINE,
            points = listOf(DrawingPoint(-35.0, 149.0), DrawingPoint(-35.1, 149.1)),
        )
        assertTrue(waypoints.add(waypoint))
        assertTrue(drawings.addFeature(drawing))
        val peerWaypointName = "T2_IOS_WP:$runId"
        val peerDrawingName = "T2_IOS_DRAW:$runId"
        waitUntil(manager, "iOS objects and verified presence") {
            waypoints.committedWaypoints.value.any { it.name == peerWaypointName } &&
                drawings.committedDocument.value.features.any { it.name == peerDrawingName } &&
                manager.peers.value[peerActor] != null
        }
        val peerWaypointId = waypoints.committedWaypoints.value.single { it.name == peerWaypointName }.id
        val peerDrawingId = drawings.committedDocument.value.features.single { it.name == peerDrawingName }.id
        suspend fun barrier(phase: String) {
            val own = "T2_ANDROID_${phase}:$runId"
            val peer = "T2_IOS_${phase}:$runId"
            sentMessageId(manager.sendChat(TacMapChatTarget.EntireRoom, TacMapChatContentKind.TEXT, own), phase)
            waitUntil(manager, "iOS $phase barrier") {
                manager.chatMessages.value.any { !it.isOutgoing && it.body == peer && it.senderActorId == peerActor }
            }
        }
        barrier("CREATED_SEEN")
        assertTrue(waypoints.update(waypoint.copy(name = "${waypoint.name}:edited")))
        assertTrue(drawings.updateFeature(drawing.copy(name = "${drawing.name}:edited")))
        waitUntil(manager, "iOS edits") {
            waypoints.committedWaypoints.value.any { it.id == peerWaypointId && it.name == "$peerWaypointName:edited" } &&
                drawings.committedDocument.value.features.any { it.id == peerDrawingId && it.name == "$peerDrawingName:edited" }
        }
        barrier("EDITED_SEEN")
        assertTrue(waypoints.remove(waypoints.committedWaypoints.value.single { it.id == waypoint.id }))
        assertTrue(drawings.removeFeature(drawing.id))
        waitUntil(manager, "iOS authenticated deletes") {
            waypoints.committedWaypoints.value.none { it.id == peerWaypointId } &&
                drawings.committedDocument.value.features.none { it.id == peerDrawingId }
        }
        barrier("DELETED_SEEN")
        assertTrue(waypoints.committedWaypoints.value.isEmpty())
        assertTrue(drawings.committedDocument.value.features.isEmpty())
        println("FULL_SYNC_INTEROP_ANDROID_OK presence waypointCRUD drawingCRUD $runId")
    }

    private suspend fun waitUntil(
        manager: SyncManager,
        description: String,
        timeoutMs: Long = INTEROP_TIMEOUT_MS,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var nextDiagnostic = 0L
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= nextDiagnostic) {
                println("INTEROP_ANDROID_WAIT $description chat=" + manager.chatMessages.value.map { "${it.id}|${it.body}|out=${it.isOutgoing}|state=${it.deliveryState}|failure=${it.failureCode}" })
                nextDiagnostic = SystemClock.elapsedRealtime() + 5_000
            }
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for $description; " +
                        "status=${manager.status.value}, " +
                        "chatReady=${manager.chatSessionReady.value}, " +
                        "recipients=${manager.chatRecipients.value.keys.sorted()}, peers=${manager.peers.value.keys.sorted()}, " +
                        "lastError=${manager.lastError.value}"
                )
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun sentMessageId(result: TacMapChatSendResult, label: String): String = when (result) {
        is TacMapChatSendResult.Sent -> result.messageId
        is TacMapChatSendResult.Blocked -> throw AssertionError("$label was blocked: ${result.reason}")
    }

    private fun assertOutgoingMessage(
        message: TacMapChatMessage,
        expectedScope: TacMapChatScope,
        expectedKind: TacMapChatContentKind,
        expectedBody: String,
        expectedRecipientActorId: String?,
    ) {
        assertTrue(message.isOutgoing)
        assertEquals(expectedScope, message.scope)
        assertEquals(expectedKind, message.kind)
        assertEquals(expectedBody, message.body)
        assertEquals(expectedRecipientActorId, message.recipientActorId)
        assertEquals(TacMapChatDeliveryState.ROUTED, message.deliveryState)
        assertNull(message.failureCode)
    }

    private fun assertInboundMessage(
        message: TacMapChatMessage,
        expectedScope: TacMapChatScope,
        expectedKind: TacMapChatContentKind,
        expectedBody: String,
        expectedSenderActorId: String,
        expectsRecipient: Boolean,
    ) {
        assertFalse(message.isOutgoing)
        assertEquals(expectedScope, message.scope)
        assertEquals(expectedKind, message.kind)
        assertEquals(expectedBody, message.body)
        assertEquals(expectedSenderActorId, message.senderActorId)
        if (expectsRecipient) {
            assertTrue(message.recipientActorId != null)
            assertFalse(message.recipientActorId == message.senderActorId)
        } else {
            assertNull(message.recipientActorId)
        }
        assertEquals(TacMapChatDeliveryState.RECEIVED, message.deliveryState)
        assertNull(message.failureCode)
    }

    private fun requiredArgument(arguments: android.os.Bundle, names: List<String>): String {
        val value = names.firstNotNullOfOrNull { name ->
            arguments.getString(name)?.trim()?.takeIf(String::isNotEmpty)
        }
        assumeTrue(
            "Live TacMap Chat interop is opt-in; pass instrumentation argument ${names.first()}",
            value != null,
        )
        return requireNotNull(value)
    }

    /** Context boundary that gives production stores isolated files and preferences. */
    private class InteropSandboxContext(base: Context, stableId: String? = null) : ContextWrapper(base) {
        private val sandboxId = stableId ?: UUID.randomUUID().toString()
        private val root = File(base.cacheDir, "tacmap-chat-live-interop/$sandboxId")
        private val isolatedFilesDir = File(root, "files").apply {
            check(isDirectory || mkdirs()) { "Could not create Android chat interop sandbox" }
        }
        private val preferencePrefix = "tacmap_chat_live_interop_${sandboxId}_"
        private val backingPreferenceNames = Collections.synchronizedSet(mutableSetOf<String>())

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File = isolatedFilesDir

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val backingName = preferencePrefix + name
            backingPreferenceNames += backingName
            return baseContext.getSharedPreferences(backingName, mode)
        }

        fun close() {
            val names = synchronized(backingPreferenceNames) { backingPreferenceNames.toList() }
            names.forEach { name ->
                val preferences = baseContext.getSharedPreferences(name, Context.MODE_PRIVATE)
                check(preferences.edit().clear().commit()) {
                    "Could not flush and clear isolated preference $name"
                }
                baseContext.deleteSharedPreferences(name)
            }
            check(!root.exists() || root.deleteRecursively()) {
                "Could not remove Android chat interop sandbox"
            }
        }
    }

    private companion object {
        const val ANDROID_CALLSIGN = "Android Interop"
        const val INTEROP_TIMEOUT_MS = 45_000L
        const val POLL_INTERVAL_MS = 50L
        const val MAX_RUN_ID_UTF8_BYTES = 256

        val RELAY_ARGUMENTS = listOf("TACMAP_LIVE_RELAY", "tacmapLiveRelay")
        val JOIN_CODE_ARGUMENTS = listOf("TACMAP_LIVE_JOIN_CODE", "tacmapLiveJoinCode")
        val RUN_ID_ARGUMENTS = listOf("TACMAP_CHAT_INTEROP_RUN_ID", "tacmapChatInteropRunId")
    }
}
