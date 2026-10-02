package com.tacmap.map

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.map.render.DrawingsCanvas
import com.tacmap.map.render.DrawingLabelsLayer
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.MapProjection
import com.tacmap.map.render.MeasureOverlay
import com.tacmap.map.render.MgrsGridCanvas
import com.tacmap.map.render.OnlineRasterTileSource
import com.tacmap.map.render.CalibrationMarkerModel
import com.tacmap.map.render.CalibrationMarkersLayer
import com.tacmap.map.render.hitCalibrationMarker
import com.tacmap.calibration.fiducial.CalibrationCameraAnchor
import com.tacmap.map.render.HeatmapGroundLayer
import com.tacmap.map.render.PresenceLayer
import com.tacmap.map.render.TileMapView
import com.tacmap.map.render.TileSource
import com.tacmap.map.render.UserLocationCanvas
import com.tacmap.map.render.WaypointLabelsLayer
import com.tacmap.map.render.WaypointSymbolsLayer
import com.tacmap.map.render.UnitAmplifierLabelsLayer
import com.tacmap.sync.PresencePeer
import com.tacmap.waypoints.Waypoint
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.math.hypot

/**
 * The map surface for the whole app. The basemap renders through the custom
 * [TileMapView] and every overlay + interaction projects through a [MapCamera]
 * we own, so nothing links the Google Maps SDK - this is what lets Android ship
 * with no Google map dependency at all.
 */
@Composable
internal fun CustomMapScreen(
    modifier: Modifier = Modifier,
    waypoints: List<Waypoint> = emptyList(),
    mapSource: MapSource? = null,
    /** the tile view's memory cache, MapViewModel owns it so rotation keeps it */
    tileCache: com.tacmap.map.render.TileBitmapCache,
    /**
     * the imported PDF as a tile source (MapViewModel.pdfRuntime), drawn on the georef it's
     * handed (calibration's displayed one) or its own when that's null. null for other maps
     */
    pdfTileSourceFor: ((com.tacmap.calibration.PdfGeoreference?) -> TileSource?)? = null,
    /** bumped by Try Again so the failed source gets swapped for the new one */
    pdfRetryKey: Int = 0,
    /** Layers > Show Imported Map off: background only, overlays stay */
    importedMapHidden: Boolean = false,
    /** the PDF's first tiles are on screen (render status ready) */
    pdfRenderReady: Boolean = false,
    drawings: List<DrawingFeature> = emptyList(),
    drawingLayers: List<DrawingLayer> = emptyList(),
    draftDrawing: DrawingFeature? = null,
    /** Active measure-tool points (lat, lng); drawn even with drawings hidden. */
    measurePoints: List<Pair<Double, Double>> = emptyList(),
    graphicsLocked: Boolean = false,
    drawingInputEnabled: Boolean = false,
    freeDrawActive: Boolean = false,
    onFreeDrawPoint: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onFreeDrawEnd: () -> Unit = {},
    /** calibrating: crosshair placement, markers, live pan/zoom/rotate, no item editing */
    calibrationActive: Boolean = false,
    /** the georef + generation the calibration wants shown (installed atomically, s7.2) */
    calibrationDisplay: CalibrationDisplay? = null,
    calibrationMarkers: CalibrationMarkerModel? = null,
    calibrationBridge: CalibrationMapBridge? = null,
    /** a tap while calibrating: the marker it hit (24 dp), or null. never places a point */
    onCalibrationMarkerTap: (String?) -> Unit = {},
    zoomStepRequests: Flow<Int>? = null,
    centreRequests: Flow<Pair<Double, Double>>? = null,
    /** symbology, drawings, labels, peers, heatmap, measure off (persisted settings untouched) */
    overlaysHidden: Boolean = false,
    userLocationHidden: Boolean = false,
    /** calibration's own Grid toggle; null = the user's normal setting */
    gridOverride: Boolean? = null,
    mgrsGridVisible: Boolean = false,
    terrainHeatmapVisible: Boolean = false,
    unitLabelsVisible: Boolean = true,
    unitAmplifiersVisible: Boolean = true,
    taskLabelsVisible: Boolean = true,
    drawingLabelsVisible: Boolean = true,
    symbologyVisible: Boolean = true,
    drawingsVisible: Boolean = true,
    userLocationVisible: Boolean = true,
    peers: Map<String, PresencePeer> = emptyMap(),
    selectedDrawingId: String? = null,
    myLat: Double? = null,
    myLon: Double? = null,
    myAccuracyMetres: Float = 0f,
    onlineBasemapsEnabled: Boolean = false,
    onlineLookupsEnabled: Boolean = false,
    initialCameraState: MapViewportState? = null,
    pendingTarget: Triple<Double, Double, Float>? = null,
    resetNorthRequests: Flow<Unit>? = null,
    /** set the heading outright (debug camera hook) */
    headingRequests: Flow<Double>? = null,
    headingUpEnabled: Boolean = false,
    deviceHeadingDegrees: Flow<Double?>,
    onConsumePendingTarget: () -> Unit = {},
    onCameraIdle: (camera: MapCamera, byUser: Boolean) -> Unit = { _, _ -> },
    onBearingChanged: (Double) -> Unit = {},
    onMarkerTap: (Waypoint) -> Unit = {},
    onWaypointMoved: (waypoint: Waypoint, lat: Double, lng: Double) -> Unit = { _, _, _ -> },
    onDrawingTap: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onDrawingFeatureTap: (String) -> Unit = {},
    onPresencePeerTap: (PresencePeer) -> Unit = {},
    onVertexMoved: (featureId: String, vertexIndex: Int, lat: Double, lng: Double) -> Unit = { _, _, _, _ -> },
    onVertexInserted: (featureId: String, atIndex: Int, lat: Double, lng: Double) -> Unit = { _, _, _, _ -> },
    onVertexDeleted: (featureId: String, vertexIndex: Int) -> Unit = { _, _ -> },
    onShapeMoved: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit = { _, _, _ -> },
    onMapTap: () -> Unit = {},
    /** Long-press on empty map: point and screen position. */
    onMapLongPress: ((lat: Double, lng: Double, screen: androidx.compose.ui.geometry.Offset) -> Unit)? = null
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val cameraEntry = remember {
        MapCameraLifecyclePolicy.enter(initialCameraState, pendingTarget)
    }
    // the state object itself so the calibration bridge reads the live camera, not a recomposition old one
    val cameraState = remember { mutableStateOf(cameraEntry.camera) }
    var camera by cameraState
    var cameraPublicationReady by remember {
        mutableStateOf(cameraEntry.publicationReady)
    }
    var browsing by remember { mutableStateOf(false) }

    val source: TileSource? = remember(mapSource, onlineBasemapsEnabled) {
        when (mapSource) {
            is OnlineRasterMapSourceAndroid ->
                if (onlineBasemapsEnabled) OnlineRasterTileSource(mapSource.style) else null
            is OfflineTileMapSourceAndroid -> mapSource.renderTileSource()
            else -> null
        }
    }
    val wantsOnlineRaster = mapSource is OnlineRasterMapSourceAndroid
    val basemapBlank = !onlineBasemapsEnabled && wantsOnlineRaster

    LaunchedEffect(pendingTarget) {
        pendingTarget?.let { (lat, lng, zoom) ->
            cameraPublicationReady = false
            val applied = MapCameraLifecyclePolicy.cameraForTarget(
                current = camera,
                target = Triple(lat, lng, zoom),
            )
            if (applied != null) camera = applied
            browsing = false
            onConsumePendingTarget()
            cameraPublicationReady = applied != null || cameraEntry.publicationReady
        }
    }
    LaunchedEffect(resetNorthRequests) {
        resetNorthRequests?.collect { camera = camera.copy(headingDegrees = 0.0) }
    }
    LaunchedEffect(zoomStepRequests) {
        zoomStepRequests?.collect { step ->
            camera = camera.copy(
                zoom = (camera.zoom + step).coerceIn(CalibrationCameraAnchor.MIN_ZOOM, CalibrationCameraAnchor.MAX_ZOOM)
            )
            cameraPublicationReady = true
        }
    }
    LaunchedEffect(centreRequests) {
        centreRequests?.collect { (lat, lon) ->
            camera = camera.copy(centerLat = lat, centerLon = lon)
            cameraPublicationReady = true
        }
    }

    // s7.2: the shown georef and the camera swap in ONE snapshot, so no frame ever
    // draws the new georef with the old camera or the other way round. The anchor
    // keeps the page point under the crosshair and the page's on-screen scale.
    val shownState = remember { mutableStateOf(calibrationDisplay) }
    var shown by shownState
    LaunchedEffect(calibrationDisplay?.generation) {
        val next = calibrationDisplay
        val prev = shown
        if (next == null || prev == null || next.generation == prev.generation) {
            shown = next
            return@LaunchedEffect
        }
        // WP2 has no prefetch, so this is s7.2's plain swap: the tile source is picked off
        // shownState below, so the new source and the moved camera land in the same frame.
        // the new source starts empty (a moment of dark), never with the old georef's tiles
        Snapshot.withMutableSnapshot {
            CalibrationCameraAnchor.adjust(cameraState.value, prev.georef, next.georef)?.let { cameraState.value = it }
            shownState.value = next
        }
    }
    // the PDF's tiles follow the shown georef, read in the same snapshot as the camera
    val shownGeoref = shown?.georef
    val tileSource: TileSource? = if (mapSource is PdfMapSource) {
        remember(mapSource, shownGeoref, pdfRetryKey, density) { pdfTileSourceFor?.invoke(shownGeoref) }
    } else source
    DisposableEffect(calibrationBridge) {
        calibrationBridge?.live = { cameraState.value to shownState.value }
        onDispose { calibrationBridge?.live = null }
    }
    LaunchedEffect(headingRequests) {
        headingRequests?.collect { h ->
            if (h.isFinite()) camera = camera.copy(headingDegrees = normalizedHeadingDegrees(h))
        }
    }
    LaunchedEffect(headingUpEnabled, deviceHeadingDegrees) {
        if (headingUpEnabled) {
            deviceHeadingDegrees.collect { heading ->
                if (heading != null && heading.isFinite()) {
                    camera = camera.copy(headingDegrees = normalizedHeadingDegrees(heading))
                }
            }
        }
    }
    LaunchedEffect(
        camera.centerLat,
        camera.centerLon,
        camera.zoom,
        cameraPublicationReady,
    ) {
        if (!MapCameraLifecyclePolicy.canPublish(cameraPublicationReady, camera)) {
            return@LaunchedEffect
        }
        // Publish the live crosshair immediately. Move-to-crosshair and quick
        // add previously read a centre delayed by 200 ms, so a fast action
        // after panning could reuse the old coordinate and look like a no-op.
        onCameraIdle(camera, browsing)
        delay(200)
        browsing = false
    }
    LaunchedEffect(camera.headingDegrees) {
        onBearingChanged(camera.headingDegrees)
        // A manual rotate-only gesture has no centre/zoom change to settle it.
        // Automatic compass samples must not keep restarting this delay.
        if (!headingUpEnabled && browsing &&
            MapCameraLifecyclePolicy.canPublish(cameraPublicationReady, camera)
        ) {
            onCameraIdle(camera, true)
            delay(200)
            browsing = false
        }
    }

    // Terrain heatmap: sample the visible-region DEM once the camera settles and
    // draw a coloured overlay across it. Including the OPSEC gate in the effect
    // key cancels its request immediately when the user turns online lookups off;
    // the service repeats the same check at every network boundary as defence in depth.
    val heatmapService = remember { com.tacmap.map.TerrainHeatmapService() }
    var heatmap by remember { mutableStateOf<Pair<android.graphics.Bitmap, com.tacmap.calibration.Wgs84Bounds>?>(null) }
    val heatmapViewportRadius = hypot(camera.viewportWidth, camera.viewportHeight) / 2.0
    LaunchedEffect(
        terrainHeatmapVisible,
        onlineLookupsEnabled,
        cameraPublicationReady,
        camera.centerLat,
        camera.centerLon,
        camera.zoom,
        heatmapViewportRadius,
    ) {
        if (!terrainHeatmapVisible || !onlineLookupsEnabled ||
            !MapCameraLifecyclePolicy.canPublish(cameraPublicationReady, camera) ||
            camera.viewportWidth <= 0.0 || camera.viewportHeight <= 0.0
        ) {
            heatmap = null
            return@LaunchedEffect
        }
        delay(500) // debounce; a new camera cancels this
        val wb = orientationInvariantHeatmapBounds(camera)
        heatmapService.generate(wb)?.let { bmp -> heatmap = bmp to wb }
    }

    val visibleLayerIds = drawingLayers.ifEmpty { DrawingDocument.defaultLayers() }
        .filter { it.isVisible }.map { it.id }.toSet()
    val visibleDrawings = if (drawingsVisible) drawings.filter { it.layerId in visibleLayerIds } else emptyList()
    val visibleWaypoints = if (!symbologyVisible) emptyList() else if (drawingLayers.isEmpty()) waypoints
        else waypoints.filter { it.layerId in visibleLayerIds }
    val selectedDrawing = visibleDrawings.firstOrNull {
        it.id == selectedDrawingId &&
            (it.geometry == DrawingGeometry.LINE || it.geometry == DrawingGeometry.POLYGON)
    }

    Box(modifier = modifier.fillMaxSize()) {
        TileMapView(
            camera = camera,
            onCameraChange = { camera = it },
            source = tileSource,
            cache = tileCache,
            hidden = importedMapHidden && mapSource is PdfMapSource,
            contentDescription = (mapSource as? PdfMapSource)
                ?.takeIf { pdfRenderReady && !importedMapHidden }
                ?.let { L10n.text("PDF map rendered: %1\$s", it.displayName) },
            // The full-screen interaction overlay below owns the entire pointer
            // stream so a transform can take over even when finger one started
            // on a waypoint/drawing.
            gesturesEnabled = false,
            modifier = Modifier.fillMaxSize()
        )

        // Terrain heatmap sits above the basemap/PDF, under the grid + symbols.
        if (terrainHeatmapVisible && !overlaysHidden) {
            heatmap?.let { (bmp, bounds) ->
                HeatmapGroundLayer(bmp, bounds, camera, density)
            }
        }

        if ((gridOverride ?: mgrsGridVisible) && !freeDrawActive) {
            MgrsGridCanvas(camera = camera, density = density)
        }

        val projection = remember(camera, density) { MapProjection(camera, density) }
        if (!overlaysHidden) {
            DrawingsCanvas(
                features = visibleDrawings, draft = draftDrawing.takeIf { drawingsVisible },
                selectedId = selectedDrawingId, projection = projection
            )
            MeasureOverlay(points = measurePoints, projection = projection)
            WaypointSymbolsLayer(waypoints = visibleWaypoints, camera = camera, density = density)
            PresenceLayer(peers = peers, camera = camera, density = density)
        }
        // Centre reticle sits under the user dot so "you are here" is never
        // swallowed by the crosshair when the map is following the user.
        if (calibrationActive) CalibrationReticle() else CrosshairOverlay()
        if (userLocationVisible && !userLocationHidden) {
            UserLocationCanvas(myLat, myLon, myAccuracyMetres, camera, density)
        }

        // Calibration points, drawn through the georef actually on screen (s7.5).
        CalibrationMarkersLayer(calibrationMarkers, shown?.georef, shown?.generation ?: 0L, camera, density)

        // Labels above the symbols.
        if (!overlaysHidden) {
            WaypointLabelsLayer(visibleWaypoints, camera, density, unitLabelsVisible, taskLabelsVisible)
            if (unitAmplifiersVisible) {
                UnitAmplifierLabelsLayer(visibleWaypoints, camera, density)
            }
            if (drawingLabelsVisible && !freeDrawActive) {
                DrawingLabelsLayer(visibleDrawings, camera, density)
            }
        }

        // Interaction. Drawing/calibration/free-draw taps go through MapInputOverlay;
        // otherwise the touch overlay hit-tests items for select/drag.
        MapInputOverlay(
            camera = camera, density = density,
            drawingInputEnabled = drawingInputEnabled,
            freeDrawActive = freeDrawActive,
            onDrawingTap = onDrawingTap,
            onFreeDrawPoint = onFreeDrawPoint,
            onFreeDrawEnd = onFreeDrawEnd
        )
        if (!drawingInputEnabled && !freeDrawActive) {
            // calibrating keeps the whole pan/pinch/rotate arbitrator (D2-01) but with
            // nothing to grab: no items, locked, no long-press menu
            MapItemTouchOverlayCustom(
                waypoints = if (calibrationActive) emptyList() else visibleWaypoints,
                drawings = if (calibrationActive) emptyList() else visibleDrawings,
                peers = if (calibrationActive) emptyMap() else peers,
                camera = camera, density = density,
                drawingInputEnabled = false,
                locked = graphicsLocked || calibrationActive,
                onDragStateChange = { },
                onWaypointTap = onMarkerTap,
                onWaypointMoved = onWaypointMoved,
                onDrawingTap = onDrawingFeatureTap,
                onPresencePeerTap = onPresencePeerTap,
                onDrawingMoved = onShapeMoved,
                // camera limits only, every source overzooms past its own max (WP2 contract A)
                minZoom = MapCamera.MIN_ZOOM,
                maxZoom = MapCamera.MAX_ZOOM,
                rotationEnabled = !headingUpEnabled || calibrationActive,
                onCameraChange = {
                    camera = it
                    cameraPublicationReady = true
                },
                onMapGestureStart = { browsing = true },
                onEmptyTap = { pos ->
                    if (calibrationActive) {
                        onCalibrationMarkerTap(hitCalibrationMarker(pos, calibrationMarkers, shownState.value?.georef, cameraState.value, density))
                    } else {
                        onMapTap()
                    }
                },
                onEmptyLongPress = onMapLongPress.takeUnless { calibrationActive },
            )
            VertexHandlesOverlayCustom(
                feature = selectedDrawing.takeUnless { graphicsLocked || calibrationActive },
                camera = camera, density = density,
                onVertexMoved = onVertexMoved,
                onVertexInserted = onVertexInserted,
                onVertexDeleted = onVertexDeleted
            )
        }

        if (basemapBlank) {
            NoBasemapNoticeCustom(Modifier.align(Alignment.Center))
        }
        if (importedMapHidden && mapSource is PdfMapSource) {
            ImportedMapHiddenCapsule(Modifier.align(Alignment.Center))
        }
    }
}

/** Layers > Show Imported Map is off: say so, the dark map is deliberate */
@Composable
private fun ImportedMapHiddenCapsule(modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        Messages.importedMapHiddenNotice(),
        color = androidx.compose.ui.graphics.Color.White,
        fontSize = 13.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        modifier = modifier
            .padding(24.dp)
            .background(androidx.compose.ui.graphics.Color(0xCC000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** North-up square around the viewport half-diagonal. It contains the visible
 * map at every heading, so compass updates can reuse one heatmap sample. */
internal fun orientationInvariantHeatmapBounds(camera: MapCamera): Wgs84Bounds {
    val radius = hypot(camera.viewportWidth, camera.viewportHeight) / 2.0
    val centreX = camera.viewportWidth / 2.0
    val centreY = camera.viewportHeight / 2.0
    val northUp = camera.copy(headingDegrees = 0.0)
    val coordinates = listOf(
        northUp.coordinate(centreX - radius, centreY - radius),
        northUp.coordinate(centreX + radius, centreY - radius),
        northUp.coordinate(centreX + radius, centreY + radius),
        northUp.coordinate(centreX - radius, centreY + radius),
    )
    val latitudes = coordinates.map { it.first }
    val longitudes = coordinates.map { it.second }
    return Wgs84Bounds(
        Wgs84Coordinate(latitudes.min(), longitudes.min()),
        Wgs84Coordinate(latitudes.max(), longitudes.max()),
    )
}

/** When online basemaps are gated off with no offline pack, draw nothing but
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
            L10n.text("No basemap"),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        androidx.compose.material3.Text(
            Messages.onlineBasemapsDisabled(),
            color = androidx.compose.ui.graphics.Color(0xFFBBBBBB),
            fontSize = 11.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
