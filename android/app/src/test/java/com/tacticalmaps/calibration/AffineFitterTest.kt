package com.tacticalmaps.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Mirror of the iOS AffineFitterTests. Generates fiduciaries from a known
 * affine and asserts the Kotlin solver recovers it, so the two platform
 * implementations cannot silently diverge.
 */
class AffineFitterTest {

    private val known = AffineTransform2D(
        a = 0.0001, b = 0.00002, c = -122.5,
        d = -0.00003, e = 0.00009, f = 37.7
    )

    private fun fid(x: Double, y: Double): Fiduciary {
        val c = known.apply(x, y)            // Wgs84Coordinate(latitude, longitude)
        return Fiduciary(pdfX = x, pdfY = y, mgrs = "",
                         latitude = c.latitude, longitude = c.longitude)
    }

    @Test
    fun recoversKnownTransform() {
        val fids = listOf(fid(0.0, 0.0), fid(1000.0, 0.0), fid(0.0, 800.0), fid(1000.0, 800.0))
        val r = AffineFitter.fit(fids)
        assertEquals(known.a, r.transform.a, 1e-9)
        assertEquals(known.b, r.transform.b, 1e-9)
        assertEquals(known.c, r.transform.c, 1e-5)
        assertEquals(known.d, r.transform.d, 1e-9)
        assertEquals(known.e, r.transform.e, 1e-9)
        assertEquals(known.f, r.transform.f, 1e-5)
        assertTrue(r.rmsMetres < 1e-4)
    }

    @Test
    fun colinearThrowsDegenerate() {
        val fids = listOf(
            Fiduciary(pdfX = 0.0,   pdfY = 0.0,   mgrs = "", latitude = 0.0, longitude = 0.0),
            Fiduciary(pdfX = 100.0, pdfY = 100.0, mgrs = "", latitude = 1.0, longitude = 1.0),
            Fiduciary(pdfX = 200.0, pdfY = 200.0, mgrs = "", latitude = 2.0, longitude = 2.0),
        )
        try {
            AffineFitter.fit(fids)
            fail("expected AffineFitError.Degenerate")
        } catch (_: AffineFitError.Degenerate) {
            // expected
        }
    }

    @Test
    fun tooFewFiduciariesThrows() {
        val fids = listOf(
            Fiduciary(pdfX = 0.0, pdfY = 0.0, mgrs = "", latitude = 0.0, longitude = 0.0),
            Fiduciary(pdfX = 1.0, pdfY = 1.0, mgrs = "", latitude = 1.0, longitude = 1.0),
        )
        try {
            AffineFitter.fit(fids)
            fail("expected AffineFitError.TooFewFiduciaries")
        } catch (_: AffineFitError.TooFewFiduciaries) {
            // expected
        }
    }

    @Test
    fun invertedRoundTripsPoint() {
        val inv = known.inverted()!!
        val x = 321.0
        val y = 654.0
        val fwd = known.apply(x, y)                       // (latitude, longitude)
        val back = inv.apply(fwd.longitude, fwd.latitude) // feed (lon, lat) back as (x, y)
        assertEquals(x, back.longitude, 1e-6)
        assertEquals(y, back.latitude, 1e-6)
    }

    @Test
    fun invertedSingularReturnsNull() {
        assertNull(AffineTransform2D(0.0, 0.0, 1.0, 0.0, 0.0, 2.0).inverted())
    }
}
