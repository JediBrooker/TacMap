package com.tacticalmaps.map

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tacticalmaps.calibration.MapSource
import com.tacticalmaps.calibration.OpenStreetMapSourceAndroid
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
 *
 * Coordinates are exposed as bare (lat, lng, zoom) triples so the VM
 * doesn't depend on any specific map library type.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    val locationService = LocationService(app)

    // Camera centre published by MapScreen on every camera-idle event.
    private val _cameraLat = MutableStateFlow(0.0)
    val cameraLat: StateFlow<Double> = _cameraLat.asStateFlow()
    private val _cameraLng = MutableStateFlow(0.0)
    val cameraLng: StateFlow<Double> = _cameraLng.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private val _mapBearingDegrees = MutableStateFlow(0.0)
    val mapBearingDegrees: StateFlow<Double> = _mapBearingDegrees.asStateFlow()

    private val _mapSource = MutableStateFlow<MapSource>(OpenStreetMapSourceAndroid())
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    fun setMapSource(source: MapSource) {
        _mapSource.value = source
        source.coverage?.center?.let { center ->
            flyTo(center.latitude, center.longitude, 13f)
        }
    }

    fun unloadPdfMap() {
        _mapSource.value = OpenStreetMapSourceAndroid()
    }

    /** Programmatic camera target = (lat, lng, zoom). null when nothing
     *  pending. MapScreen consumes via [consumePendingCameraTarget]. */
    private val _pendingCameraTarget = MutableStateFlow<Triple<Double, Double, Float>?>(null)
    val pendingCameraTarget: StateFlow<Triple<Double, Double, Float>?> = _pendingCameraTarget.asStateFlow()

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
    fun onCameraIdle(lat: Double, lng: Double, byUser: Boolean) {
        _cameraLat.value = lat
        _cameraLng.value = lng
        if (byUser) _isBrowsing.value = true
    }

    fun onMapBearingChanged(degrees: Double) {
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        if (abs(_mapBearingDegrees.value - normalized) > 0.05) {
            _mapBearingDegrees.value = normalized
        }
    }

    fun consumePendingCameraTarget() { _pendingCameraTarget.value = null }

    private fun onUserLocation(loc: Location) {
        lastUserLocation = loc
        if (!hasInitialFix) {
            hasInitialFix = true
            centreOnUser()
        }
    }

    fun centreOnUser() {
        val loc = lastUserLocation ?: return
        _isBrowsing.value = false
        _cameraLat.value = loc.latitude
        _cameraLng.value = loc.longitude
        _pendingCameraTarget.value = Triple(loc.latitude, loc.longitude, 15f)
    }

    /** Animate camera to an arbitrary coordinate. Used by the
     *  waypoint list's "fly to" rows. Enters browse mode so the
     *  header reads the map centre, not the user. */
    fun flyTo(lat: Double, lng: Double, zoom: Float = 15f) {
        _isBrowsing.value = true
        _cameraLat.value = lat
        _cameraLng.value = lng
        _pendingCameraTarget.value = Triple(lat, lng, zoom)
    }

    // MARK: - Header content

    val headerMgrs: String get() {
        val (lat, lng) = headerCoordinate
        return MgrsFormatter.format(lat, lng)
    }

    val headerWgs84: String get() {
        val (lat, lng) = headerCoordinate
        return "%.5f° %s, %.5f° %s".format(
            abs(lat), if (lat >= 0) "N" else "S",
            abs(lng), if (lng >= 0) "E" else "W"
        )
    }

    private val headerCoordinate: Pair<Double, Double>
        get() = if (_isBrowsing.value) {
            _cameraLat.value to _cameraLng.value
        } else {
            lastUserLocation?.let { it.latitude to it.longitude }
                ?: (_cameraLat.value to _cameraLng.value)
        }
}
