package com.tacticalmaps.map

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
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.drawings.DrawingStrokeStyle

// Drawing-related controls (edit bar, draft bar, transform sliders, colour /
// style pickers, name dialog, centre pill) extracted verbatim from MapScreen.kt.
// The three composables MapScreen calls directly are `internal`; the leaf
// widgets they compose stay `private` to this file. Behaviour is unchanged.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrawingFeatureEditBar(
    feature: DrawingFeature,
    layers: List<DrawingLayer>,
    onFeatureChange: (DrawingFeature) -> Unit,
    onFeatureChangeDraft: (DrawingFeature) -> Unit = onFeatureChange,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var colorMenuOpen by remember { mutableStateOf(false) }
    var nameDialogOpen by remember { mutableStateOf(false) }
    // Rotation / W / H sliders take up most of the card's vertical
    // space and aren't needed for every edit, so they hide behind a
    // "Transform" toggle by default. Points don't get the toggle
    // (they have no transform to apply).
    var showTransforms by remember(feature.id) { mutableStateOf(false) }
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
                        contentDescription = "Edit drawing name",
                        tint = Color.White.copy(alpha = 0.42f),
                        modifier = Modifier.size(12.dp)
                    )
                }
                Text(
                    "${feature.geometry.displayName} - Drawing",
                    color = Color.White.copy(alpha = 0.58f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close drawing controls",
                    tint = Color.White.copy(alpha = 0.58f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (hasTransforms && showTransforms) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                DrawingTransformSliderRow(
                    icon = Icons.AutoMirrored.Filled.RotateRight,
                    value = normalizedDrawingDegrees(feature.rotationDegrees).toFloat(),
                    valueLabel = "${normalizedDrawingDegrees(feature.rotationDegrees).toInt()}°",
                    range = 0f..360f,
                    onChange = { onFeatureChangeDraft(feature.copy(rotationDegrees = it.toDouble())) },
                    onCommit = { onFeatureChange(feature.copy(rotationDegrees = it.toDouble())) },
                    onReset = { onFeatureChange(feature.copy(rotationDegrees = 0.0)) }
                )
                DrawingTransformSliderRow(
                    icon = Icons.Default.SwapHoriz,
                    value = feature.scaleX.toFloat().coerceIn(0.15f, 6f),
                    valueLabel = "%.2fx".format(feature.scaleX),
                    range = 0.15f..6f,
                    onChange = { onFeatureChangeDraft(feature.copy(scaleX = it.toDouble())) },
                    onCommit = { onFeatureChange(feature.copy(scaleX = it.toDouble())) },
                    onReset = { onFeatureChange(feature.copy(scaleX = 1.0)) }
                )
                DrawingTransformSliderRow(
                    icon = Icons.Default.SwapVert,
                    value = feature.scaleY.toFloat().coerceIn(0.15f, 6f),
                    valueLabel = "%.2fx".format(feature.scaleY),
                    range = 0.15f..6f,
                    onChange = { onFeatureChangeDraft(feature.copy(scaleY = it.toDouble())) },
                    onCommit = { onFeatureChange(feature.copy(scaleY = it.toDouble())) },
                    onReset = { onFeatureChange(feature.copy(scaleY = 1.0)) }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                DrawingColorSelectButton(
                    color = feature.strokeColor,
                    onClick = { colorMenuOpen = true }
                )
                DrawingColorMenu(
                    expanded = colorMenuOpen,
                    selectedColor = feature.strokeColor,
                    onDismiss = { colorMenuOpen = false },
                    onColorSelected = { color ->
                        onFeatureChange(
                            feature.copy(
                                strokeColor = color,
                                fillColor = color.withAlpha(0x33)
                            )
                        )
                        colorMenuOpen = false
                    }
                )
            }
            DrawingStyleButton(
                strokeStyle = feature.strokeStyle,
                onClick = {
                    onFeatureChange(feature.copy(strokeStyle = feature.strokeStyle.next()))
                }
            )
            if (hasTransforms) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            Color.White.copy(alpha = if (showTransforms) 0.22f else 0.10f)
                        )
                        .clickable { showTransforms = !showTransforms },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = "Toggle transform sliders",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            LayerSelectorButton(
                layers = layers,
                selectedLayerId = feature.layerId,
                onLayerSelected = { layerId ->
                    onFeatureChange(feature.copy(layerId = layerId))
                },
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onDelete,
                modifier = Modifier.height(36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE53935),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(6.dp))
                Text("Delete", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (nameDialogOpen) {
        DrawingNameDialog(
            name = feature.name,
            onNameChange = { name ->
                val cleanName = name.trim().ifBlank { feature.name }
                onFeatureChange(feature.copy(name = cleanName))
            },
            onDismiss = { nameDialogOpen = false }
        )
    }
}

@Composable
private fun DrawingTransformSliderRow(
    icon: ImageVector,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit = onChange,
    onReset: () -> Unit
) {
    var latestValue by remember(value) { mutableStateOf(value) }
    Row(
        modifier = Modifier.height(30.dp),
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
                .height(24.dp),
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
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFF315D70))
                .clickable(onClick = onReset),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Reset",
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
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF202020))
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
        Button(
            onClick = onFinish,
            enabled = canFinish,
            shape = CircleShape,
            modifier = Modifier.height(38.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFFA000),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFF4A4A4A),
                disabledContentColor = Color.White.copy(alpha = 0.45f)
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
        ) {
            Text(
                if (geometry == DrawingGeometry.POINT) "Done" else "Finish",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
    if (nameDialogOpen) {
        DrawingNameDialog(
            name = drawingName,
            onNameChange = onDrawingNameChange,
            onDismiss = { nameDialogOpen = false }
        )
    }
}

@Composable
private fun DrawingNameButton(name: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(38.dp)
            .widthIn(min = 56.dp, max = 78.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.ifBlank { "Name" },
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
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editedName by remember(name) { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drawing name") },
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
                    onNameChange(editedName)
                    onDismiss()
                }
            ) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
private fun DrawingColorSelectButton(color: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Color(0xFF202020))
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
            .size(width = 46.dp, height = 38.dp)
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
            .size(30.dp)
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
internal fun CentrePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC000000)),
        shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color.White,
             modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(8.dp))
        Text("Centre on My Location", color = Color.White,
             fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
