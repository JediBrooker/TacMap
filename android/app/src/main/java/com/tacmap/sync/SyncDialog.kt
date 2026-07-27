package com.tacmap.sync

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.waypoints.SymbolAffiliation
import com.tacmap.waypoints.SymbolEchelon
import com.tacmap.waypoints.SymbolFunction

/** Join / create a unit sync room and show connection status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncDialog(manager: SyncManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status by manager.status.collectAsState()
    val room by manager.room.collectAsState()
    var code by remember { mutableStateOf(room ?: "") }
    var codeError by remember { mutableStateOf<String?>(null) }
    var legacyConfirmed by remember { mutableStateOf(false) }

    // Local copies of the presence config fields, initialised from the manager.
    var callsign by remember { mutableStateOf(manager.presenceConfig.callsign) }
    var shareLocation by remember { mutableStateOf(manager.presenceConfig.shareLocation) }
    var affiliation by remember { mutableStateOf(manager.presenceConfig.affiliation) }
    var echelon by remember { mutableStateOf(manager.presenceConfig.echelon) }
    var function by remember { mutableStateOf(manager.presenceConfig.function) }
    var isHQ by remember { mutableStateOf(manager.presenceConfig.isHQ) }

    fun commitConfig() {
        manager.presenceConfig = PresenceConfig(
            callsign = callsign,
            shareLocation = shareLocation,
            affiliation = affiliation,
            echelon = echelon,
            function = function,
            isHQ = isHQ
        )
    }

    AlertDialog(
        onDismissRequest = {
            commitConfig()
            onDismiss()
        },
        confirmButton = {
            TextButton(onClick = {
                commitConfig()
                onDismiss()
            }) { Text("Done") }
        },
        title = { Text("Unit Sync") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val (label, colour) = when (status) {
                    SyncManager.Status.CONNECTED -> "Connected" to Color(0xFF2E7D32)
                    SyncManager.Status.SNAPSHOTTING -> "Verifying room snapshot…" to Color(0xFFEF6C00)
                    SyncManager.Status.CONNECTING -> "Connecting…" to Color(0xFFEF6C00)
                    SyncManager.Status.OFFLINE -> "Offline" to Color(0xFF9E9E9E)
                }
                Text(label, color = colour, fontWeight = FontWeight.SemiBold)

                val joinedRoom = room
                if (joinedRoom != null) {
                    OutlinedButton(
                        onClick = { copyRoomCode(context, joinedRoom) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            Text(
                                text = "Room code",
                                fontSize = 11.sp,
                                lineHeight = 12.sp,
                            )
                            Text(
                                text = joinedRoom,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy room code",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (joinedRoom.startsWith("2:")) {
                        Text("LEGACY ROOM: weaker replay, identity, and metadata protections.", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = { manager.leave() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Leave room") }
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; codeError = null; legacyConfirmed = false },
                        label = { Text("Unit join code") },
                        isError = codeError != null,
                        supportingText = {
                            Text(codeError
                                ?: "Share this with your unit. Tap Generate for a strong one.")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { code = SyncCrypto.generateJoinCode(); codeError = null; legacyConfirmed = false },
                            modifier = Modifier.weight(1f)
                        ) { Text("Generate") }
                        Button(
                            enabled = code.isNotBlank(),
                            onClick = {
                                if (!code.trim().startsWith("3:") && !code.trim().startsWith("2:")) {
                                    codeError = "Codes must start with 3:. Enter 2: only for an intentional legacy room."
                                } else if (code.trim().startsWith("2:") && !legacyConfirmed) {
                                    legacyConfirmed = true
                                    codeError = "Legacy v2 has weaker rollback and identity protection. Tap again to confirm legacy join."
                                } else if (SyncCrypto.isJoinCodeTooWeak(code)) {
                                    codeError = "Too short to be safe. Use at least " +
                                        "${SyncCrypto.MIN_JOIN_CODE_LEN} characters, or tap Generate."
                                } else {
                                    manager.join(code)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Join / create") }
                    }
                    if (code.trim().startsWith("2:")) {
                        Text("LEGACY ROOM: weaker replay, identity, and metadata protections.", color = Color.Red, fontWeight = FontWeight.Bold)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    "Replay protection only rejects signed sessions at or below epochs this device has already stored. Detecting an obsolete but previously unseen higher session requires external verification.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                // ----- Your Identity -----
                Text("Your Identity", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

                OutlinedTextField(
                    value = callsign,
                    onValueChange = { callsign = manager.boundCallsign(it); commitConfig() },
                    label = { Text("Callsign") },
                    placeholder = { Text("Alpha 1-1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Affiliation dropdown
                PresenceDropdown(
                    label = "Affiliation",
                    selected = affiliation.displayName,
                    options = SymbolAffiliation.entries.map { it.displayName },
                    onSelect = { idx ->
                        affiliation = SymbolAffiliation.entries[idx]
                        commitConfig()
                    }
                )

                // Echelon dropdown
                PresenceDropdown(
                    label = "Echelon",
                    selected = echelon.displayName,
                    options = SymbolEchelon.entries.map { it.displayName },
                    onSelect = { idx ->
                        echelon = SymbolEchelon.entries[idx]
                        commitConfig()
                    }
                )

                // Function dropdown (common subset)
                val commonFunctions = listOf(
                    SymbolFunction.INFANTRY,
                    SymbolFunction.ARMOUR,
                    SymbolFunction.ARTILLERY,
                    SymbolFunction.RECCE,
                    SymbolFunction.ENGINEER,
                    SymbolFunction.SIGNAL,
                    SymbolFunction.LOGISTICS,
                    SymbolFunction.MEDICAL,
                    SymbolFunction.AIR_DEFENCE,
                    SymbolFunction.AVIATION
                )
                PresenceDropdown(
                    label = "Function",
                    selected = function.displayName,
                    options = commonFunctions.map { it.displayName },
                    onSelect = { idx ->
                        function = commonFunctions[idx]
                        commitConfig()
                    }
                )

                // HQ switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Headquarters", fontSize = 13.sp)
                    Switch(
                        checked = isHQ,
                        onCheckedChange = { isHQ = it; commitConfig() }
                    )
                }

                // Share location switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Share my location", fontSize = 13.sp)
                    Switch(
                        checked = shareLocation,
                        onCheckedChange = { shareLocation = it; commitConfig() }
                    )
                }

                Text(
                    "Everyone who enters the same code shares a live, end-to-end-encrypted map. " +
                        "The relay only ever sees ciphertext.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    )
}

private fun copyRoomCode(context: Context, roomCode: String) {
    val appContext = context.applicationContext
    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (clipboard == null) {
        Toast.makeText(appContext, "Unable to copy unit code", Toast.LENGTH_SHORT).show()
        return
    }

    val clip = ClipData.newPlainText("Unit Sync room code", roomCode).apply {
        description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
    }
    clipboard.setPrimaryClip(clip)

    Handler(Looper.getMainLooper()).postDelayed({
        val currentClip = clipboard.primaryClip
        val currentText = if (currentClip != null && currentClip.itemCount > 0) {
            currentClip.getItemAt(0).coerceToText(appContext)?.toString()
        } else {
            null
        }
        if (currentText == roomCode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }
    }, ROOM_CODE_CLIPBOARD_TTL_MS)

    Toast.makeText(appContext, "Unit code copied", Toast.LENGTH_SHORT).show()
}

private const val ROOM_CODE_CLIPBOARD_TTL_MS = 60_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresenceDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}
