package com.tacmap.calibration

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

object PdfPageRenderer {
    // Bumped from the original 2048 b/c the bitmap gets stretched across
    // the map's ground overlay and any zoom past per-pixel shows as blur.
    // 4096 gives enough resolution for a few extra zoom steps while
    // staying under the 64MB (4096*4096*4 ~ 64MB) ARGB limit.
    private const val MAX_RENDER_DIMENSION_PX = 4096

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
                    val maxPageDimension = max(page.width, page.height).coerceAtLeast(1)
                    val scale = MAX_RENDER_DIMENSION_PX.toDouble() / maxPageDimension.toDouble()
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderedPdfPage(bitmap, PdfPageInfo(page.width, page.height))
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
                        "PDF render region must have positive size."
                    }

                    val bitmap = Bitmap.createBitmap(
                        outputWidth.coerceAtLeast(1),
                        outputHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
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
                val bitmap = Bitmap.createBitmap(
                    outputWidth.coerceAtLeast(1),
                    outputHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(Color.WHITE)
                for (s in strips) {
                    if (s.pageRect.width() <= 0f || s.pageRect.height() <= 0f) continue
                    if (s.dest.width() <= 0f || s.dest.height() <= 0f) continue
                    val matrix = Matrix().apply {
                        setRectToRect(s.pageRect, s.dest, Matrix.ScaleToFit.FILL)
                    }
                    val clip = Rect(
                        s.dest.left.roundToInt(),
                        s.dest.top.roundToInt(),
                        s.dest.right.roundToInt(),
                        s.dest.bottom.roundToInt()
                    )
                    renderer.openPage(0).use { page ->
                        page.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                bitmap
            }
        }

    private fun openDescriptor(context: Context, uri: Uri): ParcelFileDescriptor {
        if (uri.scheme == "file") {
            return ParcelFileDescriptor.open(File(uri.path ?: ""), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        return requireNotNull(
            context.contentResolver.openFileDescriptor(uri, "r")
        ) {
            "Unable to open PDF URI: $uri"
        }
    }
}
