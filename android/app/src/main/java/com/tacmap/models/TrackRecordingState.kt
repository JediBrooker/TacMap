package com.tacmap.models

import com.tacmap.localization.LocalizedMessage

import com.tacmap.localization.Messages


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
    val pendingMessage: LocalizedMessage,
    val settingsTarget: TrackRecordingSettingsTarget,
) {
    val message: String get() = pendingMessage.text
}

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
    val titleMessage: LocalizedMessage,
    val guidanceMessage: LocalizedMessage,
    val action: LiveMapLocationAction,
) {
    val title: String get() = titleMessage.text
    val guidance: String get() = guidanceMessage.text
}

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
            titleMessage = Messages.liveLocationEnableMessage(),
            guidanceMessage = Messages.liveLocationFirstLaunchMessage(),
            action = LiveMapLocationAction.RequestPermission,
        )
        LiveMapLocationState.Precise -> LiveMapLocationControl(
            titleMessage = Messages.liveLocationCentreMessage(),
            guidanceMessage = Messages.liveLocationCentrePreciseHintMessage(),
            action = LiveMapLocationAction.CentreOnLocation,
        )
        LiveMapLocationState.ApproximateOnly -> LiveMapLocationControl(
            titleMessage = Messages.liveLocationEnablePreciseMessage(),
            guidanceMessage = Messages.liveLocationApproximateHintMessage(),
            action = LiveMapLocationAction.OpenSettings,
        )
        LiveMapLocationState.Denied -> LiveMapLocationControl(
            titleMessage = Messages.liveLocationSettingsMessage(),
            guidanceMessage = Messages.liveLocationDisabledHintMessage(),
            action = LiveMapLocationAction.OpenSettings,
        )
        LiveMapLocationState.Restricted -> LiveMapLocationControl(
            titleMessage = Messages.liveLocationRestrictedMessage(),
            guidanceMessage = Messages.liveLocationRestrictedAndroidHintMessage(),
            action = LiveMapLocationAction.OpenSettings,
        )
    }

    fun guidanceFor(access: LocationAccess): LiveMapLocationGuidance? = when (access) {
        LocationAccess.Precise -> null
        LocationAccess.ApproximateOnly -> LiveMapLocationGuidance(
            pendingMessage = Messages.liveLocationApproximateGuidanceMessage(),
            settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
        )
        LocationAccess.Denied -> LiveMapLocationGuidance(
            pendingMessage = Messages.liveLocationPreciseNeededMessage(),
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
    val pendingMessage: LocalizedMessage? = null,
    val settingsTarget: TrackRecordingSettingsTarget? = null,
) {
    val message: String? get() = pendingMessage?.text
    val showsRec: Boolean get() = phase == TrackRecordingPhase.Recording
    val isAuthorizedSession: Boolean
        get() = phase == TrackRecordingPhase.Starting || phase == TrackRecordingPhase.Recording
}

sealed interface TrackRecordingEvent {
    data class AwaitingPermission(
        val message: LocalizedMessage,
        val settingsTarget: TrackRecordingSettingsTarget? = null,
    ) : TrackRecordingEvent

    data class StartRequested(
        val locationAccess: LocationAccess,
        val gpsEnabled: Boolean,
    ) : TrackRecordingEvent

    data object ServiceActivated : TrackRecordingEvent

    data class Interrupted(
        val message: LocalizedMessage,
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
            pendingMessage = event.message,
            settingsTarget = event.settingsTarget,
        )

        is TrackRecordingEvent.StartRequested -> when {
            state.isAuthorizedSession -> state

            event.locationAccess == LocationAccess.ApproximateOnly -> TrackRecordingUiState(
                phase = TrackRecordingPhase.AwaitingPermission,
                pendingMessage = Messages.recordingPreciseRequiredRetryMessage(),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )

            event.locationAccess == LocationAccess.Denied -> TrackRecordingUiState(
                phase = TrackRecordingPhase.AwaitingPermission,
                pendingMessage = Messages.recordingPreciseRequiredMessage(),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )

            !event.gpsEnabled -> TrackRecordingUiState(
                phase = TrackRecordingPhase.Interrupted,
                pendingMessage = Messages.recordingGpsRequiredMessage(),
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
            pendingMessage = event.message,
            settingsTarget = event.settingsTarget,
        )

        is TrackRecordingEvent.PermissionChanged -> when {
            event.locationAccess == LocationAccess.Precise -> state
            !state.isAuthorizedSession -> state
            event.locationAccess == LocationAccess.ApproximateOnly -> TrackRecordingUiState(
                phase = TrackRecordingPhase.Interrupted,
                pendingMessage = Messages.recordingPrecisionLostMessage(),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )

            else -> TrackRecordingUiState(
                phase = TrackRecordingPhase.Interrupted,
                pendingMessage = Messages.recordingPermissionLostMessage(),
                settingsTarget = TrackRecordingSettingsTarget.AppPermissions,
            )
        }

        TrackRecordingEvent.Stopped -> TrackRecordingUiState()
    }
}
