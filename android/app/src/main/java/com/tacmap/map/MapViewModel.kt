package com.tacmap.map

import com.tacmap.localization.L10n

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
import com.tacmap.models.HeadingService
import com.tacmap.models.TrackRecordingService
import com.tacmap.map.render.MapCamera
import com.tacmap.settings.MapOrientationMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

// Online basemap choice is just BasemapStyle now (Esri Satellite/Topo, OSM
// Topo/Street). The old native-Google-satellite BaseMap enum is gone.

data class MapSelectionPersistenceIssue(
    val id: Long,
    val message: String,
)

private sealed interface PendingMapSelectionTransition {
    data class Online(
        val source: OnlineRasterMapSourceAndroid,
        val style: BasemapStyle,
    ) : PendingMapSelectionTransition

    data class Pdf(
        val source: PdfMapSource,
        val preferredOnlineStyle: BasemapStyle,
    ) : PendingMapSelectionTransition

    data class PersistedPdf(
        val source: PdfMapSource,
        val preferredOnlineStyle: BasemapStyle,
    ) : PendingMapSelectionTransition

    data class Offline(
        val source: OfflineTileMapSourceAndroid,
        val path: String,
        val preferredOnlineStyle: BasemapStyle,
    ) : PendingMapSelectionTransition

    data class UnloadImported(
        val onlineSource: MapSource,
        val style: BasemapStyle,
        val clearRetained: Boolean,
    ) : PendingMapSelectionTransition

    data object ClearPdfSession : PendingMapSelectionTransition
}

private fun PendingMapSelectionTransition.offlineSourceOrNull(): OfflineTileMapSourceAndroid? =
    (this as? PendingMapSelectionTransition.Offline)?.source

internal fun closeSupersededOfflineSources(
    candidates: List<MapSource?>,
    stillReferenced: List<MapSource?>,
) {
    val closed = java.util.Collections.newSetFromMap(
        java.util.IdentityHashMap<OfflineTileMapSourceAndroid, Boolean>()
    )
    candidates.filterIsInstance<OfflineTileMapSourceAndroid>().forEach { source ->
        if (stillReferenced.none { it === source } && closed.add(source)) source.close()
    }
}

/**
 * Map camera, browse-mode toggle, MGRS header readout.
 *
 * "Browse mode" just means the user panned away from their location.
 * Cleared by centreOnUser. Coords are bare (lat, lng, zoom) so we
 * don't depend on any particular map SDK.
 */
class MapViewModel(app: Application) : AndroidViewModel(app) {

    val locationService = LocationService(app)
    val headingService = HeadingService(app) { locationService.lastLocation.value }

    /** App-scoped OPSEC/privacy settings (screen-capture, online lookups, relay). */
    val opsec = (app as com.tacmap.app.TacticalApp).opsec

    val trackRecorder = (app as com.tacmap.app.TacticalApp).trackRecorder

    // Camera centre published by MapScreen on every camera-idle event.
    private val _cameraLat = MutableStateFlow(0.0)
    val cameraLat: StateFlow<Double> = _cameraLat.asStateFlow()
    private val _cameraLng = MutableStateFlow(0.0)
    val cameraLng: StateFlow<Double> = _cameraLng.asStateFlow()
    private val _cameraViewportState = MutableStateFlow<MapViewportState?>(null)
    val cameraViewportState: StateFlow<MapViewportState?> = _cameraViewportState.asStateFlow()

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
    private val canReconcileManagedMapsAtColdStart: Boolean =
        savedMapSelectionState is ActiveMapSelectionLoadState.Loaded &&
            activeMapSelectionStore.hasAuthenticatedCurrentSnapshot()
    private val savedMapSelection: ActiveMapSelection? =
        (savedMapSelectionState as? ActiveMapSelectionLoadState.Loaded)?.selection
    private val savedRetainedMapSelection: ActiveMapSelection? =
        (activeMapSelectionStore.loadRetainedImportedState() as? ActiveMapSelectionLoadState.Loaded)
            ?.selection

    /** Default basemap: Esri Satellite when we have a key, else the one style
     *  that needs none (OpenTopoMap) so a keyless dev build still shows a map. */
    private val defaultStyle: BasemapStyle =
        if (com.tacmap.calibration.EsriKey.isAvailable) BasemapStyle.ESRI_SATELLITE
        else BasemapStyle.OSM_TOPO

    private val _mapSource = MutableStateFlow<MapSource>(OnlineRasterMapSourceAndroid(defaultStyle))
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()
    private val _retainedImportedMapSource = MutableStateFlow<MapSource?>(null)
    /** Last imported map remains switchable after choosing an online basemap,
     * including after process death via an encrypted retained descriptor. */
    val retainedImportedMapSource: StateFlow<MapSource?> =
        _retainedImportedMapSource.asStateFlow()

    private val _mapSelectionPersistenceIssue = MutableStateFlow<MapSelectionPersistenceIssue?>(null)
    val mapSelectionPersistenceIssue: StateFlow<MapSelectionPersistenceIssue?> =
        _mapSelectionPersistenceIssue.asStateFlow()
    private var pendingMapSelectionTransition: PendingMapSelectionTransition? = null
    private var nextMapSelectionIssueId = 0L

    /** Which online basemap to return to when an imported map is unloaded. */
    private var preferredBaseMap: BasemapStyle =
        savedMapSelection?.preferredOnlineStyle
            ?.let { saved -> BasemapStyle.entries.firstOrNull { it.name == saved } }
            ?.takeIf { !it.requiresEsriKey || com.tacmap.calibration.EsriKey.isAvailable }
            ?: defaultStyle
    private fun baseMapSource(style: BasemapStyle): OnlineRasterMapSourceAndroid =
        OnlineRasterMapSourceAndroid(style)
    private fun onlineBasemap(): OnlineRasterMapSourceAndroid = baseMapSource(preferredBaseMap)

    private val mapSelectionCommitCoordinator =
        ActiveMapSelectionCommitCoordinator<MapSource>(activeMapSelectionStore) { publication ->
            val previousActive = _mapSource.value
            val previousRetained = _retainedImportedMapSource.value
            preferredBaseMap = publication.preferredOnlineStyle
            _mapSource.value = publication.active
            when (val retained = publication.retained) {
                RetainedMapPublication.Keep -> Unit
                RetainedMapPublication.Clear -> _retainedImportedMapSource.value = null
                is RetainedMapPublication.Set -> _retainedImportedMapSource.value = retained.source
            }
            closeSupersededOfflineSources(
                candidates = listOf(previousActive, previousRetained),
                stillReferenced = listOf(_mapSource.value, _retainedImportedMapSource.value),
            )
            frameCameraFor(publication.active)
        }

    /**
     * Switch online basemap. The PDF session remains in the private map
     * library; the active-source descriptor is the authority on what reopens.
     * Only an explicit "Unload PDF Map" deletes that restorable session.
     */
    fun selectBaseMap(style: BasemapStyle): Boolean = executeMapSelectionTransition(
        PendingMapSelectionTransition.Online(baseMapSource(style), style)
    )

    /** The style currently selected (for menu highlight). */
    val activeBaseMapStyle: BasemapStyle? get() = (_mapSource.value as? OnlineRasterMapSourceAndroid)?.style

    /** Return to the preferred online basemap (e.g. after unloading offline tiles). */
    fun restoreOnlineBasemap(): Boolean = executeMapSelectionTransition(
        PendingMapSelectionTransition.Online(onlineBasemap(), preferredBaseMap)
    )

    /** Set active map source + fly camera to a sensible starting position if
     *  it has coverage. Calibrated PDFs get persisted to pdfSessionStore. */
    fun setMapSource(source: MapSource): Boolean = activateAndPersist(source)

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

    fun unloadPdfMap(): Boolean {
        val clearRetained = _retainedImportedMapSource.value is PdfMapSource
        val succeeded = executeMapSelectionTransition(
            PendingMapSelectionTransition.UnloadImported(
                onlineSource = onlineBasemap(),
                style = preferredBaseMap,
                clearRetained = clearRetained,
            )
        )
        if (succeeded && clearRetained && !pdfSessionStore.clear()) {
            reportMapSelectionIssue(
                transition = PendingMapSelectionTransition.ClearPdfSession,
                message = L10n.text("The PDF map was unloaded, but its private session metadata could not be removed. Retry cleanup."),
            )
        }
        return succeeded
    }

    fun unloadOfflineTiles(): Boolean = executeMapSelectionTransition(
        PendingMapSelectionTransition.UnloadImported(
            onlineSource = onlineBasemap(),
            style = preferredBaseMap,
            clearRetained = _retainedImportedMapSource.value is OfflineTileMapSourceAndroid,
        )
    )

    fun restoreRetainedImportedMap(): Boolean {
        val retained = _retainedImportedMapSource.value ?: return false
        return when (retained) {
            is PdfMapSource -> executeMapSelectionTransition(
                PendingMapSelectionTransition.PersistedPdf(retained, preferredBaseMap)
            )
            is OfflineTileMapSourceAndroid -> executeMapSelectionTransition(
                PendingMapSelectionTransition.Offline(retained, retained.path, preferredBaseMap)
            )
            is OnlineRasterMapSourceAndroid -> selectBaseMap(retained.style)
        }
    }

    fun retryMapSelectionPersistence() {
        pendingMapSelectionTransition?.let(::executeMapSelectionTransition)
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
        restoreRetainedImportedSource()
        restoreActiveMapSource()
        if (canReconcileManagedMapsAtColdStart) {
            reconcileManagedImportedMapFiles()
        }
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
                val restored = restoreSelection(state.selection)
                if (restored != null) {
                    // This publication is already backed by the descriptor we
                    // just loaded; no second write is needed or useful.
                    _mapSource.value = restored
                    frameCameraFor(restored)
                } else {
                    activateAndPersist(onlineBasemap())
                }
            }
            ActiveMapSelectionLoadState.Missing -> {
                val legacyPdf = if (activeMapSelectionStore.legacyPdfMigrationPending()) {
                    pdfSessionStore.load()
                } else {
                    null
                }
                activateAndPersist(legacyPdf ?: onlineBasemap(), pdfSessionAlreadyPersisted = true)
            }
            is ActiveMapSelectionLoadState.Unavailable -> {
                // Never reinterpret a locked/corrupt descriptor as an absent
                // legacy selector. SafeStore preserved a corrupt recovery copy;
                // keep every imported backing file until the user explicitly
                // retries this reset or selects/imports a replacement map.
                if (state.reason == ActiveMapSelectionFailure.CORRUPT) {
                    reportMapSelectionIssue(
                        transition = PendingMapSelectionTransition.Online(
                            onlineBasemap(),
                            preferredBaseMap,
                        ),
                        message = L10n.text("The saved basemap details could not be authenticated. ") +
                            L10n.text("A recovery copy and all imported maps were preserved. ") +
                            L10n.text("Tap Retry to reset the saved choice to the online map, ") +
                            L10n.text("or import/select a replacement map."),
                    )
                }
            }
        }
    }

    private fun restoreRetainedImportedSource() {
        val selection = savedRetainedMapSelection ?: return
        if (selection.kind == ActiveMapKind.ONLINE) return
        _retainedImportedMapSource.value = restoreSelection(selection)
    }

    private fun restoreSelection(selection: ActiveMapSelection): MapSource? =
        when (selection.kind) {
            ActiveMapKind.ONLINE -> onlineBasemap()
            ActiveMapKind.PDF ->
                (_retainedImportedMapSource.value as? PdfMapSource) ?: pdfSessionStore.load()
            ActiveMapKind.OFFLINE_TILES -> {
                val path = activeMapSelectionStore.offlineFile(selection)?.path
                if (path == null) null else {
                    (_retainedImportedMapSource.value as? OfflineTileMapSourceAndroid)
                        ?.takeIf { it.path == path }
                        ?: OfflineTileMapSourceAndroid.open(path)
                }
            }
        }

    private fun activateAndPersist(
        source: MapSource,
        pdfSessionAlreadyPersisted: Boolean = false,
    ): Boolean = when (source) {
        is PdfMapSource -> executeMapSelectionTransition(
            if (pdfSessionAlreadyPersisted) {
                PendingMapSelectionTransition.PersistedPdf(source, preferredBaseMap)
            } else {
                PendingMapSelectionTransition.Pdf(source, preferredBaseMap)
            }
        )
        is OfflineTileMapSourceAndroid -> executeMapSelectionTransition(
            PendingMapSelectionTransition.Offline(source, source.path, preferredBaseMap)
        )
        is OnlineRasterMapSourceAndroid -> executeMapSelectionTransition(
            PendingMapSelectionTransition.Online(source, source.style)
        )
    }

    private fun executeMapSelectionTransition(
        transition: PendingMapSelectionTransition,
    ): Boolean {
        pendingMapSelectionTransition
            ?.takeIf { it !== transition }
            ?.offlineSourceOrNull()
            ?.let { abandoned ->
                closeSupersededOfflineSources(
                    candidates = listOf(abandoned),
                    stillReferenced = listOf(
                        _mapSource.value,
                        _retainedImportedMapSource.value,
                        transition.offlineSourceOrNull(),
                    ),
                )
            }
        if (transition == PendingMapSelectionTransition.ClearPdfSession) {
            return if (pdfSessionStore.clear()) {
                reconcileManagedImportedMapFiles()
                pendingMapSelectionTransition = null
                _mapSelectionPersistenceIssue.value = null
                true
            } else {
                reportMapSelectionIssue(
                    transition,
                    L10n.text("PDF session cleanup still could not be saved. Check device storage and retry."),
                )
                false
            }
        }

        val outcome = when (transition) {
            is PendingMapSelectionTransition.Online ->
                mapSelectionCommitCoordinator.selectOnline(transition.source, transition.style)
            is PendingMapSelectionTransition.Pdf -> {
                val snapshot = pdfSessionStore.snapshotActiveSession()
                mapSelectionCommitCoordinator.activatePdf(
                    source = transition.source,
                    preferredOnlineStyle = transition.preferredOnlineStyle,
                    persistPdfSession = { pdfSessionStore.save(transition.source) },
                    rollbackPdfSession = { pdfSessionStore.restoreActiveSession(snapshot) },
                )
            }
            is PendingMapSelectionTransition.PersistedPdf ->
                mapSelectionCommitCoordinator.activatePersistedPdf(
                    transition.source,
                    transition.preferredOnlineStyle,
                )
            is PendingMapSelectionTransition.Offline ->
                mapSelectionCommitCoordinator.activateOffline(
                    transition.source,
                    transition.path,
                    transition.preferredOnlineStyle,
                )
            is PendingMapSelectionTransition.UnloadImported ->
                mapSelectionCommitCoordinator.unloadImportedMap(
                    transition.onlineSource,
                    transition.style,
                    transition.clearRetained,
                )
            PendingMapSelectionTransition.ClearPdfSession -> error("Handled above")
        }

        return when (outcome) {
            MapSelectionCommitResult.Succeeded -> {
                reconcileManagedImportedMapFiles()
                pendingMapSelectionTransition = null
                _mapSelectionPersistenceIssue.value = null
                true
            }
            is MapSelectionCommitResult.Failed -> {
                val message = when (outcome.reason) {
                    MapSelectionCommitFailure.PDF_SESSION ->
                        L10n.text("The PDF map could not be saved for relaunch. The previous map remains active. Check device storage and retry.")
                    MapSelectionCommitFailure.SELECTOR ->
                        L10n.text("The basemap choice could not be saved. The previous map remains active. Check device storage and retry.")
                    MapSelectionCommitFailure.PDF_SESSION_ROLLBACK ->
                        L10n.text("Map storage recovery did not complete. Keep the app open and retry before closing it.")
                }
                reportMapSelectionIssue(transition, message)
                false
            }
        }
    }

    private fun reconcileManagedImportedMapFiles(): Boolean =
        activeMapSelectionStore.reconcileManagedImportedMapFiles(
            currentPdfFile = {
                pdfSessionStore.load()?.uri?.path?.let { path -> java.io.File(path) }
            },
            clearPdfSession = pdfSessionStore::clear,
        )

    private fun reportMapSelectionIssue(
        transition: PendingMapSelectionTransition,
        message: String,
    ) {
        pendingMapSelectionTransition = transition
        _mapSelectionPersistenceIssue.value = MapSelectionPersistenceIssue(
            id = ++nextMapSelectionIssueId,
            message = message,
        )
    }

    /** Debounced 400ms, de-duped to ~11m so jitter doesn't spam the network.
     *  collectLatest cancels in-flight fetches when centre moves again. */
    /** Round coord to ~110m so lookups don't disclose exact map centre. */
    private fun coarsen(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    private fun observeElevation() {
        viewModelScope.launch {
            combine(_cameraLat, _cameraLng, opsec.onlineLookups) { lat, lng, enabled ->
                Triple(lat, lng, enabled)
            }
                .filter { (lat, lng, _) -> lat != 0.0 || lng != 0.0 }
                .distinctUntilChanged { old, new ->
                    old.third == new.third &&
                        abs(old.first - new.first) < 0.0001 &&
                        abs(old.second - new.second) < 0.0001
                }
                .collectLatest { (lat, lng, enabled) ->
                    // OPSEC: elevation lookups send coords to Open-Meteo. Only
                    // do it if user opted in, and coarsen to ~110m so exact
                    // map centre isn't disclosed. OFF is handled before the
                    // debounce so it immediately cancels any in-flight request.
                    if (!enabled) {
                        _centreElevation.value = null
                    } else {
                        delay(400)
                        _centreElevation.value = elevationService.reading(coarsen(lat), coarsen(lng))
                    }
                }
        }
    }

    /** Called on every camera idle. byUser distinguishes gestures from
     *  programmatic moves. */
    fun onCameraIdle(camera: MapCamera, byUser: Boolean) {
        val viewport = MapViewportState.from(camera)
        if (!viewport.isUsable()) return
        _cameraLat.value = viewport.latitude
        _cameraLng.value = viewport.longitude
        _cameraViewportState.value = viewport
        if (byUser) _isBrowsing.value = true
    }

    fun onMapBearingChanged(degrees: Double) {
        val normalized = ((degrees % 360.0) + 360.0) % 360.0
        if (abs(_mapBearingDegrees.value - normalized) > 0.05) {
            _mapBearingDegrees.value = normalized
        }
        _cameraViewportState.value = _cameraViewportState.value?.copy(
            bearingDegrees = normalized,
        )
    }

    fun consumePendingCameraTarget() { _pendingCameraTarget.value = null }

    /** Compass HUD tapped - animate bearing back to 0 (north up).
     *  Channel w/ BUFFERED capacity so rapid taps don't drop. */
    private val _resetNorthRequests = Channel<Unit>(Channel.BUFFERED)
    val resetNorthRequests: Flow<Unit> = _resetNorthRequests.receiveAsFlow()
    fun requestResetNorth() { _resetNorthRequests.trySend(Unit) }

    internal fun onCompassTapped(): CompassTapAction {
        val action = compassTapAction(
            mode = opsec.mapOrientationMode.value,
            currentHeading = _mapBearingDegrees.value,
            headingAvailable = headingService.isHeadingAvailable,
        )
        when (action) {
            CompassTapAction.RESET_NORTH -> requestResetNorth()
            CompassTapAction.ENABLE_HEADING_UP ->
                opsec.setMapOrientationMode(MapOrientationMode.HEADING_UP)
            CompassTapAction.DISABLE_HEADING_UP ->
                opsec.setMapOrientationMode(MapOrientationMode.NORTH_UP)
            CompassTapAction.HEADING_UNAVAILABLE -> Unit
        }
        return action
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
        rememberProgrammaticCamera(loc.latitude, loc.longitude, 15.0)
        _pendingCameraTarget.value = Triple(loc.latitude, loc.longitude, 15f)
        // Preserve the phone's live orientation while recentering in Heading Up.
        if (opsec.mapOrientationMode.value == MapOrientationMode.NORTH_UP) {
            requestResetNorth()
        }
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
        rememberProgrammaticCamera(lat, lng, zoom.toDouble())
        _pendingCameraTarget.value = Triple(lat, lng, zoom)
    }

    private fun rememberProgrammaticCamera(lat: Double, lng: Double, zoom: Double) {
        val viewport = MapViewportState(
            latitude = lat,
            longitude = lng,
            zoom = zoom,
            bearingDegrees = _mapBearingDegrees.value,
        )
        if (!viewport.isUsable()) return
        _cameraLat.value = lat
        _cameraLng.value = lng
        _cameraViewportState.value = viewport
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
        headingService.stop()
        locationService.stop()
        closeSupersededOfflineSources(
            candidates = listOf(
                _mapSource.value,
                _retainedImportedMapSource.value,
                pendingMapSelectionTransition?.offlineSourceOrNull(),
            ),
            stillReferenced = emptyList(),
        )
        super.onCleared()
    }
}
