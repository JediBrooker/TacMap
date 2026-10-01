package com.tacmap.map.render

import com.tacmap.localization.L10n

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.drawings.LineGraphic
import com.tacmap.map.SymbolIconFactory
import com.tacmap.map.TaskGraphicSizing
import com.tacmap.waypoints.TacticalControlMeasure
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.tacmap.calibration.Fiduciary
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.calibration.Wgs84Coordinate
import com.tacmap.calibration.PdfPageRenderer
import com.tacmap.mgrs.GridScreenFrame
import com.tacmap.mgrs.MgrsGridBuildSpec
import com.tacmap.mgrs.MgrsGridLabels
import com.tacmap.mgrs.MgrsGridRenderer
import mil.nga.mgrs.grid.GridType
import androidx.compose.runtime.produceState
import kotlinx.coroutines.ensureActive
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.WaypointKind
import com.tacmap.sync.PresencePeer
import com.tacmap.sync.presenceMarkerPresentation
import com.tacmap.waypoints.Waypoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Vector drawings (lines / polygons / points) on the SDK-free renderer. Projects
 * each feature through [MapProjection] and strokes it on a Canvas, with the NATO
 * tactical-line decorations (FLOT crenellations, boundary ticks, axis arrowhead,
 * phase-line dash) done in SCREEN space - the same approach as the iOS
 * DrawingsOverlayView. Replaces the Google Maps Polyline/Polygon path.
 */
@Composable
fun DrawingsCanvas(
    features: List<DrawingFeature>,
    draft: DrawingFeature?,
    selectedId: String?,
    projection: MapProjection,
    modifier: Modifier = Modifier
) {
    Canvas(modifier.fillMaxSize()) {
        features.forEach { f -> drawFeature(f, selected = f.id == selectedId, isDraft = false, projection) }
        draft?.let { drawFeature(it, selected = false, isDraft = true, projection) }
    }
}

private val HALO = Color(0xFFFFA63D)

private fun DrawScope.drawFeature(
    feature: DrawingFeature,
    selected: Boolean,
    isDraft: Boolean,
    proj: MapProjection
) {
    val pts = feature.effectivePoints.map { val o = proj.toScreen(it.latitude, it.longitude); o }
    if (pts.isEmpty()) return

    val d = proj.density
    val stroke = Color(feature.strokeColor)
    // Fill is an independent persisted style. Deriving it from the stroke here
    // made a correctly imported polygon silently render with the wrong colour
    // and opacity even though its GeoJSON round-trip was intact.
    val fill = Color(feature.fillColor)
    val width = (if (isDraft) feature.strokeWidth + 2f else feature.strokeWidth)
    val dash = if (isDraft || feature.strokeStyle == DrawingStrokeStyle.DASHED)
        PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f) else null

    when (feature.geometry) {
        DrawingGeometry.POINT -> {
            drawCircle(fill, radius = 7f * d, center = pts.first())
            drawCircle(stroke, radius = 7f * d, center = pts.first(),
                style = Stroke(width = max(2f, width * 0.6f)))
        }
        DrawingGeometry.LINE -> {
            if (pts.size < 2) return
            val lg = feature.lineGraphic ?: LineGraphic.PLAIN
            if (selected) strokePolyline(pts, HALO.copy(alpha = 0.55f), width + 14f, null)
            when (lg) {
                LineGraphic.FORWARD_EDGE ->
                    strokePath(crenellated(pts, 22f * d, 12f * d), stroke, width, null)
                LineGraphic.PHASE_LINE ->
                    strokePolyline(pts, stroke, width,
                        PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f))
                LineGraphic.BOUNDARY -> {
                    strokePolyline(pts, stroke, width, dash)
                    strokePath(boundaryTicks(pts, 30f * d, 9f * d), stroke, width, null)
                }
                LineGraphic.AXIS_OF_ADVANCE -> {
                    strokePolyline(pts, stroke, width, dash)
                    arrowHead(pts, 17f * d)?.let { strokePath(it, stroke, width, null) }
                }
                LineGraphic.PLAIN -> strokePolyline(pts, stroke, width, dash)
            }
        }
        DrawingGeometry.POLYGON -> {
            if (pts.size < 2) return
            if (pts.size < 3) {
                if (selected) strokePolyline(pts, HALO.copy(alpha = 0.55f), width + 14f, null)
                strokePolyline(pts, stroke, width, dash)
                return
            }
            val ring = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                pts.drop(1).forEach { lineTo(it.x, it.y) }
                close()
            }
            if (selected) {
                drawPath(ring, HALO.copy(alpha = 0.55f), style = Stroke(width = width + 14f,
                    cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            drawPath(ring, fill, style = Fill)
            drawPath(ring, stroke, style = Stroke(width = width, cap = StrokeCap.Round,
                join = StrokeJoin.Round, pathEffect = dash))
        }
    }
}

private fun DrawScope.strokePolyline(pts: List<Offset>, color: Color, width: Float, dash: PathEffect?) {
    if (pts.size < 2) return
    val p = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
    }
    strokePath(p, color, width, dash)
}

private fun DrawScope.strokePath(path: Path, color: Color, width: Float, dash: PathEffect?) {
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round,
        join = StrokeJoin.Round, pathEffect = dash))
}

// MARK: - Screen-space tactical line geometry (mirrors iOS DrawingsOverlayView)

private data class Samp(val p: Offset, val nx: Float, val ny: Float, val s: Float)

private fun sample(pts: List<Offset>, step: Float): List<Samp> {
    val out = ArrayList<Samp>()
    var s = 0f
    for (i in 0 until pts.size - 1) {
        val a = pts[i]; val b = pts[i + 1]
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = max(hypot(dx, dy), 0.0001f)
        val ux = dx / len; val uy = dy / len
        val nx = -uy; val ny = ux
        var t = 0f
        while (t < len) {
            out.add(Samp(Offset(a.x + ux * t, a.y + uy * t), nx, ny, s + t))
            t += step
        }
        s += len
    }
    out.add(Samp(pts.last(), 0f, 0f, s))
    return out
}

private fun crenellated(pts: List<Offset>, period: Float, height: Float): Path {
    val samp = sample(pts, max(1f, period / 8f))
    val half = period / 2
    val p = Path()
    var started = false
    for (sm in samp) {
        val raised = (sm.s / half).toInt() % 2 == 1
        val off = if (raised) height else 0f
        val q = Offset(sm.p.x + sm.nx * off, sm.p.y + sm.ny * off)
        if (started) p.lineTo(q.x, q.y) else { p.moveTo(q.x, q.y); started = true }
    }
    return p
}

private fun boundaryTicks(pts: List<Offset>, spacing: Float, len: Float): Path {
    val samp = sample(pts, max(1f, spacing / 6f))
    val p = Path()
    var next = spacing
    for (sm in samp) {
        if (sm.s >= next) {
            p.moveTo(sm.p.x + sm.nx * len, sm.p.y + sm.ny * len)
            p.lineTo(sm.p.x - sm.nx * len, sm.p.y - sm.ny * len)
            next += spacing
        }
    }
    return p
}

private fun arrowHead(pts: List<Offset>, size: Float): Path? {
    if (pts.size < 2) return null
    val tip = pts.last(); val prev = pts[pts.size - 2]
    val ang = atan2(tip.y - prev.y, tip.x - prev.x)
    val p = Path()
    for (da in floatArrayOf((Math.PI * 0.83).toFloat(), (-Math.PI * 0.83).toFloat())) {
        p.moveTo(tip.x, tip.y)
        p.lineTo(tip.x + cos(ang + da) * size, tip.y + sin(ang + da) * size)
    }
    return p
}

/**
 * MGRS grid (lines + labels) on the SDK-free renderer. Geometry comes from a
 * cached MgrsGridBuildSpec built on Dispatchers.Default, so pans / pinches /
 * compass spins just reproject the cached lines. It only rebuilds when the camera
 * leaves the spec's envelope, and the old lines stay up while the new ones build.
 * Labels get laid out per frame against the visible viewport (MgrsGridLabels).
 */
@Composable
fun MgrsGridCanvas(camera: MapCamera, density: Float, modifier: Modifier = Modifier) {
    if (camera.viewportWidth <= 0.0 || camera.viewportHeight <= 0.0) return
    val pxPerDp = density.toDouble()
    val lod = MgrsGridRenderer.lod(camera.zoom, camera.centerLat)

    // holder not state: swapping the spec shouldn't itself trigger a recompose,
    // the new key on produceState below does the work
    val specHolder = remember { arrayOfNulls<MgrsGridBuildSpec>(1) }
    val spec = specHolder[0]?.takeUnless { it.isStaleFor(camera, pxPerDp, lod) }
        ?: MgrsGridBuildSpec.forCamera(camera, pxPerDp, lod).also { specHolder[0] = it }
    val geometry by produceState<MgrsGridRenderer.GridGeometry?>(initialValue = null, spec) {
        val built = try {
            withContext(Dispatchers.Default) { spec.build(checkCancelled = { ensureActive() }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RuntimeException) {
            // keep whatever grid we had rather than take the map down with it
            android.util.Log.w("MgrsGridCanvas", "grid build failed", e)
            null
        }
        if (built != null) value = built
    }
    val paints = remember { MgrsLabelPaints() }

    Canvas(modifier.fillMaxSize()) {
        val g = geometry ?: return@Canvas
        val frame = GridScreenFrame(camera)
        val ink = Color(MgrsGridRenderer.INK_COLOR)
        // one path per level so the 85% ink doesn't double up at joins
        for (level in lod.drawn) {
            val path = Path()
            var any = false
            for (piece in g.pieces) {
                if (piece.level != level ||
                    !frame.mayBeVisible(piece.minX, piece.minY, piece.maxX, piece.maxY)
                ) continue
                val mx = piece.mercX
                val my = piece.mercY
                path.moveTo((frame.sx(mx[0], my[0]) * density).toFloat(), (frame.sy(mx[0], my[0]) * density).toFloat())
                for (i in 1 until mx.size) {
                    path.lineTo((frame.sx(mx[i], my[i]) * density).toFloat(), (frame.sy(mx[i], my[i]) * density).toFloat())
                }
                any = true
            }
            if (any) {
                drawPath(
                    path, ink,
                    style = Stroke(
                        width = MgrsGridRenderer.lineWidthPx(level, density),
                        cap = StrokeCap.Round, join = StrokeJoin.Round,
                    ),
                )
            }
        }

        val labels = MgrsGridLabels.place(g, camera, lod.drawn, lod.labelled) { text, level ->
            paints.measureDp(text, level, density)
        }
        val nc = drawContext.canvas.nativeCanvas
        labels.forEach { label ->
            val ts = MgrsGridRenderer.labelTextSp(label.level) * density
            val main = paints.main
            val halo = paints.halo
            main.textSize = ts; halo.textSize = ts
            val fm = main.fontMetrics
            val px = (label.x * density).toFloat()
            val py = (label.y * density).toFloat()
            val textY = py - (fm.ascent + fm.descent) / 2f
            val off = ts * 0.07f
            if (label.rotated) { nc.save(); nc.rotate(-90f, px, py) }
            nc.drawText(label.text, px - off, textY - off, halo)
            nc.drawText(label.text, px + off, textY + off, halo)
            nc.drawText(label.text, px, textY, main)
            if (label.rotated) nc.restore()
        }
    }
}

/** Label paints, same bold dark text + white halo as before. */
private class MgrsLabelPaints {
    val main = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
        color = MgrsGridRenderer.LABEL_TEXT_COLOR
    }
    val halo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        textAlign = android.graphics.Paint.Align.CENTER
        color = 0xE6FFFFFF.toInt()
    }

    /** unrotated text box in dp, halo offset included */
    fun measureDp(text: String, level: GridType, density: Float): DoubleArray {
        val ts = MgrsGridRenderer.labelTextSp(level) * density
        main.textSize = ts
        val fm = main.fontMetrics
        val pad = ts * 0.14f
        return doubleArrayOf(
            ((main.measureText(text) + pad) / density).toDouble(),
            ((fm.descent - fm.ascent + pad) / density).toDouble(),
        )
    }
}

/** Heading-independent MGRS geometry coverage for the current viewport (the build spec's square). */
internal fun orientationInvariantGridBounds(camera: MapCamera): Wgs84Bounds {
    val b = MgrsGridBuildSpec.coverageBounds(
        camera.centerLat, camera.centerLon, camera.zoom,
        MgrsGridBuildSpec.coverageHalfSideDp(camera.viewportWidth, camera.viewportHeight),
    )
    return Wgs84Bounds(Wgs84Coordinate(b[0], b[1]), Wgs84Coordinate(b[2], b[3]))
}

/**
 * The blue you-are-here dot on the SDK-free renderer, with an accuracy circle
 * sized to the reported horizontal accuracy. Gated by the caller (User Location
 * layers toggle). Mirrors the iOS UserLocationOverlayView.
 */
@Composable
fun UserLocationCanvas(
    lat: Double?, lon: Double?, accuracyMetres: Float,
    camera: MapCamera, density: Float, modifier: Modifier = Modifier
) {
    if (lat == null || lon == null) return
    val proj = remember(camera, density) { MapProjection(camera, density) }
    Canvas(modifier.fillMaxSize()) {
        val c = proj.toScreen(lat, lon)
        val radiusPx = (accuracyMetres / proj.metresPerPx).toFloat()
        if (radiusPx > 14f * density) {
            drawCircle(Color(0x263B7BE0), radiusPx, c)
            drawCircle(Color(0x593B7BE0), radiusPx, c, style = Stroke(width = 1f * density))
        }
        // Prominent you-are-here marker: soft glow + fat white halo + blue core.
        // Sized to read clearly - it draws on top of the centre crosshair, which
        // used to swallow the old smaller dot when the map was following the user.
        drawCircle(Color(0x333B7BE0), 16f * density, c)   // soft glow
        drawCircle(Color.White, 11f * density, c)         // white halo
        drawCircle(Color(0xFF1E88E5), 7.5f * density, c)  // blue core
    }
}

/**
 * Numbered orange pins for the PDF-calibration fiduciaries on the SDK-free
 * renderer. Each fiducial's geographic position (the grid the user typed for a
 * PDF point) projects to screen and the pin's tail tip sits on that point, so
 * you can see where you've placed each correspondence while calibrating.
 * Tactical orange to pop against satellite and PDF basemaps. Replaces the old
 * native CalibrationFiduciaryMarker.
 */
@Composable
fun CalibrationFiduciariesLayer(
    fiduciaries: List<Fiduciary>,
    camera: MapCamera, density: Float, modifier: Modifier = Modifier
) {
    if (fiduciaries.isEmpty()) return
    val proj = remember(camera, density) { MapProjection(camera, density) }
    Canvas(modifier.fillMaxSize()) {
        val nc = drawContext.canvas.nativeCanvas
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFA63D.toInt() }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF1A1A1A.toInt()
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textSize = 13f * density
        }
        val r = 13f * density
        val tail = 9f * density
        fiduciaries.forEachIndexed { i, fid ->
            val p = proj.toScreen(fid.latitude, fid.longitude)
            val cx = p.x
            // disc centre sits above the point so the tail tip lands on it
            val cy = p.y - tail - r
            val path = android.graphics.Path().apply {
                moveTo(cx - 5f * density, cy + r - 1f)
                lineTo(cx + 5f * density, cy + r - 1f)
                lineTo(cx, p.y)
                close()
            }
            nc.drawPath(path, disc)
            nc.drawCircle(cx, cy, r, disc)
            nc.drawCircle(cx, cy, r, ring)
            val fm = label.fontMetrics
            nc.drawText("${i + 1}", cx, cy - (fm.ascent + fm.descent) / 2f, label)
        }
    }
}

/**
 * Waypoint symbols (military / control measures / markers) on the SDK-free
 * renderer: SymbolIconFactory drawables placed at their projected screen coord,
 * upright. Task graphics keep a fixed ground size instead, so they grow and
 * shrink with the zoom (see [TaskGraphicSizing]). Replaces the GroundOverlay +
 * native-marker path. Selection/labels/touch are layered separately.
 */
@Composable
fun WaypointSymbolsLayer(
    waypoints: List<Waypoint>,
    camera: MapCamera,
    density: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val proj = remember(camera, density) { MapProjection(camera, density) }
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        waypoints.forEach { wp ->
            val kind = wp.kind
            if (kind is WaypointKind.ControlMeasure) {
                TaskGraphicC(wp, kind.measure, proj)
                return@forEach
            }
            val baked = remember(wp.kind, wp.rotation, wp.scaleX, wp.scaleY, wp.taskColor) {
                val d = SymbolIconFactory.drawableFor(context, wp)
                val bmp: Bitmap = d.toBitmap(
                    d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1)
                )
                val vb = SymbolIconFactory.visibleBoundsFor(context, wp)
                Triple(bmp.asImageBitmap(), (vb.left + vb.right) / 2f, (vb.top + vb.bottom) / 2f)
            }
            val (img, vcx, vcy) = baked
            val screen = proj.toScreen(wp.latitude, wp.longitude)
            Image(
                bitmap = img,
                contentDescription = wp.name,
                modifier = Modifier
                    .offset { IntOffset((screen.x - vcx).roundToInt(), (screen.y - vcy).roundToInt()) }
                    .size(width = with(androidx.compose.ui.platform.LocalDensity.current) { img.width.toDp() },
                          height = with(androidx.compose.ui.platform.LocalDensity.current) { img.height.toDp() })
            )
        }
    }
}

/** Largest side of a task graphic's accessibility node; the drawing itself is unbounded. */
private const val TASK_SEMANTICS_MAX_PX = 4096f

/**
 * One task graphic at its zoom-dependent ground size. The artwork bitmap is
 * the same at every zoom and the canvas scales it, so zooming never re-renders
 * or allocates, however large the task gets on screen. The node carries the
 * task's name for TalkBack; its size is capped so a task that fills the screen
 * still lays out, and the drawing is free to extend past it.
 */
@Composable
private fun TaskGraphicC(wp: Waypoint, measure: TacticalControlMeasure, proj: MapProjection) {
    val context = LocalContext.current
    val artwork = remember(measure) { SymbolIconFactory.controlMeasureArtwork(context, measure) }
    val paint = remember(wp.taskColor) { SymbolIconFactory.controlMeasurePaint(wp.taskColor) }
    val box = TaskGraphicSizing.displaySize(wp.scaleX, wp.scaleY, proj.camera.metresPerPoint)
    val bounds = TaskGraphicSizing.screenBounds(box, SymbolIconFactory.artworkAspect(artwork), wp.rotation)
    val boxW = (box.width * proj.density).toFloat()
    val boxH = (box.height * proj.density).toFloat()
    val nodeW = (bounds.width * proj.density).toFloat().coerceIn(1f, TASK_SEMANTICS_MAX_PX)
    val nodeH = (bounds.height * proj.density).toFloat().coerceIn(1f, TASK_SEMANTICS_MAX_PX)
    val screen = proj.toScreen(wp.latitude, wp.longitude)
    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.Box(
        Modifier
            .offset { IntOffset((screen.x - nodeW / 2f).roundToInt(), (screen.y - nodeH / 2f).roundToInt()) }
            .size(with(localDensity) { nodeW.toDp() }, with(localDensity) { nodeH.toDp() })
            .semantics {
                contentDescription = wp.name
                role = Role.Image
            }
            .drawBehind {
                drawIntoCanvas { canvas ->
                    SymbolIconFactory.drawControlMeasure(
                        canvas = canvas.nativeCanvas,
                        artwork = artwork,
                        cx = size.width / 2f,
                        cy = size.height / 2f,
                        boxWidth = boxW,
                        boxHeight = boxH,
                        rotation = wp.rotation,
                        paint = paint,
                    )
                }
            }
    )
}

// MARK: - Labels + presence (SDK-free, projected via MapProjection)

private enum class ScreenAnchorC { CENTER, TOP }

private enum class ScreenHorizontalAnchorC { START, END }
private enum class ScreenVerticalAnchorC { TOP, BOTTOM }

@Composable
private fun ScreenAnchoredC(screenX: Int, screenY: Int, anchor: ScreenAnchorC = ScreenAnchorC.CENTER,
                            content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val child = measurables.firstOrNull() ?: return@Layout layout(0, 0) {}
        val placeable = child.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(0, 0) {
            val yShift = if (anchor == ScreenAnchorC.CENTER) -placeable.height / 2 else 0
            placeable.place(x = screenX - placeable.width / 2, y = screenY + yShift)
        }
    }
}

@Composable
private fun LabelPillC(text: String) {
    Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp))
}

/** Match iOS's compact Unit Sync callsign badge without changing other map labels. */
@Composable
private fun PresenceCallsignPillC(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        style = TextStyle(
            // UILabel does not add Android's legacy top/bottom font padding.
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        ),
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
private fun ScreenEdgeAnchoredC(
    screenX: Int,
    screenY: Int,
    horizontalAnchor: ScreenHorizontalAnchorC,
    verticalAnchor: ScreenVerticalAnchorC,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val child = measurables.firstOrNull() ?: return@Layout layout(0, 0) {}
        val placeable = child.measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(0, 0) {
            placeable.place(
                x = screenX - if (horizontalAnchor == ScreenHorizontalAnchorC.END) placeable.width else 0,
                y = screenY - if (verticalAnchor == ScreenVerticalAnchorC.BOTTOM) placeable.height else 0,
            )
        }
    }
}

@Composable
private fun AmplifierLabelC(field: String, text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 10.sp,
        style = TextStyle(
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        ),
        maxLines = 1,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
            // Font padding is disabled above, so this 3 dp horizontal inset
            // produces a compact chip without hidden Android top/bottom space.
            .padding(horizontal = 3.dp)
            .semantics { contentDescription = L10n.text("Unit amplifier %1\$s, %2\$s", field, text) },
    )
}

internal data class UnitAmplifierAnchors(
    val fieldFX: Float,
    val fieldFY: Float,
    val fieldMX: Float,
    val fieldMY: Float,
    val fieldTX: Float,
    val fieldTY: Float,
)

/** Approximate the APP-6 frame inside the bundled icon view boxes. HQ icons
 * reserve their lower portion for the staff, so Fields M/T must use the frame
 * bottom rather than the overall visible bottom. */
internal fun unitAmplifierAnchors(
    centreX: Float,
    centreY: Float,
    visibleWidth: Float,
    visibleHeight: Float,
    isHeadquarters: Boolean,
    density: Float,
): UnitAmplifierAnchors {
    val left = centreX - visibleWidth / 2f
    val top = centreY - visibleHeight / 2f
    val right = centreX + visibleWidth / 2f
    val frameTop = top + visibleHeight * if (isHeadquarters) 0.13f else 0.25f
    val frameBottom = top + visibleHeight * if (isHeadquarters) 0.62f else 0.97f
    val gap = 3f * density
    return UnitAmplifierAnchors(
        fieldFX = right + gap,
        fieldFY = frameTop,
        fieldMX = right + gap,
        fieldMY = frameBottom,
        fieldTX = left - gap,
        fieldTY = frameBottom,
    )
}

/** FM 1-02.2 Fields F/M/T. This layer is intentionally independent from
 * [WaypointLabelsLayer], whose unit label is the user-facing waypoint name. */
@Composable
fun UnitAmplifierLabelsLayer(
    waypoints: List<Waypoint>,
    camera: MapCamera,
    density: Float,
) {
    val context = LocalContext.current
    val projection = remember(camera, density) { MapProjection(camera, density) }
    waypoints.forEach { waypoint ->
        val military = waypoint.kind as? WaypointKind.Military ?: return@forEach
        val fieldF = waypoint.reinforcementStatus.amplifier
        val fieldM = waypoint.higherFormation?.trim().orEmpty()
        val fieldT = waypoint.uniqueIdentifier?.trim().orEmpty()
        if (fieldF.isEmpty() && fieldM.isEmpty() && fieldT.isEmpty()) return@forEach

        val screen = projection.toScreen(waypoint.latitude, waypoint.longitude)
        val visible = SymbolIconFactory.visibleBoundsFor(context, waypoint)
        val anchors = unitAmplifierAnchors(
            centreX = screen.x,
            centreY = screen.y,
            visibleWidth = visible.width().toFloat(),
            visibleHeight = visible.height().toFloat(),
            isHeadquarters = military.spec.isHeadquarters,
            density = density,
        )
        if (fieldF.isNotEmpty()) {
            ScreenEdgeAnchoredC(
                anchors.fieldFX.roundToInt(),
                anchors.fieldFY.roundToInt(),
                ScreenHorizontalAnchorC.START,
                ScreenVerticalAnchorC.BOTTOM,
            ) { AmplifierLabelC("F", fieldF) }
        }
        if (fieldM.isNotEmpty()) {
            ScreenEdgeAnchoredC(
                anchors.fieldMX.roundToInt(),
                anchors.fieldMY.roundToInt(),
                ScreenHorizontalAnchorC.START,
                ScreenVerticalAnchorC.TOP,
            ) { AmplifierLabelC("M", fieldM) }
        }
        if (fieldT.isNotEmpty()) {
            ScreenEdgeAnchoredC(
                anchors.fieldTX.roundToInt(),
                anchors.fieldTY.roundToInt(),
                ScreenHorizontalAnchorC.END,
                ScreenVerticalAnchorC.TOP,
            ) { AmplifierLabelC("T", fieldT) }
        }
    }
}

/** Waypoint name labels (unit pill below the icon, task pill centred). */
@Composable
fun WaypointLabelsLayer(
    waypoints: List<Waypoint>, camera: MapCamera, density: Float,
    unitLabelsVisible: Boolean, taskLabelsVisible: Boolean
) {
    val context = LocalContext.current
    val proj = remember(camera, density) { MapProjection(camera, density) }
    waypoints.forEach { wp ->
        val name = wp.name.trim()
        if (name.isEmpty()) return@forEach
        val isTask = wp.kind is WaypointKind.ControlMeasure
        if (if (isTask) !taskLabelsVisible else !unitLabelsVisible) return@forEach
        val s = proj.toScreen(wp.latitude, wp.longitude)
        if (isTask) {
            ScreenAnchoredC(s.x.roundToInt(), s.y.roundToInt()) { LabelPillC(name) }
        } else {
            val vb = SymbolIconFactory.visibleBoundsFor(context, wp)
            val anchor = SymbolIconFactory.anchorFor(context, wp)
            val d = SymbolIconFactory.drawableFor(context, wp)
            val iconH = d.intrinsicHeight.coerceAtLeast(1)
            val bottomY = s.y - anchor.second * iconH + vb.bottom + 3f * density
            ScreenAnchoredC(s.x.roundToInt(), bottomY.roundToInt(), ScreenAnchorC.TOP) { LabelPillC(name) }
        }
    }
}

/** Drawing name labels at each shape's label anchor. */
@Composable
fun DrawingLabelsLayer(drawings: List<DrawingFeature>, camera: MapCamera, density: Float) {
    val proj = remember(camera, density) { MapProjection(camera, density) }
    drawings.forEach { f ->
        val name = f.name.trim()
        if (name.isEmpty()) return@forEach
        val a = f.labelAnchor ?: return@forEach
        val s = proj.toScreen(a.latitude, a.longitude)
        ScreenAnchoredC(s.x.roundToInt(), s.y.roundToInt()) { LabelPillC(name) }
    }
}

internal data class PresenceMarkerPlacement(
    val iconLeftPx: Float,
    val iconTopPx: Float,
    val labelCentreXPx: Float,
    val labelTopPx: Float,
)

/**
 * Keep a presence symbol and its callsign on one screen-space anchor.
 *
 * Military bitmaps are not ordinary centred pins: they can contain transparent
 * padding, echelon marks above the frame, and (for HQ units) a long staff below
 * it. Positioning the bitmap by its full dimensions while positioning the label
 * separately makes those two pieces look like different units. Centre the
 * bitmap's visible bounds on the projection and put the label under the APP-6
 * frame; an HQ staff may continue behind/below the label, matching iOS.
 */
internal fun presenceMarkerPlacement(
    projectedX: Float,
    projectedY: Float,
    visibleLeftPx: Float,
    visibleTopPx: Float,
    visibleRightPx: Float,
    visibleBottomPx: Float,
    isHeadquarters: Boolean,
    density: Float,
): PresenceMarkerPlacement {
    val visibleWidth = (visibleRightPx - visibleLeftPx).coerceAtLeast(1f)
    val visibleHeight = (visibleBottomPx - visibleTopPx).coerceAtLeast(1f)
    val iconLeft = projectedX - (visibleLeftPx + visibleWidth / 2f)
    val iconTop = projectedY - (visibleTopPx + visibleHeight / 2f)
    val frameBottomRatio = if (isHeadquarters) 0.62f else 1f
    return PresenceMarkerPlacement(
        iconLeftPx = iconLeft,
        iconTopPx = iconTop,
        labelCentreXPx = projectedX,
        labelTopPx = iconTop + visibleTopPx + visibleHeight * frameBottomRatio + 3f * density,
    )
}

/** Presence peers: military symbol + callsign pill, projected. */
@Composable
fun PresenceLayer(peers: Map<String, PresencePeer>, camera: MapCamera, density: Float) {
    val context = LocalContext.current
    val proj = remember(camera, density) { MapProjection(camera, density) }
    var nowUptimeMs by remember { mutableLongStateOf(System.nanoTime() / 1_000_000L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            nowUptimeMs = System.nanoTime() / 1_000_000L
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        peers.values.forEach { peer ->
            val presentation = presenceMarkerPresentation(peer, nowUptimeMs)
            val wp = remember(peer.clientId, peer.affiliation, peer.echelon, peer.function, peer.isHQ,
                              peer.lat, peer.lon, peer.callsign) {
                val spec = MilitarySymbolSpec(
                    // Garbled/unknown affiliation renders UNKNOWN, not FRIEND -
                    // an unidentified contact must never look friendly.
                    affiliation = SymbolAffiliation.entries.firstOrNull { it.name.equals(peer.affiliation, true) } ?: SymbolAffiliation.UNKNOWN,
                    echelon = SymbolEchelon.entries.firstOrNull { it.name.equals(peer.echelon, true) } ?: SymbolEchelon.TEAM,
                    function = SymbolFunction.entries.firstOrNull { it.name.equals(peer.function, true) } ?: SymbolFunction.INFANTRY,
                    isHeadquarters = peer.isHQ)
                Waypoint(id = peer.clientId, name = peer.callsign, latitude = peer.lat, longitude = peer.lon,
                    kind = WaypointKind.Military(spec))
            }
            val artwork = remember(wp.kind) {
                val drawable = SymbolIconFactory.drawableFor(context, wp)
                val image = drawable.toBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                ).asImageBitmap()
                image to SymbolIconFactory.visibleBoundsFor(context, wp)
            }
            val (img, visible) = artwork
            val s = proj.toScreen(peer.lat, peer.lon)
            val placement = presenceMarkerPlacement(
                projectedX = s.x,
                projectedY = s.y,
                visibleLeftPx = visible.left.toFloat(),
                visibleTopPx = visible.top.toFloat(),
                visibleRightPx = visible.right.toFloat(),
                visibleBottomPx = visible.bottom.toFloat(),
                isHeadquarters = peer.isHQ,
                density = density,
            )
            Image(bitmap = img, contentDescription = presentation.accessibilityLabel,
                modifier = Modifier.offset {
                    IntOffset(placement.iconLeftPx.roundToInt(), placement.iconTopPx.roundToInt())
                }
                    .alpha(if (peer.isStale) 0.55f else 1f)
                    .size(with(androidx.compose.ui.platform.LocalDensity.current) { img.width.toDp() },
                          with(androidx.compose.ui.platform.LocalDensity.current) { img.height.toDp() }))
            if (presentation.visibleLabel.isNotBlank()) {
                ScreenAnchoredC(
                    placement.labelCentreXPx.roundToInt(),
                    placement.labelTopPx.roundToInt(),
                    ScreenAnchorC.TOP,
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.alpha(if (peer.isStale) 0.55f else 1f)
                    ) { PresenceCallsignPillC(presentation.visibleLabel) }
                }
            }
        }
    }
}

/** Imported PDF/GeoPDF ground overlay on the SDK-free renderer: renders the page
 *  bitmap once and warps it to its geo corners with a poly matrix, so it rides
 *  pan/zoom/rotate. The bitmap corners are mapped back to raw page space through
 *  the page geometry (crop origin, /Rotate, pdfium's int size) and placed with
 *  the placement's best-fit lon/lat affine. That's a stopgap until the plan s2
 *  tile renderer: a couple of metres on a 1:25k UTM sheet, not the km the old
 *  fits could be off. Uncalibrated sheets ride their provisional placement. */
@Composable
fun PdfGroundLayer(source: PdfMapSource, camera: MapCamera, density: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val proj = remember(camera, density) { MapProjection(camera, density) }
    val bitmapState = remember(source.uri) { mutableStateOf<Bitmap?>(null) }
    var bmp by bitmapState
    val active = remember(source.uri) { java.util.concurrent.atomic.AtomicBoolean(true) }
    DisposableEffect(source.uri) {
        active.set(true)
        onDispose {
            active.set(false)
            bitmapState.value?.takeUnless(Bitmap::isRecycled)?.recycle()
            bitmapState.value = null
        }
    }
    LaunchedEffect(source.uri) {
        var rendered: Bitmap? = null
        try {
            rendered = withContext(Dispatchers.IO) {
                PdfPageRenderer.renderFirstPage(context, source.uri).bitmap.also {
                    // Preserve ownership if prompt cancellation wins the race
                    // while dispatching the completed render back to Main.
                    rendered = it
                }
            }
            if (rendered != null && active.get()) {
                bitmapState.value?.takeUnless(Bitmap::isRecycled)?.recycle()
                bitmapState.value = rendered
                rendered = null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The surrounding map remains usable if a corrupt PDF cannot render.
        } finally {
            rendered?.takeUnless(Bitmap::isRecycled)?.recycle()
        }
    }
    val image = bmp ?: return
    val display = source.placement?.bestFitLatLonAffine ?: return
    // raw page points under the bitmap's TL, TR, BR, BL
    val rawCorners = remember(source.geometry) { source.geometry.bitmapCornersRaw() }

    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = L10n.text("PDF map rendered: %1\$s", source.displayName) }
    ) {
        val corners: List<Pair<Double, Double>> = rawCorners.map { p ->
            display.apply(p.x, p.y).let { it.latitude to it.longitude }
        }
        val dst = FloatArray(8)
        corners.forEachIndexed { i, (lat, lon) ->
            val s = proj.toScreen(lat, lon); dst[i * 2] = s.x; dst[i * 2 + 1] = s.y
        }
        val w = image.width.toFloat(); val h = image.height.toFloat()
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val m = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        drawContext.canvas.nativeCanvas.drawBitmap(image, m, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}

/**
 * Terrain-heatmap ground overlay on the SDK-free renderer. Draws the coloured
 * DEM bitmap from [TerrainHeatmapService] stretched across its sampled [bounds],
 * projected through the camera - same Matrix.setPolyToPoly trick as
 * [PdfGroundLayer]. Bitmap origin is the NW corner (row 0 = north, col 0 = west).
 * Replaces the old GroundOverlay.
 */
@Composable
fun HeatmapGroundLayer(
    bitmap: Bitmap, bounds: Wgs84Bounds,
    camera: MapCamera, density: Float, modifier: Modifier = Modifier
) {
    val proj = remember(camera, density) { MapProjection(camera, density) }
    Canvas(modifier.fillMaxSize()) {
        val corners = listOf(
            bounds.northeast.latitude to bounds.southwest.longitude,  // NW = top-left
            bounds.northeast.latitude to bounds.northeast.longitude,  // NE = top-right
            bounds.southwest.latitude to bounds.northeast.longitude,  // SE = bottom-right
            bounds.southwest.latitude to bounds.southwest.longitude   // SW = bottom-left
        )
        val dst = FloatArray(8)
        corners.forEachIndexed { i, (lat, lon) ->
            val s = proj.toScreen(lat, lon); dst[i * 2] = s.x; dst[i * 2 + 1] = s.y
        }
        val w = bitmap.width.toFloat(); val h = bitmap.height.toFloat()
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val m = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
        drawContext.canvas.nativeCanvas.drawBitmap(bitmap, m, Paint(Paint.FILTER_BITMAP_FLAG))
    }
}
