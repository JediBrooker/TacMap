package com.tacmap.models

import com.tacmap.localization.L10n

/** The location grant states Android can return when fine and coarse are requested together. */
enum class LocationAccess {
    Precise,
    ApproximateOnly,
    Denied,
}

object LocationAccessPolicy {
    fun resolve(fineGranted: Boolean, coarseGranted: Boolean): LocationAccess = when {
        fineGranted -> LocationAccess.Precise
        coarseGranted -> LocationAccess.ApproximateOnly
        else -> LocationAccess.Denied
    }
}

data class LiveMapLocationGuidance(
    val message: String,
    val settingsTarget: TrackRecordingSettingsTarget,
)

enum class LiveMapLocationState {
    NotRequested,
    Precise,
    ApproximateOnly,
    Denied,
    Restricted,
}

enum class LiveMapLocationAction {
    RequestPermission,
    CentreOnLocation,
    OpenSettings,
}

data class LiveMapLocationControl(
    val title: String,
    val guidance: String,
    val action: LiveMapLocationAction,
)

/** Permission copy for the live map, kept separate from the recording state machine. */
object LiveMapLocationPermissionPolicy {
    fun shouldRequestOnInitialMapPresentation(state: LiveMapLocationState): Boolean =
        state == LiveMapLocationState.NotRequested

    fun resolveUiState(
        access: LocationAccess,
        permissionRequested: Boolean,
        policyRestricted: Boolean,
    ): LiveMapLocationState = when {
        access == LocationAccess.Precise -> LiveMapLocationState.Precise
        access == LocationAccess.ApproximateOnly -> LiveMapLocationState.ApproximateOnly
        policyRestricted -> LiveMapLocationState.Restricted
        permissionRequested -> LiveMapLocationState.Denied
        else -> LiveMapLocationState.NotRequested
    }

    fun controlFor(state: LiveMapLocationState): LiveMapLocationControl = when (state) {
        LiveMapLocationState.NotRequested -> LiveMapLocationControl(
            title = L10n.text("Enable Live Location"),
            guidance = L10n.text("TacMap requests Location access on first launch. Tap to request it again if needed."),
            action = LiveMapLocationAction.RequestPermission,
        )
        LiveMapLocationState.Precise -> LiveMapLocationControl(
            title = L10n.text("Centre on My Location"),
            guidance = L10n.text("Centres the map on your latest precise location."),
            action = LiveMapLocationAction.CentreOnLocation,
        )
        LiveMapLocationState.ApproximateOnly -> LiveMapLocationControl(
            title = L10n.text("Enable Precise Location"),
            guidance = L10n.text("Approximate location is on. Open Settings to allow Precise location."),
            action = LiveMapLocationAction.OpenSettings,
        )
        LiveMapLocationState.Denied -> LiveMapLocationControl(
            title = L10n.text("Open Location Settings"),
            guidance = L10n.text("Location access is off. Opens Settings so you can enable it."),
            action = LiveMapLocationAction.OpenSettings,
        )
        LiveMapLocationState.Restricted -> LiveMapLocationControl(
            title = L10n.text("Location Restricted"),
            guidance = L10n.text("Location access is restricted by this device. Review Location settings."),
            action = LiveMapLocationAction.OpenSettings,
        )
    }

    fun guidanceFor(access: LocationAccess): LiveMapLocationGuidance? = when (access) {
        LocationAccess.Precise -> null
        LocationAccess.ApproximateOnly -> LiveMapLocationGuidance(
            message = L10n.text("Approximate location cannot provide TacMap's on-device GPS position. ") +
                L10n.text("Allow Precise location, then try again."),
            settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
        )
        LocationAccess.Denied -> LiveMapLocationGuidance(
            message = L10n.text("Allow Precise location to show your live position on the map."),
            settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
        )
    }
}

sealed interface TrackRecordingServiceStartDecision {
    data object IgnoreDuplicate : TrackRecordingServiceStartDecision
    data class Activate(val generation: Long) : TrackRecordingServiceStartDecision
    data object Reject : TrackRecordingServiceStartDecision
}

/** Pure seam for the Service command path, including Android's duplicate start delivery. */
object TrackRecordingServiceStartPolicy {
    fun decide(
        activeGeneration: Long?,
        activeGenerationAuthorized: Boolean,
        preparedGeneration: Long?,
    ): TrackRecordingServiceStartDecision = when {
        activeGeneration != null && activeGenerationAuthorized ->
            TrackRecordingServiceStartDecision.IgnoreDuplicate
        preparedGeneration != null -> TrackRecordingServiceStartDecision.Activate(preparedGeneration)
        else -> TrackRecordingServiceStartDecision.Reject
    }
}

enum class TrackRecordingPhase {
    Idle,
    AwaitingPermission,
    Starting,
    Recording,
    Interrupted,
}

enum class TrackRecordingSettingsTarget {
    AppPermissions,
    LocationServices,
}

data class TrackRecordingUiState(
    val phase: TrackRecordingPhase = TrackRecordingPhase.Idle,
    val message: String? = null,
    val settingsTarget: TrackRecordingSettingsTarget? = null,
) {
    val showsRec: Boolean get() = phase == TrackRecordingPhase.Recording
    val isAuthorizedSession: Boolean
        get() = phase == TrackRecordingPhase.Starting || phase == TrackRecordingPhase.Recording
}

sealed interface TrackRecordingEvent {
    data class AwaitingPermission(
        val message: String,
        val settingsTarget: TrackRecordingSettingsTarget? = null,
    ) : TrackRecordingEvent

    data class StartRequested(
        val locationAccess: LocationAccess,
        val gpsEnabled: Boolean,
    ) : TrackRecordingEvent

    data object ServiceActivated : TrackRecordingEvent

    data class Interrupted(
        val message: String,
        val settingsTarget: TrackRecordingSettingsTarget? = null,
    ) : TrackRecordingEvent

    data class PermissionChanged(val locationAccess: LocationAccess) : TrackRecordingEvent

    data object Stopped : TrackRecordingEvent
}

/** Pure state contract shared by permission, recorder, service, and Compose UI. */
object TrackRecordingReducer {
    fun reduce(
        state: TrackRecordingUiState,
        event: TrackRecordingEvent,
    ): TrackRecordingUiState = when (event) {
        is TrackRecordingEvent.AwaitingPermission -> TrackRecordingUiState(
            phase = TrackRecordingPhase.AwaitingPermission,
            message = event.message,
            settingsTarget = event.settingsTarget,
        )

        is TrackRecordingEvent.StartRequested -> when {
            state.isAuthorizedSession -> state

            event.locationAccess == LocationAccess.ApproximateOnly -> TrackRecordingUiState(
                phase = TrackRecordingPhase.AwaitingPermission,
                message = L10n.text("Approximate location cannot provide the precise GPS track TacMap records. ") +
                    L10n.text("Allow Precise location, then retry."),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )

            event.locationAccess == LocationAccess.Denied -> TrackRecordingUiState(
                phase = TrackRecordingPhase.AwaitingPermission,
                message = L10n.text("Precise location permission is required to record a GPS track."),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )

            !event.gpsEnabled -> TrackRecordingUiState(
                phase = TrackRecordingPhase.Interrupted,
                message = L10n.text("GPS is turned off. Turn on device location services, then retry."),
                settingsTarget = TrackRecordingSettingsTarget.LocationServices,
            )

            else -> TrackRecordingUiState(phase = TrackRecordingPhase.Starting)
        }

        TrackRecordingEvent.ServiceActivated ->
            if (state.phase == TrackRecordingPhase.Starting) {
                TrackRecordingUiState(phase = TrackRecordingPhase.Recording)
            } else {
                state
            }

        is TrackRecordingEvent.Interrupted -> TrackRecordingUiState(
            phase = TrackRecordingPhase.Interrupted,
            message = event.message,
            settingsTarget = event.settingsTarget,
        )

        is TrackRecordingEvent.PermissionChanged -> when {
            event.locationAccess == LocationAccess.Precise -> state
            !state.isAuthorizedSession -> state
            event.locationAccess == LocationAccess.ApproximateOnly -> TrackRecordingUiState(
                phase = TrackRecordingPhase.Interrupted,
                message = L10n.text("Precise location was removed; recording stopped. ") +
                    L10n.text("Approximate location is not accurate enough for a GPS track."),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )

            else -> TrackRecordingUiState(
                phase = TrackRecordingPhase.Interrupted,
                message = L10n.text("Location permission was removed; recording stopped."),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )
        }

        TrackRecordingEvent.Stopped -> TrackRecordingUiState()
    }
}
