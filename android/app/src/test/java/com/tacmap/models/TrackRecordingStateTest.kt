package com.tacmap.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecordingStateTest {
    @Test fun permissionPolicyDistinguishesPreciseApproximateAndDenied() {
        assertEquals(LocationAccess.Precise, LocationAccessPolicy.resolve(true, true))
        assertEquals(LocationAccess.ApproximateOnly, LocationAccessPolicy.resolve(false, true))
        assertEquals(LocationAccess.Denied, LocationAccessPolicy.resolve(false, false))
    }

    @Test fun liveMapPermissionGuidanceIsSeparateAndTruthful() {
        assertEquals(null, LiveMapLocationPermissionPolicy.guidanceFor(LocationAccess.Precise))
        val approximate = requireNotNull(
            LiveMapLocationPermissionPolicy.guidanceFor(LocationAccess.ApproximateOnly)
        )
        assertTrue(approximate.message.contains("Approximate"))
        assertTrue(approximate.message.contains("Precise"))
        assertEquals(TrackRecordingSettingsTarget.AppPermissions, approximate.settingsTarget)

        val denied = requireNotNull(
            LiveMapLocationPermissionPolicy.guidanceFor(LocationAccess.Denied)
        )
        assertTrue(denied.message.contains("live position"))
        assertFalse(denied.message.contains("record", ignoreCase = true))
    }

    @Test fun liveMapControlsCoverExplicitRequestPreciseApproximateDeniedAndRestrictedStates() {
        val cases = listOf(
            LiveMapLocationState.NotRequested to LiveMapLocationAction.RequestPermission,
            LiveMapLocationState.Precise to LiveMapLocationAction.CentreOnLocation,
            LiveMapLocationState.ApproximateOnly to LiveMapLocationAction.OpenSettings,
            LiveMapLocationState.Denied to LiveMapLocationAction.OpenSettings,
            LiveMapLocationState.Restricted to LiveMapLocationAction.OpenSettings,
        )
        cases.forEach { (state, action) ->
            val control = LiveMapLocationPermissionPolicy.controlFor(state)
            assertEquals(action, control.action)
            assertTrue(control.title.isNotBlank())
            assertTrue(control.guidance.isNotBlank())
        }
        assertTrue(
            LiveMapLocationPermissionPolicy.controlFor(LiveMapLocationState.ApproximateOnly)
                .guidance.contains("Approximate")
        )
        assertTrue(
            LiveMapLocationPermissionPolicy.controlFor(LiveMapLocationState.Restricted)
                .guidance.contains("restricted")
        )
    }

    @Test fun liveMapUiStateDistinguishesFirstRequestDenialAndDeviceRestriction() {
        assertTrue(
            LiveMapLocationPermissionPolicy.shouldRequestOnInitialMapPresentation(
                LiveMapLocationState.NotRequested
            )
        )
        assertFalse(
            LiveMapLocationPermissionPolicy.shouldRequestOnInitialMapPresentation(
                LiveMapLocationState.Denied
            )
        )
        assertEquals(
            LiveMapLocationState.NotRequested,
            LiveMapLocationPermissionPolicy.resolveUiState(LocationAccess.Denied, false, false),
        )
        assertEquals(
            LiveMapLocationState.Denied,
            LiveMapLocationPermissionPolicy.resolveUiState(LocationAccess.Denied, true, false),
        )
        assertEquals(
            LiveMapLocationState.Restricted,
            LiveMapLocationPermissionPolicy.resolveUiState(LocationAccess.Denied, false, true),
        )
        assertEquals(
            LiveMapLocationState.ApproximateOnly,
            LiveMapLocationPermissionPolicy.resolveUiState(LocationAccess.ApproximateOnly, true, true),
        )
    }

    @Test fun duplicateStartEventsDoNotRegressAuthorizedSession() {
        for (phase in listOf(TrackRecordingPhase.Starting, TrackRecordingPhase.Recording)) {
            val state = TrackRecordingUiState(phase = phase)
            assertEquals(
                state,
                TrackRecordingReducer.reduce(
                    state,
                    TrackRecordingEvent.StartRequested(LocationAccess.Precise, gpsEnabled = true),
                ),
            )
        }
    }

    @Test fun serviceStartPolicyIgnoresDuplicateWithoutRejectingActiveService() {
        assertEquals(
            TrackRecordingServiceStartDecision.IgnoreDuplicate,
            TrackRecordingServiceStartPolicy.decide(
                activeGeneration = 7L,
                activeGenerationAuthorized = true,
                preparedGeneration = null,
            ),
        )
        assertEquals(
            TrackRecordingServiceStartDecision.Activate(8L),
            TrackRecordingServiceStartPolicy.decide(
                activeGeneration = null,
                activeGenerationAuthorized = false,
                preparedGeneration = 8L,
            ),
        )
        assertEquals(
            TrackRecordingServiceStartDecision.Reject,
            TrackRecordingServiceStartPolicy.decide(
                activeGeneration = null,
                activeGenerationAuthorized = false,
                preparedGeneration = null,
            ),
        )
    }

    @Test fun preciseStartShowsStartingUntilServiceActivation() {
        val starting = TrackRecordingReducer.reduce(
            TrackRecordingUiState(),
            TrackRecordingEvent.StartRequested(LocationAccess.Precise, gpsEnabled = true),
        )
        assertEquals(TrackRecordingPhase.Starting, starting.phase)
        assertFalse(starting.showsRec)

        val recording = TrackRecordingReducer.reduce(starting, TrackRecordingEvent.ServiceActivated)
        assertEquals(TrackRecordingPhase.Recording, recording.phase)
        assertTrue(recording.showsRec)
    }

    @Test fun approximateAndDeniedGiveTruthfulPermissionGuidance() {
        val approximate = TrackRecordingReducer.reduce(
            TrackRecordingUiState(),
            TrackRecordingEvent.StartRequested(LocationAccess.ApproximateOnly, true),
        )
        assertEquals(TrackRecordingPhase.AwaitingPermission, approximate.phase)
        assertTrue(approximate.message.orEmpty().contains("Approximate"))
        assertEquals(TrackRecordingSettingsTarget.AppPermissions, approximate.settingsTarget)

        val denied = TrackRecordingReducer.reduce(
            TrackRecordingUiState(),
            TrackRecordingEvent.StartRequested(LocationAccess.Denied, true),
        )
        assertEquals(TrackRecordingPhase.AwaitingPermission, denied.phase)
        assertTrue(denied.message.orEmpty().contains("Precise"))
    }

    @Test fun gpsDisabledIsInterruptedWithLocationSettings() {
        val state = TrackRecordingReducer.reduce(
            TrackRecordingUiState(),
            TrackRecordingEvent.StartRequested(LocationAccess.Precise, gpsEnabled = false),
        )
        assertEquals(TrackRecordingPhase.Interrupted, state.phase)
        assertEquals(TrackRecordingSettingsTarget.LocationServices, state.settingsTarget)
        assertFalse(state.showsRec)
    }

    @Test fun midSessionPermissionRevocationStopsRecTruthfully() {
        val recording = TrackRecordingUiState(phase = TrackRecordingPhase.Recording)
        val approximate = TrackRecordingReducer.reduce(
            recording,
            TrackRecordingEvent.PermissionChanged(LocationAccess.ApproximateOnly),
        )
        assertEquals(TrackRecordingPhase.Interrupted, approximate.phase)
        assertTrue(approximate.message.orEmpty().contains("Approximate"))
        assertFalse(approximate.showsRec)

        val denied = TrackRecordingReducer.reduce(
            recording,
            TrackRecordingEvent.PermissionChanged(LocationAccess.Denied),
        )
        assertEquals(TrackRecordingPhase.Interrupted, denied.phase)
        assertFalse(denied.showsRec)
    }
}
