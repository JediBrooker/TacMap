package com.tacmap.map

import com.tacmap.localization.Messages
import com.tacmap.localization.DisplayFormat

import com.tacmap.localization.L10n

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tacmap.drawings.DrawingDocument
import com.tacmap.drawings.DrawingLayer
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.waypoints.MarkerSet
import com.tacmap.waypoints.MarkerSymbol
import com.tacmap.waypoints.MilitarySymbolSpec
import com.tacmap.waypoints.TaskColor
import com.tacmap.waypoints.TacticalControlMeasure
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind

private enum class SelectedKindCategory(private val displayNameKey: String) {
    GENERIC("Waypoint"),
    MILITARY("Military Unit"),
    CONTROL_MEASURE("Tactical Task"),
    MARKER("Marker");

    val displayName: String get() = L10n.text(displayNameKey)
}

private val WaypointKind.selectedCategory: SelectedKindCategory
    get() = when (this) {
        WaypointKind.Generic -> SelectedKindCategory.GENERIC
        is WaypointKind.Military -> SelectedKindCategory.MILITARY
        is WaypointKind.ControlMeasure -> SelectedKindCategory.CONTROL_MEASURE
        is WaypointKind.Marker -> SelectedKindCategory.MARKER
    }

@Composable
internal fun SelectedSymbolEditorDialog(
    waypoint: Waypoint,
    layers: List<DrawingLayer>,
    onSave: (Waypoint) -> Boolean,
    onDelete: () -> Boolean,
    onDismiss: () -> Unit,
) {
    var draft by remember(waypoint.id) { mutableStateOf(SymbolEditDraft(waypoint)) }
    var validationError by remember(waypoint.id) { mutableStateOf<String?>(null) }
    var deleteError by remember(waypoint.id) { mutableStateOf<String?>(null) }
    var confirmDelete by remember(waypoint.id) { mutableStateOf(false) }
    val safeLayers = layers.ifEmpty { DrawingDocument.defaultLayers() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF16161A)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(L10n.text("Edit symbol"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(draft.kindDisplayName, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp)
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = L10n.text("Close symbol editor"), tint = Color.White)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(L10n.text("Cancel")) }
                    Button(
                        onClick = {
                            when (val result = draft.normalized(safeLayers)) {
                                is SymbolDraftResult.Invalid -> validationError = result.message
                                is SymbolDraftResult.Valid -> {
                                    if (onSave(result.waypoint)) onDismiss()
                                    else validationError = L10n.text("The symbol could not be saved. Try again.")
                                }
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(L10n.text("Save")) }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f).imePadding(),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        top = 12.dp,
                        end = 20.dp,
                        bottom = 72.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = { draft = draft.copy(name = it); validationError = null },
                            label = { Text(L10n.text("Name")) },
                            placeholder = { Text(draft.kindDisplayName) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PickerField(
                                label = L10n.text("Kind"),
                                selected = draft.kind.selectedCategory,
                                values = SelectedKindCategory.entries,
                                text = { it.displayName },
                                onSelected = { category ->
                                    val kind = when (category) {
                                        SelectedKindCategory.GENERIC -> WaypointKind.Generic
                                        SelectedKindCategory.MILITARY -> WaypointKind.Military(MilitarySymbolSpec())
                                        SelectedKindCategory.CONTROL_MEASURE ->
                                            WaypointKind.ControlMeasure(TacticalControlMeasure.ASSEMBLY_AREA)
                                        SelectedKindCategory.MARKER -> WaypointKind.Marker(MarkerSymbol())
                                    }
                                    if (category != draft.kind.selectedCategory) draft = draft.changingKind(kind)
                                },
                            )
                            when (val kind = draft.kind) {
                                WaypointKind.Generic -> Unit
                                is WaypointKind.Military -> MilitaryTypeFields(kind.spec) {
                                    draft = draft.changingKind(WaypointKind.Military(it))
                                }
                                is WaypointKind.ControlMeasure -> TaskTypeField(kind.measure) {
                                    draft = draft.changingKind(WaypointKind.ControlMeasure(it))
                                }
                                is WaypointKind.Marker -> MarkerTypeFields(
                                    set = kind.marker.set,
                                    symbolId = kind.marker.symbolId,
                                    colorHex = kind.marker.colorHex,
                                    onSetChange = { set ->
                                        val first = com.tacmap.waypoints.MarkerCatalog.entries(set).first()
                                        draft = draft.changingKind(
                                            WaypointKind.Marker(MarkerSymbol(set, first.id, first.defaultColor))
                                        )
                                    },
                                    onSymbolChange = { id ->
                                        draft = draft.changingKind(
                                            WaypointKind.Marker(kind.marker.copy(symbolId = id))
                                        )
                                    },
                                    onColorChange = { color ->
                                        draft = draft.changingKind(
                                            WaypointKind.Marker(kind.marker.copy(colorHex = color))
                                        )
                                    },
                                )
                            }
                        }
                    }
                    if (draft.kind is WaypointKind.Military) {
                        item {
                            UnitAmplifierFields(
                                higherFormation = draft.higherFormation,
                                uniqueIdentifier = draft.uniqueIdentifier,
                                reinforcementStatus = draft.reinforcementStatus,
                                onHigherFormationChange = {
                                    draft = draft.copy(higherFormation = it)
                                },
                                onUniqueIdentifierChange = {
                                    draft = draft.copy(uniqueIdentifier = it)
                                },
                                onReinforcementStatusChange = {
                                    draft = draft.copy(reinforcementStatus = it)
                                },
                            )
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = draft.mgrsInput,
                            onValueChange = {
                                draft = draft.copy(mgrsInput = it)
                                validationError = null
                            },
                            label = { Text(L10n.text("Move to MGRS")) },
                            placeholder = { Text(MgrsFormatter.format(draft.latitude, draft.longitude)) },
                            supportingText = {
                                Text(
                                    if (validationError == MGRS_MOVE_VALIDATION_ERROR) {
                                        MGRS_MOVE_VALIDATION_ERROR
                                    } else {
                                        L10n.text("4, 6, 8, or 10 figures; shorthand uses this graphic's local grid square.")
                                    }
                                )
                            },
                            isError = validationError == MGRS_MOVE_VALIDATION_ERROR,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = draft.notes,
                            onValueChange = { draft = draft.copy(notes = it) },
                            label = { Text(L10n.text("Notes")) },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = draft.elevationText,
                            onValueChange = { draft = draft.copy(elevationText = it); validationError = null },
                            label = { Text(L10n.text("Elevation (metres)")) },
                            singleLine = true,
                            isError = validationError == ELEVATION_VALIDATION_ERROR,
                            supportingText = validationError
                                ?.takeIf { it != MGRS_MOVE_VALIDATION_ERROR }
                                ?.let { error -> { Text(error) } }
                                ?: { Text(Messages.decimalInputHint()) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        val selectedLayer = safeLayers.firstOrNull { it.id == draft.layerId }
                            ?: safeLayers.first()
                        PickerField(
                            label = L10n.text("Layer"),
                            selected = selectedLayer,
                            values = safeLayers,
                            text = { it.name },
                            onSelected = { draft = draft.copy(layerId = it.id) },
                        )
                    }
                    if (draft.kind is WaypointKind.ControlMeasure) {
                        item {
                            TaskColorPicker(draft.taskColor) { draft = draft.copy(taskColor = it) }
                        }
                        item {
                            DraftSlider(
                                label = L10n.text("Rotation"),
                                value = draft.rotationDegrees.toFloat().coerceIn(0f, 360f),
                                valueLabel = "${normalizedDegrees(draft.rotationDegrees).toInt()}°",
                                range = 0f..360f,
                                onChange = { draft = draft.copy(rotationDegrees = it.toDouble()) },
                                onReset = { draft = draft.copy(rotationDegrees = 0.0) },
                            )
                        }
                        item {
                            DraftSlider(
                                label = L10n.text("Width scale"),
                                value = draft.scaleX.toFloat().coerceIn(MIN_SYMBOL_SCALE.toFloat(), MAX_SYMBOL_SCALE.toFloat()),
                                valueLabel = DisplayFormat.number(draft.scaleX, 2) + "x",
                                range = MIN_SYMBOL_SCALE.toFloat()..MAX_SYMBOL_SCALE.toFloat(),
                                onChange = { draft = draft.copy(scaleX = it.toDouble()) },
                                onReset = { draft = draft.copy(scaleX = 1.0) },
                            )
                        }
                        item {
                            DraftSlider(
                                label = L10n.text("Height scale"),
                                value = draft.scaleY.toFloat().coerceIn(MIN_SYMBOL_SCALE.toFloat(), MAX_SYMBOL_SCALE.toFloat()),
                                valueLabel = DisplayFormat.number(draft.scaleY, 2) + "x",
                                range = MIN_SYMBOL_SCALE.toFloat()..MAX_SYMBOL_SCALE.toFloat(),
                                onChange = { draft = draft.copy(scaleY = it.toDouble()) },
                                onReset = { draft = draft.copy(scaleY = 1.0) },
                            )
                        }
                    }
                    item {
                        OutlinedButton(
                            onClick = {
                                deleteError = null
                                confirmDelete = true
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5A5A)),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(L10n.text("Delete symbol"))
                        }
                    }
                }

            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(L10n.text("Delete symbol?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L10n.text("This will permanently remove \"%1\$s\".", waypoint.name))
                    deleteError?.let { error ->
                        Text(error, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (onDelete()) {
                        deleteError = null
                        confirmDelete = false
                        onDismiss()
                    } else {
                        deleteError = L10n.text("The symbol could not be deleted. It remains on the map.")
                    }
                }) { Text(L10n.text("Delete"), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(L10n.text("Cancel")) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskColorPicker(selectedColor: TaskColor, onSelect: (TaskColor) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(L10n.text("Task colour"), color = Color.White, fontWeight = FontWeight.SemiBold)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TaskColor.entries.forEach { taskColor ->
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = L10n.text("%1\$s task colour", taskColor.displayName)
                            selected = taskColor == selectedColor
                            role = Role.RadioButton
                        }
                        .clickable { onSelect(taskColor) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(if (taskColor == selectedColor) 34.dp else 28.dp)
                            .clip(CircleShape)
                            .background(Color(taskColor.argb))
                    )
                }
            }
        }
    }
}

@Composable
private fun DraftSlider(
    label: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val resetLabel = resetAccessibilityLabel(label)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f))
            Text(valueLabel, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            TextButton(
                onClick = onReset,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = resetLabel },
            ) { Text(L10n.text("Reset")) }
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = label
                    stateDescription = valueLabel
                },
        )
    }
}

internal fun resetAccessibilityLabel(label: String): String = when (label) {
    L10n.text("Rotation") -> L10n.text("Reset rotation")
    L10n.text("Width scale") -> L10n.text("Reset width")
    L10n.text("Height scale") -> L10n.text("Reset height")
    else -> L10n.text("Reset %1\$s", label)
}
