package com.tacmap.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingPoint
import com.tacmap.drawings.DrawingStore
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.export.GeoJsonExporter
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.waypoints.WaypointStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun MapScreen(
    vm: MapViewModel = viewModel(),
    isPurchased: Boolean = true,
    trialDaysRemaining: Int = 0,
    onUnlock: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val onlineBasemapsEnabled by vm.opsec.onlineBasemaps.collectAsState()
    val isBrowsing by vm.isBrowsing.collectAsState()
    val pendingTarget by vm.pendingCameraTarget.collectAsState()
    val cameraLat by vm.cameraLat.collectAsState()
    val cameraLng by vm.cameraLng.collectAsState()
    val centreElevation by vm.centreElevation.collectAsState()
    val isRecordingTrack by vm.trackRecorder.isRecording.collectAsState()
    val trackPoints by vm.trackRecorder.points.collectAsState()
    val mapSource by vm.mapSource.collectAsState()

    /// Is anything on screen actually pulling tiles off the internet right now?
    /// Google's own satellite basemap counts, and so does no-source-selected,
    /// which falls back to it.
    val onlineTilesActive = onlineBasemapsEnabled && (
        mapSource == null ||
            mapSource is com.tacmap.calibration.SatelliteMapSourceAndroid ||
            mapSource is com.tacmap.calibration.OnlineRasterMapSourceAndroid
        )
    val waypointStore = remember { WaypointStore(context) }
    val waypoints by waypointStore.waypoints.collectAsState()
    val drawingStore = remember { DrawingStore(context) }
    val drawingDocument by drawingStore.document.collectAsState()
    val drawingCanUndo by drawingStore.canUndo.collectAsState()
    val drawingCanRedo by drawingStore.canRedo.collectAsState()
    val waypointCanUndo by waypointStore.canUndo.collectAsState()
    val waypointCanRedo by waypointStore.canRedo.collectAsState()
    val canUndo = drawingCanUndo || waypointCanUndo
    val canRedo = drawingCanRedo || waypointCanRedo
    val lastLocation by vm.locationService.lastLocation.collectAsState()
    val selectedWaypointId by vm.selectedWaypointId.collectAsState()
    val mapBearingDegrees by vm.mapBearingDegrees.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    var showWaypointSheet by remember { mutableStateOf(false) }
    var showDrawingSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showLayersSheet by remember { mutableStateOf(false) }
    var showImportExportSheet by remember { mutableStateOf(false) }
    var hamburgerOpen by remember { mutableStateOf(false) }
    /// weather/UAV widget target = (lat, lng) of map centre, null when closed
    var weatherTarget by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showAppLockSetup by remember { mutableStateOf(false) }
    val appLock = remember { com.tacmap.app.AppLock(context) }
    var showSyncDialog by remember { mutableStateOf(false) }
    val syncManager = remember {
        com.tacmap.sync.SyncManager(waypointStore, drawingStore, scope, context)
    }
    val syncStatus by syncManager.status.collectAsState()
    val presencePeers by syncManager.peers.collectAsState()

    // Wire the location provider so SyncManager can send presence updates.
    syncManager.locationProvider = { vm.locationService.lastLocation.value }

    // Snackbar for remote sync conflict notifications (Fix #3).
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(Unit) {
        syncManager.remoteUpdates.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = androidx.compose.material3.SnackbarDuration.Short)
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
    var taskLabelsVisible by rememberPersistedBoolean("taskLabels", false)
    var drawingLabelsVisible by rememberPersistedBoolean("drawingLabels", false)
    var mgrsGridVisible by rememberPersistedBoolean("mgrsGrid", false)
    var terrainHeatmapVisible by rememberPersistedBoolean("terrainHeatmap", false)
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
    var activeStrokeColor by remember { mutableStateOf(DrawingDefaults.DEFAULT_COLOR) }
    var activeStrokeStyle by remember { mutableStateOf(DrawingStrokeStyle.SOLID) }

    var hasLocationPermission by remember {
        mutableStateOf(vm.locationService.hasPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            hasLocationPermission = true
        }
    }

    val pdfImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val source = runCatching {
                withContext(Dispatchers.IO) {
                    importPdfMapSource(
                        context = context,
                        sourceUri = uri,
                        cameraLat = cameraLat,
                        cameraLng = cameraLng
                    )
                }
            }.onFailure {
                Toast.makeText(context, "Unable to import PDF map.", Toast.LENGTH_SHORT).show()
            }.getOrNull()

            source?.let {
                vm.setMapSource(it)
                Toast.makeText(context, "Imported ${it.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val mbtilesImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val source = runCatching {
                withContext(Dispatchers.IO) { importMBTilesMapSource(context, uri) }
            }.getOrNull()
            if (source == null) {
                Toast.makeText(context, "Couldn't open this file as MBTiles.", Toast.LENGTH_SHORT).show()
            } else {
                vm.setMapSource(source)
                Toast.makeText(context, "Loaded offline tiles: ${source.displayName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val geoJsonImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val json = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    }
                }
            }.getOrNull()
            if (json == null) {
                Toast.makeText(context, "Couldn't read file", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val fallback = drawingDocument.layers
                .firstOrNull { it.id == activeDrawingLayerId }?.id
                ?: drawingDocument.layers.firstOrNull()?.id
                ?: com.tacmap.drawings.DrawingDocument.DEFAULT_LAYER_ID
            val parsed = runCatching {
                com.tacmap.export.GeoJsonImporter.parse(
                    json = json,
                    existingLayers = drawingDocument.layers,
                    fallbackLayerId = fallback
                )
            }.getOrElse { e ->
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            parsed.newLayers.forEach { drawingStore.addLayerVerbatim(it) }
            parsed.drawings.forEach { drawingStore.addFeature(it) }
            parsed.waypoints.forEach { waypointStore.add(it) }
            Toast.makeText(
                context,
                "Imported ${parsed.waypoints.size} waypoint(s) and ${parsed.drawings.size} drawing(s)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val kmlImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val fallback = drawingDocument.layers
                .firstOrNull { it.id == activeDrawingLayerId }?.id
                ?: drawingDocument.layers.firstOrNull()?.id
                ?: com.tacmap.drawings.DrawingDocument.DEFAULT_LAYER_ID
            // Read bytes (not text) so a KMZ zip survives intact.
            val parsed = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        com.tacmap.export.KmlImporter.parseStream(
                            input = stream,
                            existingLayers = drawingDocument.layers,
                            fallbackLayerId = fallback
                        )
                    } ?: throw IllegalStateException("Couldn't read file")
                }
            }.getOrElse { e ->
                Toast.makeText(context, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            parsed.newLayers.forEach { drawingStore.addLayerVerbatim(it) }
            parsed.drawings.forEach { drawingStore.addFeature(it) }
            parsed.waypoints.forEach { waypointStore.add(it) }
            Toast.makeText(
                context,
                "Imported ${parsed.waypoints.size} waypoint(s) and ${parsed.drawings.size} drawing(s)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!vm.locationService.hasPermission()) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    DisposableEffect(lifecycleOwner, hasLocationPermission) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (hasLocationPermission) vm.locationService.start()
                }
                Lifecycle.Event.ON_STOP -> vm.locationService.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (hasLocationPermission &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            vm.locationService.start()
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.locationService.stop()
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
    val draftDrawing = when {
        // measure tool takes precedence - render its polyline as draft
        // overlay so user can see the path they're laying down
        measureSession.isActive && measureSession.points.size >= 1 -> DrawingFeature(
            name = "",
            geometry = DrawingGeometry.LINE,
            points = measureSession.points.map { DrawingPoint(it.first, it.second) },
            layerId = safeActiveLayerId,
            strokeColor = 0xFFFFA500.toInt(),
            fillColor = 0,
            strokeWidth = DrawingDefaults.STROKE_WIDTH,
            strokeStyle = DrawingStrokeStyle.DASHED
        )
        draftGeometry != null -> DrawingFeature(
            name = drawingNameOrDefault(activeDrawingName, draftGeometry!!, drawingDocument.features),
            geometry = draftGeometry!!,
            points = draftPoints,
            layerId = safeActiveLayerId,
            strokeColor = activeStrokeColor,
            fillColor = activeStrokeColor.withAlpha(0x33),
            strokeWidth = DrawingDefaults.STROKE_WIDTH,
            strokeStyle = activeStrokeStyle
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
            drawingStore.addFeature(
                DrawingFeature(
                    name = drawingNameOrDefault(activeDrawingName, geometry, drawingDocument.features),
                    geometry = geometry,
                    points = points,
                    layerId = safeActiveLayerId,
                    strokeColor = activeStrokeColor,
                    fillColor = activeStrokeColor.withAlpha(0x33),
                    strokeWidth = DrawingDefaults.STROKE_WIDTH,
                    strokeStyle = activeStrokeStyle
                )
            )
            stopDrawing()
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
                drawingStore.addFeature(
                    DrawingFeature(
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
                        strokeWidth = DrawingDefaults.STROKE_WIDTH,
                        strokeStyle = activeStrokeStyle
                    )
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
            Toast.makeText(context, "Calibration needs 3 non-colinear points.", Toast.LENGTH_SHORT).show()
            return
        }
        vm.setMapSource(source.calibrated(result.transform, calibrationFiduciaries))
        isCalibratingPdf = false
        pendingCalibrationTap = null
        Toast.makeText(context, "Calibration RMS ${result.rmsMetres.toInt()}m", Toast.LENGTH_SHORT).show()
    }

    fun cancelPdfCalibration() {
        isCalibratingPdf = false
        calibrationFiduciaries = emptyList()
        pendingCalibrationTap = null
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMapScreen(
                modifier = Modifier.fillMaxSize(),
                waypoints = waypoints,
                mapSource = mapSource,
                onlineBasemapsEnabled = onlineBasemapsEnabled,
                drawings = drawingDocument.features,
                drawingLayers = drawingDocument.layers,
                draftDrawing = draftDrawing,
                graphicsLocked = graphicsLocked,
                drawingInputEnabled = activeDrawTool != null || measureSession.isActive,
                freeDrawActive = isFreeDrawMode,
                onFreeDrawPoint = { lat, lng ->
                    draftPoints = (draftPoints + DrawingPoint(lat, lng)).dedupeTrailingPoints()
                },
                onFreeDrawEnd = {
                    finishDraft()
                    isFreeDrawMode = false
                    activeDrawTool = null
                    draftGeometry = null
                    draftPoints = emptyList()
                },
                calibrationInputEnabled = isCalibratingPdf,
                mgrsGridVisible = mgrsGridVisible,
                terrainHeatmapVisible = terrainHeatmapVisible,
                unitLabelsVisible = unitLabelsVisible,
                taskLabelsVisible = taskLabelsVisible,
                drawingLabelsVisible = drawingLabelsVisible,
                peers = presencePeers,
                selectedDrawingId = selectedDrawingId,
                selectedWaypointId = selectedWaypointId,
                calibrationFiduciaries = calibrationFiduciaries,
                myLocationEnabled = hasLocationPermission,
                pendingTarget = pendingTarget,
                resetNorthRequests = vm.resetNorthRequests,
                onConsumePendingTarget = vm::consumePendingCameraTarget,
                onCameraIdle = { lat, lng, byUser ->
                    vm.onCameraIdle(lat, lng, byUser)
                },
                onBearingChanged = vm::onMapBearingChanged,
                onMarkerTap = { wp ->
                    selectedDrawingId = null
                    vm.selectWaypoint(wp.id)
                },
                onWaypointMoved = { wp, lat, lng ->
                    waypointStore.update(wp.copy(latitude = lat, longitude = lng))
                },
                onDrawingTap = ::handleDrawingTap,
                onCalibrationTap = { lat, lng ->
                    val tap = pdfSource?.pdfPointFor(lat, lng)
                    if (tap != null) {
                        pendingCalibrationTap = tap
                    } else {
                        Toast.makeText(context, "Tap inside the PDF map.", Toast.LENGTH_SHORT).show()
                    }
                },
                onDrawingFeatureTap = { featureId ->
                    vm.selectWaypoint(null)
                    selectedDrawingId = featureId
                },
                onVertexMoved = { featureId, vertexIndex, lat, lng ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        drawingStore.updateFeature(feature.withVertexMoved(vertexIndex, lat, lng))
                    }
                },
                onVertexInserted = { featureId, atIndex, lat, lng ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        drawingStore.updateFeature(feature.withVertexInserted(atIndex, lat, lng))
                    }
                },
                onShapeMoved = { featureId, deltaLat, deltaLng ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        drawingStore.updateFeature(
                            feature.copy(
                                points = feature.points.map { point ->
                                    point.copy(
                                        latitude = point.latitude + deltaLat,
                                        longitude = point.longitude + deltaLng
                                    )
                                }
                            )
                        )
                    }
                },
                onVertexDeleted = { featureId, vertexIndex ->
                    drawingDocument.features.firstOrNull { it.id == featureId }?.let { feature ->
                        feature.withVertexRemovedOrNull(vertexIndex)?.let {
                            drawingStore.updateFeature(it)
                        }
                    }
                },
                onMapTap = {
                    if (selectedWaypointId != null) vm.selectWaypoint(null)
                    selectedDrawingId = null
                }
            )

        CrosshairOverlay()

        // MGRS header - anchored to top edge, offset by status-bar inset
        // so dynamic island / hole-punch doesn't cover it. The online-tiles
        // warning stacks above it in the same column, otherwise the header
        // (drawn after the map) would sit on top of the banner and hide it.
        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .fillMaxWidth()
        ) {
        if (onlineTilesActive) OnlineTilesBanner()
        MgrsHeader(
            mgrs = vm.headerMgrs,
            wgs84 = vm.headerWgs84,
            isBrowsing = isBrowsing,
            accuracy = lastLocation?.accuracy?.toDouble(),
            elevation = centreElevation?.metres,
            elevationApprox = centreElevation?.isStale == true,
            utm = vm.headerUtm,
            syncConnected = syncStatus == com.tacmap.sync.SyncManager.Status.CONNECTED,
            // align + statusBarsPadding moved to the wrapping Column so the
            // banner shares the same top inset.
            modifier = Modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            onDropPin = {
                val (lat, lng) = vm.headerCoordinate
                val mgrs = vm.headerMgrs
                val activeLayerId = drawingDocument.layers
                    .firstOrNull { it.isVisible }?.id
                    ?: com.tacmap.drawings.DrawingDocument.DEFAULT_LAYER_ID
                waypointStore.add(
                    com.tacmap.waypoints.Waypoint(
                        name = mgrs,
                        latitude = lat,
                        longitude = lng,
                        kind = com.tacmap.waypoints.WaypointKind.Generic,
                        layerId = activeLayerId
                    )
                )
            }
        )
        }

        // live track-recording badge, only while recording. Tap to stop.
        if (isRecordingTrack) {
            RecordingIndicator(
                pointCount = trackPoints.size,
                onStop = { vm.trackRecorder.stop() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 104.dp)
            )
        }

        // hamburger (left) + compass (right), pinned below MGRS header
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 100.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                CircleHudButton(Icons.Default.Menu, "Menu") { hamburgerOpen = true }
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
                                        "Free trial — $trialDaysRemaining ${if (trialDaysRemaining == 1) "day" else "days"} left"
                                    else "Free trial ended"
                                )
                            },
                            onClick = {},
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Unlock Full Version") },
                            onClick = {
                                hamburgerOpen = false
                                onUnlock()
                            },
                            leadingIcon = { Icon(Icons.Default.LockOpen, contentDescription = null) }
                        )
                        HorizontalDivider()
                    }
                    DropdownMenuItem(
                        text = { Text("Search") },
                        onClick = {
                            hamburgerOpen = false
                            showSearchDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Symbology") },
                        onClick = {
                            hamburgerOpen = false
                            showWaypointSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.Place, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Drawings") },
                        onClick = {
                            hamburgerOpen = false
                            showDrawingSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.Gesture, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Layers and Labels") },
                        onClick = {
                            hamburgerOpen = false
                            showLayersSheet = true
                        },
                        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Measure") },
                        onClick = {
                            hamburgerOpen = false
                            stopDrawing()
                            measureSession.start()
                        },
                        leadingIcon = { Icon(Icons.Default.Straighten, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Weather & UAV Safety") },
                        onClick = {
                            hamburgerOpen = false
                            weatherTarget = vm.headerCoordinate
                        },
                        leadingIcon = { Icon(Icons.Default.Air, contentDescription = null) }
                    )
                    HorizontalDivider()
                    // all import/export behind one item, keeps menu short
                    DropdownMenuItem(
                        text = { Text("Import / Export") },
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
                                if (isRecordingTrack) "Stop Track Recording (${trackPoints.size} pts)"
                                else "Start Track Recording"
                            )
                        },
                        onClick = {
                            hamburgerOpen = false
                            if (isRecordingTrack) vm.stopTrackRecording() else vm.startTrackRecording()
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
                        text = { Text("Unit Sync") },
                        onClick = {
                            hamburgerOpen = false
                            showSyncDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("App Lock") },
                        onClick = {
                            hamburgerOpen = false
                            showAppLockSetup = true
                        },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("About & Credits") },
                        onClick = {
                            hamburgerOpen = false
                            showAboutDialog = true
                        },
                        leadingIcon = { Icon(Icons.Default.Info, contentDescription = null) }
                    )
                }
            }
            UnitLabelsToggle(active = unitLabelsVisible) { unitLabelsVisible = !unitLabelsVisible }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CompassChip(
                    mapOrientationDegrees = mapBearingDegrees,
                    onTap = vm::requestResetNorth
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
                onFeatureChange = drawingStore::updateFeature,
                onFeatureChangeDraft = drawingStore::updateFeatureNoUndo,
                onDelete = {
                    drawingStore.removeFeature(selectedDrawing.id)
                    selectedDrawingId = null
                },
                onDismiss = { selectedDrawingId = null },
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
            CentrePill(
                onClick = { vm.centreOnUser() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            )
        }

        // Snackbar for remote sync conflict notifications
        androidx.compose.material3.SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
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
            onLayerVisibilityChange = drawingStore::setLayerVisible,
            onAddLayer = drawingStore::addLayer,
            onDeleteFeature = drawingStore::removeFeature
        )
    }

    if (showSearchDialog) {
        SearchDialog(
            waypoints = waypoints,
            drawings = drawingDocument.features,
            cameraLat = cameraLat,
            cameraLng = cameraLng,
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
        WeatherDialog(lat = lat, lng = lng, onDismiss = { weatherTarget = null })
    }

    if (showAppLockSetup) {
        com.tacmap.app.AppLockSetupDialog(appLock = appLock, onDismiss = { showAppLockSetup = false })
    }

    if (showSyncDialog) {
        com.tacmap.sync.SyncDialog(manager = syncManager, onDismiss = { showSyncDialog = false })
    }

    tilingProgress?.let { (done, total) ->
        AlertDialog(
            onDismissRequest = { /* non-cancelable while baking */ },
            confirmButton = {},
            title = { Text("Generating offline tiles") },
            text = {
                Column {
                    @Suppress("DEPRECATION")
                    LinearProgressIndicator(
                        progress = if (total > 0) done.toFloat() / total else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (total > 0) "$done / $total tiles" else "Preparing…",
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        )
    }

    if (showLayersSheet) {
        LayersSheet(
            mgrsGridVisible = mgrsGridVisible,
            unitLabelsVisible = unitLabelsVisible,
            taskLabelsVisible = taskLabelsVisible,
            drawingLabelsVisible = drawingLabelsVisible,
            terrainHeatmapVisible = terrainHeatmapVisible,
            onMgrsGridChange = { mgrsGridVisible = it },
            onTerrainHeatmapChange = { terrainHeatmapVisible = it },
            onUnitLabelsChange = { unitLabelsVisible = it },
            onTaskLabelsChange = { taskLabelsVisible = it },
            onDrawingLabelsChange = { drawingLabelsVisible = it },
            drawingLayers = drawingDocument.layers,
            drawingFeatures = drawingDocument.features,
            onSetLayerVisible = { id, v -> drawingStore.setLayerVisible(id, v) },
            activeBaseMap = mapSource.let { ms ->
                when (ms) {
                    is OnlineRasterMapSourceAndroid ->
                        if (ms.style == BasemapStyle.TERRAIN) BaseMap.TERRAIN else BaseMap.ESRI_SATELLITE
                    else -> BaseMap.SATELLITE
                }
            },
            onSelectBaseMap = { vm.selectBaseMap(it) },
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
                    Toast.makeText(context, "Load a PDF map first", Toast.LENGTH_SHORT).show()
                } else {
                    scope.launch {
                        tilingProgress = 0 to 0
                        val path = com.tacmap.calibration.PdfTiler.generate(context, pdf) { p ->
                            tilingProgress = p.done to p.total
                        }
                        tilingProgress = null
                        if (path != null) {
                            com.tacmap.calibration.OfflineTileMapSourceAndroid.open(path)
                                ?.let { vm.setMapSource(it) }
                            Toast.makeText(context, "Offline tiles ready", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(
                                context,
                                "Couldn't generate tiles — calibrate the PDF first (3+ fiduciaries).",
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
                vm.restoreOnlineBasemap()
            },
            onDismiss = { showLayersSheet = false }
        )
    }

    if (showImportExportSheet) {
        ImportExportSheet(
            onImportPdf = {
                showImportExportSheet = false
                pdfImportLauncher.launch(arrayOf("application/pdf"))
            },
            onImportTiles = {
                showImportExportSheet = false
                // MBTiles has no standard MIME type, show all files.
                mbtilesImportLauncher.launch(arrayOf("*/*"))
            },
            onImportGeoJson = {
                showImportExportSheet = false
                geoJsonImportLauncher.launch(arrayOf("application/geo+json", "application/json", "*/*"))
            },
            onImportKml = {
                showImportExportSheet = false
                // KML/KMZ have no reliable MIME registration across providers, show all files.
                kmlImportLauncher.launch(arrayOf(
                    "application/vnd.google-earth.kml+xml",
                    "application/vnd.google-earth.kmz",
                    "*/*"
                ))
            },
            onExportGeoJson = {
                showImportExportSheet = false
                shareGeoJson(
                    context = context,
                    waypoints = waypoints,
                    drawings = drawingDocument.features,
                    layers = drawingDocument.layers
                )
            },
            onExportGpx = {
                showImportExportSheet = false
                shareGpx(context = context, points = trackPoints)
            },
            onExportAllData = {
                showImportExportSheet = false
                exportAllData(
                    context = context,
                    waypoints = waypoints,
                    drawings = drawingDocument.features,
                    layers = drawingDocument.layers
                )
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
