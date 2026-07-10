package com.tacmap.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PatternItem
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.DragState
import com.google.android.gms.maps.model.LatLngBounds
import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.calibration.Wgs84Coordinate
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.tacmap.calibration.MapSource
import com.tacmap.calibration.OfflineTileMapSourceAndroid
import com.tacmap.calibration.OnlineRasterMapSourceAndroid
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.PdfPageRenderer
import com.tacmap.mgrs.MgrsGridRenderer
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.sync.PresencePeer
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Google Maps satellite map. Waypoint markers, drawings (lines/polygons/
 * points), draft drawing, drawing input, vertex-edit handles, camera
 * control, MGRS grid lines, PDF ground overlay.
 *
 * Known parity gaps vs old osmdroid surface:
 *  - MGRS grid labels not rendered (just lines)
 *  - PDF overlay uses single base bitmap, no hi-res viewport re-render on zoom
 *  - Drawing name labels not rendered next to features
 *  - No whole-feature drag (polyline/polygon body move) - vertex handles only
 */
@Composable
fun GoogleMapScreen(
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
    mgrsGridVisible: Boolean = false,
    terrainHeatmapVisible: Boolean = false,
    unitLabelsVisible: Boolean = true,
    taskLabelsVisible: Boolean = true,
    drawingLabelsVisible: Boolean = true,
    peers: Map<String, PresencePeer> = emptyMap(),
    selectedDrawingId: String? = null,
    selectedWaypointId: String? = null,
    calibrationFiduciaries: List<com.tacmap.calibration.Fiduciary> = emptyList(),
    myLocationEnabled: Boolean = false,
    /// OPSEC gate. Off by default, and while off we never ask Google, Esri or
    /// OpenTopoMap for a tile - the basemap just isn't drawn. See OpsecSettings.
    onlineBasemapsEnabled: Boolean = false,
    pendingTarget: Triple<Double, Double, Float>? = null,
    resetNorthRequests: kotlinx.coroutines.flow.Flow<Unit>? = null,
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
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }

    LaunchedEffect(pendingTarget) {
        pendingTarget?.let { (lat, lng, zoom) ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lng), zoom)
            )
            onConsumePendingTarget()
        }
    }

    /// Compass tap - animate bearing back to 0 (north up), keep
    /// target + zoom. Drop tilt to 0 too so map is flat north-up.
    LaunchedEffect(resetNorthRequests) {
        val flow = resetNorthRequests ?: return@LaunchedEffect
        flow.collect {
            val current = cameraPositionState.position
            cameraPositionState.animate(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(current.target)
                        .zoom(current.zoom)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                )
            )
        }
    }

    // terrain heatmap: sample visible region DEM once camera settles
    // (debounced) and stretch a coloured overlay across it
    val heatmapService = remember { TerrainHeatmapService() }
    var terrainHeatmap by remember { mutableStateOf<Pair<Bitmap, LatLngBounds>?>(null) }
    LaunchedEffect(terrainHeatmapVisible, cameraPositionState) {
        if (!terrainHeatmapVisible) {
            terrainHeatmap = null
            return@LaunchedEffect
        }
        snapshotFlow { cameraPositionState.isMoving }
            .collectLatest { moving ->
                if (moving) return@collectLatest
                delay(500)   // debounce; collectLatest cancels this if the camera moves again
                val region = cameraPositionState.projection?.visibleRegion ?: return@collectLatest
                val b = region.latLngBounds
                val wb = Wgs84Bounds(
                    southwest = Wgs84Coordinate(b.southwest.latitude, b.southwest.longitude),
                    northeast = Wgs84Coordinate(b.northeast.latitude, b.northeast.longitude)
                )
                heatmapService.generate(wb)?.let { bmp -> terrainHeatmap = bmp to b }
            }
    }

    val currentOnCameraIdle = rememberUpdatedState(onCameraIdle)
    val currentOnBearingChanged = rememberUpdatedState(onBearingChanged)
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }
            .drop(1)
            .distinctUntilChanged()
            .collect { isMoving ->
                if (!isMoving) {
                    val byUser = cameraPositionState.cameraMoveStartedReason ==
                        CameraMoveStartedReason.GESTURE
                    val pos = cameraPositionState.position
                    currentOnCameraIdle.value(pos.target.latitude, pos.target.longitude, byUser)
                }
                /// Don't clear selection on gesture-start anymore.
                /// The SDK camera tracker briefly flips isMoving=true
                /// on clean taps (no real camera movement) and that
                /// race would nuke the selection MapItemTouchOverlay
                /// just set. Empty-tap clearing is handled by the
                /// overlay's onEmptyTap now, which only fires when
                /// user lifts without hitting any item.
            }
    }
    /// report bearing changes so compass chip stays in sync
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.position.bearing }
            .distinctUntilChanged()
            .collect { bearing ->
                currentOnBearingChanged.value(bearing.toDouble())
            }
    }

    val visibleLayerIds = drawingLayers
        .ifEmpty { DrawingDocument.defaultLayers() }
        .filter { it.isVisible }
        .map { it.id }
        .toSet()
    val visibleWaypoints = if (drawingLayers.isEmpty()) {
        waypoints
    } else {
        waypoints.filter { it.layerId in visibleLayerIds }
    }
    val visibleDrawings = drawings.filter { it.layerId in visibleLayerIds }

    val currentOnMarkerTap = rememberUpdatedState(onMarkerTap)
    val currentOnWaypointMoved = rememberUpdatedState(onWaypointMoved)
    val currentOnDrawingTap = rememberUpdatedState(onDrawingTap)
    val currentOnCalibrationTap = rememberUpdatedState(onCalibrationTap)
    val currentOnDrawingFeatureTap = rememberUpdatedState(onDrawingFeatureTap)
    val currentOnVertexMoved = rememberUpdatedState(onVertexMoved)
    val currentOnVertexInserted = rememberUpdatedState(onVertexInserted)
    val currentOnVertexDeleted = rememberUpdatedState(onVertexDeleted)
    val currentOnShapeMoved = rememberUpdatedState(onShapeMoved)
    val currentOnMapTap = rememberUpdatedState(onMapTap)
    val currentDrawingInputEnabled = rememberUpdatedState(drawingInputEnabled)
    val currentCalibrationInputEnabled = rememberUpdatedState(calibrationInputEnabled)

    val selectedDrawing = remember(selectedDrawingId, drawings) {
        drawings.firstOrNull { it.id == selectedDrawingId }
            ?.takeIf { it.geometry == DrawingGeometry.LINE || it.geometry == DrawingGeometry.POLYGON }
    }

    /// Single source of truth for in-flight drag of a map item
    /// (waypoint or drawing). Touch overlay writes, SDK polyline
    /// renderer + Compose waypoint renderer both read to show item
    /// following the finger in realtime.
    var dragState by remember { mutableStateOf<MapItemDrag?>(null) }
    val currentDragState = rememberUpdatedState(dragState)

    /// An online raster style (Esri/OSM) owns the backdrop now - there is no
    /// Google basemap any more. Blank when that style is gated off and no
    /// imported map is loaded: say so rather than show a black rectangle.
    val wantsOnlineRaster = mapSource is OnlineRasterMapSourceAndroid
    val basemapBlank = !onlineBasemapsEnabled && wantsOnlineRaster

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                /// Always MapType.NONE: the SDK never draws or fetches a Google
                /// basemap. It's just the map surface; every basemap we show is
                /// our own tile overlay (Esri/OSM/offline) or a PDF.
                mapType = MapType.NONE,
                /// built-in blue dot. SDK throws if true without
                /// ACCESS_FINE_LOCATION so we gate on permission.
                isMyLocationEnabled = myLocationEnabled
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false,
                scrollGesturesEnabled = !freeDrawActive,
                zoomGesturesEnabled = !freeDrawActive,
                tiltGesturesEnabled = !freeDrawActive,
                rotationGesturesEnabled = !freeDrawActive
            ),
            onMapClick = { latLng ->
                when {
                    currentDrawingInputEnabled.value ->
                        currentOnDrawingTap.value(latLng.latitude, latLng.longitude)
                    currentCalibrationInputEnabled.value ->
                        currentOnCalibrationTap.value(latLng.latitude, latLng.longitude)
                    /// Normal mode: if a tap reaches here it landed on
                    /// empty map. Waypoint taps are consumed by native
                    /// marker onClick, drawing taps by MapItemTouchOverlay,
                    /// before onMapClick fires. So just deselect.
                    else -> currentOnMapTap.value()
                }
            }
        ) {
            (mapSource as? PdfMapSource)?.let { pdf ->
                PdfGroundOverlay(source = pdf)
            }

            (mapSource as? OfflineTileMapSourceAndroid)?.let { tiles ->
                /// remember on source id so provider + tile cache survive recomposition
                val provider = remember(tiles.id) { tiles.tileProvider() }
                TileOverlay(tileProvider = provider)
            }

            /// Online raster basemap (Esri / OpenTopoMap) over MapType.NONE.
            /// Gated: with the toggle off we never construct the provider, so
            /// not a single tile URL is requested.
            (mapSource as? OnlineRasterMapSourceAndroid)?.takeIf { onlineBasemapsEnabled }?.let { raster ->
                val provider = remember(raster.id) { RasterTileProvider(raster.style) }
                TileOverlay(tileProvider = provider)
            }

            if (terrainHeatmapVisible) {
                terrainHeatmap?.let { (bmp, bounds) ->
                    GroundOverlay(
                        position = GroundOverlayPosition.create(bounds),
                        image = BitmapDescriptorFactory.fromBitmap(bmp)
                    )
                }
            }

            if (mgrsGridVisible) {
                MgrsGridLayer()
            }

            visibleDrawings.forEach { feature ->
                /// Live drag preview: if this drawing is the active
                /// drag target, compute lat/lng delta from touch
                /// start to current pos (via projection) and apply
                /// to every vertex. SDK redraws on each MOVE event.
                val activeDrag = dragState
                    ?.takeIf { it.kind == MapItemDrag.Kind.DRAWING && it.itemId == feature.id }
                val drawingDragDelta = activeDrag?.let { ds ->
                    cameraPositionState.projection?.let { proj ->
                        val before = proj.fromScreenLocation(
                            Point(ds.startX.roundToInt(), ds.startY.roundToInt())
                        )
                        val after = proj.fromScreenLocation(
                            Point(
                                (ds.startX + ds.offsetX).roundToInt(),
                                (ds.startY + ds.offsetY).roundToInt()
                            )
                        )
                        (after.latitude - before.latitude) to
                            (after.longitude - before.longitude)
                    }
                }
                DrawingShape(
                    feature = feature,
                    isDraft = false,
                    selected = feature.id == selectedDrawingId,
                    inputEnabled = drawingInputEnabled,
                    dragOffsetLatLng = drawingDragDelta,
                    onTap = { currentOnDrawingFeatureTap.value(feature.id) }
                )
            }
            draftDrawing?.let { draft ->
                DrawingShape(
                    feature = draft,
                    isDraft = true,
                    selected = false,
                    inputEnabled = drawingInputEnabled,
                    dragOffsetLatLng = null,
                    onTap = null
                )
            }

            /// PDF calibration fiduciaries - small numbered pins so
            /// user can see which corners they've registered
            calibrationFiduciaries.forEachIndexed { i, fid ->
                CalibrationFiduciaryMarker(index = i + 1, fid = fid)
            }

            /// Waypoint symbols (units + tasks): GroundOverlays on map
            /// surface (glued + upright on rotate), paired with invisible
            /// native marker for SDK tap + drag. Overlay follows marker
            /// so drag moves symbol under finger; lock disables both.
            WaypointGroundOverlays(
                waypoints = visibleWaypoints,
                selectedWaypointId = selectedWaypointId,
                locked = graphicsLocked,
                cameraPositionState = cameraPositionState,
                onWaypointTap = { wp -> currentOnMarkerTap.value(wp) },
                onWaypointMoved = { wp, lat, lng -> currentOnWaypointMoved.value(wp, lat, lng) }
            )

            // presence peers - same GroundOverlay pattern as waypoints,
            // counter-rotated to stay upright
            PresenceOverlays(
                peers = peers,
                cameraPositionState = cameraPositionState
            )
        }

        // presence callsign labels - Compose overlays projected on top of map
        PresenceLabelsOverlay(
            peers = peers,
            cameraPositionState = cameraPositionState
        )

        /// Waypoint name labels (units/tasks) - Compose Text overlays
        /// projected to screen coords each frame. Units get pill below
        /// icon, tasks get it centred inside the graphic.
        WaypointLabelsOverlay(
            waypoints = visibleWaypoints,
            cameraPositionState = cameraPositionState,
            unitLabelsVisible = unitLabelsVisible,
            taskLabelsVisible = taskLabelsVisible,
            dragState = dragState
        )

        /// Drawing name labels - centroid for polygons, midpoint for
        /// lines, the point itself for points.
        if (drawingLabelsVisible && !freeDrawActive) {
            DrawingLabelsOverlay(
                drawings = visibleDrawings,
                cameraPositionState = cameraPositionState,
                dragState = dragState
            )
        }

        /// MGRS grid labels - suppressed during freehand b/c recomposing
        /// hundreds of Text composables on every pointer event is brutal
        if (mgrsGridVisible && !freeDrawActive) {
            MgrsGridLabelsOverlay(cameraPositionState = cameraPositionState)
        }

        /// Unified touch handler for all map items (waypoints + drawings).
        /// Replaces old per-icon pointerInput handlers and the seperate
        /// DrawingsDragOverlay. Hit-tests z-order (waypoints first, then
        /// drawings) and passes through to GoogleMap when nothing hit so
        /// pan/zoom still work. Also dispatches empty taps via onEmptyTap
        /// so we can clear selection without relying on SDK onMapClick
        /// (which races our tap handler and would immediately nuke
        /// a waypoint we just selected).
        MapItemTouchOverlay(
            waypoints = visibleWaypoints,
            drawings = visibleDrawings,
            cameraPositionState = cameraPositionState,
            drawingInputEnabled = drawingInputEnabled,
            calibrationInputEnabled = calibrationInputEnabled,
            locked = graphicsLocked,
            dragState = currentDragState,
            onDragStateChange = { dragState = it },
            onWaypointTap = { wp -> currentOnMarkerTap.value(wp) },
            onWaypointMoved = { wp, lat, lng -> currentOnWaypointMoved.value(wp, lat, lng) },
            onDrawingTap = { id -> currentOnDrawingFeatureTap.value(id) },
            onDrawingMoved = { id, dLat, dLng -> currentOnShapeMoved.value(id, dLat, dLng) },
            onEmptyTap = { currentOnMapTap.value() }
        )

        VertexHandlesOverlay(
            feature = selectedDrawing.takeUnless { drawingInputEnabled || graphicsLocked },
            cameraPositionState = cameraPositionState,
            onVertexMoved = currentOnVertexMoved.value,
            onVertexInserted = currentOnVertexInserted.value,
            onVertexDeleted = currentOnVertexDeleted.value
        )

        if (freeDrawActive) {
            val currentOnFreeDrawPoint = rememberUpdatedState(onFreeDrawPoint)
            val currentOnFreeDrawEnd = rememberUpdatedState(onFreeDrawEnd)
            var lastLat by remember { mutableStateOf(Double.NaN) }
            var lastLng by remember { mutableStateOf(Double.NaN) }
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            lastLat = Double.NaN
                            lastLng = Double.NaN
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.pressed) {
                                        change.consume()
                                        val pos = change.position
                                        val latLng = cameraPositionState.projection
                                            ?.fromScreenLocation(
                                                android.graphics.Point(pos.x.toInt(), pos.y.toInt())
                                            ) ?: return@forEach
                                        val dLat = latLng.latitude - lastLat
                                        val dLng = latLng.longitude - lastLng
                                        if (lastLat.isNaN() || dLat * dLat + dLng * dLng > 2e-9) {
                                            lastLat = latLng.latitude
                                            lastLng = latLng.longitude
                                            currentOnFreeDrawPoint.value(latLng.latitude, latLng.longitude)
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            currentOnFreeDrawEnd.value()
                        }
                    }
            )
        }

        /// Last child so it paints over the map and every overlay. The online
        /// warning is NOT drawn here: MapScreen stacks the HUD on top of this
        /// composable, so a banner rendered inside would end up behind the MGRS
        /// header. It lives in the HUD column instead.
        if (basemapBlank) {
            NoBasemapNotice(Modifier.align(Alignment.Center))
        }
    }
}

/// Compact warning while the map is pulling tiles from the internet - the
/// provider learns your area of interest from the tiles you request, so it
/// shouldn't be something you find out by accident. Used to be a full-width
/// strip that shoved the MGRS card down; now a small pill tucked under it.
/// The full sentence lives in the accessibility label + THREAT_MODEL.
@Composable
internal fun OnlineTilesPill(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFB00020).copy(alpha = 0.92f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .semantics {
                contentDescription =
                    "Online basemap active. Tile requests reveal your area of interest."
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(11.dp)
        )
        Text(
            "Online basemap",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/// Fresh install with no offline pack and online basemaps gated off draws
/// nothing at all. Explain that, b/c a blank map with no message reads as a
/// broken app rather than a deliberate OPSEC posture.
@Composable
private fun NoBasemapNotice(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("No basemap", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(
            "Online basemaps are off. Import an offline map pack, or enable " +
                "online basemap tiles in Privacy & OPSEC.",
            color = Color(0xFFBBBBBB),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/// Tracks in-flight touch-drag of a single map item.
/// startX/Y = where gesture began (window px). offsetX/Y = cumulative
/// finger displacement. didDrag flips true once past tap slop -
/// lift-with-didDrag-false fires tap callback.

/// Presence peer symbols as GroundOverlays inside the GoogleMap content
/// lambda. Each peer's APP-6 symbol drawn via SymbolIconFactory,
/// counter-rotated with map bearing to stay upright. Same pattern
/// as WaypointGroundOverlays.
@Composable
internal fun PresenceOverlays(
    peers: Map<String, PresencePeer>,
    cameraPositionState: CameraPositionState
) {
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val zoom by remember(cameraPositionState) {
        androidx.compose.runtime.derivedStateOf { cameraPositionState.position.zoom }
    }
    val mapBearing by remember(cameraPositionState) {
        androidx.compose.runtime.derivedStateOf { cameraPositionState.position.bearing }
    }

    peers.values.forEach { peer ->
        androidx.compose.runtime.key(peer.clientId) {
            val spec = MilitarySymbolSpec(
                affiliation = SymbolAffiliation.entries.firstOrNull {
                    it.name.equals(peer.affiliation, ignoreCase = true)
                } ?: SymbolAffiliation.FRIEND,
                echelon = SymbolEchelon.entries.firstOrNull {
                    it.name.equals(peer.echelon, ignoreCase = true)
                } ?: SymbolEchelon.TEAM,
                function = SymbolFunction.entries.firstOrNull {
                    it.name.equals(peer.function, ignoreCase = true)
                } ?: SymbolFunction.INFANTRY,
                isHeadquarters = peer.isHQ
            )
            val peerWaypoint = remember(spec) {
                Waypoint(
                    id = peer.clientId,
                    name = peer.callsign,
                    latitude = peer.lat,
                    longitude = peer.lon,
                    kind = WaypointKind.Military(spec)
                )
            }
            val drawable = remember(spec) {
                SymbolIconFactory.drawableFor(context, peerWaypoint)
            }
            val rawW = drawable.intrinsicWidth.coerceAtLeast(1)
            val rawH = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = remember(spec) {
                val b = Bitmap.createBitmap(rawW, rawH, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, rawW, rawH)
                drawable.draw(Canvas(b))
                BitmapDescriptorFactory.fromBitmap(b)
            }

            val position = LatLng(peer.lat, peer.lon)
            val metersPerPixel = remember(zoom, peer.lat, density) {
                40075016.686 *
                    kotlin.math.cos(Math.toRadians(peer.lat)) /
                    (256.0 * Math.pow(2.0, zoom.toDouble()) * density)
            }
            val widthMeters = (rawW * metersPerPixel).toFloat().coerceIn(1f, 1_000_000f)
            val heightMeters = (widthMeters * rawH / rawW).coerceAtLeast(1f)

            GroundOverlay(
                position = GroundOverlayPosition.create(position, widthMeters, heightMeters),
                image = bmp,
                anchor = Offset(0.5f, 0.5f),
                bearing = mapBearing,
                clickable = false,
                zIndex = 1.5f
            )
        }
    }
}

/// Callsign labels for presence peers - projected Compose overlays
/// below the peer symbol, same approach as WaypointLabelsOverlay.
@Composable
internal fun PresenceLabelsOverlay(
    peers: Map<String, PresencePeer>,
    cameraPositionState: CameraPositionState
) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return

    peers.values.forEach { peer ->
        val trimmed = peer.callsign.trim()
        if (trimmed.isEmpty()) return@forEach
        val screen = projection.toScreenLocation(LatLng(peer.lat, peer.lon))
        // Position the label 24px below the symbol centre
        ScreenAnchoredPresenceLabel(
            screenX = screen.x,
            screenY = screen.y + 24,
            text = trimmed
        )
    }
}

@Composable
private fun ScreenAnchoredPresenceLabel(screenX: Int, screenY: Int, text: String) {
    androidx.compose.ui.layout.Layout(
        content = {
            Text(
                text = text,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .background(
                        Color(0xFF1565C0).copy(alpha = 0.85f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    ) { measurables, constraints ->
        val child = measurables.firstOrNull()
            ?: return@Layout layout(0, 0) {}
        val placeable = child.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(0, 0) {
            placeable.place(
                x = screenX - placeable.width / 2,
                y = screenY
            )
        }
    }
}

