package com.tacticalmaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.tacticalmaps.calibration.MapSource
import com.tacticalmaps.calibration.OfflineTileMapSourceAndroid
import com.tacticalmaps.calibration.PdfMapSource
import com.tacticalmaps.calibration.PdfPageRenderer
import com.tacticalmaps.mgrs.MgrsGridRenderer
import com.tacticalmaps.drawings.DrawingDocument
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingStrokeStyle
import com.tacticalmaps.waypoints.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Google Maps satellite map. Supports waypoint markers, drawings
 * (lines / polygons / points), draft drawing, drawing input, vertex-edit
 * handles (immediate drag via Compose overlay, long-press to delete),
 * camera control, MGRS grid lines, and PDF ground overlay.
 *
 * Known parity gaps with the previous osmdroid surface:
 *  - MGRS grid labels are not rendered (just lines).
 *  - PDF overlay uses a single base bitmap — no high-res viewport
 *    re-render as the user zooms in.
 *  - Drawing name labels are not rendered next to features.
 *  - Whole-feature drag (grabbing a polyline / polygon body to move
 *    everything at once) is not supported — vertex handles only.
 */
@Composable
fun GoogleMapScreen(
    modifier: Modifier = Modifier,
    waypoints: List<Waypoint> = emptyList(),
    mapSource: MapSource? = null,
    drawings: List<DrawingFeature> = emptyList(),
    drawingLayers: List<DrawingLayer> = emptyList(),
    draftDrawing: DrawingFeature? = null,
    drawingInputEnabled: Boolean = false,
    freeDrawActive: Boolean = false,
    onFreeDrawPoint: (lat: Double, lng: Double) -> Unit = { _, _ -> },
    onFreeDrawEnd: () -> Unit = {},
    calibrationInputEnabled: Boolean = false,
    mgrsGridVisible: Boolean = false,
    unitLabelsVisible: Boolean = true,
    taskLabelsVisible: Boolean = true,
    drawingLabelsVisible: Boolean = true,
    selectedDrawingId: String? = null,
    selectedWaypointId: String? = null,
    calibrationFiduciaries: List<com.tacticalmaps.calibration.Fiduciary> = emptyList(),
    myLocationEnabled: Boolean = false,
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

    /// Compass tap → animate the camera bearing back to 0° (north up)
    /// while keeping the current target, zoom, and tilt. The viewmodel
    /// emits a Unit on every tap; we drop tilt to 0 too so the map
    /// reads as "flat, north up".
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
                /// We deliberately do NOT clear selection on
                /// gesture-start anymore. The SDK's camera tracker
                /// briefly flips `isMoving = true` for clean taps
                /// (no real camera movement), and that race would
                /// clear the selection that [MapItemTouchOverlay]
                /// just set via `onWaypointTap`. Empty-tap clearing
                /// is now handled by the overlay's `onEmptyTap`,
                /// which only fires when the user lifts without
                /// hitting any item — so we don't need this
                /// fallback any more.
            }
    }
    /// Report bearing changes so the compass chip in the HUD reflects
    /// the current map orientation.
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

    /// Single source of truth for any in-flight touch-and-drag of a
    /// map item (waypoint OR drawing). The unified touch overlay
    /// writes to this; both the SDK polyline renderer and the
    /// Compose waypoint renderer read from it to display the item
    /// following the user's finger in real time.
    var dragState by remember { mutableStateOf<MapItemDrag?>(null) }
    val currentDragState = rememberUpdatedState(dragState)

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(
                /// When a PDF basemap is loaded we hide Google Maps'
                /// satellite tiles entirely (MapType.NONE) so the
                /// PDF doesn't have to compete with the satellite
                /// imagery underneath. Otherwise the user sees the
                /// PDF surrounded by satellite where the page
                /// doesn't cover, which makes the PDF look like
                /// it's "floating" on Google Maps.
                mapType = if (mapSource is PdfMapSource || mapSource is OfflineTileMapSourceAndroid) MapType.NONE
                    else MapType.SATELLITE,
                /// Google Maps' built-in blue user-location dot.
                /// Gated on runtime permission — the SDK throws if
                /// this is true without ACCESS_FINE_LOCATION granted.
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
                    /// Normal mode tap handling lives in
                    /// [MapItemTouchOverlay] — it fires
                    /// `onEmptyTap` (= currentOnMapTap) itself so
                    /// it can sequence selection-clear AFTER any
                    /// waypoint/drawing tap it just dispatched.
                    /// If we also fired it here, the SDK's
                    /// onMapClick would race against the overlay
                    /// and clear selections the overlay just set.
                }
            }
        ) {
            (mapSource as? PdfMapSource)?.let { pdf ->
                PdfGroundOverlay(source = pdf)
            }

            (mapSource as? OfflineTileMapSourceAndroid)?.let { tiles ->
                /// remember on the source id so the provider (and its tile
                /// cache) survives recomposition; only rebuilt on source change.
                val provider = remember(tiles.id) { tiles.tileProvider() }
                TileOverlay(tileProvider = provider)
            }

            if (mgrsGridVisible) {
                MgrsGridLayer()
            }

            visibleDrawings.forEach { feature ->
                /// Live drag preview: if THIS drawing is the active
                /// drag target, compute a lat/lng delta from the
                /// touch's start position to its current position
                /// (via the projection) and apply it to every vertex.
                /// Polyline.points changing causes the SDK to redraw
                /// the shape at the new position on each MOVE event.
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

            /// PDF calibration fiduciaries — render small numbered
            /// pins for each tapped reference point so the user can
            /// see which corners of the PDF they've registered.
            calibrationFiduciaries.forEachIndexed { i, fid ->
                CalibrationFiduciaryMarker(index = i + 1, fid = fid)
            }
        }

        /// Waypoint handles — Compose overlay that ONLY renders the
        /// icons. Touch handling (tap + drag) is owned by the unified
        /// MapItemTouchOverlay below. If this waypoint is the active
        /// drag target, its icon visually follows the finger via
        /// `graphicsLayer { translationX/Y }`.
        WaypointHandlesOverlay(
            waypoints = visibleWaypoints,
            selectedWaypointId = selectedWaypointId,
            cameraPositionState = cameraPositionState,
            dragState = dragState
        )

        /// Waypoint name labels (units / tasks) — Compose Text
        /// overlays projected to screen coords each frame. Units +
        /// generic get a pill below the icon; tasks get the pill
        /// centred inside the graphic.
        WaypointLabelsOverlay(
            waypoints = visibleWaypoints,
            cameraPositionState = cameraPositionState,
            unitLabelsVisible = unitLabelsVisible,
            taskLabelsVisible = taskLabelsVisible,
            dragState = dragState
        )

        /// Drawing name labels — anchored at the centroid for polygons,
        /// midpoint for lines, the point itself for points.
        if (drawingLabelsVisible && !freeDrawActive) {
            DrawingLabelsOverlay(
                drawings = visibleDrawings,
                cameraPositionState = cameraPositionState,
                dragState = dragState
            )
        }

        /// MGRS grid labels — suppressed during freehand drawing to avoid
        /// recomposing hundreds of Text composables on every pointer event.
        if (mgrsGridVisible && !freeDrawActive) {
            MgrsGridLabelsOverlay(cameraPositionState = cameraPositionState)
        }

        /// Single unified touch handler for ALL map items (waypoints
        /// and drawings). Replaces the old per-icon pointerInput
        /// handlers on waypoints and the separate DrawingsDragOverlay.
        /// Hit-tests in z-order (waypoints first, then drawings) and
        /// passes the gesture through to the GoogleMap underneath
        /// when nothing is hit, so pan/zoom still work everywhere
        /// else. Also detects empty taps and dispatches them via
        /// `onEmptyTap` so we can clear selection without relying on
        /// the SDK's onMapClick (which races against our tap handler
        /// and would otherwise immediately clear a waypoint we just
        /// selected).
        MapItemTouchOverlay(
            waypoints = visibleWaypoints,
            drawings = visibleDrawings,
            cameraPositionState = cameraPositionState,
            drawingInputEnabled = drawingInputEnabled,
            calibrationInputEnabled = calibrationInputEnabled,
            dragState = currentDragState,
            onDragStateChange = { dragState = it },
            onWaypointTap = { wp -> currentOnMarkerTap.value(wp) },
            onWaypointMoved = { wp, lat, lng -> currentOnWaypointMoved.value(wp, lat, lng) },
            onDrawingTap = { id -> currentOnDrawingFeatureTap.value(id) },
            onDrawingMoved = { id, dLat, dLng -> currentOnShapeMoved.value(id, dLat, dLng) },
            onEmptyTap = { currentOnMapTap.value() }
        )

        VertexHandlesOverlay(
            feature = selectedDrawing.takeUnless { drawingInputEnabled },
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
    }
}

/// Tracks an in-flight touch-and-drag of a single map item.
/// `startX/Y` is the touch position where the gesture began (window
/// pixels). `offsetX/Y` is the cumulative finger displacement from
/// that start. `didDrag` flips to true once the finger has moved past
/// the tap slop — lift-with-didDrag-false fires the tap callback.



