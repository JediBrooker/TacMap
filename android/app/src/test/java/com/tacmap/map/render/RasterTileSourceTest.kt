package com.tacmap.map.render

import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.EsriKey
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the 4-basemap wiring on the SDK-free renderer. The real regression risk
 * is a wrong host, a dropped token, or a tile-size mismatch (Esri static tiles
 * are 512px), so we assert the composed URL + tile dimensions, not just that it
 * compiles. Same invariants the old RasterTileProviderTest checked, now against
 * OnlineRasterTileSource.tileUrl (pure, no network).
 */
class RasterTileSourceTest {

    private fun url(style: BasemapStyle, z: Int, x: Int, y: Int) =
        OnlineRasterTileSource(style).tileUrl(TileIndex(z, x, y))

    @Test fun esriSatelliteIsKeyed256pxIbasemaps() {
        assumeTrue("needs a build-injected Esri key", EsriKey.isAvailable)
        val u = url(BasemapStyle.ESRI_SATELLITE, 4, 3, 2)!!
        assertTrue("keyed endpoint: $u", u.contains("ibasemaps-api.arcgis.com"))
        assertTrue(u.contains("World_Imagery"))
        assertTrue("carries token", u.contains("token="))
        assertFalse("no hot-linking", u.contains("server.arcgisonline.com"))
        assertTrue("z/y/x order", u.contains("/4/2/3"))
        assertEquals(256, BasemapStyle.ESRI_SATELLITE.tileSize)
    }

    @Test fun esriTopoIsKeyed512pxStaticOutdoor() {
        assumeTrue(EsriKey.isAvailable)
        val u = url(BasemapStyle.ESRI_TOPO, 4, 3, 2)!!
        assertTrue("static tiles service: $u", u.contains("static-map-tiles-api.arcgis.com"))
        assertTrue(u.contains("/arcgis/outdoor/"))
        assertTrue(u.contains("token="))
        assertEquals("Esri static tiles are 512px", 512, BasemapStyle.ESRI_TOPO.tileSize)
    }

    @Test fun osmStreetIsKeyedEsriOpenStyleNotOsmOrg() {
        // OSM cartography, but served licensed through Esri - tile.openstreetmap.org
        // blocks app clients, so we must NOT point there.
        assumeTrue(EsriKey.isAvailable)
        val u = url(BasemapStyle.OSM_STREET, 4, 3, 2)!!
        assertTrue(u.contains("static-map-tiles-api.arcgis.com"))
        assertTrue(u.contains("/open/osm-style/"))
        assertTrue(u.contains("token="))
        assertFalse("must not hit the community OSM server", u.contains("tile.openstreetmap.org"))
        assertEquals(512, BasemapStyle.OSM_STREET.tileSize)
    }

    @Test fun osmTopoIsCommunityOpenTopoMapNoToken() {
        // The one community source (no licensed OpenTopoMap exists). Needs no key.
        val u = url(BasemapStyle.OSM_TOPO, 4, 3, 2)!!
        assertTrue(u.contains("tile.opentopomap.org"))
        assertFalse("community source carries no token", u.contains("token="))
        assertEquals(256, BasemapStyle.OSM_TOPO.tileSize)
    }

    @Test fun keyedStylesRefuseToBuildWithoutAKey() {
        assumeTrue("only meaningful when NO key is configured", !EsriKey.isAvailable)
        // With no key we must refuse, not fall back to an unauthenticated URL.
        assertNull(url(BasemapStyle.ESRI_SATELLITE, 4, 3, 2))
        assertNull(url(BasemapStyle.ESRI_TOPO, 4, 3, 2))
        assertNull(url(BasemapStyle.OSM_STREET, 4, 3, 2))
        // OSM Topo needs no key, so it still works.
        assertNotNull(url(BasemapStyle.OSM_TOPO, 4, 3, 2))
    }

    @Test fun zoomBeyondStyleMaxReturnsNull() {
        assertNull(url(BasemapStyle.OSM_TOPO, 99, 1, 1))
    }

    @Test fun everyStyleHasAttribution() {
        BasemapStyle.entries.forEach {
            assertTrue("${it.name} needs attribution", it.attribution.isNotBlank())
        }
    }
}
