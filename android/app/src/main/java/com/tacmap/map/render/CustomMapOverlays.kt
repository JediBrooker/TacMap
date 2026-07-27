package com.tacmap.map.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.drawings.LineGraphic
import com.tacmap.map.SymbolIconFactory
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.Fiduciary
import com.tacmap.calibration.PdfMapSource
import com.tacmap.calibration.Wgs84Bounds
import com.tacmap.calibration.PdfPageRenderer
import com.tacmap.mgrs.MgrsGridRenderer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.WaypointKind
import com.tacmap.sync.PresencePeer
import com.tacmap.waypoints.Waypoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
    val fill = Color(feature.strokeColor and 0x00FFFFFF or 0x33000000)
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
 * MGRS grid (lines + labels) on the SDK-free renderer, drawn from
 * MgrsGridRenderer projected through MapCamera. Replaces the TileOverlay grid,
 * so the lines stay correct at every zoom (the tile-provider grid went awry
 * zoomed out). Labels are deduped to one per line, same as the shared fix.
 */
@Composable
fun MgrsGridCanvas(camera: MapCamera, density: Float, modifier: Modifier = Modifier) {
    if (camera.viewportWidth <= 0.0 || camera.viewportHeight <= 0.0) return
    val proj = remember(camera, density) { MapProjection(camera, density) }

    val built = remember(camera) {
        val corners = listOf(
            0.0 to 0.0, camera.viewportWidth to 0.0,
            0.0 to camera.viewportHeight, camera.viewportWidth to camera.viewportHeight
        ).map { camera.coordinate(it.first, it.second) }
        val lats = corners.map { it.first }
        val lons = corners.map { it.second }
        val widthPx = (camera.viewportWidth * density).roundToInt().coerceAtLeast(1)
        MgrsGridRenderer.build(
            minLat = lats.min(), minLng = lons.min(),
            maxLat = lats.max(), maxLng = lons.max(),
            mapWidthPx = widthPx
        )
    }

    Canvas(modifier.fillMaxSize()) {
        val (segments, labels) = built
        val ink = Color(MgrsGridRenderer.INK_COLOR)
        segments.forEach { seg ->
            val a = proj.toScreen(seg.start.latitude, seg.start.longitude)
            val b = proj.toScreen(seg.end.latitude, seg.end.longitude)
            drawLine(ink, a, b, strokeWidth = MgrsGridRenderer.lineWidthPx(seg.type, density),
                cap = StrokeCap.Round)
        }

        // Declutter to one label per grid line (bucket by perpendicular screen
        // axis, keep nearest the top/left margin) - matches the MgrsGridLabelsOverlay fix.
        val bucketPx = 55f
        val kept = HashMap<String, Pair<MgrsGridRenderer.LabelMark, Offset>>()
        labels.forEach { mark ->
            val p = proj.toScreen(mark.lat, mark.lng)
            val b = Math.round((if (mark.isVertical) p.x else p.y) / bucketPx)
            val key = "${if (mark.isVertical) "v" else "h"}|$b|${mark.text}"
            val ex = kept[key]
            val margin = if (mark.isVertical) p.y else p.x
            if (ex == null || margin < (if (mark.isVertical) ex.second.y else ex.second.x)) {
                kept[key] = mark to p
            }
        }
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
        val nc = drawContext.canvas.nativeCanvas
        kept.values.forEach { (mark, p) ->
            val ts = MgrsGridRenderer.labelTextSp(mark.type) * density
            main.textSize = ts; halo.textSize = ts
            val fm = main.fontMetrics
            val textY = p.y - (fm.ascent + fm.descent) / 2f
            val off = ts * 0.07f
            if (mark.isVertical) { nc.save(); nc.rotate(-90f, p.x, p.y) }
            nc.drawText(mark.text, p.x - off, textY - off, halo)
            nc.drawText(mark.text, p.x + off, textY + off, halo)
            nc.drawText(mark.text, p.x, textY, main)
            if (mark.isVertical) nc.restore()
        }
    }
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
 * upright. Replaces the GroundOverlay + native-marker path. Selection/labels/
 * touch are layered separately.
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

// MARK: - Labels + presence (SDK-free, projected via MapProjection)

private enum class ScreenAnchorC { CENTER, TOP }

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

/** Presence peers: military symbol + callsign pill, projected. */
@Composable
fun PresenceLayer(peers: Map<String, PresencePeer>, camera: MapCamera, density: Float) {
    val context = LocalContext.current
    val proj = remember(camera, density) { MapProjection(camera, density) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        peers.values.forEach { peer ->
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
            val img = remember(wp.kind) {
                SymbolIconFactory.drawableFor(context, wp).toBitmap(
                    SymbolIconFactory.drawableFor(context, wp).intrinsicWidth.coerceAtLeast(1),
                    SymbolIconFactory.drawableFor(context, wp).intrinsicHeight.coerceAtLeast(1)).asImageBitmap()
            }
            val s = proj.toScreen(peer.lat, peer.lon)
            Image(bitmap = img, contentDescription = peer.callsign,
                modifier = Modifier.offset { IntOffset((s.x - img.width / 2).roundToInt(), (s.y - img.height / 2).roundToInt()) }
                    .size(with(androidx.compose.ui.platform.LocalDensity.current) { img.width.toDp() },
                          with(androidx.compose.ui.platform.LocalDensity.current) { img.height.toDp() }))
            if (peer.callsign.isNotBlank()) {
                ScreenAnchoredC(s.x.roundToInt(), (s.y + img.height / 2 + 2).roundToInt(), ScreenAnchorC.TOP) { LabelPillC(peer.callsign) }
            }
        }
    }
}

/** Imported PDF/GeoPDF ground overlay on the SDK-free renderer: renders the page
 *  bitmap once and warps it to its projected geo corners with a poly matrix, so
 *  it rides pan/zoom/rotate and lines up with the MGRS grid (same projection +
 *  affine that fixed the SDK path). Non-georeferenced PDFs use the axis-aligned
 *  bounds. */
@Composable
fun PdfGroundLayer(source: PdfMapSource, camera: MapCamera, density: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val proj = remember(camera, density) { MapProjection(camera, density) }
    var bmp by remember(source.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(source.uri) {
        bmp = runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                PdfPageRenderer.renderFirstPage(context, source.uri).bitmap
            }
        }.getOrNull()
    }
    val image = bmp ?: return
    val transform = (source.calibration as? Calibration.Fiduciaries)?.transform
        ?: (source.calibration as? Calibration.Parsed)?.transform
    val pageInfo = source.pageInfo

    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "PDF map rendered: ${source.displayName}" }
    ) {
        // Corners in lat/lon: georeferenced -> the affine page corners (bitmap top
        // = page-top = PDF maxY), else the coverage bounds box.
        val corners: List<Pair<Double, Double>> = if (transform != null && pageInfo != null) {
            val pw = pageInfo.pageWidth.toDouble(); val ph = pageInfo.pageHeight.toDouble()
            listOf(
                transform.apply(0.0, ph).let { it.latitude to it.longitude },   // top-left
                transform.apply(pw, ph).let { it.latitude to it.longitude },    // top-right
                transform.apply(pw, 0.0).let { it.latitude to it.longitude },   // bottom-right
                transform.apply(0.0, 0.0).let { it.latitude to it.longitude }   // bottom-left
            )
        } else {
            val b = source.coverage ?: return@Canvas
            listOf(
                b.northeast.latitude to b.southwest.longitude,  // TL
                b.northeast.latitude to b.northeast.longitude,  // TR
                b.southwest.latitude to b.northeast.longitude,  // BR
                b.southwest.latitude to b.southwest.longitude   // BL
            )
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
