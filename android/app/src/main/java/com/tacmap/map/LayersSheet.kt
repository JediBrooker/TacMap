package com.tacmap.map

import com.tacmap.localization.Messages
import com.tacmap.localization.L10n

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.tacmap.drawings.DrawingFeature
import com.tacmap.drawings.DrawingLayer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.tacmap.calibration.BasemapStyle

private data class PendingLayerVisibilityMutation(
    val message: String,
    val retry: () -> DrawingMutationUiResult,
)

/**
 * Overlay + label toggles, plus imported-map management. Opened from
 * "Layers" menu row - mirrors iOS so toggles live here instead of
 * cluttering the hamburger menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(
    symbologyVisible: Boolean,
    drawingsVisible: Boolean,
    mgrsGridVisible: Boolean,
    userLocationVisible: Boolean,
    unitLabelsVisible: Boolean,
    unitAmplifiersVisible: Boolean,
    taskLabelsVisible: Boolean,
    drawingLabelsVisible: Boolean,
    terrainHeatmapVisible: Boolean,
    onMgrsGridChange: (Boolean) -> Unit,
    onSymbologyVisibleChange: (Boolean) -> Unit,
    onDrawingsVisibleChange: (Boolean) -> Unit,
    onUserLocationChange: (Boolean) -> Unit,
    onTerrainHeatmapChange: (Boolean) -> Unit,
    onUnitLabelsChange: (Boolean) -> Unit,
    onUnitAmplifiersChange: (Boolean) -> Unit,
    onTaskLabelsChange: (Boolean) -> Unit,
    onDrawingLabelsChange: (Boolean) -> Unit,
    drawingLayers: List<DrawingLayer>,
    drawingFeatures: List<DrawingFeature>,
    onSetLayerVisible: (String, Boolean) -> DrawingMutationUiResult,
    activeBaseMap: BasemapStyle?,
    onSelectBaseMap: (BasemapStyle) -> Unit,
    retainedImportedMapName: String?,
    importedMapActive: Boolean,
    onReturnToImportedMap: () -> Unit,
    hasPdfMap: Boolean,
    hasOfflineTiles: Boolean,
    onCalibratePdf: () -> Unit,
    onGenerateTiles: () -> Unit,
    onUnloadPdf: () -> Unit,
    onUnloadOfflineTiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingVisibility by remember { mutableStateOf<PendingLayerVisibilityMutation?>(null) }
    fun attemptVisibility(retry: () -> DrawingMutationUiResult) {
        pendingVisibility = when (val result = retry()) {
            DrawingMutationUiResult.Saved -> null
            is DrawingMutationUiResult.Failed -> PendingLayerVisibilityMutation(
                message = result.message,
                retry = retry,
            )
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(L10n.text("Layers and Labels"), fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SectionHeader(L10n.text("Overlays"))
            ToggleRow(L10n.text("Symbology"), symbologyVisible, onSymbologyVisibleChange)
            ToggleRow(L10n.text("Drawings"), drawingsVisible, onDrawingsVisibleChange)
            ToggleRow(L10n.text("MGRS Grid"), mgrsGridVisible, onMgrsGridChange)
            ToggleRow(L10n.text("My Location"), userLocationVisible, onUserLocationChange)
            ToggleRow(L10n.text("Terrain Heat-map"), terrainHeatmapVisible, onTerrainHeatmapChange)

            SectionHeader(L10n.text("Labels"))
            ToggleRow(L10n.text("Unit Labels"), unitLabelsVisible, onUnitLabelsChange)
            ToggleRow(L10n.text("Unit Amplifiers"), unitAmplifiersVisible, onUnitAmplifiersChange)
            ToggleRow(L10n.text("Task Labels"), taskLabelsVisible, onTaskLabelsChange)
            ToggleRow(L10n.text("Drawing Labels"), drawingLabelsVisible, onDrawingLabelsChange)

            SectionHeader(L10n.text("Drawing Layers"))
            drawingLayers.forEach { layer ->
                DrawingLayerRow(
                    layer = layer,
                    count = drawingFeatures.count { it.layerId == layer.id },
                    onVisibleChange = { visible ->
                        attemptVisibility { onSetLayerVisible(layer.id, visible) }
                    }
                )
            }

            SectionHeader(L10n.text("Basemap"))
            // Keyed styles (all but OSM Topo) need the ArcGIS key baked in at
            // build time. No key -> hide them, rather than offer a basemap that
            // would just render blank.
            BasemapStyle.entries
                .filter { !it.requiresEsriKey || com.tacmap.calibration.EsriKey.isAvailable }
                .forEach { style ->
                    BasemapRow(
                        label = style.displayName,
                        selected = activeBaseMap == style && !importedMapActive,
                        onClick = { onSelectBaseMap(style) }
                    )
                }
            retainedImportedMapName?.let { name ->
                BasemapRow(
                    label = L10n.text("Imported: %1\$s", name),
                    selected = importedMapActive,
                    onClick = onReturnToImportedMap,
                )
            }
            if (importedMapActive) {
                Text(
                    L10n.text("The imported map is active. Choose an online basemap above to switch away without removing it."),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (!importedMapActive && retainedImportedMapName != null) {
                Text(
                    L10n.text("Your imported map is retained. Select its row to return to it."),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (hasPdfMap || hasOfflineTiles) {
                SectionHeader(L10n.text("Imported Map"))
                if (hasPdfMap) {
                    OutlinedButton(
                        onClick = onCalibratePdf,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) { Text(L10n.text("Calibrate PDF Map")) }
                    OutlinedButton(
                        onClick = onGenerateTiles,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(L10n.text("Generate Offline Tiles")) }
                    Text(
                        L10n.text("Bakes this calibrated map into an offline tile set on-device — no desktop tools needed."),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    OutlinedButton(
                        onClick = onUnloadPdf,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(L10n.text("Unload PDF Map")) }
                }
                if (hasOfflineTiles) {
                    OutlinedButton(
                        onClick = onUnloadOfflineTiles,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text(L10n.text("Unload Offline Tiles")) }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
    pendingVisibility?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingVisibility = null },
            title = { Text(L10n.text("Layer visibility not saved")) },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(onClick = { attemptVisibility(pending.retry) }) { Text(L10n.text("Retry")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingVisibility = null }) { Text(L10n.text("Not now")) }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun BasemapRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 15.sp)
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 15.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/// Drawing layer row: colour swatch + name + count, visibility toggle.
/// Mirrors iOS Layers sheet. Toggle off to hide without deleting.
@Composable
private fun DrawingLayerRow(layer: DrawingLayer, count: Int, onVisibleChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color(layer.color)))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(layer.displayName, fontSize = 15.sp)
                Text(
                    Messages.drawingCount(count),
                    fontSize = 11.sp,
                    color = Color(0xFF8A938A)
                )
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
