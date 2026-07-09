package com.tacmap.map

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
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.waypoints.Waypoint
import kotlin.math.roundToInt

// Unified touch overlay (tap/drag for waypoints + drawings), vertex-edit
// handles, and screen-space hit-testing. Pulled out of GoogleMapScreen.kt.
// Distance/polygon math lives in MapGeometry.kt.

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

    /// Force recompose when user pans/zooms so handles reposition.
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return

    val density = LocalDensity.current
    val sizePx = with(density) { 48.dp.roundToPx() }

    /// Freehand strokes have way too many vertices to edit individually.
    /// Dense LINE (> 20 pts) = freehand, so skip vertex handles entirely.
    /// Still movable/deletable via controls card, just not vertex-editable.
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

    /// Polygons also get a midpoint handle on the closing segment.
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

/// Fullscreen touch handler for all map items (waypoints + drawings).
///
/// Basically: DOWN hit-tests in z-order, if nothing hit we bail and
/// let GoogleMap handle pan. If we do hit something, we track the
/// finger - stays within tap-slop = still don't consume (map might
/// start a brief pan but we cancel it on commit). Crosses slop with
/// one finger = CLAIM the gesture, item follows the finger.
/// Lift before slop = tap. Lift after slop = commit new lat/lng.
///
/// Multi-touch: second finger before drag committed = abandon,
/// let GoogleMap do its pinch thing. Second finger AFTER commit =
/// keep dragging, user obviously meant drag not pinch.
///
/// On commit we project screen position back to lat/lng. Waypoints
/// get dropped at (anchor + offset), drawings shift all vertices.
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
    /// Drawing-input or calibration mode needs taps to reach GoogleMap
    /// for onMapClick, so bail out and don't intercept anything.
    if (drawingInputEnabled || calibrationInputEnabled) return
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val context = LocalContext.current
    val density = LocalDensity.current
    val hitExpandPx = with(density) { 6.dp.toPx() }
    val drawingTolerancePx = with(density) { 22.dp.toPx() }
    val tapSlopPx = with(density) { 8.dp.toPx() }

    /// Project all waypoints to screen-space bounding rects for cheap
    /// hit-testing. Rect = (screen - anchor*size) to (screen + (1-anchor)*size).
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

    /// Gesture state lives outside the lambda b/c the filter gets
    /// recreated on every MotionEvent - need persistent fields.
    val gesture = remember { TouchGestureState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val pos = Offset(event.x, event.y)
                        /// Waypoints use native draggable markers now
                        /// (WaypointMarkers), so this overlay only claims
                        /// DRAWINGS.
                        val wpHit: ProjectedWaypoint? = null
                        /// Locked = claim nothing. No taps, no drags.
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
                        /// Must return true on DOWN to recieve
                        /// further events (Android contract). No hit
                        /// = return false so map gets pan/pinch.
                        /// Hit = claim it. Trade-off: can't pinch
                        /// starting on a graphic, but pinch from
                        /// empty space still works.
                        gesture.itemId != null
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> {
                        /// Second finger showed up. We already
                        /// claimed on DOWN so SDK can't recover
                        /// into a pinch. Just keep going as
                        /// single-finger drag (or tap on lift).
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!gesture.tracking) return@pointerInteropFilter false
                        val itemId = gesture.itemId
                        if (itemId == null) {
                            /// No hit, just watching. If they lift
                            /// within slop on empty space we fire
                            /// onEmptyTap, otherwise map handles pan.
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
                            /// CLAIM - returning true consumes
                            /// the event, GoogleMap gets a CANCEL
                            /// and aborts its pan.
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

                        /// Tap (lift within slop). Consume the UP
                        /// so GoogleMap doesn't also fire onMapClick
                        /// - that race caused selection flicker.
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
                        /// Past slop w/o commit = user panned.
                        /// Don't fire tap, let map have the UP.
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
    /// tracking = true from DOWN to UP/CANCEL. abandoned flips on
    /// second pointer before commit so we don't fire a spurious tap
    /// after a pinch.
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
    /// Reverse so topmost (last drawn) wins.
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

/// Hit-test shapes in z-order (last drawn = topmost). Returns topmost
/// shape ID under the point, or null.
private fun hitTestShapes(
    point: Offset,
    shapes: List<ProjectedShape>,
    tolerancePx: Float
): String? {
    /// Reverse iteration so the shape the user actually sees on
    /// top is the one they grab.
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
