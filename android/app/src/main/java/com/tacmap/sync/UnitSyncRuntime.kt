package com.tacmap.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.tacmap.drawings.DrawingStore
import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.settings.OpsecSettings
import com.tacmap.waypoints.WaypointStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scoped owner for the single Unit Sync client.
 *
 * MapScreen owns decrypted mission stores. This runtime owns only the client
 * identity across a screen-off boundary and asks [SyncManager] to discard every
 * mission-data capability before MainActivity locks the data key.
 */
class UnitSyncRuntime(
    context: Context,
    private val opsec: OpsecSettings,
) {
    data class ForegroundLease internal constructor(
        val manager: SyncManager,
        internal val id: Long,
    )

    internal interface ServiceController {
        fun updateInterval(interval: BackgroundUnitSyncInterval)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _foregroundEpoch = MutableStateFlow(0L)
    /** Changes at every key-lock boundary so Compose rebuilds mission stores
     * even if lifecycle-paused recomposition coalesces false/true key state. */
    val foregroundEpoch: StateFlow<Long> = _foregroundEpoch.asStateFlow()

    private var manager: SyncManager? = null
    private var screenLeaseCounter = 0L
    private var activeScreenLeaseId: Long? = null
    private var activityForeground = false
    private var serviceGenerationCounter = 0L
    private var authorizedServiceGeneration: Long? = null
    private var serviceController: ServiceController? = null
    private var lastPrerequisiteIssue: String? = null
    /** Notification Stop must remain effective for this process even if the
     * preference disk commit fails and its last durable value is still true. */
    private var backgroundStopSuppressed = false

    init {
        scope.launch {
            combine(
                opsec.backgroundUnitSyncLocation,
                opsec.backgroundUnitSyncInterval,
            ) { enabled, interval -> enabled to interval }
                .collect { (enabled, interval) ->
                    if (!enabled) {
                        backgroundStopSuppressed = false
                    }
                    serviceController?.updateInterval(interval)
                    refreshServiceEligibility()
                }
        }
    }

    /** Acquire the one foreground client, reattaching fresh unlocked stores if
     * it survived a prior screen-off session. */
    @Synchronized
    fun acquireForeground(
        waypointStore: WaypointStore,
        drawingStore: DrawingStore,
        parentScope: CoroutineScope,
        locationProvider: () -> Location?,
    ): ForegroundLease {
        activityForeground = true
        val retained = manager?.takeUnless { it.isDisposed }
        val resolved = if (retained != null && retained.isBackgroundPresenceOnly) {
            if (retained.attachForegroundStores(waypointStore, drawingStore, locationProvider)) {
                retained
            } else {
                newManager(waypointStore, drawingStore, parentScope, locationProvider)
            }
        } else if (retained != null && activeScreenLeaseId != null) {
            retained.also { it.locationProvider = locationProvider }
        } else {
            retained?.dispose()
            newManager(waypointStore, drawingStore, parentScope, locationProvider)
        }
        manager = resolved
        screenLeaseCounter += 1L
        val lease = ForegroundLease(resolved, screenLeaseCounter)
        activeScreenLeaseId = lease.id
        refreshServiceEligibility()
        return lease
    }

    /** Called by MapScreen disposal. A restricted background client remains;
     * every other client is torn down and its secrets are zeroed. */
    @Synchronized
    fun releaseScreen(lease: ForegroundLease) {
        if (activeScreenLeaseId != lease.id) return
        activeScreenLeaseId = null
        val candidate = lease.manager
        if (manager !== candidate) return
        if (candidate.isBackgroundPresenceOnly) return
        manager = null
        invalidateAndStopService(revokeSession = false)
        candidate.runtimeStateChanged = null
        candidate.backgroundTransportEnded = null
        candidate.dispose()
    }

    /** Handles the case where Compose retained MapScreen across a stopped
     * lifecycle and therefore did not rerun remember/acquire after key unlock. */
    @Synchronized
    fun reattachRetainedScreen(
        lease: ForegroundLease,
        waypointStore: WaypointStore,
        drawingStore: DrawingStore,
        locationProvider: () -> Location?,
    ) {
        if (!activityForeground || activeScreenLeaseId != lease.id ||
            manager !== lease.manager || !lease.manager.isBackgroundPresenceOnly
        ) return
        lease.manager.attachForegroundStores(waypointStore, drawingStore, locationProvider)
        refreshServiceEligibility()
    }

    /** Must run before DataKey.lock(). */
    @Synchronized
    fun onActivityPausing() {
        activityForeground = false
        _foregroundEpoch.value = if (_foregroundEpoch.value == Long.MAX_VALUE) {
            0L
        } else {
            _foregroundEpoch.value + 1L
        }
        val current = manager ?: return
        val optedInV3Location = opsec.backgroundUnitSyncLocation.value &&
            current.canArmBackgroundLocationService()
        val eligible = optedInV3Location && authorizedServiceGeneration != null &&
            hasPreciseLocation() && gpsEnabled()
        if (eligible && current.enterBackgroundPresenceOnly(opsec.backgroundUnitSyncInterval.value)) {
            return
        }
        if (optedInV3Location && current.suspendUntilForegroundStores()) {
            invalidateAndStopService(revokeSession = false)
            return
        }

        manager = null
        activeScreenLeaseId = null
        invalidateAndStopService(revokeSession = false)
        current.runtimeStateChanged = null
        current.backgroundTransportEnded = null
        current.dispose()
    }

    /** Stops egress immediately. Reconnection waits for DataKey unlock and the
     * subsequent [acquireForeground] call with newly constructed stores. */
    @Synchronized
    fun onActivityForegrounded() {
        activityForeground = true
        manager?.prepareForForegroundUnlock()
        refreshServiceEligibility()
    }

    @Synchronized
    internal fun attachService(
        generation: Long,
        controller: ServiceController,
    ): BackgroundUnitSyncInterval? {
        if (authorizedServiceGeneration != generation || !serviceStillEligible()) return null
        serviceController = controller
        return opsec.backgroundUnitSyncInterval.value
    }

    @Synchronized
    internal fun detachService(generation: Long, controller: ServiceController) {
        if (serviceController === controller) serviceController = null
        if (authorizedServiceGeneration == generation) {
            authorizedServiceGeneration = null
            manager?.revokeBackgroundLocationEligibility(reconnectIfForeground = activityForeground)
        }
    }

    @Synchronized
    internal fun onServiceLocation(generation: Long, location: Location) {
        if (authorizedServiceGeneration != generation || activityForeground) return
        val current = manager ?: return
        current.sendBackgroundPresence(
            location = location,
            interval = opsec.backgroundUnitSyncInterval.value,
        )
    }

    @Synchronized
    internal fun onServiceUnavailable(generation: Long, message: String) {
        if (authorizedServiceGeneration != generation) return
        authorizedServiceGeneration = null
        serviceController = null
        manager?.revokeBackgroundLocationEligibility(reconnectIfForeground = activityForeground)
        manager?.reportBackgroundLocationIssue(message)
        BackgroundUnitSyncLocationService.stop(appContext)
    }

    @Synchronized
    internal fun userStoppedBackgroundSharing(generation: Long) {
        if (authorizedServiceGeneration != generation) return
        val persisted = opsec.setBackgroundUnitSyncLocation(false)
        if (!persisted) {
            backgroundStopSuppressed = true
            manager?.reportBackgroundLocationIssue(
                "Background Unit Sync stopped, but its OFF setting could not be saved. " +
                    "Check available storage and turn it off again in Privacy & OPSEC."
            )
        }
        invalidateAndStopService(revokeSession = true)
    }

    private fun newManager(
        waypointStore: WaypointStore,
        drawingStore: DrawingStore,
        parentScope: CoroutineScope,
        locationProvider: () -> Location?,
    ): SyncManager = SyncManager(waypointStore, drawingStore, parentScope, appContext).also { created ->
        created.locationProvider = locationProvider
        created.runtimeStateChanged = { refreshServiceEligibility() }
        created.backgroundTransportEnded = { onBackgroundTransportEnded(created) }
    }

    @Synchronized
    private fun onBackgroundTransportEnded(candidate: SyncManager) {
        if (manager !== candidate || !candidate.isBackgroundPresenceOnly) return
        invalidateAndStopService(revokeSession = false)
    }

    @Synchronized
    private fun refreshServiceEligibility() {
        val wantsBackgroundLocation = opsec.backgroundUnitSyncLocation.value &&
            manager?.canArmBackgroundLocationService() == true
        val prerequisiteIssue = when {
            !wantsBackgroundLocation -> null
            !hasPreciseLocation() ->
                "Background Unit Sync needs Precise location permission before it can run with the screen off."
            !gpsEnabled() ->
                "Background Unit Sync needs GPS turned on before it can run with the screen off."
            else -> null
        }
        if (activityForeground && prerequisiteIssue != null &&
            prerequisiteIssue != lastPrerequisiteIssue
        ) {
            manager?.reportBackgroundLocationIssue(prerequisiteIssue)
        }
        lastPrerequisiteIssue = prerequisiteIssue

        val shouldRun = serviceStillEligible()
        if (shouldRun) {
            serviceController?.updateInterval(opsec.backgroundUnitSyncInterval.value)
            if (authorizedServiceGeneration == null && activityForeground) {
                serviceGenerationCounter += 1L
                val generation = serviceGenerationCounter
                authorizedServiceGeneration = generation
                try {
                    BackgroundUnitSyncLocationService.start(appContext, generation)
                } catch (failure: RuntimeException) {
                    authorizedServiceGeneration = null
                    manager?.revokeBackgroundLocationEligibility(reconnectIfForeground = true)
                    manager?.reportBackgroundLocationIssue(
                        "Background Unit Sync could not start: " +
                            (failure.message ?: failure.javaClass.simpleName)
                    )
                }
            }
        } else if (authorizedServiceGeneration != null) {
            invalidateAndStopService(revokeSession = true)
        }
    }

    private fun serviceStillEligible(): Boolean =
        !backgroundStopSuppressed && opsec.backgroundUnitSyncLocation.value &&
            hasPreciseLocation() && gpsEnabled() &&
            manager?.canArmBackgroundLocationService() == true

    private fun invalidateAndStopService(revokeSession: Boolean) {
        val wasAuthorized = authorizedServiceGeneration != null
        authorizedServiceGeneration = null
        serviceController = null
        if (wasAuthorized) {
            BackgroundUnitSyncLocationService.stop(appContext)
            if (revokeSession) {
                manager?.revokeBackgroundLocationEligibility(reconnectIfForeground = activityForeground)
            }
        }
    }

    private fun hasPreciseLocation(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun gpsEnabled(): Boolean = runCatching {
        (appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager)
            .isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
}
