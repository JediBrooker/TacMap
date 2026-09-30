package com.tacmap.map.render

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.tacmap.map.DrawingDefaults

private val MeasureLine = Color(0xFFFFA500)
private val MeasureDot = Color(0xFFFFA62E)

/**
 * The measure tool's dashed route plus a dot on every tapped point, including
 * the first one before there is a line. Drawn apart from the drawings layer so
 * Layers → Drawings off doesn't hide an active measurement. Mirrors iOS, which
 * adds the measure dots to the decorations overlay and the line to the vector
 * shapes regardless of drawing visibility.
 */
@Composable
fun MeasureOverlay(
    points: List<Pair<Double, Double>>,
    projection: MapProjection,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    Canvas(modifier.fillMaxSize()) {
        val screen = points.map { (lat, lng) -> projection.toScreen(lat, lng) }
        val d = projection.density
        if (screen.size >= 2) {
            // Same weight and dash the measure draft used before it moved here.
            val width = DrawingDefaults.rendererStrokeWidth(d) + 2f
            val line = Path().apply {
                moveTo(screen[0].x, screen[0].y)
                screen.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(
                line,
                MeasureLine,
                style = Stroke(
                    width = width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(width * 3f, width * 2f), 0f),
                ),
            )
        }
        screen.forEach { centre ->
            drawCircle(MeasureDot, radius = 5f * d, center = centre)
            drawCircle(Color.White, radius = 5f * d, center = centre, style = Stroke(width = 1.5f * d))
        }
    }
}
