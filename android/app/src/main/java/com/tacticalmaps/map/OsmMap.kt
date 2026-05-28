package com.tacticalmaps.map

import android.content.Context
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tacticalmaps.calibration.MapSource
import com.tacticalmaps.calibration.PdfMapSource
import com.tacticalmaps.calibration.PdfPageRenderer
import com.tacticalmaps.drawings.DrawingDocument
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingPoint
import com.tacticalmaps.drawings.DrawingStrokeStyle
import com.tacticalmaps.waypoints.Waypoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay

/**
 * Compose wrapper around osmdroid's `MapView`. OpenStreetMap tiles
 * (free, no API key). Tile cache lives under the app's external files
 * dir; user-agent is set to the app's package to satisfy OSM's tile
 * server policy.
 *
 * Camera state is owned by the caller via [pendingTarget] and
 * [onCameraIdle]. Pan/zoom gestures fire the callback with the new
 * centre; programmatic camera moves are pushed when [pendingTarget]
 * is non-null.
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    waypoints: List<Waypoint>,
    mapSource: MapSource,
    drawings: List<DrawingFeature>,
    drawingLayers: List<DrawingLayer>,
    draftDrawing: DrawingFeature?,
    drawingInputEnabled: Boolean,
    unitLabelsVisible: Boolean = true,
    taskLabelsVisible: Boolean = true,
    drawingLabelsVisible: Boolean = true,
    mgrsGridVisible: Boolean = false,
    selectedDrawingId: String? = null,
    selectedWaypointId: String? = null,
    calibrationInputEnabled: Boolean,
    pendingTarget: Triple<Double, Double, Float>?,         // lat, lng, zoom
    onConsumePendingTarget: () -> Unit,
    onCameraIdle: (lat: Double, lng: Double, byUser: Boolean) -> Unit,
    onBearingChanged: (Double) -> Unit,
    onMarkerTap: (Waypoint) -> Unit,
    onWaypointMoved: (waypoint: Waypoint, lat: Double, lng: Double) -> Unit,
    onDrawingTap: (lat: Double, lng: Double) -> Unit,
    onCalibrationTap: (lat: Double, lng: Double) -> Unit,
    onDrawingFeatureTap: (String) -> Unit,
    onDrawingMove: (featureId: String, deltaLat: Double, deltaLng: Double) -> Unit,
    onVertexMoved: (featureId: String, vertexIndex: Int, lat: Double, lng: Double) -> Unit = { _, _, _, _ -> },
    onVertexInserted: (featureId: String, atIndex: Int, lat: Double, lng: Double) -> Unit = { _, _, _, _ -> },
    onVertexDeleted: (featureId: String, vertexIndex: Int) -> Unit = { _, _ -> },
    onMapTap: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnMarkerTap = rememberUpdatedState(onMarkerTap)
    val currentOnMapTap = rememberUpdatedState(onMapTap)
    val currentOnBearingChanged = rememberUpdatedState(onBearingChanged)
    val currentDrawingInputEnabled = rememberUpdatedState(drawingInputEnabled)
    val currentCalibrationInputEnabled = rememberUpdatedState(calibrationInputEnabled)
    val currentOnDrawingTap = rememberUpdatedState(onDrawingTap)
    val currentOnCalibrationTap = rememberUpdatedState(onCalibrationTap)
    val currentOnDrawingFeatureTap = rememberUpdatedState(onDrawingFeatureTap)
    val currentOnDrawingMove = rememberUpdatedState(onDrawingMove)
    val currentOnVertexMoved = rememberUpdatedState(onVertexMoved)
    val currentOnVertexInserted = rememberUpdatedState(onVertexInserted)
    val currentOnVertexDeleted = rememberUpdatedState(onVertexDeleted)
    val currentDrawings = rememberUpdatedState(drawings)
    val currentDrawingLayers = rememberUpdatedState(drawingLayers)
    val pdfOverlays = remember { mutableListOf<Overlay>() }
    val drawingOverlays = remember { mutableListOf<Overlay>() }
    /// MGRS grid polylines. Lifecycle is independent of drawings and
    /// waypoints — they're cleared and rebuilt whenever the visible
    /// region or the toggle changes.
    val mgrsGridOverlays = remember { mutableListOf<Polyline>() }
    val currentMgrsGridVisible = rememberUpdatedState(mgrsGridVisible)
    /// Vertex-edit handle markers (rebuilt whenever the selected
    /// drawing's id or coordinates change). Tracked separately so the
    /// long-press hit-test can find them and the waypoint-rebuild step
    /// can leave them alone.
    val vertexHandleOverlays = remember { mutableListOf<Marker>() }
    /// (vertexIndex, isMidpoint, lat, lng) for each handle; consulted
    /// by the longPressHelper to decide which vertex to delete.
    val vertexHandleInfo = remember { mutableListOf<VertexHandle>() }
    val currentSelectedDrawingId = rememberUpdatedState(selectedDrawingId)

    val mapView = remember {
        // Initialise osmdroid config BEFORE constructing MapView so the
        // first tile request goes out with a sensible user agent — the
        // tile loader threads start the moment MapView is created.
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // No built-in zoom buttons — we render our own HUD.
            zoomController.setVisibility(
                org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER
            )
            controller.setZoom(2.0)
            controller.setCenter(GeoPoint(0.0, 0.0))
        }
    }

    val drawingInputOverlay = remember {
        object : Overlay() {
            override fun onSingleTapConfirmed(event: MotionEvent, mapView: MapView): Boolean {
                val point = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                return when {
                    currentDrawingInputEnabled.value -> {
                        currentOnDrawingTap.value(point.latitude, point.longitude)
                        true
                    }
                    currentCalibrationInputEnabled.value -> {
                        currentOnCalibrationTap.value(point.latitude, point.longitude)
                        true
                    }
                    else -> false
                }
            }

            override fun onDoubleTap(event: MotionEvent, mapView: MapView): Boolean {
                return currentDrawingInputEnabled.value || currentCalibrationInputEnabled.value
            }
        }
    }

    val drawingMoveOverlay = remember {
        object : Overlay() {
            private var draggingFeatureId: String? = null
            private var lastDragLat: Double? = null
            private var lastDragLng: Double? = null

            override fun onLongPress(event: MotionEvent, mapView: MapView): Boolean {
                if (currentDrawingInputEnabled.value || currentCalibrationInputEnabled.value) return false
                val feature = findDrawingFeatureAt(
                    event = event,
                    mapView = mapView,
                    drawings = currentDrawings.value,
                    layers = currentDrawingLayers.value
                ) ?: return false

                draggingFeatureId = feature.id
                val point = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                lastDragLat = point.latitude
                lastDragLng = point.longitude
                currentOnDrawingFeatureTap.value(feature.id)
                mapView.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                val featureId = draggingFeatureId ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        val nextPoint = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                        val previousLat = lastDragLat
                        val previousLng = lastDragLng
                        if (previousLat != null && previousLng != null) {
                            currentOnDrawingMove.value(
                                featureId,
                                nextPoint.latitude - previousLat,
                                nextPoint.longitude - previousLng
                            )
                        }
                        lastDragLat = nextPoint.latitude
                        lastDragLng = nextPoint.longitude
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        draggingFeatureId = null
                        lastDragLat = null
                        lastDragLng = null
                        mapView.parent?.requestDisallowInterceptTouchEvent(false)
                        return true
                    }
                }
                return true
            }
        }
    }

    fun keepDrawingInputOnTop() {
        mapView.overlays.remove(drawingMoveOverlay)
        mapView.overlays.remove(drawingInputOverlay)
        mapView.overlays.add(drawingMoveOverlay)
        mapView.overlays.add(drawingInputOverlay)
    }

    // Lifecycle-aware MapView wiring — osmdroid needs onResume/onPause
    // so its tile downloader threads start/stop with the activity.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Push programmatic camera moves into the MapView.
    LaunchedEffect(pendingTarget) {
        pendingTarget?.let { (lat, lng, zoom) ->
            mapView.controller.animateTo(GeoPoint(lat, lng), zoom.toDouble(), 600L)
            onConsumePendingTarget()
        }
    }

    LaunchedEffect(mapSource) {
        pdfOverlays.forEach { overlay ->
            mapView.overlays.remove(overlay)
            overlay.onDetach(mapView)
        }
        pdfOverlays.clear()

        val pdfSource = mapSource as? PdfMapSource
        val bounds = pdfSource?.coverage
        if (pdfSource != null && bounds != null) {
            val rendered = runCatching {
                withContext(Dispatchers.IO) {
                    PdfPageRenderer.renderFirstPage(context, pdfSource.uri)
                }
            }.getOrNull()
            if (rendered != null) {
                val overlay = PdfMapOverlay(
                    context = context,
                    uri = pdfSource.uri,
                    bounds = bounds,
                    pageInfo = rendered.info,
                    baseBitmap = rendered.bitmap
                )
                pdfOverlays.add(overlay)
                mapView.overlays.add(0, overlay)
            }
        }
        keepDrawingInputOnTop()
        mapView.invalidate()
    }

    LaunchedEffect(drawings, drawingLayers, draftDrawing, drawingLabelsVisible, selectedDrawingId) {
        drawingOverlays.forEach { mapView.overlays.remove(it) }
        drawingOverlays.clear()

        val visibleLayerIds = drawingLayers
            .ifEmpty { DrawingDocument.defaultLayers() }
            .filter { it.isVisible }
            .map { it.id }
            .toSet()
        val visibleDrawings = drawings.filter { it.layerId in visibleLayerIds }
        val nextOverlays = visibleDrawings
            .mapNotNull {
                it.toOverlay(
                    mapView = mapView,
                    isDraft = false,
                    onTap = { featureId ->
                        if (!currentDrawingInputEnabled.value) {
                            currentOnDrawingFeatureTap.value(featureId)
                        }
                    },
                    selected = it.id == selectedDrawingId
                )
            } +
            // Text-label markers for any named drawing — non-interactive,
            // positioned at the feature's labelAnchor (centroid / mid /
            // point). Skipped entirely when the user has hidden drawing
            // labels via the Layers sheet.
            (if (drawingLabelsVisible) visibleDrawings.mapNotNull { it.toLabelMarker(mapView) } else emptyList()) +
            listOfNotNull(draftDrawing?.toOverlay(mapView, isDraft = true, onTap = null, selected = false))

        drawingOverlays.addAll(nextOverlays)
        val insertIndex = pdfOverlays.size.coerceAtMost(mapView.overlays.size)
        mapView.overlays.addAll(insertIndex, nextOverlays)
        keepDrawingInputOnTop()
        mapView.invalidate()
    }

    // Vertex-edit handles for the currently selected polyline / polygon.
    // Re-fires whenever the selected drawing or its coordinates change so
    // the dots track the rendered shape as the user drags individual
    // handles or moves the whole drawing.
    LaunchedEffect(selectedDrawingId, drawings) {
        vertexHandleOverlays.forEach { mapView.overlays.remove(it) }
        vertexHandleOverlays.clear()
        vertexHandleInfo.clear()

        val selected = drawings.firstOrNull { it.id == selectedDrawingId }
            ?.takeIf { it.geometry == DrawingGeometry.LINE || it.geometry == DrawingGeometry.POLYGON }
        if (selected != null) {
            val pts = selected.effectivePoints
            val featureId = selected.id
            // Real vertex handles. We use DragMarker (custom subclass)
            // so the user can move a vertex with one fluid drag instead
            // of OSMDroid's default long-press-then-drag, which fights
            // the map pan and rarely activates on a small handle.
            // Long-press anywhere near the handle deletes it; that's
            // wired via the tap-overlay's longPressHelper below.
            pts.forEachIndexed { i, p ->
                val marker = DragMarker(
                    mapView = mapView,
                    drawable = makeVertexHandleDrawable(context, midpoint = false),
                    lat = p.latitude,
                    lng = p.longitude,
                    onDragEnded = { lat, lng ->
                        currentOnVertexMoved.value(featureId, i, lat, lng)
                    }
                )
                vertexHandleOverlays.add(marker)
                vertexHandleInfo.add(VertexHandle(index = i, isMidpoint = false,
                    lat = p.latitude, lng = p.longitude))
            }
            // Midpoint insertion handles. Polygons get a handle for the
            // closing segment as well (between last and first vertex).
            val segmentCount = if (selected.geometry == DrawingGeometry.POLYGON) pts.size else pts.size - 1
            for (i in 0 until segmentCount.coerceAtLeast(0)) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                val midLat = (a.latitude  + b.latitude ) / 2.0
                val midLng = (a.longitude + b.longitude) / 2.0
                val insertIndex = i + 1
                val marker = DragMarker(
                    mapView = mapView,
                    drawable = makeVertexHandleDrawable(context, midpoint = true),
                    lat = midLat,
                    lng = midLng,
                    onTapped = {
                        // Quick tap inserts at the midpoint location.
                        currentOnVertexInserted.value(featureId, insertIndex, midLat, midLng)
                    },
                    onDragEnded = { lat, lng ->
                        // Drag inserts at the final dragged location.
                        currentOnVertexInserted.value(featureId, insertIndex, lat, lng)
                    }
                )
                vertexHandleOverlays.add(marker)
                vertexHandleInfo.add(VertexHandle(index = insertIndex, isMidpoint = true,
                    lat = midLat, lng = midLng))
            }
            mapView.overlays.addAll(vertexHandleOverlays)
        }
        keepDrawingInputOnTop()
        mapView.invalidate()
    }

    // Rebuild marker layer whenever waypoints change. Cheap — we
    // clear+re-add, count is small.
    LaunchedEffect(waypoints, drawingLayers, unitLabelsVisible, taskLabelsVisible, selectedWaypointId) {
        // Drop existing waypoint markers. We must NOT touch markers
        // owned by the drawing-overlay set (drawing label markers are
        // also Markers and live alongside waypoint markers in
        // mapView.overlays) — nor the vertex-edit handle markers, which
        // belong to a separate effect tied to selectedDrawingId — nor
        // the MGRS grid-label markers, which are managed by their own
        // rebuild routine.
        val drawingOwned = drawingOverlays.toSet()
        val handleOwned = vertexHandleOverlays.toSet()
        val mgrsLabelOwned = mgrsLabelMarkers.toSet()
        mapView.overlays.removeAll {
            it is Marker && it !in drawingOwned && it !in handleOwned && it !in mgrsLabelOwned
        }
        val visibleLayerIds = drawingLayers
            .ifEmpty { DrawingDocument.defaultLayers() }
            .filter { it.isVisible }
            .map { it.id }
            .toSet()

        waypoints.filter { it.layerId in visibleLayerIds }.forEach { wp ->
            val rawIconUnglowed = SymbolIconFactory.drawableFor(context, wp)
            val rawAnchorUnglowed = SymbolIconFactory.anchorFor(context, wp)
            val isSelected = wp.id == selectedWaypointId
            val isTask = wp.kind is com.tacticalmaps.waypoints.WaypointKind.ControlMeasure
            val showLabelForKind = if (isTask) taskLabelsVisible else unitLabelsVisible
            val trimmedName = wp.name.trim()
            // For UNITS, composite the un-glowed icon + label into one
            // bitmap so the label sits at a fixed distance below the
            // visible glyph regardless of selection state. When
            // selected, the composite renders the glow BEHIND the
            // icon at the same fixed icon position — so the label
            // never moves. Tasks use the raw icon (labels are drawn
            // by a separate centered marker further down) and get
            // their glow via the standalone applySelectionGlow path.
            val (compositeIcon, compositeAnchor) = if (!isTask
                && showLabelForKind
                && trimmedName.isNotEmpty()
            ) {
                compositeUnitIconWithLabel(
                    context = context,
                    icon = rawIconUnglowed,
                    text = trimmedName,
                    originalAnchorV = rawAnchorUnglowed.second,
                    selected = isSelected
                )
            } else if (isSelected) {
                applySelectionGlow(context, rawIconUnglowed, rawAnchorUnglowed)
            } else {
                rawIconUnglowed.mutate() to rawAnchorUnglowed
            }

            val m = AlphaHitMarker(mapView, compositeAnchor).apply {
                position = GeoPoint(wp.latitude, wp.longitude)
                title = wp.name
                this.icon = compositeIcon
                setAnchor(compositeAnchor.first, compositeAnchor.second)
                isDraggable = true
                setOnMarkerClickListener { _, _ ->
                    if (currentDrawingInputEnabled.value) return@setOnMarkerClickListener false
                    currentOnMarkerTap.value(wp)
                    true
                }
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker?) = Unit
                    override fun onMarkerDrag(marker: Marker?) = Unit
                    override fun onMarkerDragEnd(marker: Marker?) {
                        marker?.position?.let { point ->
                            onWaypointMoved(wp, point.latitude, point.longitude)
                        }
                    }
                })
            }
            mapView.overlays.add(m)

            // Tasks still use a SEPARATE label marker centred on the
            // icon (label sits INSIDE the graphic). Units already have
            // the label baked into the composite icon above, so they
            // skip this branch.
            val showLabel = isTask && taskLabelsVisible
            if (showLabel) {
                val trimmed = wp.name.trim()
                if (trimmed.isNotEmpty()) {
                    val density = context.resources.displayMetrics.density
                    val iconWidthPx  = rawIconUnglowed.intrinsicWidth.coerceAtLeast(40)
                    val iconHeightPx = rawIconUnglowed.intrinsicHeight.coerceAtLeast(40)
                    // The waypoint bitmaps reserve a lot of empty space
                    // around the visible glyph (echelon dots above,
                    // amplifier room below, breathing room on the sides).
                    // Empirically the visible icon is ≈60% of the bitmap
                    // width, so apply 25%-over-graphic against that:
                    //   max-label = iconBitmap × 0.60 × 1.25 = ×0.75
                    val maxLabelWidth = (iconWidthPx * 0.75f)
                        .coerceAtLeast(40f * density)
                    // Tasks: label centred on the icon (inside graphic).
                    // Units / generic: zero gap with TOP anchor — places
                    // the label pill's TOP at the icon's centre, so the
                    // pill hangs inside the bottom half of the icon
                    // bitmap. The user wants the label tight against
                    // the visible glyph; the bitmap padding around the
                    // glyph is too variable across kinds to compute,
                    // so a zero offset keeps the pill within the
                    // bitmap's vertical extent.
                    val gapAboveLabelPx = 0f
                    val labelDrawable = makeUnitLabelDrawable(
                        context = context,
                        text = trimmed,
                        maxWidthPx = maxLabelWidth,
                        topGapPx = gapAboveLabelPx
                    )
                    val labelMarker = Marker(mapView).apply {
                        position = GeoPoint(wp.latitude, wp.longitude)
                        // Anchor depends on kind: tasks centre the pill
                        // on the icon, units / generic put the pill's
                        // top at the icon's centre (the bitmap's built-in
                        // gapAbove strip then nudges it under the icon).
                        if (isTask) {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        } else {
                            // DEBUG: anchor BOTTOM — bitmap bottom at marker
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        this.icon = labelDrawable
                        setOnMarkerClickListener { _, _ ->
                            if (currentDrawingInputEnabled.value) return@setOnMarkerClickListener false
                            currentOnMarkerTap.value(wp)
                            true
                        }
                        isDraggable = false
                    }
                    mapView.overlays.add(labelMarker)
                }
            }
        }
        keepDrawingInputOnTop()
        mapView.invalidate()
    }

    // Tap-empty-map overlay (so tapping non-marker dismisses the
    // selection card). Added once on first composition.
    LaunchedEffect(Unit) {
        val rotationOverlay = object : RotationGestureOverlay(mapView) {
            override fun onRotate(deltaAngle: Float) {
                super.onRotate(deltaAngle)
                currentOnBearingChanged.value(mapView.mapOrientation.toDouble())
            }
        }.apply { setEnabled(true) }
        mapView.overlays.add(rotationOverlay)

        val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (currentDrawingInputEnabled.value || currentCalibrationInputEnabled.value) return false
                currentOnMapTap.value()
                return false   // don't consume, allow other overlays to also see it
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                // Hit-test against real vertex handles for the selected
                // drawing. If the press lands within a small tolerance
                // of one, delete that vertex. Tolerance is in screen
                // pixels so the target stays consistent across zoom.
                val featureId = currentSelectedDrawingId.value ?: return false
                if (p == null) return false
                val pressScreen = mapView.projection.toPixels(p, null)
                val tolPx = (28f * context.resources.displayMetrics.density).toInt()
                var bestIndex = -1
                var bestDist = Int.MAX_VALUE
                for (h in vertexHandleInfo) {
                    if (h.isMidpoint) continue
                    val pt = mapView.projection.toPixels(GeoPoint(h.lat, h.lng), null)
                    val dx = pt.x - pressScreen.x
                    val dy = pt.y - pressScreen.y
                    val d = (kotlin.math.hypot(dx.toDouble(), dy.toDouble())).toInt()
                    if (d <= tolPx && d < bestDist) {
                        bestDist = d
                        bestIndex = h.index
                    }
                }
                if (bestIndex >= 0) {
                    currentOnVertexDeleted.value(featureId, bestIndex)
                    return true
                }
                return false
            }
        })
        mapView.overlays.add(0, tapOverlay)   // bottom of the overlay stack
        keepDrawingInputOnTop()
    }

    // Camera-idle reporting. osmdroid fires ScrollEvent on every pan
    // tick; we treat any scroll as a user gesture (it's not — pinch-
    // zoom also scrolls — but it's good enough to flip browse mode on
    // and match iOS behaviour).
    LaunchedEffect(Unit) {
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                val c = mapView.mapCenter
                onCameraIdle(c.latitude, c.longitude, /*byUser=*/true)
                currentOnBearingChanged.value(mapView.mapOrientation.toDouble())
                rebuildMgrsGridIfNeeded(
                    mapView = mapView,
                    visible = currentMgrsGridVisible.value,
                    overlays = mgrsGridOverlays,
                    context = context
                )
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                val c = mapView.mapCenter
                onCameraIdle(c.latitude, c.longitude, /*byUser=*/true)
                currentOnBearingChanged.value(mapView.mapOrientation.toDouble())
                rebuildMgrsGridIfNeeded(
                    mapView = mapView,
                    visible = currentMgrsGridVisible.value,
                    overlays = mgrsGridOverlays,
                    context = context
                )
                return false
            }
        })
    }

    // Toggle the MGRS grid when the user flips the layers-sheet switch.
    LaunchedEffect(mgrsGridVisible) {
        rebuildMgrsGridIfNeeded(
            mapView = mapView,
            visible = mgrsGridVisible,
            overlays = mgrsGridOverlays,
            context = context,
            forceRebuild = true
        )
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/// Cached fingerprint of the last region we built the MGRS grid for so
/// gentle pans inside the same cell-set don't re-tessellate every line.
private var lastMgrsFingerprint: String = ""

/// Active MGRS grid-label markers. Kept here instead of as a parameter
/// so the rebuild helper has a single source of truth for what to strip
/// when the toggle flips or the region changes.
private val mgrsLabelMarkers: MutableList<Marker> = mutableListOf()

private fun rebuildMgrsGridIfNeeded(
    mapView: MapView,
    visible: Boolean,
    overlays: MutableList<Polyline>,
    context: Context,
    forceRebuild: Boolean = false
) {
    // Always strip the previous overlay set + labels — keeps the map
    // clean when the user toggles off and avoids leaking polylines /
    // markers across builds.
    if (overlays.isNotEmpty()) {
        mapView.overlays.removeAll(overlays.toSet())
        overlays.clear()
    }
    if (mgrsLabelMarkers.isNotEmpty()) {
        mapView.overlays.removeAll(mgrsLabelMarkers.toSet())
        mgrsLabelMarkers.clear()
    }
    if (!visible) {
        lastMgrsFingerprint = ""
        return
    }
    val box = mapView.boundingBox ?: return
    val fp = String.format(
        "%.3f,%.3f,%.3f,%.3f,%d",
        box.latNorth, box.latSouth, box.lonEast, box.lonWest,
        mapView.width
    )
    lastMgrsFingerprint = fp

    val density = context.resources.displayMetrics.density
    val ink = com.tacticalmaps.mgrs.MgrsGridRenderer.INK_COLOR
    val (segments, labels) = com.tacticalmaps.mgrs.MgrsGridRenderer.build(
        minLat = box.latSouth, minLng = box.lonWest,
        maxLat = box.latNorth, maxLng = box.lonEast,
        mapWidthPx = mapView.width.coerceAtLeast(1)
    )
    for (seg in segments) {
        val widthDp = com.tacticalmaps.mgrs.MgrsGridRenderer.lineWidthDp(seg.type)
        val polyline = Polyline(mapView).apply {
            setPoints(listOf(seg.start, seg.end))
            outlinePaint.color = ink
            outlinePaint.strokeWidth = widthDp * density
            outlinePaint.style = android.graphics.Paint.Style.STROKE
            outlinePaint.isAntiAlias = true
            infoWindow = null
        }
        overlays.add(polyline)
    }
    for (label in labels) {
        val sp = com.tacticalmaps.mgrs.MgrsGridRenderer.labelTextSp(label.type)
        val drawable = makeMgrsLabelDrawable(context, label.text, sp, rotated = label.isVertical)
        val marker = Marker(mapView).apply {
            position = org.osmdroid.util.GeoPoint(label.lat, label.lng)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = drawable
            setInfoWindow(null)
            // Non-interactive — taps fall through to the underlying map.
            setOnMarkerClickListener { _, _ -> false }
        }
        mgrsLabelMarkers.add(marker)
    }
    // Insert ABOVE the tap overlay (at slot 0) but BELOW any markers so
    // the grid never blocks gesture detection.
    val insertIndex = 1.coerceAtMost(mapView.overlays.size)
    mapView.overlays.addAll(insertIndex, overlays)
    if (mgrsLabelMarkers.isNotEmpty()) {
        // Labels go above the polylines but still below interactive
        // overlays (which are added later in the same composition).
        mapView.overlays.addAll(insertIndex + overlays.size, mgrsLabelMarkers)
    }
    mapView.invalidate()
}

/// Bare dark-grey grid-square text with a subtle white halo for
/// legibility. No pill background. When `rotated` is true the text is
/// drawn sideways so easting labels align with vertical grid lines.
private fun makeMgrsLabelDrawable(
    context: Context,
    text: String,
    textSp: Float,
    rotated: Boolean
): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    @Suppress("DEPRECATION")
    val scaledDensity = context.resources.displayMetrics.scaledDensity
    val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = textSp * scaledDensity
        color = com.tacticalmaps.mgrs.MgrsGridRenderer.LABEL_TEXT_COLOR
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    val haloPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = paint.textSize
        color = 0xE6FFFFFF.toInt()
        typeface = paint.typeface
    }
    val textWidth = paint.measureText(text)
    val fm = paint.fontMetrics
    val textHeight = fm.descent - fm.ascent
    val pad = 3f * density
    val drawW = (textWidth + pad * 2)
    val drawH = (textHeight + pad * 2)
    // Rotated labels: bitmap dimensions swap so the rotated text fits.
    val canvasW = (if (rotated) drawH else drawW).toInt().coerceAtLeast(1)
    val canvasH = (if (rotated) drawW else drawH).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(canvasW, canvasH, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    if (rotated) {
        // Rotate -90° around the canvas centre so easting labels read
        // bottom→top, aligned with vertical grid lines.
        canvas.translate(canvasW / 2f, canvasH / 2f)
        canvas.rotate(-90f)
        canvas.translate(-drawW / 2f, -drawH / 2f)
    }
    val baselineY = pad - fm.ascent
    // White halo via four offset passes — keeps dark digits readable
    // on busy satellite tiles without a visible pill.
    val offset = 1f * density
    for (dx in listOf(-offset, offset)) {
        for (dy in listOf(-offset, offset)) {
            canvas.drawText(text, pad + dx, baselineY + dy, haloPaint)
        }
    }
    canvas.drawText(text, pad, baselineY, paint)
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

/// Return a non-interactive label Marker for a named drawing, or null if
/// the drawing has no name / no usable coordinates.
private fun DrawingFeature.toLabelMarker(mapView: MapView): Marker? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    val anchor = labelAnchor ?: return null
    val marker = Marker(mapView)
    marker.position = GeoPoint(anchor.latitude, anchor.longitude)
    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_TOP)
    marker.setTextIcon(trimmed)
    marker.setOnMarkerClickListener { _, _ -> false }
    marker.isDraggable = false
    return marker
}

private fun DrawingFeature.toOverlay(
    mapView: MapView,
    isDraft: Boolean,
    onTap: ((String) -> Unit)?,
    selected: Boolean = false
): Overlay? {
    val transformedPoints = if (isDraft) points else transformedDrawingPoints()
    val geoPoints = transformedPoints.map { GeoPoint(it.latitude, it.longitude) }
    if (geoPoints.isEmpty()) return null
    val stroke = strokeColor
    val fill = fillColor
    val baseWidth = if (isDraft) strokeWidth + 2f else strokeWidth
    val width = if (selected) baseWidth + 6f else baseWidth
    val pathEffect = drawingPathEffect(width)
    val featureId = id

    return when (geometry) {
        DrawingGeometry.POINT -> Polygon(mapView).apply {
            setPoints(
                if (isDraft) {
                    Polygon.pointsAsCircle(geoPoints.first(), 12.0)
                } else {
                    transformedPointShape()
                }
            )
            fillColor = fill
            strokeColor = stroke
            strokeWidth = width
            getOutlinePaint().pathEffect = pathEffect
            applyDrawingTapHandler(featureId, onTap)
        }
        DrawingGeometry.LINE -> {
            if (geoPoints.size == 1 && isDraft) return draftVertexOverlay(mapView, geoPoints.first(), stroke, fill, width, pathEffect, name)
            if (geoPoints.size < 2) return null
            Polyline(mapView).apply {
                setPoints(geoPoints)
                color = stroke
                setWidth(width)
                getPaint().pathEffect = pathEffect
                applyDrawingTapHandler(featureId, onTap)
            }
        }
        DrawingGeometry.POLYGON -> {
            if (geoPoints.size == 1 && isDraft) return draftVertexOverlay(mapView, geoPoints.first(), stroke, fill, width, pathEffect, name)
            if (geoPoints.size < 2) return null
            if (geoPoints.size < 3 && isDraft) return Polyline(mapView).apply {
                setPoints(geoPoints)
                color = stroke
                setWidth(width)
                getPaint().pathEffect = pathEffect
                applyDrawingTapHandler(featureId, onTap)
            }
            Polygon(mapView).apply {
                setPoints(geoPoints)
                fillColor = if (geoPoints.size >= 3) fill else Color.TRANSPARENT
                strokeColor = stroke
                strokeWidth = width
                getOutlinePaint().pathEffect = pathEffect
                applyDrawingTapHandler(featureId, onTap)
            }
        }
    }
}

private fun findDrawingFeatureAt(
    event: MotionEvent,
    mapView: MapView,
    drawings: List<DrawingFeature>,
    layers: List<DrawingLayer>
): DrawingFeature? {
    val visibleLayerIds = layers
        .ifEmpty { DrawingDocument.defaultLayers() }
        .filter { it.isVisible }
        .map { it.id }
        .toSet()

    return drawings
        .asReversed()
        .firstOrNull { feature ->
            feature.layerId in visibleLayerIds && feature.hitTest(event, mapView)
        }
}

private fun DrawingFeature.hitTest(event: MotionEvent, mapView: MapView): Boolean {
    val x = event.x
    val y = event.y
    val screenPoints = when (geometry) {
        DrawingGeometry.POINT -> transformedPointShape()
        DrawingGeometry.LINE, DrawingGeometry.POLYGON -> transformedDrawingPoints().map {
            GeoPoint(it.latitude, it.longitude)
        }
    }.map { mapView.projection.toPixels(it, Point()) }

    if (screenPoints.isEmpty()) return false
    val tolerance = max(24.0, strokeWidth.toDouble() + 14.0)
    return when (geometry) {
        DrawingGeometry.POINT -> {
            screenPoints.pointInPolygon(x, y) ||
                screenPoints.minDistanceToSegments(x, y) <= tolerance
        }
        DrawingGeometry.LINE -> screenPoints.minDistanceToSegments(x, y) <= tolerance
        DrawingGeometry.POLYGON -> {
            if (screenPoints.size < 3) {
                screenPoints.minDistanceToSegments(x, y) <= tolerance
            } else {
                screenPoints.pointInPolygon(x, y) ||
                    screenPoints.minDistanceToSegments(x, y) <= tolerance
            }
        }
    }
}

private fun List<Point>.pointInPolygon(x: Float, y: Float): Boolean {
    if (size < 3) return false
    var inside = false
    var j = lastIndex
    for (i in indices) {
        val xi = this[i].x.toFloat()
        val yi = this[i].y.toFloat()
        val xj = this[j].x.toFloat()
        val yj = this[j].y.toFloat()
        val intersects = ((yi > y) != (yj > y)) &&
            (x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 0.000001f) + xi)
        if (intersects) inside = !inside
        j = i
    }
    return inside
}

private fun List<Point>.minDistanceToSegments(x: Float, y: Float): Double {
    if (isEmpty()) return Double.POSITIVE_INFINITY
    if (size == 1) return hypot((x - first().x).toDouble(), (y - first().y).toDouble())
    var minDistance = Double.POSITIVE_INFINITY
    for (i in 0 until lastIndex) {
        minDistance = minOf(minDistance, distanceToSegment(x, y, this[i], this[i + 1]))
    }
    return minDistance
}

private fun distanceToSegment(x: Float, y: Float, a: Point, b: Point): Double {
    val ax = a.x.toDouble()
    val ay = a.y.toDouble()
    val bx = b.x.toDouble()
    val by = b.y.toDouble()
    val dx = bx - ax
    val dy = by - ay
    if (dx == 0.0 && dy == 0.0) return hypot(x - ax, y - ay)

    val t = (((x - ax) * dx + (y - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
    val px = ax + t * dx
    val py = ay + t * dy
    return hypot(x - px, y - py)
}

private fun DrawingFeature.transformedPointShape(): List<GeoPoint> {
    val point = points.firstOrNull() ?: return emptyList()
    val latMetres = 111_320.0
    val lonMetres = (latMetres * cos(Math.toRadians(point.latitude))).coerceAtLeast(0.01)
    val radians = Math.toRadians(-rotationDegrees)
    val cosA = cos(radians)
    val sinA = sin(radians)
    val radiusMetres = 12.0

    return (0 until 32).map { idx ->
        val a = idx * (Math.PI * 2.0 / 32.0)
        val scaledX = cos(a) * radiusMetres * scaleX
        val scaledY = sin(a) * radiusMetres * scaleY
        val rotatedX = scaledX * cosA - scaledY * sinA
        val rotatedY = scaledX * sinA + scaledY * cosA
        GeoPoint(
            point.latitude + rotatedY / latMetres,
            point.longitude + rotatedX / lonMetres
        )
    }
}

/// Lightweight record of a vertex-edit handle's current location, so
/// the long-press hit-test can find the closest real vertex to delete
/// without re-projecting every handle marker on each press.
private data class VertexHandle(
    val index: Int,
    val isMidpoint: Boolean,
    val lat: Double,
    val lng: Double
)

/// Marker subclass that drags the moment the user's finger moves —
/// no long-press warm-up. OSMDroid's stock drag is a long-press-then-
/// drag that competes with the map's pan and basically never wins on
/// a small handle, so we drive the drag ourselves out of
/// onTouchEvent. A short touch with no movement counts as a tap and
/// fires onTapped (used by midpoint handles for tap-to-insert).
private class DragMarker(
    mapView: MapView,
    drawable: android.graphics.drawable.Drawable,
    lat: Double,
    lng: Double,
    private val onTapped: (() -> Unit)? = null,
    private val onDragEnded: (lat: Double, lng: Double) -> Unit
) : Marker(mapView) {
    private var dragging = false
    private var didMove = false
    private var downTime: Long = 0

    init {
        position = org.osmdroid.util.GeoPoint(lat, lng)
        setAnchor(ANCHOR_CENTER, ANCHOR_CENTER)
        icon = drawable
        setInfoWindow(null)
    }

    override fun onTouchEvent(event: android.view.MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                if (hitTest(event, mapView)) {
                    dragging = true
                    didMove = false
                    downTime = event.eventTime
                    return true
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    didMove = true
                    val proj = mapView.projection
                    val pt = proj.fromPixels(event.x.toInt(), event.y.toInt())
                    position = org.osmdroid.util.GeoPoint(pt.latitude, pt.longitude)
                    mapView.invalidate()
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    val proj = mapView.projection
                    val pt = proj.fromPixels(event.x.toInt(), event.y.toInt())
                    val dt = event.eventTime - downTime
                    if (!didMove && dt < 300) {
                        // Quick tap with no movement → tap callback.
                        onTapped?.invoke()
                    } else {
                        onDragEnded(pt.latitude, pt.longitude)
                    }
                    return true
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event, mapView)
    }
}

/// Build a draggable vertex-edit handle marker. `midpoint = true`
/// renders the hollow "+" insertion handle; `midpoint = false`
/// renders the solid orange disc used for existing vertices.
private fun makeVertexHandleMarker(
    context: Context,
    mapView: MapView,
    midpoint: Boolean,
    lat: Double,
    lng: Double
): Marker {
    val drawable = makeVertexHandleDrawable(context, midpoint)
    val m = Marker(mapView).apply {
        position = GeoPoint(lat, lng)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = drawable
        isDraggable = true
        setInfoWindow(null)
    }
    return m
}

private fun makeVertexHandleDrawable(context: Context, midpoint: Boolean): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (26f * density).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val r = sizePx / 2f - 2f
    val orange = 0xFFFFA63D.toInt()
    val white = 0xFFFFFFFF.toInt()
    if (midpoint) {
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            alpha = 220
        }
        canvas.drawCircle(cx, cy, r, fill)
        val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = orange
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        canvas.drawCircle(cx, cy, r, stroke)
        val cross = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = orange
            strokeWidth = 2.5f * density
        }
        val armPx = 6f * density
        canvas.drawLine(cx, cy - armPx, cx, cy + armPx, cross)
        canvas.drawLine(cx - armPx, cy, cx + armPx, cy, cross)
    } else {
        val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = orange
        }
        canvas.drawCircle(cx, cy, r, fill)
        val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = white
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        canvas.drawCircle(cx, cy, r, ring)
    }
    return BitmapDrawable(context.resources, bmp)
}

private fun DrawingFeature.transformedDrawingPoints(): List<DrawingPoint> {
    if (points.size < 2 && geometry != DrawingGeometry.POINT) return points
    if (scaleX == 1.0 && scaleY == 1.0 && rotationDegrees == 0.0) return points

    val centerLat = points.map { it.latitude }.average()
    val centerLng = points.map { it.longitude }.average()
    val lonScale = cos(Math.toRadians(centerLat)).coerceAtLeast(0.000001)
    val radians = Math.toRadians(-rotationDegrees)
    val cosA = cos(radians)
    val sinA = sin(radians)

    return points.map { point ->
        val localX = (point.longitude - centerLng) * lonScale
        val localY = point.latitude - centerLat
        val scaledX = localX * scaleX
        val scaledY = localY * scaleY
        val rotatedX = scaledX * cosA - scaledY * sinA
        val rotatedY = scaledX * sinA + scaledY * cosA
        DrawingPoint(
            latitude = centerLat + rotatedY,
            longitude = centerLng + rotatedX / lonScale
        )
    }
}

private fun Polyline.applyDrawingTapHandler(featureId: String, onTap: ((String) -> Unit)?) {
    if (onTap == null) return
    setOnClickListener { _, _, _ ->
        onTap(featureId)
        true
    }
}

private fun Polygon.applyDrawingTapHandler(featureId: String, onTap: ((String) -> Unit)?) {
    if (onTap == null) return
    setOnClickListener { _, _, _ ->
        onTap(featureId)
        true
    }
}

private fun DrawingFeature.drawingPathEffect(width: Float): DashPathEffect? =
    if (strokeStyle == DrawingStrokeStyle.DASHED) {
        DashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f)
    } else {
        null
    }

private fun draftVertexOverlay(
    mapView: MapView,
    point: GeoPoint,
    stroke: Int,
    fill: Int,
    width: Float,
    pathEffect: DashPathEffect?,
    name: String
): Overlay = Polygon(mapView).apply {
    setPoints(Polygon.pointsAsCircle(point, 12.0))
    fillColor = fill
    strokeColor = stroke
    strokeWidth = width
    getOutlinePaint().pathEffect = pathEffect
    title = name
}

/**
 * Composite the unit icon and its name-label pill into a single bitmap so
 * the marker draws them as one. Sidesteps the entire anchor-positioning
 * headache that separate label markers introduce — the label sits at a
 * known pixel offset below the icon by construction.
 *
 * Returns the composite [BitmapDrawable] plus a new anchor pair that places
 * the marker's geographic position exactly where the original icon's
 * anchor would have placed it (so geographic accuracy is preserved).
 */
private fun compositeUnitIconWithLabel(
    context: Context,
    icon: android.graphics.drawable.Drawable,
    text: String,
    originalAnchorV: Float,
    selected: Boolean = false
): Pair<BitmapDrawable, Pair<Float, Float>> {
    val density = context.resources.displayMetrics.density
    val iconW = icon.intrinsicWidth.coerceAtLeast(1)
    val iconH = icon.intrinsicHeight.coerceAtLeast(1)

    val gapPx = (2f * density).toInt()
    // Extra padding around the icon when selected so the wide blurred
    // halo doesn't get clipped at the bitmap edge.
    val glowPad = if (selected) (18f * density).toInt() else 0

    // Rasterise the icon so we can scan its visible bounding box.
    // Bitmaps carry a lot of transparent padding around the cyan
    // rectangle / diamond / etc.; we want labels glued to the visible
    // glyph, not the bitmap edge. We scan the UN-GLOWED icon so the
    // label position stays identical whether or not the bubble is
    // selected.
    val iconBmp = android.graphics.Bitmap.createBitmap(iconW, iconH, android.graphics.Bitmap.Config.ARGB_8888)
    val iconCanvas = android.graphics.Canvas(iconBmp)
    icon.setBounds(0, 0, iconW, iconH)
    icon.draw(iconCanvas)
    val visBounds = findVisibleBounds(iconBmp)
    val visibleBottom = visBounds.bottom
    val visibleWidth = visBounds.width().coerceAtLeast(1)
    val visibleCx = (visBounds.left + visBounds.right) / 2f

    // Clamp label width to 1.25× the visible glyph width — never the
    // bitmap width — so the label doesn't sprawl past a small icon
    // sitting inside a big bitmap.
    val maxLabelWidth = (visibleWidth * 1.25f).coerceAtLeast(40f * density)

    val labelBmp = makeUnitLabelDrawable(
        context = context,
        text = text,
        maxWidthPx = maxLabelWidth,
        topGapPx = 0f
    ).bitmap

    val labelW = labelBmp.width
    val labelH = labelBmp.height
    // Icon's origin INSIDE the output, shifted by the glow padding so
    // there's space for the halo on every side.
    val iconLeftInOutput = glowPad.toFloat()
    val iconTopInOutput  = glowPad.toFloat()
    // Align label horizontally on the icon's visible glyph centre.
    val visibleCxInOutput = iconLeftInOutput + visibleCx
    val labelLeftDesired = visibleCxInOutput - labelW / 2f
    val labelRightDesired = visibleCxInOutput + labelW / 2f
    // Grow the canvas sideways if the label overhangs the icon's bitmap
    // padded region; the label NEVER shifts vertically with the glow.
    val rightLimit = iconLeftInOutput + iconW + glowPad
    val extendLeft  = maxOf(0f, -labelLeftDesired).toInt()
    val extendRight = maxOf(0f, labelRightDesired - rightLimit).toInt()
    val totalW = iconW + glowPad * 2 + extendLeft + extendRight
    val labelTopY = (iconTopInOutput + visibleBottom + 1 + gapPx).toInt()
    val totalH = maxOf(iconH + glowPad * 2, labelTopY + labelH)

    val out = android.graphics.Bitmap.createBitmap(totalW, totalH, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)

    val iconDrawX = iconLeftInOutput + extendLeft
    val iconDrawY = iconTopInOutput

    if (selected) {
        // Paint the orange halo behind the icon's region only — never
        // around the label area. Reuse the icon's alpha mask so the
        // glow follows the symbol's outline.
        val alpha = iconBmp.extractAlpha()
        val outerGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFA63D.toInt()
            maskFilter = android.graphics.BlurMaskFilter(
                16f * density, android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        }
        repeat(4) { canvas.drawBitmap(alpha, iconDrawX, iconDrawY, outerGlow) }
        val innerGlow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFA63D.toInt()
            maskFilter = android.graphics.BlurMaskFilter(
                6f * density, android.graphics.BlurMaskFilter.Blur.SOLID
            )
        }
        repeat(3) { canvas.drawBitmap(alpha, iconDrawX, iconDrawY, innerGlow) }
    }

    canvas.drawBitmap(iconBmp, iconDrawX, iconDrawY, null)
    canvas.drawBitmap(labelBmp, labelLeftDesired + extendLeft, labelTopY.toFloat(), null)

    val drawable = BitmapDrawable(context.resources, out)
    // Anchor: marker geographic position lines up with the original
    // icon's anchor pixel inside the composite. Recompute U so the
    // marker sits on the icon's visible centre, not the composite
    // centre (in case the composite grew sideways for a wide label).
    val newAnchorU = (iconDrawX + iconW / 2f) / totalW
    val newAnchorV = (iconTopInOutput + originalAnchorV * iconH) / totalH
    return drawable to (newAnchorU to newAnchorV)
}

/**
 * Wrap an icon in an orange halo to indicate selection. The output is a
 * larger bitmap with the icon's pixels at the centre and a blurred alpha
 * silhouette drawn behind in tactical orange. Anchor is adjusted so the
 * original icon's anchor pixel still maps to the marker's geographic
 * position.
 */
private fun applySelectionGlow(
    context: Context,
    icon: android.graphics.drawable.Drawable,
    originalAnchor: Pair<Float, Float>
): Pair<android.graphics.drawable.BitmapDrawable, Pair<Float, Float>> {
    val density = context.resources.displayMetrics.density
    // Bigger padding so the wide outer-glow halo doesn't get clipped at
    // the bitmap edge.
    val pad = (18f * density).toInt().coerceAtLeast(18)
    val w = icon.intrinsicWidth.coerceAtLeast(1)
    val h = icon.intrinsicHeight.coerceAtLeast(1)
    val outW = w + pad * 2
    val outH = h + pad * 2

    val src = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    icon.setBounds(0, 0, w, h)
    icon.draw(android.graphics.Canvas(src))
    val alpha = src.extractAlpha()

    val out = android.graphics.Bitmap.createBitmap(outW, outH, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)

    // Outer glow: large blur, layered so the halo bleeds well beyond
    // the symbol's outline.
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
    // Inner glow: tighter blur in the same colour to bring the edges up
    // to full brightness so the halo doesn't look washed out.
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
    return android.graphics.drawable.BitmapDrawable(context.resources, out) to (newAnchorU to newAnchorV)
}

/**
 * Visible bounding box of a bitmap — the rectangle of pixels with
 * non-trivial opacity. Used to size and align a label against an icon's
 * visible glyph rather than its bitmap padding.
 */
private fun findVisibleBounds(bitmap: android.graphics.Bitmap): android.graphics.Rect {
    val w = bitmap.width
    val h = bitmap.height
    if (w == 0 || h == 0) return android.graphics.Rect(0, 0, 0, 0)
    var top = -1; var bottom = -1; var left = w; var right = -1
    val row = IntArray(w)
    for (y in 0 until h) {
        bitmap.getPixels(row, 0, w, 0, y, w, 1)
        var rowHasInk = false
        for (x in 0 until w) {
            if ((row[x] ushr 24) > 16) {
                if (x < left) left = x
                if (x > right) right = x
                rowHasInk = true
            }
        }
        if (rowHasInk) {
            if (top < 0) top = y
            bottom = y
        }
    }
    if (right < left || bottom < top) {
        return android.graphics.Rect(0, 0, w, h)
    }
    return android.graphics.Rect(left, top, right + 1, bottom + 1)
}

/**
 * Render a unit / task label as a rounded translucent pill. Text is wrapped
 * onto multiple lines so the bitmap never exceeds [maxWidthPx] (which the
 * caller sets to 1.25× the icon width). A 2dp transparent strip sits at
 * the top of the bitmap so when the marker is anchored TOP-centre at the
 * waypoint coordinate, the visible pill ends up 2dp below the icon's
 * bottom edge.
 */
private fun makeUnitLabelDrawable(
    context: Context,
    text: String,
    maxWidthPx: Float,
    topGapPx: Float = 0f
): BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val textPaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = android.graphics.Color.WHITE
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }
    val padH = 6f * density
    val padV = 3f * density
    val innerMax = (maxWidthPx - padH * 2).coerceAtLeast(40f)

    // Greedy line wrap on word boundaries; long words fall through and
    // overflow rather than truncate, so a single huge token still shows
    // (the caller can rename if it's truly unreasonable).
    val words = text.split(' ').filter { it.isNotBlank() }
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    for (word in words) {
        val candidate = if (current.isEmpty()) word else "$current $word"
        if (textPaint.measureText(candidate) <= innerMax) {
            current.clear(); current.append(candidate)
        } else {
            if (current.isNotEmpty()) {
                lines += current.toString()
                current.clear()
            }
            current.append(word)
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    if (lines.isEmpty()) lines += text

    val widest = lines.maxOf { textPaint.measureText(it) }
    val lineHeight = textPaint.fontMetrics.run { descent - ascent }
    // Caller-supplied transparent strip above the pill. For unit labels
    // this is (icon-height / 2 + 2dp) so the pill ends up 2dp below the
    // icon when the marker is anchored top-centre at the icon's centre.
    val gapTopPx = topGapPx
    val pillW = (widest + padH * 2).coerceAtLeast(40f)
    val pillH = lineHeight * lines.size + padV * 2
    val bitmapW = pillW.toInt()
    val bitmapH = (pillH + gapTopPx).toInt()

    val bitmap = android.graphics.Bitmap.createBitmap(bitmapW, bitmapH, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val rect = android.graphics.RectF(0f, gapTopPx, pillW, gapTopPx + pillH)
    val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x9E000000.toInt()  // ~62% black
    }
    canvas.drawRoundRect(rect, 4f * density, 4f * density, bg)

    var y = gapTopPx + padV - textPaint.fontMetrics.ascent
    for (line in lines) {
        val w = textPaint.measureText(line)
        canvas.drawText(line, (pillW - w) / 2f, y, textPaint)
        y += lineHeight
    }
    return BitmapDrawable(context.resources, bitmap)
}

private class AlphaHitMarker(
    mapView: MapView,
    private val anchor: Pair<Float, Float>
) : Marker(mapView) {
    /// Cache the visible-pixel bounding box per bitmap so we don't
    /// rescan on every tap.
    private var cachedBitmap: android.graphics.Bitmap? = null
    private var cachedVisibleBounds: android.graphics.Rect? = null

    override fun hitTest(event: MotionEvent, mapView: MapView): Boolean {
        if (!super.hitTest(event, mapView)) return false

        val drawable = icon
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return true
        if (bitmap.isRecycled) return false

        // Refresh the cached visible bounds whenever the icon bitmap
        // changes (composite re-built, kind switched, etc.).
        if (cachedBitmap !== bitmap) {
            cachedVisibleBounds = findVisibleBounds(bitmap)
            cachedBitmap = bitmap
        }
        val visBounds = cachedVisibleBounds ?: return true

        val markerPixel = mapView.projection.toPixels(position, Point())
        val drawWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: bitmap.width
        val drawHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: bitmap.height
        val left = markerPixel.x - drawWidth * anchor.first
        val top = markerPixel.y - drawHeight * anchor.second
        val localX = ((event.x - left) * bitmap.width / drawWidth).toInt()
        val localY = ((event.y - top) * bitmap.height / drawHeight).toInt()

        // Hit any pixel inside the visible bounding box — gives wireframe
        // task graphics (Retain, Contain, the various ring symbols) a
        // generous interior tap target, instead of forcing the user to
        // touch the thin outline stroke exactly.
        return localX in visBounds.left until visBounds.right &&
               localY in visBounds.top until visBounds.bottom
    }
}
