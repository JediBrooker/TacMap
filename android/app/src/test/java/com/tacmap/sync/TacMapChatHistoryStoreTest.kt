package com.tacmap.sync

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import com.tacmap.util.SealedEnvelope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TacMapChatHistoryStoreTest {
    private val testKey = ByteArray(32) { (it + 1).toByte() }
    private lateinit var directory: File
    private val sealedLabels = mutableSetOf<String>()

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("tacmap-chat-history").toFile()
        SafeStore.keyProvider = SafeStore.KeyProvider { testKey.copyOf() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
    }

    @After
    fun tearDown() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
        directory.deleteRecursively()
    }

    @Test
    fun historyIsSealedAndRoundTrips() {
        val room = canonical32(1)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        val message = message(room, canonicalMessageId(2), outgoing = true).copy(
            deliveryState = TacMapChatDeliveryState.ROUTED,
        )
        assertTrue(store.append(message))
        val file = historyFile(room)
        assertTrue(SealedEnvelope.isSealedFile(file.readBytes()))
        assertFalse(file.readText().contains(message.body))

        store.close()
        assertTrue(store.open(room))
        assertEquals(listOf(message), store.messages.value)
    }

    @Test
    fun legacyRoomIdFilenameMigratesWithoutDataLossOrIdentifierLeak() {
        val room = canonical32(16)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        val outgoing = message(room, canonicalMessageId(17), outgoing = true).copy(
            body = "LEGACY CHAT HISTORY",
            deliveryState = TacMapChatDeliveryState.ROUTED,
        )
        assertTrue(store.append(outgoing))
        store.close()

        val current = historyFile(room)
        val legacy = legacyHistoryFile(room)
        Files.move(current.toPath(), legacy.toPath())
        val legacyQuarantine = File(directory, "${legacy.name}.corrupt-123")
        val quarantinedBytes = "preserve-quarantine".toByteArray()
        legacyQuarantine.writeBytes(quarantinedBytes)
        assertFalse(current.exists())

        val migrated = TacMapChatHistoryStore.forTests(directory)
        assertTrue(migrated.open(room))
        assertEquals(outgoing.body, migrated.messages.value.single().body)
        assertTrue(current.exists())
        assertFalse(legacy.exists())
        assertFalse(current.name.contains(room))
        val currentQuarantine = File(directory, "${current.name}.corrupt-123")
        assertTrue(currentQuarantine.readBytes().contentEquals(quarantinedBytes))
        assertFalse(legacyQuarantine.exists())
    }

    @Test
    fun lockedNamingKeyLeavesLegacyHistoryUntouched() {
        val room = canonical32(18)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        assertTrue(store.append(message(room, canonicalMessageId(19), outgoing = true)))
        store.close()
        val current = historyFile(room)
        val legacy = legacyHistoryFile(room)
        Files.move(current.toPath(), legacy.toPath())
        val before = legacy.readBytes()

        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        val locked = TacMapChatHistoryStore.forTests(directory)
        assertFalse(locked.open(room))
        assertEquals(TacMapChatHistoryAvailability.LOCKED, locked.availability.value)
        assertTrue(legacy.readBytes().contentEquals(before))
        assertFalse(current.exists())
        SafeStore.keyProvider = SafeStore.KeyProvider { testKey.copyOf() }
    }

    @Test
    fun currentFileWinsWhileLegacyCopyIsPreservedOpaque() {
        val room = canonical32(20)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        assertTrue(store.append(message(room, canonicalMessageId(20), outgoing = true)))
        store.close()
        val current = historyFile(room)
        val legacy = legacyHistoryFile(room)
        val legacyBytes = current.readBytes()
        legacy.writeBytes(legacyBytes)

        val reopened = TacMapChatHistoryStore.forTests(directory)
        assertTrue(reopened.open(room))
        assertTrue(current.exists())
        assertFalse(legacy.exists())
        val conflict = requireNotNull(directory.listFiles()?.singleOrNull {
            it.name.contains(".legacy-conflict-")
        })
        assertFalse(conflict.name.contains(room))
        assertTrue(conflict.readBytes().contentEquals(legacyBytes))
    }

    @Test
    fun pendingOutgoingMessageReopensAsPersistedFailure() {
        val room = canonical32(21)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        val pending = message(room, canonicalMessageId(22), outgoing = true)
        assertTrue(store.append(pending))

        store.close()
        assertTrue(store.open(room))
        assertEquals(TacMapChatDeliveryState.FAILED, store.messages.value.single().deliveryState)
        assertEquals("interrupted", store.messages.value.single().failureCode)

        store.close()
        assertTrue(store.open(room))
        assertEquals(TacMapChatDeliveryState.FAILED, store.messages.value.single().deliveryState)
    }

    @Test
    fun plaintextHistoryIsRejectedWithoutMigration() {
        val room = canonical32(3)
        directory.mkdirs()
        historyFile(room).writeText("{\"version\":1,\"messages\":[],\"replay\":[]}")
        val store = TacMapChatHistoryStore.forTests(directory)

        assertFalse(store.open(room))
        assertEquals(TacMapChatHistoryAvailability.CORRUPT, store.availability.value)
        assertTrue(historyFile(room).exists())
    }

    @Test
    fun lockedKeyFailsClosedEvenForFreshRoom() {
        SafeStore.keyProvider = SafeStore.KeyProvider { throw DataKey.LockedException() }
        val store = TacMapChatHistoryStore.forTests(directory)

        assertFalse(store.open(canonical32(4)))
        assertEquals(TacMapChatHistoryAvailability.LOCKED, store.availability.value)
        assertFalse(store.append(message(canonical32(4), canonicalMessageId(1), true)))
    }

    @Test
    fun inactiveMigrationFailureDoesNotBlockTheRequestedRoom() {
        val activeRoom = canonical32(40)
        val inactiveRoom = canonical32(41)
        val external = Files.createTempFile("inactive-chat-symlink", ".json").toFile()
        external.writeText("outside")
        try {
            Files.createSymbolicLink(
                File(directory, "$inactiveRoom.json").toPath(),
                external.toPath(),
            )
            val store = TacMapChatHistoryStore.forTests(directory)
            assertTrue(store.open(activeRoom))
            assertTrue(runCatching {
                SyncLocalStore.migrateLegacyFiles(
                    directory,
                    SyncIdentity.LocalStoreDomain.CHAT,
                    testKey,
                )
            }.isFailure)
            assertEquals("outside", external.readText())
        } finally {
            external.delete()
        }
    }

    @Test
    fun inboundMessageAndReplayWatermarkCommitAtomicallyAndSurviveRestart() {
        val room = canonical32(5)
        val actor = canonical32(6)
        val session = canonical32(7)
        val kid = canonical32(8)
        val fingerprint = canonical32(9)
        val incoming = message(room, canonicalMessageId(10), outgoing = false, sender = actor)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        assertEquals(
            TacMapChatInboundResult.ACCEPTED,
            store.acceptInbound(incoming, actor, session, kid, "0000000000000001", fingerprint),
        )
        assertEquals(1, store.unreadCount.value)
        assertEquals(
            TacMapChatInboundResult.DUPLICATE,
            store.acceptInbound(incoming, actor, session, kid, "0000000000000001", fingerprint),
        )
        assertEquals(
            TacMapChatInboundResult.REPLAY_REJECTED,
            store.acceptInbound(
                incoming,
                actor,
                session,
                kid,
                "0000000000000002",
                canonical32(15),
            ),
        )

        store.close()
        assertTrue(store.open(room))
        assertEquals(listOf(incoming), store.messages.value)
        assertEquals(1, store.unreadCount.value)
        assertEquals(
            TacMapChatInboundResult.DUPLICATE,
            store.acceptInbound(incoming, actor, session, kid, "0000000000000001", fingerprint),
        )
        assertEquals(
            TacMapChatInboundResult.REPLAY_REJECTED,
            store.acceptInbound(
                incoming.copy(id = canonicalMessageId(11)),
                actor,
                session,
                kid,
                "0000000000000001",
                canonical32(12),
            ),
        )
    }

    @Test
    fun acceptedRoomAndDirectMessagesAreUnreadAndReadClearingIsConversationScoped() {
        val room = canonical32(30)
        val actor = canonical32(31)
        val localActor = canonical32(32)
        val secondActor = canonical32(39)
        val session = canonical32(33)
        val kid = canonical32(34)
        val roomMessage = message(
            room = room,
            id = canonicalMessageId(35),
            outgoing = false,
            sender = actor,
            kind = TacMapChatContentKind.REPORT,
        )
        val directMessage = message(
            room = room,
            id = canonicalMessageId(36),
            outgoing = false,
            sender = actor,
            scope = TacMapChatScope.DIRECT,
            recipientActorId = localActor,
        )
        val directTarget = TacMapChatTarget.SelectedUnit(actor, "", "", "Alpha")
        val secondDirectTarget = TacMapChatTarget.SelectedUnit(secondActor, "", "", "Bravo")
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))

        assertEquals(
            TacMapChatInboundResult.ACCEPTED,
            store.acceptInbound(
                roomMessage,
                actor,
                session,
                kid,
                "0000000000000001",
                canonical32(37),
            ),
        )
        assertEquals(
            TacMapChatInboundResult.DUPLICATE,
            store.acceptInbound(
                roomMessage,
                actor,
                session,
                kid,
                "0000000000000001",
                canonical32(37),
            ),
        )
        assertEquals(
            TacMapChatInboundResult.ACCEPTED,
            store.acceptInbound(
                directMessage,
                actor,
                session,
                kid,
                "0000000000000002",
                canonical32(38),
            ),
        )
        assertTrue(store.append(message(room, canonicalMessageId(40), outgoing = true)))
        assertEquals(
            TacMapChatInboundResult.REPLAY_REJECTED,
            store.acceptInbound(
                directMessage.copy(id = canonicalMessageId(41)),
                actor,
                session,
                kid,
                "0000000000000002",
                canonical32(42),
            ),
        )
        val secondDirect = message(
            room = room,
            id = canonicalMessageId(43),
            outgoing = false,
            sender = secondActor,
            scope = TacMapChatScope.DIRECT,
            recipientActorId = localActor,
        )
        assertEquals(
            TacMapChatInboundResult.ACCEPTED,
            store.acceptInbound(
                secondDirect,
                secondActor,
                canonical32(44),
                canonical32(45),
                "0000000000000001",
                canonical32(46),
            ),
        )
        assertEquals(3, store.unreadCount.value)
        assertEquals(1, store.unreadCount(TacMapChatTarget.EntireRoom))
        assertEquals(1, store.unreadCount(directTarget))
        assertEquals(1, store.unreadCount(secondDirectTarget))

        assertTrue(store.markRead(TacMapChatTarget.EntireRoom))
        assertEquals(2, store.unreadCount.value)
        assertEquals(0, store.unreadCount(TacMapChatTarget.EntireRoom))
        assertEquals(1, store.unreadCount(directTarget))
        assertEquals(1, store.unreadCount(secondDirectTarget))

        assertTrue(store.markRead(directTarget))
        assertEquals(1, store.unreadCount.value)
        assertEquals(0, store.unreadCount(directTarget))
        assertEquals(1, store.unreadCount(secondDirectTarget))

        store.close()
        assertTrue(store.open(room))
        assertEquals(1, store.unreadCount.value)
        assertEquals(1, store.unreadCount(secondDirectTarget))
        assertTrue(store.markRead(secondDirectTarget))
        assertEquals(0, store.unreadCount.value)

        store.close()
        assertTrue(store.open(room))
        assertEquals(0, store.unreadCount.value)
    }

    @Test
    fun failedReadPersistenceKeepsUnreadBadgeSet() {
        val room = canonical32(40)
        val actor = canonical32(41)
        val incoming = message(room, canonicalMessageId(42), outgoing = false, sender = actor)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        assertEquals(
            TacMapChatInboundResult.ACCEPTED,
            store.acceptInbound(
                incoming,
                actor,
                canonical32(43),
                canonical32(44),
                "0000000000000001",
                canonical32(45),
            ),
        )
        SafeStore.keyProvider = SafeStore.KeyProvider { error("disk/key failure") }

        assertFalse(store.markRead(TacMapChatTarget.EntireRoom))
        assertEquals(1, store.unreadCount.value)
        assertEquals(TacMapChatHistoryAvailability.UNAVAILABLE, store.availability.value)
    }

    @Test
    fun failedInboundPersistencePublishesNeitherMessageNorUnreadMetadata() {
        val room = canonical32(47)
        val actor = canonical32(48)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        SafeStore.keyProvider = SafeStore.KeyProvider { error("disk/key failure") }

        assertEquals(
            TacMapChatInboundResult.STORE_UNAVAILABLE,
            store.acceptInbound(
                message(room, canonicalMessageId(49), outgoing = false, sender = actor),
                actor,
                canonical32(50),
                canonical32(51),
                "0000000000000001",
                canonical32(52),
            ),
        )
        assertTrue(store.messages.value.isEmpty())
        assertEquals(0, store.unreadCount.value)
    }

    @Test
    fun unreadMetadataIsRoomScopedAndLockOnlyClearsItsPublishedCount() {
        val firstRoom = canonical32(53)
        val secondRoom = canonical32(54)
        val actor = canonical32(55)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(firstRoom))
        assertEquals(
            TacMapChatInboundResult.ACCEPTED,
            store.acceptInbound(
                message(firstRoom, canonicalMessageId(56), outgoing = false, sender = actor),
                actor,
                canonical32(57),
                canonical32(58),
                "0000000000000001",
                canonical32(59),
            ),
        )
        assertEquals(1, store.unreadCount.value)

        assertTrue(store.open(secondRoom))
        assertEquals(0, store.unreadCount.value)
        assertTrue(store.open(firstRoom))
        assertEquals(1, store.unreadCount.value)
        store.lock()
        assertEquals(0, store.unreadCount.value)
        assertEquals(TacMapChatHistoryAvailability.LOCKED, store.availability.value)

        assertTrue(store.open(firstRoom))
        assertEquals(1, store.unreadCount.value)
    }

    @Test
    fun legacySealedHistoryWithoutUnreadMetadataTreatsExistingMessagesAsRead() {
        val room = canonical32(50)
        val legacyMessage = message(
            room,
            canonicalMessageId(51),
            outgoing = false,
            sender = canonical32(52),
        )
        val legacyJson = Json { encodeDefaults = false }.encodeToString(
            TacMapChatHistoryEnvelope(
                version = 1,
                messages = listOf(legacyMessage),
                replay = emptyList(),
            )
        )
        assertFalse(legacyJson.contains("unreadInboundMessageIds"))
        directory.mkdirs()
        SafeStore.writeAtomically(
            historyFile(room),
            "sync/chat/$room",
            legacyJson,
        )

        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        assertEquals(listOf(legacyMessage), store.messages.value)
        assertEquals(0, store.unreadCount.value)
        val migrated = SafeStore.readOrQuarantine(
            historyFile(room),
            "sync/chat/$room",
        ) { Json.decodeFromString<TacMapChatHistoryEnvelope>(it) }
        val migratedEnvelope = when (migrated) {
            is SafeStore.LoadResult.Loaded -> migrated.value
            else -> error("Expected migrated sealed history, got $migrated")
        }
        assertEquals(2, migratedEnvelope.version)
    }

    @Test
    fun v2HistoryMissingRequiredUnreadFieldFailsClosed() {
        val room = canonical32(69)
        directory.mkdirs()
        SafeStore.writeAtomically(
            historyFile(room),
            "sync/chat/$room",
            """{"version":2,"messages":[],"replay":[]}""",
        )

        val store = TacMapChatHistoryStore.forTests(directory)
        assertFalse(store.open(room))
        assertEquals(TacMapChatHistoryAvailability.CORRUPT, store.availability.value)
        assertEquals(0, store.unreadCount.value)
    }

    @Test
    fun v1HistoryWithV2OnlyUnreadFieldFailsClosed() {
        val room = canonical32(70)
        directory.mkdirs()
        SafeStore.writeAtomically(
            historyFile(room),
            "sync/chat/$room",
            """{"version":1,"messages":[],"replay":[],"unreadInboundMessageIds":[]}""",
        )

        val store = TacMapChatHistoryStore.forTests(directory)
        assertFalse(store.open(room))
        assertEquals(TacMapChatHistoryAvailability.CORRUPT, store.availability.value)
        assertEquals(0, store.unreadCount.value)
    }

    @Test
    fun v2HistoryRejectsUnreadIdsThatDoNotNameRetainedInboundMessages() {
        val room = canonical32(60)
        val retained = message(
            room,
            canonicalMessageId(61),
            outgoing = false,
            sender = canonical32(62),
        )
        val malformed = Json { encodeDefaults = true }.encodeToString(
            TacMapChatHistoryEnvelope(
                version = 2,
                messages = listOf(retained),
                replay = emptyList(),
                unreadInboundMessageIds = listOf(canonicalMessageId(63)),
            )
        )
        directory.mkdirs()
        SafeStore.writeAtomically(
            historyFile(room),
            "sync/chat/$room",
            malformed,
        )

        val store = TacMapChatHistoryStore.forTests(directory)
        assertFalse(store.open(room))
        assertEquals(TacMapChatHistoryAvailability.CORRUPT, store.availability.value)
        assertEquals(0, store.unreadCount.value)
    }

    @Test
    fun unreadRetentionDropsEvictedAndOutgoingMessageIds() {
        val room = canonical32(64)
        val inbound = message(
            room,
            canonicalMessageId(65),
            outgoing = false,
            sender = canonical32(66),
        )
        val outgoing = message(room, canonicalMessageId(67), outgoing = true)

        assertEquals(
            listOf(inbound.id),
            retainedUnreadInboundMessageIds(
                retainedMessages = listOf(inbound, outgoing),
                candidateIds = setOf(inbound.id, outgoing.id, canonicalMessageId(68)),
            ),
        )
    }

    @Test
    fun storageFailurePublishesNoPhantomMessageAndDisablesFurtherWrites() {
        val room = canonical32(13)
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        SafeStore.keyProvider = SafeStore.KeyProvider { error("disk/key failure") }

        assertFalse(store.append(message(room, canonicalMessageId(14), true)))
        assertTrue(store.messages.value.isEmpty())
        assertEquals(TacMapChatHistoryAvailability.UNAVAILABLE, store.availability.value)
    }

    private fun message(
        room: String,
        id: String,
        outgoing: Boolean,
        sender: String = canonical32(20),
        scope: TacMapChatScope = TacMapChatScope.ROOM,
        recipientActorId: String? = null,
        kind: TacMapChatContentKind = TacMapChatContentKind.TEXT,
    ) = TacMapChatMessage(
        id = id,
        roomId = room,
        scope = scope,
        senderActorId = sender,
        senderName = "Alpha · ${sender.takeLast(6)}",
        recipientActorId = recipientActorId,
        recipientName = recipientActorId?.let { "This device" },
        kind = kind,
        body = "Route clear",
        sentAtMilliseconds = 1_720_000_000_000,
        isOutgoing = outgoing,
        deliveryState = if (outgoing) TacMapChatDeliveryState.SENT else TacMapChatDeliveryState.RECEIVED,
    )

    private fun canonical32(seed: Int): String =
        SyncIdentity.urlB64(ByteArray(32) { (seed + it).toByte() })

    private fun canonicalMessageId(seed: Int): String =
        SyncIdentity.urlB64(ByteArray(16) { (seed + it).toByte() })

    private fun historyFile(room: String): File = File(
        directory,
        requireNotNull(SyncIdentity.localStoreFileName(
            testKey,
            room,
            SyncIdentity.LocalStoreDomain.CHAT,
        )),
    )

    private fun legacyHistoryFile(room: String): File = File(directory, "$room.json")
}
