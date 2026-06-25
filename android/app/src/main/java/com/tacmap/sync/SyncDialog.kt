package com.tacmap.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Join / create a unit sync room and show connection status. */
@Composable
fun SyncDialog(manager: SyncManager, onDismiss: () -> Unit) {
    val status by manager.status.collectAsState()
    val room by manager.room.collectAsState()
    var code by remember { mutableStateOf(room ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Unit Sync") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val (label, colour) = when (status) {
                    SyncManager.Status.CONNECTED -> "Connected" to Color(0xFF2E7D32)
                    SyncManager.Status.CONNECTING -> "Connecting…" to Color(0xFFEF6C00)
                    SyncManager.Status.OFFLINE -> "Offline" to Color(0xFF9E9E9E)
                }
                Text(label, color = colour, fontWeight = FontWeight.SemiBold)

                if (room != null) {
                    Text("Room code: ${room}", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(
                        onClick = { manager.leave() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Leave room") }
                } else {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Unit join code") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = code.isNotBlank(),
                        onClick = { manager.join(code) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Join / create room") }
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
