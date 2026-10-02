package com.tacmap.map

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues

import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import com.tacmap.ui.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.tacmap.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tacmap.map.render.OnlineTileHealth
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tacmap.calibration.EntryMenuAction
import com.tacmap.calibration.EntryRowTap
import com.tacmap.calibration.ImportError
import com.tacmap.calibration.ImportFailure
import com.tacmap.calibration.ImportOutcome
import com.tacmap.calibration.ImportedMapEntry
import com.tacmap.calibration.InFlightImportFiles
import com.tacmap.calibration.InspectionResult
import com.tacmap.calibration.LibraryEntryRules
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.fiducial.CalibrationCameraAnchor
import com.tacmap.calibration.fiducial.CalibrationCapture
import com.tacmap.calibration.fiducial.resolved
import com.tacmap.map.render.CalibrationMarker
import com.tacmap.map.render.CalibrationMarkerModel
import androidx.activity.compose.BackHandler
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.app.DocumentImportKind
import com.tacmap.app.AppLock
import com.tacmap.app.PendingDocumentImport
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStore
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.export.GeoJsonExporter
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.models.LiveMapLocationAction
import com.tacmap.models.LiveMapLocationPermissionPolicy
import com.tacmap.models.LiveMapLocationState
import com.tacmap.models.MissionUndoHistory
import com.tacmap.models.TrackRecordingPhase
import com.tacmap.models.TrackRecordingSettingsTarget
import com.tacmap.models.UndoTarget
import com.tacmap.settings.MapOrientationMode
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import com.tacmap.waypoints.WaypointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

private data class QuickAddTarget(
    val latitude: Double,
    val longitude: Double,
    val layerId: String,
    /** Placed from the long-press point menu rather than at the crosshair. */
    val fromPointMenu: Boolean = false,
)

private data class PendingDrawingMutation(
    val intent: DrawingMutationIntent,
    val pendingMessage: com.tacmap.localization.LocalizedMessage,
    val persist: () -> Boolean,
    val onSaved: () -> Unit,
)

@Composable
internal fun MapScreen(
    appLock: AppLock,
    vm: MapViewModel = viewModel(),
    isPurchased: Boolean = true,
    trialDaysRemaining: Int = 0,
    pendingDocumentImport: PendingDocumentImport?,
    onRequestDocumentImport: (DocumentImportKind) -> Unit,
    onClaimDocumentImport: (String) -> Boolean,
    onCompleteDocumentImport: (String) -> Unit,
    onAbandonDocumentImport: (String) -> Boolean,
    onRequestAuthBoundChange: ((Boolean) -> Unit)? = null,
    liveMapLocationState: LiveMapLocationState,
    onRequestLiveMapLocation: () -> Unit,
    onOpenLiveMapLocationSettings: () -> Unit,
    onRequestTrackRecording: () -> Unit,
    onOpenTrackRecordingSettings: ((TrackRecordingSettingsTarget) -> Unit)? = null,
    onUnlock: () -> Unit = {},
) {
    val context = LocalContext.current
    // Read activity insets before entering the popup, whose own insets can be zero.
    val menuBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rendererDensity = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val unitSyncRuntime = remember(context) {
        (context.applicationContext as com.tacmap.app.TacticalApp).unitSyncRuntime
    }
    val unitSyncForegroundEpoch by unitSyncRuntime.foregroundEpoch.collectAsState()

    val onlineBasemapsEnabled by vm.opsec.onlineBasemaps.collectAsState()
    val onlineLookupsEnabled by vm.opsec.onlineLookups.collectAsState()
    val primaryCoordinateType by vm.opsec.primaryCoordinateType.collectAsState()
    val nightModeOn by vm.opsec.nightMode.collectAsState()
    val mapOrientationMode by vm.opsec.mapOrientationMode.collectAsState()
    var headingSessionActive by remember { mutableStateOf(false) }
    val onlineTilesUnavailable by OnlineTileHealth.temporarilyUnavailable.collectAsState()
    val pendingTarget by vm.pendingCameraTarget.collectAsState()
    val cameraLat by vm.cameraLat.collectAsState()
    val cameraLng by vm.cameraLng.collectAsState()
    val cameraViewportState by vm.cameraViewportState.collectAsState()
    val centreElevation by vm.centreElevation.collectAsState()
    val isBrowsing by vm.isBrowsing.collectAsState()
    val trackRecordingState by vm.trackRecorder.uiState.collectAsState()
    val isRecordingTrack = trackRecordingState.showsRec
    val trackPoints by vm.trackRecorder.points.collectAsState()
    val trackPersistMessage by vm.trackRecorder.persistError.collectAsState()
    val trackPersistError = trackPersistMessage?.text
    val mapSource by vm.mapSource.collectAsState()
    val mapSelectionPersistenceIssue by vm.mapSelectionPersistenceIssue.collectAsState()
    val calibrationUi by vm.calibration.state.collectAsState()
    val calibrating = calibrationUi != null
    val calibrationBridge = remember { CalibrationMapBridge() }
    val libraryState by vm.libraryState.collectAsState()
    val libraryStatus by vm.libraryStatus.collectAsState()
    val draftCounts by vm.draftCounts.collectAsState()
    val hashMismatch by vm.hashMismatch.collectAsState()
    val launchAlert by vm.launchAlert.collectAsState()
    val importProgress by vm.importProgress.collectAsState()
    val pagePicker by vm.pagePicker.collectAsState()
    val rejectedPrompt by vm.rejectedPrompt.collectAsState()

    /// Is anything on screen actually pulling tiles off the internet right now?
    /// Only the online raster styles (Esri/OSM) do; offline packs and PDFs don't.
    val onlineTilesActive = onlineBasemapsEnabled &&
        mapSource is com.tacmap.calibration.OnlineRasterMapSourceAndroid
    /// An imported, location-bound basemap (MBTiles pack or PDF/GeoPDF). These
    /// have coverage, so they get the "Centre on Map" button + green banner tag.
    val importedMapLoaded = mapSource is com.tacmap.calibration.OfflineTileMapSourceAndroid ||
        mapSource is com.tacmap.calibration.PdfMapSource
    /// Basemap status shown in the MGRS banner (replaces Live Location/Map Centre).
    /// A PDF with no real georef sits on a made up placement; say so every time it's
    /// on screen so nobody reads a grid off it (plan 02 s1, D2-06 / D5-02).
    val uncalibratedPdf = (mapSource as? com.tacmap.calibration.PdfMapSource)?.isGeoreferenced == false
    // WP2 contract G: a PDF that failed to draw or is still drawing says so, never a green
    // "Offline basemap" over a blank map (D5-16)
    val pdfRenderStatus by vm.pdfRuntime.status.collectAsState()
    val pdfPreparingShown by vm.pdfRuntime.showPreparingLabel.collectAsState()
    val pdfShown = mapSource is com.tacmap.calibration.PdfMapSource
    val pdfRenderFailed = pdfShown && pdfRenderStatus is com.tacmap.map.render.pdf.PdfRenderStatus.Failed
    val pdfDrawing = pdfShown && pdfPreparingShown &&
        pdfRenderStatus == com.tacmap.map.render.pdf.PdfRenderStatus.Preparing
    val basemapLabel: String? = when {
        // calibration owns the header (s7.8), the failure alert still comes up over it
        calibrating -> Messages.calibrationHeaderLabel()
        pdfRenderFailed -> Messages.pdfRenderFailedLabel()
        pdfDrawing -> Messages.pdfRenderDrawingLabel()
        uncalibratedPdf -> Messages.pdfMapUncalibratedLabel()
        importedMapLoaded -> L10n.text("Offline basemap")
        onlineTilesActive -> L10n.text("Online basemap")
        else -> null
    }
    val basemapColor = when {
        calibrating -> Color(0xFFFFB300)
        pdfRenderFailed -> Color(com.tacmap.map.render.pdf.PdfRenderRules.FAILED_COLOR)
        pdfDrawing -> Color(com.tacmap.map.render.pdf.PdfRenderRules.PREPARING_COLOR)
        uncalibratedPdf -> Color(0xFFFFB300)
        importedMapLoaded -> Color(com.tacmap.map.render.pdf.PdfRenderRules.READY_COLOR)
        else -> Color(0xFFFF5A5A)
    }
    val waypointStore = remember(unitSyncForegroundEpoch) { WaypointStore(context) }
    val waypoints by waypointStore.waypoints.collectAsState()
    val drawingStore = remember(unitSyncForegroundEpoch) { DrawingStore(context) }
    val ringFollower = remember(waypointStore, drawingStore) {
        com.tacmap.drawings.RangeRingFollower(drawingStore).also { follower ->
            waypointStore.committedChangeListener = follower::onWaypointsCommitted
        }
    }
    // Symbols and drawings share one undo order, like iOS's single UndoManager.
    val undoHistory = remember(waypointStore, drawingStore) {
        MissionUndoHistory().also { history ->
            waypointStore.undoStepListener = { history.recorded(UndoTarget.SYMBOLS) }
            drawingStore.undoStepListener = { history.recorded(UndoTarget.DRAWINGS) }
        }
    }
    val importIdentityJournal = remember {
        com.tacmap.export.ExternalImportIdentityJournal(context)
    }
    val documentCopyJournal = remember { DocumentImportCopyJournal(context) }
    val drawingDocument by drawingStore.document.collectAsState()
    val canUndo by undoHistory.canUndo.collectAsState()
    val canRedo by undoHistory.canRedo.collectAsState()
    val waypointDataLocked by waypointStore.locked.collectAsState()
    val drawingDataLocked by drawingStore.locked.collectAsState()
    val waypointStoreMessage by waypointStore.loadError.collectAsState()
    val waypointStoreError = waypointStoreMessage?.text
    val drawingStoreMessage by drawingStore.loadError.collectAsState()
    val drawingStoreError = drawingStoreMessage?.text
    val lastLocation by vm.locationService.lastLocation.collectAsState()
    val distanceFromUserToCrosshair = lastLocation?.let { location ->
        crosshairDistanceMetres(
            userLat = location.latitude,
            userLng = location.longitude,
            crosshairLat = cameraLat,
            crosshairLng = cameraLng
        )
    }
    val primaryCoordinateDisplay = resolvePrimaryCoordinateDisplay(
        preference = primaryCoordinateType,
        mgrs = vm.headerMgrs,
        wgs84 = vm.headerWgs84,
        utm = vm.headerUtm,
    )
    val selectedWaypointId by vm.selectedWaypointId.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showWaypointSheet by remember { mutableStateOf(false) }
    var showDrawingSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showLayersSheet by remember { mutableStateOf(false) }
    var showImportExportSheet by remember { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<ExportPreviewKind?>(null) }
    var showOpsecSettings by remember { mutableStateOf(false) }
    var showDiscardTrackConfirmation by remember { mutableStateOf(false) }
    var hamburgerOpen by remember { mutableStateOf(false) }
    var quickAddMenuOpen by remember { mutableStateOf(false) }
    var quickAddTarget by remember { mutableStateOf<QuickAddTarget?>(null) }
    var quickAddEditorMode by remember { mutableStateOf<SymbolEditorMode?>(null) }
    var mapPressPoint by remember { mutableStateOf<MapPressPoint?>(null) }
    var profilePath by remember { mutableStateOf<List<ElevationProfile.Coordinate>?>(null) }
    var showTips by rememberSaveable { mutableStateOf(FirstRunTips.shouldShow(context)) }
    val tourTargets = remember { TourTargets() }
    var pointSunMoon by remember { mutableStateOf<MapPressPoint?>(null) }
    var pointRingsCentre by remember { mutableStateOf<Waypoint?>(null) }
    var quickAddCreationError by remember { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
    /// weather/UAV widget target = (lat, lng) of map centre, null when closed
    var weatherTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showAppLockSetup by remember { mutableStateOf(false) }
    var showSyncDialog by remember { mutableStateOf(false) }
    var chatTarget by remember { mutableStateOf<com.tacmap.sync.TacMapChatTarget?>(null) }
    val unitSyncLease = remember(unitSyncRuntime, waypointStore, drawingStore) {
        unitSyncRuntime.acquireForeground(
            waypointStore = waypointStore,
            drawingStore = drawingStore,
            parentScope = scope,
            locationProvider = { vm.locationService.lastLocation.value },
        )
    }
    val syncManager = unitSyncLease.manager
    DisposableEffect(unitSyncLease) {
        onDispose { unitSyncRuntime.releaseScreen(unitSyncLease) }
    }
    DisposableEffect(lifecycleOwner, unitSyncLease) {
        var active = true
        fun reattachAfterActivityResume() {
            scope.launch {
                // MainActivity unwraps the mission key after super.onResume().
                // Yield past that callback before handing stores back to Sync.
                kotlinx.coroutines.yield()
                if (active) {
                    unitSyncRuntime.reattachRetainedScreen(
                        lease = unitSyncLease,
                        waypointStore = waypointStore,
                        drawingStore = drawingStore,
                        locationProvider = { vm.locationService.lastLocation.value },
                    )
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) reattachAfterActivityResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            reattachAfterActivityResume()
        }
        onDispose {
            active = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    val syncStatus by syncManager.status.collectAsState()
    val syncRoom by syncManager.room.collectAsState()
    val unreadChatMessageCount by syncManager.unreadChatMessageCount.collectAsState()
    val presencePeers by syncManager.peers.collectAsState()

    // Snackbar for remote sync conflict notifications (Fix #3).
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(syncManager, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            syncManager.remoteUpdates.collect { msg ->
                snackbarHostState.showSnackbar(
                    msg,
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                )
            }
        }
    }
    val mapRecoveryText = mapSelectionPersistenceIssue?.message
    LaunchedEffect(mapSelectionPersistenceIssue?.id, mapRecoveryText) {
        val issue = mapSelectionPersistenceIssue ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = issue.message,
            actionLabel = L10n.text("Retry"),
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Indefinite,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            vm.retryMapSelectionPersistence()
        }
    }

    /// Generate Offline Tiles lives at app scope, this screen just shows it
    val bakeState by vm.bakeManager.state.collectAsState()
    val pdfRecovery by vm.pdfRecovery.collectAsState()
    val pdfLaunchNotices by vm.pdfLaunchNotices.collectAsState()
    /// the render failure the user already said Not Now to, so the alert doesn't nag
    var dismissedRenderFailure by remember { mutableStateOf<String?>(null) }
    var confirmDeleteSuspect by remember { mutableStateOf(false) }
    var pdfRetryTick by remember { mutableIntStateOf(0) }
    /// lock toggle - when true no graphic can be moved. Extra guard
    /// against accidental drags in the field.
    var graphicsLocked by remember { mutableStateOf(false) }
    var activeDrawingLayerId by remember { mutableStateOf(DrawingDocument.DEFAULT_LAYER_ID) }
    val measureSession = remember { MeasureSession() }
    // persisted to SharedPrefs so layer toggles survive app relaunch
    // (previously plain remember{} that reset every launch, annoying)
    var unitLabelsVisible by rememberPersistedBoolean("unitLabels", false)
    var unitAmplifiersVisible by rememberPersistedBoolean("unitAmplifiers", true)
    var taskLabelsVisible by rememberPersistedBoolean("taskLabels", false)
    var drawingLabelsVisible by rememberPersistedBoolean("drawingLabels", false)
    var symbologyVisible by rememberPersistedBoolean("symbologyVisible", true)
    var drawingsVisible by rememberPersistedBoolean("drawingsVisible", true)
    var mgrsGridVisible by rememberPersistedBoolean(MGRS_GRID_VISIBLE_KEY, false)
    var terrainHeatmapVisible by rememberPersistedBoolean("terrainHeatmap", false)
    var userLocationVisible by rememberPersistedBoolean("userLocation", true)
    var importedMapVisible by rememberPersistedBoolean(IMPORTED_MAP_VISIBLE_KEY, true)
    var activeDrawTool by remember { mutableStateOf<DrawingGeometry?>(null) }
    var isFreeDrawMode by remember { mutableStateOf(false) }
    var draftGeometry by remember { mutableStateOf<DrawingGeometry?>(null) }
    var draftPoints by remember { mutableStateOf<List<DrawingPoint>>(emptyList()) }
    var selectedDrawingId by remember { mutableStateOf<String?>(null) }
    /// the running document import (copy / inspect), so the progress card can cancel it
    var importJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var importCancelRequested by remember { mutableStateOf(false) }
    /// "Choose page..." on a calibrated map: the picked page waits on the confirm (E1)
    var pendingChangePage by remember { mutableStateOf<Pair<PreparedPdfImport, Int>?>(null) }
    /// OD-F5: an import that failed says why in an alert with OK, not a toast that truncates
    var importAlert by rememberSaveable { mutableStateOf<String?>(null) }
    var activeDrawingName by remember { mutableStateOf("") }
    var activeStrokeColor by remember { mutableIntStateOf(DrawingDefaults.DEFAULT_COLOR) }
    var activeStrokeStyle by remember { mutableStateOf(DrawingStrokeStyle.SOLID) }
    // Area fill is chosen independently of the stroke and kept between
    // drawings, like the iOS drawing session.
    var activeFillColor by remember { mutableIntStateOf(DrawingDefaults.DEFAULT_COLOR) }
    var activeFillAlpha by remember { mutableIntStateOf(DrawingDefaults.DEFAULT_FILL_ALPHA) }
    var pendingDrawingMutation by remember { mutableStateOf<PendingDrawingMutation?>(null) }

    fun checkedDrawingMutation(
        intent: DrawingMutationIntent,
        persist: () -> Boolean,
    ): DrawingMutationUiResult {
        val result = DrawingMutationUiCoordinator.attempt(intent, persist)
        // The checked UI outcome owns this mutation failure. Avoid presenting a
        // second generic store alert over its actionable Retry surface.
        drawingStore.acknowledgeLoadError()
        return result
    }

    fun performDrawingMutation(
        intent: DrawingMutationIntent,
        persist: () -> Boolean,
        onSaved: () -> Unit = {},
    ): DrawingMutationUiResult {
        val result = checkedDrawingMutation(intent, persist)
        if (result.saved) {
            pendingDrawingMutation = null
            onSaved()
        } else {
            pendingDrawingMutation = PendingDrawingMutation(
                intent = intent,
                pendingMessage = (result as DrawingMutationUiResult.Failed).pendingMessage,
                persist = persist,
                onSaved = onSaved,
            )
        }
        return result
    }

    val hasPreciseLocation = liveMapLocationState == LiveMapLocationState.Precise
    val liveLocationControl = LiveMapLocationPermissionPolicy.controlFor(liveMapLocationState)

    DisposableEffect(lifecycleOwner, hasPreciseLocation) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (hasPreciseLocation) vm.locationService.start()
                }
                Lifecycle.Event.ON_STOP -> vm.locationService.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (hasPreciseLocation &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            vm.locationService.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.locationService.stop()
        }
    }

    LaunchedEffect(mapOrientationMode, vm.headingService.isHeadingAvailable) {
        if (mapOrientationMode == MapOrientationMode.HEADING_UP &&
            !vm.headingService.isHeadingAvailable
        ) {
            vm.opsec.setMapOrientationMode(MapOrientationMode.NORTH_UP)
        } else if (mapOrientationMode == MapOrientationMode.NORTH_UP) {
            vm.requestResetNorth()
        }
    }

    DisposableEffect(lifecycleOwner, mapOrientationMode) {
        fun startHeadingOrFallBack() {
            headingSessionActive = vm.headingService.start()
            if (!headingSessionActive) {
                vm.opsec.setMapOrientationMode(MapOrientationMode.NORTH_UP)
                Toast.makeText(
                    context,
                    L10n.text("Compass heading unavailable; switched to North Up."),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (mapOrientationMode == MapOrientationMode.HEADING_UP) {
                        startHeadingOrFallBack()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    headingSessionActive = false
                    vm.headingService.stop()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (mapOrientationMode == MapOrientationMode.HEADING_UP &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            startHeadingOrFallBack()
        } else {
            headingSessionActive = false
            vm.headingService.stop()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            headingSessionActive = false
            vm.headingService.stop()
        }
    }

    LaunchedEffect(mapOrientationMode, headingSessionActive) {
        if (mapOrientationMode == MapOrientationMode.HEADING_UP &&
            headingSessionActive
        ) {
            delay(5_000)
            if (vm.opsec.mapOrientationMode.value == MapOrientationMode.HEADING_UP &&
                headingSessionActive &&
                vm.headingService.headingDegrees.value == null
            ) {
                vm.opsec.setMapOrientationMode(MapOrientationMode.NORTH_UP)
                Toast.makeText(
                    context,
                    L10n.text("No reliable compass reading; switched to North Up."),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    // the PDF went away (online, MBTiles): the runtime lets go of its page + tiles
    LaunchedEffect(mapSource.id) {
        if (mapSource !is PdfMapSource) vm.pdfRuntime.release()
    }

    val selected = waypoints.firstOrNull { it.id == selectedWaypointId }
    val selectedDrawing = drawingDocument.features.firstOrNull { it.id == selectedDrawingId }
    val pdfSource = mapSource as? PdfMapSource
    val safeActiveLayerId = drawingDocument.layers.firstOrNull { it.id == activeDrawingLayerId }?.id
        ?: drawingDocument.layers.firstOrNull()?.id
        ?: DrawingDocument.DEFAULT_LAYER_ID
    val quickAddLayerId = drawingDocument.layers
        .firstOrNull { it.id == activeDrawingLayerId && it.isVisible }
        ?.id
        ?: drawingDocument.layers.firstOrNull { it.isVisible }?.id
        ?: safeActiveLayerId
    val quickAddAllowed = !calibrating &&
        !measureSession.isActive &&
        activeDrawTool == null &&
        !graphicsLocked &&
        !waypointDataLocked &&
        !drawingDataLocked
    LaunchedEffect(quickAddAllowed) {
        if (!quickAddAllowed) {
            quickAddMenuOpen = false
            quickAddEditorMode = null
            quickAddTarget = null
            quickAddCreationError = null
        }
    }
    // The measure line and its point dots draw in their own overlay (see
    // CustomMapScreen.measurePoints), so hiding drawings never hides them.
    val draftDrawing = when {
        draftGeometry != null -> newMapDrawingFeature(
            name = drawingNameOrDefault(activeDrawingName, draftGeometry!!, drawingDocument.features),
            geometry = draftGeometry!!,
            points = draftPoints,
            layerId = safeActiveLayerId,
            strokeColor = activeStrokeColor,
            fillColor = activeFillColor.withAlpha(activeFillAlpha),
            strokeStyle = activeStrokeStyle,
            density = rendererDensity,
        )
        else -> null
    }

    /** New drawings inherit the active layer's colour (e.g. Hostile starts
     *  red), as on iOS; the draft bar can still change it. */
    fun useActiveLayerStrokeColor() {
        (drawingDocument.layers.firstOrNull { it.id == activeDrawingLayerId } ?: drawingDocument.layers.firstOrNull())
            ?.let { activeStrokeColor = it.color or 0xFF000000.toInt() }
    }

    fun stopDrawing() {
        activeDrawTool = null
        isFreeDrawMode = false
        draftGeometry = null
        draftPoints = emptyList()
        activeDrawingName = ""
    }

    fun finishDraft(extraPoint: DrawingPoint? = null) {
        val geometry = draftGeometry ?: return
        val points = (extraPoint?.let { draftPoints + it } ?: draftPoints).dedupeTrailingPoints()
        if (points.size >= geometry.minimumVertices) {
            val candidate = newMapDrawingFeature(
                    name = drawingNameOrDefault(activeDrawingName, geometry, drawingDocument.features),
                    geometry = geometry,
                    points = points,
                    layerId = safeActiveLayerId,
                    strokeColor = activeStrokeColor,
                    fillColor = activeFillColor.withAlpha(activeFillAlpha),
                    strokeStyle = activeStrokeStyle,
                    density = rendererDensity,
                )
            performDrawingMutation(
                intent = DrawingMutationIntent.CREATE,
                persist = { drawingStore.addFeature(candidate) },
                onSaved = ::stopDrawing,
            )
        }
    }

    fun handleDrawingTap(lat: Double, lng: Double) {
        // measure-mode tap intercepted here too - when active it grabs taps
        // before drawing branch so user can lay down a route without
        // picking a draw tool
        if (measureSession.isActive) {
            measureSession.addPoint(lat, lng)
            return
        }
        val tool = activeDrawTool ?: return
        vm.selectWaypoint(null)
        selectedDrawingId = null
        val point = DrawingPoint(lat, lng)
        when (tool) {
            DrawingGeometry.POINT -> {
                val candidate = newMapDrawingFeature(
                        name = drawingNameOrDefault(
                            activeDrawingName,
                            DrawingGeometry.POINT,
                            drawingDocument.features
                        ),
                        geometry = DrawingGeometry.POINT,
                        points = listOf(point),
                        layerId = safeActiveLayerId,
                        strokeColor = activeStrokeColor,
                        fillColor = activeStrokeColor.withAlpha(0x33),
                        strokeStyle = activeStrokeStyle,
                        density = rendererDensity,
                    )
                performDrawingMutation(
                    intent = DrawingMutationIntent.CREATE,
                    persist = { drawingStore.addFeature(candidate) },
                )
            }
            DrawingGeometry.LINE, DrawingGeometry.POLYGON -> {
                draftGeometry = tool
                draftPoints = (draftPoints + point).dedupeTrailingPoints()
            }
        }
    }

    /**
     * Contract s2.3 steps 1-3 here (end measure/draw, clear selection, close menus),
     * the rest (heading-up pause, browse mode, preview publish, seed) in the VM.
     */
    fun beginCalibration(entryId: String): Boolean {
        stopDrawing()
        measureSession.cancel()
        vm.selectWaypoint(null)
        if (selectedDrawingId != null) drawingStore.revertPreview()
        selectedDrawingId = null
        mapPressPoint = null
        quickAddMenuOpen = false
        quickAddEditorMode = null
        quickAddTarget = null
        hamburgerOpen = false
        showLayersSheet = false
        return vm.startCalibration(entryId)
    }

    // the live camera through the bridge; the VM's copy of the camera if the map isn't up
    fun captureNow(): CalibrationCapture? = calibrationUi?.let { ui ->
        calibrationBridge.capture(ui.state.sheet)
            ?: cameraViewportState?.toCamera()?.let { CalibrationCameraAnchor.capture(ui.display.georef, ui.state.sheet, it) }
    }

    fun leaveCalibration() {
        if (vm.calibration.leaveTapped()) vm.endCalibrationPreview()
    }

    // Android Back = the ✕ (s2.7)
    BackHandler(enabled = calibrating) { leaveCalibration() }

    // a pause can't lose points: drafts write synchronously, but a write that failed
    // because the key was locked gets another go once we're back
    LaunchedEffect(calibrationUi?.draftUnsaved) {
        if (calibrationUi?.draftUnsaved == true) vm.calibration.retryDraftWrite()
    }
    // this screen only exists with the mission key unlocked, so being (re)composed is the
    // unlock: a library that was locked gets restored now (F3)
    LaunchedEffect(Unit) { vm.onMissionDataUnlocked() }
    LaunchedEffect(Unit) {
        vm.calibration.events.collect { e ->
            val text = when (e) {
                is CalibrationEvent.PointDeleted -> Messages.calibrationPointDeleted(e.number.toString())
                is CalibrationEvent.DatumChanged -> Messages.calibrationDatumChanged(CalibrationText.datumName(e.datumId))
                is CalibrationEvent.Resumed -> Messages.calibrationResumed(Messages.calibrationPointCount(e.points))
                CalibrationEvent.Paused -> {
                    vm.endCalibrationPreview()
                    Messages.calibrationPaused()
                }
                is CalibrationEvent.Done -> Messages.calibrationDone(Messages.calibrationPointCount(e.points), CalibrationText.rmsText(e.rmsM))
                CalibrationEvent.DoneExact -> Messages.calibrationDoneExact()
                CalibrationEvent.MaxPoints -> Messages.calibrationMaxPoints(com.tacmap.calibration.fiducial.CalibrationState.MAX_POINTS.toString())
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    val importPipeline = remember(documentCopyJournal) { MapImportPipeline(context.applicationContext, documentCopyJournal) }

    fun fileSize(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

    // OD-F6: limits and needed space go through the shared size rule, "537 MB" on both apps
    fun importFailureText(f: ImportFailure): String = CalibrationText.importFailure(f) { ImportUiRules.byteText(it) }

    fun librarySnapshot(): LibrarySnapshot {
        val st = vm.libraryState.value
        return LibrarySnapshot(
            loaded = vm.libraryStatus.value == LibraryStatus.LOADED && st != null,
            entryCount = st?.entries?.size ?: 0,
            byContentKey = { key -> st?.byContentKey(key) },
            isUnavailable = { e -> vm.fileStatus(e) != com.tacmap.calibration.EntryFileStatus.OK },
        )
    }

    fun showImportNotice(n: ImportNotice) {
        when (n) {
            is ImportNotice.Toast -> Toast.makeText(context, CalibrationText.text(n.message), Toast.LENGTH_LONG).show()
            is ImportNotice.Alert -> importAlert = n.text
        }
    }

    /**
     * P1 "Choose page...": read the entry's own file again (off main, watchdog, the
     * progress card + Cancel), then the same page picker an import uses
     */
    fun choosePageFor(id: String) {
        val entry = vm.libraryState.value?.entry(id) ?: return
        val file = vm.fileOf(entry) ?: return
        val key = entry.contentKey ?: return
        if (importJob != null || importProgress != null) return
        importCancelRequested = false
        val job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { importPipeline.reinspect(file) { vm.setImportProgress(it) } }
                when (result) {
                    is InspectionResult.Ok -> vm.showPagePicker(
                        PreparedPdfImport(
                            file = file,
                            contentKey = key,
                            displayName = entry.displayName,
                            inspection = result.inspection,
                            outcome = ImportOutcome.PagePicker(MapImportPipeline.rejectedBadges(result.inspection)),
                            existingEntryId = id,
                        )
                    )
                    is InspectionResult.Failed -> importAlert = importFailureText(result.failure)
                    null -> importAlert = Messages.mapImportTooComplex()
                }
            } catch (cancelled: CancellationException) {
                if (importCancelRequested) Toast.makeText(context, Messages.mapImportCancelled(), Toast.LENGTH_SHORT).show()
                throw cancelled
            } finally {
                vm.setImportProgress(null)
                importJob = null
                importCancelRequested = false
            }
        }
        importJob = job
    }

    /** the picked page's write, after any confirm: manual, bake + old draft go (s8.2 change page) */
    fun writeChosenPage(prepared: PreparedPdfImport, page: Int) {
        val id = prepared.existingEntryId ?: return
        scope.launch {
            val inspected = prepared.inspection.page(page) ?: return@launch
            val geometry = inspected.geometry ?: withContext(Dispatchers.IO) {
                com.tacmap.calibration.PdfInspector.geometryFor(prepared.file, inspected)?.first
            }
            val info = MapImportPipeline.pdfInfo(prepared.inspection.pageCount, inspected, geometry) ?: return@launch
            val changed = vm.changeImportedMapPage(id, info.pageIndex, info.rotate, info.pageBox, info.embedded, info.embeddedIssue, info.geometry)
            if (!changed) return@launch
            // E4: a page with its own georef is activated (framed), unless the write kept it active already
            if (info.embedded != null) {
                if (vm.libraryState.value?.active?.entryId != id) vm.activateImportedMap(id)
            } else {
                beginCalibration(id)
            }
        }
    }

    /** Layers Choose page: same page is a no-op, a hand calibration asks first (E1, lifecycle.choosePage) */
    fun applyChosenPage(prepared: PreparedPdfImport, page: Int) {
        val id = prepared.existingEntryId ?: return
        vm.dismissPagePicker(keepFile = true)
        val st = vm.libraryState.value ?: return
        val entry = st.entry(id) ?: return
        val decision = ChoosePageRules.decide(
            currentPage = entry.pdf?.pageIndex ?: 0,
            pickedPage = page,
            hasManual = entry.pdf?.manual != null,
            pickedHasValidGeoref = prepared.inspection.page(page)?.georef is com.tacmap.calibration.GeoPdfGeorefResult.Georeferenced,
            entryIsActive = st.active.entryId == id,
        )
        if (!decision.write) return
        if (decision.confirm != null) pendingChangePage = prepared to page else writeChosenPage(prepared, page)
    }

    /** WP2 import probe (M12) on the page about to be committed, before the library write */
    fun importProbeStep(operationKey: String) = ImportProbeStep(
        sourceFor = vm::probeSourceFor,
        probe = { probeImportedPdf(context, it, operationKey, vm.pdfRuntime.guard) },
        deleteCopy = { f -> withContext(Dispatchers.IO) { runCatching { f.delete() } } },
        fallbackReason = { Messages.pdfRenderReasonRenderError() },
    )

    // built per commit on purpose: beginCalibration closes over this composition's state
    fun importCommitTarget(): ImportCommitTarget =
        object : ImportCommitTarget {
            override fun addEntry(entry: ImportedMapEntry, activate: Boolean) = vm.addImportedEntry(entry, activate)
            override fun activate(id: String) = vm.activateImportedMap(id)
            override fun relink(id: String, file: java.io.File) = vm.relinkImportedMap(id, file)
            override fun showRejectedPrompt(id: String, reason: com.tacmap.calibration.GeorefRejectReason) = vm.showRejectedPrompt(id, reason)
            override fun showPagePicker(prepared: PreparedPdfImport) = vm.showPagePicker(prepared)
            override fun calibrate(id: String) { beginCalibration(id) }
        }

    fun importCommitter(operationKey: String) = PdfImportCommitter(
        target = importCommitTarget(),
        probe = importProbeStep(operationKey),
        newEntry = { prepared, page -> MapImportPipeline.pdfEntry(prepared, page, page.geometry, context.filesDir, System.currentTimeMillis()) },
        notify = ::showImportNotice,
    )

    /** a prepared PDF lands in the library (main thread), s9.6 */
    suspend fun commitPreparedPdf(prepared: PreparedPdfImport, operationKey: String) {
        importCommitter(operationKey).commit(prepared)
    }

    suspend fun runMapImport(uri: Uri, kind: DocumentImportKind, operationKey: String) {
        val snapshot = librarySnapshot()
        val outcome = withContext(Dispatchers.IO) {
            if (kind == DocumentImportKind.PDF) {
                importPipeline.runPdf(uri, operationKey, snapshot) { vm.setImportProgress(it) }
            } else {
                importPipeline.runMbtiles(uri, operationKey, snapshot) { vm.setImportProgress(it) }
            }
        }
        vm.setImportProgress(ImportProgress.Saving)
        try {
            when (outcome) {
                is PreparedOutcome.Failed -> {
                    // the launch sweep already said so in a dialog, once is enough
                    val alreadyTold = outcome.failure.error == ImportError.INTERRUPTED &&
                        vm.launchAlert.value == MapLaunchAlert.ImportInterrupted
                    if (!alreadyTold) importAlert = importFailureText(outcome.failure)
                }
                is PreparedOutcome.Pdf -> commitPreparedPdf(outcome.prepared, operationKey)
                is PreparedOutcome.Mbtiles -> {
                    val prepared = outcome.prepared
                    val existing = prepared.duplicate
                    if (existing != null) {
                        // E9: an unavailable pack takes the new (identical) copy first
                        if (!prepared.relink || vm.relinkImportedMap(existing.id, prepared.file)) {
                            vm.activateImportedMap(existing.id)
                            Toast.makeText(context, Messages.mapImportDuplicate(existing.displayName), Toast.LENGTH_LONG).show()
                        }
                    } else {
                        MapImportPipeline.mbtilesEntry(prepared, context.filesDir, System.currentTimeMillis())
                            ?.let { vm.addImportedEntry(it, activate = true) }
                    }
                    InFlightImportFiles.release(prepared.file)
                }
            }
        } finally {
            vm.setImportProgress(null)
        }
    }

    suspend fun importExternalObjects(uri: Uri, kind: DocumentImportKind) {
        val fallback = drawingDocument.layers
            .firstOrNull { it.id == activeDrawingLayerId }?.id
            ?: drawingDocument.layers.firstOrNull()?.id
            ?: DrawingDocument.DEFAULT_LAYER_ID
        val existingLayers = drawingDocument.layers.toList()
        val occupied = waypoints.map {
            com.tacmap.export.OccupiedExternalImportIdentity(
                com.tacmap.export.ExternalImportObjectKind.WAYPOINT,
                it.id,
            )
        } + drawingDocument.features.map {
            com.tacmap.export.OccupiedExternalImportIdentity(
                com.tacmap.export.ExternalImportObjectKind.DRAWING,
                it.id,
            )
        }
        val initiallyResolved = withContext(Dispatchers.IO) {
            val bytes = readBoundedExternalImport(context.contentResolver.openInputStream(uri))
            val parsed = when (kind) {
                DocumentImportKind.GEO_JSON -> com.tacmap.export.GeoJsonImporter.parseStream(
                    input = ByteArrayInputStream(bytes),
                    existingLayers = existingLayers,
                    fallbackLayerId = fallback,
                    density = context.resources.displayMetrics.density,
                )
                DocumentImportKind.KML -> com.tacmap.export.KmlImporter.parseStream(
                    input = ByteArrayInputStream(bytes),
                    existingLayers = existingLayers,
                    fallbackLayerId = fallback,
                    density = context.resources.displayMetrics.density,
                )
                else -> error("$kind is not an object import")
            }
            val batchKey = com.tacmap.export.ExternalImportIdentityJournal.batchKey(
                kind.savedValue,
                bytes,
            )
            batchKey to importIdentityJournal.resolveAndPersist(
                batchKey = batchKey,
                parsed = parsed,
                occupied = occupied,
            )
        }
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            "External import commit reconciliation must run on the main thread"
        }
        val (batchKey, preliminary) = initiallyResolved
        val liveWaypoints = waypointStore.committedWaypoints.value
        val liveDrawings = drawingStore.committedDocument.value.features
        val reconciled = importIdentityJournal.reconcileAndPersist(
            batchKey = batchKey,
            resolved = preliminary,
            liveWaypoints = liveWaypoints,
            liveDrawings = liveDrawings,
        )
        val commit = com.tacmap.export.commitExternalImport(
            imported = reconciled.result,
            commitWaypoints = { incoming ->
                val before = waypointStore.committedWaypoints.value
                    .mapTo(HashSet()) { it.id.lowercase() }
                val saved = waypointStore.addAll(incoming)
                com.tacmap.export.ExternalImportStoreCommit(
                    succeeded = saved,
                    insertedCount = if (saved) incoming.count { it.id.lowercase() !in before } else 0,
                )
            },
            commitDrawings = { layers, incoming ->
                val before = drawingStore.committedDocument.value.features
                    .mapTo(HashSet()) { it.id.lowercase() }
                val saved = drawingStore.addImported(layers, incoming)
                com.tacmap.export.ExternalImportStoreCommit(
                    succeeded = saved,
                    insertedCount = if (saved) incoming.count { it.id.lowercase() !in before } else 0,
                )
            },
        )
        val collisions = reconciled.identities.consumedRemintIds.size
        val resultMessage = if (collisions > 0) Messages.importCollisionSummaryMessage("", DisplayFormat.number(collisions.toDouble(), 0))
            .withArgument(0, commit.pendingMessage) else commit.pendingMessage
        Toast.makeText(
            context,
            resultMessage.text,
            if (commit.succeeded) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
    }

    suspend fun processDocumentImport(pending: PendingDocumentImport) {
        val uri = Uri.parse(pending.uri)
        when (pending.kind) {
            DocumentImportKind.SYMBOL_PACK -> {
                val pack = withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openInputStream(uri)).use {
                        com.tacmap.waypoints.CustomSymbolStore.preparePack(it)
                    }
                }
                // Commit only after the live, unlocked composition resumes on Main.
                com.tacmap.waypoints.CustomSymbolStore.installPack(pack)
                Toast.makeText(context, Messages.symbolsPackImported(pack.name), Toast.LENGTH_LONG).show()
            }
            DocumentImportKind.PDF -> runMapImport(uri, pending.kind, "pdf:${pending.token}")
            DocumentImportKind.MBTILES -> runMapImport(uri, pending.kind, "mbtiles:${pending.token}")
            DocumentImportKind.GEO_JSON, DocumentImportKind.KML ->
                importExternalObjects(uri, pending.kind)
        }
    }

    // The Activity owns and persists picker results. Claim exactly once while
    // this composition is alive; cancellation abandons the claim for the next
    // rebuilt MapScreen, while every terminal result releases the URI grant.
    LaunchedEffect(pendingDocumentImport?.token) {
        val pending = pendingDocumentImport ?: return@LaunchedEffect
        if (!onClaimDocumentImport(pending.token)) return@LaunchedEffect
        val interrupted = vm.interruptedImportOperationKey
        if (pending.kind == DocumentImportKind.PDF && interrupted == "pdf:${pending.token}") {
            // this exact import took the app down during its probe, replaying it would loop.
            // drop it, the launch notice says what happened
            onCompleteDocumentImport(pending.token)
            return@LaunchedEffect
        }
        var terminal = false
        importCancelRequested = false
        // a child job so the progress card's Cancel can stop just this import; a pause
        // tearing MapScreen down cancels it too, that one abandons and retries on resume
        val job = launch {
            try {
                processDocumentImport(pending)
                terminal = true
            } catch (cancelled: CancellationException) {
                if (importCancelRequested) {
                    terminal = true
                    Toast.makeText(context, Messages.mapImportCancelled(), Toast.LENGTH_SHORT).show()
                }
                throw cancelled
            } catch (failure: Exception) {
                // Exception only: an OOM / StackOverflow is caught at the inspector boundary
                // and nowhere else (D5-15), never turned into "imported" or a toast here
                terminal = true
                val mapImport = pending.kind == DocumentImportKind.PDF || pending.kind == DocumentImportKind.MBTILES
                val message = when {
                    pending.kind == DocumentImportKind.SYMBOL_PACK -> Messages.importFailed(Messages.symbolsSymbolPackError())
                    mapImport && pending.kind == DocumentImportKind.MBTILES && failure is IllegalStateException ->
                        Messages.mapImportInvalidMbtiles()
                    // never the raw exception text for a map: it can carry the picked file's path
                    mapImport -> Messages.mapImportReadFailed(failure.javaClass.simpleName)
                    else -> Messages.importFailed(
                        failure.message?.takeIf { it.isNotBlank() } ?: L10n.text("The selected document could not be imported")
                    )
                }
                // OD-F5: a map import that failed gets the alert, the other imports keep their toast
                if (mapImport) importAlert = message else Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
        importJob = job
        try {
            job.join()
        } finally {
            importJob = null
            vm.setImportProgress(null)
            if (terminal) onCompleteDocumentImport(pending.token) else onAbandonDocumentImport(pending.token)
            importCancelRequested = false
        }
    }

    // DEBUG launch hook: the camera goes where the verification script asked, after any
    // hook import has landed and framed itself (docs/DEBUG_HOOKS.md)
    LaunchedEffect(pendingDocumentImport == null, mapSource.id) {
        if (!com.tacmap.BuildConfig.DEBUG || pendingDocumentImport != null) return@LaunchedEffect
        val cam = com.tacmap.app.DebugLaunchHooks.takeCamera() ?: return@LaunchedEffect
        delay(600)
        vm.applyDebugCamera(cam)
    }

    val calibrationMarkerModel: CalibrationMarkerModel? = calibrationUi?.let { ui ->
        val datum = ui.state.datum
        CalibrationMarkerModel(
            markers = ui.state.sortedPoints.map { p ->
                CalibrationMarker(
                    id = p.id,
                    number = p.number,
                    page = p.pagePoint,
                    typed = p.resolved(datum)?.let { r -> datum.toWGS84(r.latitude, r.longitude) }
                        ?.let { com.tacmap.calibration.Wgs84Coordinate(it.latitude, it.longitude) },
                    flagged = p.number in ui.report.flagged,
                )
            },
            selectedId = ui.selectedId,
            pendingPage = ui.pendingPage,
            movingId = (ui.phase as? CalibrationPhase.Moving)?.id,
            showResiduals = ui.report.n >= 3 && ui.display.isFit,
        )
    }

    /** Layers rows off the library (contract s10 + import_limits.json entryStates) */
    fun importedMapsUi(): ImportedMapsUi {
        val st = libraryState
        if (st == null || libraryStatus != LibraryStatus.LOADED) return ImportedMapsUi(emptyList(), null, locked = libraryStatus != LibraryStatus.LOADING)
        val activeId = st.active.entryId
        val byId = st.entries.associateBy { it.id }
        // parents first, their baked tiles indented right under them
        val ordered = st.entries.filter { it.derivedFromId == null || it.derivedFromId !in byId } .flatMap { parent ->
            listOf(parent) + st.entries.filter { it.derivedFromId == parent.id }
        }
        val rows = ordered.map { e ->
            val file = if (e.id in hashMismatch) com.tacmap.calibration.EntryFileStatus.MISMATCH else vm.fileStatus(e)
            val drafts = e.contentKey?.let { key -> e.pdf?.let { draftCounts[com.tacmap.calibration.fiducial.CalibrationTarget.draftKey(key, it.pageIndex)] } }
            val facts = LibraryEntryRules.facts(e, e.derivedFromId?.let { byId[it]?.displayName })
            val pres = LibraryEntryRules.present(facts, file, drafts)
            ImportedMapRowUi(
                id = e.id,
                name = e.displayName,
                subtitle = CalibrationText.text(pres.subtitle),
                sizeText = fileSize(e.byteCount),
                active = e.id == activeId,
                indented = e.derivedFromId != null && e.derivedFromId in byId,
                state = pres.state,
                rowTap = pres.rowTap,
                // J1: once a PDF has its bake only Remove is offered (in the Imported Map block),
                // Generate comes back after that. the pure table still matches the fixture
                menu = if (e.pdf?.validBake != null) pres.menu - EntryMenuAction.GENERATE_TILES else pres.menu,
                generate = ImportUiRules.generateMenu(
                    bakeBusy = bakeState is com.tacmap.calibration.PdfBakeManager.State.Running ||
                        bakeState is com.tacmap.calibration.PdfBakeManager.State.Estimating ||
                        bakeState is com.tacmap.calibration.PdfBakeManager.State.Confirming,
                    bakeRunningForEntry = (bakeState as? com.tacmap.calibration.PdfBakeManager.State.Running)?.token == e.renderGuardToken,
                    onScreenAndFailed = pdfRenderFailed && (mapSource as? PdfMapSource)?.entryId == e.id,
                ),
            )
        }
        // OD-F14 + OD-F6: bake files count, the shared size rule
        return ImportedMapsUi(
            rows = rows,
            footer = Messages.mapLibraryFooter(Messages.importedMapCount(st.entries.size), ImportUiRules.byteText(ImportUiRules.footerBytes(st))),
            locked = false,
        )
    }

    Box(Modifier.fillMaxSize()) {
        CustomMapScreen(
                modifier = Modifier.fillMaxSize(),
                waypoints = waypoints,
                mapSource = mapSource,
                tileCache = vm.tileCache,
                pdfTileSourceFor = (mapSource as? PdfMapSource)?.let { pdf ->
                    { georef: com.tacmap.calibration.PdfGeoreference? -> vm.pdfTileSourceFor(pdf, georef, rendererDensity) }
                },
                pdfRetryKey = pdfRetryTick,
                // forced on while calibrating, you can't place points on a hidden sheet
                importedMapHidden = !(importedMapVisible || calibrating),
                pdfRenderReady = pdfRenderStatus == com.tacmap.map.render.pdf.PdfRenderStatus.Ready,
                onlineBasemapsEnabled = onlineBasemapsEnabled,
                onlineLookupsEnabled = onlineLookupsEnabled,
                drawings = drawingDocument.features,
                drawingLayers = drawingDocument.layers,
                draftDrawing = draftDrawing,
                measurePoints = if (measureSession.isActive) measureSession.points.toList() else emptyList(),
                graphicsLocked = graphicsLocked,
                userLocationVisible = userLocationVisible,
                myLat = lastLocation?.latitude,
                myLon = lastLocation?.longitude,
                myAccuracyMetres = lastLocation?.accuracy ?: 0f,
                drawingInputEnabled = activeDrawTool != null || measureSession.isActive,
                freeDrawActive = isFreeDrawMode,
                onFreeDrawPoint = { lat, lng ->
                    draftPoints = (draftPoints + DrawingPoint(lat, lng)).dedupeTrailingPoints()
                },
                onFreeDrawEnd = {
                    finishDraft()
                },
                calibrationActive = calibrating,
                calibrationDisplay = calibrationUi?.display,
                calibrationMarkers = calibrationMarkerModel,
                calibrationBridge = calibrationBridge,
                onCalibrationMarkerTap = { id ->
                    // a tap never places a point, it only (de)selects one (s7.5), and
                    // selecting flies there at the same zoom + heading (s7.7)
                    vm.calibration.select(id)
                    calibrationUi?.let { ui ->
                        id?.let(ui.state::point)?.let { p -> ui.display.georef.toWGS84(p.pagePoint.x, p.pagePoint.y) }
                            ?.let { w -> vm.requestCentre(w.latitude, w.longitude) }
                    }
                },
                zoomStepRequests = vm.zoomStepRequests,
                centreRequests = vm.centreRequests,
                overlaysHidden = calibrating,
                userLocationHidden = calibrationUi?.display?.provisional == true,
                gridOverride = calibrationUi?.let { it.gridOn && !it.display.provisional },
                mgrsGridVisible = mgrsGridVisible,
                terrainHeatmapVisible = terrainHeatmapVisible,
                unitLabelsVisible = unitLabelsVisible,
                unitAmplifiersVisible = unitAmplifiersVisible,
                taskLabelsVisible = taskLabelsVisible,
                drawingLabelsVisible = drawingLabelsVisible,
                symbologyVisible = symbologyVisible,
                drawingsVisible = drawingsVisible,
                peers = presencePeers,
                selectedDrawingId = selectedDrawingId,
                initialCameraState = cameraViewportState,
                pendingTarget = pendingTarget,
                resetNorthRequests = vm.resetNorthRequests,
                headingRequests = vm.headingRequests,
                // s2.3: heading-up pauses (device heading ignored, rotate gesture on), setting untouched
                headingUpEnabled = mapOrientationMode == MapOrientationMode.HEADING_UP && !calibrating,
                deviceHeadingDegrees = vm.headingService.headingDegrees,
                onConsumePendingTarget = vm::consumePendingCameraTarget,
                onCameraIdle = vm::onCameraIdle,
                onBearingChanged = vm::onMapBearingChanged,
                onMarkerTap = { wp ->
                    selectedDrawingId = null
                    vm.selectWaypoint(wp.id)
                },
                onWaypointMoved = { wp, lat, lng ->
                    if (!waypointStore.update(wp.copy(latitude = lat, longitude = lng))) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                L10n.text("The symbol move could not be saved. Its previous position is still active.")
                            )
                        }
                    }
                },
                onDrawingTap = ::handleDrawingTap,
                onDrawingFeatureTap = { featureId ->
                    vm.selectWaypoint(null)
                    selectedDrawingId = featureId
                },
                onPresencePeerTap = { peer ->
                    val target = syncManager.chatTargetFor(peer.clientId)
                    if (target != null) {
                        chatTarget = target
                    } else {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                L10n.text("That unit is not currently available for secure chat")
                            )
                        }
                    }
                },
                onVertexMoved = { featureId, vertexIndex, lat, lng ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        val candidate = feature.withVertexMoved(vertexIndex, lat, lng)
                        performDrawingMutation(DrawingMutationIntent.EDIT, {
                            drawingStore.updateFeature(candidate)
                        })
                    }
                },
                onVertexInserted = { featureId, atIndex, lat, lng ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        val candidate = feature.withVertexInserted(atIndex, lat, lng)
                        performDrawingMutation(DrawingMutationIntent.EDIT, {
                            drawingStore.updateFeature(candidate)
                        })
                    }
                },
                onShapeMoved = { featureId, deltaLat, deltaLng ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        val candidate = feature.copy(
                                points = feature.points.map { point ->
                                    point.copy(
                                        latitude = point.latitude + deltaLat,
                                        longitude = point.longitude + deltaLng
                                    )
                                }
                            )
                        performDrawingMutation(DrawingMutationIntent.EDIT, {
                            drawingStore.updateFeature(candidate)
                        })
                    }
                },
                onVertexDeleted = { featureId, vertexIndex ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        feature.withVertexRemovedOrNull(vertexIndex)?.let {
                            val candidate = it
                            performDrawingMutation(DrawingMutationIntent.EDIT, {
                                drawingStore.updateFeature(candidate)
                            })
                        }
                    }
                },
                onMapTap = {
                    if (selectedWaypointId != null) vm.selectWaypoint(null)
                    selectedDrawingId = null
                },
                onMapLongPress = { lat, lng, screen ->
                    mapPressPoint = MapPressPoint.at(lat, lng, screen, primaryCoordinateType)
                },
            )
            mapPressPoint?.takeUnless { calibrating }?.let { point ->
                MapPointMenu(
                    point = point,
                    canEdit = quickAddAllowed,
                    onPlaceSymbol = { mode ->
                        mapPressPoint = null
                        quickAddCreationError = null
                        quickAddTarget = QuickAddTarget(point.latitude, point.longitude, quickAddLayerId, fromPointMenu = true)
                        quickAddEditorMode = mode
                    },
                    onMeasure = {
                        mapPressPoint = null
                        stopDrawing()
                        measureSession.start()
                        measureSession.addPoint(point.latitude, point.longitude)
                    },
                    onRangeRings = {
                        mapPressPoint = null
                        pointRingsCentre = Waypoint(
                            name = point.coordinate.text,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            layerId = quickAddLayerId,
                        )
                    },
                    onSunMoon = {
                        mapPressPoint = null
                        pointSunMoon = point
                    },
                    onCopy = {
                        mapPressPoint = null
                        val type = point.coordinate.type.displayName
                        val copied = com.tacmap.util.copySensitivePlainText(
                            context,
                            L10n.text("%1\$s coordinate", type),
                            point.coordinate.text,
                        )
                        Toast.makeText(
                            context,
                            if (copied) L10n.text("%1\$s copied", type) else L10n.text("Unable to copy %1\$s", type.lowercase()),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    onDismiss = { mapPressPoint = null },
                )
            }

        // Crosshair now renders inside CustomMapScreen (under the user-location
        // dot) so the dot isn't swallowed when the map follows the user.

        // MGRS header - anchored to top edge, offset by status-bar inset
        // so dynamic island / hole-punch doesn't cover it. The online-tiles
        // warning stacks above it in the same column, otherwise the header
        // (drawn after the map) would sit on top of the banner and hide it.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        MgrsHeader(
            primaryCoordinate = primaryCoordinateDisplay.text,
            coordinateType = primaryCoordinateDisplay.type,
            // GPS altitude stands in only while the banner reads out your own
            // position, never for a crosshair somewhere else (as on iOS).
            elevation = centreElevation?.metres
                ?: lastLocation?.takeIf { !isBrowsing && it.hasAltitude() }?.altitude,
            elevationApprox = centreElevation?.isStale == true,
            syncConnected = syncStatus == com.tacmap.sync.SyncManager.Status.CONNECTED,
            basemapLabel = basemapLabel,
            basemapColor = basemapColor,
            gridMagneticDegrees = gridMagneticDegrees(
                vm.headerCoordinate.first, vm.headerCoordinate.second, centreElevation?.metres
            ),
            distanceFromUserMetres = distanceFromUserToCrosshair,
            // align + statusBarsPadding moved to the wrapping Column.
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            highlightModifier = Modifier.tourTarget(tourTargets, TourTarget.HEADER),
            calibrationReadout = calibrationUi?.let { ui ->
                if (ui.display.provisional) CalibrationReadout.NotGeoreferenced else CalibrationReadout.Preview(ui.showsPreview)
            },
            onDropPin = {
                val (lat, lng) = vm.headerCoordinate
                val activeLayerId = drawingDocument.layers
                    .firstOrNull { it.isVisible }?.id
                    ?: com.tacmap.drawings.DrawingDocument.DEFAULT_LAYER_ID
                val waypoint = com.tacmap.waypoints.Waypoint(
                        name = primaryCoordinateDisplay.text,
                        latitude = lat,
                        longitude = lng,
                        kind = com.tacmap.waypoints.WaypointKind.Generic,
                        layerId = activeLayerId
                    )
                if (persistNewSymbol(waypoint) { waypointStore.add(it) } is DurableSymbolCreation.Failed) {
                    scope.launch { snackbarHostState.showSnackbar(SYMBOL_CREATION_ERROR) }
                }
            }
        )
        // The online-tiles warning used to sit here under the header, but that's
        // where the live-tracking record badge goes - they collided. It's paired
        // with the Centre pill at the bottom now.
        }

        if (onlineTilesActive && onlineTilesUnavailable && !calibrating) {
            Text(
                L10n.text("Online basemap temporarily unavailable"),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 82.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }

        // Track-recording pill: REC while recording, and also while awaiting
        // location, starting or interrupted, as on iOS. What a tap does
        // depends on the state (see recordingPillAction).
        if (trackRecordingState.phase != TrackRecordingPhase.Idle) {
            RecordingIndicator(
                phase = trackRecordingState.phase,
                pointCount = trackPoints.size,
                onTap = when (recordingPillAction(trackRecordingState.phase)) {
                    RecordingPillAction.STOP -> { { vm.stopTrackRecording() } }
                    RecordingPillAction.DISMISS -> { { vm.trackRecorder.dismissRecordingMessage() } }
                    RecordingPillAction.NONE -> null
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 104.dp)
            )
        }

        // hamburger (left) + compass (right), pinned below the MGRS header with
        // a small gap. 100dp used to clip the header's bottom edge; the card is
        // taller than that, so sit them a bit lower.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 116.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // calibrating: the ✕ takes the hamburger's corner, as far from Finish as it gets
            if (calibrating) CalibrationLeaveButton(onLeave = ::leaveCalibration)
            if (!calibrating) Box(Modifier.tourTarget(tourTargets, TourTarget.MENU)) {
                CircleHudButton(Icons.Default.Menu, L10n.text("Menu")) { hamburgerOpen = true }
                DropdownMenu(
                    expanded = hamburgerOpen,
                    onDismissRequest = { hamburgerOpen = false },
                    // Keep the final menu rows above three-button navigation.
                    modifier = Modifier.padding(bottom = menuBottomPadding),
                ) {
                    if (!isPurchased) {
                        DropdownMenuItem(
                            enabled = false,
                            text = {
                                Text(
                                    if (trialDaysRemaining > 0)
                                        L10n.quantity("trial_remaining", trialDaysRemaining)
                                    else L10n.text("Free trial ended")
                                )
                            },
                            onClick = {},
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(L10n.text("Unlock Full Version")) },
                            onClick = {
                                hamburgerOpen = false
                                onUnlock()
                            },
                            leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) }
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text(L10n.text("Search")) },
                        onClick = {
                            hamburgerOpen = false
                            showSearchDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(L10n.text("Symbology")) },
                        onClick = {
                            hamburgerOpen = false
                            showWaypointSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(L10n.text("Drawings")) },
                        onClick = {
                            hamburgerOpen = false
                            showDrawingSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.Gesture, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(L10n.text("Layers and Labels")) },
                        onClick = {
                            hamburgerOpen = false
                            showLayersSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(L10n.text("Measure")) },
                        onClick = {
                            hamburgerOpen = false
                            stopDrawing()
                            measureSession.start()
                        },
                        leadingIcon = { Icon(Icons.Default.Straighten, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(L10n.text("Weather & UAV Safety")) },
                        onClick = {
                            hamburgerOpen = false
                            weatherTarget = vm.headerCoordinate
                        },
                        leadingIcon = { Icon(Icons.Default.Air, contentDescription = null) }
                    )
                    HorizontalDivider()
                    // all import/export behind one item, keeps menu short
                    DropdownMenuItem(
                        text = { Text(L10n.text("Import / Export")) },
                        onClick = {
                            hamburgerOpen = false
                            showImportExportSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.ImportExport, contentDescription = null) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (trackRecordingState.phase) {
                                    TrackRecordingPhase.Recording ->
                                        L10n.text("Stop Track Recording (%1\$s pts)", trackPoints.size)
                                    TrackRecordingPhase.Starting -> L10n.text("Starting Track Recording…")
                                    TrackRecordingPhase.AwaitingPermission -> L10n.text("Retry Track Recording")
                                    TrackRecordingPhase.Interrupted,
                                    TrackRecordingPhase.Idle -> L10n.text("Start Track Recording")
                                }
                            )
                        },
                        enabled = trackRecordingState.phase != TrackRecordingPhase.Starting,
                        onClick = {
                            hamburgerOpen = false
                            if (isRecordingTrack) {
                                vm.stopTrackRecording()
                            } else {
                                onRequestTrackRecording()
                            }
                        },
                        leadingIcon = {
                            Icon(
                                if (isRecordingTrack) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                contentDescription = null
                            )
                        }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(L10n.text("Unit Sync")) },
                        onClick = {
                            hamburgerOpen = false
                            showSyncDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("TacMap Chat") },
                        onClick = {
                            hamburgerOpen = false
                            chatTarget = com.tacmap.sync.TacMapChatTarget.EntireRoom
                        },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(L10n.text("App Lock")) },
                        onClick = {
                            hamburgerOpen = false
                            showAppLockSetup = true
                        },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(PRIVACY_OPSEC_LABEL) },
                        onClick = {
                            openPrivacyAndOpsecFromMenu(
                                setMenuOpen = { hamburgerOpen = it },
                                setDialogVisible = { showOpsecSettings = it },
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(L10n.text("About & Credits")) },
                        onClick = {
                            hamburgerOpen = false
                            showAboutDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                }
            }
            if (tacMapChatHudVisible(syncRoom) && !calibrating) {
                TacMapChatHudButton(unreadCount = unreadChatMessageCount) {
                    chatTarget = com.tacmap.sync.TacMapChatTarget.EntireRoom
                }
            }
            if (quickAddAllowed) {
                Box(Modifier.tourTarget(tourTargets, TourTarget.ADD)) {
                    QuickAddSymbolButton {
                        // Freeze the placement target now. The user can spend
                        // time in the editor without a later camera update
                        // silently changing where the symbol will land.
                        quickAddCreationError = null
                        quickAddTarget = QuickAddTarget(cameraLat, cameraLng, quickAddLayerId)
                        quickAddMenuOpen = true
                    }
                    DropdownMenu(
                        expanded = quickAddMenuOpen,
                        onDismissRequest = {
                            quickAddMenuOpen = false
                            if (quickAddEditorMode == null) quickAddTarget = null
                        }
                    ) {
                        DropdownMenuItem(
                            text = { Text(L10n.text("Military Unit")) },
                            leadingIcon = { Icon(Icons.Default.Security, contentDescription = null) },
                            onClick = {
                                quickAddMenuOpen = false
                                quickAddCreationError = null
                                quickAddEditorMode = SymbolEditorMode.MILITARY
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L10n.text("Tactical Task")) },
                            leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                            onClick = {
                                quickAddMenuOpen = false
                                quickAddCreationError = null
                                quickAddEditorMode = SymbolEditorMode.TASK
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(L10n.text("Marker")) },
                            leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) },
                            onClick = {
                                quickAddMenuOpen = false
                                quickAddCreationError = null
                                quickAddEditorMode = SymbolEditorMode.MARKER
                            }
                        )
                    }
                }
            }
            if (!calibrating) Box(Modifier.tourTarget(tourTargets, TourTarget.LABELS)) {
                UnitLabelsToggle(active = unitLabelsVisible) { unitLabelsVisible = !unitLabelsVisible }
            }
            Box(Modifier.tourTarget(tourTargets, TourTarget.NIGHT)) {
                NightModeToggle(active = nightModeOn) { vm.opsec.setNightMode(!nightModeOn) }
            }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.tourTarget(tourTargets, TourTarget.COMPASS)) {
                    MapCompassChip(
                        vm = vm,
                        orientationMode = mapOrientationMode,
                        onHeadingUnavailable = {
                            Toast.makeText(
                                context,
                                L10n.text("Heading Up is unavailable on this device."),
                                Toast.LENGTH_SHORT,
                            ).show()
                        },
                    )
                }
                if (!calibrating) UndoRedoButtons(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = {
                        undoHistory.undo { target ->
                            when (target) {
                                UndoTarget.DRAWINGS -> drawingStore.undo().also { undone ->
                                    if (undone) ringFollower.realign(waypointStore.committedWaypoints.value)
                                }
                                UndoTarget.SYMBOLS -> waypointStore.undo()
                            }
                        }
                    },
                    onRedo = {
                        undoHistory.redo { target ->
                            when (target) {
                                UndoTarget.DRAWINGS -> drawingStore.redo().also { redone ->
                                    if (redone) ringFollower.realign(waypointStore.committedWaypoints.value)
                                }
                                UndoTarget.SYMBOLS -> waypointStore.redo()
                            }
                        }
                    }
                )
                if (!calibrating) Box(Modifier.tourTarget(tourTargets, TourTarget.LOCK)) {
                    LockButton(
                        locked = graphicsLocked,
                        onToggle = {
                            graphicsLocked = !graphicsLocked
                            // Locking closes any open symbol/drawing card so
                            // nothing stays editable while locked (iOS parity).
                            if (graphicsLocked) {
                                if (selectedDrawingId != null) drawingStore.revertPreview()
                                selectedDrawingId = null
                                vm.selectWaypoint(null)
                            }
                        }
                    )
                }
            }
        }

        val ui = calibrationUi
        if (ui != null) {
            CalibrationScreenLayer(
                vm = vm,
                ui = ui,
                camera = cameraViewportState?.toCamera(),
                captureNow = ::captureNow,
                lastLocation = lastLocation,
                hasPreciseLocation = hasPreciseLocation,
            )
        } else if (measureSession.isActive) {
            MeasureToolbar(
                session = measureSession,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                onProfile = {
                    profilePath = measureSession.points.map { (lat, lng) -> ElevationProfile.Coordinate(lat, lng) }
                },
            )
        } else if (activeDrawTool != null) {
            DrawingDraftBar(
                geometry = activeDrawTool!!,
                pointCount = draftPoints.size,
                drawingName = drawingNameOrDefault(
                    activeDrawingName,
                    activeDrawTool!!,
                    drawingDocument.features
                ),
                strokeColor = activeStrokeColor,
                strokeStyle = activeStrokeStyle,
                fillColor = activeFillColor,
                fillAlpha = activeFillAlpha,
                onDrawingNameChange = { activeDrawingName = it },
                onStrokeColorChange = { activeStrokeColor = it },
                onStrokeStyleChange = { activeStrokeStyle = it },
                onFillColorChange = { activeFillColor = it },
                onFillAlphaChange = { activeFillAlpha = it },
                onUndoPoint = { draftPoints = draftPoints.dropLast(1) },
                onFinish = {
                    when (activeDrawTool) {
                        DrawingGeometry.POINT -> stopDrawing()
                        DrawingGeometry.LINE, DrawingGeometry.POLYGON -> finishDraft()
                        null -> Unit
                    }
                },
                onCancel = ::stopDrawing,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
            )
        } else if (selectedDrawing != null) {
            DrawingFeatureEditBar(
                feature = selectedDrawing,
                layers = drawingDocument.layers,
                onFeatureChange = { candidate ->
                    checkedDrawingMutation(DrawingMutationIntent.EDIT) {
                        drawingStore.updateFeature(candidate)
                    }
                },
                onFeatureChangeDraft = drawingStore::previewFeature,
                onMoveToCrosshair = {
                    checkedDrawingMutation(DrawingMutationIntent.EDIT) {
                        drawingStore.updateFeature(
                            selectedDrawing.movedToCrosshair(cameraLat, cameraLng)
                        )
                    }
                },
                onDelete = {
                    drawingStore.revertPreview()
                    val result = checkedDrawingMutation(DrawingMutationIntent.DELETE) {
                        drawingStore.removeFeature(selectedDrawing.id)
                    }
                    if (result.saved) selectedDrawingId = null
                    result
                },
                onElevationProfile = if (selectedDrawing.geometry == DrawingGeometry.LINE) {
                    {
                        profilePath = selectedDrawing.effectivePoints.map {
                            ElevationProfile.Coordinate(it.latitude, it.longitude)
                        }
                    }
                } else {
                    null
                },
                onDismiss = {
                    drawingStore.revertPreview()
                    selectedDrawingId = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .widthIn(max = 390.dp)
                    .fillMaxWidth()
            )
        } else if (selected != null) {
            SymbolControlsCard(
                waypoint = selected,
                layers = drawingDocument.layers,
                crosshairTargetLat = cameraLat,
                crosshairTargetLng = cameraLng,
                store = waypointStore,
                onCreateRangeRings = { rings ->
                    performDrawingMutation(
                        intent = DrawingMutationIntent.CREATE,
                        persist = { drawingStore.addFeatures(rings) },
                    ).saved
                },
                onDismiss = { vm.selectWaypoint(null) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .fillMaxWidth()
            )
        } else {
            // Basemap status now lives in the MGRS banner, so the bottom is just
            // the recentre pills. When an imported (offline/PDF) map is loaded, add
            // a "Map" pill so centring on a distant live location doesn't strand the
            // user away from their map. Side by side (stacking ate too much height);
            // the location label shrinks when both show.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CentrePill(
                    onClick = {
                        when (liveLocationControl.action) {
                            LiveMapLocationAction.RequestPermission -> onRequestLiveMapLocation()
                            LiveMapLocationAction.CentreOnLocation -> vm.centreOnUser()
                            LiveMapLocationAction.OpenSettings -> onOpenLiveMapLocationSettings()
                        }
                    },
                    label = if (hasPreciseLocation && importedMapLoaded) {
                        L10n.text("My Location")
                    } else {
                        liveLocationControl.title
                    },
                    icon = if (liveLocationControl.action == LiveMapLocationAction.OpenSettings) {
                        Icons.Default.Settings
                    } else {
                        Icons.Default.GpsFixed
                    },
                    guidance = liveLocationControl.guidance,
                )
                if (importedMapLoaded) {
                    CentrePill(
                        onClick = { vm.centreOnMap() },
                        label = L10n.text("Map"),
                        icon = Icons.Default.Map
                    )
                }
            }
        }

        // Snackbar for remote sync conflict notifications
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
        )

        // Generate Offline Tiles progress, stays up after the Layers sheet closes
        PdfBakeChip(
            state = bakeState,
            onCancel = { vm.bakeManager.cancel() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp),
        )

        if (showTips) {
            MapTourOverlay(
                targets = tourTargets,
                onFinish = {
                    FirstRunTips.markSeen(context)
                    showTips = false
                },
            )
        }
    }

    val quickMode = quickAddEditorMode
    val capturedQuickTarget = quickAddTarget
    if (quickMode != null && capturedQuickTarget != null) {
        SymbolEditorDialog(
            mode = quickMode,
            initialName = "",
            crosshairLat = capturedQuickTarget.latitude,
            crosshairLng = capturedQuickTarget.longitude,
            title = Messages.symbolsNewSymbolTitle(),
            actionLabel = L10n.text("Place"),
            defaultTaskScale = TaskGraphicSizing.defaultScale(cameraViewportState),
            submissionError = quickAddCreationError?.text,
            onDismiss = {
                quickAddEditorMode = null
                quickAddTarget = null
                quickAddCreationError = null
            },
            onConfirm = { draft ->
                val added = draft.toWaypoint(
                    latitude = capturedQuickTarget.latitude,
                    longitude = capturedQuickTarget.longitude,
                    layerId = capturedQuickTarget.layerId,
                )
                val name = added.name
                when (val result = persistNewSymbol(added) { waypointStore.add(it) }) {
                    is DurableSymbolCreation.Saved -> {
                        selectedDrawingId = null
                        drawingDocument.layers
                            .firstOrNull { it.id == capturedQuickTarget.layerId && !it.isVisible }
                            ?.let { hiddenLayer ->
                                performDrawingMutation(
                                    intent = DrawingMutationIntent.VISIBILITY,
                                    persist = { drawingStore.setLayerVisible(hiddenLayer.id, true) },
                                )
                            }
                        vm.selectWaypoint(result.waypoint.id)
                        Toast.makeText(
                            context,
                            if (capturedQuickTarget.fromPointMenu) Messages.mapPointSymbolAdded(name)
                            else L10n.text("Added %1\$s at crosshair", name),
                            Toast.LENGTH_SHORT,
                        ).show()
                        quickAddCreationError = null
                        quickAddEditorMode = null
                        quickAddTarget = null
                    }
                    is DurableSymbolCreation.Failed -> quickAddCreationError = result.pendingMessage
                }
            }
        )
    }

    if (showWaypointSheet) {
        WaypointListSheet(
            waypoints = waypoints,
            crosshairLat = cameraLat,
            crosshairLng = cameraLng,
            activeLayerId = safeActiveLayerId,
            layers = drawingDocument.layers,
            store = waypointStore,
            defaultTaskScale = TaskGraphicSizing.defaultScale(cameraViewportState),
            onDismiss = { showWaypointSheet = false },
            onFlyTo = { lat, lng ->
                vm.flyTo(lat, lng)
                showWaypointSheet = false
            }
        )
    }

    if (showDrawingSheet) {
        DrawingLayersSheet(
            layers = drawingDocument.layers,
            features = drawingDocument.features,
            activeLayerId = safeActiveLayerId,
            crosshairLat = cameraLat,
            crosshairLng = cameraLng,
            onDismiss = { showDrawingSheet = false },
            onActiveLayerChange = { activeDrawingLayerId = it },
            onPlacePoint = {
                vm.selectWaypoint(null)
                selectedDrawingId = null
                useActiveLayerStrokeColor()
                activeDrawTool = DrawingGeometry.POINT
                activeDrawingName = defaultDrawingName(DrawingGeometry.POINT, drawingDocument.features)
                draftGeometry = null
                draftPoints = emptyList()
                showDrawingSheet = false
            },
            onStartDraft = { geometry ->
                vm.selectWaypoint(null)
                selectedDrawingId = null
                useActiveLayerStrokeColor()
                activeDrawTool = geometry
                isFreeDrawMode = false
                activeDrawingName = defaultDrawingName(geometry, drawingDocument.features)
                draftGeometry = geometry
                draftPoints = emptyList()
                showDrawingSheet = false
            },
            onStartFreeDraw = {
                vm.selectWaypoint(null)
                selectedDrawingId = null
                useActiveLayerStrokeColor()
                activeDrawTool = DrawingGeometry.LINE
                isFreeDrawMode = true
                activeDrawingName = defaultDrawingName(DrawingGeometry.LINE, drawingDocument.features)
                draftGeometry = DrawingGeometry.LINE
                draftPoints = emptyList()
                showDrawingSheet = false
            },
            onLayerVisibilityChange = { id, visible ->
                checkedDrawingMutation(DrawingMutationIntent.VISIBILITY) {
                    drawingStore.setLayerVisible(id, visible)
                }
            },
            onAddLayer = drawingStore::addLayer,
            onUpdateLayer = drawingStore::updateLayer,
            onDeleteLayer = { layerId ->
                val outcome = deleteMissionLayer(layerId, waypointStore, drawingStore)
                if (outcome.succeeded) {
                    if (activeDrawingLayerId == layerId) {
                        activeDrawingLayerId = outcome.fallbackLayerId
                            ?: DrawingDocument.DEFAULT_LAYER_ID
                    }
                    Toast.makeText(context, L10n.text("Layer deleted; contents moved to Friendly"), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, L10n.text("Layer could not be deleted"), Toast.LENGTH_SHORT).show()
                }
                outcome.succeeded
            },
            onDeleteFeature = { id ->
                checkedDrawingMutation(DrawingMutationIntent.DELETE) {
                    drawingStore.removeFeature(id)
                }
            },
            onRenameFeature = { id, name ->
                checkedDrawingMutation(DrawingMutationIntent.EDIT) {
                    val feature = drawingStore.document.value.features.firstOrNull { it.id == id }
                    feature != null && drawingStore.updateFeature(feature.copy(name = name))
                }
            },
        )
    }

    if (showSearchDialog) {
        SearchDialog(
            waypoints = waypoints,
            drawings = drawingDocument.features,
            layers = drawingDocument.layers,
            cameraLat = cameraLat,
            cameraLng = cameraLng,
            onlineLookupsEnabled = onlineLookupsEnabled,
            onDismiss = { showSearchDialog = false },
            onFlyTo = { lat, lng -> vm.flyTo(lat, lng) },
            onWaypointSelected = { waypointId ->
                vm.selectWaypoint(waypointId)
                if (waypointId != null) selectedDrawingId = null
            },
            onDrawingSelected = { drawingId ->
                selectedDrawingId = drawingId
                if (drawingId != null) vm.selectWaypoint(null)
            }
        )
    }

    /** Opens the guided tour over a clear map. */
    fun startTour() {
        hamburgerOpen = false
        quickAddMenuOpen = false
        mapPressPoint = null
        vm.selectWaypoint(null)
        selectedDrawingId = null
        showTips = true
    }

    if (showAboutDialog) {
        AboutDialog(
            onDismiss = { showAboutDialog = false },
            onReplayTour = {
                showAboutDialog = false
                startTour()
            },
        )
    }

    profilePath?.let { path ->
        ElevationProfileDialog(path = path, onDismiss = { profilePath = null })
    }

    pointSunMoon?.let { point ->
        PointSunMoonDialog(point = point, onDismiss = { pointSunMoon = null })
    }

    pointRingsCentre?.let { centre ->
        RangeRingsDialog(
            waypoint = centre,
            layerColor = drawingDocument.layers.firstOrNull { it.id == centre.layerId }?.color,
            onCreate = { rings ->
                performDrawingMutation(
                    intent = DrawingMutationIntent.CREATE,
                    persist = { drawingStore.addFeatures(rings) },
                ).saved
            },
            onDismiss = { pointRingsCentre = null },
            anchored = false,
        )
    }

    weatherTarget?.let { (lat, lng) ->
        WeatherDialog(
            lat = lat,
            lng = lng,
            onlineLookupsEnabled = onlineLookupsEnabled,
            onDismiss = { weatherTarget = null },
        )
    }

    if (showAppLockSetup) {
        com.tacmap.app.AppLockSetupDialog(appLock = appLock, onDismiss = { showAppLockSetup = false })
    }

    if (showOpsecSettings) {
        OpsecSettingsDialog(
            opsec = vm.opsec,
            headingAvailable = vm.headingService.isHeadingAvailable,
            onRequestAuthBoundChange = onRequestAuthBoundChange,
            onShowTips = {
                showOpsecSettings = false
                startTour()
            },
            onDismiss = { showOpsecSettings = false },
        )
    }

    // calibration sheets + dialogs (s2, s10)
    calibrationUi?.let { ui -> CalibrationSheetsAndDialogs(vm, ui) }

    // s9.6 rejected georef on a single page: added (not active), offer calibration
    rejectedPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { vm.dismissRejectedPrompt() },
            title = { Text(Messages.pdfGeorefRejectedTitle()) },
            text = { Text(Messages.mapImportGeorefRejected(pdfGeorefRejectionReason(prompt.reason))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.dismissRejectedPrompt()
                    beginCalibration(prompt.entryId)
                }) { Text(Messages.mapImportCalibrateNow()) }
            },
            dismissButton = { TextButton(onClick = { vm.dismissRejectedPrompt() }) { Text(Messages.mapImportLater()) } },
        )
    }

    pendingChangePage?.let { (prepared, page) ->
        AlertDialog(
            onDismissRequest = { pendingChangePage = null },
            text = { Text(Messages.mapChangePageConfirm()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingChangePage = null
                    writeChosenPage(prepared, page)
                }) { Text(Messages.mapActionChoosePage()) }
            },
            dismissButton = { TextButton(onClick = { pendingChangePage = null }) { Text(L10n.text("Cancel")) } },
        )
    }

    importAlert?.let { text ->
        AlertDialog(
            onDismissRequest = { importAlert = null },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { importAlert = null }) { Text(Messages.acknowledge()) } },
        )
    }

    pagePicker?.let { prepared ->
        PdfPagePickerDialog(
            prepared = prepared,
            onPick = { page ->
                if (prepared.existingEntryId != null) {
                    applyChosenPage(prepared, page)
                    return@PdfPagePickerDialog
                }
                scope.launch {
                    val inspected = prepared.inspection.page(page) ?: return@launch
                    // a picked page past the first one needs its own pdfium frame
                    val geometry = inspected.geometry ?: withContext(Dispatchers.IO) {
                        com.tacmap.calibration.PdfInspector.geometryFor(prepared.file, inspected)?.first
                    }
                    vm.dismissPagePicker(keepFile = true)
                    importCommitter("pdf:picked:${java.util.UUID.randomUUID()}")
                        .commitPicked(prepared, inspected.copy(geometry = geometry))
                }
            },
            // an existing entry's file is never dropped, dismissPagePicker knows that too
            onCancel = { vm.dismissPagePicker(keepFile = prepared.existingEntryId != null) },
        )
    }

    launchAlert?.let { alert ->
        AlertDialog(
            onDismissRequest = { vm.dismissLaunchAlert() },
            text = {
                Text(
                    when (alert) {
                        is MapLaunchAlert.MigrationUncalibrated -> Messages.mapMigrationUncalibrated(alert.name)
                        MapLaunchAlert.ImportInterrupted -> Messages.mapImportInterrupted()
                        MapLaunchAlert.ActiveFileChanged -> Messages.mapStateUnavailable()
                    }
                )
            },
            confirmButton = { TextButton(onClick = { vm.dismissLaunchAlert() }) { Text(Messages.acknowledge()) } },
        )
    }

    importProgress?.let { progress ->
        ImportProgressCard(
            progress = progress,
            cancelling = importCancelRequested,
            onCancel = {
                importCancelRequested = true
                importJob?.cancel()
            },
        )
    }

    if (showDiscardTrackConfirmation) {
        AlertDialog(
            onDismissRequest = { showDiscardTrackConfirmation = false },
            title = {
                Text(if (isRecordingTrack) L10n.text("Stop and discard track?") else L10n.text("Discard saved track?"))
            },
            text = {
                Text(
                    if (isRecordingTrack) {
                        Messages.recordingDiscardActive(DisplayFormat.number((trackPoints.size).toDouble(), 0))
                    } else {
                        Messages.recordingDiscardSaved(DisplayFormat.number((trackPoints.size).toDouble(), 0))
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardTrackConfirmation = false
                        vm.discardTrackRecording()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5A5A)),
                ) {
                    Text(if (isRecordingTrack) L10n.text("Stop & Discard") else L10n.text("Discard"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardTrackConfirmation = false }) { Text(L10n.text("Cancel")) }
            },
        )
    }

    val recordingStateMessage = trackRecordingState.message.takeIf {
        trackRecordingState.phase == TrackRecordingPhase.AwaitingPermission ||
            trackRecordingState.phase == TrackRecordingPhase.Interrupted
    }
    (recordingStateMessage ?: trackPersistError)?.let { message ->
        AlertDialog(
            onDismissRequest = {
                if (recordingStateMessage != null) vm.trackRecorder.dismissRecordingMessage()
                else vm.trackRecorder.acknowledgePersistError()
            },
            title = { Text(L10n.text("Track recording")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (recordingStateMessage != null) {
                            vm.trackRecorder.dismissRecordingMessage()
                            onRequestTrackRecording()
                        } else {
                            vm.trackRecorder.acknowledgePersistError()
                        }
                    }
                ) { Text(if (recordingStateMessage != null) L10n.text("Retry") else Messages.acknowledge()) }
            },
            dismissButton = trackRecordingState.settingsTarget?.let { target ->
                {
                    TextButton(
                        onClick = {
                            vm.trackRecorder.dismissRecordingMessage()
                            onOpenTrackRecordingSettings?.invoke(target)
                        }
                    ) { Text(L10n.text("Settings")) }
                }
            },
        )
    }

    pendingDrawingMutation?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDrawingMutation = null },
            title = { Text(L10n.text("Drawing change not saved")) },
            text = { Text(pending.pendingMessage.text) },
            confirmButton = {
                TextButton(onClick = {
                    val result = checkedDrawingMutation(pending.intent, pending.persist)
                    if (result.saved) {
                        pendingDrawingMutation = null
                        pending.onSaved()
                    } else {
                        pendingDrawingMutation = pending.copy(
                            pendingMessage = (result as DrawingMutationUiResult.Failed).pendingMessage
                        )
                    }
                }) { Text(L10n.text("Retry")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDrawingMutation = null }) { Text(L10n.text("Not now")) }
            },
        )
    }

    (waypointStoreError ?: drawingStoreError)?.let { message ->
        AlertDialog(
            onDismissRequest = {
                if (waypointStoreError != null) waypointStore.acknowledgeLoadError()
                else drawingStore.acknowledgeLoadError()
            },
            title = { Text(L10n.text("Mission data was not saved")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (waypointStoreError != null) waypointStore.acknowledgeLoadError()
                        else drawingStore.acknowledgeLoadError()
                    }
                ) { Text(Messages.acknowledge()) }
            },
        )
    }

    if (showSyncDialog) {
        com.tacmap.sync.SyncDialog(
            manager = syncManager,
            opsec = vm.opsec,
            onDismiss = { showSyncDialog = false },
            onOpenChat = { target ->
                showSyncDialog = false
                chatTarget = target
            },
        )
    }

    chatTarget?.let { target ->
        com.tacmap.sync.TacMapChatDialog(
            manager = syncManager,
            initialTarget = target,
            onDismiss = { chatTarget = null },
        )
    }

    PdfRenderDialogs(
        vm = vm,
        mapSource = mapSource,
        pdfRenderStatus = pdfRenderStatus,
        dismissedRenderFailure = dismissedRenderFailure,
        onDismissRenderFailure = { dismissedRenderFailure = it },
        onRetry = {
            vm.pdfRuntime.retry()
            pdfRetryTick++
        },
        pdfRecovery = pdfRecovery,
        confirmDeleteSuspect = confirmDeleteSuspect,
        onConfirmDeleteSuspect = { confirmDeleteSuspect = it },
        pdfLaunchNotice = pdfLaunchNotices.firstOrNull(),
        bakeState = bakeState,
    )

    if (showLayersSheet) {
        LayersSheet(
            symbologyVisible = symbologyVisible,
            drawingsVisible = drawingsVisible,
            mgrsGridVisible = mgrsGridVisible,
            userLocationVisible = userLocationVisible,
            unitLabelsVisible = unitLabelsVisible,
            unitAmplifiersVisible = unitAmplifiersVisible,
            taskLabelsVisible = taskLabelsVisible,
            drawingLabelsVisible = drawingLabelsVisible,
            terrainHeatmapVisible = terrainHeatmapVisible,
            onMgrsGridChange = { mgrsGridVisible = it },
            onSymbologyVisibleChange = { symbologyVisible = it },
            onDrawingsVisibleChange = { drawingsVisible = it },
            onUserLocationChange = { userLocationVisible = it },
            onTerrainHeatmapChange = { terrainHeatmapVisible = it },
            onUnitLabelsChange = { unitLabelsVisible = it },
            onUnitAmplifiersChange = { unitAmplifiersVisible = it },
            onTaskLabelsChange = { taskLabelsVisible = it },
            onDrawingLabelsChange = { drawingLabelsVisible = it },
            drawingLayers = drawingDocument.layers,
            drawingFeatures = drawingDocument.features,
            onSetLayerVisible = { id, visible ->
                checkedDrawingMutation(DrawingMutationIntent.VISIBILITY) {
                    drawingStore.setLayerVisible(id, visible)
                }
            },
            activeBaseMap = (mapSource as? OnlineRasterMapSourceAndroid)?.style,
            onSelectBaseMap = { vm.selectBaseMap(it) },
            importedMaps = importedMapsUi(),
            onImportedMapTap = { row ->
                when (row.rowTap) {
                    EntryRowTap.ACTIVATE -> {
                        if (vm.activateImportedMap(row.id)) showLayersSheet = false
                    }
                    EntryRowTap.CALIBRATE -> {
                        showLayersSheet = false
                        beginCalibration(row.id)
                    }
                    EntryRowTap.NONE -> Unit
                }
            },
            onImportedMapAction = { row, action ->
                when (action) {
                    EntryMenuAction.CALIBRATE -> {
                        showLayersSheet = false
                        beginCalibration(row.id)
                    }
                    EntryMenuAction.DELETE -> {
                        // E10: the detail is why, never the map's own name. a failed write
                        // already has its Retry alert up, that says it
                        if (vm.libraryStatus.value != LibraryStatus.LOADED) {
                            importAlert = Messages.mapDeleteFailed(Messages.mapLibraryLocked())
                        } else {
                            vm.deleteImportedMap(row.id)
                        }
                    }
                    EntryMenuAction.USE_EMBEDDED -> vm.useEmbeddedGeoref(row.id)
                    EntryMenuAction.CHOOSE_PAGE -> {
                        // the confirm comes after the pick, picking the same page asks nothing (E1)
                        showLayersSheet = false
                        choosePageFor(row.id)
                    }
                    EntryMenuAction.GENERATE_TILES -> if (row.generate == ImportUiRules.GenerateMenu.CANCEL) {
                        vm.bakeManager.cancel()
                    } else if (row.generate == ImportUiRules.GenerateMenu.ENABLED) {
                        // WP2's bake (M1/M15): estimate, then the confirm with the zoom options.
                        // app scoped so closing the sheet or rotating doesn't stop it. the PDF
                        // stays, its entry gets the bake record, the active map never changes
                        val entry = vm.libraryState.value?.entry(row.id)
                        val pdf = entry?.let { e -> vm.effectiveGeoref(e)?.let { g -> vm.pdfSourceFor(e, g) } }
                        if (entry == null || pdf == null) {
                            Toast.makeText(context, Messages.mapStateNeedsCalibration(), Toast.LENGTH_SHORT).show()
                        } else {
                            vm.bakeManager.prepare(pdf, rendererDensity)
                        }
                    }
                }
            },
            pdfMap = pdfSource,
            pdfMapCalibratedLabel = pdfSource?.entryId?.let { libraryState?.entry(it) }
                ?.let { ImportUiRules.calibratedBlockLabel(it.pdf?.manual) }?.let(CalibrationText::text),
            importedMapVisible = importedMapVisible || calibrating,
            importedMapToggleEnabled = !calibrating,
            onImportedMapVisibleChange = { importedMapVisible = it },
            bakeState = bakeState,
            onGenerateTiles = { pdfSource?.let { vm.bakeManager.prepare(it, rendererDensity) } },
            onCancelBake = { vm.bakeManager.cancel() },
            onRemoveBake = { vm.removePdfBake() },
            renderFailed = pdfRenderFailed,
            renderFailureReason = (pdfRenderStatus as? com.tacmap.map.render.pdf.PdfRenderStatus.Failed)
                ?.takeIf { pdfRenderFailed }?.reason?.let(::pdfRenderFailureMessage),
            onRetryRender = {
                vm.pdfRuntime.retry()
                pdfRetryTick++
                dismissedRenderFailure = null
            },
            onDismiss = { showLayersSheet = false }
        )
    }

    if (showImportExportSheet) {
        ImportExportSheet(
            // D5-09: one map import at a time, the rows are off while one runs
            importsEnabled = importProgress == null && importJob == null,
            onImportSymbolPack = {
                showImportExportSheet = false
                onRequestDocumentImport(DocumentImportKind.SYMBOL_PACK)
            },
            onImportPdf = {
                showImportExportSheet = false
                onRequestDocumentImport(DocumentImportKind.PDF)
            },
            onImportTiles = {
                showImportExportSheet = false
                onRequestDocumentImport(DocumentImportKind.MBTILES)
            },
            onImportGeoJson = {
                showImportExportSheet = false
                onRequestDocumentImport(DocumentImportKind.GEO_JSON)
            },
            onImportKml = {
                showImportExportSheet = false
                onRequestDocumentImport(DocumentImportKind.KML)
            },
            onExportGeoJson = {
                showImportExportSheet = false
                exportPreview = ExportPreviewKind.GEOJSON
            },
            onExportGpx = {
                showImportExportSheet = false
                exportPreview = ExportPreviewKind.GPX
            },
            onExportAllData = {
                showImportExportSheet = false
                scope.launch {
                    exportAllMissionObjects(
                        context = context,
                        waypoints = waypoints,
                        drawings = drawingDocument.features,
                        layers = drawingDocument.layers,
                    )
                }
            },
            onExportKml = {
                showImportExportSheet = false
                scope.launch {
                    exportMissionKml(
                        context = context,
                        waypoints = waypoints,
                        drawings = drawingDocument.features,
                        layers = drawingDocument.layers,
                        withSymbols = false,
                    )
                }
            },
            onExportKmz = {
                showImportExportSheet = false
                scope.launch {
                    exportMissionKml(
                        context = context,
                        waypoints = waypoints,
                        drawings = drawingDocument.features,
                        layers = drawingDocument.layers,
                        withSymbols = true,
                    )
                }
            },
            hasSavedTrack = trackPoints.isNotEmpty(),
            isRecordingTrack = isRecordingTrack,
            onDiscardTrack = {
                showImportExportSheet = false
                showDiscardTrackConfirmation = true
            },
            onDismiss = { showImportExportSheet = false }
        )
    }

    when (exportPreview) {
        ExportPreviewKind.GEOJSON -> GeoJsonExportPreviewDialog(
            waypoints = waypoints,
            drawings = drawingDocument.features,
            layers = drawingDocument.layers,
            onDismiss = { exportPreview = null },
        )
        ExportPreviewKind.GPX -> GpxExportPreviewDialog(
            points = trackPoints,
            onDismiss = { exportPreview = null },
        )
        null -> Unit
    }
}

internal fun tacMapChatHudVisible(room: String?): Boolean = room?.startsWith("3:") == true
