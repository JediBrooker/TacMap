package com.tacmap.map

import com.tacmap.map.MbtilesOpenRequests.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** s14.2: which off main MBTiles open still gets to go up when it comes back */
class MbtilesOpenRequestsTest {
    @Test
    fun onlyTheNewestPickGoesUpAndTheOneItReplacedIsClosed() {
        val opens = MbtilesOpenRequests()
        val a = opens.begin("a", generation = 4)
        val b = opens.begin("b", generation = 4)
        // B came back first, A after it: A's closed, never written
        assertEquals(Verdict.CURRENT, opens.finish(b, generation = 4))
        assertEquals(Verdict.SUPERSEDED, opens.finish(a, generation = 4))
        assertNull(opens.pending)
    }

    @Test
    fun reopeningTheSamePackStillSupersedesTheFirstOpen() {
        val opens = MbtilesOpenRequests()
        val first = opens.begin("a", 1)
        val again = opens.begin("a", 1)
        assertEquals(Verdict.SUPERSEDED, opens.finish(first, 1))
        assertEquals("a", opens.pending?.entryId)
        assertEquals(Verdict.CURRENT, opens.finish(again, 1))
    }

    @Test
    fun somethingElseGoingUpSupersedesWhatsOpening() {
        val opens = MbtilesOpenRequests()
        val a = opens.begin("a", 1)
        assertEquals(a, opens.supersede())
        assertNull(opens.supersede())
        assertEquals(Verdict.SUPERSEDED, opens.finish(a, 1))
    }

    @Test
    fun aLibraryWrittenMeanwhileOrLockedNeverTakesTheResult() {
        val opens = MbtilesOpenRequests()
        val moved = opens.begin("a", 7)
        assertEquals(Verdict.MOVED, opens.finish(moved, 8))
        val locked = opens.begin("a", 8)
        // locked, cleared or not loaded: nothing can take it
        assertEquals(Verdict.SUPERSEDED, opens.finish(locked, null))
        assertNull(opens.pending)
        // and a result only counts once
        val once = opens.begin("b", 8)
        assertEquals(Verdict.CURRENT, opens.finish(once, 8))
        assertEquals(Verdict.SUPERSEDED, opens.finish(once, 8))
    }
}
