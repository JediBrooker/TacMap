package com.tacticalmaps.map

import android.graphics.Bitmap
import android.graphics.Canvas as NativeCanvas
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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PatternItem
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GroundOverlay
import com.google.maps.android.compose.GroundOverlayPosition
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.TileOverlay
import com.google.maps.android.compose.rememberMarkerState
import com.google.maps.android.SphericalUtil
import com.tacticalmaps.calibration.Calibration
import com.tacticalmaps.calibration.PdfMapSource
import com.tacticalmaps.calibration.PdfPageRenderer
import com.tacticalmaps.mgrs.MgrsGridRenderer
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingStrokeStyle
import com.tacticalmaps.waypoints.Waypoint
import kotlin.math.roundToInt

// Map overlay rendering — vertex handles, drawing shapes, MGRS grid lines +
// labels, PDF ground overlay, waypoint icons + labels, drawing labels, and the
// fiduciary pins — extracted verbatim from GoogleMapScreen.kt. Composables
// GoogleMapScreen calls are `internal`; leaf helpers stay `private`.

@Composable
internal fun VertexHandleBox(
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
internal fun DrawingShape(
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

/// MGRS grid lines rendered as a TileOverlay so the Maps SDK fetches tiles
/// lazily and handles caching internally — no per-line Polyline IPC calls.
@Composable
internal fun MgrsGridLayer() {
    val density = LocalDensity.current.density
    val provider = remember(density) { MgrsGridTileProvider(density) }
    TileOverlay(tileProvider = provider, zIndex = -0.5f)
}

/// MGRS grid labels live OUTSIDE the GoogleMap composable scope (they
/// need to draw on top of the map, projected to screen coords each
/// frame). Uses a single Canvas draw call instead of N×5 Text
/// composables to avoid exhausting the Compose change-list under
/// rapid camera movement.
@Composable
internal fun MgrsGridLabelsOverlay(cameraPositionState: CameraPositionState) {
    cameraPositionState.position
    val projection = cameraPositionState.projection ?: return
    val bounds = projection.visibleRegion.latLngBounds

    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale

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

    val mainPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            color = MgrsGridRenderer.LABEL_TEXT_COLOR
        }
    }
    val haloPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
            color = 0xE6FFFFFF.toInt()
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val nc = drawContext.canvas.nativeCanvas
        labels.forEach { mark ->
            val screen = projection.toScreenLocation(LatLng(mark.lat, mark.lng))
            val textSizePx = MgrsGridRenderer.labelTextSp(mark.type) * density * fontScale
            mainPaint.textSize = textSizePx
            haloPaint.textSize = textSizePx

            val cx = screen.x.toFloat()
            val cy = screen.y.toFloat()
            val fm = mainPaint.fontMetrics
            // Baseline that vertically centres the text glyph at cy
            val textY = cy - (fm.ascent + fm.descent) / 2f
            val haloOff = textSizePx * 0.07f

            if (mark.isVertical) {
                nc.save()
                nc.rotate(-90f, cx, cy)
            }
            // Halo: four diagonal offsets keep text readable on busy tiles
            nc.drawText(mark.text, cx - haloOff, textY - haloOff, haloPaint)
            nc.drawText(mark.text, cx + haloOff, textY - haloOff, haloPaint)
            nc.drawText(mark.text, cx - haloOff, textY + haloOff, haloPaint)
            nc.drawText(mark.text, cx + haloOff, textY + haloOff, haloPaint)
            nc.drawText(mark.text, cx, textY, mainPaint)
            if (mark.isVertical) nc.restore()
        }
    }
}

@Composable
internal fun PdfGroundOverlay(source: PdfMapSource) {
    val context = LocalContext.current

    /// Render the PDF's first page once per URI as a single high-res
    /// bitmap and place it via a GroundOverlay. Simple, robust, and at the
    /// 4096-px max dimension set in [PdfPageRenderer] it stays readable
    /// through several zoom steps. The previous tile-based approach was more
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

    // When the PDF carries a georeferencing affine, place the page with the
    // sheet's true ROTATION + scale (centre + width/height in metres + bearing)
    // instead of stretching the bitmap to an axis-aligned LatLngBounds. The
    // bounds form drops the ~1° grid-convergence rotation, which is exactly why
    // the app's MGRS grid sat offset from the sheet's printed grid.
    val transform = (source.calibration as? Calibration.Fiduciaries)?.transform
        ?: (source.calibration as? Calibration.Parsed)?.transform
    val pageInfo = source.pageInfo
    if (transform != null && pageInfo != null) {
        val pw = pageInfo.pageWidth.toDouble()
        val ph = pageInfo.pageHeight.toDouble()
        // PdfRenderer rasterises with bitmap-top = page-top (PDF y-up maxY).
        val tlC = transform.apply(0.0, ph); val tl = LatLng(tlC.latitude, tlC.longitude)
        val trC = transform.apply(pw, ph);  val tr = LatLng(trC.latitude, trC.longitude)
        val blC = transform.apply(0.0, 0.0); val bl = LatLng(blC.latitude, blC.longitude)
        val brC = transform.apply(pw, 0.0);  val br = LatLng(brC.latitude, brC.longitude)
        val widthM = SphericalUtil.computeDistanceBetween(tl, tr)
        val heightM = SphericalUtil.computeDistanceBetween(tl, bl)
        if (widthM >= 1.0 && heightM >= 1.0) {
            val center = LatLng(
                (tl.latitude + tr.latitude + bl.latitude + br.latitude) / 4.0,
                (tl.longitude + tr.longitude + bl.longitude + br.longitude) / 4.0
            )
            // At bearing 0 the bitmap's top edge runs due east (heading 90); the
            // real top edge's heading minus 90 is the clockwise rotation.
            val bearing = ((SphericalUtil.computeHeading(tl, tr) - 90.0) % 360.0 + 360.0) % 360.0
            GroundOverlay(
                position = GroundOverlayPosition.create(center, widthM.toFloat(), heightM.toFloat()),
                image = descriptor,
                bearing = bearing.toFloat(),
                clickable = false,
                zIndex = -1f
            )
            return
        }
    }

    // Fallback: axis-aligned stretch for uncalibrated PDFs (no affine).
    val bounds = source.coverage ?: return
    GroundOverlay(
        position = GroundOverlayPosition.create(
            LatLngBounds(
                LatLng(bounds.southwest.latitude, bounds.southwest.longitude),
                LatLng(bounds.northeast.latitude, bounds.northeast.longitude)
            )
        ),
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
internal fun WaypointHandlesOverlay(
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
                rawIcon.draw(NativeCanvas(bm))
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
    icon.draw(NativeCanvas(src))
    val alpha = src.extractAlpha()

    val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = NativeCanvas(out)

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
internal fun WaypointLabelsOverlay(
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
internal fun DrawingLabelsOverlay(
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
internal fun CalibrationFiduciaryMarker(index: Int, fid: com.tacticalmaps.calibration.Fiduciary) {
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
    val canvas = NativeCanvas(bmp)
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
    draw(NativeCanvas(bm))
    return BitmapDescriptorFactory.fromBitmap(bm)
}
