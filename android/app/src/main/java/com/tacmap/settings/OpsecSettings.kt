package com.tacmap.settings

import com.tacmap.localization.Messages
import com.tacmap.localization.LocalizedMessage

import com.tacmap.localization.L10n

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.tacmap.sync.RelayEndpointPolicy
import com.tacmap.util.DurablePreferenceCommit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinate format used for the map header's primary readout. The persisted
 *  enum name is intentionally stable; unknown or absent values use MGRS so
 *  existing installs keep the app's historical default. */
enum class CoordinateDisplayType(val displayName: String) {
    MGRS("MGRS"),
    WGS84("WGS84"),
    UTM("UTM");

    companion object {
        fun fromPersisted(value: String?): CoordinateDisplayType =
            entries.firstOrNull { it.name == value } ?: MGRS
    }
}

/** Map-up behavior. Persisted names are stable; absent/unknown values preserve
 * the existing north-facing, gesture-rotatable mode. */
enum class MapOrientationMode(private val displayNameKey: String) {
    NORTH_UP("North Up"),
    HEADING_UP("Heading Up");

    val displayName: String get() = L10n.text(displayNameKey)

    companion object {
        fun fromPersisted(value: String?): MapOrientationMode =
            entries.firstOrNull { it.name == value } ?: NORTH_UP
    }
}

/** User-selected cadence for screen-off Unit Sync location updates. Persisting
 * minute values keeps the preference stable if enum names change. */
enum class BackgroundUnitSyncInterval(val minutes: Int, private val displayNameKey: String) {
    ONE_MINUTE(1, "Every minute"),
    FIVE_MINUTES(5, "Every 5 minutes"),
    FIFTEEN_MINUTES(15, "Every 15 minutes"),
    THIRTY_MINUTES(30, "Every 30 minutes"),
    SIXTY_MINUTES(60, "Every 60 minutes");

    val displayName: String get() = L10n.text(displayNameKey)

    companion object {
        val DEFAULT = FIFTEEN_MINUTES

        fun fromPersisted(minutes: Int): BackgroundUnitSyncInterval =
            entries.firstOrNull { it.minutes == minutes } ?: DEFAULT
    }
}

/**
 * App-scoped general / OPSEC / privacy settings. Backed by app-private prefs,
 * exposed as StateFlows so UI, Activity window, and networking layer
 * all see the same source of truth.
 *
 * Fresh installs favour a dark-by-default network posture:
 *  - [blockScreenCapture] ON - map (with live position) stays out of
 *    recents thumbnail and screenshots/recordings by default.
 *  - [onlineLookups] OFF - place / elevation / weather / terrain providers
 *    receive nothing until the user opts in.
 *  - [onlineBasemaps] OFF - no online raster tile is requested until the user
 *    opts in or loads an imported offline map.
 *  - [backgroundUnitSyncLocation] OFF - screen-off location sharing requires
 *    explicit user consent.
 *  Both network features remain independently switchable. Stored choices are
 *  read before these fallbacks, so an update never overwrites an existing
 *  user's explicit settings.
 *
 * The "require auth to decrypt" toggle deliberately isn't here. It has to stay
 * in lockstep with which Keystore KEK the data key is wrapped under, so it
 * lives in com.tacmap.util.DataKey and prefs would only be a second, drifting
 * copy of the truth.
 */
class OpsecSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("opsec", Context.MODE_PRIVATE)
    private val persistedNetworkPreferences = resolveNetworkPreferences(prefs.all)
    private val persistedRelayPreference = RelayEndpointPolicy.resolvePersisted(
        rawValue = prefs.getString(KEY_RELAY, null),
        defaultEndpoint = DEFAULT_RELAY,
    )

    private val _blockScreenCapture = MutableStateFlow(prefs.getBoolean(KEY_SCREEN, true))
    val blockScreenCapture: StateFlow<Boolean> = _blockScreenCapture.asStateFlow()

    private val _onlineLookups = MutableStateFlow(persistedNetworkPreferences.onlineLookups)
    val onlineLookups: StateFlow<Boolean> = _onlineLookups.asStateFlow()

    private val _onlineBasemaps = MutableStateFlow(persistedNetworkPreferences.onlineBasemaps)
    val onlineBasemaps: StateFlow<Boolean> = _onlineBasemaps.asStateFlow()

    private val _backgroundUnitSyncLocation = MutableStateFlow(
        persistedNetworkPreferences.backgroundUnitSyncLocation
    )
    val backgroundUnitSyncLocation: StateFlow<Boolean> =
        _backgroundUnitSyncLocation.asStateFlow()

    private val _backgroundUnitSyncInterval = MutableStateFlow(
        BackgroundUnitSyncInterval.fromPersisted(
            prefs.getInt(
                KEY_BACKGROUND_UNIT_SYNC_INTERVAL_MINUTES,
                BackgroundUnitSyncInterval.DEFAULT.minutes,
            )
        )
    )
    val backgroundUnitSyncInterval: StateFlow<BackgroundUnitSyncInterval> =
        _backgroundUnitSyncInterval.asStateFlow()

    private val _primaryCoordinateType = MutableStateFlow(
        CoordinateDisplayType.fromPersisted(prefs.getString(KEY_COORDINATE_TYPE, null))
    )
    val primaryCoordinateType: StateFlow<CoordinateDisplayType> =
        _primaryCoordinateType.asStateFlow()

    private val _mapOrientationMode = MutableStateFlow(
        MapOrientationMode.fromPersisted(prefs.getString(KEY_MAP_ORIENTATION, null))
    )
    val mapOrientationMode: StateFlow<MapOrientationMode> = _mapOrientationMode.asStateFlow()

    private val _relayUrl = MutableStateFlow(persistedRelayPreference.endpoint)
    val relayUrl: StateFlow<String> = _relayUrl.asStateFlow()

    private val _persistenceIssue = MutableStateFlow<LocalizedMessage?>(null)
    /** Non-null means the requested value was rejected and the last durable
     * value remains active. The UI must never imply the safer value stuck. */
    val persistenceIssue: StateFlow<LocalizedMessage?> = _persistenceIssue.asStateFlow()

    private val _relayValidationIssue = MutableStateFlow<LocalizedMessage?>(null)
    val relayValidationIssue: StateFlow<LocalizedMessage?> = _relayValidationIssue.asStateFlow()

    init {
        shared = this
        if (persistedRelayPreference.needsRepair) {
            val repaired = DurablePreferenceCommit.preferences(
                preferences = prefs,
                keys = setOf(KEY_RELAY),
                mutate = { putString(KEY_RELAY, persistedRelayPreference.endpoint) },
                publish = {},
            )
            if (!repaired) {
                _persistenceIssue.value =
                    Messages.relayRecoveryFailedMessage()
            }
        }
    }

    fun setBlockScreenCapture(value: Boolean): Boolean = persistBoolean(
        KEY_SCREEN,
        value,
        _blockScreenCapture,
    )

    fun setOnlineLookups(value: Boolean): Boolean = persistBoolean(
        KEY_ONLINE,
        value,
        _onlineLookups,
    )

    fun setOnlineBasemaps(value: Boolean): Boolean = persistBoolean(
        KEY_BASEMAPS,
        value,
        _onlineBasemaps,
    )

    fun setBackgroundUnitSyncLocation(value: Boolean): Boolean = persistBoolean(
        KEY_BACKGROUND_UNIT_SYNC_LOCATION,
        value,
        _backgroundUnitSyncLocation,
    )

    fun setBackgroundUnitSyncInterval(value: BackgroundUnitSyncInterval): Boolean =
        persist(
            key = KEY_BACKGROUND_UNIT_SYNC_INTERVAL_MINUTES,
            mutate = { putInt(KEY_BACKGROUND_UNIT_SYNC_INTERVAL_MINUTES, value.minutes) },
            publish = { _backgroundUnitSyncInterval.value = value },
        )

    fun setPrimaryCoordinateType(value: CoordinateDisplayType): Boolean =
        persist(
            key = KEY_COORDINATE_TYPE,
            mutate = { putString(KEY_COORDINATE_TYPE, value.name) },
            publish = { _primaryCoordinateType.value = value },
        )

    fun setMapOrientationMode(value: MapOrientationMode): Boolean =
        persist(
            key = KEY_MAP_ORIENTATION,
            mutate = { putString(KEY_MAP_ORIENTATION, value.name) },
            publish = { _mapOrientationMode.value = value },
        )

    fun setRelayUrl(value: String): Boolean {
        val clean = when (val result = RelayEndpointPolicy.normalize(value)) {
            is RelayEndpointPolicy.Result.Valid -> result.endpoint
            is RelayEndpointPolicy.Result.Invalid -> {
                _relayValidationIssue.value = result.pendingMessage
                return false
            }
        }
        val committed = persist(
            key = KEY_RELAY,
            mutate = { putString(KEY_RELAY, clean) },
            publish = { _relayUrl.value = clean },
        )
        if (committed) _relayValidationIssue.value = null
        return committed
    }

    fun resetRelayUrl(): Boolean = setRelayUrl(DEFAULT_RELAY)

    fun clearRelayValidationIssue() {
        _relayValidationIssue.value = null
    }

    private fun persistBoolean(
        key: String,
        value: Boolean,
        state: MutableStateFlow<Boolean>,
    ): Boolean = persist(
        key = key,
        mutate = { putBoolean(key, value) },
        publish = { state.value = value },
    )

    @SuppressLint("ApplySharedPref")
    private fun persist(
        key: String,
        mutate: SharedPreferences.Editor.() -> SharedPreferences.Editor,
        publish: () -> Unit,
    ): Boolean {
        val committed = DurablePreferenceCommit.preferences(
            preferences = prefs,
            keys = setOf(key),
            mutate = mutate,
            publish = publish,
        )
        _persistenceIssue.value = if (committed) {
            null
        } else {
            Messages.privacySettingSaveFailedMessage()
        }
        return committed
    }

    companion object {
        /** App-scoped instance, set on construction. Lets the networking layer
         *  (Weather / Elevation / Terrain services, no DI context) check the
         *  [onlineLookups] OPSEC gate before making outbound requests.
         *  Mirrors iOS `OpsecSettings.shared`. */
        @Volatile var shared: OpsecSettings? = null

        /** Default relay. Self-hosters can point this elsewhere in settings. */
        const val DEFAULT_RELAY = "wss://tacmap-sync.christianbrooker.workers.dev"
        internal const val DEFAULT_ONLINE_LOOKUPS = false
        internal const val DEFAULT_ONLINE_BASEMAPS = false
        internal const val DEFAULT_BACKGROUND_UNIT_SYNC_LOCATION = false
        private const val KEY_SCREEN = "block_screen_capture"
        private const val KEY_ONLINE = "online_lookups"
        private const val KEY_BASEMAPS = "online_basemaps"
        private const val KEY_BACKGROUND_UNIT_SYNC_LOCATION = "background_unit_sync_location"
        private const val KEY_BACKGROUND_UNIT_SYNC_INTERVAL_MINUTES =
            "background_unit_sync_interval_minutes"
        private const val KEY_COORDINATE_TYPE = "primary_coordinate_type"
        private const val KEY_MAP_ORIENTATION = "map_orientation_mode"
        private const val KEY_RELAY = "relay_url"

        internal data class NetworkPreferences(
            val onlineLookups: Boolean,
            val onlineBasemaps: Boolean,
            val backgroundUnitSyncLocation: Boolean,
        )

        /** Pure resolver used by production construction and JVM regression
         * tests. Stored booleans always win; missing or malformed values use
         * the dark-by-default fresh-install posture. */
        internal fun resolveNetworkPreferences(values: Map<String, *>): NetworkPreferences =
            NetworkPreferences(
                onlineLookups = values[KEY_ONLINE] as? Boolean ?: DEFAULT_ONLINE_LOOKUPS,
                onlineBasemaps = values[KEY_BASEMAPS] as? Boolean ?: DEFAULT_ONLINE_BASEMAPS,
                backgroundUnitSyncLocation =
                    values[KEY_BACKGROUND_UNIT_SYNC_LOCATION] as? Boolean
                        ?: DEFAULT_BACKGROUND_UNIT_SYNC_LOCATION,
            )
    }
}
