package com.tacmap.calibration

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.math.cos

/**
 * Pure Web-Mercator (EPSG:3857) XYZ tile math — the slippy-map scheme MBTiles
 * and Google/OSM tiles use. Kept dependency-free and side-effect-free so the
 * tricky parts are unit-tested on the host JVM (see WebMercatorTilesTest).
 *
 * XYZ convention: tile (0,0) is the north-west corner; y increases southward.
 */
object WebMercatorTiles {

    private const val MAX_LAT = 85.05112878   // Web-Mercator clamp

    /** Fractional tile column for a longitude at zoom [z]. */
    fun lonToTileX(lon: Double, z: Int): Double =
        (lon + 180.0) / 360.0 * (1 shl z)

    /** Fractional tile row for a latitude at zoom [z]. */
    fun latToTileY(lat: Double, z: Int): Double {
        val clamped = lat.coerceIn(-MAX_LAT, MAX_LAT)
        val latRad = Math.toRadians(clamped)
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl z)
    }

    /** West-edge longitude of tile column [x] at zoom [z]. */
    fun tileXToLon(x: Double, z: Int): Double =
        x / (1 shl z) * 360.0 - 180.0

    /** North-edge latitude of tile row [y] at zoom [z]. */
    fun tileYToLat(y: Double, z: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl z)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** WGS84 bounding box of integer tile (z,x,y). */
    fun tileBounds(z: Int, x: Int, y: Int): Wgs84Bounds {
        val west = tileXToLon(x.toDouble(), z)
        val east = tileXToLon((x + 1).toDouble(), z)
        val north = tileYToLat(y.toDouble(), z)
        val south = tileYToLat((y + 1).toDouble(), z)
        return Wgs84Bounds(
            southwest = Wgs84Coordinate(south, west),
            northeast = Wgs84Coordinate(north, east)
        )
    }

    /**
     * Inclusive integer tile range (minX..maxX, minY..maxY) covering `bounds`
     * at zoom `z`. Clamped to the valid 0..(2^z - 1) grid.
     */
    fun tileRange(bounds: Wgs84Bounds, z: Int): TileRange {
        val max = (1 shl z) - 1
        val minX = floor(lonToTileX(bounds.southwest.longitude, z)).toInt().coerceIn(0, max)
        val maxX = floor(lonToTileX(bounds.northeast.longitude, z)).toInt().coerceIn(0, max)
        // North latitude → smaller y.
        val minY = floor(latToTileY(bounds.northeast.latitude, z)).toInt().coerceIn(0, max)
        val maxY = floor(latToTileY(bounds.southwest.latitude, z)).toInt().coerceIn(0, max)
        return TileRange(z, minX, maxX, minY, maxY)
    }

    data class TileRange(val z: Int, val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val count: Int get() = (maxX - minX + 1).coerceAtLeast(0) * (maxY - minY + 1).coerceAtLeast(0)
    }
}
