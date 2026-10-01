package com.tacmap.sync

import com.tacmap.util.DataKey
import com.tacmap.util.SafeStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * S3-04 / S4-06 (fence table never pruned, chat dies after ~256 peer sessions)
 * and S4-07 (history capped by bytes but pruned by count, every write fails
 * once over 2 MiB). plans/04 section 15.
 */
class TacMapChatReplayBudgetTest {
    private val testKey = ByteArray(32) { (it + 3).toByte() }
    private lateinit var directory: File
    private val sealedLabels = mutableSetOf<String>()
    private val room = canonical32(1)

    @Before fun setUp() {
        directory = Files.createTempDirectory("tacmap-chat-budget").toFile()
        SafeStore.keyProvider = SafeStore.KeyProvider { testKey.copyOf() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = label in sealedLabels
            override fun markSealedOnly(label: String) { sealedLabels += label }
        }
    }

    @After fun tearDown() {
        SafeStore.keyProvider = SafeStore.KeyProvider { DataKey.key() }
        SafeStore.migrationPolicy = object : SafeStore.MigrationPolicy {
            override fun isSealedOnly(label: String) = DataKey.isStoreSealedOnly(label)
            override fun markSealedOnly(label: String) = DataKey.markStoreSealedOnly(label)
        }
        directory.deleteRecursively()
    }

    @Test
    fun peerReconnectsNoLongerFillTheFenceTable() {
        val store = TacMapChatHistoryStore.forTests(directory)
        val durable = HashMap<String, String>()
        store.durableSessionDomain = { durable[it] }
        assertTrue(store.open(room))
        val actor = canonical32(2)
        // one actor reconnecting 300 times, one message per session
        repeat(300) { i ->
            val sd = canonical32(1_000 + i)
            durable[actor] = sd // commitActorHello made this session current
            val result = store.acceptInbound(
                inbound(i, actor), actor, sd, canonical32(5_000 + i), "0000000000000001", canonical32(9_000 + i),
            )
            assertEquals("session $i", TacMapChatInboundResult.ACCEPTED, result)
        }
    }

    @Test
    fun fullTableOfLiveSessionsRejectsTheNewIdentityAsTableFull() {
        val store = TacMapChatHistoryStore.forTests(directory)
        val durable = HashMap<String, String>()
        store.durableSessionDomain = { durable[it] }
        assertTrue(store.open(room))
        repeat(ChatReplayPruner.MAX_FENCES) { i ->
            val actor = canonical32(100 + i)
            val sd = canonical32(2_000 + i)
            durable[actor] = sd
            assertEquals(TacMapChatInboundResult.ACCEPTED, store.acceptInbound(
                inbound(i, actor), actor, sd, canonical32(3_000 + i), "0000000000000001", canonical32(4_000 + i),
            ))
        }
        val newcomer = canonical32(77)
        durable[newcomer] = canonical32(78)
        assertEquals(TacMapChatInboundResult.REPLAY_TABLE_FULL, store.acceptInbound(
            inbound(900, newcomer), newcomer, canonical32(78), canonical32(79), "0000000000000001", canonical32(80),
        ))
        // one actor moves to a new session: its old fence is now dead weight and makes room
        durable[canonical32(100)] = canonical32(81)
        assertEquals(TacMapChatInboundResult.ACCEPTED, store.acceptInbound(
            inbound(901, newcomer), newcomer, canonical32(78), canonical32(79), "0000000000000001", canonical32(82),
        ))
    }

    @Test
    fun oldExactRetryBeyondTheKeptFingerprintsIsRejectedNotDuplicate() {
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        val actor = canonical32(2)
        val sd = canonical32(3)
        val kid = canonical32(4)
        repeat(20) { i ->
            assertEquals(TacMapChatInboundResult.ACCEPTED, store.acceptInbound(
                inbound(i, actor), actor, sd, kid, counter(i + 1), canonical32(200 + i),
            ))
        }
        assertEquals(TacMapChatInboundResult.DUPLICATE, store.acceptInbound(
            inbound(19, actor), actor, sd, kid, counter(20), canonical32(219),
        ))
        // fingerprint #1 fell out of the 16 we keep
        assertEquals(TacMapChatInboundResult.REPLAY_REJECTED, store.acceptInbound(
            inbound(0, actor), actor, sd, kid, counter(1), canonical32(200),
        ))
    }

    @Test
    fun historyOverTwoMegabytesPrunesOldestInsteadOfBreakingChat() {
        val store = TacMapChatHistoryStore.forTests(directory)
        assertTrue(store.open(room))
        val actor = canonical32(2)
        val sd = canonical32(3)
        val kid = canonical32(4)
        // quotes JSON-escape to two bytes each, so every message is ~8 KB encoded
        val heavy = "\"".repeat(4_000)
        repeat(300) { i ->
            val message = inbound(i, actor).copy(body = heavy)
            assertEquals("message $i", TacMapChatInboundResult.ACCEPTED, store.acceptInbound(
                message, actor, sd, kid, counter(i + 1), canonical32(400 + i),
            ))
        }
        val kept = store.messages.value
        assertTrue("${kept.size} kept", kept.size in 150..299)
        assertEquals(messageId(299), kept.last().id)
        // and it still writes and reopens
        assertTrue(store.append(outgoing(1_000)))
        store.close()
        assertTrue(store.open(room))
        assertEquals(messageId(1_000), store.messages.value.last().id)
    }

    private fun counter(n: Int) = VersionStamp.counterHex16(n.toLong())

    private fun messageId(seed: Int): String = SyncIdentity.urlB64(ByteArray(16) { i ->
        if (i < 4) (seed shr (8 * i)).toByte() else (i * 13).toByte()
    })

    private fun inbound(seed: Int, sender: String) = TacMapChatMessage(
        id = messageId(seed),
        roomId = room,
        scope = TacMapChatScope.ROOM,
        senderActorId = sender,
        senderName = "Alpha",
        recipientActorId = null,
        recipientName = null,
        kind = TacMapChatContentKind.TEXT,
        body = "Route clear $seed",
        sentAtMilliseconds = 1_720_000_000_000,
        isOutgoing = false,
        deliveryState = TacMapChatDeliveryState.RECEIVED,
    )

    private fun outgoing(seed: Int) = inbound(seed, canonical32(9)).copy(
        isOutgoing = true,
        deliveryState = TacMapChatDeliveryState.ROUTED,
    )

    private fun canonical32(seed: Int): String = SyncIdentity.urlB64(ByteArray(32) { i ->
        if (i < 4) (seed shr (8 * i)).toByte() else (i * 7).toByte()
    })
}
