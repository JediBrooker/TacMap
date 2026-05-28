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
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tacticalmaps.calibration.AffineFitter
import com.tacticalmaps.calibration.Calibration
import com.tacticalmaps.calibration.Fiduciary
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
fun MapScreen(vm: MapViewModel = viewModel()) {
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
    val lastLocation by vm.locationService.lastLocation.collectAsState()
    val selectedWaypointId by vm.selectedWaypointId.collectAsState()
    val mapBearingDegrees by vm.mapBearingDegrees.collectAsState()

    var showWaypointSheet by remember { mutableStateOf(false) }
    var showDrawingSheet by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var hamburgerOpen by remember { mutableStateOf(false) }
    var activeDrawingLayerId by remember { mutableStateOf(DrawingDocument.DEFAULT_LAYER_ID) }
    val measureSession = remember { MeasureSession() }
    var unitLabelsVisible by remember { mutableStateOf(true) }
    var taskLabelsVisible by remember { mutableStateOf(true) }
    var drawingLabelsVisible by remember { mutableStateOf(true) }
    var mgrsGridVisible by remember { mutableStateOf(false) }
    var activeDrawTool by remember { mutableStateOf<DrawingGeometry?>(null) }
    var draftGeometry by remember { mutableStateOf<DrawingGeometry?>(null) }
    var draftPoints by remember { mutableStateOf<List<DrawingPoint>>(emptyList()) }
    var selectedDrawingId by remember { mutableStateOf<String?>(null) }
    var isCalibratingPdf by remember { mutableStateOf(false) }
    var calibrationFiduciaries by remember { mutableStateOf<List<Fiduciary>>(emptyList()) }
    var pendingCalibrationTap by remember { mutableStateOf<PendingCalibrationTap?>(null) }
    var activeDrawingName by remember { mutableStateOf("") }
    var activeStrokeColor by remember { mutableStateOf(DrawingDefaults.DEFAULT_COLOR) }
    var activeStrokeStyle by remember { mutableStateOf(DrawingStrokeStyle.SOLID) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.locationService.start()
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
        if (vm.locationService.hasPermission()) {
            vm.locationService.start()
        } else {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
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
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            waypoints = waypoints,
            mapSource = mapSource,
            drawings = drawingDocument.features,
            drawingLayers = drawingDocument.layers,
            draftDrawing = draftDrawing,
            drawingInputEnabled = activeDrawTool != null || measureSession.isActive,
            unitLabelsVisible = unitLabelsVisible,
            taskLabelsVisible = taskLabelsVisible,
            drawingLabelsVisible = drawingLabelsVisible,
            mgrsGridVisible = mgrsGridVisible,
            selectedDrawingId = selectedDrawingId,
            selectedWaypointId = selectedWaypointId,
            calibrationInputEnabled = isCalibratingPdf,
            pendingTarget = pendingTarget,
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
            onDrawingMove = { featureId, deltaLat, deltaLng ->
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
                    selectedDrawingId = featureId
                }
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
                    DropdownMenuItem(
                        text = { Text("Search") },
                        onClick = {
                            hamburgerOpen = false
                            showSearchDialog = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Symbology") },
                        onClick = {
                            hamburgerOpen = false
                            showWaypointSheet = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Draw") },
                        onClick = {
                            hamburgerOpen = false
                            showDrawingSheet = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Measure") },
                        onClick = {
                            hamburgerOpen = false
                            stopDrawing()
                            measureSession.start()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (unitLabelsVisible) "✓ Unit Labels" else "Unit Labels") },
                        onClick = {
                            unitLabelsVisible = !unitLabelsVisible
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (taskLabelsVisible) "✓ Task Labels" else "Task Labels") },
                        onClick = {
                            taskLabelsVisible = !taskLabelsVisible
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (drawingLabelsVisible) "✓ Drawing Labels" else "Drawing Labels") },
                        onClick = {
                            drawingLabelsVisible = !drawingLabelsVisible
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(if (mgrsGridVisible) "✓ MGRS Grid" else "MGRS Grid") },
                        onClick = {
                            mgrsGridVisible = !mgrsGridVisible
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Import PDF Map") },
                        onClick = {
                            hamburgerOpen = false
                            pdfImportLauncher.launch(arrayOf("application/pdf"))
                        }
                    )
                    if (pdfSource != null) {
                        DropdownMenuItem(
                            text = { Text("Calibrate PDF Map") },
                            onClick = {
                                hamburgerOpen = false
                                startPdfCalibration()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Unload PDF Map") },
                            onClick = {
                                hamburgerOpen = false
                                cancelPdfCalibration()
                                vm.unloadPdfMap()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Import GeoJSON") },
                        onClick = {
                            hamburgerOpen = false
                            geoJsonImportLauncher.launch(arrayOf("application/geo+json", "application/json", "*/*"))
                        }
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
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("About") },
                        onClick = {
                            hamburgerOpen = false
                            showAboutDialog = true
                        }
                    )
                }
            }
            CompassChip(mapOrientationDegrees = mapBearingDegrees)
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
                activeDrawingName = defaultDrawingName(geometry, drawingDocument.features)
                draftGeometry = geometry
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

    pendingCalibrationTap?.let { tap ->
        CalibrationInputDialog(
            point = tap,
            fiduciaryNumber = calibrationFiduciaries.size + 1,
            onDismiss = { pendingCalibrationTap = null },
            onSave = { mgrs, label ->
                val parsed = MgrsFormatter.parse(mgrs)
                if (parsed == null) {
                    false
                } else {
                    calibrationFiduciaries = calibrationFiduciaries + Fiduciary(
                        pdfX = tap.pdfX,
                        pdfY = tap.pdfY,
                        mgrs = mgrs.trim().uppercase(),
                        latitude = parsed.first,
                        longitude = parsed.second,
                        label = label.trim().ifBlank { null }
                    )
                    pendingCalibrationTap = null
                    true
                }
            }
        )
    }
}

@Composable
private fun CircleHudButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = Color.White,
                 modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CompassChip(mapOrientationDegrees: Double) {
    val screenUpBearingDegrees = normalizedDegrees(-mapOrientationDegrees)
    val mils = ((screenUpBearingDegrees * (6400.0 / 360.0)).toInt()) % 6400
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .size(34.dp)
                .align(Alignment.TopCenter)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val needleHalfLength = size.height * 0.34f
            val needleWing = size.width * 0.13f
            val needleWaist = size.height * 0.04f
            val strokeWidth = size.width * 0.032f
            rotate(degrees = -screenUpBearingDegrees.toFloat(), pivot = center) {
                val northNeedle = Path().apply {
                    moveTo(cx, cy - needleHalfLength)
                    lineTo(cx - needleWing, cy + needleWaist)
                    lineTo(cx, cy - needleWaist)
                    lineTo(cx + needleWing, cy + needleWaist)
                    close()
                }
                val southNeedle = Path().apply {
                    moveTo(cx, cy + needleHalfLength)
                    lineTo(cx - needleWing, cy - needleWaist)
                    lineTo(cx, cy + needleWaist)
                    lineTo(cx + needleWing, cy - needleWaist)
                    close()
                }
                drawLine(
                    Color.White,
                    start = center.copy(y = cy - needleHalfLength),
                    end = center.copy(y = cy + needleHalfLength),
                    strokeWidth = strokeWidth
                )
                drawPath(northNeedle, Color(0xFFFF3B30))
                drawPath(southNeedle, Color.White)
            }
            drawCircle(
                Color.White.copy(alpha = 0.72f),
                radius = size.width * 0.04f,
                center = center,
                style = Stroke(width = strokeWidth * 0.85f)
            )
        }
        Text(
            "%04d".format(mils),
            color = Color(0xFF8CF28C),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
        )
    }
}

private fun normalizedDegrees(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

private data class PendingCalibrationTap(
    val pdfX: Double,
    val pdfY: Double
)

private fun PdfMapSource.pdfPointFor(latitude: Double, longitude: Double): PendingCalibrationTap? {
    val bounds = coverage ?: return null
    val info = pageInfo ?: return null
    val latSpan = bounds.latitudeSpan
    val lonSpan = bounds.longitudeSpan
    if (kotlin.math.abs(latSpan) < 1e-12 || kotlin.math.abs(lonSpan) < 1e-12) return null

    val yRatio = (latitude - bounds.southwest.latitude) / latSpan
    val xRatio = (longitude - bounds.southwest.longitude) / lonSpan
    if (xRatio !in -0.05..1.05 || yRatio !in -0.05..1.05) return null

    return PendingCalibrationTap(
        pdfX = xRatio.coerceIn(0.0, 1.0) * info.pageWidth,
        pdfY = yRatio.coerceIn(0.0, 1.0) * info.pageHeight
    )
}

private object DrawingDefaults {
    val DEFAULT_COLOR: Int = 0xFFFFA000.toInt()
    const val STROKE_WIDTH: Float = 8f
    val COLORS = listOf(
        DEFAULT_COLOR,
        0xFFE53935.toInt(),
        0xFFFB8C00.toInt(),
        0xFFFDD835.toInt(),
        0xFF1E88E5.toInt(),
        0xFF00ACC1.toInt(),
        0xFF43A047.toInt(),
        0xFF3949AB.toInt(),
        0xFF8E24AA.toInt(),
        0xFFD81B60.toInt(),
        0xFF111111.toInt(),
        0xFFFFFFFF.toInt()
    )
}

private fun Int.withAlpha(alpha: Int): Int =
    (this and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

private val DrawingGeometry.minimumVertices: Int
    get() = when (this) {
        DrawingGeometry.POINT -> 1
        DrawingGeometry.LINE -> 2
        DrawingGeometry.POLYGON -> 3
    }

private fun defaultDrawingName(geometry: DrawingGeometry, existing: List<DrawingFeature>): String {
    val next = existing.count { it.geometry == geometry } + 1
    return when (geometry) {
        DrawingGeometry.POINT -> "Point $next"
        DrawingGeometry.LINE -> "Line $next"
        DrawingGeometry.POLYGON -> "Area $next"
    }
}

private fun drawingNameOrDefault(
    proposedName: String,
    geometry: DrawingGeometry,
    existing: List<DrawingFeature>
): String = proposedName.trim().ifEmpty { defaultDrawingName(geometry, existing) }

private fun List<DrawingPoint>.dedupeTrailingPoints(): List<DrawingPoint> {
    if (size < 2) return this
    return if (this[size - 1].isSameLocation(this[size - 2])) dropLast(1) else this
}

private fun DrawingPoint.isSameLocation(other: DrawingPoint): Boolean =
    kotlin.math.abs(latitude - other.latitude) < 0.0000001 &&
        kotlin.math.abs(longitude - other.longitude) < 0.0000001

private fun shareGeoJson(
    context: Context,
    waypoints: List<com.tacticalmaps.waypoints.Waypoint>,
    drawings: List<DrawingFeature>,
    layers: List<com.tacticalmaps.drawings.DrawingLayer>
) {
    val geoJson = GeoJsonExporter.export(waypoints, drawings, layers)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/geo+json"
        putExtra(Intent.EXTRA_SUBJECT, "TacticalMaps export.geojson")
        putExtra(Intent.EXTRA_TEXT, geoJson)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Export GeoJSON"))
    }.onFailure {
        Toast.makeText(context, "No app available to export GeoJSON.", Toast.LENGTH_SHORT).show()
    }
}

private fun importPdfMapSource(
    context: Context,
    sourceUri: Uri,
    cameraLat: Double,
    cameraLng: Double
): PdfMapSource {
    val displayName = context.displayNameFor(sourceUri)
    val pdfDir = File(context.filesDir, "pdf_maps").apply { mkdirs() }
    val dest = File(pdfDir, uniquePdfFileName(displayName))

    context.contentResolver.openInputStream(sourceUri).use { input ->
        requireNotNull(input) { "Unable to open selected PDF" }
        dest.outputStream().use { output -> input.copyTo(output) }
    }

    val fileUri = Uri.fromFile(dest)
    val pageInfo = PdfPageRenderer.firstPageInfo(context, fileUri)
    return PdfMapSource.imported(
        uri = fileUri,
        name = displayName.removeSuffix(".pdf").removeSuffix(".PDF"),
        center = Wgs84Coordinate(cameraLat, cameraLng),
        pageInfo = pageInfo
    )
}

private fun Context.displayNameFor(uri: Uri): String {
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && cursor.moveToFirst()) {
            return cursor.getString(idx)
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Map.pdf"
}

private fun uniquePdfFileName(displayName: String): String {
    val base = displayName.substringBeforeLast('.', displayName)
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .ifBlank { "Imported_Map" }
    return "${System.currentTimeMillis()}_$base.pdf"
}

@Composable
private fun CalibrationBar(
    fiduciaryCount: Int,
    canFinish: Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Calibrating PDF",
                color = Color(0xFFFFA000),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                calibrationStatus(fiduciaryCount),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp
            )
        }
        TextButton(onClick = onCancel) {
            Text("Cancel", color = Color.White)
        }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFA000),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF4A4A4A),
                disabledContentColor = Color.White.copy(alpha = 0.45f)
            )
        ) {
            Text("Finish", fontWeight = FontWeight.Bold)
        }
    }
}

private fun calibrationStatus(fiduciaryCount: Int): String =
    when {
        fiduciaryCount == 0 -> "Tap a known point on the PDF, then enter its MGRS."
        fiduciaryCount < 3 -> "$fiduciaryCount/3 fiduciaries placed. Add another known point."
        else -> "$fiduciaryCount fiduciaries placed. Finish or add more for accuracy."
    }

@Composable
private fun CalibrationInputDialog(
    point: PendingCalibrationTap,
    fiduciaryNumber: Int,
    onDismiss: () -> Unit,
    onSave: (mgrs: String, label: String) -> Boolean
) {
    var mgrs by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fiduciary #$fiduciaryNumber") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PDF point: ${point.pdfX.toInt()}, ${point.pdfY.toInt()}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = mgrs,
                    onValueChange = {
                        mgrs = it
                        error = null
                    },
                    label = { Text("MGRS") },
                    placeholder = { Text("56HLH 12345 67890") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Grid intersection") },
                    singleLine = true
                )
                error?.let {
                    Text(it, color = Color(0xFFE53935), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val saved = onSave(mgrs, label)
                    if (!saved) {
                        error = "Couldn't parse MGRS. Try a full grid reference."
                    }
                },
                enabled = mgrs.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrawingFeatureEditBar(
    feature: DrawingFeature,
    layers: List<DrawingLayer>,
    onFeatureChange: (DrawingFeature) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var colorMenuOpen by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE1C1C1E))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawingToolChip(feature.geometry)
            Column(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .clickable { nameDialogOpen = true }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feature.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.size(5.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit drawing name",
                        tint = Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    "${feature.geometry.displayName} - Drawing",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close drawing controls",
                    tint = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
            DrawingTransformSliderRow(
                icon = Icons.AutoMirrored.Filled.RotateRight,
                value = normalizedDrawingDegrees(feature.rotationDegrees).toFloat(),
                valueLabel = "${normalizedDrawingDegrees(feature.rotationDegrees).toInt()}°",
                range = 0f..360f,
                onChange = { onFeatureChange(feature.copy(rotationDegrees = it.toDouble())) },
                onReset = { onFeatureChange(feature.copy(rotationDegrees = 0.0)) }
            )
            DrawingTransformSliderRow(
                icon = Icons.Default.SwapHoriz,
                value = feature.scaleX.toFloat().coerceIn(0.15f, 6f),
                valueLabel = "%.2fx".format(feature.scaleX),
                range = 0.15f..6f,
                onChange = { onFeatureChange(feature.copy(scaleX = it.toDouble())) },
                onReset = { onFeatureChange(feature.copy(scaleX = 1.0)) }
            )
            DrawingTransformSliderRow(
                icon = Icons.Default.SwapVert,
                value = feature.scaleY.toFloat().coerceIn(0.15f, 6f),
                valueLabel = "%.2fx".format(feature.scaleY),
                range = 0.15f..6f,
                onChange = { onFeatureChange(feature.copy(scaleY = it.toDouble())) },
                onReset = { onFeatureChange(feature.copy(scaleY = 1.0)) }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                DrawingColorSelectButton(
                    color = feature.strokeColor,
                    onClick = { colorMenuOpen = true }
                )
                DrawingColorMenu(
                    expanded = colorMenuOpen,
                    selectedColor = feature.strokeColor,
                    onDismiss = { colorMenuOpen = false },
                    onColorSelected = { color ->
                        onFeatureChange(
                            feature.copy(
                                strokeColor = color,
                                fillColor = color.withAlpha(0x33)
                            )
                        )
                        colorMenuOpen = false
                    }
                )
            }
            DrawingStyleButton(
                strokeStyle = feature.strokeStyle,
                onClick = {
                    onFeatureChange(feature.copy(strokeStyle = feature.strokeStyle.next()))
                }
            )
            LayerSelectorButton(
                layers = layers,
                selectedLayerId = feature.layerId,
                onLayerSelected = { layerId ->
                    onFeatureChange(feature.copy(layerId = layerId))
                },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onDelete,
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(6.dp))
                Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (nameDialogOpen) {
        DrawingNameDialog(
            name = feature.name,
            onNameChange = { name ->
                val cleanName = name.trim().ifBlank { feature.name }
                onFeatureChange(feature.copy(name = cleanName))
            },
            onDismiss = { nameDialogOpen = false }
        )
    }
}

@Composable
private fun DrawingTransformSliderRow(
    icon: ImageVector,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onReset: () -> Unit
) {
    Row(
        modifier = Modifier.height(30.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.74f),
                modifier = Modifier.size(12.dp)
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF1E9BFF),
                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
            )
        )
        Text(
            valueLabel,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 46.dp)
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFF315D70))
                .clickable(onClick = onReset),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Reset",
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun normalizedDrawingDegrees(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

@Composable
private fun DrawingDraftBar(
    geometry: DrawingGeometry,
    pointCount: Int,
    drawingName: String,
    strokeColor: Int,
    strokeStyle: DrawingStrokeStyle,
    onDrawingNameChange: (String) -> Unit,
    onStrokeColorChange: (Int) -> Unit,
    onStrokeStyleChange: (DrawingStrokeStyle) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var colorMenuOpen by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    val canFinish = geometry == DrawingGeometry.POINT || pointCount >= geometry.minimumVertices

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DrawingToolChip(geometry)
        DrawingNameButton(
            name = drawingName,
            onClick = { nameDialogOpen = true }
        )
        Box {
            DrawingColorSelectButton(
                color = strokeColor,
                onClick = { colorMenuOpen = true }
            )
            DrawingColorMenu(
                expanded = colorMenuOpen,
                selectedColor = strokeColor,
                onDismiss = { colorMenuOpen = false },
                onColorSelected = { color ->
                    onStrokeColorChange(color)
                    colorMenuOpen = false
                }
            )
        }
        DrawingStyleButton(
            strokeStyle = strokeStyle,
            onClick = { onStrokeStyleChange(strokeStyle.next()) }
        )
        if (geometry != DrawingGeometry.POINT) {
            Text(
                pointCount.toString(),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF202020))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = CircleShape,
            modifier = Modifier.height(38.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFA000),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF4A4A4A),
                disabledContentColor = Color.White.copy(alpha = 0.45f)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
        ) {
            Text(
                if (geometry == DrawingGeometry.POINT) "Done" else "Finish",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    if (nameDialogOpen) {
        DrawingNameDialog(
            name = drawingName,
            onNameChange = onDrawingNameChange,
            onDismiss = { nameDialogOpen = false }
        )
    }
}

@Composable
private fun DrawingNameButton(name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .widthIn(min = 56.dp, max = 78.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.ifBlank { "Name" },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DrawingNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedName by remember(name) { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drawing name") },
        text = {
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onNameChange(editedName)
                    onDismiss()
                }
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DrawingToolChip(geometry: DrawingGeometry) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFFFFA000)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            when (geometry) {
                DrawingGeometry.POINT -> drawCircle(
                    color = Color.Black,
                    radius = 5.dp.toPx(),
                    center = center
                )
                DrawingGeometry.LINE -> drawLine(
                    color = Color.Black,
                    start = Offset(size.width * 0.18f, size.height * 0.72f),
                    end = Offset(size.width * 0.82f, size.height * 0.28f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                DrawingGeometry.POLYGON -> {
                    val path = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.72f)
                        lineTo(size.width * 0.5f, size.height * 0.2f)
                        lineTo(size.width * 0.82f, size.height * 0.7f)
                        close()
                    }
                    drawPath(path, Color.Black, style = stroke)
                }
            }
        }
    }
}

@Composable
private fun DrawingColorSelectButton(color: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
        )
    }
}

@Composable
private fun DrawingColorMenu(
    expanded: Boolean,
    selectedColor: Int,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            DrawingDefaults.COLORS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { color ->
                        DrawingColorSwatch(
                            color = color,
                            selected = color == selectedColor,
                            onClick = { onColorSelected(color) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawingStyleButton(
    strokeStyle: DrawingStrokeStyle,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 46.dp, height = 38.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(width = 28.dp, height = 16.dp)) {
            val y = size.height / 2f
            if (strokeStyle == DrawingStrokeStyle.DASHED) {
                val segment = size.width * 0.24f
                val gap = size.width * 0.13f
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color.White,
                        start = Offset(x, y),
                        end = Offset((x + segment).coerceAtMost(size.width), y),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    x += segment + gap
                }
            } else {
                drawLine(
                    color = Color.White,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun DrawingColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color(0xFFFFA000) else Color.White.copy(alpha = 0.45f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

private fun DrawingStrokeStyle.next(): DrawingStrokeStyle =
    when (this) {
        DrawingStrokeStyle.SOLID -> DrawingStrokeStyle.DASHED
        DrawingStrokeStyle.DASHED -> DrawingStrokeStyle.SOLID
    }

@Composable
private fun CentrePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC000000)),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color.White,
             modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text("Centre on My Location", color = Color.White,
             fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
