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
import java.net.Socket
import java.net.SocketAddress
import java.net.InetAddress
import java.net.InetSocketAddress
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.IdentityHashMap
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import javax.net.ssl.HandshakeCompletedListener
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface SyncWebSocket {
    fun send(text: String): Boolean
    fun close(code: Int, reason: String): Boolean
    fun cancel()
    /** Bytes handed to send() that haven't left for the network yet. */
    fun queuedBytes(): Long = 0L
    fun sendPing(): Boolean = false
    /** Library keepalive interval; background mode slows it down (plans/04 section 21.1). */
    fun setKeepaliveSeconds(seconds: Int) {}
}

internal interface SyncWebSocketListener {
    fun onOpen(webSocket: SyncWebSocket)
    fun onTextMessage(webSocket: SyncWebSocket, text: String, consumed: () -> Unit)
    fun onBinaryMessage(webSocket: SyncWebSocket, bytes: ByteArray, consumed: () -> Unit)
    fun onClosed(webSocket: SyncWebSocket, code: Int, reason: String)
    fun onFailure(webSocket: SyncWebSocket, failure: Throwable)
    /** Any inbound bytes at all, called on the reader thread. Keep it cheap. */
    fun onInboundProgress(webSocket: SyncWebSocket) {}
}

/**
 * Applies reader-thread backpressure until the protocol side has taken the
 * frame. SyncManager calls consumed as soon as the frame sits in its bounded
 * inbound queue (plans/04 section 1.1), so the reader only really waits when
 * that queue is full, which keeps a fast hostile relay from piling up decoded
 * Strings ahead of the receive budget. CountDownLatch makes duplicate
 * completion harmless.
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

/** Tracks the library's encoded buffers through write and flush, including
 * the buffer its writer has already taken out of outQueue. Array identity
 * comes from Draft_6455, so no frame parsing or private library fields are needed. */
internal class SyncSocketWriteTracker(
    private val maxBytes: Long = SyncWebSocketTransport.MAX_OUTBOUND_QUEUE_BYTES,
    private val maxFrames: Int = SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW,
    private val onOverflow: () -> Unit = {},
) {
    private data class Pending(val bytes: Long, val application: Boolean)
    private val pending = IdentityHashMap<ByteArray, Pending>()
    private var wireBytes = 0L
    private var applicationBytes = 0L
    private var closed = false

    val pendingWireBytes: Long @Synchronized get() = wireBytes
    val pendingApplicationBytes: Long @Synchronized get() = applicationBytes

    fun enqueued(buffer: ByteBuffer, application: Boolean) {
        val overflow = synchronized(this) {
            check(!closed) { "WebSocket write accounting is terminal" }
            check(buffer.hasArray()) { "WebSocket encoder must return an array-backed buffer" }
            val bytes = buffer.remaining().toLong()
            // Control responses bypass send(), so the encoder is the final
            // admission point for every frame, including PONG and CLOSE.
            if (pending.size >= maxFrames || bytes > maxBytes - wireBytes) {
                closed = true
                pending.clear()
                wireBytes = 0L
                applicationBytes = 0L
                true
            } else {
                check(pending.put(buffer.array(), Pending(bytes, application)) == null) { "WebSocket buffer queued twice" }
                wireBytes += bytes
                if (application) applicationBytes += bytes
                false
            }
        }
        if (overflow) {
            // Abort the socket directly; sending a close frame would recurse
            // into the encoder whose bounded queue has just become terminal.
            onOverflow()
            throw IllegalStateException("WebSocket outbound queue limit reached")
        }
    }

    @Synchronized
    fun flushed(buffers: List<ByteArray>) {
        for (buffer in buffers) {
            val value = pending.remove(buffer) ?: continue
            wireBytes -= value.bytes
            if (value.application) applicationBytes -= value.bytes
        }
    }

    @Synchronized
    fun clear() {
        closed = true
        pending.clear()
        wireBytes = 0L
        applicationBytes = 0L
    }

    fun outputStream(delegate: OutputStream): OutputStream = object : OutputStream() {
        private val written = ArrayList<ByteArray>()

        override fun write(value: Int) = delegate.write(value)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            // Java-WebSocket writes one complete encoded buffer at a time.
            if (offset == 0 && length == bytes.size) written += bytes
        }

        override fun flush() {
            delegate.flush()
            flushed(written)
            written.clear()
        }

        override fun close() = delegate.close()
    }
}

/** Decorates the plaintext side of both ws and wss sockets. The TLS socket
 * remains an SSLSocket so Java-WebSocket still enables HTTPS host verification. */
internal class SyncWriteTrackingSocketFactory(
    private val uri: URI,
    private val writes: SyncSocketWriteTracker,
    private val supplied: SocketFactory? = null,
) : SocketFactory() {
    override fun createSocket(): Socket {
        supplied?.let { return wrap(it.createSocket()) }
        if (uri.scheme != "wss") return wrap(Socket())
        val raw = Socket()
        try {
            val port = if (uri.port >= 0) uri.port else 443
            raw.connect(InetSocketAddress(uri.host, port), SyncWebSocketTransport.CONNECT_TIMEOUT_MS)
            // Passing the original host preserves SNI and certificate host checks.
            val secure = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, uri.host, port, true) as SSLSocket
            return wrap(secure)
        } catch (failure: Throwable) {
            runCatching { raw.close() }
            throw failure
        }
    }

    override fun createSocket(host: String, port: Int): Socket =
        wrap(delegateFactory().createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        wrap(delegateFactory().createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket =
        wrap(delegateFactory().createSocket(host, port))

    override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket =
        wrap(delegateFactory().createSocket(host, port, localHost, localPort))

    private fun delegateFactory(): SocketFactory = supplied ?: if (uri.scheme == "wss") {
        SSLSocketFactory.getDefault()
    } else {
        SocketFactory.getDefault()
    }

    private fun wrap(socket: Socket): Socket = if (socket is SSLSocket) {
        SyncTrackedTlsSocket(socket, writes)
    } else {
        SyncTrackedSocket(socket, writes)
    }
}

private class SyncTrackedSocket(
    private val delegate: Socket,
    private val writes: SyncSocketWriteTracker,
) : Socket() {
    private val output by lazy { writes.outputStream(delegate.getOutputStream()) }
    override fun getOutputStream(): OutputStream = output
    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun connect(endpoint: SocketAddress) = delegate.connect(endpoint)
    override fun connect(endpoint: SocketAddress, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun close() = delegate.close()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isBound(): Boolean = delegate.isBound
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
}

private class SyncTrackedTlsSocket(
    private val delegate: SSLSocket,
    private val writes: SyncSocketWriteTracker,
) : SSLSocket() {
    private val output by lazy { writes.outputStream(delegate.getOutputStream()) }
    override fun getOutputStream(): OutputStream = output
    override fun getInputStream(): InputStream = delegate.getInputStream()
    override fun connect(endpoint: SocketAddress) = delegate.connect(endpoint)
    override fun connect(endpoint: SocketAddress, timeout: Int) = delegate.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = delegate.bind(bindpoint)
    override fun close() = delegate.close()
    override fun isConnected(): Boolean = delegate.isConnected
    override fun isClosed(): Boolean = delegate.isClosed
    override fun isBound(): Boolean = delegate.isBound
    override fun getInetAddress(): InetAddress? = delegate.inetAddress
    override fun getLocalAddress(): InetAddress = delegate.localAddress
    override fun getPort(): Int = delegate.port
    override fun getLocalPort(): Int = delegate.localPort
    override fun getRemoteSocketAddress(): SocketAddress? = delegate.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = delegate.localSocketAddress
    override fun setTcpNoDelay(on: Boolean) { delegate.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = delegate.tcpNoDelay
    override fun setReuseAddress(on: Boolean) { delegate.reuseAddress = on }
    override fun getReuseAddress(): Boolean = delegate.reuseAddress
    override fun setSoTimeout(timeout: Int) { delegate.soTimeout = timeout }
    override fun getSoTimeout(): Int = delegate.soTimeout
    override fun setReceiveBufferSize(size: Int) { delegate.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = delegate.receiveBufferSize
    override fun setSendBufferSize(size: Int) { delegate.sendBufferSize = size }
    override fun getSendBufferSize(): Int = delegate.sendBufferSize
    override fun setKeepAlive(on: Boolean) { delegate.keepAlive = on }
    override fun getKeepAlive(): Boolean = delegate.keepAlive
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
    override fun isInputShutdown(): Boolean = delegate.isInputShutdown
    override fun isOutputShutdown(): Boolean = delegate.isOutputShutdown
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun getEnabledCipherSuites(): Array<String> = delegate.enabledCipherSuites
    override fun setEnabledCipherSuites(suites: Array<String>) { delegate.enabledCipherSuites = suites }
    override fun getSupportedProtocols(): Array<String> = delegate.supportedProtocols
    override fun getEnabledProtocols(): Array<String> = delegate.enabledProtocols
    override fun setEnabledProtocols(protocols: Array<String>) { delegate.enabledProtocols = protocols }
    override fun getSession(): SSLSession = delegate.session
    override fun getHandshakeSession(): SSLSession? = delegate.handshakeSession
    override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener) = delegate.addHandshakeCompletedListener(listener)
    override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener) = delegate.removeHandshakeCompletedListener(listener)
    override fun startHandshake() = delegate.startHandshake()
    override fun setUseClientMode(mode: Boolean) { delegate.useClientMode = mode }
    override fun getUseClientMode(): Boolean = delegate.useClientMode
    override fun setNeedClientAuth(need: Boolean) { delegate.needClientAuth = need }
    override fun getNeedClientAuth(): Boolean = delegate.needClientAuth
    override fun setWantClientAuth(want: Boolean) { delegate.wantClientAuth = want }
    override fun getWantClientAuth(): Boolean = delegate.wantClientAuth
    override fun setEnableSessionCreation(enabled: Boolean) { delegate.enableSessionCreation = enabled }
    override fun getEnableSessionCreation(): Boolean = delegate.enableSessionCreation
    override fun getSSLParameters(): SSLParameters = delegate.sslParameters
    override fun setSSLParameters(parameters: SSLParameters) { delegate.sslParameters = parameters }
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
internal class SyncWebSocketTransport(
    private val socketFactory: SocketFactory? = null,
) : SyncTransportFactory {
    private val sockets = ConcurrentHashMap.newKeySet<BoundedSocket>()

    override fun newWebSocket(
        url: String,
        headers: Map<String, String>,
        listener: SyncWebSocketListener,
    ): SyncWebSocket {
        val socket = BoundedSocket(
            uri = URI(url),
            headers = headers,
            listener = listener,
            socketFactory = socketFactory,
            onTerminal = sockets::remove,
        )
        sockets += socket
        socket.connect()
        return socket
    }

    override fun shutdown() {
        sockets.toList().forEach(BoundedSocket::cancel)
        sockets.clear()
    }

    internal companion object {
        const val MAX_FRAME_BYTES = SyncInboundFramePolicy.MAX_FRAME_BYTES
        const val MAX_OUTBOUND_QUEUE_BYTES = 16L * 1024L * 1024L
        private const val MAX_FRAME_OVERHEAD_BYTES = 14L
        const val CONNECT_TIMEOUT_MS = 10_000
        // plans/04 watchdogs.inboundQueueSpaceTimeoutMs: a reader that waits
        // this long for the protocol side is a slow consumer and gets cut
        const val CALLBACK_DRAIN_TIMEOUT_MS = 60_000L
        const val KEEPALIVE_SECONDS = 20
        const val MAX_FRAGMENT_COUNT = 128
        // Raw RFC 6455 frame ceiling, below the protocol budgets. It has to sit
        // above the biggest legit live window (room budget at its 4,000 cap plus
        // 400 self-responses, plus pings/pongs and continuation frames) or the
        // transport would trip before SyncReceiveBudget ever gets a say.
        const val MAX_WIRE_FRAMES_PER_WINDOW = 8_192
        const val MAX_WIRE_BYTES_PER_WINDOW = SyncReceiveBudget.INITIAL_MAX_BYTES
        const val WIRE_WINDOW_NANOS = 10_000_000_000L
        const val FOLLOWS_REDIRECTS = false
        const val OFFERS_COMPRESSION = false
        const val VERIFIES_TLS_HOSTNAME = true

        fun boundedDraft(onProgress: (() -> Unit)? = null): Draft_6455 = BoundedDraft(onProgress)
    }

    /**
     * Java-WebSocket 1.6.0 checks a fragmented aggregate on its first and final
     * frames, but not after intermediate non-final continuations. Enforce the
     * same cap before every continuation enters the library's retained buffer.
     * `copyInstance` is essential because WebSocketImpl clones the supplied
     * draft for each client connection.
     */
    private class BoundedDraft(
        private val onProgress: (() -> Unit)?,
        private val writes: SyncSocketWriteTracker? = null,
    ) : Draft_6455(emptyList<IExtension>(), MAX_FRAME_BYTES) {
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

        // every socket read lands here, so it's the cheapest "bytes are still
        // arriving" signal for the handshake stall watchdog (plans/04 section 9)
        override fun translateFrame(buffer: ByteBuffer): MutableList<Framedata> {
            if (buffer.hasRemaining()) onProgress?.invoke()
            return super.translateFrame(buffer)
        }

        override fun createBinaryFrame(frame: Framedata): ByteBuffer {
            val encoded = super.createBinaryFrame(frame)
            writes?.enqueued(encoded, frame.opcode == Opcode.TEXT)
            return encoded
        }

        override fun copyInstance(): Draft = BoundedDraft(onProgress, writes)

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
        socketFactory: SocketFactory?,
        private val onTerminal: (BoundedSocket) -> Unit,
    ) : SyncWebSocket {
        private val terminal = AtomicBoolean(false)
        private val sendLock = Any()
        private val inboundCallbackGate = SyncInboundCallbackGate()
        private val writes: SyncSocketWriteTracker = SyncSocketWriteTracker(onOverflow = {
            finish { listener.onFailure(this, IOException("Unit Sync outbound queue limit reached")) }
            runCatching { client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "outbound queue limit") }
        })
        private val client: WebSocketClient = object : WebSocketClient(
            uri,
            BoundedDraft({ listener.onInboundProgress(this@BoundedSocket) }, writes),
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
                setSocketFactory(SyncWriteTrackingSocketFactory(uri, writes, socketFactory))
                isDaemon = true
                connectionLostTimeout = KEEPALIVE_SECONDS
            }
        }

        fun connect() = client.connect()

        override fun send(text: String): Boolean = synchronized(sendLock) {
            if (terminal.get() || client.readyState != ReadyState.OPEN) return false
            val payloadBytes = text.toByteArray(Charsets.UTF_8).size.toLong()
            if (payloadBytes > MAX_FRAME_BYTES) return false
            val queuedBytes = writes.pendingWireBytes
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

        /** Includes a buffer the writer took out of the queue but has not flushed. */
        override fun queuedBytes(): Long = writes.pendingApplicationBytes

        override fun sendPing(): Boolean {
            if (terminal.get() || client.readyState != ReadyState.OPEN) return false
            return runCatching { client.sendPing() }.isSuccess
        }

        // Java-WebSocket restarts its lost-connection timer when this changes
        override fun setKeepaliveSeconds(seconds: Int) {
            if (terminal.get()) return
            runCatching { client.connectionLostTimeout = seconds }
        }

        override fun cancel() {
            runCatching {
                client.closeConnection(CloseFrame.ABNORMAL_CLOSE, "cancelled")
            }
        }

        private fun finish(callback: () -> Unit) {
            if (!terminal.compareAndSet(false, true)) return
            writes.clear()
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
