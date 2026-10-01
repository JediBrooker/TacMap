package com.tacmap.calibration

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * Bakes a calibrated [PdfMapSource] into an offline MBTiles raster pyramid
 * on-device, no desktop GDAL needed. Every XYZ tile is cut into latitude strips
 * and each strip's corners go back to raw page space through the georef's exact
 * toPage ([TileWarp]), so the bake follows the sheet's real projection instead
 * of a lon/lat affine. [PdfPageRenderer] maps raw space through the page geometry
 * (crop origin, /Rotate), then [MBTilesWriter] stores PNGs.
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
        val georef = source.calibration?.georef ?: return@withContext null
        val geometry = source.geometry
        val coverage = source.coverage ?: return@withContext null

        val (minZoom, maxZoom) = zoomRange(georef, coverage)
        var totalTiles = 0L
        for (z in minZoom..maxZoom) totalTiles += WebMercatorTiles.tileRange(coverage, z).count
        if (totalTiles !in 1L..MAX_TILES.toLong()) return@withContext null
        val total = totalTiles.toInt()

        val dir = File(context.filesDir, "offline_tiles").apply { mkdirs() }
        val outFile = File(dir, "tacmap-${UUID.randomUUID()}.mbtiles")
        // Bake into a .partial temp, only publish on full success. An
        // interrupted run can't leave a truncated file that later loads
        // as a "valid" but incomplete basemap.
        val tmpFile = File(dir, "${outFile.name}.partial")
        val outPath = outFile.absolutePath
        val writer = MBTilesWriter.create(tmpFile.absolutePath) ?: return@withContext null

        var done = 0
        try {
            writer.writeMetadata(
                name = source.displayName,
                minZoom = minZoom,
                maxZoom = maxZoom,
                bounds = coverage
            )
            check(!writer.hadError) { "Could not write generated map metadata" }
            for (z in minZoom..maxZoom) {
                // honour cancellation (Cancel button) - bail instead of baking
                // every zoom level after the user backed out
                ensureActive()
                val range = WebMercatorTiles.tileRange(coverage, z)
                writer.beginBatch()
                for (tx in range.minX..range.maxX) {
                    for (ty in range.minY..range.maxY) {
                        ensureActive()
                        val strips = buildStrips(georef, geometry, z, tx, ty)
                        // tiles off the page (or off the planet for this crs) stay out of the pack
                        if (strips.isNotEmpty()) {
                            val bmp = PdfPageRenderer.renderFirstPageStrips(
                                context, source.uri, geometry, strips, TILE, TILE
                            )
                            val png = try {
                                ByteArrayOutputStream().use {
                                    check(bmp.compress(Bitmap.CompressFormat.PNG, 100, it)) {
                                        "Could not encode generated PDF tile"
                                    }
                                    it.toByteArray()
                                }
                            } finally {
                                if (!bmp.isRecycled) bmp.recycle()
                            }
                            writer.putTile(z, tx, ty, png)
                            check(!writer.hadError) { "Could not write generated map tile" }
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
                deleteMBTilesArtifacts(tmpFile)
                null
            } else {
                deleteMBTilesSidecars(tmpFile)
                if (tmpFile.renameTo(File(outPath))) outPath
                else { deleteMBTilesArtifacts(tmpFile); null }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // cancelled by user, clean up temp and propagate so the caller's
            // coroutine ends without reporting a failure
            runCatching { writer.close() }
            deleteMBTilesArtifacts(tmpFile)
            throw c
        } catch (failure: Throwable) {
            Log.w(TAG, "Offline PDF tile generation failed (${failure.javaClass.simpleName})")
            runCatching { writer.close() }
            deleteMBTilesArtifacts(tmpFile)
            null
        }
    }

    /**
     * Split a tile into <=0.25 deg latitude strips. Each strip gets its own raw ->
     * pixel affine from three corners pushed through toPage, so a low zoom tile
     * spanning degrees of latitude stays Mercator-correct and the sheet's own
     * projection (UTM, LCC...) is honoured. High zoom tiles are one strip.
     * Empty when the tile doesn't touch the visible page.
     */
    internal fun buildStrips(
        georef: PdfGeoreference,
        geometry: PdfPageGeometry,
        z: Int,
        tx: Int,
        ty: Int,
    ): List<PdfPageRenderer.RenderStrip> {
        val page = geometry.visibleBox
        val corners = listOf(0.0 to 0.0, TILE.toDouble() to 0.0, TILE.toDouble() to TILE.toDouble(), 0.0 to TILE.toDouble())
            .map { (px, py) -> TileWarp.pixelToPage(georef, z, tx, ty, px, py, TILE) ?: return emptyList() }
        if (!overlaps(corners, page)) return emptyList()
        val north = WebMercatorTiles.tileYToLat(ty.toDouble(), z)
        val south = WebMercatorTiles.tileYToLat(ty + 1.0, z)
        val strips = ceil((north - south) / 0.25).toInt().coerceIn(1, 16)
        val out = ArrayList<PdfPageRenderer.RenderStrip>(strips)
        for (i in 0 until strips) {
            // Rounded integer pixel bands so adjacent strips abut with no seam.
            val topPx = (i.toDouble() * TILE / strips).roundToInt()
            val botPx = ((i + 1).toDouble() * TILE / strips).roundToInt()
            if (botPx <= topPx) continue
            val q0 = TileWarp.pixelToPage(georef, z, tx, ty, 0.0, topPx.toDouble(), TILE) ?: continue
            val q1 = TileWarp.pixelToPage(georef, z, tx, ty, TILE.toDouble(), topPx.toDouble(), TILE) ?: continue
            val q2 = TileWarp.pixelToPage(georef, z, tx, ty, 0.0, botPx.toDouble(), TILE) ?: continue
            val q3 = TileWarp.pixelToPage(georef, z, tx, ty, TILE.toDouble(), botPx.toDouble(), TILE) ?: continue
            // strip fully off-page, leave it white
            if (!overlaps(listOf(q0, q1, q3, q2), page)) continue
            val affine = pageToStripPixels(q0, q1, q2, topPx, botPx) ?: continue
            out += PdfPageRenderer.RenderStrip(affine, topPx, botPx)
        }
        return out
    }

    /**
     * raw page -> tile pixels for one strip, pinned by three corners: q0 at
     * (0, top), q1 at (TILE, top), q2 at (0, bottom). Android Matrix order.
     */
    internal fun pageToStripPixels(q0: PagePoint, q1: PagePoint, q2: PagePoint, top: Int, bottom: Int): DoubleArray? {
        val ux = q1.x - q0.x
        val uy = q1.y - q0.y
        val vx = q2.x - q0.x
        val vy = q2.y - q0.y
        val det = ux * vy - vx * uy
        if (!det.isFinite() || det == 0.0) return null
        // (s, t) = inverse([u v]) * (p - q0), pixel = (TILE * s, top + (bottom - top) * t)
        val sa = vy / det
        val sb = -vx / det
        val ta = -uy / det
        val tb = ux / det
        val h = (bottom - top).toDouble()
        val w = TILE.toDouble()
        val out = doubleArrayOf(
            w * sa, w * sb, -w * (sa * q0.x + sb * q0.y),
            h * ta, h * tb, top - h * (ta * q0.x + tb * q0.y),
        )
        return out.takeIf { v -> v.all { it.isFinite() } }
    }

    private fun overlaps(quad: List<PagePoint>, box: PdfBox): Boolean {
        val minX = quad.minOf { it.x }
        val maxX = quad.maxOf { it.x }
        val minY = quad.minOf { it.y }
        val maxY = quad.maxOf { it.y }
        val ovW = minOf(maxX, box.urx) - maxOf(minX, box.llx)
        val ovH = minOf(maxY, box.ury) - maxOf(minY, box.lly)
        // need a real sliver of page, not an all-margin band
        return ovW >= 0.5 && ovH >= 0.5
    }

    /** Min zoom (coverage roughly fits one tile) up to native-res max, capped
     *  by total-tile budget so a huge sheet can't generate forever. */
    private fun zoomRange(georef: PdfGeoreference, coverage: Wgs84Bounds): Pair<Int, Int> {
        val lonSpan = abs(coverage.longitudeSpan).coerceAtLeast(1e-9)
        val crop = georef.cropBounds()
        val pxPerDeg = (crop[2] - crop[0]) / lonSpan
        var maxZoom = floor(log2(pxPerDeg * 360.0 / TILE)).toInt().coerceIn(1, 19)

        var minZoom = 0
        for (z in 0..maxZoom) {
            if (WebMercatorTiles.tileRange(coverage, z).count <= 4L) minZoom = z else break
        }
        minZoom = minZoom.coerceAtMost(maxZoom)

        while (maxZoom > minZoom) {
            var total = 0L
            for (z in minZoom..maxZoom) total += WebMercatorTiles.tileRange(coverage, z).count
            if (total <= MAX_TILES.toLong()) break
            maxZoom--
        }
        return minZoom to maxZoom
    }

    private const val TILE = 256
    private const val MAX_TILES = 2500
    private const val TAG = "PdfTiler"
}
