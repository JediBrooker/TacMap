package com.tacmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.map.render.MapCamera
import com.tacmap.map.render.MapProjection
import com.tacmap.waypoints.Waypoint
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.roundToInt

data class MapItemDrag(
    val kind: Kind, val itemId: String,
    val startX: Float, val startY: Float, val offsetX: Float, val offsetY: Float, val didDrag: Boolean
) { enum class Kind { WAYPOINT, DRAWING } }

// The SDK-free interaction layer for CustomMapScreen. Ports MapItemTouchOverlay +
// VertexHandlesOverlay off the Google Projection onto MapProjection(MapCamera).
// Unlike the SDK path (where waypoints used native draggable markers), this
// hit-tests waypoints itself since the custom renderer has no native markers.

@Composable
internal fun VertexHandlesOverlayCustom(
    feature: DrawingFeature?,
    camera: MapCamera,
    density: Float,
    onVertexMoved: (featureId: String, vertexIndex: Int, lat: Double, lng: Double) -> Unit,
    onVertexInserted: (featureId: String, atIndex: Int, lat: Double, lng: Double) -> Unit,
    onVertexDeleted: (featureId: String, vertexIndex: Int) -> Unit
) {
    if (feature == null) return
    val effective = feature.effectivePoints
    if (effective.size < 2) return
    if (feature.geometry == DrawingGeometry.LINE && effective.size > 20) return

    val proj = remember(camera, density) { MapProjection(camera, density) }
    val sizePx = with(LocalDensity.current) { 48.dp.roundToPx() }

    effective.forEachIndexed { i, p ->
        val screen = proj.toScreen(p.latitude, p.longitude)
        CustomVertexHandleBox(
            centerX = screen.x.roundToInt(), centerY = screen.y.roundToInt(),
            sizePx = sizePx, isMidpoint = false,
            onTap = {}, onLongPress = { onVertexDeleted(feature.id, i) },
            onDragCommit = { dx, dy ->
                val (lat, lng) = proj.fromScreen(screen.x + dx, screen.y + dy)
                onVertexMoved(feature.id, i, lat, lng)
            }
        )
    }

    val segmentCount = if (feature.geometry == DrawingGeometry.POLYGON) effective.size else effective.size - 1
    for (i in 0 until segmentCount.coerceAtLeast(0)) {
        val a = effective[i]
        val b = effective[(i + 1) % effective.size]
        val midLat = (a.latitude + b.latitude) / 2.0
        val midLng = (a.longitude + b.longitude) / 2.0
        val insertIndex = i + 1
        val screen = proj.toScreen(midLat, midLng)
        CustomVertexHandleBox(
            centerX = screen.x.roundToInt(), centerY = screen.y.roundToInt(),
            sizePx = sizePx, isMidpoint = true,
            onTap = { onVertexInserted(feature.id, insertIndex, midLat, midLng) },
            onLongPress = {},
            onDragCommit = { dx, dy ->
                val (lat, lng) = proj.fromScreen(screen.x + dx, screen.y + dy)
                onVertexInserted(feature.id, insertIndex, lat, lng)
            }
        )
    }
}

@Composable
internal fun MapItemTouchOverlayCustom(
    waypoints: List<Waypoint>,
    drawings: List<DrawingFeature>,
    camera: MapCamera,
    density: Float,
    drawingInputEnabled: Boolean,
    calibrationInputEnabled: Boolean,
    locked: Boolean,
    onDragStateChange: (MapItemDrag?) -> Unit,
    onWaypointTap: (Waypoint) -> Unit,
    onWaypointMoved: (waypoint: Waypoint, lat: Double, lng: Double) -> Unit,
    onDrawingTap: (String) -> Unit,
    onDrawingMoved: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit,
    minZoom: Double,
    maxZoom: Double,
    onCameraChange: (MapCamera) -> Unit,
    onMapGestureStart: () -> Unit,
    onEmptyTap: () -> Unit
) {
    if (drawingInputEnabled || calibrationInputEnabled) return
    val proj = remember(camera, density) { MapProjection(camera, density) }
    val context = LocalContext.current
    val drawingTolerancePx = with(LocalDensity.current) { 22.dp.toPx() }
    val tapSlopPx = with(LocalDensity.current) { 8.dp.toPx() }
    val hitExpandPx = with(LocalDensity.current) { 6.dp.toPx() }

    val projectedWaypoints = remember(waypoints, camera) {
        waypoints.map { wp ->
            val drawable = SymbolIconFactory.drawableFor(context, wp)
            val anchor = SymbolIconFactory.anchorFor(context, wp)
            val s = proj.toScreen(wp.latitude, wp.longitude)
            val w = drawable.intrinsicWidth.coerceAtLeast(1).toFloat()
            val h = drawable.intrinsicHeight.coerceAtLeast(1).toFloat()
            CProjWaypoint(wp, s.x, s.y,
                s.x - anchor.first * w, s.y - anchor.second * h,
                s.x + (1f - anchor.first) * w, s.y + (1f - anchor.second) * h)
        }
    }
    val projectedShapes = remember(drawings, camera) {
        drawings.mapNotNull { f ->
            if (f.effectivePoints.isEmpty()) return@mapNotNull null
            CProjShape(f.id, f.geometry, f.effectivePoints.map { proj.toScreen(it.latitude, it.longitude) })
        }
    }

    val cWps = rememberUpdatedState(projectedWaypoints)
    val cShapes = rememberUpdatedState(projectedShapes)
    val cProj = rememberUpdatedState(proj)
    val cOnWpTap = rememberUpdatedState(onWaypointTap)
    val cOnWpMoved = rememberUpdatedState(onWaypointMoved)
    val cOnDrTap = rememberUpdatedState(onDrawingTap)
    val cOnDrMoved = rememberUpdatedState(onDrawingMoved)
    val cOnEmpty = rememberUpdatedState(onEmptyTap)
    val cOnDrag = rememberUpdatedState(onDragStateChange)
    val cCamera = rememberUpdatedState(camera)
    val cMinZoom = rememberUpdatedState(minZoom)
    val cMaxZoom = rememberUpdatedState(maxZoom)
    val cOnCameraChange = rememberUpdatedState(onCameraChange)
    val cOnMapGestureStart = rememberUpdatedState(onMapGestureStart)
    val cLocked = rememberUpdatedState(locked)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    val wpHit = if (!cLocked.value) {
                        hitWaypoints(start, cWps.value, hitExpandPx)
                    } else {
                        null
                    }
                    val shapeHit = if (wpHit == null && !cLocked.value) {
                        hitShapes(start, cShapes.value, drawingTolerancePx)
                    } else {
                        null
                    }
                    val itemKind = when {
                        wpHit != null -> MapItemDrag.Kind.WAYPOINT
                        shapeHit != null -> MapItemDrag.Kind.DRAWING
                        else -> null
                    }
                    val itemId = wpHit?.ref?.id ?: shapeHit
                    val arbitrator = CustomMapGestureArbitrator(startedOnItem = itemId != null)

                    var gestureCamera = cCamera.value
                    var lastDelta = Offset.Zero
                    var mapGestureStarted = false
                    var cancelled = false

                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) {
                            cancelled = true
                            break
                        }

                        event.changes.firstOrNull { it.id == down.id }?.let { primary ->
                            lastDelta = primary.position - start
                        }
                        val activePointerCount = maxOf(
                            event.changes.count { it.pressed },
                            event.changes.count { it.previousPressed }
                        )
                        val movedBeyondSlop = hypot(
                            lastDelta.x.toDouble(),
                            lastDelta.y.toDouble()
                        ) > tapSlopPx
                        val mode = arbitrator.update(
                            pointerCount = activePointerCount,
                            movedBeyondSlop = movedBeyondSlop
                        )

                        when (mode) {
                            CustomMapGestureMode.ITEM_DRAG -> {
                                if (itemKind != null && itemId != null) {
                                    cOnDrag.value(
                                        MapItemDrag(
                                            kind = itemKind,
                                            itemId = itemId,
                                            startX = start.x,
                                            startY = start.y,
                                            offsetX = lastDelta.x,
                                            offsetY = lastDelta.y,
                                            didDrag = true
                                        )
                                    )
                                }
                                event.changes.forEach { change ->
                                    if (change.position != change.previousPosition) change.consume()
                                }
                            }

                            CustomMapGestureMode.MAP_TRANSFORM -> {
                                cOnDrag.value(null)
                                if (event.changes.any { it.pressed }) {
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if (centroid.x.isFinite() && centroid.y.isFinite()) {
                                        val next = applyCustomMapTransform(
                                            camera = gestureCamera,
                                            centroidPx = centroid,
                                            panPx = event.calculatePan(),
                                            zoomChange = event.calculateZoom(),
                                            rotationDegrees = event.calculateRotation(),
                                            density = density,
                                            minZoom = cMinZoom.value,
                                            maxZoom = cMaxZoom.value
                                        )
                                        if (next != gestureCamera) {
                                            if (!mapGestureStarted) {
                                                mapGestureStarted = true
                                                cOnMapGestureStart.value()
                                            }
                                            gestureCamera = next
                                            cOnCameraChange.value(next)
                                        }
                                    }
                                }
                                event.changes.forEach { change ->
                                    if (change.position != change.previousPosition) change.consume()
                                }
                            }

                            CustomMapGestureMode.ITEM_PENDING,
                            CustomMapGestureMode.MAP_PENDING -> Unit
                        }
                    } while (event.changes.any { it.pressed })

                    cOnDrag.value(null)
                    if (!cancelled) {
                        when (arbitrator.mode) {
                            CustomMapGestureMode.ITEM_PENDING -> when (itemKind) {
                                MapItemDrag.Kind.WAYPOINT ->
                                    cWps.value.firstOrNull { it.ref.id == itemId }
                                        ?.ref
                                        ?.let { cOnWpTap.value(it) }
                                MapItemDrag.Kind.DRAWING -> itemId?.let { cOnDrTap.value(it) }
                                null -> Unit
                            }

                            CustomMapGestureMode.ITEM_DRAG -> {
                                val p = cProj.value
                                when (itemKind) {
                                    MapItemDrag.Kind.WAYPOINT -> {
                                        val wp = cWps.value.firstOrNull { it.ref.id == itemId }
                                        if (wp != null) {
                                            val (lat, lng) = p.fromScreen(
                                                wp.screenX + lastDelta.x,
                                                wp.screenY + lastDelta.y
                                            )
                                            cOnWpMoved.value(wp.ref, lat, lng)
                                        }
                                    }
                                    MapItemDrag.Kind.DRAWING -> if (itemId != null) {
                                        val (beforeLat, beforeLng) = p.fromScreen(start.x, start.y)
                                        val (afterLat, afterLng) = p.fromScreen(
                                            start.x + lastDelta.x,
                                            start.y + lastDelta.y
                                        )
                                        cOnDrMoved.value(
                                            itemId,
                                            afterLat - beforeLat,
                                            afterLng - beforeLng
                                        )
                                    }
                                    null -> Unit
                                }
                            }

                            CustomMapGestureMode.MAP_PENDING -> cOnEmpty.value()
                            CustomMapGestureMode.MAP_TRANSFORM -> Unit
                        }
                    }
                }
            }
    )
}

internal enum class CustomMapGestureMode {
    ITEM_PENDING,
    ITEM_DRAG,
    MAP_PENDING,
    MAP_TRANSFORM
}

/**
 * Resolves one pointer stream exactly once. An item owns a one-finger
 * tap/drag, but a second pointer always promotes the same stream to a map
 * transform. Keeping both behaviours in one full-screen pointer handler means
 * the map never has to recover an ACTION_DOWN already consumed by a sibling.
 */
internal class CustomMapGestureArbitrator(startedOnItem: Boolean) {
    var mode: CustomMapGestureMode = if (startedOnItem) {
        CustomMapGestureMode.ITEM_PENDING
    } else {
        CustomMapGestureMode.MAP_PENDING
    }
        private set

    fun update(pointerCount: Int, movedBeyondSlop: Boolean): CustomMapGestureMode {
        if (pointerCount >= 2) {
            mode = CustomMapGestureMode.MAP_TRANSFORM
            return mode
        }
        mode = when (mode) {
            CustomMapGestureMode.ITEM_PENDING ->
                if (movedBeyondSlop) CustomMapGestureMode.ITEM_DRAG else mode
            CustomMapGestureMode.MAP_PENDING ->
                if (movedBeyondSlop) CustomMapGestureMode.MAP_TRANSFORM else mode
            CustomMapGestureMode.ITEM_DRAG,
            CustomMapGestureMode.MAP_TRANSFORM -> mode
        }
        return mode
    }
}

/** Apply the incremental pan/zoom/rotation reported by a Compose pointer event. */
internal fun applyCustomMapTransform(
    camera: MapCamera,
    centroidPx: Offset,
    panPx: Offset,
    zoomChange: Float,
    rotationDegrees: Float,
    density: Float,
    minZoom: Double,
    maxZoom: Double
): MapCamera {
    if (density <= 0f || !density.isFinite()) return camera
    var next = camera
    val centreX = next.viewportWidth / 2
    val centreY = next.viewportHeight / 2
    val panX = panPx.x / density.toDouble()
    val panY = panPx.y / density.toDouble()
    val focalX = centroidPx.x / density.toDouble()
    val focalY = centroidPx.y / density.toDouble()

    val (panLat, panLon) = next.coordinate(centreX - panX, centreY - panY)
    next = next.copy(centerLat = panLat, centerLon = panLon)

    if (zoomChange.isFinite() && zoomChange > 0f && zoomChange != 1f) {
        val (anchorLat, anchorLon) = next.coordinate(focalX, focalY)
        val zoom = (next.zoom + log2(zoomChange.toDouble())).coerceIn(minZoom, maxZoom)
        next = next.copy(zoom = zoom)
        val landed = next.screenPoint(anchorLat, anchorLon)
        val (lat, lon) = next.coordinate(
            centreX + (landed.x - focalX),
            centreY + (landed.y - focalY)
        )
        next = next.copy(centerLat = lat, centerLon = lon)
    }

    if (rotationDegrees.isFinite() && rotationDegrees != 0f) {
        var heading = (next.headingDegrees - rotationDegrees) % 360
        if (heading < 0) heading += 360
        next = next.copy(headingDegrees = heading)
    }
    return next
}

private data class CProjWaypoint(
    val ref: Waypoint, val screenX: Float, val screenY: Float,
    val left: Float, val top: Float, val right: Float, val bottom: Float
)

private fun hitWaypoints(point: Offset, wps: List<CProjWaypoint>, expand: Float): CProjWaypoint? {
    for (i in wps.indices.reversed()) {
        val w = wps[i]
        if (point.x in (w.left - expand)..(w.right + expand) &&
            point.y in (w.top - expand)..(w.bottom + expand)) return w
    }
    return null
}

private data class CProjShape(val id: String, val geometry: DrawingGeometry, val screenPoints: List<Offset>)

private fun hitShapes(point: Offset, shapes: List<CProjShape>, tol: Float): String? {
    for (i in shapes.indices.reversed()) {
        val s = shapes[i]
        val pts = s.screenPoints
        if (pts.isEmpty()) continue
        val hit = when (s.geometry) {
            DrawingGeometry.POINT -> hypot(point.x - pts.first().x, point.y - pts.first().y) <= tol + 12f
            DrawingGeometry.LINE ->
                if (pts.size < 2) hypot(point.x - pts.first().x, point.y - pts.first().y) <= tol
                else pointToPolylineDistance(point, pts) <= tol
            DrawingGeometry.POLYGON ->
                if (pts.size < 3) pointToPolylineDistance(point, pts) <= tol
                else pointInPolygon(point, pts) || pointToPolylineDistance(point, pts + pts.first()) <= tol
        }
        if (hit) return s.id
    }
    return null
}

/// SDK-free copy of VertexHandleBox (orange vertex dot / '+' midpoint), so it
/// survives deleting MapOverlays.kt with the SDK.
@Composable
private fun CustomVertexHandleBox(
    centerX: Int, centerY: Int, sizePx: Int, isMidpoint: Boolean,
    onTap: () -> Unit, onLongPress: () -> Unit, onDragCommit: (dxPx: Float, dyPx: Float) -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val cOnTap = rememberUpdatedState(onTap)
    val cOnLong = rememberUpdatedState(onLongPress)
    val cOnCommit = rememberUpdatedState(onDragCommit)
    Box(
        modifier = Modifier
            .offset { IntOffset(centerX - sizePx / 2 + dragOffset.x.roundToInt(),
                                centerY - sizePx / 2 + dragOffset.y.roundToInt()) }
            .size(with(LocalDensity.current) { sizePx.toDp() })
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { val dx = dragOffset.x; val dy = dragOffset.y; dragOffset = Offset.Zero; cOnCommit.value(dx, dy) },
                    onDragCancel = { dragOffset = Offset.Zero }
                ) { change, drag -> change.consume(); dragOffset += drag }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { cOnTap.value() }, onLongPress = { cOnLong.value() })
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val orange = Color(0xFFFFA63D); val white = Color.White
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = (26.dp.toPx() / 2f) - 2.dp.toPx()
            if (isMidpoint) {
                drawCircle(white.copy(alpha = 0.86f), r, c)
                drawCircle(orange, r, c, style = Stroke(width = 2.dp.toPx()))
                val arm = 6.dp.toPx()
                drawLine(orange, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 2.5.dp.toPx())
                drawLine(orange, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 2.5.dp.toPx())
            } else {
                drawCircle(orange, r, c)
                drawCircle(white, r, c, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/// Tap + free-draw capture for drawing / calibration input on the SDK-free
/// renderer. Replaces the SDK's onMapClick + the free-draw pointerInput.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MapInputOverlay(
    camera: MapCamera,
    density: Float,
    drawingInputEnabled: Boolean,
    calibrationInputEnabled: Boolean,
    freeDrawActive: Boolean,
    onDrawingTap: (lat: Double, lng: Double) -> Unit,
    onCalibrationTap: (lat: Double, lng: Double) -> Unit,
    onFreeDrawPoint: (lat: Double, lng: Double) -> Unit,
    onFreeDrawEnd: () -> Unit
) {
    if (!drawingInputEnabled && !calibrationInputEnabled && !freeDrawActive) return
    val proj = remember(camera, density) { MapProjection(camera, density) }
    val cProj = rememberUpdatedState(proj)
    val cOnDraw = rememberUpdatedState(onDrawingTap)
    val cOnCal = rememberUpdatedState(onCalibrationTap)
    val cOnFree = rememberUpdatedState(onFreeDrawPoint)
    val cOnFreeEnd = rememberUpdatedState(onFreeDrawEnd)

    if (freeDrawActive) {
        var lastLat = remember { doubleArrayOf(Double.NaN) }
        var lastLng = remember { doubleArrayOf(Double.NaN) }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                lastLat[0] = Double.NaN; lastLng[0] = Double.NaN
                do {
                    val ev = awaitPointerEvent()
                    ev.changes.forEach { ch ->
                        if (ch.pressed) {
                            ch.consume()
                            val (lat, lng) = cProj.value.fromScreen(ch.position.x, ch.position.y)
                            val dLat = lat - lastLat[0]; val dLng = lng - lastLng[0]
                            if (lastLat[0].isNaN() || dLat * dLat + dLng * dLng > 2e-9) {
                                lastLat[0] = lat; lastLng[0] = lng
                                cOnFree.value(lat, lng)
                            }
                        }
                    }
                } while (ev.changes.any { it.pressed })
                cOnFreeEnd.value()
            }
        })
    } else {
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { pos ->
                val (lat, lng) = cProj.value.fromScreen(pos.x, pos.y)
                if (drawingInputEnabled) cOnDraw.value(lat, lng)
                else if (calibrationInputEnabled) cOnCal.value(lat, lng)
            }
        })
    }
}
