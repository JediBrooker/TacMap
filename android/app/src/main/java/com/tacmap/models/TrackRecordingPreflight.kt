package com.tacmap.models

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build

data class TrackRecordingPrerequisites(
    val activityVisible: Boolean,
    val locationAccess: LocationAccess,
    val gpsEnabled: Boolean,
    val apiLevel: Int,
    val foregroundServicePermissionDeclared: Boolean,
    val locationServicePermissionDeclared: Boolean,
    val locationServiceTypeDeclared: Boolean,
)

data class TrackRecordingPreflightResult(
    val canStart: Boolean,
    val message: String? = null,
    val settingsTarget: TrackRecordingSettingsTarget? = null,
)

object TrackRecordingPreflightPolicy {
    fun evaluate(value: TrackRecordingPrerequisites): TrackRecordingPreflightResult = when {
        !value.activityVisible -> TrackRecordingPreflightResult(
            false,
            "Track recording can only be started while TacMap is visible.",
        )

        value.locationAccess == LocationAccess.ApproximateOnly -> TrackRecordingPreflightResult(
            false,
            "Approximate location cannot provide the precise GPS track TacMap records.",
            TrackRecordingSettingsTarget.AppPermissions,
        )

        value.locationAccess == LocationAccess.Denied -> TrackRecordingPreflightResult(
            false,
            "Precise location permission is required to record a GPS track.",
            TrackRecordingSettingsTarget.AppPermissions,
        )

        !value.gpsEnabled -> TrackRecordingPreflightResult(
            false,
            "GPS is turned off. Turn on device location services, then retry.",
            TrackRecordingSettingsTarget.LocationServices,
        )

        value.apiLevel >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (!value.foregroundServicePermissionDeclared ||
                !value.locationServicePermissionDeclared ||
                !value.locationServiceTypeDeclared) -> TrackRecordingPreflightResult(
            false,
            "This build is missing an Android foreground-location service prerequisite.",
        )

        else -> TrackRecordingPreflightResult(true)
    }
}

/** Runtime inspection is kept here so the reducer remains host-unit-testable. */
object AndroidTrackRecordingPreflight {
    fun inspect(
        context: Context,
        activityVisible: Boolean,
        locationAccess: LocationAccess,
        gpsEnabled: Boolean,
    ): TrackRecordingPreflightResult {
        val permissions = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        val locationTypeDeclared = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                val component = ComponentName(context, TrackRecordingService::class.java)
                val info = context.packageManager.getServiceInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(0),
                )
                info.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0
            }.getOrDefault(false)
        } else {
            true
        }

        return TrackRecordingPreflightPolicy.evaluate(
            TrackRecordingPrerequisites(
                activityVisible = activityVisible,
                locationAccess = locationAccess,
                gpsEnabled = gpsEnabled,
                apiLevel = Build.VERSION.SDK_INT,
                foregroundServicePermissionDeclared = FOREGROUND_SERVICE_PERMISSION in permissions,
                locationServicePermissionDeclared =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                        Manifest.permission.FOREGROUND_SERVICE_LOCATION in permissions,
                locationServiceTypeDeclared = locationTypeDeclared,
            )
        )
    }

    private const val FOREGROUND_SERVICE_PERMISSION = "android.permission.FOREGROUND_SERVICE"
}
