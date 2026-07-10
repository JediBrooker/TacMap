package com.tacmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
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
import kotlin.math.roundToInt

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

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    val g = remember { CTouchState() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val pos = Offset(event.x, event.y)
                        val wpHit = if (!locked) hitWaypoints(pos, cWps.value, hitExpandPx) else null
                        val shapeHit = if (wpHit == null && !locked)
                            hitShapes(pos, cShapes.value, drawingTolerancePx) else null
                        g.startX = event.x; g.startY = event.y
                        g.committed = false; g.lastDx = 0f; g.lastDy = 0f; g.tracking = true
                        when {
                            wpHit != null -> { g.kind = MapItemDrag.Kind.WAYPOINT; g.itemId = wpHit.ref.id }
                            shapeHit != null -> { g.kind = MapItemDrag.Kind.DRAWING; g.itemId = shapeHit }
                            else -> { g.kind = null; g.itemId = null }
                        }
                        g.itemId != null
                    }
                    android.view.MotionEvent.ACTION_POINTER_DOWN -> true
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!g.tracking) return@pointerInteropFilter false
                        val itemId = g.itemId ?: return@pointerInteropFilter false
                        val dx = event.x - g.startX
                        val dy = event.y - g.startY
                        if (g.committed) {
                            g.lastDx = dx; g.lastDy = dy
                            cOnDrag.value(MapItemDrag(g.kind!!, itemId, g.startX, g.startY, dx, dy, true))
                            return@pointerInteropFilter true
                        }
                        if (!locked && hypot(dx, dy) > tapSlopPx && event.pointerCount == 1) {
                            g.committed = true; g.lastDx = dx; g.lastDy = dy
                            cOnDrag.value(MapItemDrag(g.kind!!, itemId, g.startX, g.startY, dx, dy, true))
                            return@pointerInteropFilter true
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!g.tracking) { g.reset(); return@pointerInteropFilter false }
                        val itemId = g.itemId; val kind = g.kind; val committed = g.committed
                        val dx = event.x - g.startX; val dy = event.y - g.startY
                        val lastDx = g.lastDx; val lastDy = g.lastDy
                        val startX = g.startX; val startY = g.startY
                        g.reset(); cOnDrag.value(null)
                        if (committed && itemId != null && kind != null) {
                            val p = cProj.value
                            when (kind) {
                                MapItemDrag.Kind.WAYPOINT -> {
                                    val wp = cWps.value.firstOrNull { it.ref.id == itemId }
                                    if (wp != null) {
                                        val (lat, lng) = p.fromScreen(wp.screenX + lastDx, wp.screenY + lastDy)
                                        cOnWpMoved.value(wp.ref, lat, lng)
                                    }
                                }
                                MapItemDrag.Kind.DRAWING -> {
                                    val (blat, blng) = p.fromScreen(startX, startY)
                                    val (alat, alng) = p.fromScreen(startX + lastDx, startY + lastDy)
                                    cOnDrMoved.value(itemId, alat - blat, alng - blng)
                                }
                            }
                            return@pointerInteropFilter true
                        }
                        if (hypot(dx, dy) < tapSlopPx) {
                            when {
                                itemId != null && kind == MapItemDrag.Kind.WAYPOINT -> {
                                    cWps.value.firstOrNull { it.ref.id == itemId }?.ref?.let { cOnWpTap.value(it) }
                                }
                                itemId != null && kind == MapItemDrag.Kind.DRAWING -> cOnDrTap.value(itemId)
                                else -> cOnEmpty.value()
                            }
                            return@pointerInteropFilter true
                        }
                        false
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> { g.reset(); cOnDrag.value(null); false }
                    else -> false
                }
            }
    )
}

private class CTouchState {
    var kind: MapItemDrag.Kind? = null
    var itemId: String? = null
    var startX = 0f; var startY = 0f
    var committed = false; var lastDx = 0f; var lastDy = 0f; var tracking = false
    fun reset() { kind = null; itemId = null; startX = 0f; startY = 0f; committed = false; lastDx = 0f; lastDy = 0f; tracking = false }
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
