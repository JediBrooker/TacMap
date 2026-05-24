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
    val ringRadius = with(density) { 12.dp.toPx() }

    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width  / 2f
        val cy = size.height / 2f
        val colour = Color.White.copy(alpha = 0.85f)
        drawLine(colour, Offset(cx, 0f),       Offset(cx, size.height), hairline)
        drawLine(colour, Offset(0f, cy),       Offset(size.width, cy),  hairline)
        drawCircle(colour, ringRadius, Offset(cx, cy), style = Stroke(width = ringStroke))
    }
}
