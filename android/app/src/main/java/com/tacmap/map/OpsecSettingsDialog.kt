package com.tacmap.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tacmap.settings.OpsecSettings

/** Privacy / operational-security settings: screen-capture blocking, opt-in
 *  online lookups, and the (self-hostable) sync relay URL. */
@Composable
fun OpsecSettingsDialog(opsec: OpsecSettings, onDismiss: () -> Unit) {
    val blockCapture by opsec.blockScreenCapture.collectAsState()
    val online by opsec.onlineLookups.collectAsState()
    val relay by opsec.relayUrl.collectAsState()
    var relayField by remember(relay) { mutableStateOf(relay) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { opsec.setRelayUrl(relayField); onDismiss() }) { Text("Done") }
        },
        title = { Text("Privacy & OPSEC") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(checked = blockCapture, onCheckedChange = { opsec.setBlockScreenCapture(it) })
                    Text("Block screenshots & recents preview")
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(checked = online, onCheckedChange = { opsec.setOnlineLookups(it) })
                    Text("Online terrain & weather lookups")
                }
                Text(
                    "When on, the map-centre coordinate (coarsened to ~110 m) is sent to " +
                        "Open-Meteo for elevation, weather and the terrain heat-map. Off by default.",
                    fontSize = 11.sp, color = Color.Gray
                )
                OutlinedTextField(
                    value = relayField,
                    onValueChange = { relayField = it },
                    label = { Text("Sync relay URL (self-host)") },
                    singleLine = true
                )
            }
        }
    )
}
