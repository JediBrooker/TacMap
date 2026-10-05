package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who records a finished bake. A bake is app scoped and can outlive the MapViewModel that
 * started it, and a second MainActivity can come and go on top of the first, so the
 * recorder is looked up when the publish attaches and closing a screen hands recording
 * back to the one still alive under it
 */
class PdfBakeRecordersTest {
    private class Screen : PdfBakeRecorder {
        val attached = mutableListOf<PersistedPdfBake>()
        var detached = 0

        override fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach {
            attached += bake
            return PdfBakeAttach.Attached
        }

        override fun detach(entryId: String?, bake: PersistedPdfBake): Boolean {
            detached++
            return true
        }
    }

    private val bake = PersistedPdfBake("tacmap-bake-1.mbtiles", "a".repeat(64), 0, 12, 256, 1)

    @Test
    fun aBakeRecordsThroughWhoeverIsAliveWhenItPublishesNotWhoStartedIt() {
        val recorders = PdfBakeRecorders()
        val first = Screen()
        recorders.register(first)
        // what runBake hands PdfBaker when the bake starts
        val bakeGets = recorders.atPublish
        // the screen that started it goes, a new one comes up meanwhile
        val second = Screen()
        recorders.register(second)
        recorders.unregister(first)

        assertEquals(PdfBakeAttach.Attached, bakeGets.attach("e", "sha256:x", "t", bake))
        assertTrue(bakeGets.detach("e", bake))
        assertTrue("a cleared view model recorded it", first.attached.isEmpty())
        assertEquals(0, first.detached)
        assertEquals(listOf(bake), second.attached)
        assertEquals(1, second.detached)
    }

    @Test
    fun closingTheNewerScreenHandsRecordingBackToTheOneUnderIt() {
        val recorders = PdfBakeRecorders()
        val under = Screen()
        val top = Screen()
        recorders.register(under)
        recorders.register(top)
        assertSame(top, recorders.current)
        recorders.unregister(top)
        assertSame("the surviving screen lost its recorder", under, recorders.current)
        assertEquals(PdfBakeAttach.Attached, recorders.atPublish.attach("e", null, "t", bake))
        assertEquals(listOf(bake), under.attached)
    }

    @Test
    fun comingBackToTheFrontMakesAScreenTheRecorderAgain() {
        val recorders = PdfBakeRecorders()
        val a = Screen()
        val b = Screen()
        recorders.register(a)
        recorders.register(b)
        // A's MapScreen composes again (it's resumed): it registers again and goes on top
        recorders.register(a)
        assertSame(a, recorders.current)
        recorders.unregister(a)
        assertSame(b, recorders.current)
    }

    @Test
    fun nobodyAliveFailsClosed() {
        val recorders = PdfBakeRecorders()
        val gone = Screen()
        recorders.register(gone)
        recorders.unregister(gone)
        assertNull(recorders.current)
        assertEquals(PdfBakeAttach.WriteFailed(null), recorders.atPublish.attach("e", null, "t", bake))
        assertFalse(recorders.atPublish.detach("e", bake))
        assertTrue(gone.attached.isEmpty())
    }
}
