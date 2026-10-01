package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPageRendererSizingTest {
    @Test
    fun smallPagesAreNeverUpscaled() {
        val size = boundedPdfRenderSize(612, 792)

        assertEquals(612, size.width)
        assertEquals(792, size.height)
        assertEquals(612L * 792L * 4L, size.byteCount)
    }

    @Test
    fun hugePagesPreserveAspectWithinDimensionAndMemoryCaps() {
        val size = boundedPdfRenderSize(12_000, 6_000)

        assertTrue(size.width <= 4096)
        assertTrue(size.height <= 4096)
        assertTrue(size.byteCount <= 32L * 1024L * 1024L)
        assertEquals(2.0, size.width.toDouble() / size.height.toDouble(), 0.002)
    }

    @Test
    fun extremeDimensionsCannotOverflowTheAllocationCalculation() {
        val size = boundedPdfRenderSize(Int.MAX_VALUE, Int.MAX_VALUE)

        assertTrue(size.width > 0)
        assertTrue(size.height > 0)
        assertTrue(size.byteCount <= 32L * 1024L * 1024L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositivePageIsRejected() {
        boundedPdfRenderSize(0, 100)
    }
}
