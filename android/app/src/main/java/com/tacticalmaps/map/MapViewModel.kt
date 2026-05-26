package com.tacticalmaps.map

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.tacticalmaps.calibration.AppleSatelliteMapSourceAndroid
import com.tacticalmaps.calibration.MapSource
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.models.LocationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Owns map camera, browse-mode toggle, and the MGRS readout shown in the header.
 *
 * Browse mode = the user has manually panned/zoomed away from their location.
 * Cleared by `centreOnUser`.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    val locationService = LocationService(app)

    // Camera centre published by MapScreen whenever the GoogleMap camera moves.
    private val _cameraCentre = MutableStateFlow(LatLng(0.0, 0.0))
    val cameraCentre: StateFlow<LatLng> = _cameraCentre.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private val _mapSource = MutableStateFlow<MapSource>(AppleSatelliteMapSourceAndroid())
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    // Programmatic camera target (MapScreen animates the GoogleMap when this changes).
    private val _pendingCameraTarget = MutableStateFlow<CameraPosition?>(null)
    val pendingCameraTarget: StateFlow<CameraPosition?> = _pendingCameraTarget.asStateFlow()

    /** ID of the currently-selected waypoint. Drives the floating
     *  controls card in MapScreen. null = no selection. */
    private val _selectedWaypointId = MutableStateFlow<String?>(null)
    val selectedWaypointId: StateFlow<String?> = _selectedWaypointId.asStateFlow()
    fun selectWaypoint(id: String?) { _selectedWaypointId.value = id }

    private var lastUserLocation: Location? = null
    private var hasInitialFix = false

    init {
        viewModelScope.launch {
            locationService.lastLocation.collect { loc -> loc?.let(::onUserLocation) }
        }
    }

    /** Called by MapScreen on every camera idle event. `byUser` distinguishes
     *  user gestures from programmatic moves. */
    fun onCameraIdle(centre: LatLng, byUser: Boolean) {
        _cameraCentre.value = centre
        if (byUser) _isBrowsing.value = true
    }

    /** Clear the pending target once MapScreen has consumed it. */
    fun consumePendingCameraTarget() { _pendingCameraTarget.value = null }

    private fun onUserLocation(loc: Location) {
        lastUserLocation = loc
        if (!hasInitialFix) {
            hasInitialFix = true
            centreOnUser()
        }
    }

    fun centreOnUser() {
        val target = lastUserLocation?.let { LatLng(it.latitude, it.longitude) } ?: return
        _isBrowsing.value = false
        _cameraCentre.value = target
        _pendingCameraTarget.value = CameraPosition.Builder()
            .target(target)
            .zoom(15f)
            .build()
    }

    /** Animate camera to an arbitrary coordinate. Used by the
     *  waypoint list's "fly to" rows. Enters browse mode so the
     *  header reads the map centre, not the user. */
    fun flyTo(target: LatLng, zoom: Float = 15f) {
        _isBrowsing.value = true
        _cameraCentre.value = target
        _pendingCameraTarget.value = CameraPosition.Builder()
            .target(target)
            .zoom(zoom)
            .build()
    }

    // MARK: - Header content

    val headerMgrs: String get() = MgrsFormatter.format(headerCoordinate)

    val headerWgs84: String get() {
        val c = headerCoordinate
        return "%.5f° %s, %.5f° %s".format(
            abs(c.latitude),  if (c.latitude  >= 0) "N" else "S",
            abs(c.longitude), if (c.longitude >= 0) "E" else "W"
        )
    }

    private val headerCoordinate: LatLng
        get() = if (_isBrowsing.value) _cameraCentre.value
                else lastUserLocation?.let { LatLng(it.latitude, it.longitude) } ?: _cameraCentre.value
}
