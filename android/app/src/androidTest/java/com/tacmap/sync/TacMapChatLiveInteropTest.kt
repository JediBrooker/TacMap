package com.tacmap.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tacmap.drawings.DrawingStore
import com.tacmap.settings.OpsecSettings
import com.tacmap.util.SafeStore
import com.tacmap.waypoints.WaypointStore
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
    fun roomAndDirectMessagesRoundTripWithIosProductionManager() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val relay = requiredArgument(arguments = arguments, names = RELAY_ARGUMENTS)
        val joinCode = requiredArgument(arguments = arguments, names = JOIN_CODE_ARGUMENTS)
        val runId = requiredArgument(arguments = arguments, names = RUN_ID_ARGUMENTS)

        require(joinCode.startsWith("3:")) { "Live TacMap Chat interop requires a v3 join code" }
        require(runId.toByteArray(Charsets.UTF_8).size in 1..MAX_RUN_ID_UTF8_BYTES) {
            "TacMap Chat interop run ID must be 1..$MAX_RUN_ID_UTF8_BYTES UTF-8 bytes"
        }

        val targetContext = instrumentation.targetContext
        val sandbox = InteropSandboxContext(targetContext)
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

            val syncManager = SyncManager(
                waypointStore = WaypointStore(sandbox),
                drawingStore = DrawingStore(sandbox),
                parentScope = parentScope,
                context = sandbox,
            )
            manager = syncManager
            assertTrue(
                syncManager.updatePresenceConfig(
                    syncManager.presenceConfig.copy(
                        callsign = ANDROID_CALLSIGN,
                        shareLocation = false,
                    )
                )
            )
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
                    target = iosTarget,
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
                manager?.dispose()
                parentScope.cancel()
            } finally {
                OpsecSettings.shared = previousOpsecSettings
                SafeStore.keyProvider = previousKeyProvider
                SafeStore.migrationPolicy = previousMigrationPolicy
                fixedStoreKey.fill(0)
                sandbox.close()
            }
        }
    }

    private suspend fun waitUntil(
        manager: SyncManager,
        description: String,
        timeoutMs: Long = INTEROP_TIMEOUT_MS,
        predicate: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!predicate()) {
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError(
                    "Timed out waiting for $description; " +
                        "status=${manager.status.value}, " +
                        "chatReady=${manager.chatSessionReady.value}, " +
                        "recipients=${manager.chatRecipients.value.keys.sorted()}, " +
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
    private class InteropSandboxContext(base: Context) : ContextWrapper(base) {
        private val sandboxId = UUID.randomUUID().toString()
        private val root = File(base.cacheDir, "tacmap-chat-live-interop/$sandboxId")
        private val isolatedFilesDir = File(root, "files").apply {
            check(mkdirs()) { "Could not create Android chat interop sandbox" }
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
