package com.tacmap.map.render.pdf

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.pdf.PdfRenderer
import com.tacmap.map.render.TileIndex
import com.tacmap.map.render.TileSource
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** whole page raster in page space plus its mips, finest first. immutable once built */
internal class PdfBaseRaster(
    val plan: PdfBaseRasterPlan,
    val levels: List<Bitmap>,
)

/** where a job's pixels come from */
internal sealed class PdfRenderSourceKind {
    class Raster(val base: PdfBaseRaster) : PdfRenderSourceKind()
    /** live page on the pdfium thread. [staged] = draw the bbox once then warp it (callers pick it when there's more than one cell) */
    class Vector(val page: PdfRenderer.Page, val frame: PdfPageFrame, val staged: Boolean) : PdfRenderSourceKind()
}

/**
 * The one tile render function, live view and bake alike (plan 02 s2). Per warp
 * cell: integer clip to the cell, draw page (or raster) through the cell's
 * page -> px affine onto paper white. Then, when any part of the job isn't fully
 * inside the sheet, an antialiased mask to the projected clip polygon so off
 * sheet pixels are alpha 0 and the neatline edge is smooth (D3-12, D1-08).
 */
internal object PdfTileRenderer {
    const val RENDERER_VERSION = PdfBakePlan.RENDERER_VERSION

    private val filterPaint = ThreadLocal.withInitial {
        Paint(Paint.FILTER_BITMAP_FLAG).apply { isAntiAlias = false }
    }

    /**
     * Render [job]. Returns every tile in it: a bitmap of tilePx² or [TileSource.EMPTY]
     * for the ones off the sheet. Throws [PdfRenderException] / OutOfMemoryError.
     */
    fun renderJob(
        job: TileJob,
        tilePx: Int,
        plan: PdfWarpPlan,
        footprint: PdfFootprint,
        source: PdfRenderSourceKind,
    ): Map<TileIndex, Bitmap> {
        val tiles = job.tiles().map { TileIndex(it[0], it[1], it[2]) }
        val coverage = tiles.map { footprint.classify(it.z, it.x, it.y) }
        if (plan.cells.isEmpty() || coverage.all { it == TileCoverage.OUTSIDE }) {
            return tiles.associateWith { TileSource.EMPTY }
        }
        val w = job.cols * tilePx
        val h = job.rows * tilePx
        val scratch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            scratch.eraseColor(Color.WHITE)
            when (source) {
                is PdfRenderSourceKind.Raster -> {
                    val level = source.base.plan.mipFor(plan.requiredPxPerPt)
                    drawRaster(scratch, plan, source.base.levels[level], source.base.plan.pageToMip(level))
                }
                is PdfRenderSourceKind.Vector -> {
                    if (source.staged) drawStaged(scratch, plan, footprint, source, w.toLong() * h)
                    else drawDirect(scratch, plan, source)
                }
            }
        } catch (t: Throwable) {
            scratch.recycle()
            throw t
        }
        val root = plan.root
        val needsMask = coverage.any { it != TileCoverage.INSIDE } ||
            plan.leaves.any { it.coverage != TileCoverage.INSIDE } || plan.dropped > 0 ||
            root == null || root[0] != 0 || root[1] != 0 || root[2] != w || root[3] != h
        val out = if (needsMask) mask(scratch, footprint.jobPixelOutline(job, tilePx)) else scratch
        if (out !== scratch) scratch.recycle()
        if (tiles.size == 1) {
            return mapOf(tiles[0] to if (coverage[0] == TileCoverage.OUTSIDE) TileSource.EMPTY.also { out.recycle() } else out)
        }
        val result = LinkedHashMap<TileIndex, Bitmap>(tiles.size)
        for ((i, t) in tiles.withIndex()) {
            result[t] = if (coverage[i] == TileCoverage.OUTSIDE) TileSource.EMPTY
            else Bitmap.createBitmap(out, (t.x - job.x0) * tilePx, (t.y - job.y0) * tilePx, tilePx, tilePx)
        }
        out.recycle()
        return result
    }

    /** one pdfium call per cell, the cell's own matrix and an integer clip */
    private fun drawDirect(scratch: Bitmap, plan: PdfWarpPlan, src: PdfRenderSourceKind.Vector) {
        val m = Matrix()
        for (cell in plan.cells) {
            m.setValues(src.frame.renderMatrix(cell.pageToPx).toFloatArray9())
            src.page.render(scratch, Rect(cell.left, cell.top, cell.right, cell.bottom), m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
    }

    /**
     * Small scale sheets split into lots of cells. Rather than 16+ pdfium calls, draw
     * the cells' page bbox once at 1.25x the density they need (capped at 2x the
     * job's pixels) and warp that per cell like a raster.
     */
    private fun drawStaged(
        scratch: Bitmap,
        plan: PdfWarpPlan,
        footprint: PdfFootprint,
        src: PdfRenderSourceKind.Vector,
        jobPixels: Long,
    ) {
        var x0 = Double.POSITIVE_INFINITY; var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY; var y1 = Double.NEGATIVE_INFINITY
        for (c in plan.cells) for (p in c.quad) {
            x0 = min(x0, p.x); y0 = min(y0, p.y); x1 = max(x1, p.x); y1 = max(y1, p.y)
        }
        val bbox = if (plan.cells.isEmpty()) null else doubleArrayOf(x0, y0, x1, y1)
        // nothing to stage is a failed job (R5), never a blank sheet of paper passed off as the map
        val r = PdfStagedPlan.region(bbox, plan.requiredPxPerPt, footprint.clipBounds, jobPixels)
            ?: throw PdfRenderException(PdfRenderFailure.RENDER_ERROR)
        val sw = r.width
        val sh = r.height
        val staged = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        try {
            // page -> staged px, y down, region onto [0, sw] x [0, sh]
            val sx = sw / (r.x1 - r.x0)
            val sy = sh / (r.y1 - r.y0)
            val pageToStaged = Affine2(sx, 0.0, -r.x0 * sx, 0.0, -sy, r.y1 * sy)
            val m = Matrix()
            m.setValues(src.frame.renderMatrix(pageToStaged).toFloatArray9())
            src.page.render(staged, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            drawRaster(scratch, plan, staged, pageToStaged)
        } finally {
            staged.recycle()
        }
    }

    /** warp a page space raster per cell: clip to the cell, raster px -> page -> job px */
    private fun drawRaster(scratch: Bitmap, plan: PdfWarpPlan, raster: Bitmap, pageToRaster: Affine2) {
        val rasterToPage = pageToRaster.inverse() ?: return
        val canvas = Canvas(scratch)
        val m = Matrix()
        val paint = filterPaint.get()!!
        for (cell in plan.cells) {
            canvas.save()
            canvas.clipRect(cell.left, cell.top, cell.right, cell.bottom)
            m.setValues(rasterToPage.then(cell.pageToPx).toFloatArray9())
            canvas.drawBitmap(raster, m, paint)
            canvas.restore()
        }
    }

    /** keep only what's inside the projected clip polygon, antialiased edge, alpha 0 outside */
    private fun mask(scratch: Bitmap, outline: DoubleArray): Bitmap {
        val out = Bitmap.createBitmap(scratch.width, scratch.height, Bitmap.Config.ARGB_8888)
        val path = Path()
        path.moveTo(outline[0].toFloat(), outline[1].toFloat())
        var i = 2
        while (i < outline.size) {
            path.lineTo(outline[i].toFloat(), outline[i + 1].toFloat())
            i += 2
        }
        path.close()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(scratch, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        Canvas(out).drawPath(path, paint)
        return out
    }

    /**
     * The whole clip bbox once plus mips. pdfium thread for the page draw; the mips are
     * cheap 2x downscales done by the caller off that thread.
     *
     * Same result as iOS renderRegion (G3): inside the clip polygon it's paper white with
     * the page on top, outside it stays alpha 0, so marks in the bbox but off the sheet
     * never reach the blank check. pdfium can only clip to a rect, so on one bitmap:
     * page onto transparent, paper slid in behind it inside the polygon, then everything
     * outside the polygon cleared. Saves holding a second 24 MB bitmap for a mask
     */
    fun renderBase(page: PdfRenderer.Page, frame: PdfPageFrame, plan: PdfBaseRasterPlan, clip: List<com.tacmap.calibration.PagePoint>): Bitmap {
        val bmp = Bitmap.createBitmap(plan.width, plan.height, Bitmap.Config.ARGB_8888)
        try {
            val toPx = plan.pageToMip(0)
            val m = Matrix()
            m.setValues(frame.renderMatrix(toPx).toFloatArray9())
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val path = Path()
            for ((i, p) in clip.withIndex()) {
                val x = (toPx.a * p.x + toPx.b * p.y + toPx.c).toFloat()
                val y = (toPx.d * p.x + toPx.e * p.y + toPx.f).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val canvas = Canvas(bmp)
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OVER)
            })
            path.fillType = Path.FillType.INVERSE_WINDING
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            })
            return bmp
        } catch (t: Throwable) {
            bmp.recycle()
            throw t
        }
    }

    /** ceil halving down to <= 256 on the long side, bilinear at exactly 2x is a box filter */
    fun buildMips(full: Bitmap, plan: PdfBaseRasterPlan): List<Bitmap> {
        val out = ArrayList<Bitmap>(plan.mips.size)
        out += full
        for (i in 1 until plan.mips.size) {
            val (mw, mh) = plan.mips[i].let { it[0] to it[1] }
            out += Bitmap.createScaledBitmap(out.last(), mw, mh, true)
        }
        return out
    }

    /** contract G blank rule on the full res raster, every 4th pixel, a row at a time */
    fun isBlank(full: Bitmap): Boolean {
        val stride = PdfRenderRules.BLANK_SAMPLE_STRIDE
        val w = full.width
        val row = IntArray(w)
        var y = 0
        while (y < full.height) {
            full.getPixels(row, 0, w, 0, y, w, 1)
            if (!PdfRenderRules.isBlank(row, w, 1, stride)) return false
            y += stride
        }
        return true
    }
}
