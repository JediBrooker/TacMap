package com.tacmap.map.render.pdf

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** scheduling + staged numbers from contract E/K. pure */
object PdfRenderScheduling {
    const val VECTOR_SETTLE_MS = 150L
    const val EWMA_ALPHA = 0.3
    const val HEAVY_THRESHOLD_MS = 60.0
    const val MAX_BAKE_JOBS_IN_FLIGHT = 1
    const val ORPHAN_CACHE_TILES = 8
    const val PDFIUM_THREADS = 1

    /** EWMA of wall ms per vector call. light until the first sample */
    class CallTimer {
        private var ewma = Double.NaN

        @Synchronized
        fun record(ms: Double) {
            if (!ms.isFinite() || ms < 0) return
            ewma = if (ewma.isNaN()) ms else EWMA_ALPHA * ms + (1 - EWMA_ALPHA) * ewma
        }

        @get:Synchronized
        val ewmaMs: Double get() = ewma

        @get:Synchronized
        val heavy: Boolean get() = !ewma.isNaN() && ewma > HEAVY_THRESHOLD_MS

        /** back to "no sample yet", tests only */
        @Synchronized
        internal fun reset() {
            ewma = Double.NaN
        }
    }
}

/** the staged path's intermediate raster (contract C), pure numbers */
object PdfStagedPlan {
    const val OVERSAMPLE = 1.25
    const val MAX_PIXELS_FACTOR = 2.0
    /** each squeeze when the ceil pushed W x H over the cap */
    const val SHRINK_FACTOR = 0.99
    /** the cells' bbox grows by this many output px, as page pt that's 2 / required */
    const val PAD_PX = 2.0

    /** page rect [x0, x1] x [y0, y1] the staged draw covers, at [density] px/pt into [width] x [height] */
    class Region(
        val x0: Double, val y0: Double, val x1: Double, val y1: Double,
        val density: Double, val width: Int, val height: Int,
        /** the working, so the stagedRegion fixture can check every step */
        val pad: Double,
        val cap: Double,
        val fromCap: Boolean,
        val d0: Double,
        val width0: Int,
        val height0: Int,
        val maxPx: Long,
        val shrinkSteps: Int,
    )

    /**
     * The staged region, same steps as iOS stagedPlan (G3 + r1, pinned by stagedRegion):
     * the emitted cells' page bbox [cellBbox] grown by 2/required pt, cut to the clip bbox,
     * drawn at min(1.25 x required, cap), then shrunk by 1% steps until W x H really is
     * under floor(2 x job px), the ceil can push it over. null = renderError (R5): no
     * emitted cell, required <= 0 or not finite, or nothing left after the cut. Never a
     * paper white success
     */
    fun region(cellBbox: DoubleArray?, requiredPxPerPt: Double, clipBounds: DoubleArray, jobPixels: Long): Region? {
        if (!(requiredPxPerPt.isFinite() && requiredPxPerPt > 0)) return null
        if (cellBbox == null) return null
        val pad = PAD_PX / requiredPxPerPt
        val x0 = max(cellBbox[0] - pad, clipBounds[0])
        val y0 = max(cellBbox[1] - pad, clipBounds[1])
        val x1 = min(cellBbox[2] + pad, clipBounds[2])
        val y1 = min(cellBbox[3] + pad, clipBounds[3])
        if (!(x1 > x0) || !(y1 > y0)) return null
        val rw = x1 - x0
        val rh = y1 - y0
        val cap = sqrt(MAX_PIXELS_FACTOR * jobPixels / (rw * rh))
        val over = OVERSAMPLE * requiredPxPerPt
        var d = min(over, cap)
        if (!(d > 0) || !d.isFinite()) return null
        fun side(len: Double) = max(1.0, kotlin.math.ceil(len * d)).toLong()
        var w = side(rw)
        var h = side(rh)
        val w0 = w
        val h0 = h
        val d0 = d
        val maxPx = kotlin.math.floor(MAX_PIXELS_FACTOR * jobPixels).toLong()
        var steps = 0
        while (w * h > maxPx && d > 0) {
            d *= SHRINK_FACTOR
            w = side(rw)
            h = side(rh)
            steps++
        }
        return Region(x0, y0, x1, y1, d, w.toInt(), h.toInt(), pad, cap, over > cap, d0, w0.toInt(), h0.toInt(), maxPx, steps)
    }
}
