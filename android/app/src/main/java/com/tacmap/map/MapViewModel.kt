package com.tacmap.map

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tacmap.calibration.ActiveMapKind
import com.tacmap.calibration.ActiveMapSelection
import com.tacmap.calibration.ActiveMapSelectionFailure
import com.tacmap.calibration.ActiveMapSelectionLoadState
import com.tacmap.calibration.ActiveMapSelectionStore
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
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

// Online basemap choice is just BasemapStyle now (Esri Satellite/Topo, OSM
// Topo/Street). The old native-Google-satellite BaseMap enum is gone.

/**
 * Map camera, browse-mode toggle, MGRS header readout.
 *
 * "Browse mode" just means the user panned away from their location.
 * Cleared by centreOnUser. Coords are bare (lat, lng, zoom) so we
 * don't depend on any particular map SDK.
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

    /** Live elevation for map centre (metres MSL), fetched from Open-Meteo DEM.
     *  Debounced so we only hit the network when panning stops. null until
     *  first reading comes back. */
    private val _centreElevation = MutableStateFlow<ElevationReading?>(null)
    val centreElevation: StateFlow<ElevationReading?> = _centreElevation.asStateFlow()
    private val elevationService = ElevationService()

    private val pdfSessionStore = PdfSessionStore(app)
    private val activeMapSelectionStore = ActiveMapSelectionStore(app)
    private val savedMapSelectionState: ActiveMapSelectionLoadState =
        activeMapSelectionStore.loadState()
    private val savedMapSelection: ActiveMapSelection? =
        (savedMapSelectionState as? ActiveMapSelectionLoadState.Loaded)?.selection

    /** Default basemap: Esri Satellite when we have a key, else the one style
     *  that needs none (OpenTopoMap) so a keyless dev build still shows a map. */
    private val defaultStyle: BasemapStyle =
        if (com.tacmap.calibration.EsriKey.isAvailable) BasemapStyle.ESRI_SATELLITE
        else BasemapStyle.OSM_TOPO

    private val _mapSource = MutableStateFlow<MapSource>(OnlineRasterMapSourceAndroid(defaultStyle))
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    /** Which online basemap to return to when an imported map is unloaded. */
    private var preferredBaseMap: BasemapStyle =
        savedMapSelection?.preferredOnlineStyle
            ?.let { saved -> BasemapStyle.entries.firstOrNull { it.name == saved } }
            ?.takeIf { !it.requiresEsriKey || com.tacmap.calibration.EsriKey.isAvailable }
            ?: defaultStyle
    private fun baseMapSource(style: BasemapStyle): MapSource = OnlineRasterMapSourceAndroid(style)
    private fun onlineBasemap(): MapSource = baseMapSource(preferredBaseMap)

    /**
     * Switch online basemap. The PDF session remains in the private map
     * library; the active-source descriptor is the authority on what reopens.
     * Only an explicit "Unload PDF Map" deletes that restorable session.
     */
    fun selectBaseMap(style: BasemapStyle) {
        preferredBaseMap = style
        _mapSource.value = baseMapSource(style)
        activeMapSelectionStore.saveOnline(style)
    }

    /** The style currently selected (for menu highlight). */
    val activeBaseMapStyle: BasemapStyle? get() = (_mapSource.value as? OnlineRasterMapSourceAndroid)?.style

    /** Return to the preferred online basemap (e.g. after unloading offline tiles). */
    fun restoreOnlineBasemap() {
        _mapSource.value = onlineBasemap()
        activeMapSelectionStore.saveOnline(preferredBaseMap)
    }

    /** Set active map source + fly camera to a sensible starting position if
     *  it has coverage. Calibrated PDFs get persisted to pdfSessionStore. */
    fun setMapSource(source: MapSource) {
        _mapSource.value = source
        when (source) {
            is PdfMapSource -> {
                if (pdfSessionStore.save(source)) {
                    activeMapSelectionStore.savePdf(preferredBaseMap)
                }
            }
            is OfflineTileMapSourceAndroid ->
                activeMapSelectionStore.saveOffline(source.path, preferredBaseMap)
            is OnlineRasterMapSourceAndroid -> {
                preferredBaseMap = source.style
                activeMapSelectionStore.saveOnline(source.style)
            }
        }
        frameCameraFor(source)
    }

    /** Frame camera for a new or restored map source. If user's last fix
     *  is inside the coverage, centre on them. Otherwise centre on the
     *  coverage centre so they at least see the page. No-op for unbounded
     *  sources like OSM. */
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
        activeMapSelectionStore.saveOnline(preferredBaseMap)
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
        restoreActiveMapSource()
        viewModelScope.launch {
            locationService.lastLocation.collect { loc -> loc?.let(::onUserLocation) }
        }
        observeElevation()
    }

    /**
     * Restore exactly the source that was active at shutdown. A genuinely
     * absent selector gets one legacy migration matching the old app's
     * behaviour: restore its persisted PDF session if one exists. Corrupt and
     * locked selectors never enter that migration path.
     *
     * This runs after [_pendingCameraTarget] is initialised because restoring a
     * bounded source frames its coverage immediately.
     */
    private fun restoreActiveMapSource() {
        when (val state = savedMapSelectionState) {
            is ActiveMapSelectionLoadState.Loaded -> {
                val restored = restoreSelection(state.selection) ?: onlineBasemap()
                activateAndPersist(restored)
            }
            ActiveMapSelectionLoadState.Missing -> {
                val legacyPdf = if (activeMapSelectionStore.legacyPdfMigrationPending()) {
                    pdfSessionStore.load()
                } else {
                    null
                }
                activateAndPersist(legacyPdf ?: onlineBasemap())
            }
            is ActiveMapSelectionLoadState.Unavailable -> {
                // Never reinterpret a locked/corrupt descriptor as an absent
                // legacy selector. A corrupt descriptor has already been
                // quarantined by SafeStore, so establish a clean online choice;
                // locked data must remain untouched until authentication.
                _mapSource.value = onlineBasemap()
                if (state.reason == ActiveMapSelectionFailure.CORRUPT) {
                    activeMapSelectionStore.saveOnline(preferredBaseMap)
                }
            }
        }
    }

    private fun restoreSelection(selection: ActiveMapSelection): MapSource? =
        when (selection.kind) {
            ActiveMapKind.ONLINE -> onlineBasemap()
            ActiveMapKind.PDF -> pdfSessionStore.load()
            ActiveMapKind.OFFLINE_TILES -> activeMapSelectionStore
                .offlineFile(selection)
                ?.path
                ?.let(OfflineTileMapSourceAndroid::open)
        }

    private fun activateAndPersist(source: MapSource) {
        _mapSource.value = source
        frameCameraFor(source)
        when (source) {
            is PdfMapSource -> activeMapSelectionStore.savePdf(preferredBaseMap)
            is OfflineTileMapSourceAndroid ->
                activeMapSelectionStore.saveOffline(source.path, preferredBaseMap)
            is OnlineRasterMapSourceAndroid ->
                activeMapSelectionStore.saveOnline(source.style)
        }
    }

    /** Debounced 400ms, de-duped to ~11m so jitter doesn't spam the network.
     *  collectLatest cancels in-flight fetches when centre moves again. */
    /** Round coord to ~110m so lookups don't disclose exact map centre. */
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
                    // OPSEC: elevation lookups send coords to Open-Meteo. Only
                    // do it if user opted in, and coarsen to ~110m so exact
                    // map centre isn't disclosed.
                    if (!opsec.onlineLookups.value) {
                        _centreElevation.value = null
                    } else {
                        _centreElevation.value = elevationService.reading(coarsen(lat), coarsen(lng))
                    }
                }
        }
    }

    /** Called on every camera idle. byUser distinguishes gestures from
     *  programmatic moves. */
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

    /** Compass HUD tapped - animate bearing back to 0 (north up).
     *  Channel w/ BUFFERED capacity so rapid taps don't drop. */
    private val _resetNorthRequests = Channel<Unit>(Channel.BUFFERED)
    val resetNorthRequests: Flow<Unit> = _resetNorthRequests.receiveAsFlow()
    fun requestResetNorth() { _resetNorthRequests.trySend(Unit) }

    /** Start GPX recording + foreground service so track keeps logging
     *  while app is backgrounded or screen locked. */
    fun startTrackRecording() {
        if (!trackRecorder.start()) return
        runCatching { TrackRecordingService.start(getApplication<android.app.Application>()) }
            .onFailure { trackRecorder.failRecording("Could not start background recording: ${it.message}") }
    }

    /** Stop recording, tear down the foreground service. */
    fun stopTrackRecording() {
        trackRecorder.stop()
        TrackRecordingService.stop(getApplication<android.app.Application>())
    }

    /** Permanently remove a saved track after the UI has obtained destructive
     *  confirmation. Stopping the service first makes this safe even when the
     *  confirmation was opened while recording was active. */
    fun discardTrackRecording(): Boolean {
        stopTrackRecording()
        return trackRecorder.discard()
    }

    private fun onUserLocation(loc: Location) {
        lastUserLocation = loc
        trackRecorder.onLocation(loc)
        if (!hasInitialFix) {
            hasInitialFix = true
            // Centre on user on first fix, unless a bounded map (PDF) is
            // active and user is off it - keep the import framing so the
            // PDF stays visible.
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
        // Re-orient north when recentering, so the map is always readable
        // north-up after a "Centre on My Location".
        requestResetNorth()
    }

    /** Re-frame the loaded offline/imported map's coverage. Paired with
     *  centreOnUser so that after panning off (or centring on a distant live
     *  location) the user can jump straight back to where the map actually is.
     *  No-op for unbounded online basemaps. */
    fun centreOnMap() {
        val coverage = _mapSource.value.coverage ?: return
        val c = coverage.center
        flyTo(c.latitude, c.longitude, 13f)
    }

    /** Fly camera to arbitrary coord. Used by waypoint list's "fly to"
     *  rows. Enters browse mode so header shows map centre not user. */
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
