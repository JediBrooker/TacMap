package com.tacmap.calibration

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Tile pixel -> page, the warp the plan s2 tile renderer is built on: XYZ tile,
 * pixel y down, spherical Web Mercator on WGS84 lat/lon, then georef.toPage.
 * Pure so it's pinned by testdata/pdf_georef.json tileWarp on both platforms.
 */
object TileWarp {
    const val TILE_SIZE = 256

    /** (z, x, y) tile pixel (fractional ok) -> WGS84 */
    fun pixelToWgs84(z: Int, x: Int, y: Int, px: Double, py: Double, tileSize: Int = TILE_SIZE): Wgs84Coordinate? {
        if (z !in 0..30 || tileSize <= 0 || !px.isFinite() || !py.isFinite()) return null
        val n = Math.scalb(1.0, z)
        val tx = x + px / tileSize
        val ty = y + py / tileSize
        val lon = tx / n * 360.0 - 180.0
        val lat = atan(sinh(PI * (1.0 - 2.0 * ty / n))) * 180.0 / PI
        return Wgs84Coordinate(lat, lon).takeIf { it.isValidEarthCoordinate() }
    }

    fun pixelToPage(
        georef: PdfGeoreference,
        z: Int,
        x: Int,
        y: Int,
        px: Double,
        py: Double,
        tileSize: Int = TILE_SIZE,
    ): PagePoint? {
        val w = pixelToWgs84(z, x, y, px, py, tileSize) ?: return null
        return georef.toPage(w.latitude, w.longitude)
    }
}
