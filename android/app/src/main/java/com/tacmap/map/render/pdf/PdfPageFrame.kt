package com.tacmap.map.render.pdf

import com.tacmap.calibration.PdfPageGeometry
import kotlin.math.abs

/**
 * How pdfium sizes the page it draws with a matrix. Older PdfRenderer builds stretch
 * the page box onto its int (truncated) size, a newer pdfium could use the float
 * size. API 35+ PdfRenderer is a mainline module and can change under us, hence the
 * runtime probe (PdfDisplaySizeProbe) rather than an SDK_INT check.
 */
enum class DisplaySizeMode { FLOAT, TRUNCATED }

/**
 * Raw PDF user space (y up, box origins in, /Rotate ignored, i.e. where every georef
 * lives) -> PdfRenderer's own page pixels (what Page.render draws with an identity
 * matrix). pdfium shows CropBox ∩ MediaBox, origin subtracted, rotated clockwise,
 * scaled onto the display size. So the matrix we hand Page.render is
 * pageToPx ∘ rendererToUser. Pure, unit tested for every rotation.
 */
class PdfPageFrame(
    val geometry: PdfPageGeometry,
    val rendererWidth: Int,
    val rendererHeight: Int,
    val mode: DisplaySizeMode,
) {
    private val box = geometry.visibleBox
    private val wf = box.width
    private val hf = box.height
    private val rotation = geometry.rotation
    /** float size after rotation */
    val rotatedWidth: Double = if (rotation % 180 == 0) wf else hf
    val rotatedHeight: Double = if (rotation % 180 == 0) hf else wf

    val userToRenderer: Affine2 = run {
        // u right, v down from the box's top left, before rotation
        val flip = Affine2(1.0, 0.0, -box.llx, 0.0, -1.0, box.ury)
        val rot = when (rotation) {
            90 -> Affine2(0.0, -1.0, hf, 1.0, 0.0, 0.0)      // (hf - v, u)
            180 -> Affine2(-1.0, 0.0, wf, 0.0, -1.0, hf)     // (wf - u, hf - v)
            270 -> Affine2(0.0, 1.0, 0.0, -1.0, 0.0, wf)     // (v, wf - u)
            else -> Affine2.IDENTITY
        }
        val scale = when (mode) {
            DisplaySizeMode.FLOAT -> Affine2.IDENTITY
            DisplaySizeMode.TRUNCATED -> Affine2.scale(rendererWidth / rotatedWidth, rendererHeight / rotatedHeight)
        }
        flip.then(rot).then(scale)
    }

    val rendererToUser: Affine2 = userToRenderer.inverse() ?: Affine2.IDENTITY

    /** the Page.render matrix that puts raw page point p at pageToPx(p) */
    fun renderMatrix(pageToPx: Affine2): Affine2 = rendererToUser.then(pageToPx)

    /**
     * Fails closed when the page pdfium opened isn't the box PDFBox read (D3-05): the
     * rotated float size and pdfium's int size have to agree within a point.
     */
    fun validate(): PdfRenderFailure? {
        if (!geometry.mediaBox.isValid() || rendererWidth <= 0 || rendererHeight <= 0) return PdfRenderFailure.PAGE_GEOMETRY
        if (abs(rotatedWidth - rendererWidth) > 1.0 || abs(rotatedHeight - rendererHeight) > 1.0) {
            return PdfRenderFailure.PAGE_GEOMETRY
        }
        if (!userToRenderer.isFinite() || userToRenderer.determinant == 0.0) return PdfRenderFailure.PAGE_GEOMETRY
        return null
    }
}
