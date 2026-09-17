package com.tacmap.map

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingGeometry
import com.tacmap.drawings.DrawingLayer
import com.tacmap.mgrs.MgrsFormatter

private data class PendingDrawingSheetMutation(
    val message: String,
    val retry: () -> DrawingMutationUiResult,
)

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
    onLayerVisibilityChange: (String, Boolean) -> DrawingMutationUiResult,
    onAddLayer: (String) -> Boolean,
    onUpdateLayer: (String, String, Int) -> Boolean,
    onDeleteLayer: (String) -> Boolean,
    onDeleteFeature: (String) -> DrawingMutationUiResult
) {
    var newLayerName by remember { mutableStateOf("") }
    var editingLayer by remember { mutableStateOf<DrawingLayer?>(null) }
    var deletingLayer by remember { mutableStateOf<DrawingLayer?>(null) }
    var layerMutationError by remember { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
    var pendingDrawingMutation by remember {
        mutableStateOf<PendingDrawingSheetMutation?>(null)
    }
    fun attempt(retry: () -> DrawingMutationUiResult): DrawingMutationUiResult {
        val result = retry()
        pendingDrawingMutation = when (result) {
            DrawingMutationUiResult.Saved -> null
            is DrawingMutationUiResult.Failed -> PendingDrawingSheetMutation(
                message = result.message,
                retry = retry,
            )
        }
        return result
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val safeLayers = layers.ifEmpty { DrawingDocument.defaultLayers() }
    val activeLayer = safeLayers.firstOrNull { it.id == activeLayerId } ?: safeLayers.first()
    val visibleLayerIds = safeLayers.filter { it.isVisible }.map { it.id }.toSet()
    val visibleFeatures = features.filter { it.layerId in visibleLayerIds }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // One bounded lazy container owns all vertical scrolling. Keeping the
        // header, layer actions, and feature rows in the same container makes
        // every action reachable in landscape and at large font scales without
        // illegal same-axis nested scrolling.
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            item(key = "header") {
                Column {
                    Text(L10n.text("Drawings"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        MgrsFormatter.format(crosshairLat, crosshairLng),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    )
                }
            }
            item(key = "active-layer") {
                Column {
                    Text(L10n.text("Active Layer"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                    ) {
                        safeLayers.forEach { layer ->
                            FilterChip(
                                selected = layer.id == activeLayer.id,
                                onClick = { onActiveLayerChange(layer.id) },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        LayerColorSwatch(color = layer.color, size = 12.dp)
                                        Spacer(Modifier.size(6.dp))
                                        Text(layer.displayName)
                                    }
                                },
                            )
                        }
                    }
                }
            }
            item(key = "drawing-tools") {
                Column {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ElevatedButton(onClick = onPlacePoint) {
                            DrawingTypeIcon(DrawingGeometry.POINT)
                            Spacer(Modifier.size(6.dp))
                            Text(L10n.text("Point"))
                        }
                        ElevatedButton(
                            onClick = { onStartDraft(DrawingGeometry.LINE) },
                        ) {
                            DrawingTypeIcon(DrawingGeometry.LINE)
                            Spacer(Modifier.size(6.dp))
                            Text(L10n.text("Line Tool"))
                        }
                        ElevatedButton(
                            onClick = { onStartDraft(DrawingGeometry.POLYGON) },
                        ) {
                            DrawingTypeIcon(DrawingGeometry.POLYGON)
                            Spacer(Modifier.size(6.dp))
                            Text(L10n.text("Area"))
                        }
                    }
                    ElevatedButton(onClick = onStartFreeDraw, modifier = Modifier.fillMaxWidth()) {
                        FreeDrawIcon()
                        Spacer(Modifier.size(6.dp))
                        Text(L10n.text("Free Draw"))
                    }
                    Text(
                        L10n.text("After selecting a tool, tap the map to place points. Free Draw: drag to sketch freely — lifts to finish."),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 14.dp),
                    )
                    Text(L10n.text("Layers"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            items(safeLayers, key = { "layer:${it.id}" }) { layer ->
                LayerRow(
                    layer = layer,
                    isActive = layer.id == activeLayer.id,
                    onTap = { onActiveLayerChange(layer.id) },
                    onVisibleChange = { visible ->
                        attempt { onLayerVisibilityChange(layer.id, visible) }
                    },
                    onEdit = if (layer.id in DrawingDocument.DEFAULT_LAYER_IDS) null else {
                        {
                            layerMutationError = null
                            editingLayer = layer
                        }
                    },
                    onDelete = if (layer.id in DrawingDocument.DEFAULT_LAYER_IDS) null else {
                        {
                            layerMutationError = null
                            deletingLayer = layer
                        }
                    },
                )
            }
            item(key = "add-layer") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = newLayerName,
                        onValueChange = { newLayerName = it },
                        singleLine = true,
                        label = { Text(L10n.text("Layer name")) },
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = {
                        if (onAddLayer(newLayerName)) {
                            newLayerName = ""
                            layerMutationError = null
                        } else {
                            layerMutationError = Messages.displayTheLayerCouldNotBeSavedCheckTheNameMessage()
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(L10n.text("Add"))
                    }
                }
                layerMutationError?.let { error ->
                    Text(
                        error.text,
                        color = Color(0xFFD32F2F),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item(key = "features-heading") {
                Text(
                    L10n.text("Features (%1\$s/%2\$s)", visibleFeatures.size, features.size),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
            }
            if (features.isEmpty()) {
                item(key = "empty-features") {
                    Text(L10n.text("No drawings yet."), fontSize = 12.sp, modifier = Modifier.padding(bottom = 24.dp))
                }
            } else {
                items(features, key = { "feature:${it.id}" }) { feature ->
                    DrawingFeatureRow(
                        feature = feature,
                        layerName = safeLayers.firstOrNull { it.id == feature.layerId }?.displayName,
                        isVisible = feature.layerId in visibleLayerIds,
                        onDelete = { attempt { onDeleteFeature(feature.id) } },
                    )
                }
                item(key = "feature-bottom-space") { Spacer(Modifier.size(24.dp)) }
            }
        }
    }

    editingLayer?.let { layer ->
        LayerEditDialog(
            layer = layer,
            onDismiss = { editingLayer = null },
            onSave = { name, color ->
                onUpdateLayer(layer.id, name, color).also { saved ->
                    if (saved) {
                        editingLayer = null
                        layerMutationError = null
                    }
                }
            },
        )
    }

    deletingLayer?.let { layer ->
        val waypointCountLabel = L10n.text("All symbols and drawings on this layer will move to Friendly.")
        AlertDialog(
            onDismissRequest = { deletingLayer = null },
            title = { Text(L10n.text("Delete %1\$s?", layer.displayName)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(waypointCountLabel)
                    layerMutationError?.let { error ->
                        Text(error.text, color = Color(0xFFD32F2F), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onDeleteLayer(layer.id)) {
                        deletingLayer = null
                        layerMutationError = null
                    } else {
                        layerMutationError = Messages.displayTheLayerRemainsAnySymbolsAlreadyMovedToFriendlyMessage()
                    }
                }) { Text(L10n.text("Delete layer"), color = Color(0xFFD32F2F)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingLayer = null }) { Text(L10n.text("Cancel")) }
            },
        )
    }

    pendingDrawingMutation?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDrawingMutation = null },
            title = { Text(L10n.text("Drawing change not saved")) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = { attempt(pending.retry) }) { Text(L10n.text("Retry")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDrawingMutation = null }) { Text(L10n.text("Not now")) }
            },
        )
    }
}

@Composable
private fun FreeDrawIcon() {
    Canvas(Modifier.size(18.dp)) {
        val stroke = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // double S-curve so it looks freehand vs the straight line tool
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
                // straight line w/ endpoint nodes, visually distinct from freehand
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
    onVisibleChange: (Boolean) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
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
            Text(layer.displayName, fontSize = 14.sp, fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
        }
        onEdit?.let { edit ->
            IconButton(onClick = edit) {
                Icon(Icons.Default.Edit, contentDescription = L10n.text("Edit %1\$s layer", layer.displayName))
            }
        }
        onDelete?.let { delete ->
            IconButton(onClick = delete) {
                Icon(Icons.Default.Delete, contentDescription = L10n.text("Delete %1\$s layer", layer.displayName))
            }
        }
        Switch(
            checked = layer.isVisible,
            onCheckedChange = onVisibleChange,
            modifier = Modifier.semantics {
                contentDescription = L10n.text("%1\$s layer visibility", layer.displayName)
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayerEditDialog(
    layer: DrawingLayer,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Boolean,
) {
    var name by remember(layer.id) { mutableStateOf(layer.name) }
    var color by remember(layer.id) { mutableIntStateOf(layer.color) }
    var saveError by remember(layer.id) { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
    val colors = (DrawingDocument.CUSTOM_LAYER_COLORS + layer.color).distinct()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L10n.text("Edit drawing layer")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(L10n.text("Layer name")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(L10n.text("Layer colour"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    colors.forEach { candidate ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = layerColorAccessibilityLabel(candidate)
                                }
                                .selectable(
                                    selected = candidate == color,
                                    role = Role.RadioButton,
                                    onClick = { color = candidate },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            LayerColorSwatch(
                                color = candidate,
                                size = if (candidate == color) 30.dp else 24.dp,
                            )
                        }
                    }
                }
                saveError?.let { error ->
                    Text(error.text, color = Color(0xFFD32F2F), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    if (!onSave(name.trim(), color)) {
                        saveError = Messages.displayTheLayerCouldNotBeSavedYourPreviousNameMessage()
                    }
                },
            ) { Text(L10n.text("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L10n.text("Cancel")) } },
    )
}

internal fun layerColorAccessibilityLabel(color: Int): String =
    L10n.text("Layer colour #%1\$s", (color and 0xFFFFFF).toString(16).uppercase().padStart(6, '0'))

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
                L10n.text("%1\$s • %2\$s • %3\$s pts", feature.geometry.displayName, layerName ?: L10n.text("Layer"), feature.points.size) +
                    if (isVisible) "" else L10n.text(" • hidden"),
                fontSize = 11.sp
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = L10n.text("Delete %1\$s drawing", feature.name))
        }
    }
}
