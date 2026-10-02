package com.tacmap.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pacer edge cases the fixture scenarios don't reach. The in-flight window is
 * keyed by local object id, so a newer copy of the same object has to take the
 * old copy's slot instead of stacking on top of it.
 */
class SyncOutboundPacerTest {

    private fun mutation(key: String, bytes: Int) =
        SyncOutboundPacer.Entry(SyncOutboundClass.MUTATION, bytes, key, key)

    @Test
    fun aNewerCopyOfTheSameObjectReplacesItsInFlightSlot() {
        val pacer = SyncOutboundPacer<String>(0.0)
        pacer.offer(mutation("a", 600_000))
        assertNotNull(pacer.poll(0.0))
        // user edits "a" again before the relay acked the first copy. that ack
        // is never matched any more, so the old bytes must not linger
        pacer.offer(mutation("a", 600_000))
        // plenty of time for the byte bucket, so only the window could block it
        val second = pacer.poll(10_000.0)
        assertNotNull("second copy of the same object must not wait on the first", second)
        assertEquals(1, pacer.inFlightCount)
        pacer.release("a")
        assertEquals(0, pacer.inFlightCount)
        // the window is empty again: a full 1 MiB frame for another object goes alone
        pacer.offer(mutation("b", 1_000_000))
        pacer.offer(mutation("c", 100_000))
        assertNotNull(pacer.poll(20_000.0))
        // b is in flight with 1,000,000 bytes, c doesn't fit next to it
        assertNull(pacer.poll(40_000.0))
        pacer.release("b")
        assertNotNull(pacer.poll(40_000.0))
    }

    @Test
    fun repeatedSmallEditsDontLeakWindowBytes() {
        val pacer = SyncOutboundPacer<String>(0.0)
        var now = 0.0
        // 2,000 superseding edits of one object, none of them acked
        repeat(2_000) {
            pacer.offer(mutation("dragged", 1_500))
            now += 1_000.0
            assertNotNull("edit $it", pacer.poll(now))
        }
        // one ack for the newest copy frees the whole window
        pacer.release("dragged")
        assertEquals(0, pacer.inFlightCount)
        // a frame of exactly the window size now goes next to a small one in flight
        pacer.offer(mutation("small", 1_000))
        assertNotNull(pacer.poll(now + 10_000.0))
        pacer.offer(mutation("big", (SyncOutboundPacer.MAX_IN_FLIGHT_BYTES - 1_000).toInt()))
        assertNotNull(pacer.poll(now + 20_000.0))
    }
}
