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
    val currentDrawings = rememberUpdatedState(drawings)
    val currentDrawingLayers = rememberUpdatedState(drawingLayers)
    val pdfOverlays = remember { mutableListOf<Overlay>() }
    val drawingOverlays = remember { mutableListOf<Overlay>() }

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

    LaunchedEffect(drawings, drawingLayers, draftDrawing) {
        drawingOverlays.forEach { mapView.overlays.remove(it) }
        drawingOverlays.clear()

        val visibleLayerIds = drawingLayers
            .ifEmpty { DrawingDocument.defaultLayers() }
            .filter { it.isVisible }
            .map { it.id }
            .toSet()
        val nextOverlays = drawings
            .filter { it.layerId in visibleLayerIds }
            .mapNotNull {
                it.toOverlay(
                    mapView = mapView,
                    isDraft = false,
                    onTap = { featureId ->
                        if (!currentDrawingInputEnabled.value) {
                            currentOnDrawingFeatureTap.value(featureId)
                        }
                    }
                )
            } +
            listOfNotNull(draftDrawing?.toOverlay(mapView, isDraft = true, onTap = null))

        drawingOverlays.addAll(nextOverlays)
        val insertIndex = pdfOverlays.size.coerceAtMost(mapView.overlays.size)
        mapView.overlays.addAll(insertIndex, nextOverlays)
        keepDrawingInputOnTop()
        mapView.invalidate()
    }

    // Rebuild marker layer whenever waypoints change. Cheap — we
    // clear+re-add, count is small.
    LaunchedEffect(waypoints, drawingLayers) {
        // Drop existing waypoint markers (we tag them so we don't
        // nuke our own MapEventsOverlay below).
        mapView.overlays.removeAll { it is Marker }
        val visibleLayerIds = drawingLayers
            .ifEmpty { DrawingDocument.defaultLayers() }
            .filter { it.isVisible }
            .map { it.id }
            .toSet()

        waypoints.filter { it.layerId in visibleLayerIds }.forEach { wp ->
            val icon = SymbolIconFactory.drawableFor(context, wp)
            val anchor = SymbolIconFactory.anchorFor(context, wp)
            val m = AlphaHitMarker(mapView, anchor).apply {
                position = GeoPoint(wp.latitude, wp.longitude)
                title = wp.name
                this.icon = icon.mutate()
                setAnchor(anchor.first, anchor.second)
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
            override fun longPressHelper(p: GeoPoint?): Boolean = false
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
                return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                val c = mapView.mapCenter
                onCameraIdle(c.latitude, c.longitude, /*byUser=*/true)
                currentOnBearingChanged.value(mapView.mapOrientation.toDouble())
                return false
            }
        })
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun DrawingFeature.toOverlay(
    mapView: MapView,
    isDraft: Boolean,
    onTap: ((String) -> Unit)?
): Overlay? {
    val transformedPoints = if (isDraft) points else transformedDrawingPoints()
    val geoPoints = transformedPoints.map { GeoPoint(it.latitude, it.longitude) }
    if (geoPoints.isEmpty()) return null
    val stroke = strokeColor
    val fill = fillColor
    val width = if (isDraft) strokeWidth + 2f else strokeWidth
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

private class AlphaHitMarker(
    mapView: MapView,
    private val anchor: Pair<Float, Float>
) : Marker(mapView) {
    override fun hitTest(event: MotionEvent, mapView: MapView): Boolean {
        if (!super.hitTest(event, mapView)) return false

        val drawable = icon
        val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return true
        if (bitmap.isRecycled) return false

        val markerPixel = mapView.projection.toPixels(position, Point())
        val drawWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: bitmap.width
        val drawHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: bitmap.height
        val left = markerPixel.x - drawWidth * anchor.first
        val top = markerPixel.y - drawHeight * anchor.second
        val localX = ((event.x - left) * bitmap.width / drawWidth).toInt()
        val localY = ((event.y - top) * bitmap.height / drawHeight).toInt()

        if (localX !in 0 until bitmap.width || localY !in 0 until bitmap.height) {
            return false
        }
        return (bitmap.getPixel(localX, localY) ushr 24) > 24
    }
}
