package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BoxScope.MapSideToolbar(
    onImportPdf: () -> Unit,
    onLayers: () -> Unit,
    onWaypoints: () -> Unit,
    onDraw: () -> Unit
) {
    // Top-left cluster.
    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 130.dp, start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircleHudButton(Icons.Default.Menu) { /* nav drawer */ }
        ImportButton(onImportPdf)
    }

    // Right edge cluster.
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 110.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CompassChip()
        CircleHudButton(Icons.Default.Layers, label = "Layers", onClick = onLayers)
        CircleHudButton(Icons.Default.LocationOn, label = "Waypoints", onClick = onWaypoints)
        CircleHudButton(Icons.Default.Edit, label = "Draw", onClick = onDraw)
    }
}

@Composable
private fun CircleHudButton(icon: ImageVector, label: String? = null, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0xCC000000))
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (label != null) {
            Text(label, color = Color.White, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ImportButton(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(Icons.Default.PostAdd, contentDescription = "Import PDF Map", tint = Color.White)
        Text("Import\nPDF Map", color = Color.White, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun CompassChip() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Text("N", color = Color.White, fontSize = 14.sp)
    }
}
