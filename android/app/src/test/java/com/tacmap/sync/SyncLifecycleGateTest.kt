package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SyncLifecycleGateTest {
    @Test fun disposeClosesCancelsZerosAndBlocksReconnectAndOutbound() {
        val gate = SyncLifecycleGate()
        val roomKey = ByteArray(32) { 0x5a }
        val sessionDomain = ByteArray(32) { 0x33 }
        var socketCloses = 0
        var cancellations = 0
        var outbound = 0
        var reconnects = 0

        assertTrue(gate.sendIfActive { outbound++; true })
        assertTrue(gate.runIfActive { reconnects++ })

        assertTrue(gate.dispose(listOf(roomKey, sessionDomain)) {
            socketCloses++
            cancellations++
        })
        assertFalse(gate.sendIfActive { outbound++; true })
        assertFalse(gate.runIfActive { reconnects++ })
        assertFalse("disposal is idempotent", gate.dispose(emptyList()) { socketCloses++ })

        assertEquals(1, socketCloses)
        assertEquals(1, cancellations)
        assertEquals(1, outbound)
        assertEquals(1, reconnects)
        assertTrue(roomKey.all { it == 0.toByte() })
        assertTrue(sessionDomain.all { it == 0.toByte() })
        assertTrue(gate.isDisposed)
    }

    @Test fun disposalWaitsForAnAcceptedCallbackThenRejectsLaterWork() {
        val gate = SyncLifecycleGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        var callbacks = 0

        val callbackThread = thread {
            gate.runIfActive {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                callbacks++
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))

        val disposeThread = thread {
            gate.dispose(emptyList()) {}
            disposed.countDown()
        }
        assertFalse("dispose must not race through accepted callback work", disposed.await(100, TimeUnit.MILLISECONDS))

        release.countDown()
        callbackThread.join(2_000)
        disposeThread.join(2_000)
        assertTrue(disposed.await(100, TimeUnit.MILLISECONDS))
        assertEquals(1, callbacks)
        assertFalse(gate.runIfActive { callbacks++ })
        assertEquals(1, callbacks)
    }
}
