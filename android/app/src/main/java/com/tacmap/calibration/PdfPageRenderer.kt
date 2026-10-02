package com.tacmap.calibration

import com.tacmap.localization.L10n

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tacmap.map.render.pdf.Affine2
import com.tacmap.map.render.pdf.PdfDisplaySizeProbe
import com.tacmap.map.render.pdf.PdfPageFrame
import com.tacmap.map.render.pdf.PdfRenderExecutor
import kotlinx.coroutines.runBlocking
import java.io.File

/** whole displayed page bitmap + the renderer's int page size it was stretched from */
data class RenderedPdfPage(
    val bitmap: Bitmap,
    val rendererWidth: Int,
    val rendererHeight: Int,
)

internal data class PdfRenderSize(val width: Int, val height: Int) {
    val byteCount: Long get() = width.toLong() * height.toLong() * 4L
}

/**
 * A whole-page bitmap (the page picker thumbnails) inside a predictable ARGB budget,
 * never blown up past the page's own size.
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
    val dimensionScale = maxDimension.toDouble() / kotlin.math.max(pageWidth, pageHeight).toDouble()
    val memoryScale = kotlin.math.sqrt(maxPixels.toDouble() / (pageWidth.toLong() * pageHeight.toLong()).toDouble())
    val scale = minOf(1.0, dimensionScale, memoryScale)
    return PdfRenderSize(
        width = (pageWidth * scale).toInt().coerceAtLeast(1),
        height = (pageHeight * scale).toInt().coerceAtLeast(1),
    )
}

/**
 * The little bits of PdfRenderer that aren't map tiles: the import preflight's page
 * size, page count, the page picker's thumbnails and a raw user space window for
 * tests. All of it goes through the pdfium thread like everything else (WP2). Map
 * rendering itself lives in map/render/pdf (PdfTileRenderer), the old single image
 * overlay and the tiler are gone. Blocking, call these off main.
 */
object PdfPageRenderer {
    private const val MAX_RENDER_DIMENSION_PX = 4096
    private const val MAX_RENDER_BYTES = 32L * 1024L * 1024L
    private const val MAX_REGION_DIMENSION_PX = 2048
    private const val MAX_REGION_BYTES = 16L * 1024L * 1024L

    private fun <T> onPdfiumThread(block: () -> T): T = runBlocking {
        PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE, background = true, block = block)
    }

    /** PdfRenderer's own (truncated int, rotated) size of page 0 */
    fun firstPageRendererSize(context: Context, uri: Uri): Pair<Int, Int> = pageRendererSize(context, uri, 0)

    /** same for any page */
    fun pageRendererSize(context: Context, uri: Uri, pageIndex: Int): Pair<Int, Int> = onPdfiumThread {
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page -> page.width to page.height }
            }
        }
    }

    fun pageCount(context: Context, uri: Uri): Int = onPdfiumThread {
        openDescriptor(context, uri).use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }
    }

    /** whole displayed page (visible box, /Rotate applied), stretched onto a bounded bitmap */
    fun renderFirstPage(context: Context, uri: Uri): RenderedPdfPage = renderPage(context, uri, 0)

    fun renderPage(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        maxDimension: Int = MAX_RENDER_DIMENSION_PX,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): RenderedPdfPage = onPdfiumThread {
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val size = boundedPdfRenderSize(
                        pageWidth = page.width,
                        pageHeight = page.height,
                        maxDimension = maxDimension,
                        maxBytes = MAX_RENDER_BYTES,
                    )
                    // pdfium only renders into ARGB_8888, a 565 thumbnail gets copied down after
                    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = if (config == Bitmap.Config.ARGB_8888) bitmap else {
                            bitmap.copy(config, false).also { bitmap.recycle() }
                        }
                        RenderedPdfPage(out, page.width, page.height)
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }
    }

    /**
     * Render the raw user space rect [x0,y0]-[x1,y1] (y up) onto a width x height
     * bitmap, top of the rect at the top of the bitmap, paper white behind. Uses the
     * same page frame the tile renderer does, so it pins that convention in tests.
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
        require(outputWidth in 1..MAX_REGION_DIMENSION_PX && outputHeight in 1..MAX_REGION_DIMENSION_PX) {
            "PDF render output dimensions are outside the supported range."
        }
        require(outputWidth.toLong() * outputHeight.toLong() * 4L <= MAX_REGION_BYTES) {
            "PDF render output exceeds the supported memory limit."
        }
        val sx = outputWidth / (x1 - x0)
        val sy = outputHeight / (y1 - y0)
        val rawToDest = Affine2(sx, 0.0, -x0 * sx, 0.0, -sy, y1 * sy)
        return runBlocking {
            PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE, background = true) {
                openDescriptor(context, uri).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.openPage(0).use { page ->
                            val frame = PdfPageFrame(geometry, page.width, page.height, PdfDisplaySizeProbe.mode(context.cacheDir))
                            val bitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.eraseColor(Color.WHITE)
                                val m = Matrix().apply { setValues(frame.renderMatrix(rawToDest).toFloatArray9()) }
                                page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bitmap
                            } catch (failure: Throwable) {
                                bitmap.recycle()
                                throw failure
                            }
                        }
                    }
                }
            }
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
