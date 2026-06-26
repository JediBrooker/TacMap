package com.tacmap.calibration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2

/**
 * Bakes a calibrated [PdfMapSource] into an offline MBTiles raster pyramid
 * **on-device** — no desktop GDAL step. Maps each Web-Mercator XYZ tile's WGS84
 * box back into PDF pixel space via the inverse calibration affine, renders that
 * region with [PdfPageRenderer], and writes PNG tiles via [MBTilesWriter].
 *
 * Doubles down on TacMap's georeferencing strength: any GeoPDF / calibrated
 * scanned sheet becomes a true offline basemap with zero desktop tooling.
 */
object PdfTiler {

    data class Progress(val done: Int, val total: Int)

    /** Returns the written .mbtiles path, or null on failure / nothing to do. */
    suspend fun generate(
        context: Context,
        source: PdfMapSource,
        onProgress: (Progress) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val info = source.pageInfo ?: return@withContext null
        val transform = when (val c = source.calibration) {
            is Calibration.Fiduciaries -> c.transform
            is Calibration.Parsed -> c.transform
            null -> return@withContext null
        }
        val inverse = transform.inverted() ?: return@withContext null
        val coverage = source.coverage ?: return@withContext null

        val (minZoom, maxZoom) = zoomRange(info, coverage)
        var total = 0
        for (z in minZoom..maxZoom) total += WebMercatorTiles.tileRange(coverage, z).count
        if (total == 0) return@withContext null

        val dir = File(context.filesDir, "offline_tiles").apply { mkdirs() }
        val outPath = File(dir, "tacmap-${System.currentTimeMillis()}.mbtiles").absolutePath
        val writer = MBTilesWriter.create(outPath) ?: return@withContext null
        writer.writeMetadata(
            name = source.displayName,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = coverage
        )

        var done = 0
        try {
            for (z in minZoom..maxZoom) {
                val range = WebMercatorTiles.tileRange(coverage, z)
                writer.beginBatch()
                for (tx in range.minX..range.maxX) {
                    for (ty in range.minY..range.maxY) {
                        val rect = pdfPixelRect(inverse, WebMercatorTiles.tileBounds(z, tx, ty), info)
                        if (rect != null) {
                            val png = runCatching {
                                val bmp = PdfPageRenderer.renderFirstPageRegion(
                                    context, source.uri, rect, TILE, TILE
                                )
                                val bytes = ByteArrayOutputStream().use {
                                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray()
                                }
                                bmp.recycle()
                                bytes
                            }.getOrNull()
                            if (png != null) writer.putTile(z, tx, ty, png)
                        }
                        done++
                        if (done % 16 == 0) onProgress(Progress(done, total))
                    }
                }
                writer.commitBatch()
            }
            onProgress(Progress(total, total))
            outPath
        } catch (_: Throwable) {
            null
        } finally {
            writer.close()
        }
    }

    /** Map a tile's WGS84 box to a clamped PDF-pixel rect, or null if off-page. */
    private fun pdfPixelRect(
        inverse: AffineTransform2D,
        box: Wgs84Bounds,
        info: PdfPageInfo
    ): RectF? {
        // inverse.apply(lon, lat) -> Wgs84Coordinate carrying (pdfX as longitude, pdfY as latitude).
        val corners = listOf(
            inverse.apply(box.southwest.longitude, box.southwest.latitude),
            inverse.apply(box.northeast.longitude, box.southwest.latitude),
            inverse.apply(box.northeast.longitude, box.northeast.latitude),
            inverse.apply(box.southwest.longitude, box.northeast.latitude)
        )
        val xs = corners.map { it.longitude }
        val ys = corners.map { it.latitude }
        var left = xs.min(); var right = xs.max()
        var top = ys.min(); var bottom = ys.max()
        val w = info.pageWidth.toDouble(); val h = info.pageHeight.toDouble()
        if (right < 0 || left > w || bottom < 0 || top > h) return null
        left = left.coerceIn(0.0, w); right = right.coerceIn(0.0, w)
        top = top.coerceIn(0.0, h); bottom = bottom.coerceIn(0.0, h)
        if (right - left < 1.0 || bottom - top < 1.0) return null
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** Min zoom (coverage ~fits one tile) up to a native-resolution max, capped
     *  by a total-tile budget so a huge sheet can't generate forever. */
    private fun zoomRange(info: PdfPageInfo, coverage: Wgs84Bounds): Pair<Int, Int> {
        val lonSpan = abs(coverage.longitudeSpan).coerceAtLeast(1e-9)
        val pxPerDeg = info.pageWidth / lonSpan
        var maxZoom = floor(log2(pxPerDeg * 360.0 / TILE)).toInt().coerceIn(1, 19)

        var minZoom = 0
        for (z in 0..maxZoom) {
            if (WebMercatorTiles.tileRange(coverage, z).count <= 4) minZoom = z else break
        }
        minZoom = minZoom.coerceAtMost(maxZoom)

        while (maxZoom > minZoom) {
            var total = 0
            for (z in minZoom..maxZoom) total += WebMercatorTiles.tileRange(coverage, z).count
            if (total <= MAX_TILES) break
            maxZoom--
        }
        return minZoom to maxZoom
    }

    private const val TILE = 256
    private const val MAX_TILES = 2500
}
