package com.tacmap.billing

import com.tacmap.calibration.BasemapStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGateTest {
    @After fun resetAccess() {
        // process wide switch, put it back so other tests see the paid app
        FeatureAccess.set(true)
    }

    @Test fun fullVersionAllowsEveryGatedFeature() {
        FeatureGate.Feature.entries.forEach { feature ->
            assertTrue("$feature should be open in the full version", FeatureGate.allows(feature, fullVersion = true))
        }
    }

    @Test fun viewerLocksEveryGatedFeature() {
        // the whole paid list: symbology, drawings, measure, weather, geojson/kml import,
        // exports, track recording start, chat, unit sync and the esri basemaps
        FeatureGate.Feature.entries.forEach { feature ->
            assertFalse("$feature should be locked in the viewer", FeatureGate.allows(feature, fullVersion = false))
        }
    }

    @Test fun gatedListCoversTheViewerContract() {
        val expected = setOf(
            FeatureGate.Feature.SYMBOLOGY,
            FeatureGate.Feature.DRAWINGS,
            FeatureGate.Feature.MEASURE,
            FeatureGate.Feature.WEATHER,
            FeatureGate.Feature.IMPORT_GEOJSON,
            FeatureGate.Feature.IMPORT_KML,
            FeatureGate.Feature.EXPORT,
            FeatureGate.Feature.TRACK_RECORDING,
            FeatureGate.Feature.CHAT,
            FeatureGate.Feature.UNIT_SYNC,
            FeatureGate.Feature.ESRI_BASEMAP,
        )
        assertEquals(expected, FeatureGate.Feature.entries.toSet())
    }

    @Test fun openTopoMapIsNeverSubstituted() {
        listOf(true, false).forEach { full ->
            listOf(true, false).forEach { key ->
                assertEquals(
                    BasemapStyle.OSM_TOPO,
                    FeatureGate.displayStyle(BasemapStyle.OSM_TOPO, fullVersion = full, esriKeyAvailable = key),
                )
            }
        }
    }

    @Test fun viewerShowsOpenTopoMapInPlaceOfEveryEsriStyle() {
        BasemapStyle.entries.filter { it.requiresEsriKey }.forEach { style ->
            assertEquals(
                "$style must fall back in the viewer even with a key",
                BasemapStyle.OSM_TOPO,
                FeatureGate.displayStyle(style, fullVersion = false, esriKeyAvailable = true),
            )
            assertEquals(
                BasemapStyle.OSM_TOPO,
                FeatureGate.displayStyle(style, fullVersion = false, esriKeyAvailable = false),
            )
        }
    }

    @Test fun fullVersionKeepsEsriStylesOnlyWhenTheKeyIsThere() {
        BasemapStyle.entries.filter { it.requiresEsriKey }.forEach { style ->
            assertEquals(style, FeatureGate.displayStyle(style, fullVersion = true, esriKeyAvailable = true))
            // keyless dev build: same fallback as before 3.1
            assertEquals(
                BasemapStyle.OSM_TOPO,
                FeatureGate.displayStyle(style, fullVersion = true, esriKeyAvailable = false),
            )
        }
    }

    @Test fun layersRowLockFollowsTheKeyRequirementAndEntitlement() {
        assertTrue(FeatureGate.basemapLocked(BasemapStyle.ESRI_SATELLITE, fullVersion = false))
        assertTrue(FeatureGate.basemapLocked(BasemapStyle.ESRI_TOPO, fullVersion = false))
        assertTrue(FeatureGate.basemapLocked(BasemapStyle.OSM_STREET, fullVersion = false))
        assertFalse(FeatureGate.basemapLocked(BasemapStyle.OSM_TOPO, fullVersion = false))
        BasemapStyle.entries.forEach { style ->
            assertFalse(FeatureGate.basemapLocked(style, fullVersion = true))
        }
    }

    @Test fun featureAccessDefaultsToFullAndFollowsSet() {
        assertTrue(FeatureAccess.isFullVersion.value)
        FeatureAccess.set(false)
        assertFalse(FeatureAccess.isFullVersion.value)
        FeatureAccess.set(true)
        assertTrue(FeatureAccess.isFullVersion.value)
    }
}
