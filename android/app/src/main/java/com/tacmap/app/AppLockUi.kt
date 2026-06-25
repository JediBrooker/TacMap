package com.tacmap.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Full-screen unlock gate shown when an App Lock PIN is set. */
@Composable
fun AppLockScreen(appLock: AppLock, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null,
                tint = Color(0xFF8CF28C), modifier = Modifier.size(52.dp))
            Text("TacMap Locked", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = pin,
                onValueChange = { v ->
                    val digits = v.filter { it.isDigit() }.take(4)
                    pin = digits
                    error = false
                    if (digits.length == 4) {
                        if (appLock.verify(digits)) onUnlocked() else { error = true; pin = "" }
                    }
                },
                label = { Text("Enter PIN") },
                singleLine = true,
                isError = error,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            if (error) Text("Incorrect PIN", color = Color(0xFFEF5350), fontSize = 12.sp)
        }
    }
}

/** Enable / change / disable the App Lock PIN. */
@Composable
fun AppLockSetupDialog(appLock: AppLock, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(appLock.isEnabled) }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("App Lock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Switch(checked = enabled, onCheckedChange = {
                        enabled = it
                        if (!it) { appLock.clear(); message = "App Lock disabled." }
                    })
                    Text("Require a PIN to open TacMap")
                }
                if (enabled) {
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("New 4-digit PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    TextButton(
                        enabled = newPin.length == 4 && confirmPin.length == 4,
                        onClick = {
                            if (newPin == confirmPin) {
                                appLock.setPin(newPin)
                                newPin = ""; confirmPin = ""
                                message = "PIN saved. TacMap locks on next launch."
                            } else {
                                message = "PINs don't match."
                            }
                        }
                    ) { Text("Save PIN") }
                }
                message?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
                Text(
                    "A deterrent if your device is lost or borrowed — not a substitute for device encryption.",
                    fontSize = 11.sp, color = Color.Gray
                )
            }
        }
    )
}
