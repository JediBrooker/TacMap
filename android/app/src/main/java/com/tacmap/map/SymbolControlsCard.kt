package com.tacmap.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import com.tacmap.drawings.DrawingLayer
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import com.tacmap.waypoints.WaypointStore

/**
 * Compact launcher for the selected-symbol editor. Moving is an immediate,
 * durable quick action; all field edits and deletion remain transactional in
 * [SelectedSymbolEditorDialog].
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
    var showEditor by remember(waypoint.id) { mutableStateOf(false) }
    var moveFailed by remember(waypoint.id) { mutableStateOf(false) }

    fun moveToCrosshair(): Boolean = store.update(
        waypoint.copy(latitude = crosshairTargetLat, longitude = crosshairTargetLng)
    ).also { saved -> moveFailed = !saved }

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
            onTitleClick = { showEditor = true },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { showEditor = true },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Text("Edit symbol", maxLines = 1)
            }
            OutlinedButton(
                onClick = { moveToCrosshair() },
                modifier = Modifier.weight(1f).height(48.dp),
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Move to crosshair", maxLines = 1, fontSize = 12.sp)
            }
        }

    }

    if (showEditor) {
        SelectedSymbolEditorDialog(
            waypoint = waypoint,
            layers = layers,
            onSave = store::update,
            onDelete = {
                store.remove(waypoint).also { removed ->
                    if (removed) onDismiss()
                }
            },
            onDismiss = { showEditor = false },
        )
    }

    if (moveFailed) {
        AlertDialog(
            onDismissRequest = { moveFailed = false },
            title = { Text("Symbol not moved") },
            text = { Text("The new position could not be saved. The symbol is still at its previous position.") },
            confirmButton = {
                TextButton(onClick = { moveToCrosshair() }) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = { moveFailed = false }) { Text("Cancel") }
            },
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
                .heightIn(min = 48.dp)
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
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close symbol editor",
                tint = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}
