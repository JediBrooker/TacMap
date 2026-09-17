package com.tacmap.map

import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.L10n

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.drawings.DrawingStrokeStyle

// Drawing-related controls (edit bar, draft bar, transform sliders, colour /
// style pickers, name dialog, centre pill) extracted verbatim from MapScreen.kt.
// The three composables MapScreen calls directly are `internal`; the leaf
// widgets they compose stay `private` to this file. Behaviour is unchanged.

private data class PendingDrawingControlMutation(
    val message: String,
    val retry: () -> DrawingMutationUiResult,
    val onSaved: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawingFeatureEditBar(
    feature: DrawingFeature,
    layers: List<DrawingLayer>,
    onFeatureChange: (DrawingFeature) -> DrawingMutationUiResult,
    onFeatureChangeDraft: (DrawingFeature) -> Boolean,
    onMoveToCrosshair: () -> DrawingMutationUiResult,
    onDelete: () -> DrawingMutationUiResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var colorMenuOpen by remember { mutableStateOf(false) }
    var fillColorMenuOpen by remember { mutableStateOf(false) }
    var lineGraphicMenuOpen by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    var pendingMutation by remember(feature.id) {
        mutableStateOf<PendingDrawingControlMutation?>(null)
    }
    fun attempt(
        onSaved: () -> Unit = {},
        retry: () -> DrawingMutationUiResult,
    ): DrawingMutationUiResult {
        val result = retry()
        pendingMutation = when (result) {
            DrawingMutationUiResult.Saved -> {
                onSaved()
                null
            }
            is DrawingMutationUiResult.Failed -> PendingDrawingControlMutation(
                message = result.message,
                retry = retry,
                onSaved = onSaved,
            )
        }
        return result
    }
    // Rotation / W / H sliders take up most of the card's vertical
    // space and aren't needed for every edit, so they hide behind a
    // "Transform" toggle by default. Points don't get the toggle
    // (they have no transform to apply).
    var showTransforms by remember(feature.id) { mutableStateOf(false) }
    var showEditor by remember(feature.id) { mutableStateOf(false) }
    val hasTransforms = feature.geometry != DrawingGeometry.POINT

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE1C1C1E))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawingToolChip(feature.geometry)
            Column(
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .clickable { nameDialogOpen = true }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        feature.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(Modifier.size(5.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = L10n.text("Edit drawing name"),
                        tint = Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    L10n.text("%1\$s - Drawing", feature.geometry.displayName),
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Keep the compact 30dp circle visual, but let the IconButton keep its
            // full ≥48dp touch target (M32) by sizing the inner Box, not the button.
            IconButton(onClick = onDismiss) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = L10n.text("Close drawing controls"),
                        tint = Color.White.copy(alpha = 0.58f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }


        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { showEditor = !showEditor },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(L10n.text("Edit drawing"), maxLines = 1)
            }
            Button(
                onClick = { attempt(retry = onMoveToCrosshair) },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(L10n.text("Move to crosshair"), maxLines = 1, fontSize = 12.sp)
            }
        }

        if (showEditor && hasTransforms && showTransforms) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                DrawingTransformSliderRow(
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    label = L10n.text("Rotation"),
                    value = normalizedDrawingDegrees(feature.rotationDegrees).toFloat(),
                    valueLabel = "${normalizedDrawingDegrees(feature.rotationDegrees).toInt()}°",
                    range = 0f..360f,
                    onChange = { onFeatureChangeDraft(feature.copy(rotationDegrees = it.toDouble())) },
                    onCommit = { attempt { onFeatureChange(feature.copy(rotationDegrees = it.toDouble())) } },
                    onReset = { attempt { onFeatureChange(feature.copy(rotationDegrees = 0.0)) } }
                )
                DrawingTransformSliderRow(
                    icon = Icons.Default.SwapHoriz,
                    label = L10n.text("Width scale"),
                    value = feature.scaleX.toFloat().coerceIn(0.15f, 6f),
                    valueLabel = DisplayFormat.number(feature.scaleX, 2) + "x",
                    range = 0.15f..6f,
                    onChange = { onFeatureChangeDraft(feature.copy(scaleX = it.toDouble())) },
                    onCommit = { attempt { onFeatureChange(feature.copy(scaleX = it.toDouble())) } },
                    onReset = { attempt { onFeatureChange(feature.copy(scaleX = 1.0)) } }
                )
                DrawingTransformSliderRow(
                    icon = Icons.Default.SwapVert,
                    label = L10n.text("Height scale"),
                    value = feature.scaleY.toFloat().coerceIn(0.15f, 6f),
                    valueLabel = DisplayFormat.number(feature.scaleY, 2) + "x",
                    range = 0.15f..6f,
                    onChange = { onFeatureChangeDraft(feature.copy(scaleY = it.toDouble())) },
                    onCommit = { attempt { onFeatureChange(feature.copy(scaleY = it.toDouble())) } },
                    onReset = { attempt { onFeatureChange(feature.copy(scaleY = 1.0)) } }
                )
            }
        }

        if (showEditor) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                DrawingColorSelectButton(
                    color = feature.strokeColor,
                    contentDescription = L10n.text("Stroke colour"),
                    onClick = { colorMenuOpen = true }
                )
                DrawingColorMenu(
                    expanded = colorMenuOpen,
                    selectedColor = feature.strokeColor,
                    onDismiss = { colorMenuOpen = false },
                    onColorSelected = { color ->
                        attempt(
                            retry = { onFeatureChange(feature.copy(strokeColor = color)) },
                            onSaved = { colorMenuOpen = false },
                        )
                    }
                )
            }
            if (feature.geometry == DrawingGeometry.POLYGON) {
                Box {
                    DrawingColorSelectButton(
                        color = feature.fillColor,
                        contentDescription = L10n.text("Fill colour"),
                        onClick = { fillColorMenuOpen = true },
                    )
                    DrawingColorMenu(
                        expanded = fillColorMenuOpen,
                        selectedColor = feature.fillColor or 0xFF000000.toInt(),
                        onDismiss = { fillColorMenuOpen = false },
                        onColorSelected = { color ->
                            val alpha = (feature.fillColor ushr 24) and 0xFF
                            attempt(
                                retry = {
                                    onFeatureChange(feature.copy(fillColor = color.withAlpha(alpha)))
                                },
                                onSaved = { fillColorMenuOpen = false },
                            )
                        },
                    )
                }
                DrawingOpacityButton(
                    alpha = (feature.fillColor ushr 24) and 0xFF,
                    onAlphaSelected = { alpha ->
                        attempt {
                            onFeatureChange(feature.copy(fillColor = feature.fillColor.withAlpha(alpha)))
                        }
                    },
                )
            }
            DrawingStyleButton(
                strokeStyle = feature.strokeStyle,
                onClick = {
                    attempt { onFeatureChange(feature.copy(strokeStyle = feature.strokeStyle.next())) }
                }
            )
            // Tactical line-graphic picker (line features only).
            if (feature.geometry == DrawingGeometry.LINE) {
                Box {
                    val active = feature.lineGraphic != null &&
                        feature.lineGraphic != com.tacmap.drawings.LineGraphic.PLAIN
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = if (active) 0.22f else 0.10f))
                            .clickable { lineGraphicMenuOpen = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Timeline,
                            contentDescription = L10n.text("Tactical line graphic"),
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = lineGraphicMenuOpen,
                        onDismissRequest = { lineGraphicMenuOpen = false }
                    ) {
                        com.tacmap.drawings.LineGraphic.entries.forEach { g ->
                            DropdownMenuItem(
                                text = { Text(g.displayName) },
                                onClick = {
                                    val v = if (g == com.tacmap.drawings.LineGraphic.PLAIN) null else g
                                    attempt(
                                        retry = { onFeatureChange(feature.copy(lineGraphic = v)) },
                                        onSaved = { lineGraphicMenuOpen = false },
                                    )
                                }
                            )
                        }
                    }
                }
            }
            if (hasTransforms) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            Color.White.copy(alpha = if (showTransforms) 0.22f else 0.10f)
                        )
                        .clickable { showTransforms = !showTransforms },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = L10n.text("Toggle transform sliders"),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            LayerSelectorButton(
                layers = layers,
                selectedLayerId = feature.layerId,
                onLayerSelected = { layerId ->
                    attempt { onFeatureChange(feature.copy(layerId = layerId)) }
                },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { attempt(retry = onDelete) },
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(6.dp))
                Text(L10n.text("Delete"), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (nameDialogOpen) {
        DrawingNameDialog(
            name = feature.name,
            onNameChange = { name ->
                val cleanName = name.trim().ifBlank { feature.name }
                attempt(
                    retry = { onFeatureChange(feature.copy(name = cleanName)) },
                    onSaved = { nameDialogOpen = false },
                ).saved
            },
            onDismiss = { nameDialogOpen = false }
        )
    }
    pendingMutation?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingMutation = null },
            title = { Text(L10n.text("Drawing change not saved")) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = {
                    attempt(onSaved = pending.onSaved, retry = pending.retry)
                }) {
                    Text(L10n.text("Retry"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingMutation = null }) { Text(L10n.text("Not now")) }
            },
        )
    }
}

@Composable
private fun DrawingTransformSliderRow(
    icon: ImageVector,
    label: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit = onChange,
    onReset: () -> Unit
) {
    var latestValue by remember(value) { mutableFloatStateOf(value) }
    Row(
        modifier = Modifier.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.74f),
                modifier = Modifier.size(12.dp)
            )
        }
        Slider(
            value = latestValue.coerceIn(range.start, range.endInclusive),
            onValueChange = { latestValue = it; onChange(it) },
            onValueChangeFinished = { onCommit(latestValue) },
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .semantics {
                    contentDescription = label
                    stateDescription = valueLabel
                },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color(0xFF1E9BFF),
                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
            )
        )
        Text(
            valueLabel,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.widthIn(min = 46.dp)
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF315D70))
                .clickable(onClick = onReset),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = L10n.text("Reset %1\$s", label),
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private fun normalizedDrawingDegrees(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

@Composable
internal fun DrawingDraftBar(
    geometry: DrawingGeometry,
    pointCount: Int,
    drawingName: String,
    strokeColor: Int,
    strokeStyle: DrawingStrokeStyle,
    onDrawingNameChange: (String) -> Unit,
    onStrokeColorChange: (Int) -> Unit,
    onStrokeStyleChange: (DrawingStrokeStyle) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var colorMenuOpen by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    val canFinish = geometry == DrawingGeometry.POINT || pointCount >= geometry.minimumVertices

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xE6000000))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DrawingToolChip(geometry)
        DrawingNameButton(
            name = drawingName,
            onClick = { nameDialogOpen = true }
        )
        Box {
            DrawingColorSelectButton(
                color = strokeColor,
                onClick = { colorMenuOpen = true }
            )
            DrawingColorMenu(
                expanded = colorMenuOpen,
                selectedColor = strokeColor,
                onDismiss = { colorMenuOpen = false },
                onColorSelected = { color ->
                    onStrokeColorChange(color)
                    colorMenuOpen = false
                }
            )
        }
        DrawingStyleButton(
            strokeStyle = strokeStyle,
            onClick = { onStrokeStyleChange(strokeStyle.next()) }
        )
        if (geometry != DrawingGeometry.POINT) {
            Text(
                pointCount.toString(),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
        IconButton(
            onClick = onCancel,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF202020))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = L10n.text("Cancel drawing"),
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = CircleShape,
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFA000),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF4A4A4A),
                disabledContentColor = Color.White.copy(alpha = 0.45f)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
        ) {
            Text(
                if (geometry == DrawingGeometry.POINT) L10n.text("Done") else L10n.text("Finish"),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    if (nameDialogOpen) {
        DrawingNameDialog(
            name = drawingName,
            onNameChange = {
                onDrawingNameChange(it)
                true
            },
            onDismiss = { nameDialogOpen = false }
        )
    }
}

@Composable
private fun DrawingNameButton(name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .widthIn(min = 56.dp, max = 78.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.ifBlank { L10n.text("Name") },
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DrawingNameDialog(
    name: String,
    onNameChange: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var editedName by remember(name) { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L10n.text("Drawing name")) },
        text = {
            OutlinedTextField(
                value = editedName,
                onValueChange = { editedName = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (onNameChange(editedName)) onDismiss()
                }
            ) {
                Text(L10n.text("Done"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(L10n.text("Cancel"))
            }
        }
    )
}

@Composable
private fun DrawingToolChip(geometry: DrawingGeometry) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFFFFA000)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(22.dp)) {
            val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            when (geometry) {
                DrawingGeometry.POINT -> drawCircle(
                    color = Color.Black,
                    radius = 5.dp.toPx(),
                    center = center
                )
                DrawingGeometry.LINE -> drawLine(
                    color = Color.Black,
                    start = Offset(size.width * 0.18f, size.height * 0.72f),
                    end = Offset(size.width * 0.82f, size.height * 0.28f),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
                DrawingGeometry.POLYGON -> {
                    val path = Path().apply {
                        moveTo(size.width * 0.18f, size.height * 0.72f)
                        lineTo(size.width * 0.5f, size.height * 0.2f)
                        lineTo(size.width * 0.82f, size.height * 0.7f)
                        close()
                    }
                    drawPath(path, Color.Black, style = stroke)
                }
            }
        }
    }
}

@Composable
private fun DrawingColorSelectButton(
    color: Int,
    contentDescription: String = L10n.text("Drawing colour"),
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape)
        )
    }
}

@Composable
private fun DrawingOpacityButton(alpha: Int, onAlphaSelected: (Int) -> Unit) {
    val choices = listOf(0x33, 0x66, 0x99, 0xCC)
    val closestIndex = choices.indices.minByOrNull { kotlin.math.abs(choices[it] - alpha) } ?: 0
    val percent = (alpha * 100f / 255f).toInt()
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .semantics {
                contentDescription = L10n.text("Fill opacity")
                stateDescription = L10n.text("%1\$s percent", percent)
            }
            .clickable { onAlphaSelected(choices[(closestIndex + 1) % choices.size]) },
        contentAlignment = Alignment.Center,
    ) {
        Text("$percent%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DrawingColorMenu(
    expanded: Boolean,
    selectedColor: Int,
    onDismiss: () -> Unit,
    onColorSelected: (Int) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(8.dp)
        ) {
            DrawingDefaults.COLORS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { color ->
                        DrawingColorSwatch(
                            color = color,
                            selected = color == selectedColor,
                            onClick = { onColorSelected(color) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawingStyleButton(
    strokeStyle: DrawingStrokeStyle,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 48.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(width = 28.dp, height = 16.dp)) {
            val y = size.height / 2f
            if (strokeStyle == DrawingStrokeStyle.DASHED) {
                val segment = size.width * 0.24f
                val gap = size.width * 0.13f
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = Color.White,
                        start = Offset(x, y),
                        end = Offset((x + segment).coerceAtMost(size.width), y),
                        strokeWidth = 4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    x += segment + gap
                }
            } else {
                drawLine(
                    color = Color.White,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 4.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun DrawingColorSwatch(
    color: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color(0xFFFFA000) else Color.White.copy(alpha = 0.45f),
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

private fun DrawingStrokeStyle.next(): DrawingStrokeStyle =
    when (this) {
        DrawingStrokeStyle.SOLID -> DrawingStrokeStyle.DASHED
        DrawingStrokeStyle.DASHED -> DrawingStrokeStyle.SOLID
    }

@Composable
internal fun CentrePill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = L10n.text("Centre on My Location"),
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.GpsFixed,
    guidance: String? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(48.dp)
            .then(
                if (guidance == null) Modifier else Modifier.semantics {
                    stateDescription = guidance
                }
            ),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC000000)),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.White,
             modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = Color.White,
             fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
