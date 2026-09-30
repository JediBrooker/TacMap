package com.tacmap.map

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.Sort
import com.tacmap.ui.AlertDialog
import com.tacmap.ui.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.BottomSheetDefaults
import com.tacmap.ui.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.drawings.DrawingLayer
import com.tacmap.localization.DisplayFormat
import com.tacmap.mgrs.MgrsFormatter
import com.tacmap.waypoints.SymbolListGroup
import com.tacmap.waypoints.SymbolListOrder
import com.tacmap.waypoints.SymbolListSorter
import com.tacmap.waypoints.Waypoint
import com.tacmap.waypoints.WaypointStore

/**
 * Bottom sheet listing all saved waypoints, like the iOS Symbology list: tap
 * a row to edit the symbol, swipe right to fly to it, swipe left to delete it
 * after a confirmation. TalkBack offers the swipes as custom actions. The add
 * buttons open the new-symbol builder at the current map centre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaypointListSheet(
    waypoints: List<Waypoint>,
    crosshairLat: Double,
    crosshairLng: Double,
    activeLayerId: String,
    layers: List<DrawingLayer>,
    store: WaypointStore,
    defaultTaskScale: Double,
    onDismiss: () -> Unit,
    onFlyTo: (lat: Double, lng: Double) -> Unit
) {
    var pendingEditor by remember { mutableStateOf<SymbolEditorMode?>(null) }
    var creationError by remember { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Waypoint?>(null) }
    var deleteError by remember { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var storedOrder by rememberPersistedString(
        SymbolListOrder.PREFERENCE_KEY,
        SymbolListOrder.DEFAULT.persisted,
    )
    val order = SymbolListOrder.fromPersisted(storedOrder)
    var orderMenuExpanded by remember { mutableStateOf(false) }
    val sections = SymbolListSorter.sections(
        waypoints = waypoints,
        order = order,
        layerOrder = layers.map { it.id },
        referenceLat = crosshairLat,
        referenceLng = crosshairLng,
    )
    val requestDelete: (Waypoint) -> Unit = { wp ->
        deleteError = null
        pendingDelete = wp
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            // Title
            Text(
                L10n.text("Symbology"),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            Text(
                L10n.text("Symbology (%1\$s)", waypoints.size),
                fontSize = 12.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )

            // List
            if (waypoints.isEmpty()) {
                Text(
                    L10n.text("No symbols yet. Pan the crosshair to a feature and add a marker, unit, or task below."),
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            } else {
                Box(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { orderMenuExpanded = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(Messages.symbolsSortByValue(order.displayName))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = orderMenuExpanded,
                        onDismissRequest = { orderMenuExpanded = false },
                    ) {
                        SymbolListOrder.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.displayName) },
                                onClick = {
                                    storedOrder = option.persisted
                                    orderMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    sections.forEach { section ->
                        sectionTitle(section.group, layers)?.let { title ->
                            item(key = "group:${section.group}") {
                                Text(
                                    title,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 2.dp)
                                )
                            }
                        }
                        items(section.waypoints, key = { it.id }) { wp ->
                            SwipeableWaypointRow(
                                wp = wp,
                                distanceLabel = if (order == SymbolListOrder.DISTANCE) {
                                    DisplayFormat.distance(
                                        SymbolListSorter.distanceMetres(
                                            crosshairLat, crosshairLng, wp.latitude, wp.longitude
                                        )
                                    )
                                } else {
                                    null
                                },
                                onEdit = { editingId = wp.id },
                                onFlyTo = { onFlyTo(wp.latitude, wp.longitude) },
                                onDelete = { requestDelete(wp) },
                            )
                        }
                    }
                }
                Text(
                    Messages.symbolsSwipeHelp(),
                    fontSize = 11.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            Spacer(Modifier.size(12.dp))

            AddSymbolButton(
                label = L10n.text("Military Unit"),
                icon = Icons.Default.Security,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = { creationError = null; pendingEditor = SymbolEditorMode.MILITARY }
            )
            Spacer(Modifier.size(8.dp))
            AddSymbolButton(
                label = L10n.text("Tactical Task"),
                icon = Icons.Default.Flag,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = { creationError = null; pendingEditor = SymbolEditorMode.TASK }
            )
            Spacer(Modifier.size(8.dp))
            AddSymbolButton(
                label = L10n.text("Marker (Airsoft / SAR / POI)"),
                icon = Icons.Default.Place,
                modifier = Modifier.padding(horizontal = 20.dp),
                onClick = { creationError = null; pendingEditor = SymbolEditorMode.MARKER }
            )
            Text(
                L10n.text("New symbols are placed at the current map centre."),
                fontSize = 11.sp,
                color = Color.Gray,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
    }

    // Follow the live list so an edit sees remote changes, and closes if the
    // symbol is deleted elsewhere.
    waypoints.firstOrNull { it.id == editingId }?.let { editing ->
        SelectedSymbolEditorDialog(
            waypoint = editing,
            layers = layers,
            onSave = store::update,
            onDelete = { store.remove(editing) },
            onDismiss = { editingId = null },
        )
    }

    pendingDelete?.let { wp ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(L10n.text("Delete symbol?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(L10n.text("This will permanently remove \"%1\$s\".", wp.name))
                    deleteError?.let { error ->
                        Text(error.text, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (store.remove(wp)) {
                        deleteError = null
                        pendingDelete = null
                    } else {
                        deleteError = Messages.displayTheSymbolCouldNotBeDeletedItRemainsOnMessage()
                    }
                }) { Text(L10n.text("Delete"), color = Color(0xFFFF5A5A)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(L10n.text("Cancel")) } },
        )
    }

    pendingEditor?.let { mode ->
        SymbolEditorDialog(
            mode = mode,
            initialName = "",
            crosshairLat = crosshairLat,
            crosshairLng = crosshairLng,
            title = Messages.symbolsNewSymbolTitle(),
            actionLabel = L10n.text("Place"),
            defaultTaskScale = defaultTaskScale,
            submissionError = creationError?.text,
            onDismiss = { creationError = null; pendingEditor = null },
            onConfirm = { draft ->
                val waypoint = draft.toWaypoint(crosshairLat, crosshairLng, activeLayerId)
                when (val result = persistNewSymbol(waypoint) { store.add(it) }) {
                    is DurableSymbolCreation.Saved -> {
                        creationError = null
                        pendingEditor = null
                        onDismiss()
                    }
                    is DurableSymbolCreation.Failed -> creationError = result.pendingMessage
                }
            }
        )
    }
}

@Composable
private fun AddSymbolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0A84FF))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
        Spacer(Modifier.size(8.dp))
        Text(L10n.text("Add %1\$s", label), color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}

private fun sectionTitle(group: SymbolListGroup, layers: List<DrawingLayer>): String? = when (group) {
    SymbolListGroup.All -> null
    is SymbolListGroup.Affiliation -> group.affiliation.title
    is SymbolListGroup.Layer -> layers.firstOrNull { it.id == group.layerId }?.displayName
    SymbolListGroup.OtherLayer -> Messages.symbolsGroupOther()
}

/**
 * A symbol row with the iOS swipe actions. Right (start to end) flies to the
 * symbol; left (end to start) asks to delete it. Neither leaves the row
 * dismissed: the row snaps back and the action (or its confirmation) takes
 * over, so a cancelled delete keeps the row in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableWaypointRow(
    wp: Waypoint,
    distanceLabel: String?,
    onEdit: () -> Unit,
    onFlyTo: () -> Unit,
    onDelete: () -> Unit,
) {
    val currentFlyTo by rememberUpdatedState(onFlyTo)
    val currentDelete by rememberUpdatedState(onDelete)
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentFlyTo()
                SwipeToDismissBoxValue.EndToStart -> currentDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = swipeState,
        backgroundContent = { SwipeActionBackground(swipeState.dismissDirection) },
    ) {
        WaypointRow(
            wp = wp,
            distanceLabel = distanceLabel,
            onTap = onEdit,
            onFlyTo = onFlyTo,
            onDelete = onDelete,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue) {
    val (color, icon, label, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            SwipeAction(Color(0xFF0A84FF), Icons.Default.MyLocation, Messages.symbolsFlyTo(), Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            SwipeAction(Color(0xFFD8281F), Icons.Default.Delete, L10n.text("Delete"), Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> return
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        contentAlignment = alignment,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Spacer(Modifier.size(8.dp))
            Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

private data class SwipeAction(
    val color: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val alignment: Alignment,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WaypointRow(
    wp: Waypoint,
    distanceLabel: String?,
    onTap: () -> Unit,
    onFlyTo: () -> Unit,
    onDelete: () -> Unit,
) {
    val flyToLabel = Messages.symbolsFlyTo()
    val deleteLabel = L10n.text("Delete")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The swipe reveals the list background, so the row paints its own.
            .background(BottomSheetDefaults.ContainerColor)
            .clickable(onClickLabel = L10n.text("Edit symbol")) { onTap() }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(flyToLabel) { onFlyTo(); true },
                    CustomAccessibilityAction(deleteLabel) { onDelete(); true },
                )
            }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The rendered symbol on white: task graphics are black line art.
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            WaypointKindIcon(kind = wp.kind, size = 32.dp)
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(wp.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(wp.kind.displayName, fontSize = 11.sp, color = Color.Gray)
            Text(
                MgrsFormatter.format(wp.latitude, wp.longitude) +
                    (wp.elevationLabel?.let { " • $it" } ?: ""),
                fontSize = 11.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )
        }
        distanceLabel?.let {
            Text(
                it,
                fontSize = 12.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .semantics { contentDescription = Messages.symbolsDistanceFromCentre(it) },
            )
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null,
             tint = Color.Gray)
    }
}
