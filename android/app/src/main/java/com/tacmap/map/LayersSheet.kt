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
import com.tacmap.ui.AlertDialog
import com.tacmap.ui.ModalBottomSheet
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
import androidx.compose.material3.ButtonDefaults
import com.tacmap.calibration.BasemapStyle
import com.tacmap.calibration.Calibration
import com.tacmap.calibration.MapSourceKind
import com.tacmap.calibration.PdfMapSource
import com.tacmap.localization.DisplayFormat

private data class PendingLayerVisibilityMutation(
    val message: String,
    val retry: () -> DrawingMutationUiResult,
)

/** Which imported map a pending "Delete…" confirmation is about. */
private enum class ImportedMapDeletion { PDF, OFFLINE_TILES, SAVED }

/** How an imported PDF is placed on the map, shown under its name in Layers. */
internal enum class PdfGeoreferencing { GEOREFERENCED, MANUAL, NONE }

/** the Layers subtitle under a PDF, by where its placement came from (L2, same as iOS georefLabel) */
internal enum class PdfGeorefLabel { ADOBE_VP, LGI_DICT, MANUAL, PROVISIONAL }

/** no placement at all reads as provisional, iOS always has one and it's .provisional there */
internal fun pdfGeorefLabel(origin: com.tacmap.calibration.GeorefOrigin?): PdfGeorefLabel = when (origin) {
    com.tacmap.calibration.GeorefOrigin.ADOBE_VP -> PdfGeorefLabel.ADOBE_VP
    com.tacmap.calibration.GeorefOrigin.LGI_DICT -> PdfGeorefLabel.LGI_DICT
    com.tacmap.calibration.GeorefOrigin.FIDUCIARIES -> PdfGeorefLabel.MANUAL
    com.tacmap.calibration.GeorefOrigin.PROVISIONAL, null -> PdfGeorefLabel.PROVISIONAL
}

/**
 * iOS shows "Georeferenced" for a GeoPDF, "Manually placed" for a sheet the
 * user calibrated, and the map-centre fallback when there is no calibration.
 * Android stores auto-parsed GeoPDF correspondences as fiduciaries without an
 * MGRS string; user-entered fiduciaries always carry one.
 */
internal fun pdfGeoreferencing(kind: MapSourceKind, calibration: Calibration?): PdfGeoreferencing =
    when (calibration) {
        null -> PdfGeoreferencing.NONE
        is Calibration.Parsed -> PdfGeoreferencing.GEOREFERENCED
        is Calibration.Fiduciaries -> when {
            kind == MapSourceKind.GEO_PDF -> PdfGeoreferencing.GEOREFERENCED
            calibration.fids.none { it.mgrs.isNotBlank() } -> PdfGeoreferencing.GEOREFERENCED
            else -> PdfGeoreferencing.MANUAL
        }
    }

/** Fiduciaries behind a manual calibration, or null when there is none. */
internal fun manualFiduciaryCount(kind: MapSourceKind, calibration: Calibration?): Int? =
    (calibration as? Calibration.Fiduciaries)?.fids?.size
        ?.takeIf { pdfGeoreferencing(kind, calibration) == PdfGeoreferencing.MANUAL }

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
    retainedImportedMapIssue: String?,
    importedMapActive: Boolean,
    onReturnToImportedMap: () -> Unit,
    onDeleteRetainedImportedMap: () -> Unit,
    onRemoveUnavailableRetainedMap: () -> Unit,
    pdfMap: PdfMapSource?,
    hasOfflineTiles: Boolean,
    /** Show Imported Map (WP2 contract H), forced on and locked while calibrating */
    importedMapVisible: Boolean = true,
    importedMapToggleEnabled: Boolean = true,
    onImportedMapVisibleChange: (Boolean) -> Unit = {},
    bakeState: com.tacmap.calibration.PdfBakeManager.State = com.tacmap.calibration.PdfBakeManager.State.Idle,
    onCancelBake: () -> Unit = {},
    onRemoveBake: () -> Unit = {},
    renderFailed: Boolean = false,
    /** the reason line under the failed label, same copy as the alert (OD2-R2-5) */
    renderFailureReason: String? = null,
    onRetryRender: () -> Unit = {},
    onCalibratePdf: () -> Unit,
    onGenerateTiles: () -> Unit,
    onUnloadPdf: () -> Unit,
    onUnloadOfflineTiles: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingVisibility by remember { mutableStateOf<PendingLayerVisibilityMutation?>(null) }
    var pendingDeletion by remember { mutableStateOf<ImportedMapDeletion?>(null) }
    val savedMapOnly = !importedMapActive && retainedImportedMapName != null
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
            if (!importedMapActive && retainedImportedMapIssue != null) {
                Text(
                    Messages.layersSavedMapUnavailable(),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF9800),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(retainedImportedMapIssue, fontSize = 11.sp, color = Color(0xFF8A938A))
                OutlinedButton(
                    onClick = onRemoveUnavailableRetainedMap,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) { Text(Messages.layersRemoveSavedMapEntry()) }
            }

            if (pdfMap != null || hasOfflineTiles || savedMapOnly) {
                SectionHeader(L10n.text("Imported Map"))
                if (pdfMap != null) {
                    Text(pdfMap.displayName, fontSize = 15.sp)
                    Text(
                        when (pdfGeorefLabel(pdfMap.placement?.origin)) {
                            PdfGeorefLabel.ADOBE_VP -> Messages.pdfGeorefAdobeLabel()
                            // the same catalogue entries iOS georefLabel uses (B6)
                            PdfGeorefLabel.LGI_DICT -> L10n.text("Georeferenced (GeoPDF LGIDict)")
                            PdfGeorefLabel.MANUAL -> L10n.text("Manually placed bounds")
                            PdfGeorefLabel.PROVISIONAL -> L10n.text("No georeferencing — using map-centre fallback")
                        },
                        fontSize = 11.sp,
                        color = Color(0xFF8A938A)
                    )
                    manualFiduciaryCount(pdfMap.kind, pdfMap.calibration)?.let { count ->
                        Text(
                            Messages.layersPdfFiduciaryCount(DisplayFormat.number(count.toDouble(), 0)),
                            fontSize = 11.sp,
                            color = Color(0xFF8A938A)
                        )
                    }
                    ToggleRow(Messages.importedMapShowToggle(), importedMapVisible, onImportedMapVisibleChange, importedMapToggleEnabled)
                    if (renderFailed) {
                        Text(
                            Messages.pdfRenderFailedLabel(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFFF5A5A),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        renderFailureReason?.let { reason ->
                            Text(reason, fontSize = 11.sp, color = Color(0xFF8A938A))
                        }
                        OutlinedButton(
                            onClick = onRetryRender,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) { Text(Messages.pdfRenderTryAgain()) }
                    }
                    OutlinedButton(
                        onClick = onCalibratePdf,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) { Text(L10n.text("Calibrate PDF Map")) }
                    PdfBakeSection(pdfMap, bakeState, renderFailed, onGenerateTiles, onCancelBake, onRemoveBake)
                    DeleteMapButton(Messages.layersDeletePdfMap()) {
                        pendingDeletion = ImportedMapDeletion.PDF
                    }
                }
                if (hasOfflineTiles) {
                    DeleteMapButton(Messages.layersDeleteOfflineMap()) {
                        pendingDeletion = ImportedMapDeletion.OFFLINE_TILES
                    }
                }
                if (savedMapOnly) {
                    Text(retainedImportedMapName.orEmpty(), fontSize = 15.sp)
                    DeleteMapButton(Messages.layersDeleteSavedImportedMap()) {
                        pendingDeletion = ImportedMapDeletion.SAVED
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
    pendingDeletion?.let { deletion ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(Messages.layersDeleteImportedTitle()) },
            text = { Text(Messages.layersDeleteImportedMessage()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeletion = null
                        when (deletion) {
                            ImportedMapDeletion.PDF -> onUnloadPdf()
                            ImportedMapDeletion.OFFLINE_TILES -> onUnloadOfflineTiles()
                            ImportedMapDeletion.SAVED -> onDeleteRetainedImportedMap()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5A5A)),
                ) { Text(Messages.layersDeleteImportedConfirm()) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text(L10n.text("Cancel")) }
            },
        )
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

/** Destructive imported-map action; always confirmed before anything is deleted. */
@Composable
private fun DeleteMapButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5A5A)),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) { Text(label) }
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
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 15.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/**
 * Generate Offline Tiles… for the imported PDF: the button + caption, progress with
 * Cancel while it runs, or what's baked + Remove once it's done (contract J).
 */
@Composable
private fun PdfBakeSection(
    pdfMap: PdfMapSource,
    bakeState: com.tacmap.calibration.PdfBakeManager.State,
    /** the PDF source is sticky failed (G1): Try Again is the way out, not a bake (OD-F9) */
    renderFailed: Boolean,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    val bake = pdfMap.render.bake
    val calibrated = pdfMap.calibration != null
    when {
        // only under the PDF that's actually baking (C6)
        bakeState is com.tacmap.calibration.PdfBakeManager.State.Running && bakeState.token == pdfMap.render.renderGuardToken -> {
            Text(
                Messages.pdfBakeRunning(pdfBakeTiles(bakeState.done), pdfBakeTiles(bakeState.total)),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (bakeState.total > 0) bakeState.done.toFloat() / bakeState.total else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(L10n.text("Cancel"))
            }
        }
        bake != null -> {
            Text(
                Messages.pdfBakeInfo(
                    DisplayFormat.number(bake.minZoom.toDouble(), 0),
                    DisplayFormat.number(bake.maxZoom.toDouble(), 0),
                    pdfBakeSize(bake.bytes),
                ),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(Messages.pdfBakeRemove())
            }
        }
        else -> {
            OutlinedButton(
                onClick = onGenerate,
                enabled = calibrated && !renderFailed &&
                    bakeState !is com.tacmap.calibration.PdfBakeManager.State.Estimating &&
                    bakeState !is com.tacmap.calibration.PdfBakeManager.State.Running,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(Messages.pdfBakeGenerateButton()) }
            Text(
                if (calibrated) Messages.pdfBakeCaption() else Messages.pdfBakeDisabledCaption(),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
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
