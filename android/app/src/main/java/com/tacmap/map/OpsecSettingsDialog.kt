package com.tacmap.map

import android.app.Activity
import android.app.KeyguardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.getSystemService
import com.tacmap.settings.BackgroundUnitSyncInterval
import com.tacmap.settings.CoordinateDisplayType
import com.tacmap.settings.MapOrientationMode
import com.tacmap.settings.OpsecSettings
import com.tacmap.util.DataKey

/** General, privacy and operational-security settings. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpsecSettingsDialog(
    opsec: OpsecSettings,
    headingAvailable: Boolean = true,
    onRequestAuthBoundChange: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val blockCapture by opsec.blockScreenCapture.collectAsState()
    val online by opsec.onlineLookups.collectAsState()
    val onlineBasemaps by opsec.onlineBasemaps.collectAsState()
    val backgroundUnitSyncLocation by opsec.backgroundUnitSyncLocation.collectAsState()
    val backgroundUnitSyncInterval by opsec.backgroundUnitSyncInterval.collectAsState()
    val primaryCoordinateType by opsec.primaryCoordinateType.collectAsState()
    val mapOrientationMode by opsec.mapOrientationMode.collectAsState()
    val persistenceIssue by opsec.persistenceIssue.collectAsState()
    val relayUrl by opsec.relayUrl.collectAsState()
    val relayValidationIssue by opsec.relayValidationIssue.collectAsState()
    var backgroundIntervalExpanded by remember { mutableStateOf(false) }
    var relayDraft by remember(relayUrl) { mutableStateOf(relayUrl) }
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
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        title = { Text(PRIVACY_OPSEC_LABEL) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                persistenceIssue?.let { Caption(it, Color(0xFFB00020)) }
                Text("Primary coordinate", fontWeight = FontWeight.SemiBold)
                CoordinateDisplayType.entries.forEach { type ->
                    CoordinateTypeRow(
                        selected = primaryCoordinateType == type,
                        type = type,
                        onSelect = { opsec.setPrimaryCoordinateType(type) }
                    )
                }
                Caption("Chooses the large green coordinate shown at the top of the map.")

                Text("Map orientation", fontWeight = FontWeight.SemiBold)
                Column(Modifier.selectableGroup()) {
                    MapOrientationMode.entries.forEach { mode ->
                        MapOrientationRow(
                            selected = mapOrientationMode == mode,
                            mode = mode,
                            enabled = mode != MapOrientationMode.HEADING_UP || headingAvailable,
                            onSelect = { opsec.setMapOrientationMode(mode) },
                        )
                    }
                }
                Caption(
                    if (headingAvailable) {
                        "North Up starts north-facing and keeps two-finger rotation available. " +
                            "Heading Up uses the phone compass to keep your pointing direction " +
                            "at the top of the map. It uses true north when a recent location is " +
                            "available. The compass marks bearings T for true north, or M when " +
                            "it falls back to magnetic north, and ? while waiting for a valid reading."
                    } else {
                        "Heading Up is unavailable because this device does not report compass headings."
                    }
                )

                SettingRow(blockCapture, { opsec.setBlockScreenCapture(it) }, "Block screenshots & recents preview")

                Text("Unit Sync", fontWeight = FontWeight.SemiBold)
                SettingRow(
                    backgroundUnitSyncLocation,
                    {
                        opsec.setBackgroundUnitSyncLocation(it)
                        if (!it) backgroundIntervalExpanded = false
                    },
                    "Background Unit Sync location",
                )
                ExposedDropdownMenuBox(
                    expanded = backgroundIntervalExpanded,
                    onExpandedChange = {
                        if (backgroundUnitSyncLocation) {
                            backgroundIntervalExpanded = it
                        }
                    },
                ) {
                    OutlinedTextField(
                        value = backgroundUnitSyncInterval.displayName,
                        onValueChange = {},
                        readOnly = true,
                        enabled = backgroundUnitSyncLocation,
                        label = { Text("Screen-off update interval") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = backgroundIntervalExpanded
                            )
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = backgroundIntervalExpanded,
                        onDismissRequest = { backgroundIntervalExpanded = false },
                    ) {
                        BackgroundUnitSyncInterval.entries.forEach { interval ->
                            DropdownMenuItem(
                                text = { Text(interval.displayName) },
                                onClick = {
                                    opsec.setBackgroundUnitSyncInterval(interval)
                                    backgroundIntervalExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = relayDraft,
                    onValueChange = {
                        relayDraft = it
                        opsec.clearRelayValidationIssue()
                    },
                    label = { Text("Unit Sync relay") },
                    singleLine = true,
                    isError = relayValidationIssue != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (opsec.setRelayUrl(relayDraft)) relayDraft = opsec.relayUrl.value
                        }
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            if (opsec.setRelayUrl(relayDraft)) relayDraft = opsec.relayUrl.value
                        },
                        enabled = relayDraft != relayUrl,
                    ) { Text("Save relay") }
                    TextButton(
                        onClick = {
                            if (opsec.resetRelayUrl()) relayDraft = opsec.relayUrl.value
                        },
                        enabled = relayUrl != OpsecSettings.DEFAULT_RELAY ||
                            relayDraft != OpsecSettings.DEFAULT_RELAY,
                    ) { Text("Use default") }
                }
                relayValidationIssue?.let { Caption(it, Color(0xFFB00020)) }
                Caption(
                    "Off by default. While the app is active, Unit Sync location remains " +
                        "near-real-time (about every 5 seconds); the selected interval affects " +
                        "only screen-off background updates. When enabled, a joined v3 room " +
                        "can continue sharing your encrypted position after the screen locks—" +
                        "but only while Share my location is also on. Background updates are " +
                        "best-effort, Android shows an ongoing location notification, and the " +
                        "room reconnects for a verified snapshot when you return. Track " +
                        "recording is controlled separately. Custom relays must use secure " +
                        "wss://. Debug builds also permit ws:// only on this device's " +
                        "loopback address."
                )

                SettingRow(online, { opsec.setOnlineLookups(it) }, "Online place, terrain & weather lookups")
                Caption(
                    "Off by default. When you turn this on, place-name " +
                        "search may use the device's geocoder. Elevation and weather send the " +
                        "map-centre coordinate (coarsened to ~110 m) to Open-Meteo. The terrain " +
                        "heat-map sends a 24 × 24 coordinate grid (coarsened to ~11 m) covering " +
                        "the visible map area. Turn this off when you need a fully " +
                        "offline/OPSEC posture."
                )

                SettingRow(onlineBasemaps, { opsec.setOnlineBasemaps(it) }, "Online basemap tiles")
                Caption(
                    "Off by default. While off the " +
                        "map only draws imported offline maps, and no tile request leaves the " +
                        "device. While on, Esri or OpenTopoMap can see the ground you are " +
                        "looking at from your IP. Turn this off for a fully offline posture."
                )

                SettingRow(authBound, { requestAuthBound(it) }, "Require unlock to decrypt mission data")
                Caption(
                    "Off: waypoints, drawings and tracks are encrypted with a key the device " +
                        "Keystore releases to this app automatically. Copied app files contain " +
                        "ciphertext, but code running as this app on a compromised device may ask " +
                        "the Keystore to decrypt.\n\n" +
                        "On: Android Keystore requires a recent device credential or strong " +
                        "biometric before key use. Hardware backing varies by device and TacMap " +
                        "does not verify it, so a fully compromised system remains outside this " +
                        "protection. After the app is killed, nothing can read or write mission " +
                        "data until you unlock, including background track recording. Removing " +
                        "your device lockscreen can invalidate the key and make mission data " +
                        "unrecoverable."
                )
                keyError?.let { Caption("Could not change key protection: $it", Color(0xFFB00020)) }
            }
        }
    )
}

@Composable
private fun MapOrientationRow(
    selected: Boolean,
    mode: MapOrientationMode,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                onClick = onSelect,
                role = Role.RadioButton,
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = null)
        Text(mode.displayName, color = if (enabled) Color.Unspecified else Color.Gray)
    }
}

@Composable
private fun CoordinateTypeRow(
    selected: Boolean,
    type: CoordinateDisplayType,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onSelect,
                role = Role.RadioButton
            )
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(type.displayName)
    }
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
