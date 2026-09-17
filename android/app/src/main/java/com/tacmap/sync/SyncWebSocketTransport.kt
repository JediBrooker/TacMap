package com.tacmap.sync

import org.java_websocket.WebSocketImpl
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.enums.Opcode
import org.java_websocket.enums.ReadyState
import org.java_websocket.exceptions.InvalidDataException
import org.java_websocket.exceptions.LimitExceededException
import org.java_websocket.extensions.IExtension
import org.java_websocket.framing.CloseFrame
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface SyncWebSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String): Boolean
    fun cancel()
}

internal interface SyncWebSocketListener {
    fun onOpen(webSocket: SyncWebSocket)
    fun onTextMessage(webSocket: SyncWebSocket, text: String, consumed: () -> Unit)
    fun onBinaryMessage(webSocket: SyncWebSocket, bytes: ByteArray, consumed: () -> Unit)
    fun onClosed(webSocket: SyncWebSocket, code: Int, reason: String)
    fun onFailure(webSocket: SyncWebSocket, failure: Throwable)
}

/**
 * Applies reader-thread backpressure until the main-thread protocol consumer
 * has handled the current frame. This keeps a fast hostile relay from queuing
 * an unbounded number of decoded Strings/coroutines ahead of the receive-rate
 * budget. CountDownLatch makes duplicate completion harmless.
 */
internal class SyncInboundCallbackGate(
    private val timeoutMs: Long = SyncWebSocketTransport.CALLBACK_DRAIN_TIMEOUT_MS,
) {
    fun awaitConsumption(deliver: (consumed: () -> Unit) -> Unit): Boolean {
        if (timeoutMs <= 0L) return false
        val consumed = CountDownLatch(1)
        return try {
            deliver { consumed.countDown() }
            consumed.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: RuntimeException) {
            false
        }
    }
}

/** Counts every accepted RFC 6455 frame, including control and continuation
 * frames that never reach the application message callback. */
internal class SyncWireReceiveBudget(
    private val maxFrames: Int = SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW,
    private val maxBytes: Long = SyncWebSocketTransport.MAX_WIRE_BYTES_PER_WINDOW,
    private val windowNanos: Long = SyncWebSocketTransport.WIRE_WINDOW_NANOS,
) {
    private var windowStartNanos = System.nanoTime()
    private var frames = 0
    private var bytes = 0L

    fun admit(byteCount: Int, nowNanos: Long = System.nanoTime()): Boolean {
        if (byteCount < 0 || maxFrames <= 0 || maxBytes < 0L || windowNanos <= 0L) return false
        if (nowNanos < windowStartNanos || nowNanos - windowStartNanos >= windowNanos) {
            reset(nowNanos)
        }
        if (frames >= maxFrames || byteCount.toLong() > maxBytes - bytes) return false
        frames += 1
        bytes += byteCount
        return true
    }

    fun reset(nowNanos: Long = System.nanoTime()) {
        windowStartNanos = nowNanos
        frames = 0
        bytes = 0L
    }
}

/**
 * Unit Sync transport with allocation-time inbound bounds.
 *
 * OkHttp delivers a WebSocket message only after buffering (and potentially
 * inflating) the full declared payload, so an application callback cannot
 * protect Android from a hostile relay claiming a multi-gigabyte frame. This
 * transport configures Draft_6455's maximum frame size; the implementation
 * checks each declared frame length before allocating its payload. TacMap's
 * bounded draft also closes an upstream gap by checking the aggregate before
 * every continuation is retained, combined, or delivered. No
 * compression extension is offered, so a small compressed frame cannot inflate
 * past the same ceiling.
 */
internal class SyncWebSocketTransport {
    private val sockets = ConcurrentHashMap.newKeySet<BoundedSocket>()

    fun newWebSocket(
        url: String,
        headers: Map<String, String>,
        listener: SyncWebSocketListener,
    ): SyncWebSocket {
        val socket = BoundedSocket(
            uri = URI(url),
            headers = headers,
            listener = listener,
            onTerminal = sockets::remove,
        )
        sockets += socket
        socket.connect()
        return socket
    }

    fun shutdown() {
        sockets.toList().forEach(BoundedSocket::cancel)
        sockets.clear()
    }

    internal companion object {
        const val MAX_FRAME_BYTES = SyncInboundFramePolicy.MAX_FRAME_BYTES
        const val MAX_OUTBOUND_QUEUE_BYTES = 16L * 1024L * 1024L
        private const val MAX_FRAME_OVERHEAD_BYTES = 14L
        const val CONNECT_TIMEOUT_MS = 10_000
        const val CALLBACK_DRAIN_TIMEOUT_MS = 10_000L
        const val KEEPALIVE_SECONDS = 20
        const val MAX_FRAGMENT_COUNT = 128
        // A valid v3 initial join can contain ~56 maximum-sized snapshot
        // chunks plus three ephemeral frames for each of the other 63 room
        // members. Keep this pre-protocol raw-frame ceiling above that relay
        // envelope; SyncLiveReceiveBudget still enforces 200 application
        // messages / 4 MiB after the snapshot fence.
        const val MAX_WIRE_FRAMES_PER_WINDOW = 512
        const val MAX_WIRE_BYTES_PER_WINDOW = SyncLiveReceiveBudget.MAX_INITIAL_BYTES.toLong()
        const val WIRE_WINDOW_NANOS = 10_000_000_000L
        const val FOLLOWS_REDIRECTS = false
        const val OFFERS_COMPRESSION = false
        const val VERIFIES_TLS_HOSTNAME = true

        fun boundedDraft(): Draft_6455 = BoundedDraft()
    }

    /**
     * Java-WebSocket 1.6.0 checks a fragmented aggregate on its first and final
     * frames, but not after intermediate non-final continuations. Enforce the
     * same cap before every continuation enters the library's retained buffer.
     * `copyInstance` is essential because WebSocketImpl clones the supplied
     * draft for each client connection.
     */
    private class BoundedDraft : Draft_6455(emptyList<IExtension>(), MAX_FRAME_BYTES) {
        private var fragmentedPayloadBytes = 0L
        private var fragmentedMessageActive = false
        private var fragmentedFrameCount = 0
        private val wireReceiveBudget = SyncWireReceiveBudget()

        override fun processFrame(webSocketImpl: WebSocketImpl, frame: Framedata) {
            val payloadBytes = frame.payloadData.remaining().toLong()
            if (!wireReceiveBudget.admit(payloadBytes.toInt())) {
                throw InvalidDataException(
                    CloseFrame.POLICY_VALIDATION,
                    "WebSocket wire receive budget reached.",
                )
            }
            var startsFragmentedMessage = false
            var continuationTotal: Long? = null
            var continuationFrameCount: Int? = null

            when (frame.opcode) {
                Opcode.TEXT, Opcode.BINARY -> if (!frame.isFin && !fragmentedMessageActive) {
                    enforceAggregateLimit(payloadBytes)
                    startsFragmentedMessage = true
                }
                Opcode.CONTINUOUS -> if (fragmentedMessageActive) {
                    if (fragmentedFrameCount >= MAX_FRAGMENT_COUNT) {
                        throw InvalidDataException(
                            CloseFrame.POLICY_VALIDATION,
                            "Fragmented message frame-count limit reached.",
                        )
                    }
                    if (fragmentedPayloadBytes > MAX_FRAME_BYTES.toLong() - payloadBytes) {
                        throw LimitExceededException("Fragmented message payload limit reached.", MAX_FRAME_BYTES)
                    }
                    continuationTotal = fragmentedPayloadBytes + payloadBytes
                    continuationFrameCount = fragmentedFrameCount + 1
                }
                else -> Unit
            }

            super.processFrame(webSocketImpl, frame)

            if (startsFragmentedMessage) {
                fragmentedMessageActive = true
                fragmentedPayloadBytes = payloadBytes
                fragmentedFrameCount = 1
            } else if (continuationTotal != null) {
                if (frame.isFin) {
                    clearFragmentedState()
                } else {
                    fragmentedPayloadBytes = continuationTotal
                    fragmentedFrameCount = checkNotNull(continuationFrameCount)
                }
            }
        }

        override fun copyInstance(): Draft = BoundedDraft()

        override fun reset() {
            clearFragmentedState()
            wireReceiveBudget.reset()
            super.reset()
        }

        private fun enforceAggregateLimit(bytes: Long) {
            if (bytes > MAX_FRAME_BYTES) {
                throw LimitExceededException("Fragmented message payload limit reached.", MAX_FRAME_BYTES)
            }
        }

        private fun clearFragmentedState() {
            fragmentedMessageActive = false
            fragmentedPayloadBytes = 0L
            fragmentedFrameCount = 0
        }

    }

    private class BoundedSocket(
        uri: URI,
        headers: Map<String, String>,
        private val listener: SyncWebSocketListener,
        private val onTerminal: (BoundedSocket) -> Unit,
    ) : SyncWebSocket {
        private val terminal = AtomicBoolean(false)
        private val sendLock = Any()
        private val inboundCallbackGate = SyncInboundCallbackGate()
        private val client = object : WebSocketClient(
            uri,
            boundedDraft(),
            headers,
            CONNECT_TIMEOUT_MS,
        ) {
            override fun onOpen(handshakeData: ServerHandshake) {
                if (!terminal.get()) listener.onOpen(this@BoundedSocket)
            }

            override fun onMessage(message: String) {
                if (terminal.get()) return
                if (!inboundCallbackGate.awaitConsumption { consumed ->
                        listener.onTextMessage(this@BoundedSocket, message, consumed)
                    }
                ) failSlowConsumer()
            }

            override fun onMessage(bytes: ByteBuffer) {
                if (terminal.get()) return
                val copy = ByteArray(bytes.remaining())
                bytes.slice().get(copy)
                if (!inboundCallbackGate.awaitConsumption { consumed ->
                        listener.onBinaryMessage(this@BoundedSocket, copy, consumed)
                    }
                ) failSlowConsumer()
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                finish { listener.onClosed(this@BoundedSocket, code, reason) }
            }

            override fun onError(error: Exception) {
                finish { listener.onFailure(this@BoundedSocket, error) }
                // Treat every transport/protocol error as terminal. In
                // particular, a declared-length violation must not leave a
                // partially usable connection behind.
                runCatching { closeConnection(CloseFrame.ABNORMAL_CLOSE, "transport failure") }
            }

            init {
                isDaemon = true
                connectionLostTimeout = KEEPALIVE_SECONDS
            }
        }

        fun connect() = client.connect()

        override fun send(text: String): Boolean = synchronized(sendLock) {
            if (terminal.get() || client.readyState != ReadyState.OPEN) return false
            val payloadBytes = text.toByteArray(Charsets.UTF_8).size.toLong()
            if (payloadBytes > MAX_FRAME_BYTES) return false
            val queuedBytes = (client.connection as? WebSocketImpl)
                ?.outQueue
                ?.sumOf { it.remaining().toLong() }
                ?: MAX_OUTBOUND_QUEUE_BYTES
            // RFC 6455 client frames add at most 14 bytes (mask + 64-bit length).
            // Count that overhead too so the bound applies to the actual queue.
            val nextFrameBytes = payloadBytes + MAX_FRAME_OVERHEAD_BYTES
            if (queuedBytes > MAX_OUTBOUND_QUEUE_BYTES - nextFrameBytes) return false
            runCatching { client.send(text) }.isSuccess
        }

        override fun close(code: Int, reason: String): Boolean {
            if (terminal.get()) return false
            return runCatching { client.close(code, reason) }.isSuccess
        }

        override fun cancel() {
            runCatching {
                client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "cancelled")
            }
        }

        private fun finish(callback: () -> Unit) {
            if (!terminal.compareAndSet(false, true)) return
            onTerminal(this)
            callback()
        }

        private fun failSlowConsumer() {
            val failure = SocketTimeoutException("Unit Sync inbound consumer timed out")
            finish { listener.onFailure(this, failure) }
            runCatching {
                client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "inbound consumer timeout")
            }
        }
    }
}
