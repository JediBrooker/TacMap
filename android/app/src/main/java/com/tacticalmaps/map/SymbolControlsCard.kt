package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind
import com.tacticalmaps.waypoints.WaypointStore

/**
 * Floating controls card for a tapped waypoint. Phase 2 adds the
 * Android-side APP-6C controls plus tactical task rotation and W/H
 * scale controls.
 */
@Composable
fun SymbolControlsCard(
    waypoint: Waypoint,
    layers: List<DrawingLayer>,
    crosshairTargetLat: Double,
    crosshairTargetLng: Double,
    store: WaypointStore,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var editMode by remember(waypoint.id) { mutableStateOf<SymbolEditorMode?>(null) }

    Column(
        modifier = modifier
            .shadow(elevation = 10.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE1C1C1E))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Header(
            waypoint = waypoint,
            onDismiss = onDismiss,
            onTitleClick = when (waypoint.kind) {
                WaypointKind.Generic -> null
                is WaypointKind.Military -> { { editMode = SymbolEditorMode.MILITARY } }
                is WaypointKind.ControlMeasure -> { { editMode = SymbolEditorMode.TASK } }
            }
        )

        when (waypoint.kind) {
            WaypointKind.Generic -> Unit
            is WaypointKind.ControlMeasure -> ControlMeasureControls(
                waypoint = waypoint,
                onWaypointChange = store::update,
                onWaypointChangeDraft = store::updateNoUndo
            )
            is WaypointKind.Military -> Unit
        }

        LayerSelectorButton(
            layers = layers,
            selectedLayerId = waypoint.layerId,
            onLayerSelected = { layerId ->
                store.update(waypoint.copy(layerId = layerId))
            },
            modifier = Modifier.fillMaxWidth()
        )

        ActionRow(
            waypoint = waypoint,
            crosshairTargetLat = crosshairTargetLat,
            crosshairTargetLng = crosshairTargetLng,
            store = store,
            onDelete = { showDeleteConfirm = true }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete symbol?") },
            text = { Text("This will permanently remove \"${waypoint.name}\".") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(waypoint)
                    showDeleteConfirm = false
                    onDismiss()
                }) {
                    Text("Delete", color = Color(0xFFFF3B30))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    editMode?.let { mode ->
        SymbolEditorDialog(
            mode = mode,
            initialKind = waypoint.kind,
            initialName = waypoint.name,
            crosshairLat = null,
            crosshairLng = null,
            title = when (mode) {
                SymbolEditorMode.MILITARY -> "Change Military Unit"
                SymbolEditorMode.TASK -> "Change Tactical Task"
            },
            actionLabel = "Save",
            fullScreen = mode != SymbolEditorMode.TASK,
            onDismiss = { editMode = null },
            onConfirm = { name, kind ->
                store.update(waypoint.copy(name = name, kind = kind))
                editMode = null
            }
        )
    }
}

@Composable
private fun Header(
    waypoint: Waypoint,
    onDismiss: () -> Unit,
    onTitleClick: (() -> Unit)?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (waypoint.kind is WaypointKind.ControlMeasure) Icons.Default.Flag else Icons.Default.GpsFixed,
                contentDescription = null,
                tint = if (waypoint.kind == WaypointKind.Generic) Color(0xFFB48800) else Color.Black,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (onTitleClick != null) Modifier.clickable { onTitleClick() } else Modifier)
        ) {
            Text(
                waypoint.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                waypoint.kind.categoryDisplayName,
                color = Color.White.copy(alpha = 0.58f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close symbol controls",
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun ControlMeasureControls(
    waypoint: Waypoint,
    onWaypointChange: (Waypoint) -> Unit,
    onWaypointChangeDraft: (Waypoint) -> Unit = onWaypointChange,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SliderRow(
            title = "Rotation",
            value = waypoint.rotation.toFloat(),
            valueLabel = "${waypoint.rotation.toInt()}°",
            range = 0f..360f,
            onChange = { onWaypointChangeDraft(waypoint.copy(rotation = it.toDouble())) },
            onCommit = { onWaypointChange(waypoint.copy(rotation = it.toDouble())) },
            onReset = { onWaypointChange(waypoint.copy(rotation = 0.0)) }
        )
        SliderRow(
            title = "Width",
            value = waypoint.scaleX.toFloat(),
            valueLabel = "%.2fx".format(waypoint.scaleX),
            range = 0.15f..6f,
            onChange = { onWaypointChangeDraft(waypoint.copy(scaleX = it.toDouble())) },
            onCommit = { onWaypointChange(waypoint.copy(scaleX = it.toDouble())) },
            onReset = { onWaypointChange(waypoint.copy(scaleX = 1.0)) }
        )
        SliderRow(
            title = "Height",
            value = waypoint.scaleY.toFloat(),
            valueLabel = "%.2fx".format(waypoint.scaleY),
            range = 0.15f..6f,
            onChange = { onWaypointChangeDraft(waypoint.copy(scaleY = it.toDouble())) },
            onCommit = { onWaypointChange(waypoint.copy(scaleY = it.toDouble())) },
            onReset = { onWaypointChange(waypoint.copy(scaleY = 1.0)) }
        )
    }
}

@Composable
private fun ActionRow(
    waypoint: Waypoint,
    crosshairTargetLat: Double,
    crosshairTargetLng: Double,
    store: WaypointStore,
    onDelete: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = {
                store.update(waypoint.copy(
                    latitude = crosshairTargetLat,
                    longitude = crosshairTargetLng
                ))
            },
            modifier = Modifier.weight(1f).height(36.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0A84FF).copy(alpha = 0.85f),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(6.dp))
            Text("Move to Crosshair", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onDelete,
            modifier = Modifier.size(width = 44.dp, height = 36.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF3B30).copy(alpha = 0.85f),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete symbol", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit = onChange,
    onReset: () -> Unit
) {
    var latestValue by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, modifier = Modifier.weight(0.75f))
        Slider(
            value = latestValue.coerceIn(range.start, range.endInclusive),
            onValueChange = { latestValue = it; onChange(it) },
            onValueChangeFinished = { onCommit(latestValue) },
            valueRange = range,
            modifier = Modifier.weight(1.8f)
        )
        Text(valueLabel, color = Color.White.copy(alpha = 0.76f), fontSize = 11.sp, modifier = Modifier.weight(0.65f))
        TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("Reset", fontSize = 11.sp)
        }
    }
}
