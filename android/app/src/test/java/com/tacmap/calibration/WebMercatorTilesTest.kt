package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class WebMercatorTilesTest {

    @Test
    fun zeroZeroTileAtZoomZeroCoversWholeWorld() {
        val b = WebMercatorTiles.tileBounds(0, 0, 0)
        assertEquals(-180.0, b.southwest.longitude, 1e-6)
        assertEquals(180.0, b.northeast.longitude, 1e-6)
        assertTrue("north ~85", abs(b.northeast.latitude - 85.05112878) < 1e-4)
        assertTrue("south ~-85", abs(b.southwest.latitude + 85.05112878) < 1e-4)
    }

    @Test
    fun knownTileForSanFrancisco() {
        // SF at zoom 10 is tile x=163, y=395 in the XYZ scheme.
        val x = WebMercatorTiles.lonToTileX(-122.4194, 10).toInt()
        val y = WebMercatorTiles.latToTileY(37.7749, 10).toInt()
        assertEquals(163, x)
        assertEquals(395, y)
    }

    @Test
    fun tileBoundsRoundTripThroughCorners() {
        val z = 12; val x = 1205; val y = 2615
        val b = WebMercatorTiles.tileBounds(z, x, y)
        // The NW corner of the box maps back to this tile's (x, y).
        val bx = WebMercatorTiles.lonToTileX(b.southwest.longitude, z).toInt()
        val by = WebMercatorTiles.latToTileY(b.northeast.latitude, z).toInt()
        assertEquals(x, bx)
        assertEquals(y, by)
    }

    @Test
    fun tileRangeCoversBoundsAndIsClamped() {
        val bounds = Wgs84Bounds(
            southwest = Wgs84Coordinate(37.70, -122.52),
            northeast = Wgs84Coordinate(37.83, -122.36)
        )
        val r = WebMercatorTiles.tileRange(bounds, 12)
        assertTrue(r.minX <= r.maxX)
        assertTrue(r.minY <= r.maxY)
        assertTrue("range should be small for a city box", r.count in 1..64)
        // Every tile index within the valid grid.
        val max = (1 shl 12) - 1
        assertTrue(r.minX in 0..max && r.maxX in 0..max)
        assertTrue(r.minY in 0..max && r.maxY in 0..max)
    }
}
