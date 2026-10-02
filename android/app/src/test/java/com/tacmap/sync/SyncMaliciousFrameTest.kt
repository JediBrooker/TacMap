package com.tacmap.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SEC-006 regression: frame-level admission (size, binary, close gate, budgets).
 * The corpus itself (testdata/malicious_frames.json) runs through the real
 * handler in SyncMaliciousFrameHandlerTest.
 */
class SyncMaliciousFrameTest {

    private fun fixtureText(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            val f = File(dir, "testdata/$name")
            if (f.exists()) return f.readText()
            dir = dir?.parentFile
        }
        error("Could not locate testdata/$name from ${System.getProperty("user.dir")}")
    }

    data class Case(val name: String, val frame: String)

    private val corpus: List<Case> by lazy {
        val root = Json.parseToJsonElement(fixtureText("malicious_frames.json")).jsonObject
        root["cases"]!!.jsonArray.map { el ->
            val obj = el.jsonObject
            Case(obj["name"]!!.jsonPrimitive.content, obj["frame"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun corpusLoads() {
        assertTrue("malicious_frames.json should have cases", corpus.isNotEmpty())
    }

    // The version and parse checks that used to be re-implemented here now run
    // through the real SyncManager handler in SyncMaliciousFrameHandlerTest (S6-09).

    @Test
    fun unicodeCeilingUsesUtf8WireBytesRatherThanCharacterCount() {
        val emoji = "😀".repeat(SyncInboundFramePolicy.MAX_FRAME_BYTES / 4 + 1)
        assertTrue(emoji.length < SyncInboundFramePolicy.MAX_FRAME_BYTES)
        assertTrue(emoji.toByteArray(Charsets.UTF_8).size > SyncInboundFramePolicy.MAX_FRAME_BYTES)
        assertEquals(
            SyncInboundFrameDecision.Reject(SyncInboundFrameRejection.OVERSIZED),
            SyncInboundFramePolicy.inspectText(emoji),
        )
    }

    @Test
    fun binaryFramesAreRejectedBeforeParsingAndSizeStillWins() {
        assertEquals(
            SyncInboundFrameDecision.Reject(SyncInboundFrameRejection.BINARY),
            SyncInboundFramePolicy.inspectBinary(12),
        )
        assertEquals(
            SyncInboundFrameDecision.Reject(SyncInboundFrameRejection.OVERSIZED),
            SyncInboundFramePolicy.inspectBinary(SyncInboundFramePolicy.MAX_FRAME_BYTES + 1),
        )
    }

    @Test
    fun hostileFrameClaimsOnlyOneClosePerSocketGeneration() {
        val gate = SyncInboundFrameCloseGate()
        assertTrue(gate.claimClose(41L))
        assertFalse(gate.claimClose(41L))
        assertTrue(gate.claimClose(42L))
    }

    // These used to pin a single 200 frame / 4 MiB live budget, the same as one
    // relay sender's allowance (S2-04). plans/04 section 12 splits it into room,
    // self-response, initial and background budgets.
    @Test
    fun liveRoomBudgetScalesWithSessionsCountsEveryFrameAndResetsByWindow() {
        val budget = SyncReceiveBudget()
        val room = SyncReceiveBudget.Bucket.ROOM
        val live = SyncReceiveBudget.Phase.LIVE
        val limit = SyncReceiveBudget.roomFrameLimit(sessions = 0)
        assertEquals(600, limit)
        repeat(limit) { assertTrue(budget.admit(7L, 1, live, room, 0, nowMs = 100L)) }
        assertFalse(budget.admit(7L, 1, live, room, 0, nowMs = 100L))
        // own acks have their own window and don't eat the room budget
        assertTrue(budget.admit(7L, 1, live, SyncReceiveBudget.Bucket.SELF_RESPONSE, 0, nowMs = 100L))
        assertTrue(budget.admit(7L, SyncReceiveBudget.roomByteLimit(0).toInt(), live, room, 0, nowMs = 10_100L))
        assertFalse(budget.admit(7L, 1, live, room, 0, nowMs = 10_100L))
        assertTrue(budget.admit(8L, 1, live, room, 0, nowMs = 10_100L))
        assertEquals(SyncReceiveBudget.ROOM_MAX_FRAMES, SyncReceiveBudget.roomFrameLimit(sessions = 1_000))
    }

    @Test
    fun selfResponseFramesAreClassifiedByTypeOnly() {
        for (t in listOf("op-ack", "op-nack", "chat-ack", "chat-nack", "chat-key-ack", "chat-key-nack", "hello-ack")) {
            assertEquals(t, SyncReceiveBudget.Bucket.SELF_RESPONSE, SyncReceiveBudget.bucketFor(t))
        }
        for (t in listOf("put", "loc", "hello", "chat", "snapshot", null)) {
            assertEquals(SyncReceiveBudget.Bucket.ROOM, SyncReceiveBudget.bucketFor(t))
        }
    }

    @Test
    fun initialReceiveBudgetCountsMalformedFramesThenResetsForLivePhase() {
        val budget = SyncReceiveBudget()
        val room = SyncReceiveBudget.Bucket.ROOM
        assertTrue(budget.admit(9L, SyncReceiveBudget.INITIAL_MAX_BYTES.toInt(), SyncReceiveBudget.Phase.INITIAL, room, 0, 1L))
        assertFalse(budget.admit(9L, 1, SyncReceiveBudget.Phase.INITIAL, room, 0, 2L))
        assertTrue(budget.admit(9L, 1, SyncReceiveBudget.Phase.LIVE, room, 0, 2L))
    }

    @Test
    fun veryLargeAsciiIsRejectedByCharacterGuard() {
        val huge = "A".repeat(SyncInboundFramePolicy.MAX_FRAME_BYTES * 4)
        assertEquals(
            SyncInboundFrameDecision.Reject(SyncInboundFrameRejection.OVERSIZED),
            SyncInboundFramePolicy.inspectText(huge),
        )
    }
}
