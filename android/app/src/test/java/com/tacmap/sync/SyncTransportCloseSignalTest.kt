package com.tacmap.sync

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * S2-11 on the real transport: the upgrade status and the relay's close code
 * have to survive Java-WebSocket's callbacks, otherwise SyncCloseClassifier
 * only ever sees "something failed" and retries a 401 forever.
 */
class SyncTransportCloseSignalTest {

    private class Capture : SyncWebSocketListener {
        val opened = AtomicBoolean(false)
        val closed = AtomicReference<Pair<Int, String>?>(null)
        val failed = AtomicReference<String?>(null)
        val done = CountDownLatch(1)
        override fun onOpen(webSocket: SyncWebSocket) { opened.set(true) }
        override fun onTextMessage(webSocket: SyncWebSocket, text: String, consumed: () -> Unit) = consumed()
        override fun onBinaryMessage(webSocket: SyncWebSocket, bytes: ByteArray, consumed: () -> Unit) = consumed()
        override fun onClosed(webSocket: SyncWebSocket, code: Int, reason: String) {
            closed.set(code to reason)
            done.countDown()
        }
        override fun onFailure(webSocket: SyncWebSocket, failure: Throwable) {
            failed.set(failure.message ?: failure.javaClass.simpleName)
            done.countDown()
        }

        /** What SyncManager hands the classifier when the socket never opened. */
        fun statusText(): String? = closed.get()?.second ?: failed.get()
    }

    /** One-shot HTTP server that answers the upgrade with [status]. */
    private fun rejectUpgradeWith(status: Int, reason: String): Int {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            server.use { s ->
                s.accept().use { client ->
                    val input = client.getInputStream().bufferedReader()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $status $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    Thread.sleep(200)
                }
            }
        }
        return server.localPort
    }

    private fun connect(port: Int): Capture {
        val capture = Capture()
        val transport = SyncWebSocketTransport()
        transport.newWebSocket("ws://127.0.0.1:$port/v3/room/test", mapOf("X-Protocol" to "3"), capture)
        assertTrue("transport never reported the end", capture.done.await(15, TimeUnit.SECONDS))
        transport.shutdown()
        return capture
    }

    @Test
    fun rejectedUpgradeStatusReachesTheClassifier() {
        for ((status, expected) in listOf(
            503 to SyncCloseAction.RECONNECT,
            401 to SyncCloseAction.STOP,
            429 to SyncCloseAction.RECONNECT,
        )) {
            val capture = connect(rejectUpgradeWith(status, "Nope"))
            assertFalse("$status must not look like an open socket", capture.opened.get())
            val text = capture.statusText()
            assertNotNull(text)
            assertEquals(text, status, SyncCloseClassifier.parseHandshakeStatus(text))
            assertEquals(expected, SyncCloseClassifier.classifyHttp(SyncCloseClassifier.parseHandshakeStatus(text)).action)
        }
    }

    @Test
    fun relayCloseCodeAfterOpenReachesTheClassifier() {
        val started = CountDownLatch(1)
        val server = object : WebSocketServer(InetSocketAddress("127.0.0.1", 0)) {
            override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
                conn.close(4013, "room quota")
            }
            override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) = Unit
            override fun onMessage(conn: WebSocket, message: String) = Unit
            override fun onError(conn: WebSocket?, ex: Exception) = Unit
            override fun onStart() = started.countDown()
        }
        server.isReuseAddr = true
        server.start()
        try {
            // start() binds on its own thread
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val capture = connect(server.port)
            assertTrue(capture.opened.get())
            assertEquals(4013, capture.closed.get()?.first)
            val decision = SyncCloseClassifier.classifyClose(4013)
            assertEquals(SyncCloseAction.STOP, decision.action)
            assertEquals(SyncIssueCode.ROOM_FULL_CANNOT_JOIN, decision.issue)
        } finally {
            server.stop(1_000)
        }
    }
}
