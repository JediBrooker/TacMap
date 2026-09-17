package com.tacmap.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpsecSettingsDefaultsTest {
    @Test
    fun freshInstallOnlineFeaturesDefaultOff() {
        val settings = OpsecSettings.resolveNetworkPreferences(emptyMap<String, Any?>())
        assertFalse(settings.onlineLookups)
        assertFalse(settings.onlineBasemaps)
        assertFalse(settings.backgroundUnitSyncLocation)
    }

    @Test
    fun persistedOnlineChoicesTakePrecedenceOverFreshInstallDefaults() {
        val enabled = OpsecSettings.resolveNetworkPreferences(
            mapOf(
                "online_lookups" to true,
                "online_basemaps" to true,
                "background_unit_sync_location" to true,
            )
        )
        assertTrue(enabled.onlineLookups)
        assertTrue(enabled.onlineBasemaps)
        assertTrue(enabled.backgroundUnitSyncLocation)

        val disabled = OpsecSettings.resolveNetworkPreferences(
            mapOf(
                "online_lookups" to false,
                "online_basemaps" to false,
                "background_unit_sync_location" to false,
            )
        )
        assertFalse(disabled.onlineLookups)
        assertFalse(disabled.onlineBasemaps)
        assertFalse(disabled.backgroundUnitSyncLocation)
    }
}
