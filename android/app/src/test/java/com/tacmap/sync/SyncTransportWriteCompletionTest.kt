package com.tacmap.sync

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class SyncTransportWriteCompletionTest {
    @Test
    fun dequeuedBufferRemainsUnwrittenUntilTheRealLibraryWriterFinishes() {
        runHeldWriter(holdFlush = false)
    }

    @Test
    fun successfulWriteDoesNotCompleteBeforeFlushReturns() {
        runHeldWriter(holdFlush = true)
    }

    @Test
    fun automaticPongFloodAbortsTheBlockedWriterAtTheSharedQueueLimit() {
        runHeldWriter(holdFlush = false, floodControl = true)
    }

    private fun runHeldWriter(holdFlush: Boolean, floodControl: Boolean = false) {
        val serverStarted = CountDownLatch(1)
        val messageReceived = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val flushed = CountDownLatch(1)
        val failed = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val failureSeen = CountDownLatch(1)
        val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) = Unit
            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
            override fun onMessage(conn: WebSocket, message: String) { messageReceived.countDown() }
            override fun onError(conn: WebSocket?, error: Exception) { failed.set(true) }
            override fun onStart() { serverStarted.countDown() }
        }
        val factory = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                private var stream: OutputStream? = null

                override fun getOutputStream(): OutputStream {
                    stream?.let { return it }
                    val output = super.getOutputStream()
                    return object : OutputStream() {
                    private var writingApplication = false

                    override fun write(value: Int) = output.write(value)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        // The upgrade request is small; only this test's message is large.
                        writingApplication = length > 1_024
                        if (writingApplication && !holdFlush) {
                            held.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        output.write(bytes, offset, length)
                    }

                    override fun flush() {
                        if (writingApplication && holdFlush) {
                            held.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        output.flush()
                        if (writingApplication) flushed.countDown()
                    }

                    override fun close() = output.close()
                    }.also { stream = it }
                }
            }

            override fun createSocket(host: String, port: Int): Socket = error("unused")
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = error("unused")
        }
        val transport = SyncWebSocketTransport(factory)
        var socket: SyncWebSocket? = null
        try {
            server.start()
            assertTrue(serverStarted.await(3, TimeUnit.SECONDS))
            socket = transport.newWebSocket("ws://127.0.0.1:${server.port}", emptyMap(), object : SyncWebSocketListener {
                override fun onOpen(webSocket: SyncWebSocket) { opened.countDown() }
                override fun onTextMessage(webSocket: SyncWebSocket, text: String, consumed: () -> Unit) = consumed()
                override fun onBinaryMessage(webSocket: SyncWebSocket, bytes: ByteArray, consumed: () -> Unit) = consumed()
                override fun onClosed(webSocket: SyncWebSocket, code: Int, reason: String) = Unit
                override fun onFailure(webSocket: SyncWebSocket, error: Throwable) {
                    failed.set(true)
                    failure.set(error)
                    failureSeen.countDown()
                }
            })
            assertTrue(opened.await(3, TimeUnit.SECONDS))
            val text = "a".repeat(50_000)
            assertTrue(socket.send(text))
            assertTrue(held.await(3, TimeUnit.SECONDS))
            // Java-WebSocket has taken the buffer out of outQueue at this point.
            // The manager must still see the full application frame as pending.
            assertEquals(50_008L, socket.queuedBytes())
            val timer = SyncAckTimer()
            timer.enqueued("held")
            val inferredWritten = 50_008L - socket.queuedBytes()
            if (inferredWritten >= 50_008L) timer.writeComplete("held", 0L, 0)
            assertEquals(SyncAckTimer.Due.NOTHING, timer.check("held", 20_000L))

            if (floodControl) {
                val connection = server.connections.single()
                repeat(SyncWebSocketTransport.MAX_WIRE_FRAMES_PER_WINDOW) {
                    runCatching { connection.sendPing() }
                }
                assertTrue(failureSeen.await(3, TimeUnit.SECONDS))
                assertEquals("Unit Sync outbound queue limit reached", failure.get()?.message)
                assertEquals(0L, socket.queuedBytes())
                assertFalse(socket.send("after terminal queue limit"))
                return
            }

            release.countDown()
            assertTrue(flushed.await(3, TimeUnit.SECONDS))
            assertTrue(messageReceived.await(3, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (socket.queuedBytes() > 0 && System.nanoTime() < deadline) Thread.yield()
            assertEquals(0L, socket.queuedBytes())
            assertFalse(failed.get())
        } finally {
            release.countDown()
            socket?.cancel()
            transport.shutdown()
            server.stop(1_000)
        }
    }

    @Test
    fun failingFlushKeepsTheFramePendingAndControlFramesDoNotCountAsApplicationBytes() {
        val writes = SyncSocketWriteTracker()
        val app = ByteBuffer.wrap(ByteArray(20))
        val control = ByteBuffer.wrap(ByteArray(6))
        writes.enqueued(app, application = true)
        writes.enqueued(control, application = false)
        val stream = writes.outputStream(object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun flush() { throw IOException("held test flush") }
        })
        stream.write(app.array(), 0, app.remaining())
        try { stream.flush() } catch (_: IOException) { }
        assertEquals(20L, writes.pendingApplicationBytes)
        assertEquals(26L, writes.pendingWireBytes)
        writes.flushed(listOf(control.array()))
        assertEquals(20L, writes.pendingApplicationBytes)
        writes.clear()
        writes.flushed(listOf(app.array()))
        assertEquals(0L, writes.pendingWireBytes)
    }

    @Test
    fun controlFramesCannotGrowTheRegistryAcrossBlockedWriterWindows() {
        var failures = 0
        val writes = SyncSocketWriteTracker(maxBytes = 100, maxFrames = 2, onOverflow = { failures += 1 })
        writes.enqueued(ByteBuffer.wrap(ByteArray(6)), application = false)
        writes.enqueued(ByteBuffer.wrap(ByteArray(6)), application = false)
        assertEquals(12L, writes.pendingWireBytes)
        assertThrows(IllegalStateException::class.java) {
            writes.enqueued(ByteBuffer.wrap(ByteArray(6)), application = false)
        }
        assertEquals(1, failures)
        assertEquals(0L, writes.pendingWireBytes)
        assertThrows(IllegalStateException::class.java) {
            writes.enqueued(ByteBuffer.wrap(ByteArray(6)), application = false)
        }
        assertEquals(1, failures)
    }

    @Test
    fun combinedControlAndApplicationBytesShareTheSameHardCap() {
        var failures = 0
        val writes = SyncSocketWriteTracker(maxBytes = 25, maxFrames = 10, onOverflow = { failures += 1 })
        writes.enqueued(ByteBuffer.wrap(ByteArray(20)), application = true)
        assertThrows(IllegalStateException::class.java) {
            writes.enqueued(ByteBuffer.wrap(ByteArray(6)), application = false)
        }
        assertEquals(1, failures)
        assertEquals(0L, writes.pendingWireBytes)
        assertEquals(0L, writes.pendingApplicationBytes)
    }

    @Test
    fun tlsDecorationPreservesTheSecureSocketAndHostVerificationParameters() {
        val original = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket() as SSLSocket
        val supplied = object : SocketFactory() {
            override fun createSocket(): Socket = original
            override fun createSocket(host: String, port: Int): Socket = error("unused")
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("unused")
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = error("unused")
        }
        val wrapped = SyncWriteTrackingSocketFactory(URI("wss://relay.example"), SyncSocketWriteTracker(), supplied)
            .createSocket()
        try {
            assertTrue(wrapped is SSLSocket)
            wrapped as SSLSocket
            val parameters = wrapped.sslParameters
            parameters.endpointIdentificationAlgorithm = "HTTPS"
            wrapped.sslParameters = parameters
            assertEquals("HTTPS", original.sslParameters.endpointIdentificationAlgorithm)
            assertEquals(original.enabledProtocols.toList(), wrapped.enabledProtocols.toList())
            assertEquals(original.enabledCipherSuites.toList(), wrapped.enabledCipherSuites.toList())
        } finally { wrapped.close() }
    }
}
