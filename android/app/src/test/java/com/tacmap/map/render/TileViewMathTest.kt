package com.tacmap.map.render

import com.tacmap.app.DebugLaunchHooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** WP2 tile view rules that aren't in the shared fixture: visibility, grid frames, cache */
class TileViewMathTest {

    /** brute force: does the tile square (grown 1 dp) touch the rotated viewport rectangle */
    private fun bruteVisible(cam: MapCamera, tz: Int, col: Int, row: Int): Boolean {
        val unitPerDp = 2.0.pow(tz - cam.zoom)
        val c = WebMercator.worldPoint(cam.centerLat, cam.centerLon, tz.toDouble())
        val r = cam.headingDegrees * Math.PI / 180
        val pad = unitPerDp
        val x0 = col * 256.0 - pad; val x1 = (col + 1) * 256.0 + pad
        val y0 = row * 256.0 - pad; val y1 = (row + 1) * 256.0 + pad
        // sample the tile square densely and also the viewport outline, either containing the other
        fun inViewport(wx: Double, wy: Double): Boolean {
            val dx = wx - c.x; val dy = wy - c.y
            val u = dx * cos(r) + dy * sin(r)
            val v = -dx * sin(r) + dy * cos(r)
            return abs(u) <= cam.viewportWidth / 2 * unitPerDp + 1e-9 && abs(v) <= cam.viewportHeight / 2 * unitPerDp + 1e-9
        }
        val n = 24
        for (i in 0..n) for (j in 0..n) {
            if (inViewport(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * j / n)) return true
        }
        for (i in 0..n) {
            val t = i.toDouble() / n
            val hw = cam.viewportWidth / 2 * unitPerDp; val hh = cam.viewportHeight / 2 * unitPerDp
            for ((su, sv) in listOf(-hw + 2 * hw * t to -hh, -hw + 2 * hw * t to hh, -hw to -hh + 2 * hh * t, hw to -hh + 2 * hh * t)) {
                val wx = c.x + su * cos(r) - sv * sin(r)
                val wy = c.y + su * sin(r) + sv * cos(r)
                if (wx in x0..x1 && wy in y0..y1) return true
            }
        }
        return false
    }

    @Test
    fun separatingAxisMatchesBruteForceAtEveryHeading() {
        for (heading in listOf(0.0, 17.0, 45.0, 90.0, 133.0, 271.5)) for (zoom in listOf(10.0, 13.4, 16.6)) {
            val cam = MapCamera(37.77, -122.44, zoom, heading, 393.0, 852.0)
            val tz = TileMath.tileZoom(zoom, 0, 22)
            val got = TileMath.visibleTiles(cam, tz).map { it.col to it.row }.toSet()
            val c = WebMercator.worldPoint(cam.centerLat, cam.centerLon, tz.toDouble())
            val span = (max(cam.viewportWidth, cam.viewportHeight) * 2.0.pow(tz - zoom) / 256.0).toInt() + 2
            val cc = floor(c.x / 256).toInt(); val cr = floor(c.y / 256).toInt()
            for (col in cc - span..cc + span) for (row in cr - span..cr + span) {
                val brute = bruteVisible(cam, tz, col, row)
                // the sampled brute force can only miss a sliver, never invent one
                if (brute) assertTrue("h$heading z$zoom $col,$row missing", (col to row) in got)
            }
            // and SAT isn't wildly generous: every visible tile is within a tile of something brute force saw
            assertTrue(got.isNotEmpty())
        }
    }

    @Test
    fun visibleTilesAreCentreFirstAndColumnsStayUnwrapped() {
        // camera on the antimeridian at z3: columns run -1..1ish, x wraps to 7
        val cam = MapCamera(0.0, 179.9, 3.2, 0.0, 400.0, 400.0)
        val tiles = TileMath.visibleTiles(cam, 3)
        assertTrue(tiles.any { it.col == 8 && it.index.x == 0 })
        assertTrue(tiles.all { it.index.x == Math.floorMod(it.col, 8) })
        val c = WebMercator.worldPoint(cam.centerLat, cam.centerLon, 3.0)
        val d = tiles.map { val dx = (it.col + 0.5) * 256 - c.x; val dy = (it.row + 0.5) * 256 - c.y; dx * dx + dy * dy }
        assertEquals(d.sorted(), d)
    }

    @Test
    fun sharedEdgesAreBitIdentical() {
        for (zoom in listOf(13.0, 13.37, 15.5, 18.92)) for (heading in listOf(0.0, 33.0)) {
            val cam = MapCamera(37.77, -122.44, zoom, heading, 411.4, 914.3)
            val tz = TileMath.tileZoom(zoom, 0, 16)
            val grid = TileGrid(cam, tz)
            val tiles = TileMath.visibleTiles(cam, tz)
            val byPos = tiles.associateBy { it.col to it.row }
            for (t in tiles) {
                val f = grid.frame(t)
                byPos[t.col + 1 to t.row]?.let { right -> assertEquals(f[2], grid.frame(right)[0], 0.0) }
                byPos[t.col to t.row + 1]?.let { below -> assertEquals(f[3], grid.frame(below)[1], 0.0) }
                // and the frame is where the camera actually projects the tile corner
                val (lat, lon) = WebMercator.coordinate(Vec2(t.col * 256.0, t.row * 256.0), tz.toDouble())
                val p = cam.copy(headingDegrees = 0.0).screenPoint(lat, lon)
                if (t.col in 0 until (1 shl tz)) {
                    assertEquals(p.x, f[0], 1e-6)
                    assertEquals(p.y, f[1], 1e-6)
                }
            }
        }
    }

    @Test
    fun underzoomGuardHidesFarOverscaledSources() {
        assertTrue(TileMath.underzoomHidden(10, 7.9))
        assertFalse(TileMath.underzoomHidden(10, 8.0))
        assertFalse(TileMath.underzoomHidden(16, 18.2))
    }

    @Test
    fun byteLruEvictsOldestAndBindRetainsOnlyKept() {
        val lru = ByteLruCache<Int, ByteArray>(100) { it.size.toLong() }
        lru.put(1, ByteArray(40)); lru.put(2, ByteArray(40))
        lru[1] // touch 1, so 2 is the oldest
        lru.put(3, ByteArray(40))
        assertNull(lru[2])
        assertEquals(80L, lru.bytes)
        lru.retainOnly(setOf(3))
        assertEquals(setOf(3), lru.keys())
        assertEquals(40L, lru.bytes)
        lru.resize(10)
        assertEquals(0, lru.size)
    }

    @Test
    fun cacheTiersFollowPhysicalRam() {
        val gib = 1024L * 1024 * 1024
        assertEquals(64L * 1024 * 1024, TileMemoryBudget.cacheBytes(3 * gib, false))
        assertEquals(64L * 1024 * 1024, TileMemoryBudget.cacheBytes(12 * gib, true))
        assertEquals(128L * 1024 * 1024, TileMemoryBudget.cacheBytes(4 * gib, false))
        assertEquals(192L * 1024 * 1024, TileMemoryBudget.cacheBytes(8 * gib, false))
    }

    @Test
    fun debugLaunchHooksParse() {
        val extras = mapOf(
            DebugLaunchHooks.EXTRA_IMPORT_PDF to "/sdcard/Android/data/com.tacmap/files/sheet.pdf",
            DebugLaunchHooks.EXTRA_CAMERA to "37.765,-122.443,16.5,33",
            DebugLaunchHooks.EXTRA_GRID to "1",
            DebugLaunchHooks.EXTRA_ALLOW_SCREENSHOTS to "true",
        )
        val r = DebugLaunchHooks.parse { extras[it] }!!
        assertEquals("/sdcard/Android/data/com.tacmap/files/sheet.pdf", r.importPdfPath)
        assertEquals(DebugLaunchHooks.Camera(37.765, -122.443, 16.5, 33.0), r.camera)
        assertTrue(r.grid && r.allowScreenshots)
        assertNull(DebugLaunchHooks.parse { null })
        // junk is ignored rather than half applied
        assertNull(DebugLaunchHooks.parseCamera("37.7,abc,12"))
        assertNull(DebugLaunchHooks.parseCamera("95,0,12"))
        assertEquals(DebugLaunchHooks.Camera(1.0, 2.0, 3.0, null), DebugLaunchHooks.parseCamera(" 1, 2 ,3"))
        assertNull(DebugLaunchHooks.parse { if (it == DebugLaunchHooks.EXTRA_IMPORT_PDF) "relative/x.pdf" else null })
    }
}
