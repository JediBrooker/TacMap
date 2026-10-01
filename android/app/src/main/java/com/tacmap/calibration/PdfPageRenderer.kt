package com.tacmap.calibration

import com.tacmap.localization.L10n

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/** whole displayed page bitmap + the renderer's int page size it was stretched from */
data class RenderedPdfPage(
    val bitmap: Bitmap,
    val rendererWidth: Int,
    val rendererHeight: Int,
)

internal data class PdfRenderSize(val width: Int, val height: Int) {
    val byteCount: Long get() = width.toLong() * height.toLong() * ARGB_BYTES_PER_PIXEL

    private companion object {
        const val ARGB_BYTES_PER_PIXEL = 4L
    }
}

/**
 * Keep the first-page preview within a predictable ARGB allocation without
 * inflating a small page into a much larger bitmap. PDF vectors remain sharp
 * when baked into MBTiles; this preview only backs the live ground overlay.
 */
internal fun boundedPdfRenderSize(
    pageWidth: Int,
    pageHeight: Int,
    maxDimension: Int = 4096,
    maxBytes: Long = 32L * 1024L * 1024L,
): PdfRenderSize {
    require(pageWidth > 0 && pageHeight > 0) { "PDF page dimensions must be positive." }
    require(maxDimension > 0 && maxBytes >= 4L) { "PDF render limits must be positive." }
    val maxPixels = maxBytes / 4L
    val dimensionScale = maxDimension.toDouble() / max(pageWidth, pageHeight).toDouble()
    val memoryScale = sqrt(maxPixels.toDouble() / (pageWidth.toLong() * pageHeight.toLong()).toDouble())
    val scale = minOf(1.0, dimensionScale, memoryScale)
    return PdfRenderSize(
        width = (pageWidth * scale).toInt().coerceAtLeast(1),
        height = (pageHeight * scale).toInt().coerceAtLeast(1),
    )
}

/**
 * PdfRenderer wrapper. Everything above this layer speaks raw PDF user space (see
 * [PdfPageGeometry]); the transforms here are built from the page geometry so the
 * CropBox origin, /Rotate and pdfium's int page size can't shift the map.
 */
object PdfPageRenderer {
    private const val MAX_RENDER_DIMENSION_PX = 4096
    private const val MAX_RENDER_BYTES = 32L * 1024L * 1024L
    private const val MAX_REGION_DIMENSION_PX = 2048
    private const val MAX_REGION_BYTES = 16L * 1024L * 1024L

    /** PdfRenderer's own (truncated int, rotated) size of page 0 */
    fun firstPageRendererSize(context: Context, uri: Uri): Pair<Int, Int> =
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page -> page.width to page.height }
            }
        }

    /** whole displayed page (visible box, /Rotate applied), stretched onto a bounded bitmap */
    fun renderFirstPage(context: Context, uri: Uri): RenderedPdfPage =
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val size = boundedPdfRenderSize(
                        pageWidth = page.width,
                        pageHeight = page.height,
                        maxDimension = MAX_RENDER_DIMENSION_PX,
                        maxBytes = MAX_RENDER_BYTES,
                    )
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        RenderedPdfPage(bitmap, page.width, page.height)
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }

    /**
     * One band of an output tile: raw user space -> output pixels for rows
     * [destTop, destBottom). Affine in android Matrix order.
     */
    data class RenderStrip(val rawToDest: DoubleArray, val destTop: Int, val destBottom: Int) {
        override fun equals(other: Any?): Boolean =
            other is RenderStrip && rawToDest.contentEquals(other.rawToDest) &&
                destTop == other.destTop && destBottom == other.destBottom

        override fun hashCode(): Int = (rawToDest.contentHashCode() * 31 + destTop) * 31 + destBottom
    }

    /**
     * Render a tile as a stack of horizontal [strips], each with its own raw ->
     * pixel affine, so a tile spanning a lot of latitude stays Mercator-correct.
     * Small high-zoom tiles pass a single strip.
     *
     * Each strip re-opens page 0 (only one page open at a time; a second render
     * on the same page throws on some devices) - cheap next to the one-time PDF
     * parse, and multi-strip tiles only happen at low zooms.
     */
    fun renderFirstPageStrips(
        context: Context,
        uri: Uri,
        geometry: PdfPageGeometry,
        strips: List<RenderStrip>,
        outputWidth: Int,
        outputHeight: Int,
    ): Bitmap =
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                requireBoundedOutput(outputWidth, outputHeight)
                val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    for (s in strips) {
                        val clip = Rect(
                            0,
                            s.destTop.coerceIn(0, outputHeight),
                            outputWidth,
                            s.destBottom.coerceIn(0, outputHeight),
                        )
                        if (clip.height() <= 0) continue
                        val matrix = rendererMatrix(geometry, s.rawToDest) ?: continue
                        renderer.openPage(0).use { page ->
                            page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                    bitmap
                } catch (failure: Throwable) {
                    bitmap.recycle()
                    throw failure
                }
            }
        }

    /**
     * Render the raw user space rect [x0,y0]-[x1,y1] (y up) onto a width x height
     * bitmap, top of the rect at the top of the bitmap. Anything off the page stays
     * white. Handy for calibration zoom-ins and for pinning the convention in tests.
     */
    fun renderRawRegion(
        context: Context,
        uri: Uri,
        geometry: PdfPageGeometry,
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        outputWidth: Int,
        outputHeight: Int,
    ): Bitmap {
        require(x1 > x0 && y1 > y0) { L10n.text("PDF render region must have positive size.") }
        val sx = outputWidth / (x1 - x0)
        val sy = outputHeight / (y1 - y0)
        val rawToDest = doubleArrayOf(sx, 0.0, -x0 * sx, 0.0, -sy, y1 * sy)
        return renderFirstPageStrips(
            context, uri, geometry,
            listOf(RenderStrip(rawToDest, 0, outputHeight)),
            outputWidth, outputHeight,
        )
    }

    private fun rendererMatrix(geometry: PdfPageGeometry, rawToDest: DoubleArray): Matrix? {
        val t = geometry.rendererTransformFor(rawToDest) ?: return null
        return Matrix().apply {
            setValues(
                floatArrayOf(
                    t[0].toFloat(), t[1].toFloat(), t[2].toFloat(),
                    t[3].toFloat(), t[4].toFloat(), t[5].toFloat(),
                    0f, 0f, 1f,
                )
            )
        }
    }

    private fun requireBoundedOutput(outputWidth: Int, outputHeight: Int) {
        require(outputWidth in 1..MAX_REGION_DIMENSION_PX && outputHeight in 1..MAX_REGION_DIMENSION_PX) {
            "PDF render output dimensions are outside the supported range."
        }
        require(outputWidth.toLong() * outputHeight.toLong() * 4L <= MAX_REGION_BYTES) {
            "PDF render output exceeds the supported memory limit."
        }
    }

    private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "file") {
            return ParcelFileDescriptor.open(File(uri.path ?: ""), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return requireNotNull(
            context.contentResolver.openFileDescriptor(uri, "r")
        ) {
            L10n.text("Unable to open PDF URI: %1\$s", uri)
        }
    }
}
