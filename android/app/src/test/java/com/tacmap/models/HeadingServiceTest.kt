package com.tacmap.models

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingServiceTest {
    @Test
    fun northReferencesExposeConventionalBearingSuffixes() {
        assertEquals("T", HeadingNorthReference.TRUE_NORTH.displaySuffix)
        assertEquals("M", HeadingNorthReference.MAGNETIC_NORTH.displaySuffix)
        assertEquals("true north", HeadingNorthReference.TRUE_NORTH.accessibilityLabel)
        assertEquals("magnetic north", HeadingNorthReference.MAGNETIC_NORTH.accessibilityLabel)
    }

    @Test
    fun headingErrorEstimateRejectsOnlyKnownPoorReadings() {
        assertEquals(true, HeadingService.hasUsableHeadingError(floatArrayOf(0f, 0f, 0f, 1f)))
        assertEquals(
            true,
            HeadingService.hasUsableHeadingError(floatArrayOf(0f, 0f, 0f, 1f, -1f)),
        )
        assertEquals(
            true,
            HeadingService.hasUsableHeadingError(
                floatArrayOf(0f, 0f, 0f, 1f, Math.toRadians(20.0).toFloat()),
            ),
        )
        assertEquals(
            false,
            HeadingService.hasUsableHeadingError(
                floatArrayOf(0f, 0f, 0f, 1f, Math.toRadians(80.0).toFloat()),
            ),
        )
    }

    @Test
    fun displayRotationRemapsSensorAxes() {
        assertEquals(
            SensorManager.AXIS_X to SensorManager.AXIS_Y,
            HeadingService.displayAxes(Surface.ROTATION_0),
        )
        assertEquals(
            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X,
            HeadingService.displayAxes(Surface.ROTATION_90),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y,
            HeadingService.displayAxes(Surface.ROTATION_180),
        )
        assertEquals(
            SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X,
            HeadingService.displayAxes(Surface.ROTATION_270),
        )
    }
}
