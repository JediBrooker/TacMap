package com.tacmap.map

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.SatelliteMapSourceAndroid
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfSessionStore
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.models.LocationService
import com.tacmap.models.TrackRecordingService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** User-selectable online basemap: native Google satellite, Esri imagery, or
 *  OpenTopoMap terrain. */
enum class BaseMap { SATELLITE, ESRI_SATELLITE, TERRAIN }

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

    /** App-scoped OPSEC/privacy settings (screen-capture, online lookups, relay). */
    val opsec = (app as com.tacmap.app.TacticalApp).opsec

    val trackRecorder = (app as com.tacmap.app.TacticalApp).trackRecorder

    // Camera centre published by MapScreen on every camera-idle event.
    private val _cameraLat = MutableStateFlow(0.0)
    val cameraLat: StateFlow<Double> = _cameraLat.asStateFlow()
    private val _cameraLng = MutableStateFlow(0.0)
    val cameraLng: StateFlow<Double> = _cameraLng.asStateFlow()

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing.asStateFlow()

    private val _mapBearingDegrees = MutableStateFlow(0.0)
    val mapBearingDegrees: StateFlow<Double> = _mapBearingDegrees.asStateFlow()

    /**
     * Live terrain-elevation reading for the current map centre (metres MSL +
     * staleness), fetched from Open-Meteo's Copernicus DEM via
     * [ElevationService], debounced so we only hit the network once the user
     * stops panning. null until the first reading resolves. Mirrors iOS.
     */
    private val _centreElevation = MutableStateFlow<ElevationReading?>(null)
    val centreElevation: StateFlow<ElevationReading?> = _centreElevation.asStateFlow()
    private val elevationService = ElevationService()

    private val pdfSessionStore = PdfSessionStore(app)

    private val _mapSource = MutableStateFlow<MapSource>(SatelliteMapSourceAndroid())
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    /** Which online basemap to return to when an imported map is unloaded. */
    private var preferredBaseMap = BaseMap.SATELLITE
    private fun baseMapSource(choice: BaseMap): MapSource = when (choice) {
        BaseMap.SATELLITE -> SatelliteMapSourceAndroid()
        BaseMap.ESRI_SATELLITE -> OnlineRasterMapSourceAndroid(BasemapStyle.ESRI_SATELLITE)
        BaseMap.TERRAIN -> OnlineRasterMapSourceAndroid(BasemapStyle.TERRAIN)
    }
    private fun onlineBasemap(): MapSource = baseMapSource(preferredBaseMap)

    /** Switch the online basemap. Also clears any imported PDF so the chosen
     *  basemap actually shows. */
    fun selectBaseMap(choice: BaseMap) {
        preferredBaseMap = choice
        _mapSource.value = baseMapSource(choice)
        pdfSessionStore.clear()
    }

    /** Return to the preferred online basemap (e.g. after unloading offline tiles). */
    fun restoreOnlineBasemap() {
        _mapSource.value = onlineBasemap()
    }

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
        _mapSource.value = onlineBasemap()
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
        observeElevation()
    }

    /**
     * Fetch the centre elevation whenever the camera settles. Debounced 400ms
     * (matches iOS) and de-duped to ~11 m so a tiny jitter doesn't re-hit the
     * network. `collectLatest` cancels an in-flight fetch when the centre moves
     * again, so only the latest position resolves.
     */
    /** Round a coordinate to ~110 m so online lookups don't disclose the exact
     *  map centre. */
    private fun coarsen(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    @OptIn(FlowPreview::class)
    private fun observeElevation() {
        viewModelScope.launch {
            combine(_cameraLat, _cameraLng) { lat, lng -> lat to lng }
                .filter { (lat, lng) -> lat != 0.0 || lng != 0.0 }
                .debounce(400)
                .distinctUntilChanged { (oLat, oLng), (nLat, nLng) ->
                    abs(oLat - nLat) < 0.0001 && abs(oLng - nLng) < 0.0001
                }
                .collectLatest { (lat, lng) ->
                    // OPSEC: elevation lookups transmit the queried coordinate to
                    // a third party (Open-Meteo). Only do so when the user has
                    // opted in, and coarsen to ~110 m (3 dp) so the exact map
                    // centre isn't disclosed even then.
                    if (!opsec.onlineLookups.value) {
                        _centreElevation.value = null
                    } else {
                        _centreElevation.value = elevationService.reading(coarsen(lat), coarsen(lng))
                    }
                }
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

    /** Start GPX recording and bring up the foreground service so the track
     *  keeps logging while the app is backgrounded / the screen is locked. */
    fun startTrackRecording() {
        trackRecorder.start()
        TrackRecordingService.start(getApplication<android.app.Application>())
    }

    /** Stop recording and tear down the foreground service. */
    fun stopTrackRecording() {
        trackRecorder.stop()
        TrackRecordingService.stop(getApplication<android.app.Application>())
    }

    private fun onUserLocation(loc: Location) {
        lastUserLocation = loc
        trackRecorder.onLocation(loc)
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

    val headerUtm: String get() {
        val (lat, lng) = headerCoordinate
        return MgrsFormatter.formatUtm(lat, lng)
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
