package com.tacmap.map

import com.tacmap.settings.MapOrientationMode
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

    @Test
    fun compassTapResetsBeforeEnteringHeadingUp() {
        assertEquals(
            CompassTapAction.RESET_NORTH,
            compassTapAction(MapOrientationMode.NORTH_UP, 25.0, headingAvailable = true),
        )
        assertEquals(
            CompassTapAction.ENABLE_HEADING_UP,
            compassTapAction(MapOrientationMode.NORTH_UP, 359.5, headingAvailable = true),
        )
        assertEquals(
            CompassTapAction.DISABLE_HEADING_UP,
            compassTapAction(MapOrientationMode.HEADING_UP, 140.0, headingAvailable = true),
        )
        assertEquals(
            CompassTapAction.HEADING_UNAVAILABLE,
            compassTapAction(MapOrientationMode.NORTH_UP, 0.0, headingAvailable = false),
        )
    }

    @Test
    fun circularSmoothingCrossesNorthByTheShortPath() {
        assertEquals(0.0, smoothedHeadingDegrees(359.0, 1.0, factor = 0.5), 1e-9)
        assertEquals(359.0, shortestHeadingDelta(1.0, 0.0) + 360.0, 1e-9)
    }
}
