package com.tacmap.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped operational-security / privacy settings, backed by app-private
 * prefs and exposed as StateFlows so the UI, the Activity window, and the
 * networking layer all observe the same source of truth.
 *
 * Defaults are chosen OPSEC-first for a field tool:
 *  - [blockScreenCapture] ON — the map (with live position) is kept out of the
 *    recents thumbnail and screenshots/screen-recordings by default.
 *  - [onlineLookups] OFF — elevation / weather / terrain lookups transmit the
 *    queried coordinate to a third party (Open-Meteo), so they are opt-in.
 */
class OpsecSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("opsec", Context.MODE_PRIVATE)

    init { shared = this }

    private val _blockScreenCapture = MutableStateFlow(prefs.getBoolean(KEY_SCREEN, true))
    val blockScreenCapture: StateFlow<Boolean> = _blockScreenCapture.asStateFlow()

    private val _onlineLookups = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, false))
    val onlineLookups: StateFlow<Boolean> = _onlineLookups.asStateFlow()

    private val _relayUrl = MutableStateFlow(prefs.getString(KEY_RELAY, DEFAULT_RELAY) ?: DEFAULT_RELAY)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    fun setBlockScreenCapture(value: Boolean) {
        prefs.edit().putBoolean(KEY_SCREEN, value).apply()
        _blockScreenCapture.value = value
    }

    fun setOnlineLookups(value: Boolean) {
        prefs.edit().putBoolean(KEY_ONLINE, value).apply()
        _onlineLookups.value = value
    }

    fun setRelayUrl(value: String) {
        val clean = value.trim().ifEmpty { DEFAULT_RELAY }
        prefs.edit().putString(KEY_RELAY, clean).apply()
        _relayUrl.value = clean
    }

    companion object {
        /** The app-scoped instance, set on construction. Lets the networking
         *  layer (Weather / Elevation / Terrain-heatmap services, which have no
         *  DI context) honour the [onlineLookups] OPSEC gate at the point of the
         *  outbound request. Mirrors iOS `OpsecSettings.shared`. */
        @Volatile var shared: OpsecSettings? = null

        /** Default relay. Self-hosters can override in settings. */
        const val DEFAULT_RELAY = "wss://tacmap-sync.christianbrooker.workers.dev/room/"
        private const val KEY_SCREEN = "block_screen_capture"
        private const val KEY_ONLINE = "online_lookups"
        private const val KEY_RELAY = "relay_url"
    }
}
