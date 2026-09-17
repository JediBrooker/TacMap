package com.tacmap.sync

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SnapshotGateTest {
    private fun gate() = V2SnapshotGate(maxItems = 10, maxAggregateBytes = 10_000)
    private fun frame(type: String, body: JSONObject.() -> Unit = {}) =
        JSONObject().put("t", type).apply(body)

    @Test
    fun emptySnapshotDoesNotReleaseOutboundUntilMatchingEndFence() {
        val socket = Any()
        val gate = gate().apply { start(socket, 7) }

        assertEquals(V2SnapshotGateEvent.Began,
            gate.accept(socket, 7, frame("snapshot-begin") { put("seq", 3) }, 20))
        assertEquals(V2SnapshotGateEvent.PageAccepted,
            gate.accept(socket, 7, frame("snapshot") {
                put("items", JSONArray()); put("more", false)
            }, 20))

        val complete = gate.accept(
            socket, 7, frame("snapshot-end") { put("seq", 3) }, 20,
        ) as V2SnapshotGateEvent.Completed
        assertEquals(3, complete.batch.sequence)
        assertTrue(complete.batch.records.isEmpty())
        assertTrue(complete.batch.members.isEmpty())
    }

    @Test
    fun multipageSnapshotBuffersEverythingAndReleasesExactlyOnceAtEnd() {
        val socket = Any()
        val gate = gate().apply { start(socket, 11) }
        gate.accept(socket, 11, frame("snapshot-begin") { put("seq", 9) }, 10)

        assertEquals(V2SnapshotGateEvent.PageAccepted,
            gate.accept(socket, 11, frame("snapshot") {
                put("items", JSONArray().put(JSONObject().put("id", "one")))
                put("members", JSONArray().put(JSONObject().put("clientId", "peer")))
                put("more", true)
            }, 30))
        assertEquals(V2SnapshotGateEvent.PageAccepted,
            gate.accept(socket, 11, frame("snapshot") {
                put("items", JSONArray().put(JSONObject().put("id", "two")))
                put("more", false)
            }, 30))

        val complete = gate.accept(
            socket, 11, frame("snapshot-end") { put("seq", 9) }, 10,
        ) as V2SnapshotGateEvent.Completed
        assertEquals(listOf("one", "two"), complete.batch.records.map { it.getString("id") })
        assertEquals(listOf("peer"), complete.batch.members.map { it.getString("clientId") })
        assertEquals(V2SnapshotGateEvent.Ignored,
            gate.accept(socket, 11, frame("snapshot-end") { put("seq", 9) }, 10))
    }

    @Test
    fun malformedOrOutOfOrderSnapshotsFailClosed() {
        val socket = Any()

        val pageFirst = gate().apply { start(socket, 1) }
        assertTrue(pageFirst.accept(socket, 1, frame("snapshot") {
            put("items", JSONArray()); put("more", false)
        }, 10) is V2SnapshotGateEvent.Rejected)

        val earlyEnd = gate().apply { start(socket, 2) }
        earlyEnd.accept(socket, 2, frame("snapshot-begin") { put("seq", 1) }, 10)
        assertTrue(earlyEnd.accept(socket, 2, frame("snapshot-end") { put("seq", 1) }, 10)
            is V2SnapshotGateEvent.Rejected)

        val malformed = gate().apply { start(socket, 3) }
        malformed.accept(socket, 3, frame("snapshot-begin") { put("seq", 1) }, 10)
        assertTrue(malformed.accept(socket, 3, frame("snapshot") {
            put("items", JSONArray()); put("more", "false")
        }, 10) is V2SnapshotGateEvent.Rejected)
    }

    @Test
    fun timeoutIsBoundToSocketGenerationAndFreshReconnectCanComplete() {
        val oldSocket = Any()
        val newSocket = Any()
        val gate = gate().apply { start(oldSocket, 4) }
        gate.accept(oldSocket, 4, frame("snapshot-begin") { put("seq", 1) }, 10)
        assertTrue(gate.timeout(oldSocket, 4) is V2SnapshotGateEvent.Rejected)

        gate.start(newSocket, 5)
        assertEquals(V2SnapshotGateEvent.Ignored, gate.timeout(oldSocket, 4))
        gate.accept(newSocket, 5, frame("snapshot-begin") { put("seq", 2) }, 10)
        gate.accept(newSocket, 5, frame("snapshot") {
            put("items", JSONArray()); put("more", false)
        }, 10)
        assertTrue(gate.accept(newSocket, 5, frame("snapshot-end") { put("seq", 2) }, 10)
            is V2SnapshotGateEvent.Completed)
    }

    @Test
    fun staleSocketEndCannotReleaseReplacementGeneration() {
        val oldSocket = Any()
        val newSocket = Any()
        val gate = gate().apply { start(oldSocket, 20) }
        gate.accept(oldSocket, 20, frame("snapshot-begin") { put("seq", 8) }, 10)
        gate.accept(oldSocket, 20, frame("snapshot") {
            put("items", JSONArray()); put("more", false)
        }, 10)

        gate.start(newSocket, 21)
        assertEquals(V2SnapshotGateEvent.Ignored,
            gate.accept(oldSocket, 20, frame("snapshot-end") { put("seq", 8) }, 10))
        assertTrue(gate.timeout(newSocket, 21) is V2SnapshotGateEvent.Rejected)
    }
}

