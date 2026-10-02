package com.tacmap.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.calibration.EntryMenuAction
import com.tacmap.calibration.EntryRowTap
import com.tacmap.calibration.EntryState
import com.tacmap.localization.L10n
import com.tacmap.localization.Messages
import com.tacmap.ui.AlertDialog
import com.tacmap.ui.DropdownMenu

/** one Layers row, already turned into text (contract s10) */
internal data class ImportedMapRowUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val sizeText: String,
    val active: Boolean,
    /** baked tiles sit under the PDF they came from */
    val indented: Boolean,
    val state: EntryState,
    val rowTap: EntryRowTap,
    val menu: List<EntryMenuAction>,
    /** E13: what the Generate offline tiles item does right now (Cancel while it's this one's bake) */
    val generate: ImportUiRules.GenerateMenu = ImportUiRules.GenerateMenu.ENABLED,
)

internal data class ImportedMapsUi(
    val rows: List<ImportedMapRowUi>,
    val footer: String?,
    /** library locked / corrupt: Layers says so instead of listing anything */
    val locked: Boolean,
)

/**
 * Layers -> Imported maps (replaces every retained / Unload / Delete-PDF control).
 * Imported maps stay until deleted (D5-03), delete always confirms (D5-04).
 */
@Composable
internal fun ImportedMapsSection(
    ui: ImportedMapsUi,
    onRowTap: (ImportedMapRowUi) -> Unit,
    onAction: (ImportedMapRowUi, EntryMenuAction) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ImportedMapRowUi?>(null) }
    var pendingEmbedded by remember { mutableStateOf<ImportedMapRowUi?>(null) }
    Text(
        Messages.mapLibrarySection(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    when {
        ui.locked -> Text(Messages.mapLibraryLocked(), fontSize = 13.sp, color = Color(0xFFFF9800))
        ui.rows.isEmpty() -> Text(Messages.mapLibraryEmpty(), fontSize = 13.sp, color = Color(0xFF8A938A))
        else -> ui.rows.forEach { row ->
            ImportedMapRow(
                row = row,
                onTap = { onRowTap(row) },
                onAction = { action ->
                    when (action) {
                        EntryMenuAction.DELETE -> pendingDelete = row
                        // throwing away a hand calibration asks first too (same as iOS)
                        EntryMenuAction.USE_EMBEDDED -> pendingEmbedded = row
                        else -> onAction(row, action)
                    }
                },
            )
        }
    }
    ui.footer?.takeIf { !ui.locked && ui.rows.isNotEmpty() }?.let {
        Text(it, fontSize = 11.sp, color = Color(0xFF8A938A), modifier = Modifier.padding(top = 6.dp))
    }
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(Messages.mapDeleteTitle(row.name)) },
            text = { Text(Messages.mapDeleteMessage()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onAction(row, EntryMenuAction.DELETE)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF5A5A)),
                ) { Text(L10n.text("Delete")) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(L10n.text("Cancel")) } },
        )
    }
    pendingEmbedded?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingEmbedded = null },
            text = { Text(Messages.mapUseEmbeddedConfirm()) },
            confirmButton = {
                TextButton(onClick = {
                    pendingEmbedded = null
                    onAction(row, EntryMenuAction.USE_EMBEDDED)
                }) { Text(Messages.mapActionUseEmbedded()) }
            },
            dismissButton = { TextButton(onClick = { pendingEmbedded = null }) { Text(L10n.text("Cancel")) } },
        )
    }
}

@Composable
private fun ImportedMapRow(row: ImportedMapRowUi, onTap: () -> Unit, onAction: (EntryMenuAction) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val warn = row.state == EntryState.UNAVAILABLE || row.state == EntryState.REJECTED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = row.rowTap != EntryRowTap.NONE, onClick = onTap)
            .padding(start = if (row.indented) 24.dp else 0.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = row.active, onClick = if (row.rowTap != EntryRowTap.NONE) onTap else null)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(row.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                row.subtitle,
                fontSize = 11.sp,
                color = if (warn) Color(0xFFFF9800) else Color(0xFF8A938A),
                maxLines = 2,
            )
        }
        Text(row.sizeText, fontSize = 11.sp, color = Color(0xFF8A938A), modifier = Modifier.padding(horizontal = 4.dp))
        Box {
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.size(48.dp).semantics { contentDescription = row.name },
            ) { Icon(Icons.Default.MoreVert, contentDescription = null) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                row.menu.forEach { action ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (action) {
                                    EntryMenuAction.CALIBRATE -> Messages.mapActionCalibrate()
                                    EntryMenuAction.CHOOSE_PAGE -> Messages.mapActionChoosePage()
                                    EntryMenuAction.GENERATE_TILES ->
                                        if (row.generate == ImportUiRules.GenerateMenu.CANCEL) L10n.text("Cancel")
                                        else Messages.pdfBakeGenerateButton()
                                    EntryMenuAction.USE_EMBEDDED -> Messages.mapActionUseEmbedded()
                                    EntryMenuAction.DELETE -> Messages.mapActionDelete()
                                },
                                color = if (action == EntryMenuAction.DELETE) Color(0xFFFF5A5A) else Color.Unspecified,
                            )
                        },
                        // greyed out, not hidden, while a bake is busy or this map failed (like iOS)
                        enabled = action != EntryMenuAction.GENERATE_TILES || row.generate != ImportUiRules.GenerateMenu.DISABLED,
                        onClick = {
                            menuOpen = false
                            onAction(action)
                        },
                    )
                }
            }
        }
    }
}
