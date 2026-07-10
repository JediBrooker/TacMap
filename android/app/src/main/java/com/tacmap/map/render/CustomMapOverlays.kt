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
import androidx.compose.ui.unit.IntOffset
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingStrokeStyle
import com.tacmap.drawings.LineGraphic
import com.tacmap.map.SymbolIconFactory
import com.tacmap.mgrs.MgrsGridRenderer
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
            drawLine(ink, a, b, strokeWidth = MgrsGridRenderer.lineWidthDp(seg.type) * density,
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
        drawCircle(Color.White, 9f * density, c)
        drawCircle(Color(0xFF1E88E5), 6.5f * density, c)
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
