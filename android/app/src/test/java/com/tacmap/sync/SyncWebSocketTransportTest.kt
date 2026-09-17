package com.tacmap.sync

import org.java_websocket.WebSocket
import org.java_websocket.WebSocketAdapter
import org.java_websocket.WebSocketImpl
import org.java_websocket.enums.Role
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.Handshakedata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SyncWebSocketTransportTest {
    @Test
    fun declaredOversizeFrameIsRejectedFromHeaderBeforePayloadAllocation() {
        val draft = SyncWebSocketTransport.boundedDraft().apply { setParseMode(Role.CLIENT) }
        val headerOnly = ByteBuffer.allocate(10).apply {
            put(0x81.toByte())
            put(0x7f.toByte())
            putLong(SyncWebSocketTransport.MAX_FRAME_BYTES.toLong() + 1)
            flip()
        }

        assertThrows(LimitExceededException::class.java) {
            draft.translateFrame(headerOnly)
        }
    }

    @Test
    fun fragmentedMessageAggregateCannotExceedTheSameCeiling() {
        val socket = WebSocketImpl(noOpListener, SyncWebSocketTransport.boundedDraft())
        // Exercise the clone WebSocketImpl actually installs, not only the
        // prototype supplied by TacMap's transport.
        val draft = (socket.draft as org.java_websocket.drafts.Draft_6455).apply {
            setParseMode(Role.CLIENT)
        }
        val firstPayload = ByteArray(400_000) { 'a'.code.toByte() }
        val intermediatePayload = ByteArray(400_000) { 'b'.code.toByte() }
        val overflowingIntermediatePayload = ByteArray(400_000) { 'c'.code.toByte() }

        draft.translateFrame(frame(opcode = 0x1, final = false, firstPayload))
            .forEach { draft.processFrame(socket, it) }
        draft.translateFrame(frame(opcode = 0x0, final = false, intermediatePayload))
            .forEach { draft.processFrame(socket, it) }
        assertThrows(LimitExceededException::class.java) {
            // This deliberately remains non-final. The upstream 1.6.0 draft
            // checks only the first and final fragments, so this regression
            // proves TacMap rejects a continuation flood as it arrives.
            draft.translateFrame(frame(opcode = 0x0, final = false, overflowingIntermediatePayload))
                .forEach { draft.processFrame(socket, it) }
        }
    }

    @Test
    fun emptyContinuationFloodCannotGrowTheRetainedFragmentList() {
        val socket = WebSocketImpl(noOpListener, SyncWebSocketTransport.boundedDraft())
        val draft = (socket.draft as org.java_websocket.drafts.Draft_6455).apply {
            setParseMode(Role.CLIENT)
        }
        draft.translateFrame(frame(opcode = 0x1, final = false, ByteArray(0)))
            .forEach { draft.processFrame(socket, it) }
        repeat(SyncWebSocketTransport.MAX_FRAGMENT_COUNT - 1) {
            draft.translateFrame(frame(opcode = 0x0, final = false, ByteArray(0)))
                .forEach { draft.processFrame(socket, it) }
        }

        assertThrows(InvalidDataException::class.java) {
            draft.translateFrame(frame(opcode = 0x0, final = false, ByteArray(0)))
                .forEach { draft.processFrame(socket, it) }
        }
    }

    @Test
    fun websocketControlFrameFloodIsRateLimitedBeforeAutoResponsesAccumulate() {
        val socket = WebSocketImpl(noOpListener, SyncWebSocketTransport.boundedDraft())
        val draft = (socket.draft as org.java_websocket.drafts.Draft_6455).apply {
            setParseMode(Role.CLIENT)
        }
        repeat(SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW) {
            draft.translateFrame(frame(opcode = 0x9, final = true, ByteArray(0)))
                .forEach { draft.processFrame(socket, it) }
        }

        assertThrows(InvalidDataException::class.java) {
            draft.translateFrame(frame(opcode = 0x9, final = true, ByteArray(0)))
                .forEach { draft.processFrame(socket, it) }
        }

        draft.reset()
        draft.setParseMode(Role.CLIENT)
        draft.translateFrame(frame(opcode = 0x9, final = true, ByteArray(0)))
            .forEach { draft.processFrame(socket, it) }
    }

    @Test
    fun wireFrameCeilingAllowsMaximumRelayJoinEnvelopeBeforeRejectingFlood() {
        val maximumSnapshotFrames = 56
        val maximumOtherMembers = 63
        val ephemeralFramesPerMember = 3
        val snapshotFenceFrames = 2
        val legitimateJoinEnvelope = maximumSnapshotFrames +
            maximumOtherMembers * ephemeralFramesPerMember +
            snapshotFenceFrames
        val budget = SyncWireReceiveBudget(
            maxFrames = SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW,
            maxBytes = Long.MAX_VALUE,
            windowNanos = 10L,
        )

        assertTrue(legitimateJoinEnvelope < SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW)
        repeat(legitimateJoinEnvelope) {
            assertTrue(budget.admit(byteCount = 0, nowNanos = 1L))
        }
        repeat(SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW - legitimateJoinEnvelope) {
            assertTrue(budget.admit(byteCount = 0, nowNanos = 1L))
        }
        assertFalse(budget.admit(byteCount = 0, nowNanos = 1L))
    }

    @Test
    fun wireReceiveBudgetCountsBytesAndFramesAndResetsByMonotonicWindow() {
        val budget = SyncWireReceiveBudget(maxFrames = 2, maxBytes = 5L, windowNanos = 10L)

        assertTrue(budget.admit(byteCount = 3, nowNanos = 1L))
        assertTrue(budget.admit(byteCount = 2, nowNanos = 2L))
        assertFalse(budget.admit(byteCount = 0, nowNanos = 3L))
        assertTrue(budget.admit(byteCount = 5, nowNanos = 11L))
        assertFalse(budget.admit(byteCount = 1, nowNanos = 12L))
        budget.reset(nowNanos = 20L)
        assertTrue(budget.admit(byteCount = 5, nowNanos = 20L))
    }

    @Test
    fun transportContractDisablesCompressionAndRedirectsAndBoundsOutboundQueue() {
        assertEquals(
            SyncInboundFramePolicy.MAX_FRAME_BYTES,
            SyncWebSocketTransport.boundedDraft().maxFrameSize,
        )
        assertFalse(SyncWebSocketTransport.OFFERS_COMPRESSION)
        assertFalse(SyncWebSocketTransport.FOLLOWS_REDIRECTS)
        assertTrue(SyncWebSocketTransport.VERIFIES_TLS_HOSTNAME)
        assertEquals(16L * 1024L * 1024L, SyncWebSocketTransport.MAX_OUTBOUND_QUEUE_BYTES)
        assertEquals(512, SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW)
        assertEquals(
            SyncLiveReceiveBudget.MAX_INITIAL_BYTES.toLong(),
            SyncWebSocketTransport.MAX_WIRE_BYTES_PER_WINDOW,
        )
    }

    @Test
    fun inboundCallbackGateBackpressuresUntilMainConsumerCompletes() {
        val gate = SyncInboundCallbackGate(timeoutMs = 1_000L)
        val callbackStarted = CountDownLatch(1)
        val workerFinished = AtomicBoolean(false)
        var consumed: (() -> Unit)? = null
        var accepted = false
        val worker = Thread {
            accepted = gate.awaitConsumption { completion ->
                consumed = completion
                callbackStarted.countDown()
            }
            workerFinished.set(true)
        }

        worker.start()
        assertTrue(callbackStarted.await(1, TimeUnit.SECONDS))
        assertFalse(workerFinished.get())
        requireNotNull(consumed).invoke()
        worker.join(1_000L)
        assertTrue(workerFinished.get())
        assertTrue(accepted)
    }

    @Test
    fun inboundCallbackGateFailsClosedWhenConsumerDoesNotDrain() {
        val gate = SyncInboundCallbackGate(timeoutMs = 1L)

        assertFalse(gate.awaitConsumption { _ -> Unit })
    }

    private fun frame(opcode: Int, final: Boolean, payload: ByteArray): ByteBuffer {
        val first = (if (final) 0x80 else 0x00) or opcode
        val headerBytes = when {
            payload.size <= 125 -> 2
            payload.size <= 0xffff -> 4
            else -> 10
        }
        return ByteBuffer.allocate(headerBytes + payload.size).apply {
            put(first.toByte())
            when {
                payload.size <= 125 -> put(payload.size.toByte())
                payload.size <= 0xffff -> {
                    put(126.toByte())
                    putShort(payload.size.toShort())
                }
                else -> {
                    put(127.toByte())
                    putLong(payload.size.toLong())
                }
            }
            put(payload)
            flip()
        }
    }

    private val noOpListener = object : WebSocketAdapter() {
        override fun onWebsocketMessage(conn: WebSocket, message: String) = Unit
        override fun onWebsocketMessage(conn: WebSocket, blob: ByteBuffer) = Unit
        override fun onWebsocketOpen(conn: WebSocket, handshake: Handshakedata) = Unit
        override fun onWebsocketClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
        override fun onWebsocketClosing(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
        override fun onWebsocketCloseInitiated(conn: WebSocket, code: Int, reason: String) = Unit
        override fun onWebsocketError(conn: WebSocket, ex: Exception) = Unit
        override fun onWebsocketPing(conn: WebSocket, f: Framedata) = Unit
        override fun onWriteDemand(conn: WebSocket) = Unit
        override fun getLocalSocketAddress(conn: WebSocket): InetSocketAddress? = null
        override fun getRemoteSocketAddress(conn: WebSocket): InetSocketAddress? = null
    }
}
