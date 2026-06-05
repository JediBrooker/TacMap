package com.tacticalmaps.map

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
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
import com.tacticalmaps.calibration.AffineFitter
import com.tacticalmaps.calibration.Calibration
import com.tacticalmaps.calibration.Fiduciary
import com.tacticalmaps.calibration.Datum
import com.tacticalmaps.calibration.GeoPdfParser
import com.tacticalmaps.calibration.OfflineTileMapSourceAndroid
import com.tacticalmaps.calibration.OpenStreetMapSourceAndroid
import com.tacticalmaps.calibration.PdfMapSource
import com.tacticalmaps.calibration.PdfPageRenderer
import com.tacticalmaps.calibration.Wgs84Coordinate
import com.tacticalmaps.drawings.DrawingDocument
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.drawings.DrawingStore
import com.tacticalmaps.drawings.DrawingStrokeStyle
import com.tacticalmaps.export.GeoJsonExporter
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.WaypointStore
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

    val isBrowsing by vm.isBrowsing.collectAsState()
    val pendingTarget by vm.pendingCameraTarget.collectAsState()
    val cameraLat by vm.cameraLat.collectAsState()
    val cameraLng by vm.cameraLng.collectAsState()
    val mapSource by vm.mapSource.collectAsState()
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
    var hamburgerOpen by remember { mutableStateOf(false) }
    var activeDrawingLayerId by remember { mutableStateOf(DrawingDocument.DEFAULT_LAYER_ID) }
    val measureSession = remember { MeasureSession() }
    // Persisted to SharedPreferences so layer toggles survive app relaunch
    // (previously plain remember{} state that reset on every launch).
    var unitLabelsVisible by rememberPersistedBoolean("unitLabels", false)
    var taskLabelsVisible by rememberPersistedBoolean("taskLabels", false)
    var drawingLabelsVisible by rememberPersistedBoolean("drawingLabels", false)
    var mgrsGridVisible by rememberPersistedBoolean("mgrsGrid", false)
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
                ?: com.tacticalmaps.drawings.DrawingDocument.DEFAULT_LAYER_ID
            val parsed = runCatching {
                com.tacticalmaps.export.GeoJsonImporter.parse(
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
        // Measure tool takes precedence — render its polyline as a draft
        // overlay so the user can see the path they're laying down.
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
        // Measure-mode tap is captured here too — when active it intercepts
        // taps before the drawing branch so the user can lay down a route
        // without picking a draw tool.
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
                drawings = drawingDocument.features,
                drawingLayers = drawingDocument.layers,
                draftDrawing = draftDrawing,
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
                unitLabelsVisible = unitLabelsVisible,
                taskLabelsVisible = taskLabelsVisible,
                drawingLabelsVisible = drawingLabelsVisible,
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

        // MGRS header — anchored to the top edge but offset by the
        // status-bar / camera-cutout inset so the dynamic island /
        // hole-punch doesn't cover it.
        MgrsHeader(
            mgrs = vm.headerMgrs,
            wgs84 = vm.headerWgs84,
            isBrowsing = isBrowsing,
            accuracy = lastLocation?.accuracy?.toDouble(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .fillMaxWidth(),
            onDropPin = {
                val (lat, lng) = vm.headerCoordinate
                val mgrs = vm.headerMgrs
                val activeLayerId = drawingDocument.layers
                    .firstOrNull { it.isVisible }?.id
                    ?: com.tacticalmaps.drawings.DrawingDocument.DEFAULT_LAYER_ID
                waypointStore.add(
                    com.tacticalmaps.waypoints.Waypoint(
                        name = mgrs,
                        latitude = lat,
                        longitude = lng,
                        kind = com.tacticalmaps.waypoints.WaypointKind.Generic,
                        layerId = activeLayerId
                    )
                )
            }
        )

        // Hamburger (left) + Compass (right), pinned just below the
        // MGRS header (which is ~96dp tall after the recent tighten).
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 100.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                CircleHudButton(Icons.Default.Menu) { hamburgerOpen = true }
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
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Import PDF Map") },
                        onClick = {
                            hamburgerOpen = false
                            pdfImportLauncher.launch(arrayOf("application/pdf"))
                        },
                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Import Offline Tiles") },
                        onClick = {
                            hamburgerOpen = false
                            // MBTiles has no standard MIME type — show all files.
                            mbtilesImportLauncher.launch(arrayOf("*/*"))
                        },
                        leadingIcon = { Icon(Icons.Default.Map, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Import GeoJSON") },
                        onClick = {
                            hamburgerOpen = false
                            geoJsonImportLauncher.launch(arrayOf("application/geo+json", "application/json", "*/*"))
                        },
                        leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Export GeoJSON") },
                        onClick = {
                            hamburgerOpen = false
                            shareGeoJson(
                                context = context,
                                waypoints = waypoints,
                                drawings = drawingDocument.features,
                                layers = drawingDocument.layers
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) }
                    )
                    HorizontalDivider()
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

    if (showLayersSheet) {
        LayersSheet(
            mgrsGridVisible = mgrsGridVisible,
            unitLabelsVisible = unitLabelsVisible,
            taskLabelsVisible = taskLabelsVisible,
            drawingLabelsVisible = drawingLabelsVisible,
            onMgrsGridChange = { mgrsGridVisible = it },
            onUnitLabelsChange = { unitLabelsVisible = it },
            onTaskLabelsChange = { taskLabelsVisible = it },
            onDrawingLabelsChange = { drawingLabelsVisible = it },
            hasPdfMap = pdfSource != null,
            hasOfflineTiles = mapSource is OfflineTileMapSourceAndroid,
            onCalibratePdf = {
                showLayersSheet = false
                startPdfCalibration()
            },
            onUnloadPdf = {
                showLayersSheet = false
                cancelPdfCalibration()
                vm.unloadPdfMap()
            },
            onUnloadOfflineTiles = {
                showLayersSheet = false
                vm.setMapSource(OpenStreetMapSourceAndroid())
            },
            onDismiss = { showLayersSheet = false }
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
