package com.tacmap.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecordingPreflightTest {
    private fun prerequisites(
        visible: Boolean = true,
        access: LocationAccess = LocationAccess.Precise,
        gps: Boolean = true,
        api: Int = 34,
        fgs: Boolean = true,
        fgsLocation: Boolean = true,
        serviceType: Boolean = true,
    ) = TrackRecordingPrerequisites(visible, access, gps, api, fgs, fgsLocation, serviceType)

    @Test fun visiblePreciseApi34StartPassesWhenManifestPrerequisitesExist() {
        assertTrue(TrackRecordingPreflightPolicy.evaluate(prerequisites()).canStart)
    }

    @Test fun backgroundStartAndMissingApi34DeclarationFailSafely() {
        assertFalse(TrackRecordingPreflightPolicy.evaluate(prerequisites(visible = false)).canStart)
        assertFalse(TrackRecordingPreflightPolicy.evaluate(prerequisites(fgsLocation = false)).canStart)
        assertFalse(TrackRecordingPreflightPolicy.evaluate(prerequisites(serviceType = false)).canStart)
    }

    @Test fun api33DoesNotRequireApi34LocationFgsDeclaration() {
        assertTrue(
            TrackRecordingPreflightPolicy.evaluate(
                prerequisites(api = 33, fgsLocation = false, serviceType = false)
            ).canStart
        )
    }

    @Test fun approximateAndGpsDisabledReturnCorrectSettingsTargets() {
        val approximate = TrackRecordingPreflightPolicy.evaluate(
            prerequisites(access = LocationAccess.ApproximateOnly)
        )
        assertEquals(TrackRecordingSettingsTarget.AppPermissions, approximate.settingsTarget)

        val gpsOff = TrackRecordingPreflightPolicy.evaluate(prerequisites(gps = false))
        assertEquals(TrackRecordingSettingsTarget.LocationServices, gpsOff.settingsTarget)
    }
}
