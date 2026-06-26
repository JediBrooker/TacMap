package com.tacmap.map

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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Overlay + label visibility toggles, plus imported-map management. Opened
 * from the "Layers" menu row — mirrors the iOS Layers sheet so the toggles
 * live here rather than cluttering the hamburger menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayersSheet(
    mgrsGridVisible: Boolean,
    unitLabelsVisible: Boolean,
    taskLabelsVisible: Boolean,
    drawingLabelsVisible: Boolean,
    terrainHeatmapVisible: Boolean,
    onMgrsGridChange: (Boolean) -> Unit,
    onTerrainHeatmapChange: (Boolean) -> Unit,
    onUnitLabelsChange: (Boolean) -> Unit,
    onTaskLabelsChange: (Boolean) -> Unit,
    onDrawingLabelsChange: (Boolean) -> Unit,
    activeBaseMap: BaseMap,
    onSelectBaseMap: (BaseMap) -> Unit,
    hasPdfMap: Boolean,
    hasOfflineTiles: Boolean,
    onCalibratePdf: () -> Unit,
    onGenerateTiles: () -> Unit,
    onUnloadPdf: () -> Unit,
    onUnloadOfflineTiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Layers and Labels", fontSize = 20.sp, fontWeight = FontWeight.Bold)

            SectionHeader("Overlays")
            ToggleRow("MGRS Grid", mgrsGridVisible, onMgrsGridChange)
            ToggleRow("Terrain Heat-map", terrainHeatmapVisible, onTerrainHeatmapChange)

            SectionHeader("Labels")
            ToggleRow("Unit Labels", unitLabelsVisible, onUnitLabelsChange)
            ToggleRow("Task Labels", taskLabelsVisible, onTaskLabelsChange)
            ToggleRow("Drawing Labels", drawingLabelsVisible, onDrawingLabelsChange)

            SectionHeader("Basemap")
            val importedMapActive = hasPdfMap || hasOfflineTiles
            BasemapRow(
                label = "Satellite",
                selected = activeBaseMap == BaseMap.SATELLITE && !importedMapActive,
                onClick = { onSelectBaseMap(BaseMap.SATELLITE) }
            )
            BasemapRow(
                label = "Satellite (Esri)",
                selected = activeBaseMap == BaseMap.ESRI_SATELLITE && !importedMapActive,
                onClick = { onSelectBaseMap(BaseMap.ESRI_SATELLITE) }
            )
            BasemapRow(
                label = "Terrain (OpenTopoMap)",
                selected = activeBaseMap == BaseMap.TERRAIN && !importedMapActive,
                onClick = { onSelectBaseMap(BaseMap.TERRAIN) }
            )
            if (importedMapActive) {
                Text(
                    "An imported map is active — pick a basemap to switch back to it.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (hasPdfMap || hasOfflineTiles) {
                SectionHeader("Imported Map")
                if (hasPdfMap) {
                    OutlinedButton(
                        onClick = onCalibratePdf,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) { Text("Calibrate PDF Map") }
                    OutlinedButton(
                        onClick = onGenerateTiles,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Generate Offline Tiles") }
                    Text(
                        "Bakes this calibrated map into an offline tile set on-device — no desktop tools needed.",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    OutlinedButton(
                        onClick = onUnloadPdf,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Unload PDF Map") }
                }
                if (hasOfflineTiles) {
                    OutlinedButton(
                        onClick = onUnloadOfflineTiles,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("Unload Offline Tiles") }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
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
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
