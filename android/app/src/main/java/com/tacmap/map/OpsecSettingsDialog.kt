package com.tacmap.map

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import com.tacmap.settings.OpsecSettings
import com.tacmap.util.DataKey

/** Privacy / operational-security settings: screen-capture blocking, opt-in
 *  online lookups and basemaps, at-rest key binding, and the (self-hostable)
 *  sync relay URL. */
@Composable
fun OpsecSettingsDialog(
    opsec: OpsecSettings,
    onRequestAuthBoundChange: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val blockCapture by opsec.blockScreenCapture.collectAsState()
    val online by opsec.onlineLookups.collectAsState()
    val onlineBasemaps by opsec.onlineBasemaps.collectAsState()
    val relay by opsec.relayUrl.collectAsState()
    var relayField by remember(relay) { mutableStateOf(relay) }

    var authBound by remember { mutableStateOf(DataKey.isAuthBound) }
    var keyError by remember { mutableStateOf<String?>(null) }
    val authController = remember {
        AuthBoundChangeController(object : AuthBoundChangeController.KeyProtection {
            override val isAuthBound: Boolean get() = DataKey.isAuthBound
            override fun setAuthBound(enabled: Boolean) = DataKey.setAuthBound(enabled)
        })
    }

    // Re-wrap the data key once the user has cleared the device credential prompt.
    val credentialLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val completion = authController.completeCredential(result.resultCode == Activity.RESULT_OK)
        authBound = completion.isAuthBound
        keyError = completion.error
    }

    fun requestAuthBound(target: Boolean) {
        keyError = null
        if (onRequestAuthBoundChange != null) {
            onRequestAuthBoundChange(target)
            return
        }
        val keyguard = context.getSystemService<KeyguardManager>()
        when (val request = authController.request(target, keyguard?.isDeviceSecure == true)) {
            AuthBoundChangeController.Request.NoChange -> authBound = DataKey.isAuthBound
            is AuthBoundChangeController.Request.Error -> keyError = request.message
            AuthBoundChangeController.Request.PromptCredential -> {
                // Always prompt before either direction. In particular, a cached
                // DEK must never let an unattended user weaken auth-bound storage.
                @Suppress("DEPRECATION")
                val intent = keyguard?.createConfirmDeviceCredentialIntent(
                    "Confirm mission-data protection change",
                    "Authenticate to change how the mission-data key is protected."
                )
                if (intent != null) {
                    credentialLauncher.launch(intent)
                } else {
                    authController.cancelPending()
                    keyError = "No device lockscreen is set, so this can't be changed."
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { opsec.setRelayUrl(relayField); onDismiss() }) { Text("Done") }
        },
        title = { Text(PRIVACY_OPSEC_LABEL) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingRow(blockCapture, { opsec.setBlockScreenCapture(it) }, "Block screenshots & recents preview")

                SettingRow(online, { opsec.setOnlineLookups(it) }, "Online terrain & weather lookups")
                Caption(
                    "When on, the map-centre coordinate (coarsened to ~110 m) is sent to " +
                        "Open-Meteo for elevation, weather and the terrain heat-map. Off by default."
                )

                SettingRow(onlineBasemaps, { opsec.setOnlineBasemaps(it) }, "Online basemap tiles")
                Caption(
                    "Off by default. While off the map only draws imported offline maps, and " +
                        "no tile request leaves the device. Turning it on lets Google, Esri or " +
                        "OpenTopoMap see the ground you are looking at, from your IP."
                )

                SettingRow(authBound, { requestAuthBound(it) }, "Require unlock to decrypt mission data")
                Caption(
                    "Off: waypoints, drawings and tracks are encrypted with a key the device " +
                        "keystore releases to this app automatically. A filesystem dump or a " +
                        "backup gets ciphertext, but an attacker who roots the device and runs " +
                        "code as this app can still decrypt.\n\n" +
                        "On: the secure hardware refuses the key without your device credential " +
                        "or biometric. Rooting alone gets nothing. In exchange, after the app is " +
                        "killed nothing can read or write mission data until you unlock, and that " +
                        "includes background track recording. If you remove your device lockscreen " +
                        "the key is destroyed and mission data becomes unrecoverable."
                )
                keyError?.let { Caption("Could not change key protection: $it", Color(0xFFB00020)) }

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

@Composable
private fun SettingRow(checked: Boolean, onChange: (Boolean) -> Unit, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

@Composable
private fun Caption(text: String, color: Color = Color.Gray) {
    Text(text, fontSize = 11.sp, color = color)
}
