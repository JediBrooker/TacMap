package com.tacmap.map

import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import android.app.Application
import android.location.Location
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tacmap.calibration.ActiveMapSelectionStore
import com.tacmap.calibration.ActiveRef
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.CalibrationDraftStore
import com.tacmap.calibration.EntryFileStatus
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.ImportedMapLibraryStore
import com.tacmap.calibration.InFlightImportFiles
import com.tacmap.calibration.LegacyMapReader
import com.tacmap.calibration.LibraryCommit
import com.tacmap.calibration.LibraryEntryRules
import com.tacmap.calibration.LibraryLoad
import com.tacmap.calibration.LibraryReducer
import com.tacmap.calibration.LibraryReduction
import com.tacmap.calibration.LibraryState
import com.tacmap.calibration.LibraryTransition
import com.tacmap.calibration.ManagedImportedMapFileLifecycle
import com.tacmap.calibration.ManualCalibration
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfCalibrationIdentity
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.PdfGeoreferenceCodec
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageGeometry
import com.tacmap.calibration.PdfBox
import com.tacmap.calibration.PdfSessionStore
import com.tacmap.calibration.PdfBakeAttach
import com.tacmap.calibration.PdfBakeRecorder
import com.tacmap.calibration.PersistedPdfBake
import com.tacmap.calibration.LibraryMapFiles
import com.tacmap.calibration.QuietLog
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.fiducial.CalibrationTarget
import com.tacmap.calibration.fiducial.CalibrationDraft
import com.tacmap.calibration.AutoResumeAction
import com.tacmap.calibration.AutoResumeEntry
import com.tacmap.calibration.AutoResumeRules
import com.tacmap.calibration.BackgroundHashRules
import com.tacmap.calibration.LegacyLibraryMigrator
import com.tacmap.calibration.LibraryRebuild
import com.tacmap.calibration.MismatchAction
import com.tacmap.calibration.RestoreNotice
import com.tacmap.calibration.RestoreStatus
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.models.LocationService
import com.tacmap.models.HeadingService
import com.tacmap.models.TrackRecordingService
import com.tacmap.map.render.MapCamera
import com.tacmap.settings.MapOrientationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// Online basemap choice is just BasemapStyle now (Esri Satellite/Topo, OSM
// Topo/Street). The old native-Google-satellite BaseMap enum is gone.

data class MapSelectionPersistenceIssue(
    val id: Long,
    /** resolved when shown, so a language change in between still reads right */
    private val text: () -> String,
) {
    val message: String get() = text()
}

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

/** how often the open guard looks at a pack's first draw window, well under firstDrawQuietMs */
private const val FIRST_DRAW_POLL_MS = 100L

/**
 * the MBTiles admission a restore or activation runs on IO (s14.2). a seam so a test can see
 * which thread runs it and hold it up
 */
@androidx.annotation.VisibleForTesting
@Volatile
internal var admitMbtilesPack: (path: String, displayName: String) -> OfflineTileMapSourceAndroid? =
    { path, name -> OfflineTileMapSourceAndroid.open(path, name) }

/** how an MBTiles open ends up on screen (s14.2) */
private sealed interface PackOpen {
    /** launch, unlock, Retry, an import, another screen's write: blank till it's checked */
    data class Restore(val frameActive: Boolean, val reframe: Boolean) : PackOpen
    /** Open Anyway on the held back pack, durable already: the online map stays meanwhile */
    data object Reopen : PackOpen
    /** Layers: the map that's up stays till it's checked, then the one write */
    data class Activate(val frame: Boolean) : PackOpen
}

/** where the imported-map library is at, Layers + the import pre-check read it */
internal enum class LibraryStatus { LOADING, LOADED, LOCKED, CORRUPT }

/** one-off notices at launch / after an import that need a dialog, not a toast */
internal sealed class MapLaunchAlert {
    data class MigrationUncalibrated(val name: String) : MapLaunchAlert()
    /** a salvage or an orphan adoption kept every map file, some names or calibrations may be gone (s13.1 L10) */
    data object LibraryRecovered : MapLaunchAlert()
    data object ImportInterrupted : MapLaunchAlert()
    data object ActiveFileChanged : MapLaunchAlert()
}

/**
 * What a crash guard held back at launch and is asking about: a PDF, or an MBTiles pack
 * that never got opened (s14.1). The alert only needs the name
 */
sealed interface CrashSuspect {
    val entryId: String?
    val displayName: String

    data class Pdf(val source: PdfMapSource) : CrashSuspect {
        override val entryId: String? get() = source.entryId
        override val displayName: String get() = source.displayName
    }

    data class Mbtiles(override val entryId: String, override val displayName: String) : CrashSuspect
}

/**
 * Map camera, browse-mode toggle, MGRS header readout, and the imported-map
 * library (WP4/WP5 contract s8.2): ONE sealed authority for the active map and
 * every imported map, each transition one write then publish. The calibration
 * session lives here too since MapScreen is torn down on every pause.
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

    private val filesDir: File = app.filesDir
    private val library = ImportedMapLibraryStore(filesDir)
    private val draftStore = CalibrationDraftStore(filesDir)
    private val legacyReader = LegacyMapReader(app, ActiveMapSelectionStore(app), PdfSessionStore(app))

    /** the calibration session (contract s2), VM-owned so a pause doesn't drop it */
    internal val calibration = CalibrationController(draftStore)

    /** the tile view's memory cache. lives here so a rotation keeps rendered tiles */
    val tileCache: com.tacmap.map.render.TileBitmapCache = com.tacmap.map.render.TileBitmapCache.forDevice(app)

    private val tacticalApp = app as com.tacmap.app.TacticalApp
    private val pdfRenderGuard = tacticalApp.pdfRenderGuard

    /**
     * the live PDF tile source + render status, here so a rotation keeps the open page. the
     * guard token is the library entry's and already sealed with it, nothing to persist late
     */
    val pdfRuntime = PdfMapRuntime(app, viewModelScope, pdfRenderGuard) { true }

    /** Generate Offline Tiles, app scoped */
    val bakeManager: com.tacmap.calibration.PdfBakeManager = tacticalApp.pdfBakeManager

    /** the MBTiles open guard (s14.1), app scoped like the PDF one */
    private val mbtilesGuard = tacticalApp.mbtilesOpenGuard

    /** the pack publication the open guard waits on to draw, and the job that watches it */
    private var firstDraw: Pair<String, OfflineTileMapSourceAndroid>? = null
    private var firstDrawJob: kotlinx.coroutines.Job? = null

    private val _pdfRecovery = MutableStateFlow<CrashSuspect?>(null)
    /** the PDF or pack a crash guard held back this launch, waiting on Open Anyway / Delete Map / Not Now */
    val pdfRecovery: StateFlow<CrashSuspect?> = _pdfRecovery.asStateFlow()

    private val _pdfLaunchNotices = MutableStateFlow<List<com.tacmap.map.render.pdf.PdfLaunchNotice>>(emptyList())
    /** import interrupted and/or bake interrupted from the last run, each shown once, in order */
    val pdfLaunchNotices: StateFlow<List<com.tacmap.map.render.pdf.PdfLaunchNotice>> = _pdfLaunchNotices.asStateFlow()

    private val trimListener: (Int) -> Unit = { level ->
        viewModelScope.launch(Dispatchers.Main) { onTrimMemory(level) }
    }

    /**
     * The crash guard's launch step (M10), taken lazily at the first Loaded restore like iOS
     * (F3). A locked or corrupt library at launch has no token yet, and running the step then
     * would clear an in-progress marker without ever naming the suspect. The tokens are the
     * active PDF's and the entry an auto-resume would preview (C8); an interrupted import or
     * bake just leaves a notice. null = not taken yet
     */
    private var pdfLaunchDecision: com.tacmap.map.render.pdf.GuardLaunchDecision? = null

    /** the pending import that took the app down in its probe last run, replaying it would just loop */
    val interruptedImportOperationKey: String?
        get() = pdfLaunchDecision?.takeIf { it.importInterrupted }?.operationKey

    /** the bake publish writes the record through here, on main like every library write (M3) */
    private val bakeRecorder = object : PdfBakeRecorder {
        override fun attach(entryId: String?, contentKey: String?, renderGuardToken: String, bake: PersistedPdfBake): PdfBakeAttach =
            attachBake(entryId, contentKey, renderGuardToken, bake)

        override fun detach(entryId: String?, bake: PersistedPdfBake): Boolean = detachBake(entryId, bake)
    }

    /** set in onCleared: a bake or coroutine that outlives this view model never writes the library through it */
    @Volatile private var cleared = false

    /** Default basemap: Esri Satellite when we have a key, else the one style
     *  that needs none (OpenTopoMap) so a keyless dev build still shows a map. */
    private val defaultStyle: BasemapStyle =
        if (com.tacmap.calibration.EsriKey.isAvailable) BasemapStyle.ESRI_SATELLITE
        else BasemapStyle.OSM_TOPO

    /** the old stores' migration, a salvage or an orphan adoption, the S2 rebuild's rules */
    private val migrator = LegacyLibraryMigrator(
        filesDir = filesDir,
        library = library,
        drafts = draftStore,
        legacy = legacyReader,
        defaultStyle = defaultStyle.name,
        recoveredName = ::recoveredName,
        inspectPdf = { f -> inspectForRecovery(f) },
        validateMbtiles = ::opensAsMbtiles,
        interruptedImports = {
            runCatching { MapImportPipeline.interruptedFiles(DocumentImportCopyJournal(app)) }.getOrDefault(emptySet())
        },
    )

    private val _mapSource = MutableStateFlow<MapSource>(OnlineRasterMapSourceAndroid(defaultStyle))
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    private val _libraryState = MutableStateFlow<LibraryState?>(null)
    internal val libraryState: StateFlow<LibraryState?> = _libraryState.asStateFlow()
    private val _libraryStatus = MutableStateFlow(LibraryStatus.LOADING)
    internal val libraryStatus: StateFlow<LibraryStatus> = _libraryStatus.asStateFlow()

    /** entries whose bytes didn't hash to their contentKey this launch (s8.2 restore) */
    private val _hashMismatch = MutableStateFlow<Set<String>>(emptySet())
    internal val hashMismatch: StateFlow<Set<String>> = _hashMismatch.asStateFlow()

    /** contentKey#page -> draft point count, Layers subtitles. refreshed after writes */
    private val _draftCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    internal val draftCounts: StateFlow<Map<String, Int>> = _draftCounts.asStateFlow()

    private val _launchAlert = MutableStateFlow<MapLaunchAlert?>(null)
    internal val launchAlert: StateFlow<MapLaunchAlert?> = _launchAlert.asStateFlow()
    internal fun dismissLaunchAlert() { _launchAlert.value = null }
    internal fun showLaunchAlert(alert: MapLaunchAlert) { _launchAlert.value = alert }

    private val _mapSelectionPersistenceIssue = MutableStateFlow<MapSelectionPersistenceIssue?>(null)
    val mapSelectionPersistenceIssue: StateFlow<MapSelectionPersistenceIssue?> =
        _mapSelectionPersistenceIssue.asStateFlow()
    private var pendingRetry: (() -> Boolean)? = null
    private var nextMapSelectionIssueId = 0L

    /** Which online basemap to return to when an imported map is unloaded. */
    private var preferredBaseMap: BasemapStyle = defaultStyle
    private fun baseMapSource(style: BasemapStyle): OnlineRasterMapSourceAndroid =
        OnlineRasterMapSourceAndroid(style)
    private fun onlineBasemap(): OnlineRasterMapSourceAndroid = baseMapSource(preferredBaseMap)

    /** open MBTiles handles by entry, closed when the entry goes */
    private val offlineSources = HashMap<String, OfflineTileMapSourceAndroid>()

    /** the MBTiles open running off main, if any, and what's newest (s14.2) */
    private val mbtilesOpens = MbtilesOpenRequests()

    /** while a no-georef PDF is shown for calibration: the durable map to go back to */
    private var previewReturn: MapSource? = null

    // ------------------------------------------------------------------ import UI state (s9)

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    /** the running map import's stage, the card shows it after 300 ms. VM so it outlives recomposition */
    internal val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()
    internal fun setImportProgress(p: ImportProgress?) { _importProgress.value = p }

    private val _pagePicker = MutableStateFlow<PreparedPdfImport?>(null)
    /** a multi-page PDF with no usable georef waiting for its page; the copy stays in flight */
    internal val pagePicker: StateFlow<PreparedPdfImport?> = _pagePicker.asStateFlow()
    internal fun showPagePicker(prepared: PreparedPdfImport) { _pagePicker.value = prepared }

    /** cancel deletes the copy and adds nothing (s9.6) */
    internal fun dismissPagePicker(keepFile: Boolean) {
        val p = _pagePicker.value ?: return
        _pagePicker.value = null
        // Choose page on a library entry: that file is the entry, it stays no matter what
        if (!keepFile && p.existingEntryId == null) {
            runCatching { p.file.delete() }
            InFlightImportFiles.release(p.file)
        }
    }

    internal data class RejectedPrompt(val entryId: String, val reason: com.tacmap.calibration.GeorefRejectReason)

    private val _rejectedPrompt = MutableStateFlow<RejectedPrompt?>(null)
    internal val rejectedPrompt: StateFlow<RejectedPrompt?> = _rejectedPrompt.asStateFlow()
    internal fun showRejectedPrompt(entryId: String, reason: com.tacmap.calibration.GeorefRejectReason) {
        _rejectedPrompt.value = RejectedPrompt(entryId, reason)
    }
    internal fun dismissRejectedPrompt() { _rejectedPrompt.value = null }

    /** zoom +/- buttons and fly-to-point while calibrating, CustomMapScreen owns the camera */
    private val _zoomStepRequests = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val zoomStepRequests: SharedFlow<Int> = _zoomStepRequests.asSharedFlow()
    fun requestZoomStep(delta: Int) { _zoomStepRequests.tryEmit(delta) }

    private val _centreRequests = MutableSharedFlow<Pair<Double, Double>>(extraBufferCapacity = 8)
    val centreRequests: SharedFlow<Pair<Double, Double>> = _centreRequests.asSharedFlow()
    fun requestCentre(lat: Double, lon: Double) { _centreRequests.tryEmit(lat to lon) }

    /** Switch online basemap. Imported maps stay in the library. */
    fun selectBaseMap(style: BasemapStyle): Boolean {
        val state = writableState() ?: return false
        val next = reduce(LibraryTransition.SelectOnline(style.name), state) ?: return false
        return transition(next, retry = { selectBaseMap(style) }) {
            preferredBaseMap = style
            previewReturn = null
            publish(baseMapSource(style), frame = false)
        }
    }

    /** The style currently selected (for menu highlight). */
    val activeBaseMapStyle: BasemapStyle? get() = (_mapSource.value as? OnlineRasterMapSourceAndroid)?.style

    /** Frame camera for a new or restored map source. If user's last fix
     *  is inside the coverage, centre on them. Otherwise fit the whole
     *  coverage so they see the entire sheet, as iOS does. No-op for
     *  unbounded sources like OSM. */
    private fun frameCameraFor(source: MapSource) {
        val coverage = source.coverage ?: return
        val userLoc = lastUserLocation
        if (userLoc != null && coverage.contains(userLoc.latitude, userLoc.longitude) &&
            !(source is PdfMapSource && source.isPreview)
        ) {
            flyTo(userLoc.latitude, userLoc.longitude, 15f)
        } else {
            fitCoverage(coverage)
        }
    }

    /** Fly to the zoom that shows all of [coverage]. Before the map has laid
     *  out, the screen size stands in for the viewport (the map is full-screen). */
    private fun fitCoverage(coverage: com.tacmap.calibration.Wgs84Bounds) {
        val (width, height) = lastViewportSize ?: getApplication<Application>().resources.displayMetrics
            .let { it.widthPixels / it.density.toDouble() to it.heightPixels / it.density.toDouble() }
        val fit = fitExtent(coverage, width, height)
        if (fit != null) {
            flyTo(fit.latitude, fit.longitude, fit.zoom.toFloat())
        } else {
            val centre = coverage.center
            flyTo(centre.latitude, centre.longitude, 13f)
        }
    }

    fun retryMapSelectionPersistence() {
        pendingRetry?.invoke()
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
    /** Last laid-out map viewport (dp), for fitting an imported map's extent. */
    private var lastViewportSize: Pair<Double, Double>? = null

    init {
        tacticalApp.memoryPressure += trimListener
        bakeManager.recorders.register(bakeRecorder)
        pdfRuntime.calibrating = { calibration.isActive }
        viewModelScope.launch {
            bakeManager.published.collect { onBakePublished(it) }
        }
        viewModelScope.launch {
            // a Try Again that got the right bytes back drew the map: the tampered mark goes (M8)
            pdfRuntime.status.collect { st ->
                if (st == com.tacmap.map.render.pdf.PdfRenderStatus.Ready) {
                    shownEntryId()?.let { id ->
                        if (id in _hashMismatch.value) _hashMismatch.value = _hashMismatch.value - id
                        // the session just matched the bytes to contentKey, so a new stamp is the right file (D7)
                        refreshFileStamp(id)
                    }
                }
            }
        }
        restoreLibrary()
        viewModelScope.launch {
            locationService.lastLocation.collect { loc -> loc?.let(::onUserLocation) }
        }
        observeElevation()
    }

    // ------------------------------------------------------------------ library: load, migrate, restore

    /** a restore pass (migration hop included) or a corrupt rebuild is running, don't start another */
    private var restoring = false
    private var rebuilding = false
    /** E3 runs once, after the first Loaded restore (launch, or the unlock / Retry that got there) */
    private var autoResumeTried = false
    /** what the locked / corrupt issue on screen is about, so a later restore can take it down */
    private var libraryIssueShown = false

    /**
     * One restore pass (s8.2 r1 + s13.1, F3): load, run the migration hop if it's due, then
     * adopt what was read or put up the locked / corrupt issue. Launch, the locked issue's Retry
     * and every mission-data unlock come through here
     */
    private fun restoreLibrary() {
        if (restoring || rebuilding) return
        val load = library.load()
        // Empty, or ledger only with old stores waiting (s14.3, that one only ever salvages)
        if (migrator.isDue(load)) {
            // old stores to migrate or map files to adopt: that hashes and parses, so off main.
            // the online map stands in meanwhile, unpersisted, and with the library still LOADING
            // nothing imports, reconciles or sweeps till the write lands
            restoring = true
            if (_mapSource.value !is OnlineRasterMapSourceAndroid) publish(onlineBasemap(), frame = false)
            viewModelScope.launch {
                val migrated = try {
                    withContext(Dispatchers.IO) { migrator.migrate(load) }
                } finally {
                    restoring = false
                }
                applyRestore(library.load(), migrated)
            }
            return
        }
        applyRestore(load, null)
    }

    private fun applyRestore(load: LibraryLoad, migrated: LegacyLibraryMigrator.Outcome?) {
        // D8 happens in settle when the plan says so
        val plan = migrator.settle(load, migrated)
        when (plan.status) {
            RestoreStatus.LOADED, RestoreStatus.EMPTY -> {
                val state = (load as? LibraryLoad.Loaded)?.state
                    ?: LibraryState(active = ActiveRef.online(defaultStyle.name), preferredOnlineStyle = defaultStyle.name)
                val written = migrated as? LegacyLibraryMigrator.Outcome.Written
                adoptLoaded(state, frameActive = written?.activeEntryId != null)
                if (plan.notice == RestoreNotice.RECOVERED) {
                    // once, and it stands in for the uncalibrated notice on this launch (L10)
                    _launchAlert.value = MapLaunchAlert.LibraryRecovered
                } else {
                    written?.uncalibratedName?.let { _launchAlert.value = MapLaunchAlert.MigrationUncalibrated(it) }
                }
            }
            RestoreStatus.LOCKED, RestoreStatus.MIGRATION_PENDING -> {
                // nothing written, no reconcile, sweep or prune; Retry and an unlock come back here.
                // only a locked key or a write that failed ends up here now, both can clear up
                _libraryStatus.value = LibraryStatus.LOCKED
                if (_mapSource.value !is OnlineRasterMapSourceAndroid) publish(onlineBasemap(), frame = false)
                libraryIssueShown = true
                reportMapSelectionIssue(retry = { restoreLibrary(); true }, text = { Messages.mapLibraryLocked() })
            }
            RestoreStatus.CORRUPT -> onLibraryCorrupt()
        }
    }

    private fun onLibraryCorrupt() {
        _libraryStatus.value = LibraryStatus.CORRUPT
        if (_mapSource.value !is OnlineRasterMapSourceAndroid) publish(onlineBasemap(), frame = false)
        reportCorruptLibrary()
    }

    private fun reportCorruptLibrary() {
        libraryIssueShown = true
        // the quarantined copy stays put; Retry rebuilds from the files, it never deletes one (S2)
        reportMapSelectionIssue(retry = { rebuildCorruptLibrary() }, text = { Messages.mapLibraryCorruptMessage() })
    }

    /**
     * MapScreen's back (it's only up with the mission key unlocked): a locked library gets
     * another go (F3). This screen is the one in front now, so it records bakes again, and
     * another one may have written the library while it sat in the back
     */
    internal fun onMissionDataUnlocked() {
        bakeManager.recorders.register(bakeRecorder)
        when (_libraryStatus.value) {
            LibraryStatus.LOCKED -> restoreLibrary()
            LibraryStatus.LOADED -> syncWithDurable()
            else -> Unit
        }
    }

    /** only ever with a state read back as Loaded, a genuine first launch, or one we just wrote */
    private fun adoptLoaded(state: LibraryState, frameActive: Boolean = false) {
        _libraryState.value = state
        _libraryStatus.value = LibraryStatus.LOADED
        if (libraryIssueShown) {
            libraryIssueShown = false
            pendingRetry = null
            _mapSelectionPersistenceIssue.value = null
        }
        preferredBaseMap = styleOf(state.preferredOnlineStyle) ?: defaultStyle
        takeLaunchDecision(state)
        // R3-2 bake-only sweep on every authoritative restore, needs neither the PDF nor the selection
        sweepOrphanBakes()
        restoreActive(state, frameActive)
        reconcile()
        refreshDraftCounts()
        verifyActiveInBackground(state)
        sweepInterruptedImports()
        autoResumeCalibration(state)
    }

    /** the guard's launch step, once, now there's a library to take tokens from */
    private fun takeLaunchDecision(state: LibraryState) {
        if (pdfLaunchDecision != null) return
        val tokens = listOfNotNull(
            state.activeEntry?.takeIf { it.isPdf }?.renderGuardToken,
            autoResumeCandidate(state)?.second?.renderGuardToken,
        )
        val d = pdfRenderGuard.launchDecision(tokens)
        pdfLaunchDecision = d
        val notices = com.tacmap.map.render.pdf.PdfLaunchNotice.from(d)
        if (notices.isNotEmpty() && pdfRenderGuard.takeNotice()) _pdfLaunchNotices.value = notices
        // the MBTiles guard's step at the same moment, the restored token is the active pack (s14.1)
        mbtilesGuard.launchDecision(state.activeEntry?.takeIf { it.isMbtiles }?.id)
    }

    /** a token this launch's guard step named as the crash suspect (and nobody's opened it since) */
    private fun isCrashSuspect(token: String?): Boolean =
        pdfLaunchDecision != null && pdfRenderGuard.isSuspect(token)

    /** s9.8: a marker left behind = the parse took the process down. Copy goes, no retry, tell them once */
    private fun sweepInterruptedImports() {
        val journal = runCatching { DocumentImportCopyJournal(getApplication()) }.getOrNull() ?: return
        if (MapImportPipeline.sweepInterrupted(journal)) _launchAlert.value = MapLaunchAlert.ImportInterrupted
    }

    private fun styleOf(name: String?): BasemapStyle? = BasemapStyle.entries.firstOrNull { it.name == name }
        ?.takeIf { !it.requiresEsriKey || com.tacmap.calibration.EsriKey.isAvailable }

    /** s8.2 restore: size + mtime only, no hashing on main (D5-08). [reframe] false keeps the camera */
    private fun restoreActive(state: LibraryState, frameActive: Boolean = false, reframe: Boolean = true) {
        val entry = state.activeEntry
        if (entry == null) {
            publish(baseMapSource(styleOf(state.active.style) ?: preferredBaseMap), frame = false)
            return
        }
        // a pack the open guard blamed for the last crash isn't opened again on its own
        if (entry.isMbtiles && holdBackSuspectMbtiles(entry)) return
        if (entry.isMbtiles && canBeActive(entry)) {
            openMbtiles(entry, PackOpen.Restore(frameActive, reframe))
            return
        }
        // OD-F4 (M8): a PDF whose stored file is missing or changed keeps the selection and
        // goes up anyway, the render session checks the bytes against contentKey on open and
        // fails it as cannotOpen (Try Again, Use Online Map). nothing ever draws other bytes
        val source = when {
            canBeActive(entry) -> sourceFor(entry)
            entry.isPdf && entryAllowsActive(entry) -> sourceFor(entry)
            else -> null
        }
        if (source is PdfMapSource && holdBackSuspectPdf(source)) return
        publish(source ?: onlineBasemap(), frame = reframe && source != null && !frameActive)
        if (frameActive) {
            // OD-F13: the first launch after the upgrade shows the migrated map itself, whole,
            // not wherever the first GPS fix happens to be (same as iOS)
            source?.coverage?.let { fitCoverage(it); hasInitialFix = true }
        }
    }

    /**
     * Crash guard said this PDF took the app down last time: show the online map in
     * memory only (durable selection + PDF untouched) and ask. True when held back.
     */
    private fun holdBackSuspectPdf(pdf: PdfMapSource): Boolean {
        if (!isCrashSuspect(pdf.render.renderGuardToken)) return false
        publish(onlineBasemap(), frame = false)
        _pdfRecovery.value = CrashSuspect.Pdf(pdf)
        return true
    }

    /**
     * s14.1: the open guard named this pack the crash suspect, so it isn't opened at all. Online
     * in memory only (durable selection + entry untouched) and the PDF guard's alert asks.
     * True when held back
     */
    private fun holdBackSuspectMbtiles(entry: ImportedMapEntry): Boolean {
        if (!mbtilesGuard.launchDecided || !mbtilesGuard.isSuspect(entry.id)) return false
        publish(onlineBasemap(), frame = false)
        _pdfRecovery.value = CrashSuspect.Mbtiles(entry.id, entry.displayName)
        return true
    }

    /**
     * s14.1 + s14.2: [entry]'s pack goes up. The open guard's armed first (foreground only, on
     * disk), then this screen's open handle or one these bytes were admitted as earlier this
     * process goes up right away. Anything else is admitted on IO and only goes up if nothing
     * newer was asked for meanwhile. A published one stays armed till its first draw settles
     * (see [publish]). false = an activation that couldn't even start
     */
    private fun openMbtiles(entry: ImportedMapEntry, how: PackOpen): Boolean {
        val ready = readyMbtiles(entry)
        val placeholder = if (how is PackOpen.Restore && ready == null) {
            com.tacmap.calibration.MbtilesPlaceholderSource(entry.id, entry.displayName)
        } else null
        // whatever was opening is superseded before this one's armed, it may well be the same pack
        if (placeholder != null) publish(placeholder, frame = false) else mbtilesOpens.supersede()
        mbtilesGuard.arm(entry.id, foreground = com.tacmap.map.render.pdf.PdfRenderExecutor.foreground)
        if (ready != null) return putUp(entry.id, ready, how)
        val file = library.fileOf(entry)
        val generation = _libraryState.value?.generation
        if (file == null || generation == null) {
            releaseOpenGuard(entry.id)
            if (placeholder != null) publish(onlineBasemap(), frame = false)
            return false
        }
        val ticket = mbtilesOpens.begin(entry.id, generation)
        admitOffMain(entry, file) { pack -> onAdmitted(entry.id, ticket, pack, how, placeholder) }
        return true
    }

    /** the admission on IO. a screen that goes meanwhile closes whatever it opened */
    private fun admitOffMain(entry: ImportedMapEntry, file: File, done: (OfflineTileMapSourceAndroid?) -> Unit) {
        val id = entry.id
        viewModelScope.launch {
            var opened: OfflineTileMapSourceAndroid? = null
            try {
                withContext(Dispatchers.IO) {
                    // stat'd first, bytes that change under the open never match this key again
                    val size = file.length()
                    val modified = file.lastModified()
                    opened = admitMbtilesPack(file.path, entry.displayName)
                    opened?.let { com.tacmap.calibration.AdmittedMbtiles.put(id, entry.contentKey, size, modified, it.metadata) }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                opened?.close()
                releaseOpenGuard(id)
                throw c
            }
            done(opened)
        }
    }

    /** back on main with what the admission gave (null = refused) */
    private fun onAdmitted(
        id: String,
        ticket: MbtilesOpenRequests.Ticket,
        pack: OfflineTileMapSourceAndroid?,
        how: PackOpen,
        placeholder: MapSource?,
    ) {
        val generation = when {
            cleared || _libraryStatus.value != LibraryStatus.LOADED -> null
            // the activation writes, so it wants the key and the library as it is on disk now
            how is PackOpen.Activate -> if (com.tacmap.util.DataKey.isUnlocked) writableState()?.generation else null
            else -> _libraryState.value?.generation
        }
        var verdict = mbtilesOpens.finish(ticket, generation)
        // a restore's blank stands in for it, anything else up there came later
        if (placeholder != null && _mapSource.value !== placeholder) verdict = MbtilesOpenRequests.Verdict.SUPERSEDED
        when {
            verdict != MbtilesOpenRequests.Verdict.CURRENT -> {
                pack?.close()
                releaseOpenGuard(id)
            }
            pack == null -> {
                // refused, nothing written. a restore goes online in memory, the rest keep what's up
                releaseOpenGuard(id)
                if (how is PackOpen.Restore) publish(onlineBasemap(), frame = false)
            }
            else -> {
                offlineSources.put(id, pack)?.let { old -> closeSupersededOfflineSources(listOf(old), listOf(_mapSource.value, pack)) }
                putUp(id, pack, how)
            }
        }
        restoreIfLeftBlank()
    }

    /** the pack goes up the way [how] says. an activation's write failing = false, nothing's up */
    private fun putUp(id: String, pack: OfflineTileMapSourceAndroid, how: PackOpen): Boolean = when (how) {
        is PackOpen.Restore -> {
            publish(pack, frame = how.reframe && !how.frameActive)
            // OD-F13: the first launch after the upgrade shows the migrated map itself, whole,
            // not wherever the first GPS fix happens to be (same as iOS)
            if (how.frameActive) pack.coverage?.let { fitCoverage(it); hasInitialFix = true }
            true
        }
        PackOpen.Reopen -> {
            publish(pack, frame = true)
            true
        }
        is PackOpen.Activate -> {
            // re-reduced from the library as it is now, one write, then up
            val next = writableState()?.let { reduce(LibraryTransition.ActivateEntry(id), it) }
            val done = next != null && transition(next, retry = { activateImportedMap(id, how.frame) }) {
                previewReturn = null
                publish(pack, how.frame)
            }
            // opened but never put up: nothing will draw it
            if (!done) releaseOpenGuard(id)
            done
        }
    }

    /** done with [id]'s open guard marker, unless an open or a first draw of the same pack still needs it */
    private fun releaseOpenGuard(id: String) {
        if (mbtilesOpens.pending?.entryId == id || firstDraw?.first == id) return
        mbtilesGuard.complete(id)
    }

    /** a restore's blank still up with nothing coming for it (library moved, lock): the durable map again */
    private fun restoreIfLeftBlank() {
        if (cleared || _libraryStatus.value != LibraryStatus.LOADED || mbtilesOpens.pending != null) return
        if (_mapSource.value !is com.tacmap.calibration.MbtilesPlaceholderSource) return
        _libraryState.value?.let { restoreActive(it, reframe = false) }
    }

    /** the guard's first draw window for a freshly published pack, or the end of the old one */
    private fun onPublishedForOpenGuard(source: MapSource) {
        val pack = source as? OfflineTileMapSourceAndroid
        val incoming = pack?.let(::entryIdOf)
        val open = firstDraw
        if (open != null && open.second !== source) {
            // replaced before it settled: done with it all the same. unless it's the same entry
            // as a new source (cache dropped + reopened), then the marker is the new open's
            // and stays armed till its own first draw
            finishFirstDraw(release = open.first != incoming)
        }
        if (pack == null) return
        val token = incoming?.takeIf(mbtilesGuard::isArmed) ?: return
        firstDrawJob?.cancel()
        val watch = com.tacmap.map.render.MbtilesFirstDraw(android.os.SystemClock::elapsedRealtime)
        pack.readWatch = watch
        firstDraw = token to pack
        firstDrawJob = viewModelScope.launch {
            while (!watch.settled()) kotlinx.coroutines.delay(FIRST_DRAW_POLL_MS)
            firstDrawJob = null
            finishFirstDraw()
        }
    }

    private fun finishFirstDraw(release: Boolean = true) {
        val (token, pack) = firstDraw ?: return
        firstDraw = null
        firstDrawJob?.cancel()
        firstDrawJob = null
        pack.readWatch = null
        if (release) releaseOpenGuard(token)
    }

    /** Open Anyway */
    fun openSuspectPdfAnyway() {
        val suspect = _pdfRecovery.value ?: return
        if (suspect is CrashSuspect.Mbtiles) {
            openSuspectMbtilesAnyway(suspect)
            return
        }
        val pdf = (suspect as CrashSuspect.Pdf).source
        pdfRenderGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.OPEN_ANYWAY)
        _pdfRecovery.value = null
        if (pdf.isPreview) {
            // C8: it was the calibration preview that went down, opening it means resuming that
            pdf.entryId?.let { startCalibration(it, resumeSilently = true) }
            return
        }
        if (_mapSource.value is OnlineRasterMapSourceAndroid && _libraryState.value?.active?.entryId == pdf.entryId) {
            publish(pdf, frame = true)
        }
    }

    /** the pack the open guard held back: resolved first, then the usual guarded open if it's still the one */
    private fun openSuspectMbtilesAnyway(suspect: CrashSuspect.Mbtiles) {
        mbtilesGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.OPEN_ANYWAY)
        _pdfRecovery.value = null
        val state = _libraryState.value ?: return
        if (_mapSource.value !is OnlineRasterMapSourceAndroid || state.active.entryId != suspect.entryId) return
        val entry = state.entry(suspect.entryId)?.takeIf(::canBeActive) ?: return
        openMbtiles(entry, PackOpen.Reopen)
    }

    /** Not Now: keep the suspect so the next launch asks again */
    fun dismissPdfRecovery() {
        if (_pdfRecovery.value is CrashSuspect.Mbtiles) mbtilesGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.NOT_NOW)
        _pdfRecovery.value = null
    }

    /**
     * Delete Map… (after the usual confirm): the library delete, then the suspect's resolved.
     * A failed write leaves the dialog for another go, and the issue's Retry resolves it
     * too once the delete lands (F4)
     */
    fun deleteSuspectPdf(): Boolean {
        val id = _pdfRecovery.value?.entryId ?: return false
        return deleteImportedMap(id)
    }

    /** OK on the notice showing now, the next one (if any) comes up after it */
    fun consumePdfLaunchNotice() {
        _pdfLaunchNotices.value = _pdfLaunchNotices.value.drop(1)
    }

    /** the failure alert's Use Online Map (H1): a durable switch to the preferred style */
    fun restoreOnlineBasemap(): Boolean = selectBaseMap(preferredBaseMap)

    /** sha256 the active file once per launch, off main. A changed file isn't trusted */
    private fun verifyActiveInBackground(state: LibraryState) {
        val entry = state.activeEntry ?: return
        val key = entry.contentKey ?: return
        val file = library.fileOf(entry) ?: return
        viewModelScope.launch {
            val actual = withContext(Dispatchers.IO) { PdfCalibrationIdentity.contentKey(file) }
            if (actual == null || actual == key) return@launch
            _hashMismatch.value = _hashMismatch.value + entry.id
            val shown = _mapSource.value as? PdfMapSource
            when (
                BackgroundHashRules.onMismatch(
                    isPdf = entry.isPdf,
                    shownIsEntry = shown?.entryId == entry.id,
                    stillActive = _libraryState.value?.active?.entryId == entry.id,
                )
            ) {
                // M8: a PDF keeps its selection and goes sticky cannotOpen (Try Again rechecks
                // the bytes), the same as a file found missing at restore. a fresh source is
                // what makes the render session check again
                // (the couldn't-draw alert says so, no second dialog on top of it)
                MismatchAction.RECHECK_RENDER -> {
                    pdfRuntime.retry()
                    if (shown != null) _mapSource.value = shown.withRender(shown.render)
                }
                // D6: held back by the crash guard (or not up for some other reason). never a
                // durable switch for a PDF, Open Anyway draws it and the session catches it then
                MismatchAction.MARK_ONLY -> Unit
                MismatchAction.ONLINE_AND_ALERT -> {
                    selectBaseMap(preferredBaseMap)
                    _launchAlert.value = MapLaunchAlert.ActiveFileChanged
                }
                MismatchAction.ALERT -> _launchAlert.value = MapLaunchAlert.ActiveFileChanged
            }
        }
    }

    /** the newest active draft, and the entry it's for (matched by bytes + page, ids change on a rebuild) */
    private fun autoResumeCandidate(state: LibraryState): Pair<CalibrationDraft, ImportedMapEntry?>? {
        val draft = draftStore.all().filter { it.active }.maxByOrNull { it.updatedAtMs } ?: return null
        val entry = state.entries.firstOrNull {
            it.isPdf && it.contentKey == draft.contentKey && it.pdf?.pageIndex == draft.pageIndex
        }
        return draft to entry
    }

    /** just enough of a preview source for the crash dialog (name + id), Open Anyway starts the real one */
    private fun recoveryPreviewFor(entry: ImportedMapEntry): PdfMapSource? {
        val pdf = entry.pdf ?: return null
        val g = PdfGeoreference.provisional(Wgs84Coordinate(0.0, 0.0), pdf.pageBoxPoints, pdf.rotate)?.copy(page = pdf.pageIndex) ?: return null
        return pdfSourceFor(entry, g, preview = true)
    }

    /** E3: an active draft whose entry still exists comes straight back (lifecycle.autoResume) */
    private fun autoResumeCalibration(state: LibraryState) {
        if (autoResumeTried) return
        autoResumeTried = true
        val (draft, entry) = autoResumeCandidate(state) ?: return
        val suspectPreview = entry != null && state.active.entryId != entry.id && isCrashSuspect(entry.renderGuardToken)
        val decision = AutoResumeRules.decide(
            draftActive = draft.active,
            points = draft.points.size,
            pending = draft.pending != null,
            entry = when {
                entry == null -> AutoResumeEntry.MISSING
                fileStatus(entry) != EntryFileStatus.OK -> AutoResumeEntry.UNAVAILABLE
                else -> AutoResumeEntry.OK
            },
            library = RestoreStatus.LOADED,
            crashSuspectPending = _pdfRecovery.value != null || suspectPreview,
        )
        if (suspectPreview && _pdfRecovery.value == null && entry != null) {
            // C8: the preview's what went down. ask, like for a restored map; Open Anyway resumes it
            recoveryPreviewFor(entry)?.let { src -> _pdfRecovery.value = CrashSuspect.Pdf(src) }
        }
        if (decision.action != AutoResumeAction.RESUME || entry == null) return
        startCalibration(entry.id, resumeSilently = true)
    }

    /**
     * S2 Retry on a corrupt library: rebuild from the map files still here, never delete one.
     * Hashing + parsing is slow, so off main; one library write at the end, and if that fails
     * nothing's changed and the issue comes back. True = started
     */
    private fun rebuildCorruptLibrary(): Boolean {
        if (_libraryStatus.value != LibraryStatus.CORRUPT) return true
        if (rebuilding || restoring) return false
        rebuilding = true
        viewModelScope.launch {
            val rebuilt = try {
                withContext(Dispatchers.IO) {
                    LibraryRebuild.rebuild(
                        filesDir = filesDir,
                        defaultStyle = defaultStyle.name,
                        nowMs = System.currentTimeMillis(),
                        recoveredName = ::recoveredName,
                        inspectPdf = { f -> inspectForRecovery(f) },
                        validateMbtiles = ::opensAsMbtiles,
                    )
                }
            } catch (e: Exception) {
                QuietLog.w("MapViewModel", "library rebuild failed")
                null
            } finally {
                rebuilding = false
            }
            if (_libraryStatus.value != LibraryStatus.CORRUPT) return@launch
            when (val c = rebuilt?.let(library::create)) {
                // Loaded from here: the recovery flag keeps cleanup disabled, including after restart.
                is LibraryCommit.Written -> adoptLoaded(c.state)
                // another screen got a library down first, that one stands
                is LibraryCommit.Stale -> (c.current as? LibraryLoad.Loaded)?.let { adoptLoaded(it.state) } ?: reportCorruptLibrary()
                else -> reportCorruptLibrary()
            }
        }
        return true
    }

    // the S2 rebuild's rules, the salvage and the orphan adoption use them too
    private fun recoveredName(n: Int): String = Messages.mapRecoveredName(n.toString())

    private fun inspectForRecovery(f: File): com.tacmap.calibration.InspectionResult? =
        LibraryRebuild.withWatchdog { stop -> com.tacmap.calibration.PdfInspector.inspect(getApplication(), f, stop) { _, _ -> } }

    private fun opensAsMbtiles(f: File): Boolean =
        com.tacmap.calibration.MBTilesStore.open(f.path)?.let { it.close(); true } ?: false

    /** D7: a Try Again that verified the bytes takes the new size + mtime, else it'd stay unavailable */
    private fun refreshFileStamp(id: String) {
        val state = writableState() ?: return
        val entry = state.entry(id) ?: return
        val key = entry.contentKey ?: return
        val file = library.fileOf(entry)?.takeIf { it.isFile } ?: return
        if (file.length() == entry.byteCount && file.lastModified() == entry.fileModifiedAtMs) return
        viewModelScope.launch {
            val same = withContext(Dispatchers.IO) { com.tacmap.calibration.PdfStoredFile.matches(file, key) }
            if (!same) return@launch
            val now = writableState() ?: return@launch
            val next = reduce(LibraryTransition.RefreshFileStamp(id, key, file.length(), file.lastModified()), now) ?: return@launch
            commit(next)?.let { _libraryState.value = it }
        }
    }

    /**
     * the state a transition may build on: the sealed library as it is right now, taken first
     * if another screen or a bake wrote it since. null = locked/corrupt/still migrating, or
     * this view model's been cleared
     */
    private fun writableState(): LibraryState? {
        if (cleared) return null
        syncWithDurable()
        return _libraryState.value.takeIf { _libraryStatus.value == LibraryStatus.LOADED }
    }

    /**
     * The sealed library is the authority, this view model only holds a copy. A second
     * MainActivity's view model, or a bake recorded through another screen, can write it
     * behind this one's back. Re-read it and take it when its generation moved on, before any
     * transition and whenever the screen comes back to the front
     */
    private fun syncWithDurable() {
        if (_libraryStatus.value != LibraryStatus.LOADED) return
        val cached = _libraryState.value ?: return
        val current = library.loadCurrent()
        if (current is LibraryLoad.Loaded && current.state.generation == cached.generation) return
        takeDurable(current)
    }

    /** what's on disk now, for a copy that turned out to be behind. locked or still unwritten changes nothing */
    private fun takeDurable(current: LibraryLoad) {
        val cached = _libraryState.value ?: return
        when (current) {
            is LibraryLoad.Loaded -> adoptNewer(cached, current.state)
            // it went bad under us: same as finding it corrupt at launch, nothing's written or deleted from here
            LibraryLoad.Corrupt -> onLibraryCorrupt()
            // ledger only under a loaded copy (file and marker gone, or a first write died at its
            // rename): corrupt like in 3.0.1, only a restore pass salvages that one
            LibraryLoad.Unfinished -> onLibraryCorrupt()
            LibraryLoad.Locked, LibraryLoad.Empty -> Unit
        }
    }

    /**
     * Someone else wrote the library: take theirs as it is (nothing's written or deleted from
     * here) and put its active map up if that changed. Not while calibrating, Finish checks
     * the entry again anyway
     */
    private fun adoptNewer(before: LibraryState, current: LibraryState) {
        _libraryState.value = current
        preferredBaseMap = styleOf(current.preferredOnlineStyle) ?: defaultStyle
        _hashMismatch.value = _hashMismatch.value.filterTo(HashSet()) { current.entry(it) != null }
        if (_pdfRecovery.value?.entryId?.let(current::entry) == null) _pdfRecovery.value = null
        // MBTiles handles for entries that are gone or point at another file now
        val moved = offlineSources.keys.filter { id -> current.entry(id)?.fileName != before.entry(id)?.fileName }
        val dropped = moved.mapNotNull { offlineSources.remove(it) }
        if (!calibration.isActive && (before.active != current.active || before.activeEntry != current.activeEntry)) {
            previewReturn = null
            restoreActive(current, reframe = before.active != current.active)
        }
        closeSupersededOfflineSources(candidates = dropped, stillReferenced = listOf(_mapSource.value))
        refreshDraftCounts()
    }

    /**
     * The one way a transition writes (ImportedMapLibraryStore.commit). null = nothing went
     * down: the write failed, or the sealed library moved on since [next] was reduced, and
     * then the sealed one's taken first so a Retry builds on it
     */
    private fun commit(next: LibraryState): LibraryState? = when (val c = library.commit(next)) {
        is LibraryCommit.Written -> c.state
        is LibraryCommit.Stale -> {
            takeDurable(c.current)
            null
        }
        LibraryCommit.Failed -> null
    }

    /**
     * What cleanup keeps by: the sealed library as it is right now, never this view model's
     * copy. A genuine first launch hasn't written one yet, the empty library stands for it.
     * null = no authoritative read, skip. Called with the managed files lock held
     */
    private fun durableForCleanup(): LibraryState? {
        if (cleared || _libraryStatus.value != LibraryStatus.LOADED) return null
        return when (val load = library.load()) {
            is LibraryLoad.Loaded -> load.state
            // only while there's nothing it could delete: old stores waiting to migrate (a
            // quarantined one too) or map files without a library get migrated or adopted,
            // never reconciled away (s13.1)
            LibraryLoad.Empty -> _libraryState.value?.takeIf { it.generation == 0L && it.entries.isEmpty() && !migrator.isDue() }
            else -> null
        }
    }

    // ------------------------------------------------------------------ entries -> sources

    internal fun fileStatus(entry: ImportedMapEntry): EntryFileStatus =
        if (entry.id in _hashMismatch.value) EntryFileStatus.MISMATCH else library.fileStatus(entry)

    internal fun fileOf(entry: ImportedMapEntry): File? = library.fileOf(entry)

    private fun canBeActive(entry: ImportedMapEntry): Boolean {
        val status = fileStatus(entry)
        return LibraryEntryRules.canBeDurableActive(LibraryEntryRules.state(LibraryEntryRules.facts(entry), status))
    }

    /** what the entry itself allows, file aside (a restore keeps a missing PDF selected, M8) */
    private fun entryAllowsActive(entry: ImportedMapEntry): Boolean =
        LibraryEntryRules.canBeDurableActive(LibraryEntryRules.state(LibraryEntryRules.facts(entry), EntryFileStatus.OK))

    internal fun effectiveGeoref(entry: ImportedMapEntry): PdfGeoreference? {
        val pdf = entry.pdf ?: return null
        return (pdf.manual?.georef ?: pdf.embedded)?.let(PdfGeoreferenceCodec::decode)
    }

    private fun geometryOf(entry: ImportedMapEntry): PdfPageGeometry? {
        val pdf = entry.pdf ?: return null
        pdf.geometry?.takeIf { it.isValid() }?.let { return it }
        // no pdfium frame saved: the page box is close enough to draw
        val b = pdf.pageBoxPoints
        val box = PdfBox.of(listOf(b.minOf { it.x }, b.minOf { it.y }, b.maxOf { it.x }, b.maxOf { it.y })) ?: return null
        val w = if (pdf.rotate % 180 == 0) box.width else box.height
        val h = if (pdf.rotate % 180 == 0) box.height else box.width
        return PdfPageGeometry(box, null, PdfPageGeometry.normaliseRotation(pdf.rotate), w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
    }

    /** the PDF of [entry] on [georef] (its effective one by default) */
    internal fun pdfSourceFor(entry: ImportedMapEntry, georef: PdfGeoreference?, preview: Boolean = false): PdfMapSource? {
        val pdf = entry.pdf ?: return null
        val file = library.fileOf(entry) ?: return null
        val geometry = geometryOf(entry) ?: return null
        val manual = pdf.manual != null
        val calibration = georef?.takeIf { !preview }?.let {
            if (manual) Calibration.Fiduciaries(emptyList(), it) else Calibration.Parsed(it)
        }
        return PdfMapSource(
            uri = Uri.fromFile(file),
            displayName = entry.displayName,
            kind = if (manual || georef == null) MapSourceKind.CALIBRATED_PDF else MapSourceKind.GEO_PDF,
            calibration = calibration,
            geometry = geometry,
            provisional = georef.takeIf { preview },
            entryId = entry.id,
            pageIndex = pdf.pageIndex,
            contentKey = entry.contentKey,
            isPreview = preview,
            render = com.tacmap.calibration.PdfRenderMeta(
                renderGuardToken = entry.renderGuardToken,
                contentKey = entry.contentKey,
                // baked tiles only ever ride on the georef they were made from (M4)
                bake = pdf.validBake.takeIf { !preview && georef != null && georef == effectiveGeoref(entry) },
            ),
        )
    }

    /**
     * the tile source for [pdf], drawn on [georef] when calibration shows another one (s7.2,
     * M13). no bake there, and its own cache key, so a refit never draws the old tiles
     */
    internal fun pdfTileSourceFor(pdf: PdfMapSource, georef: PdfGeoreference?, density: Float): com.tacmap.map.render.TileSource? {
        val drawn = when {
            // while calibrating it's always the bake-free copy, even on the saved georef (F1)
            calibration.isActive -> pdf.drawnOn(georef ?: pdf.placement ?: return null)
            georef == null || georef == pdf.placement -> pdf
            else -> pdf.drawnOn(georef)
        }
        return pdfRuntime.tileSource(drawn, density)
    }

    /**
     * what the import probe draws (M12): the page about to be committed on its own georef,
     * or on the provisional one it'll be calibrated on when it has none (rejected, plain)
     */
    internal fun probeSourceFor(entry: ImportedMapEntry): PdfMapSource? {
        effectiveGeoref(entry)?.let { return pdfSourceFor(entry, it) }
        val pdf = entry.pdf ?: return null
        val centre = Wgs84Coordinate(_cameraLat.value, _cameraLng.value)
        val provisional = PdfGeoreference.provisional(centre, pdf.pageBoxPoints, pdf.rotate)?.copy(page = pdf.pageIndex) ?: return null
        return pdfSourceFor(entry, provisional, preview = true)
    }

    /** never admits an MBTiles pack, that's [openMbtiles]'s job off main (s14.2) */
    private fun sourceFor(entry: ImportedMapEntry): MapSource? = when {
        entry.isPdf -> effectiveGeoref(entry)?.let { pdfSourceFor(entry, it) }
        entry.isMbtiles -> readyMbtiles(entry)
        else -> null
    }

    /**
     * a pack that can go up without admitting it here: this screen's open handle, or a
     * prevalidated one when these bytes passed admission earlier this process (a stat, no open)
     */
    private fun readyMbtiles(entry: ImportedMapEntry): OfflineTileMapSourceAndroid? {
        offlineSources[entry.id]?.let { return it }
        val file = library.fileOf(entry) ?: return null
        val metadata = com.tacmap.calibration.AdmittedMbtiles.get(entry.id, entry.contentKey, file) ?: return null
        return OfflineTileMapSourceAndroid.prevalidated(file.path, entry.displayName, metadata)
            .also { offlineSources[entry.id] = it }
    }

    private fun entryIdOf(source: MapSource): String? = when (source) {
        is PdfMapSource -> source.entryId
        is OfflineTileMapSourceAndroid -> offlineSources.entries.firstOrNull { it.value === source }?.key
        is com.tacmap.calibration.MbtilesPlaceholderSource -> source.entryId
        else -> null
    }

    /** the entry the map is showing, preview included */
    internal fun shownEntryId(): String? = entryIdOf(_mapSource.value)

    private fun publish(source: MapSource, frame: Boolean) {
        // something else going up supersedes an MBTiles open still running (s14.2)
        mbtilesOpens.supersede()
        _mapSource.value = source
        if (frame) frameCameraFor(source)
        onPublishedForOpenGuard(source)
        // s2.8: a different entry under a running calibration suspends it
        calibration.onActiveSourceChanged(entryIdOf(source))
    }

    // ------------------------------------------------------------------ transitions (one write each)

    /** the pure candidate for [t], null when it can't apply (unknown id, full, mismatch) */
    private fun reduce(t: LibraryTransition, state: LibraryState): LibraryState? =
        (LibraryReducer.apply(t, state) as? LibraryReduction.Ok)?.state

    private fun transition(next: LibraryState, retry: () -> Boolean, publishAfter: () -> Unit): Boolean {
        val previous = _libraryState.value
        val written = commit(next) ?: run {
            reportWriteFailed(retry)
            return false
        }
        previous?.let { removeKnownSupersededBakes(it, written) }
        _libraryState.value = written
        _libraryStatus.value = LibraryStatus.LOADED
        pendingRetry = null
        _mapSelectionPersistenceIssue.value = null
        publishAfter()
        reconcile()
        return true
    }

    /** row tap / after import: make [id] the durable basemap */
    internal fun activateImportedMap(id: String, frame: Boolean = true): Boolean {
        val state = writableState() ?: return false
        val entry = state.entry(id) ?: return false
        if (!canBeActive(entry)) return false
        if (entry.isMbtiles) {
            // picking the held back pack in Layers is Open Anyway, resolved before it's opened.
            // by what the guard holds, the alert may be long gone (Not Now keeps the suspect)
            if (mbtilesGuard.isSuspect(id)) {
                mbtilesGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.OPEN_ANYWAY)
                val held = _pdfRecovery.value
                if (held is CrashSuspect.Mbtiles && held.entryId == id) _pdfRecovery.value = null
            }
            // checked off main, written + up when it comes back. true = started (s14.2)
            return openMbtiles(entry, PackOpen.Activate(frame))
        }
        val source = sourceFor(entry) ?: return false
        val next = reduce(LibraryTransition.ActivateEntry(id), state)
        return next != null && transition(next, retry = { activateImportedMap(id, frame) }) {
            val suspect = _pdfRecovery.value
            if (suspect is CrashSuspect.Pdf && suspect.entryId == id) {
                // picking it in Layers is an explicit open, same as Open Anyway
                pdfRenderGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.OPEN_ANYWAY)
                _pdfRecovery.value = null
            }
            previewReturn = null
            publish(source, frame)
        }
    }

    /**
     * import commit: the entry, plus active when it has a georef or is MBTiles. [admitted] is
     * what the import worker's admission of the pack gave, so it goes up without another one
     */
    internal fun addImportedEntry(
        entry: ImportedMapEntry,
        activate: Boolean,
        frame: Boolean = true,
        admitted: com.tacmap.calibration.MBTilesStore.Metadata? = null,
    ): Boolean {
        val state = writableState() ?: return false
        val t = if (entry.derivedFromId != null) LibraryTransition.AddDerived(entry) else LibraryTransition.AddEntry(entry, activate)
        val next = reduce(t, state) ?: return false
        val shown = next.active.entryId == entry.id
        return transition(next, retry = { addImportedEntry(entry, activate, frame, admitted) }) {
            if (admitted != null && entry.isMbtiles) {
                com.tacmap.calibration.AdmittedMbtiles.put(entry.id, entry.contentKey, entry.byteCount, entry.fileModifiedAtMs, admitted)
            }
            if (shown) {
                previewReturn = null
                val reframe = frame && entry.derivedFromId == null
                if (entry.isMbtiles) openMbtiles(entry, PackOpen.Restore(frameActive = false, reframe = reframe))
                else sourceFor(entry)?.let { publish(it, reframe) }
            }
        }
    }

    /**
     * E9: the same bytes imported again over an unavailable entry. One write points the
     * entry at the new verified copy, then the old (missing or changed) file goes. Its
     * calibration and bake record stay. false = nothing changed, the usual Retry is up
     */
    internal fun relinkImportedMap(id: String, file: File): Boolean {
        val state = writableState() ?: return false
        val old = state.entry(id) ?: return false
        val key = old.contentKey ?: return false
        if (!file.isFile) return false
        val rel = ImportedMapLibraryStore.relativeName(filesDir, file) ?: return false
        val t = LibraryTransition.Relink(id, key, rel, file.length(), file.lastModified())
        val next = reduce(t, state) ?: return false
        val oldFile = library.fileOf(old)
        return transition(next, retry = { relinkImportedMap(id, file) }) {
            _hashMismatch.value = _hashMismatch.value - id
            offlineSources.remove(id)?.close()
            if (oldFile != null && oldFile.absoluteFile != file.absoluteFile) {
                runCatching { oldFile.delete() }
                ImportedMapLibraryStore.SQLITE_SIDECARS.forEach { s -> runCatching { File(oldFile.parentFile, oldFile.name + s).delete() } }
            }
        }
    }

    /**
     * P1 "Choose page...": the entry now means another page. Its calibration and the
     * old page's draft go (they were for different page points). A page without a
     * georef can't stay the basemap, the reducer drops back to online for that.
     */
    internal fun changeImportedMapPage(
        id: String,
        pageIndex: Int,
        rotate: Int,
        pageBox: List<List<Double>>,
        embedded: com.tacmap.calibration.PersistedGeoreference?,
        embeddedIssue: String?,
        geometry: PdfPageGeometry?,
    ): Boolean {
        val state = writableState() ?: return false
        val old = state.entry(id) ?: return false
        val t = LibraryTransition.ChangePage(id, pageIndex, rotate, pageBox, embedded, embeddedIssue, geometry)
        val next = reduce(t, state) ?: return false
        return transition(next, retry = { changeImportedMapPage(id, pageIndex, rotate, pageBox, embedded, embeddedIssue, geometry) }) {
            old.contentKey?.let { key -> old.pdf?.let { draftStore.delete(CalibrationTarget.draftKey(key, it.pageIndex)) } }
            refreshDraftCounts()
            if (state.active.entryId == id || shownEntryId() == id) {
                previewReturn = null
                val updated = next.entry(id)
                val source = updated?.takeIf { next.active.entryId == id }?.let(::sourceFor)
                publish(source ?: onlineBasemap(), frame = source != null)
            }
        }
    }

    /**
     * Finish (s2.6): manual + active in ONE write, then publish without reframing.
     * entryId, contentKey and pageIndex must all still match or nothing's written (D5-10).
     */
    internal fun commitCalibration(target: CalibrationTarget, manual: ManualCalibration): Boolean {
        val state = writableState() ?: return false
        val t = LibraryTransition.CommitCalibration(target.entryId, manual, target.contentKey, target.pageIndex)
        val next = reduce(t, state) ?: return false
        val updated = next.entry(target.entryId) ?: return false
        // no retry lambda: a failed commit stays in calibration with its own Retry (s2.6)
        val written = commit(next) ?: return false
        removeKnownSupersededBakes(state, written)
        _libraryState.value = written
        pendingRetry = null
        _mapSelectionPersistenceIssue.value = null
        previewReturn = null
        effectiveGeoref(updated)?.let { g -> pdfSourceFor(updated, g)?.let { _mapSource.value = it } }
        reconcile()
        refreshDraftCounts()
        return true
    }

    /** P1: drop the manual calibration, the PDF's own georef takes over */
    internal fun useEmbeddedGeoref(id: String): Boolean {
        val state = writableState() ?: return false
        val pdf = state.entry(id)?.pdf ?: return false
        if (pdf.embedded == null || pdf.manual == null) return false
        val next = reduce(LibraryTransition.RevertToEmbedded(id), state) ?: return false
        val updated = next.entry(id) ?: return false
        return transition(next, retry = { useEmbeddedGeoref(id) }) {
            if (state.active.entryId == id) sourceFor(updated)?.let { publish(it, frame = false) }
        }
    }

    /**
     * s8.2 delete (D5-04, D5-19): one write drops the entry + anything baked from it
     * (active goes online if it was any of them), publish, close MBTiles, drop the
     * draft, unlink the bytes and sidecars, reconcile. A crash mid-way only leaves
     * orphans the next reconcile removes.
     */
    internal fun deleteImportedMap(id: String): Boolean {
        val state = writableState() ?: return false
        val reduced = LibraryReducer.apply(LibraryTransition.DeleteEntry(id), state) as? LibraryReduction.Ok ?: return false
        val gone = reduced.removed
        // the PDF's bake has nowhere to go any more, stop it before anything goes (F7, M11)
        gone.forEach { e -> if (e.isPdf) bakeManager.cancelFor(library.fileOf(e)) }
        val goneIds = gone.map { it.id }.toSet()
        val wasActive = state.active.entryId in goneIds
        val written = commit(reduced.state) ?: run {
            reportWriteFailed { deleteImportedMap(id) }
            return false
        }
        _libraryState.value = written
        pendingRetry = null
        _mapSelectionPersistenceIssue.value = null
        val showingGone = shownEntryId() in goneIds
        if (wasActive || showingGone) {
            previewReturn = null
            publish(onlineBasemap(), frame = false)
        }
        // a deleted crash suspect is resolved, whichever way the delete got here (the crash
        // dialog, its Retry after a failed write, or Layers) and the dialog goes with it (F4)
        val held = _pdfRecovery.value
        if (gone.any { isCrashSuspect(it.renderGuardToken) } || (held is CrashSuspect.Pdf && held.entryId in goneIds)) {
            pdfRenderGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.DELETED)
        }
        if (gone.any { it.isMbtiles && mbtilesGuard.isSuspect(it.id) }) {
            mbtilesGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.DELETED)
        }
        if (held?.entryId in goneIds) _pdfRecovery.value = null
        gone.forEach { e ->
            offlineSources.remove(e.id)?.close()
            e.contentKey?.let { key -> e.pdf?.let { draftStore.delete(CalibrationTarget.draftKey(key, it.pageIndex)) } }
            library.fileOf(e)?.let { f ->
                runCatching { f.delete() }
                ImportedMapLibraryStore.SQLITE_SIDECARS.forEach { s -> runCatching { File(f.parentFile, f.name + s).delete() } }
            }
            // and its baked tiles, straight away (plaintext AO, M11). name checked there
            e.pdf?.bake?.fileName?.let { name -> runCatching { LibraryMapFiles.deleteBake(filesDir, name) } }
        }
        reconcile()
        sweepOrphanBakes()
        refreshDraftCounts()
        return true
    }

    // ------------------------------------------------------------------ WP2 bake record (M3, M6, M7)

    /**
     * the bake publish, main thread: one library write onto the entry it was made from, on the
     * sealed library as it is now (writableState), whichever screen started the bake
     */
    internal fun attachBake(entryId: String?, contentKey: String?, token: String, bake: PersistedPdfBake): PdfBakeAttach {
        val state = writableState() ?: return PdfBakeAttach.WriteFailed(null)
        val id = entryId ?: return PdfBakeAttach.SourceChanged
        val next = when (val r = LibraryReducer.apply(LibraryTransition.AttachBake(id, contentKey, token, bake), state)) {
            is LibraryReduction.Ok -> r.state
            is LibraryReduction.Rejected ->
                return if (r.error == com.tacmap.calibration.LibraryTransitionError.INVALID_BAKE) PdfBakeAttach.WriteFailed(null)
                else PdfBakeAttach.SourceChanged
        }
        val written = commit(next) ?: return PdfBakeAttach.WriteFailed(null)
        removeKnownSupersededBakes(state, written)
        _libraryState.value = written
        // the previous bake (if any) is unreferenced now, the reconcile reaps it
        reconcile()
        return PdfBakeAttach.Attached
    }

    /** a cancel that landed during the attach takes it back off. true = it's off */
    internal fun detachBake(entryId: String?, bake: PersistedPdfBake): Boolean {
        val state = writableState() ?: return false
        val id = entryId ?: return false
        val next = reduce(LibraryTransition.ClearBake(id, bake.fileName), state) ?: return false
        val written = commit(next) ?: return false
        removeKnownSupersededBakes(state, written)
        _libraryState.value = written
        return true
    }

    /** a bake for the PDF on screen landed: same map, now with tiles to read */
    private fun onBakePublished(p: com.tacmap.calibration.PdfBakeManager.Published) {
        val shown = _mapSource.value as? PdfMapSource ?: return
        if (shown.render.renderGuardToken != p.renderGuardToken || shown.isPreview || shown.calibration == null) return
        val upgraded = shown.withRender(shown.render.copy(bake = p.bake))
        _mapSource.value = upgraded
        // F1/M13: a calibration display never draws a bake. the record's on the entry already,
        // the runtime attaches it to the next source built on the entry's own georef
        if (!calibration.isActive) pdfRuntime.onBakePublished(upgraded)
    }

    /**
     * Remove Offline Tiles (M6): one library write drops the record (nothing's deleted if that
     * fails, the usual Retry), the live reader lets go, then the file + sidecars go right away
     * and the bake sweep after, on IO. Works with the PDF missing (R2-S2). True = started
     */
    fun removePdfBake(): Boolean {
        val pdf = _mapSource.value as? PdfMapSource ?: return false
        val id = pdf.entryId ?: return false
        val state = writableState() ?: return false
        val name = state.entry(id)?.pdf?.bake?.fileName ?: return false
        val next = reduce(LibraryTransition.ClearBake(id, name), state) ?: return false
        val written = commit(next) ?: run {
            reportWriteFailed { removePdfBake() }
            return false
        }
        _libraryState.value = written
        pendingRetry = null
        _mapSelectionPersistenceIssue.value = null
        pdfRuntime.detachBake()
        (_mapSource.value as? PdfMapSource)?.takeIf { it.entryId == id && it.render.bake?.fileName == name }
            ?.let { _mapSource.value = it.withRender(it.render.copy(bake = null)) }
        viewModelScope.launch(Dispatchers.Main) {
            val deleted = withContext(Dispatchers.IO) { LibraryMapFiles.deleteBake(filesDir, name) }
            if (!deleted) android.util.Log.w("MapViewModel", "bake file didn't go, the next sweep gets it")
            // D5: the library as it is now, a write that landed during the IO hop wins
            writableState() ?: return@launch
            sweepOrphanBakes()
            reconcile()
        }
        return true
    }

    /**
     * R3-2 bake-only sweep (M7): bake files in offline_tiles no PDF entry names. Only off a
     * Loaded library, the sealed one read under the managed files lock on IO
     */
    private fun sweepOrphanBakes() {
        if (_libraryStatus.value != LibraryStatus.LOADED) return
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                // the sealed library once the lock's held, a write since this was queued wins
                LibraryMapFiles.sweepBakes(filesDir) { durableForCleanup() }
            }.getOrNull()
            if (r == false) android.util.Log.w("MapViewModel", "bake sweep left something it wouldn't touch")
        }
    }

    private fun removeKnownSupersededBakes(previous: LibraryState, next: LibraryState) {
        if (next.permitsCleanup) return
        val kept = next.bakeFileNames
        val removed = previous.entries.mapNotNull { it.pdf?.validBake }.filter { it.fileName !in kept }
        if (removed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            removed.forEach { LibraryMapFiles.deleteBake(filesDir, it.fileName) }
        }
    }

    private fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) tileCache.trimToInUse()
        pdfRuntime.onTrimMemory(level)
    }

    /**
     * Keep = every entry's file (+ sidecars) and whatever an import or bake is still
     * writing. Never while locked/corrupt or still migrating. Drafts for files no longer in
     * the library go too. Both off the sealed library read under the lock, not this view
     * model's copy: a stale copy must never decide what gets deleted
     */
    private fun reconcile() {
        if (_libraryStatus.value != LibraryStatus.LOADED) return
        onReconcileForTests?.invoke()
        // keep set includes each PDF's bake, same lock the bake publish and Remove take (M5, R2-S2)
        runCatching { LibraryMapFiles.reconcile(filesDir, pruneDrafts = { draftStore.prune(it) }) { durableForCleanup() } }
    }

    /** instrumented tests only: sees each reconcile as it starts (which thread it's on) */
    @androidx.annotation.VisibleForTesting
    internal var onReconcileForTests: (() -> Unit)? = null

    internal fun refreshDraftCounts() {
        _draftCounts.value = draftStore.all().associate { it.key to it.points.size }
    }

    // ------------------------------------------------------------------ calibration (contract s2)

    /**
     * E1/E2/E3. Preconditions s2.2: library Loaded, file there, not calibrating.
     * A sheet with no georef is shown as a non-durable preview on a provisional
     * placement; one with a georef becomes the durable active map first.
     */
    internal fun startCalibration(entryId: String, resumeSilently: Boolean = false): Boolean {
        if (calibration.isActive) return false
        val state = writableState() ?: return false
        val entry = state.entry(entryId)?.takeIf { it.isPdf } ?: return false
        if (fileStatus(entry) != EntryFileStatus.OK) return false
        val pdf = entry.pdf ?: return false
        // a recovered PDF that wouldn't inspect has no page to put points on
        if (pdf.pageCount <= 0) return false
        val contentKey = entry.contentKey ?: return false
        val target = CalibrationTarget(entry.id, contentKey, pdf.pageIndex, pdf.pageBoxPoints, pdf.rotate)
        val effective = effectiveGeoref(entry)
        // C2/M10: calibrating the held back crash suspect is an explicit open, same as Open
        // Anyway or picking it in Layers. any other entry leaves the dialog where it is
        val suspect = _pdfRecovery.value
        if (suspect is CrashSuspect.Pdf && suspect.entryId == entry.id) {
            pdfRenderGuard.resolve(com.tacmap.map.render.pdf.GuardResolution.OPEN_ANYWAY)
            _pdfRecovery.value = null
        }
        val base: PdfGeoreference
        val preview: Boolean
        if (effective == null) {
            val centre = Wgs84Coordinate(_cameraLat.value, _cameraLng.value)
            base = PdfGeoreference.provisional(centre, pdf.pageBoxPoints, pdf.rotate)?.copy(page = pdf.pageIndex) ?: return false
            val source = pdfSourceFor(entry, base, preview = true) ?: return false
            if (previewReturn == null) previewReturn = _mapSource.value
            // framed below, on what the session actually shows (a resumed draft may be a fit)
            publish(source, frame = false)
            preview = true
        } else {
            base = effective.copy(crop = pdf.pageBoxPoints)
            if (state.active.entryId != entry.id) {
                if (!activateImportedMap(entry.id, frame = true)) return false
            } else if (shownEntryId() != entry.id) {
                // durable already but not up (the held back suspect): put it up, framed
                val source = sourceFor(entry) ?: return false
                previewReturn = null
                publish(source, frame = true)
            }
            preview = false
        }
        _isBrowsing.value = true
        // no first-fix recentre yanking the map off the sheet mid calibration
        hasInitialFix = true
        val manual = pdf.manual
        val embeddedDatum = pdf.embedded?.datum?.id?.takeIf { com.tacmap.calibration.GeoDatums.byId(it) != null }
        return calibration.start(
            CalibrationStart(
                target = target,
                entryName = entry.displayName,
                base = base,
                savedEffective = effective,
                seedDatumId = manual?.datumId,
                seedPoints = manual?.points.orEmpty(),
                embeddedDatumId = embeddedDatum,
                draft = draftStore.load(target.draftKey),
                isPreview = preview,
                resumeSilently = resumeSilently,
            )
        ).also { started ->
            refreshDraftCounts()
            // s2.3 step 8 frames the page once; E3 frames it the same way (OD-F1). on the
            // display georef, so a resumed fit lands on the sheet and not where the camera was
            if (started && (preview || resumeSilently)) {
                calibration.state.value?.display?.georef?.wgs84Bounds()?.let(::fitCoverage)
            }
        }
    }

    /** Finish: one library write, then end. false = stay in calibration with Retry */
    internal fun finishCalibration(): Boolean {
        val ui = calibration.state.value ?: return false
        val manual = calibration.manualForCommit() ?: return false
        if (!commitCalibration(ui.target, manual)) {
            calibration.commitFailed()
            return false
        }
        if (com.tacmap.BuildConfig.DEBUG) {
            // debug builds only, numbers but no coordinates: the on-device check reads the fit off this
            val r = ui.report
            runCatching {
                android.util.Log.d(
                    "TacMapCalibration",
                    "committed n=${r.n} rmsM=${r.rmsM} maxM=${r.maxM} tauM=${r.toleranceM} grade=${r.grade?.code} " +
                        "residualsM=${r.residualsM.values.joinToString { "%.3f".format(it) }}",
                )
            }
        }
        calibration.endAfterCommit()
        refreshDraftCounts()
        return true
    }

    /** after leave / suspend: a preview hands the map back without reframing */
    internal fun endCalibrationPreview() {
        val back = previewReturn ?: return
        previewReturn = null
        if ((_mapSource.value as? PdfMapSource)?.isPreview == true) {
            _mapSource.value = back
        }
        refreshDraftCounts()
    }

    private fun reportMapSelectionIssue(
        retry: () -> Boolean,
        text: () -> String,
    ) {
        pendingRetry = retry
        _mapSelectionPersistenceIssue.value = MapSelectionPersistenceIssue(
            id = ++nextMapSelectionIssueId,
            text = text,
        )
    }

    /** "Map change not saved" with Retry, the one every failed library write shows */
    private fun reportWriteFailed(retry: () -> Boolean) {
        // a library that went corrupt under the write keeps its own issue up
        if (_libraryStatus.value != LibraryStatus.LOADED) return
        val message = Messages.displayTheBasemapChoiceCouldNotBeSavedThePrevious420c88c7Message()
        reportMapSelectionIssue(retry) { message.text }
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
        if (camera.viewportWidth > 0.0 && camera.viewportHeight > 0.0) {
            lastViewportSize = camera.viewportWidth to camera.viewportHeight
        }
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

    private val _headingRequests = Channel<Double>(Channel.CONFLATED)
    /** set the map heading outright (the debug camera hook) */
    val headingRequests: Flow<Double> = _headingRequests.receiveAsFlow()

    /** DEBUG launch hook (docs/DEBUG_HOOKS.md): camera straight to lat/lon/zoom[/heading] */
    internal fun applyDebugCamera(camera: com.tacmap.app.DebugLaunchHooks.Camera) {
        if (!com.tacmap.BuildConfig.DEBUG) return
        flyTo(camera.latitude, camera.longitude, camera.zoom.toFloat())
        camera.heading?.let { _headingRequests.trySend(it) }
    }

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

    /** Re-frame the loaded offline/imported map's whole coverage. Paired with
     *  centreOnUser so that after panning off (or centring on a distant live
     *  location) the user can jump straight back to where the map actually is.
     *  No-op for unbounded online basemaps. */
    fun centreOnMap() {
        val coverage = _mapSource.value.coverage ?: return
        fitCoverage(coverage)
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
        return wgs84Text(lat, lng)
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
        cleared = true
        tacticalApp.memoryPressure -= trimListener
        // a screen still alive under this one takes the bakes back
        bakeManager.recorders.unregister(bakeRecorder)
        pdfRuntime.release()
        headingService.stop()
        locationService.stop()
        // an open still running closes what it got when it comes back, its marker goes then
        mbtilesOpens.supersede()
        // the source goes before its first draw settled, done with it all the same
        finishFirstDraw()
        closeSupersededOfflineSources(
            candidates = listOf(_mapSource.value) + offlineSources.values,
            stillReferenced = emptyList(),
        )
        offlineSources.clear()
        super.onCleared()
    }
}

/** Decimal-degree readout used by the header and the long-press point menu. */
internal fun wgs84Text(lat: Double, lng: Double): String =
    "%.5f° %s, %.5f° %s".format(
        abs(lat), if (lat >= 0) "N" else "S",
        abs(lng), if (lng >= 0) "E" else "W"
    )
