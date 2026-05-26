package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointStore

/**
 * Floating controls card for a tapped waypoint. Matches the iOS
 * `SymbolControlsCard` layout: compact one-line header (icon + name +
 * close), then a row with **Move to Crosshair** + **Delete**.
 * Generic-only for Phase 1 — rotation/W/H sliders for tactical control
 * measures come in Phase 2 once we port the APP-6C / control-measure
 * renderers.
 */
@Composable
fun SymbolControlsCard(
    waypoint: Waypoint,
    crosshairTargetLat: Double,
    crosshairTargetLng: Double,
    store: WaypointStore,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .shadow(elevation = 10.dp, shape = RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xEE1C1C1E))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header: small white-bg square (placeholder for symbol icon)
        // + name + close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.GpsFixed,
                    contentDescription = null,
                    tint = Color(0xFFB48800),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.size(10.dp))
            Text(
                waypoint.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close symbol controls",
                    tint = Color.White.copy(alpha = 0.6f)
                )
            }
        }

        // Action row: Move + Delete.
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
                    containerColor = Color(0xFF0A84FF).copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.GpsFixed, contentDescription = null,
                     modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(6.dp))
                Text("Move to Crosshair", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.size(width = 44.dp, height = 36.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF3B30).copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete symbol",
                     modifier = Modifier.size(16.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete symbol?") },
            text = { Text("This will permanently remove “${waypoint.name}”.") },
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
}
