package com.tacmap.calibration

import com.tacmap.localization.L10n

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PdfPageInfo(
    val pageWidth: Int,
    val pageHeight: Int
) {
    val aspectRatio: Double get() = pageWidth.toDouble() / pageHeight.toDouble()
}

data class RenderedPdfPage(
    val bitmap: Bitmap,
    val info: PdfPageInfo
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

object PdfPageRenderer {
    private const val MAX_RENDER_DIMENSION_PX = 4096
    private const val MAX_RENDER_BYTES = 32L * 1024L * 1024L
    private const val MAX_REGION_DIMENSION_PX = 2048
    private const val MAX_REGION_BYTES = 16L * 1024L * 1024L

    fun firstPageInfo(context: Context, uri: Uri): PdfPageInfo =
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    PdfPageInfo(page.width, page.height)
                }
            }
        }

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
                        RenderedPdfPage(bitmap, PdfPageInfo(page.width, page.height))
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }

    fun renderFirstPageRegion(
        context: Context,
        uri: Uri,
        pageRect: RectF,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap =
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    require(pageRect.width() > 0f && pageRect.height() > 0f) {
                        L10n.text("PDF render region must have positive size.")
                    }
                    requireBoundedOutput(outputWidth, outputHeight)
                    val bitmap = Bitmap.createBitmap(
                        outputWidth,
                        outputHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        // Map the requested region onto the whole tile. Region can
                        // extend past page edge for tiles that straddle the sheet
                        // boundary. PdfRenderer only paints where page content exists
                        // so off-page margins stay white and on-page content keeps
                        // correct scale, no edge-tile stretching.
                        val matrix = Matrix().apply {
                            setRectToRect(
                                pageRect,
                                RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
                                Matrix.ScaleToFit.FILL
                            )
                        }
                        page.render(
                            bitmap,
                            Rect(0, 0, bitmap.width, bitmap.height),
                            matrix,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                        )
                        bitmap
                    } catch (failure: Throwable) {
                        bitmap.recycle()
                        throw failure
                    }
                }
            }
        }

    /** One horizontal render strip: [pageRect] (PDF-pixel space, y-down)
     *  mapped onto [dest] band of the output tile. */
    data class RenderStrip(val pageRect: RectF, val dest: RectF)

    /**
     * Render a tile as a stack of horizontal [strips]. Each strip maps its own
     * PDF region onto its dest band so a tile spanning several degrees of
     * latitude stays Mercator-correct (a single region+FILL would warp it
     * since tile rows are linear in Mercator-Y, not latitude). Small high-zoom
     * tiles pass a single strip, same cost as [renderFirstPageRegion].
     *
     * Each strip re-opens page 0 (only one page open at a time; a second
     * `render` on the same page throws on some devices) - cheap next to
     * the one-time PDF parse, and multi-strip tiles only happen at low zooms.
     */
    fun renderFirstPageStrips(
        context: Context,
        uri: Uri,
        strips: List<RenderStrip>,
        outputWidth: Int,
        outputHeight: Int
    ): Bitmap =
        openDescriptor(context, uri).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                requireBoundedOutput(outputWidth, outputHeight)
                val bitmap = Bitmap.createBitmap(
                    outputWidth,
                    outputHeight,
                    Bitmap.Config.ARGB_8888
                )
                try {
                    bitmap.eraseColor(Color.WHITE)
                    for (s in strips) {
                        if (s.pageRect.width() <= 0f || s.pageRect.height() <= 0f) continue
                        if (s.dest.width() <= 0f || s.dest.height() <= 0f) continue
                        val matrix = Matrix().apply {
                            setRectToRect(s.pageRect, s.dest, Matrix.ScaleToFit.FILL)
                        }
                        val clip = Rect(
                            s.dest.left.roundToInt().coerceIn(0, outputWidth),
                            s.dest.top.roundToInt().coerceIn(0, outputHeight),
                            s.dest.right.roundToInt().coerceIn(0, outputWidth),
                            s.dest.bottom.roundToInt().coerceIn(0, outputHeight)
                        )
                        if (clip.width() <= 0 || clip.height() <= 0) continue
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
