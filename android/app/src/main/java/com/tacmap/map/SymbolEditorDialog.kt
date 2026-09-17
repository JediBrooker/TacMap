package com.tacmap.map

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
import com.tacmap.waypoints.UNIQUE_IDENTIFIER_MAX_CODE_POINTS
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointKind
import com.tacmap.waypoints.boundUnitAmplifier
import com.tacmap.waypoints.normalizedUnitAmplifiersForKind

enum class SymbolEditorMode { MILITARY, TASK, MARKER }

@Composable
fun SymbolEditorDialog(
    mode: SymbolEditorMode,
    initialKind: WaypointKind,
    initialName: String,
    crosshairLat: Double?,
    crosshairLng: Double?,
    title: String,
    actionLabel: String,
    fullScreen: Boolean = true,
    submissionError: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        name: String,
        kind: WaypointKind,
        higherFormation: String?,
        uniqueIdentifier: String?,
        reinforcementStatus: ReinforcementStatus,
    ) -> Unit
) {
    var name by remember(initialName, initialKind) { mutableStateOf(initialName) }
    var militarySpec by remember(initialKind) {
        mutableStateOf((initialKind as? WaypointKind.Military)?.spec ?: MilitarySymbolSpec())
    }
    var measure by remember(initialKind) {
        mutableStateOf((initialKind as? WaypointKind.ControlMeasure)?.measure ?: TacticalControlMeasure.ASSEMBLY_AREA)
    }
    val initialMarker = (initialKind as? WaypointKind.Marker)?.marker
    var markerSet by remember(initialKind) { mutableStateOf(initialMarker?.set ?: MarkerSet.AIRSOFT) }
    var markerSymbolId by remember(initialKind) { mutableStateOf(initialMarker?.symbolId ?: "team") }
    var markerColor by remember(initialKind) { mutableStateOf(initialMarker?.colorHex ?: "#3B7BE0") }
    var higherFormation by remember(initialKind) { mutableStateOf("") }
    var uniqueIdentifier by remember(initialKind) { mutableStateOf("") }
    var reinforcementStatus by remember(initialKind) { mutableStateOf(ReinforcementStatus.NONE) }

    val currentKind = when (mode) {
        SymbolEditorMode.MILITARY -> WaypointKind.Military(militarySpec)
        SymbolEditorMode.TASK -> WaypointKind.ControlMeasure(measure)
        SymbolEditorMode.MARKER -> WaypointKind.Marker(MarkerSymbol(markerSet, markerSymbolId, markerColor))
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
                    /// Live preview of the symbol about to be placed.
                    /// Updates whenever affiliation/echelon/function/HQ
                    /// changes. Generic waypoints just show the pin.
                    kind = currentKind,
                    fallbackIcon = when (mode) {
                        SymbolEditorMode.MILITARY -> Icons.Default.Security
                        SymbolEditorMode.TASK -> Icons.Default.Flag
                        SymbolEditorMode.MARKER -> Icons.Default.Place
                    },
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
                            label = { Text("Title") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
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
                                Text("Placed at crosshair", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
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

                    when (mode) {
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
                        SymbolEditorMode.TASK -> item {
                            TaskTypeField(measure = measure, onChange = { measure = it })
                        }
                        SymbolEditorMode.MARKER -> item {
                            MarkerTypeFields(
                                set = markerSet,
                                symbolId = markerSymbolId,
                                colorHex = markerColor,
                                onSetChange = { newSet ->
                                    markerSet = newSet
                                    val first = MarkerCatalog.entries(newSet)[0]
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
                                        .semantics { contentDescription = "Symbol save error: $error" },
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
                                    Text("Cancel")
                                }
                                Button(
                                    onClick = {
                                        val trimmed = name.trim()
                                        val resolved = if (trimmed == initialKind.displayName) {
                                            currentKind.displayName
                                        } else {
                                            trimmed.ifEmpty { currentKind.displayName }
                                        }
                                        val amplifiers = normalizedUnitAmplifiersForKind(
                                            kind = currentKind,
                                            higherFormation = higherFormation,
                                            uniqueIdentifier = uniqueIdentifier,
                                            reinforcementStatus = reinforcementStatus,
                                        )
                                        onConfirm(
                                            resolved,
                                            currentKind,
                                            amplifiers.higherFormation,
                                            amplifiers.uniqueIdentifier,
                                            amplifiers.reinforcementStatus,
                                        )
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
    kind: WaypointKind,
    fallbackIcon: ImageVector,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SymbolPreviewTile(kind = kind, fallbackIcon = fallbackIcon)
        Spacer(Modifier.size(12.dp))
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
            Icon(Icons.Default.Close, contentDescription = "Close symbol editor", tint = Color.White)
        }
    }
}

/// White tile showing the symbol that'll be placed. Military = rendered
/// SIDC frame + glyph, tasks = the task graphic. White bg for both b/c
/// task graphics are black-on-transparent and unreadable on dark.
///
/// The bitmaps have transparent padding (HQ pole reserve, echelon dots
/// etc) so we crop to visible pixels before scaling. Otherwise the
/// glyph huddles in one corner of the tile.
@Composable
internal fun SymbolPreviewTile(
    kind: WaypointKind,
    fallbackIcon: ImageVector
) {
    val context = LocalContext.current
    val bitmap = remember(kind) {
        if (kind is WaypointKind.Generic) return@remember null
        val placeholder = Waypoint(
            name = "",
            latitude = 0.0,
            longitude = 0.0,
            kind = kind
        )
        /// Reuse the factory's already-rasterised bitmap instead of
        /// allocating a throwaway copy. Don't recycle it tho, the
        /// factory's cache owns it.
        val drawable = SymbolIconFactory.drawableFor(context, placeholder)
        val full = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return@remember null
        /// Crop transparent padding so Fit scales the visible glyph,
        /// not the whole padded bitmap.
        val visible = SymbolIconFactory.visibleBoundsFor(context, placeholder)
        if (visible.width() in 1..(full.width) && visible.height() in 1..(full.height)) {
            android.graphics.Bitmap.createBitmap(
                full,
                visible.left.coerceAtLeast(0),
                visible.top.coerceAtLeast(0),
                visible.width().coerceAtMost(full.width - visible.left),
                visible.height().coerceAtMost(full.height - visible.top)
            )
        } else full
    }

    Box(
        modifier = Modifier
            .size(56.dp)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(fallbackIcon, contentDescription = null, tint = Color.Black)
        }
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
        Text("Unit Type", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PickerField("Affiliation", spec.affiliation, SymbolAffiliation.entries, { it.displayName }, onSelected = {
            onChange(spec.copy(affiliation = it))
        })
        PickerField("Echelon", spec.echelon, SymbolEchelon.entries, { it.displayName }, onSelected = {
            onChange(spec.copy(echelon = it))
        })
        PickerField("Function", spec.function, SymbolFunction.pickerEntries, { it.displayName }, onSelected = {
            onChange(spec.copy(function = it))
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Headquarters", color = Color.White, modifier = Modifier.weight(1f))
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
        Text("Unit Amplifiers", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = higherFormation,
            onValueChange = {
                onHigherFormationChange(boundUnitAmplifier(it, HIGHER_FORMATION_MAX_CODE_POINTS))
            },
            label = { Text("Higher formation / parent unit (M)") },
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
            label = { Text("Unique identifier / callsign (T)") },
            supportingText = {
                Text("${uniqueIdentifier.codePointCount(0, uniqueIdentifier.length)}/$UNIQUE_IDENTIFIER_MAX_CODE_POINTS")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        PickerField(
            label = "Reinforced / reduced (F)",
            selected = reinforcementStatus,
            values = ReinforcementStatus.entries,
            text = { it.displayName },
            onSelected = onReinforcementStatusChange,
        )
        Text(
            "These labels use the separate Unit Amplifiers switch in Layers and Labels.",
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
        Text("Task Type", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PickerField("Task", measure, TacticalControlMeasure.pickerEntries, { it.displayName }, onChange)
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
        Text("Symbol Set", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        PickerField("Set", set, MarkerSet.entries.toList(), { it.displayName }, onSetChange)
        val entries = MarkerCatalog.entries(set)
        val selectedEntry = entries.firstOrNull { it.id == symbolId } ?: entries[0]
        PickerField("Symbol", selectedEntry, entries, { it.displayName }, { onSymbolChange(it.id) })
        Text("Colour", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
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
                            contentDescription = "$hex marker colour"
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
