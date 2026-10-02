package com.tacmap.calibration

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/** a PDF rectangle [llx lly urx ury], normalised so ll really is lower left */
@Serializable
data class PdfBox(val llx: Double, val lly: Double, val urx: Double, val ury: Double) {
    val width: Double get() = urx - llx
    val height: Double get() = ury - lly

    fun isValid(): Boolean =
        listOf(llx, lly, urx, ury).all { it.isFinite() && kotlin.math.abs(it) <= MAX_SAFE_PDF_COORDINATE } &&
            width > 0.0 && height > 0.0

    fun intersect(other: PdfBox): PdfBox? =
        PdfBox(max(llx, other.llx), max(lly, other.lly), min(urx, other.urx), min(ury, other.ury)).takeIf { it.isValid() }

    fun corners(): List<PagePoint> =
        listOf(PagePoint(llx, lly), PagePoint(urx, lly), PagePoint(urx, ury), PagePoint(llx, ury))

    companion object {
        fun of(values: List<Double>?): PdfBox? {
            if (values == null || values.size != 4) return null
            return PdfBox(
                min(values[0], values[2]), min(values[1], values[3]),
                max(values[0], values[2]), max(values[1], values[3]),
            ).takeIf { it.isValid() }
        }
    }
}

/**
 * How the page's raw user space (the space every georef lives in) lands in
 * PdfRenderer's own page space, i.e. what Page.render draws with no transform.
 *
 * pdfium (CPDF_Page::GetDisplayMatrix via FPDF_RenderPageBitmapWithMatrix) takes
 * CropBox intersect MediaBox, subtracts its origin, applies /Rotate clockwise, then
 * scales it onto 0..W x 0..H with y down where W/H are the int page size
 * PdfRenderer reports (truncated, not rounded). Pinned on API 36 by
 * PdfRendererConventionInstrumentedTest to under 0.001 pt: offset boxes, /Rotate
 * 90 and fractional A-series sizes all land exactly. Ignoring any of that is the
 * 36 pt / 0.9 pt shift D1-05 / D3-05 / D4-15 were about.
 */
@Serializable
data class PdfPageGeometry(
    val mediaBox: PdfBox,
    val cropBox: PdfBox? = null,
    /** 0, 90, 180 or 270, clockwise, normalised */
    val rotation: Int = 0,
    /** PdfRenderer.Page.getWidth / getHeight */
    val rendererWidth: Int,
    val rendererHeight: Int,
) {
    /** the bit pdfium actually draws */
    val visibleBox: PdfBox get() = cropBox?.let { mediaBox.intersect(it) } ?: mediaBox

    fun isValid(): Boolean =
        mediaBox.isValid() && rotation in setOf(0, 90, 180, 270) && rendererWidth > 0 && rendererHeight > 0

    // raw -> display space before scaling, y up, origin at the displayed lower left
    private fun display(x: Double, y: Double): Pair<Double, Double> {
        val b = visibleBox
        return when (rotation) {
            90 -> (y - b.lly) to (b.urx - x)
            180 -> (b.urx - x) to (b.ury - y)
            270 -> (b.ury - y) to (x - b.llx)
            else -> (x - b.llx) to (y - b.lly)
        }
    }

    private val displayWidth: Double get() = if (rotation % 180 == 0) visibleBox.width else visibleBox.height
    private val displayHeight: Double get() = if (rotation % 180 == 0) visibleBox.height else visibleBox.width

    /** raw user space -> PdfRenderer page pixels (y down) */
    fun rawToRenderer(x: Double, y: Double): Pair<Double, Double> {
        val (dx, dy) = display(x, y)
        return (dx * rendererWidth / displayWidth) to (rendererHeight - dy * rendererHeight / displayHeight)
    }

    /**
     * the same thing as an affine [sx, kx, tx, ky, sy, ty] (android Matrix value
     * order: u = sx*x + kx*y + tx, v = ky*x + sy*y + ty)
     */
    fun rawToRendererAffine(): DoubleArray {
        val (u0, v0) = rawToRenderer(0.0, 0.0)
        val (u1, v1) = rawToRenderer(1.0, 0.0)
        val (u2, v2) = rawToRenderer(0.0, 1.0)
        return doubleArrayOf(u1 - u0, u2 - u0, u0, v1 - v0, v2 - v0, v0)
    }

    /** PdfRenderer page pixels -> raw user space */
    fun rendererToRaw(u: Double, v: Double): PagePoint {
        val m = rawToRendererAffine()
        val det = m[0] * m[4] - m[1] * m[3]
        val du = u - m[2]
        val dv = v - m[5]
        return PagePoint((m[4] * du - m[1] * dv) / det, (-m[3] * du + m[0] * dv) / det)
    }

    /**
     * raw points under the TL, TR, BR, BL corners of a bitmap rendered with no
     * transform (Page.render stretches the whole displayed page onto it)
     */
    fun bitmapCornersRaw(): List<PagePoint> = listOf(
        rendererToRaw(0.0, 0.0),
        rendererToRaw(rendererWidth.toDouble(), 0.0),
        rendererToRaw(rendererWidth.toDouble(), rendererHeight.toDouble()),
        rendererToRaw(0.0, rendererHeight.toDouble()),
    )

    /** default crop for a plain page: the visible box, raw space */
    fun visibleCrop(): List<PagePoint> = visibleBox.corners()

    /**
     * The Page.render transform that puts raw point p at rawToDest(p) in the output
     * bitmap. pdfium applies its own page matrix first, so this is rawToDest after
     * undoing that: T = rawToDest . rawToRenderer^-1. Both in android Matrix order.
     */
    fun rendererTransformFor(rawToDest: DoubleArray): DoubleArray? {
        if (rawToDest.size != 6 || rawToDest.any { !it.isFinite() }) return null
        val g = rawToRendererAffine()
        val det = g[0] * g[4] - g[1] * g[3]
        if (!det.isFinite() || det == 0.0) return null
        // inverse of g
        val ia = g[4] / det
        val ib = -g[1] / det
        val id = -g[3] / det
        val ie = g[0] / det
        val ic = -(ia * g[2] + ib * g[5])
        val iff = -(id * g[2] + ie * g[5])
        val m = rawToDest
        val out = doubleArrayOf(
            m[0] * ia + m[1] * id, m[0] * ib + m[1] * ie, m[0] * ic + m[1] * iff + m[2],
            m[3] * ia + m[4] * id, m[3] * ib + m[4] * ie, m[3] * ic + m[4] * iff + m[5],
        )
        return out.takeIf { v -> v.all { it.isFinite() } }
    }


    companion object {
        /** same as pdfium GetPageRotation: (Rotate / 90) % 4, int division, negatives wrap */
        fun normaliseRotation(raw: Int): Int {
            var quarter = (raw / 90) % 4
            if (quarter < 0) quarter += 4
            return quarter * 90
        }

        /**
         * PDFBox couldn't read the boxes (pdfium could render it though). Assume the
         * renderer's own frame so a plain sheet still calibrates self consistently.
         */
        fun rendererOnly(width: Int, height: Int): PdfPageGeometry =
            PdfPageGeometry(PdfBox(0.0, 0.0, width.toDouble(), height.toDouble()), null, 0, width, height)
    }
}
