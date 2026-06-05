package com.tacticalmaps.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacticalmaps.drawings.DrawingDocument
import com.tacticalmaps.drawings.DrawingFeature
import com.tacticalmaps.drawings.DrawingGeometry
import com.tacticalmaps.drawings.DrawingLayer
import com.tacticalmaps.mgrs.MgrsFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DrawingLayersSheet(
    layers: List<DrawingLayer>,
    features: List<DrawingFeature>,
    activeLayerId: String,
    crosshairLat: Double,
    crosshairLng: Double,
    onDismiss: () -> Unit,
    onActiveLayerChange: (String) -> Unit,
    onPlacePoint: () -> Unit,
    onStartDraft: (DrawingGeometry) -> Unit,
    onStartFreeDraw: () -> Unit,
    onLayerVisibilityChange: (String, Boolean) -> Unit,
    onAddLayer: (String) -> Unit,
    onDeleteFeature: (String) -> Unit
) {
    var newLayerName by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val safeLayers = layers.ifEmpty { DrawingDocument.defaultLayers() }
    val activeLayer = safeLayers.firstOrNull { it.id == activeLayerId } ?: safeLayers.first()
    val visibleLayerIds = safeLayers.filter { it.isVisible }.map { it.id }.toSet()
    val visibleFeatures = features.filter { it.layerId in visibleLayerIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Drawings", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                MgrsFormatter.format(crosshairLat, crosshairLng),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
            )

            Text("Active Layer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
            ) {
                safeLayers.forEach { layer ->
                    FilterChip(
                        selected = layer.id == activeLayer.id,
                        onClick = { onActiveLayerChange(layer.id) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LayerColorSwatch(color = layer.color, size = 12.dp)
                                Spacer(Modifier.size(6.dp))
                                Text(layer.name)
                            }
                        }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ElevatedButton(onClick = onPlacePoint, modifier = Modifier.weight(1f)) {
                    DrawingTypeIcon(DrawingGeometry.POINT)
                    Spacer(Modifier.size(6.dp))
                    Text("Point")
                }
                ElevatedButton(
                    onClick = { onStartDraft(DrawingGeometry.LINE) },
                    modifier = Modifier.weight(1f)
                ) {
                    DrawingTypeIcon(DrawingGeometry.LINE)
                    Spacer(Modifier.size(6.dp))
                    Text("Line Tool")
                }
                ElevatedButton(
                    onClick = { onStartDraft(DrawingGeometry.POLYGON) },
                    modifier = Modifier.weight(1f)
                ) {
                    DrawingTypeIcon(DrawingGeometry.POLYGON)
                    Spacer(Modifier.size(6.dp))
                    Text("Area")
                }
            }
            ElevatedButton(
                onClick = onStartFreeDraw,
                modifier = Modifier.fillMaxWidth()
            ) {
                FreeDrawIcon()
                Spacer(Modifier.size(6.dp))
                Text("Free Draw")
            }

            Text(
                "After selecting a tool, tap the map to place points. Free Draw: drag to sketch freely — lifts to finish.",
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
            )

            Text("Layers", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            safeLayers.forEach { layer ->
                LayerRow(
                    layer = layer,
                    isActive = layer.id == activeLayer.id,
                    onTap = { onActiveLayerChange(layer.id) },
                    onVisibleChange = { onLayerVisibilityChange(layer.id, it) }
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                OutlinedTextField(
                    value = newLayerName,
                    onValueChange = { newLayerName = it },
                    singleLine = true,
                    label = { Text("Layer name") },
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    onAddLayer(newLayerName)
                    newLayerName = ""
                }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Add")
                }
            }

            Text(
                "Features (${visibleFeatures.size}/${features.size})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
            )
            if (features.isEmpty()) {
                Text("No drawings yet.", fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp).padding(bottom = 24.dp)
                ) {
                    items(features, key = { it.id }) { feature ->
                        DrawingFeatureRow(
                            feature = feature,
                            layerName = safeLayers.firstOrNull { it.id == feature.layerId }?.name,
                            isVisible = feature.layerId in visibleLayerIds,
                            onDelete = { onDeleteFeature(feature.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FreeDrawIcon() {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Double S-curve — clearly suggests freehand vs the straight line tool
        val path = Path().apply {
            moveTo(size.width * 0.05f, size.height * 0.50f)
            cubicTo(
                size.width * 0.10f, size.height * 0.10f,
                size.width * 0.35f, size.height * 0.10f,
                size.width * 0.40f, size.height * 0.50f
            )
            cubicTo(
                size.width * 0.45f, size.height * 0.90f,
                size.width * 0.70f, size.height * 0.90f,
                size.width * 0.75f, size.height * 0.50f
            )
            cubicTo(
                size.width * 0.82f, size.height * 0.15f,
                size.width * 0.92f, size.height * 0.25f,
                size.width * 0.95f, size.height * 0.35f
            )
        }
        drawPath(path, Color.White, style = stroke)
    }
}

@Composable
private fun DrawingTypeIcon(geometry: DrawingGeometry) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.11f)
        when (geometry) {
            DrawingGeometry.POINT -> drawCircle(Color.White, radius = size.minDimension * 0.24f)
            DrawingGeometry.LINE -> {
                // Straight line with endpoint nodes — distinct from the freehand curve
                val start = Offset(size.width * 0.15f, size.height * 0.78f)
                val end = Offset(size.width * 0.85f, size.height * 0.22f)
                drawLine(Color.White, start = start, end = end, strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawCircle(Color.White, radius = stroke.width * 1.6f, center = start)
                drawCircle(Color.White, radius = stroke.width * 1.6f, center = end)
            }
            DrawingGeometry.POLYGON -> {
                val path = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.75f)
                    lineTo(size.width * 0.46f, size.height * 0.18f)
                    lineTo(size.width * 0.84f, size.height * 0.44f)
                    lineTo(size.width * 0.72f, size.height * 0.82f)
                    close()
                }
                drawPath(path, Color.White, style = stroke)
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: DrawingLayer,
    isActive: Boolean,
    onTap: () -> Unit,
    onVisibleChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LayerColorSwatch(
            color = layer.color,
            size = 18.dp,
            modifier = Modifier.padding(end = 10.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(layer.name, fontSize = 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
        }
        Switch(checked = layer.isVisible, onCheckedChange = onVisibleChange)
    }
}

@Composable
private fun DrawingFeatureRow(
    feature: DrawingFeature,
    layerName: String?,
    isVisible: Boolean,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(feature.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${feature.geometry.displayName} • ${layerName ?: "Layer"} • ${feature.points.size} pts" +
                    if (isVisible) "" else " • hidden",
                fontSize = 11.sp
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = null)
        }
    }
}
