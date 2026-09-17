package com.tacmap.app

/**
 * Owns the one pending track-start continuation across the Android permission
 * Activity and MainActivity's STARTED -> RESUMED transition.
 */
internal class TrackRecordingStartLifecycleGate {
    enum class RequestDecision {
        LaunchPermissions,
        ContinueNow,
        IgnoreDuplicate,
    }

    private var requested = false
    private var permissionRequestInFlight = false
    private var continuationQueued = false

    fun request(permissionRequired: Boolean): RequestDecision {
        if (requested) return RequestDecision.IgnoreDuplicate
        requested = true
        permissionRequestInFlight = permissionRequired
        continuationQueued = !permissionRequired
        return if (permissionRequired) {
            RequestDecision.LaunchPermissions
        } else {
            RequestDecision.ContinueNow
        }
    }

    fun onPermissionResult() {
        if (!requested || !permissionRequestInFlight) return
        permissionRequestInFlight = false
        continuationQueued = true
    }

    /** Returns true once, only after every lifecycle/key prerequisite is ready. */
    fun takeContinuation(isResumed: Boolean, prerequisitesReady: Boolean): Boolean {
        if (!requested || permissionRequestInFlight || !continuationQueued) return false
        if (!isResumed || !prerequisitesReady) return false
        requested = false
        continuationQueued = false
        return true
    }

    internal val hasPendingContinuation: Boolean
        get() = requested
}
