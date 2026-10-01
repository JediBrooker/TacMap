package com.tacmap.map.render

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import com.tacmap.calibration.PagePoint
import com.tacmap.calibration.PdfGeoreference
import com.tacmap.calibration.Wgs84Coordinate
import kotlin.math.hypot

/** one point as the markers layer needs it */
internal data class CalibrationMarker(
    val id: String,
    val number: Int,
    val page: PagePoint,
    /** where the typed reference sits (WGS84), for the residual line */
    val typed: Wgs84Coordinate?,
    val flagged: Boolean,
)

internal data class CalibrationMarkerModel(
    val markers: List<CalibrationMarker>,
    val selectedId: String?,
    val pendingPage: PagePoint?,
    val movingId: String?,
    /** n >= 3 and the shown georef is the fit */
    val showResiduals: Boolean,
)

/**
 * Contract s7.5. Every marker is drawn at shownGeoref.toWGS84(point.page), never
 * at the typed position (D2-13): a "+" right on the point with the number badge up
 * and right on a leader so it never covers the intersection being checked.
 */
@Composable
internal fun CalibrationMarkersLayer(
    model: CalibrationMarkerModel?,
    georef: PdfGeoreference?,
    generation: Long,
    camera: MapCamera,
    density: Float,
    modifier: Modifier = Modifier,
) {
    if (model == null || georef == null) return
    // ground positions only change with the georef or the points, not every frame
    val ground = remember(generation, model) {
        model.markers.associate { m -> m.id to georef.toWGS84(m.page.x, m.page.y) }
    }
    val pendingGround = remember(generation, model.pendingPage) {
        model.pendingPage?.let { georef.toWGS84(it.x, it.y) }
    }
    val proj = remember(camera, density) { MapProjection(camera, density) }
    Canvas(modifier.fillMaxSize()) {
        val nc = drawContext.canvas.nativeCanvas
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f * density; strokeCap = Paint.Cap.ROUND
        }
        val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.2f * density; strokeCap = Paint.Cap.ROUND
        }
        val residual = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFF6B5A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
            pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
        }
        val leader = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f * density
        }
        val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; textSize = 12f * density
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
        }
        val arm = 9f * density
        val gap = 2f * density

        fun plus(x: Float, y: Float, alpha: Int = 255) {
            halo.alpha = alpha; cross.alpha = alpha
            for (p in listOf(halo, cross)) {
                nc.drawLine(x - arm, y, x - gap, y, p); nc.drawLine(x + gap, y, x + arm, y, p)
                nc.drawLine(x, y - arm, x, y - gap, p); nc.drawLine(x, y + gap, x, y + arm, p)
            }
            halo.alpha = 255; cross.alpha = 255
        }

        model.markers.forEach { m ->
            val w = ground[m.id] ?: return@forEach
            val s = proj.toScreen(w.latitude, w.longitude)
            if (model.showResiduals) {
                m.typed?.let { t ->
                    val ts = proj.toScreen(t.latitude, t.longitude)
                    // s7.5: only when it's at least 6 dp long, shorter is just noise on the cross
                    if (hypot(ts.x - s.x, ts.y - s.y) >= 6f * density) nc.drawLine(s.x, s.y, ts.x, ts.y, residual)
                }
            }
            val moving = m.id == model.movingId
            plus(s.x, s.y, if (moving) 110 else 255)
            if (m.id == model.selectedId) nc.drawCircle(s.x, s.y, 14f * density, ring)
            val bx = s.x + 22f * density
            val by = s.y - 22f * density
            nc.drawLine(s.x + 5f * density, s.y - 5f * density, bx - 7f * density, by + 7f * density, leader)
            badgeFill.color = if (m.flagged) 0xFFE53935.toInt() else 0xFFFFA63D.toInt()
            val text = if (m.flagged) "${m.number}!" else m.number.toString()
            val r = if (text.length > 2) 13f * density else 11f * density
            nc.drawCircle(bx, by, r, badgeFill)
            nc.drawCircle(bx, by, r, badgeRing)
            label.color = if (m.flagged) 0xFFFFFFFF.toInt() else 0xFF1A1A1A.toInt()
            val fm = label.fontMetrics
            nc.drawText(text, bx, by - (fm.ascent + fm.descent) / 2f, label)
        }
        pendingGround?.let { w ->
            val s = proj.toScreen(w.latitude, w.longitude)
            val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFA63D.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * density
                pathEffect = DashPathEffect(floatArrayOf(5f * density, 4f * density), 0f)
            }
            plus(s.x, s.y)
            nc.drawCircle(s.x, s.y, 16f * density, dashed)
        }
    }
}

/** marker hit test, contract s7.5: within 24 dp, nearest wins, a tap never places anything */
internal fun hitCalibrationMarker(
    tapPx: androidx.compose.ui.geometry.Offset,
    model: CalibrationMarkerModel?,
    georef: PdfGeoreference?,
    camera: MapCamera,
    density: Float,
    radiusDp: Double = com.tacmap.calibration.ImportLimits.MARKER_HIT_RADIUS_DP,
): String? {
    if (model == null || georef == null) return null
    val proj = MapProjection(camera, density)
    val limit = radiusDp * density
    return model.markers.mapNotNull { m ->
        val w = georef.toWGS84(m.page.x, m.page.y) ?: return@mapNotNull null
        val s = proj.toScreen(w.latitude, w.longitude)
        val d = hypot((s.x - tapPx.x).toDouble(), (s.y - tapPx.y).toDouble())
        if (d <= limit) m.id to d else null
    }.minByOrNull { it.second }?.first
}
