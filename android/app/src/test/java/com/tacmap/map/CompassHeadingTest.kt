package com.tacmap.map

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassHeadingTest {

    @Test
    fun cardinalHeadingsUseNatoMils() {
        assertEquals(0, mapHeadingMils(0.0))
        assertEquals(1600, mapHeadingMils(90.0))
        assertEquals(3200, mapHeadingMils(180.0))
        assertEquals(4800, mapHeadingMils(270.0))
    }

    @Test
    fun negativeAndWraparoundHeadingsNormalize() {
        assertEquals(4800, mapHeadingMils(-90.0))
        assertEquals(0, mapHeadingMils(360.0))
        assertEquals(0, mapHeadingMils(359.99))
    }

    @Test
    fun headingRoundsToNearestMilInsteadOfTruncating() {
        assertEquals(0, mapHeadingMils(0.49 * 360.0 / 6400.0))
        assertEquals(1, mapHeadingMils(0.51 * 360.0 / 6400.0))
    }
}
