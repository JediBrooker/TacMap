package com.tacticalmaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import com.tacticalmaps.mgrs.MgrsGridRenderer
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Renders MGRS grid lines as raster tiles. Replaces the previous approach of
 * emitting one Compose Polyline per segment, which triggered hundreds of
 * GoogleMap.addPolyline() IPC calls and exhausted the Java heap.
 *
 * getTile() is called on a background thread by the Maps SDK; MgrsGridRenderer
 * is stateless so no locking is needed.
 */
class MgrsGridTileProvider(private val density: Float) : TileProvider {

    // Cache up to 16 rendered tiles (~16 × a few KB of PNG = negligible memory)
    private val cache = LruCache<Long, ByteArray>(16)

    override fun getTile(x: Int, y: Int, zoom: Int): Tile {
        val key = tileKey(x, y, zoom)
        cache.get(key)?.let { return Tile(TILE_SIZE, TILE_SIZE, it) }
        val bytes = renderTile(x, y, zoom) ?: return TileProvider.NO_TILE
        cache.put(key, bytes)
        return Tile(TILE_SIZE, TILE_SIZE, bytes)
    }

    private fun renderTile(x: Int, y: Int, zoom: Int): ByteArray? {
        val n = (1 shl zoom).toDouble()
        val west = x / n * 360.0 - 180.0
        val east = (x + 1) / n * 360.0 - 180.0
        val northLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / n))))
        val southLat = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * (y + 1) / n))))

        if (southLat >= northLat || west >= east) return null

        val (segments, _) = MgrsGridRenderer.build(
            minLat = southLat, minLng = west,
            maxLat = northLat, maxLng = east,
            mapWidthPx = TILE_SIZE
        )
        if (segments.isEmpty()) return null

        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        segments.forEach { seg ->
            val (px1, py1) = project(seg.start.latitude, seg.start.longitude, x.toDouble(), y.toDouble(), n)
            val (px2, py2) = project(seg.end.latitude, seg.end.longitude, x.toDouble(), y.toDouble(), n)
            paint.color = MgrsGridRenderer.INK_COLOR
            paint.strokeWidth = MgrsGridRenderer.lineWidthDp(seg.type) * density
            canvas.drawLine(px1, py1, px2, py2, paint)
        }

        val baos = ByteArrayOutputStream(4096)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        bitmap.recycle()
        return baos.toByteArray()
    }

    // WebMercator: lat/lng → pixel coordinate within the tile
    private fun project(lat: Double, lng: Double, tileX: Double, tileY: Double, n: Double): Pair<Float, Float> {
        val xNorm = (lng + 180.0) / 360.0
        val latRad = lat * PI / 180.0
        val yNorm = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
        return Pair(
            ((xNorm * n - tileX) * TILE_SIZE).toFloat(),
            ((yNorm * n - tileY) * TILE_SIZE).toFloat()
        )
    }

    private fun tileKey(x: Int, y: Int, zoom: Int): Long =
        (zoom.toLong() shl 52) or (x.toLong() and 0xFFFFF shl 26) or (y.toLong() and 0x3FFFFFF)

    companion object {
        const val TILE_SIZE = 256
    }
}
