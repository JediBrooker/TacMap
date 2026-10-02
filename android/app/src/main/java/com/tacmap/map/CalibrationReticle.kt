package com.tacmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Calibration crosshair (contract s7.6): 1 dp white hairlines with a 1 dp black
 * halo either side and a 6 dp open gap in the middle. No glow or ring, so the
 * printed intersection under it stays visible.
 */
@Composable
internal fun CalibrationReticle() {
    val density = LocalDensity.current
    val line = with(density) { 1.dp.toPx() }
    val halo = with(density) { 3.dp.toPx() }
    val gap = with(density) { 3.dp.toPx() }
    val arm = with(density) { 64.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val segments = listOf(
            Offset(cx - arm, cy) to Offset(cx - gap, cy),
            Offset(cx + gap, cy) to Offset(cx + arm, cy),
            Offset(cx, cy - arm) to Offset(cx, cy - gap),
            Offset(cx, cy + gap) to Offset(cx, cy + arm),
        )
        segments.forEach { (a, b) -> drawLine(Color.Black.copy(alpha = 0.85f), a, b, halo) }
        segments.forEach { (a, b) -> drawLine(Color.White, a, b, line) }
    }
}
