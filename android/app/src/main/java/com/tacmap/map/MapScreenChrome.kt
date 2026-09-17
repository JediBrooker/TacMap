package com.tacmap.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.calibration.Datum
import com.tacmap.models.HeadingNorthReference
import com.tacmap.settings.MapOrientationMode
import kotlin.math.roundToInt

// HUD chrome (round buttons, mils compass) + PDF-calibration UI.
// Extracted from MapScreen.kt. Behaviour unchanged.

@Composable
internal fun CircleHudButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
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
            Icon(icon, contentDescription = contentDescription, tint = Color.White,
                 modifier = Modifier.size(20.dp))
        }
    }
}

/** TacMap Chat launcher with metadata-only unread state and accessible count. */
@Composable
internal fun TacMapChatHudButton(unreadCount: Int, onClick: () -> Unit) {
    val count = unreadCount.coerceAtLeast(0)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClickLabel = "Open TacMap Chat", onClick = onClick)
            .semantics { contentDescription = tacMapChatContentDescription(count) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        tacMapChatUnreadBadgeText(count)?.let { badgeText ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .height(18.dp)
                    .widthIn(min = 18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color(0xFFD32F2F))
                    .border(1.dp, Color.White, RoundedCornerShape(9.dp))
                    .padding(horizontal = 3.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    badgeText,
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

internal fun tacMapChatUnreadBadgeText(count: Int): String? = when {
    count <= 0 -> null
    count > 99 -> "99+"
    else -> count.toString()
}

internal fun tacMapChatContentDescription(count: Int): String = when (count) {
    1 -> "TacMap Chat, 1 unread message"
    in 2..Int.MAX_VALUE -> "TacMap Chat, $count unread messages"
    else -> "TacMap Chat"
}

/** One-tap entry to the symbol builder at the current map crosshair. */
@Composable
internal fun QuickAddSymbolButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xFFE99020))
            .clickable(onClickLabel = "Add symbol at crosshair", onClick = onClick)
            .semantics { contentDescription = "Add symbol at crosshair" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Contains the rapidly changing camera-bearing and north-reference flows so a
 * compass update does not recompose the entire map screen.
 */
@Composable
internal fun MapCompassChip(
    vm: MapViewModel,
    orientationMode: MapOrientationMode,
    onHeadingUnavailable: () -> Unit,
) {
    val mapBearingDegrees by vm.mapBearingDegrees.collectAsState()
    val northReference by vm.headingService.headingNorthReference.collectAsState()
    CompassChip(
        mapOrientationDegrees = mapBearingDegrees,
        orientationMode = orientationMode,
        headingAvailable = vm.headingService.isHeadingAvailable,
        northReference = northReference,
        onTap = {
            if (vm.onCompassTapped() == CompassTapAction.HEADING_UNAVAILABLE) {
                onHeadingUnavailable()
            }
        },
    )
}

@Composable
internal fun CompassChip(
    mapOrientationDegrees: Double,
    orientationMode: MapOrientationMode,
    headingAvailable: Boolean,
    northReference: HeadingNorthReference?,
    onTap: () -> Unit = {},
) {
    /// mapOrientationDegrees = camera bearing (0 = north up, 90 = east up).
    /// Mils reading matches that bearing. The north marker rotates counter to it so
    /// it points at the active true- or magnetic-north reference as the map turns.
    val screenUpBearingDegrees = normalizedDegrees(mapOrientationDegrees)
    val mils = mapHeadingMils(screenUpBearingDegrees)
    val referenceDescription = when {
        orientationMode != MapOrientationMode.HEADING_UP -> "true north"
        northReference != null -> northReference.accessibilityLabel
        else -> "north reference pending"
    }
    val referenceSuffix = if (orientationMode == MapOrientationMode.HEADING_UP) {
        northReference?.displaySuffix ?: "?"
    } else {
        "T"
    }
    val activeBorderColor = if (northReference == HeadingNorthReference.MAGNETIC_NORTH) {
        Color(0xFFFFB74D)
    } else {
        Color(0xFF42A5F5)
    }
    val tapLabel = when (compassTapAction(
        mode = orientationMode,
        currentHeading = screenUpBearingDegrees,
        headingAvailable = headingAvailable,
    )) {
        CompassTapAction.RESET_NORTH -> "Reset map to north"
        CompassTapAction.ENABLE_HEADING_UP -> "Switch to Heading Up"
        CompassTapAction.DISABLE_HEADING_UP -> "Switch to North Up"
        CompassTapAction.HEADING_UNAVAILABLE -> "Explain Heading Up availability"
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.82f))
            .border(
                width = if (orientationMode == MapOrientationMode.HEADING_UP) 2.dp else 1.dp,
                color = if (orientationMode == MapOrientationMode.HEADING_UP) {
                    activeBorderColor.copy(alpha = 0.95f)
                } else {
                    Color.White.copy(alpha = 0.14f)
                },
                shape = CircleShape,
            )
            .clickable(onClickLabel = tapLabel) { onTap() }
            .semantics {
                contentDescription = buildString {
                    append(orientationMode.displayName)
                    append(", $referenceDescription")
                    append(", compass, $mils mils")
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // Match iOS: a small red triangle and white N orbit the upper dial as
        // one north marker. The mils readout below remains upright.
        Box(
            Modifier
                .size(34.dp)
                .align(Alignment.TopCenter)
                .rotate(-screenUpBearingDegrees.toFloat())
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val centreX = size.width / 2f
                val top = 3.dp.toPx()
                val halfWidth = 4.dp.toPx()
                val triangleHeight = 7.dp.toPx()
                val northTick = Path().apply {
                    moveTo(centreX, top)
                    lineTo(centreX - halfWidth, top + triangleHeight)
                    lineTo(centreX + halfWidth, top + triangleHeight)
                    close()
                }
                drawPath(northTick, Color(0xFFFF3B30))
            }

            Text(
                "N",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 7.dp),
            )
        }

        Box(
            Modifier
                .width(30.8.dp)
                .height(0.5.dp)
                .offset(y = 4.dp)
                .background(Color.White.copy(alpha = 0.10f))
        )

        Text(
            "%04d%s".format(mils, referenceSuffix),
            color = Color(0xFF8CF28C),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp)
        )
    }
}

/** NATO mils for a clockwise map bearing, rounded to the nearest mil. */
internal fun mapHeadingMils(degrees: Double): Int {
    if (!degrees.isFinite()) return 0
    return (normalizedDegrees(degrees) * (6400.0 / 360.0)).roundToInt() % 6400
}

/** Undo/redo buttons, below the compass chip. */
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
                icon = Icons.AutoMirrored.Filled.Undo,
                enabled = canUndo,
                contentDescription = "Undo",
                onClick = onUndo
            )
            UndoRedoChip(
                icon = Icons.AutoMirrored.Filled.Redo,
                enabled = canRedo,
                contentDescription = "Redo",
                onClick = onRedo
            )
        }
    }
}

/** Lock toggle - freezes all graphics so no gesture can move them.
 *  Below undo/redo, always visible. Turns amber when engaged. */
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
internal fun UnitLabelsToggle(
    active: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xCC1565C0) else Color(0xCC000000))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Flag,
            contentDescription = if (active) "Hide unit labels" else "Show unit labels",
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
        // Don't discard a carefully placed calibration point on a stray
        // tap outside the dialog. Require explicit Cancel or Save.
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
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
