package com.tacticalmaps.calibration

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
    /// Larger than the original 2048 because the rendered bitmap is
    /// stretched across the map's ground overlay and any zoom past
    /// the per-pixel level of this bitmap shows up as blur. 4096
    /// gives enough resolution for a few extra zoom steps while
    /// staying well under the 64MB (4096*4096*4 ≈ 64MB) ARGB limit.
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
                    val safePageRect = RectF(
                        pageRect.left.coerceIn(0f, page.width.toFloat()),
                        pageRect.top.coerceIn(0f, page.height.toFloat()),
                        pageRect.right.coerceIn(0f, page.width.toFloat()),
                        pageRect.bottom.coerceIn(0f, page.height.toFloat())
                    )
                    require(safePageRect.width() > 0f && safePageRect.height() > 0f) {
                        "PDF render region must have positive size."
                    }

                    val bitmap = Bitmap.createBitmap(
                        outputWidth.coerceAtLeast(1),
                        outputHeight.coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        setRectToRect(
                            safePageRect,
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
