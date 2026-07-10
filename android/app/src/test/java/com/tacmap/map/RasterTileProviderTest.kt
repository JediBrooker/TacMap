package com.tacmap.map

import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.EsriKey
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the keyed-Esri swap. The real regression risk is a wrong host or a
 * dropped token, so we assert the composed URL, not just that it compiles.
 */
class RasterTileProviderTest {

    @Test fun esriTileUrlUsesKeyedIbasemapsEndpointWithToken() {
        assumeTrue("needs a build-injected Esri key", EsriKey.isAvailable)
        val url = RasterTileProvider(BasemapStyle.ESRI_SATELLITE).getTileUrl(3, 2, 4)
        assertNotNull("Esri tile URL should build when a key is present", url)
        val s = url!!.toString()
        assertTrue("must hit the keyed endpoint: $s", s.contains("ibasemaps-api.arcgis.com"))
        assertTrue("must carry the token: (redacted)", s.contains("token="))
        assertFalse("must NOT hot-link the unauthenticated endpoint",
            s.contains("server.arcgisonline.com"))
        assertTrue("z/y/x order preserved", s.contains("/4/2/3"))
    }

    @Test fun esriTileUrlIsNullWithoutAKey() {
        assumeTrue("only meaningful when NO key is configured", !EsriKey.isAvailable)
        // With no key we must refuse rather than fall back to hot-linking.
        assertNull(RasterTileProvider(BasemapStyle.ESRI_SATELLITE).getTileUrl(3, 2, 4))
    }

    @Test fun terrainNeedsNoKeyAndCarriesNoToken() {
        val url = RasterTileProvider(BasemapStyle.TERRAIN).getTileUrl(3, 2, 4)
        assertNotNull(url)
        val s = url!!.toString()
        assertTrue(s.contains("opentopomap.org"))
        assertFalse("terrain must not append a token", s.contains("token="))
    }

    @Test fun zoomBeyondStyleMaxReturnsNull() {
        assertNull(RasterTileProvider(BasemapStyle.TERRAIN).getTileUrl(1, 1, 99))
    }
}
