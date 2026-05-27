package com.tacticalmaps.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tacticalmaps.mgrs.MgrsFormatter
import com.tacticalmaps.waypoints.MilitarySymbolSpec
import com.tacticalmaps.waypoints.SymbolAffiliation
import com.tacticalmaps.waypoints.SymbolEchelon
import com.tacticalmaps.waypoints.SymbolFunction
import com.tacticalmaps.waypoints.TacticalControlMeasure
import com.tacticalmaps.waypoints.WaypointKind

enum class SymbolEditorMode { WAYPOINT, MILITARY, TASK }

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
    onDismiss: () -> Unit,
    onConfirm: (name: String, kind: WaypointKind) -> Unit
) {
    var name by remember(initialName, initialKind) { mutableStateOf(initialName) }
    var militarySpec by remember(initialKind) {
        mutableStateOf((initialKind as? WaypointKind.Military)?.spec ?: MilitarySymbolSpec())
    }
    var measure by remember(initialKind) {
        mutableStateOf((initialKind as? WaypointKind.ControlMeasure)?.measure ?: TacticalControlMeasure.ASSEMBLY_AREA)
    }

    val currentKind = when (mode) {
        SymbolEditorMode.WAYPOINT -> WaypointKind.Generic
        SymbolEditorMode.MILITARY -> WaypointKind.Military(militarySpec)
        SymbolEditorMode.TASK -> WaypointKind.ControlMeasure(measure)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
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
                        .statusBarsPadding()
                } else {
                    Modifier.fillMaxWidth()
                }
            ) {
                EditorTopBar(
                    title = title,
                    subtitle = currentKind.displayName,
                    icon = when (mode) {
                        SymbolEditorMode.WAYPOINT -> Icons.Default.LocationOn
                        SymbolEditorMode.MILITARY -> Icons.Default.Security
                        SymbolEditorMode.TASK -> Icons.Default.Flag
                    },
                    onDismiss = onDismiss
                )

                LazyColumn(
                    modifier = if (fullScreen) Modifier.weight(1f) else Modifier.heightIn(max = 440.dp),
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
                        SymbolEditorMode.WAYPOINT -> Unit
                        SymbolEditorMode.MILITARY -> item {
                            MilitaryTypeFields(spec = militarySpec, onChange = { militarySpec = it })
                        }
                        SymbolEditorMode.TASK -> item {
                            TaskTypeField(measure = measure, onChange = { measure = it })
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
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
                            onConfirm(resolved, currentKind)
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

@Composable
private fun EditorTopBar(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Color.White, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.Black)
        }
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
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

@Composable
private fun MilitaryTypeFields(
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
private fun TaskTypeField(
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
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
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
