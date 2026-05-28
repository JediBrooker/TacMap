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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
    val currentOnMapTapForPan = rememberUpdatedState(onMapTap)
    val currentOnBearingChanged = rememberUpdatedState(onBearingChanged)
    LaunchedEffect(cameraPositionState) {
        snapshotFlow { cameraPositionState.isMoving }
            .drop(1)
            .distinctUntilChanged()
            .collect { isMoving ->
                val byUser = cameraPositionState.cameraMoveStartedReason ==
                    CameraMoveStartedReason.GESTURE
                if (isMoving) {
                    /// Starting a user gesture (pan / zoom / rotate)
                    /// dismisses any selection card. The platform SDK's
                    /// onMapClick doesn't always fire for taps the user
                    /// perceives as empty space, so we rely on the more
                    /// reliable "user interacted with the camera" signal
                    /// to clear selection state.
                    if (byUser) currentOnMapTapForPan.value()
                } else {
                    val pos = cameraPositionState.position
                    currentOnCameraIdle.value(pos.target.latitude, pos.target.longitude, byUser)
                }
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

    Box(modifier = modifier) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(mapType = MapType.SATELLITE),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
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
                    else -> currentOnMapTap.value()
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
                DrawingShape(
                    feature = feature,
                    isDraft = false,
                    selected = feature.id == selectedDrawingId,
                    inputEnabled = drawingInputEnabled,
                    onTap = { currentOnDrawingFeatureTap.value(feature.id) }
                )
            }
            draftDrawing?.let { draft ->
                DrawingShape(
                    feature = draft,
                    isDraft = true,
                    selected = false,
                    inputEnabled = drawingInputEnabled,
                    onTap = null
                )
            }

            visibleWaypoints.forEach { wp ->
                WaypointMarker(
                    waypoint = wp,
                    selected = wp.id == selectedWaypointId,
                    onTap = { currentOnMarkerTap.value(wp) },
                    onMoved = { lat, lng -> currentOnWaypointMoved.value(wp, lat, lng) }
                )
            }

            /// PDF calibration fiduciaries — render small numbered
            /// pins for each tapped reference point so the user can
            /// see which corners of the PDF they've registered.
            calibrationFiduciaries.forEachIndexed { i, fid ->
                CalibrationFiduciaryMarker(index = i + 1, fid = fid)
            }
        }

        /// Waypoint name labels (units / tasks) — Compose Text
        /// overlays projected to screen coords each frame. Units +
        /// generic get a pill below the icon; tasks get the pill
        /// centred inside the graphic.
        WaypointLabelsOverlay(
            waypoints = visibleWaypoints,
            cameraPositionState = cameraPositionState,
            unitLabelsVisible = unitLabelsVisible,
            taskLabelsVisible = taskLabelsVisible
        )

        /// Drawing name labels — anchored at the centroid for polygons,
        /// midpoint for lines, the point itself for points.
        if (drawingLabelsVisible) {
            DrawingLabelsOverlay(
                drawings = visibleDrawings,
                cameraPositionState = cameraPositionState
            )
        }

        /// MGRS grid labels — drawn as Compose Text on top of the map
        /// so they can be rotated for vertical lines without baking
        /// per-zoom bitmap markers.
        if (mgrsGridVisible) {
            MgrsGridLabelsOverlay(cameraPositionState = cameraPositionState)
        }

        /// Vertex-edit handles live as Compose overlays on top of the map
        /// instead of as Marker composables — maps-compose Markers wrap
        /// the platform SDK's marker drag (which requires a long-press to
        /// initiate) and their onClick wiring is unreliable when nested
        /// in a forEach. Compose pointerInput gives us immediate drag and
        /// reliable tap detection.
        VertexHandlesOverlay(
            feature = selectedDrawing.takeUnless { drawingInputEnabled },
            cameraPositionState = cameraPositionState,
            onVertexMoved = currentOnVertexMoved.value,
            onVertexInserted = currentOnVertexInserted.value,
            onVertexDeleted = currentOnVertexDeleted.value
        )

        /// Translate handle — drags the whole shape (all vertices)
        /// by the same lat/lng delta. Renders at the shape's
        /// labelAnchor (centroid for polygons, mid-segment for
        /// lines) so the user has a stable grab-point that follows
        /// the shape as it moves.
        TranslateHandleOverlay(
            feature = selectedDrawing.takeUnless { drawingInputEnabled },
            cameraPositionState = cameraPositionState,
            onShapeMoved = currentOnShapeMoved.value
        )
    }
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

/// A single grab-handle at the selected drawing's labelAnchor that
/// translates ALL of the shape's vertices by the same lat/lng delta.
/// Mirrors the old osmdroid "long-press a polyline to drag the whole
/// shape" affordance but in a more discoverable place — the user can
/// see and aim for the handle rather than guessing.
@Composable
private fun TranslateHandleOverlay(
    feature: DrawingFeature?,
    cameraPositionState: CameraPositionState,
    onShapeMoved: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit
) {
    if (feature == null) return
    val anchor = feature.labelAnchor ?: return
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val density = LocalDensity.current
    val sizePx = with(density) { 52.dp.roundToPx() }
    val screen = projection.toScreenLocation(LatLng(anchor.latitude, anchor.longitude))

    val currentOnShapeMoved = rememberUpdatedState(onShapeMoved)
    var dragOffset by remember(feature.id) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    screen.x - sizePx / 2 + dragOffset.x.roundToInt(),
                    screen.y - sizePx / 2 + dragOffset.y.roundToInt()
                )
            }
            .size(with(density) { sizePx.toDp() })
            .pointerInput(feature.id) {
                detectDragGestures(
                    onDragEnd = {
                        val dx = dragOffset.x
                        val dy = dragOffset.y
                        dragOffset = Offset.Zero
                        val proj = cameraPositionState.projection ?: return@detectDragGestures
                        val before = proj.fromScreenLocation(Point(screen.x, screen.y))
                        val after = proj.fromScreenLocation(
                            Point((screen.x + dx).roundToInt(), (screen.y + dy).roundToInt())
                        )
                        currentOnShapeMoved.value(
                            feature.id,
                            after.latitude - before.latitude,
                            after.longitude - before.longitude
                        )
                    },
                    onDragCancel = { dragOffset = Offset.Zero }
                ) { change, drag ->
                    change.consume()
                    dragOffset += drag
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val orange = Color(0xFFFFA63D)
            val white = Color.White
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = (size.width / 2f) - 3.dp.toPx()
            /// Solid orange disc with a white "+" four-way arrow icon
            /// so the affordance reads "drag to move".
            drawCircle(orange, r, center)
            drawCircle(white, r, center, style = Stroke(width = 2.5.dp.toPx()))
            val arm = r * 0.55f
            val headLen = 3.dp.toPx()
            val stroke = 2.5.dp.toPx()
            // Vertical arrow shaft
            drawLine(
                white,
                Offset(center.x, center.y - arm),
                Offset(center.x, center.y + arm),
                strokeWidth = stroke
            )
            // Horizontal arrow shaft
            drawLine(
                white,
                Offset(center.x - arm, center.y),
                Offset(center.x + arm, center.y),
                strokeWidth = stroke
            )
            // Four arrowheads — tiny V's at each end
            drawLine(
                white,
                Offset(center.x, center.y - arm),
                Offset(center.x - headLen, center.y - arm + headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x, center.y - arm),
                Offset(center.x + headLen, center.y - arm + headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x, center.y + arm),
                Offset(center.x - headLen, center.y + arm - headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x, center.y + arm),
                Offset(center.x + headLen, center.y + arm - headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x - arm, center.y),
                Offset(center.x - arm + headLen, center.y - headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x - arm, center.y),
                Offset(center.x - arm + headLen, center.y + headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x + arm, center.y),
                Offset(center.x + arm - headLen, center.y - headLen),
                strokeWidth = stroke
            )
            drawLine(
                white,
                Offset(center.x + arm, center.y),
                Offset(center.x + arm - headLen, center.y + headLen),
                strokeWidth = stroke
            )
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
    onTap: (() -> Unit)?
) {
    val effective = remember(
        feature.points,
        feature.scaleX,
        feature.scaleY,
        feature.rotationDegrees,
        feature.geometry
    ) {
        feature.effectivePoints.map { LatLng(it.latitude, it.longitude) }
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
    val width = if (selected) baseWidth + 6f else baseWidth
    val pattern: List<PatternItem>? = if (feature.strokeStyle == DrawingStrokeStyle.DASHED) {
        listOf(Dash(width * 3f), Gap(width * 2f))
    } else null

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
    val density = LocalDensity.current

    labels.forEach { mark ->
        val screen = projection.toScreenLocation(LatLng(mark.lat, mark.lng))
        val sp = MgrsGridRenderer.labelTextSp(mark.type)
        /// Approximate text bounds (a few sp wider/taller than the
        /// glyphs themselves) so we can centre the label on its
        /// geographic anchor regardless of glyph length.
        val labelWidthPx = (mark.text.length * sp * 0.62f * density.density).toInt().coerceAtLeast(8)
        val labelHeightPx = (sp * 1.25f * density.density).toInt().coerceAtLeast(8)
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        screen.x - labelWidthPx / 2,
                        screen.y - labelHeightPx / 2
                    )
                }
                .size(
                    width = with(density) { labelWidthPx.toDp() },
                    height = with(density) { labelHeightPx.toDp() }
                ),
            contentAlignment = Alignment.Center
        ) {
            val rotation = if (mark.isVertical) -90f else 0f
            /// Soft white halo via four offset passes — keeps the
            /// dark digits readable on busy satellite tiles without
            /// a visible pill.
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

@Composable
private fun PdfGroundOverlay(source: PdfMapSource) {
    val bounds = source.coverage ?: return
    val context = LocalContext.current

    /// Loads the first page of the PDF off the main thread once per URI
    /// and exposes it as a BitmapDescriptor. A high-res viewport pass
    /// that re-renders on pan / zoom (via a custom TileProvider) is a
    /// future enhancement — the single-page bitmap is sufficient for
    /// typical zoom ranges.
    var image by remember(source.uri) { mutableStateOf<BitmapDescriptor?>(null) }
    LaunchedEffect(source.uri) {
        val rendered = runCatching {
            withContext(Dispatchers.IO) {
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

@Composable
private fun WaypointMarker(
    waypoint: Waypoint,
    selected: Boolean,
    onTap: () -> Unit,
    onMoved: (lat: Double, lng: Double) -> Unit
) {
    val context = LocalContext.current
    val markerState = rememberMarkerState(
        position = LatLng(waypoint.latitude, waypoint.longitude)
    )

    LaunchedEffect(waypoint.latitude, waypoint.longitude) {
        val next = LatLng(waypoint.latitude, waypoint.longitude)
        if (markerState.position != next) markerState.position = next
    }

    val currentOnMoved = rememberUpdatedState(onMoved)
    LaunchedEffect(markerState) {
        /// Skip the initial `END` value — `snapshotFlow` emits the
        /// current state on subscribe, which would fire onMoved with
        /// whatever transient position the marker has during attach
        /// / rotation / recomposition. Only report END after we've
        /// actually seen a START or DRAG.
        var seenActiveDrag = false
        snapshotFlow { markerState.dragState }
            .collect { state ->
                when (state) {
                    DragState.START, DragState.DRAG -> seenActiveDrag = true
                    DragState.END -> if (seenActiveDrag) {
                        seenActiveDrag = false
                        val p = markerState.position
                        currentOnMoved.value(p.latitude, p.longitude)
                    }
                }
            }
    }

    val rawIcon = remember(
        waypoint.kind,
        waypoint.rotation,
        waypoint.scaleX,
        waypoint.scaleY
    ) {
        SymbolIconFactory.drawableFor(context, waypoint)
    }
    val rawAnchor = remember(waypoint.kind) {
        SymbolIconFactory.anchorFor(context, waypoint)
    }

    /// When selected, composite an orange halo behind the icon and
    /// shift the anchor so the marker still pins to the same
    /// geographic position. The icon bitmap grows by ~36dp on every
    /// side to make room for the bloom.
    val (descriptor, anchor) = remember(rawIcon, rawAnchor, selected) {
        if (selected) {
            val (glowed, ga) = applySelectionGlow(context, rawIcon, rawAnchor)
            glowed.toBitmapDescriptor() to Offset(ga.first, ga.second)
        } else {
            rawIcon.toBitmapDescriptor() to Offset(rawAnchor.first, rawAnchor.second)
        }
    }

    val currentOnTap = rememberUpdatedState(onTap)
    Marker(
        state = markerState,
        icon = descriptor,
        anchor = anchor,
        title = waypoint.name,
        draggable = true,
        onClick = {
            currentOnTap.value()
            true
        }
    )
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
/// the symbol bubble; units / generic waypoints sit the label below.
@Composable
private fun WaypointLabelsOverlay(
    waypoints: List<Waypoint>,
    cameraPositionState: CameraPositionState,
    unitLabelsVisible: Boolean,
    taskLabelsVisible: Boolean
) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val density = LocalDensity.current

    waypoints.forEach { wp ->
        val trimmed = wp.name.trim()
        if (trimmed.isEmpty()) return@forEach
        val isTask = wp.kind is com.tacticalmaps.waypoints.WaypointKind.ControlMeasure
        val visible = if (isTask) taskLabelsVisible else unitLabelsVisible
        if (!visible) return@forEach

        val screen = projection.toScreenLocation(LatLng(wp.latitude, wp.longitude))
        /// Approximate label size — kept in step with the label
        /// composable's actual rendering so the centred offset puts
        /// the pill where we want it.
        val sp = 11f
        val labelWidthPx = with(density) {
            (trimmed.length * sp * 0.65f * density.density + 12.dp.toPx()).toInt().coerceAtLeast(40)
        }
        val labelHeightPx = with(density) {
            (sp * 1.45f * density.density + 6.dp.toPx()).toInt().coerceAtLeast(20)
        }
        /// Tasks: centred on the icon. Units / generic: below the
        /// icon by ~22dp so the label clears the symbol footprint.
        val yOffset = if (isTask) 0 else with(density) { 22.dp.toPx() }.toInt()

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        screen.x - labelWidthPx / 2,
                        screen.y - labelHeightPx / 2 + yOffset
                    )
                }
                .size(
                    width = with(density) { labelWidthPx.toDp() },
                    height = with(density) { labelHeightPx.toDp() }
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = trimmed,
                    color = Color.White,
                    fontSize = sp.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    modifier = Modifier
                        .background(
                            Color.Black.copy(alpha = 0.62f),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

/// Drawing name labels — one per named drawing, anchored at the
/// shape's labelAnchor (centroid / mid-segment / point). Non-
/// interactive; the underlying drawing handles taps.
@Composable
private fun DrawingLabelsOverlay(
    drawings: List<DrawingFeature>,
    cameraPositionState: CameraPositionState
) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val density = LocalDensity.current

    drawings.forEach { feature ->
        val trimmed = feature.name.trim()
        if (trimmed.isEmpty()) return@forEach
        val anchor = feature.labelAnchor ?: return@forEach

        val screen = projection.toScreenLocation(LatLng(anchor.latitude, anchor.longitude))
        val sp = 11f
        val labelWidthPx = with(density) {
            (trimmed.length * sp * 0.65f * density.density + 12.dp.toPx()).toInt().coerceAtLeast(40)
        }
        val labelHeightPx = with(density) {
            (sp * 1.45f * density.density + 6.dp.toPx()).toInt().coerceAtLeast(20)
        }

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        screen.x - labelWidthPx / 2,
                        screen.y - labelHeightPx / 2
                    )
                }
                .size(
                    width = with(density) { labelWidthPx.toDp() },
                    height = with(density) { labelHeightPx.toDp() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = trimmed,
                color = Color.White,
                fontSize = sp.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                modifier = Modifier
                    .background(
                        Color.Black.copy(alpha = 0.62f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
    }
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
