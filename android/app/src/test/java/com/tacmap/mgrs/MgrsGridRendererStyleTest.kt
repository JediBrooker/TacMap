package com.tacmap.mgrs

import mil.nga.mgrs.grid.GridType
import org.junit.Assert.assertEquals
import org.junit.Test

class MgrsGridRendererStyleTest {

    @Test
    fun lineWidthAddsExactlyOnePhysicalPixelAfterDensityScaling() {
        assertEquals(5f, MgrsGridRenderer.lineWidthPx(GridType.HUNDRED_KILOMETER, 2f), 0f)
        assertEquals(3.6f, MgrsGridRenderer.lineWidthPx(GridType.TEN_KILOMETER, 2f), 0.0001f)
        assertEquals(2.6f, MgrsGridRenderer.lineWidthPx(GridType.KILOMETER, 2f), 0.0001f)
    }
}
