package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind
import com.tacticalmaps.waypoints.WaypointStore

/**
 * Bottom sheet listing all saved waypoints. "Add at Crosshair" drops a
 * new waypoint at the current map centre and opens a quick name
 * dialog. Tap any row to recentre on that waypoint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointListSheet(
    waypoints: List<Waypoint>,
    crosshairLat: Double,
    crosshairLng: Double,
    activeLayerId: String,
    store: WaypointStore,
    onDismiss: () -> Unit,
    onFlyTo: (lat: Double, lng: Double) -> Unit
) {
    var pendingEditor by remember { mutableStateOf<SymbolEditorMode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // Title
            Text(
                "Symbology",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Text(
                "Symbology (${waypoints.size})",
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // List
            if (waypoints.isEmpty()) {
                Text(
                    "No symbols yet. Pan the crosshair to a feature and add a marker, unit, or task below.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(waypoints, key = { it.id }) { wp ->
                        WaypointRow(wp = wp, onTap = {
                            onFlyTo(wp.latitude, wp.longitude)
                            onDismiss()
                        })
                    }
                }
            }

            Spacer(Modifier.size(12.dp))

            AddSymbolButton(
                label = "Military Unit",
                icon = Icons.Default.Security,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = { pendingEditor = SymbolEditorMode.MILITARY }
            )
            Spacer(Modifier.size(8.dp))
            AddSymbolButton(
                label = "Tactical Task",
                icon = Icons.Default.Flag,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = { pendingEditor = SymbolEditorMode.TASK }
            )
            Text(
                "New symbols are placed at the current map centre.",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
    }

    pendingEditor?.let { mode ->
        val initialKind = when (mode) {
            SymbolEditorMode.MILITARY -> WaypointKind.Military()
            SymbolEditorMode.TASK -> WaypointKind.ControlMeasure()
        }
        SymbolEditorDialog(
            mode = mode,
            initialKind = initialKind,
            initialName = "",
            crosshairLat = crosshairLat,
            crosshairLng = crosshairLng,
            title = when (mode) {
                SymbolEditorMode.MILITARY -> "New Military Unit"
                SymbolEditorMode.TASK -> "New Tactical Task"
            },
            actionLabel = "Place",
            onDismiss = { pendingEditor = null },
            onConfirm = { name, kind ->
                store.add(Waypoint(
                    name = name,
                    latitude = crosshairLat,
                    longitude = crosshairLng,
                    kind = kind,
                    layerId = activeLayerId
                ))
                pendingEditor = null
                onDismiss()
            }
        )
    }
}

@Composable
private fun AddSymbolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A84FF))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.size(8.dp))
        Text("Add $label", color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun WaypointRow(wp: Waypoint, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val icon = when (wp.kind) {
            WaypointKind.Generic -> Icons.Default.LocationOn
            is WaypointKind.Military -> Icons.Default.Security
            is WaypointKind.ControlMeasure -> Icons.Default.Flag
        }
        Icon(icon, contentDescription = null,
             tint = Color(0xFFB48800), modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(wp.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(wp.kind.displayName, fontSize = 11.sp, color = Color.Gray)
            Text(
                MgrsFormatter.format(wp.latitude, wp.longitude) +
                    (wp.elevationLabel?.let { " • $it" } ?: ""),
                fontSize = 11.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null,
             tint = Color.Gray)
    }
}
