package com.tacmap.map

import com.tacmap.localization.SupportedLanguage

import com.tacmap.localization.Messages

import com.tacmap.localization.L10n
import com.tacmap.localization.AppLanguage

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
    var backgroundIntervalExpanded by remember { mutableStateOf(false) }
    var authBound by remember { mutableStateOf(DataKey.isAuthBound) }
    var languageError by remember { mutableStateOf(false) }
    var keyError by remember { mutableStateOf<com.tacmap.localization.LocalizedMessage?>(null) }
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
        keyError = completion.pendingError
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
            is AuthBoundChangeController.Request.Error -> keyError = request.pendingMessage
            AuthBoundChangeController.Request.PromptCredential -> {
                // Always prompt before either direction. In particular, a cached
                // DEK must never let an unattended user weaken auth-bound storage.
                @Suppress("DEPRECATION")
                val intent = keyguard?.createConfirmDeviceCredentialIntent(
                    L10n.text("Confirm mission-data protection change"),
                    L10n.text("Authenticate to change how the mission-data key is protected.")
                )
                if (intent != null) {
                    credentialLauncher.launch(intent)
                } else {
                    authController.cancelPending()
                    keyError = Messages.displayNoDeviceLockscreenIsSetSoThisCanTMessage()
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(L10n.text("Done")) }
        },
        title = { Text(PRIVACY_OPSEC_LABEL) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(Messages.settingsLanguageTitle(), fontWeight = FontWeight.SemiBold)
                Column(Modifier.selectableGroup()) {
                    SupportedLanguage.entries.forEach { choice ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(
                                selected = AppLanguage.selection == choice,
                                role = Role.RadioButton,
                                onClick = { languageError = !AppLanguage.select(context, choice) },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = AppLanguage.selection == choice, onClick = null)
                            Text(choice.label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (languageError) Caption(Messages.languageSaveFailed(), Color(0xFFB00020))
                persistenceIssue?.let { Caption(it.text, Color(0xFFB00020)) }
                Text(L10n.text("Primary coordinate"), fontWeight = FontWeight.SemiBold)
                CoordinateDisplayType.entries.forEach { type ->
                    CoordinateTypeRow(
                        selected = primaryCoordinateType == type,
                        type = type,
                        onSelect = { opsec.setPrimaryCoordinateType(type) }
                    )
                }
                Caption(L10n.text("Chooses the large green coordinate shown at the top of the map."))

                Text(L10n.text("Map orientation"), fontWeight = FontWeight.SemiBold)
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
                        Messages.headingHelp()
                    } else {
                        L10n.text("Heading Up is unavailable because this device does not report compass headings.")
                    }
                )

                SettingRow(blockCapture, { opsec.setBlockScreenCapture(it) }, L10n.text("Block screenshots & recents preview"))

                Text(L10n.text("Unit Sync"), fontWeight = FontWeight.SemiBold)
                SettingRow(
                    backgroundUnitSyncLocation,
                    {
                        opsec.setBackgroundUnitSyncLocation(it)
                        if (!it) backgroundIntervalExpanded = false
                    },
                    L10n.text("Background Unit Sync location"),
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
                        label = { Text(L10n.text("Screen-off update interval")) },
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
                Caption(
                    L10n.text("Off by default. While the app is active, Unit Sync location remains near-real-time (about every 5 seconds); the selected interval affects only screen-off background updates. When enabled, a joined v3 room can continue sharing your encrypted position after the screen locks—but only while Share my location is also on. Background updates are best-effort, Android shows an ongoing location notification, and the room reconnects for a verified snapshot when you return. Track recording is controlled separately.")
                )

                SettingRow(online, { opsec.setOnlineLookups(it) }, L10n.text("Online place, terrain & weather lookups"))
                Caption(
                    Messages.onlineLookupsHelp()
                )

                SettingRow(onlineBasemaps, { opsec.setOnlineBasemaps(it) }, L10n.text("Online basemap tiles"))
                Caption(
                    Messages.onlineBasemapsHelp()
                )

                SettingRow(authBound, { requestAuthBound(it) }, L10n.text("Require unlock to decrypt mission data"))
                Caption(
                    Messages.androidKeyProtectionHelp()
                )
                keyError?.let { Caption(L10n.text("Could not change key protection: %1\$s", it.text), Color(0xFFB00020)) }
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
