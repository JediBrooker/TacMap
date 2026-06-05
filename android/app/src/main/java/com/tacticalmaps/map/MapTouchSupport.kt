package com.tacticalmaps.map

import android.graphics.Point
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.waypoints.Waypoint
import kotlin.math.roundToInt

// Map interaction layer — the unified touch overlay (tap/drag of waypoints +
// drawings), the vertex-edit handle overlay, and the screen-space hit-testing.
// Extracted verbatim from GoogleMapScreen.kt. The two composables GoogleMapScreen
// calls are `internal`; MapItemDrag stays public (the overlays in MapOverlays.kt
// read it); the hit-test helpers + gesture state stay `private`. The pure
// distance/polygon math lives in MapGeometry.kt.

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
internal fun VertexHandlesOverlay(
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

    /// Free-hand strokes have far too many vertices to edit meaningfully, so
    /// they get NO vertex handles at all — a dense LINE (> 20 points) is a
    /// free-draw. It stays selectable/movable/deletable via the controls card;
    /// it just isn't vertex-editable.
    if (feature.geometry == DrawingGeometry.LINE && effective.size > 20) {
        return
    }

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
internal fun MapItemTouchOverlay(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    cameraPositionState: CameraPositionState,
    drawingInputEnabled: Boolean,
    calibrationInputEnabled: Boolean,
    locked: Boolean,
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
                        /// Waypoints are native draggable map markers now
                        /// (see [WaypointMarkers]) — the SDK owns their
                        /// tap + long-press-drag and never blocks the map,
                        /// so this overlay only claims DRAWINGS.
                        val wpHit: ProjectedWaypoint? = null
                        /// Locked → claim nothing, so a tap can't open a
                        /// drawing's settings and a drag can't move it.
                        val shapeHitId = if (wpHit == null && !locked) {
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
                        if (!locked &&
                            kotlin.math.hypot(dx, dy) > tapSlopPx &&
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
