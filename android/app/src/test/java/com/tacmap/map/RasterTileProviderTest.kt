package com.tacmap.map

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
 * Guards the 4-basemap wiring. The real regression risk is a wrong host, a
 * dropped token, or a tile-size mismatch (Esri static tiles are 512px), so we
 * assert the composed URL + provider dimensions, not just that it compiles.
 */
class RasterTileProviderTest {

    @Test fun esriSatelliteIsKeyed256pxIbasemaps() {
        assumeTrue("needs a build-injected Esri key", EsriKey.isAvailable)
        val url = RasterTileProvider(BasemapStyle.ESRI_SATELLITE).getTileUrl(3, 2, 4)!!.toString()
        assertTrue("keyed endpoint: $url", url.contains("ibasemaps-api.arcgis.com"))
        assertTrue(url.contains("World_Imagery"))
        assertTrue("carries token", url.contains("token="))
        assertFalse("no hot-linking", url.contains("server.arcgisonline.com"))
        assertTrue("z/y/x order", url.contains("/4/2/3"))
        assertEquals(256, BasemapStyle.ESRI_SATELLITE.tileSize)
    }

    @Test fun esriTopoIsKeyed512pxStaticOutdoor() {
        assumeTrue(EsriKey.isAvailable)
        val url = RasterTileProvider(BasemapStyle.ESRI_TOPO).getTileUrl(3, 2, 4)!!.toString()
        assertTrue("static tiles service: $url", url.contains("static-map-tiles-api.arcgis.com"))
        assertTrue(url.contains("/arcgis/outdoor/"))
        assertTrue(url.contains("token="))
        assertEquals("Esri static tiles are 512px", 512, BasemapStyle.ESRI_TOPO.tileSize)
    }

    @Test fun osmStreetIsKeyedEsriOpenStyleNotOsmOrg() {
        // OSM cartography, but served licensed through Esri - tile.openstreetmap.org
        // blocks app clients, so we must NOT point there.
        assumeTrue(EsriKey.isAvailable)
        val url = RasterTileProvider(BasemapStyle.OSM_STREET).getTileUrl(3, 2, 4)!!.toString()
        assertTrue(url.contains("static-map-tiles-api.arcgis.com"))
        assertTrue(url.contains("/open/osm-style/"))
        assertTrue(url.contains("token="))
        assertFalse("must not hit the community OSM server", url.contains("tile.openstreetmap.org"))
        assertEquals(512, BasemapStyle.OSM_STREET.tileSize)
    }

    @Test fun osmTopoIsCommunityOpenTopoMapNoToken() {
        // The one community source (no licensed OpenTopoMap exists). Needs no key.
        val url = RasterTileProvider(BasemapStyle.OSM_TOPO).getTileUrl(3, 2, 4)!!.toString()
        assertTrue(url.contains("tile.opentopomap.org"))
        assertFalse("community source carries no token", url.contains("token="))
        assertEquals(256, BasemapStyle.OSM_TOPO.tileSize)
    }

    @Test fun keyedStylesRefuseToBuildWithoutAKey() {
        assumeTrue("only meaningful when NO key is configured", !EsriKey.isAvailable)
        // With no key we must refuse, not fall back to an unauthenticated URL.
        assertNull(RasterTileProvider(BasemapStyle.ESRI_SATELLITE).getTileUrl(3, 2, 4))
        assertNull(RasterTileProvider(BasemapStyle.ESRI_TOPO).getTileUrl(3, 2, 4))
        assertNull(RasterTileProvider(BasemapStyle.OSM_STREET).getTileUrl(3, 2, 4))
        // OSM Topo needs no key, so it still works.
        assertNotNull(RasterTileProvider(BasemapStyle.OSM_TOPO).getTileUrl(3, 2, 4))
    }

    @Test fun zoomBeyondStyleMaxReturnsNull() {
        assertNull(RasterTileProvider(BasemapStyle.OSM_TOPO).getTileUrl(1, 1, 99))
    }

    @Test fun everyStyleHasAttribution() {
        BasemapStyle.entries.forEach {
            assertTrue("${it.name} needs attribution", it.attribution.isNotBlank())
        }
    }
}
