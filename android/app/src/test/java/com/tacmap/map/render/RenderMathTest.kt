package com.tacmap.map.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are hand-computed canonical slippy-map values, identical to the iOS
 * WebMercator/MapCamera/TileMath tests, so both renderers are pinned to the
 * same math. A sign flip or wrong constant fails here.
 */
class RenderMathTest {

    private val eps = 1e-6

    // MARK: WebMercator

    @Test fun originMapsToWorldCentre() {
        val z0 = WebMercator.worldPoint(0.0, 0.0, 0.0)
        assertEquals(128.0, z0.x, eps) // 256/2
        assertEquals(128.0, z0.y, eps)
        val z1 = WebMercator.worldPoint(0.0, 0.0, 1.0)
        assertEquals(256.0, z1.x, eps) // 512/2
        assertEquals(256.0, z1.y, eps)
    }

    @Test fun antimeridianAndEquatorEdges() {
        assertEquals(0.0, WebMercator.worldPoint(0.0, -180.0, 3.0).x, eps)
        assertEquals(WebMercator.mapSize(3.0), WebMercator.worldPoint(0.0, 180.0, 3.0).x, eps)
    }

    @Test fun groundResolutionAtEquatorZoom0() {
        val r = WebMercator.groundResolution(0.0, 0.0)
        assertEquals(156_543.033_9, r, 1e-3)                       // textbook figure
        assertEquals(r / 2, WebMercator.groundResolution(0.0, 1.0), 1e-3)  // halves per zoom
        assertEquals(r * 0.5, WebMercator.groundResolution(60.0, 0.0), 1e-1) // cos(60)=0.5
    }

    @Test fun forwardInverseRoundTripsAcrossTheGlobe() {
        val samples = listOf(
            0.0 to 0.0,
            51.5074 to -0.1278,     // London
            -33.8688 to 151.2093,   // Sydney
            37.7749 to -122.4194,   // SF
            84.9 to 179.9           // near Mercator limit
        )
        for (z in listOf(3.0, 8.0, 14.0, 19.0)) {
            for ((lat, lon) in samples) {
                val w = WebMercator.worldPoint(lat, lon, z)
                val (bLat, bLon) = WebMercator.coordinate(w, z)
                assertEquals("lat z=$z", lat, bLat, 1e-6)
                assertEquals("lon z=$z", lon, bLon, 1e-6)
            }
        }
    }

    @Test fun clampedNorthPoleSitsOnTopEdge() {
        val p = WebMercator.worldPoint(89.9, 0.0, 5.0)
        assertTrue(p.y.isFinite())
        assertEquals(0.0, p.y, 1e-6)
    }

    @Test fun inverseClampsPointsBeyondEveryWorldEdge() {
        val size = WebMercator.mapSize(5.0)
        val (north, west) = WebMercator.coordinate(
            Vec2(x = -2.0 * size, y = -3.0 * size),
            zoom = 5.0
        )
        assertEquals(WebMercator.LAT_LIMIT, north, eps)
        assertEquals(-180.0, west, eps)

        val (south, east) = WebMercator.coordinate(
            Vec2(x = 3.0 * size, y = 4.0 * size),
            zoom = 5.0
        )
        assertEquals(-WebMercator.LAT_LIMIT, south, eps)
        assertEquals(180.0, east, eps)
    }

    // MARK: MapCamera

    private fun camera(heading: Double = 0.0) =
        MapCamera(centerLat = 0.0, centerLon = 0.0, zoom = 4.0,
                  headingDegrees = heading, viewportWidth = 400.0, viewportHeight = 600.0)

    @Test fun centreProjectsToViewportCentre() {
        val p = camera().screenPoint(0.0, 0.0)
        assertEquals(200.0, p.x, eps)
        assertEquals(300.0, p.y, eps)
    }

    @Test fun northIsUpAndEastIsRightAtHeadingZero() {
        val n = camera().screenPoint(1.0, 0.0)
        assertEquals(200.0, n.x, eps)
        assertTrue("north is up", n.y < 300.0)
        val e = camera().screenPoint(0.0, 1.0)
        assertEquals(300.0, e.y, eps)
        assertTrue("east is right", e.x > 200.0)
    }

    @Test fun headingNinetyPutsNorthOnTheLeft() {
        val p = camera(heading = 90.0).screenPoint(1.0, 0.0)
        assertTrue("north swings left at heading 90", p.x < 200.0)
        assertEquals(300.0, p.y, 1e-4)
    }

    @Test fun screenRoundTripsForEveryHeading() {
        for (h in listOf(0.0, 30.0, 90.0, 137.0, 270.0)) {
            val cam = camera(heading = h)
            for ((sx, sy) in listOf(10.0 to 20.0, 200.0 to 300.0, 390.0 to 590.0)) {
                val (lat, lon) = cam.coordinate(sx, sy)
                val back = cam.screenPoint(lat, lon)
                assertEquals("x h=$h", sx, back.x, 1e-4)
                assertEquals("y h=$h", sy, back.y, 1e-4)
            }
        }
    }

    @Test fun screenCoordinateCannotEscapeWorldBounds() {
        val cam = camera().copy(zoom = 0.0)
        val targets = listOf(
            cam.coordinate(-10_000.0, -10_000.0),
            cam.coordinate(10_000.0, 10_000.0)
        )
        targets.forEach { (lat, lon) ->
            assertTrue("latitude out of range: $lat", lat in -WebMercator.LAT_LIMIT..WebMercator.LAT_LIMIT)
            assertTrue("longitude out of range: $lon", lon in -180.0..180.0)
        }
    }

    // MARK: TileMath

    @Test fun tileZoomRoundsAndClamps() {
        assertEquals(4, TileMath.tileZoom(4.4, 0, 19))
        assertEquals(5, TileMath.tileZoom(4.6, 0, 19))
        assertEquals(19, TileMath.tileZoom(25.0, 0, 19))
        assertEquals(3, TileMath.tileZoom(-3.0, 3, 19))
    }

    @Test fun zoomZeroSeesTheSingleWorldTile() {
        val tiles = TileMath.visibleTiles(camera().copy(zoom = 0.0), 0)
        assertEquals(listOf(TileIndex(0, 0, 0)), tiles)
    }

    @Test fun originAtZoomOneTouchesTheFourCentreTiles() {
        val tiles = TileMath.visibleTiles(camera().copy(zoom = 1.0), 1).toSet()
        assertEquals(
            setOf(TileIndex(1, 0, 0), TileIndex(1, 1, 0), TileIndex(1, 0, 1), TileIndex(1, 1, 1)),
            tiles
        )
    }

    @Test fun allVisibleTilesAreInRange() {
        for (z in 1..12) {
            val cam = camera().copy(centerLat = 37.0, centerLon = -122.0, zoom = z.toDouble())
            val tiles = TileMath.visibleTiles(cam, z)
            val n = 1 shl z
            assertTrue("z=$z saw no tiles", tiles.isNotEmpty())
            tiles.forEach {
                assertTrue("x out of range z=$z: $it", it.x in 0 until n)
                assertTrue("y out of range z=$z: $it", it.y in 0 until n)
            }
        }
    }

    @Test fun rotationCoversAtLeastAsManyTiles() {
        val cam = camera().copy(centerLat = 37.0, centerLon = -122.0, zoom = 10.0)
        val flat = TileMath.visibleTiles(cam, 10)
        val turned = TileMath.visibleTiles(cam.copy(headingDegrees = 45.0), 10)
        assertTrue(turned.size >= flat.size)
    }

    @Test fun tileFrameEdgeScalesWithFractionalZoom() {
        val cam = camera().copy(centerLat = 37.0, centerLon = -122.0, zoom = 10.0)
        val tile = TileIndex(10, 163, 395)
        assertEquals(256.0, TileMath.tileFrame(tile, cam).edge, eps)
        assertEquals(256.0 * Math.sqrt(2.0),
            TileMath.tileFrame(tile, cam.copy(zoom = 10.5)).edge, eps)
    }
}
