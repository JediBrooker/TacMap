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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import kotlinx.coroutines.delay
import kotlin.math.ceil

/** Full-screen PIN gate when App Lock is on. Escalating lockout on
 *  repeated failures (see [AppLock]). */
@Composable
fun AppLockScreen(appLock: AppLock, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var lockoutMs by remember { mutableLongStateOf(appLock.lockoutRemainingMs()) }

    // tick lockout countdown to zero
    LaunchedEffect(Unit) {
        while (true) {
            lockoutMs = appLock.lockoutRemainingMs()
            delay(500)
        }
    }
    val lockedOut = lockoutMs > 0L

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
                enabled = !lockedOut,
                onValueChange = { v ->
                    val digits = v.filter { it.isDigit() }.take(4)
                    pin = digits
                    error = false
                    if (digits.length == 4) {
                        if (appLock.verify(digits)) {
                            onUnlocked()
                        } else {
                            pin = ""
                            lockoutMs = appLock.lockoutRemainingMs()
                            error = lockoutMs == 0L
                        }
                    }
                },
                label = { Text("Enter PIN") },
                singleLine = true,
                isError = error,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
            )
            if (lockedOut) {
                Text(
                    "Too many attempts. Try again in ${ceil(lockoutMs / 1000.0).toInt()}s",
                    color = Color(0xFFFFB74D), fontSize = 12.sp
                )
            } else if (error) {
                Text("Incorrect PIN", color = Color(0xFFEF5350), fontSize = 12.sp)
            }
        }
    }
}

/** Full-screen platform-credential gate for an auth-bound mission DEK. */
@Composable
fun MissionKeyUnlockScreen(error: String?, onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Lock, contentDescription = null,
                tint = Color(0xFF8CF28C), modifier = Modifier.size(52.dp))
            Text("Mission data locked", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                error ?: "Authenticate with your device credential to decrypt mission data.",
                color = if (error == null) Color.LightGray else Color(0xFFEF5350),
                fontSize = 13.sp
            )
            TextButton(onClick = onUnlock) { Text("Authenticate") }
        }
    }
}

/** Setup dialog for App Lock PIN. Changing or disabling an existing
 *  PIN requires the current one. */
@Composable
fun AppLockSetupDialog(appLock: AppLock, onDismiss: () -> Unit) {
    var enabled by remember { mutableStateOf(appLock.isEnabled) }
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("App Lock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabled) {
                    Text("App Lock is on.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = currentPin,
                        onValueChange = { currentPin = it.filter { c -> c.isDigit() }.take(4); message = null },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = newPin,
                        onValueChange = { newPin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("New PIN (to change)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = confirmPin,
                        onValueChange = { confirmPin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Confirm new PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    TextButton(
                        enabled = currentPin.length == 4 && newPin.length == 4 && confirmPin.length == 4,
                        onClick = {
                            when {
                                newPin != confirmPin -> message = "New PINs don't match."
                                appLock.changePin(currentPin, newPin) -> {
                                    currentPin = ""; newPin = ""; confirmPin = ""
                                    message = "PIN changed."
                                }
                                appLock.lockoutRemainingMs() > 0 -> message = "Too many attempts. Try again shortly."
                                else -> message = "Current PIN is incorrect."
                            }
                        }
                    ) { Text("Change PIN") }
                    TextButton(
                        enabled = currentPin.length == 4,
                        onClick = {
                            if (appLock.disable(currentPin)) {
                                enabled = false
                                currentPin = ""; newPin = ""; confirmPin = ""
                                message = "App Lock disabled."
                            } else {
                                message = if (appLock.lockoutRemainingMs() > 0)
                                    "Too many attempts. Try again shortly."
                                else "Current PIN is incorrect."
                            }
                        }
                    ) { Text("Turn Off App Lock") }
                } else {
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
                                enabled = true
                                newPin = ""; confirmPin = ""
                                message = "App Lock enabled. TacMap locks when backgrounded."
                            } else {
                                message = "PINs don't match."
                            }
                        }
                    ) { Text("Enable App Lock") }
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
