package com.tacmap.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.drop

internal const val LAYER_PREFS = "layer_prefs"

/** Layers > Show Imported Map (WP2 contract H). default on, forced on while calibrating */
const val IMPORTED_MAP_VISIBLE_KEY = "importedMapVisible"
/** the MGRS grid toggle's key, the debug launch hook flips it too */
const val MGRS_GRID_VISIBLE_KEY = "mgrsGrid"

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
    // Key the remembered state on `key`: without it, reusing this composable
    // with a different key kept the first key's value and then wrote it back to
    // the new key on the first change.
    val state = remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    LaunchedEffect(key) {
        snapshotFlow { state.value }
            .drop(1) // skip the restored initial value
            .collect { prefs.edit().putBoolean(key, it).apply() }
    }
    return state
}

/** String counterpart of [rememberPersistedBoolean] for small UI choices such
 * as a list's sort order. Callers validate the restored value. */
@Composable
fun rememberPersistedString(key: String, default: String): MutableState<String> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(LAYER_PREFS, Context.MODE_PRIVATE) }
    val state = remember(key) { mutableStateOf(prefs.getString(key, null) ?: default) }
    LaunchedEffect(key) {
        snapshotFlow { state.value }
            .drop(1) // skip the restored initial value
            .collect { prefs.edit().putString(key, it).apply() }
    }
    return state
}
