package com.tacticalmaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.tacticalmaps.calibration.MapSource
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
                mapType = if (mapSource is PdfMapSource) MapType.NONE
                    else MapType.SATELLITE,
                /// Google Maps' built-in blue user-location dot.
                /// Gated on runtime permission — the SDK throws if
                /// this is true without ACCESS_FINE_LOCATION granted.
                isMyLocationEnabled = myLocationEnabled
            ),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                /// We render our own "Centre on My Location" pill at
                /// the bottom; suppress the SDK's stock button.
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false
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

            if (mgrsGridVisible) {
                MgrsGridLayer(cameraPositionState = cameraPositionState)
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
        if (drawingLabelsVisible) {
            DrawingLabelsOverlay(
                drawings = visibleDrawings,
                cameraPositionState = cameraPositionState,
                dragState = dragState
            )
        }

        /// MGRS grid labels — drawn as Compose Text on top of the map
        /// so they can be rotated for vertical lines without baking
        /// per-zoom bitmap markers.
        if (mgrsGridVisible) {
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
    }
}

/// Tracks an in-flight touch-and-drag of a single map item.
/// `startX/Y` is the touch position where the gesture began (window
/// pixels). `offsetX/Y` is the cumulative finger displacement from
/// that start. `didDrag` flips to true once the finger has moved past
/// the tap slop — lift-with-didDrag-false fires the tap callback.
data class MapItemDrag(
    val kind: Kind,
    val itemId: String,
    val startX: Float,
    val startY: Float,
    val offsetX: Float,
    val offsetY: Float,
    val didDrag: Boolean
) {
    enum class Kind { WAYPOINT, DRAWING }
}

@Composable
private fun VertexHandlesOverlay(
    feature: DrawingFeature?,
    cameraPositionState: CameraPositionState,
    onVertexMoved: (featureId: String, vertexIndex: Int, lat: Double, lng: Double) -> Unit,
    onVertexInserted: (featureId: String, atIndex: Int, lat: Double, lng: Double) -> Unit,
    onVertexDeleted: (featureId: String, vertexIndex: Int) -> Unit
) {
    if (feature == null) return
    val effective = feature.effectivePoints
    if (effective.size < 2) return

    /// Re-read camera position so the overlay recomposes (and handles
    /// reposition) when the user pans or zooms the map.
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return

    val density = LocalDensity.current
    val sizePx = with(density) { 48.dp.roundToPx() }

    effective.forEachIndexed { i, p ->
        val screen = projection.toScreenLocation(LatLng(p.latitude, p.longitude))
        VertexHandleBox(
            centerX = screen.x,
            centerY = screen.y,
            sizePx = sizePx,
            isMidpoint = false,
            onTap = {},
            onLongPress = { onVertexDeleted(feature.id, i) },
            onDragCommit = { dxPx, dyPx ->
                val proj = cameraPositionState.projection ?: return@VertexHandleBox
                val finalScreen = Point(
                    (screen.x + dxPx).roundToInt(),
                    (screen.y + dyPx).roundToInt()
                )
                val moved = proj.fromScreenLocation(finalScreen)
                onVertexMoved(feature.id, i, moved.latitude, moved.longitude)
            }
        )
    }

    /// Polygons get a midpoint handle for the closing segment too.
    val segmentCount = if (feature.geometry == DrawingGeometry.POLYGON) effective.size
        else effective.size - 1
    for (i in 0 until segmentCount.coerceAtLeast(0)) {
        val a = effective[i]
        val b = effective[(i + 1) % effective.size]
        val midLat = (a.latitude + b.latitude) / 2.0
        val midLng = (a.longitude + b.longitude) / 2.0
        val insertIndex = i + 1
        val screen = projection.toScreenLocation(LatLng(midLat, midLng))
        VertexHandleBox(
            centerX = screen.x,
            centerY = screen.y,
            sizePx = sizePx,
            isMidpoint = true,
            onTap = { onVertexInserted(feature.id, insertIndex, midLat, midLng) },
            onLongPress = {},
            onDragCommit = { dxPx, dyPx ->
                val proj = cameraPositionState.projection ?: return@VertexHandleBox
                val finalScreen = Point(
                    (screen.x + dxPx).roundToInt(),
                    (screen.y + dyPx).roundToInt()
                )
                val moved = proj.fromScreenLocation(finalScreen)
                onVertexInserted(feature.id, insertIndex, moved.latitude, moved.longitude)
            }
        )
    }
}

/// Unified fullscreen touch handler for ALL map items — waypoints
/// (units AND tasks share this code path) and drawings.
///
/// Touch lifecycle (single finger):
///   1. DOWN: hit-test in z-order. If nothing's hit, we return
///      without consuming and the gesture falls through to
///      GoogleMap (pan starts there).
///   2. Finger moves but stays within tap-slop, no extra pointers
///      → we still don't consume; GoogleMap may briefly start
///      panning but we'll cancel it once drag commits.
///   3. Finger crosses tap-slop with no second pointer present
///      → CLAIM. We consume the change, GoogleMap sees a
///      synthetic CANCEL via Compose's interop layer, and the item
///      starts following the finger via the shared `dragState`.
///   4. Lift before slop → fire the tap callback.
///   5. Lift after slop → commit the new lat/lng.
///
/// Multi-touch escape hatch:
///   - If a second pointer arrives BEFORE the drag has committed,
///     we abandon the gesture entirely (no consume). GoogleMap has
///     been receiving every pointer event unconsumed so far, so
///     it can immediately treat the touches as a pinch / two-
///     finger pan. This is the fix for "I can't pinch when my
///     finger is on a graphic".
///   - If a second pointer arrives AFTER drag commits, we keep
///     dragging single-finger — the user clearly meant drag.
///
/// On commit we project the final screen position back to lat/lng:
///   - Waypoint: drop the icon's geographic anchor at
///     (anchor + offset) → exact match for where the icon ended
///     up visually.
///   - Drawing: shift every vertex by (after - before) in lat/lng.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MapItemTouchOverlay(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    cameraPositionState: CameraPositionState,
    drawingInputEnabled: Boolean,
    calibrationInputEnabled: Boolean,
    dragState: androidx.compose.runtime.State<MapItemDrag?>,
    onDragStateChange: (MapItemDrag?) -> Unit,
    onWaypointTap: (Waypoint) -> Unit,
    onWaypointMoved: (waypoint: Waypoint, lat: Double, lng: Double) -> Unit,
    onDrawingTap: (String) -> Unit,
    onDrawingMoved: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit,
    onEmptyTap: () -> Unit
) {
    /// Drawing-input mode (placing vertices on a draft) and
    /// calibration mode (placing PDF fiduciaries) must let every tap
    /// reach the GoogleMap so `onMapClick` fires — bail out.
    if (drawingInputEnabled || calibrationInputEnabled) return
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val hitExpandPx = with(density) { 6.dp.toPx() }
    val drawingTolerancePx = with(density) { 22.dp.toPx() }
    val tapSlopPx = with(density) { 8.dp.toPx() }

    /// Project every waypoint to a screen-space bounding rect so the
    /// pointer callback can hit-test cheaply. The icon's anchor is
    /// pinned to the projected lat/lng so the rect runs from
    /// (screen - anchor*size) to (screen + (1-anchor)*size).
    val projectedWaypoints = remember(waypoints, cameraPositionState.position) {
        waypoints.map { wp ->
            val drawable = SymbolIconFactory.drawableFor(context, wp)
            val anchor = SymbolIconFactory.anchorFor(context, wp)
            val screen = projection.toScreenLocation(LatLng(wp.latitude, wp.longitude))
            val w = drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
            val h = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
            ProjectedWaypoint(
                ref = wp,
                screenX = screen.x.toFloat(),
                screenY = screen.y.toFloat(),
                left = screen.x - anchor.first * w,
                top = screen.y - anchor.second * h,
                right = screen.x + (1f - anchor.first) * w,
                bottom = screen.y + (1f - anchor.second) * h
            )
        }
    }

    val projectedShapes = remember(drawings, cameraPositionState.position) {
        drawings.mapNotNull { feature ->
            if (feature.effectivePoints.isEmpty()) return@mapNotNull null
            val screenPts = feature.effectivePoints.map { p ->
                val sp = projection.toScreenLocation(LatLng(p.latitude, p.longitude))
                Offset(sp.x.toFloat(), sp.y.toFloat())
            }
            ProjectedShape(
                id = feature.id,
                geometry = feature.geometry,
                screenPoints = screenPts
            )
        }
    }

    val currentWaypoints = rememberUpdatedState(projectedWaypoints)
    val currentShapes = rememberUpdatedState(projectedShapes)
    val currentOnWaypointTap = rememberUpdatedState(onWaypointTap)
    val currentOnWaypointMoved = rememberUpdatedState(onWaypointMoved)
    val currentOnDrawingTap = rememberUpdatedState(onDrawingTap)
    val currentOnDrawingMoved = rememberUpdatedState(onDrawingMoved)
    val currentOnEmptyTap = rememberUpdatedState(onEmptyTap)
    val currentCameraPosition = rememberUpdatedState(cameraPositionState)
    val currentOnDragStateChange = rememberUpdatedState(onDragStateChange)

    /// Per-gesture state. Lives outside the filter lambda because
    /// the filter is recreated on every MotionEvent — we need
    /// persistent fields for the in-flight gesture.
    val gesture = remember { TouchGestureState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val pos = Offset(event.x, event.y)
                        val wpHit = hitTestWaypoints(
                            pos, currentWaypoints.value, hitExpandPx
                        )
                        val shapeHitId = if (wpHit == null) {
                            hitTestShapes(
                                pos, currentShapes.value, drawingTolerancePx
                            )
                        } else null
                        gesture.startX = event.x
                        gesture.startY = event.y
                        gesture.committed = false
                        gesture.lastDx = 0f
                        gesture.lastDy = 0f
                        gesture.tracking = true
                        gesture.abandoned = false
                        when {
                            wpHit != null -> {
                                gesture.kind = MapItemDrag.Kind.WAYPOINT
                                gesture.itemId = wpHit.ref.id
                            }
                            shapeHitId != null -> {
                                gesture.kind = MapItemDrag.Kind.DRAWING
                                gesture.itemId = shapeHitId
                            }
                            else -> {
                                gesture.kind = null
                                gesture.itemId = null
                            }
                        }
                        /// pointerInteropFilter follows the Android
                        /// View.onTouchEvent contract: returning
                        /// FALSE on DOWN means we won't receive any
                        /// further events for this gesture. So we
                        /// MUST return true to keep the gesture if
                        /// we want to detect tap/drag at all.
                        ///
                        /// When there's no hit we let the SDK have
                        /// the whole gesture (return false) so pan
                        /// and pinch work natively. When there IS a
                        /// hit we claim it — the trade-off is that
                        /// pinch starting on a graphic doesn't
                        /// work, but pinch from empty space still
                        /// does.
                        gesture.itemId != null
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        /// Second finger arrived. Since we already
                        /// claimed the gesture on DOWN, the SDK
                        /// never saw the first finger and can't
                        /// recover into a pinch. We just keep
                        /// processing as a single-finger drag (or
                        /// tap on lift).
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!gesture.tracking) return@pointerInteropFilter false
                        val itemId = gesture.itemId
                        if (itemId == null) {
                            /// No hit — just observing in case the
                            /// user lifts within tap slop on empty
                            /// space (we'll dispatch onEmptyTap).
                            /// Otherwise the map handles the pan
                            /// because we never consume.
                            return@pointerInteropFilter false
                        }
                        val dx = event.x - gesture.startX
                        val dy = event.y - gesture.startY
                        if (gesture.committed) {
                            gesture.lastDx = dx
                            gesture.lastDy = dy
                            currentOnDragStateChange.value(
                                MapItemDrag(
                                    kind = gesture.kind!!,
                                    itemId = itemId,
                                    startX = gesture.startX,
                                    startY = gesture.startY,
                                    offsetX = dx,
                                    offsetY = dy,
                                    didDrag = true
                                )
                            )
                            return@pointerInteropFilter true
                        }
                        if (kotlin.math.hypot(dx, dy) > tapSlopPx &&
                            event.pointerCount == 1
                        ) {
                            /// CLAIM. Returning true tells Compose
                            /// to mark the event consumed, which
                            /// causes GoogleMap to receive a
                            /// CANCEL and abort its incidental
                            /// pan.
                            gesture.committed = true
                            gesture.lastDx = dx
                            gesture.lastDy = dy
                            currentOnDragStateChange.value(
                                MapItemDrag(
                                    kind = gesture.kind!!,
                                    itemId = itemId,
                                    startX = gesture.startX,
                                    startY = gesture.startY,
                                    offsetX = dx,
                                    offsetY = dy,
                                    didDrag = true
                                )
                            )
                            return@pointerInteropFilter true
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!gesture.tracking) {
                            gesture.reset()
                            return@pointerInteropFilter false
                        }
                        val itemId = gesture.itemId
                        val kind = gesture.kind
                        val committed = gesture.committed
                        val dx = event.x - gesture.startX
                        val dy = event.y - gesture.startY
                        val lastDx = gesture.lastDx
                        val lastDy = gesture.lastDy
                        val startX = gesture.startX
                        val startY = gesture.startY
                        gesture.reset()
                        currentOnDragStateChange.value(null)

                        if (committed && itemId != null && kind != null) {
                            commitDragEnd(
                                kind = kind,
                                hitId = itemId,
                                startX = startX,
                                startY = startY,
                                offsetX = lastDx,
                                offsetY = lastDy,
                                projection = currentCameraPosition.value.projection,
                                waypoints = currentWaypoints.value,
                                onWaypointMoved = currentOnWaypointMoved.value,
                                onDrawingMoved = currentOnDrawingMoved.value
                            )
                            return@pointerInteropFilter true
                        }

                        /// Tap: lift within slop. Fire the right
                        /// callback and CONSUME the UP (return
                        /// true) so the GoogleMap underneath
                        /// doesn't also fire its onMapClick — that
                        /// race is what made waypoint selection
                        /// flicker and immediately disappear.
                        if (kotlin.math.hypot(dx, dy) < tapSlopPx) {
                            when {
                                itemId != null && kind == MapItemDrag.Kind.WAYPOINT -> {
                                    val wp = currentWaypoints.value
                                        .firstOrNull { it.ref.id == itemId }?.ref
                                    if (wp != null) currentOnWaypointTap.value(wp)
                                }
                                itemId != null && kind == MapItemDrag.Kind.DRAWING ->
                                    currentOnDrawingTap.value(itemId)
                                else -> currentOnEmptyTap.value()
                            }
                            return@pointerInteropFilter true
                        }
                        /// Movement past slop without commit means
                        /// the user panned — don't fire a tap, let
                        /// the map have the UP.
                        false
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        gesture.reset()
                        currentOnDragStateChange.value(null)
                        false
                    }
                    else -> false
                }
            }
    )
}

private class TouchGestureState {
    var kind: MapItemDrag.Kind? = null
    var itemId: String? = null
    var startX: Float = 0f
    var startY: Float = 0f
    var committed: Boolean = false
    var lastDx: Float = 0f
    var lastDy: Float = 0f
    /// `tracking` is true between ACTION_DOWN and ACTION_UP /
    /// ACTION_CANCEL. `abandoned` flips when a second pointer comes
    /// down before drag-commit (so we don't accidentally fire a tap
    /// when the user lifts their first finger after pinch).
    var tracking: Boolean = false
    var abandoned: Boolean = false

    fun reset() {
        kind = null
        itemId = null
        startX = 0f
        startY = 0f
        committed = false
        lastDx = 0f
        lastDy = 0f
        tracking = false
        abandoned = false
    }
}

private fun commitDragEnd(
    kind: MapItemDrag.Kind,
    hitId: String,
    startX: Float,
    startY: Float,
    offsetX: Float,
    offsetY: Float,
    projection: com.google.android.gms.maps.Projection?,
    waypoints: List<ProjectedWaypoint>,
    onWaypointMoved: (waypoint: Waypoint, lat: Double, lng: Double) -> Unit,
    onDrawingMoved: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit
) {
    val proj = projection ?: return
    when (kind) {
        MapItemDrag.Kind.WAYPOINT -> {
            val wpProj = waypoints.firstOrNull { it.ref.id == hitId } ?: return
            val after = proj.fromScreenLocation(
                Point(
                    (wpProj.screenX + offsetX).roundToInt(),
                    (wpProj.screenY + offsetY).roundToInt()
                )
            )
            onWaypointMoved(wpProj.ref, after.latitude, after.longitude)
        }
        MapItemDrag.Kind.DRAWING -> {
            val before = proj.fromScreenLocation(
                Point(startX.roundToInt(), startY.roundToInt())
            )
            val after = proj.fromScreenLocation(
                Point(
                    (startX + offsetX).roundToInt(),
                    (startY + offsetY).roundToInt()
                )
            )
            onDrawingMoved(
                hitId,
                after.latitude - before.latitude,
                after.longitude - before.longitude
            )
        }
    }
}

private data class ProjectedWaypoint(
    val ref: Waypoint,
    val screenX: Float,
    val screenY: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

private fun hitTestWaypoints(
    point: Offset,
    waypoints: List<ProjectedWaypoint>,
    expandPx: Float
): ProjectedWaypoint? {
    /// Reverse iterate so the icon drawn last (visually topmost)
    /// wins ties.
    for (i in waypoints.indices.reversed()) {
        val w = waypoints[i]
        if (point.x in (w.left - expandPx)..(w.right + expandPx) &&
            point.y in (w.top - expandPx)..(w.bottom + expandPx)
        ) {
            return w
        }
    }
    return null
}

private data class ProjectedShape(
    val id: String,
    val geometry: DrawingGeometry,
    val screenPoints: List<Offset>
)


/// Z-ordered (last drawn = topmost) hit-test against projected shapes.
/// Returns the topmost shape ID that the point lands on, or null.
private fun hitTestShapes(
    point: Offset,
    shapes: List<ProjectedShape>,
    tolerancePx: Float
): String? {
    /// Iterate in reverse so shapes drawn last (visually on top)
    /// win ties — matches the user's expectation of grabbing the
    /// shape they see, not whichever was added first.
    for (i in shapes.indices.reversed()) {
        val s = shapes[i]
        if (shapeHit(point, s, tolerancePx)) return s.id
    }
    return null
}

private fun shapeHit(point: Offset, shape: ProjectedShape, tolerancePx: Float): Boolean {
    val pts = shape.screenPoints
    if (pts.isEmpty()) return false
    return when (shape.geometry) {
        DrawingGeometry.POINT -> {
            val p = pts.first()
            kotlin.math.hypot(point.x - p.x, point.y - p.y) <= tolerancePx + 12f
        }
        DrawingGeometry.LINE -> {
            if (pts.size < 2) {
                val p = pts.first()
                kotlin.math.hypot(point.x - p.x, point.y - p.y) <= tolerancePx
            } else {
                pointToPolylineDistance(point, pts) <= tolerancePx
            }
        }
        DrawingGeometry.POLYGON -> {
            if (pts.size < 3) {
                pointToPolylineDistance(point, pts) <= tolerancePx
            } else {
                pointInPolygon(point, pts) ||
                    pointToPolylineDistance(point, pts + pts.first()) <= tolerancePx
            }
        }
    }
}

@Composable
private fun VertexHandleBox(
    centerX: Int,
    centerY: Int,
    sizePx: Int,
    isMidpoint: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragCommit: (dxPx: Float, dyPx: Float) -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnTap = rememberUpdatedState(onTap)
    val currentOnLongPress = rememberUpdatedState(onLongPress)
    val currentOnDragCommit = rememberUpdatedState(onDragCommit)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    centerX - sizePx / 2 + dragOffset.x.roundToInt(),
                    centerY - sizePx / 2 + dragOffset.y.roundToInt()
                )
            }
            .size(with(LocalDensity.current) { sizePx.toDp() })
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val dx = dragOffset.x
                        val dy = dragOffset.y
                        dragOffset = Offset.Zero
                        currentOnDragCommit.value(dx, dy)
                    },
                    onDragCancel = { dragOffset = Offset.Zero }
                ) { change, drag ->
                    change.consume()
                    dragOffset += drag
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { currentOnTap.value() },
                    onLongPress = { currentOnLongPress.value() }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val orange = Color(0xFFFFA63D)
            val white = Color.White
            val center = Offset(size.width / 2f, size.height / 2f)
            val visibleR = (26.dp.toPx() / 2f) - 2.dp.toPx()
            if (isMidpoint) {
                drawCircle(white.copy(alpha = 0.86f), visibleR, center)
                drawCircle(orange, visibleR, center, style = Stroke(width = 2.dp.toPx()))
                val arm = 6.dp.toPx()
                drawLine(
                    orange,
                    Offset(center.x, center.y - arm),
                    Offset(center.x, center.y + arm),
                    strokeWidth = 2.5.dp.toPx()
                )
                drawLine(
                    orange,
                    Offset(center.x - arm, center.y),
                    Offset(center.x + arm, center.y),
                    strokeWidth = 2.5.dp.toPx()
                )
            } else {
                drawCircle(orange, visibleR, center)
                drawCircle(white, visibleR, center, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

@Composable
private fun DrawingShape(
    feature: DrawingFeature,
    isDraft: Boolean,
    selected: Boolean,
    inputEnabled: Boolean,
    dragOffsetLatLng: Pair<Double, Double>?,
    onTap: (() -> Unit)?
) {
    val effective = remember(
        feature.points,
        feature.scaleX,
        feature.scaleY,
        feature.rotationDegrees,
        feature.geometry,
        dragOffsetLatLng
    ) {
        val base = feature.effectivePoints
        if (dragOffsetLatLng == null) {
            base.map { LatLng(it.latitude, it.longitude) }
        } else {
            val (dLat, dLng) = dragOffsetLatLng
            base.map { LatLng(it.latitude + dLat, it.longitude + dLng) }
        }
    }
    if (effective.isEmpty()) return

    val strokeColor = Color(feature.strokeColor)
    /// Fill is always a translucent version of the stroke. This matches
    /// every DrawingFeature construction site in [MapScreen] (which sets
    /// `fillColor = strokeColor.withAlpha(0x33)`) and enforces the
    /// invariant defensively at render time in case a stored feature
    /// drifted (e.g. older format, imported GeoJSON with mismatched
    /// colours).
    val fillColor = Color(feature.strokeColor and 0x00FFFFFF or 0x33000000)
    val baseWidth = if (isDraft) feature.strokeWidth + 2f else feature.strokeWidth
    val width = baseWidth
    val pattern: List<PatternItem>? = if (feature.strokeStyle == DrawingStrokeStyle.DASHED) {
        listOf(Dash(width * 3f), Gap(width * 2f))
    } else null
    /// Wider, translucent tactical-orange halo painted UNDER the
    /// real polyline / polygon when the shape is selected — gives
    /// the same "selection glow" affordance the waypoint icons get
    /// and matches the iOS thicken-stroke pattern but with colour.
    val haloColor = Color(0xFFFFA63D)
    val haloWidth = width + 14f

    /// Tapping a feature while drawing is in progress would otherwise
    /// swallow vertex placement.
    val clickable = onTap != null && !inputEnabled
    val handleClick: () -> Unit = onTap ?: {}

    when (feature.geometry) {
        DrawingGeometry.POINT -> {
            /// Point geometry renders as a small filled circle around
            /// the single coordinate. Use a tiny polygon approximation
            /// — Google Maps SDK has Circle, but a small polygon keeps
            /// the dashed-stroke + fill story consistent with line /
            /// polygon shapes above.
            val center = effective.first()
            val ring = remember(center, width) { pointCircle(center, radiusMetres = 12.0) }
            Polygon(
                points = ring,
                fillColor = fillColor,
                strokeColor = strokeColor,
                strokeWidth = width,
                strokePattern = pattern,
                clickable = clickable,
                onClick = { handleClick() }
            )
        }
        DrawingGeometry.LINE -> {
            if (effective.size < 2 && !isDraft) return
            if (effective.size < 2) {
                /// First vertex of an in-progress line: drop a small
                /// marker so the user can see the seed before they
                /// place the second vertex.
                DraftSeedMarker(effective.first())
                return
            }
            if (selected) {
                Polyline(
                    points = effective,
                    color = haloColor.copy(alpha = 0.55f),
                    width = haloWidth,
                    clickable = false,
                    zIndex = -0.1f
                )
            }
            Polyline(
                points = effective,
                color = strokeColor,
                width = width,
                pattern = pattern,
                clickable = clickable,
                onClick = { handleClick() }
            )
        }
        DrawingGeometry.POLYGON -> {
            if (effective.size < 2 && !isDraft) return
            if (effective.size < 2) {
                DraftSeedMarker(effective.first())
                return
            }
            if (effective.size < 3) {
                if (selected) {
                    Polyline(
                        points = effective,
                        color = haloColor.copy(alpha = 0.55f),
                        width = haloWidth,
                        clickable = false,
                        zIndex = -0.1f
                    )
                }
                Polyline(
                    points = effective,
                    color = strokeColor,
                    width = width,
                    pattern = pattern,
                    clickable = clickable,
                    onClick = { handleClick() }
                )
                return
            }
            if (selected) {
                Polyline(
                    points = effective + effective.first(),
                    color = haloColor.copy(alpha = 0.55f),
                    width = haloWidth,
                    clickable = false,
                    zIndex = -0.1f
                )
            }
            Polygon(
                points = effective,
                fillColor = fillColor,
                strokeColor = strokeColor,
                strokeWidth = width,
                strokePattern = pattern,
                clickable = clickable,
                onClick = { handleClick() }
            )
        }
    }
}

@Composable
private fun DraftSeedMarker(point: LatLng) {
    val markerState = rememberMarkerState(position = point)
    LaunchedEffect(point) {
        if (markerState.position != point) markerState.position = point
    }
    Marker(
        state = markerState,
        anchor = Offset(0.5f, 0.5f),
        flat = true,
        icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
    )
}

/// Approximate a small circle (in metres) around a centre point with a
/// 24-point polygon. Used for POINT geometry rendering.
private fun pointCircle(center: LatLng, radiusMetres: Double, segments: Int = 24): List<LatLng> {
    val latRadius = radiusMetres / 111_320.0
    val lngRadius = radiusMetres / (111_320.0 * kotlin.math.cos(Math.toRadians(center.latitude)).coerceAtLeast(0.000001))
    return (0 until segments).map { i ->
        val theta = 2 * Math.PI * i / segments
        LatLng(
            center.latitude + latRadius * kotlin.math.sin(theta),
            center.longitude + lngRadius * kotlin.math.cos(theta)
        )
    }
}

@Composable
private fun MgrsGridLayer(cameraPositionState: CameraPositionState) {
    /// Re-read camera position so this composable rebuilds when the
    /// user pans / zooms — the grid lines depend on the visible region.
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val bounds = projection.visibleRegion.latLngBounds

    val density = LocalDensity.current.density
    val mapWidthPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.toInt().coerceAtLeast(1)

    val ink = Color(MgrsGridRenderer.INK_COLOR)
    val sw = bounds.southwest
    val ne = bounds.northeast
    val built = remember(sw.latitude, sw.longitude, ne.latitude, ne.longitude, mapWidthPx) {
        MgrsGridRenderer.build(
            minLat = sw.latitude,
            minLng = sw.longitude,
            maxLat = ne.latitude,
            maxLng = ne.longitude,
            mapWidthPx = mapWidthPx
        )
    }

    /// Lines render inside the GoogleMap composable's scope (this
    /// composable is called from inside GoogleMap { … }), so we can
    /// emit Polyline directly here.
    built.first.forEach { seg ->
        Polyline(
            points = listOf(
                LatLng(seg.start.latitude, seg.start.longitude),
                LatLng(seg.end.latitude, seg.end.longitude)
            ),
            color = ink,
            width = MgrsGridRenderer.lineWidthDp(seg.type) * density,
            clickable = false,
            zIndex = -0.5f
        )
    }
}

/// MGRS grid labels live OUTSIDE the GoogleMap composable scope (they
/// need to draw Compose Text on top of the map, projected to screen
/// coords each frame) — they're rendered by [MgrsGridLabelsOverlay]
/// in the parent Box.
@Composable
private fun MgrsGridLabelsOverlay(cameraPositionState: CameraPositionState) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val bounds = projection.visibleRegion.latLngBounds

    val mapWidthPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }.toInt().coerceAtLeast(1)

    val sw = bounds.southwest
    val ne = bounds.northeast
    val labels = remember(sw.latitude, sw.longitude, ne.latitude, ne.longitude, mapWidthPx) {
        MgrsGridRenderer.build(
            minLat = sw.latitude,
            minLng = sw.longitude,
            maxLat = ne.latitude,
            maxLng = ne.longitude,
            mapWidthPx = mapWidthPx
        ).second
    }

    val ink = Color(MgrsGridRenderer.LABEL_TEXT_COLOR)
    val halo = Color(0xE6FFFFFF)

    labels.forEach { mark ->
        val screen = projection.toScreenLocation(LatLng(mark.lat, mark.lng))
        val sp = MgrsGridRenderer.labelTextSp(mark.type)
        val rotation = if (mark.isVertical) -90f else 0f
        ScreenAnchoredOverlay(screenX = screen.x, screenY = screen.y) {
            Box(contentAlignment = Alignment.Center) {
                /// Soft white halo via four offset passes — keeps
                /// the dark digits readable on busy satellite tiles
                /// without a visible pill.
                for (dx in listOf(-1f, 1f)) {
                    for (dy in listOf(-1f, 1f)) {
                        Text(
                            text = mark.text,
                            color = halo,
                            fontSize = sp.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .offset(x = dx.dp, y = dy.dp)
                                .graphicsLayer { rotationZ = rotation }
                        )
                    }
                }
                Text(
                    text = mark.text,
                    color = ink,
                    fontSize = sp.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation }
                )
            }
        }
    }
}

@Composable
private fun PdfGroundOverlay(source: PdfMapSource) {
    val bounds = source.coverage ?: return
    val context = LocalContext.current

    /// Render the PDF's first page once per URI as a single high-res
    /// bitmap and stretch it across the source's coverage bounds via
    /// a GroundOverlay. Simple, robust, and at the 4096-px max
    /// dimension set in [PdfPageRenderer] it stays readable through
    /// several zoom steps. The previous tile-based approach was more
    /// flexible but proved slow to first-render and held multiple
    /// PdfRenderer instances in native memory, which tripped the
    /// low-memory killer on larger PDFs.
    var image by remember(source.uri) {
        mutableStateOf<BitmapDescriptor?>(null)
    }
    LaunchedEffect(source.uri) {
        val rendered = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PdfPageRenderer.renderFirstPage(context, source.uri)
            }
        }.getOrNull() ?: return@LaunchedEffect
        image = BitmapDescriptorFactory.fromBitmap(rendered.bitmap)
    }

    val descriptor = image ?: return
    val latLngBounds = remember(bounds) {
        LatLngBounds(
            LatLng(bounds.southwest.latitude, bounds.southwest.longitude),
            LatLng(bounds.northeast.latitude, bounds.northeast.longitude)
        )
    }
    GroundOverlay(
        position = GroundOverlayPosition.create(latLngBounds),
        image = descriptor,
        clickable = false,
        zIndex = -1f
    )
}

/// Overlay that renders each waypoint icon at the projected screen
/// coordinate of its lat/lng. RENDERING ONLY — all touch handling
/// (tap + drag) lives in [MapItemTouchOverlay]. When a waypoint is
/// the active drag target, its icon visually follows the finger via
/// `graphicsLayer { translationX/Y }`.
@Composable
private fun WaypointHandlesOverlay(
    waypoints: List<Waypoint>,
    selectedWaypointId: String?,
    cameraPositionState: CameraPositionState,
    dragState: MapItemDrag?
) {
    /// Re-read camera position so handles reproject on every pan /
    /// zoom — same trick as VertexHandlesOverlay.
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val context = LocalContext.current
    val density = LocalDensity.current

    waypoints.forEach { wp ->
        val rawIcon = remember(wp.kind, wp.rotation, wp.scaleX, wp.scaleY) {
            SymbolIconFactory.drawableFor(context, wp)
        }
        val rawAnchor = remember(wp.kind) {
            SymbolIconFactory.anchorFor(context, wp)
        }
        val isSelected = wp.id == selectedWaypointId

        /// Pre-bake the icon (with halo when selected) into a
        /// Bitmap + anchor.
        val (iconBmp, anchor) = remember(rawIcon, rawAnchor, isSelected) {
            if (isSelected) {
                val (glowed, ga) = applySelectionGlow(context, rawIcon, rawAnchor)
                glowed.bitmap to ga
            } else {
                val w = rawIcon.intrinsicWidth.coerceAtLeast(1)
                val h = rawIcon.intrinsicHeight.coerceAtLeast(1)
                val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                rawIcon.setBounds(0, 0, w, h)
                rawIcon.draw(Canvas(bm))
                bm to rawAnchor
            }
        }

        val screen = projection.toScreenLocation(LatLng(wp.latitude, wp.longitude))
        val boxW = iconBmp.width
        val boxH = iconBmp.height
        val anchorPxX = (anchor.first * boxW).roundToInt()
        val anchorPxY = (anchor.second * boxH).roundToInt()

        /// Apply the active drag's screen-pixel offset visually so
        /// the icon follows the finger in real time without changing
        /// its layout origin (and thus without disturbing any other
        /// overlays' coordinate frames).
        val activeDrag = dragState?.takeIf {
            it.kind == MapItemDrag.Kind.WAYPOINT && it.itemId == wp.id
        }
        val dx = activeDrag?.offsetX ?: 0f
        val dy = activeDrag?.offsetY ?: 0f

        Box(
            modifier = Modifier
                .offset { IntOffset(screen.x - anchorPxX, screen.y - anchorPxY) }
                .size(
                    width = with(density) { boxW.toDp() },
                    height = with(density) { boxH.toDp() }
                )
                .graphicsLayer {
                    translationX = dx
                    translationY = dy
                }
        ) {
            androidx.compose.foundation.Image(
                bitmap = iconBmp.asImageBitmap(),
                contentDescription = wp.name,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/// Composite an orange halo behind the icon by drawing the icon's
/// alpha mask multiple times with a tinted blur, then drawing the
/// crisp icon on top. Returns the larger bitmap drawable plus a new
/// anchor that keeps the original icon's anchor pixel pinned to the
/// marker's geographic position.
private fun applySelectionGlow(
    context: android.content.Context,
    icon: Drawable,
    originalAnchor: Pair<Float, Float>
): Pair<BitmapDrawable, Pair<Float, Float>> {
    val density = context.resources.displayMetrics.density
    val pad = (18f * density).toInt().coerceAtLeast(18)
    val w = icon.intrinsicWidth.coerceAtLeast(1)
    val h = icon.intrinsicHeight.coerceAtLeast(1)
    val outW = w + pad * 2
    val outH = h + pad * 2

    val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    icon.setBounds(0, 0, w, h)
    icon.draw(Canvas(src))
    val alpha = src.extractAlpha()

    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)

    val outerGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFA63D.toInt()
        maskFilter = android.graphics.BlurMaskFilter(
            16f * density,
            android.graphics.BlurMaskFilter.Blur.NORMAL
        )
    }
    repeat(4) {
        canvas.drawBitmap(alpha, pad.toFloat(), pad.toFloat(), outerGlow)
    }
    val innerGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFA63D.toInt()
        maskFilter = android.graphics.BlurMaskFilter(
            6f * density,
            android.graphics.BlurMaskFilter.Blur.SOLID
        )
    }
    repeat(3) {
        canvas.drawBitmap(alpha, pad.toFloat(), pad.toFloat(), innerGlow)
    }
    canvas.drawBitmap(src, pad.toFloat(), pad.toFloat(), null)

    val newAnchorU = (pad + originalAnchor.first * w) / outW
    val newAnchorV = (pad + originalAnchor.second * h) / outH
    return BitmapDrawable(context.resources, out) to (newAnchorU to newAnchorV)
}

/// Per-waypoint name labels rendered as Compose overlays on top of
/// the map. Tasks (control measures) place the label centred inside
/// the symbol bubble; units / generic waypoints sit the label
/// horizontally centred on the icon's visual centre and just below
/// the icon's bottom edge. During a drag the label follows the icon
/// via the same screen-pixel offset.
@Composable
private fun WaypointLabelsOverlay(
    waypoints: List<Waypoint>,
    cameraPositionState: CameraPositionState,
    unitLabelsVisible: Boolean,
    taskLabelsVisible: Boolean,
    dragState: MapItemDrag?
) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val labelGapPx = with(density) { 2.dp.toPx() }.toInt()

    waypoints.forEach { wp ->
        val trimmed = wp.name.trim()
        if (trimmed.isEmpty()) return@forEach
        val isTask = wp.kind is com.tacticalmaps.waypoints.WaypointKind.ControlMeasure
        val visible = if (isTask) taskLabelsVisible else unitLabelsVisible
        if (!visible) return@forEach

        val screen = projection.toScreenLocation(LatLng(wp.latitude, wp.longitude))
        val activeDrag = dragState?.takeIf {
            it.kind == MapItemDrag.Kind.WAYPOINT && it.itemId == wp.id
        }
        val dragDx = activeDrag?.offsetX?.roundToInt() ?: 0
        val dragDy = activeDrag?.offsetY?.roundToInt() ?: 0

        if (isTask) {
            /// Tasks: centred on the projected anchor (matches the
            /// icon's geometric centre because control-measure
            /// anchors are 0.5/0.5).
            ScreenAnchoredOverlay(
                screenX = screen.x + dragDx,
                screenY = screen.y + dragDy
            ) { LabelPill(text = trimmed) }
        } else {
            /// Units / generic: anchor on the icon's VISIBLE centre
            /// (using the rendered bitmap's visible bounds, so any
            /// transparent padding baked into the SVG/asset doesn't
            /// throw the label off-centre or push it further below)
            /// and sit the label just below the icon's visible
            /// bottom edge.
            val drawable = remember(wp.kind, wp.rotation, wp.scaleX, wp.scaleY) {
                SymbolIconFactory.drawableFor(context, wp)
            }
            val anchor = remember(wp.kind) {
                SymbolIconFactory.anchorFor(context, wp)
            }
            val visibleBounds = remember(wp.kind, wp.rotation, wp.scaleX, wp.scaleY) {
                SymbolIconFactory.visibleBoundsFor(context, wp)
            }
            val iconW = drawable.intrinsicWidth.coerceAtLeast(1)
            val iconH = drawable.intrinsicHeight.coerceAtLeast(1)
            val anchorPxX = (anchor.first * iconW).roundToInt()
            val anchorPxY = (anchor.second * iconH).roundToInt()
            /// Bitmap top-left in screen coords.
            val bmpLeftX = screen.x - anchorPxX
            val bmpTopY = screen.y - anchorPxY
            val visibleCenterX = bmpLeftX + (visibleBounds.left + visibleBounds.right) / 2 + dragDx
            val visibleBottomY = bmpTopY + visibleBounds.bottom + dragDy
            ScreenAnchoredOverlay(
                screenX = visibleCenterX,
                screenY = visibleBottomY + labelGapPx,
                anchor = ScreenAnchor.TOP
            ) { LabelPill(text = trimmed) }
        }
    }
}

/// Drawing name labels — one per named drawing, anchored at the
/// shape's labelAnchor (centroid / mid-segment / point). Non-
/// interactive; the unified touch overlay handles taps. During a
/// drag the label follows the shape.
@Composable
private fun DrawingLabelsOverlay(
    drawings: List<DrawingFeature>,
    cameraPositionState: CameraPositionState,
    dragState: MapItemDrag?
) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return

    drawings.forEach { feature ->
        val trimmed = feature.name.trim()
        if (trimmed.isEmpty()) return@forEach
        val anchor = feature.labelAnchor ?: return@forEach

        val screen = projection.toScreenLocation(LatLng(anchor.latitude, anchor.longitude))
        val activeDrag = dragState?.takeIf {
            it.kind == MapItemDrag.Kind.DRAWING && it.itemId == feature.id
        }
        val dragDx = activeDrag?.offsetX?.roundToInt() ?: 0
        val dragDy = activeDrag?.offsetY?.roundToInt() ?: 0

        ScreenAnchoredOverlay(
            screenX = screen.x + dragDx,
            screenY = screen.y + dragDy
        ) {
            LabelPill(text = trimmed)
        }
    }
}

private enum class ScreenAnchor { CENTER, TOP }

/// Lay a single child out at an absolute screen coordinate. With
/// `ScreenAnchor.CENTER` the child's CENTRE sits at (`screenX`,
/// `screenY + yOffsetPx`); with `ScreenAnchor.TOP` the child's TOP
/// edge sits at that point (horizontal centring is unchanged). The
/// Layout itself reports a zero footprint so it doesn't push siblings
/// around.
@Composable
private fun ScreenAnchoredOverlay(
    screenX: Int,
    screenY: Int,
    yOffsetPx: Int = 0,
    anchor: ScreenAnchor = ScreenAnchor.CENTER,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(content = content) { measurables, constraints ->
        val child = measurables.firstOrNull()
            ?: return@Layout layout(0, 0) {}
        val placeable = child.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(0, 0) {
            val yShift = when (anchor) {
                ScreenAnchor.CENTER -> -placeable.height / 2
                ScreenAnchor.TOP -> 0
            }
            placeable.place(
                x = screenX - placeable.width / 2,
                y = screenY + yOffsetPx + yShift
            )
        }
    }
}

@Composable
private fun LabelPill(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .background(
                Color.Black.copy(alpha = 0.62f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 5.dp, vertical = 2.dp)
    )
}

/// Numbered pin for one PDF-calibration fiduciary. The pin's geographic
/// position is the MGRS the user typed for that PDF corner; the
/// number identifies the order so the user knows which fiduciary
/// they've placed.
@Composable
private fun CalibrationFiduciaryMarker(index: Int, fid: com.tacticalmaps.calibration.Fiduciary) {
    val context = LocalContext.current
    val markerState = rememberMarkerState(position = LatLng(fid.latitude, fid.longitude))
    val descriptor = remember(index) {
        makeFiduciaryPinDrawable(context, index).toBitmapDescriptor()
    }
    Marker(
        state = markerState,
        icon = descriptor,
        anchor = Offset(0.5f, 1f),
        title = "Fiduciary $index",
        snippet = fid.mgrs,
        zIndex = 1f
    )
}

/// Build a small pin-shaped bitmap with a number stamped inside.
/// Tactical orange so it pops against satellite and PDF basemaps.
private fun makeFiduciaryPinDrawable(
    context: android.content.Context,
    index: Int
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val size = (32f * density).toInt()
    val bmp = Bitmap.createBitmap(size, size + (8f * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val r = size / 2f - 2f * density

    val orange = 0xFFFFA63D.toInt()
    val white = 0xFFFFFFFF.toInt()
    val text = 0xFF1A1A1A.toInt()

    /// Pin tail — small triangle pointing down from the disc's bottom.
    val tailPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = orange
    }
    val path = android.graphics.Path().apply {
        moveTo(cx - 5f * density, cy + r - 1f)
        lineTo(cx + 5f * density, cy + r - 1f)
        lineTo(cx, bmp.height.toFloat() - 1f)
        close()
    }
    canvas.drawPath(path, tailPaint)

    /// Disc.
    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = orange
    }
    canvas.drawCircle(cx, cy, r, fill)
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = white
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    canvas.drawCircle(cx, cy, r, stroke)

    /// Number — centred in the disc.
    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = text
        textSize = 14f * density
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }
    val fm = textPaint.fontMetrics
    val baselineY = cy - (fm.ascent + fm.descent) / 2f
    canvas.drawText(index.toString(), cx, baselineY, textPaint)

    return BitmapDrawable(context.resources, bmp)
}

private fun Drawable.toBitmapDescriptor(): BitmapDescriptor {
    if (this is BitmapDrawable) {
        bitmap?.let { return BitmapDescriptorFactory.fromBitmap(it) }
    }
    val w = intrinsicWidth.coerceAtLeast(1)
    val h = intrinsicHeight.coerceAtLeast(1)
    val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, w, h)
    draw(Canvas(bm))
    return BitmapDescriptorFactory.fromBitmap(bm)
}
