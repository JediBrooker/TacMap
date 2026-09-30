package com.tacmap.map

import com.tacmap.calibration.WebMercatorTiles
import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.map.render.MapCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log2

class MapExtentFitTest {
    /** One 256-point tile at zoom 12 over Canberra. */
    private val tile = WebMercatorTiles.tileBounds(12, 3745, 2461)

    @Test
    fun oneTileFillsATileSizedViewportAtItsOwnZoom() {
        val fit = fitExtent(tile, 256.0, 256.0, margin = 0.0)!!
        assertEquals(12.0, fit.zoom, 1e-9)
    }

    @Test
    fun theTighterAxisDecides() {
        assertEquals(12.0, fitExtent(tile, 512.0, 256.0, margin = 0.0)!!.zoom, 1e-9)
        assertEquals(12.0, fitExtent(tile, 256.0, 512.0, margin = 0.0)!!.zoom, 1e-9)
        assertEquals(13.0, fitExtent(tile, 512.0, 512.0, margin = 0.0)!!.zoom, 1e-9)
    }

    @Test
    fun defaultMarginLeavesTheEdgesClearOfTheChrome() {
        val fit = fitExtent(tile, 256.0, 256.0)!!
        assertEquals(12.0 + log2(1.0 - 2 * EXTENT_FIT_MARGIN), fit.zoom, 1e-9)
    }

    @Test
    fun wholeExtentIsOnScreenAtTheFittedCamera() {
        val sheet = Wgs84Bounds(Wgs84Coordinate(-35.40, 148.95), Wgs84Coordinate(-35.20, 149.30))
        val fit = fitExtent(sheet, 390.0, 844.0)!!
        val camera = MapCamera(fit.latitude, fit.longitude, fit.zoom, 0.0, 390.0, 844.0)
        listOf(sheet.southwest, sheet.northeast).forEach { corner ->
            val p = camera.screenPoint(corner.latitude, corner.longitude)
            assertTrue(p.x in 0.0..390.0 && p.y in 0.0..844.0)
        }
        // Wide sheet on a portrait phone: the width limits, and it's used fully.
        val west = camera.screenPoint(sheet.southwest.latitude, sheet.southwest.longitude)
        val east = camera.screenPoint(sheet.northeast.latitude, sheet.northeast.longitude)
        assertEquals(390.0 * (1 - 2 * EXTENT_FIT_MARGIN), east.x - west.x, 1e-6)
    }

    @Test
    fun centreIsTheMercatorMiddleOfTheExtent() {
        val fit = fitExtent(tile, 256.0, 256.0, margin = 0.0)!!
        val camera = MapCamera(fit.latitude, fit.longitude, fit.zoom, 0.0, 256.0, 256.0)
        val nw = camera.screenPoint(tile.northeast.latitude, tile.southwest.longitude)
        val se = camera.screenPoint(tile.southwest.latitude, tile.northeast.longitude)
        assertEquals(0.0, nw.x, 1e-6)
        assertEquals(0.0, nw.y, 1e-6)
        assertEquals(256.0, se.x, 1e-6)
        assertEquals(256.0, se.y, 1e-6)
    }

    @Test
    fun zoomIsClampedLikeIos() {
        val point = Wgs84Bounds(Wgs84Coordinate(-35.3, 149.1), Wgs84Coordinate(-35.3, 149.1))
        assertEquals(EXTENT_FIT_MAX_ZOOM, fitExtent(point, 390.0, 844.0)!!.zoom, 0.0)
        val tiny = Wgs84Bounds(Wgs84Coordinate(-35.3, 149.1), Wgs84Coordinate(-35.29999, 149.10001))
        assertEquals(EXTENT_FIT_MAX_ZOOM, fitExtent(tiny, 390.0, 844.0)!!.zoom, 0.0)
        val world = Wgs84Bounds(Wgs84Coordinate(-80.0, -179.0), Wgs84Coordinate(80.0, 179.0))
        assertEquals(EXTENT_FIT_MIN_ZOOM, fitExtent(world, 390.0, 844.0)!!.zoom, 0.0)
    }

    @Test
    fun extentAcrossTheAntimeridianFitsItsNarrowSpan() {
        val fiji = Wgs84Bounds(Wgs84Coordinate(-17.0, 179.0), Wgs84Coordinate(-16.0, -179.0))
        val fit = fitExtent(fiji, 400.0, 400.0)!!
        assertEquals(180.0, abs(fit.longitude), 1e-6)
        assertTrue(fit.zoom > 6.0)
    }

    @Test
    fun unusableInputsHaveNoFit() {
        assertNull(fitExtent(tile, 0.0, 844.0))
        assertNull(fitExtent(tile, 390.0, Double.NaN))
        val inverted = Wgs84Bounds(tile.northeast, tile.southwest)
        assertNull(fitExtent(inverted, 390.0, 844.0))
    }
}
