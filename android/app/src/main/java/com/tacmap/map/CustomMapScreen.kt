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
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.OnlineRasterTileSource
import com.tacmap.map.render.TileMapView
import com.tacmap.map.render.MapProjection
import com.tacmap.map.render.MgrsGridCanvas
import com.tacmap.map.render.DrawingsCanvas
import com.tacmap.map.render.WaypointSymbolsLayer
import com.tacmap.map.render.UserLocationCanvas
import com.tacmap.map.render.TileSource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * The SDK-free replacement for [GoogleMapScreen]. Renders the basemap with the
 * custom [TileMapView] and drives a [MapCamera] we own, so nothing links the
 * Google Maps SDK. Overlays (drawings, symbols, presence, PDF, MGRS grid,
 * heatmap, touch, labels, vertex handles) are ported on top of this in following
 * steps; this first cut is basemap + camera + gestures so we can verify tiles
 * and pan/zoom/rotate against the real renderer.
 */
@Composable
fun CustomMapScreen(
    modifier: Modifier = Modifier,
    mapSource: MapSource? = null,
    onlineBasemapsEnabled: Boolean = false,
    waypoints: List<com.tacmap.waypoints.Waypoint> = emptyList(),
    drawings: List<com.tacmap.drawings.DrawingFeature> = emptyList(),
    drawingLayers: List<com.tacmap.drawings.DrawingLayer> = emptyList(),
    draftDrawing: com.tacmap.drawings.DrawingFeature? = null,
    selectedDrawingId: String? = null,
    mgrsGridVisible: Boolean = false,
    userLocationVisible: Boolean = true,
    myLat: Double? = null,
    myLon: Double? = null,
    myAccuracyMetres: Float = 0f,
    pendingTarget: Triple<Double, Double, Float>? = null,
    resetNorthRequests: Flow<Unit>? = null,
    onConsumePendingTarget: () -> Unit = {},
    onCameraIdle: (lat: Double, lng: Double, byUser: Boolean) -> Unit = { _, _, _ -> },
    onBearingChanged: (Double) -> Unit = {}
) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    var camera by remember {
        mutableStateOf(
            MapCamera(
                centerLat = 0.0, centerLon = 0.0, zoom = 2.0,
                headingDegrees = 0.0, viewportWidth = 0.0, viewportHeight = 0.0
            )
        )
    }
    // True while the last camera move came from a gesture (drives the "Map
    // Centre" vs "Live Location" header, same as the SDK's byUser flag).
    var browsing by remember { mutableStateOf(false) }

    val source: TileSource? = remember(mapSource, onlineBasemapsEnabled) {
        when (mapSource) {
            is OnlineRasterMapSourceAndroid ->
                if (onlineBasemapsEnabled) OnlineRasterTileSource(mapSource.style) else null
            is OfflineTileMapSourceAndroid -> mapSource.renderTileSource()
            else -> null // PDF draws as an overlay (ported later); otherwise blank
        }
    }
    val wantsOnlineRaster = mapSource is OnlineRasterMapSourceAndroid
    val basemapBlank = !onlineBasemapsEnabled && wantsOnlineRaster

    // Fly to a requested target (snap for now; animated flyTo comes with the
    // camera-control step).
    LaunchedEffect(pendingTarget) {
        pendingTarget?.let { (lat, lng, zoom) ->
            camera = camera.copy(centerLat = lat, centerLon = lng, zoom = zoom.toDouble())
            browsing = false
            onConsumePendingTarget()
        }
    }

    // Compass reset - heading back to north.
    LaunchedEffect(resetNorthRequests) {
        resetNorthRequests?.collect { camera = camera.copy(headingDegrees = 0.0) }
    }

    // Report the centre once the camera settles (200ms after the last move).
    LaunchedEffect(camera.centerLat, camera.centerLon, camera.zoom) {
        delay(200)
        onCameraIdle(camera.centerLat, camera.centerLon, browsing)
        browsing = false
    }
    // Keep the compass chip in sync with heading.
    LaunchedEffect(camera.headingDegrees) { onBearingChanged(camera.headingDegrees) }

    Box(modifier = modifier.fillMaxSize()) {
        TileMapView(
            camera = camera,
            onCameraChange = { camera = it },
            source = source,
            onGestureStart = { browsing = true },
            modifier = Modifier.fillMaxSize()
        )

        // Overlays, projected through the same camera. Visible-layer filtering
        // mirrors GoogleMapScreen. Interaction (tap/drag/vertex) + PDF + presence
        // + labels + heatmap get layered in following steps.
        val visibleLayerIds = drawingLayers
            .ifEmpty { com.tacmap.drawings.DrawingDocument.defaultLayers() }
            .filter { it.isVisible }.map { it.id }.toSet()
        val visibleDrawings = drawings.filter { it.layerId in visibleLayerIds }
        val visibleWaypoints = if (drawingLayers.isEmpty()) waypoints
            else waypoints.filter { it.layerId in visibleLayerIds }

        if (mgrsGridVisible) {
            MgrsGridCanvas(camera = camera, density = density, modifier = Modifier.fillMaxSize())
        }
        val projection = remember(camera, density) { MapProjection(camera, density) }
        DrawingsCanvas(
            features = visibleDrawings,
            draft = draftDrawing,
            selectedId = selectedDrawingId,
            projection = projection,
            modifier = Modifier.fillMaxSize()
        )
        WaypointSymbolsLayer(
            waypoints = visibleWaypoints,
            camera = camera,
            density = density,
            modifier = Modifier.fillMaxSize()
        )
        if (userLocationVisible) {
            UserLocationCanvas(
                lat = myLat, lon = myLon, accuracyMetres = myAccuracyMetres,
                camera = camera, density = density, modifier = Modifier.fillMaxSize()
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
