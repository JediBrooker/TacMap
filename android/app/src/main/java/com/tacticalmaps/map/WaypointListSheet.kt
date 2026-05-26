package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.Waypoint
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
    store: WaypointStore,
    onDismiss: () -> Unit,
    onFlyTo: (LatLng) -> Unit
) {
    var pendingNewName by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
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
                    "No symbols yet. Pan the crosshair to a feature and tap “Add at Crosshair” below.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(waypoints, key = { it.id }) { wp ->
                        WaypointRow(wp = wp, onTap = {
                            onFlyTo(LatLng(wp.latitude, wp.longitude))
                            onDismiss()
                        })
                    }
                }
            }

            Spacer(Modifier.size(12.dp))

            // Add at Crosshair button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0A84FF))
                    .clickable { pendingNewName = "" }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text("Add at Crosshair", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Text(
                "The new waypoint will be placed at the current map centre.",
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
    }

    // Quick-name dialog after Add at Crosshair.
    pendingNewName?.let { initial ->
        var name by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { pendingNewName = null },
            title = { Text("New waypoint") },
            text = {
                Column {
                    Text(
                        "Crosshair: ${MgrsFormatter.format(LatLng(crosshairLat, crosshairLng))}",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        placeholder = { Text("Waypoint") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val resolved = name.trim().ifEmpty { "Waypoint" }
                    store.add(Waypoint(
                        name = resolved,
                        latitude = crosshairLat,
                        longitude = crosshairLng
                    ))
                    pendingNewName = null
                    onDismiss()
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { pendingNewName = null }) { Text("Cancel") }
            }
        )
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
        Icon(Icons.Default.LocationOn, contentDescription = null,
             tint = Color(0xFFB48800), modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(wp.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                MgrsFormatter.format(LatLng(wp.latitude, wp.longitude)) +
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
