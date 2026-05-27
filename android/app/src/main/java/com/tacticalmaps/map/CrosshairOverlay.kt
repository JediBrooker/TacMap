package com.tacticalmaps.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun CrosshairOverlay() {
    val density = LocalDensity.current
    val hairline = with(density) { 1.dp.toPx() }
    val ringStroke = with(density) { 1.5.dp.toPx() }
    val glowStroke = with(density) { 5.dp.toPx() }
    val ringRadius = with(density) { 12.dp.toPx() }

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width  / 2f
        val cy = size.height / 2f
        val orange = Color(0xFFF2A24A)
        val glow = orange.copy(alpha = 0.28f)
        val lineStartVertical = Offset(cx, 0f)
        val lineEndVertical = Offset(cx, size.height)
        val lineStartHorizontal = Offset(0f, cy)
        val lineEndHorizontal = Offset(size.width, cy)
        val centre = Offset(cx, cy)

        drawLine(glow, lineStartVertical, lineEndVertical, glowStroke)
        drawLine(glow, lineStartHorizontal, lineEndHorizontal, glowStroke)
        drawCircle(glow, ringRadius, centre, style = Stroke(width = glowStroke))

        drawLine(orange.copy(alpha = 0.92f), lineStartVertical, lineEndVertical, hairline)
        drawLine(orange.copy(alpha = 0.92f), lineStartHorizontal, lineEndHorizontal, hairline)
        drawCircle(orange.copy(alpha = 0.95f), ringRadius, centre, style = Stroke(width = ringStroke))
    }
}
