package com.tacticalmaps.map

import androidx.compose.foundation.Image
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
import com.tacticalmaps.waypoints.Waypoint
import com.tacticalmaps.waypoints.WaypointKind

enum class SymbolEditorMode { MILITARY, TASK }

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
        SymbolEditorMode.MILITARY -> WaypointKind.Military(militarySpec)
        SymbolEditorMode.TASK -> WaypointKind.ControlMeasure(measure)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        /// `DialogProperties.decorFitsSystemWindows = false` alone isn't
        /// enough on every Compose / device combination — explicitly tell
        /// the dialog's underlying Window not to inset for system bars so
        /// `WindowInsets.systemBars` reports the real bottom inset, and
        /// our padding below leaves room for the gesture pill.
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
                    /// Render a LIVE preview of the actual symbol the
                    /// user is about to place — the rendered military
                    /// frame / function glyph for units, or the task
                    /// graphic for tactical tasks. Updates whenever
                    /// affiliation / echelon / function / HQ / task
                    /// changes below. Generic waypoints fall back to
                    /// the pin glyph because there's no per-instance
                    /// symbol to show.
                    kind = currentKind,
                    fallbackIcon = when (mode) {
                        SymbolEditorMode.MILITARY -> Icons.Default.Security
                        SymbolEditorMode.TASK -> Icons.Default.Flag
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
                        SymbolEditorMode.MILITARY -> item {
                            MilitaryTypeFields(spec = militarySpec, onChange = { militarySpec = it })
                        }
                        SymbolEditorMode.TASK -> item {
                            TaskTypeField(measure = measure, onChange = { measure = it })
                        }
                    }

                    /// Action buttons live inside the scrollable LazyColumn
                    /// so they always sit directly under the last form
                    /// field rather than pinned to the screen bottom, where
                    /// the gesture pill clipped them on devices whose
                    /// dialog window doesn't honour edge-to-edge insets.
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
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
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

/// White-backed tile that shows the symbol that will be placed if the
/// user taps "Place". For military units this is the rendered SIDC
/// frame + function glyph; for tactical tasks it's the task graphic.
/// The background is white in both cases — military glyphs already
/// render on white in the SDIC spec, and task graphics are black on
/// transparent so they need a non-dark backdrop to actually read.
///
/// The rendered bitmaps include transparent padding around the
/// visible glyph (HQ-pole reserve, echelon dot space, etc.). We
/// crop to the visible-pixel bounding box before scaling so the
/// glyph fills the tile instead of huddling in one corner.
@Composable
private fun SymbolPreviewTile(
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
        /// The factory already rasterised the glyph into a BitmapDrawable,
        /// so reuse its bitmap instead of allocating and re-drawing into a
        /// throwaway copy. (Don't recycle it — it's owned by the factory's
        /// icon cache.)
        val drawable = SymbolIconFactory.drawableFor(context, placeholder)
        val full = (drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?: return@remember null
        /// Crop transparent padding so ContentScale.Fit scales the
        /// VISIBLE glyph (not the bitmap-frame-including-padding) up
        /// to fill the preview tile.
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
