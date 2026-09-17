package com.tacmap.map

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import com.tacmap.calibration.AffineFitter
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.Fiduciary
import com.tacmap.calibration.Datum
import com.tacmap.calibration.GeoPdfParser
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageRenderer
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
import com.tacmap.models.TrackRecordingPhase
import com.tacmap.models.TrackRecordingSettingsTarget
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
    val layerId: String
)

private data class PendingDrawingMutation(
    val intent: DrawingMutationIntent,
    val message: String,
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
    val rendererDensity = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val unitSyncRuntime = remember(context) {
        (context.applicationContext as com.tacmap.app.TacticalApp).unitSyncRuntime
    }
    val unitSyncForegroundEpoch by unitSyncRuntime.foregroundEpoch.collectAsState()

    val onlineBasemapsEnabled by vm.opsec.onlineBasemaps.collectAsState()
    val onlineLookupsEnabled by vm.opsec.onlineLookups.collectAsState()
    val primaryCoordinateType by vm.opsec.primaryCoordinateType.collectAsState()
    val mapOrientationMode by vm.opsec.mapOrientationMode.collectAsState()
    var headingSessionActive by remember { mutableStateOf(false) }
    val onlineTilesUnavailable by OnlineTileHealth.temporarilyUnavailable.collectAsState()
    val pendingTarget by vm.pendingCameraTarget.collectAsState()
    val cameraLat by vm.cameraLat.collectAsState()
    val cameraLng by vm.cameraLng.collectAsState()
    val cameraViewportState by vm.cameraViewportState.collectAsState()
    val centreElevation by vm.centreElevation.collectAsState()
    val trackRecordingState by vm.trackRecorder.uiState.collectAsState()
    val isRecordingTrack = trackRecordingState.showsRec
    val trackPoints by vm.trackRecorder.points.collectAsState()
    val trackPersistError by vm.trackRecorder.persistError.collectAsState()
    val mapSource by vm.mapSource.collectAsState()
    val retainedImportedMap by vm.retainedImportedMapSource.collectAsState()
    val mapSelectionPersistenceIssue by vm.mapSelectionPersistenceIssue.collectAsState()

    /// Is anything on screen actually pulling tiles off the internet right now?
    /// Only the online raster styles (Esri/OSM) do; offline packs and PDFs don't.
    val onlineTilesActive = onlineBasemapsEnabled &&
        mapSource is com.tacmap.calibration.OnlineRasterMapSourceAndroid
    /// An imported, location-bound basemap (MBTiles pack or PDF/GeoPDF). These
    /// have coverage, so they get the "Centre on Map" button + green banner tag.
    val importedMapLoaded = mapSource is com.tacmap.calibration.OfflineTileMapSourceAndroid ||
        mapSource is com.tacmap.calibration.PdfMapSource
    /// Basemap status shown in the MGRS banner (replaces Live Location/Map Centre).
    val basemapLabel: String? = when {
        importedMapLoaded -> L10n.text("Offline basemap")
        onlineTilesActive -> L10n.text("Online basemap")
        else -> null
    }
    val basemapColor = if (importedMapLoaded) Color(0xFF74E38A) else Color(0xFFFF5A5A)
    val waypointStore = remember(unitSyncForegroundEpoch) { WaypointStore(context) }
    val waypoints by waypointStore.waypoints.collectAsState()
    val drawingStore = remember(unitSyncForegroundEpoch) { DrawingStore(context) }
    val importIdentityJournal = remember {
        com.tacmap.export.ExternalImportIdentityJournal(context)
    }
    val documentCopyJournal = remember { DocumentImportCopyJournal(context) }
    val drawingDocument by drawingStore.document.collectAsState()
    val drawingCanUndo by drawingStore.canUndo.collectAsState()
    val drawingCanRedo by drawingStore.canRedo.collectAsState()
    val waypointCanUndo by waypointStore.canUndo.collectAsState()
    val waypointCanRedo by waypointStore.canRedo.collectAsState()
    val waypointDataLocked by waypointStore.locked.collectAsState()
    val drawingDataLocked by drawingStore.locked.collectAsState()
    val waypointStoreError by waypointStore.loadError.collectAsState()
    val drawingStoreError by drawingStore.loadError.collectAsState()
    val canUndo = drawingCanUndo || waypointCanUndo
    val canRedo = drawingCanRedo || waypointCanRedo
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
    var showOpsecSettings by remember { mutableStateOf(false) }
    var showDiscardTrackConfirmation by remember { mutableStateOf(false) }
    var hamburgerOpen by remember { mutableStateOf(false) }
    var quickAddMenuOpen by remember { mutableStateOf(false) }
    var quickAddTarget by remember { mutableStateOf<QuickAddTarget?>(null) }
    var quickAddEditorMode by remember { mutableStateOf<SymbolEditorMode?>(null) }
    var quickAddCreationError by remember { mutableStateOf<String?>(null) }
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
    LaunchedEffect(mapSelectionPersistenceIssue?.id) {
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

    /// (done, total) while baking PDF into offline tiles, null when idle
    var tilingProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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
    var mgrsGridVisible by rememberPersistedBoolean("mgrsGrid", false)
    var terrainHeatmapVisible by rememberPersistedBoolean("terrainHeatmap", false)
    var userLocationVisible by rememberPersistedBoolean("userLocation", true)
    var activeDrawTool by remember { mutableStateOf<DrawingGeometry?>(null) }
    var isFreeDrawMode by remember { mutableStateOf(false) }
    var draftGeometry by remember { mutableStateOf<DrawingGeometry?>(null) }
    var draftPoints by remember { mutableStateOf<List<DrawingPoint>>(emptyList()) }
    var selectedDrawingId by remember { mutableStateOf<String?>(null) }
    var isCalibratingPdf by remember { mutableStateOf(false) }
    var calibrationFiduciaries by remember { mutableStateOf<List<Fiduciary>>(emptyList()) }
    var pendingCalibrationTap by remember { mutableStateOf<PendingCalibrationTap?>(null) }
    // Datum the sheet's MGRS is in; fiduciaries are shifted to WGS84 on save.
    var calibrationDatum by remember { mutableStateOf(Datum.WGS84) }
    var activeDrawingName by remember { mutableStateOf("") }
    var activeStrokeColor by remember { mutableIntStateOf(DrawingDefaults.DEFAULT_COLOR) }
    var activeStrokeStyle by remember { mutableStateOf(DrawingStrokeStyle.SOLID) }
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
                message = (result as DrawingMutationUiResult.Failed).message,
                persist = persist,
                onSaved = onSaved,
            )
        }
        return result
    }

    val hasPreciseLocation = liveMapLocationState == LiveMapLocationState.Precise
    val liveLocationControl = LiveMapLocationPermissionPolicy.controlFor(liveMapLocationState)

    suspend fun importSelectedPdf(uri: Uri, operationKey: String) {
        val source = runCatching {
            withContext(Dispatchers.IO) {
                importPdfMapSource(
                    context = context,
                    sourceUri = uri,
                    cameraLat = cameraLat,
                    cameraLng = cameraLng,
                    operationKey = operationKey,
                    copyJournal = documentCopyJournal,
                )
            }
        }.onFailure {
            Toast.makeText(context, pdfImportUserMessage(it), Toast.LENGTH_LONG).show()
        }.getOrNull()

        source?.let {
            if (vm.setMapSource(it)) {
                Toast.makeText(context, L10n.text("Imported %1\$s", it.displayName), Toast.LENGTH_SHORT).show()
            }
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
        val raceDetail = reconciled.identities.consumedRemintIds.size.takeIf { it > 0 }
            ?.let { L10n.text("; reconciled %1\$s live ID collision(s)", it) }
            .orEmpty()
        Toast.makeText(
            context,
            commit.message + raceDetail,
            if (commit.succeeded) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
        ).show()
    }

    suspend fun processDocumentImport(pending: PendingDocumentImport) {
        val uri = Uri.parse(pending.uri)
        when (pending.kind) {
            DocumentImportKind.PDF -> importSelectedPdf(uri, "pdf:${pending.token}")
            DocumentImportKind.MBTILES -> {
                val source = withContext(Dispatchers.IO) {
                    importMBTilesMapSource(
                        context,
                        uri,
                        "mbtiles:${pending.token}",
                        documentCopyJournal,
                    )
                }
                    ?: throw IllegalArgumentException(L10n.text("The selected file is not a readable MBTiles database"))
                if (vm.setMapSource(source)) {
                    Toast.makeText(
                        context,
                        L10n.text("Loaded offline tiles: %1\$s", source.displayName),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
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
        var terminal = false
        try {
            processDocumentImport(pending)
            terminal = true
        } catch (cancelled: CancellationException) {
            onAbandonDocumentImport(pending.token)
            throw cancelled
        } catch (failure: Throwable) {
            terminal = true
            val detail = failure.message?.takeIf { it.isNotBlank() }
                ?: L10n.text("The selected document could not be imported")
            Toast.makeText(context, Messages.importFailed(detail), Toast.LENGTH_LONG).show()
        } finally {
            if (terminal) onCompleteDocumentImport(pending.token)
        }
    }

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

    LaunchedEffect(mapSource.id) {
        if (mapSource !is PdfMapSource) {
            isCalibratingPdf = false
            calibrationFiduciaries = emptyList()
            pendingCalibrationTap = null
        }
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
    val quickAddAllowed = !isCalibratingPdf &&
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
    val draftDrawing = when {
        // measure tool takes precedence - render its polyline as draft
        // overlay so user can see the path they're laying down
        measureSession.isActive && measureSession.points.size >= 1 -> newMapDrawingFeature(
            name = "",
            geometry = DrawingGeometry.LINE,
            points = measureSession.points.map { DrawingPoint(it.first, it.second) },
            layerId = safeActiveLayerId,
            strokeColor = 0xFFFFA500.toInt(),
            fillColor = 0,
            strokeStyle = DrawingStrokeStyle.DASHED,
            density = rendererDensity,
        )
        draftGeometry != null -> newMapDrawingFeature(
            name = drawingNameOrDefault(activeDrawingName, draftGeometry!!, drawingDocument.features),
            geometry = draftGeometry!!,
            points = draftPoints,
            layerId = safeActiveLayerId,
            strokeColor = activeStrokeColor,
            fillColor = activeStrokeColor.withAlpha(0x33),
            strokeStyle = activeStrokeStyle,
            density = rendererDensity,
        )
        else -> null
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
                    fillColor = activeStrokeColor.withAlpha(0x33),
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

    fun startPdfCalibration() {
        val source = pdfSource ?: return
        vm.selectWaypoint(null)
        selectedDrawingId = null
        activeDrawTool = null
        draftGeometry = null
        draftPoints = emptyList()
        calibrationFiduciaries = (source.calibration as? Calibration.Fiduciaries)?.fids ?: emptyList()
        pendingCalibrationTap = null
        isCalibratingPdf = true
    }

    fun finishPdfCalibration() {
        val source = pdfSource ?: return
        val result = runCatching { AffineFitter.fit(calibrationFiduciaries) }.getOrNull()
        if (result == null) {
            Toast.makeText(context, L10n.text("Calibration needs 3 non-colinear points."), Toast.LENGTH_SHORT).show()
            return
        }
        if (!vm.setMapSource(source.calibrated(result.transform, calibrationFiduciaries))) return
        isCalibratingPdf = false
        pendingCalibrationTap = null
        Toast.makeText(context, L10n.text("Calibration RMS %1\$sm", result.rmsMetres.toInt()), Toast.LENGTH_SHORT).show()
    }

    fun cancelPdfCalibration() {
        isCalibratingPdf = false
        calibrationFiduciaries = emptyList()
        pendingCalibrationTap = null
    }

    Box(Modifier.fillMaxSize()) {
        CustomMapScreen(
                modifier = Modifier.fillMaxSize(),
                waypoints = waypoints,
                mapSource = mapSource,
                onlineBasemapsEnabled = onlineBasemapsEnabled,
                onlineLookupsEnabled = onlineLookupsEnabled,
                drawings = drawingDocument.features,
                drawingLayers = drawingDocument.layers,
                draftDrawing = draftDrawing,
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
                calibrationInputEnabled = isCalibratingPdf,
                calibrationFiduciaries = if (isCalibratingPdf) calibrationFiduciaries else emptyList(),
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
                headingUpEnabled = mapOrientationMode == MapOrientationMode.HEADING_UP,
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
                onCalibrationTap = { lat, lng ->
                    val tap = pdfSource?.pdfPointFor(lat, lng)
                    if (tap != null) {
                        pendingCalibrationTap = tap
                    } else {
                        Toast.makeText(context, L10n.text("Tap inside the PDF map."), Toast.LENGTH_SHORT).show()
                    }
                },
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
                }
            )

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
            elevation = centreElevation?.metres,
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

        if (onlineTilesActive && onlineTilesUnavailable) {
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

        // live track-recording badge, only while recording. Tap to stop.
        if (isRecordingTrack) {
            RecordingIndicator(
                pointCount = trackPoints.size,
                onStop = { vm.stopTrackRecording() },
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
            Box {
                CircleHudButton(Icons.Default.Menu, L10n.text("Menu")) { hamburgerOpen = true }
                DropdownMenu(
                    expanded = hamburgerOpen,
                    onDismissRequest = { hamburgerOpen = false }
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
            if (tacMapChatHudVisible(syncRoom)) {
                TacMapChatHudButton(unreadCount = unreadChatMessageCount) {
                    chatTarget = com.tacmap.sync.TacMapChatTarget.EntireRoom
                }
            }
            if (quickAddAllowed) {
                Box {
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
            UnitLabelsToggle(active = unitLabelsVisible) { unitLabelsVisible = !unitLabelsVisible }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                UndoRedoButtons(
                    canUndo = canUndo,
                    canRedo = canRedo,
                    onUndo = { if (drawingCanUndo) drawingStore.undo() else waypointStore.undo() },
                    onRedo = { if (drawingCanRedo) drawingStore.redo() else waypointStore.redo() }
                )
                LockButton(
                    locked = graphicsLocked,
                    onToggle = { graphicsLocked = !graphicsLocked }
                )
            }
        }

        if (isCalibratingPdf) {
            CalibrationBar(
                fiduciaryCount = calibrationFiduciaries.size,
                canFinish = calibrationFiduciaries.size >= 3,
                onFinish = ::finishPdfCalibration,
                onCancel = ::cancelPdfCalibration,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
                    .fillMaxWidth()
            )
        } else if (measureSession.isActive) {
            MeasureToolbar(
                session = measureSession,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 16.dp)
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
                onDrawingNameChange = { activeDrawingName = it },
                onStrokeColorChange = { activeStrokeColor = it },
                onStrokeStyleChange = { activeStrokeStyle = it },
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
                onDismiss = {
                    drawingStore.revertPreview()
                    selectedDrawingId = null
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
                onDismiss = { vm.selectWaypoint(null) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
    }

    val quickMode = quickAddEditorMode
    val capturedQuickTarget = quickAddTarget
    if (quickMode != null && capturedQuickTarget != null) {
        val initialKind = when (quickMode) {
            SymbolEditorMode.MILITARY -> WaypointKind.Military()
            SymbolEditorMode.TASK -> WaypointKind.ControlMeasure()
            SymbolEditorMode.MARKER -> WaypointKind.Marker()
        }
        SymbolEditorDialog(
            mode = quickMode,
            initialKind = initialKind,
            initialName = "",
            crosshairLat = capturedQuickTarget.latitude,
            crosshairLng = capturedQuickTarget.longitude,
            title = when (quickMode) {
                SymbolEditorMode.MILITARY -> L10n.text("New Military Unit")
                SymbolEditorMode.TASK -> L10n.text("New Tactical Task")
                SymbolEditorMode.MARKER -> L10n.text("New Marker")
            },
            actionLabel = L10n.text("Place"),
            submissionError = quickAddCreationError,
            onDismiss = {
                quickAddEditorMode = null
                quickAddTarget = null
                quickAddCreationError = null
            },
            onConfirm = { name, kind, higherFormation, uniqueIdentifier, reinforcementStatus ->
                val added = Waypoint(
                    name = name,
                    latitude = capturedQuickTarget.latitude,
                    longitude = capturedQuickTarget.longitude,
                    kind = kind,
                    higherFormation = higherFormation,
                    uniqueIdentifier = uniqueIdentifier,
                    reinforcementStatus = reinforcementStatus,
                    layerId = capturedQuickTarget.layerId
                )
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
                        Toast.makeText(context, L10n.text("Added %1\$s at crosshair", name), Toast.LENGTH_SHORT).show()
                        quickAddCreationError = null
                        quickAddEditorMode = null
                        quickAddTarget = null
                    }
                    is DurableSymbolCreation.Failed -> quickAddCreationError = result.message
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
            store = waypointStore,
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
                activeDrawTool = DrawingGeometry.POINT
                activeDrawingName = defaultDrawingName(DrawingGeometry.POINT, drawingDocument.features)
                draftGeometry = null
                draftPoints = emptyList()
                showDrawingSheet = false
            },
            onStartDraft = { geometry ->
                vm.selectWaypoint(null)
                selectedDrawingId = null
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
            }
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

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
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
            onDismiss = { showOpsecSettings = false },
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
                        L10n.text("Recording will stop and the encrypted track log, including all ") +
                            L10n.text("%1\$s saved point(s), will be permanently deleted.", trackPoints.size)
                    } else {
                        L10n.text("The encrypted track log and all %1\$s saved point(s) ", trackPoints.size) +
                            L10n.text("will be permanently deleted. Export GPX first if you need a copy.")
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
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = {
                    val result = checkedDrawingMutation(pending.intent, pending.persist)
                    if (result.saved) {
                        pendingDrawingMutation = null
                        pending.onSaved()
                    } else {
                        pendingDrawingMutation = pending.copy(
                            message = (result as DrawingMutationUiResult.Failed).message
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

    tilingProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = { /* non-cancelable while baking */ },
            confirmButton = {},
            title = { Text(L10n.text("Generating offline tiles")) },
            text = {
                Column {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = if (total > 0) done.toFloat() / total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (total > 0) L10n.text("%1\$s / %2\$s tiles", done, total) else L10n.text("Preparing…"),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        )
    }

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
            retainedImportedMapName = retainedImportedMap?.displayName,
            importedMapActive = importedMapLoaded,
            onReturnToImportedMap = {
                vm.restoreRetainedImportedMap()
                showLayersSheet = false
            },
            hasPdfMap = pdfSource != null,
            hasOfflineTiles = mapSource is OfflineTileMapSourceAndroid,
            onCalibratePdf = {
                showLayersSheet = false
                startPdfCalibration()
            },
            onGenerateTiles = {
                val pdf = pdfSource
                showLayersSheet = false
                if (pdf == null) {
                    Toast.makeText(context, L10n.text("Load a PDF map first"), Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        tilingProgress = 0 to 0
                        val path = com.tacmap.calibration.PdfTiler.generate(context, pdf) { p ->
                            tilingProgress = p.done to p.total
                        }
                        tilingProgress = null
                        if (path != null) {
                            val activated = com.tacmap.calibration.OfflineTileMapSourceAndroid.open(path)
                                ?.let { vm.setMapSource(it) }
                                ?: false
                            if (activated) {
                                Toast.makeText(context, L10n.text("Offline tiles ready"), Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(
                                context,
                                L10n.text("Couldn't generate tiles — calibrate the PDF first (3+ fiduciaries)."),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            },
            onUnloadPdf = {
                showLayersSheet = false
                cancelPdfCalibration()
                vm.unloadPdfMap()
            },
            onUnloadOfflineTiles = {
                showLayersSheet = false
                vm.unloadOfflineTiles()
            },
            onDismiss = { showLayersSheet = false }
        )
    }

    if (showImportExportSheet) {
        ImportExportSheet(
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
                scope.launch {
                    shareGeoJson(
                        context = context,
                        waypoints = waypoints,
                        drawings = drawingDocument.features,
                        layers = drawingDocument.layers,
                    )
                }
            },
            onExportGpx = {
                showImportExportSheet = false
                scope.launch { shareGpx(context = context, points = trackPoints) }
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
            hasSavedTrack = trackPoints.isNotEmpty(),
            isRecordingTrack = isRecordingTrack,
            onDiscardTrack = {
                showImportExportSheet = false
                showDiscardTrackConfirmation = true
            },
            onDismiss = { showImportExportSheet = false }
        )
    }

    pendingCalibrationTap?.let { tap ->
        CalibrationInputDialog(
            point = tap,
            fiduciaryNumber = calibrationFiduciaries.size + 1,
            datum = calibrationDatum,
            onDatumChange = { calibrationDatum = it },
            onDismiss = { pendingCalibrationTap = null },
            onSave = { mgrs, label ->
                val parsed = MgrsFormatter.parse(mgrs)
                if (parsed == null) {
                    false
                } else {
                    // MGRS is in the sheet's datum; shift to WGS84 before storing.
                    val (lat, lng) = calibrationDatum.toWgs84(parsed.first, parsed.second)
                    calibrationFiduciaries = calibrationFiduciaries + Fiduciary(
                        pdfX = tap.pdfX,
                        pdfY = tap.pdfY,
                        mgrs = mgrs.trim().uppercase(),
                        latitude = lat,
                        longitude = lng,
                        label = label.trim().ifBlank { null }
                    )
                    pendingCalibrationTap = null
                    true
                }
            }
        )
    }
}

internal fun tacMapChatHudVisible(room: String?): Boolean = room?.startsWith("3:") == true
