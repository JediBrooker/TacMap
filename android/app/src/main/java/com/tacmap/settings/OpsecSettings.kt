package com.tacmap.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-scoped OPSEC / privacy settings. Backed by app-private prefs,
 * exposed as StateFlows so UI, Activity window, and networking layer
 * all see the same source of truth.
 *
 * Defaults are OPSEC-first for a field tool:
 *  - [blockScreenCapture] ON - map (with live position) stays out of
 *    recents thumbnail and screenshots/recordings by default.
 *  - [onlineLookups] OFF - elevation / weather / terrain lookups send
 *    coords to a third party (Open-Meteo), so opt-in only.
 *  - [onlineBasemaps] OFF - requesting tiles hands your area of interest to
 *    the tile provider, so a fresh install fetches nothing until you say so.
 *
 * The "require auth to decrypt" toggle deliberately isn't here. It has to stay
 * in lockstep with which Keystore KEK the data key is wrapped under, so it
 * lives in com.tacmap.util.DataKey and prefs would only be a second, drifting
 * copy of the truth.
 */
class OpsecSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("opsec", Context.MODE_PRIVATE)

    init { shared = this }

    private val _blockScreenCapture = MutableStateFlow(prefs.getBoolean(KEY_SCREEN, true))
    val blockScreenCapture: StateFlow<Boolean> = _blockScreenCapture.asStateFlow()

    private val _onlineLookups = MutableStateFlow(prefs.getBoolean(KEY_ONLINE, false))
    val onlineLookups: StateFlow<Boolean> = _onlineLookups.asStateFlow()

    private val _onlineBasemaps = MutableStateFlow(prefs.getBoolean(KEY_BASEMAPS, false))
    val onlineBasemaps: StateFlow<Boolean> = _onlineBasemaps.asStateFlow()

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

    fun setOnlineBasemaps(value: Boolean) {
        prefs.edit().putBoolean(KEY_BASEMAPS, value).apply()
        _onlineBasemaps.value = value
    }

    fun setRelayUrl(value: String) {
        val clean = value.trim().ifEmpty { DEFAULT_RELAY }
        prefs.edit().putString(KEY_RELAY, clean).apply()
        _relayUrl.value = clean
    }

    companion object {
        /** App-scoped instance, set on construction. Lets the networking layer
         *  (Weather / Elevation / Terrain services, no DI context) check the
         *  [onlineLookups] OPSEC gate before making outbound requests.
         *  Mirrors iOS `OpsecSettings.shared`. */
        @Volatile var shared: OpsecSettings? = null

        /** Default relay. Self-hosters can point this elsewhere in settings. */
        const val DEFAULT_RELAY = "wss://tacmap-sync.christianbrooker.workers.dev/room/"
        private const val KEY_SCREEN = "block_screen_capture"
        private const val KEY_ONLINE = "online_lookups"
        private const val KEY_BASEMAPS = "online_basemaps"
        private const val KEY_RELAY = "relay_url"
    }
}
