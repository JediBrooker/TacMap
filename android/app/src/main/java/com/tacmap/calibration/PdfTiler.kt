package com.tacmap.calibration

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Bakes a calibrated [PdfMapSource] into an offline MBTiles raster pyramid
 * on-device, no desktop GDAL needed. Maps each Web-Mercator XYZ tile's WGS84
 * box back into PDF pixel space via the inverse calibration affine, renders
 * that region with [PdfPageRenderer], writes PNG tiles via [MBTilesWriter].
 *
 * Any GeoPDF or calibrated scanned sheet becomes a true offline basemap
 * with zero desktop tooling.
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
        val outFile = File(dir, "tacmap-${System.currentTimeMillis()}.mbtiles")
        // Bake into a .partial temp, only publish on full success. An
        // interrupted run can't leave a truncated file that later loads
        // as a "valid" but incomplete basemap.
        val tmpFile = File(dir, "${outFile.name}.partial")
        val outPath = outFile.absolutePath
        val writer = MBTilesWriter.create(tmpFile.absolutePath) ?: return@withContext null
        writer.writeMetadata(
            name = source.displayName,
            minZoom = minZoom,
            maxZoom = maxZoom,
            bounds = coverage
        )

        var done = 0
        try {
            for (z in minZoom..maxZoom) {
                // honour cancellation (Cancel button) - bail instead of baking
                // every zoom level after the user backed out
                ensureActive()
                val range = WebMercatorTiles.tileRange(coverage, z)
                writer.beginBatch()
                for (tx in range.minX..range.maxX) {
                    for (ty in range.minY..range.maxY) {
                        val box = WebMercatorTiles.tileBounds(z, tx, ty)
                        // whole-tile off-page gate; strips re-derive per-band rects
                        if (pdfPixelRect(inverse, box, info) != null) {
                            val strips = buildStrips(inverse, box, z, ty, info)
                            val png = if (strips.isEmpty()) null else runCatching {
                                val bmp = PdfPageRenderer.renderFirstPageStrips(
                                    context, source.uri, strips, TILE, TILE
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
            writer.close()
            // tile/metadata write failed mid-bake (e.g. disk full), don't
            // pass a half-baked file off as complete basemap
            if (writer.hadError) {
                tmpFile.delete()
                null
            } else {
                File(outPath).delete()
                if (tmpFile.renameTo(File(outPath))) outPath else { tmpFile.delete(); null }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // cancelled by user, clean up temp and propagate so the caller's
            // coroutine ends without reporting a failure
            writer.close()
            tmpFile.delete()
            throw c
        } catch (_: Throwable) {
            writer.close()
            tmpFile.delete()
            null
        }
    }

    /** Map tile's WGS84 box to clamped PDF-pixel rect, null if off-page. */
    private fun pdfPixelRect(
        inverse: AffineTransform2D,
        box: Wgs84Bounds,
        info: PdfPageInfo
    ): RectF? = pageRectForBand(
        inverse,
        west = box.southwest.longitude, east = box.northeast.longitude,
        north = box.northeast.latitude, south = box.southwest.latitude,
        info = info
    )

    /**
     * Split a tile into <=0.25 deg latitude horizontal strips, each mapped
     * through the calibration affine at its true latitude edges (recovered
     * from Mercator tile-Y via [WebMercatorTiles.tileYToLat]). A single
     * region+FILL over the whole tile would warp anything spanning >~2 deg
     * of latitude (large sheets at low zoom); strips cut residual to
     * sub-pixel. Small high-zoom tiles yield one strip, same cost.
     */
    private fun buildStrips(
        inverse: AffineTransform2D,
        box: Wgs84Bounds,
        z: Int,
        ty: Int,
        info: PdfPageInfo
    ): List<PdfPageRenderer.RenderStrip> {
        val north = box.northeast.latitude
        val south = box.southwest.latitude
        val west = box.southwest.longitude
        val east = box.northeast.longitude
        val strips = ceil((north - south) / 0.25).toInt().coerceIn(1, 16)
        val out = ArrayList<PdfPageRenderer.RenderStrip>(strips)
        for (i in 0 until strips) {
            // Rounded integer pixel bands so adjacent strips abut with no seam.
            val topPx = (i.toDouble() * TILE / strips).roundToInt()
            val botPx = ((i + 1).toDouble() * TILE / strips).roundToInt()
            if (botPx <= topPx) continue
            // Strip i covers tile-Y [ty+i/strips, ty+(i+1)/strips]; convert those
            // Mercator edges back to their true latitudes.
            val bandNorth = WebMercatorTiles.tileYToLat(ty + i.toDouble() / strips, z)
            val bandSouth = WebMercatorTiles.tileYToLat(ty + (i + 1).toDouble() / strips, z)
            val pageRect = pageRectForBand(inverse, west, east, bandNorth, bandSouth, info)
                ?: continue // strip fully off-page, leave it white
            out += PdfPageRenderer.RenderStrip(
                pageRect = pageRect,
                dest = RectF(0f, topPx.toFloat(), TILE.toFloat(), botPx.toFloat())
            )
        }
        return out
    }

    /** PDF-pixel rect (y-down) for one lat/lon band, null if no meaningful
     *  overlap with the page. */
    private fun pageRectForBand(
        inverse: AffineTransform2D,
        west: Double, east: Double, north: Double, south: Double,
        info: PdfPageInfo
    ): RectF? {
        // inverse.apply(lon, lat) -> Wgs84Coordinate with (pdfX as longitude, pdfY as latitude)
        val corners = listOf(
            inverse.apply(west, south),
            inverse.apply(east, south),
            inverse.apply(east, north),
            inverse.apply(west, north)
        )
        val xs = corners.map { it.longitude }
        val ys = corners.map { it.latitude }
        val left = xs.min(); val right = xs.max()
        val w = info.pageWidth.toDouble(); val h = info.pageHeight.toDouble()
        // Calibration affine is in PDF user space (y-up, origin bottom-left,
        // per GeoPDF georeferencing dicts) but PdfRenderer draws y-down
        // (origin top-left). Flip Y here - feeding y-up coords straight to
        // the renderer mirrored every tile north-for-south. iOS does the
        // same flip in its CoreGraphics context.
        val top = h - ys.max()
        val bottom = h - ys.min()
        // skip bands that don't overlap the page at all
        if (right <= 0.0 || left >= w || bottom <= 0.0 || top >= h) return null
        // need non-trivial on-page overlap, don't emit all-margin bands
        val ovW = minOf(w, right) - maxOf(0.0, left)
        val ovH = minOf(h, bottom) - maxOf(0.0, top)
        if (ovW < 0.5 || ovH < 0.5) return null
        // return FULL (unclamped) rect: renderer maps it onto the tile band
        // so on-page content keeps true scale/position, off-page stays white
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    /** Min zoom (coverage roughly fits one tile) up to native-res max, capped
     *  by total-tile budget so a huge sheet can't generate forever. */
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
