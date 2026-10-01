package com.tacmap.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain gates the 2026-10 parity review found different on iOS (GeoCrs.swift)
 * and here. The fixture pins values inside the sane domain, these pin where
 * each side gives up, so the two can't drift on the edges again.
 */
class GeoCrsParityTest {
    private val wgs = GeoEllipsoid.WGS84

    @Test
    fun transverseMercatorGivesUpAQuarterTurnFromTheCentralMeridian() {
        val tm = GeoCrs.TransverseMercator(0.0, 147.0, 0.9996, 500_000.0, 0.0)
        // iOS: abs(dLon) < 90, so 80 deg off the CM is still a (bad but finite) number
        assertNotNull(tm.forward(10.0, 147.0 + 80.0, wgs))
        assertNull(tm.forward(10.0, 147.0 + 95.0, wgs))
        // lon - lon0 is wrapped first: -118 is 265 deg west of the CM, i.e. 95 east, still out
        assertNull(tm.forward(10.0, -118.0, wgs))
        // inverse bails past |eta| 2.5 / |xi| 3.2 like iOS
        val a = 0.9996 * 6_367_449.0
        assertNotNull(tm.inverse(500_000.0 + 2.0 * a, 1_000_000.0, wgs))
        assertNull(tm.inverse(500_000.0 + 2.6 * a, 1_000_000.0, wgs))
        // lon0 isn't wrapped by the wkt parser, a full turn either way still projects
        assertNotNull(GeoCrs.TransverseMercator(0.0, 207.0, 1.0, 0.0, 0.0).forward(10.0, -150.0, wgs))
        assertNull(GeoCrs.TransverseMercator(0.0, 361.0, 1.0, 0.0, 0.0).projector(wgs))
    }

    @Test
    fun lambertAndMercatorEdgesMatchIos() {
        // every latitude parameter has to stay off the poles, lat0 too
        assertNull(GeoCrs.LambertConformalConic2SP(33.0, 45.0, 89.9995, -122.0, 0.0, 0.0).projector(wgs))
        assertNotNull(GeoCrs.LambertConformalConic2SP(33.0, 45.0, 37.5, 238.0, 0.0, 0.0).projector(wgs))
        val lcc = GeoCrs.LambertConformalConic2SP(33.0, 45.0, 37.5, -122.0, 0.0, 0.0)
        assertNull(lcc.forward(90.0, -122.0, wgs))
        assertNull(lcc.forward(-90.0, -122.0, wgs))
        val merc = GeoCrs.Mercator1SP(-200.0, 1.0, 0.0, 0.0)
        assertNotNull(merc.forward(10.0, 160.0, wgs))
        assertNull(merc.forward(89.9995, 160.0, wgs))
    }

    @Test
    fun geographicInverseWrapsAndToPageStaysOnTheSheetsBranch() {
        val g = requireNotNull(GeoCrs.Geographic.inverse(180.3, -17.0, wgs))
        assertEquals(-179.7, g.longitude, 1e-9)
        // a 0.6 deg sheet centred on 180
        val georef = PdfGeoreference(
            page = 0,
            crs = GeoCrs.Geographic,
            datum = GeoDatums.WGS84,
            affine = PlaneAffine(0.001, 0.0, 179.7, 0.0, 0.001, -17.5),
            crop = listOf(PagePoint(0.0, 0.0), PagePoint(600.0, 0.0), PagePoint(600.0, 400.0), PagePoint(0.0, 400.0)),
            origin = GeorefOrigin.LGI_DICT,
        )
        assertTrue(georef.isStructurallyValid())
        val east = requireNotNull(georef.toWGS84(500.0, 200.0))
        assertEquals(-179.8, east.longitude, 1e-9)
        val back = requireNotNull(georef.toPage(east.latitude, east.longitude))
        assertEquals(500.0, back.x, 1e-6)
        assertEquals(200.0, back.y, 1e-6)
        // the stopgap lat/lon fit for the overlay doesn't smear across 360 either
        val fit = requireNotNull(georef.bestFitLatLonAffine)
        assertEquals(180.2, fit.a * 500.0 + fit.b * 200.0 + fit.c, 1e-9)
        assertEquals(179.8, fit.a * 100.0 + fit.b * 200.0 + fit.c, 1e-9)
    }

    @Test
    fun wktPrimeMeridianIsAddedToTheCentralMeridianUnwrapped() {
        val wkt = """PROJCS["x",GEOGCS["x",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],""" +
            """PRIMEM["made up",20],UNIT["degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],""" +
            """PARAMETER["central_meridian",170],PARAMETER["scale_factor",1],UNIT["metre",1]]"""
        val r = GcsParser.fromWkt(wkt)
        assertEquals(GcsStatus.OK, r.status)
        assertEquals(190.0, (r.crs as GeoCrs.TransverseMercator).lon0, 0.0)
        assertEquals(20.0, r.primeMeridian, 0.0)
    }

    @Test
    fun ellipsoidPlausibilityIsTheIosGate() {
        // iOS isPlausibleEarth: a in [6e6, 7e6], 0 < f < 0.01
        assertTrue(GeoEllipsoid(6_378_137.0, 150.0).isValid())
        assertTrue(!GeoEllipsoid(6_378_137.0, 99.0).isValid())
        assertTrue(!GeoEllipsoid(5_999_999.0, 298.0).isValid())
    }
}
