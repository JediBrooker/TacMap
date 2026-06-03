package com.tacticalmaps.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.drop

private const val LAYER_PREFS = "layer_prefs"

/**
 * A boolean Compose state backed by SharedPreferences so map layer toggles
 * (labels, MGRS grid) survive quitting and relaunching the app. Previously
 * these were `remember { mutableStateOf(...) }`, which reset on every launch.
 *
 * The initial value is restored from prefs; every later change is written
 * back automatically, regardless of which call site flips it.
 */
@Composable
fun rememberPersistedBoolean(key: String, default: Boolean): MutableState<Boolean> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(LAYER_PREFS, Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getBoolean(key, default)) }
    LaunchedEffect(key) {
        snapshotFlow { state.value }
            .drop(1) // skip the restored initial value
            .collect { prefs.edit().putBoolean(key, it).apply() }
    }
    return state
}
