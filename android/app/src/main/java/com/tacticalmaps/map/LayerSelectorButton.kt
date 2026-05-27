package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.drawings.DrawingDocument
import com.tacticalmaps.drawings.DrawingLayer

@Composable
fun LayerSelectorButton(
    layers: List<DrawingLayer>,
    selectedLayerId: String,
    onLayerSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val safeLayers = layers.ifEmpty { DrawingDocument.defaultLayers() }
    val selectedLayer = safeLayers.firstOrNull { it.id == selectedLayerId }
        ?: safeLayers.first()

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .widthIn(min = 112.dp)
                .clip(CircleShape)
                .background(Color(0xFF202020))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LayerColorSwatch(
                color = selectedLayer.color,
                size = 16.dp
            )
            Spacer(Modifier.size(6.dp))
            Text(
                selectedLayer.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = "Change layer",
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(16.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            safeLayers.forEach { layer ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LayerColorSwatch(color = layer.color, size = 14.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                layer.name,
                                fontWeight = if (layer.id == selectedLayer.id) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    },
                    onClick = {
                        onLayerSelected(layer.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun LayerColorSwatch(
    color: Int,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(color))
            .border(1.dp, Color.White.copy(alpha = 0.45f), CircleShape)
    )
}
