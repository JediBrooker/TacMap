package com.tacmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tacmap.calibration.Fiduciary
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfMapSource
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.map.render.DrawingsCanvas
import com.tacmap.map.render.DrawingLabelsLayer
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.MapProjection
import com.tacmap.map.render.MgrsGridCanvas
import com.tacmap.map.render.OnlineRasterTileSource
import com.tacmap.map.render.CalibrationFiduciariesLayer
import com.tacmap.map.render.HeatmapGroundLayer
import com.tacmap.map.render.PdfGroundLayer
import com.tacmap.map.render.PresenceLayer
import com.tacmap.map.render.TileMapView
import com.tacmap.map.render.TileSource
import com.tacmap.map.render.UserLocationCanvas
import com.tacmap.map.render.WaypointLabelsLayer
import com.tacmap.map.render.WaypointSymbolsLayer
import com.tacmap.sync.PresencePeer
import com.tacmap.waypoints.Waypoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * The map surface for the whole app. The basemap renders through the custom
 * [TileMapView] and every overlay + interaction projects through a [MapCamera]
 * we own, so nothing links the Google Maps SDK - this is what lets Android ship
 * with no Google map dependency at all.
 */
@Composable
fun CustomMapScreen(
    modifier: Modifier = Modifier,
    waypoints: List<Waypoint> = emptyList(),
    mapSource: MapSource? = null,
    drawings: List<DrawingFeature> = emptyList(),
    drawingLayers: List<DrawingLayer> = emptyList(),
    draftDrawing: DrawingFeature? = null,
    graphicsLocked: Boolean = false,
    drawingInputEnabled: Boolean = false,
    freeDrawActive: Boolean = false,
    onFreeDrawPoint: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onFreeDrawEnd: () -> Unit = {},
    calibrationInputEnabled: Boolean = false,
    calibrationFiduciaries: List<Fiduciary> = emptyList(),
    mgrsGridVisible: Boolean = false,
    terrainHeatmapVisible: Boolean = false,
    unitLabelsVisible: Boolean = true,
    taskLabelsVisible: Boolean = true,
    drawingLabelsVisible: Boolean = true,
    userLocationVisible: Boolean = true,
    peers: Map<String, PresencePeer> = emptyMap(),
    selectedDrawingId: String? = null,
    selectedWaypointId: String? = null,
    myLat: Double? = null,
    myLon: Double? = null,
    myAccuracyMetres: Float = 0f,
    onlineBasemapsEnabled: Boolean = false,
    pendingTarget: Triple<Double, Double, Float>? = null,
    resetNorthRequests: Flow<Unit>? = null,
    onConsumePendingTarget: () -> Unit = {},
    onCameraIdle: (lat: Double, lng: Double, byUser: Boolean) -> Unit = { _, _, _ -> },
    onBearingChanged: (Double) -> Unit = {},
    onMarkerTap: (Waypoint) -> Unit = {},
    onWaypointMoved: (waypoint: Waypoint, lat: Double, lng: Double) -> Unit = { _, _, _ -> },
    onDrawingTap: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onCalibrationTap: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onDrawingFeatureTap: (String) -> Unit = {},
    onVertexMoved: (featureId: String, vertexIndex: Int, lat: Double, lng: Double) -> Unit = { _, _, _, _ -> },
    onVertexInserted: (featureId: String, atIndex: Int, lat: Double, lng: Double) -> Unit = { _, _, _, _ -> },
    onVertexDeleted: (featureId: String, vertexIndex: Int) -> Unit = { _, _ -> },
    onShapeMoved: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit = { _, _, _ -> },
    onMapTap: () -> Unit = {}
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var camera by remember {
        mutableStateOf(MapCamera(0.0, 0.0, 2.0, 0.0, 0.0, 0.0))
    }
    var browsing by remember { mutableStateOf(false) }

    val source: TileSource? = remember(mapSource, onlineBasemapsEnabled) {
        when (mapSource) {
            is OnlineRasterMapSourceAndroid ->
                if (onlineBasemapsEnabled) OnlineRasterTileSource(mapSource.style) else null
            is OfflineTileMapSourceAndroid -> mapSource.renderTileSource()
            else -> null // PDF draws as an overlay; otherwise blank
        }
    }
    val wantsOnlineRaster = mapSource is OnlineRasterMapSourceAndroid
    val basemapBlank = !onlineBasemapsEnabled && wantsOnlineRaster

    LaunchedEffect(pendingTarget) {
        pendingTarget?.let { (lat, lng, zoom) ->
            camera = camera.copy(centerLat = lat, centerLon = lng, zoom = zoom.toDouble())
            browsing = false
            onConsumePendingTarget()
        }
    }
    LaunchedEffect(resetNorthRequests) {
        resetNorthRequests?.collect { camera = camera.copy(headingDegrees = 0.0) }
    }
    LaunchedEffect(camera.centerLat, camera.centerLon, camera.zoom) {
        delay(200)
        onCameraIdle(camera.centerLat, camera.centerLon, browsing)
        browsing = false
    }
    LaunchedEffect(camera.headingDegrees) { onBearingChanged(camera.headingDegrees) }

    // Terrain heatmap: sample the visible-region DEM once the camera settles and
    // draw a coloured overlay across it. The fetch self-gates on the online-lookups
    // OPSEC toggle inside the service, so nothing leaves the device unless opted in.
    val heatmapService = remember { com.tacmap.map.TerrainHeatmapService() }
    var heatmap by remember { mutableStateOf<Pair<android.graphics.Bitmap, com.tacmap.calibration.Wgs84Bounds>?>(null) }
    LaunchedEffect(terrainHeatmapVisible, camera.centerLat, camera.centerLon, camera.zoom, camera.headingDegrees) {
        if (!terrainHeatmapVisible || camera.viewportWidth <= 0.0) { heatmap = null; return@LaunchedEffect }
        delay(500) // debounce; a new camera cancels this
        val w = camera.viewportWidth; val h = camera.viewportHeight
        val cs = listOf(
            camera.coordinate(0.0, 0.0), camera.coordinate(w, 0.0),
            camera.coordinate(w, h), camera.coordinate(0.0, h)
        )
        val lats = cs.map { it.first }; val lons = cs.map { it.second }
        val wb = com.tacmap.calibration.Wgs84Bounds(
            com.tacmap.calibration.Wgs84Coordinate(lats.min(), lons.min()),
            com.tacmap.calibration.Wgs84Coordinate(lats.max(), lons.max())
        )
        heatmapService.generate(wb)?.let { bmp -> heatmap = bmp to wb }
    }

    val visibleLayerIds = drawingLayers.ifEmpty { DrawingDocument.defaultLayers() }
        .filter { it.isVisible }.map { it.id }.toSet()
    val visibleDrawings = drawings.filter { it.layerId in visibleLayerIds }
    val visibleWaypoints = if (drawingLayers.isEmpty()) waypoints
        else waypoints.filter { it.layerId in visibleLayerIds }
    val selectedDrawing = visibleDrawings.firstOrNull {
        it.id == selectedDrawingId &&
            (it.geometry == DrawingGeometry.LINE || it.geometry == DrawingGeometry.POLYGON)
    }

    Box(modifier = modifier.fillMaxSize()) {
        TileMapView(
            camera = camera,
            onCameraChange = { camera = it },
            source = source,
            onGestureStart = { browsing = true },
            onTap = { if (!drawingInputEnabled && !calibrationInputEnabled && !freeDrawActive) onMapTap() },
            modifier = Modifier.fillMaxSize()
        )

        // Imported PDF/GeoPDF sits just above the basemap.
        (mapSource as? PdfMapSource)?.let { pdf ->
            PdfGroundLayer(source = pdf, camera = camera, density = density)
        }

        // Terrain heatmap sits above the basemap/PDF, under the grid + symbols.
        if (terrainHeatmapVisible) {
            heatmap?.let { (bmp, bounds) ->
                HeatmapGroundLayer(bmp, bounds, camera, density)
            }
        }

        if (mgrsGridVisible && !freeDrawActive) {
            MgrsGridCanvas(camera = camera, density = density)
        }

        val projection = remember(camera, density) { MapProjection(camera, density) }
        DrawingsCanvas(
            features = visibleDrawings, draft = draftDrawing,
            selectedId = selectedDrawingId, projection = projection
        )
        WaypointSymbolsLayer(waypoints = visibleWaypoints, camera = camera, density = density)
        PresenceLayer(peers = peers, camera = camera, density = density)
        // Centre reticle sits under the user dot so "you are here" is never
        // swallowed by the crosshair when the map is following the user.
        CrosshairOverlay()
        if (userLocationVisible) {
            UserLocationCanvas(myLat, myLon, myAccuracyMetres, camera, density)
        }

        // Calibration fiducial pins (only while calibrating a PDF).
        CalibrationFiduciariesLayer(calibrationFiduciaries, camera, density)

        // Labels above the symbols.
        WaypointLabelsLayer(visibleWaypoints, camera, density, unitLabelsVisible, taskLabelsVisible)
        if (drawingLabelsVisible && !freeDrawActive) {
            DrawingLabelsLayer(visibleDrawings, camera, density)
        }

        // Interaction. Drawing/calibration/free-draw taps go through MapInputOverlay;
        // otherwise the touch overlay hit-tests items for select/drag.
        MapInputOverlay(
            camera = camera, density = density,
            drawingInputEnabled = drawingInputEnabled,
            calibrationInputEnabled = calibrationInputEnabled,
            freeDrawActive = freeDrawActive,
            onDrawingTap = onDrawingTap,
            onCalibrationTap = onCalibrationTap,
            onFreeDrawPoint = onFreeDrawPoint,
            onFreeDrawEnd = onFreeDrawEnd
        )
        if (!drawingInputEnabled && !calibrationInputEnabled && !freeDrawActive) {
            MapItemTouchOverlayCustom(
                waypoints = visibleWaypoints, drawings = visibleDrawings,
                camera = camera, density = density,
                drawingInputEnabled = false, calibrationInputEnabled = false,
                locked = graphicsLocked,
                onDragStateChange = { },
                onWaypointTap = onMarkerTap,
                onWaypointMoved = onWaypointMoved,
                onDrawingTap = onDrawingFeatureTap,
                onDrawingMoved = onShapeMoved,
                onEmptyTap = onMapTap
            )
            VertexHandlesOverlayCustom(
                feature = selectedDrawing.takeUnless { graphicsLocked },
                camera = camera, density = density,
                onVertexMoved = onVertexMoved,
                onVertexInserted = onVertexInserted,
                onVertexDeleted = onVertexDeleted
            )
        }

        if (basemapBlank) {
            NoBasemapNoticeCustom(Modifier.align(Alignment.Center))
        }
    }
}

/** Fresh install, online basemaps gated off, no offline pack: draw nothing but
 *  say why, so a blank map reads as a deliberate OPSEC posture not a bug. */
@Composable
private fun NoBasemapNoticeCustom(modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .padding(24.dp)
            .background(androidx.compose.ui.graphics.Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Text(
            "No basemap",
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        androidx.compose.material3.Text(
            "Online basemaps are off. Import an offline map pack, or enable " +
                "online basemap tiles in Privacy & OPSEC.",
            color = androidx.compose.ui.graphics.Color(0xFFBBBBBB),
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
