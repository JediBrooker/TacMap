package com.tacticalmaps.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.calibration.Datum

// HUD chrome (round buttons, mils compass) + PDF-calibration UI extracted
// verbatim from MapScreen.kt. The composables MapScreen calls are `internal`;
// calibrationStatus stays `private`. Behaviour is unchanged.

@Composable
internal fun CircleHudButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = null, tint = Color.White,
                 modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun CompassChip(mapOrientationDegrees: Double, onTap: () -> Unit = {}) {
    /// `mapOrientationDegrees` is the camera bearing — the compass
    /// bearing of where screen-up points (0 = north up, 90 = east up).
    /// The displayed mils reading matches that bearing; the needle
    /// rotates counter to it so it keeps pointing at true north as
    /// the map turns.
    val screenUpBearingDegrees = normalizedDegrees(mapOrientationDegrees)
    val mils = ((screenUpBearingDegrees * (6400.0 / 360.0)).toInt()) % 6400
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .clickable { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .size(34.dp)
                .align(Alignment.TopCenter)
        ) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val needleHalfLength = size.height * 0.34f
            val needleWing = size.width * 0.13f
            val needleWaist = size.height * 0.04f
            val strokeWidth = size.width * 0.032f
            rotate(degrees = -screenUpBearingDegrees.toFloat(), pivot = center) {
                val northNeedle = Path().apply {
                    moveTo(cx, cy - needleHalfLength)
                    lineTo(cx - needleWing, cy + needleWaist)
                    lineTo(cx, cy - needleWaist)
                    lineTo(cx + needleWing, cy + needleWaist)
                    close()
                }
                val southNeedle = Path().apply {
                    moveTo(cx, cy + needleHalfLength)
                    lineTo(cx - needleWing, cy - needleWaist)
                    lineTo(cx, cy + needleWaist)
                    lineTo(cx + needleWing, cy - needleWaist)
                    close()
                }
                drawLine(
                    Color.White,
                    start = center.copy(y = cy - needleHalfLength),
                    end = center.copy(y = cy + needleHalfLength),
                    strokeWidth = strokeWidth
                )
                drawPath(northNeedle, Color(0xFFFF3B30))
                drawPath(southNeedle, Color.White)
            }
            drawCircle(
                Color.White.copy(alpha = 0.72f),
                radius = size.width * 0.04f,
                center = center,
                style = Stroke(width = strokeWidth * 0.85f)
            )
        }
        Text(
            "%04d".format(mils),
            color = Color(0xFF8CF28C),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
        )
    }
}

/** Undo / redo button pair — appears below the compass chip. */
@Composable
internal fun UndoRedoButtons(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    AnimatedVisibility(visible = canUndo || canRedo) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            UndoRedoChip(
                icon = Icons.Default.Undo,
                enabled = canUndo,
                contentDescription = "Undo",
                onClick = onUndo
            )
            UndoRedoChip(
                icon = Icons.Default.Redo,
                enabled = canRedo,
                contentDescription = "Redo",
                onClick = onRedo
            )
        }
    }
}

/** Lock toggle — freezes ALL graphics (symbols + drawings) so no gesture
 *  can move them. Sits below the undo/redo buttons; always visible. Turns
 *  amber when engaged. */
@Composable
internal fun LockButton(
    locked: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (locked) Color(0xCCEF6C00) else Color(0xCC000000))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (locked) Icons.Default.Lock else Icons.Default.LockOpen,
            contentDescription = if (locked) "Graphics locked — tap to unlock" else "Lock graphics in place",
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun UndoRedoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
internal fun CalibrationBar(
    fiduciaryCount: Int,
    canFinish: Boolean,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Calibrating PDF",
                color = Color(0xFFFFA000),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                calibrationStatus(fiduciaryCount),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 11.sp
            )
        }
        TextButton(onClick = onCancel) {
            Text("Cancel", color = Color.White)
        }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFA000),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF4A4A4A),
                disabledContentColor = Color.White.copy(alpha = 0.45f)
            )
        ) {
            Text("Finish", fontWeight = FontWeight.Bold)
        }
    }
}

private fun calibrationStatus(fiduciaryCount: Int): String =
    when {
        fiduciaryCount == 0 -> "Tap a known point on the PDF, then enter its MGRS."
        fiduciaryCount < 3 -> "$fiduciaryCount/3 fiduciaries placed. Add another known point."
        else -> "$fiduciaryCount fiduciaries placed. Finish or add more for accuracy."
    }

@Composable
internal fun CalibrationInputDialog(
    point: PendingCalibrationTap,
    fiduciaryNumber: Int,
    datum: Datum,
    onDatumChange: (Datum) -> Unit,
    onDismiss: () -> Unit,
    onSave: (mgrs: String, label: String) -> Boolean
) {
    var mgrs by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fiduciary #$fiduciaryNumber") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "PDF point: ${point.pdfX.toInt()}, ${point.pdfY.toInt()}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
                OutlinedTextField(
                    value = mgrs,
                    onValueChange = {
                        mgrs = it
                        error = null
                    },
                    label = { Text("MGRS") },
                    placeholder = { Text("56HLH 12345 67890") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    placeholder = { Text("Grid intersection") },
                    singleLine = true
                )
                Text("Sheet datum", fontSize = 12.sp, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Datum.entries.forEach { d ->
                        val selected = d == datum
                        Text(
                            d.displayName,
                            fontSize = 11.sp,
                            color = if (selected) Color.Black else Color.White,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Color(0xFFFFA000) else Color(0x33FFFFFF))
                                .clickable { onDatumChange(d) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                error?.let {
                    Text(it, color = Color(0xFFE53935), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val saved = onSave(mgrs, label)
                    if (!saved) {
                        error = "Couldn't parse MGRS. Try a full grid reference."
                    }
                },
                enabled = mgrs.trim().isNotEmpty()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
