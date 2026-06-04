package com.tacticalmaps.map

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tacticalmaps.calibration.MapSource
import com.tacticalmaps.calibration.OpenStreetMapSourceAndroid
import com.tacticalmaps.calibration.PdfMapSource
import com.tacticalmaps.calibration.PdfSessionStore
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.models.LocationService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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

    private val pdfSessionStore = PdfSessionStore(app)

    private val _mapSource = MutableStateFlow<MapSource>(OpenStreetMapSourceAndroid())
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    /**
     * Set the active map source AND, when it has coverage, fly the camera
     * to a sensible starting position (see [frameCameraFor]).
     *
     * Calibrated PDF sources are also written through to
     * [pdfSessionStore] so they survive an app restart.
     */
    fun setMapSource(source: MapSource) {
        _mapSource.value = source
        if (source is PdfMapSource) pdfSessionStore.save(source)
        frameCameraFor(source)
    }

    /**
     * Frame the camera for a freshly-set or restored map source:
     *   - If we have a recent user fix inside the source's coverage box,
     *     centre on the user (so they immediately see "I am here on
     *     this paper map").
     *   - Otherwise centre on the coverage centre (the user is off-map
     *     and we want them to at least see the page).
     *
     * No-op for unbounded sources (e.g. OSM). Shared by [setMapSource]
     * and the startup restore so a restored PDF frames like an import.
     */
    private fun frameCameraFor(source: MapSource) {
        val coverage = source.coverage ?: return
        val userLoc = lastUserLocation
        if (userLoc != null && coverage.contains(userLoc.latitude, userLoc.longitude)) {
            flyTo(userLoc.latitude, userLoc.longitude, 15f)
        } else {
            val centre = coverage.center
            flyTo(centre.latitude, centre.longitude, 13f)
        }
    }

    fun unloadPdfMap() {
        _mapSource.value = OpenStreetMapSourceAndroid()
        pdfSessionStore.clear()
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
        /// Restore the last-imported PDF map (if any) on startup so the
        /// user doesn't have to re-import after closing the app, then frame
        /// it like an import would. Runs here (not in an earlier init block)
        /// because [frameCameraFor] -> flyTo touches [_pendingCameraTarget],
        /// which is declared above and must already be initialised.
        /// No fix yet at launch, so this frames the whole page; the first
        /// fix won't yank away if the user turns out to be off-map.
        pdfSessionStore.load()?.let { restored ->
            _mapSource.value = restored
            frameCameraFor(restored)
        }
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

    /** One-shot signal that the compass HUD was tapped, asking the
     *  map to animate its bearing back to 0° (north up). A Channel
     *  with BUFFERED capacity ensures rapid taps don't drop. */
    private val _resetNorthRequests = Channel<Unit>(Channel.BUFFERED)
    val resetNorthRequests: Flow<Unit> = _resetNorthRequests.receiveAsFlow()
    fun requestResetNorth() { _resetNorthRequests.trySend(Unit) }

    private fun onUserLocation(loc: Location) {
        lastUserLocation = loc
        if (!hasInitialFix) {
            hasInitialFix = true
            // Centre on the user on the first fix — unless a bounded map
            // (PDF) is active and the user is off it, in which case keep the
            // framing set at import/restore so an off-map PDF stays visible.
            val coverage = _mapSource.value.coverage
            if (coverage == null || coverage.contains(loc.latitude, loc.longitude)) {
                centreOnUser()
            }
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

    val headerCoordinate: Pair<Double, Double>
        get() = if (_isBrowsing.value) {
            _cameraLat.value to _cameraLng.value
        } else {
            lastUserLocation?.let { it.latitude to it.longitude }
                ?: (_cameraLat.value to _cameraLng.value)
        }

    override fun onCleared() {
        locationService.stop()
        super.onCleared()
    }
}
