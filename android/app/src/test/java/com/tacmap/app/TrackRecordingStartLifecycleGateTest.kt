package com.tacmap.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRecordingStartLifecycleGateTest {
    @Test
    fun grantCallbackBeforeResumeQueuesExactlyOneContinuation() {
        val gate = TrackRecordingStartLifecycleGate()
        assertEquals(
            TrackRecordingStartLifecycleGate.RequestDecision.LaunchPermissions,
            gate.request(permissionRequired = true),
        )

        gate.onPermissionResult() // grant
        assertFalse(gate.takeContinuation(isResumed = false, prerequisitesReady = true))
        assertTrue(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
        assertFalse(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
        assertFalse(gate.hasPendingContinuation)
    }

    @Test
    fun denyCallbackAfterResumeStillContinuesOnceForTruthfulGuidance() {
        val gate = TrackRecordingStartLifecycleGate()
        gate.request(permissionRequired = true)

        assertFalse(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
        gate.onPermissionResult() // denial is evaluated by the existing permission policy
        assertTrue(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
        assertFalse(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
    }

    @Test
    fun cancelledPermissionSheetWaitsForNextResumeAndClearsOnce() {
        val gate = TrackRecordingStartLifecycleGate()
        gate.request(permissionRequired = true)

        gate.onPermissionResult() // an empty platform result uses the same safe path
        assertFalse(gate.takeContinuation(isResumed = false, prerequisitesReady = true))
        assertTrue(gate.hasPendingContinuation)
        assertTrue(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
        assertFalse(gate.hasPendingContinuation)
    }

    @Test
    fun resumeBeforeCallbackDoesNotConsumePermissionInFlight() {
        val gate = TrackRecordingStartLifecycleGate()
        gate.request(permissionRequired = true)

        assertFalse(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
        gate.onPermissionResult()
        assertTrue(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
    }

    @Test
    fun keyUnlockCanFollowResumeWithoutLosingContinuation() {
        val gate = TrackRecordingStartLifecycleGate()
        assertEquals(
            TrackRecordingStartLifecycleGate.RequestDecision.ContinueNow,
            gate.request(permissionRequired = false),
        )

        assertFalse(gate.takeContinuation(isResumed = true, prerequisitesReady = false))
        assertTrue(gate.hasPendingContinuation)
        assertTrue(gate.takeContinuation(isResumed = true, prerequisitesReady = true))
    }

    @Test
    fun duplicateTapCannotLaunchASecondPermissionRequest() {
        val gate = TrackRecordingStartLifecycleGate()
        gate.request(permissionRequired = true)

        assertEquals(
            TrackRecordingStartLifecycleGate.RequestDecision.IgnoreDuplicate,
            gate.request(permissionRequired = true),
        )
    }
}
