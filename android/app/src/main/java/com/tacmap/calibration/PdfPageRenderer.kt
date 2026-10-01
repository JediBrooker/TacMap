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

/**
 * The little bits of PdfRenderer that aren't map tiles: the import preflight's page
 * size and a raw user space window for tests and calibration zoom ins. Both go
 * through the pdfium thread like everything else (WP2). Map rendering itself lives
 * in map/render/pdf (PdfTileRenderer), the old single image overlay is gone.
 */
object PdfPageRenderer {
    private const val MAX_REGION_DIMENSION_PX = 2048
    private const val MAX_REGION_BYTES = 16L * 1024L * 1024L

    /** PdfRenderer's own (truncated int, rotated) size of page 0. blocks a worker thread */
    fun firstPageRendererSize(context: Context, uri: Uri): Pair<Int, Int> = runBlocking {
        PdfRenderExecutor.run(PdfRenderExecutor.Band.VISIBLE, background = true) {
            openDescriptor(context, uri).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page -> page.width to page.height }
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
