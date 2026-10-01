package com.tacmap.map

import com.tacmap.localization.DecimalInput
import com.tacmap.localization.DisplayFormat
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.tacmap.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.graphics.toColorInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.ui.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tacmap.mgrs.MgrsFormatter
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.tacmap.waypoints.MarkerCatalog
import com.tacmap.waypoints.MarkerSet
import com.tacmap.waypoints.MarkerSymbol
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.HIGHER_FORMATION_MAX_CODE_POINTS
import com.tacmap.waypoints.ReinforcementStatus
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.UNIQUE_IDENTIFIER_MAX_CODE_POINTS
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import com.tacmap.waypoints.boundUnitAmplifier
import com.tacmap.waypoints.normalizedUnitAmplifiersForKind
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Kind a new-symbol builder opens on. The builder can switch between all four. */
enum class SymbolEditorMode {
    POINT, MILITARY, TASK, MARKER;

    internal val segmentLabel: String
        get() = when (this) {
            POINT -> L10n.text("Point")
            MILITARY -> Messages.symbolsKindMilitary()
            TASK -> Messages.symbolsKindTasks()
            MARKER -> Messages.symbolsKindMarkers()
        }
}

/** Everything the new-symbol builder collects; the caller decides where it lands. */
data class NewSymbolDraft(
    val name: String,
    val kind: WaypointKind,
    val notes: String? = null,
    val elevationMetres: Double? = null,
    val rotation: Double = 0.0,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val higherFormation: String? = null,
    val uniqueIdentifier: String? = null,
    val reinforcementStatus: ReinforcementStatus = ReinforcementStatus.NONE,
) {
    fun toWaypoint(latitude: Double, longitude: Double, layerId: String): Waypoint = Waypoint(
        name = name,
        notes = notes,
        latitude = latitude,
        longitude = longitude,
        elevationMetres = elevationMetres,
        kind = kind,
        rotation = rotation,
        scaleX = scaleX,
        scaleY = scaleY,
        higherFormation = higherFormation,
        uniqueIdentifier = uniqueIdentifier,
        reinforcementStatus = reinforcementStatus,
        layerId = layerId,
    )
}

/**
 * Normalizes the builder's fields the way iOS `WaypointCreationSheet.save`
 * does: a blank name becomes the kind's name, blank notes and elevation are
 * dropped, and rotation/scale only persist for tasks. Null when the elevation
 * is not a number.
 */
internal fun newSymbolDraft(
    name: String,
    kind: WaypointKind,
    notes: String,
    elevationText: String,
    rotation: Double,
    scaleX: Double,
    scaleY: Double,
    higherFormation: String,
    uniqueIdentifier: String,
    reinforcementStatus: ReinforcementStatus,
): NewSymbolDraft? {
    val cleanElevation = elevationText.trim()
    val elevation = if (cleanElevation.isEmpty()) null else DecimalInput.parse(cleanElevation) ?: return null
    val isTask = kind is WaypointKind.ControlMeasure
    val amplifiers = normalizedUnitAmplifiersForKind(
        kind = kind,
        higherFormation = higherFormation,
        uniqueIdentifier = uniqueIdentifier,
        reinforcementStatus = reinforcementStatus,
    )
    return NewSymbolDraft(
        name = name.trim().ifEmpty { kind.displayName },
        kind = kind,
        notes = notes.trim().ifEmpty { null },
        elevationMetres = elevation,
        rotation = if (isTask) normalizedDegrees(rotation) else 0.0,
        scaleX = if (isTask) scaleX.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE) else 1.0,
        scaleY = if (isTask) scaleY.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE) else 1.0,
        higherFormation = amplifiers.higherFormation,
        uniqueIdentifier = amplifiers.uniqueIdentifier,
        reinforcementStatus = amplifiers.reinforcementStatus,
    )
}

/**
 * Builder for a brand-new symbol, matching iOS `WaypointCreationSheet`: a
 * live preview, the Point / Military / Tasks / Markers kinds, rotation and
 * size with presets for tasks, and notes and elevation for every kind. Each
 * kind keeps its own selections while the user switches between them.
 * [defaultTaskScale] suits the current zoom (see [TaskGraphicSizing]).
 */
@Composable
fun SymbolEditorDialog(
    mode: SymbolEditorMode,
    initialName: String,
    crosshairLat: Double?,
    crosshairLng: Double?,
    title: String,
    actionLabel: String,
    fullScreen: Boolean = true,
    defaultTaskScale: Double = 1.0,
    submissionError: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (NewSymbolDraft) -> Unit
) {
    var category by remember { mutableStateOf(mode) }
    var name by remember(initialName) { mutableStateOf(initialName) }
    var militarySpec by remember { mutableStateOf(MilitarySymbolSpec()) }
    var measure by remember { mutableStateOf(TacticalControlMeasure.ASSEMBLY_AREA) }
    var markerSet by remember { mutableStateOf(MarkerSet.AIRSOFT) }
    var markerSymbolId by remember { mutableStateOf("team") }
    var markerColor by remember { mutableStateOf("#3B7BE0") }
    var higherFormation by remember { mutableStateOf("") }
    var uniqueIdentifier by remember { mutableStateOf("") }
    var reinforcementStatus by remember { mutableStateOf(ReinforcementStatus.NONE) }
    // New tasks start at a size that suits the zoom, square on both axes.
    val initialScale = remember { defaultTaskScale.coerceIn(MIN_SYMBOL_SCALE, MAX_SYMBOL_SCALE) }
    var rotation by remember { mutableDoubleStateOf(0.0) }
    var scaleX by remember { mutableDoubleStateOf(initialScale) }
    var scaleY by remember { mutableDoubleStateOf(initialScale) }
    var notes by remember { mutableStateOf("") }
    var elevationText by remember { mutableStateOf("") }
    var elevationInvalid by remember { mutableStateOf(false) }

    val currentKind = when (category) {
        SymbolEditorMode.POINT -> WaypointKind.Generic
        SymbolEditorMode.MILITARY -> WaypointKind.Military(militarySpec)
        SymbolEditorMode.TASK -> WaypointKind.ControlMeasure(measure)
        SymbolEditorMode.MARKER -> WaypointKind.Marker(
            MarkerSymbol(markerSet, markerSymbolId, markerColor, com.tacmap.waypoints.CustomSymbolStore.symbol(markerSymbolId))
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        /// decorFitsSystemWindows=false alone doesn't work on every
        /// device/Compose combo, so also tell the Window directly. Otherwise
        /// WindowInsets.systemBars lies about the bottom inset and
        /// the gesture pill clips our buttons.
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let {
                WindowCompat.setDecorFitsSystemWindows(it, false)
            }
        }

        Surface(
            modifier = if (fullScreen) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 720.dp)
            },
            color = Color(0xFF16161A),
            shape = if (fullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = if (fullScreen) {
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                } else {
                    Modifier.fillMaxWidth()
                }
            ) {
                EditorTopBar(
                    title = title,
                    subtitle = currentKind.displayName,
                    onDismiss = onDismiss
                )

                LazyColumn(
                    modifier = if (fullScreen) {
                        Modifier
                            .weight(1f)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .imePadding()
                    } else Modifier.heightIn(max = 440.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text(currentKind.displayName) },
                            label = { Text(L10n.text("Title")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    item {
                        /// Stretched tasks preview at the geometric mean of
                        /// their scales, kept within 0.6-1.4× so the tile stays
                        /// readable; the saved values span 0.1-20×.
                        val previewScale = if (category == SymbolEditorMode.TASK) {
                            sqrt(scaleX * scaleY).coerceIn(0.6, 1.4)
                        } else 1.0
                        SymbolPreviewPanel(
                            kind = currentKind,
                            iconSize = (64 * previewScale).dp,
                            rotation = if (category == SymbolEditorMode.TASK) rotation else 0.0,
                        )
                    }

                    item {
                        KindSegmentedPicker(selected = category, onSelected = { category = it })
                    }

                    when (category) {
                        SymbolEditorMode.POINT -> Unit
                        SymbolEditorMode.MILITARY -> {
                            item {
                                MilitaryTypeFields(spec = militarySpec, onChange = { militarySpec = it })
                            }
                            item {
                                UnitAmplifierFields(
                                    higherFormation = higherFormation,
                                    uniqueIdentifier = uniqueIdentifier,
                                    reinforcementStatus = reinforcementStatus,
                                    onHigherFormationChange = { higherFormation = it },
                                    onUniqueIdentifierChange = { uniqueIdentifier = it },
                                    onReinforcementStatusChange = { reinforcementStatus = it },
                                )
                            }
                        }
                        SymbolEditorMode.TASK -> {
                            item {
                                TaskTypeField(measure = measure, onChange = { measure = it })
                            }
                            item {
                                TaskOrientationFields(rotation = rotation, onChange = { rotation = it })
                            }
                            item {
                                TaskSizeFields(
                                    scaleX = scaleX,
                                    scaleY = scaleY,
                                    onScaleXChange = { scaleX = it },
                                    onScaleYChange = { scaleY = it },
                                )
                            }
                        }
                        SymbolEditorMode.MARKER -> item {
                            MarkerTypeFields(
                                set = markerSet,
                                symbolId = markerSymbolId,
                                colorHex = markerColor,
                                onSetChange = { newSet ->
                                    markerSet = newSet
                                    val first = MarkerCatalog.entries(newSet).firstOrNull() ?: return@MarkerTypeFields
                                    markerSymbolId = first.id
                                    markerColor = first.defaultColor
                                },
                                onSymbolChange = { id ->
                                    markerSymbolId = id
                                    markerColor = MarkerCatalog.entry(markerSet, id).defaultColor
                                },
                                onColorChange = { markerColor = it }
                            )
                        }
                    }

                    item {
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(L10n.text("Notes")) },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = elevationText,
                            onValueChange = { elevationText = it; elevationInvalid = false },
                            label = { Text(L10n.text("Elevation (metres)")) },
                            singleLine = true,
                            isError = elevationInvalid,
                            supportingText = {
                                Text(if (elevationInvalid) ELEVATION_VALIDATION_ERROR else Messages.decimalInputHint())
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    crosshairLat?.let { lat ->
                        val lng = crosshairLng ?: 0.0
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
                                    .padding(12.dp)
                            ) {
                                Text(L10n.text("Placed at crosshair"), color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
                                Text(
                                    MgrsFormatter.format(lat, lng),
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    /// Buttons inside the scrollable column so they sit
                    /// under the last field, not pinned to screen bottom
                    /// where the gesture pill clips them on some devices.
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            submissionError?.let { error ->
                                Text(
                                    error,
                                    color = Color(0xFFFF8A80),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .semantics { contentDescription = L10n.text("Symbol save error: %1\$s", error) },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(L10n.text("Cancel"))
                                }
                                Button(
                                    onClick = {
                                        val draft = newSymbolDraft(
                                            name = name,
                                            kind = currentKind,
                                            notes = notes,
                                            elevationText = elevationText,
                                            rotation = rotation,
                                            scaleX = scaleX,
                                            scaleY = scaleY,
                                            higherFormation = higherFormation,
                                            uniqueIdentifier = uniqueIdentifier,
                                            reinforcementStatus = reinforcementStatus,
                                        )
                                        if (draft == null) elevationInvalid = true else onConfirm(draft)
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF0A84FF),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(actionLabel, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = L10n.text("Close symbol editor"), tint = Color.White)
        }
    }
}

/// Live preview of the symbol being built or edited, on white because task
/// graphics are black line art. Mirrors the iOS editors' 100 pt preview row.
@Composable
internal fun SymbolPreviewPanel(
    kind: WaypointKind,
    iconSize: Dp = 64.dp,
    rotation: Double = 0.0,
    taskColor: TaskColor = TaskColor.BLACK,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Messages.symbolsPreview(), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White, RoundedCornerShape(10.dp))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                WaypointKindIcon(kind = kind, size = iconSize, rotation = rotation, taskColor = taskColor)
            }
        }
    }
}

/// Point / Military / Tasks / Markers, one row like the iOS segmented picker.
@Composable
private fun KindSegmentedPicker(
    selected: SymbolEditorMode,
    onSelected: (SymbolEditorMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(L10n.text("Kind"), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .padding(3.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            SymbolEditorMode.entries.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) Color(0xFF0A84FF) else Color.Transparent)
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            onClick = { onSelected(option) },
                        )
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.segmentLabel,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/// Rotation slider plus the 0/90/180/270° presets of the iOS builder.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskOrientationFields(rotation: Double, onChange: (Double) -> Unit) {
    EditorCard(title = Messages.symbolsOrientation(), help = Messages.symbolsOrientationHelp()) {
        DraftSlider(
            label = L10n.text("Rotation"),
            value = rotation.toFloat().coerceIn(0f, 360f),
            valueLabel = DisplayFormat.number(rotation, 0) + "°",
            range = 0f..360f,
            // Whole degrees, like the iOS slider's 1° step.
            onChange = { onChange(it.roundToInt().toDouble()) },
            onReset = { onChange(0.0) },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(0, 90, 180, 270).forEach { degrees ->
                val formatted = DisplayFormat.number(degrees.toDouble(), 0)
                PresetButton(
                    label = "$formatted°",
                    description = Messages.symbolsSetRotation(formatted),
                    selected = rotation.roundToInt() == degrees,
                    onClick = { onChange(degrees.toDouble()) },
                )
            }
        }
    }
}

/// Width and height sliders plus the "both axes" presets of the iOS builder.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskSizeFields(
    scaleX: Double,
    scaleY: Double,
    onScaleXChange: (Double) -> Unit,
    onScaleYChange: (Double) -> Unit,
) {
    val range = MIN_SYMBOL_SCALE.toFloat()..MAX_SYMBOL_SCALE.toFloat()
    // Tenths, like the iOS slider's 0.1 step.
    fun stepped(value: Float): Double = (value * 10f).roundToInt() / 10.0
    EditorCard(title = Messages.symbolsSize(), help = Messages.symbolsSizeHelp()) {
        DraftSlider(
            label = L10n.text("Width scale"),
            value = scaleX.toFloat().coerceIn(range.start, range.endInclusive),
            valueLabel = DisplayFormat.number(scaleX, 2) + "×",
            range = range,
            onChange = { onScaleXChange(stepped(it)) },
            onReset = { onScaleXChange(1.0) },
        )
        DraftSlider(
            label = L10n.text("Height scale"),
            value = scaleY.toFloat().coerceIn(range.start, range.endInclusive),
            valueLabel = DisplayFormat.number(scaleY, 2) + "×",
            range = range,
            onChange = { onScaleYChange(stepped(it)) },
            onReset = { onScaleYChange(1.0) },
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                Messages.symbolsBothScales(),
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            listOf(0.5, 1.0, 2.0, 5.0, 10.0).forEach { factor ->
                val formatted = DisplayFormat.number(factor, if (factor < 1.0) 1 else 0)
                PresetButton(
                    label = "$formatted×",
                    description = Messages.symbolsSetBothScales(formatted),
                    selected = scaleX == factor && scaleY == factor,
                    onClick = {
                        onScaleXChange(factor)
                        onScaleYChange(factor)
                    },
                )
            }
        }
    }
}

@Composable
private fun EditorCard(title: String, help: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        content()
        Text(help, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
    }
}

@Composable
private fun PresetButton(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = description
                this.selected = selected
            },
        contentPadding = PaddingValues(horizontal = 12.dp),
        shape = RoundedCornerShape(8.dp),
        colors = if (selected) {
            ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF0A84FF).copy(alpha = 0.25f))
        } else {
            ButtonDefaults.outlinedButtonColors()
        },
    ) {
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
internal fun MilitaryTypeFields(
    spec: MilitarySymbolSpec,
    onChange: (MilitarySymbolSpec) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(L10n.text("Unit Type"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PickerField(L10n.text("Affiliation"), spec.affiliation, SymbolAffiliation.entries, { it.displayName }, onSelected = {
            onChange(spec.copy(affiliation = it))
        })
        PickerField(L10n.text("Echelon"), spec.echelon, SymbolEchelon.entries, { it.displayName }, onSelected = {
            onChange(spec.copy(echelon = it))
        })
        PickerField(L10n.text("Function"), spec.function, SymbolFunction.pickerEntries, { it.displayName }, onSelected = {
            onChange(spec.copy(function = it))
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(L10n.text("Headquarters"), color = Color.White, modifier = Modifier.weight(1f))
            Switch(
                checked = spec.isHeadquarters,
                onCheckedChange = { onChange(spec.copy(isHeadquarters = it)) }
            )
        }
    }
}

@Composable
internal fun UnitAmplifierFields(
    higherFormation: String,
    uniqueIdentifier: String,
    reinforcementStatus: ReinforcementStatus,
    onHigherFormationChange: (String) -> Unit,
    onUniqueIdentifierChange: (String) -> Unit,
    onReinforcementStatusChange: (ReinforcementStatus) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(L10n.text("Unit Amplifiers"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = higherFormation,
            onValueChange = {
                onHigherFormationChange(boundUnitAmplifier(it, HIGHER_FORMATION_MAX_CODE_POINTS))
            },
            label = { Text(L10n.text("Higher formation / parent unit (M)")) },
            supportingText = {
                Text("${higherFormation.codePointCount(0, higherFormation.length)}/$HIGHER_FORMATION_MAX_CODE_POINTS")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = uniqueIdentifier,
            onValueChange = {
                onUniqueIdentifierChange(boundUnitAmplifier(it, UNIQUE_IDENTIFIER_MAX_CODE_POINTS))
            },
            label = { Text(L10n.text("Unique identifier / callsign (T)")) },
            supportingText = {
                Text("${uniqueIdentifier.codePointCount(0, uniqueIdentifier.length)}/$UNIQUE_IDENTIFIER_MAX_CODE_POINTS")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PickerField(
            label = L10n.text("Reinforced / reduced (F)"),
            selected = reinforcementStatus,
            values = ReinforcementStatus.entries,
            text = { it.displayName },
            onSelected = onReinforcementStatusChange,
        )
        Text(
            L10n.text("These labels use the separate Unit Amplifiers switch in Layers and Labels."),
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 11.sp,
        )
    }
}

@Composable
internal fun TaskTypeField(
    measure: TacticalControlMeasure,
    onChange: (TacticalControlMeasure) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(L10n.text("Task Type"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PickerField(L10n.text("Task"), measure, TacticalControlMeasure.pickerEntries, { it.displayName }, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkerTypeFields(
    set: MarkerSet,
    symbolId: String,
    colorHex: String,
    onSetChange: (MarkerSet) -> Unit,
    onSymbolChange: (String) -> Unit,
    onColorChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        var packRevision by remember { mutableStateOf(0) }
        CustomSymbolLibraryInfo(onLoaded = { packRevision++ })
        Text(L10n.text("Symbol Set"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PickerField(L10n.text("Set"), set, MarkerSet.entries.filter { it != MarkerSet.CUSTOM || com.tacmap.waypoints.CustomSymbolStore.entries().isNotEmpty() || set == it }, { it.displayName }, onSetChange)
        val entries = remember(set, packRevision) { MarkerCatalog.entries(set) }.ifEmpty { listOf(MarkerCatalog.entry(set, symbolId)) }
        val selectedEntry = entries.firstOrNull { it.id == symbolId } ?: entries[0]
        if (set == MarkerSet.CUSTOM) {
            CustomSymbolPicker(entries, selectedEntry, onSymbolChange)
        } else PickerField(L10n.text("Symbol"), selectedEntry, entries, { it.displayName }, { onSymbolChange(it.id) })
        if (set != MarkerSet.CUSTOM) {
        Text(L10n.text("Colour"), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        val swatches = MarkerCatalog.teamColors.map { it.second } + listOf("#8A93A6", "#111417")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            swatches.forEach { hex ->
                val selected = hex.equals(colorHex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = L10n.text("%1\$s marker colour", hex)
                            role = Role.RadioButton
                            this.selected = selected
                        }
                        .clickable { onColorChange(hex) }
                ) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(if (selected) 34.dp else 30.dp)
                            .clip(CircleShape)
                            .background(Color(hex.toColorInt()))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) Color.White else Color.White.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                    )
                }
            }
        }
        }
    }
}

@Composable
fun <T> PickerField(
    label: String,
    selected: T,
    values: List<T>,
    text: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = "$label, ${text(selected)}"
                    role = Role.Button
                }
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
                Text(
                    text(selected),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 420.dp)
        ) {
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(text(value)) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    }
                )
            }
        }
    }
}
