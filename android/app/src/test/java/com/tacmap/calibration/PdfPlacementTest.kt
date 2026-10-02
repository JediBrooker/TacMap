package com.tacmap.calibration

import com.tacmap.calibration.PdfGeorefFixture.arr
import com.tacmap.calibration.PdfGeorefFixture.obj
import com.tacmap.calibration.PdfGeorefFixture.str
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The bridge from the new georef to the display code: the best-fit lon/lat affine,
 * the tile warp cells the WP2 renderer draws with, and the provisional placement
 * for plain PDFs.
 */
class PdfPlacementTest {
    private val sheets = PdfGeorefFixture.root.arr("sheets").map { it.jsonObject }.associateBy { it.str("id") }

    private fun georef(id: String): PdfGeoreference {
        val sheet = sheets.getValue(id)
        val written = sheet["written"]?.jsonObject ?: sheet
        return (GeoPdfGeoreferencer.build(PdfGeorefFixture.pageData(sheet, written)) as GeoPdfGeorefResult.Georeferenced).georef
    }

    @Test
    fun bestFitDisplayAffineStaysCloseToTheRealGeoref() {
        // a lon/lat affine can't be exact on a UTM sheet, that's plan s2's job. what
        // it must do is hold its own against the old placement (a lon/lat affine
        // through the four corner control points, what the v1 parsers fitted): least
        // squares wins on RMS, the worst point can be a hair either way
        for (id in listOf("sf_iso", "rot5_iso", "syd_iso", "usgs_sf_north", "lcc_lgile", "offset_iso", "rot90_iso", "cbr50k_iso")) {
            val g = georef(id)
            val display = requireNotNull(g.bestFitLatLonAffine) { id }
            val old = requireNotNull(
                runCatching {
                    AffineFitter.fit(g.crop.map { p ->
                        val w = g.toWGS84(p.x, p.y)!!
                        Fiduciary(pdfX = p.x, pdfY = p.y, mgrs = "", latitude = w.latitude, longitude = w.longitude)
                    }).transform
                }.getOrNull()
            )
            val (x0, y0, x1, y1) = g.cropBounds().toList()
            var worst = 0.0
            var sumSq = 0.0
            var sumSqOld = 0.0
            for (i in 0..8) for (j in 0..8) {
                val x = x0 + (x1 - x0) * i / 8.0
                val y = y0 + (y1 - y0) * j / 8.0
                val truth = requireNotNull(g.toWGS84(x, y))
                val d = display.apply(x, y)
                val o = old.apply(x, y)
                val e = PdfGeorefFixture.metres(truth.latitude, truth.longitude, d.latitude, d.longitude)
                val eo = PdfGeorefFixture.metres(truth.latitude, truth.longitude, o.latitude, o.longitude)
                worst = maxOf(worst, e)
                sumSq += e * e
                sumSqOld += eo * eo
            }
            assertTrue("$id display affine rms ${sqrt(sumSq / 81)} vs old ${sqrt(sumSqOld / 81)}", sumSq <= sumSqOld + 1e-9)
            // and in absolute terms well under a metre per km of sheet
            val sw = g.toWGS84(x0, y0)!!
            val ne = g.toWGS84(x1, y1)!!
            val diagonal = PdfGeorefFixture.metres(sw.latitude, sw.longitude, ne.latitude, ne.longitude)
            assertTrue("$id display affine worst $worst m on a $diagonal m sheet", worst < 0.0005 * diagonal)
        }
        // geographic sheets are affine in lon/lat already, so it's exact
        val geog = georef("geog_iso")
        val d = requireNotNull(geog.bestFitLatLonAffine)
        val truth = requireNotNull(geog.toWGS84(300.0, 400.0))
        val got = d.apply(300.0, 400.0)
        assertTrue(PdfGeorefFixture.metres(truth.latitude, truth.longitude, got.latitude, got.longitude) < 1e-3)
    }

    @Test
    fun coverageIsTheWholeCropInWgs84() {
        val g = georef("usgs_sf_north")
        val b = requireNotNull(g.wgs84Bounds())
        for (p in g.crop) {
            val w = requireNotNull(g.toWGS84(p.x, p.y))
            assertTrue(w.latitude >= b.southwest.latitude - 1e-9 && w.latitude <= b.northeast.latitude + 1e-9)
            assertTrue(w.longitude >= b.southwest.longitude - 1e-9 && w.longitude <= b.northeast.longitude + 1e-9)
        }
    }

    @Test
    fun tileWarpCellsPutEveryTileSampleOnItsPagePoint() {
        // WP2 replaced the old strip tiler; the warp planner's cells have to land the WP1
        // tileWarp samples on their pixels, tighter than the strips ever did
        val section = PdfGeorefFixture.root.obj("tileWarp")
        for (t in section.arr("tiles").map { it.jsonObject }) {
            val id = t.str("georef")
            val sheet = sheets.getValue(id)
            val media = PdfGeorefFixture.doubles(sheet["mediaBox"]!!)
            val g = georef(id)
            val box = PdfBox.of(media)!!
            val footprint = com.tacmap.map.render.pdf.PdfFootprint.build(g, box)
            val z = t["z"]!!.jsonPrimitive.int
            val x = t["x"]!!.jsonPrimitive.int
            val y = t["y"]!!.jsonPrimitive.int
            val tileSize = (t["tileSize"] ?: section["tileSize"])?.jsonPrimitive?.int ?: 256
            val plan = com.tacmap.map.render.pdf.PdfTileWarp.plan(
                com.tacmap.map.render.pdf.TileJob.single(z, x, y), tileSize, footprint, g,
            )
            for (s in t.arr("samples").map { it.jsonObject }) {
                val px = PdfGeorefFixture.doubles(s["px"]!!)
                val page = PdfGeorefFixture.point(s["page"]!!)
                if (page.x !in box.llx..box.urx || page.y !in box.lly..box.ury) continue
                val cell = plan.leaves.firstOrNull {
                    px[0] >= it.left && px[0] <= it.right && px[1] >= it.top && px[1] <= it.bottom
                } ?: continue
                val u = cell.pageToPx.mapX(page.x, page.y)
                val v = cell.pageToPx.mapY(page.x, page.y)
                assertEquals("$id z$z px $px", px[0], u, 0.25)
                assertEquals("$id z$z px $px", px[1], v, 0.25)
            }
        }
        // a tile on the far side of the planet has nothing to draw
        val g = georef("sf_iso")
        val fp = com.tacmap.map.render.pdf.PdfFootprint.build(g, PdfBox(0.0, 0.0, 824.3149606, 1051.0866142))
        assertTrue(com.tacmap.map.render.pdf.PdfTileWarp.plan(com.tacmap.map.render.pdf.TileJob.single(15, 100, 100), 256, fp, g).cells.isEmpty())
    }

    @Test
    fun provisionalPlacementIsCentredNorthUpAtOneTo50k() {
        val crop = listOf(PagePoint(20.0, 30.0), PagePoint(620.0, 30.0), PagePoint(620.0, 830.0), PagePoint(20.0, 830.0))
        val camera = Wgs84Coordinate(-35.28, 149.13)
        val g = requireNotNull(PdfGeoreference.provisional(camera, crop))
        assertEquals(GeorefOrigin.PROVISIONAL, g.origin)
        val centre = requireNotNull(g.toWGS84(320.0, 430.0))
        assertEquals(camera.latitude, centre.latitude, 1e-9)
        assertEquals(camera.longitude, centre.longitude, 1e-9)
        // 600 pt wide at 1:50k = 600 / 72 * 0.0254 * 50000 m
        val west = requireNotNull(g.toWGS84(20.0, 430.0))
        val east = requireNotNull(g.toWGS84(620.0, 430.0))
        val width = PdfGeorefFixture.metres(west.latitude, west.longitude, east.latitude, east.longitude)
        // within 0.2%, the flat-earth metric here uses a, the placement the prime vertical radius
        assertEquals(600.0 / 72.0 * 0.0254 * 50_000.0, width, 25.0)
        // north up: going up the page only changes latitude
        val up = requireNotNull(g.toWGS84(320.0, 830.0))
        assertEquals(camera.longitude, up.longitude, 1e-12)
        assertTrue(up.latitude > camera.latitude)
        // and taps map back exactly
        val back = requireNotNull(g.toPage(up.latitude, up.longitude))
        assertEquals(320.0, back.x, 1e-6)
        assertEquals(830.0, back.y, 1e-6)
        assertTrue(g.datumAssumed)
        // junk camera lands on 0,0 like iOS (it's labelled uncalibrated either way), polar ones clamp to 80
        val junk = requireNotNull(PdfGeoreference.provisional(Wgs84Coordinate(Double.NaN, 0.0), crop))
        assertEquals(0.0, requireNotNull(junk.toWGS84(320.0, 430.0)).latitude, 1e-9)
        val polar = requireNotNull(PdfGeoreference.provisional(Wgs84Coordinate(89.0, 10.0), crop))
        assertEquals(80.0, requireNotNull(polar.toWGS84(320.0, 430.0)).latitude, 1e-9)
    }

    @Test
    fun provisionalPlacementIsNorthUpAsViewedOnRotatedPages() {
        // plan s1 north-up means as the user sees the page: /Rotate 90 shows user-space +y
        // pointing right (east) and +x pointing down (south), same as iOS provisional()
        val crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 800.0), PagePoint(0.0, 800.0))
        val camera = Wgs84Coordinate(-35.28, 149.13)
        fun dir(rotation: Int, dx: Double, dy: Double): Pair<Double, Double> {
            val g = requireNotNull(PdfGeoreference.provisional(camera, crop, rotation))
            val c = requireNotNull(g.toWGS84(300.0, 400.0))
            val p = requireNotNull(g.toWGS84(300.0 + dx, 400.0 + dy))
            assertEquals(camera.latitude, c.latitude, 1e-9)
            assertEquals(camera.longitude, c.longitude, 1e-9)
            return (p.longitude - c.longitude) to (p.latitude - c.latitude)
        }
        fun assertDir(label: String, east: Int, north: Int, d: Pair<Double, Double>) {
            assertEquals("$label east", east.toDouble(), Math.signum(d.first), 0.0)
            assertEquals("$label north", north.toDouble(), Math.signum(d.second), 0.0)
        }
        assertDir("0 +y", 0, 1, dir(0, 0.0, 100.0))
        assertDir("90 +y", 1, 0, dir(90, 0.0, 100.0))
        assertDir("90 +x", 0, -1, dir(90, 100.0, 0.0))
        assertDir("180 +y", 0, -1, dir(180, 0.0, 100.0))
        assertDir("270 +y", -1, 0, dir(270, 0.0, 100.0))
        assertDir("270 +x", 0, 1, dir(270, 100.0, 0.0))
        assertDir("-90 is 270", -1, 0, dir(-90, 0.0, 100.0))
    }

    @Test
    fun fiduciaryRefitTakesTheZoneOffTheFirstStoredMgrs() {
        // first point typed in zone 55 even though it sits east of 150E: plane stays 55
        val crop = listOf(PagePoint(0.0, 0.0), PagePoint(1277.0, 0.0), PagePoint(1277.0, 1277.0), PagePoint(0.0, 1277.0))
        val set = PdfGeorefFixture.root.obj("fiduciaryFits").arr("sets").map { it.jsonObject }
            .first { it.str("name") == "zone_crossing_55_56_gda94" }
        val gda = GeoDatums.GDA94
        val fids = set.arr("points").map { it.jsonObject }.map { p ->
            val page = PdfGeorefFixture.point(p["page"]!!)
            val ref = FiduciaryReferenceParser.parse(p.str("input"), gda)!!
            val (lat, lon) = when (ref) {
                is FiduciaryReference.LatLon -> ref.latitude to ref.longitude
                is FiduciaryReference.Mgrs -> GeoCrs.utm(ref.zone, ref.southern).inverse(ref.easting, ref.northing, gda.ellipsoid)!!
                    .let { it.latitude to it.longitude }
                is FiduciaryReference.Utm -> GeoCrs.utm(ref.zone, ref.southern).inverse(ref.easting, ref.northing, gda.ellipsoid)!!
                    .let { it.latitude to it.longitude }
            }
            val w = gda.toWGS84(lat, lon)!!
            Fiduciary(pdfX = page.x, pdfY = page.y, mgrs = p.str("input"), latitude = w.latitude, longitude = w.longitude)
        }
        val fit = requireNotNull(FiduciaryFitter.refitStored(fids, crop))
        assertEquals(55, fit.zone)
        assertTrue(fit.southern)
        assertNotNull(fit.georeference(crop))
        assertTrue(fit.rmsMetres < 0.1)
        // lat/lon first point -> its standard zone
        val latLonFirst = fids.mapIndexed { i, f -> if (i == 0) f.copy(mgrs = "") else f }
        assertEquals(55, FiduciaryFitter.refitStored(latLonFirst, crop)!!.zone)
    }
}
